package com.kongye.docs.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.kongye.docs.data.Item
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.delay

// ---------------- 文件图标 ----------------

private fun svg(d: String) = PathParser().parsePathString(d).toPath()

// 石墨风格：文档是浅灰色“纸张”，文件夹是浅蓝色
private val paperBody = svg("M6.5 3h13.5l6 6v20h-19.5z")
private val paperFold = svg("M20 3v6h6z")
private val folderBack = svg("M4 9.2a1.5 1.5 0 0 1 1.5-1.5h6.6l1.9 1.9h12.5a1.5 1.5 0 0 1 1.5 1.5V14H4z")
private val folderFront = svg("M3.2 12a1.2 1.2 0 0 1 1.2-1.2h23.2a1.2 1.2 0 0 1 1.2 1.2l-.9 12.8a1.4 1.4 0 0 1-1.4 1.3H5.5a1.4 1.4 0 0 1-1.4-1.3z")
private val paperLines = listOf(9.5f to 9f, 9.5f to 6f, 9.5f to 12f, 9.5f to 11f, 9.5f to 12f, 9.5f to 7f, 9.5f to 10f)

/** 收藏标记：纸张左上角垂下来的一条蓝色书签带，底部剪成燕尾 */
private val ribbonOnPaper = svg("M8.6 3h3.4v10.6l-1.7-1.7-1.7 1.7z")
private val ribbonOnFolder = svg("M6.2 10.8h3.4v9l-1.7-1.6-1.7 1.6z")

@Composable
fun FileIcon(isFolder: Boolean, size: Dp = 36.dp, starred: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 32f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            if (isFolder) {
                drawOval(Color(0x0F000000), Offset(5f, 25.4f), Size(22f, 1.8f))
                drawPath(folderBack, Color(0xFF86A5D7))
                drawPath(folderFront, Color(0xFF98B6E1))
                if (starred) drawPath(ribbonOnFolder, Kongye.Bookmark)
            } else {
                // 阴影沿纸张轮廓（右上角是折角，不能用矩形阴影，否则折角处会露出一块灰）
                val dark = Kongye.dark
                withTransform({ translate(0.45f, 0.7f) }) { drawPath(paperBody, Color(if (dark) 0x33000000 else 0x12000000)) }
                drawPath(paperBody, Color(if (dark) 0xFF34363A else 0xFFF7F7F7))
                drawPath(paperBody, Color(if (dark) 0xFF4A4C51 else 0xFFE0E0E0), style = androidx.compose.ui.graphics.drawscope.Stroke(0.35f))
                // 折角是白色的（深色下是稍亮的灰），只有很淡的描边
                drawPath(paperFold, Color(if (dark) 0xFF45474C else 0xFFFFFFFF))
                drawPath(paperFold, Color(if (dark) 0xFF55575C else 0xFFE6E6E6), style = androidx.compose.ui.graphics.drawscope.Stroke(0.3f))
                paperLines.forEachIndexed { i, (x, w) ->
                    drawRect(Color(if (dark) 0xFF57595E else 0xFFDADADA), Offset(x, 10.5f + i * 2.2f), Size(w, 0.7f))
                }
                if (starred) drawPath(ribbonOnPaper, Kongye.Bookmark)
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFFEDEDED)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).ifEmpty { "我" }, color = Kongye.Text3, fontSize = (size.value * 0.42f).sp)
    }
}

// ---------------- 按钮与顶栏 ----------------

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Kongye.Text,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    description: String? = null,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                enabled = enabled, onClick = onClick,
                interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = true),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(iconSize), tint = if (enabled) tint else Kongye.Text4)
    }
}

@Composable
fun TopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    divider: Boolean = false,
    big: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconBtn(KongyeIcons.Back, onBack, description = "返回") else Spacer(Modifier.width(10.dp))
            if (title != null) {
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 6.dp),
                    fontSize = if (big) 22.sp else 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Kongye.Text,
                )
            } else Spacer(Modifier.weight(1f))
            actions()
        }
        if (divider) HLine()
    }
}

@Composable
fun HLine(modifier: Modifier = Modifier, color: Color = Kongye.Line) {
    Box(modifier.fillMaxWidth().height(0.6.dp).background(color))
}

