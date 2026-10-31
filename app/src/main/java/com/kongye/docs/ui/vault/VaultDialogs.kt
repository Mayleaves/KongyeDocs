package com.kongye.docs.ui.vault

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VaultPasswordException
import com.kongye.docs.data.VaultStore
import com.kongye.docs.ui.components.DialogButtons
import com.kongye.docs.ui.components.DialogFrame
import com.kongye.docs.ui.components.DialogTitle
import com.kongye.docs.ui.components.KongyeSwitch
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- 备份密码 ----------------

@Composable
internal fun DialogPasswordField(value: String, onChange: (String) -> Unit, placeholder: String, description: String) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = Kongye.Text),
        cursorBrush = SolidColor(Kongye.Primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp)
            .border(1.dp, Kongye.Line2, RoundedCornerShape(8.dp)).semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = Kongye.Text4)
                inner()
            }
        },
    )
}

/** 设置 / 修改备份密码：输两次，至少 6 位 */
@Composable
internal fun VaultPasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ok = first.length >= 6 && first == second
    DialogFrame(onDismiss) {
        DialogTitle(title)
        Text(
            "用于加密备份里的密码空间。换手机恢复时需要输入它，忘记后备份里的密码空间无法恢复。",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp), fontSize = 13.sp, color = Kongye.Text2, textAlign = TextAlign.Center, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(14.dp))
        DialogPasswordField(first, { first = it }, "至少 6 位", "备份密码")
        Spacer(Modifier.height(10.dp))
        DialogPasswordField(second, { second = it }, "再输入一次", "再次输入备份密码")
        if (second.isNotEmpty() && first != second) {
            Text("两次输入不一致", Modifier.padding(start = 24.dp, top = 8.dp), fontSize = 12.sp, color = Kongye.Danger)
        } else if (first.isNotEmpty() && first.length < 6) {
            Text("至少 6 位", Modifier.padding(start = 24.dp, top = 8.dp), fontSize = 12.sp, color = Kongye.Danger)
        }
        DialogButtons("确定", Kongye.Primary, ok, onDismiss) { onConfirm(first) }
    }
}

/**
 * 恢复的备份里有密码空间、而本机没有能解开它的备份密码时弹出（挂在全局浮层上，任何恢复入口都会触发）。
 * 文档已经恢复好；这里输入备份密码恢复密码空间，或取消跳过。
 */
@Composable
fun VaultRestoreDialog() {
    val pending by VaultStore.pendingRestore.collectAsState()
    val backup = pending ?: return
    var password by remember(backup) { mutableStateOf("") }
    var error by remember(backup) { mutableStateOf("") }
    var busy by remember(backup) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DialogFrame(onDismiss = {}) {
        DialogTitle("恢复密码空间")
        Text(
            "文档已经恢复。这份备份里还有 ${backup.count} 条加密的密码空间内容，输入当初设置的备份密码即可一起恢复。",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp), fontSize = 13.sp, color = Kongye.Text2, textAlign = TextAlign.Center, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(14.dp))
        DialogPasswordField(password, { password = it; error = "" }, "备份密码", "备份密码")
        if (error.isNotEmpty()) Text(error, Modifier.padding(start = 24.dp, top = 8.dp), fontSize = 12.sp, color = Kongye.Danger)
        DialogButtons(if (busy) "正在恢复…" else "恢复", Kongye.Primary, password.isNotEmpty() && !busy, onCancel = {
            VaultStore.dismissRestore()
            Toast.show("已跳过密码空间")
        }) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { VaultStore.restore(backup, password) } }
                busy = false
                result.onSuccess { n ->
                    // 新手机上还没设备份密码：沿用这个密码，之后的备份继续包含密码空间
                    if (Settings.value.backup.vaultPassword.isEmpty()) Settings.updateBackup { it.copy(vaultPassword = password) }
                    Toast.done("已恢复密码空间 $n 项")
                }.onFailure { error = if (it is VaultPasswordException) "备份密码不正确，请重试" else it.message ?: "恢复失败" }
            }
        }
    }
}

/** 添加 / 修改字段：字段名称，可选“隐藏内容”（像密码一样显示为圆点、不参与搜索） */
@androidx.compose.runtime.Composable
internal fun AddFieldDialog(
    onDismiss: () -> Unit,
    title: String = "添加字段",
    initialLabel: String = "",
    initialSecret: Boolean = false,
    confirmText: String = "添加",
    onConfirm: (label: String, secret: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var secret by remember { mutableStateOf(initialSecret) }
    DialogFrame(onDismiss) {
        DialogTitle(title)
        BasicTextField(
            value = label, onValueChange = { label = it.take(20) }, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = Kongye.Text),
            cursorBrush = SolidColor(Kongye.Primary),
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp)
                .border(1.dp, Kongye.Line2, RoundedCornerShape(8.dp)).semantics { contentDescription = "字段名称" },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (label.isEmpty()) Text("字段名称，如 安全问题、PIN 码", fontSize = 16.sp, color = Kongye.Text4)
                    inner()
                }
            },
        )
        // 整行都可以点：点文字或开关都能切换
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clickable { secret = !secret }.padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("隐藏内容", Modifier.weight(1f), fontSize = 15.sp, color = Kongye.Text)
            KongyeSwitch(secret, { secret = it })
        }
        Text(
            "开启后像密码一样显示为圆点，也不参与搜索",
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp), fontSize = 12.sp, color = Kongye.Text3,
        )
        DialogButtons(confirmText, Kongye.Primary, label.isNotBlank(), onDismiss) { onConfirm(label.trim(), secret) }
    }
}
