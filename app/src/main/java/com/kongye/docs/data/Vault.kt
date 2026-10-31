package com.kongye.docs.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 密码空间里的一条：动态密码（2FA）或普通密码 */
@Serializable
enum class VaultType { OTP, PASSWORD }

@Serializable
data class VaultEntry(
    val id: String = newId(),
    val type: VaultType,
    /** 网站或应用的名称，如 GitHub */
    val name: String,
    val account: String = "",
    /** 动态密码：Base32 密钥、位数、刷新周期（秒）、算法 */
    val secret: String = "",
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
    /** 普通密码 */
    val password: String = "",
    /** 2.23 的网址、备注；2.24 起放进 fields，读取时自动转换（见 normalized） */
    val url: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    /** 自定义字段：网址、备注、安全问题、备用码……可以增加、删除 */
    val fields: List<VaultField> = emptyList(),
    /** 置顶时间，0 表示未置顶；置顶项排在最前，后置顶的在上 */
    val pinnedAt: Long = 0L,
) {
    val pinned get() = pinnedAt > 0L

    /** 名称、账号、字段名和不隐藏的字段内容里包含关键词（密码、密钥和隐藏字段不参与搜索） */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val texts = listOf(name, account, url, note) + fields.flatMap { if (it.secret) listOf(it.label) else listOf(it.label, it.value) }
        return texts.any { it.contains(q, ignoreCase = true) }
    }

    /** 2.23 单独存放的网址、备注转成普通字段，之后可以像其他字段一样删除 */
    fun normalized(): VaultEntry {
        if (url.isEmpty() && note.isEmpty()) return this
        val legacy = listOfNotNull(
            url.takeIf { it.isNotBlank() }?.let { VaultField(label = "网址", value = it) },
            note.takeIf { it.isNotBlank() }?.let { VaultField(label = "备注", value = it) },
        )
        return copy(url = "", note = "", fields = legacy + fields)
    }

    fun field(label: String) = fields.firstOrNull { it.label == label }?.value.orEmpty()
}

/** 一个字段：名称、内容；secret 为 true 时列表和详情里显示为圆点 */
@Serializable
data class VaultField(
    val id: String = newId(),
    val label: String,
    val value: String = "",
    val secret: Boolean = false,
)

@Serializable
private data class VaultFile(val entries: List<VaultEntry> = emptyList())

/**
 * 备份里的密码空间：用备份密码经 PBKDF2-HMAC-SHA256 派生密钥，再做 AES-256-GCM 加密。
 * count 只是条数，恢复时用来提示，不含任何内容。
 */
@Serializable
data class VaultBackup(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val data: String,
    val count: Int,
)

class VaultPasswordException : Exception("备份密码不正确")

/**
 * 密码空间：内容只存在本机 files/vault/vault.json，用 Android Keystore 里的专用密钥加密，
 * 不进搜索、不进系统自动备份；设置了备份密码后，才会加密放进本地 / 云端备份。
 */
object VaultStore {
    const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: File? = null
    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    /** 读取失败时的说明；不为空时不再写入，以免覆盖原文件 */
    private val _problem = MutableStateFlow("")
    val problem: StateFlow<String> = _problem.asStateFlow()

    /** 恢复的备份里有密码空间、但本机记住的备份密码解不开：等用户输入密码 */
    private val _pending = MutableStateFlow<VaultBackup?>(null)
    val pendingRestore: StateFlow<VaultBackup?> = _pending.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val dir = File(context.filesDir, "vault").apply { mkdirs() }
        val f = File(dir, "vault.json")
        file = f
        _problem.value = ""
        _pending.value = null
        val loaded = runCatching {
            if (!f.exists() && !File(dir, "vault.json.bak").exists()) emptyList()
            else DurableFiles.read(f) { text -> json.decodeFromString<VaultFile>(CredentialStore.decrypt(text, CredentialStore.VAULT_ALIAS)).entries }
        }.getOrElse {
            _problem.value = "密码空间读取失败，原文件已保留"
            emptyList()
        }
        val normalized = loaded.map { it.normalized() }
        _entries.value = normalized
        // 2.23 的网址、备注转换成字段后立即写回，只做一次
        if (normalized != loaded) runCatching { persist(normalized) }
    }

    /** 置顶 / 取消置顶（不改修改时间） */
    @Synchronized
    fun togglePin(id: String) = persist(_entries.value.map {
        if (it.id == id) it.copy(pinnedAt = if (it.pinned) 0L else System.currentTimeMillis()) else it
    })

    fun get(id: String) = _entries.value.firstOrNull { it.id == id }

    @Synchronized
    private fun persist(next: List<VaultEntry>) {
        check(_problem.value.isEmpty()) { _problem.value }
        val f = file ?: error("密码空间尚未初始化")
        DurableFiles.write(f, CredentialStore.encrypt(json.encodeToString(VaultFile(next)), CredentialStore.VAULT_ALIAS))
        _entries.value = next
    }

    /** 新增或修改（同 id 覆盖） */
    @Synchronized
    fun save(entry: VaultEntry) {
        val now = System.currentTimeMillis()
        val list = _entries.value
        val next = entry.normalized()
        persist(
            if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) next.copy(updatedAt = now) else it }
            else list + next.copy(createdAt = now, updatedAt = now)
        )
    }

    @Synchronized
    fun delete(id: String) = persist(_entries.value.filterNot { it.id == id })

    // ---------- 备份 ----------

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    fun seal(entries: List<VaultEntry>, password: String): VaultBackup {
        require(password.isNotEmpty()) { "备份密码为空" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS))
        val data = cipher.doFinal(json.encodeToString(VaultFile(entries)).toByteArray(Charsets.UTF_8))
        return VaultBackup(KDF, ITERATIONS, b64(salt), b64(cipher.iv), b64(data), entries.size)
    }

    /** 用备份密码解开备份里的密码空间；密码不对抛 VaultPasswordException */
    fun open(backup: VaultBackup, password: String): List<VaultEntry> {
        require(backup.kdf == KDF && backup.iterations in 10_000..5_000_000) { "不支持的密码空间备份格式" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, unb64(backup.salt), backup.iterations), GCMParameterSpec(128, unb64(backup.iv)))
        val plain = try { cipher.doFinal(unb64(backup.data)) } catch (e: AEADBadTagException) { throw VaultPasswordException() }
        return json.decodeFromString<VaultFile>(plain.toString(Charsets.UTF_8)).entries
    }

    /** 导出备份时调用：设置了备份密码且有内容才放进备份，否则备份里没有密码空间 */
    fun backupPayload(): VaultBackup? {
        val password = Settings.value.backup.vaultPassword
        val list = _entries.value
        if (password.isEmpty() || list.isEmpty() || _problem.value.isNotEmpty()) return null
        return seal(list, password)
    }

    /** 恢复备份后调用：先用本机记住的备份密码试一次，解不开就等用户输入；返回已恢复的条数 */
    fun offerRestore(backup: VaultBackup): Int? {
        val stored = Settings.value.backup.vaultPassword
        if (stored.isNotEmpty()) runCatching { return restore(backup, stored) }
        _pending.value = backup
        return null
    }

    /** 备份里的条目覆盖本机同 id 的条目，其余保留 */
    @Synchronized
    fun restore(backup: VaultBackup, password: String): Int {
        val incoming = open(backup, password).map { it.normalized() }
        val ids = incoming.map { it.id }.toSet()
        persist(_entries.value.filterNot { it.id in ids } + incoming)
        if (_pending.value == backup) _pending.value = null
        return incoming.size
    }

    fun dismissRestore() { _pending.value = null }
}
