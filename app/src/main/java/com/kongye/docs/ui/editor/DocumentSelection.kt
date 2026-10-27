package com.kongye.docs.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kongye.docs.data.*

data class SelectionPoint(val key: String, val offset: Int)
data class DocumentRange(val start: SelectionPoint, val end: SelectionPoint)

/** One selection for the entire document; the individual fields only retain their local ranges. */
class DocumentSelection(internal val editor: EditorState) {
    var range by mutableStateOf<DocumentRange?>(null)
        private set
    var all by mutableStateOf(false)
        private set
    var fromKeyboard by mutableStateOf(false)
    var dragRoot by mutableStateOf<Offset?>(null)
    var dragStart by mutableStateOf<Boolean?>(null)
    var viewport: LayoutCoordinates? = null
    var layoutEpoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private val origins = HashMap<String, Pair<Offset, androidx.compose.ui.unit.IntSize>>()
    fun register(key: String, coordinates: LayoutCoordinates) {
        this.coordinates[key] = coordinates
        val next = coordinates.localToRoot(Offset.Zero) to coordinates.size
        if (origins.put(key,next) != next) layoutEpoch++
    }
    val coordinates = HashMap<String, LayoutCoordinates>()
    val active get() = range != null
    private fun index(key: String) = if (key == TITLE_KEY) -1 else editor.blocks.indexOfFirst { it.id == key }
    private fun value(key: String) = if (key == TITLE_KEY) editor.title else editor.blocks.first { it.id == key }.value
    private fun min(key: String) = if (key == TITLE_KEY) 0 else 1
    private fun point(p: SelectionPoint): SelectionPoint = p.copy(offset = p.offset.coerceIn(min(p.key), value(p.key).text.length))
    private fun compare(a: SelectionPoint, b: SelectionPoint): Int = if (a.key == b.key) a.offset.compareTo(b.offset) else index(a.key).compareTo(index(b.key))

    fun set(a: SelectionPoint, b: SelectionPoint, entire: Boolean = false) {
        val left = point(a); val right = point(b)
        if (left == right && !entire) return
        range = if (compare(left,right) <= 0) DocumentRange(left,right) else DocumentRange(right,left)
        all = entire
        fromKeyboard = false
        editor.pendingFocus = null
        sync()
        editor.focusedId = range!!.start.key.takeIf { it != TITLE_KEY }
        editor.focusedCell = null
    }

    fun selectAll() {
        val first = editor.blocks.firstOrNull { it.type.isText } ?: editor.blocks.firstOrNull() ?: return
        val last = editor.blocks.lastOrNull { it.type.isText } ?: first
        set(SelectionPoint(first.id,1),SelectionPoint(last.id,last.value.text.length),true)
    }

    fun clear() {
        range = null; all = false; fromKeyboard = false; endDrag()
        editor.title = editor.title.copy(selection = TextRange(editor.title.selection.end), composition = null)
        editor.blocks.filter { it.type.isText }.forEach { it.value = it.value.copy(selection = TextRange(it.value.selection.end), composition = null) }
    }

    fun containsBlock(key: String): Boolean {
        if (all) return key != TITLE_KEY
        val r = range ?: return false
        return index(key) in index(r.start.key)..index(r.end.key)
    }

    fun localRange(key: String): TextRange? {
        val r = range ?: return null
        if (!containsBlock(key)) return null
        return TextRange(if (key == r.start.key) r.start.offset else min(key), if (key == r.end.key) r.end.offset else value(key).text.length)
    }

    private fun sync() {
        editor.title = editor.title.copy(selection = localRange(TITLE_KEY) ?: TextRange(editor.title.selection.end), composition = null)
        editor.blocks.filter { it.type.isText }.forEach { b ->
            b.value = b.value.copy(selection = localRange(b.id) ?: TextRange(b.value.selection.end), composition = null)
        }
    }

    fun text(): String {
        if (all) return editor.content().plainText()
        if (!active) return ""
        val pieces = mutableListOf<String>()
        localRange(TITLE_KEY)?.let { pieces += editor.title.text.substring(it.min,it.max) }
        editor.blocks.forEach { b ->
            if (b.type.isText) localRange(b.id)?.let { pieces += b.value.text.substring(it.min,it.max) }
            else if (containsBlock(b.id)) pieces += DocContent(listOf(b.toBlock())).plainText()
        }
        return pieces.joinToString("\n")
    }

