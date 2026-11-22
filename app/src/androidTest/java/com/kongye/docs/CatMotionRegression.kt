package com.kongye.docs

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kongye.docs.ui.home.*
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

internal object CatMotionRegression {
    fun run(context: Context, pass: (String) -> Unit) {
        for(motion in CatArtMotion.entries) for(phase in listOf(-1f,-.5f,0f,.5f,1f)) {
            val matrix=motion.matrix(phase)
            val points=floatArrayOf(0f,0f,10f,0f,0f,10f)
            matrix.mapPoints(points)
            check(abs(hypot(points[2]-points[0],points[3]-points[1])-10f)<.001f)
            check(abs(hypot(points[4]-points[0],points[5]-points[1])-10f)<.001f)
            check(abs((points[2]-points[0])*(points[4]-points[0])+(points[3]-points[1])*(points[5]-points[1]))<.001f)
            if(motion==CatArtMotion.STILL) check(matrix.isIdentity)
            val anchor=floatArrayOf(motion.x,motion.y)
            matrix.mapPoints(anchor)
            val expected=floatArrayOf(motion.x,motion.y)
            if(motion==CatArtMotion.WAND_LURE) CatArtMotion.WAND_ROD.matrix(phase).mapPoints(expected)
            check(abs(anchor[0]-expected[0])<.001f && abs(anchor[1]-expected[1])<.001f)
        }
        pass("local-motion-is-rigid-rotation-with-fixed-pivots-and-connected-wand-tip")
        val folder=File(context.getExternalFilesDir(null),"cat-motion-360").apply { mkdirs() }
        for((id,name,ground) in listOf(Triple(R.raw.cat_snore,"snore",120f),Triple(R.raw.cat_wand,"wand",120f),Triple(R.raw.cat_mouse,"mouse",125f))) {
            val art=loadCatArtwork(context.resources,id)
            check(art.parts.any { it.motion!=CatArtMotion.STILL })
            fun frame(phase: Float): Bitmap {
                val bmp=Bitmap.createBitmap(816,780,Bitmap.Config.ARGB_8888)
                CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,Canvas(bmp.asImageBitmap()),Size(816f,780f)) {
                    drawRect(Color.White)
                    withTransform({ scale(6f,6f,pivot=Offset.Zero); translate(-6f,-16f) }) {
                        drawCatArtwork(art,ground,phase,Color(0xff262626),Color.White)
                    }
                }
                return bmp
            }
            val frames=listOf(-1f,0f,1f).map(::frame)
            try {
                frames.forEachIndexed { index,bmp -> File(folder,"$name-$index.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) } }
                val before=IntArray(816*780); val after=IntArray(before.size)
                frames[0].getPixels(before,0,816,0,0,816,780)
                frames[2].getPixels(after,0,816,0,0,816,780)
                check(before.indices.count { before[it]!=after[it] }>150) { "$name has no visible local movement" }
                // This region includes the back outline, away from paws, tail and toys.
                for(y in 88..99) for(x in 108..155) {
                    val px=((art.bodyLeft+art.bodyScale*x-6f)*6).toInt()
                    val py=((103f-ground*art.bodyScale+art.bodyScale*y-16f)*6).toInt()
                    check(before[py*816+px]==after[py*816+px]) { "$name body moved at $x,$y" }
                }
            } finally { frames.forEach { it.recycle() } }
        }
        pass("three-cat-poses-show-local-motion-while-body-pixels-stay-fixed")
        // Compare actual SVG geometry against the regular pose, excluding the toys.
        for(id in listOf(R.raw.cat_wand,R.raw.cat_mouse)) {
            val art=loadCatArtwork(context.resources,id)
            val body=art.parts.single { it.role=="body" }
            val bounds=android.graphics.RectF().also { body.path.computeBounds(it,true) }
            check(abs(bounds.height()*art.bodyScale-64f)<.001f)
            check(abs(bounds.left*art.bodyScale+art.bodyLeft-30f)<.001f)
            val ground=if(id==R.raw.cat_wand) 120f else 125f
            check(abs(103f-ground*art.bodyScale+bounds.top*art.bodyScale-39f)<.001f)
        }
        pass("wand-and-mouse-cat-body-match-regular-pose-height-position-and-paw-baseline")
    }
}
