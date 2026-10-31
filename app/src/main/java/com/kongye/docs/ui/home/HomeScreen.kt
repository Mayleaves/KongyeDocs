package com.kongye.docs.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Item
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.DialogRequest
import com.kongye.docs.ui.FilesSub
import com.kongye.docs.ui.HomeSub
import com.kongye.docs.ui.SortMode
import com.kongye.docs.ui.SortSpec
import com.kongye.docs.ui.Tab
import java.text.Collator
import java.util.Locale
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.EmptyState
import com.kongye.docs.ui.components.FileCard
import com.kongye.docs.ui.components.FileRow
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.SheetRow
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.vault.VaultPage
import com.kongye.docs.util.dateHeader
import com.kongye.docs.util.stampTime
import kotlin.math.max

@Composable
fun HomeScreen() {
    val items by Repository.items.collectAsState()
    Column(Modifier.fillMaxSize().background(Kongye.Surface)) {
        Box(Modifier.weight(1f)) {
            when (AppState.tab) {
                Tab.HOME -> HomeTab(items)
                Tab.FILES -> FilesTab(items)
                Tab.ME -> MeTab(items)
            }
            if (AppState.tab != Tab.ME && AppState.selection == null) {
                Fab(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) {
                    // 密码空间里的 + 是添加动态密码 / 密码
                    if (AppState.tab == Tab.FILES && AppState.filesSub == FilesSub.VAULT) AppState.vaultAdd = true
                    else AppState.requestCreate(null)
                }
            }
        }
        val selection = AppState.selection
        if (selection != null && AppState.tab != Tab.ME) SelectionBar(items.filter { it.id in selection && it.deletedAt == null })
        else BottomTabs(AppState.tab) { AppState.tab = it }
    }
}

// ---------------- 多选 ----------------

/** 多选时替换页面顶栏：取消 · 已选择 N 项 · 全选 */
@Composable
fun SelectionHeader(allIds: List<String>) {
    val selected = AppState.selection.orEmpty()
    val all = allIds.isNotEmpty() && allIds.all { it in selected }
    Box(Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding().height(56.dp).padding(horizontal = 6.dp)) {
        Text(
            "取消", Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable { AppState.endSelection() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 16.sp, color = Kongye.Ink2,
        )
        Text("已选择 ${selected.size} 项", Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Kongye.Ink)
        Text(
            if (all) "全不选" else "全选",
            Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp))
                .clickable { AppState.selection = if (all) emptySet() else allIds.toSet() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 16.sp, color = Kongye.Bookmark,
        )
    }
}

/** 多选时替换底部导航：置顶 / 收藏 / 移动 / 删除 */
@Composable
fun SelectionBar(selected: List<Item>) {
    val enabled = selected.isNotEmpty()
    val allStarred = enabled && selected.all { it.starred }
    val allPinned = enabled && selected.all { it.pinned }
    Column(Modifier.fillMaxWidth().background(Kongye.Surface).navigationBarsPadding()) {
        HLine(color = Kongye.Divider)
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            BarAction(if (allPinned) KongyeIcons.PinFilled else KongyeIcons.Pin, if (allPinned) "取消置顶" else "置顶", enabled) {
                // 按勾选的先后置顶：先勾的排在上面
                val ids = AppState.selection.orEmpty().filter { id -> selected.any { it.id == id } }
                Repository.setPinned(ids, !allPinned)
                Toast.done(if (allPinned) "已取消置顶 ${selected.size} 项" else "已置顶 ${selected.size} 项")
                AppState.endSelection()
            }
            BarAction(if (allStarred) KongyeIcons.StarFilled else KongyeIcons.Star, if (allStarred) "取消收藏" else "收藏", enabled) {
                selected.filter { it.starred == allStarred }.forEach { Repository.toggleStar(it.id) }
                Toast.done(if (allStarred) "已取消收藏 ${selected.size} 项" else "已收藏 ${selected.size} 项")
                AppState.endSelection()
            }
            BarAction(KongyeIcons.Move, "移动", enabled) { AppState.dialog = DialogRequest.Move(selected) }
            BarAction(KongyeIcons.Trash, "删除", enabled, tint = Kongye.Danger) { AppState.dialog = DialogRequest.TrashMany(selected) }
        }
    }
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean, tint: Color = Kongye.Ink2, onClick: () -> Unit) {
    val color = if (enabled) tint else Kongye.NavOff
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = color)
    }
}

