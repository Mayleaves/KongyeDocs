package com.kongye.docs.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

/** 所有数据保存在应用私有目录：library.json（目录结构）+ docs/<id>.json（正文）+ images/（图片） */
object Repository {
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _problem = MutableStateFlow("")
    val problem: StateFlow<String> = _problem.asStateFlow()
    val isReadOnly get() = _problem.value.isNotEmpty()
    @Volatile var revision: Long = 0L
        private set
    @Synchronized
    private fun writable() { check(!isReadOnly) { _problem.value } }
    @Synchronized
    private fun failed(error: Exception): Nothing {
        _problem.value = error.message ?: "数据读取或保存失败，原文件已保留，请从备份恢复"
        throw error
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _nickname = MutableStateFlow("我")
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    private val _filesGrid = MutableStateFlow(true)
    val filesGrid: StateFlow<Boolean> = _filesGrid.asStateFlow()
    private val _homeGrid = MutableStateFlow(false)
    val homeGrid: StateFlow<Boolean> = _homeGrid.asStateFlow()

    private val _templates = MutableStateFlow<List<UserTemplate>>(emptyList())
    val userTemplates: StateFlow<List<UserTemplate>> = _templates.asStateFlow()
    private val _footer = MutableStateFlow(DEFAULT_FOOTER)
    val footer: StateFlow<String> = _footer.asStateFlow()
    private val _toolbar = MutableStateFlow<List<String>>(emptyList())
    val toolbar: StateFlow<List<String>> = _toolbar.asStateFlow()
    private val _exports = MutableStateFlow<List<ExportRecord>>(emptyList())
    val exports: StateFlow<List<ExportRecord>> = _exports.asStateFlow()
    private val _manualOrder = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val manualOrders: StateFlow<Map<String, List<String>>> = _manualOrder.asStateFlow()

    private const val PREVIEW_LENGTH = 200
    private const val MAX_LOCAL = 30

    /** id -> 正文纯文本，供全文搜索 */
    private val textIndex = ConcurrentHashMap<String, String>()

    /** id -> 这篇文档里的评论（评论 id, 评论内容），供搜索评论 */
    private val commentIndex = ConcurrentHashMap<String, List<Pair<String, String>>>()

    @Synchronized
    private fun indexComments(id: String, doc: DocContent) {
        val list = doc.comments.flatMap { t -> t.comments.map { t.id to it.text } }
        if (list.isEmpty()) commentIndex.remove(id) else commentIndex[id] = list
    }

    /** 最近保存过的正文。磁盘写入是异步的，读取时优先用这里，避免读到旧内容 */
    private val docCache = ConcurrentHashMap<String, DocContent>()

    private val libFile get() = File(app.filesDir, "library.json")
    private val docsDir get() = File(app.filesDir, "docs")
    private val versionsDir get() = File(app.filesDir, "versions")
    private val templatesDir get() = File(app.filesDir, "templates")
    private val exportsDir get() = File(app.filesDir, "exports")
    private val exportsFile get() = File(app.filesDir, "exports.json")
    val imagesDir get() = File(app.filesDir, "images")

    private const val DAY = 24 * 60 * 60 * 1000L

    @Synchronized
    fun init(context: Context) {
        app = context.applicationContext
        // 密码空间与文档同一个数据目录（测试用的独立目录也一样），各自加密保存
        VaultStore.init(context)
        _problem.value = ""
        _items.value = emptyList(); _templates.value = emptyList(); _exports.value = emptyList()
        docCache.clear(); versionCache.clear(); templateCache.clear(); textIndex.clear(); commentIndex.clear()
        revision = 0L
        docsDir.mkdirs()
        imagesDir.mkdirs()
        versionsDir.mkdirs()
        templatesDir.mkdirs()
        exportsDir.mkdirs()
        try { DurableFiles.recover(app.filesDir) } catch (error: Exception) { _problem.value = "上次恢复未完成：${error.message}"; return }
        val existing = libFile.exists() || File(app.filesDir, "library.json.bak").exists() || docsDir.listFiles().orEmpty().any { it.name.endsWith(".json") }
        val lib = if (!existing) null else try {
            DurableFiles.read(libFile) { json.decodeFromString<Library>(it).also(BackupCodec::validateLibrary) }
        } catch (error: Exception) {
            _problem.value = "文档目录无法读取，已停止写入和自动清理。原文件已保留，请从备份恢复。"
            return
        }
        if (lib == null) seed() else {
            _items.value = lib.items
            _nickname.value = lib.nickname
            _filesGrid.value = lib.filesGrid
            _homeGrid.value = lib.homeGrid
            _templates.value = lib.templates
            _footer.value = lib.footer
            _toolbar.value = lib.toolbar
            _manualOrder.value = lib.manualOrder
            revision = lib.revision
        }
        // 记下第一次使用的日期（老用户取最早的条目时间），“相识于”用
        if (Settings.value.firstUse == 0L) {
            Settings.update { it.copy(firstUse = _items.value.minOfOrNull { i -> i.createdAt } ?: System.currentTimeMillis()) }
        }
        if (exportsFile.exists() || File(app.filesDir, "exports.json.bak").exists()) {
            try { _exports.value = DurableFiles.read(exportsFile) { json.decodeFromString<List<ExportRecord>>(it) } }
            catch (error: Exception) { _problem.value = error.message.orEmpty(); return }
        }
        // Remove the discontinued HISTORY stream, while keeping manually/timed saved LOCAL versions.
        try { purgeLegacyHistory() } catch (error: Exception) { _problem.value = error.message.orEmpty(); return }
        // v2.2: never permanently delete current documents automatically at startup.
        scope.launch(Dispatchers.IO) { buildIndexAndCleanImages() }
    }

    @Synchronized
    private fun seed() {
        // 第一次打开只放一篇使用说明
        val welcome = Templates.welcome()
        createDoc(null, welcome.first, welcome.second).also { markOpened(it.id) }
    }

    // ---------- 查询 ----------

    @Synchronized
    fun get(id: String?): Item? = id?.let { i -> _items.value.firstOrNull { it.id == i } }

    @Synchronized
    fun isAlive(item: Item, all: List<Item> = _items.value): Boolean {
        var cur: Item? = item
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (cur.deletedAt != null) return false
            val pid = cur.parentId ?: return true
            cur = all.firstOrNull { it.id == pid } ?: return true
        }
        return true
    }

