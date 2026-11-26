@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import android.util.SparseArray
import androidx.pdf.PdfDocument
import androidx.pdf.annotation.content.HighlightAnnotation
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.annotation.content.KeyedPdfObject
import androidx.pdf.annotation.content.PdfObject
import androidx.pdf.annotation.content.StampAnnotation
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.PdfPageGotoLinkContent
import androidx.pdf.content.PdfPageLinkContent
import androidx.pdf.content.SelectionBoundary
import androidx.pdf.models.FormWidgetInfo
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Presents one source page as page 0 while AndroidX continues to own rendering and selection.
 *
 * This is a borrowed view of the session's document: disposing it must never close the source.
 * Page indices returned to AndroidX are local; the reader maps positions and saved selections
 * back to [sourcePageIndex] at its boundary.
 */
internal class SinglePagePdfDocument(
    private val delegate: PdfDocument,
    val sourcePageIndex: Int,
) : PdfDocument by delegate {
    init {
        require(sourcePageIndex in 0 until delegate.pageCount) { "PDF source page out of range" }
    }

    override val pageCount: Int = 1
    private val invalidationListeners = IdentityHashMap<PdfDocument.OnPdfContentInvalidatedListener,
        PdfDocument.OnPdfContentInvalidatedListener>()
    private val editListeners = IdentityHashMap<PdfDocument.OnEditAppliedListener, PdfDocument.OnEditAppliedListener>()
    private val navigationToken = UUID.randomUUID().toString()
    private val internalLinks = ConcurrentHashMap<String, PdfPageGotoLinkContent.Destination>()

    private fun sourcePage(localPage: Int): Int {
        require(localPage == 0) { "Single-page PDF has only page 0" }
        return sourcePageIndex
    }

    private fun validateRange(localRange: IntRange) {
        require(localRange.isEmpty() || localRange.first == 0 && localRange.last == 0) {
            "Single-page PDF has only page 0"
        }
    }

    private fun PdfDocument.PageInfo.toLocal() =
        PdfDocument.PageInfo(0, height, width, formWidgetInfos)

    private fun PageSelection.toLocal() = PageSelection(0, start, stop, selectedContents)

    override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo =
        delegate.getPageInfo(sourcePage(pageNumber)).toLocal()

    override suspend fun getPageInfo(pageNumber: Int, pageInfoFlags: Long): PdfDocument.PageInfo =
        delegate.getPageInfo(sourcePage(pageNumber), pageInfoFlags).toLocal()

    override suspend fun getPageInfos(pageRange: IntRange): List<PdfDocument.PageInfo> {
        validateRange(pageRange)
        return if (pageRange.isEmpty()) emptyList() else listOf(getPageInfo(0))
    }

    override suspend fun getPageInfos(pageRange: IntRange, pageInfoFlags: Long): List<PdfDocument.PageInfo> {
        validateRange(pageRange)
        return if (pageRange.isEmpty()) emptyList() else listOf(getPageInfo(0, pageInfoFlags))
    }

    override suspend fun searchDocument(query: String, pageRange: IntRange): SparseArray<List<PageMatchBounds>> {
        validateRange(pageRange)
        val result = SparseArray<List<PageMatchBounds>>()
        if (!pageRange.isEmpty()) {
            val matches = delegate.searchDocument(query, sourcePageIndex..sourcePageIndex)[sourcePageIndex]
            if (!matches.isNullOrEmpty()) result.put(0, matches)
        }
        return result
    }

    override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, stop: PointF): PageSelection? =
        delegate.getSelectionBounds(sourcePage(pageNumber), start, stop)?.toLocal()

    override suspend fun getSelectionBounds(
        pageNumber: Int, start: SelectionBoundary, stop: SelectionBoundary,
    ): PageSelection? = delegate.getSelectionBounds(sourcePage(pageNumber), start, stop)?.toLocal()

    override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection? =
        delegate.getSelectAllSelectionBounds(sourcePage(pageNumber))?.toLocal()

    override suspend fun getPageContent(pageNumber: Int): PdfDocument.PdfPageContent? =
        delegate.getPageContent(sourcePage(pageNumber))

    override suspend fun getPageLinks(pageNumber: Int): PdfDocument.PdfPageLinks {
        val source = delegate.getPageLinks(sourcePage(pageNumber))
        // beta01 has no public goto callback. Route these links through its public URL callback,
        // using unpredictable per-adapter tokens rather than trusting a PDF-provided URL payload.
        val routed = source.gotoLinks.mapIndexedNotNull { index, link ->
            if (link.destination.pageNumber !in 0 until delegate.pageCount) return@mapIndexedNotNull null
            val url = "frogreader-pdf-page://$navigationToken/$index"
            internalLinks[url] = link.destination
            PdfPageLinkContent(link.bounds, Uri.parse(url))
        }
        return PdfDocument.PdfPageLinks(gotoLinks = emptyList(), externalLinks = routed + source.externalLinks)
    }

    /** Only URLs generated by this adapter can resolve to an internal destination. */
    fun resolveInternalLink(uri: Uri): PdfPageGotoLinkContent.Destination? = internalLinks[uri.toString()]

    override suspend fun getAnnotationsForPage(pageNum: Int): List<KeyedPdfAnnotation> =
        delegate.getAnnotationsForPage(sourcePage(pageNum)).mapNotNull { keyed ->
            val annotation = when (val source = keyed.annotation) {
                is HighlightAnnotation -> HighlightAnnotation(0, source.bounds, source.color)
                is StampAnnotation -> StampAnnotation(0, source.bounds, source.pdfObjects)
                // These are the two production annotation types in the pinned beta01 API.
                else -> return@mapNotNull null
            }
            KeyedPdfAnnotation(keyed.key, annotation)
        }

    override suspend fun getPageObjects(pageNum: Int, types: Long): List<KeyedPdfObject> =
        delegate.getPageObjects(sourcePage(pageNum), types)

    override suspend fun getTopPageObjectAtPosition(pageNum: Int, point: PointF): PdfObject? =
        delegate.getTopPageObjectAtPosition(sourcePage(pageNum), point)

    override suspend fun getFormWidgetInfos(pageNum: Int, types: Long): List<FormWidgetInfo> =
        delegate.getFormWidgetInfos(sourcePage(pageNum), types)

    override fun getPageBitmapSource(pageNumber: Int): PdfDocument.BitmapSource {
        val source = delegate.getPageBitmapSource(sourcePage(pageNumber))
        return object : PdfDocument.BitmapSource {
            override val pageNumber: Int = 0
            override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap =
                source.getBitmap(scaledPageSizePx, tileRegion)
            override fun close() = source.close()
        }
    }

    override fun addOnPdfContentInvalidatedListener(
        executor: Executor, listener: PdfDocument.OnPdfContentInvalidatedListener,
    ) {
        synchronized(invalidationListeners) {
            if (invalidationListeners.containsKey(listener)) return
            val mapped = object : PdfDocument.OnPdfContentInvalidatedListener {
                override fun onPdfContentInvalidated(pageNumber: Int, dirtyAreas: List<Rect>) {
                    if (pageNumber == sourcePageIndex) listener.onPdfContentInvalidated(0, dirtyAreas)
                }
            }
            invalidationListeners[listener] = mapped
            delegate.addOnPdfContentInvalidatedListener(executor, mapped)
        }
    }

    override fun removeOnPdfContentInvalidatedListener(listener: PdfDocument.OnPdfContentInvalidatedListener) {
        synchronized(invalidationListeners) {
            invalidationListeners.remove(listener)?.let(delegate::removeOnPdfContentInvalidatedListener)
        }
    }

    override fun addOnEditAppliedListener(executor: Executor, listener: PdfDocument.OnEditAppliedListener) {
        synchronized(editListeners) {
            if (editListeners.containsKey(listener)) return
            val mapped = object : PdfDocument.OnEditAppliedListener {
                override fun onEditApplied(pageNum: Int, editId: String) {
                    if (pageNum == sourcePageIndex) listener.onEditApplied(0, editId)
                }
            }
            editListeners[listener] = mapped
            delegate.addOnEditAppliedListener(executor, mapped)
        }
    }

    override fun removeOnEditAppliedListener(listener: PdfDocument.OnEditAppliedListener) {
        synchronized(editListeners) {
            editListeners.remove(listener)?.let(delegate::removeOnEditAppliedListener)
        }
    }

    /** Release adapter callbacks only; the session retains the opened source document. */
    override fun close() {
        synchronized(invalidationListeners) {
            invalidationListeners.values.forEach(delegate::removeOnPdfContentInvalidatedListener)
            invalidationListeners.clear()
        }
        synchronized(editListeners) {
            editListeners.values.forEach(delegate::removeOnEditAppliedListener)
            editListeners.clear()
        }
        internalLinks.clear()
    }
}
