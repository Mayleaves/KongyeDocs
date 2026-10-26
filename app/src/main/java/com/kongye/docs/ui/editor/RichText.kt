package com.kongye.docs.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.kongye.docs.data.InlineStyle
import com.kongye.docs.data.StyleRange
import com.kongye.docs.ui.theme.Kongye
import kotlin.math.max
import kotlin.math.min

/** 不含区间的样式描述，例如 “红色文字” “加粗” */
data class StyleKey(val style: InlineStyle, val color: Long = 0L, val url: String = "")

fun StyleRange.key() = StyleKey(style, color, url)

/** 颜色、高亮、链接同一时间只能有一种取值，其余样式按完全相同来匹配 */
private fun InlineStyle.exclusive() = this == InlineStyle.COLOR || this == InlineStyle.HIGHLIGHT || this == InlineStyle.LINK

/** 裁剪越界区间、去掉空区间、合并相邻或重叠的同类区间 */
fun List<StyleRange>.normalized(textLength: Int): List<StyleRange> {
    val clamped = mapNotNull {
        val s = it.start.coerceIn(0, textLength)
        val e = it.end.coerceIn(0, textLength)
        if (e > s) it.copy(start = s, end = e) else null
    }
    val out = mutableListOf<StyleRange>()
    clamped.groupBy { it.key() }.values.forEach { group ->
        val sorted = group.sortedBy { it.start }
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            if (r.start <= cur.end) cur = cur.copy(end = max(cur.end, r.end)) else {
                out += cur
                cur = r
            }
        }
        out += cur
    }
    return out
}

/** 在 [start, end) 上设置或去除某个样式 */
fun applyStyle(spans: List<StyleRange>, start: Int, end: Int, key: StyleKey, on: Boolean, exactOnly: Boolean = false): List<StyleRange> {
    if (end <= start) return spans
    val out = mutableListOf<StyleRange>()
    for (r in spans) {
        val match = if (!exactOnly && key.style.exclusive()) r.style == key.style else r.key() == key
        if (!match || r.end <= start || r.start >= end) {
            out += r
            continue
        }
        if (r.start < start) out += r.copy(end = start)
        if (r.end > end) out += r.copy(start = end)
    }
    if (on) out += StyleRange(start, end, key.style, key.color, key.url)
    return out
}

/** [start, end) 是否整段都带有该样式 */
fun isFullyStyled(spans: List<StyleRange>, start: Int, end: Int, key: StyleKey): Boolean {
    var pos = start
    spans.filter { r -> if (key.style.exclusive()) r.style == key.style && (key.color == 0L || r.color == key.color) else r.key() == key }
        .sortedBy { it.start }
        .forEach { r -> if (r.start <= pos && r.end > pos) pos = r.end }
    return pos >= end
}

/** 光标处的样式：继承光标前一个字的样式；块首则看第一个字。链接和评论锚点只在其内部延续 */
fun stylesAt(spans: List<StyleRange>, pos: Int, minPos: Int): Set<StyleKey> {
    val at = if (pos > minPos) pos - 1 else pos
    return spans.filter {
        if (it.style == InlineStyle.LINK || it.style == InlineStyle.COMMENT) it.start < pos && pos < it.end
        else it.start <= at && at < it.end
    }.map { it.key() }.toSet()
}

/**
 * 文本从 old 变为 new 后调整样式区间：
 * 找出公共前后缀确定被替换的范围，平移后面的区间，新插入的文字恰好套用 typing 中的样式。
 */
fun adjustSpans(spans: List<StyleRange>, old: String, new: String, typing: Set<StyleKey>): List<StyleRange> {
    val minLen = min(old.length, new.length)
    var p = 0
    while (p < minLen && old[p] == new[p]) p++
    var s = 0
    while (s < minLen - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val removedEnd = old.length - s
    val insertedEnd = new.length - s
    val delta = new.length - old.length
    fun map(x: Int) = when {
        x <= p -> x
        x >= removedEnd -> x + delta
        else -> p
    }
    var result = spans.map { it.copy(start = map(it.start), end = map(it.end)) }
    if (insertedEnd > p) {
        result.map { it.key() }.toSet().filter { it !in typing }.forEach { k ->
            result = applyStyle(result, p, insertedEnd, k, on = false, exactOnly = true)
        }
        typing.forEach { k -> result = applyStyle(result, p, insertedEnd, k, on = true) }
    }
    return result.normalized(new.length)
}

/** 取 [from, to) 内的样式并平移到从 0 开始 */
fun sliceSpans(spans: List<StyleRange>, from: Int, to: Int): List<StyleRange> =
    spans.mapNotNull { r ->
        val s = max(r.start, from)
        val e = min(r.end, to)
        if (e > s) r.copy(start = s - from, end = e - from) else null
    }

fun List<StyleRange>.shifted(delta: Int) = map { it.copy(start = it.start + delta, end = it.end + delta) }

/** 查找时的高亮：普通匹配为黄色，当前匹配为蓝色 */
class FindMark(val start: Int, val end: Int, val current: Boolean)

private val markYellow get() = if (Kongye.dark) Color(0x66F2C94C) else Color(0xFFFFE7A3)
private val markBlue get() = if (Kongye.dark) Color(0x995B93FA) else Color(0xFFBFD9FF)

fun AnnotatedString.Builder.addFindMarks(marks: List<FindMark>, length: Int) {
    marks.forEach { m ->
        val s = m.start.coerceIn(0, length)
        val e = m.end.coerceIn(0, length)
        if (e > s) addStyle(SpanStyle(background = if (m.current) markBlue else markYellow), s, e)
    }
}

/** 评论：平时文字下面一条黄线，正在查看 / 正在添加的评论整段淡黄底 */
val CommentLine = Color(0xFFF2C94C)
val CommentFocus get() = if (Kongye.dark) Color(0x55F2C94C) else Color(0xFFFCE7AE)

/** 评论的黄线、淡黄底不在这里（底色会把行间也涂满），由 Blocks.kt 按行另外画 */
fun buildStyledText(
    text: String,
    spans: List<StyleRange>,
    done: Boolean,
    marks: List<FindMark> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (done) addStyle(SpanStyle(color = Kongye.Text3, textDecoration = TextDecoration.LineThrough), 0, text.length)
    spans.forEach { r ->
        val s = r.start.coerceIn(0, text.length)
        val e = r.end.coerceIn(0, text.length)
        if (e <= s) return@forEach
        val style = when (r.style) {
            InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            InlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            InlineStyle.COLOR -> SpanStyle(color = Kongye.userText(r.color))
            InlineStyle.HIGHLIGHT -> SpanStyle(background = Kongye.userHighlight(r.color))
            InlineStyle.LINK -> SpanStyle(color = Kongye.Primary, textDecoration = TextDecoration.Underline)
            InlineStyle.COMMENT -> null
        } ?: return@forEach
        addStyle(style, s, e)
    }
    addFindMarks(marks, text.length)
}
