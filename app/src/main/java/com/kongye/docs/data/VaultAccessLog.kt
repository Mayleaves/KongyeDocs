package com.kongye.docs.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次对外接口访问：谁（调用方包名）、做了什么（方法）、在哪个 App / 网站里、哪一条、结果。
 * 不记密码、不记账号。
 */
@Serializable
data class VaultAccessRecord(
    val time: Long = System.currentTimeMillis(),
    val caller: String,
    val method: String,
    /** 目标 App 包名（键盘 / 悬浮球正在填的那个 App），可为空 */
    val target: String = "",
    val domain: String = "",
    /** 涉及的条目名称（reveal / save / bind），可为空 */
    val entry: String = "",
    /** 返回的 status：ok / locked / disabled / denied / not_found / error */
    val status: String,
)

@Serializable
private data class VaultAccessFile(val records: List<VaultAccessRecord> = emptyList())

/** 密码空间的访问记录：只保留最近 50 条，和密码空间放在同一个目录，不进备份 */
object VaultAccessLog {
    const val LIMIT = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: File? = null
    private val _records = MutableStateFlow<List<VaultAccessRecord>>(emptyList())
    /** 新的在前 */
    val records: StateFlow<List<VaultAccessRecord>> = _records.asStateFlow()

    @Synchronized
    fun init(dir: File) {
        val f = File(dir, "access.json")
        file = f
        _records.value = runCatching {
            if (!f.exists() && !File(dir, "access.json.bak").exists()) emptyList()
            else DurableFiles.read(f) { json.decodeFromString<VaultAccessFile>(it).records }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(record: VaultAccessRecord) {
        val next = (listOf(record) + _records.value).take(LIMIT)
        _records.value = next
        file?.let { f -> runCatching { DurableFiles.write(f, json.encodeToString(VaultAccessFile(next))) } }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        file?.let { f -> runCatching { DurableFiles.write(f, json.encodeToString(VaultAccessFile())) } }
    }
}
