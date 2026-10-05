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
    fun `motion blur switches off at rest and remains active during sustained movement`() {
        for (amount in listOf(0f, 0.005f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0, readerProgressCaptionBlurIndex(amount))
        }
        assertTrue(readerProgressCaptionBlurIndex(0.5f) > 0)
        assertEquals(ReaderProgressCaptionBlurSteps, readerProgressCaptionBlurIndex(1f))
        assertEquals(ReaderProgressCaptionBlurSteps, readerProgressCaptionBlurIndex(2f))
    }

    @Test
    fun `continuous motion uses only the bounded blur levels`() {
        val visited = mutableSetOf<Int>()
        repeat(100) {
            for (step in 0..100) {
                val amount = step / 100f
                val index = readerProgressCaptionBlurIndex(amount)
                assertTrue(index in 0..ReaderProgressCaptionBlurSteps)
                assertEquals(index, readerProgressCaptionBlurIndex(amount))
                visited += index
            }
        }
        assertEquals((0..ReaderProgressCaptionBlurSteps).toSet(), visited)
    }

    @Test
    fun `rapid scrubbing keeps every live value and clears blur after a pause`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        assertEquals("11 pages left", policy.update("11 pages left", true, 1000).text)
        val burst = policy.update("Last page of the chapter", true, 1016)
        assertTrue(burst.blurred)
        assertEquals("Last page of the chapter", burst.text)
        val next = policy.update("7 pages left", true, 1032)
        assertTrue(next.blurred)
        assertEquals("7 pages left", next.text)
        val last = policy.update("3 pages left", true, 1048)
        assertTrue(last.blurred)
        assertEquals("3 pages left", last.text)
        assertTrue(policy.settle(1048 + ReaderProgressCaptionSettleMillis - 1).blurred)
        val settled = policy.settle(1048 + ReaderProgressCaptionSettleMillis)
        assertFalse(settled.blurred)
        assertEquals("3 pages left", settled.text)
    }

    @Test
    fun `slow scrubbing continues to show each live caption`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        for (step in 1..10) {
            val text = "${12 - step} pages left"
            val shown = policy.update(text, true, step * 250L)
            assertFalse(shown.blurred)
            assertEquals(text, shown.text)
        }
    }

    @Test
    fun `release and cancellation show the actual value without waiting for quiet`() {
        for (actual in listOf("3 pages left", "12 pages left")) {
            val policy = ReaderProgressCaptionPolicy("12 pages left")
            policy.update("11 pages left", true, 1000)
            assertTrue(policy.update("3 pages left", true, 1016).blurred)
            val released = policy.update(actual, false, 1017)
            assertFalse(released.blurred)
            assertEquals(actual, released.text)
            // The next drag starts fresh, even if it immediately follows the previous one.
            assertFalse(policy.update("2 pages left", true, 1018).blurred)
        }
    }

    @Test
    fun `ordinary rapid page turns remain clear`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        for (step in 1..10) {
            val text = "${12 - step} pages left"
            val shown = policy.update(text, false, step * 16L)
            assertFalse(shown.blurred)
            assertEquals(text, shown.text)
        }
    }

    @Test
    fun `unchanged captions do not count as a burst or keep motion blur active`() {
        val policy = ReaderProgressCaptionPolicy("Last page of the chapter")
        for (step in 0..100) {
            assertFalse(policy.update("Last page of the chapter", true, step * 16L).blurred)
        }
        policy.update("3 pages left", true, 1700)
        assertTrue(policy.update("2 pages left", true, 1716).blurred)
        policy.update("2 pages left", true, 1800)
        assertFalse(policy.settle(1716 + ReaderProgressCaptionSettleMillis).blurred)
    }

    @Test
    fun `a slow update clears motion even if the idle callback has not run`() {
        val policy = ReaderProgressCaptionPolicy("12 pages left")
        policy.update("11 pages left", true, 1000)
        assertTrue(policy.update("3 pages left", true, 1016).blurred)
        val slow = policy.update("2 pages left", true, 1016 + ReaderProgressCaptionSettleMillis)
        assertFalse(slow.blurred)
        assertEquals("2 pages left", slow.text)
    }
}
