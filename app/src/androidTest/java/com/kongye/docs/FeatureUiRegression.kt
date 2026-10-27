package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.EditorSession
import com.kongye.docs.ui.editor.ZWS
import com.kongye.docs.ui.home.DayPart
import com.kongye.docs.ui.home.LineCat
import com.kongye.docs.ui.home.CAT_W
import com.kongye.docs.ui.home.CAT_H
import com.kongye.docs.ui.screens.VersionPreviewScreen
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.KongyeTheme
import java.io.File

object FeatureUiRegression {
    fun run(instrument: Instrumentation,context: Context,manualKeyboard: Boolean=false,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T {
            var result: Result<T>?=null
            instrument.runOnMainSync { result=runCatching(block) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String, condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+10_000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val automation=instrument.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(text: String,editable: Boolean=false,exact: Boolean=false): AccessibilityNodeInfo? {
            val roots=(automation.windows.mapNotNull { it.root }+listOfNotNull(automation.rootInActiveWindow))
            fun walk(node: AccessibilityNodeInfo,depth: Int=0): AccessibilityNodeInfo? {
                val label=node.text?.toString() ?: node.contentDescription?.toString()
                if((!editable || node.isEditable) && (if(exact) label==text else label?.contains(text)==true)) return node
                if(depth>60) return null
                for(i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child,depth+1)?.let { return it } }
                return null
            }
            return roots.firstNotNullOfOrNull { walk(it) }
        }
        fun boundsFor(node: AccessibilityNodeInfo)=Rect().also { node.getBoundsInScreen(it) }
        fun touch(x: Float,y: Float,hold: Long=45) {
            val start=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(down,true)); down.recycle()
            Thread.sleep(hold)
            val up=MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(up,true)); up.recycle()
        }
        val fixture=object: ContextWrapper(context) {
            private val directory=File(context.filesDir,"ui-features-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir(): File=directory
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        try {
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        Thread.sleep(2500)
        val body="甲乙丙丁选择测试第一段，自动换行后继续选择正文。第二行仍属于同一段，接着拖到下一段。"
        val ending="全文全选最后一段"
        main {
            val item=Repository.createDoc(null,"选字与全文测试",DocContent(listOf(Block(text=body),Block(text=ending))))
            AppState.openDoc(item.id)
        }
        Thread.sleep(700)
        val readiness=main { "current=${EditorSession.current!=null}, layouts=${EditorSession.current?.layouts?.size}, problem=${Repository.isReadOnly}" }
        instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","UI readiness: $readiness; matchingText=${find(body)!=null}; editable=${find(body)?.isEditable}\n") })
        waitFor("editor") { main { EditorSession.current?.layouts?.isNotEmpty()==true } && find(body)!=null }
        waitFor("default enabled save prompt") { find("文档将自动保存")!=null }
        main { Settings.update { it.copy(autoLocalVersion=false) } }
        waitFor("disabled prompt hidden") { find("文档将自动保存")==null && find("正在保存")==null }
        main { Settings.update { it.copy(autoLocalVersion=true) } }
        waitFor("enabled prompt restored") { find("文档将自动保存")!=null }
        passed("editor-prompt-follows-system-backup-switch")
        fun position(character: String): Pair<Float,Float> {
            val node=find(body,editable=true) ?: error("Editor accessibility node missing")
            val bounds=Rect(); node.getBoundsInScreen(bounds)
            return main {
                val editor=EditorSession.current!!
                val block=editor.blocks.first()
                val box=editor.layouts.getValue(block.id).getBoundingBox(block.value.text.indexOf(character))
                (bounds.left+box.center.x) to (bounds.top+box.center.y)
            }
        }
        val focus=position("乙"); touch(focus.first,focus.second)
        Thread.sleep(900)
        val screenDensity=context.resources.displayMetrics.density
        fun toolbarPoint(tool: Int): Pair<Float,Float> {
            val field=boundsFor(find("甲",editable=true) ?: error("Focused fixture text missing"))
            return main {
                val e=EditorSession.current!!
                val origin=e.selection.coordinates.getValue(e.blocks.first().id).localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                val windowX=field.left-origin.x; val windowY=field.top-origin.y
                val area=e.selection.viewport!!
                val bottom=area.localToRoot(androidx.compose.ui.geometry.Offset.Zero).y+area.size.height
                ((26+44*tool)*screenDensity+windowX) to (bottom+24*screenDensity+windowY)
            }
        }
        toolbarPoint(0).let { touch(it.first,it.second) }
        waitFor("style panel opened") { find("文字颜色")!=null }
        Thread.sleep(700)
        val expandedHeight=main { EditorSession.current!!.selection.viewport!!.size.height }
        val switches=listOf(1 to "段落首行缩进",2 to "拍照",0 to "文字颜色",1 to "段落首行缩进",0 to "文字颜色",2 to "拍照",0 to "文字颜色")
        switches.forEach { (tool,label) ->
            val frames=mutableListOf<Int>()
            val deadline=SystemClock.uptimeMillis()+450
            val callback=object: android.view.Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    EditorSession.current?.selection?.viewport?.let { frames+=it.size.height }
                    if(SystemClock.uptimeMillis()<deadline) android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            main { android.view.Choreographer.getInstance().postFrameCallback(callback) }
            toolbarPoint(tool).let { touch(it.first,it.second) }
            Thread.sleep(500)
            main {
                android.view.Choreographer.getInstance().removeFrameCallback(callback)
                check(frames.size>=5) { "Insufficient tool-switch frames" }
                check(frames.all { kotlin.math.abs(it-expandedHeight)<=2*screenDensity }) { "Tool area collapsed while switching: baseline=$expandedHeight min=${frames.min()} max=${frames.max()}" }
            }
            waitFor("switched panel $label") { find(label)!=null }
            instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","Tool switch $label: height remains $expandedHeight px across ${frames.size} frames\n") })
        }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null),"tool-switch-expanded.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        passed("tool-switch-7-times-keeps-expanded-height-every-frame")
        toolbarPoint(0).let { touch(it.first,it.second) }
        waitFor("style panel closed to keyboard") { find("文字颜色")==null }
        Thread.sleep(900)
        fun activeConnection()=main {
            fun input(view: android.view.View): android.view.inputmethod.InputConnection? {
                if(view.onCheckIsTextEditor()) view.onCreateInputConnection(android.view.inputmethod.EditorInfo())?.let { return it }
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
                return null
            }
            input(activity.window.decorView) ?: error("IME connection missing")
        }
        if(manualKeyboard) {
            val report=File(context.filesDir,"manual-keyboard-state.txt")
            val finish=File(context.filesDir,"manual-keyboard-finish")
            finish.delete()
            instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","READY manual keyboard fixture\n") })
            val deadline=SystemClock.uptimeMillis()+300_000
            while(SystemClock.uptimeMillis()<deadline && !finish.exists()) {
                val state=main {
                    val e=EditorSession.current!!
                    val selected=e.selection.text()
                    val clip=context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    "all=${e.articleSelected}; bodyComplete=${body in selected && ending in selected}; titleExcluded=${"选字与全文测试" !in selected}; titlePreserved=${e.title.text=="选字与全文测试"}; clipboardBody=${clip?.let { body in it && ending in it && "选字与全文测试" !in it }==true}"
                }
                report.writeText(state); Thread.sleep(200)
            }
            finish.delete(); return
        }
        fun imeHeight()=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        waitFor("IME visible before undo") { main { imeHeight()>100 } }
        val originalBody=main { EditorSession.current!!.content().bodyText() }
        val originalInput=main { EditorSession.current!!.focused!! }
        val input=activeConnection()
        main { check(input.commitText("临时输入",1)) }
        waitFor("typed fixture edit") { main { "临时输入" in EditorSession.current!!.content().bodyText() } }
        Thread.sleep(350)
        val editedBody=main { EditorSession.current!!.content().bodyText() }
        val keyboardHeight=main { imeHeight() }
        fun historyButton(tool: Int,expected: String) {
            val heights=mutableListOf<Int>()
            val until=SystemClock.uptimeMillis()+900
            val frame=object: android.view.Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    heights+=imeHeight()
                    if(SystemClock.uptimeMillis()<until) android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            main { android.view.Choreographer.getInstance().postFrameCallback(frame) }
            toolbarPoint(tool).let { touch(it.first,it.second) }
            waitFor("undo/redo restores expected content") { main { EditorSession.current!!.content().bodyText()==expected } }
            Thread.sleep(950)
            main {
                android.view.Choreographer.getInstance().removeFrameCallback(frame)
                check(heights.size>=5 && heights.all { it>=keyboardHeight-2*screenDensity }) { "Keyboard collapsed during history operation: baseline=$keyboardHeight min=${heights.minOrNull()}" }
                check(EditorSession.current!!.focused===originalInput) { "Active input field replaced" }
            }
        }
        historyButton(3,originalBody)
        historyButton(4,editedBody)
        historyButton(3,originalBody)
        passed("undo-redo-keeps-keyboard-visible-every-frame-and-input-identity")
        val connection=activeConnection()
        // Android may wrap our bridge again in its nullable connection adapter.
        main { check(connection.performContextMenuAction(android.R.id.selectAll)) }
        waitFor("keyboard selects complete document") { main { EditorSession.current!!.articleSelected && EditorSession.current!!.selection.fromKeyboard } }
        main {
            val selected=connection.getSelectedText(0).toString()
            check(body in selected && ending in selected && "选字与全文测试" !in selected && ZWS !in selected)
            check(connection.performContextMenuAction(android.R.id.copy))
            val text=context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(context).toString()
            check(text==selected)
            check(connection.performContextMenuAction(android.R.id.selectAll))
            check(connection.performContextMenuAction(android.R.id.cut))
            check(EditorSession.current!!.title.text=="选字与全文测试" && EditorSession.current!!.content().bodyText().isEmpty())
            EditorSession.current!!.undo()
            check(EditorSession.current!!.content().bodyText().contains(body))
        }
        Thread.sleep(1000)
        main {
            val e=EditorSession.current!!
            check(e.title.text=="选字与全文测试" && e.blocks.size==2 && e.blocks.first().text==body && e.blocks.last().text==ending) { "Late IME write changed undo contents" }
        }
        val beforeCompose=activeConnection()
        main { check(beforeCompose.performContextMenuAction(android.R.id.selectAll)) }
        Thread.sleep(250)
        val composing=activeConnection()
        main { check(composing.setComposingText("ni",1)) }
        waitFor("keyboard composing replaces full document") { main { val e=EditorSession.current!!; e.title.text=="选字与全文测试" && e.blocks.size==1 && e.blocks.single().text=="ni" } }
        main { check(composing.commitText("你",1)) }
        waitFor("keyboard commits Chinese replacement") { main { EditorSession.current!!.blocks.singleOrNull()?.text=="你" } }
        main { EditorSession.current!!.undo() }
        Thread.sleep(700)
        main { val e=EditorSession.current!!; check(e.title.text=="选字与全文测试" && e.blocks.size==2 && e.blocks.first().text==body && e.blocks.last().text==ending) }
        passed("actual-keyboard-select-all-copy-cut-and-undo")
        val selectionCommand=activeConnection()
        main {
            val local=selectionCommand.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)!!.text
            check(selectionCommand.setSelection(0,local.length))
        }
        waitFor("IME direct range selects body across paragraphs") { main { EditorSession.current!!.articleSelected && ending in selectionCommand.getSelectedText(0).toString() } }
        main {
            check("选字与全文测试" !in selectionCommand.getSelectedText(0).toString())
            check(selectionCommand.performContextMenuAction(android.R.id.copy))
            val ctrlA=android.view.KeyEvent(0,0,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_A,0,android.view.KeyEvent.META_CTRL_ON)
            check(selectionCommand.sendKeyEvent(ctrlA))
            check(EditorSession.current!!.articleSelected)
            check(selectionCommand.deleteSurroundingText(1,0))
            check(EditorSession.current!!.title.text=="选字与全文测试" && EditorSession.current!!.content().bodyText().isEmpty())
            EditorSession.current!!.undo()
        }
        Thread.sleep(500)
        check(find("已全选全文")==null && find("复制全文")==null && find("剪切全文")==null)
        passed("keyboard-range-and-ctrl-a-select-body-only-without-extra-toolbar")
        val nextFocus=position("乙"); touch(nextFocus.first,nextFocus.second)
        Thread.sleep(900)
        repeat(8) { attempt ->
            val point=position(if(attempt%2==0) "乙" else "丙")
            touch(point.first,point.second); Thread.sleep(listOf(70L,130L,210L)[attempt%3]); touch(point.first,point.second)
            waitFor("double tap character $attempt") {
                main { val b=EditorSession.current!!.blocks.first(); b.value.selection.length==1 && b.value.text.substring(b.value.selection.min,b.value.selection.max)==if(attempt%2==0) "乙" else "丙" }
            }
            Thread.sleep(380)
        }
        val point=position("乙"); touch(point.first,point.second,950)
        waitFor("long press one character") { main { EditorSession.current!!.blocks.first().value.selection.length==1 } }
        passed("actual-double-tap-8-of-8-and-long-press")
        waitFor("two unified selection handles") { find("选择起点")!=null && find("选择终点")!=null }
        Thread.sleep(500)
        val endNode=find("选择终点")!!; endNode.refresh()
        val handle=Rect(); endNode.getBoundsInScreen(handle)
        val lastNode=find(ending)!!; lastNode.refresh()
        val lastBounds=Rect(); lastNode.getBoundsInScreen(lastBounds)
        val destination=main {
            val e=EditorSession.current!!
            val block=e.blocks.last()
            val caret=e.layouts.getValue(block.id).getCursorRect(block.value.text.length)
            (lastBounds.left+caret.left+handle.width()/2f) to (lastBounds.top+caret.bottom+handle.height()/2f)
        }
        val dragTime=SystemClock.uptimeMillis()
        val startX=handle.exactCenterX(); val startY=handle.exactCenterY()
        fun dragEvent(action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(dragTime,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(event,true)); event.recycle()
        }
        dragEvent(MotionEvent.ACTION_DOWN,startX,startY)
        repeat(16) { step -> Thread.sleep(20); val fraction=(step+1)/16f; dragEvent(MotionEvent.ACTION_MOVE,startX+(destination.first-startX)*fraction,startY+(destination.second-startY)*fraction) }
        dragEvent(MotionEvent.ACTION_UP,destination.first,destination.second)
        waitFor("drag selection across newline") { main { val e=EditorSession.current!!; e.selection.range?.end?.key==e.blocks.last().id && ending in e.selection.text() && "\n" in e.selection.text() } }
        check(find("选择起点")!=null && find("选择终点")!=null)
        val selectionImage=automation.takeScreenshot()!!
        File(context.getExternalFilesDir(null),"selection-cross-paragraph.png").outputStream().use { selectionImage.compress(Bitmap.CompressFormat.PNG,100,it) }
        selectionImage.recycle()
        passed("two-handles-drag-across-paragraph-boundary")
        val moving=boundsFor(find("选择终点")!!)
        val firstBounds=boundsFor(find(body,editable=true)!!)
        val cross=main {
            val e=EditorSession.current!!
            val b=e.blocks.first(); val caret=e.layouts.getValue(b.id).getCursorRect(1)
            (firstBounds.left+caret.left+moving.width()/2f) to (firstBounds.top+caret.bottom+moving.height()/2f)
        }
        val crossingTime=SystemClock.uptimeMillis()
        fun crossingEvent(action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(crossingTime,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(event,true)); event.recycle()
        }
        crossingEvent(MotionEvent.ACTION_DOWN,moving.exactCenterX(),moving.exactCenterY())
        repeat(16) { step -> Thread.sleep(18); val f=(step+1)/16f; crossingEvent(MotionEvent.ACTION_MOVE,moving.exactCenterX()+(cross.first-moving.exactCenterX())*f,moving.exactCenterY()+(cross.second-moving.exactCenterY())*f) }
        waitFor("end handle crosses fixed start") { main { EditorSession.current!!.selection.text()=="甲" && EditorSession.current!!.selection.dragStart==true } }
        repeat(16) { step -> Thread.sleep(18); val f=(step+1)/16f; crossingEvent(MotionEvent.ACTION_MOVE,cross.first+(destination.first-cross.first)*f,cross.second+(destination.second-cross.second)*f) }
        crossingEvent(MotionEvent.ACTION_UP,destination.first,destination.second)
        waitFor("crossing back keeps original anchor") { main { val e=EditorSession.current!!; e.selection.range?.start?.offset==2 && ending in e.selection.text() } }
        passed("end-handle-crosses-start-and-back-with-fixed-anchor")

        waitFor("native select all action") { find("全选",exact=true)!=null }
        fun click(label: String) {
            waitFor("action $label") { find(label)!=null }
            // Read bounds after the sheet/navigation animation has settled.
            Thread.sleep(500)
            val node=find(label,exact=true) ?: error("Missing action: $label")
            node.refresh()
            var action: AccessibilityNodeInfo? = node
            while (action != null && !action.isClickable) action = action.parent
            val clicked = action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","UI click $label: semantic=$clicked bounds=$bounds\n") })
            if (!clicked) touch(bounds.exactCenterX(),bounds.exactCenterY())
        }
        click("全选")
        waitFor("all article selected") { main { EditorSession.current!!.articleSelected } }
        waitFor("copy entire article action") { (find("复制全文",exact=true)!=null || find("复制",exact=true)!=null) }
        click(if(find("复制全文",exact=true)!=null) "复制全文" else "复制")
        waitFor("entire article clipboard") {
            main {
                val clip=context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                "选字与全文测试" !in clip && body in clip && ending in clip && ZWS !in clip
            }
        }
        passed("native-select-all-and-whole-document-copy")
        main {
            AppState.goHome(Tab.HOME)
            activity.setContent { KongyeTheme { Box(Modifier.fillMaxSize().background(Kongye.Surface),contentAlignment=Alignment.Center) { LineCat(DayPart.AFTERNOON,{listOf("下午好")},Modifier.size(CAT_W.dp,CAT_H.dp)) } } }
        }
        Thread.sleep(650)
        val image=automation.takeScreenshot() ?: error("Cat screenshot failed")
        val crop=Bitmap.createBitmap(image,(image.width-400)/2,(image.height-400)/2,400,400)
        val density=context.resources.displayMetrics.density
        val bx=(200-CAT_W*density/2+12*0.7f*density).toInt()
        val by=(200-CAT_H*density/2+78*0.7f*density).toInt()
        val radius=(10*0.7f*density).toInt()
        var yarnPixels=0
        for(y in (by-radius).coerceAtLeast(0)..(by+radius).coerceAtMost(399)) for(x in (bx-radius).coerceAtLeast(0)..(bx+radius).coerceAtMost(399)) {
            val pixel=crop.getPixel(x,y)
            if(android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel)<480) yarnPixels++
        }
        check(yarnPixels>30) { "The afternoon yarn ball is not visible" }
        val output=File(context.getExternalFilesDir(null),"afternoon-cat.png")
        output.outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG,100,it)) }
        crop.recycle(); image.recycle()
        passed("afternoon-cat-yarn-render")
        // Exercise the actual accessibility action, SVG path rendering and theme fills.
        Thread.sleep(4300)
        val cat=find("小猫",exact=true) ?: error("Cat accessibility node missing")
        val yawnAction=cat.actionList.firstOrNull { it.label?.toString()=="打呵欠" } ?: error("Yawn action missing")
        check(cat.performAction(yawnAction.id))
        waitFor("SVG yawn action") { find("小猫，打呵欠",exact=true)!=null }
        fun catFrame(name: String): Bitmap {
            val frame=automation.takeScreenshot() ?: error("Yawn screenshot failed")
            val result=Bitmap.createBitmap(frame,(frame.width-400)/2,(frame.height-400)/2,400,400)
            frame.recycle()
            File(context.getExternalFilesDir(null),name).outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG,100,it)) }
            return result
        }
        Thread.sleep(120)
        val early=catFrame("cat-yawn-early.png")
        Thread.sleep(620)
        val open=catFrame("cat-yawn-open.png")
        fun svgX(x: Float)=(200-CAT_W*density/2+(x-6)*0.7f*density).toInt().coerceIn(0,399)
        fun svgY(y: Float)=(200-CAT_H*density/2+(y+10.73f-16)*0.7f*density).toInt().coerceIn(0,399)
        var mouthChanges=0
        for(y in svgY(47f)..svgY(69f)) for(x in svgX(39f)..svgX(68f)) if(early.getPixel(x,y)!=open.getPixel(x,y)) mouthChanges++
        check(mouthChanges>30) { "SVG mouth did not animate: $mouthChanges pixels" }
        early.recycle(); open.recycle()
        val wasDark=main { Kongye.dark }
        try {
            main { Kongye.dark=true }
            Thread.sleep(180)
            val dark=catFrame("cat-yawn-dark.png")
            val bodyPixel=dark.getPixel(svgX(80f),svgY(75f))
            check(android.graphics.Color.red(bodyPixel)<80 && android.graphics.Color.green(bodyPixel)<80 && android.graphics.Color.blue(bodyPixel)<80) { "SVG body has a white fill in dark theme" }
            dark.recycle()
        } finally { main { Kongye.dark=wasDark } }
        passed("svg-yawn-action-animated-mouth-and-dark-theme")
        main {
            val item=Repository.createDoc(null,"版本对比测试",DocContent(listOf(Block(text="这里是尚未修改的正文"))))
            Repository.addVersion(item.id,VersionKind.LOCAL,item.title,DocContent(listOf(Block(text="这里是删除文字"))))
            val editor=EditorSession.obtain(item.id,false)
            AppState.push(com.kongye.docs.ui.Screen.Editor(item.id))
            editor.onValueChange(editor.blocks.first(),androidx.compose.ui.text.input.TextFieldValue(ZWS+"这里是新增文字"))
            check(Repository.loadDoc(item.id).bodyText()=="这里是尚未修改的正文")
            val version=Repository.versions(item.id).first()
            activity.setContent { KongyeTheme { VersionPreviewScreen(item.id,version.id) } }
        }
        waitFor("local version colored change preview") { find("这里是")?.text?.toString()?.let { "删除" in it && "新增" in it && "尚未修改" !in it }==true }
        Thread.sleep(250)
        val preview=automation.takeScreenshot() ?: error("Diff screenshot failed")
        File(context.getExternalFilesDir(null),"local-version-diff.png").outputStream().use { check(preview.compress(Bitmap.CompressFormat.PNG,100,it)) }
        preview.recycle()
        passed("local-version-diff-against-current-unsaved-editor")
        click("版本操作")
        waitFor("version operations menu") { listOf("恢复此版本","生成副本文档","删除版本").all { find(it,exact=true)!=null } }
        val menu = automation.takeScreenshot() ?: error("Version menu screenshot failed")
        File(context.getExternalFilesDir(null),"local-version-actions.png").outputStream().use { check(menu.compress(Bitmap.CompressFormat.PNG,100,it)) }
        menu.recycle()
        val originalId = main { (AppState.current as com.kongye.docs.ui.Screen.Editor).docId }
        val selectedId = main { Repository.versions(originalId).first().id }
        val before = main { Repository.items.value.map { it.id }.toSet() }
        click("生成副本文档")
        waitFor("version copy created") { main { Repository.items.value.any { it.id !in before } } }
        main {
            val copy=Repository.items.value.single { it.id !in before }
            check(Repository.loadDoc(copy.id).bodyText()=="这里是删除文字")
            check(Repository.loadDoc(originalId).bodyText()=="这里是新增文字")
            check(AppState.current is com.kongye.docs.ui.Screen.Editor)
            check(AppState.pop())
            check(AppState.current == com.kongye.docs.ui.Screen.Home && AppState.tab == Tab.FILES)
        }
        waitFor("menu ready after copy") { find("版本操作",exact=true)!=null }
        click("版本操作")
        waitFor("delete option") { find("删除版本",exact=true)!=null }
        click("删除版本")
        waitFor("delete confirmation") { find("删除",exact=true)!=null }
        check(main { Repository.versions(originalId).any { it.id==selectedId } })
        click("取消")
        waitFor("delete canceled") { find("删除",exact=true)==null }
        check(main { Repository.versions(originalId).any { it.id==selectedId } })
        click("版本操作")
        waitFor("delete option again") { find("删除版本",exact=true)!=null }
        click("删除版本")
        waitFor("confirm delete again") { find("删除",exact=true)!=null }
        click("删除")
        waitFor("selected version removed") { main { Repository.versions(originalId).none { it.id==selectedId } } }
        main { check(Repository.loadDoc(originalId).bodyText()=="这里是新增文字") }
        passed("version-menu-copy-and-confirmed-delete-preserve-original")
        Thread.sleep(700)
        main {
            Repository.addVersion(originalId,VersionKind.LOCAL,"旧标题",DocContent(listOf(Block(text="恢复目标正文"))))
            val target=Repository.versions(originalId).first()
            val editor=EditorSession.obtain(originalId,false)
            editor.onValueChange(editor.blocks.first(),androidx.compose.ui.text.input.TextFieldValue(ZWS+"恢复前未保存的文字"))
            AppState.push(com.kongye.docs.ui.Screen.Editor(originalId))
            AppState.push(com.kongye.docs.ui.Screen.Versions(originalId))
            AppState.push(com.kongye.docs.ui.Screen.VersionPreview(originalId,target.id))
            activity.setContent { KongyeTheme { VersionPreviewScreen(originalId,target.id) } }
        }
        waitFor("restore preview ready") { find("版本操作",exact=true)!=null }
        click("版本操作")
        waitFor("restore option") { find("恢复此版本",exact=true)!=null }
        Thread.sleep(700)
        click("恢复此版本")
        waitFor("restore confirmation") { find("恢复",exact=true)!=null }
        click("恢复")
        waitFor("selected version restored") { main { Repository.loadDoc(originalId).bodyText()=="恢复目标正文" } }
        main {
            check(EditorSession.snapshot(originalId)!!.second.bodyText()=="恢复目标正文")
            check(Repository.versions(originalId).any { it.content.bodyText()=="恢复前未保存的文字" })
        }
        passed("version-menu-restore-keeps-unsaved-draft-as-safety-version")


        } catch (error: Throwable) {
            automation.takeScreenshot()?.let { bitmap -> File(context.getExternalFilesDir(null),"feature-ui-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            throw error
        } finally {
            main {
                AppState.goHome(Tab.HOME)
                activity.finish()
                Settings.init(context); Repository.init(context)
                BackupJobService.schedule(context)
            }
        }
    }
}
