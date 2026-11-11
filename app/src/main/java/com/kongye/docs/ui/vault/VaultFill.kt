package com.kongye.docs.ui.vault

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VaultAccessLog
import com.kongye.docs.data.VaultAccessRecord
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.screens.SettingsGroup
import com.kongye.docs.ui.screens.SettingsTitle
import com.kongye.docs.ui.screens.SwitchRow
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.formatTime
import com.kongye.docs.vault.VaultAccess
import com.kongye.docs.vault.VaultApi
import kotlinx.coroutines.delay

/** 包名 → App 名称；没装或看不到时显示包名 */
internal fun appName(context: Context, pkg: String): String = VaultApi.appLabel(context, pkg) ?: pkg

private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

private val methodNames = mapOf(
    "match" to "查找匹配", "search" to "搜索", "reveal" to "读取账号密码", "generate" to "生成密码",
    "save" to "保存密码", "bind" to "绑定 App",
)

/** 访问记录里的调用方名称：系统自动填充不是一个 App */
private fun callerName(context: Context, caller: String) = if (caller == VaultApi.AUTOFILL_CALLER) "系统自动填充" else appName(context, caller)
private val statusNames = mapOf(
    "ok" to "成功", "locked" to "未解锁", "disabled" to "未开启", "denied" to "已拒绝", "not_found" to "条目不存在", "error" to "出错",
)

// ---------------- 密码空间 → 密码填充 ----------------

@Composable
fun VaultFillScreen() {
    val context = LocalContext.current
    val settings by Settings.state.collectAsState()
    val records by VaultAccessLog.records.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    // 解锁剩余时间：每秒刷新
    var until by remember { mutableLongStateOf(VaultAccess.unlockedUntil(context)) }
    // 是否已选为系统自动填充服务：从系统设置回来后要刷新，所以也每秒看一次
    var autofillOn by remember { mutableStateOf(VaultApi.autofillEnabled(context)) }
    LaunchedEffect(Unit) { while (true) { until = VaultAccess.unlockedUntil(context); autofillOn = VaultApi.autofillEnabled(context); delay(1000) } }
    val apps = listOf("com.kongye.keyboard" to "空叶键盘", "com.kongye.floatball" to "空叶悬浮球")

    Column(Modifier.fillMaxSize().background(Kongye.Band)) {
        TopBar(title = "密码填充", onBack = { AppState.pop() }, divider = true)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                Spacer(Modifier.height(12.dp))
                SettingsGroup {
                    SwitchRow("允许空叶键盘 / 悬浮球读取", settings.vaultFill) { on -> Settings.update { it.copy(vaultFill = on) } }
                }
                Text(
                    "开启后，空叶键盘可以在登录框的候选栏里填账号密码，空叶悬浮球在你点「填充密码」时填写。" +
                        "读取账号密码前要用指纹或锁屏密码解锁，${VaultAccess.UNLOCK_MILLIS / 60_000} 分钟内有效，锁屏立即失效。只有和空叶文档同一签名的这两个 App 能读取。",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp, color = Kongye.Text3, lineHeight = 19.sp,
                )
                SettingsTitle("系统自动填充")
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("自动填充服务", fontSize = 16.sp, color = Kongye.Ink)
                        Spacer(Modifier.weight(1f))
                        Text(if (autofillOn) "已启用" else "未启用", fontSize = 15.sp, color = if (autofillOn) Kongye.Primary else Kongye.Sub)
                        Text(
                            if (autofillOn) "更改" else "去启用",
                            Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).clickable {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:${context.packageName}"))
                                runCatching { context.startActivity(intent) }.onFailure {
                                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                                }
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                            fontSize = 15.sp, color = Kongye.Link,
                        )
                    }
                    HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
                    SwitchRow("下拉框显示完整账号", settings.autofillFullAccount) { on -> Settings.update { it.copy(autofillFullAccount = on) } }
                    SwitchRow("保存前确认", settings.autofillConfirmSave) { on -> Settings.update { it.copy(autofillConfirmSave = on) } }
                }
                Text(
                    "选为系统的自动填充服务后，点到登录框时输入框下会弹出账号（空叶键盘可以设成显示在工具栏），注册页可以生成符合要求的强密码，提交后提示保存。" +
                        "选中账号后同样要用指纹或锁屏密码解锁。Chrome 还要在它自己的设置里打开「使用其他服务自动填充」；有的 App 会关掉自动填充，这时用空叶键盘或悬浮球。",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp, color = Kongye.Text3, lineHeight = 19.sp,
                )
                SettingsTitle("状态")
                SettingsGroup {
                    apps.forEach { (pkg, label) ->
                        val ok = installed(context, pkg) || (pkg == "com.kongye.keyboard" && installed(context, "$pkg.debug"))
                        InfoRow(label, if (ok) "已安装" else "未安装")
                    }
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val left = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        Text("解锁状态", fontSize = 16.sp, color = Kongye.Ink)
                        Spacer(Modifier.weight(1f))
                        Text(if (until > 0) "已解锁，还剩 ${left / 60}:${"%02d".format(left % 60)}" else "未解锁", fontSize = 15.sp, color = if (until > 0) Kongye.Primary else Kongye.Sub)
                        if (until > 0) Text(
                            "立即锁定", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { VaultAccess.lock(); until = 0L; Toast.done("已锁定") }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            fontSize = 15.sp, color = Kongye.Link,
                        ) else Spacer(Modifier.width(12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近访问（最多 ${VaultAccessLog.LIMIT} 条，不记录账号和密码）", Modifier.weight(1f), fontSize = 13.sp, color = Kongye.Text3)
                    if (records.isNotEmpty()) Text(
                        "清空", Modifier.clip(RoundedCornerShape(6.dp)).clickable { clearing = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 13.sp, color = Kongye.Link,
                    )
                }
                if (records.isEmpty()) Box(Modifier.fillMaxWidth().background(Kongye.Surface).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text("还没有访问记录", fontSize = 14.sp, color = Kongye.Sub)
                }
            }
            items(records, key = { "${it.time}-${it.caller}-${it.method}-${it.entry}-${it.target}" }) { r -> AccessRow(context, r) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (clearing) ConfirmDialog("清空访问记录", "只清空记录，不影响密码空间里的内容。", "清空", danger = true, onDismiss = { clearing = false }) {
        clearing = false
        VaultAccessLog.clear()
        Toast.done("已清空")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, color = Kongye.Ink)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 15.sp, color = Kongye.Sub)
        }
        HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}

