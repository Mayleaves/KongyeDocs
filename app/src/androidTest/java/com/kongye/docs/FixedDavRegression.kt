package com.kongye.docs

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.Tab
import kotlinx.coroutines.runBlocking

internal object FixedDavRegression {
    fun run(inst: Instrumentation, pass: (String) -> Unit) {
        val context = inst.targetContext
        val previous = Settings.value.backup
        try {
            runBlocking {
                MockDav(fullPaths = true).use { server ->
                    val data = Repository.exportBackup()
                    for (legacy in listOf("空叶文档备份", "", "custom/nested")) {
                        Settings.updateBackup {
                            it.copy(
                                davUrl = "http://127.0.0.1:${server.port}/",
                                davDir = legacy,
                                allowInsecureDav = true,
                                davEnabled = false,
                            )
                        }
                        val name = AutoBackup.backupDavNow()
                        val currentPath = "/KongyeDocs/$name"
                        val uploaded = server.files.getValue(currentPath).toString(Charsets.UTF_8)
                        val decoded = BackupCodec.decode(uploaded)
                        check(decoded.items == BackupCodec.decode(data).items)
                        check(server.requests.any { it.first == "MKCOL" && it.second == "/KongyeDocs" })
                        check(server.requests.filter { it.first == "PUT" }.all { it.second.startsWith("/KongyeDocs/") })

                        val legacyPath = "/" + listOf(legacy, name).filter { it.isNotEmpty() }.joinToString("/")
                        server.files[legacyPath] = data.toByteArray()
                        val entries = AutoBackup.listDav()
                        val current = entries.first { it.name == name && it.davDirectory == "KongyeDocs" }
                        val older = entries.first { it.name == name && it.davDirectory == legacy }
                        check(current.id != older.id)
                        check(AutoBackup.readDav(current) == uploaded)
                        check(AutoBackup.readDav(older) == data)
                        BackupCodec.decode(AutoBackup.readDav(older))
                        check(runCatching { AutoBackup.readDav(older.copy(davDirectory = "unrelated")) }.isFailure)
                        AutoBackup.deleteDav(older)
                        check(!server.files.containsKey(legacyPath) && server.files.containsKey(currentPath))
                        pass("fixed-folder-upload-and-legacy-restore-" + legacy.ifEmpty { "root" })
                    }
                    Settings.updateBackup {
                        it.copy(davEnabled = true, lastDav = 0L, davKeepDays = 0)
                    }
                    AutoBackup.runScheduled(context)
                    check(Settings.value.backup.lastDav > 0 && Settings.value.backup.davError.isEmpty())
                    check(server.requests.filter { it.first == "PUT" }.all { it.second.startsWith("/KongyeDocs/") })
                    pass("scheduled-backup-also-uses-KongyeDocs")
                }
            }
            Settings.updateBackup { it.copy(davEnabled = false, davUrl = "") }
            inst.runOnMainSync { AppState.goHome(Tab.HOME) }
            val activity = startTestActivity(inst, context)
            try {
                inst.runOnMainSync { AppState.push(Screen.CloudBackup) }
                fun find(text: String): AccessibilityNodeInfo? {
                    if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
                    fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        node ?: return null
                        if (node.text?.toString() == text) return node
                        for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                        return null
                    }
                    return walk(inst.uiAutomation.rootInActiveWindow)
                }
                val deadline = SystemClock.uptimeMillis() + 15000
                while (find("服务器地址") == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                check(find("服务器地址") != null)
                check(find("备份目录") == null)
                pass("cloud-backup-screen-has-no-directory-setting")
            } finally {
                inst.runOnMainSync { AppState.goHome(Tab.HOME); activity.finish() }
            }
        } finally {
            Settings.updateBackup { previous }
        }
    }
}