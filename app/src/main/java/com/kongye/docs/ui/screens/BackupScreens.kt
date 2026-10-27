package com.kongye.docs.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.AutoBackup
import com.kongye.docs.data.BackupEntry
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.InputDialog
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.Share
import com.kongye.docs.util.formatTime
import com.kongye.docs.util.sizeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 公共部分 ----------------

private val intervals = listOf(6 to "每 6 小时", 24 to "每天", 72 to "每 3 天", 168 to "每周")
private val keeps = listOf(7 to "保留 7 天", 30 to "保留 30 天", 90 to "保留 90 天", 0 to "永久保留")

fun intervalLabel(hours: Int) = intervals.firstOrNull { it.first == hours }?.second ?: "每 $hours 小时"
private fun keepLabel(days: Int) = keeps.firstOrNull { it.first == days }?.second ?: "保留 $days 天"

private val entryTime = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA)

/** 页面上的灰色说明文字 */
@Composable
private fun Hint(text: String, color: Color = Kongye.Text3) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = color)
}

@Composable
private fun BackupStatus(enabled: Boolean, last: Long, error: String) {
    when {
        error.isNotEmpty() -> Hint("上次自动备份失败：$error", Kongye.Danger)
        last > 0 -> Hint("上次自动备份：${formatTime(last)}")
        enabled -> Hint("还没有自动备份过")
    }
}

/** 清单里的一行说明（不能点，没有箭头） */
@Composable
private fun InfoRow(text: String, color: Color = Kongye.Sub) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(horizontal = 20.dp, vertical = 17.dp), fontSize = 15.sp, color = color)
        HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}

/** 备份恢复里的备份清单：读取中 / 出错 / 为空时各显示一行说明，否则每个备份一行，点击恢复 */
@Composable
private fun ColumnScope.BackupRows(list: List<BackupEntry>?, error: String, emptyText: String, onPick: (BackupEntry) -> Unit) {
    when {
        list == null -> InfoRow("正在读取备份…")
        error.isNotEmpty() -> InfoRow("读取失败：$error", Kongye.Danger)
        list.isEmpty() -> InfoRow(emptyText)
        else -> list.forEach { e ->
            SettingRow(entryTime.format(Date(e.time)), listOf(if (e.auto) "自动" else "手动", sizeText(e.size)).filter { it.isNotEmpty() }.joinToString(" · ")) {
                onPick(e)
            }
        }
    }
}

