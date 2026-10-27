package com.kongye.docs.ui.home

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import com.kongye.docs.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.PI
import kotlin.math.sin

internal data class YawnPart(val path: Path,val filled: Boolean,val strokeWidth: Float,val mouth: Boolean)

/** Read the supplied SVG's paths, lines, polylines and class styles without rasterizing it. */
internal fun loadYawnSvg(resources: Resources): List<YawnPart> {
    val parser=android.util.Xml.newPullParser()
    val styles=mutableMapOf<String,MutableMap<String,String>>()
    val parts=mutableListOf<YawnPart>()
    fun number(name: String)=parser.getAttributeValue(null,name)?.toFloatOrNull() ?: 0f
    resources.openRawResource(R.raw.cat_yawn).use { input ->
        parser.setInput(input,"UTF-8")
        while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
            if(parser.eventType==XmlPullParser.START_TAG) {
                if(parser.name=="style") {
                    val css=parser.nextText()
                    Regex("([^{}]+)\\{([^{}]*)\\}").findAll(css).forEach { rule ->
                        val properties=rule.groupValues[2].split(';').mapNotNull { declaration ->
                            val pair=declaration.split(':',limit=2)
                            if(pair.size==2) pair[0].trim() to pair[1].trim() else null
                        }.toMap()
                        rule.groupValues[1].split(',').forEach { selector ->
                            styles.getOrPut(selector.trim().removePrefix(".")) { mutableMapOf() }.putAll(properties)
                        }
                    }
                } else {
                    val path=when(parser.name) {
                        "path" -> parser.getAttributeValue(null,"d")?.let { PathParser().parsePathString(it).toPath() }
                        "line" -> Path().apply { moveTo(number("x1"),number("y1")); lineTo(number("x2"),number("y2")) }
                        "polyline","polygon" -> {
                            val points=Regex("[-+]?(?:\\d*\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?")
                                .findAll(parser.getAttributeValue(null,"points").orEmpty()).map { it.value.toFloat() }.toList()
                            Path().apply {
                                if(points.size>=4) { moveTo(points[0],points[1]); for(i in 2 until points.size-1 step 2) lineTo(points[i],points[i+1]); if(parser.name=="polygon") close() }
                            }
                        }
                        else -> null
                    }
                    if(path!=null && !path.isEmpty) {
                        val bounds=path.getBounds()
                        // Illustrator's off-canvas draft is outside the SVG viewBox.
                        if(bounds.right>=0f && bounds.left<=150f && bounds.bottom>=0f && bounds.top<=150f) {
                            val style=mutableMapOf<String,String>()
                            parser.getAttributeValue(null,"class").orEmpty().split(Regex("\\s+")).forEach { styles[it]?.let(style::putAll) }
                            val fill=parser.getAttributeValue(null,"fill") ?: style["fill"] ?: "none"
                            val width=(parser.getAttributeValue(null,"stroke-width") ?: style["stroke-width"] ?: "1").removeSuffix("px").toFloat()
                            val mouth=bounds.left>=40f && bounds.right<=65f && bounds.top>=49f && bounds.bottom<=68f
                            parts+=YawnPart(path,fill!="none",width,mouth)
                        }
                    }
                }
            }
            parser.next()
        }
    }
    require(parts.isNotEmpty()) { "打呵欠 SVG 没有可绘制内容" }
    return parts
}

internal fun DrawScope.drawYawnSvg(parts: List<YawnPart>,progress: Float,ink: Color,background: Color) {
    val wave=sin(progress.coerceIn(0f,1f)*PI.toFloat())
    // The supplied pose rests at y=92.27; align its paws with the other poses at y=103.
    translate(top=103f-92.27f) {
        scale(1f+0.02f*wave,1f+0.025f*wave,pivot=Offset(75f,92.27f)) {
            clipRect(0f,0f,150f,150f) {
                parts.forEach { part ->
                    fun DrawScope.drawPart() {
                        if(part.filled) drawPath(part.path,background)
                        drawPath(part.path,ink,style=Stroke(part.strokeWidth,cap=StrokeCap.Round,join=StrokeJoin.Round))
                    }
                    if(part.mouth) scale(1f,0.18f+0.82f*wave,pivot=Offset(55f,51f)) { drawPart() }
                    else drawPart()
                }
            }
        }
    }
}
