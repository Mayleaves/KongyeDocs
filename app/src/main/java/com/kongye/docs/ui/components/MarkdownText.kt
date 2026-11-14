package com.kongye.docs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.*
import com.kongye.docs.ui.theme.Kongye

/** Read-only Markdown; links remain actionable, without creating editor input sessions. */
@Composable
internal fun MarkdownText(markdown: String,modifier: Modifier=Modifier) {
    val blocks=remember(markdown) { Converter.fromMarkdown("\n"+markdown,"更新内容").second.blocks }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        var number=0
        blocks.forEach { block ->
            number=if(block.type==BlockType.NUMBER) number+1 else 0
            if(block.type==BlockType.DIVIDER) HLine(color=Kongye.Line2)
            else if(block.type==BlockType.TABLE) Column(Modifier.horizontalScroll(rememberScrollState())) {
                block.table.forEachIndexed { index,row ->
                    Row(Modifier.background(if(index==0) Kongye.Field else Kongye.Surface)) {
                        row.forEach { Text(it,Modifier.widthIn(min=100.dp,max=220.dp).padding(8.dp),color=Kongye.Text,fontSize=13.sp,fontWeight=if(index==0) FontWeight.Medium else FontWeight.Normal) }
                    }
                    HLine(color=Kongye.Line2)
                }
            } else {
                val type=block.type
                val text=buildAnnotatedString {
                    append(if(type==BlockType.IMAGE) "[图片] ${block.image}" else block.text)
                    block.spans.forEach { span ->
                        val from=span.start.coerceIn(0,length); val to=span.end.coerceIn(from,length)
                        if(from<to) {
                            val style=when(span.style) {
                                InlineStyle.BOLD -> SpanStyle(fontWeight=FontWeight.SemiBold)
                                InlineStyle.ITALIC -> SpanStyle(fontStyle=FontStyle.Italic)
                                InlineStyle.UNDERLINE -> SpanStyle(textDecoration=TextDecoration.Underline)
                                InlineStyle.STRIKE -> SpanStyle(textDecoration=TextDecoration.LineThrough)
                                InlineStyle.HIGHLIGHT -> SpanStyle(background=Kongye.PrimarySoft)
                                InlineStyle.COLOR -> SpanStyle(fontFamily=FontFamily.Monospace,background=Kongye.Field)
                                InlineStyle.LINK -> SpanStyle(color=Kongye.Link,textDecoration=TextDecoration.Underline)
                                else -> SpanStyle()
                            }
                            addStyle(style,from,to)
                            if(span.style==InlineStyle.LINK && (span.url.startsWith("https://") || span.url.startsWith("http://")))
                                addLink(LinkAnnotation.Url(span.url),from,to)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start=(block.indent*14).dp)) {
                    val marker=when(type) { BlockType.BULLET -> "•"; BlockType.NUMBER -> "$number."; BlockType.TODO -> if(block.checked) "☑" else "☐"; BlockType.QUOTE -> "│"; else -> null }
                    if(marker!=null) Text(marker,Modifier.width(24.dp),color=Kongye.Text3,fontSize=15.sp)
                    val code=type==BlockType.CODE
                    Text(text,
                        Modifier.weight(1f).then(if(code) Modifier.clip(RoundedCornerShape(8.dp)).background(Kongye.Field).padding(12.dp) else Modifier),
                        color=if(type==BlockType.QUOTE) Kongye.Text2 else Kongye.Text,
                        fontSize=when(type) { BlockType.H1 -> 20.sp; BlockType.H2 -> 18.sp; BlockType.H3 -> 16.sp; else -> 14.sp },
                        fontWeight=if(type.isHeading) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily=if(code) FontFamily.Monospace else FontFamily.Default,lineHeight=24.sp)
                }
            }
        }
    }
}
