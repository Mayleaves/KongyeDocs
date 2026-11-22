package com.kongye.docs.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.Layout
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
import com.kongye.docs.ui.screens.SettingRow
import com.kongye.docs.ui.screens.intervalLabel
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.LocalKongyeLayout
import com.kongye.docs.util.dayNumber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MeTab(items: List<Item>) {
    val layout = LocalKongyeLayout.current
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
        Column(
            Modifier.fillMaxWidth().background(Kongye.Surface).statusBarsPadding()
                .padding(horizontal = layout.gutter, vertical = layout.groupGap),
        ) {
            val now = remember { Calendar.getInstance() }
            val part = remember { DayPart.of(now.get(Calendar.HOUR_OF_DAY)) }
            val days = dayNumber(settings.firstUse)
            val catScale = if (layout.compact) 0.90f else 1f
            val catWidth = (CAT_W * catScale).dp
            Layout(content = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(part.greeting, fontSize = layout.headerText, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now.time),
                            fontSize = 12.sp, color = Kongye.Sub,
                        )
                    }
                    Spacer(Modifier.size(catWidth, catWidth * CAT_GROUND_WIDTH_FRACTION))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.groupGap)) {
                    StatBlock(docCount, "文件")
                    StatBlock(folderCount, "文件夹")
                    StatBlock(starredCount, "收藏")
                }
                LineCat(
                    part, { catMessages(part, words, days) },
                    Modifier.size(catWidth, (CAT_H * catScale).dp),
                )
            }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
                val header = measurables[0].measure(constraints.copy(minHeight = 0))
                val stats = measurables[1].measure(constraints.copy(minHeight = 0))
                val cat = measurables[2].measure(constraints.copy(minWidth = 0, minHeight = 0))
                val statsTop = header.height + layout.groupGap.roundToPx()
                val catGround = (cat.width * CAT_GROUND_WIDTH_FRACTION).roundToInt()
                layout(constraints.maxWidth, statsTop + stats.height) {
                    header.placeRelative(0, 0)
                    stats.placeRelative(0, statsTop)
                    // 画布按实际宽度缩放，猫爪贴着收藏卡片上沿，尾巴垂在卡片前面。
                    cat.placeRelative(stats.width - cat.width, statsTop - catGround)
                }
            }
        }
        // 按功能分块（不加标题，石墨样式）：写作 · 导出 · 数据安全（备份和找回） · 通用
        Band()
        Group {
            SettingRow("模板库") { AppState.push(Screen.Templates(null)) }
            SettingRow("编辑工具栏", last = true) { editToolbar = true }
        }
        Band()
        Group {
            SettingRow("下载记录", if (exports.isNotEmpty()) "${exports.size} 条" else null) { AppState.push(Screen.Downloads) }
            SettingRow("图片落款", footer.ifBlank { "不显示" }, last = true) { editFooter = true }
        }
        Band()
        Group {
            val b = settings.backup
            SettingRow("本地备份", if (b.localEnabled) intervalLabel(b.intervalHours) + "自动" else null) { AppState.push(Screen.LocalBackup) }
            SettingRow("云端备份", if (b.davEnabled) intervalLabel(b.davIntervalHours) + "自动" else null) { AppState.push(Screen.CloudBackup) }
            SettingRow("回收站", if (trashCount > 0) "$trashCount 个文件" else null, last = true) { AppState.push(Screen.Trash) }
        }
        Band()
        Group {
            SettingRow("深色模式", themeLabel(settings.theme, settings.darkFrom, settings.darkTo)) { editTheme = true }
            SettingRow("关于", last = true) { AppState.push(Screen.About) }
        }
        HLine(color = Kongye.Line)
        Spacer(Modifier.height(layout.groupGap))
    }

    if (editFooter) FooterDialog { editFooter = false }
    if (editToolbar) ToolbarOrderSheet { editToolbar = false }
    if (editTheme) ThemeSheet { editTheme = false }
}


@Composable
private fun RowScope.StatBlock(value: Int, label: String) {
    val layout = LocalKongyeLayout.current
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Kongye.Card).padding(vertical = layout.groupGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(Locale.US, "%,d", value), fontSize = layout.headerText, fontWeight = FontWeight.SemiBold, color = Kongye.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 13.sp, color = Kongye.Sub)
    }
}

/** 分组之间的灰色间隔带 */
@Composable
private fun Band() {
    Column {
        HLine(color = Kongye.Line)
        Box(Modifier.fillMaxWidth().height(LocalKongyeLayout.current.groupGap).background(Kongye.Band))
        HLine(color = Kongye.Line)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Kongye.Surface), content = content)
}
