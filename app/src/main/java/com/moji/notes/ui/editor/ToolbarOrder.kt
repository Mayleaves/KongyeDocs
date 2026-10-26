package com.moji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.moji.notes.data.Repository
import com.moji.notes.ui.components.BottomSheet
import com.moji.notes.ui.components.HLine
import com.moji.notes.ui.components.MojiIcons
import com.moji.notes.ui.components.Toast
import com.moji.notes.ui.theme.Moji

private val rowHeight = 50.dp

/**
 * 自定义编辑器工具栏的顺序：按住右侧 ≡ 上下拖动。
 * 拖过半行就与相邻一项交换位置，松手后立即保存。
 */
@Composable
fun ToolbarOrderSheet(onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(Tool.ordered(Repository.toolbar.value)) }
    var dragging by remember { mutableStateOf<Tool?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    fun save() = Repository.setToolbar(order.map { it.name })

    BottomSheet(onDismiss, dragToDismiss = false) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自定义工具栏", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Moji.Ink)
                Spacer(Modifier.height(2.dp))
                Text("按住右侧 ≡ 上下拖动，调整键盘上方工具的顺序", fontSize = 12.sp, color = Moji.Sub)
            }
            Text(
                "恢复默认",
                Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    order = Tool.entries.toList()
                    save()
                    Toast.show("已恢复默认顺序")
                }.padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 14.sp, color = Moji.Ink2,
            )
        }
        HLine(color = Moji.Divider)
        Column(Modifier.fillMaxWidth()) {
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
                            .background(if (active) Moji.Bg2 else Moji.Surface)
                            .padding(start = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tool.icon, null, Modifier.size(22.dp), tint = Moji.Ink2)
                        Spacer(Modifier.width(16.dp))
                        Text(tool.label, Modifier.weight(1f), fontSize = 15.sp, color = Moji.Ink)
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
                            Icon(MojiIcons.DragHandle, "拖动调整顺序", Modifier.size(22.dp), tint = Moji.Icon2)
                        }
                    }
                }
            }
        }
        HLine(color = Moji.Divider)
        Box(
            Modifier.fillMaxWidth().height(54.dp).clickable { dismiss {} }.navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) { Text("完成", fontSize = 16.sp, color = Moji.Ink) }
    }
}
