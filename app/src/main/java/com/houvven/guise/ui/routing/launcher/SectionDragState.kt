package com.houvven.guise.ui.routing.launcher

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class HeaderBounds(val top: Float, val height: Float) {
    val center: Float get() = top + height / 2f
}

/** 指针只积累位移，帧循环负责换位，重新布局负责补偿基准坐标。 */
@Stable
internal class SectionDragState {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    val liveOrder = mutableStateListOf<String>()

    private val bounds = mutableMapOf<String, HeaderBounds>()
    private var originalOrder: List<String> = emptyList()
    private var pointerDeltaY = 0f

    val isDragging: Boolean get() = draggingKey != null
    val draggingBoundsTop: Float? get() = draggingKey?.let(bounds::get)?.top

    fun begin(key: String, currentOrder: List<String>) {
        if (key !in currentOrder) return
        originalOrder = currentOrder.toList()
        liveOrder.clear()
        liveOrder.addAll(currentOrder)
        bounds.keys.retainAll(currentOrder.toSet())
        draggingKey = key
        offsetY = 0f
        pointerDeltaY = 0f
    }

    fun dragBy(deltaY: Float) {
        if (isDragging && deltaY.isFinite()) pointerDeltaY += deltaY
    }

    fun measure(key: String, baselineTop: Float, height: Float) {
        if (!baselineTop.isFinite() || !height.isFinite() || height <= 0f) return
        val previous = bounds.put(key, HeaderBounds(baselineTop, height))
        if (key == draggingKey && previous != null) {
            // 排序、折叠和滚动都可能移动基准位置，只在实测变化时补偿一次。
            offsetY += previous.top - baselineTop
        }
    }

    fun tick() {
        val key = draggingKey ?: return
        offsetY += pointerDeltaY
        pointerDeltaY = 0f
        val from = liveOrder.indexOf(key)
        val dragged = bounds[key] ?: return
        if (from < 0) return

        // 换位后的下一次布局尚未回写时，旧坐标不能再次参与排序。
        val measured = liveOrder.mapNotNull(bounds::get)
        if (measured.zipWithNext().any { (left, right) -> left.top >= right.top }) return

        val center = dragged.center + offsetY
        var to = from
        if (center > dragged.center) {
            for (index in from + 1 until liveOrder.size) {
                val candidate = bounds[liveOrder[index]] ?: continue
                if (center >= candidate.center) to = index
            }
        } else if (center < dragged.center) {
            for (index in from - 1 downTo 0) {
                val candidate = bounds[liveOrder[index]] ?: continue
                if (center <= candidate.center) to = index
            }
        }
        if (to != from) {
            liveOrder.removeAt(from)
            // to 是换位后的最终索引；向下拖过目标时应放在目标之后。
            liveOrder.add(to, key)
        }
    }

    fun finish(): List<String>? {
        // 松手可能早于下一帧，最后一段位移也必须被消费。
        if (pointerDeltaY != 0f) tick()
        val order = liveOrder.toList().takeIf { isDragging && it != originalOrder }
        reset()
        return order
    }

    fun cancel() = reset()

    private fun reset() {
        draggingKey = null
        offsetY = 0f
        pointerDeltaY = 0f
        liveOrder.clear()
        originalOrder = emptyList()
    }
}
