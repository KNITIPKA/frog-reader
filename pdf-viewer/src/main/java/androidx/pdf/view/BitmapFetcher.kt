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

// Modified by FrogReader (2026): persistent previews and multiresolution tile refinement.
// See pdf-viewer/UPSTREAM.md and docs/pdf-support.md for provenance and upgrade checks.
package androidx.pdf.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.RemoteException
import android.util.Size
import androidx.annotation.MainThread
import androidx.pdf.PdfDocument
import androidx.pdf.exceptions.RequestFailedException
import androidx.pdf.exceptions.RequestMetadata
import androidx.pdf.util.ExceptionUtils.isHandledRemoteException
import androidx.pdf.util.PAGE_BITMAP_REQUEST_NAME
import androidx.pdf.util.PAGE_BITMAP_TILE_REQUEST_NAME
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one page's raster working set. Display zoom never invalidates published pixels: resolution
 * changes retain old detail in page coordinates while a quantized new level refines the viewport.
 * All state/publication is confined to Main; only rendering runs on [backgroundScope]. Published
 * bitmaps are released by dropping references, never recycled under the hardware render thread.
 */
@MainThread
internal class BitmapFetcher(
    private val pageNum: Int,
    private val pageSize: Point,
    pdfDocument: PdfDocument,
    private val backgroundScope: CoroutineScope,
    private val maxBitmapSizePx: Point,
    private val onBitmapReady: (Int) -> Unit,
    private val errorFlow: MutableSharedFlow<Throwable>,
    private val renderQueue: PdfRenderQueue,
) : AutoCloseable {
    private val bitmapSource = pdfDocument.getPageBitmapSource(pageNum)
    private var contents: PageContents? = null
    private var previewBitmap: FullPageBitmap? = null
    private var previewJob: Job? = null
    private var previewSize: Size? = null
    private var fullPageJob: Job? = null
    private var viewportRender: ViewportRender? = null
    private val tileJobs = mutableMapOf<Long, Job>()
    private var closed = false
    private var generation = 0
    private var previewGeneration = 0
    private var renderScale: Float? = null
    private var requestedScale: Float? = null
    private var visibleArea = RectF()
    private var prefetchArea: RectF? = null
    private var prefetchTileLimit = 0
    private var useSequence = 0L
    private val presentation = PageBitmapPresentation()

    private class ViewportRender(val bounds: RectF, val indices: Set<Long>) {
        lateinit var job: Job
        @Volatile var started = false
    }

    val pageBitmaps: PageContents? get() = contents ?: previewBitmap

    fun preparePreview(size: Size) {
        if (closed || previewBitmap != null || previewJob?.isActive == true) return
        previewSize = size
        val expected = previewGeneration
        previewJob = fetch(size, preview = true) { bitmap ->
            if (expected != previewGeneration) return@fetch false
            previewBitmap = FullPageBitmap(bitmap, minOf(size.width.toFloat() / pageSize.x,
                size.height.toFloat() / pageSize.y))
            (contents as? TileBoard)?.let { if (it.fullPageBitmap == null) it.fullPageBitmap = bitmap }
            true
        }
    }

    fun retainPreviewOnly() {
        cancelDetail()
        presentation.clear()
        contents = null
        renderScale = null
        requestedScale = null
    }

    fun hasContent(): Boolean = when (val image = pageBitmaps) {
        is FullPageBitmap -> true
        is TileBoard -> image.fullPageBitmap != null
        else -> false
    }

    fun maybeFetchNewBitmaps(scale: Float, viewArea: RectF, hasFormStateChanged: Boolean = false,
        aheadArea: RectF? = null, aheadTileLimit: Int = 0) {
        if (closed || !scale.isFinite() || scale <= 0f || (viewArea.isEmpty && aheadArea == null)) return
        requestedScale = scale
        visibleArea = RectF(viewArea)
        prefetchArea = aheadArea?.let(::RectF)
        prefetchTileLimit = aheadTileLimit
        if (hasFormStateChanged) {
            // Content mutations invalidate every resolution, including previews and retained detail.
            previewGeneration++
            previewJob?.cancel()
            previewBitmap = null
            previewSize?.let(::preparePreview)
            cancelDetail()
            presentation.clear()
            contents = null
            renderScale = null
        }
        val resolution = TileResolutionPolicy.resolve(scale, renderScale)
        viewportRender?.let { render ->
            // Queued work can be replaced before it takes a native render slot. Once it starts,
            // keep the result only while it can still cover part of the current view.
            if (!RectF.intersects(render.bounds, visibleArea) ||
                (!render.started && (resolution != renderScale || !render.bounds.contains(visibleArea)))) {
                cancelViewport()
            }
        }
        if (resolution != renderScale && hasVisibleRenderInFlight()) {
            // Native rendering cannot be preempted cheaply. Finish this useful viewport before
            // moving straight to the latest requested level; cancelling it on every pinch step
            // would waste the work and leave only the preview until the fingers stop.
            return
        }
        if (resolution != renderScale) {
            cancelDetail()
            renderScale = resolution
            if (needsTiling(resolution)) {
                val previous = contents
                val board = TileBoard(tileSizePx, scaledSize(resolution), resolution, pageSize)
                board.fullPageBitmap = when (previous) {
                    is FullPageBitmap -> previous.bitmap
                    is TileBoard -> previous.fullPageBitmap
                    else -> previewBitmap?.bitmap
                }
                contents = board
                if (board.fullPageBitmap == null) preparePreview(limitedSize(resolution,
                    Point(maxBitmapSizePx.x / 2, maxBitmapSizePx.y / 2)))
            }
        }
        if (needsTiling(resolution)) {
            updateTiles(contents as TileBoard)
        } else if ((contents as? FullPageBitmap)?.bitmapScale != resolution && fullPageJob?.isActive != true) {
            val expected = generation
            fullPageJob = fetch(scaledSize(resolution)) { bitmap ->
                if (generation != expected) return@fetch false
                fullPageJob = null
                contents = FullPageBitmap(bitmap, resolution)
                presentation.offer(contents, visibleArea)
                refineLatestScale()
                true
            }
        }
    }

    private fun hasVisibleRenderInFlight(): Boolean {
        return !visibleArea.isEmpty && (fullPageJob?.isActive == true || viewportRender?.job?.isActive == true)
    }

    private fun refineLatestScale() {
        val latest = requestedScale ?: return
        // The focus can move or the viewport can expand without crossing a resolution level.
        // Revisit coverage too, even when the last touch event happened before this render ended.
        maybeFetchNewBitmaps(latest, visibleArea, aheadArea = prefetchArea, aheadTileLimit = prefetchTileLimit)
    }

    private fun updateTiles(board: TileBoard) {
        useSequence++
        val visible = board.visibleTiles(visibleArea)
        val activeViewport = viewportRender?.takeIf { it.job.isActive }
        val needsViewport = activeViewport == null && visible.any { it.bitmap == null }
        val viewport = if (needsViewport) bufferedTiles(board, visible) else visible
        val viewportIndices = viewport.mapTo(mutableSetOf()) { it.index }
        val inFlightIndices = activeViewport?.indices.orEmpty()
        val ahead = prefetchArea?.let { area -> board.visibleTiles(area)
            .filter { it.index !in viewportIndices && it.index !in inFlightIndices }
            .sortedBy { distanceTo(it.bounds, area) }
            .take(prefetchTileLimit) }.orEmpty()
        val requested = viewport + ahead
        val wanted = requested.mapTo(mutableSetOf()) { it.index }
        val obsolete = tileJobs.filterKeys { it !in wanted }.values.toSet()
        for (job in obsolete) if (tileJobs.none { it.key in wanted && it.value === job }) job.cancel()
        tileJobs.keys.removeAll { it !in wanted }
        requested.forEach { it.lastUsed = useSequence }
        if (needsViewport) {
            // Render a coherent viewport only when it lacks visible detail. A movement inside the
            // existing raster should never trigger a full replacement just to replenish overscan.
            val superseded = viewport.mapNotNull { tileJobs[it.index] }.toSet()
            superseded.forEach { it.cancel() }
            tileJobs.values.removeAll(superseded)
            fetchViewport(board, viewport)
        }
        // Predicted offscreen work remains small and never publishes an incomplete visible wave.
        val groups = ahead.filter { it.bitmap == null && tileJobs[it.index]?.isActive != true }
            .groupBy { it.batchKey }.values
        for (tiles in groups) {
            val region = Rect(tiles.first().renderRect)
            val area = RectF(tiles.first().bounds)
            tiles.drop(1).forEach { region.union(it.renderRect); area.union(it.bounds) }
            val bitmapBounds = board.pageBounds(region)
            val expected = generation
            val job = fetch(board.pageSizePx, region, area = area) { bitmap ->
                if (generation != expected || contents !== board) return@fetch false
                tiles.forEach { it.bitmapBounds = bitmapBounds; it.bitmap = bitmap; tileJobs.remove(it.index) }
                trimPanCache(board)
                true
            }
            tiles.forEach { tileJobs[it.index] = job }
        }
        trimPanCache(board)
        board.pruneEmptyTiles(wanted + inFlightIndices)
    }

    /** Extra coverage hides the old raster's edge during modest zoom-out and focus movement. */
    private fun bufferedTiles(board: TileBoard, visible: List<TileBoard.Tile>): List<TileBoard.Tile> {
        val area = RectF(visibleArea).apply {
            inset(-width() * .2f, -height() * .2f)
            intersect(0f, 0f, pageSize.x.toFloat(), pageSize.y.toFloat())
        }
        // Check the actual tile-aligned extent. Overscan must never add another native call.
        if (area.width() * board.bitmapScale > MAX_RASTER_DIMENSION ||
            area.height() * board.bitmapScale > MAX_RASTER_DIMENSION) return visible
        val buffered = board.visibleTiles(area)
        val region = Rect().apply { buffered.forEach { union(it.renderRect) } }
        return if (fitsSingleRaster(region)) buffered else visible
    }

    fun draw(canvas: Canvas, location: RectF, placeholder: PageLoadingPlaceholder): Boolean =
        presentation.draw(canvas, location, pageBitmaps, visibleArea, placeholder = placeholder)

    private fun fetchViewport(board: TileBoard, tiles: List<TileBoard.Tile>) {
        val expected = generation
        val batches = viewportBatches(tiles)
        val coverage = RectF().apply { tiles.forEach { union(it.bounds) } }
        val request = ViewportRender(coverage, tiles.mapTo(mutableSetOf()) { it.index })
        request.job = backgroundScope.launch(start = CoroutineStart.LAZY) {
            // Even a viewport larger than one texture is published as a single completed wave.
            // These pixels remain private until every batch succeeds; cancellation owns cleanup.
            val produced = mutableListOf<Pair<List<TileBoard.Tile>, Pair<Bitmap, RectF>>>()
            try {
                for (batch in batches) {
                    val region = Rect(batch.first().renderRect)
                    batch.drop(1).forEach { region.union(it.renderRect) }
                    val bounds = board.pageBounds(region)
                    renderQueue.render(pageNum, false, bounds) {
                        request.started = true
                        ensureActive()
                        val bitmap = bitmapSource.getBitmap(board.pageSizePx, region)
                        produced += batch to (bitmap to bounds)
                    }
                    ensureActive()
                }
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    if (closed || generation != expected || contents !== board || viewportRender !== request) {
                        return@withContext
                    }
                    for ((batch, raster) in produced) {
                        for (tile in batch) {
                            tile.bitmap = raster.first
                            tile.bitmapBounds = raster.second
                        }
                    }
                    produced.clear()
                    viewportRender = null
                    trimPanCache(board)
                    presentation.offer(contents, visibleArea)
                    refineLatestScale()
                    onBitmapReady(pageNum)
                }
            } catch (e: RemoteException) {
                if (!e.isHandledRemoteException) throw e
                errorFlow.emit(RequestFailedException(RequestMetadata(
                    requestName = PAGE_BITMAP_TILE_REQUEST_NAME, pageRange = pageNum..pageNum), e))
            } catch (_: IllegalStateException) {
                // A disposed document can finish an already-running native request.
            } finally {
                produced.forEach { it.second.first.recycle() }
            }
        }
        viewportRender = request
        request.job.start()
    }

    private fun viewportBatches(tiles: List<TileBoard.Tile>): List<List<TileBoard.Tile>> {
        if (tiles.isEmpty()) return emptyList()
        val batches = mutableListOf<MutableList<TileBoard.Tile>>()
        var region = Rect()
        for (tile in tiles.sortedBy { it.index }) {
            val expanded = Rect(region).apply { union(tile.renderRect) }
            if (batches.isEmpty() || !fitsSingleRaster(expanded)) {
                batches += mutableListOf(tile)
                region = Rect(tile.renderRect)
            } else {
                batches.last() += tile
                region = expanded
            }
        }
        return batches.sortedBy { batch -> batch.minOf { distanceToViewport(it.bounds) } }
    }

    private fun fitsSingleRaster(region: Rect): Boolean =
        region.width() <= MAX_RASTER_DIMENSION && region.height() <= MAX_RASTER_DIMENSION &&
            region.width().toLong() * region.height() <= MAX_VIEWPORT_PIXELS

    private fun trimPanCache(board: TileBoard) {
        // Includes predicted tiles, so prefetch cannot grow a second unbounded working set.
        var spareBytes = PAN_CACHE_BYTES
        val counted = board.tiles.filter { RectF.intersects(it.bounds, visibleArea) }.mapNotNullTo(mutableSetOf()) { it.bitmap }
        for (tile in board.tiles.filter { !RectF.intersects(it.bounds, visibleArea) && it.bitmap != null }
            .sortedByDescending { it.lastUsed }) {
            val bitmap = tile.bitmap!!
            if (bitmap in counted) continue
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes <= spareBytes) { spareBytes -= bytes; counted += bitmap } else tile.bitmap = null
        }
    }

    private fun distanceToViewport(rect: RectF): Float {
        val target = if (visibleArea.isEmpty) prefetchArea ?: visibleArea else visibleArea
        return distanceTo(rect, target)
    }

    private fun distanceTo(rect: RectF, target: RectF): Float {
        val dx = rect.centerX() - target.centerX()
        val dy = rect.centerY() - target.centerY()
        return dx * dx + dy * dy
    }

    private fun cancelDetail() {
        generation++
        fullPageJob?.cancel()
        fullPageJob = null
        cancelViewport()
        tileJobs.values.forEach { it.cancel() }
        tileJobs.clear()
    }

    private fun cancelViewport() {
        viewportRender?.job?.cancel()
        viewportRender = null
    }

    override fun close() {
        closed = true
        cancelDetail()
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        contents = null
        previewBitmap = null
        presentation.clear()
        bitmapSource.close()
    }

    /** A callback accepts ownership on Main, after cancellation and generation checks. */
    private fun fetch(size: Size, region: Rect? = null, preview: Boolean = false, area: RectF? = null,
        publish: (Bitmap) -> Boolean): Job = backgroundScope.launch {
        var produced: Bitmap? = null
        try {
            val bitmap = renderQueue.render(pageNum, preview, area) {
                bitmapSource.getBitmap(size, region).also { produced = it }
            }
            ensureActive()
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                if (!closed && publish(bitmap)) {
                    produced = null
                    onBitmapReady(pageNum)
                }
            }
        } catch (e: RemoteException) {
            if (!e.isHandledRemoteException) throw e
            errorFlow.emit(RequestFailedException(
                requestMetadata = RequestMetadata(
                    requestName = if (region == null) PAGE_BITMAP_REQUEST_NAME else PAGE_BITMAP_TILE_REQUEST_NAME,
                    pageRange = pageNum..pageNum), throwable = e))
        } catch (_: IllegalStateException) {
            // The isolated source may have closed while a native render was finishing.
        } finally {
            produced?.recycle() // Only unpublished pixels are safe to recycle immediately.
        }
    }

    private fun scaledSize(scale: Float) = Size(
        (pageSize.x * scale).roundToInt().coerceAtLeast(1),
        (pageSize.y * scale).roundToInt().coerceAtLeast(1))

    private fun needsTiling(scale: Float) =
        pageSize.x * scale >= maxBitmapSizePx.x || pageSize.y * scale >= maxBitmapSizePx.y ||
            pageSize.x.toDouble() * pageSize.y * scale * scale > MAX_VIEWPORT_PIXELS

    private fun limitedSize(scale: Float, maxSize: Point): Size = scaledSize(minOf(scale,
        maxSize.x.toFloat() / pageSize.x, maxSize.y.toFloat() / pageSize.y))

    internal fun isFullyRendered(zoom: Float, viewArea: RectF?): Boolean {
        if (viewArea == null || viewArea.isEmpty) return false
        return when (val image = pageBitmaps) {
            is FullPageBitmap -> image.bitmapScale + 0.01f >= zoom
            is TileBoard -> image.bitmapScale + 0.01f >= zoom &&
                image.visibleTiles(viewArea)
                    .let { it.isNotEmpty() && it.all { tile -> tile.bitmap != null } }
            else -> false
        }
    }

    companion object {
        internal val tileSizePx = Point(320, 320)
        private const val PAN_CACHE_BYTES = 8L * 1024 * 1024
        private const val MAX_VIEWPORT_PIXELS = 8L * 1024 * 1024
        private const val MAX_RASTER_DIMENSION = 4096
    }
}

