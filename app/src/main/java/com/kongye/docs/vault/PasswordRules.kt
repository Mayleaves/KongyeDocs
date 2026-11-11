package com.kongye.docs.vault

import android.os.Bundle
import java.security.SecureRandom

/** 密码里的一类字符 */
enum class CharClass(val key: String, val label: String) {
    LOWER("lower", "小写"), UPPER("upper", "大写"), DIGIT("digit", "数字"), SYMBOL("symbol", "符号");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key.trim().lowercase() } }
}

/**
 * 注册 / 改密码页上写的密码要求（需求文档 v2 第 6 节）。
 * 字段都是"页面写了什么"；没写的用 null / 空，生成时再取默认。
 */
data class PasswordRules(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** 必须出现的字符类 */
    val require: Set<CharClass> = emptySet(),
    /** 至少几类（"至少包含 3 种"） */
    val minClasses: Int = 0,
    /** 只能用这些字符类；null 表示没限制 */
    val allowed: Set<CharClass>? = null,
    /** 允许的符号；null 表示用默认符号 */
    val symbols: String? = null,
    val forbidSequence: Boolean = false,
    /** 不许出现在密码里的内容（同页的账号、手机号） */
    val avoid: List<String> = emptyList(),
    /** 网页输入框的 pattern */
    val pattern: String? = null,
) {
    val isEmpty get() = this == PasswordRules()

    /** 页面写了要求（不算 avoid）：候选上显示要求摘要 */
    val stated get() = copy(avoid = emptyList()) != PasswordRules()

    /** 两份要求合在一起：数值取更严的，集合取并集 / 交集 */
    fun merge(o: PasswordRules) = PasswordRules(
        minLength = listOfNotNull(minLength, o.minLength).maxOrNull(),
        maxLength = listOfNotNull(maxLength, o.maxLength).minOrNull(),
        require = require + o.require,
        minClasses = maxOf(minClasses, o.minClasses),
        allowed = when { allowed == null -> o.allowed; o.allowed == null -> allowed; else -> allowed intersect o.allowed },
        symbols = o.symbols ?: symbols,
        forbidSequence = forbidSequence || o.forbidSequence,
        avoid = (avoid + o.avoid).distinct(),
        pattern = o.pattern ?: pattern,
    )

    /** 存进条目的格式：仿 passwordrules 属性 */
    fun encode(): String = buildList {
        minLength?.let { add("minlength: $it") }
        maxLength?.let { add("maxlength: $it") }
        require.sortedBy { it.ordinal }.forEach { add("required: ${it.key}") }
        if (minClasses > 0) add("min-classes: $minClasses")
        allowed?.sortedBy { it.ordinal }?.forEach { add("allowed: ${it.key}") }
        symbols?.let { add("symbols: [$it]") }
        if (forbidSequence) add("max-consecutive: 2")
    }.joinToString("; ")

    fun toBundle(b: Bundle = Bundle()) = b.apply {
        minLength?.let { putInt("minLength", it) }
        maxLength?.let { putInt("maxLength", it) }
        if (require.isNotEmpty()) putStringArrayList("require", ArrayList(require.map { it.key }))
        if (minClasses > 0) putInt("minClasses", minClasses)
        allowed?.let { putStringArrayList("allowed", ArrayList(it.map { c -> c.key })) }
        symbols?.let { putString("symbols", it) }
        if (allowed != null && CharClass.SYMBOL !in allowed) putBoolean("noSymbols", true)
        if (forbidSequence) putBoolean("forbidSequence", true)
        if (avoid.isNotEmpty()) putStringArrayList("avoid", ArrayList(avoid))
        pattern?.let { putString("pattern", it) }
    }

    companion object {
        const val DEFAULT_LENGTH = 16
        const val DEFAULT_SYMBOLS = "!@#$%^&*_-+=?"
        private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        private const val DIGITS = "23456789"

        /** 接口传进来的参数（第 7.2 节）；ruleText 是调用方读到的页面文字，交给 parse */
        fun fromBundle(x: Bundle): PasswordRules {
            fun int(k: String) = if (x.containsKey(k)) x.getInt(k).takeIf { it > 0 } else null
            val noSymbols = x.getBoolean("noSymbols")
            val allowedList = x.getStringArrayList("allowed")?.mapNotNull(CharClass::of)?.toSet()
            val allowed = when {
                allowedList != null -> if (noSymbols) allowedList - CharClass.SYMBOL else allowedList
                noSymbols -> setOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT)
                else -> null
            }
            val given = PasswordRules(
                minLength = int("minLength"), maxLength = int("maxLength"),
                require = x.getStringArrayList("require").orEmpty().mapNotNull(CharClass::of).toSet(),
                minClasses = int("minClasses") ?: 0,
                allowed = allowed,
                symbols = x.getString("symbols")?.filter { it.code in 33..126 && !it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() },
                forbidSequence = x.getBoolean("forbidSequence"),
                avoid = x.getStringArrayList("avoid").orEmpty().map { it.trim() }.filter { it.length >= 3 },
                pattern = x.getString("pattern")?.takeIf { it.isNotBlank() },
            )
            // v1 的 length：固定长度
            val fixed = int("length")?.let { PasswordRules(minLength = it, maxLength = it) } ?: PasswordRules()
            val parsed = x.getString("ruleText")?.let { parse(it) } ?: PasswordRules()
            val attrs = x.getString("passwordrules")?.let { parseAppleRules(it) } ?: PasswordRules()
            return parsed.merge(attrs).merge(fixed).merge(given)
        }

        // ---------- 解析页面文字 ----------

        private val CN_DIGITS = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

        /** 中文数字 → 阿拉伯数字（0–99）："十二"→12，"二十"→20，"两"→2 */
        fun normalizeNumbers(text: String): String = Regex("[零〇一二两三四五六七八九十]+").replace(text) { m ->
            val s = m.value
            val n = when {
                s == "十" -> 10
                s.length == 2 && s[0] == '十' -> 10 + (CN_DIGITS[s[1]] ?: return@replace s)
                s.length == 2 && s[1] == '十' -> (CN_DIGITS[s[0]] ?: return@replace s) * 10
                s.length == 3 && s[1] == '十' -> (CN_DIGITS[s[0]] ?: return@replace s) * 10 + (CN_DIGITS[s[2]] ?: return@replace s)
                s.length == 1 -> CN_DIGITS[s[0]] ?: return@replace s
                else -> return@replace s
            }
            n.toString()
        }

        private const val UNIT = """(?:位|个?字符|characters?|chars?)"""
        private val RANGE = Regex("""(\d{1,2})\s*(?:-|~|～|－|—|–|至|到|to)\s*(\d{1,2})\s*$UNIT""", RegexOption.IGNORE_CASE)
        private val MIN = Regex("""(?:至少|不少于|最少|不低于|不小于|at\s+least|minimum(?:\s+of)?|min\.?)\s*(\d{1,2})\s*$UNIT""", RegexOption.IGNORE_CASE)
        private val MAX = Regex("""(?:不超过|最多|最长|不多于|不大于|at\s+most|maximum(?:\s+of)?|max\.?|no\s+more\s+than|up\s+to)\s*(\d{1,2})\s*$UNIT""", RegexOption.IGNORE_CASE)
        private val MIN_AFTER = Regex("""(\d{1,2})\s*$UNIT\s*(?:以上|及以上|或以上|or\s+more)""", RegexOption.IGNORE_CASE)
        private val MAX_AFTER = Regex("""(\d{1,2})\s*$UNIT\s*(?:以内|以下|及以下)""", RegexOption.IGNORE_CASE)
        private val CLASSES_N = Regex("""(?:至少|任意|最少)?\s*(?:包含|包括|含)?\s*([234])\s*种""")
        private val ONLY_DIGITS = Regex("""(?:仅|只)(?:能|可|允许)?(?:是|为|输入|使用|包含|由)?\s*数字(?![和与、及+])|纯数字|digits?\s+only|numeric\s+only|only\s+(?:digits|numbers)""", RegexOption.IGNORE_CASE)
        private val ONLY_ALNUM = Regex("""(?:仅|只)(?:能|支持|允许|可)?(?:由|包含|使用|输入|是|为)?\s*(?:英文)?(?:字母|数字)(?:和|与|、|及|\+|或)(?:英文)?(?:字母|数字)|letters\s+and\s+(?:numbers|digits)\s+only|only\s+letters\s+and\s+(?:numbers|digits)""", RegexOption.IGNORE_CASE)
        private val NO_SYMBOLS = Regex("""不(?:能|可|得|允许|支持)?(?:包含|使用|含有?|输入|带)\s*(?:任何)?(?:特殊字符|特殊符号|符号|标点)|不含(?:特殊字符|特殊符号|符号)|no\s+(?:special\s+characters|symbols)""", RegexOption.IGNORE_CASE)
        private val NO_SEQUENCE = Regex("""不(?:能|可|得|允许)?(?:包含|使用|含有?|有|是)?\s*(?:\d\s*位以上)?(?:连续|重复)|连续(?:相同|重复)|consecutive|repeated|sequential""", RegexOption.IGNORE_CASE)
        private val SYMBOL_LIST = Regex("""(?:特殊字符|特殊符号|符号)\s*(?:包括|为|仅限|支持|可用|限|如|：|:)\s*[：:]?\s*([!-/:-@\[-`{-~]{1,32})""")
        private val BOTH_CASES = Regex("""大小写|大写.{0,6}小写|小写.{0,6}大写|upper\s*(?:case)?\s*(?:and|&|,)\s*lower\s*(?:case)?|lower\s*(?:case)?\s*(?:and|&|,)\s*upper\s*(?:case)?|mixed\s+case""", RegexOption.IGNORE_CASE)
        private val UPPER_WORD = Regex("""大写|upper\s*case|uppercase|capital""", RegexOption.IGNORE_CASE)
        private val LOWER_WORD = Regex("""小写|lower\s*case|lowercase""", RegexOption.IGNORE_CASE)
        private val LETTER_WORD = Regex("""字母|letters?|alphabet""", RegexOption.IGNORE_CASE)
        private val DIGIT_WORD = Regex("""数字|numbers?|digits?|numeric""", RegexOption.IGNORE_CASE)
        private val SYMBOL_WORD = Regex("""特殊字符|特殊符号|符号|标点|special\s+characters?|symbols?|punctuation""", RegexOption.IGNORE_CASE)
        private val MUST = Regex("""须|必须|需要|需|要|包含|包括|含有|组合|混合|must|require|include|contain|at\s+least\s+one""", RegexOption.IGNORE_CASE)

        /** 第 6.2 节：从提示文字、附近文字、报错文字解析 */
        fun parse(raw: String): PasswordRules {
            val text = normalizeNumbers(raw).take(2000)
            if (text.isBlank()) return PasswordRules()
            var min: Int? = null
            var max: Int? = null
            RANGE.find(text)?.let { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                min = minOf(a, b); max = maxOf(a, b)
            }
            if (min == null) min = (MIN.find(text) ?: MIN_AFTER.find(text))?.groupValues?.get(1)?.toInt()
            if (max == null) max = (MAX.find(text) ?: MAX_AFTER.find(text))?.groupValues?.get(1)?.toInt()

            // 禁用符号的句子先拿掉，免得"不能包含特殊字符"被当成"必须有符号"
            val noSymbols = NO_SYMBOLS.containsMatchIn(text)
            val positive = NO_SYMBOLS.replace(text, " ")
            val mentioned = linkedSetOf<CharClass>()
            when {
                BOTH_CASES.containsMatchIn(positive) -> { mentioned += CharClass.LOWER; mentioned += CharClass.UPPER }
                else -> {
                    if (UPPER_WORD.containsMatchIn(positive)) mentioned += CharClass.UPPER
                    if (LOWER_WORD.containsMatchIn(positive)) mentioned += CharClass.LOWER
                    if (LETTER_WORD.containsMatchIn(positive) && CharClass.UPPER !in mentioned && CharClass.LOWER !in mentioned) mentioned += CharClass.LOWER
                }
            }
            if (DIGIT_WORD.containsMatchIn(positive.replace(RANGE, " ").replace(MIN, " ").replace(MAX, " "))) mentioned += CharClass.DIGIT
            if (!noSymbols && SYMBOL_WORD.containsMatchIn(positive)) mentioned += CharClass.SYMBOL

            val minClasses = CLASSES_N.find(text)?.groupValues?.get(1)?.toInt() ?: 0
            val require = when {
                minClasses > 0 -> emptySet()          // "任意两种"：由生成器挑
                MUST.containsMatchIn(positive) -> mentioned
                else -> mentioned
            }
            val allowed: Set<CharClass>? = when {
                ONLY_DIGITS.containsMatchIn(text) -> setOf(CharClass.DIGIT)
                ONLY_ALNUM.containsMatchIn(text) || noSymbols -> setOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT)
                // 写了字符类、但没提符号：不用符号（很多网站不接受没说明的符号）
                mentioned.isNotEmpty() && CharClass.SYMBOL !in mentioned -> setOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT)
                else -> null
            }
            val symbols = SYMBOL_LIST.find(text)?.groupValues?.get(1)?.filter { it.code in 33..126 && !it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
            return PasswordRules(
                minLength = min?.takeIf { it in 1..128 }, maxLength = max?.takeIf { it in 1..128 },
                require = require, minClasses = minClasses, allowed = allowed,
                symbols = if (allowed != null && CharClass.SYMBOL !in allowed) null else symbols,
                forbidSequence = NO_SEQUENCE.containsMatchIn(text),
            )
        }

        /** 网页输入框的 passwordrules 属性：minlength: 8; maxlength: 20; required: lower; required: upper; allowed: [-_]; max-consecutive: 2 */
        fun parseAppleRules(raw: String): PasswordRules {
            var min: Int? = null; var max: Int? = null
            val required = linkedSetOf<CharClass>(); val allowed = linkedSetOf<CharClass>()
            var symbols = ""
            var consecutive = false
            raw.split(';').map { it.trim() }.filter { it.contains(':') }.forEach { part ->
                val key = part.substringBefore(':').trim().lowercase()
                val value = part.substringAfter(':').trim()
                fun classes(): List<CharClass> = value.split(',').map { it.trim().lowercase() }.flatMap { v ->
                    when {
                        v == "lower" -> listOf(CharClass.LOWER)
                        v == "upper" -> listOf(CharClass.UPPER)
                        v == "digit" -> listOf(CharClass.DIGIT)
                        v == "special" -> listOf(CharClass.SYMBOL)
                        v == "ascii-printable" || v == "unicode" -> CharClass.entries
                        v.startsWith("[") && v.endsWith("]") -> {
                            val set = v.removeSurrounding("[", "]")
                            symbols += set.filter { !it.isLetterOrDigit() }
                            buildList {
                                if (set.any { it.isLowerCase() }) add(CharClass.LOWER)
                                if (set.any { it.isUpperCase() }) add(CharClass.UPPER)
                                if (set.any { it.isDigit() }) add(CharClass.DIGIT)
                                if (set.any { !it.isLetterOrDigit() }) add(CharClass.SYMBOL)
                            }
                        }
                        else -> emptyList()
                    }
                }
                when (key) {
                    "minlength" -> min = value.toIntOrNull()
                    "maxlength" -> max = value.toIntOrNull()
                    "required" -> required += classes()
                    "allowed" -> allowed += classes()
                    "max-consecutive" -> consecutive = true
                }
            }
            val all = required + allowed
            return PasswordRules(
                minLength = min, maxLength = max, require = required,
                allowed = all.takeIf { it.isNotEmpty() },
                symbols = symbols.takeIf { it.isNotEmpty() && CharClass.SYMBOL in all }?.toSet()?.joinToString(""),
                forbidSequence = consecutive,
            )
        }

        // ---------- 生成 ----------

        data class Generated(val password: String, val summary: String, val satisfied: Boolean)

        /** 第 7.2 节的生成规则；只用 SecureRandom */
        fun generate(rules: PasswordRules, rnd: SecureRandom = SecureRandom()): Generated {
            val pool = (rules.allowed ?: CharClass.entries.toSet()).ifEmpty { CharClass.entries.toSet() }
            val min = (rules.minLength ?: 8).coerceIn(1, 128)
            val max = (rules.maxLength ?: 64).coerceIn(min, 128)
            val required = linkedSetOf<CharClass>().apply {
                addAll(rules.require.filter { it in pool })
                // 没写必须有哪些：池子里每类都放一个，最强
                if (rules.require.isEmpty()) addAll(pool.sortedBy { it.ordinal })
                for (c in listOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT, CharClass.SYMBOL)) if (size < rules.minClasses && c in pool) add(c)
            }
            val length = DEFAULT_LENGTH.coerceIn(min, max).coerceAtLeast(required.size)
            val onlyDigits = pool == setOf(CharClass.DIGIT)
            fun chars(c: CharClass) = when (c) {
                CharClass.LOWER -> LOWER
                CharClass.UPPER -> UPPER
                CharClass.DIGIT -> if (onlyDigits) "0123456789" else DIGITS
                CharClass.SYMBOL -> rules.symbols ?: DEFAULT_SYMBOLS
            }
            val all = pool.sortedBy { it.ordinal }.joinToString("") { chars(it) }
            val pattern = rules.pattern?.let { runCatching { Regex(it) }.getOrNull() }
            val avoid = rules.avoid.map { it.lowercase() }.filter { it.length >= 3 }
            fun ok(p: String): Boolean {
                if (rules.forbidSequence && hasRun(p)) return false
                val low = p.lowercase()
                if (avoid.any { a -> (0..a.length - 3).any { i -> low.contains(a.substring(i, i + 3)) } }) return false
                if (pattern != null && !pattern.matches(p)) return false
                return true
            }
            var last = ""
            repeat(50) {
                val list = (required.map { c -> chars(c).let { s -> s[rnd.nextInt(s.length)] } } +
                    List(length - required.size) { all[rnd.nextInt(all.length)] }).toMutableList()
                for (i in list.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = list[i]; list[i] = list[j]; list[j] = t }
                last = list.joinToString("")
                if (ok(last)) return Generated(last, summary(length, required, pool), true)
            }
            return Generated(last, summary(length, required, pool), false)
        }

        /** 3 个相同字符，或 abc / 123 / cba 这样的连续 */
        fun hasRun(p: String): Boolean {
            for (i in 0..p.length - 3) {
                val a = p[i]; val b = p[i + 1]; val c = p[i + 2]
                if (a == b && b == c) return true
                if (a.isLetterOrDigit() && b.isLetterOrDigit() && c.isLetterOrDigit()) {
                    val d1 = b.lowercaseChar() - a.lowercaseChar(); val d2 = c.lowercaseChar() - b.lowercaseChar()
                    if (d1 == d2 && (d1 == 1 || d1 == -1)) return true
                }
            }
            return false
        }

        /** "16 位 · 字母+数字"、"12 位 · 大写+数字" */
        fun summary(length: Int, required: Set<CharClass>, pool: Set<CharClass>): String {
            val set = required.ifEmpty { pool }
            val parts = buildList {
                val up = CharClass.UPPER in set; val low = CharClass.LOWER in set
                when {
                    up && low -> add("字母")
                    up -> add("大写")
                    low -> add("字母")
                }
                if (CharClass.DIGIT in set) add("数字")
                if (CharClass.SYMBOL in set) add("符号")
            }
            return if (parts.isEmpty()) "$length 位" else "$length 位 · ${parts.joinToString("+")}"
        }
    }
}
