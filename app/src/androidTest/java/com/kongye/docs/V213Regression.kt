package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.home.*
import com.kongye.docs.ui.screens.UpdateEntries
import com.kongye.docs.ui.theme.*
import kotlinx.serialization.json.*
import java.io.File

object V213Regression {
    fun run(instrument: Instrumentation,context: Context,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(block) }; return result!!.getOrThrow() }
        fun fixture(name: String)=object: ContextWrapper(context) {
            val root=File(context.filesDir,"v213-$name-${System.nanoTime()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val storage=fixture("storage")
        try {
            main {
                val a=Item(id="0000000000000001",type=ItemType.DOC,title="Apple",createdAt=20,updatedAt=10)
                val z=Item(id="0000000000000002",type=ItemType.FOLDER,title="Zebra",createdAt=10,updatedAt=20)
                val p=a.copy(id="0000000000000003",title="Pinned A",pinnedAt=100)
                val q=z.copy(id="0000000000000004",title="Pinned Z",pinnedAt=200)
                val items=listOf(a,p,z,q)
                for(mode in SortMode.entries) for(desc in listOf(false,true)) {
                    val result=sortItems(items,SortSpec(mode,desc),listOf(z.id,q.id,a.id,p.id))
                    check(result.take(2).map { it.id }==listOf(q.id,p.id)) { "Pins moved under $mode/$desc" }
                    val ascending=when(mode) { SortMode.NAME,SortMode.UPDATED -> listOf(a.id,z.id); else -> listOf(z.id,a.id) }
                    val expected=if(mode!=SortMode.MANUAL && desc) ascending.reversed() else ascending
                    check(result.drop(2).map { it.id }==expected) { "Normal sort changed under $mode/$desc" }
                    val tied=sortItems(listOf(a,p,q.copy(pinnedAt=p.pinnedAt)),SortSpec(mode,desc))
                    check(tied.take(2).map { it.id }==listOf(p.id,q.id)) { "Equal pin timestamps depend on sorting" }
                }
                check(recentSections(listOf(p,a,z.copy(pinnedAt=300))).first().entries.first().first.id==p.id)
                val reorder=ReorderState()
                val rows=listOf(q,p,a,z).map { it to "" }
                val slots=rows.mapIndexed { index,pair -> Slot(pair.first.id,index,androidx.compose.ui.geometry.Offset(0f,index*100f),androidx.compose.ui.geometry.Size(300f,100f)) }
                reorder.begin(a.id,slots[2].pos,androidx.compose.ui.geometry.Offset.Zero,rows)
                reorder.moveBy(androidx.compose.ui.geometry.Offset(0f,-200f),slots,false) { _,_ -> error("Normal item crossed pin boundary") }
                check(reorder.order==rows)
            }
            passed("pins-fixed-across-all-sort-modes-directions-and-manual-drag")
            main {
                AppState.goHome(Tab.HOME); Settings.init(storage); Repository.init(storage)
                val folder=Repository.createFolder(null,"置顶文件夹")
                val document=Repository.createDoc(folder.id,"置顶文档",DocContent(listOf(Block(text="置顶不改变正文和修改时间"))))
                Repository.togglePin(folder.id); Repository.togglePin(document.id)
                check(Repository.get(document.id)!!.updatedAt==document.updatedAt)
                val content=Repository.loadDoc(document.id)
                val backup=Repository.exportBackup()
                val pinned=BackupCodec.decode(backup)
                check(pinned.items.first { it.id==folder.id }.pinned && pinned.items.first { it.id==document.id }.pinned)
                Repository.init(storage)
                check(Repository.get(document.id)!!.pinned && Repository.get(folder.id)!!.pinned)
                val raw=Json.parseToJsonElement(backup).jsonObject
                fun oldItems(array: JsonArray)=JsonArray(array.map { JsonObject(it.jsonObject.filterKeys { key -> key!="pinnedAt" }) })
                val unsigned=raw.toMutableMap().apply {
                    put("checksum",JsonPrimitive("")); put("items",oldItems(raw.getValue("items").jsonArray))
                    val library=raw.getValue("library").jsonObject
                    put("library",JsonObject(library+ ("items" to oldItems(library.getValue("items").jsonArray))))
                }
                // Recreate pre-pin wire data independently, then verify its original-style checksum.
                val legacy=JsonObject(unsigned+("checksum" to JsonPrimitive(BackupCodec.sha(JsonObject(unsigned).toString().toByteArray())))).toString()
                check("pinnedAt" !in legacy)
                check(BackupCodec.decode(legacy).items.none { it.pinned }) { "Pre-v2.13 backup checksum compatibility broken" }
                val restored=fixture("restore"); Settings.init(restored); Repository.init(restored); Repository.importBackup(backup)
                check(Repository.get(folder.id)!!.pinned && Repository.get(document.id)!!.pinned)
                check(Repository.loadDoc(document.id)==content)
                Repository.togglePin(document.id)
                check(!Repository.get(document.id)!!.pinned && Repository.get(document.id)!!.updatedAt==document.updatedAt)
                check(Repository.duplicate(document.id)?.pinned==false)
            }
            passed("pin-persistence-backup-restore-and-pre-pin-checksum-compatibility")
            val notes="""
                # 更新内容
                - **新增置顶**：文档和文件夹始终排在前面。
                - 缩小分享区域，保留三种分享方式。
                - 精简更新提示，只显示重点。
                - 第四条不应该显示。
                ## 验证
                - 自动化测试全部通过。
                APK SHA-256：${"a".repeat(64)}
            """.trimIndent()
            val summary=ReleaseNotes.summary(notes)
            check(summary.lines().size==3 && summary.length<=200 && "置顶" in summary && "第四条" !in summary && "测试" !in summary && "SHA" !in summary)
            check("**" !in summary && "```" !in summary)
            check(ReleaseNotes.summary("- "+"🐈".repeat(100)).codePointCount(0,ReleaseNotes.summary("- "+"🐈".repeat(100)).length)<=66)
            check(ReleaseNotes.summary("包名 com.kongye.docs\nSHA-256：${"0".repeat(64)}").contains("GitHub"))
            check(ReleaseNotes.summary("改进文档编辑和使用体验。").contains("改进文档编辑"))
            passed("update-highlights-three-bounded-points-without-build-details")

            val ui=fixture("ui")
            main { AppState.goHome(Tab.HOME); Settings.init(ui); Repository.init(ui) }
            val activity=startTestActivity(instrument,context)
            val auto=instrument.uiAutomation
            auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            fun find(label: String): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if(node.text?.toString()==label || node.contentDescription?.toString()==label) return node
                    for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                    return null
                }
                return (auto.windows.mapNotNull { it.root }+listOfNotNull(auto.rootInActiveWindow)).firstNotNullOfOrNull { walk(it) }
            }
            fun waitFor(label: String,condition: ()->Boolean) {
                val end=SystemClock.uptimeMillis()+12000
                while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(50) }; error("Timed out: $label")
            }
            fun bounds(label: String)=Rect().also { (find(label) ?: error("Missing $label")).getBoundsInScreen(it) }
            fun click(label: String) {
                Thread.sleep(450)
                waitFor("click $label") {
                    runCatching {
                        var node=find(label) ?: return@runCatching false
                        while(!node.isClickable) node=node.parent ?: return@runCatching false
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                }
            }
            fun shot(name: String) { val bmp=auto.takeScreenshot()!!; File(context.getExternalFilesDir(null),name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            try {
                main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                Thread.sleep(2000)
                val folder=main { Repository.createFolder(null,"置顶UI文件夹") }
                val doc=main { Repository.createDoc(null,"置顶UI文档",DocContent(listOf(Block(text="测试正文保持不变")))) }
                val other=main { Repository.createDoc(null,"普通UI文档",DocContent(listOf(Block(text="普通文档")))) }
                main { Repository.setFilesGrid(false); AppState.goHome(Tab.FILES); AppState.menuFor=doc }
                click("置顶")
                waitFor("document pinned") { main { Repository.get(doc.id)!!.pinned } && find("置顶")==null }
                main { AppState.menuFor=folder }; click("置顶")
                waitFor("folder pinned") { main { Repository.get(folder.id)!!.pinned } && find("置顶")==null }
                for(mode in SortMode.entries) {
                    main { AppState.sort=SortSpec(mode,true) }
                    waitFor("settled list $mode") { runCatching { bounds(folder.title).top<bounds(doc.title).top && bounds(doc.title).top<bounds(other.title).top }.getOrDefault(false) }
                }
                shot("pinned-files-list.png")
                main { Repository.setFilesGrid(true) }; Thread.sleep(500)
                val a=bounds(folder.title); val b=bounds(doc.title)
                check(a.top<=b.top && (a.top!=b.top || a.left<b.left))
                check(find("已置顶")!=null)
                shot("pinned-files-grid.png")
                passed("document-and-folder-menu-pin-list-grid-and-sort-ui")

                main { AppState.menuFor=folder }; click("取消置顶")
                waitFor("folder unpinned") { main { !Repository.get(folder.id)!!.pinned } && find("取消置顶")==null }
                main { AppState.openDoc(doc.id) }
                waitFor("editor") { find("测试正文保持不变")!=null }
                click("更多"); click("置顶")
                waitFor("editor pin toggle") { main { !Repository.get(doc.id)!!.pinned } }
                Thread.sleep(1500)
                val density=context.resources.displayMetrics.density
                for(label in listOf("分享图片","分享文字","分享 Markdown")) {
                    var node=find(label) ?: error("Share action missing")
                    while(!node.isClickable) node=node.parent ?: error("Share action not clickable")
                    val size=Rect().also { node.getBoundsInScreen(it) }
                    check(size.height()>=48*density && size.height()<=84*density) { "Share area too tall: $size" }
                }
                shot("compact-document-menu.png")
                check(main { Repository.loadDoc(doc.id).bodyText() }=="测试正文保持不变")
                passed("editor-pin-toggle-and-compact-three-share-targets")

                main { AppState.goHome(Tab.HOME) }
                MockDav().use { server ->
                    server.files["latest"]=buildJsonObject {
                        put("tag_name","v999.0"); put("body",notes); put("draft",false); put("prerelease",false)
                        putJsonArray("assets") { addJsonObject { put("name","app.apk"); put("browser_download_url","http://127.0.0.1:${server.port}/backup/app.apk"); put("size",100) } }
                    }.toString().toByteArray()
                    val client=AppUpdates("http://127.0.0.1:${server.port}/backup/latest",true)
                    main { activity.setContent { KongyeTheme { Column(Modifier.fillMaxSize().background(Kongye.Surface)) { UpdateEntries(client) } } } }
                    click("检查更新")
                    waitFor("concise actual update dialog") { find("发现新版本 v999.0")!=null && find(summary)!=null }
                    check(find("第四条不应该显示。")==null && find("自动化测试全部通过。")==null)
                    shot("concise-update-dialog.png")
                    click("取消")
                }
                passed("update-check-fetches-release-and-shows-only-three-highlights")
            } catch(error: Throwable) { shot("v213-ui-failure.png"); throw error }
            finally { main { AppState.goHome(Tab.HOME); activity.finish() } }
        } finally { main { Settings.init(context); Repository.init(context) } }
    }
}
