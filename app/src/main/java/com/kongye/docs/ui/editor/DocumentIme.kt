package com.kongye.docs.ui.editor

import android.content.Context
import com.kongye.docs.data.BlockType
import android.view.View
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** One UTF-16 coordinate space for all keyboards; rendering remains block based. */
internal class DocumentIme(private val editor: EditorState, private val view: View) {
    data class Entry(val block: BlockState, val start: Int, val length: Int)
    data class Snapshot(val text: String, val entries: List<Entry>, val start: Int, val end: Int, val composingStart: Int, val composingEnd: Int) {
        fun offset(point: SelectionPoint): Int? = entries.firstOrNull { it.block.id == point.key }
            ?.let { it.start + (point.offset - 1).coerceIn(0, it.length) }
        fun point(offset: Int, images: Boolean = false): SelectionPoint? {
            val at = offset.coerceIn(0, text.length)
            if(images) entries.firstOrNull { it.block.type==BlockType.IMAGE && at in it.start..it.start+1 }?.let {
                return SelectionPoint(it.block.id,1+at-it.start)
            }
            val entry = entries.firstOrNull { it.block.type.isText && at in it.start..it.start + it.length }
                ?: entries.firstOrNull { it.block.type.isText && it.start >= at }
                ?: entries.lastOrNull { it.block.type.isText } ?: return null
            return SelectionPoint(entry.block.id, 1 + (at - entry.start).coerceIn(0, entry.length))
        }
        fun extracted(maxChars: Int = 100000): ExtractedText? {
            val limit=maxChars.coerceIn(1,100000)
            val low=minOf(start,end); val high=maxOf(start,end)
            if(high-low>limit) return null
            var from=(low-(limit-(high-low))/2).coerceAtLeast(0)
            var to=(from.toLong()+limit).coerceAtLeast(high.toLong()).coerceAtMost(text.length.toLong()).toInt()
            if(from>0 && from<text.length && Character.isLowSurrogate(text[from])) from--
            if(to>0 && to<text.length && Character.isLowSurrogate(text[to])) to--
            return ExtractedText().also {
                it.text = text.substring(from,to); it.startOffset = from; it.partialStartOffset = -1; it.partialEndOffset = -1
                it.selectionStart = start-from; it.selectionEnd = end-from
            }
        }
    }

    var active = false
    var monitorToken: Int? = null
    var monitorLimit = 100000
    var shiftHeld = false
    var batchDepth = 0
    var nativeEpoch = -1
    private var directed: Pair<SelectionPoint, SelectionPoint>? = null
    val available get() = active && editor.bodyInputActive && !editor.titleFocused &&
        editor.focusedCell == null && editor.commentDraft == null && editor.commentView == null && !editor.findMode

    fun snapshot(): Snapshot {
        val text = StringBuilder()
        val entries = editor.blocks.mapIndexed { index, block ->
            if (index > 0) text.append('\n')
            val body = if (block.type.isText) block.text else "\uFFFC"
            Entry(block, text.length, body.length).also { text.append(body) }
        }
        fun offset(point: SelectionPoint) = entries.firstOrNull { it.block.id == point.key }
            ?.let { it.start + (point.offset - 1).coerceIn(0, it.length) } ?: 0
        val range = editor.selection.range
        val direction = directed?.takeIf { (a,b) -> range != null &&
            setOf(a,b) == setOf(range.start,range.end) }
        val focused = editor.focused
        val start = if(editor.imageCaret!=null && range==null) offset(editor.imageCaret!!) else if (range != null) offset(direction?.first ?: range.start)
            else focused?.let { offset(SelectionPoint(it.id,it.value.selection.start)) } ?: 0
        val end = if(editor.imageCaret!=null && range==null) start else if (range != null) offset(direction?.second ?: range.end)
            else focused?.let { offset(SelectionPoint(it.id,it.value.selection.end)) } ?: start
        val composition = focused?.value?.composition?.takeIf { range == null }
        return Snapshot(text.toString(),entries,start,end,
            composition?.let { offset(SelectionPoint(focused.id,it.min)) } ?: -1,
            composition?.let { offset(SelectionPoint(focused.id,it.max)) } ?: -1)
    }

    fun notifyIme() {
        if (!available || batchDepth > 0) return
        val state = snapshot()
        val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.updateSelection(view,state.start,state.end,state.composingStart,state.composingEnd)
        monitorToken?.let { token -> state.extracted(monitorLimit)?.let { manager.updateExtractedText(view,token,it) } }
    }

    fun finishComposition(): Boolean {
        editor.blocks.filter { it.type.isText && it.value.composition != null }.forEach { it.value = it.value.copy(composition = null) }
        editor.invalidateImeCallbacks()
        return true
    }

