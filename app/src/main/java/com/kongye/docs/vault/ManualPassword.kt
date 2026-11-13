package com.kongye.docs.vault

import java.security.SecureRandom

/** 用户勾选的字符池互不重叠；勾选易混字符才会加入 10iolIOL。 */
data class ManualPassword(
    val length: Int = 16,
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val ambiguous: Boolean = false,
) {
    val groups: List<String> get() = listOfNotNull(
        "ABCDEFGHJKMNPQRSTUVWXYZ".takeIf { upper },
        "abcdefghjkmnpqrstuvwxyz".takeIf { lower },
        "23456789".takeIf { digits },
        "${'$'}%&^*()[].@#_!".takeIf { symbols },
        "10iolIOL".takeIf { ambiguous },
    )
    fun generate(random: SecureRandom = SecureRandom()): String {
        val groups = groups
        require(length in 4..64 && groups.isNotEmpty() && length >= groups.size)
        val pool = groups.joinToString("")
        val chars = groups.map { it[random.nextInt(it.length)] }.toMutableList()
        repeat(length - chars.size) { chars += pool[random.nextInt(pool.length)] }
        for (i in chars.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val old = chars[i]; chars[i] = chars[j]; chars[j] = old
        }
        return chars.joinToString("")
    }
}

/** 页面范围单独显示，不把默认输出长度说成页面要求。 */
fun PasswordRules.lengthHint(): String = when {
    minLength != null && maxLength != null && minLength == maxLength -> "页面长度：$minLength 位"
    minLength != null && maxLength != null -> "页面长度：$minLength–$maxLength 位"
    minLength != null -> "页面长度：至少 $minLength 位"
    maxLength != null -> "页面长度：最多 $maxLength 位"
    else -> "页面未提供长度范围"
}
