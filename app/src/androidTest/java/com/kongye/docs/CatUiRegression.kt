package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kongye.docs.ui.home.*
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.KongyeTheme
import java.io.File

object CatUiRegression {
    fun run(instrument: Instrumentation,context: Context,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T {
            var result: Result<T>?=null
            instrument.runOnMainSync { result=runCatching(block) }
            return result!!.getOrThrow()
        }
        val auto=instrument.uiAutomation
        val activity=startTestActivity(instrument,context)
        val wasDark=main { Kongye.dark }
        val density=context.resources.displayMetrics.density
        fun x(value: Float)=(200-CAT_W*density/2+(value-6)*.7f*density).toInt().coerceIn(0,399)
        fun y(value: Float)=(200-CAT_H*density/2+(value-16)*.7f*density).toInt().coerceIn(0,399)
        fun frame(name: String): Bitmap {
            val all=auto.takeScreenshot() ?: error("Cat screenshot failed")
            val crop=Bitmap.createBitmap(all,(all.width-400)/2,(all.height-400)/2,400,400)
            all.recycle()
            File(context.getExternalFilesDir(null),name).outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG,100,it)) }
            return crop
        }
        fun changed(a: Bitmap,b: Bitmap,left: Float,top: Float,right: Float,bottom: Float): Int {
            var count=0
            for(py in y(top)..y(bottom)) for(px in x(left)..x(right)) if(a.getPixel(px,py)!=b.getPixel(px,py)) count++
            return count
        }
        fun cat(): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.contentDescription?.toString()?.startsWith("小猫")==true && node.actionList.any { it.label?.toString()=="扑逗猫棒" }) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.rootInActiveWindow?.let(::walk)
        }
        fun perform(label: String) {
            val node=cat() ?: error("Cat node missing")
            val action=node.actionList.firstOrNull { it.label?.toString()==label } ?: error("Cat action missing: $label")
            check(node.performAction(action.id))
            val deadline=SystemClock.uptimeMillis()+2000
            while(cat()?.contentDescription?.toString()!="小猫，$label" && SystemClock.uptimeMillis()<deadline) Thread.sleep(20)
            check(cat()?.contentDescription?.toString()=="小猫，$label")
        }
        try {
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Kongye.dark=false
                activity.setContent { KongyeTheme { Box(Modifier.fillMaxSize().background(Kongye.Surface),contentAlignment=Alignment.Center) { LineCat(DayPart.NOON,{listOf("中午好")},Modifier.size(CAT_W.dp,CAT_H.dp)) } } }
            }
            Thread.sleep(4800)
            val first=frame("fish-bowl.png")
            Thread.sleep(600)
            val second=frame("fish-bowl-steam.png")
            var fishInk=0
            for(py in y(74f)..y(88f)) for(px in x(10f)..x(26f)) if(android.graphics.Color.red(first.getPixel(px,py))<130) fishInk++
            check(fishInk>30) { "Fish tail above bowl missing" }
            check(changed(first,second,7f,58f,31f,73f)>10) { "Bowl steam is static" }
            first.recycle(); second.recycle()
            passed("fish-tail-above-bowl-and-moving-steam")
            for((label,name) in listOf("扑逗猫棒" to "wand","拨小老鼠" to "mouse")) {
                main { Kongye.dark=false }
                perform(label)
                Thread.sleep(160)
                val early=frame("cat-$name-early.png")
                Thread.sleep(800)
                val active=frame("cat-$name-play.png")
                check(changed(early,active,6f,if(name=="wand") 40f else 86f,31f,if(name=="wand") 78f else 106f)>20) { "$label toy did not move" }
                check(changed(early,active,32f,76f,54f,104f)>20) { "$label paw did not move" }
                early.recycle(); active.recycle()
                main { Kongye.dark=true }
                Thread.sleep(180)
                val dark=frame("cat-$name-dark.png")
                check(android.graphics.Color.red(dark.getPixel(x(80f),y(85f)))<80) { "$label theme fill mismatch" }
                var lightInk=0
                for(py in y(27f)..y(107f)) for(px in x(6f)..x(32f)) if(android.graphics.Color.red(dark.getPixel(px,py))>150) lightInk++
                check(lightInk>30) { "$label SVG strokes invisible in dark theme" }
                dark.recycle()
                passed("$name-svg-toy-and-paw-animate-in-both-themes")
            }
        } finally {
            main { Kongye.dark=wasDark; activity.finish() }
        }
    }
}
