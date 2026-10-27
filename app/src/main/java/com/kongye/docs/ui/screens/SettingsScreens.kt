package com.kongye.docs.ui.screens

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.AutoBackup
import com.kongye.docs.data.Settings
import com.kongye.docs.data.ThemeMode
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.InputDialog
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.KongyeSwitch
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.formatTime
import kotlinx.coroutines.launch

// ---------------- 通用的设置行 ----------------

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface), content = content)
}

@Composable
internal fun SettingsTitle(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp), fontSize = 13.sp, color = Kongye.Text3)
}

@Composable
internal fun SettingRow(label: String, value: String? = null, last: Boolean = false, valueColor: androidx.compose.ui.graphics.Color = Kongye.Sub, showChevron: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, color = Kongye.Ink)
            Spacer(Modifier.width(16.dp))
            Text(
                value.orEmpty(), Modifier.weight(1f), fontSize = 15.sp, color = valueColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Spacer(Modifier.width(6.dp))
            if (showChevron) Icon(KongyeIcons.ChevronRight, null, Modifier.size(18.dp), tint = Kongye.Text4)
        }
        if (!last) HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
            KongyeSwitch(checked, onChange)
        }
        HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}

/** 底部弹出的单选列表，当前项打勾 */
@Composable
internal fun <T> ChoiceSheet(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    BottomSheet(onDismiss) { dismiss ->
        Text(title, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        options.forEach { (v, label) ->
            Row(
                Modifier.fillMaxWidth().height(54.dp).clickable { dismiss { onPick(v) } }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), fontSize = 16.sp, color = if (v == selected) Kongye.Primary else Kongye.Text)
                if (v == selected) Icon(KongyeIcons.Check, null, Modifier.size(20.dp), tint = Kongye.Primary)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

// ---------------- 深色模式 ----------------

private fun hm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

fun themeLabel(mode: ThemeMode, from: Int, to: Int) =
    if (mode == ThemeMode.SCHEDULE) "${hm(from)} – ${hm(to)}" else mode.label

/** 深色模式：浅色 / 深色 / 跟随系统 / 定时切换（可设开始、结束时间） */
@Composable
fun ThemeSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val st by Settings.state.collectAsState()
    fun pickTime(start: Boolean) {
        val cur = if (start) st.darkFrom else st.darkTo
        TimePickerDialog(context, { _, h, m ->
            Settings.update { if (start) it.copy(darkFrom = h * 60 + m) else it.copy(darkTo = h * 60 + m) }
        }, cur / 60, cur % 60, true).show()
    }
    BottomSheet(onDismiss) {
        Text(
            "深色模式", Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        HLine(color = Kongye.Divider)
        listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SCHEDULE).forEachIndexed { i, m ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { Settings.update { it.copy(theme = m) } }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.label, Modifier.weight(1f), fontSize = 16.sp, color = if (st.theme == m) Kongye.Primary else Kongye.Text)
                if (st.theme == m) Icon(KongyeIcons.Check, null, Modifier.size(20.dp), tint = Kongye.Primary)
            }
            if (i < 3) HLine(Modifier.padding(horizontal = 24.dp), color = Kongye.Divider)
        }
        // 定时切换的二级设置：紧贴在它下面，浅灰底、缩进、字小一号，一眼看出是它的子选项
        if (st.theme == ThemeMode.SCHEDULE) {
            Column(Modifier.fillMaxWidth().background(Kongye.Card)) {
                SubSettingRow("深色开始", hm(st.darkFrom)) { pickTime(true) }
                HLine(Modifier.padding(start = 48.dp, end = 24.dp), color = Kongye.Divider)
                SubSettingRow("深色结束", hm(st.darkTo)) { pickTime(false) }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(36.dp))
    }
}

@Composable
private fun SubSettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(start = 48.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = Kongye.Text2)
        Text(value, fontSize = 15.sp, color = Kongye.Sub)
        Spacer(Modifier.width(6.dp))
        Icon(KongyeIcons.ChevronRight, null, Modifier.size(16.dp), tint = Kongye.Text4)
    }
}
