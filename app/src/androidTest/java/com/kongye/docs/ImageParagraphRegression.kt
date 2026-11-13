package com.kongye.docs

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.geometry.Offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File

/** Isolated image-edge and repeated paragraph-tool checks. */
object ImageParagraphRegression {
    fun runChecks(instrument: Instrumentation,pass: (String) -> Unit) {
        fun <T> main(action: () -> T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(action) }; return result!!.getOrThrow() }
        fun waitFor(label: String,condition: () -> Boolean) {
            val until=SystemClock.uptimeMillis()+14000
            while(SystemClock.uptimeMillis()<until) { if(condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val context=instrument.targetContext
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"image-paragraph-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        val auto=instrument.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=33) auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.text?.contains(label)==true || node.contentDescription?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        fun tap(p: Offset) {
            val time=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,p.x,p.y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event,true)); event.recycle(); Thread.sleep(55)
            }
        }
        fun e()=EditorSession.current!!
        fun input(view: View=activity.window.decorView): InputConnection? {
            if(view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
            if(view is ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        fun ime()=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun settle() { waitFor("focus settled") { main { e().pendingFocus==null } }; instrument.waitForIdleSync(); Thread.sleep(160) }
        fun focus(index: Int,pos: Int) { main { e().placeCaret(SelectionPoint(e().blocks[index].id,pos),deferFocus=true) }; settle() }
        fun arrow(key: Int) { main { check(input()!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key))) }; settle() }
        fun textTap(index: Int) {
            val point=main { val b=e().blocks[index]; e().selection.coordinates[b.id]!!.localToRoot(e().layouts[b.id]!!.getCursorRect(2).center) }
            tap(point); settle()
        }
        fun open(title: String,blocks: List<Block>) {
            val doc=main { Repository.createDoc(null,title,DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("document $title") { find(title)!=null && main { e().layouts.isNotEmpty() } }
            waitFor("window focus") { main { activity.hasWindowFocus() } }
            textTap(blocks.indexOfFirst { it.type.isText })
            waitFor("keyboard expanded") { main { ime()>100 } }; Thread.sleep(550)
        }
        fun watch(label: String,visible: Boolean,action: () -> Unit) {
            val frames=mutableListOf<Int>()
            val baseline=main { ime() }
            val frame=object: Choreographer.FrameCallback {
                override fun doFrame(nanos: Long) { frames+=ime(); Choreographer.getInstance().postFrameCallback(this) }
            }
            main { Choreographer.getInstance().postFrameCallback(frame) }
            try { action(); Thread.sleep(300) } finally { main { Choreographer.getInstance().removeFrameCallback(frame) } }
            check(frames.size>3 && frames.all { if(visible) it>baseline*.7f else it<80 }) { "$label changed keyboard: ${frames.minOrNull()}..${frames.maxOrNull()} baseline=$baseline" }
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1500)
            val imageName="${newId()}.png"
            val bitmap=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120,180,110)) }
            File(Repository.imagesDir,imageName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            open("图片前光标检查",listOf(Block(text="图片前段落"),Block(type=BlockType.IMAGE,image=imageName,imageRatio=16f/9f),Block(text="图片后段落")))
            val original=main { e().content() }; val imageId=main { e().blocks[1].id }
            focus(0,main { e().blocks[0].value.text.length })
            watch("image edges",true) {
                arrow(KeyEvent.KEYCODE_DPAD_RIGHT)
                main { check(e().imageCaret==SelectionPoint(imageId,1)); check(e().content()==original && !e().canUndo) }
                arrow(KeyEvent.KEYCODE_DPAD_RIGHT)
                main { check(e().imageCaret==SelectionPoint(imageId,2)); check(e().content()==original) }
                arrow(KeyEvent.KEYCODE_DPAD_RIGHT)
                main { check(e().focusedId==e().blocks[2].id && e().imageCaret==null) }
                arrow(KeyEvent.KEYCODE_DPAD_LEFT)
                main { check(e().imageCaret==SelectionPoint(imageId,2)) }
                arrow(KeyEvent.KEYCODE_DPAD_UP)
                main { check(e().imageCaret==SelectionPoint(imageId,1)) }
            }
            pass("keyboard-arrows-reach-both-image-edges-without-editing-or-closing-keyboard")
            main { val text=input()!!.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)!!; check(text.text.toString()=="图片前段落\n\uFFFC\n图片后段落" && text.selectionStart=="图片前段落\n".length) }
            pass("image-edge-reports-correct-document-offset-to-current-keyboard")
            watch("newline before image",true) { main { check(input()!!.commitText("\n",1)) }; settle() }
            main { check(e().blocks.map { it.type }==listOf(BlockType.TEXT,BlockType.TEXT,BlockType.IMAGE,BlockType.TEXT)); check(e().blocks[1].text.isEmpty() && e().blocks[2].id==imageId); check(e().imageCaret==SelectionPoint(imageId,1)) }
            pass("enter-at-image-front-adds-one-empty-line-and-retains-image-and-caret")
            watch("undo newline",true) { main { e().undo() }; settle(); main { check(e().content()==original && e().imageCaret==SelectionPoint(imageId,1)); e().redo() }; settle() }
            main { check(e().blocks.size==4) }
            pass("image-front-newline-shares-undo-redo-history")
            watch("type before image",true) { main { check(input()!!.commitText("图片说明",1)) }; settle() }
            main { check(e().blocks[2].text=="图片说明" && e().blocks[3].id==imageId && e().imageCaret==null) }
            pass("typing-at-image-edge-creates-text-before-picture-with-keyboard-open")
            main { e().save(); check(Repository.loadDoc(e().docId)==e().content()) }
            pass("image-position-and-added-lines-survive-saving")
            open("首尾图片检查",listOf(Block(type=BlockType.IMAGE,image=imageName,imageRatio=16f/9f),Block(text="中间正文"),Block(type=BlockType.IMAGE,image=imageName,imageRatio=16f/9f)))
            val edges=main { e().content() }
            watch("first-image newline",true) {
                main { check(input()!!.setSelection(0,0)) }; settle()
                main { check(e().imageCaret==SelectionPoint(e().blocks.first().id,1)); check(input()!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))) }; settle()
                main { check(e().blocks.first().type==BlockType.TEXT && e().blocks.first().text.isEmpty() && e().blocks[1].type==BlockType.IMAGE); e().undo() }; settle()
            }
            main { check(e().content()==edges) }
            pass("document-start-image-accepts-absolute-keyboard-position-and-physical-enter-with-undo")
            focus(1,main { e().blocks[1].value.text.length })
            watch("last-image input",true) {
                arrow(KeyEvent.KEYCODE_DPAD_RIGHT); arrow(KeyEvent.KEYCODE_DPAD_RIGHT)
                main { check(e().imageCaret==SelectionPoint(e().blocks.last().id,2)); check(input()!!.commitText("图片后说明",1)) }; settle()
                main { check(e().blocks.last().text=="图片后说明" && e().blocks[2].type==BlockType.IMAGE); e().undo() }; settle()
                main { check(e().content()==edges && e().imageCaret==SelectionPoint(e().blocks.last().id,2)); e().redo() }; settle()
                main { check(e().blocks.last().text=="图片后说明") }
            }
            pass("last-image-end-accepts-text-with-shared-undo-redo-without-closing-keyboard")
            open("长图滚动检查",listOf(Block(text="长图前正文"),Block(type=BlockType.IMAGE,image=imageName,imageRatio=.4f),Block(text="长图下一行正文")))
            fun edgeVisible(before: Boolean)=main {
                val image=e().blocks.first { it.type==BlockType.IMAGE }
                val coordinates=e().selection.coordinates[image.id]!!
                val viewport=e().selection.viewport!!
                val imageTop=coordinates.localToRoot(Offset.Zero).y
                val viewportTop=viewport.localToRoot(Offset.Zero).y
                val y=if(before) imageTop else imageTop+coordinates.size.height
                check(coordinates.size.height>viewport.size.height) { "Fixture image should exceed visible viewport" }
                check(y in viewportTop-2f..viewportTop+viewport.size.height+2f) {
                    "Image ${if(before) "top" else "bottom"}=$y outside viewport $viewportTop..${viewportTop+viewport.size.height}"
                }
            }
            focus(2,1); Thread.sleep(650)
            watch("up from line below tall image",true) { arrow(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(650) }
            edgeVisible(false)
            pass("up-from-next-line-to-tall-image-right-edge-keeps-image-bottom-visible")
            watch("tall image top then bottom",true) {
                arrow(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(650); edgeVisible(true)
                arrow(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(650); edgeVisible(false)
            }
            pass("switching-tall-image-front-back-reveals-the-corresponding-image-edge")
            focus(2,1); Thread.sleep(650)
            watch("left from next line to tall image",true) { arrow(KeyEvent.KEYCODE_DPAD_LEFT); Thread.sleep(650) }
            edgeVisible(false)
            pass("left-from-next-line-to-tall-image-right-edge-keeps-image-bottom-visible")
            open("连续设置序号检查",listOf(Block(text="第一行序号内容"),Block(text="第二行序号内容"),Block(text="第三行序号内容")))
            val density=context.resources.displayMetrics.density
            fun toolPoint(index: Int)=main { val viewport=e().selection.viewport!!; val origin=viewport.localToRoot(Offset.Zero); Offset((26+44*index)*density,origin.y+viewport.size.height+24*density) }
            tap(toolPoint(1))
            waitFor("paragraph panel") { find("段落首行缩进")!=null && main { ime()<80 } }; Thread.sleep(450)
            val panelTop=main { val viewport=e().selection.viewport!!; viewport.localToRoot(Offset.Zero).y+viewport.size.height+48*density }
            val width=main { activity.window.decorView.width.toFloat() }
            val numbered=Offset(16*density+(width-40*density)/1.67f/6f,panelTop+36*density)
            watch("consecutive numbered lines",false) {
                for(index in 0..2) {
                    if(index>0) textTap(index)
                    check(find("段落首行缩进")!=null) { "Paragraph panel closed after tapping line $index" }
                    tap(numbered)
                    waitFor("numbered $index") { main { e().blocks[index].type==BlockType.NUMBER } }
                }
            }
            main { check(e().blocks.map { e().numberIndex(e().blocks.indexOf(it)) }==listOf(1,2,3)) }
            pass("tap-next-line-keeps-number-panel-open-and-applies-consecutive-1-2-3")
            watch("newline inside number panel",false) {
                main { val b=e().blocks.last(); e().placeCaret(SelectionPoint(b.id,b.value.text.length),deferFocus=true) }; settle()
                main { check(input()!!.commitText("\n",1)) }; settle()
                main { check(e().blocks.last().type==BlockType.NUMBER) }
                check(find("段落首行缩进")!=null)
            }
            pass("numbered-newline-preserves-numbering-and-keeps-paragraph-panel")
            tap(toolPoint(1))
            waitFor("explicit keyboard return") { find("段落首行缩进")==null && main { ime()>100 } }
            pass("explicit-tool-toggle-restores-keyboard")
        } catch(error: Throwable) {
            auto.takeScreenshot()?.let { bitmap -> File(context.getExternalFilesDir(null),"image-paragraph-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            throw error
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
