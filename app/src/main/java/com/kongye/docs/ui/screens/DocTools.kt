package com.kongye.docs.ui.screens

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
import com.kongye.docs.data.DocContent
import com.kongye.docs.data.DocVersion
import com.kongye.docs.data.ExportKind
import com.kongye.docs.ui.components.InputDialog
import com.kongye.docs.ui.exportName
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.data.Templates
import com.kongye.docs.data.VersionKind
import com.kongye.docs.render.DocRenderer
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.EmptyState
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.KongyeSwitch
import com.kongye.docs.ui.components.NoticeDialog
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.Share
import com.kongye.docs.util.dateHeader
import com.kongye.docs.util.safeFileName
import com.kongye.docs.util.stampTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val pageBg get() = Kongye.Card

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
                CircularProgressIndicator(Modifier.size(28.dp), color = Kongye.Ink2, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("正在生成图片…", fontSize = 14.sp, color = Kongye.Sub)
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
        Modifier.height(34.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Kongye.Line2, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, color = if (enabled) Kongye.Ink else Kongye.Sub) }
}

@Composable
internal fun DarkButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(4.dp)).background(if (enabled) (if (Kongye.dark) Color(0xFF4A4D53) else Color(0xFF3F4246)) else Kongye.Text4)
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
            if (ok) Toast.done("图片已保存") else Toast.show("保存失败")
            if (ok) record(saved = true)
        }
    }
    Column(Modifier.fillMaxSize().background(pageBg)) {
        Row(
            Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(KongyeIcons.Back, { AppState.pop() }, tint = Kongye.Ink2, description = "返回")
            Spacer(Modifier.weight(1f))
            OutlineButton("保存", file != null) {
                val f = file ?: return@OutlineButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Share.saveToGallery(context, f, exportName(title, ExportKind.IMAGE)) }
                    if (ok) {
                        Toast.done("已保存到相册「空叶文档」")
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
        HLine(color = Kongye.Divider)
        RenderedPages(tiles) {
            // 图片下方右侧：点“修改落款”自定义图片底部的文字
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "修改落款", Modifier.clip(RoundedCornerShape(4.dp)).clickable { editFooter = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 13.sp, color = Kongye.Bookmark,
                )
            }
        }
    }
    if (editFooter) FooterDialog { editFooter = false }
}

/** 修改分享图片的落款；留空表示不显示 */
@Composable
fun FooterDialog(onDismiss: () -> Unit) {
    val footer by Repository.footer.collectAsState()
    InputDialog(
        title = "图片落款", initial = footer, placeholder = "留空则不显示落款",
        confirmText = "保存", allowEmpty = true, onDismiss = onDismiss,
    ) { text ->
        onDismiss()
        Repository.setFooter(text)
        Toast.done(if (text.isBlank()) "已关闭图片落款" else "落款已更新")
    }
}

// ---------------- 本地版本 ----------------

@Composable
fun VersionsScreen(docId: String) { LocalVersionsScreen(docId) }

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
    Column(Modifier.fillMaxSize().background(Kongye.Band)) {
        Box(Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding().height(56.dp)) {
            IconBtn(KongyeIcons.Back, { AppState.pop() }, Modifier.align(Alignment.CenterStart).padding(start = 6.dp), tint = Kongye.Ink2, description = "返回")
            Text("本地版本", Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
            Text(
                "保存版本",
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).clickable {
                    val item = Repository.get(docId) ?: return@clickable
                    val saved = Repository.addVersion(docId, VersionKind.LOCAL, item.title, Repository.loadDoc(docId), "手动保存")
                    if (saved) Toast.done("已保存当前版本") else Toast.show("当前内容已经保存过")
                    refresh++
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 16.sp, color = Kongye.Link,
            )
        }
        HLine(color = Kongye.Divider)
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 30.dp)) {
                    Text("你可以尝试在下方版本清单里找到尚未保存或遗漏的内容", fontSize = 14.sp, lineHeight = 21.sp, color = Kongye.Text3)
                    Text(
                        "了解详情", Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { showHelp = true }
                            .padding(top = 2.dp, bottom = 2.dp),
                        fontSize = 14.sp, lineHeight = 21.sp, color = Kongye.Link,
                    )
                }
                HLine(color = Kongye.Line)
                Row(
                    Modifier.fillMaxWidth().background(Kongye.Surface).height(60.dp).padding(start = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("系统自动备份", Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
                    KongyeSwitch(settings.autoLocalVersion, { on -> Settings.update { it.copy(autoLocalVersion = on) } })
                }
                HLine(color = Kongye.Line)
                Text(
                    "开关开启后将每10分钟对文档内容进行一次本地备份",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp), fontSize = 13.sp, color = Kongye.Text3,
                )
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已保存版本", Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
                    // 没有版本时灰着、点不动；有版本时是红色
                    Text(
                        "清空版本",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = list.isNotEmpty()) { confirmClear = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 16.sp, color = if (list.isEmpty()) Kongye.Sub else Kongye.Danger,
                    )
                }
                HLine(color = Kongye.Line)
            }
            if (list.isEmpty()) item(key = "empty") { EmptyState("目前暂无本地版本文件", top = 96.dp) }
            items(list, key = { it.id }) { v ->
                Column(Modifier.fillMaxWidth().background(Kongye.Surface).clickable { AppState.push(Screen.VersionPreview(docId, v.id)) }) {
                    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt.format(java.util.Date(v.time))} 版本", Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
                        Icon(KongyeIcons.ChevronRight, null, Modifier.size(18.dp), tint = Kongye.Ink2)
                    }
                    if (v.id != list.last().id) HLine(Modifier.padding(start = 20.dp), color = Kongye.Divider) else HLine(color = Kongye.Line)
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
            Toast.done("已清空本地版本")
            refresh++
        }
    }
    if (showHelp) {
        NoticeDialog(
            "本地版本",
            "本地版本只保存在这台手机里，不会上传到任何地方。\n\n" +
                "开启“系统自动备份”后，编辑文档时每 10 分钟会自动存一个版本；也可以随时点右上角“保存版本”手动存一个。\n\n" +
                "点开某个版本可以对比和预览，也可以恢复此版本、生成副本文档或删除这个版本。每篇文档最多保留最近 30 个本地版本。",
        ) { showHelp = false }
    }
}

