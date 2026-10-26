package com.moji.notes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.data.Repository
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.moji.notes.data.Templates
import com.moji.notes.data.UserTemplate
import com.moji.notes.ui.components.ConfirmDialog
import com.moji.notes.ui.components.Toast
import com.moji.notes.util.stampTime
import com.moji.notes.ui.AppState
import com.moji.notes.ui.DialogRequest
import com.moji.notes.ui.Screen
import com.moji.notes.ui.Tab
import com.moji.notes.ui.components.EmptyState
import com.moji.notes.ui.components.FileRow
import com.moji.notes.ui.components.IconBtn
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.SectionLabel
import com.moji.notes.ui.components.TopBar
import com.moji.notes.ui.home.BottomTabs
import com.moji.notes.ui.home.Fab
import com.moji.notes.ui.home.FolderContents
import com.moji.notes.ui.home.SelectionBar
import com.moji.notes.ui.home.SelectionHeader
import com.moji.notes.ui.itemSubtitle
import com.moji.notes.ui.theme.Moji
import com.moji.notes.util.formatTime
import kotlinx.coroutines.delay

// ---------------- 文件夹 ----------------

@Composable
fun FolderScreen(id: String) {
    val items by Repository.items.collectAsState()
    val folder = items.firstOrNull { it.id == id }
    if (folder == null || !Repository.isAlive(folder, items)) {
        LaunchedEffect(Unit) { AppState.pop() }
        return
    }
    val selection = AppState.selection
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                // 石墨风格顶栏：返回 · 居中的“文件夹名 ∨”（点开是文件夹菜单）· 搜索；多选时换成多选顶栏
                if (selection != null) SelectionHeader(Repository.children(id, items).map { it.id })
                else Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp)) {
                    IconBtn(MojiIcons.Back, { AppState.pop() }, Modifier.align(Alignment.CenterStart), tint = Moji.Ink2, description = "返回")
                    Row(
                        Modifier.align(Alignment.Center).fillMaxWidth(0.62f).clip(RoundedCornerShape(8.dp))
                            .clickable { AppState.menuFor = folder }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            folder.displayTitle, Modifier.weight(1f, fill = false),
                            fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Moji.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(MojiIcons.ChevronDown, null, Modifier.size(18.dp), tint = Moji.Ink2)
                    }
                    IconBtn(MojiIcons.Search, { AppState.searchOpen = true }, Modifier.align(Alignment.CenterEnd), tint = Moji.Ink2, iconSize = 24.dp, description = "搜索")
                }
                FolderContents(id, items)
            }
            if (selection == null) Fab(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) { AppState.requestCreate(id) }
        }
        if (selection != null) SelectionBar(items.filter { it.id in selection && it.deletedAt == null })
        else BottomTabs(Tab.FILES) { AppState.goHome(it) }
    }
}

// ---------------- 回收站 ----------------

@Composable
fun TrashScreen() {
    val items by Repository.items.collectAsState()
    val list = remember(items) { items.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        TopBar(title = "回收站", onBack = { AppState.pop() }) {
            if (list.isNotEmpty()) {
                Text(
                    "清空", Modifier.clip(RoundedCornerShape(8.dp)).clickable { AppState.dialog = DialogRequest.EmptyTrash }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 15.sp, color = Moji.Danger,
                )
            }
        }
        Text(
            "文件会保留到你手动彻底删除；卸载或清除应用数据会删除全部内容",
            Modifier.fillMaxWidth().background(Moji.Bg2).padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 12.sp, color = Moji.Text3,
        )
        if (list.isEmpty()) EmptyState("回收站是空的")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(list, key = { it.id }) { item ->
                FileRow(
                    item, "删除于 ${formatTime(item.deletedAt ?: 0)}",
                    onClick = { AppState.menuFor = item }, onMore = { AppState.menuFor = item },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------- 模板库 ----------------

@Composable
fun TemplatesScreen(parentId: String?) {
    val mine by Repository.userTemplates.collectAsState()
    var deleting by remember { mutableStateOf<UserTemplate?>(null) }
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        TopBar(title = "模板库", onBack = { AppState.pop() })
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (mine.isNotEmpty()) {
                item(key = "h-mine", span = { GridItemSpan(maxLineSpan) }) { GridHeader("我的模板", "长按可删除") }
                items(mine, key = { "u-" + it.id }) { t ->
                    TemplateCard(
                        t.name, "保存于 ${stampTime(t.createdAt)}", 0xFF8A8A8A, blank = false,
                        onLongClick = { deleting = t },
                    ) { AppState.push(Screen.TemplatePreview(t.id, userTemplate = true, parentId = parentId)) }
                }
                item(key = "h-builtin", span = { GridItemSpan(maxLineSpan) }) { GridHeader("推荐模板", null) }
            }
            items(Templates.all, key = { it.id }) { t ->
                TemplateCard(t.name, t.desc, t.accent, blank = t.id == "blank") {
                    // 空白文档直接新建；其它模板先进入预览，确认后才创建文档
                    if (t.id == "blank") AppState.openFromTemplate(Repository.createDoc(parentId).id, parentId, isNew = true)
                    else AppState.push(Screen.TemplatePreview(t.id, userTemplate = false, parentId = parentId))
                }
            }
        }
    }
    deleting?.let { t ->
        ConfirmDialog(
            title = "删除模板", message = "「${t.name}」将从模板库中删除，已经用它创建的文档不受影响。",
            confirmText = "删除", danger = true, onDismiss = { deleting = null },
        ) {
            deleting = null
            Repository.deleteTemplate(t.id)
            Toast.show("模板已删除")
        }
    }
}

@Composable
private fun GridHeader(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = Moji.Ink2)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, fontSize = 12.sp, color = Moji.Sub)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TemplateCard(
    name: String,
    desc: String,
    accentColor: Long,
    blank: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = Color(accentColor)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Moji.Line, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().height(124.dp).background(accent.copy(alpha = 0.08f)).padding(14.dp)) {
            if (blank) {
                Icon(MojiIcons.Plus, null, Modifier.size(36.dp).align(Alignment.Center), tint = accent)
            } else {
                Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Moji.Surface).padding(10.dp)) {
                    Box(Modifier.width(56.dp).height(7.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                    Spacer(Modifier.height(9.dp))
                    listOf(1f, 0.85f, 0.6f, 0.9f, 0.5f).forEach { frac ->
                        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Moji.Line2))
                        Spacer(Modifier.height(7.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = Moji.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
