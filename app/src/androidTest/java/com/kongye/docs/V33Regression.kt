package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import com.kongye.docs.autofill.FieldType
import com.kongye.docs.autofill.Page
import com.kongye.docs.autofill.PageField
import com.kongye.docs.autofill.PageKind
import com.kongye.docs.autofill.PageText
import com.kongye.docs.data.*
import com.kongye.docs.vault.AppCerts
import com.kongye.docs.vault.CharClass
import com.kongye.docs.vault.PasswordRules
import com.kongye.docs.vault.VaultAccess
import com.kongye.docs.vault.VaultApi
import java.io.File

/**
 * 3.3：需求文档 v2 —— 密码要求解析与按要求生成、页面识别、接口 v1.1（features、带要求的 generate、多字段 save、改密码、签名核对）。
 * 全部是测试用假数据；不输出任何生成的密码。
 */
object V33Regression {
    /**
     * 自动填充界面的真机检查（-e only v33-ui）：生成随机密码、保存确认、更多密码三种弹出界面能打开、显示对、能关掉。
     * 只在 .audit 测试包里打开自己的界面，不碰其他 App；截图存到测试包的外部文件目录。
     */
    fun runUi(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun texts(): List<String> {
            val out = ArrayList<String>()
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo) { n.text?.let { out += it.toString() }; n.contentDescription?.let { out += it.toString() }; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
            (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).forEach { runCatching { walk(it) } }
            return out
        }
        fun waitFor(label: String, ms: Long = 8000, condition: (List<String>) -> Boolean) {
            val end = android.os.SystemClock.uptimeMillis() + ms
            while (android.os.SystemClock.uptimeMillis() < end) { if (runCatching { condition(texts()) }.getOrDefault(false)) return; Thread.sleep(100) }
            error("Timed out: $label; saw ${texts().take(30)}")
        }
        fun shot(name: String) {
            Thread.sleep(500)
            val bmp = auto.takeScreenshot() ?: return
            File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        fun open(vararg extras: Pair<String, Any?>) {
            val intent = android.content.Intent(context, com.kongye.docs.autofill.AutofillActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            extras.forEach { (k, v) -> when (v) { is String -> intent.putExtra(k, v); is Boolean -> intent.putExtra(k, v); is Bundle -> intent.putExtra(k, v) } }
            context.startActivity(intent)
        }
        fun back() { auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(700) }
        val A = com.kongye.docs.autofill.AutofillActivity

        // 生成随机密码：页面要求"8–16 位，须包含字母和数字"
        open(A.EXTRA_MODE to A.MODE_GENERATE, A.EXTRA_PKG to "com.example.shop", A.EXTRA_LABEL to "示例商城",
            A.EXTRA_RULES to PasswordRules.parse("密码 8–16 位，须包含字母和数字").toBundle(), A.EXTRA_RULES_STATED to true)
        waitFor("generate sheet") { t -> t.contains("生成随机密码") && t.any { it.contains("按页面要求：16 位 · 字母+数字") } && t.contains("填入这个密码") }
        shot("v33-generate.png")
        back(); waitFor("generate closed") { t -> !t.contains("填入这个密码") }
        passed("generate-sheet-shows-rule-summary")

        // 保存确认：新建，名称可改，手机号可取消勾选
        val token = com.kongye.docs.autofill.PendingSaves.put(VaultApi.SaveInput(
            app = "com.example.shop", domain = "", name = "示例商城", account = "", password = "Ui#Only1", kind = "register",
            fields = listOf(VaultApi.SaveField("username", "", "ui_tester"), VaultApi.SaveField("phone", "", "13800001234")),
        ))
        open(A.EXTRA_MODE to A.MODE_SAVE, A.EXTRA_TOKEN to token, A.EXTRA_PKG to "com.example.shop")
        waitFor("save sheet") { t -> t.contains("保存到空叶文档") && t.contains("手机号") && t.any { it.contains("138****1234") } && t.contains("不保存") }
        shot("v33-save.png")
        back(); waitFor("save closed") { t -> !t.contains("不保存") }
        check(com.kongye.docs.autofill.PendingSaves.peek(token) == null) { "closing must drop the pending save" }
        passed("save-sheet-shows-fields-and-drops-on-close")

        // 更多密码：搜索框
        open(A.EXTRA_MODE to A.MODE_MORE, A.EXTRA_PKG to "com.example.shop", A.EXTRA_LABEL to "示例商城")
        waitFor("more sheet") { t -> t.contains("选择要填入的密码") && t.contains("搜索密码空间") }
        shot("v33-more.png")
        back(); waitFor("more closed") { t -> !t.contains("选择要填入的密码") }
        passed("more-sheet-opens-search")
    }

    private const val PWD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private const val TEXT = InputType.TYPE_CLASS_TEXT

    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun classes(p: String) = buildSet {
            if (p.any { it.isLowerCase() }) add(CharClass.LOWER)
            if (p.any { it.isUpperCase() }) add(CharClass.UPPER)
            if (p.any { it.isDigit() }) add(CharClass.DIGIT)
            if (p.any { !it.isLetterOrDigit() }) add(CharClass.SYMBOL)
        }

        // ---------- 密码要求解析 + 生成（S6 / S7 / S8） ----------
        PasswordRules.parse("密码 8–16 位，须包含字母和数字").let { r ->
            check(r.minLength == 8 && r.maxLength == 16) { "range: $r" }
            check(r.require == setOf(CharClass.LOWER, CharClass.DIGIT) && r.allowed?.contains(CharClass.SYMBOL) == false) { "classes: $r" }
            repeat(20) {
                val g = PasswordRules.generate(r)
                check(g.satisfied && g.password.length == 16 && g.summary == "16 位 · 字母+数字") { "S6 summary=${g.summary} len=${g.password.length}" }
                check(g.password.any { it.isLetter() } && g.password.any { it.isDigit() } && g.password.all { it.isLetterOrDigit() }) { "S6 classes ${classes(g.password)}" }
            }
        }
        PasswordRules.parse("6-20位，至少包含大写字母、小写字母、数字、特殊字符中的三种").let { r ->
            check(r.minLength == 6 && r.maxLength == 20 && r.minClasses == 3) { "three kinds: $r" }
            repeat(20) { check(classes(PasswordRules.generate(r).password).size >= 3) }
        }
        PasswordRules.parse("密码长度为八到十二位，不能包含特殊字符").let { r ->
            check(r.minLength == 8 && r.maxLength == 12 && r.allowed?.contains(CharClass.SYMBOL) == false) { "cn numbers / no symbols: $r" }
            repeat(20) { val p = PasswordRules.generate(r).password; check(p.length == 12 && p.all { it.isLetterOrDigit() }) }
        }
        check(PasswordRules.parse("仅支持字母和数字").allowed == setOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT))
        PasswordRules.parse("Must be at least 10 characters and include an uppercase letter and a number").let { r ->
            check(r.minLength == 10 && CharClass.UPPER in r.require && CharClass.DIGIT in r.require) { "english: $r" }
            repeat(10) { val p = PasswordRules.generate(r).password; check(p.any { it.isUpperCase() } && p.any { it.isDigit() } && p.length == 16) }
        }
        PasswordRules.parseAppleRules("minlength: 10; maxlength: 12; required: upper; required: digit").let { r ->
            repeat(20) { val p = PasswordRules.generate(r).password; check(p.length == 12 && p.any { it.isUpperCase() } && p.any { it.isDigit() } && p.all { it.isUpperCase() || it.isDigit() }) { "S7" } }
        }
        PasswordRules.generate(PasswordRules()).let { g ->
            check(g.password.length == 16 && classes(g.password) == CharClass.entries.toSet() && g.summary == "16 位 · 字母+数字+符号") { "S8 default ${g.summary}" }
        }
        PasswordRules.parse("不能包含连续3位相同或连续的字符").let { r ->
            check(r.forbidSequence)
            repeat(30) { check(!PasswordRules.hasRun(PasswordRules.generate(r).password)) }
        }
        PasswordRules(avoid = listOf("alice2026")).let { r ->
            repeat(30) { val p = PasswordRules.generate(r).password.lowercase(); check((0..6).none { i -> p.contains("alice2026".substring(i, i + 3)) }) }
        }
        PasswordRules.parse("8-16位字母和数字").merge(PasswordRules(pattern = "[A-Za-z0-9]+")).let { r ->
            val g = PasswordRules.generate(r); check(g.satisfied && Regex("[A-Za-z0-9]+").matches(g.password))
        }
        // 接口参数：ruleText + 结构化参数合并；v1 的 length 仍然是固定长度
        PasswordRules.fromBundle(Bundle().apply { putString("ruleText", "6-20位"); putInt("maxLength", 12); putBoolean("noSymbols", true) }).let { r ->
            check(r.minLength == 6 && r.maxLength == 12 && r.allowed?.contains(CharClass.SYMBOL) == false) { "bundle: $r" }
        }
        check(PasswordRules.generate(PasswordRules.fromBundle(Bundle().apply { putInt("length", 24) })).password.length == 24)
        passed("rules-parse-chinese-english-attributes-and-generate-within-them")

        // ---------- 页面识别（S10 / 注册 / 改密码 / 验证码） ----------
        fun f(id: Int, label: String = "", type: Int = TEXT, nearby: String = "", ac: String = "", html: String = "", attrs: Map<String, String> = emptyMap(), value: String = "") =
            PageField(id, label = label, inputType = type, nearby = nearby, autocomplete = ac, htmlType = html, htmlAttrs = attrs, value = value)
        Page("com.example", "", listOf(f(1, "请输入手机号"), f(2, "请输入密码", PWD)),
            listOf(PageText("添加账号"), PageText("未注册手机号验证通过后将自动注册"), PageText("登录", true), PageText("忘记密码"))).let { p ->
            check(p.kind == PageKind.LOGIN && p.types == listOf(FieldType.PHONE, FieldType.PASSWORD) && p.newPassword == null && p.loginPassword?.id == 2) { "S10 ${p.kind} ${p.types}" }
        }
        val singlePassword = listOf(f(1, "手机号"), f(2, "密码", PWD))
        for (texts in listOf(listOf(PageText("注册", true)), listOf(PageText("注册个人版"), PageText("下一步", true)))) {
            val page = Page("com.example", "", singlePassword, texts)
            check(page.kind == PageKind.REGISTER && page.newPassword?.id == 2 && page.loginPassword == null)
        }
        Page("com.example", "", singlePassword, listOf(PageText("登录", true), PageText("快速注册", true))).let { p ->
            check(p.kind == PageKind.LOGIN && p.newPassword == null)
        }
        check(Page.isRegistrationScreen("com.example.RegisterPasswordActivity"))
        check(!Page.isRegistrationScreen("com.example.LoginActivity"))
        check(Page("com.example", "", singlePassword, emptyList(), registrationScreen = true).newPassword?.id == 2)
        Page("com.example", "", listOf(f(1, "用户名"), f(2, "设置密码", PWD, nearby = "8-16位，须包含字母和数字"), f(3, "确认密码", PWD)),
            listOf(PageText("注册", true))).let { p ->
            check(p.kind == PageKind.REGISTER && p.newPassword?.id == 2 && p.confirm?.id == 3 && p.account?.id == 1) { "register ${p.kind} ${p.types}" }
            check(p.rules().minLength == 8 && p.rules().maxLength == 16) { "register rules ${p.rules()}" }
        }
        Page("com.example", "", listOf(f(1, "邮箱"), f(2, "", PWD), f(3, "", PWD)), listOf(PageText("下一步", true))).let { p ->
            check(p.types == listOf(FieldType.EMAIL, FieldType.NEW_PASSWORD, FieldType.CONFIRM) && p.kind == PageKind.REGISTER) { "unlabelled pair ${p.types}" }
        }
        Page("com.example", "", listOf(f(1, "原密码", PWD), f(2, "新密码", PWD), f(3, "确认新密码", PWD)), listOf(PageText("修改密码"))).let { p ->
            check(p.kind == PageKind.CHANGE && p.loginPassword?.id == 1 && p.newPassword?.id == 2 && p.confirm?.id == 3) { "change ${p.types}" }
        }
        check(Page.classify(f(1, "短信验证码", PWD)) == FieldType.OTP)
        Page("site", "example.com", listOf(
            f(1, "email", html = "email"),
            f(2, "", html = "password", ac = "section-registration new-password", attrs = mapOf("passwordrules" to "minlength: 10; maxlength: 12; required: upper; required: digit")),
        ), emptyList()).let { p ->
            check(p.types == listOf(FieldType.EMAIL, FieldType.NEW_PASSWORD) && p.kind == PageKind.REGISTER) { "html ${p.types}" }
            check(p.rules().maxLength == 12 && CharClass.UPPER in p.rules().require)
        }
        Page("com.example", "", listOf(f(1), f(2, "", PWD)), emptyList()).let { p -> check(p.types == listOf(FieldType.USERNAME, FieldType.PASSWORD)) { "fallback ${p.types}" } }
        // 同页的账号、手机号不能出现在生成的密码里
        Page("com.example", "", listOf(f(1, "手机号", value = "13800001234"), f(2, "新密码", PWD), f(3, "确认密码", PWD)), emptyList()).let { p ->
            check(p.rules().avoid == listOf("13800001234"))
        }
        passed("page-kind-login-register-change-otp-html-and-fallback")

        // ---------- 接口 v1.1 ----------
        val root = File(context.filesDir, "v33-${System.nanoTime()}").apply { mkdirs() }
        val fixture = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val uri = Uri.parse("content://${context.packageName}.vault")
        fun call(method: String, extras: Bundle = Bundle()) = context.contentResolver.call(uri, method, null, extras) ?: error("null bundle for $method")
        fun Bundle.status() = getString("status")
        @Suppress("DEPRECATION") fun Bundle.entries() = getParcelableArrayList<Bundle>("entries").orEmpty()
        fun field(type: String, value: String) = Bundle().apply { putString("type", type); putString("value", value) }
        val self = context.packageName
        val realCert = AppCerts.of(context, self) ?: error("own cert unreadable")
        try {
            main { Settings.init(fixture); Repository.init(fixture); VaultAccess.lock(); Settings.update { it.copy(vaultFill = true) } }

            call("status").let { s ->
                @Suppress("DEPRECATION") val features = s.getStringArrayList("features").orEmpty()
                check(s.getInt("version") == 1 && features.containsAll(listOf("generate.rules", "save.fields", "match.cert")) && s.containsKey("autofill")) { "features $features" }
            }
            call("generate", Bundle().apply { putString("ruleText", "密码 8–16 位，须包含字母和数字") }).let { g ->
                check(g.status() == "ok" && g.getString("summary") == "16 位 · 字母+数字" && g.getBoolean("satisfied") && g.getString("password")!!.all { it.isLetterOrDigit() })
            }
            passed("status-features-and-generate-with-rule-text")

            // 注册：多字段保存（账号取用户名 → 手机号 → 邮箱），带上要求
            val reg = call("save", Bundle().apply {
                putString("package", "com.example.shop"); putString("password", "Reg#Pass1"); putString("kind", "register"); putString("rules", "minlength: 8")
                putParcelableArrayList("fields", arrayListOf(field("phone", "13800001234"), field("email", "bob@example.com")))
            })
            check(reg.status() == "ok" && reg.getString("result") == "created" && reg.getInt("fields") == 1) { "register save $reg" }
            VaultStore.get(reg.getString("id")!!)!!.let { e ->
                check(e.account == "13800001234" && e.field("邮箱") == "bob@example.com" && e.passwordRules == "minlength: 8" && e.apps == listOf("com.example.shop")) { "saved $e" }
            }
            // 改密码：同一个 App 下当前密码对得上的那一条被更新，旧密码保留
            val changed = call("save", Bundle().apply {
                putString("package", "com.example.shop"); putString("password", "New#Pass2"); putString("kind", "change"); putString("currentPassword", "Reg#Pass1")
            })
            check(changed.getString("result") == "updated" && changed.getString("id") == reg.getString("id")) { "change $changed" }
            VaultStore.get(reg.getString("id")!!)!!.let { e -> check(e.password == "New#Pass2" && e.fields.any { it.secret && it.label.startsWith("旧密码") && it.value == "Reg#Pass1" }) }
            passed("save-fields-register-and-change-password")

            // 签名核对：记过且不同 → 不推荐、reveal 拒绝；没记过 → 推荐；bind 补记证书
            val bad = VaultEntry(type = VaultType.PASSWORD, name = "Fake", account = "a1", password = "x", apps = listOf(self), appCerts = mapOf(self to "00"))
            val good = VaultEntry(type = VaultType.PASSWORD, name = "Good", account = "a2", password = "y", apps = listOf(self), appCerts = mapOf(self to realCert))
            val legacy = VaultEntry(type = VaultType.PASSWORD, name = "Legacy", account = "a3", password = "z", apps = listOf(self))
            main { listOf(bad, good, legacy).forEach { VaultStore.save(it) } }
            call("match", Bundle().apply { putString("package", self) }).entries().let { list ->
                check(list.map { it.getString("id") }.toSet() == setOf(good.id, legacy.id)) { "v1 caller must not see mismatched: ${list.map { it.getString("name") }}" }
            }
            call("match", Bundle().apply { putString("package", self); putStringArrayList("features", arrayListOf("match.cert")) }).entries().let { list ->
                check(list.last().getString("id") == bad.id && list.last().getString("matchedBy") == "cert_mismatch" && list.count { it.getString("matchedBy") == "package" } == 2) { "aware caller" }
            }
            check(call("reveal", Bundle().apply { putString("id", bad.id); putString("package", self) }).status() == "cert_mismatch")
            check(call("reveal", Bundle().apply { putString("id", bad.id); putString("package", self); putBoolean("confirmCert", true) }).status() == "locked")
            check(call("bind", Bundle().apply { putString("id", legacy.id); putString("package", self) }).status() == "ok")
            check(VaultStore.get(legacy.id)!!.appCerts[self] == realCert) { "bind records cert" }
            // 移除 App 时证书一起去掉
            check(VaultStore.get(legacy.id)!!.copy(apps = emptyList()).normalized().appCerts.isEmpty())
            passed("cert-recorded-on-bind-mismatch-hidden-from-v1-and-blocked-on-reveal")

            // 新字段随加密备份走，旧备份没有它们也能读
            val sealed = VaultStore.seal(VaultStore.entries.value, "v33-pw")
            VaultStore.open(sealed, "v33-pw").first { it.id == good.id }.let { check(it.appCerts[self] == realCert) }
            passed("new-fields-survive-encrypted-backup")
        } finally {
            main { VaultAccess.lock(); Settings.update { it.copy(vaultFill = false) }; Settings.init(context); Repository.init(context) }
        }
    }
}
