package com.moji.notes.ui.editor

import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.moji.notes.data.Align
import com.moji.notes.data.Block
import com.moji.notes.data.BlockType
import com.moji.notes.data.Comment
import com.moji.notes.data.CommentThread
import com.moji.notes.data.DocContent
import com.moji.notes.data.InlineStyle
import com.moji.notes.data.Repository
import com.moji.notes.data.Settings
import com.moji.notes.data.StyleRange
import com.moji.notes.data.VersionKind
import com.moji.notes.data.canFirstIndent
import com.moji.notes.ui.components.Toast
import kotlin.math.max

/**
 * 每个文本块的输入框开头放一个零宽字符作为“哨兵”：
 * 软键盘在块首按退格时会删掉它，由此可以可靠地识别“块首退格”（合并到上一块 / 取消列表格式）。
 * 输入框内的偏移量都包含这个哨兵，所以 spans 也比存储的偏移量大 1。
 */
const val ZWS = "​"

/** 标题输入框在“双击选字”里用的键 */
const val TITLE_KEY = "__title__"

class BlockState(b: Block) {
    val id: String = b.id
    var type by mutableStateOf(b.type)
    var value by mutableStateOf(TextFieldValue(ZWS + b.text, TextRange(1)), neverEqualPolicy())
    var spans by mutableStateOf(b.spans.shifted(1))
    var checked by mutableStateOf(b.checked)
    var indent by mutableIntStateOf(b.indent)
    var align by mutableStateOf(b.align)
    var image by mutableStateOf(b.image)
    var imageRatio by mutableFloatStateOf(b.imageRatio)
    var table by mutableStateOf(b.table)
    var firstIndent by mutableStateOf(b.firstIndent)
    val focus = FocusRequester()

    /** 无选区时用户在工具栏切换的待输入样式；null 表示沿用光标前文字的样式 */
    var typing by mutableStateOf<Set<StyleKey>?>(null)

    val text: String get() = value.text.removePrefix(ZWS)

    fun toBlock() = Block(
        id = id, type = type, text = text, spans = spans.shifted(-1).normalized(text.length),
        checked = checked, indent = indent, align = align, image = image, imageRatio = imageRatio, table = table,
        firstIndent = firstIndent,
    )
}

enum class TableOp { ROW_ABOVE, ROW_BELOW, COL_LEFT, COL_RIGHT, DELETE_ROW, DELETE_COL, DELETE_TABLE }

class EditorState(val docId: String, val isNew: Boolean) {
    val blocks = mutableStateListOf<BlockState>()
    var title by mutableStateOf(TextFieldValue(""), neverEqualPolicy())
    var focusedId by mutableStateOf<String?>(null)
    var focusedCell by mutableStateOf<CellRef?>(null)
    var pendingFocus by mutableStateOf<PendingFocus?>(null)
    var version by mutableIntStateOf(0)
        private set
    var savedVersion by mutableIntStateOf(0)
        private set
    var lastSavedAt by mutableLongStateOf(0L)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    data class CellRef(val blockId: String, val row: Int, val col: Int)
    data class PendingFocus(val blockId: String, val pos: Int, val nonce: Long = System.nanoTime())
    private data class Snap(val title: String, val blocks: List<Block>, val focusId: String?, val pos: Int)

    /** 正在给 [start, end)（输入框偏移）写一条新评论 */
    data class CommentDraft(val blockId: String, val start: Int, val end: Int)

    /** 评论面板：threadId 为 null 时列出全部评论；fromAll 表示是从“全部评论”点进来的，可以返回 */
    data class CommentView(val threadId: String?, val fromAll: Boolean = false)

    /** 双击时点中的那个字：输入框随后按“选中整个词”回调时，改成只选这一个字 */
    private class CharSelect(val key: String, val start: Int, val end: Int, val at: Long = SystemClock.uptimeMillis())

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var lastTypingAt = 0L
    private var charSelect: CharSelect? = null

    /** 本次编辑期间的全部评论；只有在正文里还挂着锚点的才会保存 */
    val comments = mutableStateListOf<CommentThread>()
    var commentDraft by mutableStateOf<CommentDraft?>(null)
    var commentView by mutableStateOf<CommentView?>(null)

