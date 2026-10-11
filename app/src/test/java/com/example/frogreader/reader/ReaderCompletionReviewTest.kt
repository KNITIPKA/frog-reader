package com.example.frogreader.reader

import com.example.frogreader.ui.reader.readerHalfStarRatingAt
import com.example.frogreader.ui.reader.readerHalfStarRatingForValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCompletionReviewTest {
    @Test fun `each visible half of a star selects the corresponding rating`() {
        for (half in 0 until 10) {
            val center = (half + 0.5f) / 10f
            val expected = (half + 1).coerceAtLeast(2)
            assertEquals(expected, readerHalfStarRatingAt(center))
            assertEquals(expected, readerHalfStarRatingAt(1f - center, rtl = true))
        }
        assertEquals(9, readerHalfStarRatingAt(0.85f))
        assertEquals(10, readerHalfStarRatingAt(0.95f))
    }

    @Test fun `dragging past either edge stays within one to five stars`() {
        assertEquals(2, readerHalfStarRatingAt(-0.5f))
        assertEquals(10, readerHalfStarRatingAt(1.5f))
        assertEquals(10, readerHalfStarRatingAt(-0.5f, rtl = true))
        assertEquals(2, readerHalfStarRatingAt(1.5f, rtl = true))
    }

    @Test fun `accessible rating adjustment retains every half star step`() {
        for (half in 2..10) assertEquals(half, readerHalfStarRatingForValue(half / 2f))
        assertEquals(2, readerHalfStarRatingForValue(0f))
        assertEquals(10, readerHalfStarRatingForValue(10f))
        assertEquals(2, readerHalfStarRatingForValue(Float.NaN))
    }
}
