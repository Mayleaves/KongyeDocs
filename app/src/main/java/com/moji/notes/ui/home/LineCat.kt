package com.moji.notes.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.notes.ui.theme.Moji
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 一天中的时段：“我的”页的问候语和小猫的样子都按它来 */
enum class DayPart(val greeting: String) {
    MORNING("早上好"), NOON("中午好"), AFTERNOON("下午好"), EVENING("晚上好"), NIGHT("夜深了");

    companion object {
        fun of(hour: Int) = when (hour) {
            in 5..10 -> MORNING
            in 11..12 -> NOON
            in 13..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> NIGHT
        }
    }
}

// 小猫画在 72 × 78 的画布上，y = 60 是它坐着的地面（统计块的上沿），尾巴从块的边上垂下来
internal const val CAT_W = 80f
internal const val CAT_H = CAT_W * 78f / 72f
internal const val CAT_GROUND = CAT_H * 60f / 78f

/** 点小猫时的小动作，以及各自持续的秒数 */
private enum class CatAct(val seconds: Float) { WINK(0.9f), WAVE(1.6f), TAIL(1.4f), JUMP(0.8f), HEART(1.3f), MEOW(1.1f), TILT(1.4f) }

private enum class Eyes { OPEN, LOOK, HAPPY, SQUINT, SLEEP, WINK }

private object CatPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    // 头和耳朵（闭合，先用底色填充，挡住身后的线）
    val head = p("M18.5 27C16.5 21 17 15 19.5 11L19 4.5L25.5 9.5C28.5 8.8 31.5 8.8 34.5 9.5L41 4.5L40.5 11C43 15 43.5 21 41.5 27C39 32.5 34.5 35 30 35C25.5 35 21 32.5 18.5 27Z")
    val squint = p("M23.5 20.5Q25.5 22.8 27.5 20.5M32.5 20.5Q34.5 22.8 36.5 20.5")
    val happy = p("M23.5 21.8Q25.5 19 27.5 21.8M32.5 21.8Q34.5 19 36.5 21.8")
    val sleep = p("M23.6 21.2Q25.5 22.3 27.4 21.2M32.6 21.2Q34.5 22.3 36.4 21.2")
    val happyRight = p("M32.5 21.8Q34.5 19 36.5 21.8")
    val nose = p("M28.9 24.8L31.1 24.8L30 26.1Z")
    val mouth = p("M30 26.1Q29.2 27.9 27.6 27.3M30 26.1Q30.8 27.9 32.4 27.3")
    val tongue = p("M29.2 27.6Q30 29.9 30.8 27.6")
    val mouthOpen = p("M28.4 27.2Q30 30.8 31.6 27.2Q30 28 28.4 27.2Z")
    val whiskers = p("M21 25.3L14.8 24.3M21 27.4L15.2 28.4M39 25.3L45.2 24.3M39 27.4L44.8 28.4")

    // 坐着：胸口、后背、并拢的前爪
    val chest = p("M22.3 33.2C18.3 39.5 16.8 49.5 18.2 60")
    val back = p("M38.2 32.6C46.8 35 53.6 42.8 54.8 51.6C55.3 55.6 54.6 58.2 53 60")
    val pawLeft = p("M24 60C24 56.6 28.8 56.6 28.8 60")
    val pawRight = p("M30.6 60C30.6 56.6 35.4 56.6 35.4 60")
    // 举起来打招呼的左前爪（以肩膀 20.5, 44.5 为轴摆动）
    val arm = p("M19.6 46C15.8 43.5 13 39.6 12.4 35.4C12 33 14.6 31.8 15.8 33.8C17 37 19.2 40 22 42")

    // 尾巴：垂在块边上 / 在背后翘起来
    val tailHang = p("M53.6 59C59.6 58.6 63.8 60.2 64.8 63.8C65.8 67.6 63.4 70.6 64.4 73.8C65 75.6 67.2 76.2 68.8 75")
    val tailUp = p("M53.4 58.6C60.5 58 65 52.5 64.6 45.5C64.3 40.5 61 37.6 58.6 39.4C57 40.6 57.8 43 59.8 42.6")

    // 夜里：蜷成一团睡觉
    val sleepBody = p("M16.5 60C15.5 50.5 22.5 43 34 42.2C46 41.4 56 47 57.6 55C58.1 57.6 57.6 59.2 56.6 60")
    val sleepPaw = p("M27.2 60C27.6 57.4 32.2 57.2 33 59.8")

    // 各个时段的小物件
    val bowl = p("M1.5 53.5L15.5 53.5C15.5 57.6 12.4 60 8.5 60C4.6 60 1.5 57.6 1.5 53.5Z")
    val steam = p("M5.5 50.5Q4.2 48.2 5.6 46.2Q7 44.2 5.8 42M10.5 50.5Q9.2 48.2 10.6 46.2Q12 44.2 10.8 42")
    val yarnLines = p("M5 53Q8.5 55 12 52.6M4.6 56.8Q8.5 58.6 12.6 56.2M7 51.4Q5.4 55.6 7.6 59.8")
    val thread = p("M12.6 57.6Q18 61.4 24.2 59.6")
    val moon = p("M64.5 5.2A6 6 0 1 0 69.6 15A4.7 4.7 0 1 1 64.5 5.2Z")
    val star = p("M57 5.2L57 8.8M55.2 7L58.8 7")
    val heart = p("M0 3C-2.5 1 -4 -0.6 -4 -2.2C-4 -3.8 -2.6 -4.6 -1.4 -4.4C-0.6 -4.3 -0.1 -3.7 0 -3.2C0.1 -3.7 0.6 -4.3 1.4 -4.4C2.6 -4.6 4 -3.8 4 -2.2C4 -0.6 2.5 1 0 3Z")
    val question = p("M47.6 8.6C47.6 6 52.2 5.6 52.2 8.4C52.2 10.4 49.9 10.6 49.9 12.6")
    val meow = p("M12.5 18Q10.5 21 12.5 24M9.5 16.5Q6.5 21 9.5 25.5")
}