// ---------------- 列表 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: Item,
    subtitle: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleOverride: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = onMore,
    /** 多选状态下不为 null：右侧显示勾选圈代替 ⋮ */
    selected: Boolean? = null,
) {
    Row(
        modifier.fillMaxWidth().height(70.dp).background(Kongye.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 列表模式下收藏直接画在图标上（左上角的蓝色书签带）
        FileIcon(item.isFolder, 30.dp, starred = item.starred && item.deletedAt == null)
        Spacer(Modifier.width(16.dp))
        Row(
            Modifier.weight(1f).fillMaxSize().drawBehind {
                drawLine(Kongye.Divider, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    titleOverride ?: item.displayTitle,
                    fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 13.sp, color = Kongye.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if(item.pinned && item.deletedAt==null) { PinnedMark(); Spacer(Modifier.width(6.dp)) }
            if (selected != null) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { SelectCircle(selected) }
                Spacer(Modifier.width(8.dp))
            } else {
                if (trailing != null) trailing()
                if (onMore != null) IconBtn(KongyeIcons.MoreV, onMore, tint = Kongye.Icon2, size = 44.dp, iconSize = 20.dp, description = "更多")
                else Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/** 开关（石墨样式）：浅灰底、带一点阴影的白色圆钮；打开后底色变浅蓝，圆钮滑到右边 */
@Composable
fun KongyeSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (checked) 1f else 0f, androidx.compose.animation.core.tween(180), label = "switch",
    )
    val off = if (Kongye.dark) Color(0xFF3A3B3F) else Color(0xFFE9E9EB)
    val on = if (Kongye.dark) Color(0xFF4F7FD6) else Color(0xFF8DB4F2)
    Box(
        modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(androidx.compose.ui.graphics.lerp(off, on, t))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onChange(!checked) },
    ) {
        Box(
            Modifier.padding(2.dp).offset(x = 20.dp * t).size(26.dp)
                .shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/** 多选的勾选圈：未选中是灰色空心圈，选中是蓝底白勾 */
@Composable
fun SelectCircle(selected: Boolean, size: Dp = 21.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (selected) Modifier.background(Kongye.Bookmark) else Modifier.border(1.4.dp, Kongye.Text4, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(KongyeIcons.Check, "已选中", Modifier.size(size * 0.66f), tint = Color.White)
    }
}

/** 网格（缩略图）视图中的一张卡片：时间、标题，下方是文件夹图标或正文预览 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    item: Item,
    meta: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = onMore,
    selected: Boolean? = null,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected == true) Kongye.PrimarySoft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meta, Modifier.weight(1f), fontSize = 12.sp, color = Kongye.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(item.pinned && item.deletedAt==null) PinnedMark()
            if (item.starred) BookmarkMark()
            if (selected != null) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { SelectCircle(selected, 19.dp) }
            else IconBtn(KongyeIcons.MoreV, onMore, tint = Kongye.Icon2, size = 34.dp, iconSize = 18.dp, description = "更多")
        }
        Text(
            item.displayTitle, Modifier.padding(end = 12.dp),
            fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.fillMaxWidth().padding(end = 12.dp).height(156.dp), contentAlignment = Alignment.Center) {
            if (item.isFolder) {
                FileIcon(true, 92.dp)
            } else {
                // 文档一律显示正文预览；没有文字时也保留预览区，淡淡地写“无内容”
                Text(
                    item.preview.ifBlank { "无内容" }, Modifier.fillMaxSize().padding(top = 12.dp),
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (item.preview.isBlank()) Kongye.Text4 else Kongye.Text4,
                    maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = Kongye.Text3)
        trailing?.invoke()
    }
}

// ---------------- 空状态 ----------------

@Composable
fun PinnedMark() {
    Icon(KongyeIcons.Pin,"已置顶",Modifier.size(15.dp),tint=Kongye.Sub)
}

/** 已收藏的标记：蓝色书签，放在 ⋮ 左边 */
@Composable
fun BookmarkMark(size: Dp = 17.dp) {
    Icon(KongyeIcons.Bookmark, "已收藏", Modifier.size(size), tint = Kongye.Bookmark)
}

/** 石墨风格的空状态：一个大大的“無”字加说明 */
@Composable
fun EmptyState(title: String, desc: String? = null, modifier: Modifier = Modifier, top: Dp = 150.dp) {
    Column(
        modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = top, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("無", fontSize = 64.sp, color = Kongye.Text4, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 14.sp, color = Kongye.Sub, textAlign = TextAlign.Center, lineHeight = 22.sp)
        if (desc != null) Text(desc, fontSize = 14.sp, color = Kongye.Sub, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

// ---------------- 轻提示 ----------------

/**
 * 轻提示。show：屏幕下方一行小字（说明、出错）；
 * done：操作成功（删除、移动、创建、保存……），石墨样式，屏幕正中一个深灰方块，上面一个对勾。
 */
object Toast {
    var message by mutableStateOf<String?>(null)
        private set
    var success by mutableStateOf(false)
        private set
    private var seq by mutableStateOf(0)
    val key get() = seq

    fun show(text: String) {
        message = text
        success = false
        seq++
    }

    fun done(text: String) {
        message = text
        success = true
        seq++
    }

    fun clear() { message = null }
}

@Composable
fun ToastHost() {
    val key = Toast.key
    val msg = Toast.message
    val success = Toast.success
    LaunchedEffect(key) {
        if (msg != null) {
            delay(if (success) 1500 else 2000)
            Toast.clear()
        }
    }
    // 消失动画期间保留最后一条的样式和文字
    var shown by remember { mutableStateOf("" to false) }
    if (msg != null) shown = msg to success
    val (text, centered) = shown
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = if (centered) 0.dp else 120.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = msg != null, enter = fadeIn(), exit = fadeOut()) {
            if (centered) {
                Column(
                    Modifier.widthIn(min = 132.dp, max = 240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF0505459))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(30.dp)) {
                        val w = 1.8.dp.toPx()
                        drawCircle(Color.White, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                        val check = Path().apply {
                            moveTo(size.width * 0.3f, size.height * 0.52f)
                            lineTo(size.width * 0.45f, size.height * 0.66f)
                            lineTo(size.width * 0.71f, size.height * 0.38f)
                        }
                        drawPath(check, Color.White, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(text, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                }
            } else {
                Text(
                    text,
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE61F2329))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.White, fontSize = 14.sp,
                )
            }
        }
    }
}
