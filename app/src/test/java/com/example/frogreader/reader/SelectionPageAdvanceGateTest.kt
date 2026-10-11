package com.example.frogreader.reader

import androidx.compose.ui.geometry.Offset
import com.example.frogreader.ui.reader.selection.SelectionHitRules
import com.example.frogreader.ui.reader.selection.SelectionPageAdvanceGate
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionPageAdvanceGateTest {
    private val width = 400f
    private val hold = SelectionPageAdvanceGate.HOLD_MILLIS

    private fun gate(x: Float = 200f) = SelectionPageAdvanceGate(
        initialPoint = Offset(x, 200f),
        bandPx = SelectionHitRules.PAGE_EDGE_BAND.value,
        minTravelPx = SelectionHitRules.PAGE_ADVANCE_MIN_TRAVEL.value,
        exitSlopPx = 8f,
    )

    @Test
    fun `long pressing a word at either edge never turns a page`() {
        for (x in listOf(8f, 392f)) {
            val gate = gate(x)
            assertEquals(0, gate.direction(Offset(x, 200f), width, 0L))
            assertEquals(0, gate.direction(Offset(x, 200f), width, hold * 10))
        }
    }

    @Test
    fun `selecting a paragraph vertically at the margin does not turn`() {
        for (x in listOf(8f, 392f)) {
            val gate = gate(x)
            for (line in 0..20) {
                assertEquals(0, gate.direction(Offset(x, 200f + line * 20f), width, line * hold))
            }
        }
    }

    @Test
    fun `the old wide edge band is safe for first and last words`() {
        for (x in listOf(24f, 25f, 32f, 40f, 360f, 368f, 375f, 376f)) {
            val gate = gate()
            assertEquals(0, gate.direction(Offset(x, 200f), width, 0L))
            assertEquals(0, gate.direction(Offset(x, 200f), width, hold * 10))
        }
    }

    @Test
    fun `moving to either outer edge requires a full hold`() {
        for ((x, expected) in listOf(8f to -1, 392f to 1)) {
            val gate = gate()
            val point = Offset(x, 200f)
            assertEquals(0, gate.direction(point, width, 100L))
            assertEquals(0, gate.direction(point, width, 100L + hold - 1))
            assertEquals(expected, gate.direction(point, width, 100L + hold))
        }
    }

    @Test
    fun `small sideways corrections of a margin word do not arm a turn`() {
        val gate = gate(392f)
        assertEquals(0, gate.direction(Offset(395f, 200f), width, 0L))
        assertEquals(0, gate.direction(Offset(397f, 200f), width, hold * 10))
    }

    @Test
    fun `a handle already at either edge needs only a small outward drag`() {
        for ((start, end, expected) in listOf(Triple(8f, 2f, -1), Triple(392f, 398f, 1))) {
            val gate = gate(start)
            val point = Offset(end, 200f)
            assertEquals(0, gate.direction(point, width, 0L))
            assertEquals(0, gate.direction(point, width, hold - 1))
            assertEquals(expected, gate.direction(point, width, hold))
        }
    }

    @Test
    fun `a drag starting at the edge can move inward and return to turn`() {
        val gate = gate(392f)
        assertEquals(0, gate.direction(Offset(300f, 200f), width, 100L))
        assertEquals(0, gate.direction(Offset(392f, 200f), width, 200L))
        assertEquals(1, gate.direction(Offset(392f, 200f), width, 200L + hold))
    }

    @Test
    fun `leaving the edge cancels the pending turn`() {
        val gate = gate()
        val edge = Offset(392f, 200f)
        assertEquals(0, gate.direction(edge, width, 0L))
        assertEquals(0, gate.direction(Offset(360f, 200f), width, hold - 1))
        assertEquals(0, gate.direction(edge, width, hold))
        assertEquals(0, gate.direction(edge, width, hold * 2 - 1))
        assertEquals(1, gate.direction(edge, width, hold * 2))
    }

    @Test
    fun `dragging deeper into either margin and along lines keeps the original deadline`() {
        for (expected in listOf(-1, 1)) {
            val gate = gate()
            fun point(distanceFromEdge: Float, y: Float) =
                Offset(if (expected < 0) distanceFromEdge else width - distanceFromEdge, y)

            assertEquals(0, gate.direction(point(20f, 200f), width, 0L))
            assertEquals(0, gate.direction(point(13f, 220f), width, 100L))
            assertEquals(0, gate.direction(point(5f, 245f), width, 200L))
            assertEquals(0, gate.direction(point(1f, 260f), width, hold - 1))
            assertEquals(expected, gate.direction(point(1f, 280f), width, hold))
        }
    }

    @Test
    fun `small movements across the entry boundary do not restart an armed hold`() {
        for (expected in listOf(-1, 1)) {
            val gate = gate()
            fun point(distanceFromEdge: Float) =
                Offset(if (expected < 0) distanceFromEdge else width - distanceFromEdge, 200f)

            assertEquals(0, gate.direction(point(23f), width, 0L))
            assertEquals(0, gate.direction(point(25f), width, 100L))
            assertEquals(0, gate.direction(point(27f), width, 200L))
            assertEquals(expected, gate.direction(point(23f), width, hold))
        }
    }

    @Test
    fun `small finger tremors do not prevent an intentional turn`() {
        val gate = gate()
        assertEquals(0, gate.direction(Offset(392f, 200f), width, 0L))
        assertEquals(0, gate.direction(Offset(394f, 203f), width, hold - 1))
        assertEquals(1, gate.direction(Offset(391f, 199f), width, hold))
    }

    @Test
    fun `holding still after a turn can continue but waits again`() {
        val gate = gate()
        val edge = Offset(392f, 200f)
        assertEquals(0, gate.direction(edge, width, 0L))
        assertEquals(1, gate.direction(edge, width, hold))
        gate.didAdvance(hold)
        // The previous page's animation completed at this later time.
        val settledAt = hold + 240L
        val nextTurn = hold + SelectionPageAdvanceGate.REPEAT_MILLIS
        assertEquals(0, gate.direction(edge, width, settledAt))
        assertEquals(0, gate.direction(edge, width, nextTurn - 1))
        assertEquals(1, gate.direction(edge, width, nextTurn))
    }

    @Test
    fun `adjusting the selection along the edge after a turn keeps the repeat deadline`() {
        val gate = gate()
        val edge = Offset(392f, 200f)
        assertEquals(0, gate.direction(edge, width, 0L))
        assertEquals(1, gate.direction(edge, width, hold))
        gate.didAdvance(hold)
        val nextTurn = hold + SelectionPageAdvanceGate.REPEAT_MILLIS
        assertEquals(0, gate.direction(Offset(388f, 240f), width, hold + 240L))
        assertEquals(0, gate.direction(Offset(375f, 280f), width, nextTurn - 1))
        assertEquals(1, gate.direction(Offset(390f, 310f), width, nextTurn))
    }

    @Test
    fun `repeat cadence includes the transition instead of waiting after it`() {
        for (animationMillis in listOf(0L, 120L, 240L, 420L)) {
            val gate = gate()
            val edge = Offset(392f, 200f)
            gate.direction(edge, width, 0L)
            assertEquals(1, gate.direction(edge, width, hold))
            gate.didAdvance(hold)
            assertEquals(0, gate.direction(edge, width, hold + animationMillis))
            assertEquals(1, gate.direction(edge, width, hold + SelectionPageAdvanceGate.REPEAT_MILLIS))
        }
    }

    @Test
    fun `changing direction starts a new hold`() {
        val gate = gate()
        assertEquals(0, gate.direction(Offset(392f, 200f), width, 0L))
        assertEquals(0, gate.direction(Offset(8f, 200f), width, hold - 1))
        assertEquals(0, gate.direction(Offset(8f, 200f), width, hold))
        assertEquals(-1, gate.direction(Offset(8f, 200f), width, hold * 2 - 1))
    }
}
