package com.kongye.docs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.DiffKind
import com.kongye.docs.data.DiffPart
import com.kongye.docs.data.DocVersion
import com.kongye.docs.data.DocContent
import com.kongye.docs.data.VersionDiff
import com.kongye.docs.data.plainText
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.stampTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VersionChanges(version: DocVersion, currentTitle: String, currentContent: DocContent) {
    var parts by remember(version.id,currentTitle,currentContent) { mutableStateOf<List<DiffPart>?>(null) }
    LaunchedEffect(version.id,currentTitle,currentContent) {
        parts=withContext(Dispatchers.Default) {
            VersionDiff.preview(currentTitle,currentContent,version)
        }
    }
    val added=if(Kongye.dark) Color(0xFF93D8A2) else Color(0xFF206A3A)
    val removed=if(Kongye.dark) Color(0xFFFFA6A6) else Color(0xFFAC3030)
    Column(Modifier.fillMaxSize().background(Kongye.Surface).verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("与当前编辑文档对比：绿色为本地版本新增，红色删除线为本地版本删除",color=Kongye.Text2,fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp))
        val diff=parts
        if(diff==null) Text("正在比较…",color=Kongye.Text3)
        else {
            if(diff.none { it.kind!=DiffKind.SAME }) Text(if(currentContent!=version.content) "文字一致，格式、图片或评论有调整；可切换文档预览查看该版本。" else "该版本与当前文档文字一致",color=Kongye.Text2,fontSize=13.sp,modifier=Modifier.padding(bottom=12.dp))
            val styled=buildAnnotatedString {
                diff.forEach { part ->
                    val start=length
                    append(part.text)
                    when(part.kind) {
                        DiffKind.ADDED -> addStyle(SpanStyle(color=added,background=added.copy(alpha=0.13f)),start,length)
                        DiffKind.REMOVED -> addStyle(SpanStyle(color=removed,background=removed.copy(alpha=0.12f),textDecoration=TextDecoration.LineThrough),start,length)
                        DiffKind.SAME -> {}
                    }
                }
            }
            SelectionContainer { Text(styled,color=Kongye.Text,fontFamily=FontFamily.Monospace,fontSize=15.sp,lineHeight=25.sp) }
        }
    }
}
