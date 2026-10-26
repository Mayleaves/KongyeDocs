package com.moji.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.data.Item
import com.moji.notes.data.Repository
import com.moji.notes.ui.theme.Moji
import kotlinx.coroutines.launch

class SheetAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** 统一样式的底部弹出面板。content 里调用 dismiss(then) 可先收起面板再执行操作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    showHandle: Boolean = true,
    /** false：内容里上下滑动只滚动内容，不会把面板拖下去关掉（点遮罩或返回键仍可关闭） */
    dragToDismiss: Boolean = true,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.(dismiss: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: (() -> Unit) -> Unit = { then ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        sheetGesturesEnabled = dragToDismiss,
        shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius),
        containerColor = Moji.Surface,
        scrimColor = Moji.Mask,
        dragHandle = if (showHandle) {
            { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Moji.Line2)) }
        } else null,
    ) {
        content(dismiss)
    }
}

@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
    header: (@Composable () -> Unit)? = null,
) {
    BottomSheet(onDismiss) { dismiss ->
        if (header != null) {
            header()
            HLine()
        }
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            actions.forEach { a -> SheetRow(a.icon, a.label, a.danger) { dismiss(a.onClick) } }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Moji.Bg2))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clickable { dismiss {} },
            contentAlignment = Alignment.Center,
        ) { Text("取消", fontSize = 16.sp, color = Moji.Text2) }
    }
}

@Composable
fun SheetRow(icon: ImageVector?, label: String, danger: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (danger) Moji.Danger else Moji.Text2)
            Spacer(Modifier.width(14.dp))
        }
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = if (danger) Moji.Danger else Moji.Text)
        if (trailing != null) Text(trailing, fontSize = 14.sp, color = Moji.Text3)
    }
}

@Composable
fun ItemSheetHeader(item: Item, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FileIcon(item.isFolder, 40.dp, starred = item.starred && item.deletedAt == null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = Moji.Text3, maxLines = 1)
        }
    }
}

// ---------------- 新建面板 ----------------

class CreateOption(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun CreateSheet(onDismiss: () -> Unit, options: List<CreateOption>) {
    BottomSheet(onDismiss) { dismiss ->
        Text("新建", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            options.forEach { o ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { dismiss(o.onClick) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 与条目操作面板一致的单色线性图标，垫一块浅灰圆角底
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Moji.Field), contentAlignment = Alignment.Center) {
                        Icon(o.icon, null, Modifier.size(27.dp), tint = Moji.Ink2)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(o.label, fontSize = 13.sp, color = Moji.Text)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(Moji.Bg2))
        Box(Modifier.fillMaxWidth().height(54.dp).clickable { dismiss {} }, contentAlignment = Alignment.Center) {
            Text("取消", fontSize = 16.sp, color = Moji.Text2)
        }
    }
}

// ---------------- 移动到文件夹 ----------------

@Composable
fun FolderPickerSheet(moving: List<Item>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val all by Repository.items.collectAsState()
    // 不能移到正在移动的文件夹自身或其子文件夹里
    val excluded = remember(moving) { moving.flatMap { m -> Repository.descendants(m.id).map { it.id } + m.id }.toSet() }
    val rows = remember(moving, all) {
        val out = mutableListOf<Pair<Item, Int>>()
        fun walk(parent: String?, depth: Int) {
            all.filter { it.isFolder && it.parentId == parent && it.deletedAt == null && it.id !in excluded }
                .sortedBy { it.displayTitle }
                .forEach { out += it to depth; walk(it.id, depth + 1) }
        }
        walk(null, 1)
        out
    }
    // 都在同一个文件夹时默认选中它，否则默认桌面
    var selected by remember { mutableStateOf(moving.map { it.parentId }.distinct().singleOrNull()) }
    BottomSheet(onDismiss) { dismiss ->
        Text(if (moving.size > 1) "移动 ${moving.size} 项到" else "移动到", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            FolderPickRow("我的桌面", 0, selected == null, root = true) { selected = null }
            rows.forEach { (f, depth) -> FolderPickRow(f.displayTitle, depth, selected == f.id) { selected = f.id } }
        }
        HLine()
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Moji.Primary)
                    .clickable { dismiss { onPick(selected) } },
                contentAlignment = Alignment.Center,
            ) { Text("移动到此处", color = Color.White, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun FolderPickRow(name: String, depth: Int, selected: Boolean, root: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(50.dp).background(if (selected) Moji.PrimarySoft else Color.Transparent)
            .clickable(onClick = onClick).padding(start = (20 + depth * 20).dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (root) Icon(MojiIcons.Folder, null, Modifier.size(24.dp), tint = Moji.Primary) else FileIcon(true, 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), fontSize = 15.sp, color = if (selected) Moji.Primary else Moji.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(MojiIcons.Check, null, Modifier.size(20.dp), tint = Moji.Primary)
    }
}
