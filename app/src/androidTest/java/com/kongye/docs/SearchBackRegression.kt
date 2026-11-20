package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.Tab
import java.io.File
import java.io.FileInputStream

internal object SearchBackRegression {
    fun run(inst: Instrumentation, pass: (String) -> Unit) {
        val context = inst.targetContext
        val fixture = object : ContextWrapper(context) {
            private val root = File(context.filesDir, "search-back-${newId()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            inst.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun shell(command: String) {
            inst.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index), predicate)?.let { return it }
            return null
        }
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
            return walk(inst.uiAutomation.rootInActiveWindow, predicate)
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                val intro = find { it.contentDescription?.toString() == "启动画面，点击跳过" }
                intro?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(80)
            }
            error("Timed out waiting for $label")
        }
        main {
            Settings.init(fixture)
            Repository.init(fixture)
            Repository.createDoc(null, "GestureSearchFixture", DocContent(listOf(Block(text = "Search fixture body"))))
            AppState.goHome(Tab.HOME)
        }
        val activity = startTestActivity(inst, context)
        fun imeVisible() = main {
            ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        fun openSearch() {
            main { AppState.searchOpen = true }
            await("focused search with keyboard") { find { it.isEditable } != null && imeVisible() }
            val editor = find { it.isEditable } ?: error("No search field")
            check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "GestureSearchFixture")
            }))
            await("search result") { find { it.text?.toString() == "GestureSearchFixture" && !it.isEditable } != null }
        }
        fun assertReturned() {
            await("search closed after one Back") { main { !AppState.searchOpen } }
            check(main { AppState.current == Screen.Home && !activity.isFinishing })
            await("keyboard hidden") { !imeVisible() }
            main { AppState.searchOpen = true }
            await("empty reopened search") { find { it.isEditable } != null && imeVisible() }
            check(find { it.isEditable }!!.text.isNullOrEmpty())
        }
        try {
            shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            openSearch()
            val width = context.resources.displayMetrics.widthPixels
            val y = context.resources.displayMetrics.heightPixels / 2
            shell("input swipe 1 $y ${width / 3} $y 300")
            assertReturned()
            pass("one-edge-back-closes-search-with-keyboard-and-clears-the-query")

            val editor = find { it.isEditable }!!
            check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "GestureSearchFixture")
            }))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                check(editor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
                await("search action hides keyboard") { !imeVisible() }
                check(main { AppState.searchOpen })
                check(find { it.isEditable }?.text?.toString() == "GestureSearchFixture")
                shell("input keyevent 4")
                assertReturned()
                pass("search-action-keeps-results-and-one-back-with-hidden-keyboard-returns-home")
            }

            shell("input keyevent 4")
            assertReturned()
            pass("one-back-key-also-closes-an-empty-search-with-keyboard")
        } finally {
            main {
                AppState.searchOpen = false
                AppState.goHome(Tab.HOME)
                activity.finish()
                Settings.init(context)
                Repository.init(context)
            }
        }
    }
}