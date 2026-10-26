package com.moji.notes.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.data.Align
import com.moji.notes.data.BlockType
import com.moji.notes.data.Repository
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.theme.Moji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextIndent
import com.moji.notes.data.InlineStyle
import com.moji.notes.data.canFirstIndent
import java.text.BreakIterator
import kotlin.math.max

private val lineStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

fun blockTextStyle(type: BlockType): TextStyle = when (type) {
    BlockType.H1 -> TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    BlockType.H2 -> TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    BlockType.H3 -> TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    BlockType.QUOTE -> TextStyle(fontSize = 16.sp, lineHeight = 26.sp, color = Moji.Text2)
    BlockType.CODE -> TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
    else -> TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
}.let { if (it.color == Color.Unspecified) it.copy(color = Moji.Text) else it }.copy(lineHeightStyle = lineStyle)

private fun placeholderFor(type: BlockType) = when (type) {
    BlockType.H1 -> "标题 1"
    BlockType.H2 -> "标题 2"
    BlockType.H3 -> "标题 3"
    BlockType.QUOTE -> "引用"
    BlockType.CODE -> "代码"
    BlockType.TODO -> "待办事项"
    BlockType.BULLET, BlockType.NUMBER -> "列表"
    else -> null
}

@Composable
fun BlockView(
    b: BlockState,
    index: Int,
    editor: EditorState,
    onImageTap: (BlockState) -> Unit,
    onDividerTap: (BlockState) -> Unit,
) {
    when (b.type) {
        BlockType.DIVIDER -> Box(
            Modifier.fillMaxWidth().height(30.dp).clickable { onDividerTap(b) },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.fillMaxWidth().height(1.dp).background(Moji.Line2)) }
        BlockType.IMAGE -> ImageBlock(b) { onImageTap(b) }
        BlockType.TABLE -> TableBlock(b, editor)
        else -> TextBlock(b, index, editor)
    }
}

