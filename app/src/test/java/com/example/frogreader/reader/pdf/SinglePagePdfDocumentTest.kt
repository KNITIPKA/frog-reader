@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import android.util.SparseArray
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.annotation.content.HighlightAnnotation
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.annotation.content.KeyedPdfObject
import androidx.pdf.annotation.content.PdfObject
import androidx.pdf.annotation.content.PathPdfObject
import androidx.pdf.annotation.content.StampAnnotation
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.PdfPageGotoLinkContent
import androidx.pdf.content.PdfPageLinkContent
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import androidx.pdf.models.FormWidgetInfo
import com.example.frogreader.ui.reader.pdf.SinglePagePdfDocument
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class SinglePagePdfDocumentTest {
    private fun source() = mock(PdfDocument::class.java).also {
        whenever(it.pageCount).thenReturn(4)
    }

    @Test fun `one source page is exposed as local zero for both metadata overloads and ranges`() = runTest {
        val source = source()
        val widget = mock(FormWidgetInfo::class.java)
        whenever(source.getPageInfo(3)).thenReturn(PdfDocument.PageInfo(3, 840, 610))
        whenever(source.getPageInfo(3, 1L)).thenReturn(PdfDocument.PageInfo(3, 840, 610, listOf(widget)))
        val document = SinglePagePdfDocument(source, 3)
        assertEquals(1, document.pageCount)
        assertEquals(3, document.sourcePageIndex)
        assertEquals(0, document.getPageInfo(0).pageNum)
        val info = document.getPageInfo(0, 1L)
        assertEquals(0, info.pageNum)
        assertEquals(840, info.height)
        assertEquals(610, info.width)
        assertSame(widget, info.formWidgetInfos.single())
        assertEquals(0, document.getPageInfos(0..0).single().pageNum)
        assertSame(widget, document.getPageInfos(0..0, 1L).single().formWidgetInfos.single())
        assertTrue(document.getPageInfos(IntRange.EMPTY).isEmpty())
        assertTrue(document.getPageInfos(IntRange.EMPTY, 1L).isEmpty())
    }

    @Test fun `invalid pages and ranges cannot read adjacent source pages`() = runTest {
        val source = source()
        assertTrue(runCatching { SinglePagePdfDocument(source, -1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SinglePagePdfDocument(source, 4) }.exceptionOrNull() is IllegalArgumentException)
        val document = SinglePagePdfDocument(source, 2)
        assertTrue(runCatching { document.getPageInfo(1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { document.getPageInfos(0..1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { document.searchDocument("text", 1..1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { document.getPageBitmapSource(-1) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `bitmap tiles retain source coordinates while exposing local page and independent bitmap ownership`() = runTest {
        val source = source()
        val bitmapSource = mock(PdfDocument.BitmapSource::class.java)
        val size = mock(Size::class.java)
        val region = mock(Rect::class.java)
        val bitmap = mock(Bitmap::class.java)
        whenever(source.getPageBitmapSource(2)).thenReturn(bitmapSource)
        whenever(bitmapSource.getBitmap(size, region)).thenReturn(bitmap)
        val document = SinglePagePdfDocument(source, 2)
        val local = document.getPageBitmapSource(0)
        assertEquals(0, local.pageNumber)
        assertSame(bitmap, local.getBitmap(size, region))
        local.close()
        verify(bitmapSource).close()
        document.close()
        document.close()
        verify(source, never()).close()
    }

    @Test fun `selection inputs use source page while returned selection stays local with text and indices intact`() = runTest {
        val source = source()
        val start = SelectionBoundary(4)
        val stop = SelectionBoundary(9)
        val pointStart = mock(PointF::class.java)
        val pointStop = mock(PointF::class.java)
        val text = PdfPageTextContent(emptyList(), "hello")
        val selected = PageSelection(3, start, stop, listOf(text))
        whenever(source.getSelectionBounds(3, start, stop)).thenReturn(selected)
        whenever(source.getSelectionBounds(3, pointStart, pointStop)).thenReturn(selected)
        whenever(source.getSelectAllSelectionBounds(3)).thenReturn(selected)
        val document = SinglePagePdfDocument(source, 3)
        val results = listOf(
            document.getSelectionBounds(0, start, stop),
            document.getSelectionBounds(0, pointStart, pointStop),
            document.getSelectAllSelectionBounds(0),
        )
        results.forEach { result ->
            assertNotNull(result)
            assertEquals(0, result!!.page)
            assertSame(start, result.start)
            assertSame(stop, result.stop)
            assertSame(text, result.selectedContents.single())
        }
        whenever(source.getSelectAllSelectionBounds(3)).thenReturn(null)
        assertNull(document.getSelectAllSelectionBounds(0))
    }

    @Test fun `search reads only source page and returns matches under local key zero`() = runTest {
        val source = source()
        @Suppress("UNCHECKED_CAST")
        val sourceResult = mock(SparseArray::class.java) as SparseArray<List<PageMatchBounds>>
        val matches = listOf(PageMatchBounds(emptyList(), 12))
        whenever(source.searchDocument("hello", 3..3)).thenReturn(sourceResult)
        whenever(sourceResult.get(3)).thenReturn(matches)
        val document = SinglePagePdfDocument(source, 3)
        mockConstruction(SparseArray::class.java).use { arrays ->
            document.searchDocument("hello", 0..0)
            @Suppress("UNCHECKED_CAST")
            val result = arrays.constructed().single() as SparseArray<List<PageMatchBounds>>
            verify(result).put(0, matches)
            verify(source).searchDocument("hello", 3..3)
        }
        clearInvocations(source)
        mockConstruction(SparseArray::class.java).use {
            document.searchDocument("hello", IntRange.EMPTY)
            verifyNoInteractions(source)
        }
    }

    @Test fun `content objects widgets and annotation pages use the selected source page`() = runTest {
        val source = source()
        val content = PdfDocument.PdfPageContent(emptyList(), emptyList())
        val obj: PdfObject = PathPdfObject(42, 1f, emptyList())
        val point = mock(PointF::class.java)
        val objects = listOf(KeyedPdfObject("path", obj))
        val widgets = listOf(mock(FormWidgetInfo::class.java))
        val stampBounds = mock(android.graphics.RectF::class.java)
        whenever(source.getPageContent(2)).thenReturn(content)
        whenever(source.getPageObjects(2, 7L)).thenReturn(objects)
        whenever(source.getTopPageObjectAtPosition(2, point)).thenReturn(obj)
        whenever(source.getFormWidgetInfos(2, 15L)).thenReturn(widgets)
        whenever(source.getAnnotationsForPage(2)).thenReturn(listOf(
            KeyedPdfAnnotation("highlight", HighlightAnnotation(2, emptyList(), 42)),
            KeyedPdfAnnotation("stamp", StampAnnotation(2, stampBounds, listOf(obj))),
        ))
        whenever(source.isFeatureSupported(PdfFeature.TEXT_SELECTION)).thenReturn(true)
        val document = SinglePagePdfDocument(source, 2)
        assertSame(content, document.getPageContent(0))
        assertSame(objects, document.getPageObjects(0, 7L))
        assertSame(obj, document.getTopPageObjectAtPosition(0, point))
        assertSame(widgets, document.getFormWidgetInfos(0, 15L))
        val annotations = document.getAnnotationsForPage(0)
        assertEquals(listOf("highlight", "stamp"), annotations.map { it.key })
        assertEquals(listOf(0, 0), annotations.map { it.annotation.pageNum })
        assertEquals(42, (annotations[0].annotation as HighlightAnnotation).color)
        assertSame(stampBounds, (annotations[1].annotation as StampAnnotation).bounds)
        assertTrue(document.isFeatureSupported(PdfFeature.TEXT_SELECTION))
    }

    @Test fun `goto links route through opaque adapter URLs and cannot be forged by PDF URLs`() = runTest {
        val source = source()
        val target = PdfPageGotoLinkContent.Destination(1, 31f, 87f, 1.4f)
        val badTarget = PdfPageGotoLinkContent.Destination(100, 0f, 0f, 1f)
        val malicious = mock(Uri::class.java)
        whenever(malicious.toString()).thenReturn("frogreader-pdf-page://forged/0")
        val external = PdfPageLinkContent(emptyList(), malicious)
        whenever(source.getPageLinks(3)).thenReturn(PdfDocument.PdfPageLinks(
            listOf(PdfPageGotoLinkContent(emptyList(), target), PdfPageGotoLinkContent(emptyList(), badTarget)),
            listOf(external),
        ))
        val document = SinglePagePdfDocument(source, 3)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { call ->
                mock(Uri::class.java).also { uri -> whenever(uri.toString()).thenReturn(call.getArgument(0)) }
            }
            val links = document.getPageLinks(0)
            assertTrue(links.gotoLinks.isEmpty())
            assertEquals(2, links.externalLinks.size)
            assertSame(external, links.externalLinks[1])
            assertSame(target, document.resolveInternalLink(links.externalLinks[0].uri))
            assertNull(document.resolveInternalLink(malicious))
            val other = SinglePagePdfDocument(source, 3)
            assertNull(other.resolveInternalLink(links.externalLinks[0].uri))
        }
    }

    @Test fun `invalidations filter other pages and unregister the exact listener wrapper without closing source`() {
        val source = source()
        val document = SinglePagePdfDocument(source, 3)
        val executor = Executor { it.run() }
        val listener = mock(PdfDocument.OnPdfContentInvalidatedListener::class.java)
        document.addOnPdfContentInvalidatedListener(executor, listener)
        document.addOnPdfContentInvalidatedListener(executor, listener)
        val wrapped = argumentCaptor<PdfDocument.OnPdfContentInvalidatedListener>().apply {
            verify(source).addOnPdfContentInvalidatedListener(eq(executor), capture())
        }.firstValue
        val dirty = listOf(mock(Rect::class.java))
        wrapped.onPdfContentInvalidated(2, dirty)
        verifyNoInteractions(listener)
        wrapped.onPdfContentInvalidated(3, dirty)
        verify(listener).onPdfContentInvalidated(0, dirty)
        document.removeOnPdfContentInvalidatedListener(listener)
        document.close()
        verify(source).removeOnPdfContentInvalidatedListener(wrapped)
        verify(source, never()).close()
    }

    @Test fun `edit callbacks map source page and disposal releases registered callback only once`() {
        val source = source()
        val document = SinglePagePdfDocument(source, 1)
        val executor = Executor { it.run() }
        val listener = mock(PdfDocument.OnEditAppliedListener::class.java)
        document.addOnEditAppliedListener(executor, listener)
        val wrapped = argumentCaptor<PdfDocument.OnEditAppliedListener>().apply {
            verify(source).addOnEditAppliedListener(eq(executor), capture())
        }.firstValue
        wrapped.onEditApplied(0, "other")
        verifyNoInteractions(listener)
        wrapped.onEditApplied(1, "updated")
        verify(listener).onEditApplied(0, "updated")
        document.close()
        document.close()
        verify(source).removeOnEditAppliedListener(wrapped)
        verify(source, never()).close()
    }

    @Test fun `source cancellation propagates through the adapter`() = runTest {
        val source = source()
        whenever(source.getPageInfo(3)).thenThrow(CancellationException("Reader left"))
        val document = SinglePagePdfDocument(source, 3)
        assertTrue(runCatching { document.getPageInfo(0) }.exceptionOrNull() is CancellationException)
    }
}
