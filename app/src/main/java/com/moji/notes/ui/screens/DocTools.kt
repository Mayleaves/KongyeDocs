package com.moji.notes.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import com.moji.notes.data.DocContent
import com.moji.notes.data.DocVersion
import com.moji.notes.data.ExportKind
import com.moji.notes.ui.components.InputDialog
import com.moji.notes.ui.exportName
import com.moji.notes.data.Repository
import com.moji.notes.data.Settings
import com.moji.notes.data.Templates
import com.moji.notes.data.VersionKind
import com.moji.notes.render.DocRenderer
import com.moji.notes.ui.AppState
import com.moji.notes.ui.Screen
import com.moji.notes.ui.components.ConfirmDialog
import com.moji.notes.ui.components.EmptyState
import com.moji.notes.ui.components.HLine
import com.moji.notes.ui.components.IconBtn
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.MojiSwitch
import com.moji.notes.ui.components.NoticeDialog
import com.moji.notes.ui.components.Toast
import com.moji.notes.ui.components.TopBar
import com.moji.notes.ui.theme.Moji
import com.moji.notes.util.Share
import com.moji.notes.util.dateHeader
import com.moji.notes.util.safeFileName
import com.moji.notes.util.stampTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val pageBg get() = Moji.Card

/** 按手机屏幕宽度出图（像素宽与屏幕一致，预览时 1:1 清晰），版式见 DocRenderer */
private fun renderExport(context: Context, title: String, content: DocContent, footer: String): Bitmap =
    DocRenderer.renderImage(title, content, context.resources.displayMetrics.widthPixels, footer)

/** 长图切成多段显示，避免单张位图超过 GPU 纹理尺寸 */
internal fun slice(bmp: Bitmap, step: Int = 2048): List<ImageBitmap> =
    (0 until bmp.height step step).map { y -> Bitmap.createBitmap(bmp, 0, y, bmp.width, minOf(step, bmp.height - y)).asImageBitmap() }

@Composable
internal fun RenderedPages(tiles: List<ImageBitmap>?, modifier: Modifier = Modifier, below: (@Composable () -> Unit)? = null) {
    if (tiles == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Moji.Ink2, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("正在生成图片…", fontSize = 14.sp, color = Moji.Sub)
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            tiles.forEach { Image(it, null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), contentScale = ContentScale.FillWidth) }
            below?.invoke()
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
internal fun OutlineButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Moji.Line2, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, color = if (enabled) Moji.Ink else Moji.Sub) }
}

@Composable
internal fun DarkButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(4.dp)).background(if (enabled) (if (Moji.dark) Color(0xFF4A4D53) else Color(0xFF3F4246)) else Moji.Text4)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}

// ---------------- 图片预览（导出图片 / 分享为图片） ----------------

@Composable
fun ImagePreviewScreen(docId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = Repository.get(docId)?.title?.ifBlank { "无标题" } ?: "无标题"
    val footer by Repository.footer.collectAsState()
    var tiles by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var editFooter by remember { mutableStateOf(false) }
    // 落款改了就重新生成
    LaunchedEffect(docId, footer) {
        tiles = null
        val content = Repository.loadDoc(docId)
        val (f, t) = withContext(Dispatchers.Default) {
            val bmp = renderExport(context, title, content, footer)
            val out = Share.shareCacheFile(context, safeFileName(title) + ".png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out to slice(bmp)
        }
        file = f
        tiles = t
    }
    fun record(saved: Boolean) {
        file?.let { Repository.recordExport(ExportKind.IMAGE, exportName(title, ExportKind.IMAGE), saved, docId, source = it) }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val f = file
        if (uri != null && f != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Share.writeBytes(context, uri, f.readBytes()) }
            Toast.show(if (ok) "图片已保存" else "保存失败")
            if (ok) record(saved = true)
        }
    }
    Column(Modifier.fillMaxSize().background(pageBg)) {
        Row(
            Modifier.fillMaxWidth().background(Moji.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(MojiIcons.Back, { AppState.pop() }, tint = Moji.Ink2, description = "返回")
            Spacer(Modifier.weight(1f))
            OutlineButton("保存", file != null) {
                val f = file ?: return@OutlineButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Share.saveToGallery(context, f, exportName(title, ExportKind.IMAGE)) }
                    if (ok) {
                        Toast.show("已保存到相册「空叶文档」")
                        record(saved = true)
                    } else saveAs.launch(f.name)
                }
            }
            Spacer(Modifier.width(12.dp))
            DarkButton("分享", file != null) {
                file?.let {
                    Share.shareExisting(context, it, "image/png", "分享图片")
                    record(saved = false)
                }
            }
        }
        HLine(color = Moji.Divider)
        RenderedPages(tiles) {
            // 图片下方右侧：点“修改落款”自定义图片底部的文字
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "修改落款", Modifier.clip(RoundedCornerShape(4.dp)).clickable { editFooter = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 13.sp, color = Moji.Bookmark,
                )
            }
        }
    }
    if (editFooter) FooterDialog { editFooter = false }
}

