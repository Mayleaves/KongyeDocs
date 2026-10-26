package com.kongye.docs.ui.screens

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.APP_VERSION
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.LeafLogo
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.components.TopBar
import com.kongye.docs.ui.leafPaths
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.data.Settings
import com.kongye.docs.util.dayNumber
import com.kongye.docs.util.sizeText
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

/** 点一下换一句；都是公版的古诗文或俗语 */
private val lines = listOf(
    "一叶落而知天下秋。" to "《淮南子》",
    "空山新雨后，天气晚来秋。" to "王维",
    "纸上得来终觉浅，绝知此事要躬行。" to "陆游",
    "落红不是无情物，化作春泥更护花。" to "龚自珍",
    "好记性不如烂笔头。" to "俗语",
    "删繁就简三秋树，领异标新二月花。" to "郑板桥",
)

/** 落叶彩蛋里的一片叶子：起点、下落速度、左右摆动、旋转和大小 */
private class FallingLeaf(rnd: Random) {
    val x = rnd.nextFloat()
    val delay = rnd.nextFloat() * 1.2f
    val speed = 0.22f + rnd.nextFloat() * 0.18f
    val sway = 18f + rnd.nextFloat() * 36f
    val swayRate = 1.2f + rnd.nextFloat() * 1.6f
    val spin = (rnd.nextFloat() - 0.5f) * 220f
    val size = 18f + rnd.nextFloat() * 22f
    val alpha = 0.25f + rnd.nextFloat() * 0.45f
}

/**
 * 关于：Logo、版本、一句随机的古诗，以及“你和空叶”的小统计。
 * 彩蛋：连点 Logo 7 次会刮起一阵秋风；长按版本号有一句悄悄话；10 月 25 日是空叶文档的生日。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val items by Repository.items.collectAsState()
    val alive = remember(items) { items.filter { Repository.isAlive(it, items) } }
    val aliveDocs = alive.filter { it.isDoc }
    val words = aliveDocs.sumOf { it.words }
    val folders = alive.count { it.isFolder }
    val comments = remember(items) { Repository.commentCount(aliveDocs.map { it.id }) }
    // 占用空间：应用本身 + 全部数据（和系统设置里看到的一致）；以及其中笔记数据的大小
    var storage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(Unit) { storage = withContext(Dispatchers.IO) { appStorage(context) } }
    val today = remember { Calendar.getInstance() }
    val birthday = today.get(Calendar.MONTH) == Calendar.OCTOBER && today.get(Calendar.DAY_OF_MONTH) == 25

    var lineIndex by remember { mutableIntStateOf(Random.nextInt(lines.size)) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    var autumn by remember { mutableStateOf(false) }
    val wobble = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Kongye.Band)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "关于", onBack = { AppState.pop() }, divider = true)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(40.dp))
                // 点 Logo：叶子晃一下；1.5 秒内连点 7 次，刮起一阵秋风
                LeafLogo(
                    Modifier.size(96.dp)
                        .graphicsLayer { rotationZ = wobble.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.36f, 0.95f) }
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            val now = System.currentTimeMillis()
                            taps = if (now - lastTap < 1500) taps + 1 else 1
                            lastTap = now
                            scope.launch {
                                wobble.snapTo(if (taps % 2 == 0) -9f else 9f)
                                wobble.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                            }
                            when {
                                taps >= 7 -> {
                                    taps = 0
                                    autumn = true
                                    Toast.clear()
                                }
                                taps >= 4 -> Toast.show("再点 ${7 - taps} 下，会有风")
                            }
                        },
                )
                Spacer(Modifier.height(18.dp))
                Text("空叶文档", fontSize = 21.sp, letterSpacing = 5.sp, color = Kongye.Ink, fontWeight = FontWeight.Medium)
                // 标题下面：相识于哪天、相伴第几天（每年相识的那天换成“相识 N 周年”）；10 月 25 日下面再加一句生日。版本号移到页面最下面
                val settings by Settings.state.collectAsState()
                if (settings.firstUse > 0L) {
                    Spacer(Modifier.height(6.dp))
                    TogetherLine(settings.firstUse, today, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (birthday) {
                    Spacer(Modifier.height(4.dp))
                    Text("今天也是空叶文档的生日", fontSize = 13.sp, color = Kongye.Bookmark)
                }

                // 一句古诗，点一下换一句
                val (line, from) = lines[lineIndex]
                Column(
                    Modifier.padding(horizontal = 32.dp, vertical = 28.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { lineIndex = (lineIndex + 1) % lines.size }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(line, fontSize = 15.sp, lineHeight = 24.sp, color = Kongye.Ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("—— $from", fontSize = 12.sp, color = Kongye.Sub)
                }

                // 你和空叶
                Column(Modifier.fillMaxWidth().background(Kongye.Surface)) {
                    HLine(color = Kongye.Line)
                    AboutRow("已写下", String.format(Locale.US, "%,d 字", words))
                    AboutRow("相当于", if (words >= 28) "${words / 28} 首七言绝句" else "还不够一首绝句，接着写吧")
                    AboutRow("文档", "${aliveDocs.size} 篇")
                    AboutRow("文件夹", "$folders 个")
                    AboutRow("评论", "$comments 条")
                    AboutRow("占用空间", storage?.let { sizeText(it.first) } ?: "计算中…", last = true) {
                        storage?.let { (total, notes) ->
                            Toast.show(if (total > notes) "其中笔记和图片 ${sizeText(notes).ifEmpty { "0 KB" }}，其余是应用本身和缓存" else "全部是笔记和图片")
                        }
                    }
                    HLine(color = Kongye.Line)
                }
                Spacer(Modifier.height(36.dp))
                Text("愿每一片叶子，都有字可落。", fontSize = 12.sp, color = Kongye.Text4)
                // 版本号：长按有一句悄悄话
                Text(
                    "版本 $APP_VERSION",
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).combinedClickable(
                        onClick = {},
                        onLongClick = { Toast.show("这是空叶文档的第 ${APP_VERSION.substringAfter('.').toIntOrNull()?.plus(1) ?: 1} 个版本，谢谢你一路提的每一条建议") },
                    ).padding(horizontal = 8.dp, vertical = 2.dp),
                    fontSize = 11.sp, color = Kongye.Text4,
                )
                Spacer(Modifier.navigationBarsPadding().height(36.dp))
            }
        }
        AnimatedVisibility(autumn, enter = fadeIn(tween(200)), exit = fadeOut(tween(600))) {
            AutumnWind { autumn = false }
        }
    }
}

/** “相识于 2026年10月25日 · 相伴第 N 天”，灰色，天数加深加粗；每年相识的那天换成“今天是相识 N 周年” */
@Composable
private fun TogetherLine(firstUse: Long, now: Calendar, modifier: Modifier = Modifier) {
    val first = Calendar.getInstance().apply { timeInMillis = firstUse }
    val years = now.get(Calendar.YEAR) - first.get(Calendar.YEAR)
    val anniversary = years > 0 && now.get(Calendar.MONTH) == first.get(Calendar.MONTH) &&
        now.get(Calendar.DAY_OF_MONTH) == first.get(Calendar.DAY_OF_MONTH)
    val strong = SpanStyle(color = Kongye.Ink2, fontWeight = FontWeight.SemiBold)
    Text(
        buildAnnotatedString {
            append("相识于 ")
            append(SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(first.time))
            append(" · ")
            if (anniversary) {
                append("今天是相识 ")
                withStyle(strong) { append("$years") }
                append(" 周年")
            } else {
                append("相伴第 ")
                withStyle(strong) { append(String.format(Locale.US, "%,d", dayNumber(firstUse, now.timeInMillis))) }
                append(" 天")
            }
        },
        modifier, fontSize = 13.sp, color = Kongye.Sub,
    )
}

