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
import com.kongye.docs.ui.editor.Tool
import java.io.File

object ToolbarCustomizeRegression {
    fun run(instrument: Instrumentation,context: Context,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(block) }; return result!!.getOrThrow() }
        fun waitFor(label: String,condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+12000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(40) }; error("Timed out: $label")
        }
        val auto=instrument.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String,description: Boolean=false,inPanel: Boolean=false): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if((if(description) node.contentDescription else node.text)?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            val roots=if(inPanel) listOfNotNull(find("自定义工具栏面板",true)) else auto.windows.mapNotNull { it.root }+listOfNotNull(auto.rootInActiveWindow)
            return roots.firstNotNullOfOrNull { walk(it) }
        }
        fun touch(x: Float,y: Float) {
            val start=SystemClock.uptimeMillis()
            fun event(action: Int)=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            event(MotionEvent.ACTION_DOWN).let { check(auto.injectInputEvent(it,true)); it.recycle() }
            Thread.sleep(40)
            event(MotionEvent.ACTION_UP).let { check(auto.injectInputEvent(it,true)); it.recycle() }
        }
        fun click(label: String) {
            var target: AccessibilityNodeInfo?=null
            waitFor("click $label") {
                var node=if(label in listOf("取消","恢复默认","完成") && find("自定义工具栏面板",true)!=null) {
                    find(label,true,true)
                } else find(label) ?: find(label,true)
                while(node!=null && !node.isClickable) node=node.parent
                target=node
                target?.isVisibleToUser==true
            }
            val rect=Rect().also { target!!.getBoundsInScreen(it) }
            touch(rect.exactCenterX(),rect.exactCenterY())
        }
        fun bounds(node: AccessibilityNodeInfo)=Rect().also { node.getBoundsInScreen(it) }
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"toolbar-customize-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        val density=context.resources.displayMetrics.density
        fun ime()=main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun viewport()=main { EditorSession.current!!.selection.viewport!!.boundsInWindow().bottom }
        fun tool(index: Int) {
            val bar=bounds(find("编辑工具栏",true) ?: error("Editor toolbar missing"))
            touch((26+44*index)*density,bar.exactCenterY())
        }
        fun shot(name: String) { val bitmap=auto.takeScreenshot()!!; File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(2200)
            val body="自定义工具栏在下面显示，正文保持不变。"
            val doc=main {
                Repository.setToolbar(Tool.entries.map { it.name })
                Repository.createDoc(null,"内嵌工具排序测试",DocContent(listOf(Block(text=body))))
            }
            fun enterEditor() {
                main { AppState.openDoc(doc.id) }
                waitFor("editor body") { find(com.kongye.docs.ui.editor.ZWS+body)!=null }
                val field=bounds(find(com.kongye.docs.ui.editor.ZWS+body)!!)
                touch(field.left+30*density,field.exactCenterY())
                waitFor("keyboard") { ime()>160*density }
                Thread.sleep(800)
            }
            enterEditor()
            val anchor=viewport()
            val toolbarHeight=bounds(find("编辑工具栏",true)!!).height()
            fun fixed(name: String,lowerDp: Float=2f,action: ()->Unit) {
                val frames=mutableListOf<Float>()
                val deadline=SystemClock.uptimeMillis()+1100
                val callback=object: Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        EditorSession.current?.selection?.viewport?.let { frames+=it.boundsInWindow().bottom }
                        if(SystemClock.uptimeMillis()<deadline) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                main { Choreographer.getInstance().postFrameCallback(callback) }
                action(); Thread.sleep(1250)
                main { Choreographer.getInstance().removeFrameCallback(callback) }
                instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","Customize $name: baseline=$anchor min=${frames.minOrNull()} max=${frames.maxOrNull()} frames=${frames.size}\n") })
                check(frames.size>10 && frames.all { it<=anchor+2*density && it>=anchor-lowerDp*density }) { "Custom panel moved during $name" }
            }
            fun drag(label: String,deltaDp: Float,holdMs: Long=android.view.ViewConfiguration.getLongPressTimeout().toLong()+250) {
            val handle=bounds(find(label,true) ?: error("Drag handle missing: $label"))
            val start=SystemClock.uptimeMillis()
            fun event(action: Int,y: Float)=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,handle.exactCenterX(),y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            event(MotionEvent.ACTION_DOWN,handle.exactCenterY()).let { check(auto.injectInputEvent(it,true)); it.recycle() }
            Thread.sleep(holdMs)
            repeat(12) { i -> event(MotionEvent.ACTION_MOVE,handle.exactCenterY()+(i+1)*deltaDp*density/12).let { check(auto.injectInputEvent(it,true)); it.recycle() }; Thread.sleep(25) }
            event(MotionEvent.ACTION_UP,handle.exactCenterY()+deltaDp*density).let { check(auto.injectInputEvent(it,true)); it.recycle() }
            }
            fun action(label: String)=find(label,true,true)
            fixed("keyboard-to-customize") { tool(6) }
            waitFor("inline customize") { find("自定义工具栏面板",true)!=null && find("编辑工具栏",true)!=null && ime()==0 }
            check(bounds(find("编辑工具栏",true)!!).height()==toolbarHeight)
            check(find("自定义工具栏面板",true)!!.windowId==find("编辑工具栏",true)!!.windowId) { "Customization must share the editor window" }
            check(find("自定义工具栏")==null && find("按住右侧 ≡ 上下拖动，调整键盘上方工具的顺序")==null)
            check(action("取消")==null && action("恢复默认")==null && action("完成")==null)
            shot("toolbar-customize-locked.png")
            val initial=main { Repository.toolbar.value.toList() }
            val initialHandle=bounds(find("拖动文字样式",true)!!)
            touch(initialHandle.exactCenterX(),initialHandle.exactCenterY())
            drag("拖动文字样式",55f,90)
            Thread.sleep(400)
            check(main { Repository.toolbar.value==initial })
            check(action("取消")==null && action("完成")==null)
            passed("tap-and-short-drag-cannot-reorder-or-show-actions")
            fixed("long-press-shows-actions") { drag("拖动文字样式",0f) }
            waitFor("icon actions") { action("取消")!=null && action("恢复默认")!=null && action("完成")!=null }
            check(find("取消")==null && find("恢复默认")==null && find("完成")==null)
            val cancel=bounds(action("取消")!!)
            val reset=bounds(action("恢复默认")!!)
            val done=bounds(action("完成")!!)
            check(cancel.top==reset.top && reset.top==done.top && cancel.right<=reset.left && reset.right<=done.left)
            check(main { Repository.toolbar.value==initial })
            shot("toolbar-customize-inline.png")
            passed("long-press-reveals-three-accessible-icons-without-moving-toolbar")
            fixed("customize-to-keyboard") { tool(6) }
            waitFor("keyboard restored") { find("自定义工具栏面板",true)==null && ime()>160*density }
            passed("customize-replaces-keyboard-inline-without-modal-or-toolbar-movement")

            fixed("style-open") { tool(0) }
            for((index,label) in listOf(6 to "自定义工具栏面板",1 to "段落首行缩进",6 to "自定义工具栏面板",2 to "拍照",6 to "自定义工具栏面板")) {
                fixed("switch-$index") { tool(index) }
                waitFor("settled panel $label") { find(label,label=="自定义工具栏面板")!=null && ime()==0 }
            }
            passed("customize-switches-with-three-tool-panels-in-the-same-space")

            drag("拖动文字样式",55f)
            val expected=listOf("PARAGRAPH","STYLE","INSERT","UNDO","REDO","COMMENT")
            waitFor("order saved after drag") { main { Repository.toolbar.value==expected } }
            shot("toolbar-customize-reordered.png")
            fixed("finish-reordered") { click("完成") }
            waitFor("done returns to keyboard") { ime()>160*density && find("自定义工具栏面板",true)==null }
            main { AppState.goHome(Tab.HOME); Repository.init(fixture); check(Repository.toolbar.value==expected) }
            enterEditor()
            fixed("reopen-persisted") { tool(6) }
            waitFor("persisted order visible") { runCatching { bounds(find("段落")!!).top<bounds(find("文字样式")!!).top }.getOrDefault(false) }
            passed("inline-handle-drag-saves-order-and-persists-after-reload")

            val defaults=Tool.entries.map { it.name }
            drag("拖动文字样式",-55f)
            waitFor("second drag changed order") { main { Repository.toolbar.value==defaults } }
            fixed("cancel-reordered") { click("取消") }
            waitFor("cancel returns to keyboard") { ime()>160*density && find("自定义工具栏面板",true)==null }
            main { Repository.init(fixture); check(Repository.toolbar.value==expected) }
            fixed("reopen-after-cancel") { tool(6) }
            waitFor("cancelled order restored") { runCatching { bounds(find("段落")!!).top<bounds(find("文字样式")!!).top }.getOrDefault(false) }
            passed("cancel-rolls-back-drag-and-preserves-keyboard-and-saved-order")

            check(action("完成")==null)
            drag("拖动段落",0f)
            waitFor("actions after reopening") { action("完成")!=null }
            var scroll=find("段落")!!
            while(!scroll.isScrollable) scroll=scroll.parent ?: error("Order list must scroll")
            check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            waitFor("last item visible") { find("评论")?.let { it.isVisibleToUser && bounds(it).bottom<bounds(action("完成")!!).top }==true }
            shot("toolbar-customize-scrolled.png")
            check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            click("恢复默认")
            waitFor("default order restored") { main { Repository.toolbar.value==Tool.entries.map { it.name } } }
            Thread.sleep(1500)
            passed("inline-order-list-scrolls-and-restore-default-still-works")

            fixed("cancel-reset") { click("取消") }
            waitFor("reset cancelled") { ime()>160*density && find("自定义工具栏面板",true)==null }
            main { Repository.init(fixture); check(Repository.toolbar.value==expected) }
            fixed("reopen-reset") { tool(6) }
            check(action("恢复默认")==null)
            drag("拖动段落",0f)
            waitFor("reset button ready") { action("恢复默认")!=null }
            click("恢复默认")
            waitFor("reset preview") { main { Repository.toolbar.value==defaults } }
            fixed("complete-reset") { click("完成") }
            waitFor("reset completed") { ime()>160*density && find("自定义工具栏面板",true)==null }
            main { Repository.init(fixture); check(Repository.toolbar.value==defaults) }
            fixed("reopen-completed-reset") { tool(6) }
            waitFor("completed reset visible") { runCatching { bounds(find("文字样式")!!).top<bounds(find("段落")!!).top }.getOrDefault(false) }
            passed("cancel-rolls-back-reset-and-done-persists-default-order")

            fixed("customize-to-comment",20f) { tool(5) }
            waitFor("comment keyboard") { find("取消")!=null && find("编辑工具栏",true)==null && ime()>160*density }
            click("取消")
            waitFor("comment cancel restores toolbar") { find("编辑工具栏",true)!=null && main { EditorSession.current!!.commentDraft==null } }
            check(ime()>160*density)
            check(main { EditorSession.current!!.content().let { it.bodyText()==body && it.comments.isEmpty() } })
            passed("customize-to-comment-retains-space-and-cancel-retains-keyboard")

            main { AppState.goHome(Tab.ME) }
            click("编辑工具栏")
            waitFor("settings toolbar sheet") { find("自定义工具栏")!=null && find("完成")!=null }
            shot("toolbar-customize-settings-sheet.png")
            click("完成")
            waitFor("settings sheet closed") { find("自定义工具栏")==null }
            passed("my-settings-customization-sheet-keeps-existing-behavior")
        } catch(error: Throwable) { shot("toolbar-customize-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
