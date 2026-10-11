package com.example.frogreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLogDragBoundsTest {
    @Test fun `a tall final card can cross a short first peer`() {
        val top = readingLogBoundedDragTop(-500f, heldHeight = 300, firstCenter = 50f, lastCenter = 260f)
        assertTrue(top + 150f < 50f)
        assertTrue(top + 150f >= 49f)
    }

    @Test fun `a tall first card can cross a short final peer`() {
        val top = readingLogBoundedDragTop(500f, heldHeight = 300, firstCenter = 150f, lastCenter = 360f)
        assertTrue(top + 150f > 360f)
        assertTrue(top + 150f <= 361f)
    }

    @Test fun `unseen group edges allow scrolling while visible edges bound the centre`() {
        assertEquals(-500f, readingLogBoundedDragTop(-500f, 100, null, 350f), 0f)
        assertEquals(500f, readingLogBoundedDragTop(500f, 100, 50f, null), 0f)
        assertEquals(20f, readingLogBoundedDragTop(20f, 100, 50f, 350f), 0f)
    }
}
