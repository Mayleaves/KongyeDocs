package com.kongye.docs

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.kongye.docs.data.*
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.home.*
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.KongyeTheme
import com.kongye.docs.ui.vault.VaultDetailSheet
import java.io.File

/** Real device windows/screenshots with synthetic data; never reads the user's vault. */
object ForegroundToastCatRegression {
    fun run(inst: Instrumentation, pass: (String) -> Unit) {
        fun <T> main(action: () -> T): T {
            var result: Result<T>?=null
            inst.runOnMainSync { result=runCatching(action) }
            return result!!.getOrThrow()
        }
        val ctx=inst.targetContext
        CatMotionRegression.run(ctx,pass)
        val auto=inst.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun findAll(text: String): List<Pair<Int,AccessibilityNodeInfo>> {
            auto.clearCache()
            val found=mutableListOf<Pair<Int,AccessibilityNodeInfo>>()
            fun walk(layer: Int,node: AccessibilityNodeInfo) {
                if(node.text?.toString()==text || node.contentDescription?.toString()==text) found+=layer to node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(layer,it) }
            }
            auto.windows.forEach { window -> window.root?.takeIf { it.packageName?.toString()==ctx.packageName }?.let { walk(window.layer,it) } }
            return found
        }
        fun waitFor(label: String,test: () -> Boolean) {
            val end=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<end) { if(test()) return; Thread.sleep(50) }
            error("Timed out: $label")
        }
        fun click(label: String) {
            var node=findAll(label).maxByOrNull { it.first }?.second ?: error("Missing $label")
            while(!node.isClickable) node=node.parent ?: error("No click target: $label")
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val out=File(ctx.getExternalFilesDir(null),"toast-cat-360").apply { mkdirs() }
        fun screenshot(name: String): Bitmap {
            val bitmap=requireNotNull(auto.takeScreenshot())
            File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            return bitmap
        }
        val activity=startTestActivity(inst,ctx)
        var mode by mutableIntStateOf(0)
        var nested by mutableStateOf(false)
        var closed by mutableStateOf(false)
        val short=VaultEntry(type=VaultType.PASSWORD,name="提示层级测试",account="test-only-account",password="synthetic-password")
        val tall=short.copy(name="多字段提示层级测试",fields=(1..18).map { VaultField(label="测试字段$it",value="测试内容 $it\n第二行内容\n第三行内容") })
        val oldDark=Kongye.dark
        val clipboard=ctx.getSystemService(ClipboardManager::class.java)
        val oldClip=main { clipboard.primaryClip }
        try {
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    KongyeTheme {
                        Box(Modifier.fillMaxSize().background(Kongye.Surface)) {
                            Text("详情层级测试背景",Modifier.align(Alignment.TopCenter).statusBarsPadding())
                            if(mode<=1 && !closed) VaultDetailSheet(if(mode==0) short else tall,onEdit={nested=true},onDismiss={closed=true})
                            if(nested) NoticeDialog("前景对话框","提示应出现在这个对话框上方",onDismiss={nested=false})
                            if(mode==2) Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center) {
                                for((id,ground) in listOf(R.raw.cat_snore to 120f,R.raw.cat_wand to 120f,R.raw.cat_mouse to 125f)) {
                                    val art=remember(id) { loadCatArtwork(ctx.resources,id) }
                                    Canvas(Modifier.width(272.dp).height(260.dp)) {
                                        val k=size.width/136f
                                        withTransform({ scale(k,k,pivot=Offset.Zero); translate(-6f,-16f) }) {
                                            drawCatArtwork(art,ground,0f,Kongye.Ink2,Kongye.Surface)
                                        }
                                    }
                                }
                            }
                            if(mode==3) MeTab(emptyList())
                            if(mode==4) Box(Modifier.align(Alignment.Center).width(CAT_W.dp).height(CAT_H.dp)) {
                                LineCat(DayPart.NIGHT,{ listOf("测试") },Modifier.fillMaxSize())
                            }
                            ToastHost()
                        }
                    }
                }
            }
            waitFor("short details") { findAll("提示层级测试").isNotEmpty() }
            Thread.sleep(500)
            fun checkCopy(name: String,title: String) {
                click("密码")
                waitFor("foreground toast") { findAll("已复制密码").size>=2 }
                main { check(Toast.success) { "Copy must retain the original centered checkmark style" } }
                val toast=findAll("已复制密码").maxBy { it.first }
                val sheet=findAll(title).maxBy { it.first }
                check(toast.first>sheet.first) { "Toast is behind details: ${toast.first} <= ${sheet.first}" }
                val rect=Rect().also { toast.second.getBoundsInScreen(it) }
                check(!rect.isEmpty)
                Thread.sleep(300) // Let the original fade-in finish before sampling its background.
                val shot=screenshot(name)
                // The centered style's Text has no padding; sample beside its glyphs.
                val pixel=shot.getPixel((rect.left-12).coerceIn(0,shot.width-1),rect.centerY().coerceIn(0,shot.height-1))
                check(android.graphics.Color.red(pixel)<130 && android.graphics.Color.green(pixel)<130) { "Toast background not visible: $rect" }
                shot.recycle()
                main { check(clipboard.primaryClip!!.getItemAt(0).text.toString()==short.password) }
            }
            checkCopy("short-sheet-copy","提示层级测试")
            pass("short-sheet-copy-toast-is-above-modal-window-and-visible-in-screenshot")
            Thread.sleep(2300)
            main { mode=1 }
            waitFor("tall details") { findAll("多字段提示层级测试").isNotEmpty() }
            Thread.sleep(400)
            checkCopy("tall-sheet-copy","多字段提示层级测试")
            pass("tall-many-field-sheet-copy-toast-remains-visible-over-content")
            // The small, non-focusable popup must not block the sheet's own controls.
            click("编辑")
            waitFor("nested dialog") { findAll("前景对话框").isNotEmpty() }
            main { Toast.show("对话框前景提示") }
            waitFor("nested toast") { findAll("对话框前景提示").isNotEmpty() }
            check(findAll("对话框前景提示").maxOf { it.first }>findAll("前景对话框").maxOf { it.first })
            screenshot("nested-dialog-toast").recycle()
            pass("nested-dialog-owns-front-toast-and-popup-does-not-block-underlying-controls")
            main { nested=false; closed=true; Toast.show("返回页面提示") }
            waitFor("root fallback") { findAll("返回页面提示").isNotEmpty() }
            screenshot("root-toast-after-close").recycle()
            pass("closing-modal-restores-root-toast-host-without-duplicates")
            main { Toast.clear(); mode=2; Kongye.dark=false }
            Thread.sleep(700)
            screenshot("new-svg-light").recycle()
            main { Kongye.dark=true }; Thread.sleep(400)
            screenshot("new-svg-dark").recycle()
            main {
                for(id in listOf(R.raw.cat_snore,R.raw.cat_wand,R.raw.cat_mouse)) {
                    val art=loadCatArtwork(ctx.resources,id)
                    check(art.parts.size>=20)
                    check(art.parts.all { part -> val bounds=android.graphics.RectF(); part.path.computeBounds(bounds,true); bounds.left>=0 && bounds.right<=240 && bounds.top>=0 && bounds.bottom<=160 })
                    if(id!=R.raw.cat_snore) check(art.parts.count { it.clip!=null }==2)
                    if(id==R.raw.cat_wand) check(art.parts.any { it.dash!=null })
                }
            }
            pass("all-three-original-svg-resources-render-in-light-and-dark-with-eye-clips-and-dashed-cord")
            main { Kongye.dark=false; mode=3 }; Thread.sleep(650)
            screenshot("actual-regular-header").recycle()
            fun action(label: String,description: String,filename: String) {
                val cat=findAll("小猫").first().second
                val action=cat.actionList.first { it.label?.toString()==label }
                check(cat.performAction(action.id))
                waitFor(description) { findAll(description).isNotEmpty() }
                Thread.sleep(350); screenshot(filename).recycle()
                waitFor("action finished") { findAll("小猫").isNotEmpty() }
            }
            action("扑逗猫棒","小猫，扑逗猫棒","actual-wand-small")
            action("拨小老鼠","小猫，拨小老鼠","actual-mouse-small")
            main { mode=4 }; waitFor("night cat") { findAll("小猫，打呼噜").isNotEmpty() }
            Thread.sleep(350); screenshot("actual-snore-small").recycle()
            pass("real-linecat-widget-displays-replacement-wand-mouse-and-night-snore-poses")
        } catch(error: Throwable) {
            screenshot("failure").recycle()
            throw error
        } finally {
            main {
                Kongye.dark=oldDark; Toast.clear()
                if(oldClip!=null) clipboard.setPrimaryClip(oldClip) else clipboard.clearPrimaryClip()
                activity.finish()
            }
        }
    }
}
