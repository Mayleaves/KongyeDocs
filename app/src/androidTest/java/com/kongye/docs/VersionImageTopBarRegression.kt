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
import androidx.compose.ui.graphics.toArgb
import com.kongye.docs.data.*
import com.kongye.docs.ui.*
import com.kongye.docs.ui.editor.*
import com.kongye.docs.ui.theme.KongyeTheme
import com.kongye.docs.ui.theme.Kongye
import java.io.File
import kotlin.math.abs

internal object VersionImageTopBarRegression {
    fun run(inst: Instrumentation, pass: (String)->Unit) {
        fun <T> main(action: ()->T): T { var result: Result<T>?=null; inst.runOnMainSync { result=runCatching(action) }; return result!!.getOrThrow() }
        val context=inst.targetContext
        val activity=startTestActivity(inst,context)
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"visual379-${newId()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val out=File(context.getExternalFilesDir(null),"visual379").apply { mkdirs() }
        File(out,"top-bar-measurements.txt").writeText("")
        val auto=inst.uiAutomation
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun shot(name: String): Bitmap {
            val bitmap=auto.takeScreenshot() ?: error("Screenshot unavailable")
            File(out,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            return bitmap
        }
        fun waitFor(label: String, test: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<end) { if(test()) return; Thread.sleep(150) }
            shot("timeout.png").recycle()
            error("Timed out: $label")
        }
        fun find(label: String, description: Boolean=false): AccessibilityNodeInfo? {
            auto.clearCache()
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(!node.isVisibleToUser) return null
                if(node.contentDescription?.toString()==label || !description && node.text?.toString()?.contains(label)==true) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull(::walk)
        }
        fun bounds(label: String)=Rect().also { (find(label) ?: error("Missing $label")).getBoundsInScreen(it) }
        fun colors(bitmap: Bitmap, color: Int): Int {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.count { abs(Color.red(it)-Color.red(color))<4 && abs(Color.green(it)-Color.green(color))<4 && abs(Color.blue(it)-Color.blue(color))<4 }
        }
        fun doc(vararg blocks: Block)=DocContent(blocks.toList())
        fun picture(name: String)=Block(type=BlockType.IMAGE,image=name,imageRatio=4f)
        fun diff(before: DocContent, after: DocContent)=VersionDiff.previewWithImages("图片测试",before,DocVersion(kind=VersionKind.LOCAL,title="图片测试",content=after))
        val blue=Color.rgb(44,119,201); val orange=Color.rgb(214,122,56)
        try {
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture)
                Repository.items.value.toList().forEach { Repository.destroy(it.id) }
                Settings.update { it.copy(autoLocalVersion=true) }
            }
            val text=Block(text="字面 [图片]、😀 与私用字符 "+String(Character.toChars(0xF0000)))
            val same=doc(text,picture("aaaaaaaaaaaaaaaa.png"),Block(text="图后文字"))
            check(diff(same,same).filter { it.image.isNotEmpty() }.single()==DiffPart("",DiffKind.SAME,"aaaaaaaaaaaaaaaa.png"))
            check(diff(same,same).filter { it.image.isEmpty() }.joinToString("") { it.text }.contains(text.text))
            check(diff(doc(text),doc(Block(text="修改后的文字")))==VersionDiff.preview("图片测试",doc(text),DocVersion(kind=VersionKind.LOCAL,title="图片测试",content=doc(Block(text="修改后的文字")))))
            pass("unchanged-images-and-text-emoji-private-characters-preserved")
            val replaced=diff(doc(picture("bbbbbbbbbbbbbbbb.png")),doc(picture("aaaaaaaaaaaaaaaa.png"))).filter { it.image.isNotEmpty() }
            check(replaced==listOf(DiffPart("",DiffKind.REMOVED,"bbbbbbbbbbbbbbbb.png"),DiffPart("",DiffKind.ADDED,"aaaaaaaaaaaaaaaa.png")))
            check(diff(doc(text),doc(text,picture("aaaaaaaaaaaaaaaa.png"))).any { it.image=="aaaaaaaaaaaaaaaa.png" && it.kind==DiffKind.ADDED })
            check(diff(doc(text,picture("aaaaaaaaaaaaaaaa.png")),doc(text)).any { it.image=="aaaaaaaaaaaaaaaa.png" && it.kind==DiffKind.REMOVED })
            check(diff(doc(picture("aaaaaaaaaaaaaaaa.png"),picture("aaaaaaaaaaaaaaaa.png")),doc(picture("aaaaaaaaaaaaaaaa.png"),picture("aaaaaaaaaaaaaaaa.png"))).count { it.image=="aaaaaaaaaaaaaaaa.png" && it.kind==DiffKind.SAME }==2)
            pass("image-add-delete-replace-and-repeated-images-compared-by-identity")
            listOf("aaaaaaaaaaaaaaaa.png" to blue,"bbbbbbbbbbbbbbbb.png" to orange).forEach { (name,color) ->
                val bitmap=Bitmap.createBitmap(320,80,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                File(Repository.imagesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            val item=main {
                val item=Repository.createDoc(null,"图片测试",doc(Block(text="当前正文"),picture("bbbbbbbbbbbbbbbb.png")))
                Repository.addVersion(item.id,VersionKind.LOCAL,item.title,doc(Block(text="旧版正文"),picture("aaaaaaaaaaaaaaaa.png")))
                val editor=EditorSession.obtain(item.id,false)
                AppState.push(Screen.Editor(item.id))
                editor.onValueChange(editor.blocks.first(),androidx.compose.ui.text.input.TextFieldValue(ZWS+"未保存的当前正文"))
                AppState.push(Screen.VersionPreview(item.id,Repository.versions(item.id).first().id))
                activity.setContent { KongyeTheme { KongyeApp() } }
                item
            }
            waitFor("image comparison") { find("本地版本新增图片",true)!=null && find("本地版本删除图片",true)!=null && find("未保存的当前")!=null }
            Thread.sleep(300)
            val comparison=shot("version-image-comparison.png")
            check(colors(comparison,blue)>500 && colors(comparison,orange)>500) { "Actual image pixels absent from comparison" }; comparison.recycle()
            pass("real-version-comparison-displays-both-images-and-unsaved-current-text")
            check(find("文档预览")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("selected version document image") {
                val image=auto.takeScreenshot() ?: return@waitFor false
                val okay=colors(image,blue)>500 && colors(image,orange)==0; image.recycle(); okay
            }
            shot("version-document-preview.png").recycle()
            pass("document-preview-keeps-selected-version-image")
            main {
                AppState.goHome(Tab.HOME)
                val missing=Repository.createDoc(null,"缺失图片测试",doc(picture("dddddddddddddddd.png"),Block(text="图片后正文仍可查看")))
                Repository.addVersion(missing.id,VersionKind.LOCAL,missing.title,Repository.loadDoc(missing.id))
                AppState.push(Screen.VersionPreview(missing.id,Repository.versions(missing.id).first().id))
            }
            waitFor("missing image fallback") { find("图片文件不可用")!=null && find("图片后正文仍可查看")!=null }
            shot("missing-image-fallback.png").recycle()
            pass("missing-image-shows-placeholder-without-hiding-following-text")
            main { AppState.goHome(Tab.HOME); AppState.openDoc(item.id) }
            waitFor("editor") { main { EditorSession.current?.docId==item.id && EditorSession.current!!.layouts.isNotEmpty() } }
            Thread.sleep(800) // Measure after the navigation slide has finished.
            for((pin,star) in listOf(false to false,true to false,false to true,true to true)) {
                main {
                    if(Repository.get(item.id)!!.pinned!=pin) Repository.togglePin(item.id)
                    if(Repository.get(item.id)!!.starred!=star) Repository.toggleStar(item.id)
                }
                waitFor("top bar flags") { (find("已置顶")!=null)==pin && (find("已收藏")!=null)==star && find("自动保存")!=null }
                Thread.sleep(400)
                val label=bounds("自动保存")
                val icons=listOfNotNull(if(pin) "已置顶" else null,if(star) "已收藏" else null,"分享","更多").map(::bounds)
                val screen=shot("editor-top-bar-${if(pin) 1 else 0}${if(star) 1 else 0}.png")
                // Check the visible lettering, rather than the accessibility text rectangle.
                val ink=main { Kongye.Sub.toArgb() }
                val textPixels=mutableListOf<Int>()
                val actionStart=icons.minOf { it.left }
                for(y in label.top.coerceAtLeast(0) until label.bottom.coerceAtMost(screen.height))
                    for(x in screen.width-actionStart until actionStart) {
                        val pixel=screen.getPixel(x,y)
                        if(abs(Color.red(pixel)-Color.red(ink))<8 && abs(Color.green(pixel)-Color.green(ink))<8 && abs(Color.blue(pixel)-Color.blue(ink))<8) textPixels+=x
                    }
                check(textPixels.isNotEmpty()) { "Save label not painted" }
                val textCenter=(textPixels.min()+textPixels.max())/2f
                File(out,"top-bar-measurements.txt").appendText("pin=$pin star=$star textCenter=$textCenter screenCenter=${screen.width/2f} label=$label icons=$icons\n")
                check(abs(textCenter-screen.width/2f)<3) { "Save lettering not centered: $textCenter, width=${screen.width}" }
                check(icons.maxOf { it.centerY() }-icons.minOf { it.centerY() }<=2) { "Action icon centers differ: $icons" }
                check(abs(label.centerY()-icons.first().centerY())<=2) { "Label vertical center differs" }
                check(label.right<=icons.minOf { it.left }) { "Label overlaps actions" }
                screen.recycle()
            }
            pass("save-label-screen-centered-and-action-icons-aligned-for-all-pin-star-combinations")
        } finally {
            main {
                AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context)
            }
        }
    }
}
