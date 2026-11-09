package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File
import kotlin.math.abs

/** Exercise the same InputConnection.sendKeyEvent path used by keyboard cursor panels. */
object CursorNavigationRegression {
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
            val root = File(context.filesDir, "cursor-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        fun editor() = EditorSession.current!!
        fun connection(view: View = activity.window.decorView): InputConnection? {
            if (view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
            if (view is ViewGroup) for (i in 0 until view.childCount) connection(view.getChildAt(i))?.let { return it }
            return null
        }
        fun ime() = main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun focus(index: Int, offset: Int) {
            main { editor().cursorNavigation.reset(); editor().requestFocus(editor().blocks[index], offset) }
            waitFor("focus $index/$offset") { main { editor().pendingFocus == null && editor().focusedId == editor().blocks[index].id && editor().focused!!.value.selection == TextRange(offset) } }
            Thread.sleep(200)
        }
        fun arrow(down: Boolean, hardware: Boolean = false) {
            val key = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            if (hardware) instrument.sendKeyDownUpSync(key)
            else main {
                val input = connection() ?: error("No input connection")
                check(input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                check(input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
            }
            instrument.waitForIdleSync()
            Thread.sleep(150)
        }
        fun at(index: Int, line: Int? = null) = main {
            val e = editor(); val b = e.blocks[index]
            e.focusedId == b.id && (line == null || e.layouts[b.id]!!.getLineForOffset(b.value.selection.end) == line)
        }
        fun x() = main {
            val e = editor(); val b = e.focused!!
            e.selection.coordinates[b.id]!!.localToRoot(Offset.Zero).x + e.layouts[b.id]!!.getCursorRect(b.value.selection.end).left
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1800)
            val doc = main { Repository.createDoc(null, "跨段光标测试", DocContent(listOf(
                Block(text = "甲乙丙丁戊己庚辛壬癸".repeat(12)),
                Block(text = "短"),
                Block(text = ""),
                Block(type = BlockType.BULLET, text = "列表跨段光标测试文字".repeat(10), indent = 1),
                Block(type = BlockType.CODE, text = "first code line\nsecond code line\nthird code line"),
                Block(type = BlockType.DIVIDER),
                Block(type = BlockType.H2, text = "居中标题", align = Align.CENTER),
                Block(text = "最后一段跨行光标测试".repeat(12)),
            ))) }
            main { AppState.openDoc(doc.id) }
            waitFor("layouts") { main { EditorSession.current?.layouts?.size?.let { it >= 8 } == true } }
            waitFor("window focus") { main { activity.hasWindowFocus() } }
            fun bodyNode(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.contains("甲乙丙丁") == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { bodyNode(it)?.let { return it } }
                return null
            }
            fun findBody() = (instrument.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(instrument.uiAutomation.rootInActiveWindow))
                .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull { bodyNode(it) }
            waitFor("body semantics") { findBody() != null }
            val node = findBody() ?: error("No body node")
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val tap = Offset(bounds.left + 100f, bounds.top + 40f)
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Body tap=$tap bounds=$bounds\n") })
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, tap.x, tap.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(instrument.uiAutomation.injectInputEvent(event, true))
                event.recycle()
                Thread.sleep(60)
            }
            main { editor().requestFocus(editor().blocks[0], 8) }
            waitFor("keyboard visible after tap") { ime() > 100 }
            val original = main { editor().content() }
            val originalVersion = main { editor().version }
            val firstLastLine = main { editor().layouts[editor().blocks[0].id]!!.lineCount - 1 }
            val start = main {
                val l = editor().layouts[editor().blocks[0].id]!!
                (l.getLineStart(firstLastLine) + 7).coerceAtMost(l.layoutInput.text.length)
            }
            focus(0, start)
            waitFor("keyboard visible") { ime() > 100 }
            val preferredX = x()
            arrow(true); check(at(1, 0)) { "IME Down did not cross paragraph" }
            arrow(true); check(at(2, 0)) { "IME Down did not enter empty paragraph" }
            main { check(editor().focused!!.value.selection.end == 1) { "Caret entered sentinel" } }
            arrow(true); check(at(3, 0)) { "IME Down did not enter list" }
            check(abs(x() - preferredX) < 35 * context.resources.displayMetrics.density) { "Preferred column lost across short/empty paragraphs" }
            check(ime() > 100) { "Keyboard closed across paragraphs" }
            passed("ime-down-crosses-short-empty-list-paragraphs-keeps-column-and-keyboard")

            arrow(false); check(at(2, 0))
            arrow(false); check(at(1, 0))
            arrow(false); check(at(0, firstLastLine))
            check(abs(x() - preferredX) < 2) { "Preferred column not restored" }
            passed("ime-up-returns-to-previous-paragraph-last-line-and-restores-column")

            focus(0, 8)
            arrow(true); check(at(0, 1)) { "Wrapped line was skipped" }
            arrow(false); check(at(0, 0))
            passed("wrapped-lines-move-within-paragraph-before-crossing")

            focus(4, 5)
            arrow(true); check(at(4, 1))
            arrow(true); check(at(4, 2))
            arrow(true); check(at(6, 0)) { "Divider did not skip to heading" }
            arrow(false); check(at(4, 2))
            passed("code-newlines-and-divider-heading-navigation")

            focus(0, 1)
            arrow(false); check(at(0, 0))
            main { check(!editor().titleFocused && editor().focused!!.value.selection.end == 1) }
            val lastEnd = main { editor().blocks[7].value.text.length }
            focus(7, lastEnd)
            arrow(true); check(at(7))
            main { check(editor().focused!!.value.selection.end == lastEnd) }
            passed("document-boundaries-stay-in-body-and-exclude-sentinel")

            focus(1, 2)
            arrow(true, hardware = true); check(at(2, 0))
            arrow(false, hardware = true); check(at(1, 0))
            passed("physical-arrow-keys-cross-paragraphs")

            for ((index, next) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 6, 6 to 7)) {
                val end = main { editor().blocks[index].value.text.length }
                focus(index, end)
                arrow(true)
                check(at(next, 0)) { "Down at paragraph $index absolute end did not enter $next" }
            }
            passed("down-at-absolute-end-crosses-wrapped-short-empty-list-code-and-heading-paragraphs")

            val paragraphEnd = main { editor().blocks[0].value.text.length }
            focus(0, 2)
            main { check(connection()!!.setSelection(paragraphEnd, paragraphEnd)) }
            waitFor("IME sets caret at exact paragraph end") { main { editor().blocks[0].value.selection == TextRange(paragraphEnd) } }
            arrow(true)
            check(at(1, 0)) { "Down after IME setSelection to paragraph end did not cross" }
            passed("ime-set-selection-to-paragraph-end-then-down-crosses-immediately")

            focus(0, paragraphEnd)
            main {
                val input = connection()!!
                val local = editor().blocks[0].value.text
                check(input.getTextAfterCursor(1, 0)?.toString() == "\n") { "Keyboard preflight sees false document end" }
                check(input.getTextAfterCursor(256, 0)?.toString() == "\n") { "Next paragraph text leaked into local input session" }
                check(input.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString() == local) { "Extracted text must remain local to prevent article duplication" }
                check(input.getTextBeforeCursor(1000, 0)?.toString() == local)
            }
            val documentEnd = main { editor().blocks.last().value.text.length }
            focus(7, documentEnd)
            main { check(connection()!!.getTextAfterCursor(1, 0).isNullOrEmpty()) { "Real document end exposes a false paragraph" } }
            passed("keyboard-preflight-sees-only-separator-at-paragraph-end-and-empty-at-document-end")

            fun burst(key: Int, count: Int) = main {
                val input = connection() ?: error("No connection for burst")
                repeat(count) {
                    check(input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    check(input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                }
            }
            focus(0, 1)
            burst(KeyEvent.KEYCODE_DPAD_RIGHT, 30)
            main { check(editor().focused!!.value.selection.end == 31) { "Rapid right keys dropped" } }
            burst(KeyEvent.KEYCODE_DPAD_LEFT, 30)
            main { check(editor().focused!!.value.selection.end == 1) { "Rapid left keys dropped" } }
            instrument.waitForIdleSync()
            main { check(editor().focused!!.value.selection.end == 1) { "Caret reverted after frame" } }
            passed("thirty-right-and-left-keys-without-frame-delay-all-applied")

            val bodyEnd = main { editor().blocks[0].value.text.length }
            focus(0, bodyEnd)
            burst(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
            main { check(editor().focusedId == editor().blocks[3].id && editor().focused!!.value.selection.end == 1) { "Rapid horizontal paragraph crossing dropped keys" } }
            burst(KeyEvent.KEYCODE_DPAD_LEFT, 4)
            main { check(editor().focusedId == editor().blocks[0].id && editor().focused!!.value.selection.end == bodyEnd) }
            passed("rapid-left-right-cross-short-and-empty-paragraphs")

            focus(0, 1)
            val totalSteps = main { editor().blocks.filter { it.type.isText }.sumOf { editor().layouts[it.id]!!.lineCount } - 1 }
            burst(KeyEvent.KEYCODE_DPAD_DOWN, totalSteps)
            check(at(7, main { editor().layouts[editor().blocks[7].id]!!.lineCount - 1 })) { "Rapid Down lost a line or paragraph" }
            burst(KeyEvent.KEYCODE_DPAD_UP, totalSteps)
            check(at(0, 0)) { "Rapid Up lost a line or paragraph" }
            instrument.waitForIdleSync()
            check(at(0, 0))
            passed("rapid-up-down-entire-document-without-frame-delay")

            focus(1, 2)

            main { check(editor().content() == original && editor().version == originalVersion && !editor().canUndo) { "Navigation modified document or undo history" } }
            main { check((connection() ?: error("No connection")).commitText("新", 1)) }
            waitFor("typing after move") { main { editor().blocks[1].text == "短新" } }
            passed("navigation-does-not-edit-content-and-next-input-uses-destination")
        } finally {
            main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) }
        }
    }
}
