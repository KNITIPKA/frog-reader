package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderProgressChapters
import com.example.frogreader.ui.reader.readerProgressBoundaryGap
import com.example.frogreader.ui.reader.readerProgressFocus
import com.example.frogreader.ui.reader.readerProgressSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressGeometryTest {
    @Test
    fun `two page interlude keeps its true span between two long chapters`() {
        val chapters = ReaderProgressChapters(listOf(0f, 100f / 202, 102f / 202))
        val short = chapters.at(101f / 202)
        assertEquals(3, chapters.size)
        assertEquals(100f / 202, short.start, 0.00001f)
        assertEquals(102f / 202, short.end, 0.00001f)
        assertEquals(300f * 2 / 202, short.span * 300, 0.0001f)
        val gap = readerProgressBoundaryGap(150f, short.span * 300, 1.6f)
        assertTrue(short.span * 300 - gap >= short.span * 300 * 0.74f - 0.00001f)
    }

    @Test
    fun `expansion pins the cursor while leaving book coordinates unchanged`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.4f, 0.42f, 0.8f))
        val fraction = 0.405f
        val chapter = chapters.at(fraction)
        val resting = readerProgressFocus(chapter, fraction, 300f, 2f, 8f)
        val expanded = readerProgressFocus(chapter, fraction, 300f, 2f, 28f)
        val local = (fraction - chapter.start) / chapter.span
        assertEquals(fraction * 300f, resting.startPx + 2f + local * (resting.endPx - resting.startPx - 4f), 0.0001f)
        assertEquals(fraction * 300f, expanded.startPx + 2f + local * (expanded.endPx - expanded.startPx - 4f), 0.0001f)
        assertEquals(28f, expanded.endPx - expanded.startPx, 0.0001f)
        assertEquals(0.4f, chapters[1].start, 0f)
        assertEquals(0.42f, chapters[1].end, 0f)
    }

    @Test
    fun `chapter outline leaves room for cursor at both chapter boundaries`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.4f, 0.42f, 0.8f))
        val chapter = chapters[1]
        for (fraction in listOf(chapter.start, chapter.end)) {
            val focus = readerProgressFocus(chapter, fraction, 300f, 4f, 28f)
            assertTrue(fraction * 300f - focus.startPx >= 4f - 0.0001f)
            assertTrue(focus.endPx - fraction * 300f >= 4f - 0.0001f)
        }
    }

    @Test
    fun `expanded focus crosses adjacent tiny chapters without jumping sideways`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.4f, 0.41f, 0.42f, 0.43f, 0.8f))
        val before = 0.41999f
        val after = 0.42001f
        val left = readerProgressFocus(chapters.at(before), before, 300f, 4f, 28f, centering = 1f)
        val right = readerProgressFocus(chapters.at(after), after, 300f, 4f, 28f, centering = 1f)
        assertEquals(before * 300f, (left.startPx + left.endPx) / 2f, 0.0001f)
        assertEquals(after * 300f, (right.startPx + right.endPx) / 2f, 0.0001f)
        assertEquals((after - before) * 300f, right.startPx - left.startPx, 0.0001f)
    }

    @Test
    fun `expanded focus stays inside narrow tracks at either end`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.01f, 0.99f))
        for (fraction in listOf(0f, 0.005f, 0.995f, 1f)) {
            val focus = readerProgressFocus(chapters.at(fraction), fraction, 20f, 2f, 28f)
            assertTrue(focus.startPx >= 0f)
            assertTrue(focus.endPx <= 20f)
            assertTrue(fraction * 20 >= focus.startPx && fraction * 20 <= focus.endPx)
            assertEquals(20f, focus.endPx - focus.startPx, 0.0001f)
        }
    }

    @Test
    fun `chapter starting on final page owns the endpoint`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.6f, 1f))
        assertEquals(0.6f, chapters.at(0.999f).start, 0f)
        val last = chapters.at(1f)
        assertEquals(1f, last.start, 0f)
        assertEquals(0f, last.span, 0f)
        val focus = readerProgressFocus(last, 1f, 300f, 2f, 28f)
        assertEquals(272f, focus.startPx, 0f)
        assertEquals(300f, focus.endPx, 0f)
    }

    @Test
    fun `invalid and repeated chapter anchors cannot make invalid spans`() {
        val chapters = ReaderProgressChapters(listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0.7f, 0.3f, 0.3f, 2f))
        assertEquals(3, chapters.size)
        assertEquals(0f, chapters.at(Float.NaN).start, 0f)
        assertEquals(0.3f, chapters.at(0.3f).start, 0f)
        assertEquals(0.7f, chapters.at(1f).start, 0f)
        assertEquals(1f, chapters.at(1f).end, 0f)
    }

    @Test
    fun `dense chapter list becomes continuous without losing destinations`() {
        val chapters = ReaderProgressChapters(List(200) { it / 200f })
        assertEquals(0f, readerProgressSegmentDetail(chapters.size, 300f), 0f)
        assertEquals(200, chapters.size)
        assertEquals(0.555f, chapters.at(0.558f).start, 0.00001f)
        assertEquals(0.56f, chapters.at(0.558f).end, 0.00001f)
        assertEquals(1f, readerProgressSegmentDetail(13, 300f), 0f)
    }

    @Test
    fun `unknown chapter structure remains a single full book rail`() {
        val chapters = ReaderProgressChapters(emptyList())
        assertEquals(1, chapters.size)
        assertEquals(0f, chapters.at(0.5f).start, 0f)
        assertEquals(1f, chapters.at(0.5f).end, 0f)
        assertEquals(0f, readerProgressSegmentDetail(chapters.size, 300f), 0f)
    }
}
