package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import java.io.File

/**
 * Manual probe (not part of any suite): opens a synthetic document and keeps it open so a real
 * third-party keyboard can be exercised by hand / adb taps; logs the document every second (tag KongyeProbe).
 * Args: probeSeconds (default 90), probeCaret = "body" | "image1" | "image2" | "after".
 */
object ImeProbe {
    fun run(instrument: Instrumentation, args: Bundle?) {
        fun <T> main(action: () -> T): T { var result: Result<T>? = null; instrument.runOnMainSync { result = runCatching(action) }; return result!!.getOrThrow() }
        fun waitFor(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 14000
            while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(40) }
            error("Timed out: $label")
        }
        val seconds = args?.getString("probeSeconds")?.toIntOrNull() ?: 90
        val stop=File(instrument.targetContext.getExternalFilesDir(null),"ime-probe-stop").also { it.delete() }
        val caret = args?.getString("probeCaret") ?: "body"
        val context = instrument.targetContext
        val fixture = object : ContextWrapper(context) {
            val root = File(context.filesDir, "ime-probe-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        main { AppState.goHome(Tab.HOME); Settings.init(fixture); Repository.init(fixture) }
        val activity = startTestActivity(instrument, context)
        fun e() = EditorSession.current!!
        fun dump(label: String) = main {
            val ed = EditorSession.current ?: return@main
            Log.d("KongyeProbe", "$label focused=${ed.blocks.indexOfFirst { it.id == ed.focusedId }} imageCaret=${ed.imageCaret?.offset} blocks=" +
                ed.blocks.joinToString(" | ") { b -> if (b.type.isText) "${b.type}:'${b.text}'@${b.value.selection.start - 1} checked=${b.checked} styles=${b.toBlock().spans}" + (b.value.composition?.let { "c${it}" } ?: "") else "${b.type}" })
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread.sleep(1200)
            val imageName = "${newId()}.png"
            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 180, 110)) }
            File(Repository.imagesDir, imageName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val rich=args?.getString("probeRich")=="true"
            val ratio=args?.getString("probeImageRatio")?.toFloatOrNull()?.coerceIn(0.25f,5f) ?: (16f/9f)
            val doc = main { Repository.createDoc(null, "输入法测试", DocContent(listOf(
                Block(type=if(rich) BlockType.H2 else BlockType.TEXT,text = "第一段测试",spans=if(rich) listOf(StyleRange(0,2,InlineStyle.BOLD)) else emptyList()),
                Block(type = BlockType.IMAGE, image = imageName, imageRatio = ratio),
                Block(type=if(rich) BlockType.TODO else BlockType.TEXT,text = "第二段测试",checked=rich)))) }
            main { AppState.openDoc(doc.id) }
            waitFor("editor") { main { EditorSession.current?.layouts?.isNotEmpty() == true } }
            waitFor("window focus") { main { activity.hasWindowFocus() } }
            instrument.waitForIdleSync(); Thread.sleep(650)
            // A real tap focuses the first paragraph and opens the current keyboard.
            val p = main { val b = e().blocks[0]; e().selection.coordinates[b.id]!!.localToScreen(e().layouts[b.id]!!.getBoundingBox(2).center) }
            instrument.uiAutomation.executeShellCommand("input tap ${p.x.toInt()} ${p.y.toInt()}").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
            main { val b=e().blocks[0]; e().requestFocus(b,b.value.text.length) }
            Thread.sleep(800)
            when (caret) {
                "image1" -> main { e().placeCaret(SelectionPoint(e().blocks[1].id, 1), deferFocus = true) }
                "image2" -> main { e().placeCaret(SelectionPoint(e().blocks[1].id, 2), deferFocus = true) }
                "after" -> main { e().placeCaret(SelectionPoint(e().blocks[2].id, 1), deferFocus = true) }
            }
            Log.d("KongyeProbe", "READY caret=$caret seconds=$seconds")
            val end = SystemClock.uptimeMillis() + seconds * 1000L
            var last = ""
            while (SystemClock.uptimeMillis() < end && !stop.exists()) {
                val now = main { EditorSession.current?.let { ed -> ed.blocks.joinToString("|") { "${it.type}${it.text}${it.value.selection}" } + ed.imageCaret } ?: "closed" }
                if (now != last) { dump("state"); last = now }
                Thread.sleep(250)
            }
        } finally { main { AppState.goHome(Tab.HOME); activity.finish(); Settings.init(context); Repository.init(context) } }
    }
}
