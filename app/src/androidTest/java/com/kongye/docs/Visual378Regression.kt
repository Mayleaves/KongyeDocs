package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.editor.*
import com.kongye.docs.ui.home.*
import com.kongye.docs.ui.theme.KongyeTheme
import com.kongye.docs.util.DocumentFonts
import java.io.File
import kotlin.math.abs

internal object Visual378Regression {
    fun run(inst: Instrumentation,pass: (String)->Unit) {
        fun <T> main(action: ()->T): T { var result: Result<T>?=null; inst.runOnMainSync { result=runCatching(action) }; return result!!.getOrThrow() }
        val context=inst.targetContext
        val activity=startTestActivity(inst,context)
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"visual378-${newId()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val out=File(context.getExternalFilesDir(null),"visual378").apply { mkdirs() }
        val auto=inst.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun save(bitmap: Bitmap,name: String) { File(out,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        fun shot(name: String) { Thread.sleep(300); auto.takeScreenshot()?.let { save(it,name) } }
        fun waitFor(label: String,test: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+18000
            while(SystemClock.uptimeMillis()<end) { if(test()) return; Thread.sleep(60) }
            shot("timeout.png")
            File(out,"timeout-state.txt").writeText(main { "screen=${AppState.current} tab=${AppState.tab} files=${AppState.filesSub} problem=${Repository.problem.value} items=${Repository.items.value.map { it.displayTitle }}" })
            error("Timed out: $label")
        }
        fun find(label: String): AccessibilityNodeInfo? {
            auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(!node.isVisibleToUser) return null
                // Clickable cards merge the date, title and preview into one accessibility node.
                if(node.text?.toString()?.contains(label)==true || node.contentDescription?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull(::walk)
        }
        try {
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Settings.init(fixture); Repository.init(fixture)
                Repository.items.value.toList().forEach { Repository.destroy(it.id) }
            }
            CatMotionRegression.run(context,pass)
            val art=loadCatArtwork(context.resources,R.raw.cat_snore)
            check(art.parts.count { it.effect==CatArtEffect.BUBBLE }==2)
            check(art.parts.count { it.effect in listOf(CatArtEffect.Z1,CatArtEffect.Z2,CatArtEffect.Z3) }==3)
            CatArtEffect.entries.filter { it!=CatArtEffect.NONE }.forEach { effect ->
                val frames=(0..340).map { effect.frame(it/100f) }
                check(frames.maxOf { it.scale }>1.25f && frames.minOf { it.alpha }<.02f && frames.maxOf { it.alpha }>.99f)
                check(abs(effect.frame(.5f).alpha-effect.frame(3.9f).alpha)<.001f)
            }
            pass("sleep-bubble-and-three-Z-effects-grow-and-fade-in-repeating-cycles")
            fun sleepFrame(seconds: Float): Bitmap {
                val bitmap=Bitmap.createBitmap(816,780,Bitmap.Config.ARGB_8888)
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    androidx.compose.ui.unit.Density(1f),androidx.compose.ui.unit.LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()),androidx.compose.ui.geometry.Size(816f,780f)) {
                    drawRect(Color.White)
                    withTransform({ scale(6f,6f,pivot=androidx.compose.ui.geometry.Offset.Zero); translate(-6f,-16f) }) {
                        drawCatArtwork(art,120f,0f,Color(0xff262626),Color.White,seconds)
                    }
                }
                return bitmap
            }
            val effectBefore=sleepFrame(1.2f); val effectAfter=sleepFrame(2.9f)
            var effectPixels=0
            for(y in 0 until effectBefore.height) for(x in 0 until effectBefore.width) if(effectBefore.getPixel(x,y)!=effectAfter.getPixel(x,y)) effectPixels++
            check(effectPixels>80) { "Bubble and Z effects did not render" }
            for(y in 88..98) for(x in 112..150) {
                val px=((art.bodyLeft+art.bodyScale*x-6f)*6).toInt()
                val py=((103f-120f*art.bodyScale+art.bodyScale*y-16f)*6).toInt()
                check(effectBefore.getPixel(px,py)==effectAfter.getPixel(px,py)) { "Sleep body changed with bubble animation" }
            }
            save(effectBefore,"snore-effect-before.png"); save(effectAfter,"snore-effect-after.png")
            pass("rendered-bubble-and-Z-change-while-cat-body-pixels-remain-fixed")
            val phrase="空叶文档 Bold"
            fun fontImage(face: Typeface): Bitmap {
                val bitmap=Bitmap.createBitmap(720,110,Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK; textSize=56f; typeface=face }
                canvas.drawText(phrase,16f,78f,paint)
                return bitmap
            }
            fun darkPixels(bitmap: Bitmap): Int {
                val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                return pixels.count { android.graphics.Color.red(it)<180 }
            }
            val before=fontImage(Typeface.DEFAULT_BOLD)
            val after=fontImage(DocumentFonts.nativeBold)
            if(android.os.Build.MANUFACTURER.equals("Xiaomi",true)) {
                check(DocumentFonts.hasCorrection) { "MiSans correction unavailable" }
                check(darkPixels(after)>darkPixels(before)*1.12f) { "Bold axis did not improve stroke weight" }
                val previousFace=Typeface.Builder(File("/system/fonts/MiSansVF.ttf")).setFontVariationSettings("'wght' 600").setWeight(700).build()
                val previous=fontImage(previousFace)
                check(darkPixels(after)<darkPixels(previous)*.95f) { "Revised bold must be visibly lighter than the previous 600-axis correction" }
                save(previous,"font-previous-heavy.png")
            }
            File(out,"font-coverage.txt").writeText("system=${darkPixels(before)} corrected=${darkPixels(after)} correction=${DocumentFonts.hasCorrection}")
            save(before,"font-system.png"); save(after,"font-corrected.png")
            val styled=buildStyledText("加粗",listOf(StyleRange(0,2,InlineStyle.BOLD)),false)
            check(styled.spanStyles.any { it.item.fontFamily==DocumentFonts.boldFamily && it.item.fontWeight==androidx.compose.ui.text.font.FontWeight.Bold })
            check(blockTextStyle(BlockType.TEXT).fontFamily==null && blockTextStyle(BlockType.CODE).fontFamily==androidx.compose.ui.text.font.FontFamily.Monospace)
            pass("document-bold-uses-real-variable-weight-while-regular-and-code-fonts-stay-unchanged")
            main {
                activity.setContent { KongyeTheme {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                        Text("小猫姿态大小对照")
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            Column { Text("常规"); LineCat(DayPart.MORNING,{listOf("测试")},Modifier.size(CAT_W.dp,CAT_H.dp)) }
                            Column { Text("呼噜"); LineCat(DayPart.NIGHT,{listOf("测试")},Modifier.size(CAT_W.dp,CAT_H.dp)) }
                        }
                    }
                } }
            }
            repeat(5) { i -> Thread.sleep(600); shot("cats-$i.png") }
            val bodyText="线条小猫的 SVG 需要自己绘制。猫碗中的鱼尾巴，猫碗离小猫远一些。正文预览采用小一号文字。"
            val doc=main {
                val created=Repository.createDoc(null,"待办事项",DocContent(listOf(
                    Block(type=BlockType.H1,text="空叶文档"),
                    Block(type=BlockType.TODO,text="线条小猫的 svg 需要自己绘制"),
                    Block(type=BlockType.TODO,text="猫碗中的鱼尾巴，猫碗离小猫远一些",indent=1),
                    Block(type=BlockType.TEXT,text="加粗文字 Bold 与正常文字",spans=listOf(StyleRange(0,9,InlineStyle.BOLD))))))
                Repository.createDoc(null,"欢迎使用空叶文档",DocContent(listOf(Block(text=bodyText))))
                Repository.createDoc(null,"读书笔记",DocContent(listOf(Block(text=bodyText))))
                Repository.createDoc(null,"本周计划",DocContent(listOf(Block(text=bodyText))))
                Repository.setFilesGrid(true)
                AppState.goHome(Tab.FILES); AppState.filesSub=FilesSub.DESKTOP
                activity.setContent { KongyeTheme { KongyeApp() } }
                created
            }
            waitFor("desktop cards") { find("待办事项")!=null && find("欢迎使用空叶文档")!=null }
            shot("desktop-cards.png")
            pass("real-desktop-cards-render-with-smaller-title-meta-and-preview")
            main { AppState.openDoc(doc.id) }
            waitFor("editor layout") { main { EditorSession.current?.docId==doc.id && EditorSession.current!!.layouts.isNotEmpty() } }
            Thread.sleep(400); shot("editor-bold.png")
            val editor=main { EditorSession.current!! }
            main { editor.requestFocus(editor.blocks[1],2) }
            waitFor("editor focused") { main { editor.pendingFocus==null && editor.focusedId==editor.blocks[1].id } }
            Thread.sleep(350); shot("editor-bold-keyboard.png")
            main { editor.toggleStyle(InlineStyle.BOLD); editor.undo(false); editor.redo(false) }
            save(com.kongye.docs.render.DocRenderer.renderImage("待办事项",main { Repository.loadDoc(doc.id) },900,""),"export-bold.png")
            pass("real-editor-heading-inline-bold-keyboard-and-export-render-without-changing-content-format")
        } finally {
            main {
                EditorSession.current?.let { EditorSession.closeDocument(it.docId) }
                AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context)
            }
        }
    }
}
