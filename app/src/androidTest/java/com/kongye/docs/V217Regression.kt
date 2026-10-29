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
import java.io.File

/** v2.17：添加评论栏的“取消”放弃评论，回到原段落继续编辑（键盘和工具栏都回来） */
object V217Regression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(40) }; error("Timed out: $label")
        }
        val auto = instrument.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString() == label) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).firstNotNullOfOrNull { walk(it) }
        }
        fun touch(x: Float, y: Float) {
            val start = SystemClock.uptimeMillis()
            fun event(action: Int) = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            event(MotionEvent.ACTION_DOWN).let { check(auto.injectInputEvent(it, true)); it.recycle() }
            Thread.sleep(40)
            event(MotionEvent.ACTION_UP).let { check(auto.injectInputEvent(it, true)); it.recycle() }
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
            val root = File(context.filesDir, "v217-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        val density = context.resources.displayMetrics.density
        fun ime() = main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        fun toolbar() = Rect().also { (find("编辑工具栏") ?: error("Toolbar missing")).getBoundsInScreen(it) }
        fun tool(index: Int) { val bar = toolbar(); touch((26 + 44 * index) * density, bar.exactCenterY()) }
        /** 当前获得焦点的输入框（只看本应用的窗口） */
        fun focusedEditable(): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEditable && node.isFocused) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }.firstNotNullOfOrNull { walk(it) }
        }
        /** 输入框在屏幕下半部分：就是键盘上方的评论栏，而不是正文段落 */
        fun inComposer(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }.top > context.resources.displayMetrics.heightPixels / 2
        fun setText(text: String) {
            val node = focusedEditable() ?: error("No focused field")
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "Set text failed" }
        }
        fun shot(name: String) { val bitmap = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        val body = "这一段用来测试评论栏的返回。"
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(2000)
            val doc = main {
                Repository.setToolbar(listOf("STYLE", "PARAGRAPH", "INSERT", "UNDO", "REDO", "COMMENT"))
                Repository.createDoc(null, "评论栏测试", DocContent(listOf(Block(text = body))))
            }
            val blockId = main { Repository.loadDoc(doc.id).blocks.first().id }
            main { AppState.openDoc(doc.id) }
            waitFor("editor") { find("这一段用来测试") != null }
            val field = Rect().also { find("这一段用来测试")!!.getBoundsInScreen(it) }
            touch(field.left + 30 * density, field.exactCenterY())
            waitFor("keyboard open") { ime() > 160 * density && main { EditorSession.current?.focusedId } == blockId }
            Thread.sleep(600)

            // 工具栏“评论”→ 出现添加评论栏：左边“取消”，中间输入框，右边“发送”；工具栏让位
            tool(5)
            waitFor("comment composer") { find("添加评论…") != null && find("取消") != null && find("发送") != null && find("编辑工具栏") == null }
            check(main { EditorSession.current!!.commentDraft?.blockId } == blockId)
            val cancel = Rect().also { find("取消")!!.getBoundsInScreen(it) }
            val input = Rect().also { find("添加评论…")!!.getBoundsInScreen(it) }
            check(cancel.right <= input.left) { "取消 should sit left of the comment field" }
            // 评论输入框自动获得焦点；用无障碍直接写入文字（拼音键盘会把按键变成候选词）
            waitFor("comment field focused") { focusedEditable()?.let { inComposer(it) } == true }
            setText("draft")
            waitFor("typed draft") { find("draft")?.let { inComposer(it) } == true }
            Thread.sleep(400)
            shot("v217-comment-composer.png")

            click("取消")
            waitFor("back to editing") {
                find("添加评论…") == null && find("draft") == null && find("编辑工具栏") != null &&
                    main { EditorSession.current!!.let { it.commentDraft == null && it.focusedId == blockId } }
            }
            // 焦点回到原段落（不在底部的评论栏里），键盘保持打开
            waitFor("paragraph focused") { focusedEditable()?.let { !inComposer(it) } == true }
            waitFor("keyboard stays") { ime() > 160 * density }
            main {
                val content = EditorSession.current!!.content()
                check(content.comments.isEmpty()) { "Cancelled comment was saved" }
                check(content.blocks.first().spans.none { it.style == InlineStyle.COMMENT }) { "Cancelled comment left an anchor" }
                check(content.bodyText() == body)
            }
            Thread.sleep(400)
            shot("v217-after-cancel.png")
            passed("comment-cancel-returns-to-paragraph-with-keyboard-and-toolbar")

            // 发送照常：再开一次评论栏，写一条发出去
            tool(5)
            waitFor("comment composer again") { find("添加评论…") != null && find("编辑工具栏") == null }
            waitFor("comment field focused again") { focusedEditable()?.let { inComposer(it) } == true }
            setText("ok")
            waitFor("typed ok") { find("ok")?.let { inComposer(it) } == true }
            click("发送")
            waitFor("comment saved") { main { EditorSession.current!!.content().comments.size } == 1 && find("添加评论…") == null }
            check(main { EditorSession.current!!.content().comments.single().comments.single().text } == "ok")
            passed("comment-send-still-attaches-comment")
        } catch (error: Throwable) { shot("v217-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
