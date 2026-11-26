package com.example.frogreader.reader.pdf

import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.pdf.pdfNoteAt
import org.junit.Assert.*
import org.junit.Test

class PdfNoteHitTest {
    private val firstLine = PdfTextBounds(7, 20f, 30f, 80f, 40f)
    private val secondLine = PdfTextBounds(7, 20f, 50f, 60f, 60f)
    private fun quote(id: String = "note", note: String? = "Remember this", time: Long = 0L) =
        Quote(id, "Selected passage", -1, time, note = note, pdfSelection = listOf(firstLine, secondLine))

    @Test fun `both lines open note while line gaps and other pages do not`() {
        val note = quote()
        assertEquals(note, pdfNoteAt(listOf(note), 7, 50f, 35f))
        assertEquals(note, pdfNoteAt(listOf(note), 7, 50f, 55f))
        assertNull(pdfNoteAt(listOf(note), 7, 50f, 45f))
        assertNull(pdfNoteAt(listOf(note), 6, 50f, 35f))
        assertNull(pdfNoteAt(listOf(note), 7, 65f, 55f))
    }

    @Test fun `plain highlights and blank notes do not intercept page taps`() {
        assertNull(pdfNoteAt(listOf(quote(note = null), quote(note = " \n")), 7, 50f, 35f))
    }

    @Test fun `most recent overlapping note wins regardless of list ordering`() {
        val older = quote(id = "old", time = 1L)
        val newer = quote(id = "new", time = 2L)
        assertEquals(newer, pdfNoteAt(listOf(newer, older), 7, 50f, 35f))
        assertEquals(newer, pdfNoteAt(listOf(older, newer), 7, 50f, 35f))
    }

    @Test fun `invalid viewport points cannot open a note`() {
        assertNull(pdfNoteAt(listOf(quote()), 7, Float.NaN, 35f))
        assertNull(pdfNoteAt(listOf(quote()), -1, 50f, 35f))
        assertNull(pdfNoteAt(listOf(quote()), 7, 50f, Float.POSITIVE_INFINITY))
    }
}