/** 恢复前确认；恢复是合并：同一篇文档用备份覆盖，备份之后新建的保留 */
@Composable
private fun RestoreConfirm(entry: BackupEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "从备份恢复",
        message = "恢复 ${entryTime.format(Date(entry.time))} 的备份？\n备份里的文档会覆盖同一篇文档的当前内容，备份之后新建的文档会保留。",
        confirmText = "恢复",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** 读取备份内容并导入 */
private fun CoroutineScope.restore(read: suspend () -> String?) = launch {
    val text = runCatching { read() }.getOrElse {
        Toast.show("读取备份失败：" + AutoBackup.describe(it))
        return@launch
    }
    runCatching { withContext(Dispatchers.IO) { Repository.importBackup(text ?: error("备份为空")) } }
        .onSuccess { Toast.done("已恢复 $it 篇文档") }
        .onFailure { Toast.show("恢复失败，原文件已保留：" + AutoBackup.describe(it)) }
}

private fun manualFileName() = AutoBackup.MANUAL_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

private val backupMimeTypes = arrayOf("application/json", "application/octet-stream", "text/*")

// ---------------- 本地备份 ----------------

/** 本地备份：自动备份到所选文件夹、手动备份、从文件夹里的备份（或任意备份文件）恢复 */
@Composable
fun LocalBackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Settings.state.collectAsState()
    val s = settings.backup
    var choice by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var listError by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf<BackupEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var safetyBackups by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var safetyPick by remember { mutableStateOf<BackupEntry?>(null) }
    var selectedBackup by remember { mutableStateOf<Pair<BackupEntry, Boolean>?>(null) }
    var deletePick by remember { mutableStateOf<Pair<BackupEntry, Boolean>?>(null) }
    var deletingBackup by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { safetyBackups = AutoBackup.listSafety(context) }
    // 从开关进入选文件夹时，选好后顺便打开自动备份
    var enableAfterPick by remember { mutableStateOf(false) }

    LaunchedEffect(s.localTree, s.lastLocal, reloadKey) {
        if (s.localTree.isEmpty()) {
            backups = emptyList()
            return@LaunchedEffect
        }
        runCatching { AutoBackup.listLocal(context) }
            .onSuccess { backups = it; listError = "" }
            .onFailure { backups = emptyList(); listError = AutoBackup.describe(it) }
    }

    // 选择备份文件夹：取得长期读写权限；开启了自动备份的话马上备份一次
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val old = Settings.value.backup.localTree
        if (old.isNotEmpty() && old != uri.toString()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        Settings.updateBackup {
            it.copy(
                localEnabled = it.localEnabled || enableAfterPick, localTree = uri.toString(), localName = AutoBackup.folderName(uri),
                localError = "", lastLocal = 0L,
            )
        }
        enableAfterPick = false
        AutoBackup.runIfDue(context)
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = Repository.exportBackup()
                    check(Share.writeText(context, uri, text)) { "无法写入备份" }
                    check(Share.readText(context, uri) == text) { "备份回读校验失败" }
                }
            }.onSuccess { Toast.done("备份已保存并校验") }.onFailure { Toast.show("备份失败：${it.message}") }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            pendingImport = withContext(Dispatchers.IO) { Share.readText(context, uri) }
            if (pendingImport == null) Toast.show("无法读取备份")
        }
    }

    Column(Modifier.fillMaxSize().background(Kongye.Band)) {
        TopBar(title = "本地备份", onBack = { AppState.pop() }, divider = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsTitle("自动备份")
            SettingsGroup {
                SwitchRow("自动备份", s.localEnabled) { on ->
                    when {
                        !on -> Settings.updateBackup { it.copy(localEnabled = false) }
                        s.localTree.isEmpty() -> { enableAfterPick = true; pickFolder.launch(null) }
                        else -> { Settings.updateBackup { it.copy(localEnabled = true) }; AutoBackup.runIfDue(context) }
                    }
                }
                SettingRow("备份位置", s.localName.ifEmpty { "点击选择文件夹" }) { pickFolder.launch(null) }
                SettingRow("备份频率", intervalLabel(s.intervalHours)) { choice = "interval" }
                SettingRow("旧备份", keepLabel(s.keepDays), last = true) { choice = "keep" }
            }
            BackupStatus(s.localEnabled, s.lastLocal, s.localError)

            SettingsTitle("手动备份")
            SettingsGroup {
                if (s.localTree.isNotEmpty()) {
                    SettingRow("立即备份", if (busy) "正在备份…" else "存到备份位置") {
                        if (busy) return@SettingRow
                        busy = true
                        scope.launch {
                            runCatching { AutoBackup.backupLocalNow(context) }
                                .onSuccess {
                                    Toast.done("已备份到 ${s.localName}")
                                    backups = runCatching { AutoBackup.listLocal(context) }.getOrDefault(backups)
                                }
                                .onFailure { Toast.show("备份失败：" + AutoBackup.describe(it)) }
                            busy = false
                        }
                    }
                    SettingRow("另存到其他位置", last = true) { saveAs.launch(manualFileName()) }
                } else {
                    SettingRow("立即备份", "选择保存位置", last = true) { saveAs.launch(manualFileName()) }
                }
            }

            SettingsTitle("备份恢复")
            SettingsGroup {
                if (s.localTree.isNotEmpty()) {
                    BackupRows(backups, listError, "备份位置里还没有备份") { selectedBackup = it to false }
                }
                SettingRow("从其他备份文件恢复", last = true) { openFile.launch(backupMimeTypes) }
            }
            SettingsTitle("恢复前安全备份")
            SettingsGroup {
                BackupRows(safetyBackups, "", "还没有恢复前安全备份") { selectedBackup = it to true }
            }
            Hint("安全备份保存在应用内部，卸载或清除应用数据会删除。需要长期保存时，请另存到外部位置。")
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    selectedBackup?.let { (entry, safety) ->
        ActionSheet(onDismiss = { selectedBackup = null }, actions = listOf(
            SheetAction(KongyeIcons.Restore, "恢复此备份") { if (safety) safetyPick = entry else restoring = entry },
            SheetAction(KongyeIcons.Trash, "删除备份", danger = true) { deletePick = entry to safety },
        ))
    }
    deletePick?.let { (entry, safety) ->
        ConfirmDialog(title = "删除备份", message = "将从这台设备删除这份备份文件，删除后无法再用它恢复文档。此操作不能在应用中撤销。当前文档和其他备份不会被删除。", confirmText = "删除", danger = true,
            onDismiss = { if (!deletingBackup) deletePick = null }) {
            if (!deletingBackup) {
                deletingBackup = true
                scope.launch {
                    runCatching { if (safety) AutoBackup.deleteSafety(context, entry) else AutoBackup.deleteLocal(context, entry, s.localTree) }
                        .onSuccess { Toast.done("备份文件已删除"); reloadKey++; safetyBackups = AutoBackup.listSafety(context); deletePick = null }
                        .onFailure { Toast.show("删除失败：" + AutoBackup.describe(it)) }
                    deletingBackup = false
                }
            }
        }
    }
    when (choice) {
        "interval" -> ChoiceSheet("备份频率", intervals, s.intervalHours, { choice = null }) { v -> Settings.updateBackup { it.copy(intervalHours = v) } }
        "keep" -> ChoiceSheet("旧备份", keeps, s.keepDays, { choice = null }) { v -> Settings.updateBackup { it.copy(keepDays = v) } }
    }
    restoring?.let { e ->
        RestoreConfirm(e, onDismiss = { restoring = null }) {
            restoring = null
            scope.restore { AutoBackup.readLocal(context, e) }
        }
    }
    pendingImport?.let { text ->
        ConfirmDialog(title = "从文件恢复", message = "同一篇文档会被备份覆盖。恢复前会保留当前数据的安全副本。", confirmText = "恢复", onDismiss = { pendingImport = null }, onConfirm = {
            pendingImport = null
            scope.restore { text }
        })
    }
    safetyPick?.let { entry ->
        RestoreConfirm(entry, onDismiss = { safetyPick = null }) {
            safetyPick = null
            scope.restore { AutoBackup.readSafety(context, entry) }
        }
    }
}

// ---------------- 云端备份（WebDAV） ----------------

private enum class DavField { URL, USER, PASSWORD, DIR }

/** 云端备份：WebDAV 服务器设置，自动备份、手动备份到云端，从云端的备份恢复 */
@Composable
fun CloudBackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Settings.state.collectAsState()
    val s = settings.backup
    var choice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DavField?>(null) }
    var davTest by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var listError by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf<BackupEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selectedBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var deletePick by remember { mutableStateOf<BackupEntry?>(null) }
    var deletingBackup by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val configured = s.davUrl.isNotBlank()

    LaunchedEffect(s.davUrl, s.davUser, s.davPassword, s.davDir, s.lastDav, reloadKey) {
        if (!configured) {
            backups = emptyList()
            return@LaunchedEffect
        }
        backups = null
        runCatching { AutoBackup.listDav() }
            .onSuccess { backups = it; listError = "" }
            .onFailure { backups = emptyList(); listError = AutoBackup.describe(it) }
    }

    fun needServer(): Boolean {
        if (configured) return false
        Toast.show("请先填写服务器地址")
        editing = DavField.URL
        return true
    }

    Column(Modifier.fillMaxSize().background(Kongye.Band)) {
        TopBar(title = "云端备份", onBack = { AppState.pop() }, divider = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsTitle("WebDAV 服务器")
            SettingsGroup {
                SettingRow("服务器地址", s.davUrl.ifBlank { "未填写" }) { editing = DavField.URL }
                SettingRow("账号", s.davUser.ifBlank { "未填写" }) { editing = DavField.USER }
                SettingRow("密码", if (s.davPassword.isEmpty()) "未填写" else "••••••") { editing = DavField.PASSWORD }
                SwitchRow("允许局域网 HTTP（明文）", s.allowInsecureDav) { on -> Settings.updateBackup { it.copy(allowInsecureDav = on) } }
                SettingRow("备份目录", s.davDir.ifBlank { "（根目录）" }) { editing = DavField.DIR }
                SettingRow(
                    "测试连接", davTest ?: "", last = true, showChevron = false,
                    valueColor = when {
                        davTest?.startsWith("连接成功") == true -> Kongye.Primary
                        davTest?.startsWith("连接失败") == true -> Kongye.Danger
                        else -> Kongye.Sub
                    },
                ) {
                    if (!needServer()) scope.launch {
                        davTest = "正在连接…"
                        davTest = AutoBackup.testDav(Settings.value.backup)
                    }
                }
            }

            SettingsTitle("自动备份")
            SettingsGroup {
                SwitchRow("自动备份", s.davEnabled) { on ->
                    if (!on) Settings.updateBackup { it.copy(davEnabled = false) }
                    else if (!needServer()) {
                        Settings.updateBackup { it.copy(davEnabled = true) }
                        AutoBackup.runIfDue(context)
                    }
                }
                SettingRow("备份频率", intervalLabel(s.davIntervalHours)) { choice = "interval" }
                SettingRow("旧备份", keepLabel(s.davKeepDays), last = true) { choice = "keep" }
            }
            BackupStatus(s.davEnabled, s.lastDav, s.davError)

            SettingsTitle("手动备份")
            SettingsGroup {
                SettingRow("立即备份到云端", if (busy) "正在上传…" else null, last = true) {
                    if (busy || needServer()) return@SettingRow
                    busy = true
                    scope.launch {
                        runCatching { AutoBackup.backupDavNow() }
                            .onSuccess { Toast.done("已备份到云端"); reloadKey++ }
                            .onFailure { Toast.show("备份失败：" + AutoBackup.describe(it)) }
                        busy = false
                    }
                }
            }

            SettingsTitle("备份恢复")
            SettingsGroup {
                if (configured) {
                    SettingRow("刷新列表", showChevron = false) { reloadKey++ }
                    BackupRows(backups, listError, "云端还没有备份") { selectedBackup = it }
                } else {
                    SettingRow("填写服务器后可以查看云端的备份", last = true) { editing = DavField.URL }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    selectedBackup?.let { entry ->
        ActionSheet(onDismiss = { selectedBackup = null }, actions = listOf(
            SheetAction(KongyeIcons.Restore, "恢复此备份") { restoring = entry },
            SheetAction(KongyeIcons.Trash, "删除备份", danger = true) { deletePick = entry },
        ))
    }
    deletePick?.let { entry ->
        ConfirmDialog(title = "删除云端备份", message = "将从网盘的备份目录删除这个文件，删除后无法再用它恢复文档。此操作不能在应用中撤销。当前文档和其他备份不会被删除。", confirmText = "删除", danger = true,
            onDismiss = { if (!deletingBackup) deletePick = null }) {
            if (!deletingBackup) {
                deletingBackup = true
                scope.launch {
                    runCatching { AutoBackup.deleteDav(entry, s) }
                        .onSuccess { Toast.done("云端备份文件已删除"); reloadKey++; deletePick = null }
                        .onFailure { Toast.show("删除失败：" + AutoBackup.describe(it)) }
                    deletingBackup = false
                }
            }
        }
    }
    when (choice) {
        "interval" -> ChoiceSheet("备份频率", intervals, s.davIntervalHours, { choice = null }) { v -> Settings.updateBackup { it.copy(davIntervalHours = v) } }
        "keep" -> ChoiceSheet("旧备份", keeps, s.davKeepDays, { choice = null }) { v -> Settings.updateBackup { it.copy(davKeepDays = v) } }
    }
    restoring?.let { e ->
        RestoreConfirm(e, onDismiss = { restoring = null }) {
            restoring = null
            Toast.show("正在下载备份…")
            scope.restore { AutoBackup.readDav(e) }
        }
    }
    editing?.let { field ->
        val close = { editing = null }
        when (field) {
            DavField.URL -> InputDialog(
                "服务器地址", s.davUrl, "https://dav.jianguoyun.com/dav/", keyboardType = KeyboardType.Uri, onDismiss = close,
            ) { v -> close(); davTest = null; Settings.updateBackup { it.copy(davUrl = AutoBackup.normalizeUrl(v), davError = "") } }
            DavField.USER -> InputDialog("账号", s.davUser, "通常是邮箱或用户名", allowEmpty = true, keyboardType = KeyboardType.Email, onDismiss = close) { v ->
                close(); davTest = null; Settings.updateBackup { it.copy(davUser = v.trim(), davError = "") }
            }
            DavField.PASSWORD -> InputDialog("密码", s.davPassword, "应用密码", allowEmpty = true, password = true, onDismiss = close) { v ->
                close(); davTest = null; Settings.updateBackup { it.copy(davPassword = v, davError = "") }
            }
            DavField.DIR -> InputDialog("备份目录", s.davDir, "留空则放在根目录", allowEmpty = true, onDismiss = close) { v ->
                close(); Settings.updateBackup { it.copy(davDir = v.trim().trim('/')) }
            }
        }
    }
}
