package com.example.frogreader.reader.pdf

import androidx.pdf.PdfRect
import androidx.pdf.selection.LinkSelection
import androidx.pdf.selection.Selection
import androidx.pdf.selection.model.TextSelection
import com.example.frogreader.ui.reader.pdf.toActionTextSelection
import org.junit.Assert.*
import org.junit.Test

class PdfSelectionActionsTest {
    private val bounds = listOf(PdfRect(3, 10f, 20f, 70f, 35f))

    @Test fun `ordinary text keeps its original selection and coordinates`() {
        val selection = TextSelection("Selected passage", bounds)

        assertSame(selection, selection.toActionTextSelection())
    }

    @Test fun `selected link text exposes shared text actions with real page bounds`() {
        val link = object : LinkSelection {
            override val linkText = "Chapter four"
            override val bounds = this@PdfSelectionActionsTest.bounds
        }

        val actions = link.toActionTextSelection()

        assertNotNull(actions)
        assertEquals("Chapter four", actions!!.text)
        assertEquals(bounds, actions.bounds)
    }

    @Test fun `links without visible text retain native actions`() {
        val link = object : LinkSelection {
            override val linkText = " \n"
            override val bounds = this@PdfSelectionActionsTest.bounds
        }

        assertNull(link.toActionTextSelection())
    }

    @Test fun `empty text bounds and unsupported selections do not create an unusable toolbar`() {
        assertNull(TextSelection("Passage", emptyList()).toActionTextSelection())
        assertNull(TextSelection("", bounds).toActionTextSelection())
        assertNull((null as Selection?).toActionTextSelection())
        assertNull(object : Selection { override val bounds = this@PdfSelectionActionsTest.bounds }
            .toActionTextSelection())
    }
}