// ---------------- 公共部件 ----------------

/** 石墨风格的深灰色圆形新建按钮（不带阴影） */
@Composable
fun Fab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(56.dp)
            .clip(CircleShape).background(Brush.verticalGradient(listOf(Kongye.FabTop, Kongye.FabBottom)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(KongyeIcons.Plus, "新建", Modifier.size(28.dp), tint = Color.White) }
}

private val nameOrder = Collator.getInstance(Locale.CHINA)

/**
 * 按排序方式排列；“按类型”时文件夹在前、文档在后，同类之间按名称。
 * 手动排序按 manual 记下的顺序，没排过的（之后新建、移进来的）放在最前，新的在上。
 */
/** Stable fixed pin order, independent of the selected sort direction or manual order. */
internal fun pinnedFirst(list: List<Item>): List<Item> = list.sortedWith { a,b ->
    val order=b.pinnedAt.compareTo(a.pinnedAt)
    if(order!=0) order else if(a.pinned) a.id.compareTo(b.id) else 0
}

fun sortItems(list: List<Item>, spec: SortSpec, manual: List<String> = emptyList()): List<Item> {
    if (spec.mode == SortMode.MANUAL) {
        val rank = manual.withIndex().associate { it.value to it.index }
        val (known, fresh) = list.partition { it.id in rank }
        return pinnedFirst(fresh.sortedByDescending { it.createdAt } + known.sortedBy { rank[it.id] })
    }
    val byName = compareBy<Item, String>(nameOrder) { it.displayTitle }
    val ascending = when (spec.mode) {
        SortMode.NAME -> list.sortedWith(byName)
        SortMode.TYPE -> list.sortedWith(compareBy<Item> { if (it.isFolder) 0 else 1 }.then(byName))
        SortMode.CREATED -> list.sortedBy { it.createdAt }
        SortMode.UPDATED, SortMode.MANUAL -> list.sortedBy { it.updatedAt }
    }
    return pinnedFirst(if (spec.desc) ascending.reversed() else ascending)
}

fun updatedMeta(item: Item) = "${stampTime(item.updatedAt)} 更新"

/** 网格视图左右的留白 */
private val GRID_SIDE = 12.dp

/** 一组条目（可带日期标题），每个条目附带一行时间说明 */
class Section(val title: String?, val entries: List<Pair<Item, String>>)

/**
 * 列表 / 网格两种展示方式。top 是排序栏等置顶内容，firstHeaderTrailing 显示在第一个日期标题右侧。
 * inset 参数让置顶内容在两种模式下都和左边距 20dp 对齐。
 * onReorder 不为 null 时（只有一组、不带日期标题），长按后拖动可以调整顺序，松手时回调新的完整顺序。
 * 置顶项在任何列表里都可以长按拖动，只和其他置顶项换位；松手后保存为新的置顶顺序（各处共用），不改排序方式。
 */
