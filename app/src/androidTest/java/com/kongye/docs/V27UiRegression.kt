package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.editor.EditorSession
import com.kongye.docs.ui.home.*
import com.kongye.docs.ui.theme.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object V27UiRegression {
    fun run(instrument: Instrumentation,context: Context,passed: (String)->Unit) {
        fun <T> main(block: ()->T): T { var result: Result<T>?=null; instrument.runOnMainSync { result=runCatching(block) }; return result!!.getOrThrow() }
        val auto=instrument.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(node.text?.toString()?.trimStart(8203.toChar())==label || node.contentDescription?.toString()==label) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return (auto.windows.mapNotNull { it.root }+listOfNotNull(auto.rootInActiveWindow)).firstNotNullOfOrNull { walk(it) }
        }
        fun waitFor(label: String, timeout: Long=10_000, condition: ()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+timeout
            while(SystemClock.uptimeMillis()<deadline) { if(condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        fun bounds(node: AccessibilityNodeInfo): Rect { node.refresh(); return Rect().also { node.getBoundsInScreen(it) } }
        fun touch(x: Float,y: Float,action: Int=MotionEvent.ACTION_DOWN,downTime: Long=SystemClock.uptimeMillis()) {
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(auto.injectInputEvent(event,true)); event.recycle()
        }
        fun tap(x: Float,y: Float) { val t=SystemClock.uptimeMillis(); touch(x,y,MotionEvent.ACTION_DOWN,t); Thread.sleep(40); touch(x,y,MotionEvent.ACTION_UP,t) }
        fun click(label: String) {
            waitFor("action $label") { find(label)!=null }
            Thread.sleep(450)
            val node=find(label) ?: error("Missing action: $label")
            node.refresh(); var target: AccessibilityNodeInfo?=node
            while(target!=null && !target.isClickable) target=target.parent
            if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)!=true) { val r=bounds(node); tap(r.exactCenterX(),r.exactCenterY()) }
        }
        fun screenshot(name: String) {
            val bmp=auto.takeScreenshot() ?: error("Screenshot failed")
            File(context.getExternalFilesDir(null),name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
        }
        val fixture=object: ContextWrapper(context) {
            private val root=File(context.filesDir,"v27-ui-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity=startTestActivity(instrument,context)
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitFor("startup skip control",1500) { find("启动画面，点击跳过")!=null }
            val intro=find("启动画面，点击跳过")!!
            val start=SystemClock.uptimeMillis()
            val introBounds=bounds(intro); tap(introBounds.exactCenterX(),introBounds.exactCenterY())
            waitFor("startup skipped immediately",800) { find("启动画面，点击跳过")==null }
            check(SystemClock.uptimeMillis()-start<800)
            passed("tap-startup-skips-immediately")

            val item=main { Repository.createDoc(null,"面板测试",DocContent(listOf(Block(type=BlockType.H1,text="第一节"),Block(text="面板与备份测试正文")))) }
            main { AppState.openDoc(item.id) }
            waitFor("editor loaded") { find("面板与备份测试正文")!=null }
            click("更多"); waitFor("info menu") { find("文档信息")!=null }; click("文档信息")
            waitFor("inline information panel") { find("文档信息")!=null && find("完成")!=null && find("面板与备份测试正文")!=null }
            screenshot("document-info-panel.png")
            val info=bounds(find("文档信息")!!)
            check(kotlin.math.abs(info.exactCenterX()-context.resources.displayMetrics.widthPixels/2f)<12)
            click("完成")
            click("更多"); waitFor("toc menu") { find("查看目录")!=null }; click("查看目录")
            waitFor("inline directory panel") { find("目录")!=null && find("完成")!=null }
            screenshot("document-toc-panel.png")
            click("完成")
            passed("information-and-directory-inline-panels")

            main { AppState.goHome(Tab.ME); AppState.push(Screen.LocalBackup) }
            waitFor("local backup settings") { find("备份频率")!=null }
            click("备份频率"); waitFor("frequency choice") { find("每 6 小时")!=null }
            val headings=auto.rootInActiveWindow
            screenshot("backup-frequency-centered.png")
            // White sheet may scroll but it must not be pulled down by a swipe.
            val from=bounds(find("每天")!!)
            val t=SystemClock.uptimeMillis(); touch(from.exactCenterX(),from.exactCenterY(),MotionEvent.ACTION_DOWN,t)
            repeat(12) { Thread.sleep(20); touch(from.exactCenterX(),from.exactCenterY()+it*9f,MotionEvent.ACTION_MOVE,t) }
            touch(from.exactCenterX(),from.exactCenterY()+108f,MotionEvent.ACTION_UP,t)
            Thread.sleep(450); check(find("每 6 小时")!=null)
            tap(100f,250f)
            waitFor("gray area dismisses") { find("每 6 小时")==null }
            click("旧备份"); waitFor("old backup centered title") { find("保留 7 天")!=null }
            check(find("旧备份保留多久")==null)
            screenshot("old-backups-centered.png"); tap(100f,250f)
            waitFor("old backups dismissed") { find("保留 7 天")==null }
            passed("choice-sheets-swipe-stays-open-gray-tap-closes")

            val tree=android.provider.DocumentsContract.buildTreeDocumentUri(context.packageName+".test-backups","root")
            main { Settings.updateBackup { it.copy(localTree=tree.toString(),localName="隔离测试目录") } }
            waitFor("local backup manual control") { find("立即备份")!=null }
            click("立即备份")
            waitFor("backup UI writes file") { runBlocking { AutoBackup.listLocal(context).isNotEmpty() } }
            val entry=runBlocking { AutoBackup.listLocal(context).first() }
            val name=entry.name
            val stamp=SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.CHINA).format(Date(entry.time))
            check(find("刷新列表")==null)
            waitFor("local backup row") { find(stamp)!=null }
            click(stamp); waitFor("local backup actions") { find("删除备份")!=null }; click("删除备份")
            waitFor("local red deletion confirmation") { find("删除")!=null }
            screenshot("local-delete-warning.png")
            click("取消"); check(File(context.filesDir,"v27-saf-fixture/$name").exists())
            click(stamp); waitFor("local delete again") { find("删除备份")!=null }; click("删除备份")
            waitFor("local confirmation again") { find("删除")!=null }; click("删除")
            waitFor("local file actually removed") { !File(context.filesDir,"v27-saf-fixture/$name").exists() }
            check(main { Repository.loadDoc(item.id).bodyText().contains("面板与备份测试正文") })
            passed("local-backup-confirm-cancel-and-physical-delete")

            MockDav().use { server ->
                val name=AutoBackup.MANUAL_PREFIX+"20261026-120000.json"
                val other=AutoBackup.MANUAL_PREFIX+"20261026-120100.json"
                server.files[name]="synthetic".toByteArray(); server.files[other]="preserve".toByteArray()
                main { Settings.updateBackup { it.copy(davUrl="http://127.0.0.1:${server.port}/",davDir="backup",allowInsecureDav=true) }; AppState.pop(); AppState.push(Screen.CloudBackup) }
                val stamp="2026/10/26 12:00"
                waitFor("cloud settings loaded") { find("服务器地址")!=null }
                Thread.sleep(400)
                val scrollTime=SystemClock.uptimeMillis()
                touch(780f,2100f,MotionEvent.ACTION_DOWN,scrollTime)
                repeat(16) { Thread.sleep(20); touch(780f,2100f-it*65f,MotionEvent.ACTION_MOVE,scrollTime) }
                touch(780f,1125f,MotionEvent.ACTION_UP,scrollTime)
                waitFor("cloud backup list") { find(stamp)!=null && find("刷新列表")!=null }
                check(bounds(find("刷新列表")!!).top<bounds(find(stamp)!!).top)
                screenshot("cloud-backup-manager.png")
                click(stamp); waitFor("cloud actions") { find("删除备份")!=null }; click("删除备份")
                waitFor("cloud warning") { find("删除云端备份")!=null && find("删除")!=null }
                screenshot("cloud-delete-warning.png")
                click("取消"); check(server.files.containsKey(name))
                click(stamp); waitFor("cloud actions again") { find("删除备份")!=null }; click("删除备份")
                waitFor("cloud warning again") { find("删除")!=null }; click("删除")
                waitFor("real remote DELETE") { !server.files.containsKey(name) }
                check(server.files.containsKey(other))
            }
            passed("cloud-backup-refresh-first-and-real-delete")

            main {
                AppState.goHome(Tab.HOME)
                activity.setContent { KongyeTheme { Box(Modifier.fillMaxSize().background(Kongye.Surface),contentAlignment=Alignment.Center) { LineCat(DayPart.NOON,{listOf("中午好")},Modifier.size(CAT_W.dp,CAT_H.dp)) } } }
            }
            Thread.sleep(600)
            fun catFrame(name: String): Bitmap {
                val all=auto.takeScreenshot()!!
                val crop=Bitmap.createBitmap(all,(all.width-400)/2,(all.height-400)/2,400,400); all.recycle()
                File(context.getExternalFilesDir(null),name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) }
                return crop
            }
            val first=catFrame("noon-cat-1.png"); Thread.sleep(650); val next=catFrame("noon-cat-2.png")
            val density=context.resources.displayMetrics.density
            val x=(200-CAT_W*density/2+2*.7f*density).toInt()
            val y=(200-CAT_H*density/2+47*.7f*density).toInt()
            var changed=0
            for(py in y..(y+32*.7f*density).toInt()) for(px in x..(x+25*.7f*density).toInt()) if(first.getPixel(px,py)!=next.getPixel(px,py)) changed++
            check(changed>5) { "No moving steam above the noon bowl" }
            first.recycle(); next.recycle()
            passed("noon-bowl-steam-moves-between-frames")
        } catch(error: Throwable) { screenshot("v27-ui-failure.png"); throw error }
        finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context); BackupJobService.schedule(context) } }
    }
}
