package com.kongye.docs.autofill

import android.text.InputType
import com.kongye.docs.vault.PasswordRules

/** 字段类型（需求文档 v2 第 5.1 节） */
enum class FieldType(val key: String) {
    USERNAME("username"), PHONE("phone"), EMAIL("email"),
    PASSWORD("password"), NEW_PASSWORD("new"), CONFIRM("confirm"), CURRENT("current"),
    OTP("otp"), OTHER("other");

    val isAccount get() = this == USERNAME || this == PHONE || this == EMAIL
    val isPassword get() = this == PASSWORD || this == NEW_PASSWORD || this == CONFIRM || this == CURRENT
}

enum class PageKind { LOGIN, REGISTER, CHANGE }

/**
 * 页面上一个可输入的框。label 是能找到的所有说明文字（提示、placeholder、id、无障碍描述、
 * aria-label、name，以及紧挨在它前面的文字）；nearby 是紧挨在它后面的文字（密码要求常写在这里）。
 */
data class PageField<T>(
    val id: T,
    val hints: List<String> = emptyList(),
    val htmlType: String = "",
    val autocomplete: String = "",
    val inputType: Int = 0,
    val label: String = "",
    val nearby: String = "",
    val htmlAttrs: Map<String, String> = emptyMap(),
    val focused: Boolean = false,
    val value: String = "",
) {
    val passwordInput: Boolean get() {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return htmlType == "password" ||
            cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
            hints.any { it.contains("password") }
    }
}

/** 页面上的一段文字；button = 按钮上的字 */
data class PageText(val text: String, val button: Boolean = false)

/** 识别结果：每个框的类型、页面类型、新密码框的要求 */
class Page<T>(val pkg: String, val domain: String, val fields: List<PageField<T>>, val texts: List<PageText>) {
    val types: List<FieldType> = classifyAll(fields)
    val kind: PageKind = kindOf(types, texts)
    val score: Int = registerScore(types, texts)

    fun first(vararg t: FieldType): PageField<T>? = fields.indices.firstOrNull { types[it] in t }?.let { fields[it] }
    fun all(vararg t: FieldType): List<PageField<T>> = fields.indices.filter { types[it] in t }.map { fields[it] }
    fun typeOf(f: PageField<T>): FieldType = types[fields.indexOf(f)]

    /** 账号框：用户名 / 手机号 / 邮箱里最靠前的 */
    val account: PageField<T>? get() = first(FieldType.USERNAME, FieldType.PHONE, FieldType.EMAIL)
    /** 登录用的密码框：改密码页是当前密码框，其他是普通密码框 */
    val loginPassword: PageField<T>? get() = if (kind == PageKind.CHANGE) first(FieldType.CURRENT) ?: first(FieldType.PASSWORD) else first(FieldType.PASSWORD, FieldType.CURRENT)
    /** 新密码框：注册 / 改密码页才有；注册页没标"新密码"时用第一个密码框 */
    val newPassword: PageField<T>? get() = when (kind) {
        PageKind.LOGIN -> null
        else -> first(FieldType.NEW_PASSWORD) ?: if (kind == PageKind.REGISTER) first(FieldType.PASSWORD) else null
    }
    val confirm: PageField<T>? get() = first(FieldType.CONFIRM)

    /** 新密码框的要求：网页属性 + 提示文字 + 附近文字（第 6.1 节） */
    fun rules(): PasswordRules {
        val f = newPassword ?: return PasswordRules()
        val attrs = f.htmlAttrs
        val fromAttrs = PasswordRules(
            minLength = attrs["minlength"]?.trim()?.toIntOrNull()?.takeIf { it in 1..128 },
            maxLength = attrs["maxlength"]?.trim()?.toIntOrNull()?.takeIf { it in 1..128 },
            pattern = attrs["pattern"]?.takeIf { it.isNotBlank() },
        )
        val apple = attrs["passwordrules"]?.let { PasswordRules.parseAppleRules(it) } ?: PasswordRules()
        val text = PasswordRules.parse(ruleText())
        val avoid = all(FieldType.USERNAME, FieldType.PHONE, FieldType.EMAIL).map { it.value.trim() }.filter { it.length >= 3 }
        return text.merge(apple).merge(fromAttrs).merge(PasswordRules(avoid = avoid))
    }

    /** 新密码框的说明文字和它后面的文字（交给 PasswordRules.parse） */
    fun ruleText(): String {
        val f = newPassword ?: return ""
        return listOf(f.label, f.nearby, confirm?.nearby.orEmpty()).filter { it.isNotBlank() }.joinToString("\n").take(1200)
    }

