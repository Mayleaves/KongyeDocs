package com.kongye.docs.ui

import com.kongye.docs.ui.theme.Kongye

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** 每次冷启动只播放一次 */
private object IntroState {
    var played = false
}

/** CSS 的 ease-out / ease / ease-in-out */
private val CssEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * Logo 的一笔（viewBox 0 0 64 64）：fill 是这一笔的墨迹形状，mask 是沿笔画走向的中线。
 * 动画时沿中线逐渐描出一条宽 width 的蒙版，只露出蒙版下面的墨迹，就像毛笔一笔写出来。
 */
private class Brush(fill: String, mask: String, val width: Float, val delay: Float, val duration: Float, val easing: Easing) {
    val fill: Path = PathParser().parsePathString(fill).toPath()
    val measure = PathMeasure().apply { setPath(PathParser().parsePathString(mask).toPath(), false) }
    val length = measure.length

    /** HTML 里 pathLength=100、stroke-dasharray=105，dashoffset 从 105 变到 0，所以露出的比例是 min(1, 1.05p) */
    fun shown(t: Float): Float {
        val p = ((t - delay) / duration).coerceIn(0f, 1f)
        return min(1f, 1.05f * easing.transform(p))
    }
}

/** 与 gemini-code-1792920621913.html 中的四笔、时间和缓动完全一致（单位：秒） */
private val strokes by lazy {
    listOf(
        Brush(
            "M 34.6 16.2 C 35.8 16.4 36.4 17.4 35.9 18.4 C 35.1 19.8 33.1 21.0 30.8 21.9 C 30.5 22.0 30.4 21.8 30.6 21.6 C 32.5 19.9 33.9 18.3 34.2 16.9 C 34.3 16.4 34.3 16.1 34.6 16.2 Z",
            "M 36.2 15.6 L 29.8 22.5",
            5f, 0.10f, 0.16f, CssEaseOut,
        ),
        Brush(
            "M 21.8 28.0 C 22.3 28.6 23.3 28.3 25.2 27.4 C 30.0 25.1 36.4 22.5 41.2 21.6 C 42.8 21.3 43.9 22.4 43.1 23.8 C 42.1 25.4 38.5 27.3 33.4 29.2 C 33.1 29.3 33.0 29.0 33.3 28.8 C 37.1 26.5 39.8 24.8 40.4 23.7 C 40.7 23.1 40.2 22.9 39.0 23.2 C 34.4 24.2 28.6 27.0 23.5 29.7 C 22.1 30.4 21.1 30.0 21.2 28.9 C 21.2 28.3 21.5 27.8 21.8 28.0 Z",
            "M 20.2 29.6 C 27.5 26.5 36.0 23.0 43.2 22.0 L 32.5 29.5",
            5f, 0.28f, 0.36f, CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f),
        ),
        Brush(
            "M 25.5 33.2 C 26.2 33.8 27.2 33.7 29.8 32.7 C 30.1 32.6 30.2 32.8 29.9 33.1 C 27.9 35.0 26.7 35.7 25.8 35.2 C 25.1 34.8 24.9 33.7 25.2 33.2 C 25.3 33.0 25.4 33.1 25.5 33.2 Z",
            "M 24.6 32.6 L 25.8 35.2 L 30.8 32.4",
            5f, 0.66f, 0.15f, CssEaseOut,
        ),
        Brush(
            "M 34.4 30.6 C 35.6 31.0 36.0 31.8 35.3 32.8 C 33.6 35.0 31.8 36.8 31.2 38.1 C 30.8 39.0 31.5 39.6 33.0 40.0 C 34.8 40.5 35.0 42.0 33.6 44.6 C 32.0 47.4 30.2 49.5 29.5 51.2 C 28.9 52.5 29.8 53.1 31.8 52.7 C 38.8 51.4 45.4 45.0 47.8 34.3 C 49.7 25.2 48.8 17.0 46.9 12.8 C 46.1 11.0 44.0 10.6 41.6 11.7 C 36.6 13.9 29.0 16.4 22.2 23.2 C 16.2 29.2 14.8 38.5 17.7 46.2 C 19.0 49.6 21.1 51.0 23.3 51.2 C 25.4 51.4 26.0 52.6 25.3 54.8 C 24.6 57.0 23.8 59.3 23.1 61.1 C 22.9 61.5 22.4 61.4 21.7 60.3 C 21.1 59.3 21.2 58.3 22.0 56.7 C 22.8 55.1 23.7 53.8 23.9 53.0 C 24.1 52.6 23.6 52.6 22.6 52.6 C 19.8 52.3 17.4 50.5 16.0 46.7 C 13.2 38.4 14.7 28.4 21.2 21.9 C 28.2 14.9 36.0 12.2 41.2 9.9 C 44.3 8.5 47.4 9.1 48.6 11.8 C 50.8 16.4 51.8 25.2 49.7 34.8 C 47.3 46.2 40.0 53.1 31.9 54.5 C 28.4 55.1 26.8 53.5 27.8 50.9 C 28.7 48.7 30.5 46.6 32.0 43.9 C 32.9 42.3 32.7 41.6 31.4 41.3 C 29.2 40.8 28.4 39.5 29.3 37.5 C 30.2 35.5 32.2 33.2 34.0 30.8 C 34.2 30.6 34.3 30.5 34.4 30.6 Z",
            "M 35.2 30.0 C 33.5 33.5 31.0 36.4 30.2 38.2 C 29.6 39.5 30.4 40.4 32.2 40.8 C 33.8 41.2 34.0 42.4 32.8 44.5 C 31.2 47.4 29.4 49.5 28.7 51.4 C 28.1 53.2 29.4 54.0 31.8 53.6 C 39.5 52.2 46.5 45.5 48.8 34.5 C 50.8 25.0 49.8 16.5 47.8 12.2 C 46.8 10.0 44.2 9.6 41.5 10.8 C 36.5 13.0 28.5 15.5 21.5 22.5 C 15.2 28.8 13.8 38.5 16.8 46.5 C 18.2 50.2 20.6 51.8 23.0 52.0 C 24.6 52.1 25.0 52.8 24.4 54.6 C 23.8 56.6 23.0 59.0 22.3 61.8",
            3.4f, 0.82f, 1.03f, CubicBezierEasing(0.35f, 0f, 0.2f, 1f),
        ),
    )
}

