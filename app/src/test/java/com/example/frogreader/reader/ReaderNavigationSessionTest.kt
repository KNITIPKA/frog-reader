package com.example.frogreader.ui.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNavigationSessionTest {
    @Test fun `page four return expires permanently fifteen seconds after jumping to page one hundred`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        val page4 = ReaderReturnLocation.Main(3, charOffset = 42)
        session.remember(page4, expires = true)
        runCurrent()
        advanceTimeBy(14_999)
        assertEquals(page4, session.returnLocation.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(session.returnLocation.value)
        // Reattaching UI / applying the same configuration cannot resurrect it.
        session.configureExpiry(15_000)
        assertNull(session.returnLocation.value)
        assertNull(session.take())
        session.configureExpiry(null)
        assertNull(session.returnLocation.value)
    }

    @Test fun `reopening controls and reapplying settings do not restart a deadline`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        session.remember(ReaderReturnLocation.Main(3), expires = true)
        runCurrent()
        advanceTimeBy(10_000)
        session.configureExpiry(15_000)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(session.returnLocation.value)
    }

    @Test fun `new jumps get their own deadline without reviving older origins`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        session.remember(ReaderReturnLocation.Main(3), expires = true)
        runCurrent()
        advanceTimeBy(10_000)
        val page100 = ReaderReturnLocation.Main(99)
        session.remember(page100, expires = true)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(page100, session.returnLocation.value)
        assertEquals(page100, session.take())
        assertNull(session.returnLocation.value)
        assertNull(session.take())
    }

    @Test fun `disabled expiry preserves the return until used`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        val origin = ReaderReturnLocation.Main(3)
        session.remember(origin, expires = true)
        runCurrent()
        advanceTimeBy(5_000)
        session.configureExpiry(null)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(origin, session.returnLocation.value)
        assertEquals(origin, session.take())
    }

    @Test fun `duplicate jump origin gets a fresh deadline`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        val origin = ReaderReturnLocation.Main(3)
        session.remember(origin, expires = true)
        runCurrent()
        advanceTimeBy(10_000)
        session.remember(origin, expires = true)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(origin, session.returnLocation.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(session.returnLocation.value)
    }

    @Test fun `essential linked and note navigation survives expiration of page jumps`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        session.configureExpiry(15_000)
        val main = ReaderReturnLocation.Main(3)
        session.remember(main, expires = true)
        session.remember(main, expires = false) // Return out of a linked document.
        val linked = ReaderReturnLocation.Linked("appendix", 5)
        session.remember(linked, expires = false)
        val note = ReaderReturnLocation.Note("note", underlay = linked)
        session.remember(note, expires = false)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(note, session.take())
        assertEquals(linked, session.take())
        assertEquals(main, session.take())
        assertNull(session.take())
    }

    @Test fun `expired return cannot be used after background delay before the timer dispatches`() = runTest {
        var elapsed = 0L
        val session = ReaderNavigationSession(this) { elapsed }
        session.configureExpiry(15_000)
        session.remember(ReaderReturnLocation.Main(3), expires = true)
        runCurrent()
        elapsed = 20_000
        assertNull(session.take())
        assertNull(session.returnLocation.value)
    }

    @Test fun `accessibility extends or disables expiry and clear cancels pending work`() = runTest {
        val session = ReaderNavigationSession(this) { testScheduler.currentTime }
        val origin = ReaderReturnLocation.Main(3)
        session.configureExpiry(60_000)
        session.remember(origin, expires = true)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(origin, session.returnLocation.value)
        advanceTimeBy(45_000)
        runCurrent()
        assertNull(session.returnLocation.value)
        session.configureExpiry(Long.MAX_VALUE)
        session.remember(origin, expires = true)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(origin, session.returnLocation.value)
        session.clear()
        assertNull(session.returnLocation.value)
    }
}
