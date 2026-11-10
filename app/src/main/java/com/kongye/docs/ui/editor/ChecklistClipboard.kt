package com.kongye.docs.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kongye.docs.data.BlockType

private val checklistPrefix = Regex("""^([ \t]*)(?:([☐☑☒□])(?:\uFE0F)?(?:[ \t]+|$)|(?:[-*+] +)?\[([ xX])](?:[ \t]+|$))""")

/** Keyboard clipboard history may retain only plain text, including the checkbox marker. */
internal fun BlockState.restoreClipboardChecklist(): Int {
    if (!type.isText || type == BlockType.CODE) return 0
    val marker = checklistPrefix.find(text) ?: return 0
    val removed = marker.value.length
    val old = value
    val nextText = ZWS + text.substring(removed)
    fun offset(value: Int) = (value - removed).coerceIn(1, nextText.length)
    type = BlockType.TODO
    checked = marker.groupValues[2] in listOf("☑", "☒") || marker.groupValues[3].equals("x", ignoreCase = true)
    marker.groupValues[1].takeIf { it.isNotEmpty() }?.let { indent = (it.replace("\t", "  ").length / 2).coerceIn(0, 4) }
    value = TextFieldValue(nextText, TextRange(offset(old.selection.start), offset(old.selection.end)))
    spans = spans.mapNotNull { span ->
        val start = (span.start - removed).coerceAtLeast(1)
        val end = (span.end - removed).coerceIn(1, nextText.length)
        if (end > start) span.copy(start = start, end = end) else null
    }
    return removed
}
