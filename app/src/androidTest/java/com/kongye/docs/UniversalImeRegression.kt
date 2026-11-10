package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
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
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File

/** Standard Android calls only: no package-specific input-method commands. */
object UniversalImeRegression {
    fun run(instrument: Instrumentation, context: Context, actual: Boolean, typingOnly: Boolean = false, passed: (String) -> Unit) {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrument.runOnMainSync { result=runCatching(action) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String,timeout: Long=12000,condition: () -> Boolean) {
            val end=SystemClock.uptimeMillis()+timeout
            while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val auto=instrument.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=33) auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.text?.contains(label)==true) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root }+listOfNotNull(auto.rootInActiveWindow))
                .filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"universal-ime-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        fun editor()=EditorSession.current!!
        fun input(view: View=activity.window.decorView): Pair<InputConnection,EditorInfo>? {
            if(view.onCheckIsTextEditor()) { val info=EditorInfo(); view.onCreateInputConnection(info)?.let { return it to info } }
            if(view is ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        fun ic()=input()!!.first
        fun extract()=main { ic().getExtractedText(ExtractedTextRequest(),0)!! }
        fun text()=main { editor().blocks.joinToString("\n") { it.text } }
        fun state()=main { editor().content() }
        fun height()=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun watch(label: String,action: () -> Unit) {
            val baseline=main { height() }; check(baseline>100)
            val samples=mutableListOf<Int>()
            val frame=object: Choreographer.FrameCallback {
                override fun doFrame(time: Long) { samples+=height(); Choreographer.getInstance().postFrameCallback(this) }
            }
            main { Choreographer.getInstance().postFrameCallback(frame) }
            try { action(); Thread.sleep(350) }
            finally { main { Choreographer.getInstance().removeFrameCallback(frame) } }
            main { check(samples.size>3 && samples.all { it>baseline*.7f }) { "$label hid IME: min=${samples.minOrNull()}, baseline=$baseline" } }
        }
        fun settle() { waitFor("focus settled") { main { editor().pendingFocus==null } }; instrument.waitForIdleSync(); Thread.sleep(160) }
        fun select(start: Int,end: Int=start) { main { check(ic().setSelection(start,end)) }; settle() }
        fun key(code: Int,meta: Int=0) = main {
            val connection=ic()
            check(connection.sendKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,meta)))
            // Undo may remove the focused paragraph and close this connection.
            // Its key-down must apply the edit; a key-up on the retired session may fail.
            connection.sendKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_UP,code,0,meta))
        }
        fun type(value: String) { main { check(ic().commitText(value,1)) }; settle() }
        fun open(title: String,blocks: List<Block>,label: String) {
            val doc=main { Repository.createDoc(null,title,DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("body $label") { find(label)!=null }
            waitFor("window focus") { main { activity.hasWindowFocus() } }
            val point=main { val e=editor(); val b=e.blocks.first(); e.selection.coordinates[b.id]!!.localToRoot(e.layouts[b.id]!!.getBoundingBox(2).center) }
            val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point.x,point.y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event,true)); event.recycle(); Thread.sleep(55)
            }
            waitFor("keyboard") { main { height()>100 } }; Thread.sleep(600)
        }
        fun stage(label: String,condition: () -> Boolean) {
            passed("READY-$label")
            watch(label) { waitFor(label,180000,condition) }
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1600)
            if(typingOnly) {
                open("实际中文输入确认",listOf(Block(text="前段原文不变"),Block(text="后段输入位置")),"前段原文不变")
                select("前段原文不变".length+1+2)
                stage("type-ni-and-choose-chinese-candidate") { main { editor().blocks[1].text=="后段你输入位置" } }
                main { check(editor().blocks[0].text=="前段原文不变"); editor().undo() }; settle()
                main { check(editor().blocks[1].text=="后段输入位置"); editor().redo() }; settle()
                main { check(editor().blocks[1].text=="后段你输入位置" && editor().blocks[0].text=="前段原文不变") }
                passed("real-chinese-candidate-commits-in-destination-and-one-step-undo-redo-preserves-other-paragraph")
                return
            }
            open("通用输入法跨段测试",listOf(Block(text="第一段通用键盘甲乙丙丁戊己庚辛壬癸".repeat(7)),Block(text=""),
                Block(type=BlockType.TODO,text="清单段落通用选择甲乙丙丁".repeat(3)),Block(text="末段😀安全输入")),"第一段通用键盘")
            val original=state(); val whole=text(); val first=main { editor().blocks[0].text.length }; val third=first+2
            if(actual) {
                val control=File(context.getExternalFilesDir(null),"generic-ime-control"); control.delete()
                select(first)
                stage("real-down-from-end-to-empty") { main { editor().focusedId==editor().blocks[1].id } }
                stage("real-down-crosses-empty-to-todo") { main { editor().focusedId==editor().blocks[2].id } }
                // Put the caret at the first display line; select+Up can now cross both boundaries.
                select(third)
                stage("real-keyboard-select-up-crosses-to-first-paragraph") { main { val e=editor(); e.selection.range?.start?.key==e.blocks[0].id && e.selection.range?.end?.key==e.blocks[2].id } }
                check(state()==original)
                stage("finish-real-selection") { control.exists() }; control.delete()
            } else {
                main {
                    val (_,info)=input()!!
                    val extracted=ic().getExtractedText(ExtractedTextRequest(),0)!!
                    check(extracted.text.toString()==whole && ZWS !in extracted.text.toString() && "通用输入法跨段测试" !in extracted.text.toString())
                    check(info.initialSelStart==extracted.selectionStart && info.initialSelEnd==extracted.selectionEnd)
                }
                passed("editor-info-and-extracted-text-use-one-document-coordinate-space-without-title-or-sentinels")
                watch("direct setSelection and immediate input") {
                    main { val connection=ic(); check(connection.setSelection(third,third)); check(connection.commitText("跨段",1)) }
                    settle()
                    main { check(editor().blocks[0].text==original.blocks[0].text && editor().blocks[2].text=="跨段"+original.blocks[2].text); editor().undo() }
                    settle()
                }
                check(state()==original)
                passed("plain-setSelection-crosses-blocks-and-immediate-input-does-not-write-to-stale-paragraph")
                watch("forward and reverse document selection") {
                    select(first-3,third+3)
                    main { val e=editor(); check(e.selection.range==DocumentRange(SelectionPoint(e.blocks[0].id,first-2),SelectionPoint(e.blocks[2].id,4))) }
                    check(extract().text.toString()==whole)
                    main { check(ic().getTextBeforeCursor(100000,0).toString()==whole.take(first-3)); check(ic().getTextAfterCursor(100000,0).toString()==whole.drop(third+3)) }
                    select(third+3,first-3)
                    check(extract().selectionStart==third+3 && extract().selectionEnd==first-3)
                }
                passed("direct-forward-reverse-selections-span-empty-and-checklist-paragraphs-with-keyboard-open")
                select(third)
                key(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.META_SHIFT_ON); settle()
                key(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.META_SHIFT_ON); settle()
                main { val r=editor().selection.range!!; check(r.start.key==editor().blocks[0].id && r.end==SelectionPoint(editor().blocks[2].id,1)) }
                key(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.META_SHIFT_ON); settle()
                main { check(editor().selection.range!!.start.key==editor().blocks[1].id) }
                passed("standard-shift-arrows-extend-and-shrink-a-fixed-anchor-across-paragraphs")
                select(first)
                key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
                main { check(editor().focusedId==editor().blocks[2].id) }; settle()
                passed("standard-direction-events-cross-paragraphs-without-waiting-for-input-session-restarts")
                select(third+2)
                main { check(ic().setComposingText("ni",1,null)); check(ic().commitText("你",1,null)) }; settle()
                main { check(editor().blocks[2].text==original.blocks[2].text.take(2)+"你"+original.blocks[2].text.drop(2)); editor().undo() }; settle()
                check(state()==original)
                passed("api33-composition-commits-chinese-in-the-global-destination-with-single-step-undo")
                select(third+2)
                main { check(ic().setComposingRegion(third,third+2,null)); check(ic().commitText("中文",1)) }; settle()
                main { check(editor().blocks[2].text=="中文"+original.blocks[2].text.drop(2)); editor().undo() }; settle()
                check(state()==original)
                passed("composing-region-maps-absolute-document-offsets-to-the-correct-block")
                select(third+2)
                main { val surrounding=ic().getSurroundingText(5,6,0)!!; check(surrounding.selectionStart==5 && (surrounding.offset==-1 || surrounding.offset+surrounding.selectionStart==third+2)); check(surrounding.text.toString()==whole.substring(third-3,third+8)) }
                passed("surrounding-text-and-selection-offsets-agree-across-paragraph-separators")
                select(third,third+original.blocks[2].text.length)
                main { check(ic().getSelectedText(0).toString().startsWith("☐ ")); check(ic().performContextMenuAction(android.R.id.copy)) }
                passed("copy-retains-checkbox-markers-while-editing-offsets-exclude-internal-placeholders")
                open("通用删除边界",listOf(Block(text="甲😀乙"),Block(text="丙丁")),"甲😀乙")
                select(5)
                main { check(ic().deleteSurroundingTextInCodePoints(1,0)) }; settle()
                main { check(editor().blocks.size==1 && editor().blocks[0].text=="甲😀乙丙丁"); editor().undo() }; settle()
                select(3)
                main { check(ic().deleteSurroundingTextInCodePoints(1,0)) }; settle()
                main { check(editor().blocks[0].text=="甲乙"); editor().undo() }; settle()
                passed("codepoint-delete-preserves-emoji-and-merges-paragraph-boundaries-with-undo")
                select(5)
                main { editor().setBlockType(BlockType.TODO); check(ic().deleteSurroundingText(1,0)) }; settle()
                main { check(editor().blocks.size==2 && editor().blocks[1].type==BlockType.TEXT && editor().blocks[1].text=="丙丁") }
                passed("backspace-at-checklist-start-retains-existing-format-removal-semantics")
            }
            open("通用键盘共同历史",listOf(Block(text="前段保持原文"),Block(text="历史初始文字")),"前段保持")
            select(text().length)
            val states=mutableListOf(state())
            type("输入"); states+=state()
            main { check(ic().deleteSurroundingText(1,0)) }; settle(); states+=state()
            type("\n"); states+=state()
            type("新段内容"); states+=state()
            main { editor().setBlockType(BlockType.TODO) }; states+=state()
            main { editor().toggleTodo(editor().focused!!) }; states+=state()
            main { val e=editor(); val b=e.focused!!; e.selection.set(SelectionPoint(b.id,1),SelectionPoint(b.id,b.value.text.length)); e.toggleStyle(InlineStyle.BOLD); e.clearArticleSelection() }; states+=state()
            main { editor().setAlign(Align.CENTER) }; states+=state()
            select(2)
            watch("shared history") {
                for(i in states.lastIndex-1 downTo 0) {
                    if(actual) stage("real-undo-$i") { state()==states[i] }
                    else { key(KeyEvent.KEYCODE_Z,KeyEvent.META_CTRL_ON); waitFor("undo $i") { state()==states[i] }; settle() }
                }
                for(i in 1..states.lastIndex) {
                    if(actual) stage("real-redo-$i") { state()==states[i] }
                    else { key(if(i%2==0) KeyEvent.KEYCODE_Z else KeyEvent.KEYCODE_Y,KeyEvent.META_CTRL_ON or if(i%2==0) KeyEvent.META_SHIFT_ON else 0); waitFor("redo $i") { state()==states[i] }; settle() }
                }
            }
            passed("ctrl-z-ctrl-shift-z-and-ctrl-y-share-all-eight-text-structure-and-format-history-steps")
            check(android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)!=null)
        } catch(failure: Throwable) {
            auto.takeScreenshot()?.let { bitmap -> File(context.getExternalFilesDir(null),"universal-ime-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            throw failure
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
