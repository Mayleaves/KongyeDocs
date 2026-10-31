package com.kongye.docs.data

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 动态密码（TOTP，RFC 6238）：Base32 密钥、otpauth:// 链接解析和验证码计算 */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val algorithms = listOf("SHA1", "SHA256", "SHA512")

    /** 网站给的密钥常带空格、短横线或末尾的 =，统一去掉并转成大写 */
    fun normalize(secret: String) = secret.uppercase().filterNot { it.isWhitespace() || it == '-' }.trimEnd('=')

    fun decodeBase32(secret: String): ByteArray {
        val s = normalize(secret)
        require(s.isNotEmpty()) { "请填写密钥" }
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "密钥里有无效字符「$c」，只能是字母 A–Z 和数字 2–7" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray().also { require(it.isNotEmpty()) { "密钥太短" } }
    }

    fun code(key: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        val mac = Mac.getInstance("Hmac" + algorithm.uppercase())
        mac.init(SecretKeySpec(key, "RAW"))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h.last().toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** 某个动态密码在 time（毫秒）时刻的验证码 */
    fun code(entry: VaultEntry, time: Long = System.currentTimeMillis()): String =
        code(decodeBase32(entry.secret), time / 1000 / entry.period, entry.digits, entry.algorithm)

    /** 当前验证码还能用几秒 */
    fun remaining(period: Int, time: Long = System.currentTimeMillis()): Int = period - ((time / 1000) % period).toInt()

    /** 解析 otpauth://totp/发行方:账号?secret=…&issuer=…&digits=6&period=30&algorithm=SHA1 */
    fun parse(text: String): VaultEntry {
        val s = text.trim()
        require(s.startsWith("otpauth://", ignoreCase = true)) { "不是 otpauth:// 开头的动态密码链接" }
        val rest = s.substring("otpauth://".length)
        val type = rest.substringBefore('/').lowercase()
        require(type == "totp") { if (type == "hotp") "暂不支持计数器型（HOTP）动态密码" else "不支持的动态密码类型" }
        val pathAndQuery = rest.substringAfter('/', "")
        val label = decode(pathAndQuery.substringBefore('?'))
        val params = pathAndQuery.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', "")) }
        val secret = normalize(params["secret"].orEmpty())
        decodeBase32(secret)
        val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
        val account = (if (':' in label) label.substringAfter(':') else label).trim()
        val name = params["issuer"].orEmpty().trim().ifEmpty { labelIssuer }.ifEmpty { account }.ifEmpty { "动态密码" }
        val digits = params["digits"]?.toIntOrNull() ?: 6
        require(digits in 6..8) { "不支持 $digits 位验证码" }
        val period = params["period"]?.toIntOrNull() ?: 30
        require(period in 10..300) { "不支持 $period 秒的刷新周期" }
        val algorithm = (params["algorithm"] ?: "SHA1").uppercase()
        require(algorithm in algorithms) { "不支持 $algorithm 算法" }
        return VaultEntry(
            type = VaultType.OTP, name = name, account = if (account == name) "" else account,
            secret = secret, digits = digits, period = period, algorithm = algorithm,
        )
    }

    // 标签里的空格写作 %20；“+”按字面保留
    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
