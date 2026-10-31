package com.kongye.docs.ui.vault

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Settings
import com.kongye.docs.data.Totp
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.data.VaultField
import com.kongye.docs.data.VaultStore
import com.kongye.docs.data.VaultType
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.EmptyState
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.IconBtn
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.PinnedMark
import com.kongye.docs.ui.components.SectionLabel
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.screens.ChoiceSheet
import com.kongye.docs.ui.screens.SettingRow
import com.kongye.docs.ui.screens.SettingsGroup
import com.kongye.docs.ui.screens.SettingsTitle
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.Share
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** 每秒更新一次的当前时间（对齐到整秒），用来刷新验证码和倒计时 */
@Composable
private fun ticker(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    return now
}

/** 验证码分组显示：6 位 123 456，8 位 1234 5678 */
private fun grouped(code: String) = if (code.length >= 6) code.substring(0, code.length / 2) + " " + code.substring(code.length / 2) else code

private fun codeOrNull(entry: VaultEntry, now: Long) = runCatching { Totp.code(entry, now) }.getOrNull()

/** 置顶的在前（后置顶的在上），其余按名称 */
private fun ordered(list: List<VaultEntry>) = list.filter { it.pinned }.sortedByDescending { it.pinnedAt } + list.filterNot { it.pinned }.sortedBy { it.name.lowercase() }

/** 长按 → 复制全部字段：每行“字段名：内容”，含密码和隐藏内容，标记为敏感 */
private fun copyAll(context: Context, e: VaultEntry) {
    val lines = buildList {
        add("名称" to e.name)
        add("账号" to e.account)
        add("密码" to e.password)
        if (e.type == VaultType.OTP) add("密钥" to e.secret)
        e.fields.forEach { add(it.label to it.value) }
    }.filter { it.second.isNotBlank() }
    Share.copySecret(context, e.name, lines.joinToString("\n") { (k, v) -> "$k：$v" })
    Toast.done("已复制全部字段")
}

/** 一条的交互：点一下的字段详情、长按 / ⋮ 的菜单、删除确认；列表和搜索结果共用 */
private class VaultSheets {
    var menuFor by mutableStateOf<VaultEntry?>(null)
    var detailFor by mutableStateOf<String?>(null)
    var deleting by mutableStateOf<VaultEntry?>(null)
}

// ---------------- 密码空间（文件页里和“我的桌面”同级） ----------------

@Composable
fun VaultPage() {
    val entries by VaultStore.entries.collectAsState()
    val problem by VaultStore.problem.collectAsState()
    val settings by Settings.state.collectAsState()
    val hasPassword = settings.backup.vaultPassword.isNotEmpty()
    val sheets = remember { VaultSheets() }
    var settingPassword by remember { mutableStateOf(false) }
    var backupMenu by remember { mutableStateOf(false) }
    var stopBackup by remember { mutableStateOf(false) }
    val pinned = entries.filter { it.pinned }.sortedByDescending { it.pinnedAt }
    val otp = entries.filter { !it.pinned && it.type == VaultType.OTP }.sortedBy { it.name.lowercase() }
    val passwords = entries.filter { !it.pinned && it.type == VaultType.PASSWORD }.sortedBy { it.name.lowercase() }
    val now = if (entries.any { it.type == VaultType.OTP }) ticker() else 0L

    Column(Modifier.fillMaxSize()) {
        // 备份状态：没设备份密码时提醒一下，点一下就能设置
        Row(
            Modifier.fillMaxWidth().background(Kongye.Bg2)
                .clickable { if (hasPassword) backupMenu = true else settingPassword = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (problem.isNotEmpty()) problem
                else if (hasPassword) "已设置备份密码，密码空间加密后随本地 / 云端备份"
                else "未设置备份密码，密码空间不会进入备份",
                Modifier.weight(1f), fontSize = 12.sp, color = if (problem.isNotEmpty()) Kongye.Danger else Kongye.Text3,
            )
            Text(if (hasPassword) "管理" else "设置备份密码", fontSize = 12.sp, color = Kongye.Link)
        }
        if (entries.isEmpty()) {
            EmptyState("密码空间是空的", "点击右下角 + 添加动态密码或密码")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                listOf("置顶" to pinned, "动态密码" to otp, "密码" to passwords).forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        item(key = "h-$title") { SectionLabel(title) }
                        items(list, key = { it.id }) { e -> VaultEntryRow(e, now, sheets) }
                    }
                }
            }
        }
    }

    VaultEntrySheets(sheets)
    if (AppState.vaultAdd) ActionSheet(
        onDismiss = { AppState.vaultAdd = false },
        actions = listOf(
            SheetAction(KongyeIcons.Shield, "添加动态密码") { AppState.push(Screen.VaultEdit(null, VaultType.OTP)) },
            SheetAction(KongyeIcons.Key, "添加密码") { AppState.push(Screen.VaultEdit(null, VaultType.PASSWORD)) },
        ),
    )
    if (settingPassword) VaultPasswordDialog(if (hasPassword) "修改备份密码" else "设置备份密码", onDismiss = { settingPassword = false }) { pw ->
        settingPassword = false
        Settings.updateBackup { it.copy(vaultPassword = pw) }
        Toast.done("备份密码已保存")
    }
    if (backupMenu) ActionSheet(
        onDismiss = { backupMenu = false },
        actions = listOf(
            SheetAction(KongyeIcons.Edit, "修改备份密码") { settingPassword = true },
            SheetAction(KongyeIcons.Close, "不再备份密码空间", danger = true) { stopBackup = true },
        ),
    )
    if (stopBackup) ConfirmDialog(
        "不再备份密码空间", "之后的本地 / 云端备份里不再包含密码空间；已经生成的备份不受影响。",
        "确定", danger = true, onDismiss = { stopBackup = false },
    ) {
        stopBackup = false
        Settings.updateBackup { it.copy(vaultPassword = "") }
        Toast.done("已关闭密码空间备份")
    }
}