    init {
        val item = Repository.get(docId)
        val t = item?.title.orEmpty()
        title = TextFieldValue(t, TextRange(t.length))
        val doc = Repository.loadDoc(docId)
        doc.blocks.forEach { blocks += BlockState(it) }
        comments += doc.comments
        if (blocks.isEmpty()) blocks += BlockState(Block())
        lastSavedAt = item?.updatedAt ?: 0L
        // 第一次打开时留一个初始版本，之后的历史都能退回到这里
        if (Repository.versions(docId).none { it.kind == VersionKind.HISTORY }) recordHistory("初始版本")
    }

    val focused: BlockState? get() = focusedId?.let { id -> blocks.firstOrNull { it.id == id } }
    val isSaved get() = version == savedVersion
    val changedInSession get() = version > 0

    /** 保存的内容：去掉找不到评论的锚点（例如撤销恢复了已结束评论的锚点），也只保存还挂在正文里的评论 */
    fun content(): DocContent {
        val ids = comments.mapTo(HashSet()) { it.id }
        fun dangling(r: StyleRange) = r.style == InlineStyle.COMMENT && r.url !in ids
        val bs = blocks.map { it.toBlock() }.map { b -> if (b.spans.any(::dangling)) b.copy(spans = b.spans.filterNot(::dangling)) else b }
        val used = bs.flatMapTo(HashSet()) { b -> b.spans.filter { it.style == InlineStyle.COMMENT }.map { it.url } }
        return DocContent(bs, comments.filter { it.id in used })
    }

    fun isEmptyDoc() = title.text.isBlank() && blocks.all { it.type.isText && it.text.isBlank() }

    fun save(): Boolean {
        if (isSaved) return true
        val result = runCatching { Repository.saveDoc(docId, title.text, content()) }
        if (result.isFailure) { Toast.show("保存失败，编辑内容仍保留在此页：${result.exceptionOrNull()?.message}"); return false }
        savedVersion = version
        lastSavedAt = System.currentTimeMillis()
        runCatching {
        // 长时间连续编辑时，每 10 分钟记录一次历史版本
        if (lastSavedAt - Repository.lastVersionTime(docId, VersionKind.HISTORY) > 10 * 60 * 1000L) recordHistory()
        // 开着“系统自动备份”时，每 10 分钟再存一个本地版本
        if (Settings.value.autoLocalVersion && !isEmptyDoc() &&
            lastSavedAt - Repository.lastVersionTime(docId, VersionKind.LOCAL) > 10 * 60 * 1000L
        ) Repository.addVersion(docId, VersionKind.LOCAL, title.text, content(), "自动备份")
        }.onFailure { Toast.show("正文已保存，但版本保存失败：${it.message}") }
        return true
    }

    /** 记录一条历史版本；内容与上一条相同则不重复记录 */
    fun recordHistory(note: String = "") {
        if (!isEmptyDoc()) Repository.addVersion(docId, VersionKind.HISTORY, title.text, content(), note)
    }

    private fun changed() { version++ }

    fun requestFocus(b: BlockState, pos: Int) { pendingFocus = PendingFocus(b.id, pos) }

    // ---------------- 撤销 / 重做 ----------------

    private fun snap() = Snap(title.text, blocks.map { it.toBlock() }, focusedId, focused?.value?.selection?.start ?: 1)

    fun checkpoint() {
        undoStack.addLast(snap())
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
        lastTypingAt = 0L
    }