@Composable
private fun TextBlock(b: BlockState, index: Int, editor: EditorState) {
    val type = b.type
    val base = blockTextStyle(type)
    val style = base.copy(
        textAlign = when (b.align) { Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End; else -> TextAlign.Start },
        // 段落首行缩进两个字
        textIndent = if (b.firstIndent && type.canFirstIndent) TextIndent(firstLine = (base.fontSize.value * 2).sp) else null,
    )
    val spans = b.spans
    val done = type == BlockType.TODO && b.checked
    val marks = if (editor.findMode) {
        editor.matches.mapIndexedNotNull { i, m -> if (m.blockId == b.id && m.row < 0) FindMark(m.start, m.end, i == editor.matchIndex) else null }
    } else emptyList()
    // 评论：锚点文字下画黄线；正在添加评论的文字也画黄线；正在查看的评论逐行淡黄底
    val commentSpans = spans.filter { it.style == InlineStyle.COMMENT && editor.thread(it.url) != null }
    val focusedComment = editor.commentView?.threadId
    val draft = editor.commentDraft?.takeIf { it.blockId == b.id }
    val transformation = remember(spans, done, marks.map { Triple(it.start, it.end, it.current) }) {
        VisualTransformation { t -> TransformedText(buildStyledText(t.text, spans, done, marks), OffsetMapping.Identity) }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val lineHeight = with(density) { style.lineHeight.toDp() }
    // 正文不再显示“开始书写…”的整篇提示，只在空的标题、列表等块获得焦点时提示它的类型
    val placeholder = if (b.text.isEmpty() && editor.focusedId == b.id) placeholderFor(type) else null
    val (top, bottom) = when (type) {
        BlockType.H1 -> 14.dp to 4.dp
        BlockType.H2 -> 12.dp to 2.dp
        BlockType.H3 -> 8.dp to 2.dp
        BlockType.CODE -> 6.dp to 6.dp
        else -> 2.dp to 2.dp
    }
    Box(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(start = (b.indent * 24).dp, top = top, bottom = bottom)
            .then(if (type == BlockType.QUOTE) Modifier.drawBehind {
                drawRoundRect(Moji.Line2, Offset.Zero, Size(3.dp.toPx(), size.height), CornerRadius(1.5f.dp.toPx()))
            } else Modifier),
    ) {
        when (type) {
            BlockType.BULLET -> Box(Modifier.width(24.dp).height(lineHeight), contentAlignment = Alignment.CenterStart) { BulletMark(b.indent) }
            BlockType.NUMBER -> Box(Modifier.widthIn(min = 24.dp).height(lineHeight).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                Text(numberLabel(editor.numberIndex(index), b.indent), style = style.copy(color = Moji.Text2, fontWeight = FontWeight.Normal, textAlign = TextAlign.Start))
            }
            BlockType.TODO -> Box(
                Modifier.width(28.dp).height(lineHeight)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { editor.toggleTodo(b) },
                contentAlignment = Alignment.CenterStart,
            ) { CheckMark(b.checked) }
            BlockType.QUOTE -> Spacer(Modifier.width(14.dp))
            else -> {}
        }
        val field: @Composable (Modifier) -> Unit = { mod ->
            BasicTextField(
                value = b.value,
                onValueChange = { editor.onValueChange(b, it) },
                readOnly = editor.findMode,
                textStyle = style,
                cursorBrush = SolidColor(Moji.Primary),
                visualTransformation = transformation,
                onTextLayout = {
                    editor.layouts[b.id] = it
                    layout = it
                },
                modifier = mod
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        commentSpans.forEach { r ->
                            if (r.url == focusedComment) drawCommentFill(l, r.start, r.end, style.fontSize.toPx())
                            else drawCommentLine(l, r.start, r.end)
                        }
                        draft?.let { drawCommentLine(l, it.start, it.end) }
                    }
                    .textGestures(editor, b.id, minOffset = 1, layout = { layout }) { p ->
                        // 没在编辑时点到带评论的文字：直接打开这条评论
                        val l = layout
                        if (l == null || editor.focusedId != null || editor.focusedCell != null || editor.findMode) null
                        else charAt(l, p)?.let { editor.threadAt(b, it) }?.let { id -> { editor.commentView = EditorState.CommentView(id) } }
                    }
                    .focusRequester(b.focus).onFocusChanged {
                        if (it.isFocused) {
                            editor.focusedId = b.id
                            editor.focusedCell = null
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (placeholder != null) Text(placeholder, style = style.copy(color = Moji.Text4))
                        inner()
                    }
                },
            )
        }
        if (type == BlockType.CODE) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Moji.Bg2).padding(horizontal = 12.dp, vertical = 10.dp)) {
                field(Modifier.fillMaxWidth())
            }
        } else field(Modifier.weight(1f))
    }
    // 评论角标：画在这段右侧的页边上，对齐评论开始的那一行；同一条评论跨段时只画在第一段
    val badgeThreads = commentSpans.map { it.url }.distinct().filter { editor.firstAnchor[it] == b.id }
    val l = layout
    if (badgeThreads.isNotEmpty() && l != null && l.layoutInput.text.isNotEmpty()) {
        val start = commentSpans.filter { it.url in badgeThreads }.minOf { it.start }.coerceIn(0, l.layoutInput.text.length - 1)
        val lineTop = with(density) { l.getLineTop(l.getLineForOffset(start)).toDp() }
        val count = badgeThreads.sumOf { editor.thread(it)?.comments?.size ?: 0 }
        CommentBadge(
            count, active = focusedComment != null && focusedComment in badgeThreads,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 24.dp, y = top + lineTop + (lineHeight - 20.dp) / 2),
        ) {
            editor.commentView = EditorState.CommentView(badgeThreads.first())
        }
    }
    }
}

/** 点击位置所在的那个字（必须真的点在字上），输入框偏移 */
private fun charAt(l: TextLayoutResult, p: Offset): Int? {
    val len = l.layoutInput.text.length
    if (len == 0 || p.y < 0 || p.y > l.size.height) return null
    val o = l.getOffsetForPosition(p)
    return listOf(o - 1, o).firstOrNull { it in 0 until len && l.getBoundingBox(it).contains(p) }
}

