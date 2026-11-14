package com.kongye.docs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateHistoryScreen() {
    val context=LocalContext.current
    val current=remember { AppUpdates.installed(context).version }
    val scope=rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ReleaseHistoryEntry>?>(null) }
    var selected by remember { mutableStateOf<ReleaseHistoryEntry?>(null) }
    var reading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val filtered=remember(entries,query) { entries.orEmpty().filter { entry ->
        query.trim().let { it.isEmpty() || "v${entry.version} ${entry.date} ${entry.notes}".contains(it,ignoreCase=true) }
    } }
    val pages=((filtered.size+9)/10).coerceAtLeast(1)
    val currentPage=page.coerceIn(0,pages-1)
    val listState=rememberLazyListState()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun finishSearch() { focus.clearFocus();keyboard?.hide() }
    fun refresh() {
        if (reading || refreshing) return
        refreshing = true
        scope.launch {
            try {
                val published = AppUpdates().history()
                entries = withContext(Dispatchers.IO) { ReleaseHistory.save(context, published) }
                page = 0
                listState.requestScrollToItem(0)
                Toast.show("已从 GitHub 刷新")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.show("无法从 GitHub 刷新，请重试")
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(currentPage,query) { listState.scrollToItem(0) }
    LaunchedEffect(Unit) {
        entries=withContext(Dispatchers.IO) { runCatching { ReleaseHistory.read(context) }.getOrNull() }
        reading=false
    }
    BackHandler(selected!=null) { selected=null }
    Column(Modifier.fillMaxSize().background(Kongye.Surface)) {
        TopBar(title=selected?.let { "v${it.version} 更新详情" } ?: "更新历史",onBack={ if(selected!=null) selected=null else AppState.pop() },divider=true) {
            if (selected == null) {
                if (refreshing) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Kongye.Primary, strokeWidth = 2.dp)
                    }
                } else {
                    IconBtn(KongyeIcons.Refresh, ::refresh, enabled = !reading, description = "从 GitHub 刷新更新历史")
                }
            }
        }
        val detail=selected
        if(detail!=null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp)) {
            Text(detail.date+if(detail.version==current) " · 当前版本" else "",color=Kongye.Text3,fontSize=13.sp,modifier=Modifier.padding(bottom=16.dp))
            SelectionContainer { MarkdownText(detail.notes) }
        } else if(entries==null) Text(if(reading || refreshing) "正在读取…" else "暂时无法读取更新记录",Modifier.padding(20.dp),color=Kongye.Text3)
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).background(Kongye.Field,androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                BasicTextField(query,{ query=it;page=0 },singleLine=true,textStyle=TextStyle(color=Kongye.Text,fontSize=15.sp),cursorBrush=SolidColor(Kongye.Primary),
                    modifier=Modifier.weight(1f).semantics { contentDescription="搜索更新历史" },
                    decorationBox={ inner -> Box { if(query.isEmpty()) Text("搜索版本、日期或更新内容",color=Kongye.Text3,fontSize=15.sp);inner() } })
                if(query.isNotEmpty()) Text("清除",Modifier.clickable { query="";page=0 }.padding(start=12.dp),color=Kongye.Link,fontSize=13.sp)
            }
            Text("共 ${filtered.size} 个版本",Modifier.padding(horizontal=20.dp,vertical=4.dp),color=Kongye.Text3,fontSize=12.sp)
            LazyColumn(Modifier.weight(1f),state=listState) {
            if(filtered.isEmpty()) item { Text("没有找到相关更新",Modifier.fillMaxWidth().padding(24.dp),color=Kongye.Text3) }
            items(filtered.drop(currentPage*10).take(10),key={it.version}) { entry ->
                Column(Modifier.fillMaxWidth().clickable { finishSearch();selected=entry }.padding(horizontal=20.dp,vertical=18.dp)) {
                    Text("v${entry.version}"+if(entry.version==current) " · 当前版本" else "",color=Kongye.Text,fontSize=17.sp,fontWeight=FontWeight.Medium)
                    if(entry.date.isNotBlank()) Text(entry.date,color=Kongye.Text3,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))
                    Text(ReleaseNotes.summary(entry.notes),color=Kongye.Text2,fontSize=13.sp,lineHeight=21.sp,maxLines=3,modifier=Modifier.padding(top=8.dp))
                }
                HLine(Modifier.padding(horizontal=20.dp),color=Kongye.Line)
            }
            }
            HLine(color=Kongye.Line)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("上一页",Modifier.clickable(enabled=currentPage>0) { finishSearch();page=currentPage-1 }.padding(8.dp),color=if(currentPage>0) Kongye.Link else Kongye.Text4,fontSize=14.sp)
                Text("第 ${currentPage+1} / $pages 页",color=Kongye.Text3,fontSize=13.sp)
                Text("下一页",Modifier.clickable(enabled=currentPage<pages-1) { finishSearch();page=currentPage+1 }.padding(8.dp),color=if(currentPage<pages-1) Kongye.Link else Kongye.Text4,fontSize=14.sp)
            }
        }
    }
}
