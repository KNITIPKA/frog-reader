package com.example.frogreader.reader.pdf

import com.example.frogreader.ui.reader.pdf.pdfPageIndexFromInput
import org.junit.Assert.*
import org.junit.Test

class PdfPageNavigationTest {
    @Test fun `page jump accepts only real one based PDF pages`() {
        assertEquals(0, pdfPageIndexFromInput("1", 42))
        assertEquals(41, pdfPageIndexFromInput(" 42 ", 42))
        for (input in listOf("", " ", "0", "-1", "43", "1.5", "chapter 1", "999999999999999999999999")) {
            assertNull(input, pdfPageIndexFromInput(input, 42))
        }
        assertNull(pdfPageIndexFromInput("1", 0))
    }

    @Test fun `large document navigation does not enumerate pages`() {
        assertEquals(999_999, pdfPageIndexFromInput("1000000", 1_000_000))
        assertNull(pdfPageIndexFromInput("1000001", 1_000_000))
    }
}
