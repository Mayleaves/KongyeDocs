package com.kongye.docs.vault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.autofill.AutofillManager
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VaultAccessLog
import com.kongye.docs.data.VaultAccessRecord
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.data.VaultField
import com.kongye.docs.data.VaultStore
import com.kongye.docs.data.VaultType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 密码空间的对外接口（接口 v1，见 docs/密码填充-接口与任务.md 第 1 节；v1.1 新增见 docs/密码填充-需求文档-v2.md 第 7 节）。
 * VaultProvider 只负责把 ContentResolver.call 转到这里；测试也直接调 handle 检查白名单。
 * 系统自动填充（autofill/）不经过 Provider，直接用这里的 match / saveCredential。
 */
object VaultApi {
    /** 保持 1：v1 的键盘和悬浮球只认 1。新能力看 features */
    const val VERSION = 1
    val FEATURES = listOf("generate.rules", "save.fields", "match.cert", "association.manage", "association.clear-url")

    /** 调用方白名单（签名权限之外的第二道） */
    val CALLERS = setOf("com.kongye.keyboard", "com.kongye.keyboard.debug", "com.kongye.floatball")

    /** 系统自动填充写进访问记录时用的调用方 */
    const val AUTOFILL_CALLER = "android.autofill"

    const val OK = "ok"
    const val LOCKED = "locked"
    const val DISABLED = "disabled"
    const val DENIED = "denied"
    const val NOT_FOUND = "not_found"
    const val ERROR = "error"
    /** v1.1：条目关联的 App 证书和现在不一样 */
    const val CERT_MISMATCH = "cert_mismatch"

    /** 调试版（.audit 等）额外允许自己调自己，方便在测试包里跑通接口；正式版不可调试，不生效 */
    fun allowed(context: Context, caller: String?): Boolean =
        caller != null && (caller in CALLERS || (caller == context.packageName && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0))

