package androidx.pdf.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.util.Size
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageBitmapPresentationTest {
    private val area = RectF(0f, 0f, 160f, 120f)
    private val fadeMillis = PageBitmapPresentation.FADE_MILLIS
    private fun bitmap(color: Int) = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }
    private fun board(bitmap: Bitmap? = null) = TileBoard(Point(40, 40), Size(160, 120), 1f).apply {
        fullPageBitmap = bitmap(Color.WHITE)
        visibleTiles(area).forEach { it.bitmap = bitmap; it.bitmapBounds = RectF(area) }
    }
    private fun draw(presentation: PageBitmapPresentation, contents: PageContents, now: Long): Pair<Bitmap, Boolean> {
        val pixels = bitmap(Color.TRANSPARENT)
        return pixels to presentation.draw(Canvas(pixels), area, contents, area, now)
    }

    @Test fun incompleteReplacementKeepsOldImageThenFadesTheEntireViewportTogether() {
        val presentation = PageBitmapPresentation()
        val old = board(bitmap(Color.RED))
        draw(presentation, old, 0)
        val next = board()
        val blue = bitmap(Color.BLUE)
        next.tiles.filter { it.bounds.left < 80f }.forEach { it.bitmap = blue }
        val partial = draw(presentation, next, 1000)
        assertEquals(Color.RED, partial.first.getPixel(20, 60))
        assertEquals(Color.RED, partial.first.getPixel(140, 60))
        assertFalse(partial.second)

        next.tiles.forEach { it.bitmap = blue }
        assertTrue(draw(presentation, next, 1100).second)
        val middle = draw(presentation, next, 1100 + fadeMillis / 2)
        val color = middle.first.getPixel(20, 60)
        assertTrue(Color.red(color) in 120..135 && Color.blue(color) in 120..135)
        assertEquals(color, middle.first.getPixel(140, 60))
        val final = draw(presentation, next, 1100 + fadeMillis)
        assertFalse(final.second)
        assertEquals(Color.BLUE, final.first.getPixel(140, 60))
    }

    @Test fun oneRasterDrawsExactlyLikeOneBitmapAtFractionalZoomWithoutTileClipSeams() {
        val raster = bitmap(Color.WHITE)
        for (x in 0 until raster.width) for (y in 0 until raster.height) {
            raster.setPixel(x, y, if ((x + y) % 3 == 0) Color.BLACK else Color.WHITE)
        }
        val actual = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(actual).apply { scale(1.371f, 1.371f) }
        PageBitmapPresentation().draw(canvas, area, board(raster), area, 0)
        Canvas(expected).apply {
            scale(1.371f, 1.371f)
            clipRect(area)
            drawRect(area, BLANK_PAINT)
            drawBitmap(raster, null, area, BMP_PAINT)
        }
        assertTrue("Fractional tile boundaries must not change the composed pixels", actual.sameAs(expected))
    }

    @Test fun completedIntermediateZoomIsShownWhileLatestZoomIsStillRendering() {
        val presentation = PageBitmapPresentation()
        val completed = board(bitmap(Color.BLUE))
        presentation.offer(completed, area)
        val newerButEmpty = board()
        val frame = draw(presentation, newerButEmpty, 0)
        assertEquals(Color.BLUE, frame.first.getPixel(80, 60))
    }

    @Test fun rapidZoomOutSoftensExposedOldDetailEdgesWithoutFadingPageEdges() {
        val presentation = PageBitmapPresentation()
        val cropped = RectF(40f, 0f, 120f, 120f)
        val old = board()
        old.visibleTiles(cropped).forEach {
            it.bitmap = bitmap(Color.BLACK)
            it.bitmapBounds = RectF(cropped)
        }
        val initial = bitmap(Color.TRANSPARENT)
        presentation.draw(Canvas(initial), area, old, cropped, 0)
        val expanded = draw(presentation, board(), 100).first
        assertEquals(Color.WHITE, expanded.getPixel(39, 60))
        assertTrue("The exposed edge must blend into its base, not form a sharp block",
            Color.red(expanded.getPixel(40, 60)) in 200..254)
        assertTrue(Color.red(expanded.getPixel(43, 60)) in 60..190)
        assertEquals(Color.BLACK, expanded.getPixel(60, 60))
        assertEquals("The authored page edge stays opaque", Color.BLACK, expanded.getPixel(80, 0))
    }

    @Test fun successiveCompletionsCannotRestartOrStarveAnActiveFade() {
        val presentation = PageBitmapPresentation()
        draw(presentation, board(bitmap(Color.RED)), 0)
        val blue = board(bitmap(Color.BLUE))
        draw(presentation, blue, 10)
        val green = board(bitmap(Color.GREEN))
        presentation.offer(green, area)
        val halfway = draw(presentation, green, 10 + fadeMillis / 2).first.getPixel(80, 60)
        assertEquals(0, Color.green(halfway))
        assertTrue(Color.blue(halfway) in 120..135)
        assertEquals(Color.BLUE, draw(presentation, green, 10 + fadeMillis).first.getPixel(80, 60))
        assertEquals(Color.GREEN, draw(presentation, green, 10 + 2 * fadeMillis).first.getPixel(80, 60))
    }

    @Test fun contentInvalidationClearsBothSidesOfTheFade() {
        val presentation = PageBitmapPresentation()
        draw(presentation, board(bitmap(Color.RED)), 0)
        draw(presentation, board(bitmap(Color.BLUE)), 10)
        presentation.clear()
        val fresh = draw(presentation, board(bitmap(Color.GREEN)), 20)
        assertFalse(fresh.second)
        assertEquals(Color.GREEN, fresh.first.getPixel(80, 60))
    }
}
