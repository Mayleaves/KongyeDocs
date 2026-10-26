package com.moji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.ui.components.IconBtn
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.Toast
import com.moji.notes.ui.theme.Moji
import kotlinx.coroutines.delay

private val barBg get() = Moji.Card

@Composable
private fun PillField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
) {
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(Moji.Surface).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading) {
            Icon(MojiIcons.Search, null, Modifier.size(18.dp), tint = Moji.Sub)
            Spacer(Modifier.width(6.dp))
        }
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = Moji.Ink),
            cursorBrush = SolidColor(Moji.Primary),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onAction() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = Moji.Text4)
                    inner()
                }
            },
        )
    }
}

@Composable
fun FindTopBar(editor: EditorState, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().background(barBg).statusBarsPadding().height(56.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillField(
            editor.findQuery, editor::setQuery, "查找", Modifier.weight(1f).focusRequester(focus),
            leading = true, imeAction = ImeAction.Search, onAction = { editor.moveMatch(1) },
        )
        Text(
            "取消", Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 16.sp, color = Moji.Ink2,
        )
    }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
}

/** 浮在正文底部的 “‹ 2/43 ›” */
@Composable
fun FindNavigator(editor: EditorState, modifier: Modifier = Modifier) {
    if (editor.findQuery.isEmpty()) return
    val total = editor.matches.size
    Row(
        modifier.shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Moji.Surface)
            .border(0.6.dp, Moji.Divider, RoundedCornerShape(22.dp)).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(MojiIcons.Back, { editor.moveMatch(-1) }, tint = Moji.Ink2, size = 52.dp, iconSize = 20.dp, enabled = total > 0, description = "上一个")
        Box(Modifier.width(0.8.dp).height(20.dp).background(Moji.Line2))
        Text(
            if (total == 0) "0/0" else "${editor.matchIndex + 1}/$total",
            Modifier.padding(horizontal = 20.dp), fontSize = 16.sp, color = Moji.Ink2,
        )
        Box(Modifier.width(0.8.dp).height(20.dp).background(Moji.Line2))
        IconBtn(MojiIcons.ChevronRight, { editor.moveMatch(1) }, tint = Moji.Ink2, size = 52.dp, iconSize = 20.dp, enabled = total > 0, description = "下一个")
    }
}

@Composable
fun FindBottomBar(editor: EditorState) {
    var replacement by remember { mutableStateOf("") }
    val hasMatch = editor.matches.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().background(barBg).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillField(replacement, { replacement = it }, "替换为…", Modifier.weight(1f))
        Action("替换", hasMatch) { editor.replaceCurrent(replacement) }
        Action("全部替换", hasMatch) { Toast.show("已替换 ${editor.replaceAll(replacement)} 处") }
        Action("撤销", editor.replacedInFind && editor.canUndo) { editor.undoReplace() }
    }
}

@Composable
private fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (enabled) Moji.Ink else Moji.Text4)
    }
}