/** 修改分享图片的落款；留空表示不显示 */
@Composable
fun FooterDialog(onDismiss: () -> Unit) {
    InputDialog(
        title = "图片落款", initial = Repository.footer.value, placeholder = "留空则不显示落款",
        confirmText = "保存", allowEmpty = true, onDismiss = onDismiss,
    ) { text ->
        onDismiss()
        Repository.setFooter(text)
        Toast.show(if (text.isBlank()) "已关闭图片落款" else "落款已更新")
    }
}

// ---------------- 查看历史 / 本地版本 ----------------

@Composable
fun VersionsScreen(docId: String, kind: VersionKind) {
    if (kind == VersionKind.LOCAL) LocalVersionsScreen(docId) else HistoryScreen(docId)
}

/**
 * 本地版本（石墨样式）：顶部说明和“了解详情”，“系统自动备份”开关（每 10 分钟存一个本地版本），
 * 下面是已保存版本列表（没有时显示“無”），可以清空；右上角“保存版本”立即存一个。
 */
@Composable
private fun LocalVersionsScreen(docId: String) {
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val list = remember(docId, refresh) { Repository.versions(docId).filter { it.kind == VersionKind.LOCAL } }
    val settings by Settings.state.collectAsState()
    val fmt = remember { java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.CHINA) }
    Column(Modifier.fillMaxSize().background(Moji.Band)) {
        Box(Modifier.fillMaxWidth().background(Moji.Surface).statusBarsPadding().height(56.dp)) {
            IconBtn(MojiIcons.Back, { AppState.pop() }, Modifier.align(Alignment.CenterStart).padding(start = 6.dp), tint = Moji.Ink2, description = "返回")
            Text("本地版本", Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Moji.Ink)
            Text(
                "保存版本",
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).clickable {
                    val item = Repository.get(docId) ?: return@clickable
                    val saved = Repository.addVersion(docId, VersionKind.LOCAL, item.title, Repository.loadDoc(docId), "手动保存")
                    Toast.show(if (saved) "已保存当前版本" else "当前内容已经保存过")
                    refresh++
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 16.sp, color = Moji.Link,
            )
        }
        HLine(color = Moji.Divider)
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 30.dp)) {
                    Text("你可以尝试在下方版本清单里找到尚未保存或遗漏的内容", fontSize = 14.sp, lineHeight = 21.sp, color = Moji.Text3)
                    Text(
                        "了解详情", Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { showHelp = true }
                            .padding(top = 2.dp, bottom = 2.dp),
                        fontSize = 14.sp, lineHeight = 21.sp, color = Moji.Link,
                    )
                }
                HLine(color = Moji.Line)
                Row(
                    Modifier.fillMaxWidth().background(Moji.Surface).height(60.dp).padding(start = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("系统自动备份", Modifier.weight(1f), fontSize = 16.sp, color = Moji.Ink)
                    MojiSwitch(settings.autoLocalVersion, { on -> Settings.update { it.copy(autoLocalVersion = on) } })
                }
                HLine(color = Moji.Line)
                Text(
                    "开关开启后将每10分钟对文档内容进行一次本地备份",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp), fontSize = 13.sp, color = Moji.Text3,
                )
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已保存版本", Modifier.weight(1f), fontSize = 16.sp, color = Moji.Ink)
                    // 没有版本时灰着、点不动；有版本时是红色
                    Text(
                        "清空版本",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = list.isNotEmpty()) { confirmClear = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 16.sp, color = if (list.isEmpty()) Moji.Sub else Moji.Danger,
                    )
                }
                HLine(color = Moji.Line)
            }
            if (list.isEmpty()) item(key = "empty") { EmptyState("目前暂无本地版本文件", top = 96.dp) }
            items(list, key = { it.id }) { v ->
                Column(Modifier.fillMaxWidth().background(Moji.Surface).clickable { AppState.push(Screen.VersionPreview(docId, v.id)) }) {
                    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt.format(java.util.Date(v.time))} 版本", Modifier.weight(1f), fontSize = 16.sp, color = Moji.Ink)
                        Icon(MojiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Moji.Ink2)
                    }
                    if (v.id != list.last().id) HLine(Modifier.padding(start = 20.dp), color = Moji.Divider) else HLine(color = Moji.Line)
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空版本", message = "将删除这篇文档的全部本地版本，无法恢复。", confirmText = "清空", danger = true,
            onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            Repository.clearVersions(docId, VersionKind.LOCAL)
            Toast.show("已清空本地版本")
            refresh++
        }
    }
    if (showHelp) {
        NoticeDialog(
            "本地版本",
            "本地版本只保存在这台手机里，不会上传到任何地方。\n\n" +
                "开启“系统自动备份”后，编辑文档时每 10 分钟会自动存一个版本；也可以随时点右上角“保存版本”手动存一个。\n\n" +
                "点开某个版本可以预览，并把文档恢复成那时的样子。每篇文档最多保留最近 30 个本地版本。",
        ) { showHelp = false }
    }
}