    fun select(start: Int, end: Int): Boolean {
        if (start < 0 || end < 0) return false
        val state = snapshot()
        val a = state.point(start,images=start==end) ?: return false
        val b = state.point(end,images=start==end) ?: return false
        if (editor.selection.range?.let { setOf(it.start,it.end)==setOf(a,b) }==true) {
            directed=a to b
            return true
        }
        finishComposition()
        editor.cursorNavigation.reset()
        editor.keyboardSelection.end()
        if (a == b) {
            editor.selection.clear()
            editor.placeCaret(a,deferFocus = true)
            directed = null
        } else {
            val liveInput = editor.focusedId
            editor.selection.set(a,b,entire = minOf(start,end) == 0 && maxOf(start,end) >= state.text.length)
            editor.focusedId = liveInput?.takeIf { id -> editor.blocks.any { it.id==id && it.type.isText } } ?: a.key
            directed = a to b
        }
        editor.keyboardSelection.reveal(b)
        return true
    }

    fun selectionMoved() {
        val range = editor.selection.range ?: run { directed = null; return }
        directed = if (editor.keyboardSelection.endpoint == range.start) range.end to range.start else range.start to range.end
    }

    fun beginShiftSelection() {
        val state = snapshot()
        editor.keyboardSelection.start(activeStart = state.end < state.start)
    }

    fun insert(text: CharSequence?, cursor: Int, composing: Boolean): Boolean {
        val insertion = text?.toString().orEmpty().replace(ZWS,"")
        directed = null
        if(editor.imageCaret!=null) return editor.insertAtImageCaret(insertion,composing)
        if (editor.selection.active) {
            editor.selection.replace(insertion)
            val target = editor.pendingFocus?.let { focus -> editor.blocks.firstOrNull { it.id == focus.blockId } } ?: editor.focused
            if (composing && !insertion.contains('\n') && target != null && insertion.isNotEmpty()) {
                val end = target.value.selection.end
                target.value = target.value.copy(composition = TextRange((end - insertion.length).coerceAtLeast(1),end))
            }
        } else {
            val block = editor.focused?.takeIf { it.type.isText } ?: return false
            val old = block.value
            val replace = old.composition ?: old.selection
            val from = replace.min.coerceIn(1,old.text.length)
            val to = replace.max.coerceIn(from,old.text.length)
            val next = old.text.replaceRange(from,to,insertion)
            val caret = (if (cursor > 0) from + insertion.length + cursor - 1 else from + cursor).coerceIn(1,next.length)
            val composition = if (composing && insertion.isNotEmpty()) TextRange(from,from + insertion.length) else null
            editor.onValueChange(block,TextFieldValue(next,TextRange(caret),composition))
        }
        editor.invalidateImeCallbacks()
        return true
    }

    fun composingRegion(start: Int, end: Int): Boolean {
        val state = snapshot()
        val a = state.point(minOf(start,end)) ?: return false
        val b = state.point(maxOf(start,end)) ?: return false
        if (a.key != b.key) return false
        val block = editor.blocks.first { it.id == a.key }
        block.value = block.value.copy(composition = TextRange(a.offset,b.offset))
        editor.invalidateImeCallbacks()
        return true
    }

    fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (before < 0 || after < 0) return false
        if (editor.selection.active) { editor.selection.replace(""); editor.invalidateImeCallbacks(); return true }
        val state = snapshot()
        val low = minOf(state.start,state.end); val high = maxOf(state.start,state.end)
        val focused=editor.focused
        if(before==1 && after==0 && low==high && focused?.value?.selection==TextRange(1) && focused.value.composition==null) {
            // Preserve the editor's existing list/indent/code backspace semantics.
            editor.onValueChange(focused,TextFieldValue(focused.text,TextRange.Zero))
            editor.invalidateImeCallbacks()
            return true
        }
        val from = if (codePoints) Character.offsetByCodePoints(state.text,low,-minOf(before,Character.codePointCount(state.text,0,low)))
            else (low - before.toLong()).coerceAtLeast(0).toInt()
        val to = if (codePoints) Character.offsetByCodePoints(state.text,high,minOf(after,Character.codePointCount(state.text,high,state.text.length)))
            else (high + after.toLong()).coerceAtMost(state.text.length.toLong()).toInt()
        if (from == to) return true
        val a = state.point(from) ?: return false; val b = state.point(to) ?: return false
        if (a.key == b.key) {
            val block = editor.blocks.first { it.id == a.key }
            editor.onValueChange(block,TextFieldValue(block.value.text.removeRange(a.offset,b.offset),TextRange(a.offset)))
        } else {
            select(from,to); editor.selection.replace("")
        }
        editor.invalidateImeCallbacks()
        return true
    }
}
