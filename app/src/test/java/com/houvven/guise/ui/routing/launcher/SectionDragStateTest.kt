package com.houvven.guise.ui.routing.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SectionDragStateTest {
    private val order = listOf("A", "B", "C")

    private fun dragging(key: String = "A") = SectionDragState().apply {
        begin(key, order)
        measureOrder(order)
    }

    private fun SectionDragState.measureOrder(keys: List<String>, scroll: Float = 0f) {
        keys.forEachIndexed { index, key -> measure(key, index * 64f - scroll, 60f) }
    }

    @Test
    fun stationaryDragDoesNotReorderOrPersist() {
        val state = dragging()
        repeat(3) { state.tick() }
        assertEquals(order, state.liveOrder.toList())
        assertNull(state.finish())
    }

    @Test
    fun movementBelowNextHeaderCenterDoesNotReorder() {
        val state = dragging()
        state.dragBy(20f)
        state.tick()
        assertEquals(order, state.liveOrder.toList())
        assertEquals(20f, state.offsetY, 0.01f)
        assertNull(state.finish())
    }

    @Test
    fun draggingPastLastHeaderStaysInsideListBounds() {
        val state = dragging()
        state.dragBy(200f)
        state.tick()
        assertEquals(listOf("B", "C", "A"), state.liveOrder.toList())
        assertEquals(listOf("B", "C", "A"), state.finish())
    }

    @Test
    fun draggingAboveFirstHeaderKeepsVisualPositionAfterLayout() {
        val state = dragging("C")
        state.dragBy(-130f)
        state.tick()
        val moved = listOf("C", "A", "B")
        assertEquals(moved, state.liveOrder.toList())
        state.measureOrder(moved)
        assertEquals(-2f, state.offsetY, 0.01f)
        repeat(3) { state.tick() }
        assertEquals(moved, state.liveOrder.toList())
    }

    @Test
    fun reversingToOriginalOrderDoesNotPersist() {
        val state = dragging()
        state.dragBy(70f)
        state.tick()
        val moved = listOf("B", "A", "C")
        assertEquals(moved, state.liveOrder.toList())
        state.measureOrder(moved)
        state.dragBy(-70f)
        state.tick()
        assertEquals(order, state.liveOrder.toList())
        assertNull(state.finish())
    }

    @Test
    fun downwardMoveAccountsForRemovalShift() {
        val state = dragging()
        // A 头中心 30，越过 B 中心 94：A 应插到 B 之后，而不是 B 的位置。
        state.dragBy(70f)
        state.tick()
        assertEquals(listOf("B", "A", "C"), state.liveOrder.toList())
        state.measureOrder(listOf("B", "A", "C"))
        // 继续下移越过 C 中心 158：A 到末尾，且不得越界。
        state.dragBy(70f)
        state.tick()
        assertEquals(listOf("B", "C", "A"), state.liveOrder.toList())
    }

    @Test
    fun scrollingLayoutKeepsDraggedHeaderUnderPointer() {
        val state = dragging("B")
        state.dragBy(10f)
        state.tick()
        state.measureOrder(order, scroll = 8f)
        assertEquals(18f, state.offsetY, 0.01f)
        assertEquals(74f, state.draggingBoundsTop!! + state.offsetY, 0.01f)
    }

    @Test
    fun pendingLayoutCannotRepeatTheSameMove() {
        val state = dragging()
        state.dragBy(70f)
        state.tick()
        repeat(5) { state.tick() }
        assertEquals(listOf("B", "A", "C"), state.liveOrder.toList())
        state.measureOrder(state.liveOrder.toList())
        repeat(5) { state.tick() }
        assertEquals(listOf("B", "A", "C"), state.liveOrder.toList())
    }

    @Test
    fun releaseConsumesTheLastMovementBeforeNextFrame() {
        val state = dragging()
        state.dragBy(200f)
        assertEquals(listOf("B", "C", "A"), state.finish())
    }

    @Test
    fun centersAreInclusiveInBothDirections() {
        val state = dragging()
        state.dragBy(64f)
        state.tick()
        assertEquals(listOf("B", "A", "C"), state.liveOrder.toList())
        state.measureOrder(state.liveOrder.toList())
        state.dragBy(-64f)
        state.tick()
        assertEquals(order, state.liveOrder.toList())
        assertNull(state.finish())
    }

    @Test
    fun cancelClearsDragWithoutReturningAnOrder() {
        val state = dragging()
        state.dragBy(200f)
        state.tick()
        state.cancel()
        assertFalse(state.isDragging)
        assertEquals(0f, state.offsetY, 0.01f)
        assertNull(state.finish())
    }
}
