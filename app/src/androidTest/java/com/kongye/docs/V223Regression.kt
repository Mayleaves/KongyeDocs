package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.FilesSub
import com.kongye.docs.ui.Tab
import java.io.File

/** v2.23：密码空间（动态密码 + 普通密码）、加密保存、加密备份与恢复。所有密钥和密码都是测试用的假数据。 */
object V223Regression {
    private const val SECRET = "JBSWY3DPEHPK3PXP"
    private const val LINK = "otpauth://totp/Example%20Co:alice@example.com?secret=$SECRET&issuer=Example%20Co&digits=6&period=30"
    private const val BACKUP_PASSWORD = "synthetic-backup-pass"

    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun fixture(name: String) = object : ContextWrapper(context) {
            val root = File(context.filesDir, "v223-$name-${System.nanoTime()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            // ---------- 动态密码计算（RFC 6238 附录 B 的测试向量） ----------
            main {
                val seed1 = "12345678901234567890".toByteArray()
                check(Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").contentEquals(seed1))
                check(Totp.decodeBase32("gezd gnbv-gy3t qojq gezdgnbvgy3tqojq==").contentEquals(seed1)) { "Base32 cleanup failed" }
                val seed256 = "12345678901234567890123456789012".toByteArray()
                val seed512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()
                val vectors = listOf(59L to listOf("94287082", "46119246", "90693936"), 1111111109L to listOf("07081804", "68084774", "25091201"),
                    1234567890L to listOf("89005924", "91819424", "93441116"), 2000000000L to listOf("69279037", "90698825", "38618901"))
                for ((t, expected) in vectors) {
                    check(Totp.code(seed1, t / 30, 8, "SHA1") == expected[0]) { "SHA1 @$t" }
                    check(Totp.code(seed256, t / 30, 8, "SHA256") == expected[1]) { "SHA256 @$t" }
                    check(Totp.code(seed512, t / 30, 8, "SHA512") == expected[2]) { "SHA512 @$t" }
                }
                val entry = VaultEntry(type = VaultType.OTP, name = "x", secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8)
                check(Totp.code(entry, 59_000L) == "94287082")
                check(Totp.remaining(30, 59_000L) == 1 && Totp.remaining(30, 60_000L) == 30)
                check(runCatching { Totp.decodeBase32("ABC1") }.exceptionOrNull()?.message?.contains("无效字符") == true)
                val parsed = Totp.parse(LINK)
                check(parsed.name == "Example Co" && parsed.account == "alice@example.com" && parsed.secret == SECRET && parsed.digits == 6 && parsed.period == 30 && parsed.algorithm == "SHA1") { "Parse: $parsed" }
                check(Totp.parse("otpauth://totp/OnlyName?secret=jbsw y3dp&algorithm=sha256&digits=8&period=60").let { it.name == "OnlyName" && it.account.isEmpty() && it.secret == "JBSWY3DP" && it.algorithm == "SHA256" && it.digits == 8 && it.period == 60 })
                check(runCatching { Totp.parse("otpauth://hotp/A?secret=$SECRET&counter=1") }.exceptionOrNull()?.message?.contains("HOTP") == true)
                check(runCatching { Totp.parse("otpauth://totp/A?issuer=B") }.isFailure)
                check(runCatching { Totp.parse("https://example.com") }.isFailure)
            }
            passed("totp-rfc6238-vectors-base32-and-otpauth-parsing")

            // ---------- 加密保存 ----------
            val store = fixture("store")
            val otp = VaultEntry(type = VaultType.OTP, name = "Example Co", account = "alice@example.com", secret = SECRET)
            val pwd = VaultEntry(type = VaultType.PASSWORD, name = "测试邮箱", account = "tester", password = "Synthetic#Pass123", url = "mail.example.com", note = "测试备注")
            main {
                Settings.init(store); Repository.init(store)
                check(VaultStore.entries.value.isEmpty())
                VaultStore.save(otp); VaultStore.save(pwd)
                val raw = File(store.filesDir, "vault/vault.json").readText()
                check(SECRET !in raw && "Synthetic#Pass123" !in raw && "alice@example.com" !in raw && "测试邮箱" !in raw) { "Vault file is not encrypted" }
                Repository.init(store)
                check(VaultStore.entries.value.map { it.id }.toSet() == setOf(otp.id, pwd.id) && VaultStore.get(pwd.id)!!.password == "Synthetic#Pass123")
                // 2.24 起网址、备注是普通字段
                val stored = VaultStore.get(pwd.id)!!
                check(stored.url.isEmpty() && stored.note.isEmpty() && stored.field("网址") == "mail.example.com" && stored.field("备注") == "测试备注")
                VaultStore.save(stored.copy(fields = stored.fields.map { if (it.label == "备注") it.copy(value = "改过的备注") else it }))
                check(VaultStore.get(pwd.id)!!.field("备注") == "改过的备注" && VaultStore.entries.value.size == 2)
                // 没设备份密码：备份里没有密码空间（JSON 里也不出现 vault 字段）
                val plain = Repository.exportBackup()
                check("\"vault\"" !in plain && SECRET !in plain && "Synthetic#Pass123" !in plain)
                // 设置备份密码：本机 settings.json 里不出现明文，重新读取后仍在
                Settings.updateBackup { it.copy(vaultPassword = BACKUP_PASSWORD) }
                check(BACKUP_PASSWORD !in File(store.filesDir, "settings.json").readText()) { "Backup password stored in plain text" }
                Settings.init(store)
                check(Settings.value.backup.vaultPassword == BACKUP_PASSWORD)
            }
            passed("vault-encrypted-at-rest-reload-and-backup-password-protected")

            // ---------- 加密备份与恢复 ----------
            val sealed = main {
                val text = Repository.exportBackup()
                check("\"vault\"" in text && SECRET !in text && "Synthetic#Pass123" !in text && "alice@example.com" !in text) { "Backup leaks vault content" }
                val decoded = BackupCodec.decode(text)
                check(decoded.vault!!.count == 2)
                text
            }
            // 新手机：没有备份密码，恢复文档后等待输入；错密码被拒绝，对的密码恢复全部条目
            val fresh = fixture("fresh")
            main {
                Settings.init(fresh); Repository.init(fresh)
                Repository.importBackup(sealed)
                val pending = VaultStore.pendingRestore.value ?: error("Restore prompt not offered")
                check(VaultStore.entries.value.isEmpty())
                check(runCatching { VaultStore.restore(pending, "wrong-password") }.exceptionOrNull() is VaultPasswordException)
                check(VaultStore.restore(pending, BACKUP_PASSWORD) == 2 && VaultStore.pendingRestore.value == null)
                check(VaultStore.get(otp.id)!!.secret == SECRET && VaultStore.get(pwd.id)!!.field("备注") == "改过的备注")
            }
            // 同一台手机：记住的备份密码直接解开，不弹窗；本机其他条目保留
            val same = fixture("same")
            main {
                Settings.init(same); Repository.init(same)
                Settings.updateBackup { it.copy(vaultPassword = BACKUP_PASSWORD) }
                val local = VaultEntry(type = VaultType.PASSWORD, name = "本机条目", password = "Local#1")
                VaultStore.save(local)
                Repository.importBackup(sealed)
                check(VaultStore.pendingRestore.value == null) { "Should restore silently with the stored password" }
                check(VaultStore.entries.value.map { it.id }.toSet() == setOf(otp.id, pwd.id, local.id))
            }
            passed("vault-sealed-into-backup-restores-with-password-or-stored-password")

            // ---------- 界面 ----------
            val ui = fixture("ui")
            main { AppState.goHome(Tab.HOME); AppState.filesSub = FilesSub.DESKTOP; Settings.init(ui); Repository.init(ui) }
            val activity = startTestActivity(instrument, context)
            val auto = instrument.uiAutomation
            auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            fun roots() = auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)
            fun findWhere(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (match(node)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                    return null
                }
                return roots().firstNotNullOfOrNull { walk(it) }
            }
            fun find(label: String) = findWhere { it.text?.toString() == label || it.contentDescription?.toString() == label }
            fun all(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo) { if (match(node)) out += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
                roots().forEach(::walk)
                return out
            }
            fun box(n: AccessibilityNodeInfo) = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            /** 编辑页的输入框：和左边标签（名称 / 账号 / 密钥 / 密码）在同一行的可编辑节点 */
            fun field(label: String): AccessibilityNodeInfo? {
                val editables = all { it.isEditable }
                for (l in all { !it.isEditable && it.text?.toString() == label }) {
                    val lb = box(l)
                    editables.firstOrNull { e -> val eb = box(e); lb.centerY() in eb.top..eb.bottom }?.let { return it }
                }
                return null
            }
            /** 弹窗里的输入框没有标签：按从上到下的顺序取 */
            // 当前窗口会同时出现在 windows 和 rootInActiveWindow 里：按位置去重
            fun dialogField(index: Int) = all { it.isEditable }.distinctBy { box(it).flattenToString() }.sortedBy { box(it).top }.getOrNull(index)
            fun waitFor(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 12000
                while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }; error("Timed out: $label")
            }
            fun click(label: String) {
                Thread.sleep(400)
                waitFor("click $label") {
                    runCatching {
                        var node = find(label) ?: return@runCatching false
                        while (!node.isClickable) node = node.parent ?: return@runCatching false
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                }
            }
            /** 在字段详情面板（单独的窗口）里点某一项：subtitle 用来认出面板所在的窗口 */
            fun clickInSheet(subtitle: String, label: String) {
                waitFor("sheet $subtitle") { find(subtitle) != null }
                val window = find(subtitle)!!.windowId
                Thread.sleep(300)
                waitFor("click $label in sheet") {
                    runCatching {
                        var node = all { it.windowId == window && it.text?.toString() == label }.firstOrNull() ?: return@runCatching false
                        while (!node.isClickable) node = node.parent ?: return@runCatching false
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                }
            }
            fun setText(node: AccessibilityNodeInfo, text: String) = check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "Set text failed" }
            fun type(label: String, text: String) {
                waitFor("field $label") { field(label) != null }
                setText(field(label)!!, text)
            }
            fun typeDialog(index: Int, text: String) {
                waitFor("dialog field $index") { dialogField(index) != null }
                setText(dialogField(index)!!, text)
            }
            fun shot(name: String) { val bmp = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            fun clipboard() = main { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Settings.update { it.copy(theme = ThemeMode.LIGHT) }; AppState.goHome(Tab.FILES) }
                Thread.sleep(1500)
                waitFor("files header") { find("我的桌面") != null && find("密码空间") != null && find("搜索") != null }
                click("密码空间")
                // 2.24 起密码空间也有搜索（只搜密码空间）
                waitFor("vault page") { find("密码空间是空的") != null && find("未设置备份密码，密码空间不会进入备份") != null && find("搜索") != null }
                shot("v223-vault-empty.png")
                passed("files-header-switches-to-empty-vault")

                // 添加动态密码：在密钥栏粘贴 otpauth 链接，名称、账号、密钥一次填好
                click("新建"); click("添加动态密码")
                waitFor("otp editor") { find("添加动态密码") != null && field("密钥") != null }
                type("密钥", LINK)
                fun shows(text: String) = findWhere { it.text?.toString()?.contains(text) == true } != null
                waitFor("filled from link") { find("当前验证码") != null && shows("Example Co") && shows("alice@example.com") }
                Thread.sleep(400)
                shot("v223-otp-editor.png")
                click("保存")
                waitFor("otp saved") { main { VaultStore.entries.value.any { it.type == VaultType.OTP && it.secret == SECRET && it.name == "Example Co" && it.account == "alice@example.com" } } && find("Example Co") != null }
                val saved = main { VaultStore.entries.value.first { it.type == VaultType.OTP } }
                waitFor("code shown") {
                    val now = System.currentTimeMillis()
                    listOf(now, now - 1000).map { Totp.code(saved, it) }.any { findWhere { n -> n.contentDescription?.toString() == "验证码 $it" } != null }
                }
                // 2.25 起点一下打开字段详情，在详情里点“验证码”复制
                click("Example Co")
                clickInSheet("动态密码 · 点一项即可复制", "验证码")
                waitFor("code copied") { val c = clipboard(); val now = System.currentTimeMillis(); c != null && c in listOf(now, now - 2000).map { Totp.code(saved, it) } }
                click("关闭")
                waitFor("sheet closed") { find("动态密码 · 点一项即可复制") == null }
                passed("add-otp-from-link-shows-live-code-and-copies")

                // 添加密码：随机生成，保存后列表里显示圆点，点一下复制密码
                click("新建"); click("添加密码")
                waitFor("password editor") { find("添加密码") != null && field("密码") != null }
                type("名称", "测试邮箱"); type("账号", "tester")
                click("随机生成密码")
                waitFor("generated") { find("要保存的密码") == null }
                click("保存")
                waitFor("password saved") { main { VaultStore.entries.value.any { it.type == VaultType.PASSWORD && it.name == "测试邮箱" } } && find("测试邮箱") != null }
                val generated = main { VaultStore.entries.value.first { it.type == VaultType.PASSWORD }.password }
                check(generated.length == 16 && generated.any { it.isUpperCase() } && generated.any { it.isLowerCase() } && generated.any { it.isDigit() } && generated.any { !it.isLetterOrDigit() })
                // 等编辑页退场（退场动画期间它还在界面树里，上面显示着刚生成的明文密码）
                waitFor("editor closed") { find("添加密码") == null && find("••••••") != null }
                check(find(generated) == null) { "Password shown in plain text in the list" }
                click("测试邮箱")
                clickInSheet("密码 · 点一项即可复制", "密码")
                waitFor("password copied") { clipboard() == generated }
                click("关闭")
                waitFor("sheet closed") { find("密码 · 点一项即可复制") == null }
                click("显示密码")
                waitFor("password revealed") { find(generated) != null }
                click("隐藏密码")
                passed("add-password-with-generator-masks-reveals-and-copies")

                // 设置备份密码：输两次
                click("设置备份密码")
                waitFor("password dialog") { find("至少 6 位") != null && dialogField(1) != null }
                typeDialog(0, BACKUP_PASSWORD); typeDialog(1, BACKUP_PASSWORD)
                Thread.sleep(300)
                shot("v223-backup-password-dialog.png")
                click("确定")
                waitFor("backup password saved") { main { Settings.value.backup.vaultPassword } == BACKUP_PASSWORD && find("已设置备份密码，密码空间加密后随本地 / 云端备份") != null }
                Thread.sleep(800)
                shot("v223-vault-list.png")
                main { Settings.update { it.copy(theme = ThemeMode.DARK) } }; Thread.sleep(900)
                shot("v223-vault-list-dark.png")
                main { Settings.update { it.copy(theme = ThemeMode.LIGHT) } }; Thread.sleep(500)
                passed("backup-password-dialog-and-vault-list-light-dark")

                // 恢复弹窗：本机记住的备份密码解不开时出现；错密码提示，对的密码恢复
                val other = main { VaultStore.seal(listOf(VaultEntry(type = VaultType.PASSWORD, name = "旧手机条目", password = "Old#Pass9")), "another-synthetic-pass") }
                main { VaultStore.offerRestore(other) }
                waitFor("restore dialog") { find("恢复密码空间") != null && dialogField(0) != null }
                typeDialog(0, "wrong-password"); click("恢复")
                waitFor("wrong password") { find("备份密码不正确，请重试") != null }
                Thread.sleep(300)
                shot("v223-restore-dialog.png")
                typeDialog(0, "another-synthetic-pass"); click("恢复")
                waitFor("restored") { find("恢复密码空间") == null && main { VaultStore.entries.value.any { it.name == "旧手机条目" } } }
                check(main { Settings.value.backup.vaultPassword } == BACKUP_PASSWORD) { "Existing backup password must not change" }
                passed("restore-dialog-rejects-wrong-password-and-restores")
            } catch (error: Throwable) { shot("v223-failure.png"); throw error }
            finally { main { Settings.update { it.copy(theme = ThemeMode.SYSTEM) }; AppState.filesSub = FilesSub.DESKTOP; AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
