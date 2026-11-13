package com.kongye.docs.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.Html
import android.text.Spanned
import android.text.style.*
import androidx.core.text.HtmlCompat
import com.kongye.docs.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class ClipboardFragment(val blocks: List<Block>,val wholeFirst: Boolean=true)

/** Keep document structure alongside interoperable plain text and HTML. */
internal object DocumentClipboard {
    private const val TOKEN="com.kongye.docs.CLIPBOARD_FRAGMENT"
    private const val MIME="application/vnd.kongye.document-fragment"
    private val json=Json { ignoreUnknownKeys=true }
    @Serializable private data class Saved(val plain: String,val fragment: ClipboardFragment)
    private data class Candidate(val plain: String,val fragment: ClipboardFragment,val at: Long)
    private var candidate: Candidate?=null
    private var richText: String?=null
    private var publishing=false
    private val tokens=Regex("[a-f0-9-]{36}")
    private fun root(context: Context)=File(context.cacheDir,"document-clipboard").apply { mkdirs() }
    private fun plain(context: Context,data: ClipData)=(0 until data.itemCount).joinToString("\n") { data.getItemAt(it).coerceToText(context).toString() }

    fun fragment(editor: EditorState): ClipboardFragment? {
        val selection=editor.selection
        val range=selection.range ?: return null
        if(selection.all) return ClipboardFragment(editor.content().withoutComments().blocks)
        val first=editor.blocks.indexOfFirst { it.id==range.start.key }
        val last=editor.blocks.indexOfFirst { it.id==range.end.key }
        if(range.start.key==TITLE_KEY) {
            val title=editor.title.text.substring(range.start.offset,if(range.end.key==TITLE_KEY) range.end.offset else editor.title.text.length)
            val body=if(last<0) emptyList() else editor.blocks.take(last+1).mapIndexed { index,b ->
                b.toBlock().let { block -> if(index==last && b.type.isText) block.copy(text=block.text.take(range.end.offset-1),spans=sliceSpans(block.spans,0,range.end.offset-1)) else block }
            }
            return ClipboardFragment(listOf(Block(text=title))+body.map { it.copy(spans=it.spans.filterNot { span -> span.style==InlineStyle.COMMENT }) },false)
        }
        if(first<0 || last<first) return null
        val blocks=editor.blocks.subList(first,last+1).mapIndexed { index,b ->
            val block=b.toBlock()
            if(!b.type.isText) block else {
                val from=if(index==0) range.start.offset-1 else 0
                val to=if(first+index==last) range.end.offset-1 else block.text.length
                block.copy(text=block.text.substring(from,to),spans=sliceSpans(block.spans,from,to))
            }
        }.map { it.copy(spans=it.spans.filterNot { span -> span.style==InlineStyle.COMMENT }) }
        return ClipboardFragment(blocks,range.start.offset==1)
    }

    /** A keyboard may read selected text and put its own plain-text ClipData on the clipboard. */
    fun rememberSelection(editor: EditorState) {
        fragment(editor)?.let { candidate=Candidate(editor.selection.text(),it,SystemClock.uptimeMillis()) }
    }

    fun copy(context: Context,editor: EditorState,cut: Boolean=false): Boolean {
        val part=fragment(editor) ?: return false
        publish(context,editor.selection.text(),part)
        if(cut) editor.selection.replace("") else editor.clearArticleSelection()
        return true
    }

