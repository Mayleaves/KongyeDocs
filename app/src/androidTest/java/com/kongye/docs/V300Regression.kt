package com.kongye.docs

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.FilesSub
import com.kongye.docs.ui.Tab
import com.kongye.docs.vault.VaultAccess
import com.kongye.docs.vault.VaultApi
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 3.0：密码空间对外接口（VaultProvider）、解锁、开关、访问记录、网址与适用 App。
 * 按 docs/密码填充-接口与任务.md 第 2 节「测试」：没开开关 → disabled；不在白名单 → denied；没解锁 → locked；解锁 → ok；锁屏 → 再次 locked。
 * 全部是测试用假数据；真正的指纹 / 锁屏密码不在测试里输入，解锁成功用 VaultAccess.unlock() 代替。
 */
object V300Regression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun log(text: String) = instrument.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        val root = File(context.filesDir, "v300-${System.nanoTime()}").apply { mkdirs() }
        val fixture = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val uri = Uri.parse("content://${context.packageName}.vault")
        fun call(method: String, vararg pairs: Pair<String, Any?>): Bundle {
            val extras = Bundle().apply {
                pairs.forEach { (k, v) -> when (v) { is String -> putString(k, v); is Int -> putInt(k, v); null -> Unit; else -> error("bad extra") } }
            }
            return context.contentResolver.call(uri, method, null, extras) ?: error("null bundle for $method")
        }
        fun Bundle.status() = getString("status")
        @Suppress("DEPRECATION") fun Bundle.entries() = getParcelableArrayList<Bundle>("entries").orEmpty()

        val mail = VaultEntry(type = VaultType.PASSWORD, name = "Example Mail", account = "alice@example.com", password = "Fill#Pass1",
            url = "example.com", apps = listOf("com.example.mail"))
        val web = VaultEntry(type = VaultType.PASSWORD, name = "Example Web", account = "13812345678", password = "Web#Pass2",
            url = "https://accounts.example.org/login")
        val otpOnly = VaultEntry(type = VaultType.OTP, name = "Only Code", secret = "JBSWY3DPEHPK3PXP")
        try {
            main { AppState.goHome(Tab.HOME); AppState.filesSub = FilesSub.DESKTOP; Settings.init(fixture); Repository.init(fixture); VaultAccess.lock() }

            // ---------- 数据：旧数据没有 apps；2.24–2.32 的「网址」字段搬回固定网址；备份带上新字段，旧备份照常恢复 ----------
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val legacyJson = """{"id":"legacy1","type":"PASSWORD","name":"Old Site","account":"old@example.net","password":"Old#Pass0",""" +
                """"fields":[{"id":"f1","label":"网址","value":"https://old.example.net/login"},{"id":"f2","label":"备注","value":"留着"}]}"""
            val legacy = json.decodeFromString(VaultEntry.serializer(), legacyJson)
            check(legacy.apps.isEmpty() && legacy.lastUsedAt == 0L)
            val lifted = legacy.normalized()
            check(lifted.url == "https://old.example.net/login" && lifted.fields.map { it.label } == listOf("备注")) { "Lifted: $lifted" }
            check(VaultEntry(type = VaultType.PASSWORD, name = "n", note = "旧备注").normalized().let { it.note.isEmpty() && it.field("备注") == "旧备注" })
            main { listOf(mail, web, otpOnly).forEach { VaultStore.save(it) } }
            val sealed = VaultStore.seal(VaultStore.entries.value, "test-backup-pw")
            val reopened = VaultStore.open(sealed, "test-backup-pw").associateBy { it.id }
            check(reopened[mail.id]!!.apps == listOf("com.example.mail") && reopened[web.id]!!.url == web.url)
            // 2.32 的备份：JSON 里没有 apps / lastUsedAt，网址在字段里
            val oldBackup = run {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val spec = PBEKeySpec("old-pw".toCharArray(), salt, 120_000, 256)
                val key = SecretKeySpec(SecretKeyFactory.getInstance(VaultStore.KDF).generateSecret(spec).encoded, "AES")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                val data = cipher.doFinal("""{"entries":[$legacyJson]}""".toByteArray())
                fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
                VaultBackup(VaultStore.KDF, 120_000, b64(salt), b64(cipher.iv), b64(data), 1)
            }
            check(main { VaultStore.restore(oldBackup, "old-pw") } == 1)
            val restored = VaultStore.get("legacy1")!!
            check(restored.apps.isEmpty() && restored.url == "https://old.example.net/login" && restored.password == "Old#Pass0") { "Restored: $restored" }
            passed("data-apps-default-url-lifted-backup-roundtrip-and-old-backup-restore")

            // ---------- 开关没开 → disabled（status 也报 disabled，并带上 version / enabled / unlocked） ----------
            check(!Settings.value.vaultFill) { "Fill must default to off" }
            call("status").let { check(it.status() == "disabled" && it.getInt("version") == 1 && !it.getBoolean("enabled") && !it.getBoolean("unlocked")) { "status off: $it" } }
            listOf("match", "search", "reveal", "generate", "save", "bind").forEach { m ->
                check(call(m, "package" to "com.example.mail", "id" to mail.id, "query" to "Example", "password" to "x").status() == "disabled") { "$m not disabled" }
            }
            passed("switch-off-returns-disabled-for-every-method")

            // ---------- 不在白名单 → denied；白名单里的三个包名放行 ----------
            main { Settings.update { it.copy(vaultFill = true) } }
            listOf("status", "match", "reveal").forEach { m -> check(VaultApi.handle(context, "com.example.other", m, Bundle()).getString("status") == "denied") { "$m not denied" } }
            check(VaultApi.handle(context, null, "status", null).getString("status") == "denied")
            check(listOf("com.kongye.keyboard", "com.kongye.keyboard.debug", "com.kongye.floatball").all { VaultApi.allowed(context, it) })
            check(!VaultApi.allowed(context, "com.kongye.docs") || context.packageName == "com.kongye.docs")
            passed("caller-outside-whitelist-denied")

            // ---------- match / search：只给打码账号，按包名 > 域名 ----------
            call("status").let { check(it.status() == "ok" && it.getBoolean("enabled") && !it.getBoolean("unlocked") && it.getLong("unlockExpiresAt") == 0L) { "status on: $it" } }
            call("match", "package" to "com.example.mail").entries().let { list ->
                check(list.size == 1 && list[0].getString("id") == mail.id && list[0].getString("matchedBy") == "package" && list[0].getString("account") == "al****@example.com" && !list[0].containsKey("password")) { "match pkg: $list" }
            }
            call("match", "package" to "com.example.browser", "domain" to "https://login.accounts.example.org/x").entries().let { list ->
                check(list.map { it.getString("id") to it.getString("matchedBy") } == listOf(web.id to "domain") && list[0].getString("account") == "138****5678") { "match domain: $list" }
            }
            check(call("match", "package" to "com.example.browser", "domain" to "example.org").entries().isEmpty()) { "parent domain must not match a subdomain entry" }
            check(call("match", "package" to "com.example.browser", "domain" to "notexample.com").entries().isEmpty())
            call("match", "package" to "com.example.mail", "domain" to "example.com").entries().let { check(it.size == 1 && it[0].getString("matchedBy") == "package") { "dedupe: $it" } }
            call("search", "query" to "Example").entries().let { list ->
                // 恢复进来的 Old Site 账号是 old@example.net，也含 example
                check(list.map { it.getString("id") }.toSet() == setOf(mail.id, web.id, "legacy1") && list.all { it.getString("matchedBy") == "search" }) { "search: $list" }
            }
            check(call("search", "query" to "Fill#Pass1").entries().isEmpty()) { "search must not look at passwords" }
            check(call("search", "query" to "Only Code").entries().isEmpty()) { "entries without account / password are not fillable" }
            passed("match-by-package-then-domain-search-masked-accounts")

            // ---------- 没解锁 → locked + unlock PendingIntent；打开解锁页 ----------
            val locked = call("reveal", "id" to mail.id, "package" to "com.example.mail")
            check(locked.status() == "locked" && !locked.containsKey("password") && !locked.containsKey("account")) { "reveal locked: $locked" }
            @Suppress("DEPRECATION") val unlock = locked.getParcelable<PendingIntent>("unlock") ?: error("no unlock PendingIntent")
            val activity = startTestActivity(instrument, context)
            val auto = instrument.uiAutomation
            auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            fun roots() = auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)
            fun all(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo) { if (match(node)) out += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
                roots().forEach { runCatching { walk(it) } }
                return out
            }
            fun find(label: String) = all { it.text?.toString() == label || it.contentDescription?.toString() == label }.firstOrNull()
            fun findContains(label: String) = all { it.text?.toString()?.contains(label) == true }.firstOrNull()
            fun box(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
            fun waitFor(label: String, ms: Long = 12000, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + ms
                while (SystemClock.uptimeMillis() < end) { if (runCatching(condition).getOrDefault(false)) return; Thread.sleep(60) }; error("Timed out: $label")
            }
            fun press(node: AccessibilityNodeInfo): Boolean { var n = node; while (!n.isClickable) n = n.parent ?: return false; return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            fun click(label: String) { Thread.sleep(400); waitFor("click $label") { find(label)?.let(::press) ?: false } }
            fun shot(name: String) { val bmp = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            fun foreground() = auto.executeShellCommand("dumpsys activity activities").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
            }.lineSequence().firstOrNull { it.contains("mResumedActivity") || it.contains("topResumedActivity") }.orEmpty()
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Settings.update { it.copy(theme = ThemeMode.LIGHT) } }
                Thread.sleep(1200)
                unlock.send()
                // 系统指纹 / 锁屏密码面板（由系统界面画）出现；不输入任何东西，按返回取消
                waitFor("unlock prompt", 10000) { find("解锁密码空间") != null || foreground().contains("VaultUnlockActivity") }
                Thread.sleep(800)
                val prompt = find("解锁密码空间") != null
                log("Unlock prompt visible=$prompt subtitle=${findContains("允许")?.text}")
                shot("v300-unlock-prompt.png")
                check(prompt) { "BiometricPrompt not shown; foreground=${foreground()}" }
                check(findContains("允许")?.text?.toString()?.contains("Example Mail") == true) { "Prompt should name the entry" }
                auto.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor("prompt cancelled") { find("解锁密码空间") == null && !foreground().contains("VaultUnlockActivity") }
                check(call("reveal", "id" to mail.id, "package" to "com.example.mail").status() == "locked") { "Cancelling must keep it locked" }
                passed("locked-reveal-gives-pendingintent-that-opens-biometric-prompt-cancel-stays-locked")

                // ---------- 解锁 → ok（5 分钟）；锁屏 → 再次 locked ----------
                val before = System.currentTimeMillis()
                VaultAccess.unlock()
                call("status").let { s ->
                    val exp = s.getLong("unlockExpiresAt")
                    check(s.getBoolean("unlocked") && exp in before + 4 * 60_000..before + 6 * 60_000) { "status unlocked: $s" }
                }
                call("reveal", "id" to mail.id, "package" to "com.example.mail").let { r ->
                    check(r.status() == "ok" && r.getString("account") == "alice@example.com" && r.getString("password") == "Fill#Pass1") { "reveal ok: ${r.status()}" }
                }
                check(VaultStore.get(mail.id)!!.lastUsedAt >= before) { "lastUsedAt not recorded" }
                check(call("reveal", "id" to "no-such-id", "package" to "com.example.mail").status() == "not_found")
                main { VaultAccess.screenOffReceiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF)) }
                check(call("reveal", "id" to mail.id, "package" to "com.example.mail").status() == "locked") { "Screen off must lock" }
                check(!call("status").getBoolean("unlocked"))
                passed("unlock-gives-ok-for-five-minutes-and-screen-off-locks-again")

                // ---------- generate / save / bind ----------
                val g16 = call("generate").getString("password")!!
                val g24 = call("generate", "length" to 24).getString("password")!!
                listOf(g16, g24).forEach { p ->
                    check(p.any { it.isUpperCase() } && p.any { it.isLowerCase() } && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() }) { "weak generated password" }
                }
                check(g16.length == 16 && g24.length == 24 && g16 != g24)
                val created = call("save", "name" to "Example Shop", "account" to "bob", "password" to "Shop#Pass3", "package" to "com.example.shop", "domain" to "shop.example.com")
                check(created.status() == "ok" && created.getString("result") == "created") { "save created: $created" }
                val shopId = created.getString("id")!!
                VaultStore.get(shopId)!!.let { check(it.apps == listOf("com.example.shop") && it.url == "shop.example.com" && it.password == "Shop#Pass3" && it.type == VaultType.PASSWORD) }
                val updated = call("save", "account" to "bob", "password" to "Shop#Pass4", "package" to "com.example.shop")
                check(updated.status() == "ok" && updated.getString("result") == "updated" && updated.getString("id") == shopId) { "save updated: $updated" }
                check(VaultStore.get(shopId)!!.password == "Shop#Pass4" && VaultStore.entries.value.count { it.account == "bob" } == 1)
                check(call("save", "account" to "bob", "password" to "Other#5", "package" to "com.example.other").getString("result") == "created") { "other app = new entry" }
                check(call("bind", "id" to web.id, "package" to "com.example.web").status() == "ok")
                check(VaultStore.get(web.id)!!.apps == listOf("com.example.web"))
                check(call("bind", "id" to web.id, "package" to "com.example.web").status() == "ok" && VaultStore.get(web.id)!!.apps == listOf("com.example.web")) { "bind must not duplicate" }
                call("match", "package" to "com.example.web").entries().let { check(it.map { e -> e.getString("id") to e.getString("matchedBy") } == listOf(web.id to "package")) { "after bind: $it" } }
                check(call("bind", "id" to "no-such-id", "package" to "com.example.web").status() == "not_found")
                passed("generate-save-create-update-and-bind")

                // ---------- 访问记录：有调用方、方法、目标包名；不记 status；文件里没有任何密码 ----------
                val records = VaultAccessLog.records.value
                check(records.size <= VaultAccessLog.LIMIT && records.none { it.method == "status" })
                check(records.any { it.method == "reveal" && it.status == "ok" && it.target == "com.example.mail" && it.entry == "Example Mail" && it.caller == context.packageName }) { "reveal not logged" }
                check(records.any { it.method == "reveal" && it.status == "locked" })
                check(records.any { it.method == "save" && it.entry == "Example Shop" })
                val logText = File(root, "vault/access.json").readText()
                listOf("Fill#Pass1", "Web#Pass2", "Shop#Pass3", "Shop#Pass4", "Other#5", g16, g24, "alice@example.com", "13812345678").forEach {
                    check(!logText.contains(it)) { "Access log leaked a secret or account" }
                }
                repeat(60) { call("match", "package" to "com.example.flood") }
                check(VaultAccessLog.records.value.size == 50 && VaultAccessLog.records.value.first().target == "com.example.flood")
                passed("access-log-keeps-50-without-passwords")

                // ---------- 界面：密码空间 → 密码填充（开关、状态、访问记录）；详情的网址与已用于；编辑页的网址与适用 App ----------
                main { Settings.update { it.copy(vaultFill = false) }; AppState.filesSub = FilesSub.VAULT; AppState.goHome(Tab.FILES) }
                waitFor("vault page") { find("Example Mail") != null && findContains("密码填充未开启") != null }
                click("密码填充")
                waitFor("fill screen") { find("允许空叶键盘 / 悬浮球读取") != null && find("空叶键盘") != null }
                check(!Settings.value.vaultFill)
                click("允许空叶键盘 / 悬浮球读取")
                waitFor("switched on") { Settings.value.vaultFill }
                waitFor("log rows") { findContains("查找匹配") != null }
                check(find("未解锁") != null)
                Thread.sleep(400)
                shot("v300-fill-settings.png")
                auto.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor("back on vault") { findContains("已允许空叶键盘") != null && find("Example Web") != null }
                passed("fill-settings-switch-status-and-log-ui")

                click("Example Web")
                waitFor("detail") { find("密码 · 点一项即可复制") != null }
                waitFor("url and used-in") { find("网址") != null && find("https://accounts.example.org/login") != null && findContains("已用于：") != null }
                Thread.sleep(300)
                shot("v300-detail-used-in.png")
                click("移除com.example.web")
                waitFor("app removed") { VaultStore.get(web.id)!!.apps.isEmpty() && findContains("已用于：") == null }
                passed("detail-shows-url-and-used-in-apps-removable")

                click("编辑")
                waitFor("editor") { find("编辑密码") != null && find("添加 App") != null }
                val urlField = all { it.isEditable }.firstOrNull { it.text?.toString() == "https://accounts.example.org/login" }
                check(urlField != null) { "url field not shown in editor" }
                Thread.sleep(300)
                shot("v300-editor-url-apps.png")
                passed("editor-shows-url-and-app-section")
            } catch (error: Throwable) { shot("v300-failure.png"); throw error }
            finally {
                VaultAccess.lock()
                main { Settings.update { it.copy(theme = ThemeMode.SYSTEM, vaultFill = false) }; AppState.filesSub = FilesSub.DESKTOP; AppState.goHome(Tab.HOME); activity.finish() }
            }
        } finally { main { VaultAccess.lock(); Settings.init(context); Repository.init(context) } }
    }
}