@Composable
private fun HistoryScreen(docId: String) {
    val kind = VersionKind.HISTORY
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(docId, kind, refresh) { Repository.versions(docId).filter { it.kind == kind } }
    val history = kind == VersionKind.HISTORY
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        TopBar(title = if (history) "查看历史" else "本地版本", onBack = { AppState.pop() }, divider = true) {
            if (!history) {
                Text(
                    "保存当前版本",
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        val item = Repository.get(docId) ?: return@clickable
                        val saved = Repository.addVersion(docId, VersionKind.LOCAL, item.title, Repository.loadDoc(docId), "手动保存")
                        Toast.show(if (saved) "已保存当前版本" else "当前内容已经保存过")
                        refresh++
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 15.sp, color = Moji.Ink2,
                )
            }
        }
        Text(
            if (history) "编辑文档时会自动记录历史版本，最多保留 50 个。点开可以预览和恢复。"
            else "这里是手动保存的版本，以及恢复历史版本、全部替换之前自动留下的备份，最多保留 30 个。",
            Modifier.fillMaxWidth().background(Moji.Band).padding(horizontal = 20.dp, vertical = 10.dp),
            fontSize = 12.sp, color = Moji.Sub, lineHeight = 18.sp,
        )
        if (list.isEmpty()) {
            EmptyState(if (history) "还没有历史版本" else "还没有本地版本", if (history) "编辑并离开文档后会自动记录" else "点击右上角「保存当前版本」")
        } else {
            val groups = list.groupBy { dateHeader(it.time) }
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (date, versions) ->
                    item(key = "h-$date") {
                        Text(date, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp), fontSize = 15.sp, color = Moji.Ink2)
                    }
                    items(versions, key = { it.id }) { v ->
                        VersionRow(v, latest = v.id == list.first().id) { AppState.push(Screen.VersionPreview(docId, v.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(v: DocVersion, latest: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (v.kind == VersionKind.HISTORY) MojiIcons.HistoryFilled else MojiIcons.SaveFilled, null, Modifier.size(22.dp), tint = Moji.Icon2)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stampTime(v.time), fontSize = 16.sp, color = Moji.Ink)
                if (latest) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "最新", Modifier.clip(RoundedCornerShape(3.dp)).background(Moji.Bg2).padding(horizontal = 6.dp, vertical = 1.dp),
                        fontSize = 11.sp, color = Moji.Ink2,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            val note = if (v.note.isNotEmpty()) " · ${v.note}" else ""
            Text("${v.title.ifBlank { "无标题" }} · ${v.words} 字$note", fontSize = 13.sp, color = Moji.Sub, maxLines = 1)
        }
        Icon(MojiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Moji.Text4)
    }
}

@Composable
fun VersionPreviewScreen(docId: String, versionId: String) {
    val version = remember(docId, versionId) { Repository.versions(docId).firstOrNull { it.id == versionId } }
    if (version == null) {
        LaunchedEffect(Unit) { AppState.pop() }
        return
    }
    var tiles by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(versionId) {
        tiles = withContext(Dispatchers.Default) { slice(renderExport(context, version.title, version.content, "")) }
    }
    Column(Modifier.fillMaxSize().background(pageBg)) {
        Row(
            Modifier.fillMaxWidth().background(Moji.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(MojiIcons.Back, { AppState.pop() }, tint = Moji.Ink2, description = "返回")
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stampTime(version.time), fontSize = 16.sp, color = Moji.Ink)
                Text("${version.words} 字", fontSize = 12.sp, color = Moji.Sub)
            }
            DarkButton("恢复此版本", true) { confirm = true }
        }
        HLine(color = Moji.Divider)
        RenderedPages(tiles)
    }
    if (confirm) {
        ConfirmDialog(
            title = "恢复此版本",
            message = "当前内容会先自动保存到「本地版本」，再替换成这个版本。",
            confirmText = "恢复",
            onDismiss = { confirm = false },
        ) {
            confirm = false
            Repository.restoreVersion(docId, version)
            Toast.show("已恢复到 ${stampTime(version.time)} 的版本")
            AppState.pop()
            AppState.pop()
        }
    }
}

