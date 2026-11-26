package com.example.frogreader.reader.pdf

import com.example.frogreader.ui.reader.pdf.pdfDragTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfSinglePageDragTest {
    @Test fun `short slow drags return to the current sheet`() {
        assertEquals(0, pdfDragTarget(0.2f, -100f, 500f))
        assertEquals(0, pdfDragTarget(-0.2f, 100f, 500f))
    }
    @Test fun `dragging a quarter page commits in physical PDF order`() {
        assertEquals(1, pdfDragTarget(0.25f, 0f, 500f))
        assertEquals(-1, pdfDragTarget(-0.25f, 0f, 500f))
    }
    @Test fun `a flick commits early and reversing velocity returns to the current sheet`() {
        assertEquals(1, pdfDragTarget(0.1f, -800f, 500f))
        assertEquals(0, pdfDragTarget(0.1f, 800f, 500f))
        assertEquals(0, pdfDragTarget(0f, 800f, 500f))
    }
    @Test fun `invalid motion cannot navigate`() {
        assertEquals(0, pdfDragTarget(Float.NaN, 0f, 500f))
        assertEquals(0, pdfDragTarget(0.4f, Float.POSITIVE_INFINITY, 500f))
    }
}
