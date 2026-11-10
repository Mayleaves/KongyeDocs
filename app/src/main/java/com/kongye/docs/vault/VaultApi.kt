package com.kongye.docs.vault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VaultAccessLog
import com.kongye.docs.data.VaultAccessRecord
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.data.VaultPasswords
import com.kongye.docs.data.VaultStore
import com.kongye.docs.data.VaultField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kongye.docs.data.VaultType

/**
 * 密码空间的对外接口（接口 v1，见 docs/密码填充-接口与任务.md 第 1 节）。
 * VaultProvider 只负责把 ContentResolver.call 转到这里；测试也直接调 handle 检查白名单。
 */
object VaultApi {
    const val VERSION = 1

    /** 调用方白名单（签名权限之外的第二道） */
    val CALLERS = setOf("com.kongye.keyboard", "com.kongye.keyboard.debug", "com.kongye.floatball")

    const val OK = "ok"
    const val LOCKED = "locked"
    const val DISABLED = "disabled"
    const val DENIED = "denied"
    const val NOT_FOUND = "not_found"
    const val ERROR = "error"

    /** 调试版（.audit 等）额外允许自己调自己，方便在测试包里跑通接口；正式版不可调试，不生效 */
    fun allowed(context: Context, caller: String?): Boolean =
        caller != null && (caller in CALLERS || (caller == context.packageName && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0))

