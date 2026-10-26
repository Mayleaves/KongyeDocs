package com.moji.notes.data

import android.text.Html

/** 文档与 Markdown / HTML / 纯文本之间的转换 */
object Converter {

    // ---------- 导出 ----------

    fun toMarkdown(title: String, doc: DocContent): String = buildString {
        if (title.isNotBlank()) append("# ").append(title.trim()).append("\n\n")
        var number = 0
        doc.blocks.forEachIndexed { i, b ->
            number = if (b.type == BlockType.NUMBER) number + 1 else 0
            val pad = "  ".repeat(b.indent)
            val text = inlineMarkdown(b)
            val line = when (b.type) {
                BlockType.H1 -> "## $text"
                BlockType.H2 -> "### $text"
                BlockType.H3 -> "#### $text"
                BlockType.BULLET -> "$pad- $text"
                BlockType.NUMBER -> "$pad$number. $text"
                BlockType.TODO -> "$pad- [${if (b.checked) "x" else " "}] $text"
                BlockType.QUOTE -> "> $text"
                BlockType.CODE -> "```\n${b.text}\n```"
                BlockType.DIVIDER -> "---"
                BlockType.IMAGE -> "![图片](${b.image})"
                BlockType.TABLE -> tableMarkdown(b.table)
                // 首行缩进用两个全角空格表示，Markdown 渲染时不会被吞掉
                BlockType.TEXT -> if (b.firstIndent) "　　$text" else text
            }
            append(line)
            val next = doc.blocks.getOrNull(i + 1)
            val tight = next != null && b.type.isList && next.type.isList
            append(if (tight) "\n" else "\n\n")
        }
    }.trimEnd() + "\n"

    private fun tableMarkdown(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val cols = rows.maxOf { it.size }
        fun row(r: List<String>) = "| " + (0 until cols).joinToString(" | ") { r.getOrElse(it) { "" }.replace("|", "\\|").replace("\n", " ") } + " |"
        return (listOf(row(rows[0]), "|" + " --- |".repeat(cols)) + rows.drop(1).map(::row)).joinToString("\n")
    }

    private fun inlineMarkdown(b: Block): String {
        if (b.spans.isEmpty()) return b.text
        val opens = HashMap<Int, StringBuilder>()
        val closes = HashMap<Int, StringBuilder>()
        b.spans.filter { it.end > it.start }.sortedBy { it.start }.forEach { s ->
            val (o, c) = when (s.style) {
                InlineStyle.BOLD -> "**" to "**"
                InlineStyle.ITALIC -> "*" to "*"
                InlineStyle.STRIKE -> "~~" to "~~"
                InlineStyle.UNDERLINE -> "<u>" to "</u>"
                InlineStyle.LINK -> "[" to "](${s.url})"
                InlineStyle.HIGHLIGHT -> "==" to "=="
                InlineStyle.COLOR, InlineStyle.COMMENT -> "" to ""
            }
            opens.getOrPut(s.start) { StringBuilder() }.append(o)
            closes.getOrPut(s.end) { StringBuilder() }.insert(0, c)
        }
        return buildString {
            for (i in 0..b.text.length) {
                closes[i]?.let { append(it) }
                opens[i]?.let { append(it) }
                if (i < b.text.length) append(b.text[i])
            }
        }
    }

