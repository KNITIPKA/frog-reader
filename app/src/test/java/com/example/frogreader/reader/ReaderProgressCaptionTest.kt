package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderProgressCaptionPolicy
import com.example.frogreader.ui.reader.ReaderProgressCaptionSettleMillis
import com.example.frogreader.ui.reader.ReaderProgressCaptionBlurSteps
import com.example.frogreader.ui.reader.readerProgressCaptionBlurIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressCaptionTest {
    @Test
    fun `motion blur switches off at rest and when the caption cannot be seen`() {
        for (opacity in listOf(1f, 0.995f, 0.05f, 0f, -1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0, readerProgressCaptionBlurIndex(opacity))
        }
        assertTrue(readerProgressCaptionBlurIndex(0.5f) > 0)
    }

    @Test
    fun `repeated fades use only the bounded blur levels`() {
        val visited = mutableSetOf<Int>()
        repeat(100) {
            for (step in 0..100) {
                val opacity = step / 100f
                val index = readerProgressCaptionBlurIndex(opacity)
                assertTrue(index in 0..ReaderProgressCaptionBlurSteps)
                assertEquals(index, readerProgressCaptionBlurIndex(opacity))
                visited += index
            }
        }
        assertEquals((0..ReaderProgressCaptionBlurSteps).toSet(), visited)
    }

    @Test
    fun `rapid scrubbing freezes one text face and reveals only the final caption after a pause`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        assertEquals("11 pages left", policy.update("11 pages left", true, 1000).text)
        val burst = policy.update("Last page of the chapter", true, 1016)
        assertTrue(burst.subdued)
        assertEquals("11 pages left", burst.text)
        assertEquals(burst, policy.update("7 pages left", true, 1032))
        assertEquals(burst, policy.update("3 pages left", true, 1048))
        assertTrue(policy.settle(1048 + ReaderProgressCaptionSettleMillis - 1).subdued)
        val settled = policy.settle(1048 + ReaderProgressCaptionSettleMillis)
        assertFalse(settled.subdued)
        assertEquals("3 pages left", settled.text)
    }

    @Test
    fun `slow scrubbing continues to show each live caption`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        for (step in 1..10) {
            val text = "${12 - step} pages left"
            val shown = policy.update(text, true, step * 250L)
            assertFalse(shown.subdued)
            assertEquals(text, shown.text)
        }
    }

    @Test
    fun `release and cancellation show the actual value without waiting for quiet`() {
        for (actual in listOf("3 pages left", "12 pages left")) {
            val policy = ReaderProgressCaptionPolicy("12 pages left")
            policy.update("11 pages left", true, 1000)
            assertTrue(policy.update("3 pages left", true, 1016).subdued)
            val released = policy.update(actual, false, 1017)
            assertFalse(released.subdued)
            assertEquals(actual, released.text)
            // The next drag starts fresh, even if it immediately follows the previous one.
            assertFalse(policy.update("2 pages left", true, 1018).subdued)
        }
    }

    @Test
    fun `ordinary rapid page turns are never suppressed`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        for (step in 1..10) {
            val text = "${12 - step} pages left"
            val shown = policy.update(text, false, step * 16L)
            assertFalse(shown.subdued)
            assertEquals(text, shown.text)
        }
    }

    @Test
    fun `unchanged captions do not count as a burst or keep the caption hidden`() {
        val policy = ReaderProgressCaptionPolicy("Last page of the chapter")
        for (step in 0..100) {
            assertFalse(policy.update("Last page of the chapter", true, step * 16L).subdued)
        }
        policy.update("3 pages left", true, 1700)
        assertTrue(policy.update("2 pages left", true, 1716).subdued)
        policy.update("2 pages left", true, 1800)
        assertFalse(policy.settle(1716 + ReaderProgressCaptionSettleMillis).subdued)
    }
}