/** “Z” 字：左上角在 (x, y)，边长 s */
private fun zPath(x: Float, y: Float, s: Float) = Path().apply {
    moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s)
}

/**
 * 线条小猫：样子跟着问候语走（早上睁眼翘尾巴、中午舔嘴边的饭碗、下午盯着毛线团、晚上眯眼看月亮、深夜蜷着睡觉），
 * 点一下做个小动作（眨眼、招手、甩尾巴、跳一下、冒爱心、喵一声、歪头），连点几下（每次 5～8 下，随机）会冒出一句悄悄话。
 */
@Composable
fun LineCat(part: DayPart, messages: () -> List<String>, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    // 动画时钟（秒），只在绘制时读取，不会引起重组
    val clock = produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    var act by remember { mutableStateOf<CatAct?>(null) }
    var actStart by remember { mutableFloatStateOf(0f) }
    var actId by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) }
    var goal by remember { mutableIntStateOf(Random.nextInt(5, 9)) }
    var bubble by remember { mutableStateOf<String?>(null) }
    var bubbleText by remember { mutableStateOf("") }
    var bubbleId by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf("") }

    LaunchedEffect(actId) {
        val a = act ?: return@LaunchedEffect
        delay((a.seconds * 1000).toLong())
        act = null
    }
    LaunchedEffect(bubbleId) {
        if (bubble == null) return@LaunchedEffect
        delay(3200)
        bubble = null
    }

    val ink = Moji.Ink2
    val bg = Moji.Surface
    val pink = Color(0xFFF28B82)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "小猫" }
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    act = CatAct.entries.filter { it != act }.random()
                    actStart = clock.value
                    actId++
                    taps++
                    if (taps >= goal) {
                        taps = 0
                        goal = Random.nextInt(5, 9)
                        val text = messages().filter { it != lastMessage }.random()
                        lastMessage = text
                        bubbleText = text
                        bubble = text
                        bubbleId++
                    }
                },
        ) {
            val t = clock.value
            val a = act
            val p = if (a == null) 0f else ((t - actStart) / a.seconds).coerceIn(0f, 1f)
            drawCat(part, a, p, t, ink, bg, pink)
        }
        // 悄悄话气泡：贴在小猫脑袋左边，不占布局
        Box(
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints(maxWidth = 220.dp.roundToPx()))
                val k = (CAT_W / 72f).dp.toPx()
                layout(0, 0) { placeable.place((16f * k).toInt() - placeable.width, (20f * k).toInt() - placeable.height / 2) }
            },
        ) {
            AnimatedVisibility(
                bubble != null,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0.5f)),
                exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0.5f)),
            ) { CatBubble(bubbleText) }
        }
    }
}

