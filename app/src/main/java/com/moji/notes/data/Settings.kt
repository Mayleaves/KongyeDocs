package com.moji.notes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 深色模式：跟随系统 / 一直浅色 / 一直深色 / 按时间段切换 */
@Serializable
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色模式"), DARK("深色模式"), SCHEDULE("定时切换") }

/** 自动备份：本地文件夹与 WebDAV（云端）各自开关、各自的备份频率和保留时长 */
@Serializable
data class BackupSettings(
    /** 本地：距上次备份满多少小时再备份 */
    val intervalHours: Int = 24,
    /** 本地：自动备份文件保留多少天，0 表示永久保留 */
    val keepDays: Int = 30,
    /** 云端：备份频率和保留时长 */
    val davIntervalHours: Int = 24,
    val davKeepDays: Int = 30,
    val localEnabled: Boolean = false,
    /** 用户选择的文件夹（SAF 树形 URI，已获得长期读写权限） */
    val localTree: String = "",
    val localName: String = "",
    val lastLocal: Long = 0L,
    val localError: String = "",
    val davEnabled: Boolean = false,
    val davUrl: String = "",
    val davUser: String = "",
    val davPassword: String = "",
    val davDir: String = "空叶文档备份",
    val lastDav: Long = 0L,
    val davError: String = "",
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** 定时切换：每天 darkFrom 到 darkTo（一天中的第几分钟）用深色，可以跨过午夜 */
    val darkFrom: Int = 22 * 60,
    val darkTo: Int = 7 * 60,
    val backup: BackupSettings = BackupSettings(),
    /** 本地版本页的“系统自动备份”：编辑时每 10 分钟给文档存一个本地版本 */
    val autoLocalVersion: Boolean = true,
    /** 文件页的排序方式（SortMode 名称）和方向，下次打开仍沿用 */
    val filesSortMode: String = "UPDATED",
    val filesSortDesc: Boolean = true,
    /** 第一次使用的时间（“相识于”），0 表示还没记下 */
    val firstUse: Long = 0L,
)

/** 应用设置，单独存在 settings.json（与笔记数据分开） */
object Settings {
    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    fun init(context: Context) {
        file = File(context.filesDir, "settings.json")
        _state.value = runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())
    }

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        val text = json.encodeToString(next)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun updateBackup(block: (BackupSettings) -> BackupSettings) = update { it.copy(backup = block(it.backup)) }
}
