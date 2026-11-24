package com.kongye.docs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VersionChanges(version: DocVersion, currentTitle: String, currentContent: DocContent) {
    var parts by remember(version.id,currentTitle,currentContent) { mutableStateOf<List<DiffPart>?>(null) }
    LaunchedEffect(version.id,currentTitle,currentContent) {
        parts=withContext(Dispatchers.Default) {
            VersionDiff.previewWithImages(currentTitle,currentContent,version)
        }
    }
    val added=if(Kongye.dark) Color(0xFF93D8A2) else Color(0xFF206A3A)
    val removed=if(Kongye.dark) Color(0xFFFFA6A6) else Color(0xFFAC3030)
    val diff=parts
    val rows=remember(diff) {
        buildList<List<DiffPart>> {
            val text=mutableListOf<DiffPart>()
            fun flush() { if(text.isNotEmpty()) { add(text.toList()); text.clear() } }
            diff.orEmpty().forEach { part ->
                if(part.image.isNotEmpty()) { flush(); add(listOf(part)) } else text+=part
            }
            flush()
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(Kongye.Surface), contentPadding=PaddingValues(18.dp)) {
        item { Text("与当前编辑文档对比：绿色为本地版本新增，红色删除线为本地版本删除",color=Kongye.Text2,fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp)) }
        if(diff==null) item { Text("正在比较…",color=Kongye.Text3) }
        else if(diff.none { it.kind!=DiffKind.SAME }) item {
            Text(if(currentContent!=version.content) "文字与图片一致，格式或评论有调整；可切换文档预览查看该版本。" else "该版本与当前文档一致",color=Kongye.Text2,fontSize=13.sp,modifier=Modifier.padding(bottom=12.dp))
        }
        items(rows) { row ->
            val picture=row.first().takeIf { it.image.isNotEmpty() }
            if(picture!=null) VersionDiffImage(picture,added,removed)
            else {
            val styled=buildAnnotatedString {
                row.forEach { part ->
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
}

@Composable
private fun VersionDiffImage(part: DiffPart, added: Color, removed: Color) {
    var bitmap by remember(part.image) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(part.image) { mutableStateOf(true) }
    LaunchedEffect(part.image) {
        bitmap=withContext(Dispatchers.IO) { Repository.loadBitmap(part.image)?.asImageBitmap() }
        loading=false
    }
    val label=when(part.kind) {
        DiffKind.ADDED -> "本地版本新增图片"
        DiffKind.REMOVED -> "本地版本删除图片"
        DiffKind.SAME -> "本地版本图片"
    }
    val color=when(part.kind) { DiffKind.ADDED -> added; DiffKind.REMOVED -> removed; DiffKind.SAME -> Kongye.Divider }
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp).then(
        if(part.kind==DiffKind.SAME) Modifier else Modifier.border(1.dp,color).padding(8.dp)
    )) {
        if(part.kind!=DiffKind.SAME) Text(label,color=color,fontSize=12.sp,modifier=Modifier.padding(bottom=8.dp))
        val image=bitmap
        if(image!=null) Image(image,label,Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height),contentScale=ContentScale.Fit)
        else Box(Modifier.fillMaxWidth().height(100.dp).background(Kongye.Band),contentAlignment=Alignment.Center) {
            Text(if(loading) "正在加载图片…" else "图片文件不可用",color=Kongye.Text3,fontSize=13.sp)
        }
    }
}