/** 列表里的一条：点一下查看全部字段（再点某一项复制），长按或 ⋮ 打开菜单（置顶、编辑、复制全部字段、删除） */
@Composable
private fun VaultEntryRow(e: VaultEntry, now: Long, sheets: VaultSheets) {
    if (e.type == VaultType.OTP) {
        val code = codeOrNull(e, now)
        VaultRow(e, KongyeIcons.Shield, onClick = { sheets.detailFor = e.id }, onLongClick = { sheets.menuFor = e }, onMore = { sheets.menuFor = e }) {
            if (code == null) Text("密钥无效", fontSize = 13.sp, color = Kongye.Danger)
            else {
                val left = Totp.remaining(e.period, now)
                val urgent = left <= 5
                Text(
                    grouped(code), fontSize = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    color = if (urgent) Kongye.Danger else Kongye.Primary,
                    modifier = Modifier.semantics { contentDescription = "验证码 $code" },
                )
                Spacer(Modifier.width(10.dp))
                Countdown(left, e.period, urgent)
            }
        }
    } else {
        var shown by remember(e.id) { mutableStateOf(false) }
        VaultRow(e, KongyeIcons.Key, onClick = { sheets.detailFor = e.id }, onLongClick = { sheets.menuFor = e }, onMore = { sheets.menuFor = e }) {
            Text(
                if (e.password.isEmpty()) "未填写" else if (shown) e.password else "••••••",
                Modifier.widthIn(max = 140.dp), fontSize = 15.sp,
                color = if (e.password.isEmpty()) Kongye.Text4 else Kongye.Ink2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (e.password.isNotEmpty()) {
                IconBtn(
                    if (shown) KongyeIcons.EyeOff else KongyeIcons.Eye, { shown = !shown },
                    tint = Kongye.Icon2, size = 36.dp, iconSize = 20.dp, description = if (shown) "隐藏密码" else "显示密码",
                )
            }
        }
    }
}

/** 一条：左边圆形图标，中间名称和账号，右边验证码 / 密码，最右 ⋮；置顶的整行浅灰底、名称后带图钉（与我的桌面一致） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultRow(
    e: VaultEntry,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(70.dp).background(if (e.pinned) Kongye.Pinned else Kongye.Surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Kongye.Field), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = Kongye.Ink2)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, Modifier.weight(1f, fill = false), fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.pinned) PinnedMark(Modifier.padding(start = 5.dp))
                }
                if (e.account.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(e.account, fontSize = 13.sp, color = Kongye.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
            IconBtn(KongyeIcons.MoreV, onMore, tint = Kongye.Icon2, size = 40.dp, iconSize = 20.dp, description = "更多")
        }
        HLine(Modifier.padding(start = 70.dp), color = Kongye.Divider)
    }
}

/** 验证码剩余时间：一圈细环，最后 5 秒变红 */
@Composable
private fun Countdown(left: Int, period: Int, urgent: Boolean) {
    val track = Kongye.Line2
    val color = if (urgent) Kongye.Danger else Kongye.Primary
    Canvas(Modifier.size(18.dp).semantics { contentDescription = "还剩 $left 秒" }) {
        val w = 2.2.dp.toPx()
        drawCircle(track, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(color, -90f, 360f * left / period, false, style = Stroke(w, cap = StrokeCap.Round),
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
    }
}

/** ⋮ 菜单、字段详情、删除确认。beforeEdit：从搜索里去编辑时先关掉搜索 */
@Composable
private fun VaultEntrySheets(sheets: VaultSheets, beforeEdit: () -> Unit = {}) {
    val context = LocalContext.current
    val entries by VaultStore.entries.collectAsState()
    fun edit(e: VaultEntry) { beforeEdit(); AppState.push(Screen.VaultEdit(e.id, e.type)) }
    // 长按 / ⋮：置顶、编辑、复制全部字段、删除
    sheets.menuFor?.let { e ->
        ActionSheet(
            onDismiss = { sheets.menuFor = null },
            actions = listOf(
                SheetAction(if (e.pinned) KongyeIcons.PinFilled else KongyeIcons.Pin, if (e.pinned) "取消置顶" else "置顶") {
                    runCatching { VaultStore.togglePin(e.id) }
                        .onSuccess { Toast.done(if (e.pinned) "已取消置顶" else "已置顶") }
                        .onFailure { Toast.show(it.message ?: "操作失败") }
                },
                SheetAction(KongyeIcons.Edit, "编辑") { edit(e) },
                SheetAction(KongyeIcons.Copy, "复制全部字段") { copyAll(context, e) },
                SheetAction(KongyeIcons.Trash, "删除", danger = true) { sheets.deleting = e },
            ),
        )
    }
    sheets.detailFor?.let { id ->
        entries.firstOrNull { it.id == id }?.let { e -> VaultDetailSheet(e, onEdit = { edit(e) }) { sheets.detailFor = null } }
    }
    sheets.deleting?.let { e ->
        ConfirmDialog(
            "删除「${e.name}」", if (e.type == VaultType.OTP) "删除后这个账号的动态密码就看不到了，请确认已在网站上关闭或换绑两步验证。" else "删除后无法恢复。",
            "删除", danger = true, onDismiss = { sheets.deleting = null },
        ) {
            sheets.deleting = null
            runCatching { VaultStore.delete(e.id) }.onSuccess { Toast.done("删除成功") }.onFailure { Toast.show(it.message ?: "删除失败") }
        }
    }
}

/** 详情里的一行：label 字段名，value 复制的内容；masked 先显示圆点；sensitive 复制时标记为敏感 */
private class DetailLine(val label: String, val value: String, val masked: Boolean, val sensitive: Boolean, val display: String = value)

/** 长按一条：列出全部字段，点哪一项就复制哪一项 */
@Composable
private fun VaultDetailSheet(e: VaultEntry, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = if (e.type == VaultType.OTP) ticker() else 0L
    val revealed = remember(e.id) { mutableStateMapOf<String, Boolean>() }
    val lines = buildList {
        if (e.type == VaultType.OTP) codeOrNull(e, now)?.let { add(DetailLine("验证码", it, masked = false, sensitive = true, display = grouped(it))) }
        // 与编辑页同样的顺序：账号、密码、（动态密码的）密钥，然后是自定义字段
        add(DetailLine("账号", e.account, masked = false, sensitive = false))
        add(DetailLine("密码", e.password, masked = true, sensitive = true))
        if (e.type == VaultType.OTP) add(DetailLine("密钥", e.secret, masked = true, sensitive = true))
        e.fields.forEach { add(DetailLine(it.label, it.value, masked = it.secret, sensitive = it.secret)) }
    }.filter { it.value.isNotBlank() }
    BottomSheet(onDismiss) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Kongye.Field), contentAlignment = Alignment.Center) {
                Icon(if (e.type == VaultType.OTP) KongyeIcons.Shield else KongyeIcons.Key, null, Modifier.size(22.dp), tint = Kongye.Ink2)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (e.type == VaultType.OTP) "动态密码 · 点一项即可复制" else "密码 · 点一项即可复制", fontSize = 12.sp, color = Kongye.Text3)
            }
            Text(
                "编辑", Modifier.clip(RoundedCornerShape(6.dp)).clickable { dismiss(onEdit) }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 15.sp, color = Kongye.Link,
            )
        }
        HLine(color = Kongye.Divider)
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (lines.isEmpty()) Text("还没有填写内容", Modifier.fillMaxWidth().padding(24.dp), fontSize = 14.sp, color = Kongye.Text3, textAlign = TextAlign.Center)
            lines.forEach { line ->
                val hidden = line.masked && revealed[line.label] != true
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (line.sensitive) Share.copySecret(context, line.label, line.value) else Share.copy(context, line.label, line.value)
                        Toast.done("已复制${line.label}")
                    }.padding(start = 20.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(line.label, fontSize = 13.sp, color = Kongye.Text3)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (hidden) "••••••••" else line.display, fontSize = 16.sp,
                            color = if (line.label == "验证码") Kongye.Primary else Kongye.Ink,
                            fontFamily = if (line.masked && !hidden) FontFamily.Monospace else null, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (line.masked) IconBtn(
                        if (hidden) KongyeIcons.Eye else KongyeIcons.EyeOff, { revealed[line.label] = hidden },
                        tint = Kongye.Icon2, size = 40.dp, iconSize = 20.dp, description = if (hidden) "显示${line.label}" else "隐藏${line.label}",
                    )
                }
                HLine(Modifier.padding(start = 20.dp), color = Kongye.Divider)
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Kongye.Bg2))
        Box(Modifier.fillMaxWidth().height(54.dp).clickable { dismiss {} }.navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Text("关闭", fontSize = 16.sp, color = Kongye.Text2)
        }
    }
}

