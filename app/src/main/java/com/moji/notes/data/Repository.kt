package com.moji.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 所有数据保存在应用私有目录：library.json（目录结构）+ docs/<id>.json（正文）+ images/（图片） */
object Repository {
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
    private const val MAX_HISTORY = 50
    private const val MAX_LOCAL = 30

    /** id -> 正文纯文本，供全文搜索 */
    private val textIndex = ConcurrentHashMap<String, String>()

    /** id -> 这篇文档里的评论（评论 id, 评论内容），供搜索评论 */
    private val commentIndex = ConcurrentHashMap<String, List<Pair<String, String>>>()

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

    fun init(context: Context) {
        app = context.applicationContext
        docsDir.mkdirs()
        imagesDir.mkdirs()
        versionsDir.mkdirs()
        templatesDir.mkdirs()
        exportsDir.mkdirs()
        val lib = runCatching { json.decodeFromString<Library>(libFile.readText()) }.getOrNull()
        if (lib == null) seed() else {
            _items.value = lib.items
            _nickname.value = lib.nickname
            _filesGrid.value = lib.filesGrid
            _homeGrid.value = lib.homeGrid
            _templates.value = lib.templates
            _footer.value = lib.footer
            _toolbar.value = lib.toolbar
            _manualOrder.value = lib.manualOrder
        }
        // 记下第一次使用的日期（老用户取最早的条目时间），“相识于”用
        if (Settings.value.firstUse == 0L) {
            Settings.update { it.copy(firstUse = _items.value.minOfOrNull { i -> i.createdAt } ?: System.currentTimeMillis()) }
        }
        _exports.value = runCatching { json.decodeFromString<List<ExportRecord>>(exportsFile.readText()) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        _items.value.filter { it.deletedAt != null && now - it.deletedAt > 30 * DAY }.forEach { destroy(it.id) }
        scope.launch(Dispatchers.IO) { buildIndexAndCleanImages() }
    }

    private fun seed() {
        // 第一次打开只放一篇使用说明
        val welcome = Templates.welcome()
        createDoc(null, welcome.first, welcome.second).also { markOpened(it.id) }
    }

    // ---------- 查询 ----------

    fun get(id: String?): Item? = id?.let { i -> _items.value.firstOrNull { it.id == i } }

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
    fun commentCount(docIds: Collection<String>): Int = docIds.sumOf { commentIndex[it]?.size ?: 0 }

    /** 文档、图片、版本、模板、下载记录等笔记数据占用的空间（字节） */
    fun dataSize(): Long = app.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun children(parentId: String?, all: List<Item> = _items.value): List<Item> =
        all.filter { it.parentId == parentId && it.deletedAt == null }

    fun ancestors(id: String?): List<Item> {
        val path = mutableListOf<Item>()
        var cur = get(id)
        while (cur != null && path.size < 64) {
            path.add(0, cur)
            cur = get(cur.parentId)
        }
        return path
    }

    fun descendants(id: String): List<Item> {
        val all = _items.value
        val out = mutableListOf<Item>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            all.filter { it.parentId == pid }.forEach { out += it; queue += it.id }
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

    private fun snippet(text: String, pos: Int, len: Int): String {
        val start = (pos - 18).coerceAtLeast(0)
        val end = (pos + len + 40).coerceAtMost(text.length)
        // 换行、连续空白都并成一个空格，摘要更紧凑
        return (if (start > 0) "…" else "") + text.substring(start, end).replace(blank, " ").trim() + (if (end < text.length) "…" else "")
    }

    // ---------- 修改 ----------

    private fun update(id: String, persist: Boolean = true, block: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == id) block(it) else it }
        if (persist) persistLibrary()
    }

    fun createDoc(parentId: String?, title: String = "", content: DocContent = DocContent(), time: Long = System.currentTimeMillis()): Item {
        val text = content.bodyText()
        val item = Item(
            type = ItemType.DOC, title = title, parentId = parentId, createdAt = time, updatedAt = time,
            preview = text.take(PREVIEW_LENGTH).replace('\n', ' '), words = countWords(title + "\n" + text),
        )
        _items.value = _items.value + item
        textIndex[item.id] = text
        indexComments(item.id, content)
        writeDoc(item.id, content)
        persistLibrary()
        return item
    }