/** 写完后叶子随风轻摆：1.85 秒起，4 秒一个来回，绕叶柄 (22.8, 61) 转 0→2°、上移 0.5 */
private fun breeze(t: Float): Float {
    if (t <= 1.85f) return 0f
    val u = ((t - 1.85f) / 4f) % 2f
    return CssEaseInOut.transform(if (u <= 1f) u else 2f - u)
}

private val Ink get() = if (Kongye.dark) Color(0xFFEDEDED) else Color(0xFF111111)

/** 空叶 Logo 的四笔墨迹（viewBox 64），给“关于”页和彩蛋里的落叶用 */
val leafPaths: List<Path> by lazy { strokes.map { it.fill } }

/** 静态的空叶 Logo */
@Composable
fun LeafLogo(modifier: Modifier, color: Color = Ink) {
    Canvas(modifier) {
        val k = size.minDimension / 64f
        withTransform({ scale(k, k, pivot = Offset.Zero) }) { leafPaths.forEach { drawPath(it, color) } }
    }
}

/**
 * 启动动画：照搬 HTML 里的“毛笔书写”效果——四笔依次写出 Logo，1.4 秒起“空叶文档”淡入上浮，
 * 写完后叶子轻轻摆动；稍停后整体淡出，过渡到首页。
 */
@Composable
fun IntroOverlay() {
    if (IntroState.played) return
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    var t by remember { mutableFloatStateOf(0f) }
    val exit = remember { Animatable(0f) }
    val segment = remember { Path() }
    val layerPaint = remember { Paint() }
    LaunchedEffect(Unit) {
        val clock = launch {
            val start = withFrameNanos { it }
            while (isActive) withFrameNanos { t = (it - start) / 1_000_000_000f }
        }
        // 书写 1.85 秒、文字淡入到 2.0 秒，再停一下
        delay(2300)
        exit.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        clock.cancel()
        IntroState.played = true
        visible = false
    }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Kongye.Surface)
            .semantics { contentDescription = "启动画面，点击跳过" }
            .clickable(remember { MutableInteractionSource() }, indication = null) { IntroState.played = true; visible = false }
            // 整体比正中略微靠上（底部多留 150dp，内容上移约 75dp）
            .padding(bottom = 150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.graphicsLayer {
                val s = 1f + 0.08f * exit.value
                scaleX = s
                scaleY = s
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(160.dp)) {
                val k = size.minDimension / 64f
                val sway = breeze(t)
                withTransform({
                    scale(k, k, pivot = Offset.Zero)
                    rotate(2f * sway, pivot = Offset(22.8f, 61f))
                    translate(0f, -0.5f * sway)
                }) {
                    strokes.forEach { brush ->
                        val shown = brush.shown(t)
                        if (shown <= 0f) return@forEach
                        segment.reset()
                        brush.measure.getSegment(0f, brush.length * shown, segment, true)
                        // 先画白色蒙版笔触，再用 SrcIn 画墨迹：只露出蒙版覆盖到的部分
                        drawContext.canvas.saveLayer(Rect(-4f, -4f, 68f, 68f), layerPaint)
                        drawPath(segment, Color.White, style = Stroke(brush.width, cap = StrokeCap.Butt, join = StrokeJoin.Round))
                        drawPath(brush.fill, Ink, blendMode = BlendMode.SrcIn)
                        drawContext.canvas.restore()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val a = CssEase.transform(((t - 1.4f) / 0.6f).coerceIn(0f, 1f))
            Text(
                "空叶文档",
                Modifier.graphicsLayer {
                    alpha = a
                    translationY = (1f - a) * 4.dp.toPx()
                },
                fontSize = 15.sp, letterSpacing = 4.sp, color = Kongye.Ink,
            )
        }
    }
}
