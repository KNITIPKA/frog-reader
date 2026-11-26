@file:Suppress("RestrictedApi")

package com.example.frogreader.reader.pdf

import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import androidx.pdf.PdfDocument
import androidx.pdf.content.PageSelection
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import com.example.frogreader.ui.reader.pdf.StabilizedPdfDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class PdfSelectionStabilizerTest {

    private companion object {
        fun pointF(x: Float, y: Float) = PointF().apply { this.x = x; this.y = y }
        fun point(x: Int, y: Int) = Point().apply { this.x = x; this.y = y }
        fun line(left: Float, top: Float, right: Float, bottom: Float) = RectF().apply {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom
        }
    }

    private class FakePdfDocument(
        private val base: PdfDocument = mock(PdfDocument::class.java),
    ) : PdfDocument by base {
        var selectAll: PageSelection? = null
        var selectionResponse: PageSelection? = null
        var lastStartPoint: PointF? = null
        var lastStopPoint: PointF? = null
        var lastStartBoundary: SelectionBoundary? = null
        var lastStopBoundary: SelectionBoundary? = null

        override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection? = selectAll

        override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, stop: PointF): PageSelection? {
            lastStartPoint = pointF(start.x, start.y)
            lastStopPoint = pointF(stop.x, stop.y)
            return selectionResponse
        }

        override suspend fun getSelectionBounds(
            pageNumber: Int,
            start: SelectionBoundary,
            stop: SelectionBoundary,
        ): PageSelection? {
            lastStartBoundary = start
            lastStopBoundary = stop
            return selectionResponse
        }
    }

    private fun mockSelection(
        page: Int,
        text: String,
        bounds: List<RectF>,
    ): PageSelection = PageSelection(
        page = page,
        start = SelectionBoundary(0),
        stop = SelectionBoundary(text.length),
        selectedContents = listOf(PdfPageTextContent(bounds, text)),
    )

    @Test
    fun `caret selection records anchor and passes exact bounds`() = runTest {
        val fake = FakePdfDocument()
        val wordBounds = listOf(line(20f, 96f, 105f, 114f))
        val initialSelection = mockSelection(0, "FrogReader", wordBounds)
        fake.selectionResponse = initialSelection

        val stabilized = StabilizedPdfDocument(fake)
        val result = stabilized.getSelectionBounds(0, pointF(50f, 105f), pointF(50f, 105f))

        assertNotNull(result)
        assertEquals("FrogReader", (result!!.selectedContents.single() as PdfPageTextContent).text)
        assertEquals(50f, fake.lastStartPoint!!.x, 0.1f)
        assertEquals(105f, fake.lastStartPoint!!.y, 0.1f)
    }

    @Test
    fun `dragging end handle locks start anchor to character interior and clamps right margin drag`() = runTest {
        val fake = FakePdfDocument()
        val line1 = line(20f, 96f, 280f, 114f)
        val word1 = line(20f, 96f, 105f, 114f)

        fake.selectAll = mockSelection(0, "FrogReader selection on a line", listOf(line1))

        // Step 1: Initial selection of "FrogReader" via long-press
        fake.selectionResponse = mockSelection(0, "FrogReader", listOf(word1))
        val stabilized = StabilizedPdfDocument(fake)
        stabilized.getSelectionBounds(0, pointF(50f, 105f), pointF(50f, 105f))

        // Step 2: Dragging END handle.
        // SelectionStateManager passes start = (firstRect.left, firstRect.bottom) = (20f, 114f).
        // User moves finger into right margin: stop = (450f, 114f).
        fake.selectionResponse = mockSelection(0, "FrogReader selection on a line", listOf(line1))
        val dragResult = stabilized.getSelectionBounds(0, pointF(20f, 114f), pointF(450f, 114f))
        assertNotNull(dragResult)

        // Start anchor must be firmly inside first character at line.centerY() (105f), not at bottom (114f)
        assertNotNull(fake.lastStartPoint)
        assertEquals(21.5f, fake.lastStartPoint!!.x, 0.1f)
        assertEquals(105f, fake.lastStartPoint!!.y, 0.1f)

        // Stop point dragged to x=450 must be clamped to right margin of line (279.4f) and centered at y=105f
        assertNotNull(fake.lastStopPoint)
        assertEquals(279.4f, fake.lastStopPoint!!.x, 0.1f)
        assertEquals(105f, fake.lastStopPoint!!.y, 0.1f)
    }

    @Test
    fun `dragging start handle locks end anchor to character interior and clamps left margin drag`() = runTest {
        val fake = FakePdfDocument()
        val line1 = line(20f, 96f, 280f, 114f)
        val word = line(100f, 96f, 180f, 114f)

        fake.selectAll = mockSelection(0, "First paragraph with selection on line", listOf(line1))

        // Step 1: Select "selection"
        fake.selectionResponse = mockSelection(0, "selection", listOf(word))
        val stabilized = StabilizedPdfDocument(fake)
        stabilized.getSelectionBounds(0, pointF(140f, 105f), pointF(140f, 105f))

        // Step 2: Drag START handle: SelectionStateManager passes fixed = (lastRect.right, lastRect.bottom) = (180f, 114f).
        // User moves finger way into left margin: stop = (-30f, 114f).
        fake.selectionResponse = mockSelection(0, "First paragraph with selection", listOf(line1))
        val dragResult = stabilized.getSelectionBounds(0, pointF(180f, 114f), pointF(-30f, 114f))
        assertNotNull(dragResult)

        // Start anchor must be fixed at endInterior (179.4f, 105f)
        assertNotNull(fake.lastStartPoint)
        assertEquals(179.4f, fake.lastStartPoint!!.x, 0.1f)
        assertEquals(105f, fake.lastStartPoint!!.y, 0.1f)

        // Stop point dragged to x=-30 must be clamped to start of line (21.5f, 105f)
        assertNotNull(fake.lastStopPoint)
        assertEquals(21.5f, fake.lastStopPoint!!.x, 0.1f)
        assertEquals(105f, fake.lastStopPoint!!.y, 0.1f)
    }

    @Test
    fun `vertical drag across multiple lines snaps to target line center`() = runTest {
        val fake = FakePdfDocument()
        val line1 = line(20f, 96f, 280f, 114f) // centerY = 105f
        val line2 = line(20f, 120f, 290f, 138f) // centerY = 129f
        val line3 = line(20f, 144f, 250f, 162f) // centerY = 153f

        fake.selectAll = mockSelection(0, "Line 1\nLine 2\nLine 3", listOf(line1, line2, line3))
        fake.selectionResponse = mockSelection(0, "Line 1", listOf(line1))

        val stabilized = StabilizedPdfDocument(fake)
        stabilized.getSelectionBounds(0, pointF(50f, 105f), pointF(50f, 105f))

        // User drags down to Line 3 (y = 155f, x = 100f)
        fake.selectionResponse = mockSelection(0, "Line 1\nLine 2\nLine 3", listOf(line1, line2, line3))
        stabilized.getSelectionBounds(0, pointF(20f, 114f), pointF(100f, 155f + 9f))

        assertNotNull(fake.lastStopPoint)
        assertEquals(100f, fake.lastStopPoint!!.x, 0.1f)
        assertEquals(153f, fake.lastStopPoint!!.y, 0.1f) // Snapped to Line 3 centerY (153f)
    }

    @Test
    fun `multi-page selection boundary points are centered and clamped`() = runTest {
        val fake = FakePdfDocument()
        val line1 = line(20f, 96f, 280f, 114f)

        fake.selectAll = mockSelection(0, "All page text", listOf(line1))
        fake.selectionResponse = mockSelection(0, "All page text", listOf(line1))

        val stabilized = StabilizedPdfDocument(fake)
        // Multi-page start boundary with raw bottom point (20, 114)
        val rawBoundary = SelectionBoundary(0, point(20, 114))
        stabilized.getSelectionBounds(0, rawBoundary, SelectionBoundary(Int.MAX_VALUE))

        assertNotNull(fake.lastStartBoundary?.point)
        // Snapped to centerY (105) and clamped x (21)
        assertEquals(21, fake.lastStartBoundary!!.point!!.x)
        assertEquals(105, fake.lastStartBoundary!!.point!!.y)
    }
}
