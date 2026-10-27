package com.kongye.docs.data

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
    val allowInsecureDav: Boolean = false,
    val lastLocalRevision: Long = -1L,
    val lastDavRevision: Long = -1L,
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** 定时切换：每天 darkFrom 到 darkTo（一天中的第几分钟）用深色，可以跨过午夜 */
    val darkFrom: Int = 22 * 60,
    val darkTo: Int = 7 * 60,
    val backup: BackupSettings = BackupSettings(),
    /** 本地版本系统自动备份：默认开启，保留 v2.5 的每 10 分钟保存机制。 */
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
        val stored = if (!file.exists() && !File(file.parentFile, "settings.json.bak").exists()) AppSettings()
            else runCatching { DurableFiles.read(file) { json.decodeFromString<AppSettings>(it) } }.getOrElse {
                AppSettings(backup = BackupSettings(localError = "设置读取失败，原文件已保留，请重新配置备份", davError = "设置读取失败，请重新配置备份"))
            }
        val plain = runCatching { CredentialStore.decrypt(stored.backup.davPassword) }
        _state.value = if (plain.isSuccess) stored.copy(backup = stored.backup.copy(davPassword = plain.getOrThrow()))
            else stored.copy(backup = stored.backup.copy(davPassword = "", davEnabled = false, davError = "无法解密 WebDAV 凭据，请重新输入应用密码"))
        if (stored.backup.davPassword.isNotEmpty() && !CredentialStore.isEncrypted(stored.backup.davPassword)) {
            // Rotate twice so a plaintext 2.1 settings backup is not left behind.
            runCatching { persist(_state.value); persist(_state.value); File(file.parentFile, "settings.json.tmp").delete() }
        }
    }

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        if (next == _state.value) return
        persist(next)
        _state.value = next
    }

    private fun persist(settings: AppSettings) {
        val protected = settings.copy(backup = settings.backup.copy(davPassword = CredentialStore.encrypt(settings.backup.davPassword)))
        DurableFiles.write(file, json.encodeToString(protected))
    }

    fun updateBackup(block: (BackupSettings) -> BackupSettings) = update { it.copy(backup = block(it.backup)) }
}