/** 一条访问记录：「空叶键盘 · 读取账号密码 · GitHub」，下面是目标 App / 网站、时间和结果 */
@Composable
private fun AccessRow(context: Context, r: VaultAccessRecord) {
    val caller = remember(r.caller) { callerName(context, r.caller) }
    val target = remember(r.target) { if (r.target.isEmpty()) "" else appName(context, r.target) }
    Column(Modifier.fillMaxWidth().background(Kongye.Surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(caller, methodNames[r.method] ?: r.method, r.entry).filter { it.isNotEmpty() }.joinToString(" · "),
                    Modifier.weight(1f), fontSize = 15.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(statusNames[r.status] ?: r.status, fontSize = 13.sp, color = if (r.status == "ok") Kongye.Sub else Kongye.Danger)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOf(formatTime(r.time), target.takeIf { it.isNotEmpty() }?.let { "在 $it" }, r.domain.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · "),
                fontSize = 12.sp, color = Kongye.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        HLine(Modifier.padding(start = 20.dp), color = Kongye.Divider)
    }
}

// ---------------- 适用的 App ----------------

/** 一个 App：名称 + 包名，右边 × 删除 */
@Composable
internal fun AppChipRow(context: Context, pkg: String, onRemove: () -> Unit) {
    val name = remember(pkg) { appName(context, pkg) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(name, fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name != pkg) Text(pkg, fontSize = 12.sp, color = Kongye.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(KongyeIcons.Close, onRemove, tint = Kongye.Icon2, size = 40.dp, iconSize = 18.dp, description = "移除$name")
        }
        HLine(Modifier.padding(start = 20.dp), color = Kongye.Divider)
    }
}

/** 选一个手机上装的 App（有桌面图标的），可以按名称或包名筛选 */
@Composable
internal fun AppPickerSheet(exclude: Collection<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .filter { !com.kongye.docs.vault.Browsers.isBrowser(context, it.first) }
            .sortedBy { it.second.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { (pkg, label) -> pkg !in exclude && (query.isBlank() || label.contains(query.trim(), true) || pkg.contains(query.trim(), true)) }
    BottomSheet(onDismiss) { dismiss ->
        Text("选择适用的 App", Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 10.dp), fontSize = 17.sp, color = Kongye.Text)
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp)).background(Kongye.Field).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(KongyeIcons.Search, null, Modifier.size(18.dp), tint = Kongye.Icon2)
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Ink), cursorBrush = SolidColor(Kongye.Primary),
                modifier = Modifier.weight(1f).semantics { contentDescription = "筛选 App" },
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("按名称或包名筛选", fontSize = 15.sp, color = Kongye.Sub); inner() } },
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) Text("没有找到", Modifier.fillMaxWidth().padding(24.dp), fontSize = 14.sp, color = Kongye.Sub)
            shown.forEach { (pkg, label) ->
                Column(Modifier.fillMaxWidth().clickable { dismiss { onPick(pkg) } }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(label, fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pkg, fontSize = 12.sp, color = Kongye.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(54.dp).clickable { dismiss {} }.navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Text("取消", fontSize = 16.sp, color = Kongye.Text2)
        }
    }
}
