package com.kongye.docs.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.BreakIterator

/** The keyboard chooses a handle; the document owns its offsets and visual lines. */
internal class KeyboardSelection(private val editor: EditorState) {
    var endpoint by mutableStateOf<SelectionPoint?>(null)
        private set
    private var left: SelectionPoint? = null
    private var right: SelectionPoint? = null
    private var activeLeft = false
    private var column: Float? = null
    private var expectedRange: DocumentRange? = null
    private var version = -1

    fun end() { left = null; right = null; endpoint = null; column = null; expectedRange = null }

    fun start(): Boolean {
        end()
        val focused = editor.focused?.takeIf { it.type.isText } ?: return false
        val range = editor.selection.range?.takeIf { range ->
            listOf(range.start, range.end).all { point -> editor.blocks.any { it.id == point.key && it.type.isText } }
        }
        left = range?.start ?: SelectionPoint(focused.id, focused.value.selection.min.coerceAtLeast(1))
        right = range?.end ?: SelectionPoint(focused.id, focused.value.selection.max.coerceAtLeast(1))
        activeLeft = false
        version = editor.version
        expectedRange = editor.selection.range
        endpoint = right
        editor.insertionHandle = null
        return true
    }

    private fun compare(a: SelectionPoint, b: SelectionPoint): Int = if (a.key == b.key) a.offset.compareTo(b.offset)
        else editor.blocks.indexOfFirst { it.id == a.key }.compareTo(editor.blocks.indexOfFirst { it.id == b.key })

    private fun horizontal(point: SelectionPoint, forward: Boolean): SelectionPoint {
        val index = editor.blocks.indexOfFirst { it.id == point.key }
        if (index < 0) return point
        val b = editor.blocks[index]
        if (forward && point.offset < b.value.text.length || !forward && point.offset > 1) {
            val boundary = BreakIterator.getCharacterInstance().apply { setText(b.value.text) }
            val offset = if (forward) boundary.following(point.offset) else boundary.preceding(point.offset)
            return point.copy(offset = offset.coerceIn(1, b.value.text.length))
        }
        val indices = if (forward) (index + 1 until editor.blocks.size) else (index - 1 downTo 0)
        val next = indices.firstOrNull { editor.blocks[it].type.isText }?.let { editor.blocks[it] } ?: return point
        return SelectionPoint(next.id, if (forward) 1 else next.value.text.length)
    }

    fun move(direction: String): Boolean {
        if (direction !in listOf("left", "right", "up", "down", "home", "end")) return false
        if (left == null || version != editor.version || expectedRange != editor.selection.range) if (!start()) return false
        val previousSide = activeLeft
        if (direction == "left") activeLeft = true
        if (direction == "right") activeLeft = false
        val point = (if (activeLeft) left else right) ?: return false
        val next = when (direction) {
            "left", "right" -> { column = null; horizontal(point, direction == "right") }
            "home", "end" -> {
                column = null
                val b = editor.blocks.first { it.id == point.key }
                point.copy(offset = if (direction == "home") 1 else b.value.text.length)
            }
            else -> {
                if (previousSide != activeLeft) column = null
                val step = editor.cursorNavigation.vertical(point, direction == "down", column) ?: return false
                column = step.column
                step.point
            }
        }
        val fixed = (if (activeLeft) right else left) ?: return false
        if (compare(next, fixed) < 0) { left = next; right = fixed; activeLeft = true }
        else if (compare(next, fixed) > 0) { left = fixed; right = next; activeLeft = false }
        else { left = next; right = next }
        val liveInput = editor.focusedId
        if (left == right) {
            editor.selection.clear()
            editor.placeCaret(next, deferFocus = true)
        } else {
            editor.selection.set(left!!, right!!)
            // Preserve the attached input field even if it is outside the new range.
            // Typing still replaces the document range through onValueChange.
            editor.focusedId = liveInput
        }
        expectedRange = editor.selection.range
        endpoint = next
        return true
    }
}