    fun endpoint(root: Offset): SelectionPoint? {
        val entries = coordinates.entries.filter { (key,c) -> c.isAttached && (key == TITLE_KEY || editor.blocks.any { it.id == key && it.type.isText }) }
        val entry = entries.minByOrNull { (_,c) ->
            val top = c.localToRoot(Offset.Zero).y
            when { root.y < top -> top-root.y; root.y > top+c.size.height -> root.y-top-c.size.height; else -> 0f }
        } ?: return null
        val layout = editor.layouts[entry.key] ?: return null
        val p = entry.value.localPositionOfRoot(root)
        val offset = layout.getOffsetForPosition(p).coerceIn(min(entry.key),layout.layoutInput.text.length)
        // Keep handle endpoints on Unicode grapheme boundaries.
        val boundary = java.text.BreakIterator.getCharacterInstance().apply { setText(layout.layoutInput.text.text) }
        val safe = if (boundary.isBoundary(offset)) offset else boundary.preceding(offset).coerceAtLeast(min(entry.key))
        return SelectionPoint(entry.key,safe)
    }

    private var dragAnchor: SelectionPoint? = null

    fun beginDrag(start: Boolean) {
        val r = range ?: return
        dragAnchor = if(start) r.end else r.start
        dragStart = start
    }

    fun endDrag() { dragAnchor = null; dragStart = null; dragRoot = null }

    fun move(start: Boolean, root: Offset) {
        val r = range ?: return
        val next = endpoint(root) ?: return
        val anchor = dragAnchor ?: if(start) r.end else r.start
        if(next == anchor) return
        // Keep the opposite endpoint fixed for this gesture. Crossing it swaps the
        // visual start/end handles, rather than blocking or moving the fixed anchor.
        set(next,anchor)
        if(dragAnchor != null) dragStart = compare(next,anchor) < 0
    }

    /** Cut/paste a spanning range while keeping unselected blocks, formatting and comment anchors. */
    fun replace(text: String) {
        val r = range ?: return
        if (all) { editor.replaceArticleSelection(text); return }
        editor.checkpoint()
        val si = index(r.start.key); val ei = index(r.end.key)
        val insertion = text.replace("\r\n","\n").replace('\r','\n').replace(ZWS,"")
        if (ei < 0) {
            val next = editor.title.text.replaceRange(r.start.offset,r.end.offset,insertion.replace("\n",""))
            clear(); editor.title = TextFieldValue(next,TextRange(r.start.offset+insertion.replace("\n","").length))
            editor.selectionEdited(); return
        }
        val end = editor.blocks[ei]
        val suffix = end.value.text.substring(r.end.offset)
        val suffixStyles = clip(end.spans,r.end.offset,end.value.text.length)
        if (si < 0) {
            val prefix = editor.title.text.take(r.start.offset)
            val lines = insertion.split('\n')
            val bodyInsertion = lines.drop(1).joinToString("\n")
            val remaining = bodyInsertion+suffix
            val spans = suffixStyles.shifted(bodyInsertion.length)
            clear()
            repeat(ei+1) { editor.blocks.removeAt(0) }
            editor.title = TextFieldValue(prefix+lines.first())
            if (remaining.isNotEmpty()) {
                var offset = 0
                val kept = remaining.split('\n').map { line ->
                    val block = BlockState(end.toBlock().copy(id=newId(),text=line,spans=clip(spans,offset,offset+line.length)))
                    offset += line.length+1
                    block
                }
                editor.blocks.addAll(0,kept)
            }
            if (editor.blocks.isEmpty()) editor.blocks += BlockState(Block())
            editor.requestFocus(editor.blocks.first(),1)
        } else {
            val first = editor.blocks[si]
            val prefix = first.value.text.substring(1,r.start.offset)
            val combined = prefix+insertion+suffix
            val styles = clip(first.spans,1,r.start.offset) + suffixStyles.shifted(prefix.length+insertion.length)
            if (si == ei && first.type == BlockType.CODE) {
                clear()
                first.value = TextFieldValue(ZWS+combined,TextRange(1+prefix.length+insertion.length))
                first.spans = styles.shifted(1).normalized(first.value.text.length)
                editor.requestFocus(first,first.value.selection.end)
                editor.selectionEdited()
                return
            }
            val lines = combined.split('\n')
            var offset = 0
            val blocks = lines.mapIndexed { i,line ->
                val block = BlockState(first.toBlock().copy(id=newId(),type=if(i==0) first.type else BlockType.TEXT,text=line,spans=clip(styles,offset,offset+line.length)))
                offset += line.length+1
                block
            }
            clear()
            repeat(ei-si+1) { editor.blocks.removeAt(si) }
            editor.blocks.addAll(si,blocks)
            editor.requestFocus(blocks.last(),1+(prefix+insertion).substringAfterLast('\n').length)
        }
        editor.selectionEdited()
    }

    private fun clip(spans: List<StyleRange>, from: Int, to: Int) = spans.filter { it.start < to && it.end > from }
        .map { it.copy(start=maxOf(it.start,from)-from,end=minOf(it.end,to)-from) }
}

private fun LayoutCoordinates.localPositionOfRoot(root: Offset): Offset = root-localToRoot(Offset.Zero)