    fun handle(context: Context, caller: String?, method: String, extras: Bundle?): Bundle {
        val x = extras ?: Bundle.EMPTY
        val target = x.getString("package").orEmpty().trim()
        val app = target.takeUnless { Browsers.isBrowser(context, it) }.orEmpty()
        val domain = host(x.getString("domain").orEmpty()).orEmpty()
        // v1.1 调用方声明自己认识 cert_mismatch；v1 的不认识，就不给它这类条目
        val certAware = x.getStringArrayList("features")?.contains("match.cert") == true
        var entryName = ""
        val result = try {
            when {
                !allowed(context, caller) -> status(DENIED)
                method == "status" -> statusOf(context)
                !Settings.value.vaultFill -> status(DISABLED)
                !VaultStore.healthy -> status(ERROR).apply { putString("message", "密码空间读取失败") }
                else -> when (method) {
                    "match" -> entries(match(app, domain) { AppCerts.of(context, it) }.filter { certAware || it.second != CERT_MISMATCH })
                    "search" -> entries(search(x.getString("query").orEmpty()).map { it to "search" })
                    "association.list", "association.remove" -> manageAssociations(context,caller.orEmpty(),method,x)
                    "reveal" -> {
                        val e = VaultStore.get(x.getString("id").orEmpty())
                        entryName = e?.name.orEmpty()
                        when {
                            e == null -> status(NOT_FOUND)
                            // 条目记过这个 App 的证书、但现在的不一样：调用方提示用户并带上 confirmCert 才给
                            !x.getBoolean("confirmCert") && !certOk(context, e, app) -> status(CERT_MISMATCH)
                            !VaultAccess.isUnlocked(context) -> status(LOCKED).apply { putParcelable("unlock", unlockIntent(context, caller.orEmpty(), e)) }
                            else -> {
                                VaultStore.update(e.id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                                status(OK).apply { putString("account", e.account); putString("password", e.password) }
                            }
                        }
                    }
                    "generate" -> {
                        val g = PasswordRules.generate(PasswordRules.fromBundle(x))
                        status(OK).apply { putString("password", g.password); putString("summary", g.summary); putBoolean("satisfied", g.satisfied) }
                    }
                    "save" -> {
                        val input = SaveInput.from(x, app, domain)
                        if (input.password.isEmpty()) status(ERROR).apply { putString("message", "密码为空") }
                        else saveCredential(context, input).let { r ->
                            entryName = r.name
                            status(OK).apply { putString("id", r.id); putString("result", r.result); putInt("fields", r.fields) }
                        }
                    }
                    "bind" -> {
                        val id = x.getString("id").orEmpty()
                        val e = VaultStore.update(id) { if (app.isEmpty() && domain.isEmpty()) it else bind(it, app, domain, AppCerts.of(context, app)) }
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
        if (method != "status") log(caller.orEmpty(), method, target, domain, entryName, result.getString("status").orEmpty())
        return result
    }

    private data class AssociationTarget(val packageName:String,val domain:String,val observedAt:Long)
    @Volatile private var associationTarget:AssociationTarget?=null

    fun observeAutofillTarget(packageName:String,domain:String) {
        associationTarget=AssociationTarget(packageName,host(domain).orEmpty(),android.os.SystemClock.elapsedRealtime())
    }

    private fun manageAssociations(context:Context,caller:String,method:String,extras:Bundle):Bundle {
        val pkg=extras.getString("package").orEmpty()
        if(pkg.isBlank())return status(ERROR)
        val browser=Browsers.isBrowser(context,pkg)
        val observed=associationTarget
        val domain=if(browser && observed?.packageName==pkg &&
            android.os.SystemClock.elapsedRealtime()-observed.observedAt<300_000) observed.domain else ""
        if(browser && domain.isEmpty())return status(ERROR).apply {putString("message","请重新点一下网页账号框，再管理推荐")}
        val scope=if(browser) domain else pkg
        // Management includes previously hidden domain matches, so old exclusions can be removed too.
        val matches=if(browser) fillable().filter {sameSite(host(it.url),domain)}.map {it to "domain"}
            else match(pkg,"") {AppCerts.of(context,it)}
        if(method=="association.list")return entries(matches).apply {
            putString("scope",scope)
            putString("removeMode",if(browser) "clear_url" else "remove_app")
            putString("label",if(browser) domain else appLabel(context,pkg) ?: pkg)
        }
        if(extras.getString("scope")!=scope)return status(ERROR).apply {putString("message","页面已变化，请重新打开推荐管理")}
        val id=extras.getString("id").orEmpty()
        val entry=matches.firstOrNull {it.first.id==id}?.first ?: return status(NOT_FOUND)
        if(!VaultAccess.isUnlocked(context))return status(LOCKED).apply {
            putParcelable("unlock",unlockIntent(context,caller,entry))
        }
        VaultStore.update(id) {
            if(browser)it.copy(url="",fields=it.fields.filterNot {field->field.label.trim()=="网址" && !field.secret},excludedDomains=it.excludedDomains.filterNot {sameSite(host(it),domain)}) else it.copy(apps=it.apps-pkg,appCerts=it.appCerts-pkg)
        } ?: return status(NOT_FOUND)
        return status(OK)
    }

    fun log(caller: String, method: String, target: String, domain: String, entry: String, status: String) {
        runCatching { VaultAccessLog.add(VaultAccessRecord(caller = caller, method = method, target = target, domain = domain, entry = entry, status = status)) }
    }

    private fun status(value: String) = Bundle().apply { putString("status", value) }

    /** 空叶文档现在是不是系统选中的自动填充服务 */
    fun autofillEnabled(context: Context): Boolean =
        runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false)

    private fun statusOf(context: Context): Bundle {
        val enabled = Settings.value.vaultFill
        val until = VaultAccess.unlockedUntil(context)
        val autofill = autofillEnabled(context)
        return status(if (enabled) OK else DISABLED).apply {
            putInt("version", VERSION)
            putBoolean("enabled", enabled)
            putBoolean("unlocked", until > 0L)
            putLong("unlockExpiresAt", until)
            putBoolean("autofill", autofill)
            putStringArrayList("features", ArrayList(FEATURES + if (autofill) listOf("autofill") else emptyList()))
        }
    }

    /** 能拿来填的条目：有账号或密码 */
    fun fillable() = VaultStore.entries.value.filter { it.account.isNotBlank() || it.password.isNotEmpty() }

    /** 条目能不能给这个 App 用：没记过证书，或者证书相同 */
    fun certOk(context: Context, e: VaultEntry, app: String): Boolean {
        if (app.isEmpty()) return true
        val recorded = e.appCerts[app] ?: return true
        return AppCerts.matches(recorded, AppCerts.of(context, app))
    }

    /**
     * 按包名命中 > 按域名命中 > 最近使用。
     * 包名命中、但记下的证书和现在不一样的，放在最后，matchedBy = cert_mismatch，调用方不能把它当推荐。
     */
    fun match(target: String, domain: String, certOf: (String) -> String? = { null }): List<Pair<VaultEntry, String>> {
        val byPackage = if (target.isEmpty()) emptyList() else fillable().filter { target in it.apps }
        val current by lazy { certOf(target) }
        val (trusted, mismatched) = byPackage.partition { e -> e.appCerts[target]?.let { AppCerts.matches(it, current) } ?: true }
        val byDomain = if (domain.isEmpty()) emptyList() else fillable().filter { it !in byPackage && sameSite(host(it.url), domain) && domain !in it.excludedDomains }
        return trusted.sortedByDescending { it.lastUsedAt }.map { it to "package" } +
            byDomain.sortedByDescending { it.lastUsedAt }.map { it to "domain" } +
            mismatched.sortedByDescending { it.lastUsedAt }.map { it to CERT_MISMATCH }
    }

    fun search(query: String): List<VaultEntry> =
        if (query.isBlank()) emptyList() else fillable().filter { it.matches(query) }.sortedWith(compareByDescending<VaultEntry> { it.lastUsedAt }.thenBy { it.name.lowercase() }).take(30)

    private fun entries(list: List<Pair<VaultEntry, String>>) = status(OK).apply {
        putParcelableArrayList("entries", ArrayList(list.map { (e, by) ->
            Bundle().apply { putString("id", e.id); putString("name", e.name); putString("account", mask(e.account)); putString("matchedBy", by) }
        }))
    }

    /** 关联 App / 网址；带上 App 当前的证书（取不到就不记） */
    fun bind(e: VaultEntry, target: String, domain: String, cert: String? = null) = e.copy(
        excludedDomains = e.excludedDomains.filterNot {it==host(domain)},
        apps = if (target.isEmpty() || target in e.apps) e.apps else e.apps + target,
        appCerts = if (target.isEmpty() || cert == null) e.appCerts else e.appCerts + (target to cert),
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

    // ---------- 保存（v1.1：多字段、页面类型、改密码） ----------

    /** 一个要存的字段：type = username / phone / email / other */
    data class SaveField(val type: String, val label: String, val value: String, val secret: Boolean = false) {
        val storedLabel: String get() = when (type) {
            "username" -> "用户名"
            "phone" -> "手机号"
            "email" -> "邮箱"
            else -> label.trim().take(20).ifEmpty { "其他" }
        }
    }

    data class SaveInput(
        val app: String, val domain: String, val name: String, val account: String, val password: String,
        val kind: String = "login", val fields: List<SaveField> = emptyList(), val currentPassword: String = "", val rules: String = "",
    ) {
        /** 没传账号时：用户名 → 手机号 → 邮箱 */
        val resolvedAccount: String get() = account.trim().ifEmpty {
            listOf("username", "phone", "email").firstNotNullOfOrNull { t -> fields.firstOrNull { it.type == t && it.value.isNotBlank() }?.value?.trim() }.orEmpty()
        }

        companion object {
            fun from(x: Bundle, app: String, domain: String): SaveInput {
                @Suppress("DEPRECATION") val rows = x.getParcelableArrayList<Bundle>("fields").orEmpty()
                return SaveInput(
                    app = app, domain = domain, name = x.getString("name").orEmpty().trim(),
                    account = x.getString("account").orEmpty().trim(), password = x.getString("password").orEmpty(),
                    kind = x.getString("kind").orEmpty().ifEmpty { "login" },
                    fields = rows.take(20).map { SaveField(it.getString("type").orEmpty(), it.getString("label").orEmpty(), it.getString("value").orEmpty().take(500), it.getBoolean("secret")) },
                    currentPassword = x.getString("currentPassword").orEmpty(),
                    rules = x.getString("rules").orEmpty().take(500),
                )
            }
        }
    }

    data class SaveResult(val id: String, val result: String, val name: String, val fields: Int)

    /**
     * 普通 App 按账号和包名更新；网页按账号和规范化主机名完全相同更新；都没有时一律新建。
     * 改密码（kind = change）且给了当前密码：同一个 App / 网站下密码等于当前密码的那一条优先。
     */
    /** 保存时会更新哪一条；null 表示会新建（确认页用来提示"更新 XX"还是"新建"） */
    fun findExisting(input: SaveInput): VaultEntry? {
        val app = input.app
        val domain = input.domain
        val account = input.resolvedAccount
        fun sameScope(e: VaultEntry) = when {
            app.isNotEmpty() -> app in e.apps
            domain.isNotEmpty() -> host(e.url) == domain
            else -> false
        }
        val passwords = VaultStore.entries.value.filter { it.type == VaultType.PASSWORD }
        return (if (input.kind == "change" && input.currentPassword.isNotEmpty())
            passwords.firstOrNull { sameScope(it) && it.password == input.currentPassword && (account.isEmpty() || it.account == account) } else null)
            ?: passwords.firstOrNull { it.account == account && sameScope(it) }
    }

    fun saveCredential(context: Context, input: SaveInput): SaveResult {
        val app = input.app
        val domain = input.domain
        val account = input.resolvedAccount
        val existing = findExisting(input)
        val cert = AppCerts.of(context, app)
        val extra = input.fields.filter { it.value.isNotBlank() && it.value.trim() != account && !it.storedLabel.startsWith(OLD_PASSWORD) }
        if (existing != null) {
            var next = replacePassword(bind(existing, app, domain, cert), input.password)
            next = mergeFields(next, extra)
            if (input.rules.isNotEmpty()) next = next.copy(passwordRules = input.rules)
            if (next.account.isEmpty() && account.isNotEmpty()) next = next.copy(account = account)
            VaultStore.save(next.copy(lastUsedAt = System.currentTimeMillis()))
            return SaveResult(existing.id, "updated", existing.name, extra.size)
        }
        val name = input.name.ifEmpty { appLabel(context, app) ?: domain.ifEmpty { "网页密码" } }
        val created = VaultStore.add(mergeFields(VaultEntry(
            type = VaultType.PASSWORD, name = name, account = account, password = input.password,
            url = domain, apps = listOfNotNull(app.ifEmpty { null }),
            appCerts = if (app.isNotEmpty() && cert != null) mapOf(app to cert) else emptyMap(),
            passwordRules = input.rules, lastUsedAt = System.currentTimeMillis(),
        ), extra))
        return SaveResult(created.id, "created", created.name, extra.size)
    }

    /** 同名字段覆盖，没有就加在后面 */
    private fun mergeFields(e: VaultEntry, fields: List<SaveField>): VaultEntry {
        var list = e.fields
        for (f in fields) {
            val label = f.storedLabel
            val value = f.value.trim()
            val i = list.indexOfFirst { it.label == label }
            list = if (i >= 0) list.toMutableList().also { it[i] = it[i].copy(value = value, secret = f.secret) }
            else list + VaultField(label = label, value = value, secret = f.secret)
        }
        return if (list == e.fields) e else e.copy(fields = list)
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
