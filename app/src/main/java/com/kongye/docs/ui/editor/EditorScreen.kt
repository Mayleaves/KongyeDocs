package com.kongye.docs.ui.editor

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import android.content.Intent
import com.kongye.docs.render.DocRenderer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kongye.docs.data.Block
import com.kongye.docs.data.BlockType
import com.kongye.docs.data.Repository
import com.kongye.docs.data.VersionKind
import com.kongye.docs.data.bodyText
import com.kongye.docs.data.countWords
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.DocShareSheet
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.shareDoc
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.components.InfoDialog
import com.kongye.docs.ui.components.InputDialog
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.locationOf
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.Share
import com.kongye.docs.util.formatFullTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.draw.drawBehind

/** 当前打开的编辑器，供 Activity 进入后台时立即保存 */
object EditorSession {
    var current: EditorState? = null
    var keyboardHeight by mutableStateOf(0.dp)
    private val documents=mutableMapOf<String,EditorState>()
    fun obtain(id: String, isNew: Boolean)=documents.getOrPut(id) { EditorState(id,isNew) }
    fun closeDocument(id: String): Boolean {
        val editor=documents[id] ?: return true
        if(editor.isNew && editor.isEmptyDoc()) Repository.destroy(id)
        else if(!editor.closeDocument()) return false
        documents.remove(id)
        return true
    }
    fun reload(id: String) { documents[id]?.reloadSavedContent() }
    fun snapshot(id: String) = documents[id]?.let { it.title.text to it.content() }
}

private sealed interface EdSheet {
    data object Share : EdSheet
    data object More : EdSheet
    data object Table : EdSheet
    data class Image(val b: BlockState) : EdSheet
    data class Divider(val b: BlockState) : EdSheet
    data object Toolbar : EdSheet
}

private enum class EdDialog { LINK, DELETE, TEMPLATE }

private val doneBlue = Color(0xFF4A90E2)

