package com.kongye.docs.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Align
import com.kongye.docs.data.Block
import com.kongye.docs.data.BlockType
import com.kongye.docs.data.InlineStyle
import com.kongye.docs.data.Repository
import com.kongye.docs.data.canFirstIndent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.theme.Kongye

/** 工具栏按钮展开的二级面板，显示在键盘的位置 */
enum class EdPanel { STYLE, PARAGRAPH, INSERT }

/**
 * 键盘上方工具栏里可以调整顺序的工具（默认顺序即枚举顺序）。
 * 列表、缩进、首行缩进收在“段落”里；图片、表格、分割线、链接、引用、代码块收在“+”里。
 */
enum class Tool(val label: String, val icon: ImageVector) {
    STYLE("文字样式", KongyeIcons.TextStyle),
    PARAGRAPH("段落", KongyeIcons.Numbered),
    INSERT("插入", KongyeIcons.Plus),
    UNDO("撤销", KongyeIcons.Undo),
    REDO("重做", KongyeIcons.Redo),
    COMMENT("评论", KongyeIcons.Comment);

    companion object {
        /** 按保存的顺序排列；新加的工具（保存时还没有的）补在最后 */
        fun ordered(ids: List<String>): List<Tool> {
            val known = ids.mapNotNull { id -> entries.firstOrNull { it.name == id } }.distinct()
            return known + entries.filter { it !in known }
        }
    }
}

@Composable
fun EditorToolbar(
    editor: EditorState,
    panel: EdPanel?,
    onPanel: (EdPanel) -> Unit,
    onComment: () -> Unit,
    onTableMenu: () -> Unit,
    onHideKeyboard: () -> Unit,
    onCustomize: () -> Unit,
    onUndo: () -> Unit = { editor.undo() },
    onRedo: () -> Unit = { editor.redo() },
) {
    val b = editor.focused
    val order by Repository.toolbar.collectAsState()
    Column(Modifier.fillMaxWidth().background(Kongye.Surface)) {
        HLine()
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool.ordered(order).forEach { tool ->
                    when (tool) {
                        Tool.STYLE -> TbBtn(tool.icon, active = panel == EdPanel.STYLE, enabled = b != null) { onPanel(EdPanel.STYLE) }
                        Tool.PARAGRAPH -> TbBtn(tool.icon, active = panel == EdPanel.PARAGRAPH, enabled = b != null) { onPanel(EdPanel.PARAGRAPH) }
                        Tool.INSERT -> TbBtn(tool.icon, active = panel == EdPanel.INSERT) { onPanel(EdPanel.INSERT) }
                        Tool.UNDO -> TbBtn(tool.icon, enabled = editor.canUndo) { onUndo() }
                        Tool.REDO -> TbBtn(tool.icon, enabled = editor.canRedo) { onRedo() }
                        Tool.COMMENT -> TbBtn(tool.icon, enabled = b != null, onClick = onComment)
                    }
                }
                // 最后一个：自定义工具栏顺序
                TbBtn(KongyeIcons.Tune, onClick = onCustomize)
            }
            if (editor.focusedCell != null) TbBtn(KongyeIcons.TableEdit, active = true, onClick = onTableMenu)
            Box(Modifier.width(0.6.dp).height(22.dp).background(Kongye.Line2))
            TbBtn(KongyeIcons.KeyboardHide, onClick = onHideKeyboard)
        }
    }
}

@Composable
private fun TbBtn(icon: ImageVector, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Kongye.PrimarySoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = when { !enabled -> Kongye.Text4; active -> Kongye.Primary; else -> Kongye.Text })
    }
}

/** “Aa” 样式面板：替代键盘的位置显示 */
@Composable
fun StylePanel(editor: EditorState, height: Dp) {
    val b = editor.focused
    val active = editor.activeStyles()
    val fg = active.firstOrNull { it.style == InlineStyle.COLOR }?.color ?: 0L
    val bg = active.firstOrNull { it.style == InlineStyle.HIGHLIGHT }?.color ?: 0L
    Column(
        Modifier.fillMaxWidth().height(height).background(Kongye.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(BlockType.TEXT to "正文", BlockType.H1 to "标题1", BlockType.H2 to "标题2", BlockType.H3 to "标题3").forEach { (t, label) ->
                val selected = b?.type == t
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Kongye.PrimarySoft else Kongye.Bg2)
                        .clickable(enabled = b != null) { editor.setBlockType(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = when (t) { BlockType.H1 -> 18.sp; BlockType.H2 -> 16.sp; BlockType.H3 -> 15.sp; else -> 14.sp },
                        fontWeight = if (t == BlockType.TEXT) FontWeight.Normal else FontWeight.Bold,
                        color = if (selected) Kongye.Primary else Kongye.Text,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Bg2)) {
                Seg(KongyeIcons.Bold, StyleKey(InlineStyle.BOLD) in active) { editor.toggleStyle(InlineStyle.BOLD) }
                Seg(KongyeIcons.Italic, StyleKey(InlineStyle.ITALIC) in active) { editor.toggleStyle(InlineStyle.ITALIC) }
                Seg(KongyeIcons.Underline, StyleKey(InlineStyle.UNDERLINE) in active) { editor.toggleStyle(InlineStyle.UNDERLINE) }
                Seg(KongyeIcons.Strike, StyleKey(InlineStyle.STRIKE) in active) { editor.toggleStyle(InlineStyle.STRIKE) }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(0.75f).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Bg2)) {
                Seg(KongyeIcons.AlignLeft, b?.align == Align.START) { editor.setAlign(Align.START) }
                Seg(KongyeIcons.AlignCenter, b?.align == Align.CENTER) { editor.setAlign(Align.CENTER) }
                Seg(KongyeIcons.AlignRight, b?.align == Align.END) { editor.setAlign(Align.END) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("文字颜色", fontSize = 12.sp, color = Kongye.Text3)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Kongye.textPalette.forEach { c -> Swatch(c, isText = true, selected = c == fg) { editor.setColor(InlineStyle.COLOR, c) } }
        }
        Spacer(Modifier.height(14.dp))
        Text("背景颜色", fontSize = 12.sp, color = Kongye.Text3)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Kongye.highlightPalette.forEach { c -> Swatch(c, isText = false, selected = c == bg) { editor.setColor(InlineStyle.HIGHLIGHT, c) } }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Bg2).clickable { editor.clearFormat() },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(KongyeIcons.ClearFormat, null, Modifier.size(18.dp), tint = Kongye.Text2)
            Spacer(Modifier.width(6.dp))
            Text("清除格式", fontSize = 14.sp, color = Kongye.Text2)
        }
    }
}

