package com.kongye.docs.ui

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
import com.kongye.docs.data.Converter
import com.kongye.docs.data.DocContent
import com.kongye.docs.data.Item
import com.kongye.docs.data.Repository
import com.kongye.docs.data.plainText
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.CreateOption
import com.kongye.docs.ui.components.CreateSheet
import com.kongye.docs.ui.components.FolderPickerSheet
import com.kongye.docs.ui.components.InputDialog
import com.kongye.docs.ui.components.ItemSheetHeader
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.util.Share
import com.kongye.docs.util.formatTime
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
            SheetAction(KongyeIcons.Restore, "恢复") {
                Repository.restore(item.id)
                Toast.done("已恢复到「${locationOf(Repository.get(item.id) ?: item)}」")
            },
            SheetAction(KongyeIcons.Trash, "彻底删除", danger = true) { AppState.dialog = DialogRequest.Destroy(item) },
        ) else buildList {
            add(SheetAction(if (item.pinned) KongyeIcons.PinFilled else KongyeIcons.Pin, if (item.pinned) "取消置顶" else "置顶") {
                Repository.togglePin(item.id)
                Toast.done(if(item.pinned) "已取消置顶" else "已置顶")
            })
            add(SheetAction(if (item.starred) KongyeIcons.StarFilled else KongyeIcons.Star, if (item.starred) "取消收藏" else "收藏") {
                Repository.toggleStar(item.id)
                Toast.done(if (item.starred) "已取消收藏" else "收藏成功")
            })
            add(SheetAction(KongyeIcons.Edit, "重命名") { AppState.dialog = DialogRequest.Rename(item) })
            add(SheetAction(KongyeIcons.Move, "移动到") { AppState.dialog = DialogRequest.Move(listOf(item)) })
            if (item.isDoc) {
                add(SheetAction(KongyeIcons.Copy, "创建副本") {
                    Repository.duplicate(item.id)
                    Toast.done("创建副本成功")
                })
                add(SheetAction(KongyeIcons.Share, "分享") { AppState.shareFor = item })
            }
            // 删除前先确认（和多选删除共用一个确认框）
            add(SheetAction(KongyeIcons.Trash, "删除", danger = true) { AppState.dialog = DialogRequest.TrashMany(listOf(item)) })
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
                CreateOption("文档", KongyeIcons.Doc) { AppState.newDoc(parent) },
                CreateOption("文件夹", KongyeIcons.Folder) { AppState.dialog = DialogRequest.NewFolder(parent) },
                CreateOption("模板", KongyeIcons.Template) { AppState.push(Screen.Templates(parent)) },
                CreateOption("导入", KongyeIcons.Upload) { AppState.importInto = target },
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
            Toast.done("创建文件夹成功")
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
            Toast.done("移动成功")
        }
        is DialogRequest.TrashMany -> {
            val single = d.items.singleOrNull()
            ConfirmDialog(
                title = when {
                    single == null -> "删除 ${d.items.size} 项"
                    single.isFolder -> "删除文件夹"
                    else -> "删除文档"
                },
                message = when {
                    single == null -> "将移到回收站，彻底删除前可以恢复。"
                    single.isFolder -> "「${single.displayTitle}」和其中的文件将移到回收站，彻底删除前可以恢复。"
                    else -> "「${single.displayTitle}」将移到回收站，彻底删除前可以恢复。"
                },
                confirmText = "删除", danger = true, onDismiss = close,
            ) {
                close()
                d.items.forEach { Repository.trash(it.id) }
                AppState.endSelection()
                Toast.done(
                    when {
                        single == null -> "删除成功"
                        single.isFolder -> "删除文件夹成功"
                        else -> "删除文档成功"
                    }
                )
            }
        }
        is DialogRequest.Destroy -> ConfirmDialog(
            title = "彻底删除", message = "「${d.item.displayTitle}」将被永久删除，无法恢复。",
            confirmText = "删除", danger = true, onDismiss = close,
        ) {
            close()
            Repository.destroy(d.item.id)
            Toast.done("彻底删除成功")
        }
        DialogRequest.EmptyTrash -> ConfirmDialog(
            title = "清空回收站", message = "回收站中的所有文件将被永久删除，无法恢复。",
            confirmText = "清空", danger = true, onDismiss = close,
        ) {
            close()
            Repository.emptyTrash()
            Toast.done("清空回收站成功")
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
    if (created.size == 1) AppState.openDoc(created[0].id) else Toast.done("已导入 ${created.size} 篇文档")
}
