package com.kongye.docs.ui.editor

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.ui.components.DialogButtons
import com.kongye.docs.ui.components.DialogFrame
import com.kongye.docs.ui.components.DialogTitle
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.delay

@Composable
fun LinkDialog(
    initialText: String,
    initialUrl: String,
    editingExisting: Boolean,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: () -> Unit,
    onConfirm: (text: String, url: String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var url by remember { mutableStateOf(initialUrl) }
    val focus = remember { FocusRequester() }
    val askText = !hasSelection && !editingExisting
    DialogFrame(onDismiss) {
        DialogTitle(if (editingExisting) "编辑链接" else "插入链接")
        if (askText) {
            LinkField(text, { text = it }, "显示文字（可选）")
            Spacer(Modifier.height(10.dp))
        }
        LinkField(url, { url = it }, "链接地址，如 www.example.com", Modifier.focusRequester(focus), KeyboardType.Uri)
        if (editingExisting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("打开链接", Modifier.clickable { onOpen(initialUrl) }.padding(vertical = 8.dp), fontSize = 14.sp, color = Kongye.Primary)
                Spacer(Modifier.weight(1f))
                Text("移除链接", Modifier.clickable(onClick = onRemove).padding(vertical = 8.dp), fontSize = 14.sp, color = Kongye.Danger)
            }
        }
        DialogButtons("确定", Kongye.Primary, url.isNotBlank(), onDismiss) { onConfirm(text, url) }
    }
    LaunchedEffect(Unit) {
        delay(120)
        focus.requestFocus()
    }
}

@Composable
private fun LinkField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Text),
        cursorBrush = SolidColor(Kongye.Primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp)
            .border(1.dp, Kongye.Line2, RoundedCornerShape(8.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Kongye.Text4)
                inner()
            }
        },
    )
}
