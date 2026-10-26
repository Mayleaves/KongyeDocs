package com.moji.notes.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 全部界面颜色。每个颜色都有浅色、深色两个取值，按 [dark] 返回；
 * dark 是可观察的状态，由 MainActivity 根据“深色模式”设置更新，界面会自动重组 / 重绘。
 */
object Moji {
    var dark by mutableStateOf(false)

    private fun c(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val Primary get() = c(0xFF417FF9, 0xFF5B93FA)
    val PrimarySoft get() = c(0xFFEDF3FF, 0xFF1F2A40)
    val Text get() = c(0xFF1F2329, 0xFFE4E6EA)
    val Text2 get() = c(0xFF646A73, 0xFFA7ACB4)
    val Text3 get() = c(0xFF8F959E, 0xFF80868E)
    val Text4 get() = c(0xFFBBBFC4, 0xFF51555B)
    val Line get() = c(0xFFEFF0F1, 0xFF2C2E32)
    val Line2 get() = c(0xFFDEE0E3, 0xFF3B3E43)
    val Bg get() = c(0xFFFFFFFF, 0xFF1C1D1F)
    val Bg2 get() = c(0xFFF5F6F7, 0xFF26272A)
    val Bg3 get() = c(0xFFF2F3F5, 0xFF2A2B2E)
    val Danger get() = c(0xFFF54A45, 0xFFFF6B66)
    val Star get() = c(0xFFFFB21F, 0xFFFFB21F)
    val Mask get() = c(0x66000000, 0x99000000)

    // 石墨风格的黑白灰
    val Ink get() = c(0xFF333333, 0xFFE2E2E2)
    val Ink2 get() = c(0xFF424242, 0xFFC9C9C9)
    val Sub get() = c(0xFFB3B3B3, 0xFF7C7C7C)
    val TabOff get() = c(0xFFBDBDBD, 0xFF6E6E6E)
    val NavOff get() = c(0xFFD9D9D9, 0xFF4C4C4C)
    val Divider get() = c(0xFFEEEEEE, 0xFF2C2D30)
    val Band get() = c(0xFFF5F5F5, 0xFF111113)
    val Bookmark get() = c(0xFF4D8DF0, 0xFF5B9BF5)
    /** 文字链接（“了解详情”“保存版本”等），比 Primary 柔和 */
    val Link get() = c(0xFF6A8CC8, 0xFF7FA3DE)
    val FabTop get() = c(0xFF5B5E63, 0xFF56595F)
    val FabBottom get() = c(0xFF393B3F, 0xFF3A3C41)

    /** 页面、面板、弹窗的底色 */
    val Surface get() = c(0xFFFFFFFF, 0xFF1C1D1F)
    /** 浅灰卡片（统计块、模板卡等） */
    val Card get() = c(0xFFF7F7F7, 0xFF26272A)
    /** 输入框底色 */
    val Field get() = c(0xFFF5F5F5, 0xFF2A2B2E)
    /** 次要图标（⋮ 等） */
    val Icon2 get() = c(0xFF9E9E9E, 0xFF7E7E7E)
    /** 浮层遮罩 */
    val Scrim get() = c(0x59000000, 0x99000000)

    /** 文字颜色（0 表示默认色） */
    val textPalette = listOf(0L, 0xFF8F959E, 0xFFF54A45, 0xFFFF8800, 0xFFDC9B04, 0xFF2EA121, 0xFF245BDB, 0xFF6425D0)

    /** 背景高亮颜色（0 表示无） */
    val highlightPalette = listOf(0L, 0xFFDEE0E3, 0xFFFBBFBC, 0xFFFED4A4, 0xFFF8E6AB, 0xFFB7EDB1, 0xFFBACEFD, 0xFFCDB2FA)

    /** 用户设置的文字颜色 / 背景高亮在深色下要压暗一些，否则太刺眼、看不清字 */
    fun userText(color: Long): Color = Color(color).let { if (dark && color == 0xFF245BDBL) Color(0xFF6C93F0) else if (dark && color == 0xFF6425D0L) Color(0xFFA27BF0) else it }
    fun userHighlight(color: Long): Color = Color(color).let { if (dark) it.copy(alpha = 0.32f) else it }
}

private val base = TextStyle()

private val typography = Typography(
    titleLarge = base.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = base.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp),
)

@Composable
fun MojiTheme(content: @Composable () -> Unit) {
    val colors = if (Moji.dark) darkColorScheme(
        primary = Moji.Primary, onPrimary = Color.White, primaryContainer = Moji.PrimarySoft, onPrimaryContainer = Moji.Primary,
        background = Moji.Bg, onBackground = Moji.Text, surface = Moji.Surface, onSurface = Moji.Text,
        surfaceVariant = Moji.Bg3, onSurfaceVariant = Moji.Text2,
        surfaceContainerLowest = Moji.Surface, surfaceContainerLow = Moji.Surface, surfaceContainer = Moji.Surface,
        surfaceContainerHigh = Moji.Surface, surfaceContainerHighest = Moji.Bg3,
        outline = Moji.Line2, outlineVariant = Moji.Line, error = Moji.Danger,
    ) else lightColorScheme(
        primary = Moji.Primary, onPrimary = Color.White, primaryContainer = Moji.PrimarySoft, onPrimaryContainer = Moji.Primary,
        background = Moji.Bg, onBackground = Moji.Text, surface = Moji.Surface, onSurface = Moji.Text,
        surfaceVariant = Moji.Bg3, onSurfaceVariant = Moji.Text2,
        surfaceContainerLowest = Moji.Surface, surfaceContainerLow = Moji.Surface, surfaceContainer = Moji.Surface,
        surfaceContainerHigh = Moji.Surface, surfaceContainerHighest = Moji.Bg3,
        outline = Moji.Line2, outlineVariant = Moji.Line, error = Moji.Danger,
    )
    // 默认文字色、图标色跟着主题走（Text / Icon 不写颜色时使用）
    MaterialTheme(colorScheme = colors, typography = typography.copy(
        titleLarge = typography.titleLarge.copy(color = Moji.Text),
        titleMedium = typography.titleMedium.copy(color = Moji.Text),
        bodyLarge = typography.bodyLarge.copy(color = Moji.Text),
        bodyMedium = typography.bodyMedium.copy(color = Moji.Text),
        bodySmall = typography.bodySmall.copy(color = Moji.Text3),
        labelLarge = typography.labelLarge.copy(color = Moji.Text),
    )) {
        CompositionLocalProvider(LocalContentColor provides Moji.Text, content = content)
    }
}
