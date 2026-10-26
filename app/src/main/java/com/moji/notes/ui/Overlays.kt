package com.moji.notes.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.moji.notes.data.Converter
import com.moji.notes.data.DocContent
import com.moji.notes.data.Item
import com.moji.notes.data.Repository
import com.moji.notes.data.plainText
import com.moji.notes.ui.components.ActionSheet
import com.moji.notes.ui.components.ConfirmDialog
import com.moji.notes.ui.components.CreateOption
import com.moji.notes.ui.components.CreateSheet
import com.moji.notes.ui.components.FolderPickerSheet
import com.moji.notes.ui.components.InputDialog
import com.moji.notes.ui.components.ItemSheetHeader
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.SheetAction
import com.moji.notes.ui.components.Toast
import com.moji.notes.util.Share
import com.moji.notes.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件所在位置的名称 */
fun locationOf(item: Item): String = Repository.get(item.parentId)?.displayTitle ?: "我的桌面"

fun itemSubtitle(item: Item): String =
    if (item.isFolder) "${Repository.children(item.id).size} 项 · ${formatTime(item.updatedAt)}"
    else "${locationOf(item)} · ${formatTime(item.updatedAt)}"

@Composable
fun GlobalOverlays() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Repository.items.collectAsState()

    // ---------- 导入文件 ----------
    var importTarget by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val parent = importTarget
        if (uris.isNotEmpty()) scope.launch { importFiles(context, uris, parent) }
    }
    LaunchedEffect(AppState.importInto) {
        val target = AppState.importInto ?: return@LaunchedEffect
        importTarget = target.fromRoot()
        AppState.importInto = null
        runCatching { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }
            .onFailure { Toast.show("没有可用的文件管理器") }
    }

    // ---------- 条目操作面板 ----------
    AppState.menuFor?.let { menuItem ->
        val item = items.firstOrNull { it.id == menuItem.id } ?: menuItem
        val actions = if (item.deletedAt != null) listOf(
            SheetAction(MojiIcons.Restore, "恢复") {
                Repository.restore(item.id)
                Toast.show("已恢复到「${locationOf(Repository.get(item.id) ?: item)}」")
            },
            SheetAction(MojiIcons.Trash, "彻底删除", danger = true) { AppState.dialog = DialogRequest.Destroy(item) },
        ) else buildList {
            add(SheetAction(if (item.starred) MojiIcons.StarFilled else MojiIcons.Star, if (item.starred) "取消收藏" else "收藏") {
                Repository.toggleStar(item.id)
                Toast.show(if (item.starred) "已取消收藏" else "已收藏")
            })
            add(SheetAction(MojiIcons.Edit, "重命名") { AppState.dialog = DialogRequest.Rename(item) })
            add(SheetAction(MojiIcons.Move, "移动到") { AppState.dialog = DialogRequest.Move(listOf(item)) })
            if (item.isDoc) {
                add(SheetAction(MojiIcons.Copy, "创建副本") {
                    Repository.duplicate(item.id)
                    Toast.show("已创建副本")
                })
                add(SheetAction(MojiIcons.Share, "分享") { AppState.shareFor = item })
            }
            add(SheetAction(MojiIcons.Trash, "删除", danger = true) {
                Repository.trash(item.id)
                Toast.show("已移到回收站")
            })
        }
        ActionSheet(
            onDismiss = { AppState.menuFor = null },
            actions = actions,
            header = { ItemSheetHeader(item, if (item.deletedAt != null) "删除于 ${formatTime(item.deletedAt)}" else itemSubtitle(item)) },
        )
    }

    // ---------- 分享面板 ----------
    AppState.shareFor?.let { item -> DocShareSheet(item.id, onDismiss = { AppState.shareFor = null }) }

    // ---------- 新建面板 ----------
    AppState.createIn?.let { target ->
        val parent = target.fromRoot()
        CreateSheet(
            onDismiss = { AppState.createIn = null },
            options = listOf(
                CreateOption("文档", MojiIcons.Doc) { AppState.newDoc(parent) },
                CreateOption("文件夹", MojiIcons.Folder) { AppState.dialog = DialogRequest.NewFolder(parent) },
                CreateOption("模板", MojiIcons.Template) { AppState.push(Screen.Templates(parent)) },
                CreateOption("导入", MojiIcons.Upload) { AppState.importInto = target },
            ),
        )
    }

    // ---------- 弹窗 ----------
    val close = { AppState.dialog = null }
    when (val d = AppState.dialog) {
        is DialogRequest.NewFolder -> InputDialog(
            title = "新建文件夹", placeholder = "请输入文件夹名称", confirmText = "创建", onDismiss = close,
        ) { name ->
            close()
            Repository.createFolder(d.parentId, name)
            Toast.show("已创建文件夹")
        }
        is DialogRequest.Rename -> InputDialog(
            title = "重命名", initial = d.item.title, placeholder = d.item.displayTitle, onDismiss = close,
        ) { name ->
            close()
            Repository.rename(d.item.id, name)
        }
        is DialogRequest.Move -> FolderPickerSheet(d.items, onDismiss = close) { target ->
            d.items.forEach { Repository.move(it.id, target) }
            AppState.endSelection()
            val where = Repository.get(target)?.displayTitle ?: "我的桌面"
            Toast.show(if (d.items.size > 1) "已将 ${d.items.size} 项移动到「$where」" else "已移动到「$where」")
        }
        is DialogRequest.TrashMany -> ConfirmDialog(
            title = "删除 ${d.items.size} 项", message = "将移到回收站，30 天内可以恢复。",
            confirmText = "删除", danger = true, onDismiss = close,
        ) {
            close()
            d.items.forEach { Repository.trash(it.id) }
            AppState.endSelection()
            Toast.show("已将 ${d.items.size} 项移到回收站")
        }
        is DialogRequest.Destroy -> ConfirmDialog(
            title = "彻底删除", message = "「${d.item.displayTitle}」将被永久删除，无法恢复。",
            confirmText = "删除", danger = true, onDismiss = close,
        ) {
            close()
            Repository.destroy(d.item.id)
            Toast.show("已彻底删除")
        }
        DialogRequest.EmptyTrash -> ConfirmDialog(
            title = "清空回收站", message = "回收站中的所有文件将被永久删除，无法恢复。",
            confirmText = "清空", danger = true, onDismiss = close,
        ) {
            close()
            Repository.emptyTrash()
            Toast.show("回收站已清空")
        }
        null -> {}
    }
}

private suspend fun importFiles(context: Context, uris: List<Uri>, parentId: String?) {
    val parsed = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            val name = Share.displayName(context, uri)
            val text = Share.readText(context, uri) ?: return@mapNotNull null
            val base = name.substringBeforeLast('.')
            when (name.substringAfterLast('.', "").lowercase()) {
                "md", "markdown" -> Converter.fromMarkdown(text, base)
                "html", "htm" -> base to Converter.fromHtml(text)
                else -> if (text.trimStart().startsWith("#")) Converter.fromMarkdown(text, base)
                else base to Converter.fromPlainText(text)
            }
        }
    }
    if (parsed.isEmpty()) {
        Toast.show("没有可以导入的文件")
        return
    }
    val created = parsed.map { (title, doc: DocContent) -> Repository.createDoc(parentId, title, doc) }
    if (created.size == 1) AppState.openDoc(created[0].id) else Toast.show("已导入 ${created.size} 篇文档")
}
