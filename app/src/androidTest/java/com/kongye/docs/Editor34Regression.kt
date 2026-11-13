package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File

/** IME call ordering, image deletion, and structured clipboard round trips. */
object Editor34Regression {
    fun run(instrument: Instrumentation,pass: (String)->Unit) {
        fun <T> main(action: ()->T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(action) }; return result!!.getOrThrow() }
        fun waitFor(label: String,condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val context=instrument.targetContext
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"editor34-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        fun e()=EditorSession.current!!
        fun input(view: View=activity.window.decorView): InputConnection? {
            if(view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
            if(view is ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        fun height()=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun settle() { waitFor("pending focus") { main { e().pendingFocus==null } }; instrument.waitForIdleSync(); Thread.sleep(180) }
        fun open(blocks: List<Block>) {
            val doc=main { Repository.createDoc(null,"3.4编辑器检查",DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("document") { main { EditorSession.current?.docId==doc.id && e().layouts.isNotEmpty() && activity.hasWindowFocus() } }
            instrument.waitForIdleSync(); Thread.sleep(650)
            val point=main { val b=e().blocks.first { it.type.isText }; e().selection.coordinates[b.id]!!.localToScreen(e().layouts[b.id]!!.getBoundingBox(1).center) }
            android.util.Log.d("Kongye34","tap=$point")
            instrument.uiAutomation.executeShellCommand("input tap ${point.x.toInt()} ${point.y.toInt()}").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
            main { val b=e().blocks.first { it.type.isText }; e().requestFocus(b,1) }
            waitFor("keyboard") { main { height()>100 && e().focusedId!=null } }; settle(); Thread.sleep(400)
        }
        fun watch(label: String,action: ()->Unit) {
            val baseline=main { height() }; check(baseline>100)
            val heights=mutableListOf<Int>()
            val callback=object: Choreographer.FrameCallback { override fun doFrame(time: Long) { heights+=height(); Choreographer.getInstance().postFrameCallback(this) } }
            main { Choreographer.getInstance().postFrameCallback(callback) }
            try { action(); settle(); Thread.sleep(300) } finally { main { Choreographer.getInstance().removeFrameCallback(callback) } }
            check(heights.size>3 && heights.all { it>baseline*.7f }) { "$label closed keyboard: ${heights.minOrNull()} / $baseline" }
        }
        fun same(actual: List<Block>,expected: List<Block>)=actual.map { it.copy(id="",image=if(it.type==BlockType.IMAGE) "image" else it.image) }==expected.map { it.copy(id="",image=if(it.type==BlockType.IMAGE) "image" else it.image) }
        val clipboard=context.getSystemService(ClipboardManager::class.java)
        val previous=main { clipboard.primaryClip }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            open(listOf(Block(text="中文段落")))
            main { val b=e().blocks.first(); e().placeCaret(SelectionPoint(b.id,b.value.text.length),deferFocus=true) }; settle()
            watch("finish then Enter") { main { val ic=input()!!; check(ic.finishComposingText()); check(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))); check(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))) } }
            main { check(e().blocks.map { it.text }==listOf("中文段落","")) }
            pass("iflytek-finish-composing-then-enter-in-same-frame-produces-one-newline")
            watch("rapid Enter and text") { main { val ic=input()!!; check(ic.performEditorAction(EditorInfo.IME_ACTION_NONE)); check(ic.commitText("后续文字",1)); check(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))); check(ic.commitText("下一段",1)) } }
            main { check(e().blocks.map { it.text }==listOf("中文段落","","后续文字","下一段")) { e().blocks.map { it.text }.toString() } }
            pass("rapid-newline-and-input-use-new-paragraph-before-focus-frame")
            open(listOf(Block(text="组字测试")))
            main {
                val b=e().blocks.first(); b.value=b.value.copy(selection=TextRange(b.value.text.length))
                val ime=DocumentIme(e(),activity.window.decorView)
                val ic=DocumentInputConnection(android.view.inputmethod.BaseInputConnection(activity.window.decorView,false),context,e(),true,ime,b.id)
                check(ic.setComposingText("中文",1)); check(ic.finishComposingText()); check(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER)))
                check(e().blocks.map { it.text }==listOf("组字测试中文",""))
            }; settle()
            pass("finishing-chinese-composition-before-enter-keeps-characters")
            open(listOf(Block(text="图前"),Block(type=BlockType.IMAGE,image="preserved.png"),Block(),Block(),Block(text="图后正文")))
            val original=main { e().content() }; val image=main { e().blocks[1] }
            main { e().placeCaret(SelectionPoint(image.id,2),deferFocus=true) }; settle()
            watch("image-after deletion") {
                repeat(2) { main { check(input()!!.deleteSurroundingText(1,0)) }; settle() }
                main { check(e().blocks.map { it.type }==listOf(BlockType.TEXT,BlockType.IMAGE,BlockType.TEXT)); check(e().blocks.last().text=="图后正文"); check(input()!!.deleteSurroundingText(1,0)); check(e().blocks.size==3) }
            }
            pass("image-after-backspace-removes-only-empty-lines-keeping-picture-and-text")
            watch("image delete history") { main { e().undo() }; settle(); main { e().undo() }; settle(); main { check(e().content()==original); e().redo() }; settle(); main { e().redo() } }
            main { check(e().blocks.size==3 && e().blocks[1].id==image.id) }
            pass("image-empty-line-deletion-shares-undo-redo-with-keyboard-open")
            val imageName="${newId()}.png"
            val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
            File(Repository.imagesDir,imageName).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            val rich=listOf(
                Block(type=BlockType.H2,text="标题链接",align=Align.CENTER,firstIndent=true,spans=listOf(StyleRange(0,2,InlineStyle.BOLD),StyleRange(1,4,InlineStyle.ITALIC),StyleRange(2,4,InlineStyle.LINK,url="https://example.org"))),
                Block(type=BlockType.TODO,text="清单样式",checked=true,indent=2,spans=listOf(StyleRange(0,4,InlineStyle.UNDERLINE),StyleRange(0,2,InlineStyle.COLOR,0xff123456L),StyleRange(2,4,InlineStyle.HIGHLIGHT,0xffffff00L))),
                Block(type=BlockType.NUMBER,text="序号段落",indent=1),Block(type=BlockType.CODE,text="one\ntwo"),
                Block(type=BlockType.TABLE,table=listOf(listOf("甲","乙"))),Block(type=BlockType.IMAGE,image=imageName,imageRatio=1f),Block(text="结尾",spans=listOf(StyleRange(0,2,InlineStyle.STRIKE)))
            )
            open(rich)
            watch("rich copy paste") { main { e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.copy)); e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.paste)); check(same(e().content().blocks,rich)) { e().content().blocks.toString() } } }
            main { e().save(); check(Repository.loadDoc(e().docId)==e().content()); val imageCopy=e().blocks.first { it.type==BlockType.IMAGE }; check(imageCopy.image!=imageName && BackupCodec.validImage(imageCopy.image)); check(Repository.loadBitmap(imageCopy.image)?.getPixel(0,0)==android.graphics.Color.GREEN) }
            pass("clipboard-preserves-list-state-paragraph-format-inline-styles-table-code-and-save")
            val pasted=main { e().content() }
            watch("rich history") { main { e().undo() }; settle(); main { check(same(e().content().blocks,rich)); e().redo() }; settle(); main { check(e().content()==pasted) } }
            pass("formatted-paste-is-one-undo-redo-step-with-keyboard-expanded")
            watch("rich cut") { main { e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.cut)) }; settle(); main { check(e().blocks.single().text.isEmpty()); e().undo() }; settle(); main { check(e().content()==pasted) } }
            pass("formatted-cut-undo-restores-document-with-keyboard-expanded")
            File(Repository.imagesDir,imageName).delete()
            watch("cached image survives source deletion") { main { e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.paste)); check(Repository.loadBitmap(e().blocks.first { it.type==BlockType.IMAGE }.image)!=null) } }
            pass("copied-picture-remains-pasteable-after-source-file-is-removed")
            open(listOf(Block(text="甲乙丙丁",spans=listOf(StyleRange(0,3,InlineStyle.BOLD),StyleRange(1,4,InlineStyle.UNDERLINE)))))
            main { val b=e().blocks.first(); e().selection.set(SelectionPoint(b.id,2),SelectionPoint(b.id,4)); check(input()!!.getSelectedText(0).toString()=="乙丙"); clipboard.setPrimaryClip(ClipData.newPlainText("键盘复制","乙丙")) }
            waitFor("keyboard clip upgraded") { main { clipboard.primaryClip?.description?.hasMimeType("application/vnd.kongye.document-fragment")==true } }
            main { e().clearArticleSelection(); e().placeCaret(SelectionPoint(e().blocks.first().id,5),deferFocus=true) }; settle()
            watch("direct commit paste") { main { check(input()!!.commitText("乙丙",1)) } }
            main { val b=e().blocks.first(); check(b.text=="甲乙丙丁乙丙"); val spans=b.toBlock().spans; check(spans.any { it.style==InlineStyle.BOLD && it.start==4 && it.end==6 }); check(spans.any { it.style==InlineStyle.UNDERLINE && it.start<=4 && it.end>=6 }) { spans.toString() } }
            pass("keyboard-plain-copy-and-direct-commitText-paste-preserve-selection-format")
            open(listOf(Block(text="前后",spans=listOf(StyleRange(0,1,InlineStyle.BOLD),StyleRange(1,2,InlineStyle.ITALIC)))))
            val fragment=ClipboardFragment(listOf(Block(type=BlockType.TODO,text="中一",checked=true,spans=listOf(StyleRange(0,2,InlineStyle.UNDERLINE))),Block(type=BlockType.NUMBER,text="中二")))
            watch("middle paste") { main { val b=e().blocks.first(); e().placeCaret(SelectionPoint(b.id,2)); check(e().pasteFragment(fragment)); check(e().blocks.map { it.text }==listOf("前中一","中二后")); check(e().blocks.last().toBlock().spans.any { it.style==InlineStyle.ITALIC && it.start==2 && it.end==3 }); check(e().pendingFocus?.pos==3) } }
            pass("multi-paragraph-paste-retains-prefix-suffix-styles-and-caret")
            open(listOf(Block(text="换行后粘贴")))
            watch("Enter then rich paste in one frame") { main {
                val b=e().blocks.first(); b.value=b.value.copy(selection=TextRange(b.value.text.length))
                val ic=input()!!; check(ic.performEditorAction(EditorInfo.IME_ACTION_NONE))
                check(e().pasteFragment(ClipboardFragment(listOf(Block(text="格式文字",spans=listOf(StyleRange(0,4,InlineStyle.BOLD)))))))
                check(e().blocks.map { it.text }==listOf("换行后粘贴","格式文字"))
            } }
            pass("rich-paste-immediately-after-enter-targets-new-paragraph")
            main { clipboard.setPrimaryClip(ClipData.newHtmlText("外部","外部格式","<p><b>外部</b><u>格式</u></p>")) }
            watch("external HTML") { main { e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.paste)); check(e().blocks.single().text=="外部格式"); check(e().blocks.single().toBlock().spans.any { it.style==InlineStyle.BOLD && it.start==0 && it.end==2 }) } }
            pass("external-html-clipboard-imports-formatting")
            main { clipboard.setPrimaryClip(ClipData.newPlainText("纯文本","普通\n文本")) }
            watch("plain paste") { main { e().selectArticle(); check(input()!!.performContextMenuAction(android.R.id.paste)); check(e().blocks.map { it.text }==listOf("普通","文本")); check(e().blocks.all { it.spans.isEmpty() }) } }
            pass("plain-text-paste-still-works-without-invented-formatting")
        } finally { main { if(previous!=null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip(); AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