    fun handle(context: Context, caller: String?, method: String, extras: Bundle?): Bundle {
        val x = extras ?: Bundle.EMPTY
        val target = x.getString("package").orEmpty().trim()
        val app = target.takeUnless { Browsers.isBrowser(context, it) }.orEmpty()
        val domain = host(x.getString("domain").orEmpty()).orEmpty()
        var entryName = ""
        val result = try {
            when {
                !allowed(context, caller) -> status(DENIED)
                method == "status" -> statusOf(context)
                !Settings.value.vaultFill -> status(DISABLED)
                !VaultStore.healthy -> status(ERROR).apply { putString("message", "密码空间读取失败") }
                else -> when (method) {
                    "match" -> entries(match(app, domain))
                    "search" -> entries(search(x.getString("query").orEmpty()).map { it to "search" })
                    "reveal" -> {
                        val e = VaultStore.get(x.getString("id").orEmpty())
                        entryName = e?.name.orEmpty()
                        when {
                            e == null -> status(NOT_FOUND)
                            !VaultAccess.isUnlocked(context) -> status(LOCKED).apply { putParcelable("unlock", unlockIntent(context, caller.orEmpty(), e)) }
                            else -> {
                                VaultStore.update(e.id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                                status(OK).apply { putString("account", e.account); putString("password", e.password) }
                            }
                        }
                    }
                    "generate" -> status(OK).apply {
                        putString("password", VaultPasswords.generate(if (x.containsKey("length")) x.getInt("length") else VaultPasswords.DEFAULT_LENGTH))
                    }
                    "save" -> save(context, x, app, domain).also { entryName = it.getString("name").orEmpty(); it.remove("name") }
                    "bind" -> {
                        val id = x.getString("id").orEmpty()
                        val e = VaultStore.update(id) { if (app.isEmpty() && domain.isEmpty()) it else bind(it, app, domain) }
                        entryName = e?.name.orEmpty()
                        if (e == null) status(NOT_FOUND) else status(OK)
                    }
                    else -> status(ERROR).apply { putString("message", "不支持的方法：$method") }
                }
            }
        } catch (t: Throwable) {
            status(ERROR).apply { putString("message", t.message ?: t.javaClass.simpleName) }
        }
        // status 调用很频繁，不记；其余每次都记（不记账号、密码）
        if (method != "status") runCatching {
            VaultAccessLog.add(VaultAccessRecord(caller = caller.orEmpty(), method = method, target = target, domain = domain, entry = entryName, status = result.getString("status").orEmpty()))
        }
        return result
    }

    private fun status(value: String) = Bundle().apply { putString("status", value) }

    private fun statusOf(context: Context): Bundle {
        val enabled = Settings.value.vaultFill
        val until = VaultAccess.unlockedUntil(context)
        return status(if (enabled) OK else DISABLED).apply {
            putInt("version", VERSION)
            putBoolean("enabled", enabled)
            putBoolean("unlocked", until > 0L)
            putLong("unlockExpiresAt", until)
        }
    }

    /** 能拿来填的条目：有账号或密码 */
    private fun fillable() = VaultStore.entries.value.filter { it.account.isNotBlank() || it.password.isNotEmpty() }

    /** 按包名命中 > 按域名命中 > 最近使用 */
    fun match(target: String, domain: String): List<Pair<VaultEntry, String>> {
        val byPackage = if (target.isEmpty()) emptyList() else fillable().filter { target in it.apps }
        val byDomain = if (domain.isEmpty()) emptyList() else fillable().filter { it !in byPackage && sameSite(host(it.url), domain) }
        return byPackage.sortedByDescending { it.lastUsedAt }.map { it to "package" } +
            byDomain.sortedByDescending { it.lastUsedAt }.map { it to "domain" }
    }

    fun search(query: String): List<VaultEntry> =
        if (query.isBlank()) emptyList() else fillable().filter { it.matches(query) }.sortedWith(compareByDescending<VaultEntry> { it.lastUsedAt }.thenBy { it.name.lowercase() }).take(30)

    private fun entries(list: List<Pair<VaultEntry, String>>) = status(OK).apply {
        putParcelableArrayList("entries", ArrayList(list.map { (e, by) ->
            Bundle().apply { putString("id", e.id); putString("name", e.name); putString("account", mask(e.account)); putString("matchedBy", by) }
        }))
    }

    private fun bind(e: VaultEntry, target: String, domain: String) = e.copy(
        apps = if (target.isEmpty() || target in e.apps) e.apps else e.apps + target,
        url = if (e.url.isBlank() && domain.isNotEmpty()) domain else e.url,
    )

    private const val OLD_PASSWORD = "旧密码"
    private fun replacePassword(e: VaultEntry, password: String): VaultEntry {
        if (e.password.isEmpty() || e.password == password) return e.copy(password = password)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val old = e.fields.filter { it.secret && it.label.startsWith(OLD_PASSWORD) }
        val dropped = old.dropLast(2).toSet()
        return e.copy(password = password, fields = e.fields.filterNot { it in dropped } +
            VaultField(label = "$OLD_PASSWORD $stamp", value = e.password, secret = true))
    }

    /** 普通 App 按账号和包名更新；网页按账号和规范化主机名完全相同更新。 */
    private fun save(context: Context, x: Bundle, app: String, domain: String): Bundle {
        val account = x.getString("account").orEmpty().trim()
        val password = x.getString("password").orEmpty()
        if (password.isEmpty()) return status(ERROR).apply { putString("message", "密码为空") }
        val existing = VaultStore.entries.value.firstOrNull {
            it.type == VaultType.PASSWORD && it.account == account && when {
                app.isNotEmpty() -> app in it.apps
                domain.isNotEmpty() -> host(it.url) == domain
                else -> false
            }
        }
        if (existing != null) {
            val next = replacePassword(bind(existing, app, domain), password).copy(lastUsedAt = System.currentTimeMillis())
            VaultStore.save(next)
            return status(OK).apply { putString("id", existing.id); putString("result", "updated"); putString("name", existing.name) }
        }
        val name = x.getString("name").orEmpty().trim().ifEmpty { appLabel(context, app) ?: domain.ifEmpty { "网页密码" } }
        val created = VaultStore.add(VaultEntry(
            type = VaultType.PASSWORD, name = name, account = account, password = password,
            url = domain, apps = listOfNotNull(app.ifEmpty { null }), lastUsedAt = System.currentTimeMillis(),
        ))
        return status(OK).apply { putString("id", created.id); putString("result", "created"); putString("name", created.name) }
    }

    private fun unlockIntent(context: Context, caller: String, e: VaultEntry): PendingIntent {
        val intent = Intent(context, VaultUnlockActivity::class.java)
            .putExtra(VaultUnlockActivity.EXTRA_CALLER, caller)
            .putExtra(VaultUnlockActivity.EXTRA_ENTRY, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, 0x5641, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun appLabel(context: Context, pkg: String): String? = if (pkg.isEmpty()) null else runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** 打码后的账号：邮箱留前 2 位和域名，11 位手机号留前 3 后 4，其余留首尾 */
    fun mask(account: String): String {
        val a = account.trim()
        if (a.isEmpty()) return ""
        val at = a.indexOf('@')
        if (at > 0) return a.take(minOf(2, at)) + "****" + a.substring(at)
        if (a.length == 11 && a.all { it.isDigit() }) return a.take(3) + "****" + a.takeLast(4)
        return when {
            a.length <= 2 -> a.take(1) + "*"
            a.length <= 5 -> a.take(1) + "***" + a.takeLast(1)
            else -> a.take(2) + "****" + a.takeLast(2)
        }
    }

    /** 网址 / 域名 → 小写主机名（去掉协议、路径、端口、www.）；空的返回 null */
    fun host(text: String): String? {
        var t = text.trim().lowercase()
        if (t.isEmpty()) return null
        t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringAfterLast('@').substringBefore(':')
        t = t.removePrefix("www.").trimEnd('.')
        return t.ifEmpty { null }
    }

    /**
     * 网页属于条目的网站：完全相同，或网页是条目网址的子域名（条目 github.com 匹配 gist.github.com）。
     * 反过来不算：条目 a.example.com 不推荐给 example.com 或 b.example.com。
     */
    fun sameSite(entryHost: String?, pageDomain: String?): Boolean {
        if (entryHost.isNullOrEmpty() || pageDomain.isNullOrEmpty()) return false
        return pageDomain == entryHost || pageDomain.endsWith(".$entryHost")
    }
}
