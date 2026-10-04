package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderReadingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderReadingSessionTest {
    @Test
    fun `last read qualifies at ten foreground seconds but not an accidental open`() {
        var now = 0L
        val session = ReaderReadingSession { now }
        session.setRunning(true)
        now = 9_999L
        assertEquals(9L, session.drainSeconds())
        assertFalse(session.qualifiesAsReading)
        now = 10_000L
        assertEquals(1L, session.drainSeconds())
        assertTrue(session.qualifiesAsReading)
        session.reset()
        assertFalse(session.qualifiesAsReading)
    }

    @Test
    fun `last read threshold excludes time paused in a menu or background`() {
        var now = 0L
        val session = ReaderReadingSession { now }
        session.setRunning(true)
        now += 6_000L
        session.setRunning(false)
        assertEquals(6L, session.drainSeconds())
        now += 3_600_000L
        session.setRunning(false)
        assertFalse(session.qualifiesAsReading)
        session.setRunning(true)
        now += 4_000L
        session.setRunning(false)
        assertTrue(session.qualifiesAsReading)
    }

    @Test
    fun `pause and disposal do not count the same time twice`() {
        var now = 0L
        val session = ReaderReadingSession { now }
        session.setRunning(true)
        now = 12_450L
        session.setRunning(false)
        assertEquals(12L, session.drainSeconds())
        now += 80_000L
        session.setRunning(false)
        assertEquals(0L, session.drainSeconds())
    }

    @Test
    fun `short sessions retain subsecond time and exclude an overlay`() {
        var now = 0L
        val session = ReaderReadingSession { now }
        session.setRunning(true)
        now = 600L
        session.setRunning(false)
        assertEquals(0L, session.drainSeconds())
        now = 50_000L
        session.setRunning(true)
        now += 600L
        session.setRunning(false)
        assertEquals(1L, session.drainSeconds())
    }

    @Test
    fun `completion drains the active session once and a reread resets its remainder`() {
        var now = 0L
        val session = ReaderReadingSession { now }
        session.setRunning(true)
        now = 3_900L
        session.setRunning(false)
        assertEquals(3L, session.drainSeconds())
        assertEquals(0L, session.drainSeconds())
        session.reset()
        session.setRunning(true)
        now += 200L
        session.setRunning(false)
        assertEquals(0L, session.drainSeconds())
    }
}
