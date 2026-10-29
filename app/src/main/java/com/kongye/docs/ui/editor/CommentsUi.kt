package com.kongye.docs.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Comment
import com.kongye.docs.data.countWords
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.formatTime
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

// ---------------- 正文里的评论标记 ----------------

private fun Char.isBlankChar() = isWhitespace() || this == '​' || this == '　'

/**
 * 评论区间 [start, end)（输入框偏移）逐行分段：每行去掉首尾空白（含全角空格），
 * 交给 block 画这一段，x1..x2 是这段文字的左右边界。
 */
private inline fun commentSegments(l: TextLayoutResult, start: Int, end: Int, block: (line: Int, x1: Float, x2: Float) -> Unit) {
    val text = l.layoutInput.text.text
    val len = text.length
    val s = start.coerceIn(0, len)
    val e = end.coerceIn(s, len)
    if (e <= s) return
    for (line in l.getLineForOffset(s)..l.getLineForOffset(e - 1)) {
        var ls = max(s, l.getLineStart(line))
        var le = min(e, l.getLineEnd(line, visibleEnd = true))
        while (ls < le && text[ls].isBlankChar()) ls++
        while (le > ls && text[le - 1].isBlankChar()) le--
        if (le <= ls) continue
        val x1 = l.getBoundingBox(ls).left
        val x2 = l.getBoundingBox(le - 1).right
        block(line, min(x1, x2), max(x1, x2))
    }
}

/** 评论文字下面的黄线：逐行画，空白处不画 */
fun DrawScope.drawCommentLine(l: TextLayoutResult, start: Int, end: Int) {
    val stroke = 1.6.dp.toPx()
    val below = 4.dp.toPx()
    commentSegments(l, start, end) { line, x1, x2 ->
        val y = l.getLineBaseline(line) + below
        drawLine(CommentLine, Offset(x1, y), Offset(x2, y), stroke, StrokeCap.Round)
    }
}

/** 正在查看的评论：每行一条淡黄底，只盖住文字的高度，行与行之间留出空隙 */
fun DrawScope.drawCommentFill(l: TextLayoutResult, start: Int, end: Int, fontPx: Float) {
    val h = fontPx * 1.3f
    commentSegments(l, start, end) { line, x1, x2 ->
        val cy = (l.getLineTop(line) + l.getLineBottom(line)) / 2
        drawRect(CommentFocus, Offset(x1, cy - h / 2), Size(x2 - x1, h))
    }
}

/** 段落右侧页边上的评论角标：平时是浅灰色气泡，点开这条评论时变成深色；里面是评论条数 */
@Composable
fun CommentBadge(count: Int, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when {
        active -> if (Kongye.dark) Color(0xFFB4B8BE) else Color(0xFF5F6368)
        else -> if (Kongye.dark) Color(0xFF4A4D52) else Color(0xFFC6C9CE)
    }
    val fg = if (active && Kongye.dark) Color(0xFF1C1D1F) else Color.White
    Box(
        modifier.size(width = 22.dp, height = 20.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 2.5.dp.toPx()
            val h = size.height - 4.dp.toPx()
            drawRoundRect(bg, Offset.Zero, Size(size.width, h), CornerRadius(r))
            // 左下角的小尾巴
            val tail = Path().apply {
                moveTo(4.dp.toPx(), h - 0.5f)
                lineTo(4.dp.toPx(), size.height)
                lineTo(9.dp.toPx(), h - 0.5f)
                close()
            }
            drawPath(tail, bg)
        }
        Text(
            if (count > 99) "99+" else count.toString(), Modifier.padding(top = 1.dp),
            fontSize = 11.sp, lineHeight = 14.sp, color = fg, fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------- 输入框 ----------------

/** 灰底的“添加评论…”输入框，右侧“发送”；onCancel 不为 null 时左侧多一个“取消” */
@Composable
fun CommentInput(autoFocus: Boolean, onCancel: (() -> Unit)? = null, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().background(Kongye.Surface).padding(start = if (onCancel != null) 8.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            Text(
                "取消",
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 11.dp),
                fontSize = 15.sp, color = Kongye.Text2,
            )
            Spacer(Modifier.width(6.dp))
        }
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).background(Kongye.Field).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text, onValueChange = { text = it }, maxLines = 4,
                textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Ink, lineHeight = 21.sp),
                cursorBrush = SolidColor(Kongye.Primary),
                modifier = Modifier.weight(1f).padding(vertical = 11.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text("添加评论…", fontSize = 15.sp, color = Kongye.Text4)
                        inner()
                    }
                },
            )
            Text(
                "发送",
                Modifier.clickable(enabled = text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }.padding(horizontal = 14.dp, vertical = 11.dp),
                fontSize = 15.sp, color = if (text.isBlank()) Kongye.Text4 else Kongye.Primary,
            )
        }
    }
    if (autoFocus) LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