/** 应用总共占用的空间（与系统设置一致：应用本身 + 数据 + 缓存）和其中笔记数据的大小 */
private fun appStorage(context: Context): Pair<Long, Long> {
    val notes = Repository.dataSize()
    val total = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
        stats.appBytes + stats.dataBytes
    }.getOrNull()?.takeIf { it >= notes } ?: notes
    return total to notes
}

@Composable
private fun AboutRow(label: String, value: String, last: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
            Text(value, fontSize = 15.sp, color = Kongye.Sub)
        }
        if (!last) HLine(Modifier.padding(horizontal = 20.dp), color = Kongye.Divider)
    }
}

/** 彩蛋：一阵秋风，十几片空叶从屏幕上方飘落，正中浮现一句话，约 4.5 秒后散去 */
@Composable
private fun AutumnWind(onEnd: () -> Unit) {
    val leaves = remember { Random(System.nanoTime()).let { r -> List(16) { FallingLeaf(r) } } }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 4.5f) withFrameNanos { t = (it - start) / 1_000_000_000f }
        onEnd()
    }
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onEnd() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = 64f
            leaves.forEach { leaf ->
                val time = t - leaf.delay
                if (time <= 0f) return@forEach
                val px = leaf.size.dp.toPx() / unit
                val y = -leaf.size.dp.toPx() + time * leaf.speed * size.height
                val x = leaf.x * size.width + sin(time * leaf.swayRate) * leaf.sway.dp.toPx()
                translate(x, y) {
                    rotate(leaf.spin * time, pivot = Offset.Zero) {
                        scale(px, px, pivot = Offset.Zero) {
                            leafPaths.forEach { drawPath(it, Kongye.Ink.copy(alpha = leaf.alpha)) }
                        }
                    }
                }
            }
        }
        val a = when {
            t < 0.8f -> 0f
            t < 1.4f -> (t - 0.8f) / 0.6f
            t < 3.6f -> 1f
            else -> ((4.5f - t) / 0.9f).coerceIn(0f, 1f)
        }
        Column(
            Modifier.graphicsLayer { alpha = a }.clip(RoundedCornerShape(14.dp)).background(Kongye.Surface.copy(alpha = 0.92f))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("起风了", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Kongye.Ink)
            Spacer(Modifier.height(6.dp))
            Text("一叶落而知天下秋", fontSize = 14.sp, color = Kongye.Text3)
        }
    }
}
