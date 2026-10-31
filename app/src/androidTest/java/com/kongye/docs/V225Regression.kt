package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.FilesSub
import com.kongye.docs.ui.Tab
import java.io.File

/** v2.25：点一下查看详情、长按菜单（置顶 / 编辑 / 复制全部字段 / 删除）、字段顺序、字段改名 / 移动 / 隐藏 / 删除。全部是测试用假数据。 */
object V225Regression {
    private const val SECRET = "JBSWY3DPEHPK3PXP"

    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        val root = File(context.filesDir, "v225-ui-${System.nanoTime()}").apply { mkdirs() }
        val ui = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val github = VaultEntry(type = VaultType.OTP, name = "Example Co", account = "alice@example.com", password = "Login#Pass1", secret = SECRET,
            fields = listOf(VaultField(label = "备用码", value = "1111-2222", secret = true), VaultField(label = "备注", value = "测试备注")))
        try {
            main {
                AppState.goHome(Tab.HOME); AppState.filesSub = FilesSub.DESKTOP; Settings.init(ui); Repository.init(ui)
                VaultStore.save(github)
            }
            val activity = startTestActivity(instrument, context)
            val auto = instrument.uiAutomation
            auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val screenW = context.resources.displayMetrics.widthPixels
            fun roots() = auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)
            fun all(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo) { if (match(node)) out += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
                roots().forEach(::walk)
                return out
            }
            fun box(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
            fun find(label: String) = all { it.text?.toString() == label || it.contentDescription?.toString() == label }.firstOrNull()
            fun findIn(window: Int, label: String) = all { it.windowId == window && (it.text?.toString() == label || it.contentDescription?.toString() == label) }.firstOrNull()
            fun waitFor(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 12000
                while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }; error("Timed out: $label")
            }
            fun press(node: AccessibilityNodeInfo): Boolean { var n = node; while (!n.isClickable) n = n.parent ?: return false; return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            fun click(label: String) { Thread.sleep(400); waitFor("click $label") { runCatching { find(label)?.let(::press) ?: false }.getOrDefault(false) } }
            fun clickIn(window: Int, label: String) { Thread.sleep(300); waitFor("click $label") { runCatching { findIn(window, label)?.let(::press) ?: false }.getOrDefault(false) } }
            fun setText(node: AccessibilityNodeInfo, text: String) = check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "Set text failed" }
            fun field(label: String): AccessibilityNodeInfo? {
                val editables = all { it.isEditable }
                for (l in all { !it.isEditable && it.text?.toString() == label }) {
                    val lb = box(l)
                    editables.firstOrNull { e -> lb.centerY() in box(e).top..box(e).bottom }?.let { return it }
                }
                return null
            }
            /** 编辑页里各字段名从上到下的顺序（只看左边的字段名，和输入框同一行的才算） */
            fun labelOrder(vararg labels: String) = labels.map { l -> field(l)?.let { box(it).top } ?: Int.MAX_VALUE }
            fun touch(x: Float, y: Float, hold: Long) {
                val down = SystemClock.uptimeMillis()
                fun ev(action: Int) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                ev(MotionEvent.ACTION_DOWN).let { check(auto.injectInputEvent(it, true)); it.recycle() }
                Thread.sleep(hold)
                ev(MotionEvent.ACTION_UP).let { check(auto.injectInputEvent(it, true)); it.recycle() }
            }
            fun shot(name: String) { val bmp = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            fun clipboard() = main { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Settings.update { it.copy(theme = ThemeMode.LIGHT) }; AppState.filesSub = FilesSub.VAULT; AppState.goHome(Tab.FILES) }
                Thread.sleep(1500)
                waitFor("vault") { find("Example Co") != null }

                // 长按：菜单只有 置顶、编辑、复制全部字段、删除
                touch(screenW * 0.5f, box(find("Example Co")!!).exactCenterY(), hold = 900)
                waitFor("long press menu") { find("复制全部字段") != null }
                check(find("置顶") != null && find("编辑") != null && find("删除") != null && find("查看字段") == null && find("动态密码 · 点一项即可复制") == null)
                Thread.sleep(300)
                shot("v225-long-press-menu.png")
                click("复制全部字段")
                val expected = "名称：Example Co\n账号：alice@example.com\n密码：Login#Pass1\n密钥：$SECRET\n备用码：1111-2222\n备注：测试备注"
                waitFor("all fields copied") { clipboard() == expected }
                passed("long-press-menu-four-actions-and-copy-all-fields")

                // 点一下：查看全部字段，顺序 验证码、账号、密码、密钥、自定义字段
                Thread.sleep(500)
                touch(screenW * 0.5f, box(find("Example Co")!!).exactCenterY(), hold = 40)
                waitFor("detail") { find("动态密码 · 点一项即可复制") != null }
                val sheet = find("动态密码 · 点一项即可复制")!!.windowId
                val order = listOf("验证码", "账号", "密码", "密钥", "备用码", "备注").map { l -> findIn(sheet, l)?.let { box(it).top } ?: error("Missing $l in detail") }
                check(order == order.sorted()) { "Detail order: $order" }
                clickIn(sheet, "密码"); waitFor("password copied") { clipboard() == "Login#Pass1" }
                Thread.sleep(300)
                shot("v225-detail.png")
                passed("tap-opens-details-in-field-order")

                // 编辑动态密码：名称、账号、密码、密钥、自定义字段、添加字段
                clickIn(sheet, "编辑")
                waitFor("otp editor") { find("编辑动态密码") != null && field("密钥") != null }
                val top = labelOrder("名称", "账号", "密码", "密钥", "备用码", "备注")
                check(top == top.sorted() && top.last() < box(find("添加字段")!!).top) { "Editor order: $top" }
                check(find("其他字段") == null)
                Thread.sleep(300)
                shot("v225-otp-editor.png")
                passed("otp-editor-field-order")

                // 字段操作：修改名字并设为不隐藏、下移、上移、删除
                click("字段操作备用码")
                waitFor("field menu") { find("字段「备用码」") != null && find("修改字段") != null && find("下移") != null && find("上移") == null }
                Thread.sleep(300)
                shot("v225-field-menu.png")
                click("修改字段")
                waitFor("field dialog") { find("修改字段") != null && find("隐藏内容") != null }
                val dialog = find("隐藏内容")!!.windowId
                setText(all { it.windowId == dialog && it.isEditable }.first(), "恢复码")
                clickIn(dialog, "隐藏内容")
                clickIn(dialog, "确定")
                waitFor("renamed") { field("恢复码") != null && field("备用码") == null && find("显示恢复码") == null }
                click("字段操作恢复码"); click("下移")
                waitFor("moved down") { labelOrder("备注", "恢复码").let { it[0] < it[1] } }
                click("字段操作恢复码"); click("上移")
                waitFor("moved up") { labelOrder("恢复码", "备注").let { it[0] < it[1] } }
                click("字段操作备注"); click("删除字段")
                // 面板收起前下面的输入框不报给无障碍：等面板消失、输入框重新出现后再判断
                waitFor("deleted") { find("字段「备注」") == null && field("名称") != null && field("备注") == null }
                setText(field("密码")!!, "Login#Pass2")
                click("保存")
                waitFor("saved") { find("编辑动态密码") == null }
                val saved = main { VaultStore.get(github.id)!! }
                check(saved.fields.map { it.label to it.secret } == listOf("恢复码" to false) && saved.field("恢复码") == "1111-2222") { "Fields: ${saved.fields}" }
                check(saved.password == "Login#Pass2" && saved.secret == SECRET && saved.account == "alice@example.com")
                passed("rename-unhide-move-delete-fields-and-otp-password-saved")

                // 新建密码：名称、账号、密码，没有默认的网址 / 备注
                click("新建"); click("添加密码")
                waitFor("password editor") { find("添加密码") != null && field("密码") != null }
                val newTop = labelOrder("名称", "账号", "密码")
                check(newTop == newTop.sorted() && newTop.last() < box(find("添加字段")!!).top && field("网址") == null && field("备注") == null)
                Thread.sleep(300)
                shot("v225-password-editor.png")
                passed("new-password-has-only-name-account-password")
            } catch (error: Throwable) { shot("v225-failure.png"); throw error }
            finally { main { Settings.update { it.copy(theme = ThemeMode.SYSTEM) }; AppState.filesSub = FilesSub.DESKTOP; AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
