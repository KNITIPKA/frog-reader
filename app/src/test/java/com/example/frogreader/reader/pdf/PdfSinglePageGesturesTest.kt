package com.example.frogreader.reader.pdf

import com.example.frogreader.ui.reader.pdf.PdfSinglePageSwipeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfSinglePageGesturesTest {
    private fun gesture() = PdfSinglePageSwipeGesture(minimumDistancePx = 48f, maximumDurationMillis = 500L)

    @Test fun `a deliberate swipe turns exactly one page in physical document order`() {
        val gesture = gesture()
        gesture.begin(240f, 200f, 1_000L, canTurn = true)
        gesture.update(100f, 208f, 1_150L, pointerCount = 1, canTurn = true)
        assertEquals(1, gesture.end(20f, 210f, 1_200L, canTurn = true))
        assertNull(gesture.end(0f, 210f, 1_210L, canTurn = true))
        gesture.begin(20f, 200f, 2_000L, canTurn = true)
        assertEquals(-1, gesture.end(240f, 190f, 2_200L, canTurn = true))
    }

    @Test fun `taps small movement and diagonal drags leave native gestures alone`() {
        for ((x, y) in listOf(240f to 200f, 193f to 200f, 150f to 280f, 240f to 400f)) {
            val gesture = gesture()
            gesture.begin(240f, 200f, 1_000L, canTurn = true)
            assertNull(gesture.end(x, y, 1_200L, canTurn = true))
        }
    }

    @Test fun `a vertical pan cannot become a page turn when it curves sideways`() {
        val gesture = gesture()
        gesture.begin(240f, 200f, 1_000L, canTurn = true)
        gesture.update(230f, 300f, 1_050L, pointerCount = 1, canTurn = true)
        assertNull(gesture.end(20f, 210f, 1_200L, canTurn = true))
    }

    @Test fun `holding until the native long press deadline never turns a page`() {
        for (duration in listOf(500L, 700L, 1_000L)) {
            val gesture = gesture()
            gesture.begin(240f, 200f, 1_000L, canTurn = true)
            assertNull(gesture.end(20f, 200f, 1_000L + duration, canTurn = true))
        }
    }

    @Test fun `pinch or selection cancels the entire press even if eligibility returns`() {
        val pinch = gesture()
        pinch.begin(240f, 200f, 1_000L, canTurn = true)
        pinch.update(220f, 200f, 1_050L, pointerCount = 2, canTurn = true)
        assertNull(pinch.end(20f, 200f, 1_200L, canTurn = true))
        val selection = gesture()
        selection.begin(240f, 200f, 1_000L, canTurn = true)
        selection.update(220f, 200f, 1_050L, pointerCount = 1, canTurn = false)
        assertNull(selection.end(20f, 200f, 1_200L, canTurn = true))
    }

    @Test fun `a press beginning while zoomed or selected cannot turn after becoming enabled`() {
        val gesture = gesture()
        gesture.begin(240f, 200f, 1_000L, canTurn = false)
        assertNull(gesture.end(20f, 200f, 1_200L, canTurn = true))
        gesture.begin(240f, 200f, 2_000L, canTurn = true)
        assertNull(gesture.end(20f, 200f, 2_200L, canTurn = false))
    }

    @Test fun `cancelled native input and invalid coordinates cannot turn a page`() {
        val gesture = gesture()
        gesture.begin(240f, 200f, 1_000L, canTurn = true)
        gesture.cancel()
        assertNull(gesture.end(20f, 200f, 1_200L, canTurn = true))
        gesture.begin(Float.NaN, 200f, 2_000L, canTurn = true)
        assertNull(gesture.end(20f, 200f, 2_200L, canTurn = true))
        gesture.begin(240f, 200f, 3_000L, canTurn = true)
        assertNull(gesture.end(Float.POSITIVE_INFINITY, 200f, 3_200L, canTurn = true))
    }

    @Test fun `reversed event time is rejected and a new press can recover`() {
        val gesture = gesture()
        gesture.begin(240f, 200f, 1_000L, canTurn = true)
        assertNull(gesture.end(20f, 200f, 999L, canTurn = true))
        gesture.begin(240f, 200f, 2_000L, canTurn = true)
        assertEquals(1, gesture.end(20f, 200f, 2_200L, canTurn = true))
    }
}