@Composable
private fun RowScope.Seg(icon: ImageVector, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Kongye.Surface else Color.Transparent).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = when { !enabled -> Kongye.Text4; selected -> Kongye.Primary; else -> Kongye.Text })
    }
}

/** “段落”面板：有序 / 无序 / 待办列表，增加 / 减少缩进，段落首行缩进 */
@Composable
fun ParagraphPanel(editor: EditorState, height: Dp) {
    val b = editor.focused
    val type = b?.type
    Column(
        Modifier.fillMaxWidth().height(height).background(Kongye.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row {
            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Bg2)) {
                Seg(KongyeIcons.Numbered, type == BlockType.NUMBER, b != null) { editor.setBlockType(BlockType.NUMBER) }
                Seg(KongyeIcons.Bullet, type == BlockType.BULLET, b != null) { editor.setBlockType(BlockType.BULLET) }
                Seg(KongyeIcons.Todo, type == BlockType.TODO, b != null) { editor.setBlockType(BlockType.TODO) }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(0.67f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Bg2)) {
                Seg(KongyeIcons.Indent, false, b != null && b.indent < 4) { editor.indent(1) }
                Seg(KongyeIcons.Outdent, false, b != null && b.indent > 0) { editor.indent(-1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val canIndent = b != null && b.type.canFirstIndent
        val on = canIndent && b?.firstIndent == true
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) Kongye.PrimarySoft else Kongye.Bg2)
                .clickable(enabled = canIndent) { editor.toggleFirstIndent() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "段落首行缩进", fontSize = 15.sp,
                color = when { !canIndent -> Kongye.Text4; on -> Kongye.Primary; else -> Kongye.Text },
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (canIndent || b == null) "开启后这一段首行空两个字，回车新起的段落也会缩进；在段首按删除键可取消"
            else "列表和代码块不支持首行缩进",
            fontSize = 12.sp, lineHeight = 17.sp, color = Kongye.Text3,
        )
    }
}

/** “+”面板：插入图片、拍照、表格、分割线、链接，以及转为引用、代码块 */
@Composable
fun InsertPanel(
    editor: EditorState,
    height: Dp,
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onLink: () -> Unit,
    onDone: () -> Unit,
) {
    val b = editor.focused
    val type = b?.type
    val items = listOf(
        InsertItem("图片", KongyeIcons.Image, onClick = onImage),
        InsertItem("拍照", KongyeIcons.Camera, onClick = onCamera),
        InsertItem("表格", KongyeIcons.Table) {
            editor.insertBlock(Block(type = BlockType.TABLE, table = List(3) { List(3) { "" } }))
            onDone()
        },
        InsertItem("分割线", KongyeIcons.Divider) {
            editor.insertBlock(Block(type = BlockType.DIVIDER))
            onDone()
        },
        InsertItem("链接", KongyeIcons.Link, active = b != null && editor.linkAtCursor() != null, enabled = b != null, onClick = onLink),
        InsertItem("引用", KongyeIcons.Quote, active = type == BlockType.QUOTE, enabled = b != null) {
            editor.setBlockType(BlockType.QUOTE)
            onDone()
        },
        InsertItem("代码块", KongyeIcons.Code, active = type == BlockType.CODE, enabled = b != null) {
            editor.setBlockType(BlockType.CODE)
            onDone()
        },
    )
    Column(
        Modifier.fillMaxWidth().height(height).background(Kongye.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 14.dp),
    ) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = item.enabled, onClick = item.onClick)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(if (item.active) Kongye.PrimarySoft else Kongye.Bg2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                item.icon, null, Modifier.size(26.dp),
                                tint = when { !item.enabled -> Kongye.Text4; item.active -> Kongye.Primary; else -> Kongye.Ink2 },
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(item.label, fontSize = 12.sp, color = if (item.enabled) Kongye.Text2 else Kongye.Text4)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private class InsertItem(
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun Swatch(color: Long, isText: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (isText || color == 0L) Kongye.Surface else Color(color))
            .border(if (selected) 2.dp else 1.dp, if (selected) Kongye.Primary else Kongye.Line2, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isText) {
            Text("A", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (color == 0L) Kongye.Text else Color(color))
        } else if (color == 0L) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                drawLine(Kongye.Danger, Offset(size.width, 0f), Offset(0f, size.height), 1.5.dp.toPx())
            }
        }
    }
}
