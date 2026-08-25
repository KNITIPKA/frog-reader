package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderNavigationHistory
import com.example.frogreader.ui.reader.ReaderReturnLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNavigationHistoryTest {

    @Test
    fun `history restores exact main and linked locations in reverse jump order`() {
        val history = ReaderNavigationHistory()
        val main = ReaderReturnLocation.Main(42, charOffset = 17, scrollOffset = 93)
        val linked = ReaderReturnLocation.Linked("OPS/notes.xhtml", 5, scrollOffset = 31)

        history.push(main)
        history.push(linked)

        assertTrue(history.canGoBack)
        assertEquals(linked, history.pop())
        assertEquals(main, history.pop())
        assertFalse(history.canGoBack)
        assertNull(history.pop())
    }

    @Test
    fun `rich note location retains its exact scroll and underlying surface`() {
        val history = ReaderNavigationHistory()
        val underlay = ReaderReturnLocation.Linked("OPS/appendix.xhtml", 3, 27)
        val note = ReaderReturnLocation.Note(
            noteKey = "OPS/notes.xhtml#n7",
            itemIndex = 5,
            scrollOffset = 81,
            underlay = underlay,
        )

        history.push(note)

        assertEquals(note, history.pop())
        assertEquals(underlay, note.underlay)
    }

    @Test
    fun `duplicate origins are coalesced and oldest entries are bounded`() {
        val history = ReaderNavigationHistory(maxEntries = 2)
        val first = ReaderReturnLocation.Main(1)
        val second = ReaderReturnLocation.Main(2)
        val third = ReaderReturnLocation.Main(3)

        history.push(first)
        history.push(first)
        assertEquals(1, history.size)
        history.push(second)
        history.push(third)

        assertEquals(2, history.size)
        assertEquals(third, history.pop())
        assertEquals(second, history.pop())
        assertNull(history.pop())
    }

    @Test
    fun `clear removes contextual return affordance`() {
        val history = ReaderNavigationHistory()
        history.push(ReaderReturnLocation.Main(9, 4, 2))

        history.clear()

        assertFalse(history.canGoBack)
        assertEquals(0, history.size)
    }
}
