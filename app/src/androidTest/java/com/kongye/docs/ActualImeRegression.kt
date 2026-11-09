package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
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
import java.io.File

/** Touch the installed CyIME cursor panel itself, including its end-of-text preflight. */
object ActualImeRegression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrument.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String, timeout: Long = 12000, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
            error("Timed out: $label")
        }
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(text: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.contains(text) == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "actual-ime-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1600)
            val doc = main { Repository.createDoc(null, "实际键盘光标测试", DocContent(listOf(
                Block(type = BlockType.TODO, text = "第一段实际键盘测试文字甲乙丙丁".repeat(3)),
                Block(type = BlockType.TODO, text = "第二段实际键盘测试文字甲乙丙丁".repeat(3)),
                Block(text = ""),
                Block(type = BlockType.TODO, text = "最后一段实际键盘测试文字"),
            ))) }
            main { AppState.openDoc(doc.id) }
            waitFor("body semantics") { find("第一段实际键盘") != null }
            val node = find("第一段实际键盘")!!
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.left + 140f, bounds.top + 35f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event, true)); event.recycle(); Thread.sleep(60)
            }
            waitFor("keyboard") { main { (ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 100 } }
            Thread.sleep(650) // Read final IME bounds after its opening animation.
            val imeId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            check(imeId == "com.cyletix.cyime/com.kingzcheung.xime.service.XimeInputMethodService") { "Use the user's current CyIME for this test: $imeId" }
            val geometry = main {
                Triple(activity.window.decorView.width.toFloat(), activity.window.decorView.height.toFloat(),
                    ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom.toFloat())
            }
            val (width, bottom, height) = geometry
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Actual IME bounds: width=$width bottom=$bottom height=$height\n") })
            val top = bottom - height
            fun tap(x: Float, y: Float) {
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    check(auto.injectInputEvent(event, true)); event.recycle(); Thread.sleep(50)
                }
            }
            // These positions belong to the existing CyIME circular cursor panel,
            // scaled from the real IME window, not to app buttons or injected keys.
            fun panelButton(x: Float, y: Float) { tap(width * x, top + height * y); Thread.sleep(250) }
            fun at(index: Int) = main { val e = EditorSession.current!!; e.focusedId == e.blocks[index].id && e.pendingFocus == null }
            fun end(index: Int) {
                panelButton(.73f, .282f)
                waitFor("actual keyboard paragraph end $index") { main { val e = EditorSession.current!!; e.focusedId == e.blocks[index].id && e.focused!!.value.selection.end == e.focused!!.value.text.length } }
            }
            fun downTo(index: Int) { panelButton(.5f, .773f); waitFor("actual keyboard Down to $index") { at(index) } }
            val original = main { EditorSession.current!!.content() to EditorSession.current!!.version }
            panelButton(.442f, .075f)
            Thread.sleep(500) // Wait for the keyboard's cursor-panel animation.
            end(0)
            downTo(1)
            passed("actual-cyime-paragraph-end-button-then-down-enters-next-todo-paragraph")

            end(1); downTo(2); downTo(3)
            passed("actual-cyime-down-crosses-empty-paragraph-without-stopping")

            end(3)
            panelButton(.5f, .773f)
            check(at(3)) { "Last paragraph moved out of document" }
            passed("actual-cyime-down-at-document-end-stays-in-body")

            panelButton(.5f, .32f); waitFor("actual Up into empty") { at(2) }
            panelButton(.5f, .32f); waitFor("actual Up into previous paragraph") { at(1) }
            passed("actual-cyime-up-crosses-empty-paragraph-and-returns")

            main { val e = EditorSession.current!!; e.requestFocus(e.blocks[0], e.blocks[0].value.text.length) }
            waitFor("focus first end") { at(0) }
            Thread.sleep(300)
            val point = main {
                val e = EditorSession.current!!; val b = e.blocks[0]
                val r = e.layouts[b.id]!!.getCursorRect(b.value.text.length)
                e.selection.coordinates[b.id]!!.localToRoot(r.center) + androidx.compose.ui.geometry.Offset(12f, 0f)
            }
            tap(point.x, point.y)
            waitFor("tap exact paragraph end") { main { val e = EditorSession.current!!; e.visibleInsertionHandle != null && e.blocks[0].value.selection.end == e.blocks[0].value.text.length } }
            downTo(1)
            passed("actual-cyime-down-after-tapping-paragraph-end-enters-next-paragraph")
            main { check(EditorSession.current!!.content() == original.first && EditorSession.current!!.version == original.second) { "Cursor panel changed text" } }
            check(android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) == imeId)
            passed("actual-cyime-panel-navigation-preserves-document-and-current-keyboard")
            val bitmap = auto.takeScreenshot()!!
            File(context.getExternalFilesDir(null), "actual-ime-after-down.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } catch (failure: Throwable) {
            val bitmap = auto.takeScreenshot()
            bitmap?.let {
                File(context.getExternalFilesDir(null), "actual-ime-panel-failure.png").outputStream().use { stream -> it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
                it.recycle()
            }
            throw failure
        } finally {
            main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) }
        }
    }
}
