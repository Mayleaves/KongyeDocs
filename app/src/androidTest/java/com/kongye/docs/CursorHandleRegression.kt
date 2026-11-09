package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
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
import kotlin.math.abs

/** Pixel alignment and actual finger drags; all content is synthetic and isolated. */
object CursorHandleRegression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrument.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "cursor-handle-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun roots() = (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).filter { it.packageName?.toString() == context.packageName }
        fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (match(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return roots().firstNotNullOfOrNull { walk(it) }
        }
        fun handle() = find { it.contentDescription?.toString() == "插入手柄" }?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
        fun inject(start: Long, action: Int, p: Offset) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, p.x, p.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(auto.injectInputEvent(event, true)); event.recycle()
        }
        fun touch(p: Offset) {
            val start = SystemClock.uptimeMillis()
            inject(start, MotionEvent.ACTION_DOWN, p); Thread.sleep(50); inject(start, MotionEvent.ACTION_UP, p)
        }
        fun drag(from: Offset, to: Offset, hold: Long = 0L) {
            val start = SystemClock.uptimeMillis()
            inject(start, MotionEvent.ACTION_DOWN, from); Thread.sleep(60)
            for (i in 1..18) { inject(start, MotionEvent.ACTION_MOVE, from + (to - from) * (i / 18f)); Thread.sleep(25) }
            if (hold > 0) Thread.sleep(hold)
            inject(start, MotionEvent.ACTION_UP, to)
            instrument.waitForIdleSync(); Thread.sleep(200)
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        fun editor() = EditorSession.current!!
        fun ime() = main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun caret(index: Int, offset: Int) = main {
            val e = editor(); val b = e.blocks[index]
            val r = e.layouts[b.id]!!.getCursorRect(offset)
            e.selection.coordinates[b.id]!!.localToRoot(r.center)
        }
        fun dragTo(index: Int, offset: Int) {
            val bounds = handle() ?: error("No handle to drag")
            val from = Offset(bounds.exactCenterX(), bounds.exactCenterY())
            val current = main { editor().visibleInsertionHandle!! }
            val source = main { editor().blocks.indexOfFirst { it.id == current.key } }
            val grab = from - caret(source, current.offset)
            drag(from, caret(index, offset) + grab)
            val state = main { "actual=${editor().blocks.indexOfFirst { it.id == editor().focusedId }}, handle=${editor().visibleInsertionHandle}, target=$index, from=$from, grab=$grab" }
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Drag: $state\n") })
            waitFor("drag to $index") { main { editor().focusedId == editor().blocks[index].id && editor().visibleInsertionHandle?.key == editor().blocks[index].id } }
            waitFor("handle follows destination $index") {
                val b = handle() ?: return@waitFor false
                val expected = main {
                    val e = editor(); val point = e.visibleInsertionHandle!!
                    val rect = e.layouts[point.key]!!.getCursorRect(point.offset)
                    e.selection.coordinates[point.key]!!.localToRoot(Offset(rect.left, rect.bottom))
                }
                abs(b.exactCenterX() - expected.x) < 5 && abs(b.top - expected.y) < 3
            }
            main { check(!editor().selection.active && editor().focused!!.value.selection.collapsed) }
            check(ime() > 100) { "Keyboard closed during drag" }
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1600)
            val doc = main { Repository.createDoc(null, "手柄跨段检查", DocContent(listOf(
                Block(text = "第一段甲乙丙丁戊己庚辛"),
                Block(text = "第二段拖动目标文字"),
                // Keep the empty-line handle clear of the test phone's left-edge
                // FooView accessibility overlay, which owns touches over that area.
                Block(text = "", indent = 2),
                Block(type = BlockType.BULLET, text = "列表拖动目标文字"),
                Block(type = BlockType.CODE, text = "code first\ncode second"),
            ) + (1..24).map { Block(text = "滚动目标第${it}段文字") })) }
            main { AppState.openDoc(doc.id) }
            waitFor("body rendered") { find { it.text?.contains("第一段甲乙") == true } != null }
            val node = find { it.text?.contains("第一段甲乙") == true }!!
            val field = Rect().also { node.getBoundsInScreen(it) }
            touch(Offset(field.left + 100f, field.exactCenterY()))
            waitFor("insertion handle and keyboard") { handle() != null && ime() > 100 }
            Thread.sleep(250)
            // The hidden per-field popup must not own touch input over the app handle.
            check(auto.windows.count { it.root?.packageName?.toString() == context.packageName } == 1) { "Native invisible handle popup still present" }
            val original = main { editor().content() }
            val version = main { editor().version }
            val h = handle()!!
            val lineHeight = main { val e = editor(); e.layouts[e.focusedId]!!.getCursorRect(e.focused!!.value.selection.end).height }
            var alignment: Float? = null
            repeat(10) {
                if (alignment == null) {
                    val bitmap = auto.takeScreenshot()!!
                    fun blue(x: Int, y: Int): Boolean {
                        val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                        return b > 140 && b > r + 45 && b > g + 20
                    }
                    fun center(top: Int, bottom: Int): Float? {
                        var sum = 0L; var count = 0
                        for (y in top..bottom) for (x in h.centerX() - 12..h.centerX() + 12) if (blue(x, y)) { sum += x; count++ }
                        return if (count == 0) null else sum.toFloat() / count
                    }
                    val stem = center((h.top - lineHeight * .65f).toInt(), (h.top - lineHeight * .4f).toInt())
                    val tip = center(h.top, h.top + 3)
                    if (stem != null && tip != null) {
                        alignment = abs(stem - tip)
                        val cropTop = (h.top - lineHeight * 1.2f).toInt().coerceAtLeast(0)
                        val crop = Bitmap.createBitmap(bitmap, (h.centerX() - 100).coerceAtLeast(0), cropTop, 200, (lineHeight * 1.2f + h.height() + 10).toInt())
                        File(context.getExternalFilesDir(null), "cursor228-alignment.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        crop.recycle()
                    }
                    bitmap.recycle(); Thread.sleep(100)
                }
            }
            check(alignment != null && alignment!! <= 1.2f) { "Rendered cursor/handle centres differ: $alignment px" }
            passed("rendered-cursor-and-handle-tip-align-within-1-pixel-no-hidden-popup")

            dragTo(1, 5); dragTo(2, 1); dragTo(3, 5); dragTo(0, 6)
            main { check(editor().content() == original && editor().version == version) }
            passed("actual-handle-drag-crosses-paragraphs-empty-list-and-back-with-keyboard")

            val handle = handle()!!
            val from = Offset(handle.exactCenterX(), handle.exactCenterY())
            val viewportBottom = main { val v = editor().selection.viewport!!; v.localToRoot(Offset(0f, v.size.height.toFloat())).y }
            drag(from, Offset(from.x, viewportBottom - 8), hold = 1400)
            main { check(editor().blocks.indexOfFirst { it.id == editor().focusedId } >= 6) { "Holding handle at edge did not scroll across paragraphs" }; check(editor().content() == original && editor().version == version) }
            passed("hold-insertion-handle-at-viewport-edge-autoscrolls-across-paragraphs")

            val expected = main {
                val b = editor().focused!!; val offset = b.value.selection.end - 1
                b.id to b.text.replaceRange(offset, offset, "新")
            }
            main {
                fun connection(view: View): InputConnection? {
                    if (view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
                    if (view is ViewGroup) for (i in 0 until view.childCount) connection(view.getChildAt(i))?.let { return it }
                    return null
                }
                check(connection(activity.window.decorView)!!.commitText("新", 1))
            }
            waitFor("type in dragged destination") { main { editor().blocks.first { it.id == expected.first }.text == expected.second && editor().visibleInsertionHandle == null } }
            passed("typing-after-cross-paragraph-handle-drag-uses-destination-and-hides-handle")
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