@Composable
private fun CatBubble(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.widthIn(max = 200.dp).shadow(3.dp, shape).background(Moji.Surface, shape)
                .border(0.6.dp, Moji.Line2, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Moji.Ink2) }
        // 指向小猫的小尖角
        val surface = Moji.Surface
        val line = Moji.Line2
        Canvas(Modifier.size(6.dp, 10.dp)) {
            val tip = Path().apply { moveTo(-1f, 0f); lineTo(size.width, size.height / 2); lineTo(-1f, size.height); close() }
            drawPath(tip, surface)
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height) }, line, style = Stroke(0.6.dp.toPx()))
        }
    }
}

/** 画一帧小猫。p 是当前小动作的进度（0～1），t 是动画时钟（秒） */
private fun DrawScope.drawCat(part: DayPart, act: CatAct?, p: Float, t: Float, ink: Color, bg: Color, pink: Color) {
    val k = size.width / 72f
    val line = Stroke(1.6.dp.toPx() / k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(1.2.dp.toPx() / k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val wave = sin(p * PI.toFloat())
    withTransform({ scale(k, k, pivot = Offset.Zero) }) {
        // 背景里的小物件（不跟着跳）
        when (part) {
            DayPart.MORNING -> {
                // 慢慢转的小太阳
                drawCircle(ink, 3.6f, Offset(63f, 12f), style = line)
                rotate(t * 18f, pivot = Offset(63f, 12f)) {
                    for (i in 0 until 8) rotate(i * 45f, pivot = Offset(63f, 12f)) {
                        drawLine(ink, Offset(63f, 6.6f), Offset(63f, 4.8f), strokeWidth = line.width, cap = StrokeCap.Round)
                    }
                }
            }
            DayPart.NOON -> {
                // 饭碗冒着热气
                translate(left = sin(t * 2.2f) * 0.5f) { drawPath(CatPaths.steam, ink.copy(alpha = 0.45f + 0.25f * sin(t * 1.7f)), style = thin) }
                drawPath(CatPaths.bowl, bg)
                drawPath(CatPaths.bowl, ink, style = line)
            }
            DayPart.AFTERNOON -> {
                // 毛线团，线头牵到爪子边
                drawPath(CatPaths.thread, ink, style = thin)
                drawCircle(bg, 4.5f, Offset(8.5f, 55.5f))
                drawCircle(ink, 4.5f, Offset(8.5f, 55.5f), style = line)
                rotate(sin(t * 1.3f) * 8f, pivot = Offset(8.5f, 55.5f)) { drawPath(CatPaths.yarnLines, ink, style = thin) }
            }
            DayPart.EVENING, DayPart.NIGHT -> {
                drawPath(CatPaths.moon, ink, style = line)
                drawPath(CatPaths.star, ink.copy(alpha = 0.35f + 0.45f * (0.5f + 0.5f * sin(t * 2.4f))), style = thin)
            }
        }

        if (part == DayPart.NIGHT && act == null) {
            drawSleeping(t, ink, bg, line, thin)
            return@withTransform
        }

        // 坐着的小猫（深夜被点醒时也坐起来）
        val jump = if (act == CatAct.JUMP) -wave * 7f else 0f
        translate(top = jump) {
            // 尾巴：早上翘着，其余垂在块边；轻轻摆，“甩尾巴”时大幅摆动
            val swish = if (act == CatAct.TAIL) sin(p * PI.toFloat() * 4f) * 16f * (1f - p * 0.4f) else sin(t * 1.3f) * 3f
            if (part == DayPart.MORNING) {
                rotate(swish, pivot = Offset(53.4f, 58.6f)) { drawPath(CatPaths.tailUp, ink, style = line) }
            } else {
                rotate(swish, pivot = Offset(53.6f, 59f)) { drawPath(CatPaths.tailHang, ink, style = line) }
            }
            drawPath(CatPaths.chest, ink, style = line)
            drawPath(CatPaths.back, ink, style = line)
            drawPath(CatPaths.pawRight, ink, style = line)
            if (act == CatAct.WAVE) {
                rotate(sin(p * PI.toFloat() * 5f) * 15f, pivot = Offset(20.5f, 44.5f)) { drawPath(CatPaths.arm, ink, style = line) }
            } else {
                drawPath(CatPaths.pawLeft, ink, style = line)
            }
            // 头：“歪头”时绕脖子转一点
            val tilt = if (act == CatAct.TILT) wave * 13f else 0f
            rotate(tilt, pivot = Offset(30f, 33f)) {
                drawHead(eyesFor(part, act, t), mouthFor(part, act), ink, bg, line)
            }
        }

        // 小动作附带的符号
        when (act) {
            CatAct.HEART -> translate(49f, 13f - p * 9f) {
                scale(0.8f + p * 0.4f, pivot = Offset.Zero) { drawPath(CatPaths.heart, pink.copy(alpha = 1f - p * p)) }
            }
            CatAct.TILT -> {
                drawPath(CatPaths.question, ink.copy(alpha = wave), style = line)
                drawCircle(ink.copy(alpha = wave), 0.75f, Offset(49.9f, 15.2f))
            }
            CatAct.MEOW -> translate(left = -p * 1.5f) { drawPath(CatPaths.meow, ink.copy(alpha = wave), style = thin) }
            else -> {}
        }
    }
}

private fun eyesFor(part: DayPart, act: CatAct?, t: Float): Eyes {
    val eyes = when (act) {
        CatAct.WINK -> Eyes.WINK
        CatAct.WAVE, CatAct.JUMP, CatAct.HEART -> Eyes.HAPPY
        CatAct.MEOW -> Eyes.SQUINT
        CatAct.TAIL, CatAct.TILT -> Eyes.OPEN
        null -> when (part) {
            DayPart.MORNING -> Eyes.OPEN
            DayPart.NOON -> Eyes.HAPPY
            DayPart.AFTERNOON -> Eyes.LOOK
            DayPart.EVENING -> Eyes.SQUINT
            DayPart.NIGHT -> Eyes.SLEEP
        }
    }
    // 睁着眼时隔几秒眨一下
    val blinking = (eyes == Eyes.OPEN || eyes == Eyes.LOOK) && t % 4.3f < 0.13f
    return if (blinking) Eyes.SLEEP else eyes
}

private fun mouthFor(part: DayPart, act: CatAct?) = when {
    act == CatAct.MEOW -> CatPaths.mouthOpen
    act == null && part == DayPart.NOON -> null // 中午舔嘴：嘴加小舌头
    else -> CatPaths.mouth
}

private fun DrawScope.drawHead(eyes: Eyes, mouth: Path?, ink: Color, bg: Color, line: Stroke) {
    drawPath(CatPaths.head, bg)
    drawPath(CatPaths.head, ink, style = line)
    when (eyes) {
        Eyes.OPEN -> { drawEye(25.5f, 20.6f, ink, bg); drawEye(34.5f, 20.6f, ink, bg) }
        Eyes.LOOK -> { drawEye(24.9f, 21.2f, ink, bg); drawEye(33.9f, 21.2f, ink, bg) }
        Eyes.HAPPY -> drawPath(CatPaths.happy, ink, style = line)
        Eyes.SQUINT -> drawPath(CatPaths.squint, ink, style = line)
        Eyes.SLEEP -> drawPath(CatPaths.sleep, ink, style = line)
        Eyes.WINK -> { drawEye(25.5f, 20.6f, ink, bg); drawPath(CatPaths.happyRight, ink, style = line) }
    }
    drawPath(CatPaths.nose, ink)
    if (mouth == null) {
        drawPath(CatPaths.mouth, ink, style = line)
        drawPath(CatPaths.tongue, ink, style = line)
    } else {
        drawPath(mouth, ink, style = line)
    }
    drawPath(CatPaths.whiskers, ink, style = line)
}

/** 圆圆的眼睛，带一个小高光 */
private fun DrawScope.drawEye(cx: Float, cy: Float, ink: Color, bg: Color) {
    drawOval(ink, Offset(cx - 1.5f, cy - 1.8f), Size(3f, 3.6f))
    drawCircle(bg, 0.55f, Offset(cx + 0.55f, cy - 0.7f))
}

/** 深夜：蜷成一团，下巴搁在爪子上，尾巴垂在边上，头顶飘着 Z */
private fun DrawScope.drawSleeping(t: Float, ink: Color, bg: Color, line: Stroke, thin: Stroke) {
    translate(3f, 0.6f) { rotate(sin(t * 0.9f) * 2f, pivot = Offset(53.6f, 59f)) { drawPath(CatPaths.tailHang, ink, style = line) } }
    // 呼吸：身体轻轻起伏
    scale(1f, 1f + 0.02f * sin(t * 1.6f), pivot = Offset(36f, 60f)) {
        drawPath(CatPaths.sleepBody, bg)
        drawPath(CatPaths.sleepBody, ink, style = line)
    }
    withTransform({
        translate(23f, 58f)
        rotate(-10f, pivot = Offset.Zero)
        scale(0.74f, 0.74f, pivot = Offset.Zero)
        translate(-30f, -35f)
    }) {
        drawHead(Eyes.SLEEP, CatPaths.mouth, ink, bg, Stroke(line.width / 0.74f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(CatPaths.sleepPaw, ink, style = line)
    for (i in 0 until 3) {
        val ph = (t * 0.42f + i / 3f) % 1f
        val s = 2.4f + i * 0.5f + ph * 1.4f
        drawPath(zPath(30f + ph * 11f, 34f - ph * 20f, s), ink.copy(alpha = sin(ph * PI.toFloat()) * 0.8f), style = thin)
    }
}

/** 连点小猫后冒出来的悄悄话 */
fun catMessages(part: DayPart, words: Int, days: Int): List<String> {
    val w = String.format(Locale.US, "%,d", words)
    val byTime = when (part) {
        DayPart.MORNING -> "早起的小猫有鱼吃，早起的你有字写"
        DayPart.NOON -> "午饭吃了吗？小猫已经吃饱了"
        DayPart.AFTERNOON -> "下午容易犯困，喝口水再接着写吧"
        DayPart.EVENING -> "晚上好呀，今天也辛苦啦"
        DayPart.NIGHT -> "嘘……小猫睡着了，你也早点休息吧"
    }
    return listOf(
        byTime,
        "喵～ 被你发现啦",
        if (words > 0) "你已经写下 $w 个字，小猫都记着呢" else "还一个字都没写呢，小猫在等你",
        "今天是我们相伴的第 $days 天",
        "别戳啦，再戳毛都要掉了",
        "偷偷告诉你：「关于」里的叶子也能点",
        "愿每一片叶子，都有字可落",
    )
}