// ---------------- 模板预览 ----------------

/** 只是查看模板内容，不会创建文档；点“使用模板”才新建，之后从编辑器返回直接回到文件 */
@Composable
fun TemplatePreviewScreen(templateId: String, userTemplate: Boolean, parentId: String?) {
    val mine by Repository.userTemplates.collectAsState()
    val builtIn = remember(templateId) { if (userTemplate) null else Templates.byId(templateId) }
    val own = mine.firstOrNull { it.id == templateId }
    val name = if (userTemplate) own?.name else builtIn?.name
    if (name == null) {
        LaunchedEffect(Unit) { AppState.pop() }
        return
    }
    val title = if (userTemplate) own?.title.orEmpty() else builtIn?.title.orEmpty()
    val context = LocalContext.current
    var tiles by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    LaunchedEffect(templateId) {
        tiles = withContext(Dispatchers.Default) {
            val content = if (userTemplate) Repository.templatePreview(templateId) else builtIn!!.content()
            slice(renderExport(context, title.ifBlank { "无标题" }, content, ""))
        }
    }
    Column(Modifier.fillMaxSize().background(Moji.Surface)) {
        Row(
            Modifier.fillMaxWidth().background(Moji.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(MojiIcons.Back, { AppState.pop() }, tint = Moji.Ink2, description = "返回")
            Text(
                name, Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Moji.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            DarkButton("使用模板", true) {
                val content = if (userTemplate) Repository.templateContent(templateId) else builtIn!!.content()
                val item = Repository.createDoc(parentId, title, content)
                AppState.openFromTemplate(item.id, parentId)
            }
        }
        HLine(color = Moji.Divider)
        RenderedPages(tiles)
    }
}
