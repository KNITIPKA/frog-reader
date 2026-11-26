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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.RemoteException
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.core.graphics.ColorUtils
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.exceptions.RequestFailedException
import androidx.pdf.exceptions.RequestMetadata
import androidx.pdf.models.FormWidgetInfo
import androidx.pdf.ocr.OcrContextRepository
import androidx.pdf.ocr.getAllText
import androidx.pdf.util.ExceptionUtils.isHandledRemoteException
import androidx.pdf.util.PAGE_CONTENTS_REQUEST_NAME
import androidx.pdf.util.PAGE_LINKS_REQUEST_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** A single PDF page that knows how to render and draw itself */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@MainThread
internal class Page(
    /** The 0-based index of this page in the PDF */
    private val pageNum: Int,
    /** The size of this PDF page, in content coordinates */
    private val pageSize: Point,
    /** The [PdfDocument] this [Page] belongs to */
    private val pdfDocument: PdfDocument,
    /** The [CoroutineScope] to use for background work */
    private val backgroundScope: CoroutineScope,
    /**
     * The maximum size of any single [android.graphics.Bitmap] we render for a page, i.e. the
     * threshold for tiled rendering
     */
    private val maxBitmapSizePx: Point,
    /** A function to call when the bitmap of the page is ready (invoked with page number). */
    private val onBitmapReady: (Int) -> Unit,
    /** A function to call when form widgets are ready (invoked with page number). */
    private val onFormWidgetReady: (Int) -> Unit,
    /** A function to call when page text is ready (invoked with page number). */
    private val onPageTextReady: ((Int) -> Unit),
    /** Error flow for propagating error occurred while processing to [PdfView]. */
    private val errorFlow: MutableSharedFlow<Throwable>,
    isAccessibilityEnabled: Boolean,
    /** A list represent the [FormWidgetInfo] present on the page. */
    formWidgetInfos: List<FormWidgetInfo>? = null,
    private val pdfFormFillingConfig: PdfFormFillingConfig,
    private val onBitmapCleared: (Int) -> Unit,
    ocrContextRepository: OcrContextRepository? = null,
    private val renderQueue: PdfRenderQueue,
) {
    init {
        require(pageNum >= 0) { "Invalid negative page" }
    }

    private var _ocrContextRepository: OcrContextRepository? = ocrContextRepository

    internal fun setOcrContextRepository(value: OcrContextRepository?) {
        _ocrContextRepository = value
        if (value != null && isAccessibilityEnabled) {
            fetchTextJob?.cancel()
            maybeFetchText()
        }
    }

    /** Handles rendering bitmaps for this page using [PdfDocument] */
    private var bitmapFetcher: BitmapFetcher? = null
    // Pre-allocated values to avoid allocations at drawing time
    private val highlightPaint =
        Paint().apply {
            style = Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            isAntiAlias = true
            isDither = true
        }
    private val highlightRect = RectF()
    private val formWidgetHighlightRect = RectF()

    private var fetchTextJob: Job? = null

    internal var pageText: String? = null
        private set

    internal var ocrText: String? = null
        private set

    private val isTextReady: Boolean
        get() {
            val isPageTextReady =
                !pdfDocument.isFeatureSupported(PdfFeature.TEXT_EXTRACTION) || pageText != null
            val isOcrTextReady = _ocrContextRepository == null || ocrText != null
            return isPageTextReady && isOcrTextReady
        }

    private var fetchLinksJob: Job? = null
    internal var links: PdfDocument.PdfPageLinks? = null
        private set

    private var fetchFormWidgetInfoJob: Job? = null

    internal var isAccessibilityEnabled: Boolean = isAccessibilityEnabled
        set(value) {
            field = value
            if (value) {
                maybeFetchText()
            }
        }

    internal var formWidgetInfos: List<FormWidgetInfo>? = formWidgetInfos
        private set(value) {
            field = value
            formWidgetIndexToInfoMap = value?.associateBy { it.widgetIndex }
        }

    internal var formWidgetIndexToInfoMap: Map<Int, FormWidgetInfo>? =
        formWidgetInfos?.associateBy { it.widgetIndex }
        private set

    //  Checks if the content of this page within the specified visible area is fully rendered.
    internal fun isFullyRendered(zoom: Float, viewArea: RectF?): Boolean {
        return bitmapFetcher?.isFullyRendered(zoom, viewArea) ?: false
    }

    /**
     * Puts this page into a "visible" state, and / or updates various properties related to the
     * page's visible state
     *
     * @param zoom the current scale
     * @param viewArea the portion of the page that's visible, in content coordinates
     * @param stablePosition true if position is not actively changing, e.g. during a fling
     * @param pauseBitmapFetch true if we should wait to fetch Bitmaps
     */
    fun setVisible(
        zoom: Float,
        viewArea: RectF,
        stablePosition: Boolean = true,
        pauseBitmapFetch: Boolean = false,
        aheadArea: RectF? = null,
        aheadTileLimit: Int = 0,
    ) {
        ensureBitmapFetcher()
        if (!pauseBitmapFetch) {
            bitmapFetcher?.maybeFetchNewBitmaps(zoom, viewArea, aheadArea = aheadArea, aheadTileLimit = aheadTileLimit)
        }
        if (stablePosition) {
            maybeFetchLinks()
            maybeFetchText()
        }
    }

    private fun ensureBitmapFetcher() {
        if (bitmapFetcher == null) {
            bitmapFetcher =
                BitmapFetcher(
                    pageNum,
                    pageSize,
                    pdfDocument,
                    backgroundScope,
                    maxBitmapSizePx,
                    onBitmapReady,
                    errorFlow,
                    renderQueue,
                )
        }
    }

    fun preparePreview(size: android.util.Size) {
        ensureBitmapFetcher()
        bitmapFetcher?.preparePreview(size)
    }

    fun hasContent(): Boolean = bitmapFetcher?.hasContent() == true

    fun retainPreviewOnly() { bitmapFetcher?.retainPreviewOnly() }

    fun prepareDetail(zoom: Float, area: RectF, tileLimit: Int) {
        ensureBitmapFetcher()
        bitmapFetcher?.maybeFetchNewBitmaps(zoom, RectF(), aheadArea = area, aheadTileLimit = tileLimit)
    }

    /**
     * Invalidates and fetches new bitmaps for the [invalidatedArea] of the page.
     *
     * @param zoom the current scale
     * @param invalidatedArea visible portion of the page which has been invalidated
     */
    fun maybeInvalidateAreas(zoom: Float, invalidatedArea: RectF) {
        bitmapFetcher?.maybeFetchNewBitmaps(zoom, invalidatedArea, hasFormStateChanged = true)
    }

    /** Puts this page into an "invisible" state, i.e. retaining only the minimum data required */
    fun setInvisible() {
        if (bitmapFetcher != null) {
            // Bitmaps are managed by BitmapFetcher; only signal clearing if it was active for this
            // page.
            onBitmapCleared(pageNum)
        }
        bitmapFetcher?.close()
        bitmapFetcher = null
        pageText = null
        ocrText = null
        fetchTextJob?.cancel()
        fetchTextJob = null
        links = null
        fetchLinksJob?.cancel()
        fetchLinksJob = null
    }

    private fun maybeFetchText() {
        if (!isAccessibilityEnabled || fetchTextJob?.isActive == true || isTextReady) return

        fetchTextJob =
            backgroundScope.launch {
                joinAll(launch { maybeFetchPageText() }, launch { maybeFetchOcrText() })
                onPageTextReady.invoke(pageNum)
            }
    }

    private suspend fun maybeFetchPageText() {
        if (!pdfDocument.isFeatureSupported(PdfFeature.TEXT_EXTRACTION) || pageText != null) return

        try {
            pageText = pdfDocument.getPageContent(pageNum)?.textContents?.joinToString { it.text }
        } catch (e: RemoteException) {
            if (!e.isHandledRemoteException) throw e

            val exception =
                RequestFailedException(
                    requestMetadata =
                        RequestMetadata(
                            requestName = PAGE_CONTENTS_REQUEST_NAME,
                            pageRange = pageNum..pageNum,
                        ),
                    throwable = e,
                )
            errorFlow.emit(exception)
        }
    }

    private suspend fun maybeFetchOcrText() {
        if (ocrText != null) return
        val localOcrContextRepository = _ocrContextRepository ?: return

        ocrText =
            localOcrContextRepository
                .getOcrContexts(pageNum)
                .map { it.getAllText().text }
                .joinToString(separator = "\n")
    }

    /** Updates the [formWidgetInfos] associated with the page. */
    internal fun maybeUpdateFormWidgetInfos(formWidgetMetadataLoader: FormWidgetMetadataLoader) {
        val previousJob = fetchFormWidgetInfoJob

        fetchFormWidgetInfoJob =
            backgroundScope.launch {
                // Cancel the previous job, since we want to fetch the latest set of widgets
                previousJob?.cancelAndJoin()
                ensureActive()
                formWidgetInfos = formWidgetMetadataLoader.loadFormWidgetInfos(pageNum)
                onFormWidgetReady(pageNum)
            }
    }

    fun draw(canvas: Canvas, locationInView: RectF, highlights: List<Highlight>,
        placeholder: PageLoadingPlaceholder): Boolean {
        val animating = bitmapFetcher?.draw(canvas, locationInView, placeholder) ?: run {
            placeholder.draw(canvas, locationInView)
            false
        }
        for (highlight in highlights) {
            // Highlight locations are defined in content coordinates, compute their location
            // in View coordinates using locationInView
            highlightRect.set(
                highlight.area.left,
                highlight.area.top,
                highlight.area.right,
                highlight.area.bottom,
            )
            highlightRect.offset(locationInView.left, locationInView.top)
            // Porter-Duff MULTIPLY also multiplies alpha. Fold opacity into the tint
            // against white first so multiplication preserves page luminance and black ink.
            highlightPaint.color = ColorUtils.compositeColors(highlight.color, Color.WHITE)
            canvas.drawRect(highlightRect, highlightPaint)
        }

        if (pdfFormFillingConfig.isFormFillingEnabled()) {
            formWidgetInfos
                ?.filter { !it.isReadOnly }
                ?.forEach {
                    formWidgetHighlightRect.set(it.widgetRect)
                    formWidgetHighlightRect.offset(locationInView.left, locationInView.top)
                    highlightPaint.color = ColorUtils.compositeColors(
                        pdfFormFillingConfig.formFieldsHighlightColor, Color.WHITE,
                    )
                    canvas.drawRect(formWidgetHighlightRect, highlightPaint)
                }
        }
        return animating
    }

    private fun maybeFetchLinks() {
        if (!pdfDocument.isFeatureSupported(PdfFeature.LINKS)) return
        if (fetchLinksJob?.isActive == true || links != null) return
        fetchLinksJob =
            backgroundScope
                .launch {
                    ensureActive()
                    try {
                        links = pdfDocument.getPageLinks(pageNum)
                    } catch (e: RemoteException) {
                        if (!e.isHandledRemoteException) throw e

                        val exception =
                            RequestFailedException(
                                requestMetadata =
                                    RequestMetadata(
                                        requestName = PAGE_LINKS_REQUEST_NAME,
                                        pageRange = pageNum..pageNum,
                                    ),
                                throwable = e,
                            )
                        errorFlow.emit(exception)
                    }
                }
                .also { it.invokeOnCompletion { fetchLinksJob = null } }
    }


}

/** Constant [Paint]s used in drawing */
internal val BMP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

internal val BLANK_PAINT =
    Paint().apply {
        color = Color.WHITE
        style = Style.FILL
    }
