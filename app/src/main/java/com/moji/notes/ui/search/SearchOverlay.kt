package com.moji.notes.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.data.Item
import com.moji.notes.data.Repository
import com.moji.notes.ui.AppState
import com.moji.notes.ui.SearchTime
import com.moji.notes.ui.SearchType
import com.moji.notes.ui.components.FileIcon
import com.moji.notes.ui.components.HLine
import com.moji.notes.ui.components.IconBtn
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.theme.Moji
import com.moji.notes.util.stampTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HighlightBg get() = if (Moji.dark) Color(0x664D8DF0) else Color(0xFFD6E8FF)

/**
 * 石墨风格的搜索浮层：搜索栏从顶部滑下、键盘从底部升起，中间是变暗的当前页面。
 * 输入关键词后显示结果，标题和正文中的关键词都会高亮；齿轮里可以设置搜索范围。
 */
@Composable
fun SearchOverlay() {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var scopeOpen by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val items by Repository.items.collectAsState()
    val time = AppState.searchTime
    val type = AppState.searchType
    val results = remember(query, items, time, type) { filtered(Repository.search(query, onlyComments = type == SearchType.COMMENT), time, type) }

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun close() {
        if (closing) return
        closing = true
        dismissKeyboard()
        scope.launch {
            progress.animateTo(0f, tween(220, easing = FastOutLinearInEasing))
            AppState.searchOpen = false
        }
    }

    LaunchedEffect(Unit) {
        launch { progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
        // 搜索栏开始滑下的同时聚焦，键盘由系统动画从底部升起
        delay(60)
        runCatching { focus.requestFocus() }
    }
    BackHandler(enabled = !closing) { if (scopeOpen) scopeOpen = false else close() }

    Box(Modifier.fillMaxSize()) {
        // 变暗的当前页面，点空白处关闭
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(Moji.Scrim)
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = -size.height * (1f - progress.value) }
                    .background(Moji.Surface).statusBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 齿轮在搜索框左边；设置了范围时变蓝，提醒结果是筛选过的
                    val filtering = time != SearchTime.ANY || type != SearchType.ANY
                    IconBtn(
                        MojiIcons.Gear, { dismissKeyboard(); scopeOpen = true },
                        tint = if (filtering) Moji.Bookmark else Moji.Ink2, size = 42.dp, iconSize = 22.dp, description = "搜索范围",
                    )
                    Spacer(Modifier.width(2.dp))
                    Row(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Moji.Field)
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MojiIcons.Search, null, Modifier.size(18.dp), tint = Moji.Icon2)
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = Moji.Ink),
                            cursorBrush = SolidColor(Moji.Bookmark),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text("搜索文件、文件夹", fontSize = 15.sp, color = Moji.Sub)
                                    inner()
                                }
                            },
                        )
                        if (query.isNotEmpty()) IconBtn(MojiIcons.Close, { query = "" }, size = 28.dp, iconSize = 15.dp, tint = Moji.Icon2, description = "清空")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "取消", Modifier.clip(RoundedCornerShape(8.dp)).clickable { close() }.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 16.sp, color = Moji.Ink2,
                    )
                }
                HLine(color = Moji.Divider)
            }
            if (query.isNotBlank()) {
                Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = progress.value }.background(Moji.Surface).imePadding()) {
                    if (results.isEmpty()) {
                        Text(
                            "没有找到相关文件", Modifier.fillMaxWidth().padding(top = 72.dp),
                            fontSize = 14.sp, color = Moji.Sub, textAlign = TextAlign.Center,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(results, key = { it.item.id }) { hit ->
                                ResultRow(hit, query.trim()) {
                                    dismissKeyboard()
                                    AppState.searchOpen = false
                                    // 命中的是评论：打开文档后直接打开这条评论
                                    AppState.pendingComment = hit.commentThread
                                    AppState.open(hit.item)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 搜索范围面板：从顶部滑下
        AnimatedVisibility(scopeOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Moji.Scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { scopeOpen = false },
            )
        }
        AnimatedVisibility(
            scopeOpen,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it },
            exit = slideOutVertically(tween(200, easing = FastOutLinearInEasing)) { -it },
        ) {
            ScopePanel(onDismiss = { scopeOpen = false })
        }
    }
}

private fun filtered(list: List<Repository.SearchHit>, time: SearchTime, type: SearchType): List<Repository.SearchHit> {
    val since = time.days?.let { System.currentTimeMillis() - it * 86_400_000L }
    return list.filter { hit ->
        (since == null || hit.item.updatedAt >= since) && when (type) {
            SearchType.ANY -> true
            SearchType.FOLDER -> hit.item.isFolder
            SearchType.DOC -> !hit.item.isFolder
            SearchType.COMMENT -> hit.commentThread != null
        }
    }
}

/** 把文本里出现的关键词（不区分大小写）都标成浅蓝底 */
private fun highlight(text: String, q: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (q.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val i = text.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        addStyle(SpanStyle(background = HighlightBg), i, i + q.length)
        from = i + q.length
    }
}

@Composable
private fun ResultRow(hit: Repository.SearchHit, q: String, onClick: () -> Unit) {
    val item = hit.item
    // 评论里命中的摘要前面标上“评论：”
    val snippet = hit.snippet?.let { if (hit.commentThread != null) "评论：$it" else it }
    Row(
        Modifier.fillMaxWidth().background(Moji.Surface).clickable(onClick = onClick).padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(item.isFolder, 30.dp, starred = item.starred)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 13.dp, end = 20.dp)) {
            Text(highlight(item.displayTitle, q), fontSize = 16.sp, color = Moji.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snippet != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    highlight(snippet, q), fontSize = 13.sp, lineHeight = 19.sp, color = Moji.Text3,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("${stampTime(item.updatedAt)} 更新", fontSize = 12.sp, color = Moji.Sub, maxLines = 1)
            Spacer(Modifier.height(13.dp))
            HLine(color = Moji.Divider)
        }
    }
}

/** 设置文件搜索范围：时间范围 + 文件类型，点“完成”才生效 */
@Composable
private fun ScopePanel(onDismiss: () -> Unit) {
    var time by remember { mutableStateOf(AppState.searchTime) }
    var type by remember { mutableStateOf(AppState.searchType) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)).background(Moji.Surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding().padding(bottom = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp)) {
            Text(
                "取消", Modifier.align(Alignment.CenterStart).padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 16.sp, color = Moji.Ink2,
            )
            Text("设置文件搜索范围", Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Moji.Ink)
            Text(
                "完成", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        AppState.searchTime = time
                        AppState.searchType = type
                        onDismiss()
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Moji.Bookmark,
            )
        }
        HLine(color = Moji.Divider)
        ScopeSection("时间范围", SearchTime.entries, time, { it.label }) { time = it }
        ScopeSection("文件类型", SearchType.entries, type, { it.label }) { type = it }
    }
}

@Composable
private fun <T> ScopeSection(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp), fontSize = 13.sp, color = Moji.Sub)
    // 选项做成两列的小方块，选中项蓝字蓝底并带 ✓
    options.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { option ->
                val on = option == selected
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) Moji.PrimarySoft else Moji.Field)
                        .clickable { onPick(option) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label(option), Modifier.weight(1f), fontSize = 15.sp, color = if (on) Moji.Bookmark else Moji.Ink)
                    if (on) Icon(MojiIcons.Check, null, Modifier.size(16.dp), tint = Moji.Bookmark)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
