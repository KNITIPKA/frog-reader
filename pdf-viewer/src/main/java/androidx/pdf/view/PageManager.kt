/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by FrogReader (2026): bounded render-ahead and persistent page previews.
// See pdf-viewer/UPSTREAM.md and docs/pdf-support.md for provenance and upgrade checks.
package androidx.pdf.view

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.util.Range
import android.util.Size
import android.util.SparseArray
import androidx.core.util.isEmpty
import androidx.core.util.keyIterator
import androidx.core.util.valueIterator
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.models.FormWidgetInfo
import androidx.pdf.ocr.OcrContextRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Manages a collection of [Page]s, each representing a single PDF page. Receives events to update
 * pages' internal state, and produces events via a [SharedFlow] of type [Unit] to signal the host
 * [PdfView] to invalidate itself when any page needs to be redrawn. Operations like drawing pages
 * and handling touch events on pages may be delegated to this manager.
 *
 * Not thread safe
 */
internal class PageManager(
    private val pdfDocument: PdfDocument,
    private val backgroundScope: CoroutineScope,
    /**
     * The maximum size of any single [android.graphics.Bitmap] we render for a page, i.e. the
     * threshold for tiled rendering
     */
    private val maxBitmapSizePx: Point,
    /** Error flow for propagating error occurred while processing to [PdfView]. */
    private val errorFlow: MutableSharedFlow<Throwable>,
    isAccessibilityEnabled: Boolean,
    ocrContextRepository: OcrContextRepository? = null,
) {
    private var _ocrContextRepository: OcrContextRepository? = ocrContextRepository

    /** Sets the [OcrContextRepository] and updates all existing pages. */
    internal fun setOcrContextRepository(value: OcrContextRepository?) {
        _ocrContextRepository = value
        for (page in pages.valueIterator()) {
            page.setOcrContextRepository(value)
        }
    }

    /**
     * Replay at least 1 value in case of an invalidation signal issued while [PdfView] is not
     * collecting
     */
    private val _invalidationSignalFlow = MutableSharedFlow<Unit>(replay = 1)

    /**
     * This [SharedFlow] serves as an event bus of sorts to signal our host [PdfView] to invalidate
     * itself in a decoupled way. It is of type [Unit] because the reason for invalidation is
     * inconsequential. The model is: we update the data that affects what will be drawn, we signal
     * [PdfView] to invalidate itself, and the relevant changes in state will be reflected in the
     * next call to [PdfView.onDraw]
     */
    val invalidationSignalFlow: SharedFlow<Unit>
        get() = _invalidationSignalFlow

    internal val pages = SparseArray<Page>()
    private val renderQueue = PdfRenderQueue()
    private val renderAhead = RenderAheadPolicy()
    private val sizes = mutableMapOf<Int, RenderAheadPolicy.PageSize>()
    private var retained = emptySet<Int>()
    private data class Viewport(val areas: SparseArray<RectF>, val zoom: Float, val stable: Boolean,
        val paused: Boolean, val top: Float, val width: Int, val details: SparseArray<RectF>,
        val protectIncoming: Boolean)
    private var lastViewport: Viewport? = null

    internal fun hasVisiblePageContent(range: Range<Int>): Boolean =
        (range.lower..range.upper).all { pages[it]?.hasContent() == true }

    private val _pageTextReadyFlow = MutableSharedFlow<Int>(replay = 1)
    val pageTextReadyFlow: SharedFlow<Int>
        get() = _pageTextReadyFlow

    /**
     * [REPLAY_COUNT_FOR_BITMAP_STATUS] is set to 1 to ensure that if [PdfView] starts collecting
     * late, it immediately receives the status of the most recently processed page.
     *
     * [BUFFER_CAPACITY_FOR_BITMAP_STATUS] provides a cushion so that [MutableSharedFlow.emit] calls
     * from background threads don't suspend immediately if the UI thread is busy with a layout
     * pass.
     */
    private var _bitmapUpdatedFlow: MutableSharedFlow<PageBitmapState> =
        MutableSharedFlow(
            replay = REPLAY_COUNT_FOR_BITMAP_STATUS,
            extraBufferCapacity = BUFFER_CAPACITY_FOR_BITMAP_STATUS,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /**
     * This [SharedFlow] signals [PdfView] that bitmaps are either ready or cleared for the page.
     */
    val bitmapUpdatedFlow: SharedFlow<PageBitmapState>
        get() = _bitmapUpdatedFlow

    internal var isAccessibilityEnabled: Boolean = isAccessibilityEnabled
        set(value) {
            field = value
            for (page in pages.valueIterator()) {
                page.isAccessibilityEnabled = value
            }
        }

    internal fun areAllVisiblePagesFullyRendered(
        visiblePageRange: Range<Int>,
        zoom: Float,
        visiblePageAreas: SparseArray<RectF>?,
    ): Boolean =
        (visiblePageRange.lower..visiblePageRange.upper).all { pageNum ->
            pages[pageNum]?.isFullyRendered(zoom, visiblePageAreas?.get(pageNum)) ?: false
        }

    /**
     * [Highlight]s supplied by the developer to be drawn along with the pages they belong to
     *
     * We store these in a map keyed by page number for more efficient lookup at drawing time, even
     * though each [Highlight] contains its own page number.
     */
    private val highlights: MutableMap<Int, MutableList<Highlight>> = mutableMapOf()

    /**
     * Updates the visibility state of [Page]s owned by this manager.
     *
     * @param visiblePageAreas the visible area of each visible page, in page coordinates
     * @param currentZoomLevel the current zoom level
     * @param stablePosition true if we don't believe our position is actively changing
     * @param pauseBitmapFetch true if we should avoid fetching Bitmaps, regardless of current
     *   visibility
     */
    fun updatePageVisibilities(
        visiblePageAreas: SparseArray<RectF>,
        currentZoomLevel: Float,
        stablePosition: Boolean,
        pauseBitmapFetch: Boolean,
        viewportTopPx: Float,
        viewportWidthPx: Int,
        detailedAreas: SparseArray<RectF> = SparseArray(),
        protectIncomingPreviews: Boolean = false,
    ) {
        if (visiblePageAreas.isEmpty()) return
        lastViewport = Viewport(visiblePageAreas, currentZoomLevel, stablePosition,
            pauseBitmapFetch, viewportTopPx, viewportWidthPx, detailedAreas, protectIncomingPreviews)
        val first = visiblePageAreas.keyAt(0)
        val last = visiblePageAreas.keyAt(visiblePageAreas.size() - 1)
        val previews = renderAhead.plan(first, last, pdfDocument.pageCount, viewportTopPx,
            android.os.SystemClock.uptimeMillis(), viewportWidthPx, currentZoomLevel,
            renderQueue.renderMillis, includeNeighbors = !pauseBitmapFetch) { sizes[it] }
        val visible = visiblePageAreas.keyIterator().asSequence().toSet()
        val detailPages = detailedAreas.keyIterator().asSequence().take(8).toSet()
        val desired = visible + previews.map { it.page } + detailPages
        // Enlarged pans often stay within the current sheet. Boost incoming previews only when
        // the prediction reaches another page. At fit, preview planning handles page crossings.
        val incomingPage = detailPages.isEmpty() || detailPages.any { it !in visible }
        renderQueue.update(first, last, (previews.map { it.page } + detailPages).distinct(),
            visible.associateWith { RectF(visiblePageAreas[it]) }, protectIncomingPreviews && incomingPage)
        // At most eight speculative tiles across the viewport, and no more than four per page.
        var remaining = 8
        val detailLimits = detailPages.mapIndexed { index, page ->
            val limit = minOf(4, remaining / (detailPages.size - index))
            remaining -= limit
            page to limit
        }.toMap()
        // Only visit the small rendered working set, never every page of a long document.
        for (page in retained - desired) {
            pages[page]?.setInvisible()
        }
        for (preview in previews) {
            pages[preview.page]?.preparePreview(Size(preview.width, preview.height))
            if (preview.page !in visible && preview.page !in detailPages) pages[preview.page]?.retainPreviewOnly()
        }
        for (page in visible) pages[page]?.setVisible(currentZoomLevel,
            visiblePageAreas[page], stablePosition, pauseBitmapFetch, detailedAreas[page], detailLimits[page] ?: 0)
        if (!pauseBitmapFetch) for (page in detailPages - visible) {
            pages[page]?.prepareDetail(currentZoomLevel, detailedAreas[page], detailLimits.getValue(page))
        }
        retained = desired
    }

    /**
     * Invalidates the given [areasToUpdate] for the [Page] at [pageNum].
     *
     * This function checks if the union of [areasToUpdate] intersects with the [visibleArea]. If
     * there's an intersection, it updates the specific page to invalidate the intersecting area.
     */
    fun maybeInvalidateAreas(
        pageNum: Int,
        visibleArea: RectF,
        currentZoom: Float,
        areasToUpdate: List<RectF>,
    ) {
        if (areasToUpdate.isEmpty()) return
        val invalidatedArea = areasToUpdate.union()
        if (invalidatedArea.intersect(visibleArea)) {
            // If there is some intersection in the visible area and the invalidated area,
            // invalidatedArea is updated to hold the intersection.
            pages[pageNum]?.maybeInvalidateAreas(currentZoom, invalidatedArea)
        }
    }

    /**
     * Updates the set of [Page]s owned by this manager when a new Page's dimensions are loaded.
     * Dimensions are the minimum data required to instantiate a page.
     */
    fun addPage(
        pageNum: Int,
        size: Point,
        currentZoomLevel: Float,
        stablePosition: Boolean,
        viewArea: RectF? = null,
        pauseBitmapFetch: Boolean,
        pdfFormFillingConfig: PdfFormFillingConfig,
        formWidgetInfos: List<FormWidgetInfo>? = null,
    ) {
        if (pages.indexOfKey(pageNum) >= 0) return
        val page =
            Page(
                    pageNum,
                    size,
                    pdfDocument,
                    backgroundScope,
                    maxBitmapSizePx,
                    onBitmapReady = {
                        backgroundScope.launch {
                            _bitmapUpdatedFlow.emit(PageBitmapState.PageBitmapReady(pageNum))
                        }
                        _invalidationSignalFlow.tryEmit(Unit)
                    },
                    onFormWidgetReady = { _invalidationSignalFlow.tryEmit(Unit) },
                    onPageTextReady = { pageNumber -> _pageTextReadyFlow.tryEmit(pageNumber) },
                    errorFlow = errorFlow,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    pdfFormFillingConfig = pdfFormFillingConfig,
                    formWidgetInfos = formWidgetInfos,
                    onBitmapCleared = {
                        backgroundScope.launch {
                            _bitmapUpdatedFlow.emit(PageBitmapState.PageBitmapCleared(pageNum))
                        }
                    },
                    ocrContextRepository = _ocrContextRepository,
                    renderQueue = renderQueue,
                )
                .apply {
                    // If the page is visible, let it know
                    if (viewArea != null) {
                        setVisible(currentZoomLevel, viewArea, stablePosition, pauseBitmapFetch)
                    }
                }
        pages.put(pageNum, page)
        sizes[pageNum] = RenderAheadPolicy.PageSize(size.x, size.y)
        // A neighbour's dimensions can arrive without changing the visible range.
        lastViewport?.let { viewport ->
            val first = viewport.areas.keyAt(0)
            val last = viewport.areas.keyAt(viewport.areas.size() - 1)
            if (pageNum in (first - 6)..(last + 6)) updatePageVisibilities(viewport.areas,
                viewport.zoom, viewport.stable, viewport.paused, viewport.top, viewport.width, viewport.details,
                viewport.protectIncoming)
        }
    }

    fun maybeLoadFormWidgetMetadata(formWidgetMetadataLoader: FormWidgetMetadataLoader) {
        pages.valueIterator().forEach {
            if (it.formWidgetInfos?.isEmpty() == true) {
                it.maybeUpdateFormWidgetInfos(formWidgetMetadataLoader)
            }
        }
    }

    /** Updates the form widget information in the given [pageNum] when a edit is applied. */
    fun maybeUpdateFormWidgetMetadata(
        pageNum: Int,
        formWidgetMetadataLoader: FormWidgetMetadataLoader,
    ) {
        pages[pageNum]?.maybeUpdateFormWidgetInfos(formWidgetMetadataLoader)
    }

    /** Adds [newHighlights]s to this manager to be drawn along with the pages they belong to */
    fun setHighlights(newHighlights: List<Highlight>) {
        // Prevent extra invalidation of Pdfview on new pdf load by setting empty highlights
        if (highlights.isEmpty() && newHighlights.isEmpty()) {
            return
        }
        highlights.clear()
        for (highlight in newHighlights) {
            highlights.getOrPut(highlight.area.pageNum) { mutableListOf() }.add(highlight)
        }
        _invalidationSignalFlow.tryEmit(Unit)
    }

    /** Draws the [Page] at [pageNum] to the canvas at [locationInView] */
    fun drawPage(pageNum: Int, canvas: Canvas, locationInView: RectF,
        placeholder: PageLoadingPlaceholder): Boolean {
        val highlightsForPage = highlights[pageNum] ?: EMPTY_HIGHLIGHTS
        return pages.get(pageNum)?.draw(canvas, locationInView, highlightsForPage, placeholder) ?: run {
            placeholder.draw(canvas, locationInView)
            false
        }
    }

    /**
     * Sets all [Page]s owned by this manager to invisible, i.e. to reduce memory when the host
     * [PdfView] is not in an interactive state.
     */
    fun cleanup() {
        for (page in retained) pages[page]?.setInvisible()
        retained = emptySet()
        lastViewport = null
    }

    fun getPageLinks(pageNum: Int): PdfDocument.PdfPageLinks? {
        return pages[pageNum]?.links
    }

    fun getWidgetAtTapPoint(pdfPoint: PdfPoint): List<FormWidgetInfo>? {
        return pages[pdfPoint.pageNum]?.formWidgetInfos
    }

    private fun List<RectF>.union(): RectF {
        if (isEmpty()) return RectF()
        val unionRect = RectF()
        for (rect in this) {
            unionRect.union(rect)
        }
        return unionRect
    }

    companion object {
        private const val BUFFER_CAPACITY_FOR_BITMAP_STATUS = 64
        private const val REPLAY_COUNT_FOR_BITMAP_STATUS = 1
    }
}

/** Constant empty list to avoid allocations during drawing */
private val EMPTY_HIGHLIGHTS = listOf<Highlight>()


/** Represents the state of bitmap for the specified page. */
internal sealed interface PageBitmapState {
    val pageNum: Int

    /** Represents that bitmap has been fetched and is available for use. */
    data class PageBitmapReady(override val pageNum: Int) : PageBitmapState

    /** Represents that bitmap has been cleared from the memory. */
    data class PageBitmapCleared(override val pageNum: Int) : PageBitmapState
}
