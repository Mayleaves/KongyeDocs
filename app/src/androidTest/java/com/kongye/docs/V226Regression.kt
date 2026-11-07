package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.EditorSession
import com.kongye.docs.ui.editor.TITLE_KEY
import java.io.File

/** v2.26：点在字与字之间放下光标时显示蓝色插入手柄，可拖动；开始打字后变回普通文本光标 */
object V226Regression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(40) }; error("Timed out: $label")
        }
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun appRoots() = (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow))
            .filter { it.packageName?.toString() == context.packageName }
        fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (match(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return appRoots().firstNotNullOfOrNull { walk(it) }
        }
        fun find(label: String) = findNode { it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label }
        /** 只认无障碍描述「插入手柄」，不会误中含这几个字的正文 */
        fun handleNode() = findNode { it.contentDescription?.toString() == "插入手柄" }
        fun handleBounds() = handleNode()?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
        fun inject(down: Long, action: Int, x: Float, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                .let { check(auto.injectInputEvent(it, true)); it.recycle() }
        }
        fun touch(x: Float, y: Float) {
            val start = SystemClock.uptimeMillis()
            inject(start, MotionEvent.ACTION_DOWN, x, y)
            Thread.sleep(40)
            inject(start, MotionEvent.ACTION_UP, x, y)
        }
        fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
            val start = SystemClock.uptimeMillis()
            inject(start, MotionEvent.ACTION_DOWN, fromX, fromY)
            Thread.sleep(60)
            val steps = 14
            for (i in 1..steps) {
                inject(start, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps)
                Thread.sleep(25)
            }
            Thread.sleep(80)
            inject(start, MotionEvent.ACTION_UP, toX, toY)
        }
        fun click(label: String) {
            Thread.sleep(300)
            waitFor("click $label") {
                runCatching {
                    var node = find(label) ?: return@runCatching false
                    while (!node.isClickable) node = node.parent ?: return@runCatching false
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            }
        }
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "v226-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        val density = context.resources.displayMetrics.density
        fun ime() = main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        /** 只截键盘上方的部分，键盘候选栏里的内容不进截图 */
        fun shot(name: String) {
            val bitmap = auto.takeScreenshot()!!
            val keep = (bitmap.height - ime()).coerceIn(1, bitmap.height)
            val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, keep)
            File(context.getExternalFilesDir(null), name).outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
            top.recycle(); bitmap.recycle()
        }
        fun commit(text: String) = main {
            fun connection(view: android.view.View): android.view.inputmethod.InputConnection? {
                if (view.onCheckIsTextEditor()) view.onCreateInputConnection(android.view.inputmethod.EditorInfo())?.let { return it }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) connection(view.getChildAt(i))?.let { return it }
                return null
            }
            check((connection(activity.window.decorView) ?: error("Input connection missing")).commitText(text, 1))
        }
        val title = "手柄标题测试"
        val body = "第一段文字用来测试光标下面的蓝色手柄和拖动效果"
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(2000)
            val doc = main { Repository.createDoc(null, title, DocContent(listOf(Block(text = body)))) }
            val blockId = main { Repository.loadDoc(doc.id).blocks.first().id }
            main { AppState.openDoc(doc.id) }
            waitFor("editor") { find("第一段文字") != null }
            Thread.sleep(500)
            check(handleNode() == null && main { EditorSession.current!!.visibleInsertionHandle } == null) { "Handle shown before any tap" }

            // 1. 点在正文字缝里：光标 + 下方蓝色手柄，手柄正对光标
            val field = Rect().also { find("第一段文字")!!.getBoundsInScreen(it) }
            // 段落折成两行时中线落在第二行末尾，点第一行
            val line1 = field.top + 14 * density
            touch(field.left + 120 * density, line1)
            waitFor("keyboard + focus") { ime() > 160 * density && main { EditorSession.current?.focusedId } == blockId }
            waitFor("handle shown") { handleNode() != null }
            val tapped = main {
                val e = EditorSession.current!!
                val h = e.visibleInsertionHandle ?: error("No handle state")
                val sel = e.focused!!.value.selection
                check(h.key == blockId && sel.collapsed && sel.start == h.offset) { "Handle not at caret: $h vs $sel" }
                check(h.offset in 2 until body.length) { "Tap did not land between characters: ${h.offset}" }
                h.offset
            }
            val first = handleBounds()!!
            check(first.top >= field.top && first.top < field.bottom) { "Handle not under the caret line: $first in $field" }
            check(first.width() in (18 * density).toInt()..(26 * density).toInt()) { "Handle size ${first.width()}" }
            Thread.sleep(300)
            shot("v226-tap-handle.png")
            passed("tap-between-characters-shows-blue-insertion-handle-at-caret")

            // 2. 拖手柄：光标跟着走，文字不变、不产生选区、焦点不变
            drag(first.exactCenterX(), first.exactCenterY(), first.exactCenterX() + 90 * density, first.exactCenterY())
            waitFor("caret moved right") { main { EditorSession.current!!.focused!!.value.selection.start } > tapped }
            val right = main {
                val e = EditorSession.current!!
                val sel = e.focused!!.value.selection
                check(sel.collapsed && !e.selection.active && e.focusedId == blockId) { "Drag created a selection or moved focus" }
                check(e.visibleInsertionHandle?.offset == sel.start) { "Handle left the caret after drag: handle=${e.insertionHandle} visible=${e.visibleInsertionHandle} sel=$sel tapped=$tapped" }
                check(e.content().bodyText() == body) { "Drag changed text" }
                sel.start
            }
            waitFor("handle follows") { handleBounds()?.let { it.centerX() > first.centerX() + 40 * density } == true }
            drag(handleBounds()!!.exactCenterX(), handleBounds()!!.exactCenterY(), field.left + 10 * density, handleBounds()!!.exactCenterY())
            waitFor("caret moved left") { main { EditorSession.current!!.focused!!.value.selection.start } < tapped }
            main { val e = EditorSession.current!!; check(e.visibleInsertionHandle?.offset == e.focused!!.value.selection.start && e.content().bodyText() == body) }
            check(ime() > 160 * density) { "Keyboard closed while dragging" }
            shot("v226-drag-handle.png")
            passed("drag-insertion-handle-moves-caret-both-ways-right=$right")

            // 3. 开始打字：手柄消失，回到普通文本光标，字打在光标处
            val caret = main { EditorSession.current!!.focused!!.value.selection.start }
            commit("新")
            waitFor("typed") { main { EditorSession.current!!.content().bodyText() } == body.substring(0, caret - 1) + "新" + body.substring(caret - 1) }
            waitFor("handle hidden after typing") { handleNode() == null }
            main {
                val e = EditorSession.current!!
                check(e.visibleInsertionHandle == null && e.focusedId == blockId && e.focused!!.value.selection.collapsed)
            }
            Thread.sleep(300)
            shot("v226-after-typing.png")
            passed("typing-hides-handle-leaving-plain-caret")

            // 4. 再点一次又出现；点标题，手柄跟到标题
            touch(field.left + 60 * density, line1)
            waitFor("handle again") { handleNode() != null && main { EditorSession.current!!.visibleInsertionHandle?.key } == blockId }
            val titleRect = Rect().also { find(title)!!.getBoundsInScreen(it) }
            touch(titleRect.left + 50 * density, titleRect.exactCenterY())
            waitFor("title handle") { main { EditorSession.current!!.visibleInsertionHandle?.key } == TITLE_KEY && handleNode() != null }
            val titleHandle = handleBounds()!!
            check(titleHandle.top >= titleRect.top && titleHandle.top < titleRect.bottom + 4 * density) { "Title handle misplaced: $titleHandle in $titleRect" }
            commit("改")
            waitFor("title typed") { main { EditorSession.current!!.title.text.contains("改") } }
            waitFor("title handle hidden") { handleNode() == null && main { EditorSession.current!!.visibleInsertionHandle } == null }
            passed("title-tap-shows-handle-and-typing-hides-it")

            // 5. 点「完成」结束编辑：手柄不留在页面上
            touch(field.left + 60 * density, line1)
            waitFor("handle before done") { handleNode() != null }
            click("完成")
            waitFor("done hides handle") { handleNode() == null && main { EditorSession.current!!.insertionHandle } == null }
            passed("finish-editing-removes-handle")
        } catch (error: Throwable) { shot("v226-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