// ---------------- 搜索 ----------------

/** 密码空间的搜索：和我的桌面一样从顶部滑下；搜名称、账号、字段名和不隐藏的字段内容 */
@Composable
fun VaultSearchOverlay() {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf(false) }
    val entries by VaultStore.entries.collectAsState()
    val results = remember(query, entries) { if (query.isBlank()) emptyList() else ordered(entries.filter { it.matches(query) }) }
    val sheets = remember { VaultSheets() }
    val now = if (results.any { it.type == VaultType.OTP }) ticker() else 0L

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    fun close() {
        if (closing) return
        closing = true
        dismissKeyboard()
        scope.launch {
            progress.animateTo(0f, tween(220, easing = FastOutLinearInEasing))
            AppState.vaultSearchOpen = false
        }
    }
    LaunchedEffect(Unit) {
        launch { progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
        delay(60)
        runCatching { focus.requestFocus() }
    }
    BackHandler(enabled = !closing) { close() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(Kongye.Scrim)
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = -size.height * (1f - progress.value) }
                    .background(Kongye.Surface).statusBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Kongye.Field)
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(KongyeIcons.Search, null, Modifier.size(18.dp), tint = Kongye.Icon2)
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Ink),
                            cursorBrush = SolidColor(Kongye.Bookmark),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text("搜索名称、账号、网址、备注", fontSize = 15.sp, color = Kongye.Sub)
                                    inner()
                                }
                            },
                        )
                        if (query.isNotEmpty()) IconBtn(KongyeIcons.Close, { query = "" }, size = 28.dp, iconSize = 15.dp, tint = Kongye.Icon2, description = "清空")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "取消", Modifier.clip(RoundedCornerShape(8.dp)).clickable { close() }.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 16.sp, color = Kongye.Ink2,
                    )
                }
                HLine(color = Kongye.Divider)
            }
            if (query.isNotBlank()) {
                Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = progress.value }.background(Kongye.Surface).imePadding()) {
                    if (results.isEmpty()) {
                        Text(
                            "没有找到相关条目", Modifier.fillMaxWidth().padding(top = 72.dp),
                            fontSize = 14.sp, color = Kongye.Sub, textAlign = TextAlign.Center,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "搜索结果" }, contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(results, key = { it.id }) { e -> VaultEntryRow(e, now, sheets) }
                        }
                    }
                }
            }
        }
    }
    VaultEntrySheets(sheets, beforeEdit = { dismissKeyboard(); AppState.vaultSearchOpen = false })
}

