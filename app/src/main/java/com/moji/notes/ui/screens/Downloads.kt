package com.moji.notes.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.data.ExportKind
import com.moji.notes.data.ExportRecord
import com.moji.notes.data.Repository
import com.moji.notes.ui.AppState
import com.moji.notes.ui.Screen
import com.moji.notes.ui.components.ActionSheet
import com.moji.notes.ui.components.ConfirmDialog
import com.moji.notes.ui.components.EmptyState
import com.moji.notes.ui.components.FileIcon
import com.moji.notes.ui.components.HLine
import com.moji.notes.ui.components.IconBtn
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.SheetAction
import com.moji.notes.ui.components.Toast
import com.moji.notes.ui.components.TopBar
import com.moji.notes.ui.theme.Moji
import com.moji.notes.util.Share
import com.moji.notes.util.stampTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val checkBlue = Color(0xFF8FAEE0)

/** 再次分享一条记录：先复制成原来的文件名再分享，对方看到的就是“无标题_10_25_135129.png”这样的名字 */
private fun reshare(context: android.content.Context, r: ExportRecord) {
    val file = Repository.exportFile(r)
    if (!file.exists()) {
        Toast.show("文件已不存在")
        return
    }
    when (r.kind) {
        ExportKind.IMAGE -> {
            val copy = Share.shareCacheFile(context, r.fileName)
            file.copyTo(copy, overwrite = true)
            Share.shareExisting(context, copy, "image/png", "分享图片")
        }
        ExportKind.TEXT -> Share.text(context, r.fileName.substringBeforeLast('.'), file.readText())
        ExportKind.MARKDOWN -> Share.file(context, r.fileName, "text/markdown", file.readText())
    }
}

// ---------------- 下载记录列表 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val list by Repository.exports.collectAsState()
    var menuFor by remember { mutableStateOf<ExportRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        TopBar(title = "下载记录", onBack = { AppState.pop() }, divider = true) {
            if (list.isNotEmpty()) {
                Text(
                    "清空", Modifier.clip(RoundedCornerShape(6.dp)).clickable { confirmClear = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 15.sp, color = Moji.Ink2,
                )
            }
        }
        if (list.isEmpty()) {
            EmptyState("还没有下载记录", "分享或保存的图片、文字、Markdown 会显示在这里")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().height(74.dp)
                            .combinedClickable(onClick = { AppState.push(Screen.ExportView(r.id)) }, onLongClick = { menuFor = r })
                            .padding(start = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindIcon(r.kind)
                        Spacer(Modifier.width(16.dp))
                        Row(
                            Modifier.weight(1f).fillMaxSize().drawBehind {
                                drawLine(Moji.Divider, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.fileName, fontSize = 16.sp, color = Moji.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    (if (r.saved) "已保存到相册" else "分享成功") + " · " + stampTime(r.time),
                                    fontSize = 13.sp, color = Moji.Sub, maxLines = 1,
                                )
                            }
                            Icon(MojiIcons.CheckCircle, null, Modifier.padding(horizontal = 18.dp).size(26.dp), tint = checkBlue)
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { r ->
        ActionSheet(
            onDismiss = { menuFor = null },
            actions = listOf(
                SheetAction(MojiIcons.Share, "再次分享") { reshare(context, r) },
                SheetAction(MojiIcons.Trash, "删除记录", danger = true) {
                    Repository.deleteExport(r.id)
                    Toast.show("已删除")
                },
            ),
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空下载记录", message = "所有记录和保存的副本都会删除；已经分享出去或保存到相册的文件不受影响。",
            confirmText = "清空", danger = true, onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            Repository.clearExports()
            Toast.show("下载记录已清空")
        }
    }
}

/** 记录类型图标：图片是浅蓝色的风景图，文字是纸张，Markdown 是纸张上写着 MD */
@Composable
private fun KindIcon(kind: ExportKind) {
    when (kind) {
        ExportKind.TEXT -> FileIcon(false, 38.dp)
        ExportKind.MARKDOWN -> Box(contentAlignment = Alignment.Center) {
            FileIcon(false, 38.dp)
            Text("MD", Modifier.padding(top = 8.dp), fontSize = 9.sp, color = Moji.Ink2)
        }
        ExportKind.IMAGE -> Canvas(Modifier.size(38.dp)) {
            val u = size.width / 32f
            drawRoundRect(Color(0xFFE6EEFA), Offset(3f * u, 7f * u), Size(26f * u, 19f * u), CornerRadius(1.5f * u))
            drawRoundRect(Color(0xFFC6D6F0), Offset(3f * u, 7f * u), Size(26f * u, 19f * u), CornerRadius(1.5f * u), style = Stroke(0.8f * u))
            val hills = Path().apply {
                moveTo(5f * u, 24f * u); lineTo(12f * u, 16f * u); lineTo(17f * u, 21f * u)
                lineTo(21f * u, 17.5f * u); lineTo(27f * u, 24f * u); close()
            }
            drawPath(hills, Color(0xFFA7BFE6))
            drawCircle(Color(0xFFA7BFE6), 1.9f * u, Offset(22f * u, 12.5f * u))
        }
    }
}

// ---------------- 单条记录查看 ----------------

@Composable
fun ExportViewScreen(id: String) {
    val context = LocalContext.current
    val list by Repository.exports.collectAsState()
    val r = list.firstOrNull { it.id == id }
    if (r == null) {
        LaunchedEffect(Unit) { AppState.pop() }
        return
    }
    val file = Repository.exportFile(r)
    val tiles by produceState<List<ImageBitmap>?>(null, id) {
        value = if (r.kind == ExportKind.IMAGE) withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.let { slice(it) } ?: emptyList()
        } else emptyList()
    }
    val text by produceState("", id) {
        value = if (r.kind != ExportKind.IMAGE) withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") } else ""
    }
    Column(Modifier.fillMaxSize().background(Moji.Card)) {
        Row(
            Modifier.fillMaxWidth().background(Moji.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(MojiIcons.Back, { AppState.pop() }, tint = Moji.Ink2, description = "返回")
            Text(
                r.fileName, Modifier.weight(1f).padding(start = 2.dp, end = 10.dp),
                fontSize = 15.sp, color = Moji.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (r.kind == ExportKind.IMAGE) {
                OutlineButton("保存", file.exists()) {
                    val ok = Share.saveToGallery(context, file, r.fileName)
                    Toast.show(if (ok) "已保存到相册「空叶文档」" else "保存失败")
                }
            } else {
                OutlineButton("复制", text.isNotEmpty()) {
                    Share.copy(context, r.fileName, text)
                    Toast.show("已复制到剪贴板")
                }
            }
            Spacer(Modifier.width(12.dp))
            DarkButton("分享", file.exists()) { reshare(context, r) }
        }
        HLine(color = Moji.Divider)
        if (r.kind == ExportKind.IMAGE) {
            RenderedPages(tiles)
        } else {
            Column(Modifier.fillMaxSize().background(Moji.Surface).verticalScroll(rememberScrollState()).padding(20.dp)) {
                SelectionContainer {
                    Text(text, fontSize = 15.sp, lineHeight = 24.sp, color = Moji.Text)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