    fun onClipboardChanged(context: Context) {
        if(publishing) return
        val data=context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        richText=if(data.description.hasMimeType(MIME) || data.description.hasMimeType("text/html") || data.getItemAt(0).text is Spanned) plain(context,data) else null
        val pending=candidate ?: return
        if(SystemClock.uptimeMillis()-pending.at>30000) { candidate=null; return }
        if(data.description.extras?.getString(TOKEN)!=null || data.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")==true) return
        if(data.description.hasMimeType("text/html") || data.getItemAt(0).text is Spanned) return
        if(plain(context,data)!=pending.plain) return
        candidate=null
        publish(context,pending.plain,pending.fragment)
    }

    private fun publish(context: Context,text: String,part: ClipboardFragment) {
        val token=UUID.randomUUID().toString()
        val dir=File(root(context),token).apply { mkdirs() }
        val payload=json.encodeToString(Saved(text,part))
        File(dir,"fragment.json").writeText(payload)
        part.blocks.filter { it.type==BlockType.IMAGE && BackupCodec.validImage(it.image) }.forEach { block ->
            File(Repository.imagesDir,block.image).takeIf { it.isFile }?.copyTo(File(dir,block.image),overwrite=true)
        }
        // The complete structure stays in the private cache; bound the optional
        // HTML duplicate so large documents do not overflow the clipboard parcel.
        val html=html(part).takeIf { (it.length.toLong()+text.length)*2<700000 }
        val types=if(html!=null) arrayOf("text/plain","text/html",MIME) else arrayOf("text/plain",MIME)
        val clip=ClipData(android.content.ClipDescription("文档",types),ClipData.Item(text,html,null,null))
        clip.description.extras=PersistableBundle().apply { putString(TOKEN,token) }
        publishing=true
        try { richText=text; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
        finally { publishing=false }
        root(context).listFiles()?.filter { it.isDirectory && tokens.matches(it.name) }?.sortedByDescending { it.lastModified() }
            ?.drop(12)?.forEach { it.deleteRecursively() }
    }

    fun paste(context: Context,editor: EditorState): Boolean {
        val data=context.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        val text=plain(context,data)
        val part=read(context,data,text)
        return if(part!=null) editor.pasteFragment(part) else editor.pastePlain(text)
    }

    /** Keyboard clipboard/history buttons often paste by commitText rather than menu action. */
    fun pasteCommitted(context: Context,editor: EditorState,text: CharSequence): Boolean {
        // Ordinary typing must not read the system clipboard on every keystroke.
        if(text.toString()!=richText) return false
        val data=context.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        if(text.toString()!=plain(context,data)) return false
        val part=read(context,data,text.toString()) ?: return false
        return editor.pasteFragment(part)
    }

    private fun read(context: Context,data: ClipData,text: String): ClipboardFragment? {
        val token=data.description.extras?.getString(TOKEN)
        if(token!=null && tokens.matches(token)) {
            val dir=File(root(context),token)
            val file=File(dir,"fragment.json")
            if(file.isFile) {
                val saved=runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()
                if(saved?.plain==text && saved.fragment.blocks.isNotEmpty()) {
                    val blocks=saved.fragment.blocks.map { block ->
                        if(block.type!=BlockType.IMAGE || !BackupCodec.validImage(block.image)) block else {
                            val source=File(dir,block.image)
                            if(!source.isFile) block else {
                                val name="${newId()}.${source.extension}"
                                source.copyTo(File(Repository.imagesDir,name))
                                block.copy(image=name)
                            }
                        }
                    }
                    return saved.fragment.copy(blocks=blocks)
                }
            }
        }
        if(data.itemCount!=1) return null
        val item=data.getItemAt(0)
        val spanned=(item.text as? Spanned) ?: item.htmlText?.takeIf { it.length<=500000 }?.let { HtmlCompat.fromHtml(it,HtmlCompat.FROM_HTML_MODE_COMPACT) } ?: return null
        if(spanned.getSpans(0,spanned.length,Any::class.java).isEmpty()) return null
        return fromSpanned(spanned)
    }

    private fun fromSpanned(value: Spanned): ClipboardFragment {
        val text=value.toString().trimEnd('\n')
        var offset=0
        val blocks=text.split('\n').map { line ->
            val from=offset; val to=from+line.length
            val spans=mutableListOf<StyleRange>()
            var type=BlockType.TEXT; var align=Align.START
            value.getSpans(from,to,Any::class.java).forEach { span ->
                val start=(value.getSpanStart(span)-from).coerceIn(0,line.length)
                val end=(value.getSpanEnd(span)-from).coerceIn(0,line.length)
                fun add(style: InlineStyle,color: Long=0,url: String="") { if(start<end) spans+=StyleRange(start,end,style,color,url) }
                when(span) {
                    is StyleSpan -> { if(span.style and android.graphics.Typeface.BOLD!=0) add(InlineStyle.BOLD); if(span.style and android.graphics.Typeface.ITALIC!=0) add(InlineStyle.ITALIC) }
                    is UnderlineSpan -> add(InlineStyle.UNDERLINE)
                    is StrikethroughSpan -> add(InlineStyle.STRIKE)
                    is ForegroundColorSpan -> add(InlineStyle.COLOR,span.foregroundColor.toLong() and 0xffffffffL)
                    is BackgroundColorSpan -> add(InlineStyle.HIGHLIGHT,span.backgroundColor.toLong() and 0xffffffffL)
                    is URLSpan -> add(InlineStyle.LINK,url=span.url)
                    is BulletSpan -> type=BlockType.BULLET
                    is QuoteSpan -> type=BlockType.QUOTE
                    is AlignmentSpan -> align=when(span.alignment) { android.text.Layout.Alignment.ALIGN_CENTER -> Align.CENTER; android.text.Layout.Alignment.ALIGN_OPPOSITE -> Align.END; else -> Align.START }
                    is RelativeSizeSpan -> if(span.sizeChange>=1.4f) type=if(span.sizeChange>=1.8f) BlockType.H1 else BlockType.H2
                }
            }
            offset=to+1
            Block(type=type,text=line,spans=spans,align=align)
        }
        return ClipboardFragment(blocks)
    }

    private fun html(part: ClipboardFragment): String = buildString {
        fun escape(value: String)=Html.escapeHtml(value)
        fun rich(block: Block): String = buildString {
            val boundaries=(listOf(0,block.text.length)+block.spans.flatMap { listOf(it.start,it.end) }).filter { it in 0..block.text.length }.distinct().sorted()
            boundaries.zipWithNext().forEach { (from,to) ->
                val styles=block.spans.filter { it.start<=from && it.end>=to }
                val css=buildString { styles.forEach { when(it.style) {
                    InlineStyle.BOLD -> append("font-weight:bold;")
                    InlineStyle.ITALIC -> append("font-style:italic;")
                    InlineStyle.UNDERLINE,InlineStyle.STRIKE -> {}
                    InlineStyle.COLOR -> append("color:#%06x;".format(it.color and 0xffffff))
                    InlineStyle.HIGHLIGHT -> append("background-color:#%06x;".format(it.color and 0xffffff))
                    else -> {}
                } }; val decoration=styles.mapNotNull { when(it.style) { InlineStyle.UNDERLINE -> "underline"; InlineStyle.STRIKE -> "line-through"; else -> null } }.distinct(); if(decoration.isNotEmpty()) append("text-decoration:").append(decoration.joinToString(" ")).append(';') }
                val link=styles.firstOrNull { it.style==InlineStyle.LINK }?.url?.takeIf { it.startsWith("https:") || it.startsWith("http:") || it.startsWith("mailto:") }
                if(link!=null) append("<a href=\"").append(escape(link)).append("\">")
                append("<span style=\"").append(css).append("\">").append(escape(block.text.substring(from,to))).append("</span>")
                if(link!=null) append("</a>")
            }
        }
        append("<html><body>")
        part.blocks.forEach { block ->
            val tag=when(block.type) { BlockType.H1 -> "h1"; BlockType.H2 -> "h2"; BlockType.H3 -> "h3"; BlockType.QUOTE -> "blockquote"; BlockType.CODE -> "pre"; else -> "p" }
            when(block.type) {
                BlockType.IMAGE -> append("<p>[图片]</p>")
                BlockType.DIVIDER -> append("<hr>")
                BlockType.TABLE -> { append("<table>"); block.table.forEach { row -> append("<tr>"); row.forEach { append("<td>").append(escape(it)).append("</td>") }; append("</tr>") }; append("</table>") }
                else -> {
                    if(block.type==BlockType.NUMBER) append("<ol><li>")
                    if(block.type==BlockType.BULLET) append("<ul><li>")
                    append("<").append(tag).append(" style=\"text-align:").append(when(block.align) { Align.CENTER -> "center"; Align.END -> "right"; else -> "left" })
                        .append(";margin-left:").append(block.indent*24).append("px;\">")
                    if(block.type==BlockType.TODO) append(if(block.checked) "☑ " else "☐ ")
                    append(rich(block)).append("</").append(tag).append(">")
                    if(block.type==BlockType.NUMBER) append("</li></ol>")
                    if(block.type==BlockType.BULLET) append("</li></ul>")
                }
            }
        }
        append("</body></html>")
    }
}
