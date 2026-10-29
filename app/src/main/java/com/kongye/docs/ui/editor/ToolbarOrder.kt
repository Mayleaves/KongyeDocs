package com.kongye.docs.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kongye.docs.data.Repository
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.theme.Kongye

private val rowHeight = 50.dp

/**
 * 自定义编辑器工具栏的顺序：按住右侧 ≡ 上下拖动。
 * 拖过半行就与相邻一项交换位置，松手后立即保存。
 */
@Composable
fun ToolbarOrderSheet(onDismiss: () -> Unit) {
    BottomSheet(onDismiss,dragToDismiss=false) { dismiss ->
        ToolbarOrderContent(onDone={ dismiss {} })
    }
}

/** Editor customization occupies the same space as the other keyboard tools. */
@Composable
fun ToolbarOrderPanel(height: Dp,onDone: ()->Unit) {
    ToolbarOrderContent(Modifier.height(height),embedded=true,onDone=onDone)
}

@Composable
private fun ToolbarOrderContent(modifier: Modifier=Modifier,embedded: Boolean=false,onDone: ()->Unit) {
    val originalOrder = remember { Tool.ordered(Repository.toolbar.value) }
    var order by remember { mutableStateOf(originalOrder) }
    var dragging by remember { mutableStateOf<Tool?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    fun save() = Repository.setToolbar(order.map { it.name })

    fun restoreDefault() {
        order = Tool.entries.toList()
        save()
        Toast.done("已恢复默认顺序")
    }

    Column(modifier.fillMaxWidth().background(Kongye.Surface)
        .then(if (embedded) Modifier.semantics { contentDescription = "自定义工具栏面板" } else Modifier)) {
        if (!embedded) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自定义工具栏", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kongye.Ink)
                Spacer(Modifier.height(2.dp))
                Text("按住右侧 ≡ 上下拖动，调整键盘上方工具的顺序", fontSize = 12.sp, color = Kongye.Sub)
            }
            Text(
                "恢复默认",
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { restoreDefault() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 14.sp, color = Kongye.Ink2,
            )
        }
        HLine(color = Kongye.Divider)
        }
        Column(Modifier.fillMaxWidth().then(if(embedded) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier)) {
            order.forEach { tool ->
                key(tool) {
                    val active = dragging == tool
                    Row(
                        Modifier.fillMaxWidth().height(rowHeight)
                            .zIndex(if (active) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (active) offset else 0f
                                shadowElevation = if (active) 12f else 0f
                            }
                            .background(if (active) Kongye.Bg2 else Kongye.Surface)
                            .padding(start = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tool.icon, null, Modifier.size(22.dp), tint = Kongye.Ink2)
                        Spacer(Modifier.width(16.dp))
                        Text(tool.label, Modifier.weight(1f), fontSize = 15.sp, color = Kongye.Ink)
                        Box(
                            Modifier.width(64.dp).fillMaxHeight().pointerInput(tool) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = tool
                                        offset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val i = order.indexOf(tool)
                                        if (offset > rowPx / 2 && i < order.lastIndex) {
                                            order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                            offset -= rowPx
                                        } else if (offset < -rowPx / 2 && i > 0) {
                                            order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                            offset += rowPx
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = null
                                        offset = 0f
                                        save()
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        offset = 0f
                                        save()
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KongyeIcons.DragHandle, "拖动${tool.label}", Modifier.size(22.dp), tint = Kongye.Icon2)
                        }
                    }
                }
            }
        }
        HLine(color = Kongye.Divider)
        if (embedded) {
            Row(Modifier.fillMaxWidth().height(54.dp)) {
                listOf<Pair<String, () -> Unit>>(
                    "取消" to { order = originalOrder; save(); onDone() },
                    "恢复默认" to { restoreDefault() },
                    "完成" to { save(); onDone() },
                ).forEach { (label, action) ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = action),
                        contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 16.sp, color = Kongye.Ink)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(54.dp).clickable(onClick=onDone).navigationBarsPadding(),
                contentAlignment = Alignment.Center) {
                Text("完成", fontSize = 16.sp, color = Kongye.Ink)
            }
        }
    }
}
