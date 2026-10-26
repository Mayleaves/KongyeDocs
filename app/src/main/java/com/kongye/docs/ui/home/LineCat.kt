package com.kongye.docs.ui.home

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.ui.theme.Kongye
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 一天中的时段：“我的”页的问候语、小猫的样子和它说的话都按它来 */
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

// 小猫直接用设计稿（150 × 150 的 SVG）的坐标来画，只取其中 x 6～142、y 16～146 这一块；
// y = 103 是它坐着的地面（统计块的上沿），尾巴从块的边上垂下来。每个单位画成 0.7dp
private const val X0 = 6f
private const val Y0 = 16f
private const val UNITS_W = 136f
private const val UNITS_H = 130f
private const val GROUND = 103f
private const val DP_PER_UNIT = 0.7f
internal const val CAT_W = UNITS_W * DP_PER_UNIT
internal const val CAT_H = UNITS_H * DP_PER_UNIT
internal const val CAT_GROUND = (GROUND - Y0) * DP_PER_UNIT

/** 点小猫（或它自己闲着）时的小动作，以及各自持续的秒数 */
private enum class CatAct(val seconds: Float) {
    PET(1.9f), FIERCE(1.5f), STRETCH(1.9f), CLINGY(1.8f), WINK(0.9f), TAIL(1.4f), JUMP(0.8f), MEOW(1.1f)
}

private enum class Eyes { OPEN, CLOSED, HAPPY, SQUEEZE, SPARKLE, ANGRY, WINK }
private enum class Mouth { DEFAULT, OMEGA, MEOW, YAWN, HISS }

private object CatPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    // ---- 坐着（1.svg 闭眼、2.svg 睁眼共用同一个轮廓）----
    val tail = p("M115 91C123 94 128 100 129 107L133 108L130 114L134 120L130 126C129 134 125 138 119 138L115 140C111 134 110 124 110 110L110 103Z")
    val body = p("M33 39L46 51Q55 46 65 51L78 39L77 61L83 64L80 68C98 68 109 76 113 87L116 88L113 94L116 99L112 103L35 103L32 98L35 93L33 87C32 82 33 77 35 73L30 70L34 65Z")
    val earLines = p("M38 48L44 56M73 48L67 56")
    val whiskers = p("M27 75L35 73M28 80L36 77M75 73L84 75M74 77L83 80")
    val paws = p("M44 103A5 5 0 0 1 54 103M61 103A5 5 0 0 1 71 103")
    val leg = p("M90 103C90 95 94 92 100 92")

    val eyesClosed = p("M42 68L51 70M60 70L69 68")
    val eyesHappy = p("M41.5 70Q46.5 64.5 51.5 70M59.5 70Q64.5 64.5 69.5 70")
    val eyeHappyRight = p("M59.5 70Q64.5 64.5 69.5 70")
    val eyesSqueeze = p("M42 65.5L49.5 68.5L42 71.5M69 65.5L61.5 68.5L69 71.5")
    val brows = p("M39.5 61L51 64.5M71.5 61L60 64.5")

    val mouth = p("M52 77L55.5 75L59 77")
    val mouthOmega = p("M50.5 76Q53 79 55.5 76Q58 79 60.5 76")
    val mouthMeow = p("M52.5 76Q55.5 82.5 58.5 76Q55.5 77.2 52.5 76Z")
    val mouthHiss = p("M50.5 78L55.5 75.5L60.5 78")
    val fangs = p("M52.4 77.1L53 79.4L53.9 76.3M58.6 77.1L58 79.4L57.1 76.3")
    val blush = p("M37.5 74.5L39.5 72M40.5 75L42.5 72.5M68.5 72.5L70.5 75M71.5 72L73.5 74.5")

    // ---- 睡觉（3.svg）----
    val sleepTail = p("M113 91c9 2 15 9 16 16l4 1-3 6 4 6-4 6c-1 8-5 12-11 12l-4 2c-4-6-5-15-5-28v-9l3-12Z")
    val sleepBody = p("M27 83l-11-15 19 4c2.67-1.33 5-1.67 7-1l9-20 9 18 6-2-2 4c18-3 38 3 48 15l4 2-3 5 3 5-4 5H33l-8-1 3-5-6-3 5-5-4-3 4-3Z")
    val sleepEars = p("M22 73L31 76M51 59L55 68")
    val sleepEyes = p("M31 92L41 90M48 88L56 81")
    val sleepWhiskers = p("M15 95L25 94M17 100L26 98M58 79L69 75M60 84L71 81")
    val sleepPaws = p("M48 103c0-3.04 2.46-5.5 5.5-5.5s5.5 2.46 5.5 5.5M65 103c0-3.04 2.46-5.5 5.5-5.5s5.5 2.46 5.5 5.5")
    val sleepLeg = p("M90 103c0-7.33 3-11.33 9-12")

    // ---- 小物件和符号 ----
    val moon = p("M128 28A10 10 0 1 0 136.5 44.5A8 8 0 1 1 128 28Z")
    val star = p("M113 26.5L113 33.5M109.5 30L116.5 30")
    val heart = p("M0 3C-2.5 1 -4 -0.6 -4 -2.2C-4 -3.8 -2.6 -4.6 -1.4 -4.4C-0.6 -4.3 -0.1 -3.7 0 -3.2C0.1 -3.7 0.6 -4.3 1.4 -4.4C2.6 -4.6 4 -3.8 4 -2.2C4 -0.6 2.5 1 0 3Z")
    val anger = p("M83.5 44.5Q86.5 44.5 86.5 41.5M89.5 41.5Q89.5 44.5 92.5 44.5M92.5 47.5Q89.5 47.5 89.5 50.5M86.5 50.5Q86.5 47.5 83.5 47.5")
    val sound = p("M22 62Q18.5 68.5 22 75M17 59.5Q12 68.5 17 77.5")
    val sparkle = p("M0 -4Q0 0 4 0Q0 0 0 4Q0 0 -4 0Q0 0 0 -4Z")
}