    fun createFolder(parentId: String?, title: String): Item {
        val item = Item(type = ItemType.FOLDER, title = title.trim(), parentId = parentId)
        _items.value = _items.value + item
        persistLibrary()
        return item
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim(), updatedAt = System.currentTimeMillis()) }

    fun toggleStar(id: String) = update(id) { it.copy(starred = !it.starred) }

    fun move(id: String, parentId: String?) = update(id) { it.copy(parentId = parentId) }

    fun markOpened(id: String) = update(id) { it.copy(openedAt = System.currentTimeMillis()) }

    fun trash(id: String) = update(id) { it.copy(deletedAt = System.currentTimeMillis()) }

    fun restore(id: String) {
        val item = get(id) ?: return
        val parent = get(item.parentId)
        val parentOk = parent == null || isAlive(parent)
        update(id) { it.copy(deletedAt = null, parentId = if (parentOk) it.parentId else null) }
    }

    fun destroy(id: String) {
        val ids = (descendants(id).map { it.id } + id).toSet()
        ids.forEach { docId ->
            val f = File(docsDir, "$docId.json")
            if (f.exists()) {
                runCatching { json.decodeFromString<DocContent>(f.readText()) }.getOrNull()
                    ?.blocks?.filter { it.image.isNotEmpty() }?.forEach { File(imagesDir, it.image).delete() }
                f.delete()
            }
            textIndex.remove(docId)
            commentIndex.remove(docId)
            docCache.remove(docId)
            versionCache.remove(docId)
            File(versionsDir, "$docId.json").delete()
        }
        _items.value = _items.value.filterNot { it.id in ids }
        persistLibrary()
    }

    fun emptyTrash() = _items.value.filter { it.deletedAt != null }.forEach { destroy(it.id) }

    fun duplicate(id: String): Item? {
        val src = get(id)?.takeIf { it.isDoc } ?: return null
        return createDoc(src.parentId, src.displayTitle + " 副本", withCopiedImages(loadDoc(id)))
    }

    /** 复制一份正文：图片文件也各自复制，避免多篇文档 / 模板共用同一个文件 */
    private fun withCopiedImages(content: DocContent) = content.copy(blocks = content.blocks.map { b ->
        if (b.image.isEmpty()) b.copy(id = newId()) else {
            val name = newId() + "." + b.image.substringAfterLast('.', "jpg")
            runCatching { File(imagesDir, b.image).copyTo(File(imagesDir, name)) }
            b.copy(id = newId(), image = name)
        }
    })

    fun setNickname(name: String) {
        _nickname.value = name.trim().ifEmpty { "我" }
        persistLibrary()
    }

    fun setFilesGrid(grid: Boolean) {
        _filesGrid.value = grid
        persistLibrary()
    }

    fun setHomeGrid(grid: Boolean) {
        _homeGrid.value = grid
        persistLibrary()
    }

    /** 分享图片的落款；传空字符串表示不显示落款 */
    fun setFooter(text: String) {
        _footer.value = text.trim()
        persistLibrary()
    }

    fun setToolbar(ids: List<String>) {
        _toolbar.value = ids
        persistLibrary()
    }

    /** 记下某个文件夹（null 为桌面）里拖动排好的顺序 */
    fun setManualOrder(parentId: String?, ids: List<String>) {
        _manualOrder.value = _manualOrder.value + ((parentId ?: "") to ids)
        persistLibrary()
    }

    fun manualOrder(parentId: String?): List<String> = _manualOrder.value[parentId ?: ""].orEmpty()

    // ---------- 下载记录 ----------

    fun exportFile(r: ExportRecord) = File(exportsDir, "${r.id}.${r.kind.ext}")

    /** 记下一次分享 / 保存，并把内容另存一份，之后可以在“下载记录”里再次查看、分享 */
    fun recordExport(kind: ExportKind, fileName: String, saved: Boolean, docId: String, source: File? = null, text: String? = null) {
        val r = ExportRecord(kind = kind, fileName = fileName, saved = saved, docId = docId)
        runCatching {
            val dest = exportFile(r)
            if (source != null) source.copyTo(dest, overwrite = true) else dest.writeText(text.orEmpty())
        }
        _exports.value = listOf(r) + _exports.value
        persistExports()
    }

    fun deleteExport(id: String) {
        _exports.value.firstOrNull { it.id == id }?.let { exportFile(it).delete() }
        _exports.value = _exports.value.filterNot { it.id == id }
        persistExports()
    }

    fun clearExports() {
        _exports.value.forEach { exportFile(it).delete() }
        _exports.value = emptyList()
        persistExports()
    }

    private fun persistExports() {
        val text = json.encodeToString(_exports.value)
        scope.launch(writer) { atomicWrite(exportsFile, text) }
    }

    // ---------- 文档正文 ----------

    fun loadDoc(id: String): DocContent {
        docCache[id]?.let { return it }
        val f = File(docsDir, "$id.json")
        val content = runCatching { json.decodeFromString<DocContent>(f.readText()) }.getOrNull()
        return if (content == null || content.blocks.isEmpty()) DocContent() else content
    }

    fun saveDoc(id: String, title: String, content: DocContent) {
        val text = content.bodyText()
        textIndex[id] = text
        indexComments(id, content)
        update(id) {
            it.copy(
                title = title.trim(), updatedAt = System.currentTimeMillis(),
                preview = text.take(PREVIEW_LENGTH).replace('\n', ' '), words = countWords(title + "\n" + text),
            )
        }
        writeDoc(id, content)
    }

    private fun writeDoc(id: String, content: DocContent) {
        docCache[id] = content
        val text = json.encodeToString(content)
        scope.launch(writer) { atomicWrite(File(docsDir, "$id.json"), text) }
    }

    private fun persistLibrary() {
        val text = json.encodeToString(
            Library(
                _items.value, _nickname.value, _filesGrid.value, _homeGrid.value, _templates.value, _footer.value, _toolbar.value,
                _manualOrder.value,
            )
        )
        scope.launch(writer) { atomicWrite(libFile, text) }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 建立搜索索引，并清理没有被任何文档、历史版本或模板引用的图片 */
    private fun buildIndexAndCleanImages() {
        val referenced = HashSet<String>()
        fun collect(doc: DocContent) = doc.blocks.forEach { if (it.image.isNotEmpty()) referenced += it.image }
        _items.value.filter { it.isDoc }.forEach { item ->
            val doc = loadDoc(item.id)
            textIndex[item.id] = doc.bodyText()
            indexComments(item.id, doc)
            collect(doc)
        }
        versionsDir.listFiles()?.forEach { f ->
            runCatching { json.decodeFromString<List<DocVersion>>(f.readText()) }.getOrNull()?.forEach { collect(it.content) }
        }
        templatesDir.listFiles()?.forEach { f ->
            runCatching { json.decodeFromString<DocContent>(f.readText()) }.getOrNull()?.let(::collect)
        }
        val now = System.currentTimeMillis()
        imagesDir.listFiles()?.forEach { f ->
            if (f.name !in referenced && now - f.lastModified() > DAY) f.delete()
        }
    }

    // ---------- 历史版本 / 本地版本 ----------

    private val versionCache = ConcurrentHashMap<String, List<DocVersion>>()
    private val templateCache = ConcurrentHashMap<String, DocContent>()

    fun versions(docId: String): List<DocVersion> = versionCache.getOrPut(docId) {
        runCatching { json.decodeFromString<List<DocVersion>>(File(versionsDir, "$docId.json").readText()) }
            .getOrDefault(emptyList()).sortedByDescending { it.time }
    }

    /** 记录一个版本；与同类最新版本内容完全相同则跳过。返回是否真的记录了 */
    fun addVersion(docId: String, kind: VersionKind, title: String, content: DocContent, note: String = ""): Boolean {
        val all = versions(docId)
        val latest = all.firstOrNull { it.kind == kind }
        if (latest != null && latest.title == title && latest.content == content) return false
        val v = DocVersion(kind = kind, note = note, title = title, content = content, words = countWords(title + "\n" + content.bodyText()))
        val limit = if (kind == VersionKind.HISTORY) MAX_HISTORY else MAX_LOCAL
        val kept = (listOf(v) + all).groupBy { it.kind }.flatMap { (k, list) -> if (k == kind) list.take(limit) else list }
            .sortedByDescending { it.time }
        versionCache[docId] = kept
        val text = json.encodeToString(kept)
        scope.launch(writer) { atomicWrite(File(versionsDir, "$docId.json"), text) }
        return true
    }

    fun lastVersionTime(docId: String, kind: VersionKind): Long = versions(docId).firstOrNull { it.kind == kind }?.time ?: 0L

    /** 清空某一类版本（本地版本页的“清空版本”） */
    fun clearVersions(docId: String, kind: VersionKind) {
        val kept = versions(docId).filterNot { it.kind == kind }
        versionCache[docId] = kept
        val text = json.encodeToString(kept)
        scope.launch(writer) { atomicWrite(File(versionsDir, "$docId.json"), text) }
    }

    /** 用某个版本覆盖当前文档；覆盖前先把当前内容存为本地版本 */
    fun restoreVersion(docId: String, version: DocVersion) {
        val item = get(docId) ?: return
        addVersion(docId, VersionKind.LOCAL, item.title, loadDoc(docId), "恢复历史版本前自动保存")
        saveDoc(docId, version.title, version.content)
    }

    // ---------- 自定义模板 ----------

    fun saveAsTemplate(name: String, title: String, content: DocContent): UserTemplate {
        val t = UserTemplate(name = name.trim().ifEmpty { "我的模板" }, title = title)
        // 评论属于这篇文档本身，不带进模板
        val copy = withCopiedImages(content.withoutComments())
        templateCache[t.id] = copy
        val text = json.encodeToString(copy)
        scope.launch(writer) { atomicWrite(File(templatesDir, "${t.id}.json"), text) }
        _templates.value = listOf(t) + _templates.value
        persistLibrary()
        return t
    }

    /** 从自定义模板新建文档时取正文（图片另复制一份给新文档） */
    fun templateContent(id: String): DocContent = withCopiedImages(templatePreview(id))

    /** 只用于预览模板内容，不复制图片 */
    fun templatePreview(id: String): DocContent =
        templateCache[id]
            ?: runCatching { json.decodeFromString<DocContent>(File(templatesDir, "$id.json").readText()) }.getOrNull()
            ?: DocContent()

    fun deleteTemplate(id: String) {
        templateCache.remove(id)
        _templates.value = _templates.value.filterNot { it.id == id }
        persistLibrary()
        scope.launch(writer) { File(templatesDir, "$id.json").delete() }
    }

    // ---------- 图片 ----------

    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 从相册导入图片：缩放到最长边 1600px、修正方向后存入私有目录，返回 文件名 和 宽高比 */
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
        File(imagesDir, name).outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
        }
        bitmapCache.put(name, bmp)
        name to bmp.width.toFloat() / bmp.height
    }.getOrNull()

    fun loadBitmap(name: String): Bitmap? {
        bitmapCache.get(name)?.let { return it }
        val f = File(imagesDir, name)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        bitmapCache.put(name, bmp)
        return bmp
    }

    // ---------- 备份 ----------

    fun exportBackup(): String {
        val items = _items.value
        val docs = items.filter { it.isDoc }.associate { it.id to loadDoc(it.id) }
        val images = docs.values.flatMap { d -> d.blocks.mapNotNull { b -> b.image.takeIf { it.isNotEmpty() } } }
            .distinct().mapNotNull { name ->
                val f = File(imagesDir, name)
                if (f.exists()) name to Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
            }.toMap()
        return json.encodeToString(Backup(nickname = _nickname.value, items = items, docs = docs, images = images))
    }

    /** 导入备份：同 id 的条目会被覆盖，其余保留；返回导入的文档数（不算回收站里的） */
    fun importBackup(text: String): Int {
        val backup = json.decodeFromString<Backup>(text)
        require(backup.app == "moji-notes") { "不是空叶文档的备份文件" }
        backup.images.forEach { (name, data) ->
            val f = File(imagesDir, name)
            if (!f.exists()) f.writeBytes(Base64.decode(data, Base64.NO_WRAP))
        }
        backup.docs.forEach { (id, doc) ->
            docCache[id] = doc
            atomicWrite(File(docsDir, "$id.json"), json.encodeToString(doc))
            textIndex[id] = doc.bodyText()
            indexComments(id, doc)
        }
        val incoming = backup.items.associateBy { it.id }
        _items.value = _items.value.filterNot { it.id in incoming } + backup.items
        persistLibrary()
        return backup.items.count { it.isDoc && it.deletedAt == null }
    }
}
