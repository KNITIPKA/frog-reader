@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import androidx.pdf.PdfDocument
import com.example.frogreader.ui.reader.pdf.PdfPageViewport
import com.example.frogreader.ui.reader.pdf.PdfPageViewports
import com.example.frogreader.ui.reader.pdf.SinglePagePdfDocument
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class PdfPageViewportsTest {
    private fun source() = mock(PdfDocument::class.java).also {
        whenever(it.pageCount).thenReturn(176)
    }

    @Test fun `parent cleanup before pager retain keeps the actual neighbouring sheet`() {
        val pages = PdfPageViewports(source())
        pages.page(63)
        val incoming = pages.page(64)
        // Parent SideEffects run before the pager's subcomposition attaches its effects.
        pages.trim(63)
        pages.retain(64, incoming)
        assertSame(incoming, pages.composedPage(64))
        assertSame(incoming, pages.page(64))
        pages.close()
    }

    @Test fun `late disposal cannot evict a replacement that has already attached`() {
        val source = source()
        val pages = PdfPageViewports(source)
        val previous = pages.page(64)
        pages.retain(64, previous)
        val replacement = PdfPageViewport(SinglePagePdfDocument(source, 64))
        pages.retain(64, replacement)
        pages.release(64, previous)
        assertSame(replacement, pages.composedPage(64))
        assertSame(replacement, pages.page(64))
        pages.close()
    }
}
