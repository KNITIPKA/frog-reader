@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Size
import android.util.SparseArray
import androidx.pdf.PdfDocument
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.annotation.content.KeyedPdfObject
import androidx.pdf.annotation.content.PdfObject
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.SelectionBoundary
import androidx.pdf.models.FormWidgetInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Session-wide dimensions shared by Scroll and every Pages sheet. AndroidX beta01 otherwise
 * reopens the same native page for layout, fitting, saved positions and each replacement viewer.
 * Native calls are serialized: its metadata/text helpers can close a cached page being rendered
 * by another Binder request, and older Android renderers allow only one open page at a time.
 */
internal class CachedPdfDocument(
    private val delegate: PdfDocument,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PdfDocument by delegate {
    private val dimensions = ConcurrentHashMap<Int, PdfDocument.PageInfo>()
    private val nativeCalls = Mutex()
    private val closed = AtomicBoolean(false)

    private fun checkOpen() {
        if (closed.get()) throw PdfDocument.DocumentClosedException()
    }

    private suspend fun <T> nativeCall(block: suspend () -> T): T = withContext(dispatcher) {
        nativeCalls.withLock {
            checkOpen()
            block()
        }
    }

    override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
        checkOpen()
        dimensions[pageNumber]?.let { return it }
        return nativeCall {
            dimensions[pageNumber] ?: delegate.getPageInfo(pageNumber).also {
                if (it.width > 0 && it.height > 0) dimensions[pageNumber] = it
            }
        }
    }

    override suspend fun getPageInfo(pageNumber: Int, pageInfoFlags: Long): PdfDocument.PageInfo =
        if (pageInfoFlags == PdfDocument.PAGE_INFO_EXCLUDE_FORM_WIDGETS) getPageInfo(pageNumber)
        // Form values can change; cache only immutable page dimensions.
        else nativeCall { delegate.getPageInfo(pageNumber, pageInfoFlags) }

    override suspend fun getPageInfos(pageRange: IntRange): List<PdfDocument.PageInfo> =
        pageRange.map { getPageInfo(it) }

    override suspend fun getPageInfos(pageRange: IntRange, pageInfoFlags: Long): List<PdfDocument.PageInfo> =
        pageRange.map { getPageInfo(it, pageInfoFlags) }

    override suspend fun searchDocument(query: String, pageRange: IntRange): SparseArray<List<PageMatchBounds>> =
        nativeCall { delegate.searchDocument(query, pageRange) }

    override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, stop: PointF): PageSelection? =
        nativeCall { delegate.getSelectionBounds(pageNumber, start, stop) }

    override suspend fun getSelectionBounds(pageNumber: Int, start: SelectionBoundary, stop: SelectionBoundary): PageSelection? =
        nativeCall { delegate.getSelectionBounds(pageNumber, start, stop) }

    override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection? =
        nativeCall { delegate.getSelectAllSelectionBounds(pageNumber) }

    override suspend fun getPageContent(pageNumber: Int): PdfDocument.PdfPageContent? =
        nativeCall { delegate.getPageContent(pageNumber) }

    override suspend fun getPageLinks(pageNumber: Int): PdfDocument.PdfPageLinks =
        nativeCall { delegate.getPageLinks(pageNumber) }

    override suspend fun getAnnotationsForPage(pageNum: Int): List<KeyedPdfAnnotation> =
        nativeCall { delegate.getAnnotationsForPage(pageNum) }

    override suspend fun getPageObjects(pageNum: Int, types: Long): List<KeyedPdfObject> =
        nativeCall { delegate.getPageObjects(pageNum, types) }

    override suspend fun getTopPageObjectAtPosition(pageNum: Int, point: PointF): PdfObject? =
        nativeCall { delegate.getTopPageObjectAtPosition(pageNum, point) }

    override suspend fun getFormWidgetInfos(pageNum: Int, types: Long): List<FormWidgetInfo> =
        nativeCall { delegate.getFormWidgetInfos(pageNum, types) }

    override fun getPageBitmapSource(pageNumber: Int): PdfDocument.BitmapSource {
        checkOpen()
        val source = delegate.getPageBitmapSource(pageNumber)
        return object : PdfDocument.BitmapSource {
            private val released = AtomicBoolean(false)
            override val pageNumber: Int = pageNumber

            override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap {
                // BitmapSource.close in beta01 releases the native page asynchronously. An old
                // viewer's late release can race a new viewer of the same page even with our
                // request mutex. Retry that transient failure before AndroidX swallows it and
                // leaves a blank sheet; disposal/cancellation must still stop immediately.
                repeat(3) { attempt ->
                    coroutineContext.ensureActive()
                    check(!released.get()) { "PDF bitmap source is closed" }
                    var produced: Bitmap? = null
                    try {
                        return nativeCall {
                            check(!released.get()) { "PDF bitmap source is closed" }
                            source.getBitmap(scaledPageSizePx, tileRegion).also { produced = it }
                        }
                    } catch (cancelled: CancellationException) {
                        // withContext can discard a completed render when its caller is cancelled
                        // while dispatching back. Ownership has not reached AndroidX in that case.
                        produced?.recycle()
                        throw cancelled
                    } catch (failure: IllegalStateException) {
                        checkOpen()
                        coroutineContext.ensureActive()
                        if (released.get() || attempt == 2) throw failure
                        delay(16)
                    }
                }
                error("Unreachable PDF bitmap retry")
            }

            override fun close() {
                if (released.compareAndSet(false, true)) source.close()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dimensions.clear()
        delegate.close()
    }
}

/**
 * Warm just the prefix needed by AndroidX's sequential layout before a distant Scroll jump.
 * This performs no rendering and avoids one native IPC interleaved with each Main-thread layout
 * publication. A later jump or mode switch reuses these exact dimensions for this session.
 */
internal suspend fun preparePdfPageLayout(document: PdfDocument, throughPage: Int) {
    val cached = when (document) {
        is CachedPdfDocument -> document
        is StabilizedPdfDocument -> return preparePdfPageLayout(document.delegate, throughPage)
        is CompatibilityPdfDocument -> return preparePdfPageLayout(document.renderer, throughPage)
        else -> return
    }
    withContext(Dispatchers.IO) {
        for (page in 0..throughPage.coerceAtMost(cached.pageCount - 1)) {
            coroutineContext.ensureActive()
            try { cached.getPageInfo(page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withContext } // Let the native viewer retry on demand.
        }
    }
}