/** “Z” 字：左上角在 (x, y)，边长 s */
private fun zPath(x: Float, y: Float, s: Float) = Path().apply {
    moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s)
}

/** 记住这次打开应用后小猫在哪个时段打过招呼，切换页面回来不会重复说 */
private object CatMemory {
    var greeted: DayPart? = null
}

/**
 * 线条小猫：样子跟着时段走（早上、中午睁着眼，下午、晚上眯着眼，深夜趴着睡觉），每个时段第一次见面会说一句话；
 * 点一下做个小动作（被摸爽了、凶狠猫猫、伸懒腰、黏人猫猫、眨眼、甩尾巴、跳一下、喵一声），闲着时偶尔自己伸个懒腰；
 * 连点几下（每次 5～8 下，随机）会冒出一句悄悄话。
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
    var lastTap by remember { mutableFloatStateOf(-100f) }
    var taps by remember { mutableIntStateOf(0) }
    var goal by remember { mutableIntStateOf(Random.nextInt(5, 9)) }
    var bubble by remember { mutableStateOf<String?>(null) }
    var bubbleText by remember { mutableStateOf("") }
    var bubbleId by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf("") }
    val currentMessages by rememberUpdatedState(messages)

    fun say(text: String) {
        lastMessage = text
        bubbleText = text
        bubble = text
        bubbleId++
    }

    fun start(a: CatAct) {
        act = a
        actStart = clock.value
        actId++
    }

    LaunchedEffect(actId) {
        val a = act ?: return@LaunchedEffect
        delay((a.seconds * 1000).toLong())
        act = null
    }
    LaunchedEffect(bubbleId) {
        if (bubble == null) return@LaunchedEffect
        delay(3600)
        bubble = null
    }
    // 每个时段第一次见面，打个招呼
    LaunchedEffect(part) {
        if (CatMemory.greeted == part) return@LaunchedEffect
        CatMemory.greeted = part
        delay(800)
        say(catGreetings(part).random())
    }
    // 闲着的时候偶尔自己动一动（睡着时不动）
    LaunchedEffect(part) {
        if (part == DayPart.NIGHT) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(15_000, 25_000))
            if (act == null && clock.value - lastTap > 6f) start(listOf(CatAct.STRETCH, CatAct.TAIL, CatAct.WINK).random())
        }
    }

    val ink = Kongye.Ink2
    val bg = Kongye.Surface
    val pink = Color(0xFFF28B82)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "小猫" }
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastTap = clock.value
                    start(CatAct.entries.filter { it != act }.random())
                    taps++
                    if (taps >= goal) {
                        taps = 0
                        goal = Random.nextInt(5, 9)
                        say(currentMessages().filter { it != lastMessage }.random())
                    }
                },
        ) {
            val t = clock.value
            val a = act
            val p = if (a == null) 0f else ((t - actStart) / a.seconds).coerceIn(0f, 1f)
            val k = size.width / UNITS_W
            withTransform({
                scale(k, k, pivot = Offset.Zero)
                translate(-X0, -Y0)
            }) { drawCat(part, a, p, t, ink, bg, pink) }
        }
        // 小猫说的话：气泡贴在它脑袋左边，尖角指向它，不占布局
        Box(
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints(maxWidth = 228.dp.roundToPx()))
                val unit = DP_PER_UNIT.dp.toPx()
                val tipX = ((24f - X0) * unit).toInt()
                val tipY = ((70f - Y0) * unit).toInt()
                layout(0, 0) { placeable.place(tipX - placeable.width, tipY - placeable.height / 2) }
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

/** 气泡：圆角矩形和右侧的尖角是同一条轮廓，填充、描边、阴影一次画完，连接处不会多出一条竖线 */
private class BubbleShape(private val corner: Dp, private val tailWidth: Dp, private val tailHeight: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tw = with(density) { tailWidth.toPx() }
        val w = size.width - tw
        val h = size.height
        val r = min(with(density) { corner.toPx() }, h / 2)
        val th = min(with(density) { tailHeight.toPx() }, h - 2 * r)
        val cy = h / 2
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
            lineTo(w, cy - th / 2)
            lineTo(size.width, cy)
            lineTo(w, cy + th / 2)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

private val bubbleTail = 7.dp

@Composable
private fun CatBubble(text: String) {
    val shape = remember { BubbleShape(12.dp, bubbleTail, 11.dp) }
    Box(
        Modifier.widthIn(max = 220.dp).shadow(3.dp, shape).background(Kongye.Surface, shape)
            .border(0.6.dp, Kongye.Line2, shape)
            .padding(start = 12.dp, end = 12.dp + bubbleTail, top = 8.dp, bottom = 8.dp),
    ) { Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Kongye.Ink2) }
}

