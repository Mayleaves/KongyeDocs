package com.kongye.docs.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.kongye.docs.data.Align
import com.kongye.docs.data.Block
import com.kongye.docs.data.BlockType
import com.kongye.docs.data.DocContent
import com.kongye.docs.data.InlineStyle
import com.kongye.docs.data.Repository
import com.kongye.docs.data.canFirstIndent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把文档画成一张长图，用于“分享为图片”、历史版本和模板预览。
 * 各类块（字号层级、列表符号、复选框、引用条、代码块、图片、表格）沿用编辑器的画法，
 * 整体版式则照石墨导出图：页边距大、正文字号相对图宽更小、行距更松、标题克制。
 */
object DocRenderer {

    /** 编辑器正文左右边距（dp） */
    const val SIDE_DP = 26

    private class Piece(val height: Float, val draw: (Canvas) -> Unit)

    /**
     * 尺寸换算：dp / sp 按 density 换成像素（dp 四舍五入成整像素，行高向上取整，与 Compose 一致）。
     * lineK 把各类块的行高整体放宽；ink 是正文颜色。
     */
    private class Px(val density: Float, val fontScale: Float, val lineK: Float = 1f, val ink: Int = TEXT) {
        fun dp(v: Float) = (v * density).roundToInt().toFloat()
        fun sp(v: Float) = v * density * fontScale
        fun line(v: Float) = ceil(v * density * fontScale * lineK)
    }

    private const val TITLE_INK = 0xFF222222.toInt()
    private const val INK = 0xFF333333.toInt()
    private const val TEXT = 0xFF1F2329.toInt()
    private const val TEXT2 = 0xFF646A73.toInt()
    private const val TEXT3 = 0xFF8F959E.toInt()
    private const val TEXT4 = 0xFFBBBFC4.toInt()
    private const val LINE2 = 0xFFDEE0E3.toInt()
    private const val BG2 = 0xFFF5F6F7.toInt()
    private const val PRIMARY = 0xFF417FF9.toInt()

    /** 每行恰好 height 像素高，文字在行内垂直居中（对应 Compose 的 LineHeightStyle.Center） */
    private class ExactLineHeight(private val height: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val current = fm.descent - fm.ascent
            if (current <= 0) return
            val extra = height - current
            fm.ascent -= extra - extra / 2
            fm.descent += extra / 2
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    }

