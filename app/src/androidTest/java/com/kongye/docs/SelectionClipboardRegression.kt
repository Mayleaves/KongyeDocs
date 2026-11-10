package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
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

/** Real selection gestures, per-frame IME checks, and keyboard plain-text clipboard round trips. */
object SelectionClipboardRegression {
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
        fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (match(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow))
                .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull { walk(it) }
        }
        fun findText(text: String) = find { it.text?.contains(text) == true }
        fun handle() = find { it.contentDescription?.toString() == "选择终点" }?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
        fun inject(start: Long, action: Int, point: Offset) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(auto.injectInputEvent(event, true)); event.recycle()
        }
        fun touch(point: Offset, hold: Long = 50) {
            val start = SystemClock.uptimeMillis()
            inject(start, MotionEvent.ACTION_DOWN, point); Thread.sleep(hold); inject(start, MotionEvent.ACTION_UP, point)
        }
        fun drag(from: Offset, to: Offset) {
            val start = SystemClock.uptimeMillis(); inject(start, MotionEvent.ACTION_DOWN, from); Thread.sleep(70)
            for (i in 1..20) { inject(start, MotionEvent.ACTION_MOVE, from + (to - from) * (i / 20f)); Thread.sleep(25) }
            inject(start, MotionEvent.ACTION_UP, to)
        }
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "selection-clipboard-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        fun editor() = EditorSession.current!!
        fun ime() = ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun connection(view: View = activity.window.decorView): InputConnection? {
            if (view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
            if (view is ViewGroup) for (i in 0 until view.childCount) connection(view.getChildAt(i))?.let { return it }
            return null
        }
        fun char(index: Int, offset: Int) = main {
            val e = editor(); val b = e.blocks[index]
            e.selection.coordinates[b.id]!!.localToRoot(e.layouts[b.id]!!.getBoundingBox(offset).center)
        }
        fun watch(label: String, action: () -> Unit) {
            val baseline = main { ime() }
            check(baseline > 100) { "Keyboard missing before $label" }
            val heights = mutableListOf<Int>()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) { heights += ime(); Choreographer.getInstance().postFrameCallback(this) }
            }
            main { Choreographer.getInstance().postFrameCallback(callback) }
            try { action(); Thread.sleep(650) }
            finally { main { Choreographer.getInstance().removeFrameCallback(callback) } }
            main { check(heights.size > 5 && heights.all { it >= baseline * .7f }) { "$label hid/shrank keyboard: baseline=$baseline min=${heights.minOrNull()}" } }
        }
        fun open(title: String, blocks: List<Block>, label: String) {
            val doc = main { Repository.createDoc(null, title, DocContent(blocks)) }
            main { AppState.openDoc(doc.id) }
            waitFor("body $label") { findText(label) != null }
            waitFor("window focus $label") { main { activity.hasWindowFocus() } }
            waitFor("body layout $label") { main { editor().layouts[editor().blocks[0].id] != null } }
            // Code blocks include padding around the editable text; use its actual
            // text layout rather than the accessibility container's top edge.
            touch(char(0, 2))
            waitFor("keyboard $label") { main { ime() > 100 } }
            Thread.sleep(650)
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1700)
            if (actualKeyboard) {
                check(android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) == "com.kongye.keyboard/.KongyeIME")
                val expected = "☐ 键盘复制未完成\n  ☑ 键盘复制已完成"
                open("空叶键盘实际复制", listOf(Block(type = BlockType.TODO, text = "键盘复制未完成"), Block(type = BlockType.TODO, text = "键盘复制已完成", checked = true, indent = 1)), "键盘复制未完成")
                main { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "等待真实键盘复制")); editor().selectArticle() }
                passed("ready-for-actual-kongye-keyboard-copy-button")
                waitFor("actual keyboard copy", timeout = 180000) { main { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == expected } }
                passed("actual-kongye-keyboard-copy-preserves-checklist-markers-and-state")
                open("空叶键盘实际粘贴", listOf(Block(text = "键盘粘贴目标")), "键盘粘贴目标")
                main { editor().selectArticle() }
                passed("ready-for-actual-kongye-keyboard-paste-button")
                waitFor("actual keyboard paste", timeout = 180000) { main {
                    val e = editor()
                    e.blocks.size == 2 && e.blocks[0].type == BlockType.TODO && e.blocks[0].text == "键盘复制未完成" && !e.blocks[0].checked &&
                        e.blocks[1].type == BlockType.TODO && e.blocks[1].checked && e.blocks[1].text == "键盘复制已完成" && e.blocks[1].indent == 1
                } }
                passed("actual-kongye-keyboard-paste-restores-interactive-checkboxes")
                Thread.sleep(500)
                val e = main { editor() }
                val checkbox = main { val b = e.blocks[0]; val r = e.layouts[b.id]!!.getCursorRect(1); e.selection.coordinates[b.id]!!.localToRoot(Offset(-14 * context.resources.displayMetrics.density, r.center.y)) }
                touch(checkbox)
                waitFor("actual pasted checklist checks") { main { editor().blocks[0].checked } }
                passed("actual-keyboard-pasted-checkbox-can-be-toggled")
                auto.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "selection230-actual-keyboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                return
            }
            val first = "甲乙丙丁戊己庚辛选字键盘保持展开"
            val second = "第二段跨段选择后继续输入文字"
            open("选字保持键盘测试", listOf(Block(text = first), Block(text = second)), "甲乙丙丁")
            val point = char(0, 3)
            watch("double tap selection") {
                touch(point); Thread.sleep(100); touch(point)
                waitFor("double tap selection") { main { editor().selection.active } }
            }
            passed("double-tap-selection-keeps-keyboard-open-every-frame")

            main { editor().clearArticleSelection() }
            watch("long press selection") {
                touch(char(0, 10), 900)
                waitFor("long press selection") { main { editor().selection.active } }
            }
            passed("long-press-selection-keeps-keyboard-open-every-frame")

            waitFor("selection end handle") { handle() != null }
            val bounds = handle()!!
            val from = Offset(bounds.exactCenterX(), bounds.exactCenterY())
            val target = main {
                val e = editor(); val b = e.blocks[1]
                val r = e.layouts[b.id]!!.getCursorRect(6)
                e.selection.coordinates[b.id]!!.localToRoot(Offset(r.left + bounds.width() / 2f, r.bottom + bounds.height() / 2f))
            }
            watch("cross paragraph selection") {
                drag(from, target)
                waitFor("cross paragraph selection") { main { editor().selection.range?.end?.key == editor().blocks[1].id } }
            }
            passed("drag-selection-handle-across-paragraph-keeps-keyboard-open")

            val replacement = main {
                val e = editor(); val r = e.selection.range!!
                e.blocks[0].text.take(r.start.offset - 1) + "新" + e.blocks[1].text.drop(r.end.offset - 1)
            }
            watch("replace cross paragraph selection") {
                main { check(connection()!!.commitText("新", 1)) }
                waitFor("selection replaced") { main { !editor().selection.active && editor().blocks.size == 1 && editor().blocks[0].text == replacement } }
            }
            passed("typing-replaces-cross-paragraph-selection-without-hiding-keyboard")

            watch("select all") {
                main { editor().selectArticle() }
                waitFor("all selected") { main { editor().articleSelected } }
            }
            watch("replace select all") {
                main { check(connection()!!.commitText("替换全文", 1)) }
                waitFor("all replaced") { main { editor().blocks.size == 1 && editor().blocks[0].text == "替换全文" } }
            }
            passed("touch-select-all-and-immediate-typing-keep-keyboard-open")

            open("清单复制测试", listOf(Block(type = BlockType.TODO, text = "未完成项目"), Block(type = BlockType.TODO, text = "已完成项目", checked = true, indent = 1)), "未完成项目")
            main {
                val e = editor(); e.selectArticle()
                check(connection()!!.performContextMenuAction(android.R.id.copy))
            }
            val copied = main { clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString() }
            check(copied == "☐ 未完成项目\n  ☑ 已完成项目") { "Checkbox states/indent missing from copy: $copied" }
            passed("keyboard-copy-includes-checked-unchecked-and-nested-checklist-markers")

            // Emulate keyboard history: drop all rich metadata and commit only its plain text.
            main { clipboard.setPrimaryClip(ClipData.newPlainText("keyboard history", copied)) }
            open("键盘清单粘贴测试", listOf(Block(text = "粘贴位置")), "粘贴位置")
            main {
                val e = editor(); val b = e.blocks[0]
                e.onValueChange(b, b.value.copy(selection = TextRange(1, b.value.text.length)))
                check(connection()!!.commitText(copied, 1))
            }
            waitFor("native checklist paste") { main { editor().blocks.size == 2 && editor().blocks.all { it.type == BlockType.TODO } } }
            main {
                val e = editor(); check(e.blocks[0].text == "未完成项目" && !e.blocks[0].checked)
                check(e.blocks[1].text == "已完成项目" && e.blocks[1].checked && e.blocks[1].indent == 1)
                check(e.save()); check(Repository.loadDoc(e.docId).blocks.map { it.type } == listOf(BlockType.TODO, BlockType.TODO))
            }
            passed("keyboard-plain-text-history-paste-restores-native-checkbox-state-indent-and-save")

            val checkbox = main {
                val e = editor(); val b = e.blocks[0]
                val r = e.layouts[b.id]!!.getCursorRect(1)
                e.selection.coordinates[b.id]!!.localToRoot(Offset(-14 * context.resources.displayMetrics.density, r.center.y))
            }
            touch(checkbox)
            waitFor("pasted checkbox toggles") { main { editor().blocks[0].checked } }
            passed("pasted-checkbox-is-interactive-and-can-be-checked")

            open("单条清单粘贴测试", listOf(Block(text = "粘贴位置单条")), "粘贴位置单条")
            main { val e = editor(); e.selectArticle(); check(connection()!!.commitText("☑ 单条已完成", 1)) }
            waitFor("single checklist") { main { editor().blocks.single().type == BlockType.TODO && editor().blocks.single().checked && editor().blocks.single().text == "单条已完成" } }
            passed("single-checked-checklist-paste-restores-native-checkbox")

            open("直接粘贴测试", listOf(Block(text = "直接粘贴")), "直接粘贴")
            main {
                val e = editor(); val b = e.blocks[0]
                b.value = b.value.copy(selection = TextRange(b.value.text.length))
            }
            instrument.waitForIdleSync(); Thread.sleep(200)
            main { check(connection()!!.commitText("\n☐ 追加未完成\n  ☑ 追加完成", 1)) }
            waitFor("direct checklist paste") { main { editor().blocks.size == 3 && editor().blocks[1].type == BlockType.TODO && editor().blocks[2].checked } }
            main { check(editor().blocks[0].text == "直接粘贴" && editor().blocks[1].text == "追加未完成" && editor().blocks[2].indent == 1) }
            main { editor().undo() }
            waitFor("undo checklist paste") { main { editor().blocks.size == 1 && editor().blocks[0].text == "直接粘贴" } }
            main { editor().redo() }
            waitFor("redo checklist paste") { main { editor().blocks.size == 3 && editor().blocks[2].checked } }
            passed("direct-multiline-keyboard-paste-restores-checkboxes-with-undo-redo")

            main {
                for ((marker, checked) in listOf("☐" to false, "☑" to true, "□" to false, "☒" to true, "- [ ]" to false, "- [x]" to true)) {
                    val b = BlockState(Block(text = "$marker 测试内容"))
                    check(b.restoreClipboardChecklist() > 0 && b.type == BlockType.TODO && b.checked == checked && b.text == "测试内容")
                }
                val plain = BlockState(Block(text = "正文中的☐符号保持原文"))
                check(plain.restoreClipboardChecklist() == 0 && plain.type == BlockType.TEXT)
            }
            passed("common-checkbox-markers-restore-without-changing-inline-square-symbols")

            open("普通粘贴与代码测试", listOf(Block(type = BlockType.CODE, text = "代码位置")), "代码位置")
            main { val e = editor(); val b = e.blocks[0]; e.onValueChange(b, b.value.copy(selection = TextRange(1, b.value.text.length))); check(connection()!!.commitText("☐ 保留代码原文", 1)) }
            waitFor("code stays literal") { main { editor().blocks.single().type == BlockType.CODE && editor().blocks.single().text == "☐ 保留代码原文" } }
            passed("checklist-markers-in-code-block-stay-literal")
        } catch (failure: Throwable) {
            auto.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "selection230-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw failure
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