/** 画一帧小猫（设计稿坐标）。p 是当前小动作的进度（0～1），t 是动画时钟（秒） */
private fun DrawScope.drawCat(part: DayPart, act: CatAct?, p: Float, t: Float, ink: Color, bg: Color, pink: Color) {
    val wave = sin(p * PI.toFloat())
    fun line(w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // 背景小物件：早上一个慢慢转的太阳，晚上和深夜一弯月亮
    when (part) {
        DayPart.MORNING -> {
            drawCircle(ink, 5.5f, Offset(127f, 40f), style = line(2f))
            rotate(t * 18f, pivot = Offset(127f, 40f)) {
                for (i in 0 until 8) rotate(i * 45f, pivot = Offset(127f, 40f)) {
                    drawLine(ink, Offset(127f, 31.5f), Offset(127f, 28.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
            }
        }
        DayPart.EVENING, DayPart.NIGHT -> {
            drawPath(CatPaths.moon, ink, style = line(2f))
            drawPath(CatPaths.star, ink.copy(alpha = 0.35f + 0.45f * (0.5f + 0.5f * sin(t * 2.4f))), style = line(1.6f))
        }
        else -> {}
    }

    if (part == DayPart.NIGHT && act == null) {
        drawSleeping(t, ink, bg, ::line)
        return
    }

    // 整只猫的变形：跳、伸懒腰（后半身不动，往前抻长）、炸毛（变大一点并发抖）、被摸时一下下压扁、黏人时往左边靠
    val jump = if (act == CatAct.JUMP) -wave * 9f else 0f
    val shake = if (act == CatAct.FIERCE) sin(p * 70f) * 0.8f * wave else 0f
    val lean = if (act == CatAct.CLINGY) -6f * wave else 0f
    withTransform({
        translate(shake + (if (act == CatAct.CLINGY) -3f * wave else 0f), jump)
        rotate(lean, pivot = Offset(72f, GROUND))
        when (act) {
            CatAct.STRETCH -> scale(1f + 0.16f * wave, 1f - 0.1f * wave, pivot = Offset(112f, GROUND))
            CatAct.FIERCE -> scale(1f + 0.06f * wave, 1f + 0.06f * wave, pivot = Offset(72f, GROUND))
            CatAct.PET -> {
                val squish = abs(sin(p * PI.toFloat() * 3f)) * (1f - p * 0.3f)
                scale(1f + 0.02f * squish, 1f - 0.04f * squish, pivot = Offset(72f, GROUND))
            }
            else -> {}
        }
    }) {
        // 尾巴：平时轻轻摆，甩尾巴时大幅摆，炸毛时翘起来
        val swing = when (act) {
            CatAct.TAIL -> sin(p * PI.toFloat() * 4f) * 18f * (1f - p * 0.4f)
            CatAct.FIERCE -> -32f * wave
            CatAct.PET, CatAct.CLINGY -> sin(p * PI.toFloat() * 3f) * 10f
            else -> sin(t * 1.3f) * 3f
        }
        rotate(swing, pivot = Offset(112f, 97f)) {
            drawPath(CatPaths.tail, bg)
            drawPath(CatPaths.tail, ink, style = line(2.4f))
        }
        drawPath(CatPaths.body, bg)
        drawPath(CatPaths.body, ink, style = line(2.4f))
        drawPath(CatPaths.earLines, ink, style = line(1.6f))
        drawEyes(eyesFor(part, act, t), ink, bg, ::line)
        if (act == CatAct.PET || act == CatAct.CLINGY) drawPath(CatPaths.blush, pink.copy(alpha = 0.9f), style = line(1.3f))
        when (mouthFor(act)) {
            Mouth.DEFAULT -> drawPath(CatPaths.mouth, ink, style = line(1.6f))
            Mouth.OMEGA -> drawPath(CatPaths.mouthOmega, ink, style = line(1.6f))
            Mouth.MEOW -> {
                drawPath(CatPaths.mouthMeow, ink.copy(alpha = 0.25f))
                drawPath(CatPaths.mouthMeow, ink, style = line(1.6f))
            }
            Mouth.YAWN -> {
                val ry = 2.4f + 1.8f * wave
                drawOval(ink.copy(alpha = 0.2f), Offset(52.5f, 79.5f - ry), Size(6f, ry * 2))
                drawOval(ink, Offset(52.5f, 79.5f - ry), Size(6f, ry * 2), style = line(1.6f))
            }
            Mouth.HISS -> {
                drawPath(CatPaths.mouthHiss, ink, style = line(1.6f))
                drawPath(CatPaths.fangs, ink, style = line(1.2f))
            }
        }
        drawPath(CatPaths.whiskers, ink, style = line(1.7f))
        drawPath(CatPaths.paws, ink, style = line(2f))
        drawPath(CatPaths.leg, ink, style = line(2f))
    }

    // 小动作附带的符号（不跟着变形）
    when (act) {
        CatAct.PET -> {
            drawHeart(40f, 50f - 16f * p, 0.9f + 0.3f * p, pink.copy(alpha = 1f - p * p))
            if (p > 0.25f) {
                val q = (p - 0.25f) / 0.75f
                drawHeart(88f, 52f - 14f * q, 0.7f + 0.3f * q, pink.copy(alpha = 1f - q * q))
            }
        }
        CatAct.CLINGY -> {
            drawHeart(28f, 54f - 12f * p, 0.8f + 0.3f * p, pink.copy(alpha = 1f - p * p))
            val twinkle = 0.4f + 0.6f * abs(sin(p * PI.toFloat() * 3f))
            translate(22f, 76f) { scale(0.8f, pivot = Offset.Zero) { drawPath(CatPaths.sparkle, ink.copy(alpha = twinkle * wave)) } }
            translate(90f, 58f) { scale(0.6f, pivot = Offset.Zero) { drawPath(CatPaths.sparkle, ink.copy(alpha = twinkle * wave)) } }
        }
        CatAct.FIERCE -> scale(0.6f + 0.4f * min(1f, p * 3f), pivot = Offset(88f, 46f)) {
            drawPath(CatPaths.anger, Kongye.Danger.copy(alpha = wave), style = line(2f))
        }
        CatAct.MEOW -> translate(left = -p * 1.5f) { drawPath(CatPaths.sound, ink.copy(alpha = wave), style = line(1.6f)) }
        else -> {}
    }
}

private fun eyesFor(part: DayPart, act: CatAct?, t: Float): Eyes {
    val eyes = when (act) {
        CatAct.PET, CatAct.JUMP -> Eyes.HAPPY
        CatAct.FIERCE -> Eyes.ANGRY
        CatAct.STRETCH -> Eyes.SQUEEZE
        CatAct.CLINGY -> Eyes.SPARKLE
        CatAct.WINK -> Eyes.WINK
        CatAct.MEOW -> Eyes.CLOSED
        CatAct.TAIL -> Eyes.OPEN
        null -> if (part == DayPart.MORNING || part == DayPart.NOON) Eyes.OPEN else Eyes.CLOSED
    }
    // 睁着眼时隔几秒眨一下
    return if (eyes == Eyes.OPEN && t % 4.3f < 0.13f) Eyes.CLOSED else eyes
}

private fun mouthFor(act: CatAct?) = when (act) {
    CatAct.PET, CatAct.CLINGY -> Mouth.OMEGA
    CatAct.MEOW -> Mouth.MEOW
    CatAct.STRETCH -> Mouth.YAWN
    CatAct.FIERCE -> Mouth.HISS
    else -> Mouth.DEFAULT
}

private fun DrawScope.drawEyes(eyes: Eyes, ink: Color, bg: Color, line: (Float) -> Stroke) {
    when (eyes) {
        Eyes.OPEN -> { drawCatEye(45.5f, 8f, ink, bg, line); drawCatEye(65.5f, -8f, ink, bg, line) }
        Eyes.CLOSED -> drawPath(CatPaths.eyesClosed, ink, style = line(2.2f))
        Eyes.HAPPY -> drawPath(CatPaths.eyesHappy, ink, style = line(2.2f))
        Eyes.SQUEEZE -> drawPath(CatPaths.eyesSqueeze, ink, style = line(2.2f))
        Eyes.WINK -> { drawCatEye(45.5f, 8f, ink, bg, line); drawPath(CatPaths.eyeHappyRight, ink, style = line(2.2f)) }
        Eyes.ANGRY -> {
            drawCatEye(45.5f, 8f, ink, bg, line, pupil = 1.6f)
            drawCatEye(65.5f, -8f, ink, bg, line, pupil = 1.6f)
            drawPath(CatPaths.brows, ink, style = line(2.2f))
        }
        Eyes.SPARKLE -> for (cx in listOf(45.5f, 65.5f)) {
            drawCircle(ink, 4.2f, Offset(cx, 68.5f))
            drawCircle(bg, 1.4f, Offset(cx + 1.4f, 67f))
            drawCircle(bg, 0.7f, Offset(cx - 1.3f, 70f))
        }
    }
}

/** 2.svg 的猫眼：微微倾斜的椭圆，中间一道竖瞳 */
private fun DrawScope.drawCatEye(cx: Float, tilt: Float, ink: Color, bg: Color, line: (Float) -> Stroke, pupil: Float = 2.2f) {
    rotate(tilt, pivot = Offset(cx, 68.5f)) {
        drawOval(bg, Offset(cx - 6.2f, 68.5f - 3.8f), Size(12.4f, 7.6f))
        drawOval(ink, Offset(cx - 6.2f, 68.5f - 3.8f), Size(12.4f, 7.6f), style = line(1.6f))
    }
    drawLine(ink, Offset(cx, 65f), Offset(cx, 72f), strokeWidth = pupil, cap = StrokeCap.Round)
}

private fun DrawScope.drawHeart(x: Float, y: Float, s: Float, color: Color) {
    translate(x, y) { scale(s, pivot = Offset.Zero) { drawPath(CatPaths.heart, color) } }
}

/** 深夜：3.svg 趴着睡觉，身体轻轻起伏，尾巴慢慢摆，头顶飘着 Z */
private fun DrawScope.drawSleeping(t: Float, ink: Color, bg: Color, line: (Float) -> Stroke) {
    rotate(sin(t * 0.9f) * 2f, pivot = Offset(111f, 97f)) {
        drawPath(CatPaths.sleepTail, bg)
        drawPath(CatPaths.sleepTail, ink, style = line(2.4f))
    }
    scale(1f, 1f + 0.02f * sin(t * 1.6f), pivot = Offset(70f, GROUND)) {
        drawPath(CatPaths.sleepBody, bg)
        drawPath(CatPaths.sleepBody, ink, style = line(2.4f))
        drawPath(CatPaths.sleepEars, ink, style = line(1.6f))
        drawPath(CatPaths.sleepEyes, ink, style = line(2.2f))
        drawPath(CatPaths.sleepWhiskers, ink, style = line(1.7f))
    }
    drawPath(CatPaths.sleepPaws, ink, style = line(2f))
    drawPath(CatPaths.sleepLeg, ink, style = line(2f))
    for (i in 0 until 3) {
        val ph = (t * 0.42f + i / 3f) % 1f
        val s = 4f + i * 0.8f + ph * 2.2f
        drawPath(zPath(64f + ph * 16f, 58f - ph * 30f, s), ink.copy(alpha = sin(ph * PI.toFloat()) * 0.8f), style = line(1.6f))
    }
}

/** 每个时段第一次见面时说的话 */
fun catGreetings(part: DayPart): List<String> = when (part) {
    DayPart.MORNING -> listOf(
        "早呀，今天打算怎么过？",
        "早上好！先把今天最想做的一件事写下来吧",
        "睡饱了吗？伸个懒腰再出发",
        "早饭吃了吗？别空着肚子出门哦",
    )
    DayPart.NOON -> listOf(
        "中午啦，吃饭了吗？",
        "吃饱了才有力气写字",
        "午饭吃的什么？给我留点小鱼干",
        "吃完饭眯一会儿吧，我帮你看着",
    )
    DayPart.AFTERNOON -> listOf(
        "下午容易犯困，起来走两步吧",
        "喝口水，再接着忙",
        "今天的事做得怎么样啦？",
        "来点下午茶吗？我要猫条",
    )
    DayPart.EVENING -> listOf(
        "晚上好，今天辛苦啦",
        "今天有什么想记下来的吗？",
        "晚饭吃了吗？别饿着自己",
        "把今天的心情写下来吧",
    )
    DayPart.NIGHT -> listOf(
        "夜深了，早点睡觉吧",
        "再熬夜，我就要咬你了",
        "嘘……我先睡了，你也快去睡",
        "明天的事，明天再写也来得及",
    )
}

/** 连点小猫后冒出来的悄悄话：当前时段的话，再加上一些什么时候都能说的 */
fun catMessages(part: DayPart, words: Int, days: Int): List<String> {
    val w = String.format(Locale.US, "%,d", words)
    return catGreetings(part) + listOf(
        "喵～ 被你发现啦",
        if (words > 0) "你已经写下 $w 个字，我都记着呢" else "还一个字都没写呢，我在等你",
        "今天是我们相伴的第 $days 天",
        "别戳啦，毛都要被摸秃了",
        "摸头可以，摸肚子不行！",
        "偷偷告诉你：「关于」里的叶子也能点",
        "愿每一片叶子，都有字可落",
    )
}