/** 双击要选中的那一个字：取点中位置最近的字，按字形簇（emoji 等）扩展；minOffset 之前的哨兵不选 */
private fun charRangeAt(l: TextLayoutResult, p: Offset, minOffset: Int): Pair<Int, Int>? {
    val text = l.layoutInput.text.text
    val len = text.length
    if (len <= minOffset) return null
    val c = (charAt(l, p) ?: (l.getOffsetForPosition(p) - 1)).coerceIn(minOffset, len - 1)
    val it = BreakIterator.getCharacterInstance().apply { setText(text) }
    val start = if (it.isBoundary(c)) c else it.preceding(c)
    val end = it.following(c).let { e -> if (e == BreakIterator.DONE) len else e }
    return max(minOffset, start) to end
}

/**
 * 输入框上的手势，在 Initial 阶段旁观，不影响输入框自己的点击、长按和拖动：
 * - 双击：记下点中的那个字，输入框随后“选中整个词”（中文常常是一整句）时改成只选这一个字；
 * - intercept 返回非空时，吃掉这次点击并执行它（例如阅读状态下点开评论）。
 */
internal fun Modifier.textGestures(
    editor: EditorState,
    key: String,
    minOffset: Int,
    layout: () -> TextLayoutResult?,
    intercept: (Offset) -> (() -> Unit)? = { null },
): Modifier = pointerInput(key) {
    var lastUp = 0L
    var lastPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val action = intercept(down.position)
        if (action != null) {
            down.consume()
            val up = waitForUpOrCancellation(PointerEventPass.Initial)
            if (up != null) {
                up.consume()
                action()
            }
            return@awaitEachGesture
        }
        val second = down.uptimeMillis - lastUp < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastPos).getDistance() < viewConfiguration.touchSlop * 4
        val l = layout()
        if (second && l != null) charRangeAt(l, down.position, minOffset)?.let { (s, e) -> editor.armCharSelect(key, s, e) }
        val up = waitForUpOrCancellation(PointerEventPass.Initial)
        if (up != null) {
            lastUp = up.uptimeMillis
            lastPos = up.position
        }
    }
}

@Composable
private fun BulletMark(level: Int) {
    Canvas(Modifier.size(6.dp)) {
        when (level % 3) {
            0 -> drawCircle(Moji.Text)
            1 -> drawCircle(Moji.Text, radius = size.minDimension / 2 - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx()))
            else -> drawRect(Moji.Text)
        }
    }
}

