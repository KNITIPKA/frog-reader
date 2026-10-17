package com.example.frogreader.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import com.example.frogreader.ui.components.SegmentPillDrag
import com.example.frogreader.ui.components.draggedSegmentPill
import com.example.frogreader.ui.components.segmentSlots
import com.example.frogreader.ui.components.segmentUnderPill
import com.example.frogreader.ui.components.segmentPillAtProgress
import com.example.frogreader.ui.components.segmentPillProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentedControlGeometryTest {
    private val slots = segmentSlots(300, listOf(1f, 1f, 1f), 0, false)

    @Test
    fun `pager progress and grab coordinates round trip in LTR and RTL`() {
        for (rtl in listOf(false, true)) {
            val navigation = segmentSlots(301, listOf(1f, 1f), 0, rtl)
            for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val pill = segmentPillAtProgress(navigation, progress, 60f)
                assertEquals(progress, segmentPillProgress(navigation, pill), 0.00001f)
            }
            assertEquals(navigation.first { it.index == 0 }.rect(60f), segmentPillAtProgress(navigation, -1f, 60f))
            assertEquals(navigation.first { it.index == 1 }.rect(60f), segmentPillAtProgress(navigation, 2f, 60f))
        }
    }

    @Test
    fun `grabbing a moving navigation pill continues from its visible location`() {
        val navigation = segmentSlots(300, listOf(1f, 1f), 0, false)
        val visible = segmentPillAtProgress(navigation, 0.4f, 60f)
        val gesture = SegmentPillDrag(navigation, visible, Offset(visible.right - 5f, 30f), 8f)
        val move = gesture.update(Offset(visible.right + 15f, 30f), pressed = true)
        assertNull(move.selection)
        assertEquals(visible.left + 20f, move.pill!!.left, 0.002f)
        assertEquals(1, gesture.update(Offset(300f, 30f), pressed = false).selection)
    }

    @Test
    fun `holding and crossing several options only commits on release`() {
        val gesture = SegmentPillDrag(slots, Rect(0f, 0f, 100f, 40f), Offset(50f, 20f), 8f)
        val held = gesture.update(Offset(50f, 20f), pressed = true)
        assertNull(held.selection)
        assertFalse(held.consume)
        val moved = gesture.update(Offset(240f, 20f), pressed = true)
        assertNull(moved.selection)
        assertTrue(moved.consume)
        assertFalse(moved.finished)
        val released = gesture.update(Offset(250f, 20f), pressed = false)
        assertEquals(2, released.selection)
        assertTrue(released.finished)
        assertNull(gesture.update(Offset(150f, 20f), pressed = false).selection)
    }

    @Test
    fun `vertical motion leaves scrolling alone and a tap is not a drag commit`() {
        val scroll = SegmentPillDrag(slots, Rect(0f, 0f, 100f, 40f), Offset(50f, 20f), 8f)
        val vertical = scroll.update(Offset(53f, 40f), pressed = true)
        assertTrue(vertical.finished)
        assertFalse(vertical.consume)
        assertNull(vertical.selection)
        val tap = SegmentPillDrag(slots, Rect(0f, 0f, 100f, 40f), Offset(50f, 20f), 8f)
        val released = tap.update(Offset(52f, 21f), pressed = false)
        assertTrue(released.finished)
        assertFalse(released.consume)
        assertNull(released.selection)
    }

    @Test
    fun `consumed gestures and additional fingers cancel without a selection`() {
        for (secondFinger in listOf(false, true)) {
            val gesture = SegmentPillDrag(slots, Rect(0f, 0f, 100f, 40f), Offset(50f, 20f), 8f)
            gesture.update(Offset(160f, 20f), pressed = true)
            val cancelled = gesture.update(
                Offset(180f, 20f), pressed = true,
                consumed = !secondFinger, otherPointerPressed = secondFinger,
            )
            assertTrue(cancelled.finished)
            assertFalse(cancelled.consume)
            assertNull(cancelled.selection)
            assertNull(gesture.update(Offset(250f, 20f), pressed = false).selection)
        }
    }

    @Test
    fun `weighted options preserve gaps and cover their measured pixel bounds`() {
        val weighted = segmentSlots(408, listOf(1f, 2f, 1f), 4, false)
        assertEquals(listOf(0f, 104f, 308f), weighted.map { it.left })
        assertEquals(listOf(100f, 304f, 408f), weighted.map { it.right })
        assertEquals(408f, weighted.last().right, 0f)
    }

    @Test
    fun `grab near an edge preserves the grip and does not follow the finger into the next option early`() {
        val pill = draggedSegmentPill(slots, 105f, 0.95f, 100f, 40f)
        assertEquals(10f, pill.left, 0.002f)
        assertEquals(105f, pill.left + pill.width * 0.95f, 0.002f)
        assertEquals(0, segmentUnderPill(slots, pill, 105f))
        val next = draggedSegmentPill(slots, 195f, 0.95f, 100f, 40f)
        assertEquals(1, segmentUnderPill(slots, next, 195f))
    }

    @Test
    fun `a drag adapts pill width while retaining the same grip in unequal options`() {
        val weighted = segmentSlots(408, listOf(1f, 2f, 1f), 4, false)
        val middle = draggedSegmentPill(weighted, 124f, 0.1f, 100f, 40f)
        assertEquals(104f, middle.left, 0.004f)
        assertEquals(200f, middle.width, 0.004f)
        assertEquals(124f, middle.left + middle.width * 0.1f, 0.004f)
        assertEquals(1, segmentUnderPill(weighted, middle, 124f))
    }

    @Test
    fun `release uses pill overlap and uses the finger to resolve an exact middle tie`() {
        val boundary = Rect(50f, 0f, 150f, 40f)
        assertEquals(0, segmentUnderPill(slots, boundary, 60f))
        assertEquals(1, segmentUnderPill(slots, boundary, 140f))
        assertEquals(0, segmentUnderPill(slots, Rect(49f, 0f, 149f, 40f), 149f))
        assertEquals(1, segmentUnderPill(slots, Rect(51f, 0f, 151f, 40f), 51f))
    }

    @Test
    fun `dragging far outside clamps to an end and a fast drag can cross several options`() {
        val left = draggedSegmentPill(slots, -1000f, 0.5f, 100f, 40f)
        val right = draggedSegmentPill(slots, 1000f, 0.5f, 100f, 40f)
        assertEquals(0f, left.left, 0.002f)
        assertEquals(300f, right.right, 0.002f)
        assertEquals(0, segmentUnderPill(slots, left, -1000f))
        assertEquals(2, segmentUnderPill(slots, right, 1000f))
    }

    @Test
    fun `RTL maps physical dragging to the correct logical option`() {
        val rtl = segmentSlots(300, listOf(1f, 1f, 1f), 0, true)
        assertEquals(listOf(2, 1, 0), rtl.map { it.index })
        val left = draggedSegmentPill(rtl, 50f, 0.5f, 100f, 40f)
        assertEquals(2, segmentUnderPill(rtl, left, 50f))
    }

    @Test
    fun `pixel rounding and invalid weights do not leave negative or missing bounds`() {
        val rounded = segmentSlots(101, listOf(1f, Float.NaN, 0f), 2, false)
        assertEquals(0f, rounded.first().left, 0f)
        assertEquals(101f, rounded.last().right, 0f)
        assertTrue(rounded.all { it.width >= 0f })
        assertTrue(segmentSlots(0, listOf(1f), 0, false).isEmpty())
        assertTrue(segmentSlots(100, emptyList(), 0, false).isEmpty())
    }
}