@Composable
fun ItemCollection(
    sections: List<Section>,
    grid: Boolean,
    emptyTitle: String,
    emptyDesc: String? = null,
    top: (@Composable (inset: Dp) -> Unit)? = null,
    firstHeaderTrailing: (@Composable () -> Unit)? = null,
    onReorder: ((List<Item>) -> Unit)? = null,
) {
    val reorder = remember { ReorderState() }
    // 拖动中按临时顺序显示；换位只发生在同一组内，各组条数不变，标题（如“置顶”、日期）留在原处
    val shown = reorder.order?.let { order ->
        if (order.size != sections.sumOf { it.entries.size }) listOf(Section(null, order))
        else {
            var next = 0
            sections.map { s -> Section(s.title, order.subList(next, next + s.entries.size).also { next += s.entries.size }) }
        }
    } ?: sections
    val empty = shown.all { it.entries.isEmpty() }
    val useGrid = grid && !empty
    // 多选时点击是勾选 / 取消勾选；长按由下面容器上的手势处理
    val selection = AppState.selection
    val click = { item: Item -> if (selection != null) AppState.toggleSelect(item.id) else AppState.open(item) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val gridStart = with(density) { GRID_SIDE.toPx() }
    val slots: () -> List<Slot> = {
        if (useGrid) gridState.layoutInfo.visibleItemsInfo.map {
            Slot(it.key, it.index, it.offset.toOffset() + Offset(gridStart, 0f), it.size.toSize())
        } else listState.layoutInfo.let { info ->
            info.visibleItemsInfo.map { Slot(it.key, it.index, Offset(0f, it.offset.toFloat()), Size(info.viewportSize.width.toFloat(), it.size.toFloat())) }
        }
    }
    // 换位涉及第一个可见条目时，按位置而不是按 key 保持滚动，免得列表跟着被拖的条目跳走
    val keepScroll: (Slot, Slot) -> Unit = { a, b ->
        if (useGrid) {
            val first = gridState.firstVisibleItemIndex
            if (a.index == first || b.index == first) gridState.requestScrollToItem(first, gridState.firstVisibleItemScrollOffset)
        } else {
            val first = listState.firstVisibleItemIndex
            if (a.index == first || b.index == first) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
    }
    val entries by rememberUpdatedState(shown.flatMap { it.entries })
    val canDrag by rememberUpdatedState(onReorder != null && sections.size == 1 && sections[0].title == null)
    val commit by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    fun drop() {
        val order = reorder.order ?: return reorder.clear()
        reorder.release()
        if (order.any { it.first.id == reorder.id && it.first.pinned }) Repository.reorderPinned(order.filter { it.first.pinned }.map { it.first.id })
        else commit?.invoke(order.map { it.first })
        // 松手后条目滑回它在新顺序里的位置
        scope.launch {
            val slot = slots().firstOrNull { it.key == reorder.id }
            if (slot != null) {
                val from = reorder.delta
                val to = slot.pos - reorder.start
                animate(0f, 1f, animationSpec = tween(160)) { v, _ -> reorder.delta = lerp(from, to, v) }
            }
            reorder.clear()
        }
    }

    // 长按进入多选（已在多选时则勾选 / 取消勾选）；可以拖动时，长按后继续移动就改为拖动排序
    val gestures = Modifier.pointerInput(useGrid) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (reorder.id != null) return@awaitEachGesture
            val slot = slots().firstOrNull { it.contains(press.position, useGrid) } ?: return@awaitEachGesture
            val id = entries.firstOrNull { it.first.id == slot.key }?.first?.id ?: return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val before = AppState.selection
            if (before == null) AppState.startSelection(id) else AppState.toggleSelect(id)
            var moved = Offset.Zero
            try {
                while (true) {
                    // 在 Initial 阶段先拿到并消费事件：条目自己的点击不会再触发，列表也不会跟着滚动
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == press.id } ?: break
                    val d = change.position - change.previousPosition
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                    if (reorder.dragging) {
                        reorder.pointer = change.position
                        reorder.moveBy(if (useGrid) d else Offset(0f, d.y), slots(), useGrid, keepScroll)
                    } else if (if (entries.any { it.first.id == id && it.first.pinned }) entries.count { it.first.pinned } > 1 else canDrag) {
                        moved += d
                        if (moved.getDistance() > viewConfiguration.touchSlop) {
                            // 长按后拖动：这次长按不算选择，改为拖动排序
                            AppState.selection = before
                            reorder.begin(id, slot.pos, if (useGrid) moved else Offset(0f, moved.y), entries)
                            reorder.pointer = change.position
                        }
                    }
                }
            } finally {
                if (reorder.dragging) drop()
            }
        }
    }

    // 拖到上下边缘附近时自动滚动
    if (reorder.dragging) LaunchedEffect(Unit) {
        val edge = with(density) { 80.dp.toPx() }
        val fastest = with(density) { 12.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val h = (if (useGrid) gridState.layoutInfo.viewportSize.height else listState.layoutInfo.viewportSize.height).toFloat()
            val y = reorder.pointer.y
            val speed = when {
                y < edge -> -fastest * (1f - y / edge)
                y > h - edge -> fastest * (1f - (h - y) / edge)
                else -> 0f
            }.coerceIn(-fastest, fastest)
            if (speed != 0f && (if (useGrid) gridState.scrollBy(speed) else listState.scrollBy(speed)) != 0f) {
                reorder.moveBy(Offset.Zero, slots(), useGrid, keepScroll)
            }
        }
    }

    if (useGrid) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().then(gestures),
            state = gridState,
            // 左右留白相等、两列之间留 10dp：置顶卡片和多选高亮的灰底 / 蓝底不会贴在一起
            contentPadding = PaddingValues(start = GRID_SIDE, end = GRID_SIDE, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (top != null) item(key = "top", span = { GridItemSpan(maxLineSpan) }) { top(20.dp - GRID_SIDE) }
            shown.forEachIndexed { i, s ->
                if (s.title != null) item(key = "h-${s.title}", span = { GridItemSpan(maxLineSpan) }) {
                    DateHeader(s.title, 20.dp - GRID_SIDE, if (i == 0) firstHeaderTrailing else null)
                }
                items(s.entries, key = { it.first.id }) { (item, meta) ->
                    val lifted = reorder.id == item.id
                    FileCard(
                        item, meta, onClick = { click(item) }, onMore = { AppState.menuFor = item },
                        modifier = if (lifted) Modifier.lifted(reorder, { slots().firstOrNull { it.key == item.id } }, RoundedCornerShape(8.dp), Kongye.Surface)
                        else Modifier.animateItem(),
                        onLongClick = null, selected = selection?.let { item.id in it },
                    )
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().then(gestures), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            if (top != null) item(key = "top") { top(20.dp) }
            if (empty) item(key = "empty") { EmptyState(emptyTitle, emptyDesc) }
            shown.forEachIndexed { i, s ->
                if (s.title != null) item(key = "h-${s.title}") {
                    DateHeader(s.title, 20.dp, if (i == 0) firstHeaderTrailing else null)
                }
                items(s.entries, key = { it.first.id }) { (item, meta) ->
                    val lifted = reorder.id == item.id
                    FileRow(
                        item, meta, onClick = { click(item) }, onMore = { AppState.menuFor = item },
                        modifier = if (lifted) Modifier.lifted(reorder, { slots().firstOrNull { it.key == item.id } }, RectangleShape, Kongye.Surface)
                        else Modifier.animateItem(),
                        onLongClick = null, selected = selection?.let { item.id in it },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(title: String, inset: Dp, trailing: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = inset, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, color = Kongye.Ink2)
        trailing?.invoke()
    }
}

@Composable
fun ViewToggle(grid: Boolean, onToggle: () -> Unit) {
    IconBtn(if (grid) KongyeIcons.ViewGrid else KongyeIcons.ViewList, onToggle, tint = Kongye.Ink2, iconSize = 22.dp, description = if (grid) "切换为列表" else "切换为缩略图")
}

/** 除手动排序以外的排序方式（收藏、创建里没有手动排序） */
val autoSortModes = SortMode.entries.filter { it != SortMode.MANUAL }

/** “按更新时间 ∨” 排序栏，右侧是视图切换 */
@Composable
fun SortRow(
    spec: SortSpec,
    inset: Dp,
    onChange: (SortSpec) -> Unit,
    modes: List<SortMode> = autoSortModes,
    trailing: @Composable () -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(50.dp).padding(start = inset, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { show = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(spec.mode.short, fontSize = 15.sp, color = Kongye.Ink2)
            Spacer(Modifier.width(4.dp))
            Icon(KongyeIcons.ChevronDown, null, Modifier.size(18.dp), tint = Kongye.Ink2)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
    if (show) SortSheet(spec, modes, onDismiss = { show = false }, onChange = onChange)
}

/**
 * 石墨样式的排序面板：每项之间一条细线；
 * 当前项左边是方向箭头（↓ 从新到旧 / 从 Z 到 A，手动排序没有方向），右边打勾。没有“取消”，点遮罩关闭。
 */
@Composable
fun SortSheet(current: SortSpec, modes: List<SortMode>, onDismiss: () -> Unit, onChange: (SortSpec) -> Unit) {
    BottomSheet(onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).navigationBarsPadding()) {
            modes.forEachIndexed { i, mode ->
                val selected = current.mode == mode
                Row(
                    Modifier.fillMaxWidth().height(58.dp).clickable { dismiss { onChange(current.pick(mode)) } }.padding(start = 14.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                        if (selected && mode != SortMode.MANUAL) {
                            Icon(if (current.desc) KongyeIcons.ArrowDown else KongyeIcons.ArrowUp, null, Modifier.size(20.dp), tint = Kongye.Ink2)
                        }
                    }
                    Text(mode.label, Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
                    if (selected) Icon(KongyeIcons.Check, "当前", Modifier.size(22.dp), tint = Kongye.Ink2)
                }
                if (i < modes.lastIndex) HLine(color = Kongye.Divider)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 一级页面的顶栏：左侧标题内容，右侧搜索 */
@Composable
private fun PageHeader(onSearch: () -> Unit = { AppState.searchOpen = true }, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.weight(1f))
        IconBtn(KongyeIcons.Search, onSearch, tint = Kongye.Ink2, iconSize = 24.dp, description = "搜索")
    }
}

/** 页面顶部的分栏标题（首页的最近 / 收藏 / 创建，文件页的我的桌面 / 密码空间） */
@Composable
private fun HeaderTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(end = 22.dp, top = 8.dp, bottom = 8.dp),
        fontSize = 20.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Kongye.Ink else Kongye.TabOff,
    )
}

// ---------------- 首页：最近 / 收藏 / 创建 ----------------

@Composable
private fun HomeTab(items: List<Item>) {
    val grid by Repository.homeGrid.collectAsState()
    val toggle: @Composable () -> Unit = { ViewToggle(grid) { Repository.setHomeGrid(!grid) } }
    // 最近 / 收藏 / 创建 三页可以左右滑动切换：滑动停下后同步到 homeSub；点标题（或别处改了 homeSub）时翻到对应页
    val pager = rememberPagerState(initialPage = AppState.homeSub.ordinal) { HomeSub.entries.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { AppState.homeSub = HomeSub.entries[it] } }
    LaunchedEffect(AppState.homeSub) {
        val page = AppState.homeSub.ordinal
        if (pager.targetPage != page) pager.animateScrollToPage(page)
    }
    Column(Modifier.fillMaxSize()) {
        if (AppState.selection != null) {
            val sections = homeSections(AppState.homeSub, items)
            SelectionHeader(sections.flatMap { s -> s.entries.map { it.first.id } })
        } else PageHeader {
            HomeSub.entries.forEach { sub ->
                HeaderTab(sub.label, pager.targetPage == sub.ordinal) { AppState.homeSub = sub }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = AppState.selection == null, key = { it }) { page ->
            HomePage(HomeSub.entries[page], items, grid, toggle)
        }
    }
}

@Composable
private fun homeSections(sub: HomeSub, items: List<Item>): List<Section> = when (sub) {
    HomeSub.RECENT -> remember(items) { recentSections(items) }
    HomeSub.STARRED -> remember(items, AppState.starredSort) {
        val list = sortItems(items.filter { it.starred && Repository.isAlive(it, items) }, AppState.starredSort)
        listOf(Section(null, list.map { it to updatedMeta(it) }))
    }
    HomeSub.CREATED -> remember(items, AppState.createdSort) {
        val list = sortItems(items.filter { it.isDoc && Repository.isAlive(it, items) }, AppState.createdSort)
        listOf(Section(null, list.map { it to "${stampTime(it.createdAt)} 创建" }))
    }
}

/** 首页的一页 */
@Composable
private fun HomePage(sub: HomeSub, items: List<Item>, grid: Boolean, toggle: @Composable () -> Unit) {
    val sections = homeSections(sub, items)
    when (sub) {
        HomeSub.RECENT -> {
            // 没有记录时也保留右上角的视图切换
            val emptyTop: (@Composable (Dp) -> Unit)? =
                if (sections.isEmpty()) { { inset -> DateHeader("", inset, toggle) } } else null
            ItemCollection(
                sections, grid, "暂无最近使用的文档", "点击右下角 + 新建一篇吧",
                top = emptyTop,
                firstHeaderTrailing = toggle,
            )
        }
        HomeSub.STARRED -> ItemCollection(
            sections, grid, "无收藏的文件，在文件菜单中可将文档或", "文件夹添加至收藏夹",
            top = { inset -> SortRow(AppState.starredSort, inset, { AppState.starredSort = it }, trailing = toggle) },
        )
        HomeSub.CREATED -> ItemCollection(
            sections, grid, "还没有创建过文档", "点击右下角 + 新建一篇吧",
            top = { inset -> SortRow(AppState.createdSort, inset, { AppState.createdSort = it }, trailing = toggle) },
        )
    }
}

/** 最近打开或编辑过的文档，按日期分组 */
internal fun recentSections(items: List<Item>): List<Section> {
    val available=items.filter { it.isDoc && Repository.isAlive(it,items) }
    val pinned=pinnedFirst(available.filter { it.pinned })
    val sections=available.filter { !it.pinned && (it.openedAt>0 || it.updatedAt>it.createdAt) }
        .map { it to max(it.openedAt,it.updatedAt) }
        .sortedByDescending { it.second }
        .groupBy { dateHeader(it.second) }
        .map { (date,list) -> Section(date,list.map { (item,time) ->
            item to "${stampTime(time)} ${if(item.updatedAt>=item.openedAt) "编辑" else "打开"}"
        }) }
    return (if(pinned.isEmpty()) emptyList() else listOf(Section("置顶",pinned.map { it to updatedMeta(it) })))+sections
}

// ---------------- 文件（我的桌面） ----------------

@Composable
private fun FilesTab(items: List<Item>) {
    // 我的桌面 / 密码空间：同级的两页，和首页“最近 / 收藏 / 创建”一样可以左右滑动切换
    val pager = rememberPagerState(initialPage = AppState.filesSub.ordinal) { FilesSub.entries.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { AppState.filesSub = FilesSub.entries[it] } }
    LaunchedEffect(AppState.filesSub) {
        val page = AppState.filesSub.ordinal
        if (pager.targetPage != page) pager.animateScrollToPage(page)
    }
    Column(Modifier.fillMaxSize()) {
        if (AppState.selection != null) SelectionHeader(Repository.children(null, items).map { it.id })
        else PageHeader(onSearch = {
            // 搜索跟着当前页：我的桌面搜文件，密码空间只搜密码空间
            if (FilesSub.entries[pager.targetPage] == FilesSub.VAULT) AppState.vaultSearchOpen = true else AppState.searchOpen = true
        }) {
            FilesSub.entries.forEach { sub ->
                HeaderTab(sub.label, pager.targetPage == sub.ordinal) {
                    AppState.endSelection()
                    AppState.filesSub = sub
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = AppState.selection == null, key = { it }) { page ->
            if (FilesSub.entries[page] == FilesSub.VAULT) VaultPage() else FolderContents(null, items)
        }
    }
}

/** 某个文件夹（null 为桌面根目录）里的内容：排序栏 + 列表 / 网格 */
@Composable
fun FolderContents(folderId: String?, items: List<Item>) {
    val grid by Repository.filesGrid.collectAsState()
    val orders by Repository.manualOrders.collectAsState()
    val manual = orders[folderId ?: ""].orEmpty()
    val list = remember(items, folderId, AppState.sort, manual) { sortItems(Repository.children(folderId, items), AppState.sort, manual) }
    ItemCollection(
        listOf(Section(null, list.map { it to updatedMeta(it) })), grid,
        if (folderId == null) "桌面空空如也" else "文件夹是空的", "点击右下角 + 新建文档或文件夹",
        top = { inset ->
            SortRow(
                AppState.sort, inset,
                onChange = { spec ->
                    // 第一次切到手动排序时，以当前看到的顺序为起点，列表不会突然跳动
                    if (spec.mode == SortMode.MANUAL && manual.isEmpty()) Repository.setManualOrder(folderId, list.map { it.id })
                    AppState.sort = spec
                },
                modes = SortMode.entries,
            ) { ViewToggle(grid) { Repository.setFilesGrid(!grid) } }
        },
        onReorder = { order ->
            Repository.setManualOrder(folderId, order.map { it.id })
            if (AppState.sort.mode != SortMode.MANUAL) {
                AppState.sort = SortSpec(SortMode.MANUAL)
                Toast.show("已切换为手动排序")
            }
        },
    )
}

// ---------------- 底部导航 ----------------

@Composable
fun BottomTabs(active: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface).navigationBarsPadding()) {
        HLine(color = Kongye.Divider)
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            NavItem("首页", KongyeIcons.NavHome, active == Tab.HOME) { onSelect(Tab.HOME) }
            NavItem("文件", KongyeIcons.NavFiles, active == Tab.FILES) { onSelect(Tab.FILES) }
            NavItem("我的", KongyeIcons.NavMe, active == Tab.ME) { onSelect(Tab.ME) }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(25.dp), tint = if (selected) Kongye.Ink2 else Kongye.NavOff)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Kongye.Ink2 else Kongye.TabOff)
    }
}
