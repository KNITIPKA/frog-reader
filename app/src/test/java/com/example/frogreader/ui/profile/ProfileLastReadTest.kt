package com.example.frogreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLastReadTest {
    private val readAt = 1_700_000_000_000L

    @Test
    fun `relative time moves from just now to minutes hours and days`() {
        assertEquals(LastReadAge(LastReadUnit.JUST_NOW), lastReadAge(readAt, readAt + 59_999L))
        assertEquals(LastReadAge(LastReadUnit.MINUTES, 1), lastReadAge(readAt, readAt + 60_000L))
        assertEquals(LastReadAge(LastReadUnit.MINUTES, 59), lastReadAge(readAt, readAt + 3_599_999L))
        assertEquals(LastReadAge(LastReadUnit.HOURS, 1), lastReadAge(readAt, readAt + 3_600_000L))
        assertEquals(LastReadAge(LastReadUnit.HOURS, 23), lastReadAge(readAt, readAt + 86_399_999L))
        assertEquals(LastReadAge(LastReadUnit.DAYS, 1), lastReadAge(readAt, readAt + 86_400_000L))
    }

    @Test
    fun `unknown history and wall clock changes do not invent an age`() {
        assertNull(lastReadAge(null, readAt))
        assertNull(lastReadAge(0L, readAt))
        assertEquals(LastReadAge(LastReadUnit.JUST_NOW), lastReadAge(readAt, readAt - 60_000L))
    }
}