    /** 这些文档里的评论条数（含回复） */
    @Synchronized
    fun commentCount(docIds: Collection<String>): Int = docIds.sumOf { commentIndex[it]?.size ?: 0 }

    /** 文档、图片、版本、模板、下载记录等笔记数据占用的空间（字节） */
    @Synchronized
    fun dataSize(): Long = app.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Synchronized
    fun children(parentId: String?, all: List<Item> = _items.value): List<Item> =
        all.filter { it.parentId == parentId && it.deletedAt == null }

    @Synchronized
    fun ancestors(id: String?): List<Item> {
        val path = mutableListOf<Item>()
        var cur = get(id)
        while (cur != null && path.size < 64) {
            path.add(0, cur)
            cur = get(cur.parentId)
        }
        return path
    }

    @Synchronized
    fun descendants(id: String): List<Item> {
        val all = _items.value
        val out = mutableListOf<Item>()
        val queue = ArrayDeque(listOf(id))
        val visited = mutableSetOf(id)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            all.filter { it.parentId == pid && visited.add(it.id) }.forEach { out += it; queue += it.id }
        }
        return out
    }

    /**
     * 一条搜索结果：snippet 是正文或评论里命中处的摘要；
     * commentThread 不为空表示命中的是评论（打开文档时直接打开这条评论）
     */
    data class SearchHit(val item: Item, val snippet: String?, val commentThread: String? = null) {
        val inTitle get() = snippet == null
    }

    /** 搜索标题、正文和评论；onlyComments 时只看评论 */
    @Synchronized
    fun search(query: String, onlyComments: Boolean = false): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val all = _items.value
        return all.filter { isAlive(it, all) }.mapNotNull { item ->
            val comment = commentIndex[item.id]?.firstOrNull { it.second.contains(q, ignoreCase = true) }
            val commentHit = comment?.let { (tid, text) -> SearchHit(item, snippet(text, text.indexOf(q, ignoreCase = true), q.length), tid) }
            if (onlyComments) return@mapNotNull commentHit
            val text = textIndex[item.id].orEmpty()
            val inTitle = item.displayTitle.contains(q, ignoreCase = true)
            val pos = text.indexOf(q, ignoreCase = true)
            when {
                pos >= 0 -> SearchHit(item, snippet(text, pos, q.length))
                inTitle -> SearchHit(item, null)
                else -> commentHit
            }
        }.sortedWith(compareByDescending<SearchHit> { it.item.displayTitle.contains(q, true) }
            .thenByDescending { it.item.updatedAt })
    }

    private val blank = Regex("\\s+")

    @Synchronized
    private fun snippet(text: String, pos: Int, len: Int): String {
        val start = (pos - 18).coerceAtLeast(0)
        val end = (pos + len + 40).coerceAtMost(text.length)
        // 换行、连续空白都并成一个空格，摘要更紧凑
        return (if (start > 0) "…" else "") + text.substring(start, end).replace(blank, " ").trim() + (if (end < text.length) "…" else "")
    }

    // ---------- 修改 ----------

    @Synchronized
    private fun update(id: String, persist: Boolean = true, block: (Item) -> Item) {
        writable()
        _items.value = _items.value.map { if (it.id == id) block(it) else it }
        if (persist) persistLibrary()
    }

    @Synchronized
    fun createDoc(parentId: String?, title: String = "", content: DocContent = DocContent(), time: Long = System.currentTimeMillis()): Item {
        writable()
        val text = content.bodyText()
        val item = Item(
            type = ItemType.DOC, title = title, parentId = parentId, createdAt = time, updatedAt = time,
            preview = text.take(PREVIEW_LENGTH).replace('\n', ' '), words = countWords(title + "\n" + text),
        )
        val next = _items.value + item
        commit(mapOf("docs/${item.id}.json" to json.encodeToString(content), "library.json" to json.encodeToString(library(next))))
        _items.value = next
        textIndex[item.id] = text
        indexComments(item.id, content)
        docCache[item.id] = content
        return item
    }

    @Synchronized
    fun createFolder(parentId: String?, title: String): Item {
        val item = Item(type = ItemType.FOLDER, title = title.trim(), parentId = parentId)
        _items.value = _items.value + item
        persistLibrary()
        return item
    }

    @Synchronized
    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim(), updatedAt = System.currentTimeMillis()) }

    @Synchronized
    fun toggleStar(id: String) = update(id) { it.copy(starred = !it.starred) }

    @Synchronized
    fun togglePin(id: String) = update(id) {
        it.copy(pinnedAt = if(it.pinned) 0L else System.currentTimeMillis().coerceAtLeast(1L))
    }

    /**
     * 多选后一起置顶 / 取消置顶，只保存一次。新置顶的排在已有置顶项之上，
     * 彼此之间按 ids 的先后（先选的在上）。已经是目标状态的条目不变。
     */
    @Synchronized
    fun setPinned(ids: List<String>, pinned: Boolean) {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val rank = ids.withIndex().associate { it.value to it.index }
        val next = _items.value.map { item ->
            val i = rank[item.id]
            when {
                i == null || item.pinned == pinned -> item
                pinned -> item.copy(pinnedAt = now + ids.size - i)
                else -> item.copy(pinnedAt = 0L)
            }
        }
        if (next == _items.value) return
        writable()
        _items.value = next
        persistLibrary()
    }

    /**
     * 拖动调整置顶项之间的顺序：ids 是这些置顶项的新顺序。
     * 只在它们原有的置顶时间之间重新分配（大的在前），不在 ids 里的置顶项位置不变，也不影响手动排序。
     */
    @Synchronized
    fun reorderPinned(ids: List<String>) {
        val current = ids.distinct().mapNotNull { get(it) }.filter { it.pinned && it.deletedAt == null }
        if (current.size < 2) return
        val stamps = current.map { it.pinnedAt }.sortedDescending().toMutableList()
        // 时间相同（同一刻置顶）时拉开 1 毫秒，保证新顺序稳定
        for (i in 1 until stamps.size) if (stamps[i] >= stamps[i - 1]) stamps[i] = (stamps[i - 1] - 1).coerceAtLeast(1L)
        val assigned = current.map { it.id }.zip(stamps).toMap()
        val next = _items.value.map { item -> assigned[item.id]?.let { item.copy(pinnedAt = it) } ?: item }
        if (next == _items.value) return
        writable()
        _items.value = next
        persistLibrary()
    }

    @Synchronized
    fun move(id: String, parentId: String?) {
        require(parentId == null || (get(parentId)?.isFolder == true && parentId != id && descendants(id).none { it.id == parentId })) { "不能移入自身或子文件夹" }
        update(id) { it.copy(parentId = parentId) }
    }

    @Synchronized
    fun markOpened(id: String) = update(id) { it.copy(openedAt = System.currentTimeMillis()) }

    @Synchronized
    fun trash(id: String) = update(id) { it.copy(deletedAt = System.currentTimeMillis()) }

    @Synchronized
    fun restore(id: String) {
        val item = get(id) ?: return
        val parent = get(item.parentId)
        val parentOk = parent == null || isAlive(parent)
        update(id) { it.copy(deletedAt = null, parentId = if (parentOk) it.parentId else null) }
    }

    @Synchronized
    fun destroy(id: String) {
        writable()
        val ids = (descendants(id).map { it.id } + id).toSet()
        // Commit directory removal first; a crash may leave orphan files, never live entries with missing bodies.
        _items.value = _items.value.filterNot { it.id in ids }
        persistLibrary()
        ids.forEach { docId ->
            val f = File(docsDir, "$docId.json")
            if (f.exists()) {
                f.delete()
                File(docsDir, "$docId.json.bak").delete()
            }
            textIndex.remove(docId)
            commentIndex.remove(docId)
            docCache.remove(docId)
            versionCache.remove(docId)
            File(versionsDir, "$docId.json").delete()
            File(versionsDir, "$docId.json.bak").delete()
        }
        // Images are kept: another document, template, history or recovery snapshot may reference them.
    }

    @Synchronized
    fun emptyTrash() = _items.value.filter { it.deletedAt != null }.forEach { destroy(it.id) }

    @Synchronized
    fun duplicate(id: String): Item? {
        val src = get(id)?.takeIf { it.isDoc } ?: return null
        return createDoc(src.parentId, src.displayTitle + " 副本", withCopiedImages(loadDoc(id)))
    }

    /** 复制一份正文：图片文件也各自复制，避免多篇文档 / 模板共用同一个文件 */
    @Synchronized
    private fun withCopiedImages(content: DocContent) = content.copy(blocks = content.blocks.map { b ->
        if (b.image.isEmpty()) b.copy(id = newId()) else {
            val name = newId() + "." + b.image.substringAfterLast('.', "jpg")
            require(BackupCodec.validImage(b.image)) { "非法图片文件名" }
            DurableFiles.write(File(imagesDir, name), File(imagesDir, b.image).readBytes())
            b.copy(id = newId(), image = name)
        }
    })

    @Synchronized
    fun setNickname(name: String) {
        _nickname.value = name.trim().ifEmpty { "我" }
        persistLibrary()
    }

    @Synchronized
    fun setFilesGrid(grid: Boolean) {
        _filesGrid.value = grid
        persistLibrary()
    }

    @Synchronized
    fun setHomeGrid(grid: Boolean) {
        _homeGrid.value = grid
        persistLibrary()
    }

    /** 分享图片的落款；传空字符串表示不显示落款 */
    @Synchronized
    fun setFooter(text: String) {
        _footer.value = text.trim()
        persistLibrary()
    }

    @Synchronized
    fun setToolbar(ids: List<String>) {
        _toolbar.value = ids
        persistLibrary()
    }

    /** 记下某个文件夹（null 为桌面）里拖动排好的顺序 */
    @Synchronized
    fun setManualOrder(parentId: String?, ids: List<String>) {
        _manualOrder.value = _manualOrder.value + ((parentId ?: "") to ids)
        persistLibrary()
    }

    @Synchronized
    fun manualOrder(parentId: String?): List<String> = _manualOrder.value[parentId ?: ""].orEmpty()

    // ---------- 下载记录 ----------

    @Synchronized
    fun exportFile(r: ExportRecord) = File(exportsDir, "${r.id}.${r.kind.ext}")

    /** 记下一次分享 / 保存，并把内容另存一份，之后可以在“下载记录”里再次查看、分享 */
    @Synchronized
    fun recordExport(kind: ExportKind, fileName: String, saved: Boolean, docId: String, source: File? = null, text: String? = null) {
        val r = ExportRecord(kind = kind, fileName = fileName, saved = saved, docId = docId)
        val dest = exportFile(r)
        DurableFiles.write(dest, source?.readBytes() ?: text.orEmpty().toByteArray())
        _exports.value = listOf(r) + _exports.value
        persistExports()
    }

    @Synchronized
    fun deleteExport(id: String) {
        _exports.value.firstOrNull { it.id == id }?.let { exportFile(it).delete() }
        _exports.value = _exports.value.filterNot { it.id == id }
        persistExports()
    }

    @Synchronized
    fun clearExports() {
        _exports.value.forEach { exportFile(it).delete() }
        _exports.value = emptyList()
        persistExports()
    }

    @Synchronized
    private fun persistExports() {
        val text = json.encodeToString(_exports.value)
        atomicWrite(exportsFile, text)
        persistLibrary()
    }

    // ---------- 文档正文 ----------

    @Synchronized
    fun loadDoc(id: String): DocContent {
        require(BackupCodec.validId(id)) { "非法文档 ID" }
        docCache[id]?.let { return it }
        val f = File(docsDir, "$id.json")
        return try {
            DurableFiles.read(f) { text -> json.decodeFromString<DocContent>(text).also { require(it.blocks.isNotEmpty()) } }
        } catch (error: Exception) { failed(error) }
    }

    @Synchronized
    fun saveDoc(id: String, title: String, content: DocContent) {
        writable()
        require(get(id)?.isDoc == true) { "文档已关闭或已删除，请重新打开" }
        loadDoc(id) // A damaged existing body must never be silently overwritten.
        val text = content.bodyText()
        val next = _items.value.map { item -> if (item.id != id) item else item.copy(
                title = title.trim(), updatedAt = System.currentTimeMillis(),
                preview = text.take(PREVIEW_LENGTH).replace('\n', ' '), words = countWords(title + "\n" + text),
            )
        }
        commit(mapOf("docs/$id.json" to json.encodeToString(content), "library.json" to json.encodeToString(library(next))))
        _items.value = next
        docCache[id] = content
        textIndex[id] = text
        indexComments(id, content)
    }

    @Synchronized
    private fun writeDoc(id: String, content: DocContent) {
        val text = json.encodeToString(content)
        atomicWrite(File(docsDir, "$id.json"), text)
        docCache[id] = content
    }

    @Synchronized
    private fun library(items: List<Item> = _items.value) = Library(items, _nickname.value, _filesGrid.value, _homeGrid.value, _templates.value, _footer.value, _toolbar.value, _manualOrder.value, revision + 1)

    @Synchronized
    private fun commit(changes: Map<String, String>) {
        try { DurableFiles.transaction(app.filesDir, changes.mapValues { it.value.toByteArray() }); revision++ }
        catch (error: Exception) { failed(error) }
    }

    @Synchronized
    private fun persistLibrary() {
        writable()
        atomicWrite(libFile, json.encodeToString(library()))
        revision++
    }

    @Synchronized
    private fun atomicWrite(file: File, text: String) {
        writable()
        try { DurableFiles.write(file, text) } catch (error: Exception) { failed(error) }
    }

    /** 建立搜索索引，并清理没有被任何文档、历史版本或模板引用的图片 */
    @Synchronized
    private fun buildIndexAndCleanImages() {
        // Indexing must not turn a read error into an empty document or delete attachments.
        if (isReadOnly) return
        try {
        _items.value.filter { it.isDoc }.forEach { item ->
            val doc = loadDoc(item.id)
            textIndex[item.id] = doc.bodyText()
            indexComments(item.id, doc)
        }
        } catch (_: Exception) { /* problem is observable in the recovery UI */ }
    }

    // ---------- 历史版本 / 本地版本 ----------

    private val versionCache = ConcurrentHashMap<String, List<DocVersion>>()
    private val templateCache = ConcurrentHashMap<String, DocContent>()

    @Synchronized
    fun versions(docId: String): List<DocVersion> = versionCache.getOrPut(docId) {
        require(BackupCodec.validId(docId)) { "非法文档 ID" }
        val file = File(versionsDir, "$docId.json")
        if (!file.exists() && !File(versionsDir, "$docId.json.bak").exists()) emptyList()
        else try {
            var original=""
            val fallback=File(file.path+".bak")
            val savedAt=(if(file.isFile) file.lastModified() else fallback.lastModified()).coerceAtLeast(1L)
            val loaded=DurableFiles.read(file) { text ->
                val raw=json.parseToJsonElement(text).jsonArray
                val decoded=json.decodeFromString<List<DocVersion>>(text)
                original=text
                decoded.mapIndexed { index,version ->
                    val fields=raw[index].jsonObject
                    val rawThreads=fields["content"]?.jsonObject?.get("comments")?.jsonArray
                    val content=version.content.copy(comments=version.content.comments.mapIndexed { threadIndex,thread ->
                        val rawComments=rawThreads?.getOrNull(threadIndex)?.jsonObject?.get("comments")?.jsonArray
                        thread.copy(comments=thread.comments.mapIndexed { commentIndex,comment ->
                            if(rawComments?.getOrNull(commentIndex)?.jsonObject?.containsKey("time")==true) comment else comment.copy(time=savedAt)
                        })
                    })
                    version.copy(time=if("time" in fields) version.time else savedAt,content=content)
                }
            }.sortedByDescending { it.time }
            val stable=json.encodeToString(loaded)
            if(stable!=original) {
                atomicWrite(file,stable)
                atomicWrite(file,stable) // Keep the recovery copy complete as well.
            }
            loaded
        }
        catch (error: Exception) { failed(error) }
    }

    @Synchronized
    private fun purgeLegacyHistory() {
        _items.value.filter { it.isDoc }.forEach { item ->
            val all = versions(item.id)
            val local = all.filter { it.kind == VersionKind.LOCAL }
            if (local.size != all.size) {
                val text = json.encodeToString(local)
                val file = File(versionsDir, "${item.id}.json")
                atomicWrite(file, text)
                atomicWrite(file, text) // Rotate the previous copy as well; no HISTORY is left in .bak.
                versionCache[item.id] = local
            }
        }
    }

    /** 记录一个版本；与同类最新版本内容完全相同则跳过。返回是否真的记录了 */
    @Synchronized
    fun addVersion(docId: String, kind: VersionKind, title: String, content: DocContent, note: String = ""): Boolean {
        if (kind != VersionKind.LOCAL) return false
        val all = versions(docId)
        val latest = all.firstOrNull { it.kind == kind }
        if (latest != null && latest.title == title && latest.content == content) return false
        val v = DocVersion(kind = kind, note = note, title = title, content = content, words = countWords(title + "\n" + content.bodyText()))
        val limit = MAX_LOCAL
        val kept = (listOf(v) + all).groupBy { it.kind }.flatMap { (k, list) -> if (k == kind) list.take(limit) else list }
            .sortedByDescending { it.time }
        versionCache[docId] = kept
        val text = json.encodeToString(kept)
        atomicWrite(File(versionsDir, "$docId.json"), text)
        persistLibrary()
        return true
    }

    @Synchronized
    fun lastVersionTime(docId: String, kind: VersionKind): Long = versions(docId).firstOrNull { it.kind == kind }?.time ?: 0L

    /** 清空某一类版本（本地版本页的“清空版本”） */
    @Synchronized
    fun clearVersions(docId: String, kind: VersionKind) {
        val kept = versions(docId).filterNot { it.kind == kind }
        versionCache[docId] = kept
        val text = json.encodeToString(kept)
        atomicWrite(File(versionsDir, "$docId.json"), text)
        persistLibrary()
    }

    /** 按选中的版本新建独立副本，正文、评论及图片均保留。 */
    @Synchronized
    fun duplicateVersion(docId: String, versionId: String): Item {
        writable()
        val source = get(docId)?.takeIf { it.isDoc } ?: error("原文档不存在")
        val version = versions(docId).firstOrNull { it.id == versionId } ?: error("该版本不存在")
        val parent = source.parentId?.takeIf { id -> get(id)?.let { it.isFolder && isAlive(it) } == true }
        return createDoc(parent, version.title.ifBlank { "无标题" } + " 副本", withCopiedImages(version.content))
    }

    /** 仅移除选中的版本；正文及可能被其他版本引用的图片不删除。 */
    @Synchronized
    fun deleteVersion(docId: String, versionId: String): Boolean {
        writable()
        val all = versions(docId)
        val kept = all.filterNot { it.id == versionId }
        if (kept.size == all.size) return false
        atomicWrite(File(versionsDir, "$docId.json"), json.encodeToString(kept))
        versionCache[docId] = kept
        persistLibrary()
        return true
    }

    /** 用某个版本覆盖当前文档；覆盖前先把当前内容存为本地版本 */
    @Synchronized
    fun restoreVersion(docId: String, version: DocVersion) {
        val item = get(docId) ?: return
        addVersion(docId, VersionKind.LOCAL, item.title, loadDoc(docId), "恢复版本前自动保存")
        saveDoc(docId, version.title, version.content)
    }

    // ---------- 自定义模板 ----------

    @Synchronized
    fun saveAsTemplate(name: String, title: String, content: DocContent): UserTemplate {
        val t = UserTemplate(name = name.trim().ifEmpty { "我的模板" }, title = title)
        // 评论属于这篇文档本身，不带进模板
        val copy = withCopiedImages(content.withoutComments())
        templateCache[t.id] = copy
        val text = json.encodeToString(copy)
        atomicWrite(File(templatesDir, "${t.id}.json"), text)
        _templates.value = listOf(t) + _templates.value
        persistLibrary()
        return t
    }

    /** 从自定义模板新建文档时取正文（图片另复制一份给新文档） */
    @Synchronized
    fun templateContent(id: String): DocContent = withCopiedImages(templatePreview(id))

    /** 只用于预览模板内容，不复制图片 */
    @Synchronized
    fun templatePreview(id: String): DocContent =
        templateCache[id]
            ?: try {
                require(BackupCodec.validId(id)) { "非法模板 ID" }
                DurableFiles.read(File(templatesDir, "$id.json")) { json.decodeFromString<DocContent>(it) }
            } catch (error: Exception) { failed(error) }

    @Synchronized
    fun deleteTemplate(id: String) {
        templateCache.remove(id)
        _templates.value = _templates.value.filterNot { it.id == id }
        persistLibrary()
        File(templatesDir, "$id.json").delete()
        File(templatesDir, "$id.json.bak").delete()
    }

    // ---------- 图片 ----------

    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 从相册导入图片：缩放到最长边 1600px、修正方向后存入私有目录，返回 文件名 和 宽高比 */
    @Synchronized
    fun importImage(uri: Uri): Pair<String, Float>? = runCatching {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val longSide = maxOf(bmp.width, bmp.height)
        if (rotation != 0f || longSide > 1600) {
            val scale = if (longSide > 1600) 1600f / longSide else 1f
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val png = bmp.hasAlpha()
        val name = newId() + if (png) ".png" else ".jpg"
        val bytes = java.io.ByteArrayOutputStream()
        check(bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, bytes)) { "图片保存失败" }
        DurableFiles.write(File(imagesDir, name), bytes.toByteArray())
        bitmapCache.put(name, bmp)
        name to bmp.width.toFloat() / bmp.height
    }.getOrNull()

    @Synchronized
    fun loadBitmap(name: String): Bitmap? {
        if (!BackupCodec.validImage(name)) return null
        bitmapCache.get(name)?.let { return it }
        val f = File(imagesDir, name)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        bitmapCache.put(name, bmp)
        return bmp
    }

    // ---------- 备份 ----------

    @Synchronized
    fun exportBackup(): String {
        writable()
        val items = _items.value
        val docs = items.filter { it.isDoc }.associate { it.id to loadDoc(it.id) }
        val history = items.filter { it.isDoc }.associate { it.id to versions(it.id).filter { version -> version.kind == VersionKind.LOCAL } }
        val templates = _templates.value.associate { it.id to templatePreview(it.id) }
        val contents = docs.values + templates.values + history.values.flatten().map { it.content }
        val images = contents.flatMap { d -> d.blocks.mapNotNull { b -> b.image.takeIf { it.isNotEmpty() } } }
            .distinct().associateWith { name ->
                require(BackupCodec.validImage(name)) { "非法图片引用" }
                val f = File(imagesDir, name)
                require(f.isFile) { "文档引用的图片丢失：$name，未生成备份" }
                Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            }
        val files = _exports.value.associate { record ->
            require(BackupCodec.validId(record.id)) { "非法下载记录" }
            val file = exportFile(record)
            require(file.isFile) { "下载副本丢失，请先清理该下载记录再备份" }
            file.name to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
        return BackupCodec.encode(Backup(nickname = _nickname.value, items = items, docs = docs, images = images,
            library = library().copy(revision = revision), versions = history, templates = templates, exportRecords = _exports.value, exportFiles = files, revision = revision,
            vault = VaultStore.backupPayload()))
    }

    /** 导入备份：同 id 的条目会被覆盖，其余保留；返回导入的文档数（不算回收站里的） */
    @Synchronized
    fun importBackup(text: String): Int {
        val backup = BackupCodec.decode(text) // Validate the entire input before touching any current data.
        val safety = File(app.filesDir, "recovery").apply { mkdirs() }
        val safetyName = "恢复前备份-${System.currentTimeMillis()}-${newId()}"
        if (!isReadOnly) {
            val snapshot = runCatching { exportBackup() }.getOrNull()
            if (snapshot != null) DurableFiles.write(File(safety, "$safetyName.json"), snapshot)
            else preserveRaw(File(safety, safetyName))
        } else preserveRaw(File(safety, safetyName))

        val renamed = backup.images.mapValues { (name, data) ->
            val existing = File(imagesDir, name)
            if (existing.isFile && !existing.readBytes().contentEquals(Base64.decode(data, Base64.NO_WRAP))) newId() + "." + name.substringAfterLast('.') else name
        }
        fun remap(doc: DocContent) = doc.copy(blocks = doc.blocks.map { b -> b.copy(image = renamed[b.image] ?: b.image) })
        val docs = backup.docs.mapValues { remap(it.value) }
        val templates = backup.templates.mapValues { remap(it.value) }
        val incoming = backup.items.associateBy { it.id }
        var merged = _items.value.filterNot { it.id in incoming } + backup.items
        val mergedMap = merged.associateBy { it.id }
        // Preserve visibility of new items even when an old backup marks their ancestor deleted.
        merged = merged.map { item ->
            if (item.id !in incoming && isAlive(item, _items.value) && !isAlive(item, merged)) item.copy(parentId = null) else item
        }
        merged.forEach { item ->
            val seen = mutableSetOf(item.id)
            var parent = item.parentId
            while (parent != null) {
                val folder = mergedMap[parent]
                require(folder?.isFolder == true && seen.add(parent)) { "恢复后的文件夹结构冲突，未导入" }
                parent = folder.parentId
            }
        }
        val mergedTemplates = _templates.value.filterNot { t -> backup.library?.templates?.any { it.id == t.id } == true } + backup.library?.templates.orEmpty()
        val mergedExports = _exports.value.filterNot { r -> backup.exportRecords.any { it.id == r.id } } + backup.exportRecords
        val nextLibrary = backup.library?.copy(items = merged, templates = mergedTemplates, revision = revision + 1)
            ?: library(merged).copy(nickname = backup.nickname)
        val history = docs.mapValues { (id, _) ->
            val old = runCatching { versions(id) }.getOrDefault(emptyList()).filter { it.kind == VersionKind.LOCAL }
            val current = get(id)?.takeIf { it.isDoc }?.let { item ->
                runCatching { DocVersion(kind = VersionKind.LOCAL, title = item.title, content = loadDoc(id), note = "恢复备份前自动保存") }.getOrNull()
            }
            (listOfNotNull(current) + old + backup.versions[id].orEmpty().filter { it.kind == VersionKind.LOCAL }.map { it.copy(content = remap(it.content)) }).distinctBy { it.id }.sortedByDescending { it.time }
        }
        val changes = linkedMapOf<String, ByteArray>()
        backup.images.forEach { (name, data) -> changes["images/${renamed.getValue(name)}"] = Base64.decode(data, Base64.NO_WRAP) }
        docs.forEach { (id, doc) -> changes["docs/$id.json"] = json.encodeToString(doc).toByteArray() }
        templates.forEach { (id, doc) -> changes["templates/$id.json"] = json.encodeToString(doc).toByteArray() }
        history.forEach { (id, versions) -> changes["versions/$id.json"] = json.encodeToString(versions).toByteArray() }
        backup.exportFiles.forEach { (name, data) -> changes["exports/$name"] = Base64.decode(data, Base64.NO_WRAP) }
        changes["exports.json"] = json.encodeToString(mergedExports).toByteArray()
        changes["library.json"] = json.encodeToString(nextLibrary).toByteArray() // directory commits last
        try { DurableFiles.transaction(app.filesDir, changes) } catch (error: Exception) { failed(error) }
        docCache.clear(); versionCache.clear(); templateCache.clear(); textIndex.clear(); commentIndex.clear()
        _items.value = merged
        _templates.value = mergedTemplates
        _exports.value = mergedExports
        _nickname.value = nextLibrary.nickname; _filesGrid.value = nextLibrary.filesGrid; _homeGrid.value = nextLibrary.homeGrid
        _footer.value = nextLibrary.footer; _toolbar.value = nextLibrary.toolbar; _manualOrder.value = nextLibrary.manualOrder
        revision++
        _problem.value = ""
        buildIndexAndCleanImages()
        // 文档恢复完成后再处理密码空间：本机记住的备份密码能解开就直接恢复，否则弹窗请用户输入
        backup.vault?.let { VaultStore.offerRestore(it) }
        return backup.items.count { it.isDoc && it.deletedAt == null }
    }

    @Synchronized
    private fun preserveRaw(destination: File) {
        destination.mkdirs()
        listOf("library.json", "docs", "images", "versions", "templates", "exports.json", "exports").forEach { name ->
            val file = File(app.filesDir, name)
            if (file.exists()) check(file.copyRecursively(File(destination, name))) { "无法保存恢复前的原始文件，已停止恢复" }
        }
    }
}