    private fun paint(size: Float, color: Int, weight: Int = 400, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = when {
            mono -> Typeface.MONOSPACE
            weight >= 700 -> Typeface.DEFAULT_BOLD
            weight > 400 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(Typeface.DEFAULT, weight, false)
            weight > 400 -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, lineHeight: Float, align: Align = Align.START): StaticLayout {
        // 空段落也要占一行，和编辑器里的空行一样高
        val sb = SpannableStringBuilder(if (text.isEmpty()) " " else text)
        sb.setSpan(ExactLineHeight(lineHeight.roundToInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return StaticLayout.Builder.obtain(sb, 0, sb.length, p, width.coerceAtLeast(1))
            .setAlignment(
                when (align) {
                    Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Align.END -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun styled(b: Block): CharSequence {
        val sb = SpannableStringBuilder(b.text)
        b.spans.forEach { r ->
            val st = r.start.coerceIn(0, b.text.length)
            val en = r.end.coerceIn(0, b.text.length)
            if (en <= st) return@forEach
            val spans = when (r.style) {
                InlineStyle.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                InlineStyle.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                InlineStyle.UNDERLINE -> listOf(UnderlineSpan())
                InlineStyle.STRIKE -> listOf(StrikethroughSpan())
                InlineStyle.COLOR -> listOf(ForegroundColorSpan(r.color.toInt()))
                InlineStyle.HIGHLIGHT -> listOf(BackgroundColorSpan(r.color.toInt()))
                InlineStyle.LINK -> listOf(ForegroundColorSpan(PRIMARY), UnderlineSpan())
                // 评论只在编辑器里显示，导出的图片里不画
                InlineStyle.COMMENT -> emptyList()
            }
            spans.forEach { sb.setSpan(it, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (b.type == BlockType.TODO && b.checked && b.text.isNotEmpty()) {
            sb.setSpan(ForegroundColorSpan(TEXT3), 0, b.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StrikethroughSpan(), 0, b.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun numberLabel(blocks: List<Block>, i: Int): String {
        val b = blocks[i]
        var n = 1
        var j = i - 1
        while (j >= 0) {
            val p = blocks[j]
            if (p.type.isList && p.indent > b.indent) { j--; continue }
            if (p.type == BlockType.NUMBER && p.indent == b.indent) { n++; j--; continue }
            break
        }
        return when (b.indent % 3) {
            0 -> "$n."
            1 -> buildString {
                var x = n
                while (x > 0) { x--; insert(0, 'a' + x % 26); x /= 26 }
            } + "."
            else -> roman(n) + "."
        }
    }

    private fun roman(n: Int): String {
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        return buildString { values.forEachIndexed { i, v -> while (x >= v) { append(symbols[i]); x -= v } } }
    }

    // ---------------- 各类块 ----------------

    private fun textPiece(blocks: List<Block>, index: Int, w: Float, px: Px): Piece {
        val b = blocks[index]
        val type = b.type
        // 与 Blocks.kt 的 blockTextStyle 一致：字号 / 行高 / 字重 / 颜色
        val size: Float
        val line: Float
        var weight = 400
        var color = px.ink
        when (type) {
            BlockType.H1 -> { size = 24f; line = 34f; weight = 700 }
            BlockType.H2 -> { size = 20f; line = 30f; weight = 700 }
            BlockType.H3 -> { size = 17f; line = 26f; weight = 600 }
            BlockType.QUOTE -> { size = 16f; line = 26f; color = TEXT2 }
            BlockType.CODE -> { size = 14f; line = 22f }
            else -> { size = 16f; line = 26f }
        }
        val p = paint(px.sp(size), color, weight, mono = type == BlockType.CODE)
        val lineH = px.line(line)
        val (padTop, padBottom) = when (type) {
            BlockType.H1 -> 14f to 4f
            BlockType.H2 -> 12f to 2f
            BlockType.H3 -> 8f to 2f
            BlockType.CODE -> 6f to 6f
            else -> 2f to 2f
        }
        val indent = px.dp(24f * b.indent)
        val labelLayout = if (type == BlockType.NUMBER) layout(numberLabel(blocks, index), paint(px.sp(size), TEXT2), w.toInt(), lineH) else null
        val marker = when (type) {
            BlockType.BULLET -> px.dp(24f)
            BlockType.NUMBER -> max(px.dp(24f), labelLayout!!.getLineWidth(0) + px.dp(6f))
            BlockType.TODO -> px.dp(28f)
            BlockType.QUOTE -> px.dp(14f)
            else -> 0f
        }
        val codeH = if (type == BlockType.CODE) px.dp(12f) else 0f
        val codeV = if (type == BlockType.CODE) px.dp(10f) else 0f
        val textW = (w - indent - marker - codeH * 2).toInt()
        val text = styled(b)
        // 段落首行缩进两个字
        if (b.firstIndent && type.canFirstIndent && text is SpannableStringBuilder && text.isNotEmpty()) {
            text.setSpan(LeadingMarginSpan.Standard((p.textSize * 2).roundToInt(), 0), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val lay = layout(text, p, textW, lineH, b.align)
        val contentH = lay.height + codeV * 2
        return Piece(px.dp(padTop) + contentH + px.dp(padBottom)) { c ->
            c.save()
            c.translate(indent, px.dp(padTop))
            val cy = lineH / 2
            when (type) {
                BlockType.QUOTE -> c.drawRoundRect(0f, 0f, px.dp(3f), contentH, px.dp(1.5f), px.dp(1.5f), fill(LINE2))
                BlockType.BULLET -> when (b.indent % 3) {
                    0 -> c.drawCircle(px.dp(3f), cy, px.dp(3f), fill(px.ink))
                    1 -> c.drawCircle(px.dp(3f), cy, px.dp(2.4f), stroke(px.ink, px.dp(1.2f)))
                    else -> c.drawRect(0f, cy - px.dp(3f), px.dp(6f), cy + px.dp(3f), fill(px.ink))
                }
                BlockType.NUMBER -> labelLayout?.draw(c)
                BlockType.TODO -> drawCheckbox(c, cy, b.checked, px)
                BlockType.CODE -> c.drawRoundRect(0f, 0f, w - indent, contentH, px.dp(6f), px.dp(6f), fill(BG2))
                else -> {}
            }
            c.translate(marker + codeH, codeV)
            lay.draw(c)
            c.restore()
        }
    }

    /** 与编辑器一致：18dp 圆角方框，勾选后蓝底白勾 */
    private fun drawCheckbox(c: Canvas, cy: Float, checked: Boolean, px: Px) {
        val s = px.dp(18f)
        val top = cy - s / 2
        if (checked) {
            c.drawRoundRect(0f, top, s, top + s, px.dp(4f), px.dp(4f), fill(PRIMARY))
            val k = px.dp(14f) / 24f
            val ox = px.dp(2f)
            val oy = top + px.dp(2f)
            val path = Path().apply {
                moveTo(ox + 5f * k, oy + 12.5f * k)
                lineTo(ox + 9.5f * k, oy + 17f * k)
                lineTo(ox + 19f * k, oy + 7.5f * k)
            }
            c.drawPath(path, stroke(Color.WHITE, 2f * k).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
        } else {
            val half = px.dp(0.75f)
            c.drawRoundRect(half, top + half, s - half, top + s - half, px.dp(4f), px.dp(4f), stroke(TEXT4, px.dp(1.5f)))
        }
    }

    private fun imagePiece(b: Block, w: Float, px: Px): Piece {
        val bmp = Repository.loadBitmap(b.image)
        val ratio = (b.imageRatio.takeIf { it > 0f } ?: bmp?.let { it.width.toFloat() / it.height } ?: 1.5f).coerceIn(0.25f, 5f)
        val h = w / ratio
        val pad = px.dp(6f)
        return Piece(pad * 2 + h) { c ->
            c.save()
            val rect = RectF(0f, pad, w, pad + h)
            c.clipPath(Path().apply { addRoundRect(rect, px.dp(6f), px.dp(6f), Path.Direction.CW) })
            if (bmp != null) c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            else c.drawRect(rect, fill(BG2))
            c.restore()
        }
    }

    private fun tablePiece(b: Block, w: Float, px: Px): Piece? {
        val cols = b.table.maxOfOrNull { it.size } ?: return null
        if (cols == 0) return null
        val colW = ((if (cols <= 3) (w - px.dp(2f)) / cols else w / cols)).roundToInt().toFloat()
        val tableW = colW * cols
        val pad = px.dp(8f)
        val lineH = px.line(22f)
        val rows = b.table.mapIndexed { r, row ->
            val p = paint(px.sp(15f), px.ink, weight = if (r == 0) 500 else 400)
            val cells = (0 until cols).map { layout(row.getOrElse(it) { "" }, p, (colW - pad * 2).toInt(), lineH) }
            cells to (cells.maxOf { it.height } + pad * 2)
        }
        val tableH = rows.sumOf { it.second.toDouble() }.toFloat()
        val outer = px.dp(8f)
        return Piece(outer * 2 + tableH) { c ->
            c.save()
            c.translate(0f, outer)
            val radius = px.dp(6f)
            val border = RectF(0f, 0f, tableW, tableH)
            c.save()
            c.clipPath(Path().apply { addRoundRect(border, radius, radius, Path.Direction.CW) })
            var y = 0f
            val line = px.dp(1f)
            rows.forEachIndexed { r, (cells, h) ->
                if (r == 0) c.drawRect(0f, 0f, tableW, h, fill(BG2))
                cells.forEachIndexed { k, l ->
                    c.save()
                    c.translate(k * colW + pad, y + pad)
                    l.draw(c)
                    c.restore()
                    if (k < cols - 1) c.drawRect((k + 1) * colW - line, y, (k + 1) * colW, y + h, fill(LINE2))
                }
                if (r < rows.size - 1) c.drawRect(0f, y + h - line, tableW, y + h, fill(LINE2))
                y += h
            }
            c.restore()
            val half = line / 2
            c.drawRoundRect(half, half, tableW - half, tableH - half, radius, radius, stroke(LINE2, line))
            c.restore()
        }
    }

    // ---------------- 生成整张图 ----------------

    /**
     * 版式按石墨导出图量出的比例（以图片宽度 W 为基准）：
     * 上、左、右边距约 0.081W；行高放宽到约 1.9 倍字号；
     * 标题只比正文大两成，下面一条很淡、几乎通栏的分隔线；正文用柔和的 #333。
     * 标题、正文字号比石墨导出图（16sp 对应 0.031W）大两级，16sp 对应 0.037W，手机上看更舒服；落款仍按 0.034W 的大小。
     * 内容不足一屏时在下方补白，保证图片至少是正方形；有没有落款，底部留白都一样。
     */
    fun renderImage(title: String, doc: DocContent, widthPx: Int, footer: String): Bitmap {
        val width = widthPx.toFloat()
        val px = Px(density = width * 0.037f / 16f, fontScale = 1f, lineK = 1.18f, ink = INK)
        val side = (width * 0.081f).roundToInt().toFloat()
        val w = width - side * 2
        val pieces = mutableListOf<Piece>()

        // 标题区：标题文字顶端对齐上边距；标题下 0.039W 处一条分隔线（两端只留 0.019W），再往下 0.05W 是正文第一行
        val titleSize = px.sp(19.2f)
        val titleLine = ceil(titleSize * 1.5f)
        val titleLayout = layout(title.ifBlank { "无标题" }, paint(titleSize, TITLE_INK, 700), w.toInt(), titleLine)
        val titleSlack = (titleLine - titleSize) / 2
        pieces += Piece(side - titleSlack) {}
        pieces += Piece(titleLayout.height.toFloat()) { titleLayout.draw(it) }
        val gap = width * 0.039f - titleSlack
        val rule = max(1f, (width * 0.003f).roundToInt().toFloat())
        val bodySlack = (px.line(26f) - px.sp(16f)) / 2 + px.dp(2f)
        val inset = width * 0.019f
        pieces += Piece(gap + rule + width * 0.05f - bodySlack) {
            it.drawRect(inset - side, gap, w + side - inset, gap + rule, fill(0xFFF0F0F0.toInt()))
        }

        // 文末的空行不画，底部留白才一致
        val blocks = doc.blocks.dropLastWhile { it.type == BlockType.TEXT && it.text.isBlank() }.ifEmpty { doc.blocks.take(1) }
        blocks.forEachIndexed { i, b ->
            when (b.type) {
                BlockType.DIVIDER -> pieces += Piece(px.dp(30f)) { it.drawRect(0f, px.dp(14.5f), w, px.dp(15.5f), fill(LINE2)) }
                BlockType.IMAGE -> pieces += imagePiece(b, w, px)
                BlockType.TABLE -> tablePiece(b, w, px)?.let { pieces += it }
                else -> pieces += textPiece(doc.blocks, i, w, px)
            }
        }

        // 底部留白约 1.5 倍边距，落款画在其中；没有落款时留白不变
        val footerH = side * 1.5f - bodySlack
        val content = pieces.sumOf { it.height.toDouble() }.toFloat()
        val total = max(content + footerH, width)
        // 特别长的文档整体缩小一点，避免图片过大占用太多内存
        val k = if (total > 16000f) 16000f / total else 1f
        val bmp = Bitmap.createBitmap((widthPx * k).roundToInt(), (total * k).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(k, k)
        var y = 0f
        pieces.forEach { piece ->
            c.save()
            c.translate(side, y)
            piece.draw(c)
            c.restore()
            y += piece.height
        }
        if (footer.isNotBlank()) {
            val foot = paint(11.5f * width * 0.034f / 16f, 0xFFBDBDBD.toInt()).apply { textAlign = Paint.Align.CENTER }
            c.drawText(footer, width / 2f, total - side * 0.6f, foot)
        }
        return bmp
    }
}
