package com.moji.notes.ui.home

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moji.notes.data.Item

/** 列表 / 网格里一个可见条目的位置（容器坐标） */
internal class Slot(val key: Any, val index: Int, val pos: Offset, val size: Size) {
    /** 列表只比较纵向，网格还要比较横向 */
    fun contains(p: Offset, grid: Boolean) =
        p.y >= pos.y && p.y < pos.y + size.height && (!grid || (p.x >= pos.x && p.x < pos.x + size.width))
}

/**
 * 长按拖动排序的状态。拖动中条目按临时顺序 order 显示；
 * 被拖的条目始终画在 start + delta（开始拖动时的位置加上手指移动的距离），不受换位和自动滚动影响。
 */
internal class ReorderState {
    var id by mutableStateOf<String?>(null)
        private set
    var order by mutableStateOf<List<Pair<Item, String>>?>(null)
        private set

    /** 手指还按着；松手后到条目滑回原位之前为 false，但 id 仍在 */
    var dragging by mutableStateOf(false)
        private set
    var start = Offset.Zero
        private set
    var delta by mutableStateOf(Offset.Zero)

    /** 手指当前位置，靠近上下边缘时自动滚动 */
    var pointer = Offset.Zero

    fun begin(id: String, at: Offset, moved: Offset, entries: List<Pair<Item, String>>) {
        this.id = id
        order = entries
        start = at
        delta = moved
        dragging = true
    }

    /**
     * 手指移动 d 后，被拖条目的中心落到别的条目上就和它换位。
     * beforeSwap 在换位前调用（列表用它保持滚动位置）。
     */
    fun moveBy(d: Offset, slots: List<Slot>, grid: Boolean, beforeSwap: (Slot, Slot) -> Unit) {
        delta += d
        val id = id ?: return
        val order = order ?: return
        val cur = slots.firstOrNull { it.key == id } ?: return
        val center = start + delta + Offset(cur.size.width / 2, cur.size.height / 2)
        val target = slots.firstOrNull { s -> s.key != id && order.any { it.first.id == s.key } && s.contains(center, grid) } ?: return
        val from = order.indexOfFirst { it.first.id == id }
        val to = order.indexOfFirst { it.first.id == target.key }
        // 上一次换位后布局还没更新时，屏幕上的前后关系和临时顺序对不上，等布局更新后再换
        if (from < 0 || to < 0 || (target.index > cur.index) != (to > from)) return
        beforeSwap(cur, target)
        this.order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 被拖条目要画在离它布局位置多远的地方 */
    fun offsetFrom(slot: Slot?) = if (slot == null) Offset.Zero else start + delta - slot.pos

    fun release() { dragging = false }

    fun clear() {
        id = null
        order = null
        dragging = false
        delta = Offset.Zero
    }
}

/** 正在拖动的条目：浮在其他条目之上，带阴影，跟着手指走 */
internal fun Modifier.lifted(state: ReorderState, slot: () -> Slot?, shape: Shape, background: Color): Modifier =
    zIndex(1f)
        .graphicsLayer {
            val t = state.offsetFrom(slot())
            translationX = t.x
            translationY = t.y
            scaleX = 1.02f
            scaleY = 1.02f
            shadowElevation = 10.dp.toPx()
            this.shape = shape
        }
        .background(background, shape)
