package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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

object KeyboardEditorProtocolRegression {
    fun run(instrument: Instrumentation, context: Context, actualKeyboard: Boolean = false, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrument.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun waitFor(label: String, timeout: Long = 12000, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.contains(label) == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow))
                .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        fun tap(point: Offset) {
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event, true)); event.recycle(); Thread.sleep(55)
            }
        }
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "keyboard-protocol-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        fun editor() = EditorSession.current!!
        fun ime() = ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun input(view: View = activity.window.decorView): Pair<InputConnection, EditorInfo>? {
            if (view.onCheckIsTextEditor()) {
                val info = EditorInfo()
                view.onCreateInputConnection(info)?.let { return it to info }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        fun command(action: String, direction: String? = null) = main {
            val (connection, info) = input() ?: error("No input connection")
            check(info.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL") == 1) { "Document did not advertise protocol" }
            check(connection.performPrivateCommand("com.kongye.input.$action", Bundle().apply { direction?.let { putString("direction", it) } })) { "Command rejected: $action/$direction" }
        }
        fun move(direction: String) = command("SELECT_MOVE", direction)
        fun focus(index: Int, offset: Int) {
            main { val e = editor(); e.keyboardSelection.end(); e.clearArticleSelection(); e.requestFocus(e.blocks[index], offset) }
            waitFor("focus $index/$offset") { main { val e = editor(); e.pendingFocus == null && e.focusedId == e.blocks[index].id && e.focused!!.value.selection == TextRange(offset) } }
            Thread.sleep(180)
        }
        fun open(title: String, blocks: List<Block>, label: String) {
            val doc = main { Repository.createDoc(null, title, DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("body $label") { find(label) != null }
            waitFor("window focus") { main { activity.hasWindowFocus() } }
            val point = main { val e = editor(); val b = e.blocks[0]; e.selection.coordinates[b.id]!!.localToRoot(e.layouts[b.id]!!.getBoundingBox(2).center) }
            tap(point)
            waitFor("keyboard") { main { ime() > 100 } }
            Thread.sleep(650)
        }
        fun watch(label: String, action: () -> Unit) {
            val baseline = main { ime() }
            check(baseline > 100)
            val heights = mutableListOf<Int>()
            val frame = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) { heights += ime(); Choreographer.getInstance().postFrameCallback(this) }
            }
            main { Choreographer.getInstance().postFrameCallback(frame) }
            try { action(); Thread.sleep(350) }
            finally { main { Choreographer.getInstance().removeFrameCallback(frame) } }
            main { check(heights.size > 3 && heights.all { it > baseline * .7f }) { "$label closed keyboard: min=${heights.minOrNull()}, baseline=$baseline" } }
        }
        fun state() = main { editor().content() }
        fun range() = main { editor().selection.range!! }
        fun assertState(expected: DocContent, label: String) = waitFor(label) { state() == expected }
        fun type(text: String) {
            val previous = main { editor().version }
            main { check(input()!!.first.commitText(text, 1)) }
            waitFor("typed $text") { main { editor().version > previous && editor().pendingFocus == null } }
            instrument.waitForIdleSync(); Thread.sleep(160)
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1700)
            open("键盘跨行跨段选择", listOf(
                Block(text = "第一段甲乙丙丁戊己庚辛壬癸".repeat(9)),
                Block(text = ""),
                Block(type = BlockType.TODO, text = "清单第二段选择测试".repeat(5), indent = 1),
                Block(type = BlockType.CODE, text = "code first line\ncode second line\ncode third line"),
                Block(text = "最后一段选择边界"),
            ), "第一段甲乙")
            if (actualKeyboard) {
                val control = File(context.getExternalFilesDir(null), "keyboard231-control")
                control.delete()
                fun stage(name: String, condition: () -> Boolean) {
                    passed("READY-$name")
                    watch(name) { waitFor(name, timeout = 180000, condition = condition) }
                }
                focus(0, main { editor().blocks[0].value.text.length })
                val end = main { editor().blocks[0].value.text.length }
                stage("actual-select-then-right-crosses-to-empty", { main { val e = editor(); e.selection.range?.start == SelectionPoint(e.blocks[0].id,end) && e.selection.range?.end == SelectionPoint(e.blocks[1].id,1) } })
                stage("actual-right-crosses-empty-to-todo", { main { editor().selection.range?.end?.key == editor().blocks[2].id } })
                val fixedRight = range().end
                stage("actual-left-controls-left-handle", { main { editor().selection.range?.start?.offset == end - 1 && editor().selection.range?.end == fixedRight } })
                stage("actual-up-moves-left-handle-by-visual-line", { main { val e = editor(); val p = e.selection.range?.start; p != null && e.layouts[p.key]!!.getLineForOffset(p.offset) < e.layouts[p.key]!!.lineCount - 1 && e.selection.range?.end == fixedRight } })
                stage("actual-down-restores-left-handle-line", { main { val e = editor(); e.selection.range?.start == SelectionPoint(e.blocks[0].id,end - 1) && e.selection.range?.end == fixedRight } })
                passed("actual-selection-panel-left-right-up-crosses-lines-and-paragraphs")
                stage("finish-actual-selection", { control.exists() })
                control.delete()
                main { editor().keyboardSelection.end(); editor().clearArticleSelection() }
            } else {
                focus(0, 8)
                val original = state()
                watch("handle selection") {
                    command("SELECTION_START"); move("left")
                    check(range().start.offset == 7 && range().end.offset == 8)
                    move("right"); check(range().start.offset == 7 && range().end.offset == 9)
                    move("left"); check(range().start.offset == 6 && range().end.offset == 9)
                }
                passed("left-and-right-keys-control-independent-selection-handles-with-keyboard-open")
                move("right")
                val fixedLeft = range().start
                val before = range().end
                move("down")
                main { val e = editor(); val after = e.selection.range!!.end; check(e.selection.range!!.start == fixedLeft); check(e.layouts[after.key]!!.getLineForOffset(after.offset) == e.layouts[before.key]!!.getLineForOffset(before.offset) + 1) }
                move("up"); check(range().end == before)
                passed("vertical-selection-uses-wrapped-visual-lines-and-restores-column")
                val end = main { editor().blocks[0].value.text.length }
                focus(0, end); command("SELECTION_START"); move("right")
                main { val e = editor(); check(e.selection.range == DocumentRange(SelectionPoint(e.blocks[0].id,end), SelectionPoint(e.blocks[1].id,1))) }
                move("right"); main { check(editor().selection.range!!.end == SelectionPoint(editor().blocks[2].id,1)) }
                val fixed = range().end
                move("left"); check(range().start.offset == end - 1 && range().end == fixed)
                move("up"); check(range().end == fixed && range().start.offset < end - 1)
                passed("selection-crosses-paragraph-boundary-empty-paragraph-and-list-with-fixed-opposite-handle")
                focus(0, end); command("SELECTION_START"); move("down")
                main { check(editor().selection.range!!.end.key == editor().blocks[1].id) }
                move("down"); main { check(editor().selection.range!!.end.key == editor().blocks[2].id) }
                repeat(30) { move("down") }
                main { val e = editor(); check(e.selection.range!!.end == SelectionPoint(e.blocks.last().id,e.blocks.last().value.text.length)); check(e.title.text == "键盘跨行跨段选择") }
                repeat(30) { move("up") }
                main { val e = editor(); check(e.selection.range?.start == SelectionPoint(e.blocks[0].id,1)) }
                check(state() == original)
                passed("up-down-selection-crosses-entire-body-stops-at-boundaries-and-does-not-edit")
                focus(3, 5); command("SELECTION_START"); move("down")
                main { val e = editor(); check(e.layouts[e.blocks[3].id]!!.getLineForOffset(e.selection.range!!.end.offset) == 1) }
                move("down"); main { val e = editor(); check(e.layouts[e.blocks[3].id]!!.getLineForOffset(e.selection.range!!.end.offset) == 2) }
                move("down"); main { check(editor().selection.range!!.end.key == editor().blocks[4].id) }
                passed("selection-navigates-code-newlines-and-continues-to-next-paragraph")
                command("SELECTION_END")
                check(main { editor().selection.active })
                val selected = main { editor().selection.text() }
                main { check(input()!!.first.getSelectedText(0).toString() == selected) }
                passed("ending-selection-mode-retains-copyable-document-range")
                focus(0, 8); command("SELECTION_START")
                val tapped = main { val e = editor(); val b = e.blocks[0]; e.selection.coordinates[b.id]!!.localToRoot(e.layouts[b.id]!!.getBoundingBox(20).center) }
                tap(tapped)
                val newOffset = main { editor().blocks[0].value.selection.end }
                check(newOffset > 15)
                move("right")
                check(range().start.offset == newOffset && range().end.offset > newOffset)
                passed("tapping-new-caret-resets-keyboard-selection-anchor-without-stale-offsets")
            }

            open("键盘与工具栏共同历史", listOf(Block(text = "前段保持原文"), Block(text = "历史初始文字")), "前段保持")
            focus(1, main { editor().blocks[1].value.text.length })
            val states = mutableListOf(state())
            type("输入"); states += state()
            main { check(input()!!.first.deleteSurroundingText(1,0)) }
            waitFor("deleted") { state() != states.last() }; Thread.sleep(180); states += state()
            type("\n"); states += state()
            type("新段内容"); states += state()
            main { editor().setBlockType(BlockType.TODO) }; states += state()
            main { editor().toggleTodo(editor().focused!!) }; states += state()
            main { val e = editor(); val b = e.focused!!; e.selection.set(SelectionPoint(b.id,1),SelectionPoint(b.id,b.value.text.length)); e.toggleStyle(InlineStyle.BOLD); e.clearArticleSelection() }; states += state()
            main { editor().setAlign(Align.CENTER) }; states += state()
            check(states.distinct().size == states.size)
            focus(0, 3) // Undo must not paste this paragraph's older extraction into another paragraph.
            if (actualKeyboard) {
                val file = File(context.getExternalFilesDir(null), "keyboard231-state")
                fun checkpoint(label: String, expected: DocContent) {
                    passed("READY-$label")
                    watch(label) { waitFor(label, timeout = 180000) { state() == expected } }
                }
                for (i in states.lastIndex - 1 downTo 0) checkpoint("actual-undo-$i", states[i])
                for (i in 1..states.lastIndex) checkpoint("actual-redo-$i", states[i])
                file.writeText("complete")
                passed("actual-keyboard-undo-redo-restores-all-eight-text-structure-and-format-operations-in-order")
            } else {
                watch("shared history") {
                    for (i in states.lastIndex - 1 downTo 0) {
                        if (i % 2 == 0) command("UNDO") else main { editor().undo() }
                        assertState(states[i], "undo state $i")
                        waitFor("undo focus settled") { main { editor().pendingFocus == null } }
                        Thread.sleep(150)
                    }
                    command("UNDO"); assertState(states.first(), "empty undo is no-op")
                    for (i in 1..states.lastIndex) {
                        if (i % 2 == 0) command("REDO") else main { editor().redo() }
                        assertState(states[i], "redo state $i")
                        waitFor("redo focus settled") { main { editor().pendingFocus == null } }
                        Thread.sleep(150)
                    }
                    command("REDO"); assertState(states.last(), "empty redo is no-op")
                }
                passed("keyboard-and-toolbar-share-ordered-history-for-input-delete-newline-checklist-check-and-format")
                command("UNDO"); waitFor("undo settled") { main { editor().pendingFocus == null } }
                type("分支输入")
                check(main { !editor().canRedo })
                val changed = state(); command("REDO"); assertState(changed, "new edit invalidates redo")
                passed("new-input-after-undo-clears-shared-redo-without-restoring-stale-paragraph")
                main { val (connection,info)=input()!!; check(connection.performContextMenuAction(android.R.id.undo)) { "Standard undo rejected: marker=${info.extras}, title=${editor().titleFocused}, cell=${editor().focusedCell}, focused=${editor().focusedId}, comment=${editor().commentView}, find=${editor().findMode}" } }
                waitFor("standard undo settled") { main { editor().pendingFocus == null } }
                main { check(input()!!.first.performContextMenuAction(android.R.id.redo)) }
                assertState(changed, "standard undo redo uses history")
                passed("standard-input-method-undo-and-redo-use-the-same-document-history")
            }
        } catch (failure: Throwable) {
            auto.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "keyboard231-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw failure
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
