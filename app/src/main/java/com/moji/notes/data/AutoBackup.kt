package com.moji.notes.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 本地文件夹或云端目录里的一个备份文件；id 是本地文档 id 或云端文件名 */
data class BackupEntry(val name: String, val time: Long, val size: Long, val auto: Boolean, val id: String)

/**
 * 备份：本地文件夹（系统文件选择器授权的目录）和云端（WebDAV）。
 * 自动备份在打开或离开应用时检查，距上次备份满了所选间隔就在后台备份一次（数据只在使用时才会变化）；
 * 自动备份文件名为“空叶文档自动备份-时间”，到期只清理这类文件，手动备份（“空叶文档备份-时间”）不受影响。
 */
object AutoBackup {
    const val PREFIX = "空叶文档自动备份-"
    const val MANUAL_PREFIX = "空叶文档备份-"
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 到时间了才备份（打开 / 离开应用时调用） */
    fun runIfDue(context: Context) {
        val app = context.applicationContext
        scope.launch { runAuto(app) }
    }

    private suspend fun runAuto(ctx: Context) = mutex.withLock {
        val s = Settings.value.backup
        val now = System.currentTimeMillis()
        val doLocal = s.localEnabled && s.localTree.isNotEmpty() && now - s.lastLocal >= s.intervalHours * HOUR
        val doDav = s.davEnabled && s.davUrl.isNotBlank() && now - s.lastDav >= s.davIntervalHours * HOUR
        if (!doLocal && !doDav) return@withLock
        val data = Repository.exportBackup()
        val name = PREFIX + stamp.format(Date(now)) + ".json"
        if (doLocal) {
            // 备份写成功就算成功，清理旧备份失败不影响
            val err = runCatching { writeLocal(ctx, s, name, data) }.exceptionOrNull()
            if (err == null) runCatching { cleanLocal(ctx, s, name, now) }
            Settings.updateBackup { it.copy(lastLocal = if (err == null) now else it.lastLocal, localError = err?.let(::describe).orEmpty()) }
        }
        if (doDav) {
            val err = runCatching { putDav(s, name, data) }.exceptionOrNull()
            if (err == null) runCatching { cleanDav(s, name, now) }
            Settings.updateBackup { it.copy(lastDav = if (err == null) now else it.lastDav, davError = err?.let(::describe).orEmpty()) }
        }
    }

