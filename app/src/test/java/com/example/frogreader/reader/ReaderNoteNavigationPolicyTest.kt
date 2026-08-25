package com.example.frogreader.reader

import com.example.frogreader.ui.reader.noteInitialLazyIndex
import com.example.frogreader.ui.reader.shouldReturnToPreviousNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNoteNavigationPolicyTest {

    @Test
    fun `saved note position includes the synthetic title and final rich element`() {
        assertEquals(0, noteInitialLazyIndex(savedIndex = 0, elementCount = 1))
        assertEquals(1, noteInitialLazyIndex(savedIndex = 1, elementCount = 1))
        assertEquals(4, noteInitialLazyIndex(savedIndex = 4, elementCount = 4))
    }

    @Test
    fun `invalid saved note positions clamp to the complete lazy range`() {
        assertEquals(0, noteInitialLazyIndex(savedIndex = -7, elementCount = 3))
        assertEquals(3, noteInitialLazyIndex(savedIndex = 20, elementCount = 3))
        assertEquals(0, noteInitialLazyIndex(savedIndex = 4, elementCount = 0))
    }

    @Test
    fun `system Back returns only from a contextual note with live history`() {
        assertTrue(
            shouldReturnToPreviousNote(
                contextualReturn = true,
                navigationBackAvailable = true,
            ),
        )
        assertFalse(
            shouldReturnToPreviousNote(
                contextualReturn = false,
                navigationBackAvailable = true,
            ),
        )
        assertFalse(
            shouldReturnToPreviousNote(
                contextualReturn = true,
                navigationBackAvailable = false,
            ),
        )
    }
}
