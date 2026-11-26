@file:Suppress("RestrictedApi")

package com.example.frogreader.ui.reader.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.SparseArray
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import com.example.frogreader.data.pdf.compat.PdfTextBackend
import com.example.frogreader.data.pdf.compat.PdfTextBoundary
import com.example.frogreader.data.pdf.compat.PdfTextClient
import com.example.frogreader.data.pdf.compat.PdfTextRect
import com.example.frogreader.data.pdf.compat.PdfTextSelectionData
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * AndroidX still owns tiles, zoom, gestures, selection handles and highlight drawing. Only genuine
 * document-text operations are supplied by the isolated compatibility engine on older Android.
 *
 * Beta01's backend capability/boundary methods are JVM-public but RestrictTo. Keep this adapter
 * pinned and covered by integration tests on dependency upgrades; no reflection or SDK spoofing.
 */
@SuppressLint("RestrictedApi")
internal class CompatibilityPdfDocument(
    internal val renderer: PdfDocument,
    private val text: PdfTextBackend,
) : PdfDocument by renderer {
    private val closed = AtomicBoolean(false)
    private val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val selectionFailures = failures.asSharedFlow()

    init { require(renderer.pageCount == text.pageCount) { "PDF engines disagree about the page count" } }

    override fun isFeatureSupported(feature: PdfFeature): Boolean {
        if (closed.get()) return false
        return when (feature) {
            PdfFeature.TEXT_SELECTION, PdfFeature.SEARCH, PdfFeature.TEXT_EXTRACTION -> true
            else -> renderer.isFeatureSupported(feature)
        }
    }

    override suspend fun searchDocument(query: String, pageRange: IntRange): SparseArray<List<PageMatchBounds>> {
        checkOpen()
        val result = SparseArray<List<PageMatchBounds>>()
        for (page in pageRange) {
            val info = renderer.getPageInfo(page)
            val matches = text.search(page, info.width, info.height, query)
            if (matches.isNotEmpty()) result.put(page, matches.map { match ->
                PageMatchBounds(match.bounds.map(PdfTextRect::toRectF), match.textStartIndex)
            })
        }
        return result
    }

    override suspend fun getPageContent(pageNumber: Int): PdfDocument.PdfPageContent? {
        return try {
            checkOpen()
            val info = renderer.getPageInfo(pageNumber)
            val content = text.pageContent(pageNumber, info.width, info.height)
            PdfDocument.PdfPageContent(
                textContents = if (content.text.isEmpty()) emptyList() else listOf(
                    PdfPageTextContent(content.bounds.map(PdfTextRect::toRectF), content.text),
                ),
                imageContents = emptyList(),
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Accessibility extraction must never terminate rendering.
    }

    override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, stop: PointF): PageSelection? =
        select(pageNumber, PdfTextBoundary(x = start.x, y = start.y), PdfTextBoundary(x = stop.x, y = stop.y))

    override suspend fun getSelectionBounds(pageNumber: Int, start: SelectionBoundary, stop: SelectionBoundary): PageSelection? =
        select(pageNumber, start.toTextBoundary(), stop.toTextBoundary())

    override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection? =
        select(pageNumber, PdfTextBoundary(index = 0), PdfTextBoundary(index = Int.MAX_VALUE))

    private suspend fun select(page: Int, start: PdfTextBoundary, stop: PdfTextBoundary): PageSelection? =
        try {
            checkOpen()
            val info = renderer.getPageInfo(page)
            text.selection(page, info.width, info.height, start, stop)?.toPageSelection(page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // AndroidX only handles a subset of RemoteException types in its selection coroutine.
            // Report a user-action failure through FrogReader and retain the readable document.
            failures.tryEmit(Unit)
            null
        }

    private fun checkOpen() { if (closed.get()) throw IOException("PDF text document is closed") }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { text.close() } finally { renderer.close() }
    }
}

internal class StabilizedPdfDocument(
    val delegate: PdfDocument,
) : PdfDocument by delegate {
    private val pageLinesCache = java.util.concurrent.ConcurrentHashMap<Int, List<RectF>>()
    private val activeAnchors = java.util.concurrent.ConcurrentHashMap<Int, AnchorInfo>()

    val selectionFailures: kotlinx.coroutines.flow.SharedFlow<Unit>?
        get() = (delegate as? CompatibilityPdfDocument)?.selectionFailures

    override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, stop: PointF): PageSelection? {
        val isCaret = kotlin.math.abs(start.x - stop.x) <= 0.5f && kotlin.math.abs(start.y - stop.y) <= 0.5f
        if (isCaret) {
            val direct = delegate.getSelectionBounds(pageNumber, start, stop)
            if (direct != null) {
                recordAnchor(pageNumber, direct, null)
                return direct
            }
            // If direct caret hit failed (e.g. touch landed slightly in the inter-line space or margin),
            // snap to the nearest line center and retry once.
            val lines = getPageLines(pageNumber)
            if (lines.isNotEmpty()) {
                val snapped = snapPointToLines(lines, start.x, start.y)
                val retry = delegate.getSelectionBounds(pageNumber, snapped, snapped)
                if (retry != null) {
                    recordAnchor(pageNumber, retry, null)
                    return retry
                }
            }
            return null
        }

        // Handle drag: adjust start/stop coordinates
        val lines = getPageLines(pageNumber)
        val anchor = activeAnchors[pageNumber]
        var draggingEnd: Boolean? = null

        val (adjustedStart, adjustedStop) = when {
            anchor != null && isNearby(start, anchor.startRaw) -> {
                // Dragging the END handle: start is anchored at startInterior.
                // stop is dragged from endRaw -> shift y up by endYCorrection to match line center.
                draggingEnd = true
                val unshiftedY = stop.y - anchor.endYCorrection
                val snappedStop = snapPointToLines(lines, stop.x, unshiftedY)
                anchor.startInterior to snappedStop
            }
            anchor != null && isNearby(start, anchor.endRaw) -> {
                // Dragging the START handle: start is anchored at endInterior.
                // stop is dragged from startRaw -> shift y up by startYCorrection to match line center.
                draggingEnd = false
                val unshiftedY = stop.y - anchor.startYCorrection
                val snappedStop = snapPointToLines(lines, stop.x, unshiftedY)
                anchor.endInterior to snappedStop
            }
            else -> {
                val snappedStart = snapPointToLines(lines, start.x, start.y)
                val snappedStop = snapPointToLines(lines, stop.x, stop.y)
                snappedStart to snappedStop
            }
        }

        val result = delegate.getSelectionBounds(pageNumber, adjustedStart, adjustedStop)
        if (result != null) {
            recordAnchor(pageNumber, result, draggingEnd)
        }
        return result
    }

    override suspend fun getSelectionBounds(
        pageNumber: Int,
        start: SelectionBoundary,
        stop: SelectionBoundary,
    ): PageSelection? {
        val lines = getPageLines(pageNumber)
        val adjustedStart = if (start.point != null && lines.isNotEmpty()) {
            val pt = pointF(start.point!!.x.toFloat(), start.point!!.y.toFloat())
            val snapped = snapPointToLines(lines, pt.x, pt.y)
            SelectionBoundary(start.index, point(snapped.x.toInt(), snapped.y.toInt()), start.isRtl)
        } else start

        val adjustedStop = if (stop.point != null && lines.isNotEmpty()) {
            val pt = pointF(stop.point!!.x.toFloat(), stop.point!!.y.toFloat())
            val snapped = snapPointToLines(lines, pt.x, pt.y)
            SelectionBoundary(stop.index, point(snapped.x.toInt(), snapped.y.toInt()), stop.isRtl)
        } else stop

        return delegate.getSelectionBounds(pageNumber, adjustedStart, adjustedStop)
    }

    private suspend fun getPageLines(pageNumber: Int): List<RectF> {
        pageLinesCache[pageNumber]?.let { return it }
        val fullSelection = try {
            delegate.getSelectAllSelectionBounds(pageNumber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val rects = fullSelection?.selectedContents
            ?.filterIsInstance<PdfPageTextContent>()
            ?.flatMap { it.bounds }
            .orEmpty()
        val merged = mergeLines(rects)
        pageLinesCache[pageNumber] = merged
        return merged
    }

    private fun recordAnchor(pageNumber: Int, selection: PageSelection, draggingEnd: Boolean?) {
        val rects = selection.selectedContents.filterIsInstance<PdfPageTextContent>().flatMap { it.bounds }
        val firstRect = rects.firstOrNull() ?: return
        val lastRect = rects.lastOrNull() ?: return
        val previous = activeAnchors[pageNumber]

        val startCenterX = (firstRect.left + firstRect.right) / 2f
        val startCenterY = (firstRect.top + firstRect.bottom) / 2f
        val newStartRaw = pointF(firstRect.left, firstRect.bottom)
        val newStartInterior = pointF(minOf(firstRect.left + 1.5f, startCenterX), startCenterY)
        val newStartYCorr = (firstRect.bottom - firstRect.top) / 2f

        val endCenterX = (lastRect.left + lastRect.right) / 2f
        val endCenterY = (lastRect.top + lastRect.bottom) / 2f
        val newEndRaw = pointF(lastRect.right, lastRect.bottom)
        val newEndInterior = pointF(maxOf(lastRect.right - 0.6f, endCenterX), endCenterY)
        val newEndYCorr = (lastRect.bottom - lastRect.top) / 2f

        activeAnchors[pageNumber] = when (draggingEnd) {
            true -> {
                // Keep existing start anchor so continued dragging in this gesture stays pinned
                AnchorInfo(
                    startRaw = previous?.startRaw ?: newStartRaw,
                    startInterior = previous?.startInterior ?: newStartInterior,
                    startYCorrection = previous?.startYCorrection ?: newStartYCorr,
                    endRaw = newEndRaw,
                    endInterior = newEndInterior,
                    endYCorrection = newEndYCorr,
                )
            }
            false -> {
                // Keep existing end anchor so continued dragging in this gesture stays pinned
                AnchorInfo(
                    startRaw = newStartRaw,
                    startInterior = newStartInterior,
                    startYCorrection = newStartYCorr,
                    endRaw = previous?.endRaw ?: newEndRaw,
                    endInterior = previous?.endInterior ?: newEndInterior,
                    endYCorrection = previous?.endYCorrection ?: newEndYCorr,
                )
            }
            null -> {
                AnchorInfo(
                    startRaw = newStartRaw,
                    startInterior = newStartInterior,
                    startYCorrection = newStartYCorr,
                    endRaw = newEndRaw,
                    endInterior = newEndInterior,
                    endYCorrection = newEndYCorr,
                )
            }
        }
    }

    private fun isNearby(a: PointF, b: PointF): Boolean =
        kotlin.math.abs(a.x - b.x) <= 1.5f && kotlin.math.abs(a.y - b.y) <= 1.5f

    override fun close() {
        pageLinesCache.clear()
        activeAnchors.clear()
        delegate.close()
    }

    private data class AnchorInfo(
        val startRaw: PointF,
        val startInterior: PointF,
        val startYCorrection: Float,
        val endRaw: PointF,
        val endInterior: PointF,
        val endYCorrection: Float,
    )
}

internal suspend fun openReaderPdfDocument(
    context: Context,
    uri: Uri,
    password: String?,
    forceCompatibilityText: Boolean = false,
): PdfDocument {
    val renderer = CachedPdfDocument(openSandboxedPdfDocument(context, uri, password))
    try {
        val document = if (!forceCompatibilityText && renderer.supportsTextSearch() && renderer.supportsTextSelection()) {
            renderer
        } else {
            val backend = PdfTextClient.connect(context, uri, password)
            try {
                CompatibilityPdfDocument(renderer, backend)
            } catch (failure: Throwable) {
                runCatching { backend.close() }
                throw failure
            }
        }
        return StabilizedPdfDocument(document)
    } catch (failure: Throwable) {
        runCatching { renderer.close() }
        throw failure
    }
}

private fun snapPointToLines(lines: List<RectF>, x: Float, y: Float): PointF {
    if (lines.isEmpty()) return PointF(x, y)

    val directHits = lines.filter { line -> y >= line.top - 2f && y <= line.bottom + 2f }
    val targetLine = if (directHits.isNotEmpty()) {
        directHits.minByOrNull { line ->
            when {
                x < line.left -> line.left - x
                x > line.right -> x - line.right
                else -> 0f
            }
        } ?: directHits.first()
    } else {
        lines.minByOrNull { line ->
            val dy = when {
                y < line.top -> line.top - y
                y > line.bottom -> y - line.bottom
                else -> 0f
            }
            val dx = when {
                x < line.left -> line.left - x
                x > line.right -> x - line.right
                else -> 0f
            }
            dy * 6f + dx
        } ?: lines.first()
    }

    val targetCenterX = (targetLine.left + targetLine.right) / 2f
    val targetCenterY = (targetLine.top + targetLine.bottom) / 2f
    val clampedX = x.coerceIn(
        minOf(targetLine.left + 1.5f, targetCenterX),
        maxOf(targetLine.right - 0.6f, targetCenterX),
    )
    return pointF(clampedX, targetCenterY)
}

private fun mergeLines(rects: List<RectF>): List<RectF> {
    if (rects.isEmpty()) return emptyList()
    val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
    val lines = mutableListOf<RectF>()
    for (rect in sorted) {
        val lastLine = lines.lastOrNull()
        if (lastLine != null && isSameLine(lastLine, rect)) {
            lines[lines.lastIndex] = rectF(
                minOf(lastLine.left, rect.left),
                minOf(lastLine.top, rect.top),
                maxOf(lastLine.right, rect.right),
                maxOf(lastLine.bottom, rect.bottom),
            )
        } else {
            lines.add(rectF(rect.left, rect.top, rect.right, rect.bottom))
        }
    }
    return lines
}

private fun isSameLine(a: RectF, b: RectF): Boolean {
    val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    val aHeight = a.bottom - a.top
    val bHeight = b.bottom - b.top
    val minHeight = minOf(aHeight, bHeight)
    val aCenterY = (a.top + a.bottom) / 2f
    val bCenterY = (b.top + b.bottom) / 2f
    val horizontalGap = maxOf(0f, b.left - a.right)
    return (overlap >= minHeight * 0.4f || kotlin.math.abs(aCenterY - bCenterY) <= minHeight * 0.35f) &&
        horizontalGap <= 60f
}

private fun pointF(x: Float, y: Float) = PointF().apply { this.x = x; this.y = y }
private fun point(x: Int, y: Int) = Point().apply { this.x = x; this.y = y }
private fun rectF(left: Float, top: Float, right: Float, bottom: Float) = RectF().apply {
    this.left = left; this.top = top; this.right = right; this.bottom = bottom
}

private fun PdfTextRect.toRectF() = rectF(left, top, right, bottom)
private fun SelectionBoundary.toTextBoundary() = PdfTextBoundary(index, point?.x?.toFloat(), point?.y?.toFloat(), isRtl)
private fun PdfTextBoundary.toSelectionBoundary() = SelectionBoundary(index,
    if (x != null && y != null) point(x.toInt(), y.toInt()) else null, isRtl)
private fun PdfTextSelectionData.toPageSelection(page: Int) = PageSelection(page,
    start.toSelectionBoundary(), stop.toSelectionBoundary(), listOf(PdfPageTextContent(bounds.map(PdfTextRect::toRectF), text)))
