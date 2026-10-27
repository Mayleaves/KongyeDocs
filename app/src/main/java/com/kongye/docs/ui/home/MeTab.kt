package com.kongye.docs.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Item
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.editor.ToolbarOrderSheet
import com.kongye.docs.ui.screens.FooterDialog
import com.kongye.docs.ui.screens.ThemeSheet
import com.kongye.docs.ui.screens.themeLabel
import com.kongye.docs.data.Settings
import androidx.compose.runtime.collectAsState
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.screens.intervalLabel
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.dayNumber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun MeTab(items: List<Item>) {
    val alive = remember(items) { items.filter { Repository.isAlive(it, items) } }
    val docCount = alive.count { it.isDoc }
    val folderCount = alive.count { it.isFolder }
    val starredCount = alive.count { it.starred }
    val words = alive.filter { it.isDoc }.sumOf { it.words }
    val trashCount = items.count { it.deletedAt != null }
    val exports by Repository.exports.collectAsState()
    val footer by Repository.footer.collectAsState()
    var editFooter by remember { mutableStateOf(false) }
    var editToolbar by remember { mutableStateOf(false) }
    var editTheme by remember { mutableStateOf(false) }
    val settings by Settings.state.collectAsState()

    Column(Modifier.fillMaxSize().background(Kongye.Band).verticalScroll(rememberScrollState())) {
        // 顶部：问候语和日期，下面三个统计块（文件 · 文件夹 · 收藏），一只线条小猫坐在最右边的块上
        Column(
            Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 24.dp),
        ) {
            val now = remember { Calendar.getInstance() }
            val part = remember { DayPart.of(now.get(Calendar.HOUR_OF_DAY)) }
            val days = dayNumber(settings.firstUse)
            Text(part.greeting, Modifier.padding(start = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now.time), Modifier.padding(start = 4.dp),
                fontSize = 13.sp, color = Kongye.Sub,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBlock(docCount, "文件")
                    StatBlock(folderCount, "文件夹")
                    StatBlock(starredCount, "收藏")
                }
                // 小猫的“地面”就是统计块的上沿，尾巴垂在块的前面
                LineCat(
                    part, { catMessages(part, words, days) },
                    Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = (-CAT_GROUND).dp).size(CAT_W.dp, CAT_H.dp),
                )
            }
        }
        // 按功能分块（不加标题，石墨样式）：写作 · 导出 · 数据安全（备份和找回） · 通用
        Band()
        Group {
            MenuRow("模板库") { AppState.push(Screen.Templates(null)) }
            MenuRow("编辑工具栏", last = true) { editToolbar = true }
        }
        Band()
        Group {
            MenuRow("下载记录", if (exports.isNotEmpty()) "${exports.size} 条" else null) { AppState.push(Screen.Downloads) }
            MenuRow("图片落款", footer.ifBlank { "不显示" }.let { if (it.length > 12) it.take(12) + "…" else it }, last = true) { editFooter = true }
        }
        Band()
        Group {
            val b = settings.backup
            MenuRow("本地备份", if (b.localEnabled) intervalLabel(b.intervalHours) + "自动" else null) { AppState.push(Screen.LocalBackup) }
            MenuRow("云端备份", if (b.davEnabled) intervalLabel(b.davIntervalHours) + "自动" else null) { AppState.push(Screen.CloudBackup) }
            MenuRow("回收站", if (trashCount > 0) "$trashCount 个文件" else null, last = true) { AppState.push(Screen.Trash) }
        }
        Band()
        Group {
            MenuRow("深色模式", themeLabel(settings.theme, settings.darkFrom, settings.darkTo)) { editTheme = true }
            MenuRow("关于", last = true) { AppState.push(Screen.About) }
        }
        HLine(color = Kongye.Line)
        Spacer(Modifier.height(40.dp))
    }

    if (editFooter) FooterDialog { editFooter = false }
    if (editToolbar) ToolbarOrderSheet { editToolbar = false }
    if (editTheme) ThemeSheet { editTheme = false }
}


@Composable
private fun RowScope.StatBlock(value: Int, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Kongye.Card).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(Locale.US, "%,d", value), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = Kongye.Sub)
    }
}

/** 分组之间的灰色间隔带 */
@Composable
private fun Band() {
    Column {
        HLine(color = Kongye.Line)
        Box(Modifier.fillMaxWidth().height(18.dp).background(Kongye.Band))
        HLine(color = Kongye.Line)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface), content = content)
}

@Composable
private fun MenuRow(label: String, trailing: String? = null, last: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
            if (trailing != null) {
                Text(trailing, fontSize = 15.sp, color = Kongye.Sub)
                Spacer(Modifier.width(6.dp))
            }
            Icon(KongyeIcons.ChevronRight, null, Modifier.size(18.dp), tint = Kongye.Text4)
        }
        if (!last) HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}
