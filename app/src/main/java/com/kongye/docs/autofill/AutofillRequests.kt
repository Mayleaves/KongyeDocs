package com.kongye.docs.autofill

import android.os.SystemClock
import com.kongye.docs.vault.VaultApi
import java.security.SecureRandom

/**
 * 系统交来的"保存"请求先放在内存里，确认页只拿一个随机 token，
 * 密码不经过 Intent 传来传去。5 分钟没确认就丢掉；进程被回收也随之丢掉。
 */
object PendingSaves {
    private const val TTL = 5 * 60_000L
    private class Item(val input: VaultApi.SaveInput, val until: Long)
    private val items = HashMap<String, Item>()
    private val rnd = SecureRandom()

    @Synchronized
    fun put(input: VaultApi.SaveInput): String {
        sweep()
        val token = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        items[token] = Item(input, SystemClock.elapsedRealtime() + TTL)
        return token
    }

    @Synchronized
    fun peek(token: String): VaultApi.SaveInput? { sweep(); return items[token]?.input }

    @Synchronized
    fun take(token: String): VaultApi.SaveInput? { sweep(); return items.remove(token)?.input }

    private fun sweep() {
        val now = SystemClock.elapsedRealtime()
        items.entries.removeAll { it.value.until <= now }
    }
}
