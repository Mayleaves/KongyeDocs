package com.kongye.docs.ui.home

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import com.kongye.docs.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.PI
import kotlin.math.sin

/** Named SVG layers keep the fish behind the bowl and let the toys move independently. */
internal class CatProps(private val paths: Map<String,Path>) {
    operator fun get(name: String): Path=paths.getValue(name)
}

internal fun loadCatProps(resources: Resources): CatProps {
    val paths=mutableMapOf<String,Path>()
    val parser=android.util.Xml.newPullParser()
    resources.openRawResource(R.raw.cat_props).use { input ->
        parser.setInput(input,"UTF-8")
        while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
            if(parser.eventType==XmlPullParser.START_TAG && parser.name=="path") {
                val id=requireNotNull(parser.getAttributeValue(null,"id"))
                paths[id]=PathParser().parsePathString(requireNotNull(parser.getAttributeValue(null,"d"))).toPath()
            }
            parser.next()
        }
    }
    return CatProps(paths)
}

private fun propStroke(width: Float=2f)=Stroke(width,cap=StrokeCap.Round,join=StrokeJoin.Round)

internal fun DrawScope.drawFishBowl(bowl: Path,props: CatProps,ink: Color,background: Color) {
    drawPath(bowl,ink,style=propStroke(2.1f))
    drawPath(props["fish"],background)
    drawPath(props["fish"],ink,style=propStroke(1.7f))
    drawPath(props["fishDetail"],ink,style=propStroke(1.3f))
    drawPath(props["bowlFront"],background)
    drawPath(props["bowlFront"],ink,style=propStroke(2.1f))
}

internal fun DrawScope.drawCatPlay(props: CatProps,wand: Boolean,progress: Float,ink: Color,background: Color) {
    val wave=sin(progress*PI.toFloat())
    val oscillation=sin(progress*4f*PI.toFloat())
    val opacity=minOf(1f,progress*8f,(1f-progress)*8f).coerceIn(0f,1f)
    val color=ink.copy(alpha=opacity)
    if(wand) {
        drawPath(props["wand"],color,style=propStroke())
        rotate(oscillation*14f*wave,pivot=Offset(27f,44f)) {
            drawPath(props["cord"],color,style=propStroke(1.4f))
            drawPath(props["feather"],background)
            drawPath(props["feather"],color,style=propStroke(1.6f))
            drawPath(props["featherDetail"],color,style=propStroke(1.3f))
        }
    } else {
        // The mouse scoots after each paw tap and settles back on the ground.
        translate(left=oscillation*2.6f*wave,top=-kotlin.math.abs(oscillation)*1.8f*wave) {
            drawPath(props["mouseTail"],color,style=propStroke(1.5f))
            drawPath(props["mouse"],background)
            drawPath(props["mouse"],color,style=propStroke(1.8f))
            drawPath(props["mouseDetail"],color,style=propStroke(1.3f))
        }
    }
    drawPath(props["leftPaw"],ink.copy(alpha=1f-wave),style=propStroke())
    scale(wave,wave,pivot=Offset(49f,103f)) {
        rotate(if(wand) oscillation*13f else -35f+oscillation*15f,pivot=Offset(45f,95f)) {
            drawPath(props["playPaw"],background)
            drawPath(props["playPaw"],ink,style=propStroke())
            drawPath(props["pawToes"],ink,style=propStroke(1.2f))
        }
    }
}