@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
            .then(if (checked) Modifier.background(Moji.Primary) else Modifier.border(1.5.dp, Moji.Text4, RoundedCornerShape(4.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(MojiIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
    }
}

private fun numberLabel(n: Int, indent: Int): String = when (indent % 3) {
    0 -> "$n."
    1 -> buildString {
        var x = n
        while (x > 0) { x--; insert(0, 'a' + x % 26); x /= 26 }
    } + "."
    else -> roman(n) + "."
}

private fun roman(n: Int): String {
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
    var x = n
    return buildString { values.forEachIndexed { i, v -> while (x >= v) { append(symbols[i]); x -= v } } }
}

@Composable
private fun ImageBlock(b: BlockState, onTap: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, b.image) {
        value = withContext(Dispatchers.IO) { Repository.loadBitmap(b.image)?.asImageBitmap() }
    }
    val ratio = (b.imageRatio.takeIf { it > 0f } ?: bitmap?.let { it.width.toFloat() / it.height } ?: 1.5f).coerceIn(0.25f, 5f)
    Box(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp)).background(Moji.Bg2).clickable(onClick = onTap),
    ) {
        bitmap?.let { Image(it, "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableBlock(b: BlockState, editor: EditorState) {
    val rows = b.table
    val cols = rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val colWidth = if (cols <= 3) (maxWidth - 2.dp) / cols else 120.dp
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Moji.Line2, RoundedCornerShape(6.dp))) {
                rows.forEachIndexed { r, row ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for (c in 0 until cols) {
                            val ref = EditorState.CellRef(b.id, r, c)
                            val active = editor.focusedCell == ref
                            val requester = remember { BringIntoViewRequester() }
                            val cellSize = remember { FloatArray(2) }
                            // 输入框自己只会把光标那一行滚进来，格子下半截和选中框常被工具栏挡住。
                            // 选中后等键盘、工具栏都出现（键盘高度不再变化），再把整个格子连同上下 12dp 滚进可见区域
                            if (active) LaunchedEffect(ref) {
                                snapshotFlow { ime.getBottom(density) }.collectLatest {
                                    delay(80)
                                    requester.bringIntoView(Rect(0f, -margin, cellSize[0], cellSize[1] + margin))
                                }
                            }
                            val cellMarks = if (editor.findMode) editor.matches.mapIndexedNotNull { i, m ->
                                if (m.blockId == b.id && m.row == r && m.col == c) FindMark(m.start, m.end, i == editor.matchIndex) else null
                            } else emptyList()
                            BasicTextField(
                                value = row.getOrElse(c) { "" },
                                onValueChange = { editor.setCell(b, r, c, it) },
                                readOnly = editor.findMode,
                                visualTransformation = if (cellMarks.isEmpty()) VisualTransformation.None else VisualTransformation { t ->
                                    TransformedText(
                                        androidx.compose.ui.text.buildAnnotatedString { append(t.text); addFindMarks(cellMarks, t.text.length) },
                                        OffsetMapping.Identity,
                                    )
                                },
                                textStyle = TextStyle(
                                    fontSize = 15.sp, lineHeight = 22.sp, color = Moji.Text,
                                    fontWeight = if (r == 0) FontWeight.Medium else FontWeight.Normal,
                                ),
                                cursorBrush = SolidColor(Moji.Primary),
                                modifier = Modifier.width(colWidth).fillMaxHeight()
                                    .bringIntoViewRequester(requester)
                                    .onSizeChanged { cellSize[0] = it.width.toFloat(); cellSize[1] = it.height.toFloat() }
                                    .background(if (r == 0) Moji.Bg2 else Moji.Surface)
                                    .drawBehind {
                                        val w = 1.dp.toPx()
                                        if (c < cols - 1) drawRect(Moji.Line2, Offset(size.width - w, 0f), Size(w, size.height))
                                        if (r < rows.size - 1) drawRect(Moji.Line2, Offset(0f, size.height - w), Size(size.width, w))
                                        if (active) {
                                            // 选中框完整画在格子内部：居中画在边线上时，右、下半边会被后画的格子盖住，
                                            // 靠表格外沿的边又会被外框和圆角裁掉，于是看起来左上粗、右下细
                                            val s = 2.dp.toPx()
                                            val edge = 1.dp.toPx()
                                            val last = rows.size - 1
                                            val l = (if (c == 0) edge else 0f) + s / 2
                                            val t = (if (r == 0) edge else 0f) + s / 2
                                            val rt = size.width - (if (c == cols - 1) edge else 0f) - s / 2
                                            val bt = size.height - (if (r == last) edge else 0f) - s / 2
                                            // 四个角上的格子跟着表格的 6dp 圆角走
                                            val k = CornerRadius(6.dp.toPx() - edge - s / 2)
                                            fun corner(on: Boolean) = if (on) k else CornerRadius.Zero
                                            val frame = RoundRect(
                                                l, t, rt, bt,
                                                topLeftCornerRadius = corner(r == 0 && c == 0),
                                                topRightCornerRadius = corner(r == 0 && c == cols - 1),
                                                bottomRightCornerRadius = corner(r == last && c == cols - 1),
                                                bottomLeftCornerRadius = corner(r == last && c == 0),
                                            )
                                            drawPath(Path().apply { addRoundRect(frame) }, Moji.Primary, style = Stroke(s))
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            editor.focusedCell = ref
                                            editor.focusedId = null
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