internal sealed interface PageContents {
    val bitmapScale: Float
}

internal class FullPageBitmap(val bitmap: Bitmap, override val bitmapScale: Float) : PageContents

internal class TileBoard(
    val tileSizePx: Point,
    val pageSizePx: Size,
    override val bitmapScale: Float,
    pageSize: Point? = null,
) : PageContents {
    // Native rendering maps the rounded integer raster size to the entire source page. Use
    // those exact axis scales for drawing too, otherwise zoom swaps shift text by subpixels.
    private val scaleX = pageSize?.let { pageSizePx.width.toFloat() / it.x } ?: bitmapScale
    private val scaleY = pageSize?.let { pageSizePx.height.toFloat() / it.y } ?: bitmapScale
    fun pageBounds(rect: Rect) = RectF(rect.left / scaleX, rect.top / scaleY,
        rect.right / scaleX, rect.bottom / scaleY)
    var fullPageBitmap: Bitmap? = null
    private val rows = 1 + (pageSizePx.height - 1) / tileSizePx.y
    private val columns = 1 + (pageSizePx.width - 1) / tileSizePx.x
    // Only materialize the viewport and the bounded pan cache. A large vector drawing at a high
    // zoom must not allocate or scan a grid covering millions of invisible page pixels per frame.
    private val grid = mutableMapOf<Long, Tile>()
    val tiles: Collection<Tile> get() = grid.values

    fun visibleTiles(area: RectF): List<Tile> {
        if (area.isEmpty) return emptyList()
        val firstColumn = maxOf(0, floor(area.left * scaleX / tileSizePx.x).toInt())
        val lastColumn = minOf(columns - 1, ceil(area.right * scaleX / tileSizePx.x).toInt() - 1)
        val firstRow = maxOf(0, floor(area.top * scaleY / tileSizePx.y).toInt())
        val lastRow = minOf(rows - 1, ceil(area.bottom * scaleY / tileSizePx.y).toInt() - 1)
        return buildList {
            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                val index = row.toLong() * columns + column
                add(grid.getOrPut(index) { Tile(index) })
            }
        }
    }

    fun pruneEmptyTiles(visible: Set<Long>) {
        grid.entries.removeAll { (index, tile) -> index !in visible && tile.bitmap == null }
    }

    inner class Tile(val index: Long) {
        val batchKey = (index / columns / 2) to (index % columns / 2)
        private val left = (index % columns).toInt() * tileSizePx.x
        private val top = (index / columns).toInt() * tileSizePx.y
        private val right = minOf(left + tileSizePx.x, pageSizePx.width)
        private val bottom = minOf(top + tileSizePx.y, pageSizePx.height)
        val bounds = pageBounds(Rect(left, top, right, bottom))
        // One-pixel gutters supply texture-filter samples at raster boundaries. Presentation
        // draws a shared raster once, without clipping its constituent cells separately.
        val renderRect = Rect(maxOf(0, left - 1), maxOf(0, top - 1),
            minOf(pageSizePx.width, right + 1), minOf(pageSizePx.height, bottom + 1))
        var bitmapBounds = pageBounds(renderRect)
        var bitmap: Bitmap? = null
        var lastUsed = 0L
    }
}
