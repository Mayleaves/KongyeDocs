package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.components.DialogButtons
import com.kongye.docs.ui.components.DialogFrame
import com.kongye.docs.ui.components.DialogTitle
import com.kongye.docs.ui.screens.DownloadBar
import com.kongye.docs.ui.theme.*
import java.io.File
import kotlin.math.abs

/** v2.15：菜单收藏 / 置顶图标随开关空心 / 实心、分享区留白、下载更新弹窗 */
object V215Regression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        val root = File(context.filesDir, "v215-ui-${System.nanoTime()}").apply { mkdirs() }
        val ui = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val density = context.resources.displayMetrics.density
        try {
            main { AppState.goHome(Tab.HOME); Settings.init(ui); Repository.init(ui) }
            val activity = startTestActivity(instrument, context)
            val auto = instrument.uiAutomation
            auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            fun find(label: String): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                    return null
                }
                return (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).firstNotNullOfOrNull { walk(it) }
            }
            fun waitFor(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 12000
                while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }; error("Timed out: $label")
            }
            fun bounds(label: String) = Rect().also { (find(label) ?: error("Missing $label")).getBoundsInScreen(it) }
            fun click(label: String) {
                Thread.sleep(450)
                waitFor("click $label") {
                    runCatching {
                        var node = find(label) ?: return@runCatching false
                        while (!node.isClickable) node = node.parent ?: return@runCatching false
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                }
            }
            fun screen(): Bitmap = auto.takeScreenshot()!!
            fun save(bmp: Bitmap, name: String) = File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun shot(name: String) { val b = screen(); save(b, name); b.recycle() }
            /** 菜单行左侧图标区域（起点 22dp、宽 22dp）里的深色像素数：实心图标明显多于空心图标 */
            fun iconInk(bmp: Bitmap, label: String): Int {
                val row = bounds(label)
                val x0 = (22 * density).toInt(); val x1 = (44 * density).toInt()
                val half = (11 * density).toInt()
                var n = 0
                for (y in row.centerY() - half until row.centerY() + half) for (x in x0 until x1) {
                    val c = bmp.getPixel(x, y)
                    if (Color.red(c) + Color.green(c) + Color.blue(c) < 3 * 150) n++
                }
                return n
            }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Settings.update { it.copy(theme = ThemeMode.LIGHT) } }
                Thread.sleep(1500)
                val doc = main { Repository.createDoc(null, "菜单图标", DocContent(listOf(Block(text = "菜单图标正文")))) }
                main { AppState.openDoc(doc.id) }
                waitFor("editor") { find("更多") != null && find("分享") != null }
                click("更多")
                waitFor("menu") { find("分享 Markdown") != null && find("收藏") != null }
                Thread.sleep(600)
                val off = screen(); save(off, "v215-menu-off.png")
                val starOff = iconInk(off, "收藏"); val pinOff = iconInk(off, "置顶"); off.recycle()
                for (label in listOf("分享图片", "分享文字", "分享 Markdown")) {
                    var node = find(label) ?: error("Share action missing")
                    while (!node.isClickable) node = node.parent ?: error("Share action not clickable")
                    val size = Rect().also { node.getBoundsInScreen(it) }
                    check(size.height() >= 64 * density && size.height() <= 84 * density) { "Share tile height out of range: $size" }
                }
                click("收藏"); waitFor("starred") { main { Repository.get(doc.id)!!.starred } }
                click("置顶"); waitFor("pinned") { main { Repository.get(doc.id)!!.pinned } }
                Thread.sleep(600)
                val on = screen(); save(on, "v215-menu-on.png")
                val starOn = iconInk(on, "收藏"); val pinOn = iconInk(on, "置顶"); on.recycle()
                check(starOff > 0 && pinOff > 0) { "Menu icons not drawn: $starOff / $pinOff" }
                // 书签很宽，实心后深色像素翻倍以上；图钉细长，描边本身就占了大半，实心后多出 20% 以上即可
                check(starOn > starOff * 1.6 && pinOn > pinOff * 1.2) { "Icons should turn solid when on: 收藏 $starOff→$starOn, 置顶 $pinOff→$pinOn" }
                passed("doc-menu-icons-hollow-off-solid-on-and-share-row-height")

                // 下载弹窗：0% 时进度条上没有任何主色像素（不再有末端圆点）；45% 时主色停在进度条约 45% 处
                fun showDownload(progress: Float) = main {
                    activity.setContent {
                        KongyeTheme {
                            Box(Modifier.fillMaxSize().background(Kongye.Surface)) {
                                DialogFrame(onDismiss = {}) {
                                    DialogTitle("正在下载 v2.15")
                                    DownloadBar(progress, 2_000_000L)
                                    DialogButtons("取消", Kongye.Text2, onCancel = null) {}
                                }
                            }
                        }
                    }
                }
                val primary = main { Kongye.Primary.toArgb() }
                val track = main { Kongye.Line2.toArgb() }
                fun near(c: Int, target: Int) = abs(Color.red(c) - Color.red(target)) < 24 && abs(Color.green(c) - Color.green(target)) < 24 && abs(Color.blue(c) - Color.blue(target)) < 24
                /** 标题与“取消”之间所有主色像素：(所在行, 最左列..最右列) */
                fun primaryPixels(bmp: Bitmap): Pair<Int, IntRange>? {
                    val top = bounds("正在下载 v2.15").bottom; val bottom = bounds("取消").top
                    var min = Int.MAX_VALUE; var max = -1; var first = -1; var last = -1
                    for (y in top until bottom) for (x in 0 until bmp.width) if (near(bmp.getPixel(x, y), primary)) {
                        if (x < min) min = x; if (x > max) max = x; if (first < 0) first = y; last = y
                    }
                    // 圆角两端在最上、最下一行会短一点，取中间那一行
                    return if (max < 0) null else (first + last) / 2 to min..max
                }
                showDownload(0f)
                waitFor("download dialog") { find("正在下载 v2.15") != null && find("0%") != null && find("0 KB / 1.9 MB") != null }
                Thread.sleep(700)
                val zero = screen(); save(zero, "v215-download-0.png")
                check(primaryPixels(zero) == null) { "Progress bar shows a coloured dot at 0%" }
                zero.recycle()
                showDownload(0.45f)
                waitFor("progress 45") { find("45%") != null }
                Thread.sleep(700)
                val half = screen(); save(half, "v215-download-45.png")
                val (row, fill) = primaryPixels(half) ?: error("No progress fill at 45%")
                // 同一行上灰色底槽的范围就是整条进度条
                val barEnd = (fill.last until half.width).lastOrNull { near(half.getPixel(it, row), track) } ?: error("Track not found")
                val ratio = (fill.last - fill.first + 1).toFloat() / (barEnd - fill.first + 1)
                check(ratio in 0.38f..0.52f) { "Progress fill wrong: $fill to $barEnd ($ratio)" }
                half.recycle()
                main { Settings.update { it.copy(theme = ThemeMode.DARK) } }; Thread.sleep(800)
                shot("v215-download-45-dark.png")
                passed("download-dialog-app-style-no-dot-at-zero-and-accurate-fill")
            } catch (error: Throwable) { shot("v215-ui-failure.png"); throw error }
            finally { main { Settings.update { it.copy(theme = ThemeMode.SYSTEM) }; AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
