package com.kongye.docs.ui.vault

import android.icu.text.AlphabeticIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.ui.theme.Kongye
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

internal object VaultAlphabet {
    val letters=('A'..'Z').map { it.toString() }+"#"
    private val index=AlphabeticIndex<VaultEntry>(Locale.CHINA).addLabels(Locale.ENGLISH).buildImmutableIndex()
    fun key(name: String): String {
        val label=index.getBucket(index.getBucketIndex(name.trim())).label
        return label.takeIf { it.length==1 && it[0] in 'A'..'Z' } ?: "#"
    }
}

@Composable internal fun VaultIndexBar(available: Set<String>,onDragging: (Boolean) -> Unit,descending: Boolean=false,onJump: (String) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableIntStateOf(0) }
    val latestJump by rememberUpdatedState(onJump)
    val latestAvailable by rememberUpdatedState(available)
    val latestDragging by rememberUpdatedState(onDragging)
    val letters=remember(descending) { if(descending) VaultAlphabet.letters.reversed() else VaultAlphabet.letters }
    val expansion by animateFloatAsState(if(dragging) 1f else 0f,spring(dampingRatio=1f,stiffness=550f),label="index-wave")
    val following by animateFloatAsState(position,spring(dampingRatio=1f,stiffness=1400f),label="index-finger")
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxHeight().width(104.dp)) {
        val barHeight=minOf(maxHeight*.92f,560.dp)
        val rowPx=with(density) { barHeight.toPx()/letters.size }
        val topPx=with(density) { ((maxHeight-barHeight)/2).toPx() }
        val wavePx=with(density) { 36.dp.toPx() }
        Column(Modifier.align(Alignment.CenterEnd).padding(end=12.dp).width(28.dp).height(barHeight)
            .semantics { contentDescription="密码字母滑动索引" }
            .pointerInput(descending) {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    down.consume()
                    var previous: String?=null
                    fun select(y: Float) {
                        val index=(y/size.height*letters.size).toInt().coerceIn(letters.indices)
                        val letter=letters[index]
                        selected=index
                        position=(y/size.height*letters.size-.5f).coerceIn(0f,letters.lastIndex.toFloat())
                        val target=if(letter in latestAvailable) letter else letters.drop(index).firstOrNull { it in latestAvailable }
                        if(target!=null && target!=previous) { latestJump(target); previous=target }
                    }
                    position=(down.position.y/size.height*letters.size-.5f).coerceIn(0f,letters.lastIndex.toFloat())
                    dragging=true
                    latestDragging(true)
                    try {
                        select(down.position.y)
                        do {
                            val event=awaitPointerEvent()
                            val change=event.changes.firstOrNull { it.id==down.id } ?: break
                            if(change.pressed) select(change.position.y)
                            change.consume()
                        } while(change.pressed)
                    } finally { dragging=false; latestDragging(false) }
                }
            },horizontalAlignment=Alignment.CenterHorizontally) {
            letters.forEachIndexed { index,letter -> Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                val distance=(abs(index-following)/4f).coerceAtMost(1f)
                val bell=cos(distance*Math.PI.toFloat()/2f).let { it*it }*expansion
                translationX=-wavePx*bell
                scaleX=1f+.55f*bell; scaleY=scaleX
                alpha=if(index==selected) 1f-expansion else 1f
            },contentAlignment=Alignment.Center) {
                Text(letter,fontSize=10.sp,color=if(letter in available) Kongye.Text2 else Kongye.Text4)
            } }
        }
        Box(Modifier.offset {
            val centerX=with(density) { 78.dp.toPx() }-wavePx*expansion
            val radius=with(density) { 16.dp.toPx() }
            IntOffset((centerX-radius).roundToInt(),(topPx+(following+.5f)*rowPx-radius).roundToInt())
        }.size(32.dp).graphicsLayer {
            alpha=expansion; scaleX=.65f+.35f*expansion; scaleY=scaleX
        }.clip(RoundedCornerShape(16.dp)).background(Kongye.Primary),contentAlignment=Alignment.Center) {
            Text(letters[selected],fontSize=17.sp,color=Color.White)
        }
    }
}