    /** 把异常变成给用户看的一句话 */
    fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "没有访问所选文件夹的权限，请重新选择"
        is BackupError -> e.message.orEmpty()
        is IOException -> "无法连接服务器，请检查网络和地址"
        else -> e.message ?: e.javaClass.simpleName
    }

    private class BackupError(message: String) : Exception(message)

    private fun manualName() = MANUAL_PREFIX + stamp.format(Date()) + ".json"

    private fun isBackupName(name: String) = name.endsWith(".json") && (name.startsWith(PREFIX) || name.startsWith(MANUAL_PREFIX))

    /** 文件名里的时间（自动备份精确到秒；旧版手动备份只到分钟） */
    private fun nameTime(name: String): Long? {
        val base = name.removeSuffix(".json")
        val rest = when {
            base.startsWith(PREFIX) -> base.removePrefix(PREFIX)
            base.startsWith(MANUAL_PREFIX) -> base.removePrefix(MANUAL_PREFIX)
            else -> return null
        }
        return listOf("yyyyMMdd-HHmmss", "yyyyMMdd-HHmm").firstNotNullOfOrNull { p ->
            runCatching { SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(rest)?.time }.getOrNull()
        }
    }

    private fun expired(name: String, current: String, keepDays: Int, now: Long): Boolean {
        if (keepDays <= 0 || name == current || !name.startsWith(PREFIX)) return false
        val t = nameTime(name) ?: return false
        return now - t > keepDays * DAY
    }

    // ---------------- 本地文件夹 ----------------

    /** 文件夹的显示名：primary:Documents/空叶 → 内部存储/Documents/空叶 */
    fun folderName(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault("")
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (volume == "primary") "内部存储" else volume.ifEmpty { "存储" }
        return if (path.isEmpty()) root else "$root/$path"
    }

    private fun writeLocal(ctx: Context, s: BackupSettings, name: String, data: String) {
        val tree = Uri.parse(s.localTree)
        val resolver = ctx.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, dir, "application/json", name)
            ?: throw BackupError("无法在所选文件夹中创建文件")
        resolver.openOutputStream(doc)?.use { it.write(data.toByteArray()) } ?: throw BackupError("无法写入备份文件")
    }

    private fun cleanLocal(ctx: Context, s: BackupSettings, current: String, now: Long) {
        if (s.keepDays <= 0) return
        val tree = Uri.parse(s.localTree)
        queryLocal(ctx, tree).filter { expired(it.name, current, s.keepDays, now) }.forEach {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.id)) }
        }
    }

    private fun queryLocal(ctx: Context, tree: Uri): List<BackupEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val out = mutableListOf<BackupEntry>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (!isBackupName(name)) continue
                out += BackupEntry(name, nameTime(name) ?: c.getLong(2), c.getLong(3), name.startsWith(PREFIX), id)
            }
        }
        return out.sortedByDescending { it.time }
    }

    /** 手动备份到所选文件夹，返回文件名 */
    suspend fun backupLocalNow(context: Context): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val name = manualName()
            writeLocal(context.applicationContext, Settings.value.backup, name, Repository.exportBackup())
            name
        }
    }

    /** 所选文件夹里的备份文件，新的在前 */
    suspend fun listLocal(context: Context): List<BackupEntry> = withContext(Dispatchers.IO) {
        val tree = Settings.value.backup.localTree
        if (tree.isEmpty()) emptyList() else queryLocal(context.applicationContext, Uri.parse(tree))
    }

    suspend fun readLocal(context: Context, entry: BackupEntry): String = withContext(Dispatchers.IO) {
        val tree = Uri.parse(Settings.value.backup.localTree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw BackupError("无法读取备份文件")
    }

    // ---------------- 云端（WebDAV） ----------------

    /** 中文输入法会把“：／．”等打成全角，统一转成半角，并去掉空格 */
    fun normalizeUrl(input: String): String = buildString {
        input.forEach { c ->
            when (c) {
                in '！'..'～' -> append(c - 0xFEE0)
                '。' -> append('.')
                ' ', '　' -> {}
                else -> append(c)
            }
        }
    }.trim().let { url ->
        val lower = url.lowercase()
        when {
            lower.startsWith("http://") -> "http://" + url.substring(7)
            lower.startsWith("https://") -> "https://" + url.substring(8)
            else -> url
        }
    }

    private fun rootUrl(s: BackupSettings): HttpUrl {
        val raw = normalizeUrl(s.davUrl).let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        return runCatching { raw.toHttpUrl() }.getOrElse { throw BackupError("服务器地址格式不对") }
    }

    private fun dirSegments(s: BackupSettings) = s.davDir.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun dirUrl(s: BackupSettings, depth: Int = Int.MAX_VALUE): HttpUrl {
        val b = rootUrl(s).newBuilder()
        dirSegments(s).take(depth).forEach { b.addPathSegment(it) }
        return b.addPathSegment("").build()
    }

    private fun fileUrl(s: BackupSettings, name: String) = dirUrl(s).newBuilder().addPathSegment(name).build()

    private fun auth(s: BackupSettings) = Credentials.basic(s.davUser, s.davPassword, Charsets.UTF_8)

    private fun httpError(code: Int) = BackupError(
        when (code) {
            401 -> "账号或密码不对（坚果云等需要使用“应用密码”）"
            403 -> "没有权限访问这个目录"
            404 -> "服务器地址或目录不存在"
            507 -> "网盘空间不足"
            else -> "服务器返回错误（$code）"
        }
    )

    private fun request(url: HttpUrl, s: BackupSettings) = Request.Builder().url(url).header("Authorization", auth(s))

    private fun propfindBody() = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""
        .toRequestBody("application/xml; charset=utf-8".toMediaType())

    /** 测试连接：能列出服务器根目录即可 */
    suspend fun testDav(s: BackupSettings): String = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request(rootUrl(s), s).header("Depth", "0").method("PROPFIND", propfindBody()).build()).execute().use { r ->
                if (r.code != 207 && !r.isSuccessful) throw httpError(r.code)
            }
        }.fold({ "连接成功" }, { "连接失败：" + describe(it) })
    }

    /** 逐级创建备份目录（已存在时服务器返回 405，忽略），再上传 */
    private fun putDav(s: BackupSettings, name: String, data: String) {
        for (depth in 1..dirSegments(s).size) {
            client.newCall(request(dirUrl(s, depth), s).method("MKCOL", null).build()).execute().use { r ->
                if (r.code == 401) throw httpError(401)
            }
        }
        client.newCall(request(fileUrl(s, name), s).put(data.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw httpError(r.code)
        }
    }

    /** 删掉过期的自动备份 */
    private fun cleanDav(s: BackupSettings, current: String, now: Long) {
        if (s.davKeepDays <= 0) return
        queryDav(s).filter { expired(it.name, current, s.davKeepDays, now) }.forEach { old ->
            runCatching { client.newCall(request(fileUrl(s, old.name), s).delete().build()).execute().close() }
        }
    }

    private val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    private fun tag(block: String, name: String) =
        Regex("<(?:[A-Za-z0-9]+:)?$name\\b[^>]*>([^<]*)</(?:[A-Za-z0-9]+:)?$name>").find(block)?.groupValues?.get(1)?.trim()

    /** 列出备份目录里的备份文件；目录还不存在时返回空 */
    private fun queryDav(s: BackupSettings): List<BackupEntry> {
        val body = client.newCall(request(dirUrl(s), s).header("Depth", "1").method("PROPFIND", propfindBody()).build()).execute().use { r ->
            if (r.code == 404) return emptyList()
            if (r.code != 207 && !r.isSuccessful) throw httpError(r.code)
            r.body?.string().orEmpty()
        }
        return Regex("<(?:[A-Za-z0-9]+:)?response\\b.*?</(?:[A-Za-z0-9]+:)?response>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .mapNotNull { m ->
                val href = tag(m.value, "href") ?: return@mapNotNull null
                val name = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href).trimEnd('/').substringAfterLast('/')
                if (!isBackupName(name)) return@mapNotNull null
                val modified = tag(m.value, "getlastmodified")?.let { runCatching { synchronized(httpDate) { httpDate.parse(it)?.time } }.getOrNull() }
                BackupEntry(name, nameTime(name) ?: modified ?: 0L, tag(m.value, "getcontentlength")?.toLongOrNull() ?: 0L, name.startsWith(PREFIX), name)
            }
            .sortedByDescending { it.time }
            .toList()
    }

    /** 手动备份到云端，返回文件名 */
    suspend fun backupDavNow(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val name = manualName()
            putDav(Settings.value.backup, name, Repository.exportBackup())
            name
        }
    }

    suspend fun listDav(): List<BackupEntry> = withContext(Dispatchers.IO) { queryDav(Settings.value.backup) }

    suspend fun readDav(entry: BackupEntry): String = withContext(Dispatchers.IO) {
        val s = Settings.value.backup
        client.newCall(request(fileUrl(s, entry.name), s).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw httpError(r.code)
            r.body?.string() ?: throw BackupError("下载的备份是空的")
        }
    }
}