    fun toHtml(title: String, doc: DocContent, imageSrc: (String) -> String = { it }): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title>")
        append("<style>body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;max-width:760px;margin:40px auto;padding:0 20px;color:#1F2329;line-height:1.75}")
        append("blockquote{border-left:3px solid #DEE0E3;margin:0;padding-left:12px;color:#646A73}pre{background:#F5F6F7;padding:12px;border-radius:6px;overflow:auto}")
        append("table{border-collapse:collapse}td,th{border:1px solid #DEE0E3;padding:6px 10px}img{max-width:100%;border-radius:6px}hr{border:0;border-top:1px solid #DEE0E3}</style></head><body>")
        if (title.isNotBlank()) append("<h1>").append(esc(title)).append("</h1>")
        var openList: String? = null
        fun closeList() { openList?.let { append("</$it>") }; openList = null }
        doc.blocks.forEach { b ->
            val listTag = when (b.type) { BlockType.BULLET, BlockType.TODO -> "ul"; BlockType.NUMBER -> "ol"; else -> null }
            if (listTag != openList) { closeList(); listTag?.let { append("<$it>"); openList = it } }
            val text = inlineHtml(b)
            val indentCss = if (b.firstIndent && b.type.canFirstIndent) "text-indent:2em;" else ""
            val style = when (b.align) { Align.CENTER -> "text-align:center;"; Align.END -> "text-align:right;"; else -> "" }
                .let { (it + indentCss).let { css -> if (css.isEmpty()) "" else " style=\"$css\"" } }
            when (b.type) {
                BlockType.H1 -> append("<h2$style>$text</h2>")
                BlockType.H2 -> append("<h3$style>$text</h3>")
                BlockType.H3 -> append("<h4$style>$text</h4>")
                BlockType.BULLET, BlockType.NUMBER -> append("<li style=\"margin-left:${b.indent * 24}px\">$text</li>")
                BlockType.TODO -> append("<li style=\"list-style:none;margin-left:${b.indent * 24}px\">${if (b.checked) "☑" else "☐"} $text</li>")
                BlockType.QUOTE -> append("<blockquote>$text</blockquote>")
                BlockType.CODE -> append("<pre><code>").append(esc(b.text)).append("</code></pre>")
                BlockType.DIVIDER -> append("<hr>")
                BlockType.IMAGE -> append("<p><img src=\"").append(imageSrc(b.image)).append("\"></p>")
                BlockType.TABLE -> {
                    append("<table>")
                    b.table.forEachIndexed { r, row ->
                        append("<tr>"); row.forEach { append(if (r == 0) "<th>" else "<td>").append(esc(it)).append(if (r == 0) "</th>" else "</td>") }; append("</tr>")
                    }
                    append("</table>")
                }
                BlockType.TEXT -> append("<p$style>").append(text.ifEmpty { "<br>" }).append("</p>")
            }
        }
        closeList()
        append("</body></html>")
    }

    private fun inlineHtml(b: Block): String {
        val opens = HashMap<Int, StringBuilder>()
        val closes = HashMap<Int, StringBuilder>()
        b.spans.filter { it.end > it.start }.sortedBy { it.start }.forEach { s ->
            val color = "#%06X".format(s.color and 0xFFFFFF)
            val (o, c) = when (s.style) {
                InlineStyle.BOLD -> "<b>" to "</b>"
                InlineStyle.ITALIC -> "<i>" to "</i>"
                InlineStyle.STRIKE -> "<s>" to "</s>"
                InlineStyle.UNDERLINE -> "<u>" to "</u>"
                InlineStyle.LINK -> "<a href=\"${esc(s.url)}\">" to "</a>"
                InlineStyle.HIGHLIGHT -> "<span style=\"background:$color\">" to "</span>"
                InlineStyle.COLOR -> "<span style=\"color:$color\">" to "</span>"
                InlineStyle.COMMENT -> "" to ""
            }
            opens.getOrPut(s.start) { StringBuilder() }.append(o)
            closes.getOrPut(s.end) { StringBuilder() }.insert(0, c)
        }
        return buildString {
            for (i in 0..b.text.length) {
                closes[i]?.let { append(it) }
                opens[i]?.let { append(it) }
                if (i < b.text.length) append(esc(b.text[i].toString()))
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------- 导入 ----------

    /** 返回 (标题, 正文)。若首行是一级标题则作为文档标题 */
    fun fromMarkdown(source: String, fallbackTitle: String): Pair<String, DocContent> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<Block>()
        var title = fallbackTitle
        var i = 0
        if (lines.isNotEmpty() && lines[0].startsWith("# ")) { title = lines[0].removePrefix("# ").trim(); i = 1 }
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val indent = (raw.length - raw.trimStart().length) / 2
            val t = line.trimStart()
            when {
                t.startsWith("```") -> {
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    blocks += Block(type = BlockType.CODE, text = code.joinToString("\n"))
                }
                t.isEmpty() -> {}
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> blocks += Block(type = BlockType.DIVIDER)
                t.startsWith("|") && lines.getOrNull(i + 1)?.trim()?.matches(Regex("^\\|?\\s*:?-+.*")) == true -> {
                    val rows = mutableListOf(splitRow(t))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) rows += splitRow(lines[i++].trim())
                    i--
                    blocks += Block(type = BlockType.TABLE, table = rows)
                }
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length
                    val text = t.drop(level).trim()
                    val type = when (level) { 1, 2 -> BlockType.H1; 3 -> BlockType.H2; else -> BlockType.H3 }
                    blocks += inline(text, type)
                }
                t.startsWith(">") -> blocks += inline(t.removePrefix(">").trim(), BlockType.QUOTE)
                Regex("^[-*+] \\[( |x|X)] ").containsMatchIn(t) ->
                    blocks += inline(t.substring(6), BlockType.TODO, indent).copy(checked = t[3] != ' ')
                Regex("^[-*+] ").containsMatchIn(t) -> blocks += inline(t.substring(2), BlockType.BULLET, indent)
                Regex("^\\d+[.)] ").containsMatchIn(t) -> blocks += inline(t.substringAfter(' '), BlockType.NUMBER, indent)
                else -> blocks += inline(t, BlockType.TEXT)
            }
            i++
        }
        return title to DocContent(blocks.ifEmpty { listOf(Block()) })
    }

    private fun splitRow(line: String) = line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }

    private val inlinePattern = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|==(.+?)==|\\[([^\\]]+)]\\(([^)]+)\\)|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)")

    private fun inline(src: String, type: BlockType, indent: Int = 0): Block {
        val sb = StringBuilder()
        val spans = mutableListOf<StyleRange>()
        var last = 0
        inlinePattern.findAll(src).forEach { m ->
            sb.append(src, last, m.range.first)
            val start = sb.length
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> { sb.append(g[1] + g[2]); spans += StyleRange(start, sb.length, InlineStyle.BOLD) }
                g[3].isNotEmpty() -> { sb.append(g[3]); spans += StyleRange(start, sb.length, InlineStyle.STRIKE) }
                g[4].isNotEmpty() -> { sb.append(g[4]); spans += StyleRange(start, sb.length, InlineStyle.HIGHLIGHT, 0xFFF8E6AB) }
                g[5].isNotEmpty() -> { sb.append(g[5]); spans += StyleRange(start, sb.length, InlineStyle.LINK, url = g[6]) }
                g[7].isNotEmpty() -> { sb.append(g[7]); spans += StyleRange(start, sb.length, InlineStyle.COLOR, 0xFFF54A45) }
                g[8].isNotEmpty() -> { sb.append(g[8]); spans += StyleRange(start, sb.length, InlineStyle.ITALIC) }
            }
            last = m.range.last + 1
        }
        sb.append(src.substring(last))
        return Block(type = type, text = sb.toString(), spans = spans, indent = indent.coerceIn(0, 4))
    }

    fun fromPlainText(source: String): DocContent {
        val paras = source.replace("\r\n", "\n").split('\n').map { Block(text = it.trimEnd()) }
        return DocContent(paras.ifEmpty { listOf(Block()) })
    }

    fun fromHtml(source: String): DocContent {
        val text = Html.fromHtml(source, Html.FROM_HTML_MODE_COMPACT).toString()
        return fromPlainText(text.replace(Regex("\n{3,}"), "\n\n").trim())
    }
}
