package com.moji.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.moji.notes.ui.theme.Moji
import kotlinx.coroutines.delay

@Composable
internal fun DialogFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(Moji.Surface), content = content)
    }
}

@Composable
internal fun DialogTitle(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = Moji.Text,
    )
}

@Composable
internal fun DialogButtons(
    confirmText: String,
    confirmColor: Color,
    confirmEnabled: Boolean = true,
    onCancel: (() -> Unit)?,
    onConfirm: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))
    HLine()
    Row(Modifier.fillMaxWidth().height(50.dp)) {
        if (onCancel != null) {
            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Text("取消", fontSize = 16.sp, color = Moji.Text2)
            }
            Box(Modifier.width(0.6.dp).fillMaxHeight().background(Moji.Line))
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().clickable(enabled = confirmEnabled, onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            Text(confirmText, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (confirmEnabled) confirmColor else Moji.Text4)
        }
    }
}

@Composable
fun InputDialog(
    title: String,
    initial: String = "",
    placeholder: String = "",
    confirmText: String = "确定",
    allowEmpty: Boolean = false,
    /** 密码：输入内容显示为圆点 */
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ok = allowEmpty || value.text.isNotBlank()
    DialogFrame(onDismiss) {
        DialogTitle(title)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = Moji.Text),
            cursorBrush = SolidColor(Moji.Primary),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done, autoCorrectEnabled = !password && keyboardType == KeyboardType.Text,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
            ),
            keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(value.text) }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp)
                .border(1.dp, if (focused) Moji.Primary else Moji.Line2, RoundedCornerShape(8.dp))
                .focusRequester(focus).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = Moji.Text4)
                    inner()
                }
            },
        )
        DialogButtons(confirmText, Moji.Primary, ok, onDismiss) { onConfirm(value.text) }
    }
    LaunchedEffect(Unit) {
        delay(120)
        focus.requestFocus()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String? = null,
    confirmText: String = "确定",
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogFrame(onDismiss) {
        DialogTitle(title)
        if (message != null) {
            Text(
                message, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                fontSize = 14.sp, color = Moji.Text2, textAlign = TextAlign.Center, lineHeight = 21.sp,
            )
        }
        DialogButtons(confirmText, if (danger) Moji.Danger else Moji.Primary, true, onDismiss, onConfirm)
    }
}

/** 说明弹窗：一段左对齐的说明文字，只有“知道了” */
@Composable
fun NoticeDialog(title: String, message: String, onDismiss: () -> Unit) {
    DialogFrame(onDismiss) {
        DialogTitle(title)
        Text(message, Modifier.fillMaxWidth().padding(horizontal = 24.dp), fontSize = 14.sp, color = Moji.Text2, lineHeight = 22.sp)
        DialogButtons("知道了", Moji.Primary, true, null, onDismiss)
    }
}

/** 只读信息弹窗：若干 “名称 — 值” 行 */
@Composable
fun InfoDialog(title: String, rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    DialogFrame(onDismiss) {
        DialogTitle(title)
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                Text(k, Modifier.width(84.dp), fontSize = 14.sp, color = Moji.Text3)
                Text(v, Modifier.weight(1f), fontSize = 14.sp, color = Moji.Text, textAlign = TextAlign.End)
            }
        }
        DialogButtons("知道了", Moji.Primary, true, null, onDismiss)
    }
}