    /** 连续输入合并为一步撤销：停顿超过 1 秒才记录新的撤销点 */
    private fun typingCheckpoint() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTypingAt > 1000) checkpoint()
        lastTypingAt = now
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snap())
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snap())
        restore(s)
    }

    private fun restore(s: Snap) {
        title = TextFieldValue(s.title, TextRange(s.title.length))
        blocks.clear()
        s.blocks.forEach { blocks += BlockState(it) }
        if (blocks.isEmpty()) blocks += BlockState(Block())
        blocks.firstOrNull { it.id == s.focusId && it.type.isText }?.let { requestFocus(it, s.pos.coerceIn(1, it.value.text.length)) }
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        lastTypingAt = 0L
        changed()
    }

    // ---------------- 标题 ----------------

    fun onTitleChange(v: TextFieldValue) {
        takeCharSelect(TITLE_KEY, title, v)?.let { title = it; return }
        if (v.text.contains('\n')) {
            val clean = v.text.replace("\n", "")
            title = TextFieldValue(clean, TextRange(clean.length))
            val first = blocks.first()
            if (first.type.isText) requestFocus(first, 1) else {
                checkpoint()
                val nb = BlockState(Block())
                blocks.add(0, nb)
                requestFocus(nb, 1)
            }
            changed()
            return
        }
        if (v.text != title.text) {
            typingCheckpoint()
            changed()
        }
        title = v
    }

    // ---------------- 文本输入 ----------------

    fun onValueChange(b: BlockState, incoming: TextFieldValue) {
        // 已被合并删除、但输入框还没销毁的块（失焦时会再回调一次），不再处理
        if (b !in blocks) return
        takeCharSelect(b.id, b.value, incoming)?.let {
            b.value = it
            b.typing = null
            return
        }
        val old = b.value
        var nv = incoming
        if (!nv.text.startsWith(ZWS)) {
            if (old.selection.collapsed && old.selection.start <= 1 && nv.text == old.text.substring(1)) {
                b.value = old.copy(selection = TextRange(1))
                backspaceAtStart(b)
                return
            }
            val clean = nv.text.replace(ZWS, "")
            fun shift(r: TextRange) = TextRange(r.start + 1, r.end + 1)
            nv = TextFieldValue(ZWS + clean, shift(nv.selection), nv.composition?.let(::shift))
        }
        if (nv.selection.min < 1) nv = nv.copy(selection = TextRange(max(1, nv.selection.start), max(1, nv.selection.end)))
        if (nv.text == old.text) {
            if (nv.selection != old.selection) b.typing = null
            b.value = nv
            return
        }
        val prefix = commonPrefix(old.text, nv.text)
        val typing = b.typing ?: stylesAt(b.spans, prefix, 1)
        val spans = adjustSpans(b.spans, old.text, nv.text, typing)
        if (b.type != BlockType.CODE && nv.text.contains('\n')) {
            checkpoint()
            splitLines(b, nv, spans)
            changed()
            return
        }
        if (b.type == BlockType.CODE && isCodeExit(old, nv)) {
            checkpoint()
            b.value = TextFieldValue(nv.text.dropLast(2), TextRange(nv.text.length - 2))
            b.spans = spans.normalized(nv.text.length - 2)
            insertAfter(b, BlockState(Block()))
            changed()
            return
        }
        typingCheckpoint()
        b.spans = spans
        b.value = nv
        if (nv.composition == null) markdownShortcut(b)
        changed()
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun isCodeExit(old: TextFieldValue, nv: TextFieldValue) =
        nv.text.length == old.text.length + 1 && nv.text.endsWith("\n\n") && nv.selection.start == nv.text.length

    private fun splitLines(b: BlockState, nv: TextFieldValue, spans: List<StyleRange>) {
        val body = nv.text.substring(1)
        val parts = body.split('\n')
        val bodySpans = spans.shifted(-1)
        val caret = nv.selection.start - 1
        val idx = blocks.indexOf(b)

        // 在空的列表 / 引用 / 标题 / 缩进行按回车：先取消格式
        if (parts.size == 2 && parts[0].isEmpty() && parts[1].isEmpty() && (b.type != BlockType.TEXT || b.indent > 0)) {
            when {
                b.type.isList && b.indent > 0 -> b.indent--
                b.type != BlockType.TEXT -> b.type = BlockType.TEXT
                else -> b.indent--
            }
            b.value = TextFieldValue(ZWS, TextRange(1))
            b.spans = emptyList()
            return
        }
        val nextType = if (b.type.isList || b.type == BlockType.QUOTE) b.type else BlockType.TEXT

        // 在非空块的开头按回车：在上方插入空行，当前块保持原样
        if (parts.size == 2 && parts[0].isEmpty() && caret == 1 && parts[1].isNotEmpty()) {
            b.value = TextFieldValue(ZWS + parts[1], TextRange(1))
            b.spans = sliceSpans(bodySpans, 1, body.length).shifted(1)
            blocks.add(idx, BlockState(Block(type = nextType, indent = b.indent, firstIndent = b.firstIndent && nextType.canFirstIndent)))
            requestFocus(b, 1)
            return
        }

        var offset = 0
        val pieces = parts.map { part ->
            val s = sliceSpans(bodySpans, offset, offset + part.length)
            offset += part.length + 1
            part to s
        }
        b.value = TextFieldValue(ZWS + pieces[0].first, TextRange(1 + pieces[0].first.length))
        b.spans = pieces[0].second.shifted(1)
        val created = pieces.drop(1).map { (t, s) ->
            BlockState(
                Block(
                    type = nextType, text = t, spans = s, indent = b.indent,
                    align = if (nextType == BlockType.TEXT) b.align else Align.START,
                    // 首行缩进的段落回车后，新段落也缩进
                    firstIndent = b.firstIndent && nextType.canFirstIndent,
                )
            )
        }
        blocks.addAll(idx + 1, created)

        var acc = 0
        var target = b
        var pos = 1 + pieces[0].first.length
        for ((i, part) in parts.withIndex()) {
            if (caret <= acc + part.length) {
                target = if (i == 0) b else created[i - 1]
                pos = 1 + (caret - acc)
                break
            }
            acc += part.length + 1
        }
        requestFocus(target, pos)
    }

    private fun backspaceAtStart(b: BlockState) {
        val idx = blocks.indexOf(b)
        if (idx < 0) return
        checkpoint()
        when {
            b.type == BlockType.CODE -> {
                val lines = b.text.split('\n')
                b.type = BlockType.TEXT
                b.value = TextFieldValue(ZWS + lines[0], TextRange(1))
                b.spans = emptyList()
                blocks.addAll(idx + 1, lines.drop(1).map { BlockState(Block(text = it)) })
            }
            b.type != BlockType.TEXT -> {
                if (b.type.isList && b.indent > 0) b.indent-- else {
                    b.type = BlockType.TEXT
                    b.checked = false
                }
            }
            b.indent > 0 -> b.indent--
            // 首行缩进的段落在段首退格：先取消缩进
            b.firstIndent -> b.firstIndent = false
            b.align != Align.START -> b.align = Align.START
            idx == 0 -> {
                undoStack.removeLastOrNull()
                canUndo = undoStack.isNotEmpty()
                return
            }
            else -> {
                val prev = blocks[idx - 1]
                if (!prev.type.isText) {
                    blocks.removeAt(idx - 1)
                } else {
                    val join = prev.value.text.length
                    prev.spans = (prev.spans + b.spans.shifted(join - 1)).normalized(join + b.text.length)
                    prev.value = TextFieldValue(prev.value.text + b.text, TextRange(join))
                    blocks.removeAt(idx)
                    // 当前块的输入框要到下一帧才销毁，趁它还在时把焦点直接交给上一块：
                    // 输入法连接在同一帧内转移，键盘不会先收起再弹出。
                    // 当前块失焦时还会回调一次 onValueChange，它已不在 blocks 里，会被忽略
                    runCatching { prev.focus.requestFocus() }
                    focusedId = prev.id
                    requestFocus(prev, join)
                }
            }
        }
        changed()
    }

    private val shortcut = Regex("^(#{1,3}|[-*+•]|\\d+\\.|>|\\[ ?]|```|---)[ ]$")

    /** 中文输入法会输出全角符号（＃ － ＞ 》 【】 。 ···），统一转成半角再匹配 */
    private fun normalizeSymbols(s: String) = buildString {
        s.forEach { c ->
            append(
                when (c) {
                    in '！'..'～' -> c - 0xFEE0
                    '》' -> '>'
                    '【' -> '['
                    '】' -> ']'
                    '。' -> '.'
                    '—', '－' -> '-'
                    '·' -> '`'
                    '　', ' ' -> ' '
                    else -> c
                }
            )
        }
    }

    /** Markdown 快捷输入：块首输入 “# ”、“- ”、“1. ”、“[] ”、“> ”、“``` ”、“--- ” */
    private fun markdownShortcut(b: BlockState) {
        if (b.type == BlockType.CODE || !b.value.selection.collapsed) return
        val caret = b.value.selection.start
        val before = b.value.text.substring(1, caret)
        val token = shortcut.matchEntire(normalizeSymbols(before))?.groupValues?.get(1) ?: return
        val newType = when {
            token.startsWith("#") -> when (token.length) { 1 -> BlockType.H1; 2 -> BlockType.H2; else -> BlockType.H3 }
            token in setOf("-", "*", "+", "•") -> BlockType.BULLET
            token[0].isDigit() -> BlockType.NUMBER
            token == ">" -> BlockType.QUOTE
            token.startsWith("[") -> BlockType.TODO
            token == "```" -> BlockType.CODE
            else -> BlockType.DIVIDER
        }
        checkpoint()
        val cut = before.length
        val rest = b.value.text.substring(1 + cut)
        b.spans = sliceSpans(b.spans.shifted(-1), cut, cut + rest.length).shifted(1)
        b.value = TextFieldValue(ZWS + rest, TextRange(1))
        if (newType == BlockType.DIVIDER) {
            blocks.add(blocks.indexOf(b), BlockState(Block(type = BlockType.DIVIDER)))
        } else {
            b.type = newType
            b.checked = false
        }
    }

    // ---------------- 工具栏操作 ----------------

    fun setBlockType(t: BlockType) {
        val b = focused ?: return
        checkpoint()
        b.type = if (b.type == t && t != BlockType.TEXT) BlockType.TEXT else t
        b.checked = false
        changed()
    }

    fun toggleTodo(b: BlockState) {
        checkpoint()
        b.checked = !b.checked
        changed()
    }

    fun setAlign(a: Align) {
        val b = focused ?: return
        if (b.align == a) return
        checkpoint()
        b.align = a
        changed()
    }

    fun indent(delta: Int) {
        val b = focused ?: return
        val v = (b.indent + delta).coerceIn(0, 4)
        if (v == b.indent) return
        checkpoint()
        b.indent = v
        changed()
    }

    /** 段落首行缩进两个字（开 / 关） */
    fun toggleFirstIndent() {
        val b = focused ?: return
        if (!b.type.canFirstIndent) return
        checkpoint()
        b.firstIndent = !b.firstIndent
        changed()
    }

    // ---------------- 评论 ----------------

    fun thread(id: String?): CommentThread? = id?.let { comments.firstOrNull { t -> t.id == it } }

    /** 每条评论第一个锚点所在的块（评论角标只画在这一块） */
    val firstAnchor: Map<String, String> by derivedStateOf {
        val m = HashMap<String, String>()
        blocks.forEach { b -> b.spans.forEach { if (it.style == InlineStyle.COMMENT && it.url !in m) m[it.url] = b.id } }
        m
    }

    /** 正文里实际挂着的评论，按出现顺序 */
    fun anchoredThreads(): List<CommentThread> {
        val order = LinkedHashSet<String>()
        blocks.forEach { b -> b.spans.filter { it.style == InlineStyle.COMMENT }.sortedBy { it.start }.forEach { order += it.url } }
        return order.mapNotNull { thread(it) }
    }

    /** 评论所引用的原文（跨了几段时用空格连起来） */
    fun quoteOf(threadId: String): String = blocks.flatMap { b ->
        val t = b.value.text
        b.spans.filter { it.style == InlineStyle.COMMENT && it.url == threadId }.sortedBy { it.start }
            .map { t.substring(it.start.coerceIn(0, t.length), it.end.coerceIn(0, t.length)) }
    }.joinToString(" ").replace(ZWS, "").trim()

    /** 某个位置（输入框偏移）上挂着的评论 */
    fun threadAt(b: BlockState, offset: Int): String? =
        b.spans.firstOrNull { it.style == InlineStyle.COMMENT && it.start <= offset && offset < it.end && thread(it.url) != null }?.url

    /** 工具栏“评论”：给选中的文字（没有选中就是整段）写评论；不能评论时返回原因 */
    fun startComment(): String? {
        val b = focused ?: return "先把光标放到要评论的段落"
        val sel = b.value.selection
        val start = if (sel.collapsed) 1 else max(1, sel.min)
        val end = if (sel.collapsed) b.value.text.length else sel.max
        if (end <= start) return "这一段还没有文字"
        commentView = null
        commentDraft = CommentDraft(b.id, start, end)
        return null
    }

    fun submitDraft(text: String) {
        val d = commentDraft ?: return
        commentDraft = null
        val b = blocks.firstOrNull { it.id == d.blockId } ?: return
        if (text.isBlank()) return
        val t = CommentThread(comments = listOf(Comment(text = text.trim())))
        checkpoint()
        comments += t
        val len = b.value.text.length
        b.spans = applyStyle(b.spans, d.start, d.end.coerceAtMost(len), StyleKey(InlineStyle.COMMENT, url = t.id), on = true).normalized(len)
        changed()
    }

    fun reply(threadId: String, text: String) {
        val i = comments.indexOfFirst { it.id == threadId }
        if (i < 0 || text.isBlank()) return
        comments[i] = comments[i].copy(comments = comments[i].comments + Comment(text = text.trim()))
        changed()
    }

    /** 删除一条评论；删到一条不剩就等于结束评论 */
    fun deleteComment(threadId: String, commentId: String) {
        val i = comments.indexOfFirst { it.id == threadId }
        if (i < 0) return
        val rest = comments[i].comments.filterNot { it.id == commentId }
        if (rest.isEmpty()) resolve(threadId) else {
            comments[i] = comments[i].copy(comments = rest)
            changed()
        }
    }

    /** 结束评论：删掉这条评论以及它在正文里的锚点 */
    fun resolve(threadId: String) {
        comments.removeAll { it.id == threadId }
        blocks.forEach { b ->
            if (b.spans.any { it.style == InlineStyle.COMMENT && it.url == threadId }) {
                b.spans = b.spans.filterNot { it.style == InlineStyle.COMMENT && it.url == threadId }
            }
        }
        if (commentView?.threadId == threadId) commentView = null
        changed()
    }

    // ---------------- 双击选字 ----------------

    /** 记下双击点中的字（[start, end) 为输入框偏移） */
    fun armCharSelect(key: String, start: Int, end: Int) { charSelect = CharSelect(key, start, end) }

    /** 输入框因双击而选中一整段时，把选区换成刚才点中的那一个字 */
    private fun takeCharSelect(key: String, old: TextFieldValue, incoming: TextFieldValue): TextFieldValue? {
        val cs = charSelect ?: return null
        if (cs.key != key || SystemClock.uptimeMillis() - cs.at > 1500) return null
        if (incoming.text != old.text || incoming.selection.collapsed) return null
        charSelect = null
        val end = cs.end.coerceAtMost(incoming.text.length)
        if (end <= cs.start) return null
        return incoming.copy(selection = TextRange(cs.start, end))
    }

    fun activeStyles(): Set<StyleKey> {
        val b = focused ?: return emptySet()
        val sel = b.value.selection
        if (sel.collapsed) return b.typing ?: stylesAt(b.spans, sel.start, 1)
        return b.spans.map { it.key() }.distinct().filter { isFullyStyled(b.spans, sel.min, sel.max, it) }.toSet()
    }

    fun toggleStyle(style: InlineStyle) {
        val b = focused ?: return
        val key = StyleKey(style)
        val sel = b.value.selection
        if (sel.collapsed) {
            val cur = b.typing ?: stylesAt(b.spans, sel.start, 1)
            b.typing = if (key in cur) cur - key else cur + key
            return
        }
        checkpoint()
        val on = !isFullyStyled(b.spans, sel.min, sel.max, key)
        b.spans = applyStyle(b.spans, sel.min, sel.max, key, on).normalized(b.value.text.length)
        changed()
    }

    /** 设置文字颜色或背景高亮；color = 0 表示清除 */
    fun setColor(style: InlineStyle, color: Long) {
        val b = focused ?: return
        val key = StyleKey(style, color)
        val sel = b.value.selection
        if (sel.collapsed) {
            val cur = (b.typing ?: stylesAt(b.spans, sel.start, 1)).filterNot { it.style == style }.toSet()
            b.typing = if (color == 0L) cur else cur + key
            return
        }
        checkpoint()
        b.spans = applyStyle(b.spans, sel.min, sel.max, key, on = color != 0L).normalized(b.value.text.length)
        changed()
    }

    fun clearFormat() {
        val b = focused ?: return
        val sel = b.value.selection
        if (sel.collapsed) {
            b.typing = emptySet()
            return
        }
        checkpoint()
        var spans = b.spans
        // 评论锚点不算格式，清除格式时保留
        spans.map { it.key() }.distinct().filter { it.style != InlineStyle.COMMENT }
            .forEach { spans = applyStyle(spans, sel.min, sel.max, it, on = false, exactOnly = true) }
        b.spans = spans.normalized(b.value.text.length)
        changed()
    }

    fun linkAtCursor(): StyleRange? {
        val b = focused ?: return null
        val pos = b.value.selection.start
        return b.spans.firstOrNull { it.style == InlineStyle.LINK && it.start <= pos && pos <= it.end && it.end > it.start }
    }

    fun selectedText(): String {
        val b = focused ?: return ""
        val sel = b.value.selection
        return if (sel.collapsed) "" else b.value.text.substring(sel.min, sel.max)
    }

    fun applyLink(label: String, url: String) {
        val b = focused ?: return
        val sel = b.value.selection
        checkpoint()
        val key = StyleKey(InlineStyle.LINK, url = url)
        val existing = linkAtCursor()
        when {
            !sel.collapsed -> b.spans = applyStyle(b.spans, sel.min, sel.max, key, true).normalized(b.value.text.length)
            existing != null -> b.spans = b.spans.map { if (it == existing) it.copy(url = url) else it }
            else -> {
                val t = label.ifBlank { url }
                val pos = sel.start
                val newText = b.value.text.substring(0, pos) + t + b.value.text.substring(pos)
                val shifted = adjustSpans(b.spans, b.value.text, newText, emptySet())
                b.spans = applyStyle(shifted, pos, pos + t.length, key, true).normalized(newText.length)
                b.value = TextFieldValue(newText, TextRange(pos + t.length))
                b.typing = emptySet()
            }
        }
        changed()
    }

    fun removeLink() {
        val b = focused ?: return
        val link = linkAtCursor() ?: return
        checkpoint()
        b.spans = b.spans - link
        changed()
    }

    // ---------------- 块的插入与删除 ----------------

    private fun insertAfter(anchor: BlockState, nb: BlockState) {
        blocks.add(blocks.indexOf(anchor) + 1, nb)
        requestFocus(nb, 1)
    }

    /** 在光标所在块之后插入图片 / 表格 / 分割线；光标停在其后的空行 */
    fun insertBlock(block: Block) {
        checkpoint()
        val cur = focused ?: focusedCell?.let { c -> blocks.firstOrNull { it.id == c.blockId } }
        val idx = cur?.let { blocks.indexOf(it) } ?: blocks.lastIndex
        val nb = BlockState(block)
        if (cur != null && cur.type == BlockType.TEXT && cur.text.isEmpty()) {
            blocks.add(idx, nb)
            requestFocus(cur, 1)
        } else {
            blocks.add(idx + 1, nb)
            val next = blocks.getOrNull(idx + 2)
            if (next != null && next.type == BlockType.TEXT && next.text.isEmpty()) requestFocus(next, 1)
            else insertAfter(nb, BlockState(Block()))
        }
        changed()
    }

    fun deleteBlock(b: BlockState) {
        checkpoint()
        blocks.remove(b)
        if (blocks.isEmpty()) blocks += BlockState(Block())
        if (focusedCell?.blockId == b.id) focusedCell = null
        changed()
    }

    /** 点击正文下方空白处：聚焦到最后一个文本块，必要时新建一行 */
    fun focusEnd() {
        val last = blocks.last()
        if (last.type == BlockType.TEXT && last.text.isEmpty()) requestFocus(last, 1)
        else if (last.type.isText) requestFocus(last, last.value.text.length)
        else {
            checkpoint()
            insertAfter(last, BlockState(Block()))
            changed()
        }
    }

    // ---------------- 表格 ----------------

    fun setCell(b: BlockState, row: Int, col: Int, v: String) {
        if (b.table.getOrNull(row)?.getOrNull(col) == v) return
        typingCheckpoint()
        b.table = b.table.mapIndexed { i, r -> if (i == row) r.mapIndexed { j, x -> if (j == col) v else x } else r }
        changed()
    }

    fun tableOp(op: TableOp) {
        val ref = focusedCell ?: return
        val b = blocks.firstOrNull { it.id == ref.blockId } ?: return
        val t = b.table
        val cols = t.maxOfOrNull { it.size } ?: 0
        if (op == TableOp.DELETE_TABLE || (op == TableOp.DELETE_ROW && t.size <= 1) || (op == TableOp.DELETE_COL && cols <= 1)) {
            deleteBlock(b)
            return
        }
        checkpoint()
        b.table = when (op) {
            TableOp.ROW_ABOVE -> t.toMutableList().apply { add(ref.row, List(cols) { "" }) }
            TableOp.ROW_BELOW -> t.toMutableList().apply { add(ref.row + 1, List(cols) { "" }) }
            TableOp.COL_LEFT -> t.map { r -> r.toMutableList().apply { add(ref.col.coerceAtMost(size), "") } }
            TableOp.COL_RIGHT -> t.map { r -> r.toMutableList().apply { add((ref.col + 1).coerceAtMost(size), "") } }
            TableOp.DELETE_ROW -> t.filterIndexed { i, _ -> i != ref.row }
            TableOp.DELETE_COL -> t.map { r -> r.filterIndexed { j, _ -> j != ref.col } }
            TableOp.DELETE_TABLE -> t
        }
        if (op == TableOp.DELETE_ROW || op == TableOp.DELETE_COL) focusedCell = null
        changed()
    }

    // ---------------- 查找替换 ----------------

    /** 一处匹配：文本块中为 [start, end)（输入框坐标，含哨兵）；表格中 row / col 指出单元格 */
    data class Match(val blockId: String, val start: Int, val end: Int, val row: Int = -1, val col: Int = -1)

    var findMode by mutableStateOf(false)
        private set
    var findQuery by mutableStateOf("")
        private set
    var matches by mutableStateOf<List<Match>>(emptyList())
        private set
    var matchIndex by mutableIntStateOf(0)
        private set
    var replacedInFind by mutableStateOf(false)
        private set

    /** 每个文本块最近一次的排版结果，用来把当前匹配滚动到可见位置 */
    val layouts = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()

    fun openFind() {
        findMode = true
        focusedId = null
        focusedCell = null
        replacedInFind = false
        updateMatches()
    }

    fun closeFind() {
        findMode = false
        findQuery = ""
        matches = emptyList()
    }

    fun setQuery(q: String) {
        findQuery = q
        matchIndex = 0
        updateMatches()
    }

    fun updateMatches() {
        val q = findQuery
        if (q.isEmpty()) {
            matches = emptyList()
            return
        }
        val out = mutableListOf<Match>()
        blocks.forEach { b ->
            if (b.type == BlockType.TABLE) {
                b.table.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell -> findAll(cell, q, 0).forEach { out += Match(b.id, it, it + q.length, r, c) } }
                }
            } else if (b.type.isText) {
                findAll(b.value.text, q, 1).forEach { out += Match(b.id, it, it + q.length) }
            }
        }
        matches = out
        matchIndex = if (out.isEmpty()) 0 else matchIndex.coerceIn(0, out.lastIndex)
    }

    private fun findAll(text: String, q: String, from: Int): List<Int> {
        val res = mutableListOf<Int>()
        var i = text.indexOf(q, from, ignoreCase = true)
        while (i >= 0) {
            res += i
            i = text.indexOf(q, i + q.length, ignoreCase = true)
        }
        return res
    }

    fun moveMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + delta + matches.size) % matches.size
    }

    fun replaceCurrent(replacement: String) {
        val m = matches.getOrNull(matchIndex) ?: return
        checkpoint()
        applyReplace(m, replacement)
        replacedInFind = true
        changed()
        updateMatches()
    }

    fun replaceAll(replacement: String): Int {
        val count = matches.size
        if (count == 0) return 0
        Repository.addVersion(docId, VersionKind.LOCAL, title.text, content(), "全部替换前自动保存")
        checkpoint()
        // 同一处从后往前替换，前面匹配的位置不受影响
        matches.sortedWith(compareBy<Match>({ it.blockId }, { it.row }, { it.col }).thenByDescending { it.start })
            .forEach { applyReplace(it, replacement) }
        replacedInFind = true
        changed()
        updateMatches()
        return count
    }

    fun undoReplace() {
        undo()
        updateMatches()
    }

    private fun applyReplace(m: Match, r: String) {
        val b = blocks.firstOrNull { it.id == m.blockId } ?: return
        if (m.row >= 0) {
            val cell = b.table.getOrNull(m.row)?.getOrNull(m.col) ?: return
            if (m.end > cell.length) return
            val v = cell.substring(0, m.start) + r + cell.substring(m.end)
            b.table = b.table.mapIndexed { i, row -> if (i == m.row) row.mapIndexed { j, x -> if (j == m.col) v else x } else row }
        } else {
            val old = b.value.text
            if (m.end > old.length) return
            val new = old.substring(0, m.start) + r + old.substring(m.end)
            b.spans = adjustSpans(b.spans, old, new, stylesAt(b.spans, m.start + 1, 1))
            b.value = TextFieldValue(new, TextRange(m.start + r.length))
        }
    }

    // ---------------- 其它 ----------------

    fun numberIndex(i: Int): Int {
        val b = blocks[i]
        var n = 1
        var j = i - 1
        while (j >= 0) {
            val p = blocks[j]
            if (p.type.isList && p.indent > b.indent) { j--; continue }
            if (p.type == BlockType.NUMBER && p.indent == b.indent) { n++; j--; continue }
            break
        }
        return n
    }

    fun headings() = blocks.filter { it.type.isHeading && it.text.isNotBlank() }
}
