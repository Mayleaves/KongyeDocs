package com.kongye.docs.ui.home

import android.content.res.Resources
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Keep the supplied vectors intact, including their eye clips and dashed cord. */
internal class CatArtwork internal constructor(
    internal val parts: List<CatArtPart>,
    internal val bodyScale: Float = .6f,
    internal val bodyLeft: Float = 11f,
)
internal data class CatArtPart(val path: Path, val fill: String, val stroke: String, val width: Float,
    val clip: Path?, val dash: FloatArray?, val motion: CatArtMotion = CatArtMotion.STILL,
    val role: String = "",val effect: CatArtEffect = CatArtEffect.NONE)

internal data class CatEffectFrame(val scale: Float,val alpha: Float)
internal enum class CatArtEffect(val x: Float,val y: Float,private val delay: Float=0f) {
    NONE(0f,0f), BUBBLE(83f,91f), Z1(141.5f,58f,.25f), Z2(154.5f,43f,.65f), Z3(170.5f,26f,1.05f);
    fun frame(seconds: Float): CatEffectFrame {
        if(this==NONE) return CatEffectFrame(1f,1f)
        val p=(((seconds-delay)%3.4f+3.4f)%3.4f)/3.4f
        val alpha=when { p<.1f -> p/.1f; p>.65f -> (1f-p)/.35f; else -> 1f }
        return CatEffectFrame(if(this==BUBBLE) .6f+1.1f*p else .75f+.55f*p,alpha.coerceIn(0f,1f))
    }
}

/** Only local rigid rotations: the body, size and ground position never animate. */
internal enum class CatArtMotion(val degrees: Float, val x: Float, val y: Float) {
    STILL(0f,0f,0f),
    SLEEP_TAIL(3f,164f,108f),
    SLEEP_LEFT_PAW(3f,92f,120f),
    SLEEP_RIGHT_PAW(-3f,98f,120f),
    SLEEP_BUBBLE(0f,83f,91f),
    WAND_PAW(-12f,73f,98f),
    WAND_ROD(3.5f,10f,16f),
    WAND_LURE(10f,76f,24f),
    MOUSE_TOY(5f,40f,125f),
    MOUSE_PAW(-7f,70f,120f);

    fun matrix(wave: Float): Matrix {
        val phase=wave.coerceIn(-1f,1f)
        val local=Matrix().apply { setRotate(degrees*phase,x,y) }
        // Cord and fish share one pivot at the rod tip and follow the rod rotation.
        return if(this==WAND_LURE) Matrix().apply { setConcat(WAND_ROD.matrix(phase),local) } else local
    }
}

