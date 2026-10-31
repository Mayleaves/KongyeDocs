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

/** v2.24：密码空间的搜索、置顶、左右滑动切换、自定义字段、长按查看字段并点击复制。全部是测试用假数据。 */
object V224Regression {
    private const val SECRET = "JBSWY3DPEHPK3PXP"

    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun fixture(name: String) = object : ContextWrapper(context) {
            val root = File(context.filesDir, "v224-$name-${System.nanoTime()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            // ---------- 2.23 的网址、备注转成字段 ----------
            val legacy = fixture("legacy")
            main {
                Settings.init(legacy); Repository.init(legacy)
                val raw = """{"entries":[{"id":"0000000000000aa1","type":"PASSWORD","name":"旧条目","account":"old","password":"Old#1","url":"a.example.com","note":"旧备注","createdAt":1,"updatedAt":1}]}"""
                DurableFiles.write(File(legacy.filesDir, "vault/vault.json"), CredentialStore.encrypt(raw, CredentialStore.VAULT_ALIAS))
                Repository.init(legacy)
                val e = VaultStore.get("0000000000000aa1") ?: error("Legacy entry missing")
                check(e.url.isEmpty() && e.note.isEmpty() && e.fields.map { it.label to it.value } == listOf("网址" to "a.example.com", "备注" to "旧备注")) { "Legacy fields: ${e.fields}" }
                check(e.updatedAt == 1L && e.password == "Old#1")
                val disk = CredentialStore.decrypt(File(legacy.filesDir, "vault/vault.json").readText(), CredentialStore.VAULT_ALIAS)
                check("\"fields\"" in disk && "\"url\":\"a.example.com\"" !in disk) { "Migration not written back" }
                Repository.init(legacy)
                check(VaultStore.get(e.id)!!.fields.map { it.id } == e.fields.map { it.id }) { "Field ids must be stable after migration" }
            }
            passed("legacy-url-note-become-deletable-fields-once")

            // ---------- 搜索范围、置顶顺序、字段随备份 ----------
            val store = fixture("store")
            main {
                Settings.init(store); Repository.init(store)
                val a = VaultEntry(type = VaultType.PASSWORD, name = "学校邮箱", account = "student01", password = "Synthetic#Pass123",
                    fields = listOf(VaultField(label = "网址", value = "mail.example.edu"), VaultField(label = "PIN 码", value = "8642", secret = true)))
                val b = VaultEntry(type = VaultType.OTP, name = "Example Co", account = "alice@example.com", secret = SECRET)
                VaultStore.save(a); VaultStore.save(b)
                fun hits(q: String) = VaultStore.entries.value.filter { it.matches(q) }.map { it.name }.toSet()
                check(hits("学校") == setOf("学校邮箱") && hits("STUDENT") == setOf("学校邮箱") && hits("example.edu") == setOf("学校邮箱"))
                check(hits("alice") == setOf("Example Co") && hits("example") == setOf("学校邮箱", "Example Co"))
                check(hits("PIN") == setOf("学校邮箱")) { "Field labels should be searchable" }
                check(hits("Synthetic#Pass123").isEmpty() && hits("8642").isEmpty() && hits(SECRET).isEmpty() && hits("  ").isEmpty()) { "Secrets must not be searchable" }
                val updated = VaultStore.get(a.id)!!.updatedAt
                VaultStore.togglePin(a.id)
                check(VaultStore.get(a.id)!!.pinned && VaultStore.get(a.id)!!.updatedAt == updated)
                Repository.init(store)
                check(VaultStore.get(a.id)!!.pinned) { "Pin lost after reload" }
                Settings.updateBackup { it.copy(vaultPassword = "synthetic-backup-pass") }
                val text = Repository.exportBackup()
                check("8642" !in text && "mail.example.edu" !in text)
                val fresh = fixture("fresh"); Settings.init(fresh); Repository.init(fresh)
                Repository.importBackup(text)
                VaultStore.restore(VaultStore.pendingRestore.value!!, "synthetic-backup-pass")
                val back = VaultStore.get(a.id)!!
                check(back.pinned && back.field("PIN 码") == "8642" && back.fields.first { it.label == "PIN 码" }.secret && back.field("网址") == "mail.example.edu")
                VaultStore.togglePin(a.id)
                check(!VaultStore.get(a.id)!!.pinned)
            }
            passed("search-excludes-secrets-pin-persists-fields-survive-backup")

            // ---------- 界面 ----------
            val ui = fixture("ui")
            val school = VaultEntry(type = VaultType.PASSWORD, name = "学校邮箱", account = "student01", password = "Synthetic#Pass123",
                fields = listOf(VaultField(label = "网址", value = "mail.example.edu"), VaultField(label = "备注", value = "测试备注")))
            val wifi = VaultEntry(type = VaultType.PASSWORD, name = "家里 Wi-Fi", password = "Wifi#Pass456",
                fields = listOf(VaultField(label = "PIN 码", value = "1234", secret = true)))
            val otp = VaultEntry(type = VaultType.OTP, name = "Example Co", account = "alice@example.com", secret = SECRET)
            main {
                AppState.goHome(Tab.HOME); AppState.filesSub = FilesSub.DESKTOP; Settings.init(ui); Repository.init(ui)
                listOf(school, wifi, otp).forEach { VaultStore.save(it) }
            }
            val activity = startTestActivity(instrument, context)
            val auto = instrument.uiAutomation
            auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val density = context.resources.displayMetrics.density
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            fun roots() = auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)
            fun all(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo) { if (match(node)) out += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
                roots().forEach(::walk)
                return out
            }
            fun box(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
            fun unique(list: List<AccessibilityNodeInfo>) = list.distinctBy { "${it.windowId}:${box(it).flattenToString()}" }
            fun find(label: String) = all { it.text?.toString() == label || it.contentDescription?.toString() == label }.firstOrNull()
            fun count(label: String) = unique(all { it.text?.toString() == label }).size
            /** 只在某个窗口里找（底部面板、弹窗是单独的窗口，下面的页面仍在界面树里） */
            fun findIn(window: Int, label: String) = all { it.windowId == window && (it.text?.toString() == label || it.contentDescription?.toString() == label) }.firstOrNull()
            fun waitFor(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 12000
                while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }; error("Timed out: $label")
            }
            fun press(node: AccessibilityNodeInfo): Boolean {
                var n = node
                while (!n.isClickable) n = n.parent ?: return false
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            fun click(label: String) { Thread.sleep(400); waitFor("click $label") { runCatching { find(label)?.let(::press) ?: false }.getOrDefault(false) } }
            fun clickIn(window: Int, label: String) { Thread.sleep(300); waitFor("click $label") { runCatching { findIn(window, label)?.let(::press) ?: false }.getOrDefault(false) } }
            fun setText(node: AccessibilityNodeInfo, text: String) = check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "Set text failed" }
            /** 编辑页：和字段名在同一行的输入框 */
            fun field(label: String): AccessibilityNodeInfo? {
                val editables = all { it.isEditable }
                for (l in all { !it.isEditable && it.text?.toString() == label }) {
                    val lb = box(l)
                    editables.firstOrNull { e -> lb.centerY() in box(e).top..box(e).bottom }?.let { return it }
                }
                return null
            }
            fun inject(action: Int, x: Float, y: Float, down: Long) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }.let { check(auto.injectInputEvent(it, true)); it.recycle() }
            fun swipe(fromX: Float, toX: Float, y: Float) {
                val down = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, fromX, y, down)
                for (i in 1..12) { Thread.sleep(16); inject(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 12, y, down) }
                inject(MotionEvent.ACTION_UP, toX, y, down)
            }
            fun touch(x: Float, y: Float, hold: Long = 40) {
                val down = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, x, y, down); Thread.sleep(hold); inject(MotionEvent.ACTION_UP, x, y, down)
            }
            fun shot(name: String) { val bmp = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            fun clipboard() = main { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
            /** 某条右侧的 ⋮：和它的名称在同一行 */
            fun moreOf(name: String): AccessibilityNodeInfo? {
                val row = box(find(name) ?: return null)
                return all { it.contentDescription?.toString() == "更多" }.firstOrNull { box(it).centerY() in (row.top - 40 * density.toInt())..(row.bottom + 40 * density.toInt()) }
            }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Settings.update { it.copy(theme = ThemeMode.LIGHT) }; AppState.goHome(Tab.FILES) }
                Thread.sleep(1500)
                waitFor("desktop") { find("我的桌面") != null && main { AppState.filesSub } == FilesSub.DESKTOP }
                // 我的桌面上手指向左划：进入密码空间；再向右划回到我的桌面
                swipe(screenW * 0.85f, screenW * 0.15f, screenH * 0.55f)
                waitFor("swiped to vault") { main { AppState.filesSub } == FilesSub.VAULT && find("Example Co") != null }
                swipe(screenW * 0.15f, screenW * 0.85f, screenH * 0.55f)
                waitFor("swiped back") { main { AppState.filesSub } == FilesSub.DESKTOP }
                swipe(screenW * 0.85f, screenW * 0.15f, screenH * 0.55f)
                waitFor("vault again") { main { AppState.filesSub } == FilesSub.VAULT }
                passed("swipe-between-desktop-and-vault")

                // 搜索：只搜密码空间；密码、隐藏字段的内容搜不到，字段名可以搜到
                click("搜索")
                waitFor("vault search") { find("搜索名称、账号、网址、备注") != null }
                fun search(q: String) {
                    val box = unique(all { it.isEditable }).firstOrNull() ?: error("Search field missing")
                    setText(box, q)
                    Thread.sleep(500)
                }
                /** 搜索结果列表里出现的文字（只看结果列表，不看下面的页面） */
                fun results(): Set<String> {
                    val list = find("搜索结果") ?: return emptySet()
                    val out = mutableSetOf<String>()
                    fun walk(n: AccessibilityNodeInfo) { n.text?.toString()?.let { out += it }; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
                    walk(list)
                    return out
                }
                search("mail.example")
                waitFor("result by field") { results().let { "学校邮箱" in it && "家里 Wi-Fi" !in it && "Example Co" !in it } }
                // 截图前先收起键盘：输入法的候选栏可能显示剪贴板等个人内容
                fun ime() = main { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
                // 用输入法的“搜索”键收起（返回键在部分机型上会直接关掉搜索）
                if (ime() > 0) {
                    unique(all { it.isEditable }).first().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    waitFor("keyboard hidden") { ime() == 0 }
                }
                check(find("搜索名称、账号、网址、备注") != null || unique(all { it.isEditable }).isNotEmpty()) { "Search closed unexpectedly" }
                Thread.sleep(300)
                shot("v224-search.png")
                search("alice"); waitFor("result by account") { results().let { "Example Co" in it && "学校邮箱" !in it } }
                search("PIN"); waitFor("result by field label") { results().let { "家里 Wi-Fi" in it && "学校邮箱" !in it } }
                search("Wifi#Pass456"); waitFor("password not searchable") { find("没有找到相关条目") != null }
                search("1234"); waitFor("hidden field not searchable") { find("没有找到相关条目") != null }
                click("取消")
                waitFor("search closed") { find("搜索名称、账号、网址、备注") == null && !main { AppState.vaultSearchOpen } }
                passed("vault-search-like-desktop-excludes-secrets")

                // 置顶：⋮ → 置顶，进入最上面的“置顶”分组，整行浅灰底
                waitFor("row menu") { moreOf("家里 Wi-Fi") != null }
                press(moreOf("家里 Wi-Fi")!!)
                click("置顶")
                waitFor("pinned") { main { VaultStore.get(wifi.id)!!.pinned } && find("置顶") != null && runCatching { box(find("家里 Wi-Fi")!!).top < box(find("Example Co")!!).top }.getOrDefault(false) }
                Thread.sleep(600)
                shot("v224-vault-pinned.png")
                passed("pin-from-menu-moves-entry-to-pinned-section")

                // 长按：列出全部字段，点哪项复制哪项；隐藏的先显示圆点
                val row = box(find("学校邮箱")!!)
                // 2.25 起点一下查看字段（长按是菜单）
                touch(screenW * 0.5f, row.exactCenterY(), hold = 40)
                waitFor("detail sheet") { find("密码 · 点一项即可复制") != null }
                val sheet = find("密码 · 点一项即可复制")!!.windowId
                check(findIn(sheet, "student01") != null && findIn(sheet, "mail.example.edu") != null && findIn(sheet, "测试备注") != null && findIn(sheet, "Synthetic#Pass123") == null)
                clickIn(sheet, "student01"); waitFor("account copied") { clipboard() == "student01" }
                clickIn(sheet, "mail.example.edu"); waitFor("field copied") { clipboard() == "mail.example.edu" }
                clickIn(sheet, "••••••••"); waitFor("password copied") { clipboard() == "Synthetic#Pass123" }
                clickIn(sheet, "显示密码"); waitFor("password revealed") { findIn(sheet, "Synthetic#Pass123") != null }
                Thread.sleep(400)
                shot("v224-detail.png")
                passed("tap-lists-fields-and-tap-copies")

                // 编辑：删掉“网址”，添加“安全问题”和隐藏的“PIN 码”
                clickIn(sheet, "编辑")
                waitFor("editor") { find("编辑密码") != null && field("网址") != null && field("备注") != null }
                // 2.25 起删除在字段操作里
                click("字段操作网址"); click("删除字段")
                waitFor("url removed") { field("网址") == null }
                click("添加字段")
                waitFor("add field dialog") { find("隐藏内容") != null }
                val dialog = find("隐藏内容")!!.windowId
                setText(all { it.windowId == dialog && it.isEditable }.first(), "安全问题")
                Thread.sleep(300)
                shot("v224-add-field.png")
                clickIn(dialog, "添加")
                waitFor("question field") { field("安全问题") != null }
                setText(field("安全问题")!!, "第一只宠物")
                click("添加字段")
                waitFor("add field dialog 2") { find("隐藏内容") != null }
                val dialog2 = find("隐藏内容")!!.windowId
                setText(all { it.windowId == dialog2 && it.isEditable }.first(), "PIN 码")
                // 点“隐藏内容”这一行打开开关
                clickIn(dialog2, "隐藏内容")
                Thread.sleep(400)
                clickIn(dialog2, "添加")
                waitFor("pin field") { field("PIN 码") != null && find("显示PIN 码") != null }
                setText(field("PIN 码")!!, "8642")
                Thread.sleep(400)
                shot("v224-edit-fields.png")
                click("保存")
                waitFor("saved") { find("编辑密码") == null }
                val saved = main { VaultStore.get(school.id)!! }
                check(saved.fields.map { it.label } == listOf("备注", "安全问题", "PIN 码")) { "Fields: ${saved.fields}" }
                check(saved.field("安全问题") == "第一只宠物" && saved.field("PIN 码") == "8642" && saved.fields.last().secret && !saved.fields[1].secret)
                check(saved.password == "Synthetic#Pass123" && saved.account == "student01")
                passed("edit-delete-and-add-custom-fields-including-hidden")
            } catch (error: Throwable) { shot("v224-failure.png"); throw error }
            finally { main { Settings.update { it.copy(theme = ThemeMode.SYSTEM) }; AppState.vaultSearchOpen = false; AppState.filesSub = FilesSub.DESKTOP; AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