/** 文档标题字号（导出图片时 DocRenderer 用同样的数值） */
val TITLE_SIZE = 24.sp
val TITLE_LINE = 34.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(docId: String, isNew: Boolean) {
    val editor=remember(docId) { EditorSession.obtain(docId,isNew) }
    DocumentInput(editor) { EditorScreenContent(docId,isNew) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorScreenContent(docId: String, isNew: Boolean) {
    val selectionBackground=androidx.compose.foundation.text.selection.LocalTextSelectionColors.current.backgroundColor
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val editor = remember(docId) { EditorSession.obtain(docId, isNew) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scroll = rememberScrollState()
    val settings by com.kongye.docs.data.Settings.state.collectAsState()
    val items by Repository.items.collectAsState()
    val item = items.firstOrNull { it.id == docId }
    var panel by remember { mutableStateOf<EdPanel?>(null) }
    var tocOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var tocActive by remember { mutableStateOf<String?>(null) }
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var titleFocused by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<EdSheet?>(null) }
    var dialog by remember { mutableStateOf<EdDialog?>(null) }
    val blockTops = remember { mutableStateMapOf<String, Int>() }
    val imeVisible = WindowInsets.isImeVisible
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val toolbarVisible = !editor.findMode && (editor.selection.active || editor.focusedId != null || editor.focusedCell != null)
    val editing = toolbarVisible || titleFocused
    val panelHeight = EditorSession.keyboardHeight.takeIf { it > 160.dp } ?: 300.dp
    val docTitle = editor.title.text.ifBlank { "无标题" }

    // 记录键盘完全展开时的高度，样式面板用同样的高度替代键盘
    LaunchedEffect(imeBottom) {
        val h = imeBottom - navBottom
        if (h > EditorSession.keyboardHeight) EditorSession.keyboardHeight = h
    }
    LaunchedEffect(imeVisible) { if (imeVisible) panel = null }

    // 自动保存
    LaunchedEffect(editor.version) {
        if (!editor.isSaved) {
            delay(800)
            editor.save()
        }
    }
    // Continuous typing must not postpone persistence indefinitely.
    LaunchedEffect(editor) {
        while (true) { delay(5_000); editor.save() }
    }
    DisposableEffect(editor) {
        EditorSession.current = editor
        onDispose {
            if(EditorSession.current === editor) EditorSession.current = null
            // 只有真正关闭文档（而不是跳到预览、历史等页面）时，才删除没写任何内容的新文档
            val stillOpen = AppState.stack.any { it is Screen.Editor && it.docId == docId }
            if(!stillOpen) EditorSession.closeDocument(docId)
            else editor.save()
        }
    }

    // 处理“把光标移到某块某位置”的请求
    LaunchedEffect(editor.pendingFocus) {
        val pf = editor.pendingFocus ?: return@LaunchedEffect
        val b = editor.blocks.firstOrNull { it.id == pf.blockId }
        if (b != null) {
            b.value = b.value.copy(selection = TextRange(pf.pos.coerceIn(1, b.value.text.length)))
            withFrameNanos { }
            runCatching { b.focus.requestFocus() }
            editor.focusedId = b.id
        }
        if (pf.blockId == TITLE_KEY) {
            editor.title=editor.title.copy(selection=TextRange(pf.pos.coerceIn(0,editor.title.text.length)))
            withFrameNanos { }
            runCatching { titleFocus.requestFocus() }
        }
        if(pf.showKeyboard) keyboard?.show()
        editor.pendingFocus = null
    }

    LaunchedEffect(editor.selection.active,editor.selection.fromKeyboard) {
        if (editor.selection.active && !editor.selection.fromKeyboard) { focusManager.clearFocus(); keyboard?.hide(); titleFocused=false; panel=null }
    }

    // 新建的空白文档直接聚焦正文，方便立即输入
    LaunchedEffect(Unit) {
        if (isNew && editor.isEmptyDoc()) {
            delay(250)
            editor.requestFocus(editor.blocks.first(), 1)
        }
    }

    // 从搜索结果的“评论”点进来：等页面排好版后直接打开那条评论
    LaunchedEffect(Unit) {
        val id = AppState.pendingComment ?: return@LaunchedEffect
        AppState.pendingComment = null
        delay(320)
        if (editor.thread(id) != null) editor.commentView = EditorState.CommentView(id)
    }

    // 查找时把当前匹配滚动到屏幕上方三分之一处
    LaunchedEffect(editor.findMode, editor.matchIndex, editor.matches) {
        if (!editor.findMode) return@LaunchedEffect
        val m = editor.matches.getOrNull(editor.matchIndex) ?: return@LaunchedEffect
        val top = blockTops[m.blockId] ?: return@LaunchedEffect
        val lineTop = if (m.row < 0) {
            editor.layouts[m.blockId]?.let { runCatching { it.getBoundingBox(m.start).top }.getOrNull() } ?: 0f
        } else 0f
        scroll.animateScrollTo((top + lineTop - scroll.viewportSize * 0.3f).toInt().coerceAtLeast(0))
    }

    BackHandler(enabled = infoOpen || tocOpen) { infoOpen = false; tocOpen = false }
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = editor.findMode) { editor.closeFind() }
    BackHandler(enabled = editor.commentView != null) {
        // 从全部评论点进某一条时，返回键先回到全部评论
        editor.commentView = if (editor.commentView?.fromAll == true) EditorState.CommentView(null) else null
    }
    BackHandler(enabled = editor.commentDraft != null) {
        // 放弃这条评论，回到原来的段落继续编辑
        val d = editor.commentDraft
        editor.commentDraft = null
        editor.blocks.firstOrNull { it.id == d?.blockId }?.let { editor.requestFocus(it, d!!.end) }
    }

    fun finishEditing() {
        panel = null
        // 结束编辑时，还没发出去的评论一并放弃
        editor.commentDraft = null
        editor.clearArticleSelection()
        focusManager.clearFocus()
        editor.focusedId = null
        editor.focusedCell = null
        titleFocused = false
    }

    /** 离开编辑器去别的页面前先保存，让新页面读到最新内容 */
    fun leaveTo(screen: Screen) {
        if (!editor.save()) return
        AppState.push(screen)
    }

    // 插入图片
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val results = withContext(Dispatchers.IO) { uris.mapNotNull { Repository.importImage(it) } }
            results.forEach { (name, ratio) -> editor.insertBlock(Block(type = BlockType.IMAGE, image = name, imageRatio = ratio)) }
            if (results.size < uris.size) Toast.show("有 ${uris.size - results.size} 张图片无法读取")
        }
    }

    // 拍照插入：系统相机把照片写进缓存里的临时文件，导入后删除
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let(::File)
        cameraPath = null
        if (ok && f != null && f.exists()) scope.launch {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
            val result = withContext(Dispatchers.IO) { Repository.importImage(uri) }
            if (result != null) editor.insertBlock(Block(type = BlockType.IMAGE, image = result.first, imageRatio = result.second))
            else Toast.show("照片无法读取")
            f.delete()
        } else f?.delete()
    }

    fun openCamera() {
        val f = Share.shareCacheFile(context, "camera_${System.currentTimeMillis()}.jpg")
        cameraPath = f.absolutePath
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        runCatching { takePicture.launch(uri) }.onFailure {
            cameraPath = null
            Toast.show("没有可用的相机")
        }
    }

    // 评论面板：打开时收起键盘，并把这条评论所在的段落滚到上方；开始编辑正文时自动关闭
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val commentPanelLarge = screenHeight * 0.45f
    val commentPanelSmall = screenHeight * 0.3f
    LaunchedEffect(editor.commentView) {
        val v = editor.commentView ?: return@LaunchedEffect
        finishEditing()
        infoOpen = false; tocOpen = false
        val blockId = v.threadId?.let { editor.firstAnchor[it] } ?: return@LaunchedEffect
        val top = blockTops[blockId] ?: return@LaunchedEffect
        delay(120)
        val target = (top - 80.dp.value * density.density).toInt().coerceAtLeast(0)
        if (target < scroll.value || target > scroll.value + scroll.viewportSize / 2) scroll.animateScrollTo(target)
    }
    LaunchedEffect(editor.focusedId, editor.focusedCell) {
        if (editor.focusedId != null || editor.focusedCell != null) {
            editor.commentView = null
            tocOpen = false
            // 去编辑别的段落了：放弃还没发出去的评论
            if (editor.commentDraft?.blockId != editor.focusedId) editor.commentDraft = null
        }
    }
    LaunchedEffect(editor.commentView, editor.findMode) { if (editor.commentView != null || editor.findMode) tocOpen = false }
    BackHandler(enabled = tocOpen) { tocOpen = false }

    Column(Modifier.fillMaxSize().background(Kongye.Surface)) {
        // ---------- 顶栏 ----------
        if (editor.findMode) {
            FindTopBar(editor) { editor.closeFind() }
        } else {
            Column(Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp)) {
                    if (editing) IconBtn(KongyeIcons.Check, ::finishEditing, Modifier.align(Alignment.CenterStart), tint = doneBlue, iconSize = 26.dp, description = "完成")
                    else IconBtn(KongyeIcons.Back, { AppState.pop() }, Modifier.align(Alignment.CenterStart), tint = Kongye.Ink2, description = "返回")
                    if(settings.autoLocalVersion) Text(
                        if (editor.isSaved) "文档将自动保存" else "正在保存…", Modifier.align(Alignment.Center),
                        fontSize = 12.sp, color = Kongye.Sub,
                    )
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        // 已置顶的文档在顶栏显示图钉，点一下取消置顶
                        if (item?.pinned == true) {
                            IconBtn(KongyeIcons.PinFilled, {
                                Repository.togglePin(docId)
                                Toast.done("已取消置顶")
                            }, tint = Kongye.Ink2, size = 34.dp, iconSize = 20.dp, description = "已置顶")
                        }
                        // 已收藏的文档在顶栏显示蓝色书签，点一下取消收藏
                        if (item?.starred == true) {
                            IconBtn(KongyeIcons.Bookmark, {
                                Repository.toggleStar(docId)
                                Toast.done("已取消收藏")
                            }, tint = Kongye.Bookmark, size = 34.dp, iconSize = 20.dp, description = "已收藏")
                        }
                        IconBtn(KongyeIcons.Share, { sheet = EdSheet.Share }, tint = Kongye.Ink2, description = "分享")
                        IconBtn(KongyeIcons.More, { finishEditing(); sheet = EdSheet.More }, tint = Kongye.Ink2, description = "更多")
                    }
                }
                HLine(color = Kongye.Divider)
            }
        }

        // ---------- 正文 ----------
        // 目录打开时，点一下正文（上方空白处）收起目录；这次点击不会进入编辑，上下滑动照常滚动
        Box(Modifier.weight(1f).documentSelectionInput(editor).then(if (tocOpen || infoOpen) Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                    up.consume()
                    tocOpen = false
                    infoOpen = false
                }
            }
        } else Modifier)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = DocRenderer.SIDE_DP.dp)) {
                Spacer(Modifier.height(22.dp))
                key(editor.inputGeneration) {
                val titleGeneration=editor.inputGeneration
                val titleEpoch=editor.inputEpoch
                DocumentSelectionDrawing {
                BasicTextField(
                    value = editor.title,
                    onValueChange = { if(titleGeneration==editor.inputGeneration && titleEpoch==editor.inputEpoch) editor.onTitleChange(it) },
                    readOnly = editor.findMode,
                    textStyle = TextStyle(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.Bold, color = Kongye.Ink),
                    cursorBrush = SolidColor(Kongye.Primary),
                    onTextLayout = { titleLayout = it; editor.layouts[TITLE_KEY]=it },
                    modifier = Modifier.fillMaxWidth().documentTextMenu(editor).focusRequester(titleFocus).onGloballyPositioned { editor.selection.register(TITLE_KEY,it) }.drawBehind {
                        editor.selection.localRange(TITLE_KEY)?.let { range -> titleLayout?.let { if(!range.collapsed) drawPath(it.getPathForRange(range.min,range.max),selectionBackground) } }
                    }.textGestures(editor, TITLE_KEY, minOffset = 0, layout = { titleLayout }).onFocusChanged {
                        titleFocused = it.isFocused
                        if (it.isFocused) {
                            editor.focusedId = null
                            editor.focusedCell = null
                            panel = null
                        }
                    },
                    decorationBox = { inner ->
                        Box {
                            if (editor.title.text.isEmpty()) Text("无标题", fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.Bold, color = Kongye.Text4)
                            inner()
                        }
                    },
                )
                }
                }
                Spacer(Modifier.height(18.dp))
                HLine(color = Kongye.Divider)
                Spacer(Modifier.height(14.dp))
                editor.blocks.forEachIndexed { i, b ->
                    key(b.id,b) {
                        Box(Modifier.onGloballyPositioned { blockTops[b.id] = it.positionInParent().y.toInt() }) {
                            BlockView(b, i, editor, onImageTap = { sheet = EdSheet.Image(it) }, onDividerTap = { sheet = EdSheet.Divider(it) })
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(260.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { if (!editor.findMode) editor.focusEnd() },
                )
            }
            SelectionHandles(editor,scroll)
            SelectionActions(editor)
            if (editor.findMode) FindNavigator(editor, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        }

        // ---------- 底部：查找替换栏 / 评论输入 / 评论面板 / 工具栏及其二级面板 ----------
        if (editor.findMode) {
            SoftPanel { FindBottomBar(editor) }
        } else if (editor.commentDraft != null) {
            SoftPanel { CommentComposer(editor) { finishEditing() } }
        } else if (editor.commentView != null) {
            SoftPanel { CommentPanel(editor, if (imeVisible) commentPanelSmall else commentPanelLarge) }
        } else if (infoOpen) {
            SoftPanel {
            val content = editor.content()
            val body = content.bodyText()
            InfoPanel(commentPanelLarge, listOf(
                "标题" to docTitle,
                "位置" to (item?.let(::locationOf) ?: "我的桌面"),
                "创建时间" to formatFullTime(item?.createdAt ?: 0),
                "最近修改" to formatFullTime(item?.updatedAt ?: 0),
                "总字数" to countWords(editor.title.text + "\n" + body).toString(),
                "字符数" to body.count { !it.isWhitespace() }.toString(),
                "图片 / 表格" to "${content.blocks.count { it.type == BlockType.IMAGE }} / ${content.blocks.count { it.type == BlockType.TABLE }}",
            )) { infoOpen = false }
            }
        } else if (tocOpen) {
            SoftPanel {
            TocPanel(editor, commentPanelLarge, tocActive, onClose = { tocOpen = false }) { h ->
                // 选中的标题变色，正文跳到这一节；目录保持打开
                tocActive = h.id
                scope.launch { scroll.animateScrollTo(((blockTops[h.id] ?: 0) - 24 * density.density).toInt().coerceAtLeast(0)) }
            }
            }
        } else if (toolbarVisible) {
            EditorToolbar(
                editor = editor,
                panel = panel,
                onPanel = { p ->
                    // 再点一次同一个按钮：收起面板，回到键盘
                    if (panel == p) {
                        panel = null
                        keyboard?.show()
                    } else {
                        panel = p
                        keyboard?.hide()
                    }
                },
                onUndo = { editor.undo(imeVisible) },
                onRedo = { editor.redo(imeVisible) },
                onComment = {
                    val why = editor.startComment()
                    if (why != null) Toast.show(why) else panel = null
                },
                onTableMenu = { sheet = EdSheet.Table },
                onHideKeyboard = ::finishEditing,
                onCustomize = {
                    finishEditing()
                    sheet = EdSheet.Toolbar
                },
            )
            if (!imeVisible && panel != null) {
                // One opening animation belongs to the expanded tool area, not to
                // each tool. Switching its contents retains the current height.
                SoftPanel {
                    when (panel) {
                        EdPanel.STYLE -> StylePanel(editor, panelHeight)
                        EdPanel.PARAGRAPH -> ParagraphPanel(editor, panelHeight)
                        EdPanel.INSERT -> InsertPanel(
                            editor, panelHeight,
                            onImage = {
                                panel = null
                                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onCamera = {
                                panel = null
                                openCamera()
                            },
                            onLink = {
                                panel = null
                                dialog = EdDialog.LINK
                            },
                            onDone = {
                                panel = null
                                keyboard?.show()
                            },
                        )
                        null -> {}
                    }
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
    }

    // ---------- 面板 ----------
    when (val s = sheet) {
        EdSheet.Share -> DocShareSheet(docId, onDismiss = { sheet = null }, prepare = { editor.save() })
        EdSheet.More -> DocMenuSheet(
            starred = item?.starred == true,
            pinned = item?.pinned == true,
            words = countWords(editor.title.text + "\n" + editor.content().bodyText()),
            onDismiss = { sheet = null },
            actions = DocMenuActions(
                onToggleStar = {
                    val was = item?.starred == true
                    Repository.toggleStar(docId)
                    Toast.done(if (was) "已取消收藏" else "收藏成功")
                },
                onTogglePin = {
                    val was=item?.pinned==true
                    Repository.togglePin(docId)
                    Toast.done(if(was) "已取消置顶" else "已置顶")
                },
                onInfo = { editor.save(); finishEditing(); tocOpen = false; editor.commentView = null; infoOpen = true },
                onFind = { finishEditing(); editor.openFind() },
                onSaveTemplate = { dialog = EdDialog.TEMPLATE },
                onComments = { editor.commentView = EditorState.CommentView(null) },
                onToc = {
                    val headings = editor.headings()
                    run {
                        infoOpen = false
                        finishEditing()
                        editor.commentView = null
                        // 打开时先高亮当前看到的那一节
                        tocActive = headings.lastOrNull { (blockTops[it.id] ?: Int.MAX_VALUE) <= scroll.value + 24 }?.id
                        tocOpen = true
                    }
                },
                onLocalVersions = { leaveTo(Screen.Versions(docId)) },
                onShare = { kind ->
                    editor.save()
                    shareDoc(context, docId, kind)
                },
                onDelete = { dialog = EdDialog.DELETE },
            ),
        )
        EdSheet.Table -> ActionSheet(
            onDismiss = { sheet = null },
            actions = listOf(
                SheetAction(KongyeIcons.TableEdit, "在上方插入行") { editor.tableOp(TableOp.ROW_ABOVE) },
                SheetAction(KongyeIcons.TableEdit, "在下方插入行") { editor.tableOp(TableOp.ROW_BELOW) },
                SheetAction(KongyeIcons.TableEdit, "在左侧插入列") { editor.tableOp(TableOp.COL_LEFT) },
                SheetAction(KongyeIcons.TableEdit, "在右侧插入列") { editor.tableOp(TableOp.COL_RIGHT) },
                SheetAction(KongyeIcons.Close, "删除当前行") { editor.tableOp(TableOp.DELETE_ROW) },
                SheetAction(KongyeIcons.Close, "删除当前列") { editor.tableOp(TableOp.DELETE_COL) },
                SheetAction(KongyeIcons.Trash, "删除表格", danger = true) { editor.tableOp(TableOp.DELETE_TABLE) },
            ),
        )
        is EdSheet.Image -> ActionSheet(
            onDismiss = { sheet = null },
            actions = listOf(
                SheetAction(KongyeIcons.Share, "分享图片") {
                    val f = File(Repository.imagesDir, s.b.image)
                    if (f.exists()) {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND).setType(if (f.name.endsWith(".png")) "image/png" else "image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "分享图片"))
                    }
                },
                SheetAction(KongyeIcons.Trash, "删除图片", danger = true) { editor.deleteBlock(s.b) },
            ),
        )
        EdSheet.Toolbar -> ToolbarOrderSheet { sheet = null }
        is EdSheet.Divider -> ActionSheet(
            onDismiss = { sheet = null },
            actions = listOf(SheetAction(KongyeIcons.Trash, "删除分割线", danger = true) { editor.deleteBlock(s.b) }),
        )
        null -> {}
    }

    // ---------- 弹窗 ----------
    when (dialog) {
        EdDialog.LINK -> {
            val existing = editor.linkAtCursor()
            LinkDialog(
                initialText = editor.selectedText(),
                initialUrl = existing?.url.orEmpty(),
                editingExisting = existing != null,
                hasSelection = editor.selectedText().isNotEmpty(),
                onDismiss = { dialog = null },
                onOpen = { url -> dialog = null; if (!Share.openUrl(context, url)) Toast.show("无法打开链接") },
                onRemove = { dialog = null; editor.removeLink() },
                onConfirm = { text, url -> dialog = null; editor.applyLink(text, url.trim()) },
            )
        }
        EdDialog.TEMPLATE -> InputDialog(
            title = "保存为模板", initial = editor.title.text, placeholder = "模板名称", confirmText = "保存",
            onDismiss = { dialog = null },
        ) { name ->
            dialog = null
            Repository.saveAsTemplate(name, editor.title.text, editor.content())
            Toast.done("已保存到模板库")
        }
        EdDialog.DELETE -> ConfirmDialog(
            title = "删除文档", message = "文档将移到回收站，彻底删除前可以恢复。",
            confirmText = "删除", danger = true, onDismiss = { dialog = null },
        ) {
            dialog = null
            editor.save()
            Repository.trash(docId)
            Toast.done("删除文档成功")
            AppState.pop()
        }
        null -> {}
    }
}

/**
 * 目录面板（在键盘的位置，上方正文不变暗）：一级标题加粗，下级依次缩进；
 * 点标题时它变成蓝色、正文跳过去，面板不收起；点“完成”或上方正文收起。
 */
@Composable
private fun TocPanel(editor: EditorState, height: Dp, active: String?, onClose: () -> Unit, onPick: (BlockState) -> Unit) {
    val headings = editor.headings()
    val top = headings.minOfOrNull { it.type.ordinal } ?: 0
    Column(
        Modifier.fillMaxWidth().height(height).background(Kongye.Surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        HLine()
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Text("目录", Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
            Text(
                "完成", Modifier.align(Alignment.CenterEnd).clickable(onClick = onClose).padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 16.sp, color = Kongye.Primary,
            )
        }
        if (headings.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("还没有目录，设置正文标题后会显示在这里", color = Kongye.Text3, fontSize = 14.sp)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(headings, key = { it.id }) { h ->
                val level = h.type.ordinal - top
                Text(
                    h.text,
                    Modifier.fillMaxWidth().clickable { onPick(h) }.padding(start = (20 + level * 22).dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
                    fontSize = if (level == 0) 17.sp else 16.sp,
                    fontWeight = if (level == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        h.id == active -> Kongye.Primary
                        level == 0 -> Kongye.Ink
                        else -> Kongye.Ink2
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(height: Dp, rows: List<Pair<String, String>>, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height).background(Kongye.Surface)) {
        HLine()
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Text("文档信息", Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
            Text("完成", Modifier.align(Alignment.CenterEnd).clickable(onClick = onClose).padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 16.sp, color = Kongye.Primary)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                    Text(label, fontSize = 14.sp, color = Kongye.Text3)
                    Text(value, Modifier.weight(1f).padding(start = 20.dp), fontSize = 14.sp, color = Kongye.Text, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

/** Slide a fully measured panel upward with the same Material motion as share sheets. */
@Composable
private fun SoftPanel(content: @Composable () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f,visibilityThreshold=0.00001f) }
    // Material 3 1.4.0 ModalBottomSheet uses StandardMotionTokens.DefaultSpatial.
    // The corresponding MotionScheme API is internal in that library version.
    val motion = remember { androidx.compose.animation.core.spring<Float>(dampingRatio=0.9f,stiffness=700f) }
    LaunchedEffect(Unit) { progress.animateTo(1f,motion) }
    Box(Modifier.clipToBounds().layout { measurable,constraints ->
        val panel = measurable.measure(constraints.copy(minHeight=0))
        val shown = (panel.height*progress.value.coerceIn(0f,1f)).roundToInt()
        layout(panel.width,shown) { panel.placeRelative(0,0) }
    }) { content() }
}
