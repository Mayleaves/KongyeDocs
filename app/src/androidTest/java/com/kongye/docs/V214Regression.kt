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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.home.*
import java.io.File

/** v2.14：置顶样式、多选置顶、编辑器置顶标记、置顶项之间拖动换位 */
object V214Regression {
    fun run(instrument: Instrumentation, context: Context, passed: (String) -> Unit) {
        fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
        fun fixture(name: String) = object : ContextWrapper(context) {
            val root = File(context.filesDir, "v214-$name-${System.nanoTime()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            main {
                // 拖动换位：置顶 ↔ 置顶、普通 ↔ 普通可以，置顶 ↔ 普通不行
                val p1 = Item(id = "0000000000000011", type = ItemType.DOC, title = "P1", pinnedAt = 300)
                val p2 = Item(id = "0000000000000012", type = ItemType.DOC, title = "P2", pinnedAt = 200)
                val n1 = Item(id = "0000000000000013", type = ItemType.DOC, title = "N1")
                val n2 = Item(id = "0000000000000014", type = ItemType.DOC, title = "N2")
                val rows = listOf(p1, p2, n1, n2).map { it to "" }
                val slots = rows.mapIndexed { i, (item, _) -> Slot(item.id, i, Offset(0f, i * 100f), Size(300f, 100f)) }
                fun drag(id: String, from: Int, dy: Float): List<String> {
                    val r = ReorderState()
                    r.begin(id, slots[from].pos, Offset.Zero, rows)
                    r.moveBy(Offset(0f, dy), slots, false) { _, _ -> }
                    return r.order!!.map { it.first.id }
                }
                check(drag(p2.id, 1, -100f) == listOf(p2.id, p1.id, n1.id, n2.id)) { "Pinned items should swap with each other" }
                check(drag(p2.id, 1, 100f) == rows.map { it.first.id }) { "Pinned item must not drop into normal items" }
                check(drag(n1.id, 2, -100f) == rows.map { it.first.id }) { "Normal item must not cross a pin" }
                check(drag(n2.id, 3, -100f) == listOf(p1.id, p2.id, n2.id, n1.id)) { "Normal items should still swap" }
            }
            passed("drag-swaps-only-within-pinned-or-normal-group")

            val storage = fixture("storage")
            main {
                Settings.init(storage); Repository.init(storage)
                val old = Repository.createDoc(null, "旧置顶")
                Repository.togglePin(old.id)
                val a = Repository.createDoc(null, "甲"); val b = Repository.createDoc(null, "乙"); val c = Repository.createFolder(null, "丙")
                val updated = listOf(a, b, c).associate { it.id to Repository.get(it.id)!!.updatedAt }
                // 多选置顶：先选的在上，且都在已有置顶项之上；已置顶的保持不变
                Repository.setPinned(listOf(b.id, c.id, a.id, old.id), true)
                fun pinOrder() = pinnedFirst(Repository.items.value.filter { it.pinned }).map { it.id }
                check(pinOrder() == listOf(b.id, c.id, a.id, old.id)) { "Batch pin order wrong: ${pinOrder()}" }
                check(listOf(a, b, c).all { Repository.get(it.id)!!.updatedAt == updated[it.id] }) { "Pinning changed updatedAt" }
                // 拖动后的新顺序：只在这几项原有的置顶时间之间重新分配，其他置顶项位置不变
                Repository.reorderPinned(listOf(a.id, b.id, c.id))
                check(pinOrder() == listOf(a.id, b.id, c.id, old.id)) { "Pin reorder wrong: ${pinOrder()}" }
                Repository.reorderPinned(listOf(old.id, a.id))
                check(pinOrder() == listOf(old.id, b.id, c.id, a.id)) { "Subset reorder moved other pins: ${pinOrder()}" }
                // 同一时间置顶的两项也能稳定地按拖动结果排列
                val t = Repository.get(b.id)!!.pinnedAt
                Repository.setPinned(listOf(b.id, c.id), false)
                Repository.setPinned(listOf(c.id, b.id), true)
                check(Repository.get(c.id)!!.pinnedAt != Repository.get(b.id)!!.pinnedAt && t > 0)
                Repository.reorderPinned(listOf(b.id, c.id))
                check(pinOrder().indexOf(b.id) < pinOrder().indexOf(c.id))
                check(Repository.manualOrder(null).isEmpty()) { "Pin reorder touched manual order" }
                // 置顶顺序保存在磁盘上
                val before = pinOrder()
                Repository.init(storage)
                check(pinOrder() == before) { "Pin order lost after reload" }
                Repository.setPinned(listOf(a.id, b.id, c.id, old.id), false)
                check(Repository.items.value.none { it.pinned })
            }
            passed("batch-pin-order-reorder-pins-and-persistence")

            val ui = fixture("ui")
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
            fun shot(name: String) { val bmp = auto.takeScreenshot()!!; File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            /** 真实触摸：按住超过长按时间后，慢慢拖到目标标题所在的行再松手 */
            fun longPressDrag(fromLabel: String, toLabel: String) {
                val from = bounds(fromLabel); val to = bounds(toLabel)
                val x = from.exactCenterX(); val y0 = from.exactCenterY(); val y1 = to.exactCenterY()
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, y: Float) = instrument.sendPointerSync(
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
                )
                send(MotionEvent.ACTION_DOWN, y0)
                Thread.sleep(800)
                val steps = 40
                for (i in 1..steps) { send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps); Thread.sleep(25) }
                Thread.sleep(300)
                send(MotionEvent.ACTION_UP, y1)
                Thread.sleep(600)
            }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                Thread.sleep(1500)
                val folder = main { Repository.createFolder(null, "置顶资料夹") }
                val d1 = main { Repository.createDoc(null, "置顶甲", DocContent(listOf(Block(text = "甲的正文")))) }
                val d2 = main { Repository.createDoc(null, "置顶乙", DocContent(listOf(Block(text = "乙的正文")))) }
                val n1 = main { Repository.createDoc(null, "普通丙", DocContent(listOf(Block(text = "丙的正文")))) }
                val n2 = main { Repository.createDoc(null, "普通丁", DocContent(listOf(Block(text = "丁的正文")))) }
                main {
                    Repository.setFilesGrid(false); AppState.sort = SortSpec(SortMode.UPDATED, true); AppState.goHome(Tab.FILES)
                    // 长按多选：先勾乙、再勾资料夹、再勾甲
                    AppState.startSelection(d2.id); AppState.toggleSelect(folder.id); AppState.toggleSelect(d1.id)
                }
                click("置顶")
                waitFor("selection pinned") { main { AppState.selection == null && listOf(folder, d1, d2).all { Repository.get(it.id)!!.pinned } } }
                waitFor("pinned rows first in selection order") {
                    runCatching { bounds(d2.title).top < bounds(folder.title).top && bounds(folder.title).top < bounds(d1.title).top && bounds(d1.title).top < bounds(n2.title).top }.getOrDefault(false)
                }
                check(find("已置顶") != null)
                shot("v214-pinned-list.png")
                passed("selection-bar-pins-in-selection-order")

                // 置顶项之间拖动换位：把甲拖到乙的位置；不切换为手动排序，也不写手动顺序
                longPressDrag(d1.title, d2.title)
                waitFor("dragged pin saved") { main { pinnedFirst(Repository.items.value.filter { it.pinned }).map { it.id } } == listOf(d1.id, d2.id, folder.id) }
                check(main { AppState.sort.mode } == SortMode.UPDATED) { "Pin drag switched sort mode" }
                check(main { Repository.manualOrder(null) }.isEmpty()) { "Pin drag wrote manual order" }
                // 普通项拖不进置顶区
                longPressDrag(n1.title, d1.title)
                check(main { pinnedFirst(Repository.items.value.filter { it.pinned }).map { it.id } } == listOf(d1.id, d2.id, folder.id))
                waitFor("normal stays below pins") { runCatching { bounds(folder.title).top < bounds(n1.title).top }.getOrDefault(false) }
                main { AppState.endSelection() }
                passed("long-press-drag-reorders-pins-without-manual-sort")

                main { Repository.setFilesGrid(true) }; Thread.sleep(700)
                check(find("已置顶") != null)
                shot("v214-pinned-grid.png")
                main { Settings.update { it.copy(theme = ThemeMode.DARK) } }; Thread.sleep(700)
                shot("v214-pinned-grid-dark.png")
                main { Repository.setFilesGrid(false) }; Thread.sleep(700)
                shot("v214-pinned-list-dark.png")
                main { Settings.update { it.copy(theme = ThemeMode.LIGHT) } }; Thread.sleep(500)
                passed("pinned-list-and-grid-light-and-dark")

                // 首页“最近”的置顶分组里也能拖动换位，分组标题保持不动
                main { listOf(n1, n2).forEach { Repository.markOpened(it.id) }; AppState.homeSub = HomeSub.RECENT; AppState.goHome(Tab.HOME) }
                waitFor("recent pinned section") { find("置顶") != null && find(d2.title) != null && find(n1.title) != null }
                longPressDrag(d2.title, d1.title)
                waitFor("recent drag saved") { main { pinnedFirst(Repository.items.value.filter { it.pinned }).map { it.id } }.take(2) == listOf(d2.id, d1.id) }
                check(find("置顶") != null && bounds("置顶").top < bounds(d2.title).top)
                shot("v214-recent-pinned.png")
                passed("recent-pinned-section-drag")

                // 编辑器顶栏显示图钉（和收藏书签并排），点一下取消置顶
                main { Repository.toggleStar(d1.id); AppState.openDoc(d1.id) }
                // 首页列表可能仍留在界面树里：只认顶栏里的按钮（屏幕最上方 120dp 以内）
                val topBar = (120 * context.resources.displayMetrics.density).toInt()
                fun inTopBar(label: String): AccessibilityNodeInfo? {
                    fun walk(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
                        if (node.contentDescription?.toString() == label) out += node
                        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out) }
                    }
                    val all = mutableListOf<AccessibilityNodeInfo>()
                    (auto.windows.mapNotNull { it.root } + listOfNotNull(auto.rootInActiveWindow)).forEach { walk(it, all) }
                    return all.firstOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.let { it.bottom in 1..topBar } }
                }
                waitFor("editor") { inTopBar("分享") != null && inTopBar("更多") != null }
                waitFor("editor pin mark") { inTopBar("已置顶") != null && inTopBar("已收藏") != null }
                val pinBox = Rect().also { inTopBar("已置顶")!!.getBoundsInScreen(it) }
                val starBox = Rect().also { inTopBar("已收藏")!!.getBoundsInScreen(it) }
                check(pinBox.right <= starBox.left) { "Pin should sit left of the bookmark" }
                Thread.sleep(500)
                shot("v214-editor-pinned.png")
                inTopBar("已置顶")!!.let { var n = it; while (!n.isClickable) n = n.parent; n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                waitFor("editor unpinned") { main { !Repository.get(d1.id)!!.pinned } && inTopBar("已置顶") == null }
                check(main { Repository.loadDoc(d1.id).bodyText() } == "甲的正文")
                passed("editor-top-bar-pin-mark-unpins")
            } catch (error: Throwable) { shot("v214-ui-failure.png"); throw error }
            finally { main { Settings.update { it.copy(theme = ThemeMode.SYSTEM) }; AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
