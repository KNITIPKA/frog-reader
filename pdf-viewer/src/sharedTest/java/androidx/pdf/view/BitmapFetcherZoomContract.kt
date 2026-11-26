package androidx.pdf.view

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import androidx.pdf.PdfDocument
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

/** Real Android bitmap/geometry tests with controllable asynchronous native-render completion. */
abstract class BitmapFetcherZoomContract {
    protected abstract fun onMainTest(block: suspend () -> Unit)

    @Test fun fittedPhonePageLoadsAsOneFullResolutionImage() = fixture(Point(597, 841), Point(2424, 2424)) {
        fetcher.maybeFetchNewBitmaps(1.8f, RectF(0f, 0f, 597f, 841f))
        settled()
        assertTrue("A fitted page should not be split into tiles by the sampling headroom",
            fetcher.pageBitmaps is FullPageBitmap)
        assertEquals(1, source.requests)
    }
    @Test fun hugeVectorPageOnlyAllocatesVisibleTileMetadata() {
        val board = TileBoard(Point(640, 640), Size(1_000_000, 1_000_000), 64f)
        assertTrue(board.tiles.isEmpty())
        val visible = board.visibleTiles(RectF(100f, 100f, 120f, 130f))
        assertEquals(6, visible.size)
        assertEquals(6, board.tiles.size)
        assertTrue(board.visibleTiles(RectF(-20f, -20f, -10f, -10f)).isEmpty())
    }

