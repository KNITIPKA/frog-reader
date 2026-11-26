package com.example.frogreader.reader.pdf

import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.ui.reader.pdf.currentPdfOutlineIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfOutlineNavigationTest {
    @Test fun `nearest preceding destination wins without reordering author headings`() {
        val entries = listOf(
            PdfOutlineEntry("Later chapter", 30),
            PdfOutlineEntry("Opening chapter", 2),
            PdfOutlineEntry("Middle chapter", 15),
            PdfOutlineEntry("Earlier appendix", 5),
        )
        assertEquals(-1, currentPdfOutlineIndex(entries, 0, 50))
        assertEquals(1, currentPdfOutlineIndex(entries, 2, 50))
        assertEquals(3, currentPdfOutlineIndex(entries, 10, 50))
        assertEquals(2, currentPdfOutlineIndex(entries, 20, 50))
        assertEquals(0, currentPdfOutlineIndex(entries, 49, 50))
    }

    @Test fun `last source heading on the current page wins over its parent`() {
        val entries = listOf(
            PdfOutlineEntry("Part", 12),
            PdfOutlineEntry("Chapter", 12, 1),
            PdfOutlineEntry("Section", 12, 2),
            PdfOutlineEntry("Next chapter", 20, 1),
        )
        assertEquals(2, currentPdfOutlineIndex(entries, 12, 50))
        assertEquals(2, currentPdfOutlineIndex(entries, 19, 50))
        assertEquals(3, currentPdfOutlineIndex(entries, 20, 50))
    }

    @Test fun `groups and invalid destinations do not replace a real current section`() {
        val entries = listOf(
            PdfOutlineEntry("Chapter", 3),
            PdfOutlineEntry("Group without destination"),
            PdfOutlineEntry("Negative", -1),
            PdfOutlineEntry("Past document end", 50),
            PdfOutlineEntry("Later child", 10, 1),
        )
        assertEquals(0, currentPdfOutlineIndex(entries, 7, 50))
        assertEquals(4, currentPdfOutlineIndex(entries, 49, 50))
        assertEquals(-1, currentPdfOutlineIndex(entries, 49, 0))
        assertEquals(-1, currentPdfOutlineIndex(emptyList(), 49, 50))
    }
}
