package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.layout.boundsInWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.EditorSession
import java.io.File

object CommentHandoffRegression {
    fun run(instrument: Instrumentation,context: Context,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(block) }; return result!!.getOrThrow() }
        fun waitFor(label: String,condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+12000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(40) }; error("Timed out: $label")
        }
        val auto=instrument.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.text?.toString()?.contains(label)==true || node.contentDescription?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root }+listOfNotNull(auto.rootInActiveWindow)).firstNotNullOfOrNull { walk(it) }
        }
        fun touch(x: Float,y: Float) {
            val start=SystemClock.uptimeMillis()
            fun event(action: Int)=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            event(MotionEvent.ACTION_DOWN).let { check(auto.injectInputEvent(it,true)); it.recycle() }
            Thread.sleep(40)
            event(MotionEvent.ACTION_UP).let { check(auto.injectInputEvent(it,true)); it.recycle() }
        }
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"comment-handoff-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        val density=context.resources.displayMetrics.density
        fun ime()=main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun viewport()=main { EditorSession.current!!.selection.viewport!!.boundsInWindow().bottom }
        fun tool(index: Int) {
            val bar=Rect().also { (find("编辑工具栏") ?: error("Toolbar missing")).getBoundsInScreen(it) }
            touch((26+44*index)*density,bar.exactCenterY())
        }
        fun shot(name: String) { val bitmap=auto.takeScreenshot()!!; File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(2200)
            val body="从二级工具直接切换评论，底部区域不能先收起。"
            val doc=main {
                Repository.setToolbar(listOf("STYLE","PARAGRAPH","INSERT","UNDO","REDO","COMMENT"))
                Repository.createDoc(null,"评论接续测试",DocContent(listOf(Block(text=body))))
            }
            main { AppState.openDoc(doc.id) }
            waitFor("editor") { find(body)!=null }
            val field=Rect().also { find(body)!!.getBoundsInScreen(it) }
            touch(field.left+30*density,field.exactCenterY())
            waitFor("keyboard") { ime()>160*density }
            Thread.sleep(800)
            for((index,label,name) in listOf(Triple(0,"文字颜色","style"),Triple(1,"段落首行缩进","paragraph"),Triple(2,"拍照","insert"),Triple(0,"文字颜色","rapid"))) {
                if(name!="rapid") {
                    tool(index)
                    waitFor("tool $name ready") { ime()==0 && find(label)!=null }
                    Thread.sleep(200)
                }
                val baseline=viewport()
                val frames=mutableListOf<Float>()
                val deadline=SystemClock.uptimeMillis()+1600
                val callback=object: Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        EditorSession.current?.selection?.viewport?.let { frames+=it.boundsInWindow().bottom }
                        if(SystemClock.uptimeMillis()<deadline) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                main { Choreographer.getInstance().postFrameCallback(callback) }
                if(name=="rapid") { tool(index); Thread.sleep(80) }
                tool(5)
                waitFor("comment composer $name") { find("取消")!=null && find("添加评论…")!=null && find("编辑工具栏")==null && ime()>160*density }
                Thread.sleep(1700)
                main { Choreographer.getInstance().removeFrameCallback(callback) }
                instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","Comment handoff $name: baseline=$baseline min=${frames.minOrNull()} max=${frames.maxOrNull()} frames=${frames.size}\\n".replace("\\n","\n")) })
                // The comment bar is 16dp taller than the toolbar. Only that bar may change;
                // the keyboard-sized area must never collapse or stack on top of the IME.
                check(frames.size>15 && frames.all { it<=baseline+2*density && it>=baseline-20*density }) { "$name input slot collapsed or doubled" }
                check(main { EditorSession.current!!.commentDraft!=null })
                shot("comment-from-$name.png")
                val cancel=Rect().also { find("取消")!!.getBoundsInScreen(it) }
                touch(cancel.exactCenterX(),cancel.exactCenterY())
                waitFor("cancel returns to toolbar $name") { find("编辑工具栏")!=null && find("取消")==null && main { EditorSession.current!!.commentDraft==null } }
                Thread.sleep(300)
                check(ime()>160*density)
                check(main { EditorSession.current!!.content().let { it.bodyText()==body && it.comments.isEmpty() } })
                passed("$name-to-comment-reuses-keyboard-space-without-collapse-or-double-height")
            }
        } catch(error: Throwable) { shot("comment-handoff-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
