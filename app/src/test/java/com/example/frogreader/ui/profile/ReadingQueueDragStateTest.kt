package com.example.frogreader.ui.profile

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ReadingQueueDragStateTest {
    private val list: LazyListState = mock()
    private val layout: LazyListLayoutInfo = mock()
    private val drag = ReadingQueueDragState(list)
    private val keys = listOf("a", "b", "c")

    init {
        whenever(list.layoutInfo).thenReturn(layout)
        whenever(layout.viewportStartOffset).thenReturn(0)
        whenever(layout.viewportEndOffset).thenReturn(600)
    }

    private fun row(key: String, index: Int, offset: Int, size: Int) = mock<LazyListItemInfo>().also {
        whenever(it.key).thenReturn(key)
        whenever(it.index).thenReturn(index)
        whenever(it.offset).thenReturn(offset)
        whenever(it.size).thenReturn(size)
    }

    private fun show(vararg items: LazyListItemInfo) {
        whenever(layout.visibleItemsInfo).thenReturn(items.toList())
    }

    @Test fun `unequal cards swap only after crossing adjacent center and track the finger after layout`() {
        show(row("a", 0, 0, 140), row("b", 1, 152, 80), row("c", 2, 244, 100))
        assertTrue(drag.start("a", 60f))
        drag.drag(100f)
        assertNull(drag.targetIndex(keys)) // dragged center 170; next center 192
        drag.drag(30f)
        assertEquals(1, drag.targetIndex(keys))
        assertEquals(130f, drag.translation("a"), 0f)
        // Optimistic data moved; stale layout must not trigger another swap.
        assertNull(drag.targetIndex(listOf("b", "a", "c")))
        show(row("b", 0, 0, 80), row("a", 1, 92, 140), row("c", 2, 244, 100))
        assertEquals(38f, drag.translation("a"), 0f)
        assertNull(drag.targetIndex(listOf("b", "a", "c")))
        drag.drag(100f)
        assertEquals(2, drag.targetIndex(listOf("b", "a", "c")))
    }

    @Test fun `dragging upwards selects previous row and stops at first`() {
        show(row("a", 0, 0, 100), row("b", 1, 112, 100))
        drag.start("b", 160f)
        drag.drag(-120f)
        assertEquals(0, drag.targetIndex(listOf("a", "b")))
        show(row("b", 0, 0, 100), row("a", 1, 112, 100))
        assertNull(drag.targetIndex(listOf("b", "a")))
    }

    @Test fun `edge scroll is proportional bounded and still works without new pointer movement`() {
        show(row("a", 0, 450, 100))
        drag.start("a", 570f)
        assertEquals(375f, drag.scrollSpeed(60f, 750f), 0f)
        assertEquals(375f, drag.scrollSpeed(60f, 750f), 0f)
        drag.drag(100f)
        assertEquals(750f, drag.scrollSpeed(60f, 750f), 0f)
        drag.drag(-370f)
        assertEquals(0f, drag.scrollSpeed(60f, 750f), 0f)
        drag.drag(-400f)
        assertEquals(-750f, drag.scrollSpeed(60f, 750f), 0f)
    }

    @Test fun `scrolling under a stationary finger preserves dragged viewport position`() {
        show(row("a", 0, 300, 100))
        drag.start("a", 350f)
        show(row("a", 0, 270, 100))
        assertEquals(300f, drag.top, 0f)
        assertEquals(30f, drag.translation("a"), 0f)
        drag.stop()
        assertNull(drag.id)
        assertEquals(0f, drag.translation("a"), 0f)
        assertNull(drag.targetIndex(keys))
    }
    @Test fun `composer and heading are never reorder targets`() {
        show(row("composer", 0, 0, 100), row("heading", 1, 112, 40), row("a", 2, 164, 100), row("b", 3, 276, 100))
        drag.start("a", 200f)
        drag.drag(-150f)
        assertNull(drag.targetIndex(listOf("a", "b"), leadingItems = 2))
        drag.drag(280f)
        assertEquals(1, drag.targetIndex(listOf("a", "b"), leadingItems = 2))
    }

    @Test fun `FAB queue with one heading reorders first and last book without targeting heading`() {
        show(row("heading", 0, 0, 40), row("a", 1, 52, 140), row("b", 2, 204, 100))
        assertTrue(drag.start("a", 100f))
        drag.drag(-150f)
        assertNull(drag.targetIndex(listOf("a", "b"), leadingItems = 1))
        drag.drag(290f)
        assertEquals(1, drag.targetIndex(listOf("a", "b"), leadingItems = 1))
        drag.stop()
        assertTrue(drag.start("b", 250f))
        drag.drag(-140f)
        assertEquals(0, drag.targetIndex(listOf("a", "b"), leadingItems = 1))
    }

    @Test fun `Dune stays under the finger when swapping above a shorter Frog card`() {
        show(row("never", 0, 0, 176), row("frog", 1, 188, 100), row("dune", 2, 300, 140))
        drag.start("dune", 350f)
        drag.drag(-140f)
        assertEquals(1, drag.targetIndex(listOf("never", "frog", "dune")))
        val heldTop = 300f + drag.translation("dune")
        assertEquals(160f, heldTop, 0f)
        // The new slot is measured before the next pointer event. Rendering must use
        // its new offset immediately, never the negative translation from the old slot.
        show(row("never", 0, 0, 176), row("dune", 1, 188, 140), row("frog", 2, 340, 100))
        assertEquals(heldTop, 188f + drag.translation("dune"), 0f)
        assertNull(drag.targetIndex(listOf("never", "dune", "frog")))
        // Scrolling during the same drag must not move the card away from the finger.
        show(row("never", 0, -25, 176), row("dune", 1, 163, 140), row("frog", 2, 315, 100))
        assertEquals(heldTop, 163f + drag.translation("dune"), 0f)
        drag.drag(-10f)
        assertEquals(150f, 163f + drag.translation("dune"), 0f)
    }

    @Test fun `grabbing a returning card starts from its painted position`() {
        show(row("dune", 0, 300, 140))
        assertTrue(drag.start("dune", handleY = 350f, visualTop = 230f))
        assertEquals(230f, 300f + drag.translation("dune"), 0f)
        drag.drag(12f)
        assertEquals(242f, 300f + drag.translation("dune"), 0f)
        show(row("dune", 0, 250, 140))
        assertEquals(242f, 250f + drag.translation("dune"), 0f)
    }

}
