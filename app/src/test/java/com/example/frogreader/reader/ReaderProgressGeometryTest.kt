package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderProgressChapters
import com.example.frogreader.ui.reader.ReaderProgressLayout
import com.example.frogreader.ui.reader.readerProgressBoundaryGap
import com.example.frogreader.ui.reader.readerProgressSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    fun `chapter starting on final page owns the endpoint`() {
        val chapters = ReaderProgressChapters(listOf(0f, 0.6f, 1f))
        assertEquals(0.6f, chapters.at(0.999f).start, 0f)
        val last = chapters.at(1f)
        assertEquals(1f, last.start, 0f)
        assertEquals(0f, last.span, 0f)
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
    fun `fallback begins when the available width cannot support readable segments`() {
        for (count in listOf(38, 100, 200, 1000)) {
            val stops = List(count) { it / count.toFloat() }
            assertFalse(ReaderProgressLayout(stops, count * 8f - 0.1f).segmented)
            assertTrue(ReaderProgressLayout(stops, count * 8f).segmented)
        }
        assertTrue(ReaderProgressLayout(List(38) { it / 38f }, 305f).segmented)
        for (width in listOf(240f, 300f, 720f)) {
            assertFalse(ReaderProgressLayout(List(100) { it / 100f }, width).segmented)
        }
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(ReaderProgressLayout(listOf(0f, 0.5f), width).segmented)
        }
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