    companion object {
        private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
        private val OTP = re("""验证码|校验码|动态码|动态密码|短信码|captcha|otp|verif(?:y|ication)?\s*code|one.?time""")
        private val CONFIRM = re("""确认|再次|重复|再输|confirm|repeat|again|re-?enter|retype|verify\s*password""")
        private val NEW = re("""新密码|设置密码|设定密码|创建密码|设置.{0,4}密码|new\s*-?password|create\s*-?password|choose\s+a\s+password|set\s*-?password""")
        private val CURRENT = re("""原密码|旧密码|当前密码|current\s*-?password|old\s*-?password|existing\s*password""")
        private val PHONE = re("""手机|电话|mobile|phone|\btel\b|cellphone""")
        private val EMAIL = re("""邮箱|e-?mail|\bmail\b""")
        private val USER = re("""账号|帐号|账户|帐户|用户名|登录名|会员名|昵称|user\s*-?name|\buser\b|account|login\s*-?id|\blogin\b|身份证""")

        fun <T> classify(f: PageField<T>): FieldType {
            val label = f.label
            val hints = f.hints.map { it.lowercase() }
            val ac = f.autocomplete.lowercase()
            val pwd = f.passwordInput
            val cls = f.inputType and InputType.TYPE_MASK_CLASS
            val variation = f.inputType and InputType.TYPE_MASK_VARIATION
            return when {
                hints.any { "otp" in it || "onetimecode" in it || "smsotp" in it } || ac == "one-time-code" || OTP.containsMatchIn(label) -> FieldType.OTP
                pwd && CONFIRM.containsMatchIn(label) -> FieldType.CONFIRM
                hints.any { "newpassword" in it || "new-password" in it } || ac == "new-password" || pwd && NEW.containsMatchIn(label) -> FieldType.NEW_PASSWORD
                ac == "current-password" || pwd && CURRENT.containsMatchIn(label) -> FieldType.CURRENT
                pwd -> FieldType.PASSWORD
                hints.any { "phone" in it } || f.htmlType == "tel" || ac.startsWith("tel") || cls == InputType.TYPE_CLASS_PHONE || PHONE.containsMatchIn(label) -> FieldType.PHONE
                hints.any { "email" in it } || f.htmlType == "email" || ac == "email" ||
                    cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) ||
                    EMAIL.containsMatchIn(label) -> FieldType.EMAIL
                hints.any { "username" in it } || ac == "username" || USER.containsMatchIn(label) -> FieldType.USERNAME
                else -> FieldType.OTHER
            }
        }

        /** 逐个分类，再按整页修正：没标的连续密码框、没有明确账号框时的兜底 */
        fun <T> classifyAll(fields: List<PageField<T>>): List<FieldType> {
            val t = fields.map { classify(it) }.toMutableList()
            val plain = t.indices.filter { t[it] == FieldType.PASSWORD }
            val labelled = t.count { it == FieldType.NEW_PASSWORD || it == FieldType.CONFIRM || it == FieldType.CURRENT }
            if (labelled == 0) when (plain.size) {
                2 -> { t[plain[0]] = FieldType.NEW_PASSWORD; t[plain[1]] = FieldType.CONFIRM }
                3 -> { t[plain[0]] = FieldType.CURRENT; t[plain[1]] = FieldType.NEW_PASSWORD; t[plain[2]] = FieldType.CONFIRM }
            } else if (plain.size == 1 && t.contains(FieldType.CONFIRM) && !t.contains(FieldType.NEW_PASSWORD)) {
                // "密码" + "确认密码"：前一个就是新密码
                t[plain[0]] = FieldType.NEW_PASSWORD
            } else if (plain.size == 1 && t.contains(FieldType.NEW_PASSWORD) && !t.contains(FieldType.CURRENT)) {
                // "原密码"没认出来，但"新密码"认出来了：剩下那个在新密码前面的就是当前密码
                val newAt = t.indexOf(FieldType.NEW_PASSWORD)
                if (plain[0] < newAt) t[plain[0]] = FieldType.CURRENT
            }
            if (t.none { it.isAccount }) {
                val firstPwd = t.indexOfFirst { it.isPassword }
                if (firstPwd > 0) {
                    val before = (firstPwd - 1 downTo 0).firstOrNull { t[it] == FieldType.OTHER }
                    if (before != null) t[before] = FieldType.USERNAME
                }
            }
            return t
        }

        private val SUBMIT_REGISTER = re("""^(?:立即|免费|快速)?注册(?:账号|帐号)?$|创建(?:账号|帐号|账户)|完成注册|^sign\s*up$|^register$|create\s+(?:an\s+)?account|^join$""")
        private val TITLE_REGISTER = re("""注册|创建(?:账号|帐号|账户)|新用户|sign\s*up|register|join\s+now""")
        private val FORGOT = re("""忘记密码|找回密码|forgot\s*(?:your\s+)?password""")
        private val SUBMIT_LOGIN = re("""^登\s*录$|^log\s*in$|^sign\s*in$|^登录/注册$""")
        private val CHANGE_TITLE = re("""修改密码|更改密码|重置密码|设置新密码|change\s+password|reset\s+password""")

        /** 第 5.2 节的打分 */
        fun registerScore(types: List<FieldType>, texts: List<PageText>): Int {
            var s = 0
            if (FieldType.NEW_PASSWORD in types) s += 3
            if (FieldType.CONFIRM in types) s += 3
            if (types.count { it.isPassword } >= 2) s += 2
            if (texts.any { it.button && SUBMIT_REGISTER.containsMatchIn(it.text.trim()) }) s += 2
            if (texts.any { !it.button && it.text.length <= 30 && TITLE_REGISTER.containsMatchIn(it.text) }) s += 1
            if (types.count { it.isPassword } == 1 && texts.any { FORGOT.containsMatchIn(it.text) }) s -= 2
            if (texts.any { it.button && SUBMIT_LOGIN.containsMatchIn(it.text.trim()) }) s -= 2
            return s
        }

        fun kindOf(types: List<FieldType>, texts: List<PageText>): PageKind {
            val change = FieldType.CURRENT in types && (FieldType.NEW_PASSWORD in types || FieldType.CONFIRM in types) ||
                texts.any { it.text.length <= 30 && CHANGE_TITLE.containsMatchIn(it.text) } && types.count { it.isPassword } >= 2
            return when {
                change -> PageKind.CHANGE
                registerScore(types, texts) >= 3 -> PageKind.REGISTER
                else -> PageKind.LOGIN
            }
        }
    }
}