// ---------------- 添加 / 编辑 ----------------

private const val SYMBOLS = "!@#$%^&*-_"

/** 随机密码：16 位，大小写字母、数字、符号各至少一个 */
private fun randomPassword(): String {
    val rnd = SecureRandom()
    val groups = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", SYMBOLS)
    val all = groups.joinToString("")
    val chars = (groups.map { it[rnd.nextInt(it.length)] } + List(12) { all[rnd.nextInt(all.length)] }).toMutableList()
    for (i in chars.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = chars[i]; chars[i] = chars[j]; chars[j] = t }
    return chars.joinToString("")
}

@Composable
fun VaultEditScreen(id: String?, type: VaultType) {
    val context = LocalContext.current
    val original = remember(id) { id?.let { VaultStore.get(it) }?.normalized() }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var account by remember { mutableStateOf(original?.account.orEmpty()) }
    var secret by remember { mutableStateOf(original?.secret.orEmpty()) }
    var digits by remember { mutableIntStateOf(original?.digits ?: 6) }
    var period by remember { mutableIntStateOf(original?.period ?: 30) }
    var algorithm by remember { mutableStateOf(original?.algorithm ?: "SHA1") }
    var password by remember { mutableStateOf(original?.password.orEmpty()) }
    val otp = type == VaultType.OTP
    // 自定义字段：排在 名称、账号、密码（、密钥）之后，可以改名、调整位置、隐藏、增加和删除
    val fields = remember { mutableStateListOf<VaultField>().apply { addAll(original?.fields.orEmpty()) } }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    var showPassword by remember { mutableStateOf(original == null) }
    var choice by remember { mutableStateOf<String?>(null) }
    var addingField by remember { mutableStateOf(false) }
    var fieldMenu by remember { mutableStateOf<String?>(null) }
    var editingField by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun draft() = (original ?: VaultEntry(type = type, name = "")).copy(
        name = name.trim(), account = account.trim(),
        secret = if (otp) Totp.normalize(secret) else "", digits = digits, period = period, algorithm = algorithm,
        password = password, url = "", note = "",
        fields = fields.map { it.copy(label = it.label.trim()) },
    )
    val changed = original?.let { draft() != it }
        ?: (name.isNotBlank() || account.isNotBlank() || secret.isNotBlank() || password.isNotEmpty() || fields.any { it.value.isNotBlank() })
    fun leave() { if (changed) confirmLeave = true else AppState.pop() }
    BackHandler { leave() }

    /** 粘贴进来的是 otpauth:// 链接：一次填好名称、账号、密钥和高级设置 */
    fun fillFrom(link: String): Boolean {
        val parsed = runCatching { Totp.parse(link) }.getOrElse { Toast.show(it.message ?: "无法识别这个链接"); return false }
        name = parsed.name; account = parsed.account; secret = parsed.secret
        digits = parsed.digits; period = parsed.period; algorithm = parsed.algorithm
        Toast.done("已从链接填好")
        return true
    }
    fun save() {
        val entry = draft()
        val error = when {
            entry.name.isEmpty() -> "请填写名称"
            otp -> runCatching { Totp.decodeBase32(entry.secret) }.exceptionOrNull()?.message
            else -> null
        }
        if (error != null) return Toast.show(error)
        runCatching { VaultStore.save(entry) }
            .onSuccess { Toast.done("已保存"); AppState.pop() }
            .onFailure { Toast.show(it.message ?: "保存失败") }
    }

    Column(Modifier.fillMaxSize().background(Kongye.Band)) {
        TopBar(
            title = (if (original == null) "添加" else "编辑") + (if (otp) "动态密码" else "密码"),
            onBack = ::leave, divider = true,
        ) {
            Text(
                "保存", Modifier.clip(RoundedCornerShape(6.dp)).clickable { save() }.padding(horizontal = 14.dp, vertical = 8.dp),
                fontSize = 15.sp, color = Kongye.Primary, fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Spacer(Modifier.height(12.dp))
            // 顺序：名称、账号、密码、（动态密码的）密钥，然后是自定义字段，最后“添加字段”
            Column(Modifier.fillMaxWidth().background(Kongye.Surface)) {
                FormField("名称", name, { name = it }, if (otp) "如 GitHub、微软账户" else "如 学校邮箱、家里 Wi-Fi")
                FormField("账号", account, { account = it }, "用户名、邮箱或手机号（可不填）", keyboard = KeyboardType.Email)
                FormField(
                    "密码", password, { password = it }, if (otp) "这个账号的登录密码（可不填）" else "要保存的密码",
                    secret = !showPassword, mono = showPassword,
                ) {
                    IconBtn(if (showPassword) KongyeIcons.EyeOff else KongyeIcons.Eye, { showPassword = !showPassword },
                        tint = Kongye.Icon2, size = 36.dp, iconSize = 20.dp, description = if (showPassword) "隐藏密码" else "显示密码")
                    IconBtn(KongyeIcons.Refresh, { password = randomPassword(); showPassword = true },
                        tint = Kongye.Icon2, size = 36.dp, iconSize = 20.dp, description = "随机生成密码")
                }
                if (otp) FormField(
                    "密钥", secret,
                    { text -> if (text.trim().startsWith("otpauth://", ignoreCase = true)) { fillFrom(text) } else { secret = text } },
                    "网站提供的密钥，或粘贴 otpauth:// 链接", mono = true,
                )
                fields.forEach { f ->
                    val hidden = f.secret && revealed[f.id] != true
                    FormField(
                        f.label, f.value,
                        { v -> fields.indexOfFirst { it.id == f.id }.takeIf { it >= 0 }?.let { i -> fields[i] = fields[i].copy(value = v) } },
                        if (f.secret) "隐藏内容" else "可不填",
                        secret = hidden, mono = f.secret && !hidden, singleLine = f.secret,
                        onLabelClick = { fieldMenu = f.id },
                    ) {
                        if (f.secret) IconBtn(if (hidden) KongyeIcons.Eye else KongyeIcons.EyeOff, { revealed[f.id] = hidden },
                            tint = Kongye.Icon2, size = 36.dp, iconSize = 20.dp, description = if (hidden) "显示${f.label}" else "隐藏${f.label}")
                        IconBtn(KongyeIcons.More, { fieldMenu = f.id },
                            tint = Kongye.Icon2, size = 36.dp, iconSize = 20.dp, description = "字段操作${f.label}")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { addingField = true }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(KongyeIcons.Plus, null, Modifier.size(18.dp), tint = Kongye.Link)
                    Spacer(Modifier.width(8.dp))
                    Text("添加字段", fontSize = 15.sp, color = Kongye.Link)
                }
            }
            if (otp) {
                // 预览当前验证码：和网站上要求输入的对得上，说明密钥填对了
                val now = ticker()
                val preview = runCatching { Totp.code(draft(), now) }.getOrNull()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (secret.isBlank()) "填好密钥后，这里会显示当前验证码" else if (preview == null) "密钥格式不对：只能是字母 A–Z 和数字 2–7" else "当前验证码",
                        Modifier.weight(1f), fontSize = 13.sp, color = if (secret.isNotBlank() && preview == null) Kongye.Danger else Kongye.Text3,
                    )
                    if (preview != null) Text(grouped(preview), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Kongye.Primary, letterSpacing = 1.sp)
                }
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Surface)
                        .clickable {
                            val text = context.getSystemService(ClipboardManager::class.java).primaryClip
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.trim().startsWith("otpauth://", ignoreCase = true)) fillFrom(text)
                            else Toast.show("剪贴板里没有 otpauth:// 链接；可用相机扫网站上的二维码后复制链接")
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("粘贴 otpauth:// 链接", fontSize = 15.sp, color = Kongye.Link) }
            }
            if (otp) {
                SettingsTitle("高级设置（一般不用改）")
                SettingsGroup {
                    SettingRow("位数", "$digits 位") { choice = "digits" }
                    SettingRow("刷新周期", "$period 秒") { choice = "period" }
                    SettingRow("算法", algorithm, last = true) { choice = "algorithm" }
                }
            }
            if (original != null) {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().height(52.dp).background(Kongye.Surface).clickable { confirmDelete = true },
                    contentAlignment = Alignment.Center,
                ) { Text("删除", fontSize = 16.sp, color = Kongye.Danger) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    when (choice) {
        "digits" -> ChoiceSheet("位数", listOf(6 to "6 位", 7 to "7 位", 8 to "8 位"), digits, { choice = null }) { digits = it }
        "period" -> ChoiceSheet("刷新周期", listOf(30 to "30 秒", 60 to "60 秒"), period, { choice = null }) { period = it }
        "algorithm" -> ChoiceSheet("算法", Totp.algorithms.map { it to it }, algorithm, { choice = null }) { algorithm = it }
    }
    if (addingField) AddFieldDialog(onDismiss = { addingField = false }) { label, hidden ->
        addingField = false
        fields.add(VaultField(label = label, secret = hidden))
    }
    // 某个自定义字段的操作：修改（名字、是否隐藏）、上移、下移、删除
    fieldMenu?.let { fid ->
        val index = fields.indexOfFirst { it.id == fid }
        if (index >= 0) {
            val f = fields[index]
            ActionSheet(
                onDismiss = { fieldMenu = null },
                header = { Text("字段「${f.label}」", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 12.dp), fontSize = 15.sp, color = Kongye.Text3) },
                actions = buildList {
                    add(SheetAction(KongyeIcons.Edit, "修改字段") { editingField = fid })
                    if (index > 0) add(SheetAction(KongyeIcons.ArrowUp, "上移") { fields.add(index - 1, fields.removeAt(index)) })
                    if (index < fields.lastIndex) add(SheetAction(KongyeIcons.ArrowDown, "下移") { fields.add(index + 1, fields.removeAt(index)) })
                    add(SheetAction(KongyeIcons.Trash, "删除字段", danger = true) { fields.removeAt(index) })
                },
            )
        }
    }
    editingField?.let { fid ->
        fields.firstOrNull { it.id == fid }?.let { f ->
            AddFieldDialog(
                onDismiss = { editingField = null }, title = "修改字段", initialLabel = f.label, initialSecret = f.secret, confirmText = "确定",
            ) { label, hidden ->
                editingField = null
                fields.indexOfFirst { it.id == fid }.takeIf { it >= 0 }?.let { i -> fields[i] = fields[i].copy(label = label, secret = hidden) }
            }
        }
    }
    if (confirmLeave) ConfirmDialog("放弃这次修改？", "还没保存的内容会丢失。", "放弃", danger = true, onDismiss = { confirmLeave = false }) {
        confirmLeave = false
        AppState.pop()
    }
    if (confirmDelete && original != null) ConfirmDialog(
        "删除「${original.name}」", if (otp) "删除后这个账号的动态密码就看不到了，请确认已在网站上关闭或换绑两步验证。" else "删除后无法恢复。",
        "删除", danger = true, onDismiss = { confirmDelete = false },
    ) {
        confirmDelete = false
        runCatching { VaultStore.delete(original.id) }.onSuccess { Toast.done("删除成功"); AppState.pop() }.onFailure { Toast.show(it.message ?: "删除失败") }
    }
}

/** 表单一行：左边字段名，右边输入框；last 为最后一行时不画分隔线 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    last: Boolean = false,
    /** 自定义字段：点字段名打开字段操作（修改、上移、下移、删除） */
    onLabelClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.width(72.dp).then(if (onLabelClick != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLabelClick) else Modifier).padding(end = 8.dp),
                fontSize = 15.sp, color = if (onLabelClick != null) Kongye.Ink2 else Kongye.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
            )
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 6,
                textStyle = TextStyle(fontSize = 16.sp, color = Kongye.Ink, fontFamily = if (mono) FontFamily.Monospace else null, lineHeight = 22.sp),
                cursorBrush = SolidColor(Kongye.Primary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).padding(vertical = 15.dp).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = Kongye.Text4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
        }
        if (!last) HLine(Modifier.padding(start = 20.dp), color = Kongye.Divider)
    }
}
