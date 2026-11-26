@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.frogreader.reader.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.pdf.PdfDocument
import com.example.frogreader.ui.reader.pdf.CachedPdfDocument
import com.example.frogreader.ui.reader.pdf.SinglePagePdfDocument
import com.example.frogreader.ui.reader.pdf.StabilizedPdfDocument
import com.example.frogreader.ui.reader.pdf.preparePdfPageLayout
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class CachedPdfDocumentTest {
    private fun source() = mock(PdfDocument::class.java).also {
        whenever(it.pageCount).thenReturn(100)
    }

    @Test fun `page dimensions are shared across viewer overloads and replacement sheet adapters`() = runTest {
        val source = source()
        val info = PdfDocument.PageInfo(70, 840, 610)
        whenever(source.getPageInfo(70)).thenReturn(info)
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        assertSame(info, document.getPageInfo(70))
        assertSame(info, document.getPageInfo(70, PdfDocument.PAGE_INFO_EXCLUDE_FORM_WIDGETS))
        assertSame(info, document.getPageInfos(70..70).single())
        repeat(2) {
            SinglePagePdfDocument(document, 70).use { sheet ->
                assertEquals(610, sheet.getPageInfo(0).width)
                assertEquals(840, sheet.getPageInfo(0, 0L).height)
            }
        }
        verify(source, times(1)).getPageInfo(70)
        verify(source, never()).getPageInfo(70, 0L)
        verify(source, never()).close()
    }

    @Test fun `concurrent layout and fitting share a single metadata request`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val source = object : PdfDocument by source() {
            override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
                requests++
                entered.complete(Unit)
                finish.await()
                return PdfDocument.PageInfo(pageNumber, 800, 600)
            }
        }
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        val layout = async { document.getPageInfo(40) }
        entered.await()
        val fit = async { document.getPageInfo(40, 0L) }
        runCurrent()
        assertEquals(1, requests)
        finish.complete(Unit)
        assertSame(layout.await(), fit.await())
        assertEquals(1, requests)
    }

    @Test fun `form widget metadata remains fresh instead of using dimension cache`() = runTest {
        val source = source()
        whenever(source.getPageInfo(2)).thenReturn(PdfDocument.PageInfo(2, 800, 600))
        val flags = PdfDocument.PAGE_INFO_INCLUDE_FORM_WIDGET
        val first = PdfDocument.PageInfo(2, 800, 600)
        val updated = PdfDocument.PageInfo(2, 800, 600)
        whenever(source.getPageInfo(2, flags)).thenReturn(first, updated)
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        document.getPageInfo(2)
        assertSame(first, document.getPageInfo(2, flags))
        assertSame(updated, document.getPageInfo(2, flags))
        verify(source, times(2)).getPageInfo(2, flags)
    }

    @Test fun `native metadata cannot close a page while another sheet is rendering`() = runTest {
        val rendering = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val bitmap = mock(Bitmap::class.java)
        var metadataCalls = 0
        val source = object : PdfDocument by source() {
            override fun getPageBitmapSource(pageNumber: Int) = object : PdfDocument.BitmapSource {
                override val pageNumber = pageNumber
                override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap {
                    rendering.complete(Unit)
                    finish.await()
                    return bitmap
                }
                override fun close() = Unit
            }
            override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
                metadataCalls++
                return PdfDocument.PageInfo(pageNumber, 800, 600)
            }
        }
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        val render = async { document.getPageBitmapSource(80).getBitmap(mock(Size::class.java)) }
        rendering.await()
        val metadata = async { document.getPageInfo(2) }
        runCurrent()
        assertEquals(0, metadataCalls)
        finish.complete(Unit)
        assertSame(bitmap, render.await())
        assertEquals(2, metadata.await().pageNum)
        assertEquals(1, metadataCalls)
    }

    @Test fun `late native page release is retried before AndroidX can leave a white sheet`() = runTest {
        val source = source()
        val native = mock(PdfDocument.BitmapSource::class.java)
        val bitmap = mock(Bitmap::class.java)
        val size = mock(Size::class.java)
        whenever(source.getPageBitmapSource(2)).thenReturn(native)
        whenever(native.getBitmap(size, null)).thenThrow(IllegalStateException("Page already closed")).thenReturn(bitmap)
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        val lease = document.getPageBitmapSource(2)
        assertSame(bitmap, lease.getBitmap(size))
        verify(native, times(2)).getBitmap(size, null)
        verify(bitmap, never()).recycle()
        lease.close()
        lease.close()
        verify(native, times(1)).close()
    }

    @Test fun `cancellation on worker return recycles a produced bitmap before ownership reaches viewer`() = runTest {
        val bitmap = mock(Bitmap::class.java)
        lateinit var request: Deferred<Bitmap>
        val source = object : PdfDocument by source() {
            override fun getPageBitmapSource(pageNumber: Int) = object : PdfDocument.BitmapSource {
                override val pageNumber = pageNumber
                override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap {
                    // Model a native render completing after its viewer was disposed. There is
                    // no suspension between cancellation and the returned bitmap's production.
                    request.cancel()
                    return bitmap
                }
                override fun close() = Unit
            }
        }
        // A distinct dispatcher forces withContext's cancellable return to the caller dispatcher.
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        request = async { document.getPageBitmapSource(2).getBitmap(mock(Size::class.java)) }
        runCurrent()
        assertTrue(request.isCancelled)
        verify(bitmap, times(1)).recycle()
    }

    @Test fun `permanent native failure has bounded retries and cancellation is never retried`() = runTest {
        val source = source()
        val native = mock(PdfDocument.BitmapSource::class.java)
        val size = mock(Size::class.java)
        whenever(source.getPageBitmapSource(2)).thenReturn(native)
        whenever(native.getBitmap(size, null)).thenThrow(IllegalStateException("Broken page"))
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { document.getPageBitmapSource(2).getBitmap(size) }.exceptionOrNull() is IllegalStateException)
        verify(native, times(3)).getBitmap(size, null)
        reset(native)
        whenever(native.getBitmap(size, null)).thenThrow(CancellationException("Reader left"))
        assertTrue(runCatching { document.getPageBitmapSource(2).getBitmap(size) }.exceptionOrNull() is CancellationException)
        verify(native, times(1)).getBitmap(size, null)
    }

    @Test fun `layout preparation warms only the requested prefix through outer reader adapters`() = runTest {
        val pages = mutableListOf<Int>()
        val source = object : PdfDocument by source() {
            override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
                pages += pageNumber
                return PdfDocument.PageInfo(pageNumber, 800, 600)
            }
        }
        val cached = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        val document = StabilizedPdfDocument(cached)
        preparePdfPageLayout(document, 3)
        assertEquals(listOf(0, 1, 2, 3), pages)
        preparePdfPageLayout(document, 2)
        assertEquals(listOf(0, 1, 2, 3), pages)
        preparePdfPageLayout(document, 5)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), pages)
    }

    @Test fun `layout preparation leaves transient metadata errors for native retry`() = runTest {
        val source = source()
        whenever(source.getPageInfo(0)).thenAnswer { throw IOException("Worker restarting") }
            .thenReturn(PdfDocument.PageInfo(0, 800, 600))
        whenever(source.getPageInfo(1)).thenReturn(PdfDocument.PageInfo(1, 800, 600))
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        preparePdfPageLayout(document, 1)
        verify(source, never()).getPageInfo(1)
        preparePdfPageLayout(document, 1)
        verify(source, times(2)).getPageInfo(0)
        verify(source, times(1)).getPageInfo(1)
    }

    @Test fun `closing once releases the owner and cached dimensions cannot outlive it`() = runTest {
        val source = source()
        whenever(source.getPageInfo(2)).thenReturn(PdfDocument.PageInfo(2, 800, 600))
        val document = CachedPdfDocument(source, StandardTestDispatcher(testScheduler))
        document.getPageInfo(2)
        document.close()
        document.close()
        assertTrue(runCatching { document.getPageInfo(2) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { document.getPageBitmapSource(2) }.exceptionOrNull() is CancellationException)
        verify(source, times(1)).close()
    }
}