@Composable
fun VersionPreviewScreen(docId: String, versionId: String) {
    val version = remember(docId, versionId) { Repository.versions(docId).firstOrNull { it.id == versionId } }
    if (version == null) {
        LaunchedEffect(Unit) { AppState.pop() }
        return
    }
    val current=remember(docId,versionId) {
        com.kongye.docs.ui.editor.EditorSession.snapshot(docId)
            ?: (Repository.get(docId)?.title.orEmpty() to Repository.loadDoc(docId))
    }
    var showChanges by remember(versionId) { mutableStateOf(true) }
    var tiles by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var actionsOpen by remember(versionId) { mutableStateOf(false) }
    var deleting by remember(versionId) { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(versionId,showChanges) {
        if(!showChanges && tiles==null) tiles=withContext(Dispatchers.Default) { slice(renderExport(context,version.title,version.content,"")) }
    }
    Column(Modifier.fillMaxSize().background(pageBg)) {
        Row(
            Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(KongyeIcons.Back, { AppState.pop() }, tint = Kongye.Ink2, description = "返回")
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stampTime(version.time), fontSize = 16.sp, color = Kongye.Ink)
                Text("${version.words} 字", fontSize = 12.sp, color = Kongye.Sub)
            }
            IconBtn(KongyeIcons.More, { actionsOpen = true }, tint = Kongye.Ink2, description = "版本操作")
        }
        HLine(color = Kongye.Divider)
        Row(Modifier.fillMaxWidth().background(Kongye.Surface).padding(horizontal=18.dp,vertical=10.dp)) {
            Text("修改对比",Modifier.clickable { showChanges=true }.padding(end=24.dp),color=if(showChanges) Kongye.Primary else Kongye.Text2,fontSize=14.sp)
            Text("文档预览",Modifier.clickable { showChanges=false },color=if(!showChanges) Kongye.Primary else Kongye.Text2,fontSize=14.sp)
        }
        if(showChanges) VersionChanges(version,current.first,current.second) else RenderedPages(tiles)
    }
    if (actionsOpen) {
        ActionSheet(
            onDismiss = { actionsOpen = false },
            actions = listOf(
                SheetAction(KongyeIcons.Restore, "恢复此版本") { confirm = true },
                SheetAction(KongyeIcons.Copy, "生成副本文档") {
                    runCatching { Repository.duplicateVersion(docId, versionId) }
                        .onSuccess { item -> Toast.done("已生成副本文档"); AppState.openFromTemplate(item.id, item.parentId) }
                        .onFailure { Toast.show(it.message ?: "生成副本失败") }
                },
                SheetAction(KongyeIcons.Trash, "删除版本", danger = true) { deleting = true },
            ),
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "删除版本",
            message = "删除这个本地版本？当前文档正文和其他版本会保留。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deleting = false },
        ) {
            deleting = false
            runCatching { Repository.deleteVersion(docId, versionId) }
                .onSuccess { Toast.done("版本已删除"); AppState.pop() }
                .onFailure { Toast.show(it.message ?: "删除版本失败") }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "恢复此版本",
            message = "当前内容会先自动保存到「本地版本」，再替换成这个版本。",
            confirmText = "恢复",
            onDismiss = { confirm = false },
        ) {
            confirm = false
            runCatching {
                com.kongye.docs.ui.editor.EditorSession.snapshot(docId)?.let { (title, content) ->
                    Repository.saveDoc(docId, title, content)
                }
                Repository.restoreVersion(docId, version)
                com.kongye.docs.ui.editor.EditorSession.reload(docId)
            }.onSuccess {
                Toast.done("已恢复到 ${stampTime(version.time)} 的版本")
                AppState.pop()
                AppState.pop()
            }.onFailure { Toast.show(it.message ?: "恢复失败，原内容已保留") }
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
    Column(Modifier.fillMaxSize().background(Kongye.Surface)) {
        Row(
            Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding().height(56.dp).padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(KongyeIcons.Back, { AppState.pop() }, tint = Kongye.Ink2, description = "返回")
            Text(
                name, Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            DarkButton("使用模板", true) {
                val content = if (userTemplate) Repository.templateContent(templateId) else builtIn!!.content()
                val item = Repository.createDoc(parentId, title, content)
                AppState.openFromTemplate(item.id, parentId)
            }
        }
        HLine(color = Kongye.Divider)
        RenderedPages(tiles)
    }
}
