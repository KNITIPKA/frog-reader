@file:Suppress("RestrictedApi")

package com.example.frogreader.reader.pdf

import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import com.example.frogreader.data.pdf.compat.*
import com.example.frogreader.ui.reader.pdf.CompatibilityPdfDocument
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class CompatibilityPdfDocumentTest {
    private class TextBackend : PdfTextBackend {
        override val pageCount = 2
        var closed = 0
        var error: Exception? = null
        var selectionRequest: Triple<Int, PdfTextBoundary, PdfTextBoundary>? = null
        var searchRequest: List<Any>? = null
        override suspend fun pageContent(page: Int, width: Int, height: Int): PdfTextPageData {
            error?.let { throw it }
            return PdfTextPageData("Café", emptyList())
        }
        override suspend fun selection(page: Int, width: Int, height: Int,
            start: PdfTextBoundary, stop: PdfTextBoundary): PdfTextSelectionData {
            error?.let { throw it }
            selectionRequest = Triple(page, start, stop)
            return PdfTextSelectionData(PdfTextBoundary(7), PdfTextBoundary(11), "Café", emptyList())
        }
        override suspend fun search(page: Int, width: Int, height: Int, query: String): List<PdfTextMatchData> {
            error?.let { throw it }
            searchRequest = listOf(page, width, height, query)
            return emptyList()
        }
        override fun close() { closed++; error?.let { throw it } }
    }

    private suspend fun renderer() = mock(PdfDocument::class.java).also {
        whenever(it.pageCount).thenReturn(2)
        whenever(it.getPageInfo(any())).thenAnswer { call ->
            PdfDocument.PageInfo(call.getArgument(0), height = 800, width = 600)
        }
    }

    @Test fun `compatibility advertises only the text operations that it supplies`() = runTest {
        val renderer = renderer()
        whenever(renderer.isFeatureSupported(PdfFeature.LINKS)).thenReturn(true)
        val document = CompatibilityPdfDocument(renderer, TextBackend())
        assertTrue(document.isFeatureSupported(PdfFeature.TEXT_SELECTION))
        assertTrue(document.isFeatureSupported(PdfFeature.SEARCH))
        assertTrue(document.isFeatureSupported(PdfFeature.TEXT_EXTRACTION))
        assertTrue(document.isFeatureSupported(PdfFeature.LINKS))
        assertFalse(document.isFeatureSupported(PdfFeature.ANNOTATIONS))
        document.close()
        assertFalse(document.isFeatureSupported(PdfFeature.SEARCH))
    }

    @Test fun `selection preserves native character indices and text through the AndroidX boundary`() = runTest {
        val text = TextBackend()
        val document = CompatibilityPdfDocument(renderer(), text)
        val result = document.getSelectionBounds(1, SelectionBoundary(7), SelectionBoundary(11))!!
        assertEquals(Triple(1, PdfTextBoundary(7), PdfTextBoundary(11)), text.selectionRequest)
        assertEquals(1, result.page)
        assertEquals(7, result.start.index)
        assertEquals(11, result.stop.index)
        assertEquals("Café", (result.selectedContents.single() as PdfPageTextContent).text)
        document.getSelectAllSelectionBounds(1)
        assertEquals(Triple(1, PdfTextBoundary(0), PdfTextBoundary(Int.MAX_VALUE)), text.selectionRequest)
    }

    @Test fun `text operations use actual renderer dimensions for rotated page coordinates`() = runTest {
        val text = TextBackend()
        val document = CompatibilityPdfDocument(renderer(), text)
        document.searchDocument("Café", 1..1)
        assertEquals(listOf(1, 600, 800, "Café"), text.searchRequest)
        val content = document.getPageContent(0)!!
        assertEquals("Café", content.textContents.single().text)
        assertTrue(content.imageContents.isEmpty())
    }

    @Test fun `worker failure clears selection and signals the reader without crashing its viewer coroutine`() = runTest {
        val text = TextBackend().apply { error = IOException("Worker died") }
        val document = CompatibilityPdfDocument(renderer(), text)
        val failure = async(start = CoroutineStart.UNDISPATCHED) { document.selectionFailures.first() }
        assertNull(document.getSelectAllSelectionBounds(0))
        failure.await()
        assertNull(document.getPageContent(0))
        assertTrue(runCatching { document.searchDocument("text", 0..0) }.exceptionOrNull() is IOException)
    }

    @Test fun `cancellation is propagated instead of becoming a selection or accessibility failure`() = runTest {
        val text = TextBackend().apply { error = CancellationException("Reader left") }
        val document = CompatibilityPdfDocument(renderer(), text)
        assertTrue(runCatching { document.getSelectAllSelectionBounds(0) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { document.getPageContent(0) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `close releases both engines exactly once even if the text worker throws`() = runTest {
        val renderer = renderer()
        val text = TextBackend().apply { error = IOException("Worker died") }
        val document = CompatibilityPdfDocument(renderer, text)
        assertTrue(runCatching { document.close() }.exceptionOrNull() is IOException)
        document.close()
        assertEquals(1, text.closed)
        verify(renderer, times(1)).close()
    }

    @Test fun `different page counts cannot create mismatched text anchors`() = runTest {
        val renderer = renderer()
        whenever(renderer.pageCount).thenReturn(3)
        assertTrue(runCatching { CompatibilityPdfDocument(renderer, TextBackend()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