internal fun loadCatArtwork(resources: Resources, id: Int): CatArtwork {
    val root=resources.openRawResource(id).use {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }
    fun children(e: Element)= (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
    fun number(e: Element,name: String)=e.getAttribute(name).toFloatOrNull() ?: 0f
    fun shape(e: Element): Path? = when(e.tagName) {
        "path" -> PathParser.createPathFromPathData(e.getAttribute("d"))
        "line" -> Path().apply { moveTo(number(e,"x1"),number(e,"y1")); lineTo(number(e,"x2"),number(e,"y2")) }
        "circle", "ellipse" -> {
            val cx=number(e,"cx"); val cy=number(e,"cy")
            val rx=number(e,if(e.tagName=="circle") "r" else "rx")
            val ry=number(e,if(e.tagName=="circle") "r" else "ry")
            Path().apply { addOval(RectF(cx-rx,cy-ry,cx+rx,cy+ry),Path.Direction.CW) }
        }
        else -> null
    }
    fun transform(e: Element,parent: Matrix): Matrix {
        val value=e.getAttribute("transform")
        val local=Matrix()
        // These three artwork files use rotations with an explicit pivot.
        if(value.isNotEmpty()) {
            val match=requireNotNull(Regex("rotate\\(([^)]+)\\)").matchEntire(value)) { "Unsupported cat SVG transform: $value" }
            val v=match.groupValues[1].split(Regex("[ ,]+" )).map { it.toFloat() }
            local.setRotate(v[0],v.getOrElse(1){0f},v.getOrElse(2){0f})
        }
        return Matrix().apply { setConcat(parent,local) }
    }
    val clips=mutableMapOf<String,Path>()
    val clipNodes=root.getElementsByTagName("clipPath")
    for(i in 0 until clipNodes.length) {
        val e=clipNodes.item(i) as Element
        val combined=Path()
        children(e).forEach { child -> shape(child)?.let { p -> p.transform(transform(child,Matrix())); combined.addPath(p) } }
        clips[e.getAttribute("id")]=combined
    }
    val parts=mutableListOf<CatArtPart>()
    fun visit(e: Element,parentStyle: Map<String,String>,parentMatrix: Matrix,parentClip: Path?) {
        if(e.tagName in listOf("defs","clipPath")) return
        val style=parentStyle.toMutableMap()
        for(name in listOf("fill","stroke","stroke-width","stroke-dasharray"))
            if(e.hasAttribute(name)) style[name]=e.getAttribute(name)
        val matrix=transform(e,parentMatrix)
        val clip=if(e.hasAttribute("clip-path")) {
            val key=e.getAttribute("clip-path").removePrefix("url(#").removeSuffix(")")
            Path(clips.getValue(key)).apply { transform(matrix) }
        } else parentClip
        shape(e)?.let { path ->
            path.transform(matrix)
            val dash=style["stroke-dasharray"]?.split(Regex("[ ,]+"))?.map { it.toFloat() }?.toFloatArray()
            parts+=CatArtPart(path,style["fill"] ?: "#000000",style["stroke"] ?: "none",
                style["stroke-width"]?.toFloat() ?: 1f,clip,dash,
                e.getAttribute("data-motion").takeIf { it.isNotEmpty() }?.let(CatArtMotion::valueOf) ?: CatArtMotion.STILL,
                e.getAttribute("data-role"),e.getAttribute("data-effect").takeIf { it.isNotEmpty() }?.let(CatArtEffect::valueOf) ?: CatArtEffect.NONE)
        }
        children(e).forEach { visit(it,style,matrix,clip) }
    }
    visit(root,emptyMap(),Matrix(),null)
    require(parts.isNotEmpty()) { "Cat SVG contains no artwork" }
    // Normalize the cat itself, rather than fitting a canvas that also contains toys.
    // The regular pose in LineCat spans y=39..103 (64 units), with its left at x=30.
    if(id in listOf(com.kongye.docs.R.raw.cat_wand,com.kongye.docs.R.raw.cat_mouse,com.kongye.docs.R.raw.cat_snore)) {
        val body=parts.single { it.role=="body" }
        val bounds=RectF().also { body.path.computeBounds(it,true) }
        val scale=64f/bounds.height()
        return CatArtwork(parts,scale,30f-bounds.left*scale)
    }
    return CatArtwork(parts)
}

internal fun DrawScope.drawCatArtwork(art: CatArtwork, ground: Float, wave: Float, ink: Color, background: Color,seconds: Float=0f) {
    // 240×160 source: paws at y=120 (sleep/wand) or y=125 (mouse).
    // Keep the source proportions and the same paw baseline as the regular pose.
    // Toys may extend to the left of the cat's canvas; they don't shrink the cat.
    translate(left=art.bodyLeft,top=103f-ground*art.bodyScale) {
        scale(art.bodyScale,art.bodyScale,pivot=Offset.Zero) {
                drawIntoCanvas { canvas ->
                    val native=canvas.nativeCanvas
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
                    fun color(value: String)=if(value.equals("#ffffff",true) || value=="white") background.toArgb() else ink.toArgb()
                    val motion=art.parts.map { it.motion }.distinct().associateWith { it.matrix(wave) }
                    art.parts.forEach { part ->
                        val save=native.save()
                        native.concat(motion.getValue(part.motion))
                        val effect=part.effect.frame(seconds)
                        native.scale(effect.scale,effect.scale,part.effect.x,part.effect.y)
                        part.clip?.let { native.clipPath(it) }
                        if(part.fill!="none") {
                            paint.style=Paint.Style.FILL; paint.pathEffect=null; paint.color=color(part.fill)
                            paint.alpha=(paint.alpha*effect.alpha).toInt()
                            native.drawPath(part.path,paint)
                        }
                        if(part.stroke!="none") {
                            paint.style=Paint.Style.STROKE; paint.color=color(part.stroke); paint.strokeWidth=part.width
                            paint.alpha=(paint.alpha*effect.alpha).toInt()
                            paint.pathEffect=part.dash?.let { DashPathEffect(it,0f) }
                            native.drawPath(part.path,paint)
                        }
                        native.restoreToCount(save)
                    }
                }
        }
    }
}
