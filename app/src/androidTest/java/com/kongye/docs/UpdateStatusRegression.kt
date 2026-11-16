package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.AppUpdates
import com.kongye.docs.data.UpdateStatus
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.Tab
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

internal object UpdateStatusRegression {
    fun run(inst: Instrumentation, pass: (String) -> Unit) {
        val context = inst.targetContext
        val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        val previousRelease = prefs.getString("release", null)
        val previousEtag = prefs.getString("etag", null)
        val current = AppUpdates.installed(context).version
        fun metadata(version: String) = buildJsonObject {
            put("tag_name", "v$version")
            put("draft", false)
            put("prerelease", false)
            put("body", "Update regression")
            putJsonArray("assets") {
                addJsonObject {
                    put("name", "KongyeDocs-v$version.apk")
                    put("browser_download_url", AppUpdates.REPOSITORY + "/releases/download/v$version/app.apk")
                    put("size", 1)
                }
            }
        }.toString()
        fun main(action: () -> Unit) = inst.runOnMainSync(action)
        fun find(label: String): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == label) return node
                for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                return null
            }
            return walk(inst.uiAutomation.rootInActiveWindow)
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(80)
            }
            error("Timed out waiting for $label")
        }
        main { AppState.goHome(Tab.HOME) }
        val activity = startTestActivity(inst, context)
        try {
            main { AppState.push(Screen.About) }
            await("About update rows") { find("检查更新") != null && find("更新历史") != null }
            val checkBounds = Rect().also { find("检查更新")!!.getBoundsInScreen(it) }
            val historyBounds = Rect().also { find("更新历史")!!.getBoundsInScreen(it) }
            check(checkBounds.centerY() < historyBounds.centerY())
            pass("check-update-row-precedes-update-history")
            MockDav().use { server ->
                val client = AppUpdates("http://127.0.0.1:" + server.port + "/backup/latest", true)
                runBlocking {
                    server.files["latest"] = metadata("9999.0").toByteArray()
                    UpdateStatus.check(context, client)
                    check(UpdateStatus.availableVersion.value == "9999.0")
                    await("new version indicator") { find("新版本 v9999.0") != null }
                    pass("newer-release-updates-about-row-without-opening-a-dialog")

                    server.files["latest"] = metadata(current).toByteArray()
                    UpdateStatus.check(context, client)
                    check(UpdateStatus.availableVersion.value == null)
                    await("current version clears indicator") { find("新版本 v9999.0") == null }
                    pass("current-version-clears-stale-update-indicator")

                    check(prefs.edit().putString("release", metadata("9999.0")).commit())
                    server.files["latest"] = "invalid response".toByteArray()
                    check(runCatching { UpdateStatus.check(context, client) }.isFailure)
                    check(UpdateStatus.availableVersion.value == "9999.0")
                    await("cached offline indicator") { find("新版本 v9999.0") != null }
                    pass("cached-newer-release-remains-visible-after-network-failure")

                    server.files["latest"] = metadata("0.1").toByteArray()
                    UpdateStatus.check(context, client)
                    check(UpdateStatus.availableVersion.value == null)
                    pass("older-releases-do-not-show-an-update")
                }
            }
        } finally {
            prefs.edit().putString("release", previousRelease).putString("etag", previousEtag).commit()
            main { AppState.goHome(Tab.HOME); activity.finish() }
        }
    }
}