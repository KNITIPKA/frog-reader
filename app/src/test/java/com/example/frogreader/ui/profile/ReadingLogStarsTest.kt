package com.example.frogreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingLogStarsTest {
    @Test fun `dragging across stars selects half stars and clamps both ends`() {
        assertEquals(2, ratingForStarPosition(-30f, 240f))
        assertEquals(2, ratingForStarPosition(24f, 240f))
        assertEquals(5, ratingForStarPosition(108f, 240f))
        assertEquals(9, ratingForStarPosition(204f, 240f))
        assertEquals(10, ratingForStarPosition(240f, 240f))
        assertEquals(10, ratingForStarPosition(400f, 240f))
    }

    @Test fun `right to left dragging follows the visual star order`() {
        assertEquals(10, ratingForStarPosition(0f, 240f, rtl = true))
        assertEquals(9, ratingForStarPosition(36f, 240f, rtl = true))
        assertEquals(5, ratingForStarPosition(132f, 240f, rtl = true))
        assertEquals(2, ratingForStarPosition(240f, 240f, rtl = true))
    }

    @Test fun `zero width and unavailable pointer positions keep a valid minimum rating`() {
        assertEquals(2, ratingForStarPosition(30f, 0f))
        assertEquals(2, ratingForStarPosition(Float.NaN, 240f))
    }
}