    @Test fun tinyZoomDoesNotDiscardOrRerenderSharpTiles() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val before = fetcher.pageBitmaps as TileBoard
        val bitmaps = before.tiles.mapNotNull { it.bitmap }
        val requests = source.requests
        repeat(50) { fetcher.maybeFetchNewBitmaps(3f + it * .002f, area) }
        settled()
        assertSame(before, fetcher.pageBitmaps)
        assertEquals(bitmaps, before.tiles.mapNotNull { it.bitmap })
        assertEquals(requests, source.requests)
        assertTrue(fetcher.isFullyRendered(3.098f, area))
    }

    @Test fun visibleViewportIsPublishedFromOneNativeRasterWithCorrectPageBounds() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val board = fetcher.pageBitmaps as TileBoard
        val rendered = board.tiles.filter { it.bitmap != null }
        assertEquals("The visible page must not sharpen in separate rectangular blocks", 1,
            rendered.map { it.bitmap }.distinct().size)
        val scaleX = board.pageSizePx.width / 2000f
        val scaleY = board.pageSizePx.height / 3000f
        for (tile in rendered) {
            assertTrue(tile.bitmapBounds.contains(tile.bounds))
            assertEquals(tile.bitmap!!.width.toFloat(), tile.bitmapBounds.width() * scaleX, .01f)
            assertEquals(tile.bitmap!!.height.toFloat(), tile.bitmapBounds.height() * scaleY, .01f)
        }
    }

    @Test fun viewportIncludesSharpCoverageBeyondEveryScreenEdge() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val zoomedOut = RectF(area).apply { inset(-width() * .15f, -height() * .15f) }
        assertTrue("A small reverse pinch must not expose thumbnail strips at the edges",
            fetcher.isFullyRendered(3f, zoomedOut))
    }

    @Test fun panningInsideSharpCoverageDoesNotRerenderTheViewportForOverscan() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val requests = source.requests
        val board = fetcher.pageBitmaps as TileBoard
        val rasterRight = board.visibleTiles(area).first().bitmapBounds.right
        val moved = RectF(area).apply { offset(rasterRight - right - 2f, 0f) }
        assertTrue(fetcher.isFullyRendered(3f, moved))
        fetcher.maybeFetchNewBitmaps(3f, moved)
        settled()
        assertEquals(requests, source.requests)
    }

    @Test fun overscanDoesNotSplitAViewportThatFitsInOneNativeRaster() = fixture {
        val zoom = 3.3f
        val tall = RectF(100f, 150f, 450f, 800f)
        fetcher.maybeFetchNewBitmaps(zoom, tall)
        settled()
        assertTrue(fetcher.isFullyRendered(zoom, tall))
        assertEquals("One base and one viewport raster", 2, source.requests)
    }

    @Test fun viewportMoveDuringRenderCatchesUpWithoutAnotherTouchEvent() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        source.gate = CompletableDeferred()
        fetcher.maybeFetchNewBitmaps(5f, area)
        delay(30)
        val moved = RectF(600f, 700f, 900f, 1100f)
        fetcher.maybeFetchNewBitmaps(5f, moved)
        source.gate!!.complete(Unit)
        settled()
        assertTrue(fetcher.isFullyRendered(5f, moved))
        val board = fetcher.pageBitmaps as TileBoard
        assertEquals("Catch-up must replace the entire viewport instead of separate missing strips",
            1, board.visibleTiles(moved).map { it.bitmap }.distinct().size)
    }

    @Test fun oversizedReplacementNeverPublishesItsFirstBlockBeforeTheRest() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val published = (fetcher.pageBitmaps as TileBoard).tiles.mapNotNull { it.bitmap }
        source.gateAfter = source.requests + 1
        source.gate = CompletableDeferred()
        val large = RectF(100f, 100f, 1000f, 1000f)
        fetcher.maybeFetchNewBitmaps(5f, large)
        delay(50)
        val board = fetcher.pageBitmaps as TileBoard
        assertEquals(source.gateAfter + 1, source.requests)
        assertTrue("The first completed texture must remain private until the whole wave is ready",
            board.tiles.none { it.bitmap != null })
        val unpublished = source.lastProduced!!
        fetcher.retainPreviewOnly()
        source.gate!!.complete(Unit)
        settled()
        assertTrue("Cancelling a staged wave must release its unpublished allocations", unpublished.isRecycled)
        assertTrue(published.all { !it.isRecycled })
    }

    @Test fun jumpingToADisjointRegionSkipsTheRestOfAnObsoleteWave() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val before = source.requests
        source.gate = CompletableDeferred()
        source.nonCancellableRender = true
        fetcher.maybeFetchNewBitmaps(5f, RectF(100f, 100f, 1000f, 1000f))
        delay(30)
        assertEquals(before + 1, source.requests)
        val moved = RectF(1200f, 1500f, 1400f, 1700f)
        fetcher.maybeFetchNewBitmaps(5f, moved)
        source.gate!!.complete(Unit)
        settled()
        assertEquals("Only the already started call and the destination should render", before + 2,
            source.requests)
        assertTrue(fetcher.isFullyRendered(5f, moved))
    }

    @Test fun aQueuedZoomRendersTheLatestLevelFirst() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val before = source.requests
        val release = CompletableDeferred<Unit>()
        scope.launch { queue.render(1, false) { release.await() } }
        val center = RectF(100f, 100f, 280f, 300f)
        fetcher.maybeFetchNewBitmaps(5f, center)
        delay(30)
        fetcher.maybeFetchNewBitmaps(7f, center)
        release.complete(Unit)
        settled()
        assertEquals(before + 1, source.requests)
        assertTrue(fetcher.isFullyRendered(7f, center))
    }

    @Test fun roundedRasterDimensionsMapExactlyToPageEdges() {
        val page = Point(597, 841)
        val raster = Size(2131, 3001)
        val board = TileBoard(Point(640, 640), raster, 3.57f, page)
        assertEquals(RectF(0f, 0f, 597f, 841f), board.pageBounds(Rect(0, 0, raster.width, raster.height)))
        val last = board.visibleTiles(RectF(596f, 840f, 597f, 841f)).single()
        assertEquals(597f, last.bounds.right, .0001f)
        assertEquals(841f, last.bounds.bottom, .0001f)
    }

    @Test fun oversizedViewportRespectsNativeRasterAllocationAndTextureLimits() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, RectF(0f, 0f, 1800f, 2600f))
        settled()
        val allocations = (fetcher.pageBitmaps as TileBoard).tiles.mapNotNull { it.bitmap }.toSet()
        assertTrue(allocations.size > 1)
        assertTrue(allocations.all { it.width <= 4096 && it.height <= 4096 &&
            it.allocationByteCount.toLong() <= 32L * 1024 * 1024 })
    }

    @Test fun predictedDetailIsReadyBeforeTheViewportReachesIt() = fixture {
        val incoming = RectF(100f, 700f, 250f, 850f)
        fetcher.maybeFetchNewBitmaps(3f, RectF(), aheadArea = incoming, aheadTileLimit = 4)
        settled()
        val before = fetcher.pageBitmaps as TileBoard
        assertTrue(fetcher.isFullyRendered(3f, incoming))
        val requests = source.requests
        fetcher.maybeFetchNewBitmaps(3f, incoming)
        settled()
        assertSame(before, fetcher.pageBitmaps)
        assertEquals("Entering a prefetched region must not render it again", requests, source.requests)
    }

    @Test fun latestZoomReplacesPredictedDetailAfterTheLastTouch() = fixture {
        val incoming = RectF(100f, 700f, 250f, 850f)
        source.gate = CompletableDeferred()
        fetcher.maybeFetchNewBitmaps(3f, RectF(), aheadArea = incoming, aheadTileLimit = 4)
        delay(30)
        fetcher.maybeFetchNewBitmaps(5f, incoming)
        source.gate!!.complete(Unit)
        settled()
        assertTrue("Speculative detail at an older resolution must not strand the latest zoom",
            fetcher.isFullyRendered(5f, incoming))
    }

    @Test fun rapidZoomFinishesUsefulWorkThenRendersLatestLevelWithoutStarvation() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val before = fetcher.pageBitmaps as TileBoard
        val bitmaps = before.tiles.mapNotNull { it.bitmap }
        source.gate = CompletableDeferred()
        fetcher.maybeFetchNewBitmaps(before.bitmapScale * 1.01f, area)
        delay(30)
        val pending = fetcher.pageBitmaps as TileBoard
        assertNotSame(before, pending)
        assertFalse(fetcher.isFullyRendered(pending.bitmapScale, area))
        fetcher.maybeFetchNewBitmaps(pending.bitmapScale * 1.01f, area)
        val interrupted = fetcher.pageBitmaps as TileBoard
        assertSame("Do not cancel a useful native viewport on every pinch step", pending, interrupted)
        assertTrue(bitmaps.all { !it.isRecycled })
        source.gate!!.complete(Unit)
        settled()
        val latest = fetcher.pageBitmaps as TileBoard
        assertTrue("Automatically catch up when native work finishes, even without another touch event",
            latest.bitmapScale > pending.bitmapScale)
        assertTrue(fetcher.isFullyRendered(latest.bitmapScale, area))
    }

    @Test fun cancellationCannotPublishStalePixelsOrRecycleDrawnTiles() = fixture {
        fetcher.maybeFetchNewBitmaps(3f, area)
        settled()
        val published = (fetcher.pageBitmaps as TileBoard).tiles.mapNotNull { it.bitmap }
        source.gate = CompletableDeferred()
        source.nonCancellableRender = true
        fetcher.maybeFetchNewBitmaps(5f, area)
        delay(30)
        fetcher.retainPreviewOnly()
        source.gate!!.complete(Unit)
        delay(100)
        assertTrue(fetcher.pageBitmaps is FullPageBitmap)
        assertTrue(published.all { !it.isRecycled })
        assertTrue(source.lastProduced!!.isRecycled)
    }

    @Test fun panRetentionIsBoundedAndContentEditsInvalidateEveryResolution() = fixture {
        repeat(12) { index ->
            fetcher.maybeFetchNewBitmaps(3f, RectF(index * 90f, 100f, index * 90f + 350f, 600f))
            settled()
            val board = fetcher.pageBitmaps as TileBoard
            val offscreen = board.tiles.filter { !RectF.intersects(it.bounds,
                RectF(index * 90f, 100f, index * 90f + 350f, 600f)) }
            val visibleBitmaps = (board.tiles - offscreen.toSet()).mapNotNull { it.bitmap }.toSet()
            assertTrue(offscreen.mapNotNull { it.bitmap }.toSet().minus(visibleBitmaps)
                .sumOf { it.allocationByteCount.toLong() } <= 8L * 1024 * 1024)
        }
        source.color = Color.RED
        fetcher.maybeFetchNewBitmaps(3f, area, hasFormStateChanged = true)
        settled()
        val board = fetcher.pageBitmaps as TileBoard
        assertTrue(board.tiles.mapNotNull { it.bitmap }.all { it.getPixel(0, 0) == Color.RED })
        assertEquals(Color.RED, board.fullPageBitmap!!.getPixel(0, 0))
    }

    private fun fixture(pageSize: Point = Point(2000, 3000), maximum: Point = Point(1600, 1600),
        block: suspend Fixture.() -> Unit) = onMainTest {
            val fixture = Fixture(pageSize, maximum)
            try { withTimeout(20_000) { fixture.block() } }
            finally { fixture.source.gate?.complete(Unit); fixture.fetcher.close(); fixture.scope.cancel() }
    }

    private class Fixture(pageSize: Point, maximum: Point) {
        val area = RectF(100f, 100f, 450f, 600f)
        val source = Source()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val document = Proxy.newProxyInstance(PdfDocument::class.java.classLoader,
            arrayOf(PdfDocument::class.java)) { _, method, _ ->
            when (method.name) {
                "getPageBitmapSource" -> source
                else -> error("Unexpected document call: ${method.name}")
            }
        } as PdfDocument
        val queue = PdfRenderQueue().apply { update(0, 0, listOf(0)) }
        val fetcher = BitmapFetcher(0, pageSize, document, scope, maximum,
            {}, MutableSharedFlow(), queue)
        suspend fun settled() {
            withTimeout(5_000) {
                do { delay(10) } while (scope.coroutineContext[Job]!!.children.any { it.isActive })
            }
        }
    }

    private class Source : PdfDocument.BitmapSource {
        override val pageNumber = 0
        var requests = 0
        var color = Color.BLACK
        var gate: CompletableDeferred<Unit>? = null
        var gateAfter = 0
        var nonCancellableRender = false
        var lastProduced: Bitmap? = null
        override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap {
            requests++
            if (requests > gateAfter) {
                if (nonCancellableRender) withContext(NonCancellable) { gate?.await() } else gate?.await()
            }
            return Bitmap.createBitmap(tileRegion?.width() ?: scaledPageSizePx.width,
                tileRegion?.height() ?: scaledPageSizePx.height, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(color); lastProduced = it }
        }
        override fun close() {}
    }
}
