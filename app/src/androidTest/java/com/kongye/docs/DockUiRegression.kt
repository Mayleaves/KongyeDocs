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

object DockUiRegression {
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
            val root=File(context.filesDir,"dock-ui-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        val density=context.resources.displayMetrics.density
        fun ime()=main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun viewport()=main { EditorSession.current!!.selection.viewport!!.boundsInWindow().bottom }
        fun toolbar()=Rect().also { (find("编辑工具栏") ?: error("Toolbar missing")).getBoundsInScreen(it) }
        fun tool(index: Int) {
            val bar=toolbar()
            touch((26+44*index)*density,bar.exactCenterY())
        }
        fun shot(name: String) { val bitmap=auto.takeScreenshot()!!; File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(2200)
            main {
                Repository.setToolbar(listOf("STYLE","PARAGRAPH","INSERT","UNDO","REDO","COMMENT"))
                val doc=Repository.createDoc(null,"键盘替换测试",DocContent(listOf(Block(text="工具面板应该替换键盘，工具栏保持固定位置。"))))
                AppState.openDoc(doc.id)
            }
            waitFor("editor") { find("工具面板应该替换键盘")!=null }
            val field=Rect().also { find("工具面板应该替换键盘")!!.getBoundsInScreen(it) }
            touch(field.left+30*density,field.exactCenterY())
            waitFor("keyboard open") { ime()>160*density }
            Thread.sleep(850)
            val anchor=viewport()
            val originalBar=toolbar()
            check(kotlin.math.abs(originalBar.height()-48.6f*density)<=2*density)
            shot("dock-system-keyboard.png")
            fun fixedTransition(name: String,action: ()->Unit) {
                val frames=mutableListOf<Float>()
                val deadline=SystemClock.uptimeMillis()+1100
                val callback=object: Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        EditorSession.current?.selection?.viewport?.let { frames+=it.boundsInWindow().bottom }
                        if(SystemClock.uptimeMillis()<deadline) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                main { Choreographer.getInstance().postFrameCallback(callback) }
                action(); Thread.sleep(1200)
                main { Choreographer.getInstance().removeFrameCallback(callback) }
                val worst=frames.maxOfOrNull { kotlin.math.abs(it-anchor) } ?: Float.MAX_VALUE
                instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","Dock $name: baseline=$anchor min=${frames.minOrNull()} max=${frames.maxOrNull()} frames=${frames.size}\n") })
                check(frames.size>10 && worst<=2*density) { "$name toolbar moved: baseline=$anchor worst=$worst" }
                val bar=toolbar()
                check(kotlin.math.abs(bar.top-originalBar.top)<=2*density && bar.height()==originalBar.height()) { "$name toolbar bounds changed: $bar / $originalBar" }
            }
            for((index,label,name) in listOf(Triple(0,"文字颜色","style"),Triple(1,"段落首行缩进","paragraph"),Triple(2,"拍照","insert"))) {
                fixedTransition("keyboard-to-$name") { tool(index) }
                waitFor("$name replaces keyboard") { ime()==0 && find(label)!=null }
                shot("dock-$name.png")
                fixedTransition("$name-to-keyboard") { tool(index) }
                waitFor("keyboard restored") { ime()>160*density && find(label)==null }
                passed("$name-replaces-keyboard-and-returns-with-fixed-toolbar-every-frame")
            }
            fixedTransition("open-style-again") { tool(0) }
            waitFor("style visible") { ime()==0 && find("文字颜色")!=null }
            for((index,label) in listOf(1 to "段落首行缩进",2 to "拍照",0 to "文字颜色",2 to "拍照",1 to "段落首行缩进",0 to "文字颜色")) {
                fixedTransition("switch-$index") { tool(index) }
                check(ime()==0 && find(label)!=null)
            }
            shot("dock-tools-stay-expanded.png")
            fixedTransition("final-return") { tool(0) }
            waitFor("final keyboard") { ime()>160*density }
            check(main { EditorSession.current!!.content().bodyText() }=="工具面板应该替换键盘，工具栏保持固定位置。")
            passed("six-tool-switches-preserve-height-and-document-content")
        } catch(error: Throwable) { shot("dock-ui-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