/** 选中文字后点“评论”：键盘上方出现输入框，发送后这段文字挂上评论；“取消”放弃这条评论、回到原段落继续编辑 */
@Composable
fun CommentComposer(editor: EditorState, onCancel: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface)) {
        HLine()
        CommentInput(autoFocus = true, onCancel = onCancel) {
            editor.submitDraft(it)
            onDone()
        }
    }
}

// ---------------- 评论面板 ----------------

/**
 * 评论面板（替代工具栏 / 键盘的位置，上方正文仍可见）：
 * 结束评论 · 评论 · 完成；中间是评论列表，底部可以继续回复。
 * 查看全部评论时按原文分组，点原文进入该条评论。
 */
@Composable
fun CommentPanel(editor: EditorState, height: Dp) {
    val view = editor.commentView ?: return
    val thread = editor.thread(view.threadId)
    Column(
        Modifier.fillMaxWidth().height(height).background(Kongye.Surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        HLine()
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            if (thread != null) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    // 从“全部评论”点进来的：左边多一个返回箭头，回到全部评论
                    if (view.fromAll) IconBtn(KongyeIcons.Back, { editor.commentView = EditorState.CommentView(null) }, Modifier.padding(start = 6.dp), tint = Kongye.Ink2, description = "全部评论")
                    Text(
                        "结束评论", Modifier.clickable { editor.resolve(thread.id) }
                            .padding(start = if (view.fromAll) 2.dp else 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        fontSize = 16.sp, color = Kongye.Danger,
                    )
                }
            } else if (view.threadId != null) {
                // 正在看的评论已被结束（例如撤销了锚点）
                LaunchedEffect(Unit) { editor.commentView = null }
            }
            val all = editor.anchoredThreads()
            Text(
                if (thread == null && all.isNotEmpty()) "评论（${all.size}）" else "评论",
                Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink,
            )
            Text(
                "完成", Modifier.align(Alignment.CenterEnd).clickable { editor.commentView = null }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 16.sp, color = Kongye.Primary,
            )
        }
        if (thread != null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(thread.comments, key = { it.id }) { c -> CommentRow(c) { editor.deleteComment(thread.id, c.id) } }
            }
            CommentInput(autoFocus = false) { editor.reply(thread.id, it) }
        } else {
            val all = editor.anchoredThreads()
            if (all.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Text("还没有评论", fontSize = 15.sp, color = Kongye.Ink2)
                    Spacer(Modifier.height(8.dp))
                    Text("选中文字后，点工具栏上的评论按钮即可添加", fontSize = 13.sp, color = Kongye.Sub, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    all.forEach { t ->
                        item(key = "q-" + t.id) {
                            // 引用的原文：左侧一条黄线，点击进入这条评论
                            Row(
                                Modifier.fillMaxWidth().clickable { editor.commentView = EditorState.CommentView(t.id, fromAll = true) }
                                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
                            ) {
                                Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(CommentLine))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    editor.quoteOf(t.id), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
                                    color = Kongye.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        items(t.comments, key = { t.id + it.id }) { c ->
                            CommentRow(c, onOpen = { editor.commentView = EditorState.CommentView(t.id, fromAll = true) }) {
                                editor.deleteComment(t.id, c.id)
                            }
                        }
                        item(key = "e-" + t.id) {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp)) {
                                Text(
                                    "回复", Modifier.clickable { editor.commentView = EditorState.CommentView(t.id, fromAll = true) }
                                        .padding(horizontal = 0.dp, vertical = 6.dp),
                                    fontSize = 13.sp, color = Kongye.Primary,
                                )
                                Spacer(Modifier.width(20.dp))
                                Text(
                                    "结束评论", Modifier.clickable { editor.resolve(t.id) }.padding(vertical = 6.dp),
                                    fontSize = 13.sp, color = Kongye.Danger,
                                )
                            }
                            HLine(Modifier.padding(top = 8.dp), color = Kongye.Divider)
                        }
                    }
                }
            }
        }
    }
}

/** 一条评论；在全部评论里 onOpen 不为 null，点一下进入这条评论 */
@Composable
private fun CommentRow(c: Comment, onOpen: (() -> Unit)? = null, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 只显示时间和这条评论的字数
            Text("${formatTime(c.time)} · ${countWords(c.text)} 字", Modifier.weight(1f), fontSize = 13.sp, color = Kongye.Sub)
            Text("删除", Modifier.clickable(onClick = onDelete).padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 14.sp, color = Kongye.Danger)
        }
        Spacer(Modifier.height(4.dp))
        Text(c.text, fontSize = 16.sp, lineHeight = 23.sp, color = Kongye.Ink)
    }
}
