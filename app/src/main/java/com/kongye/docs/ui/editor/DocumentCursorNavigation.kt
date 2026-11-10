package com.kongye.docs.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import java.text.BreakIterator

/** IME direction keys and physical arrow keys both pass through this modifier. */
internal fun Modifier.documentCursorKeys(editor: EditorState): Modifier = onPreviewKeyEvent { event ->
    editor.cursorNavigation.handle(event.nativeKeyEvent)
}

/** Keep the preferred screen column while moving through wrapped and short paragraphs. */
internal class DocumentCursorNavigation(private val editor: EditorState) {
    internal data class VerticalStep(val point: SelectionPoint, val column: Float)
    private var column: Float? = null
    private var expected: SelectionPoint? = null
    private var expectedText: String? = null

    fun reset() { column = null; expected = null; expectedText = null }

    fun handle(event: KeyEvent): Boolean {
        if(editor.focusedId!=null && !editor.titleFocused && editor.focusedCell==null && !editor.findMode && editor.commentDraft==null && editor.commentView==null) {
            val undo=event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_Z && !event.isShiftPressed
            val redo=event.isCtrlPressed && (event.keyCode==KeyEvent.KEYCODE_Y || event.keyCode==KeyEvent.KEYCODE_Z && event.isShiftPressed)
            if(undo || redo) { if(event.action==KeyEvent.ACTION_DOWN) { if(redo) editor.redo() else editor.undo() }; return true }
            if(event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                if(event.action==KeyEvent.ACTION_DOWN) editor.keyboardSelection.move(when(event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"; KeyEvent.KEYCODE_DPAD_UP -> "up"; else -> "down"
                },chooseHandle=false)
                return true
            }
        }
        val arrow = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        if (!arrow || event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            if (event.action == KeyEvent.ACTION_DOWN) reset()
            return false
        }
        val key = editor.focusedId ?: return false
        if (editor.titleFocused || editor.focusedCell != null || editor.findMode || editor.commentDraft != null) return false
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        editor.keyboardSelection.end()
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        editor.selection.range?.let { range ->
            val point = if (forward) range.end else range.start
            editor.selection.clear()
            reset()
            editor.placeCaret(point, deferFocus = true)
            return true
        }
        return if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            horizontal(key, forward) else move(key, forward)
    }

    private fun horizontal(key: String, forward: Boolean): Boolean {
        val b = editor.blocks.firstOrNull { it.id == key && it.type.isText } ?: return false
        reset()
        val range = b.value.selection
        if (!range.collapsed) {
            editor.placeCaret(SelectionPoint(key, if (forward) range.max else range.min), deferFocus = true)
            return true
        }
        val offset = range.end.coerceIn(1, b.value.text.length)
        if (forward && offset < b.value.text.length || !forward && offset > 1) {
            val boundary = BreakIterator.getCharacterInstance().apply { setText(b.value.text) }
            val next = if (forward) boundary.following(offset) else boundary.preceding(offset)
            editor.placeCaret(SelectionPoint(key, next.coerceIn(1, b.value.text.length)), deferFocus = true)
        } else {
            val index = editor.blocks.indexOf(b)
            val indices = if (forward) (index + 1 until editor.blocks.size) else (index - 1 downTo 0)
            val next = indices.firstOrNull { editor.blocks[it].type.isText }?.let { editor.blocks[it] }
            if (next != null) editor.placeCaret(SelectionPoint(next.id, if (forward) 1 else next.value.text.length), deferFocus = true)
        }
        return true
    }

    fun move(key: String, down: Boolean): Boolean {
        if (editor.focusedId != key || editor.titleFocused || editor.focusedCell != null ||
            editor.findMode || editor.commentDraft != null || editor.selection.active) return false
        val source = editor.blocks.firstOrNull { it.id == key && it.type.isText } ?: return false
        val value = source.value
        if (!value.selection.collapsed) return false
        val point = SelectionPoint(key, value.selection.end.coerceIn(1, value.text.length))
        val preferred = column?.takeIf { expected == point && expectedText == value.text }
        val step = vertical(point, down, preferred) ?: return false
        source.value = source.value.copy(composition = null)
        editor.placeCaret(step.point, deferFocus = true)
        column = step.column
        expected = step.point
        expectedText = editor.blocks.first { it.id == step.point.key }.value.text
        return true
    }

    /** Pure hit testing shared by caret navigation and document-wide keyboard selection. */
    internal fun vertical(point: SelectionPoint, down: Boolean, preferredColumn: Float? = null): VerticalStep? {
        val key = point.key
        val source = editor.blocks.firstOrNull { it.id == key && it.type.isText } ?: return null
        val value = source.value
        val layout = editor.layouts[key]?.takeIf { it.layoutInput.text.text == value.text } ?: return null
        val caret = point.offset.coerceIn(1, value.text.length)
        fun origin(id: String): Float = editor.selection.coordinates[id]?.takeIf { it.isAttached }
            ?.localToRoot(Offset.Zero)?.x ?: 0f
        val x = preferredColumn ?: (origin(key) + layout.getCursorRect(caret).left)
        val line = layout.getLineForOffset(caret)
        val nextLine = line + if (down) 1 else -1
        var target = source
        var targetLayout = layout
        var targetLine = nextLine
        if (nextLine !in 0 until layout.lineCount) {
            val index = editor.blocks.indexOf(source)
            val indices = if (down) (index + 1 until editor.blocks.size) else (index - 1 downTo 0)
            val adjacent = indices.firstOrNull { editor.blocks[it].type.isText }
            if (adjacent == null) {
                // Match ordinary text fields at the beginning/end of the document.
                val end = if (down) value.text.length else 1
                return VerticalStep(SelectionPoint(key, end), x)
            }
            target = editor.blocks[adjacent]
            targetLayout = editor.layouts[target.id]?.takeIf { it.layoutInput.text.text == target.value.text }
                ?: return null
            targetLine = if (down) 0 else targetLayout.lineCount - 1
        }
        val y = (targetLayout.getLineTop(targetLine) + targetLayout.getLineBottom(targetLine)) / 2f
        var offset = targetLayout.getOffsetForPosition(Offset(x - origin(target.id), y))
            .coerceIn(targetLayout.getLineStart(targetLine), targetLayout.getLineEnd(targetLine, visibleEnd = true))
            .coerceIn(1, target.value.text.length)
        // A soft-wrap boundary belongs to the following line. Keep a short target
        // line on that line, without splitting a surrogate pair or combining glyph.
        if (targetLayout.getLineForOffset(offset) > targetLine) {
            offset = BreakIterator.getCharacterInstance().apply { setText(target.value.text) }
                .preceding(offset).coerceAtLeast(1)
        }
        return VerticalStep(SelectionPoint(target.id, offset), x)
    }
}
