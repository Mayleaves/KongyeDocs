package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File

/** Image clipboard/IME insertion, formatted boundary deletion, and explicit panel pinning. */
object Editor3512Regression {
    fun run(inst: Instrumentation, panelsOnly: Boolean = false, pass: (String) -> Unit) {
        fun <T> main(action: () -> T): T {
            var result: Result<T>?=null
            inst.runOnMainSync { result=runCatching(action) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String, test: () -> Boolean) {
            val until=SystemClock.uptimeMillis()+18000
            while(SystemClock.uptimeMillis()<until) { if(test()) return; Thread.sleep(50) }
            error("Timed out: $label")
        }
        val context=inst.targetContext
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"editor3512-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(inst,context)
        val auto=inst.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun e()=EditorSession.current!!
        fun input(view: View=activity.window.decorView, info: EditorInfo=EditorInfo()): InputConnection? {
            if(view.onCheckIsTextEditor()) view.onCreateInputConnection(info)?.let { return it }
            if(view is ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i),info)?.let { return it }
            return null
        }
        fun ime()=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun find(label: String): AccessibilityNodeInfo? {
            auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.text?.toString()==label || node.contentDescription?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        fun tap(point: Offset) {
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                main {
                    val origin=IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point.x-origin[0],point.y-origin[1],0)
                        .apply { this.source=InputDevice.SOURCE_TOUCHSCREEN }
                    activity.dispatchTouchEvent(event); event.recycle()
                }
                Thread.sleep(45)
            }
        }
        fun doubleTap(point: Offset) {
            // Dispatch into the real activity so system animation waits cannot
            // turn the two taps into separate long presses on API 37 emulators.
            repeat(2) {
                val down=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    main {
                        val origin=IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
                        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point.x-origin[0],point.y-origin[1],0)
                            .apply { this.source=InputDevice.SOURCE_TOUCHSCREEN }
                        activity.dispatchTouchEvent(event); event.recycle()
                    }
                    Thread.sleep(35)
                }
                Thread.sleep(65)
            }
        }
        fun tapLabel(label: String) {
            val node=find(label) ?: error("Missing $label")
            val bounds=android.graphics.Rect().also { node.getBoundsInScreen(it) }
            tap(Offset(bounds.exactCenterX(),bounds.exactCenterY()))
        }
        fun settle() { waitFor("focus") { main { e().pendingFocus==null } }; inst.waitForIdleSync(); Thread.sleep(160) }
        fun textPoint(index: Int, offset: Int=2)=main {
            val b=e().blocks[index]
            e().selection.coordinates[b.id]!!.localToScreen(e().layouts[b.id]!!.getCursorRect(offset).center)
        }
        fun open(blocks: List<Block>) {
            val doc=main { Repository.createDoc(null,"图片粘贴与面板检查",DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("document") { main { EditorSession.current?.docId==doc.id && e().layouts.isNotEmpty() && activity.hasWindowFocus() } }
            inst.waitForIdleSync(); Thread.sleep(450)
            tap(textPoint(0))
            waitFor("keyboard") { main { ime()>100 } }; settle()
        }
        val clip=context.getSystemService(ClipboardManager::class.java)
        val previous=main { clip.primaryClip }
        val source=File(context.cacheDir,"share/editor3512.png").apply { parentFile!!.mkdirs() }
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",source)
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            if(!panelsOnly) {
            // Model checks cover every text block without waiting for repeated UI launches.
            main {
                for(type in listOf(BlockType.H1,BlockType.H2,BlockType.H3,BlockType.QUOTE,BlockType.CODE,BlockType.NUMBER,BlockType.BULLET,BlockType.TODO)) {
                    val first=Block(text="前文",spans=listOf(StyleRange(0,2,InlineStyle.BOLD)))
                    val second=Block(type=type,text="标题",checked=type==BlockType.TODO,indent=1)
                    val doc=Repository.createDoc(null,"退格$type",DocContent(listOf(first,second)))
                    val editor=EditorState(doc.id,false)
                    val b=editor.blocks[1]
                    editor.focusedId=b.id
                    editor.onValueChange(b,TextFieldValue(b.text,TextRange.Zero))
                    check(editor.blocks.size==1 && editor.blocks[0].text=="前文标题")
                    check(editor.blocks[0].type==type && editor.blocks[0].indent==1)
                    check(editor.blocks[0].toBlock().spans==first.spans)
                    editor.undo(false)
                    check(editor.blocks.size==2 && editor.blocks[1].type==type)
                    editor.redo(false)
                    check(editor.blocks.single().type==type && editor.blocks.single().text=="前文标题")
                }
                val doc=Repository.createDoc(null,"首段标题",DocContent(listOf(Block(type=BlockType.H1,text="首段"))))
                val editor=EditorState(doc.id,false)
                editor.onValueChange(editor.blocks.single(),TextFieldValue("首段",TextRange.Zero))
                check(editor.blocks.single().type==BlockType.H1 && !editor.canUndo)
            }
            pass("formatted-start-backspace-merges-preserves-format-and-shares-undo-redo")

            Bitmap.createBitmap(96,48,Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(android.graphics.Color.GREEN)
                source.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
            }
            open(listOf(Block(text="前后文字")))
            main {
                e().placeCaret(SelectionPoint(e().blocks[0].id,3))
                clip.setPrimaryClip(ClipData.newUri(context.contentResolver,"图片",uri))
                check(input()!!.performContextMenuAction(android.R.id.paste))
            }
            waitFor("clipboard image") { main { e().blocks.any { it.type==BlockType.IMAGE } } }; settle()
            main {
                check(e().blocks.map { it.text }==listOf("前后","","文字"))
                val image=e().blocks[1]
                check(image.type==BlockType.IMAGE && image.imageRatio==2f && File(Repository.imagesDir,image.image).isFile)
                check(ime()>100)
                e().undo(false)
                check(e().blocks.single().text=="前后文字")
                e().redo(false)
                check(e().blocks[1].type==BlockType.IMAGE)
            }; settle()
            pass("clipboard-uri-image-splits-text-and-supports-one-step-undo-redo")

            open(listOf(Block(type=BlockType.H1,text="标题"),Block(text="下文")))
            main {
                val info=EditorInfo(); val ic=input(info=info)!!
                check(info.contentMimeTypes?.contains("image/*")==true)
                e().placeCaret(SelectionPoint(e().blocks[0].id,3))
                check(ic.commitContent(InputContentInfo(uri,ClipDescription("图片",arrayOf("image/png")),null),0,null))
            }
            waitFor("IME image") { main { e().blocks.count { it.type==BlockType.IMAGE }==1 } }; settle()
            main { check(ime()>100); check(e().blocks.first().type==BlockType.H1 && e().blocks.first().text=="标题") }
            pass("native-ime-advertises-and-accepts-image-content-with-keyboard-open")
            main {
                val image=e().blocks.first { it.type==BlockType.IMAGE }
                e().placeCaret(SelectionPoint(image.id,1))
                check(DocumentClipboard.paste(context,e()))
            }
            waitFor("paste at image edge") { main { e().blocks.count { it.type==BlockType.IMAGE }==2 } }; settle()
            pass("image-edge-accepts-direct-image-paste")

            open(listOf(Block(text="前文"),Block(type=BlockType.H1,text="标题")))
            main { e().placeCaret(SelectionPoint(e().blocks[1].id,1),deferFocus=true) }; settle()
            main { check(input()!!.deleteSurroundingText(1,0)) }; settle()
            main { check(e().blocks.single().text=="前文标题" && e().blocks.single().type==BlockType.H1); check(ime()>100) }
            pass("native-ime-heading-boundary-backspace-keeps-keyboard-and-heading")
            main { e().undo(false) }; settle()
            main { check(input()!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL))); input()!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DEL)) }; settle()
            main { check(e().blocks.single().text=="前文标题" && e().blocks.single().type==BlockType.H1) }
            pass("hardware-heading-backspace-uses-same-boundary-rule")

            }
            open(listOf(Block(text="第一段内容"),Block(text="第二段内容"),Block(text="第三段内容")))
            val density=context.resources.displayMetrics.density
            fun paragraphTool()=main {
                val v=e().selection.viewport!!
                val origin=v.localToScreen(Offset.Zero)
                Offset(70*density,origin.y+v.size.height+24*density)
            }
            tap(paragraphTool())
            waitFor("panel") { find("固定面板")!=null && main { ime()<80 } }; Thread.sleep(400)
            tapLabel("固定面板")
            waitFor("pinned") { find("取消固定面板")!=null }
            for(index in 0..2) {
                tap(textPoint(index)); settle()
                main { check(ime()<80); check(e().focusedId==e().blocks[index].id); e().setBlockType(BlockType.NUMBER) }
                check(find("段落首行缩进")!=null)
            }
            main { check(e().blocks.all { it.type==BlockType.NUMBER }) }
            pass("pinned-panel-supports-batch-formatting-without-keyboard")
            val point=textPoint(2)
            doubleTap(point)
            waitFor("double tap selection") { main { e().selection.active } }
            main { check(ime()<80) }
            pass("pinned-panel-preserves-double-tap-selection")
            tapLabel("取消固定面板")
            tap(textPoint(1)); settle()
            waitFor("single tap keyboard") { find("段落首行缩进")==null && main { ime()>100 } }
            pass("unpin-then-single-document-tap-returns-keyboard")
            tap(paragraphTool())
            waitFor("panel again") { find("返回键盘")!=null && main { ime()<80 } }
            tapLabel("返回键盘")
            waitFor("explicit keyboard") { find("段落首行缩进")==null && main { ime()>100 } }
            pass("panel-keyboard-button-returns-directly-to-input")
            tap(paragraphTool())
            waitFor("unpinned panel") { find("固定面板")!=null && main { ime()<80 } }
            doubleTap(textPoint(1))
            waitFor("selection across keyboard handoff") { main { e().selection.active && ime()>100 } }
            pass("double-tap-selection-survives-unpinned-panel-to-keyboard-handoff")
        } catch(error: Throwable) {
            auto.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null),"editor3512-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            throw error
        } finally {
            main { if(previous!=null) clip.setPrimaryClip(previous) else clip.clearPrimaryClip(); AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) }
            source.delete()
        }
    }
}
