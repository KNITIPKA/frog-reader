package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderProgressChapters
import com.example.frogreader.ui.reader.ReaderProgressLayout
import com.example.frogreader.ui.reader.ReaderProgressTransition
import com.example.frogreader.ui.reader.readerProgressBoundaryGap
import com.example.frogreader.ui.reader.readerProgressSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
            assertTrue(ReaderProgressLayout(stops, count * 20f).segmented)
        }
        assertFalse(ReaderProgressLayout(List(38) { it / 38f }, 305f).segmented)
        assertTrue(ReaderProgressLayout(List(38) { it / 38f }, 600f).segmented)
        for (width in listOf(240f, 300f, 720f)) {
            assertFalse(ReaderProgressLayout(List(100) { it / 100f }, width).segmented)
        }
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(ReaderProgressLayout(listOf(0f, 0.5f), width).segmented)
        }
    }

    @Test
    fun `thirty chapters fall back on a phone but remain segmented on a wide screen`() {
        val stops = List(30) { it / 30f }
        assertFalse(ReaderProgressLayout(stops, 305f).segmented)
        assertTrue(ReaderProgressLayout(stops, 720f).segmented)
    }

    @Test
    fun `a cluster of narrow chapters falls back even when most chapters are wide`() {
        val clustered = listOf(30, 30) + List(6) { 1 } + List(5) { 30 }
        assertFalse(ReaderProgressLayout(stopsFor(clustered), 305f).segmented)
        // The same chapter count with isolated short interludes stays useful.
        val lastWish = listOf(1565, 52588, 10252, 52673, 8504, 68713, 8156, 73682, 11809, 73725, 11696, 96014, 15406)
        assertTrue(ReaderProgressLayout(stopsFor(lastWish), 305f).segmented)
    }

    @Test
    fun `mostly narrow chapters fall back even without a consecutive cluster`() {
        val mostlyShort = List(10) { listOf(1, 1, 30) }.flatten()
        assertFalse(ReaderProgressLayout(stopsFor(mostlyShort), 500f).segmented)
        assertTrue(ReaderProgressLayout(List(30) { it / 30f }, 500f).segmented)
    }

    @Test
    fun `readability includes the space reserved by an expanded chapter`() {
        // Resting bodies would exceed 12 dp in both cases. Focus compression
        // makes the 21-chapter rail too tight, before the first drag happens.
        assertTrue(ReaderProgressLayout(List(20) { it / 20f }, 305f).segmented)
        assertFalse(ReaderProgressLayout(List(21) { it / 21f }, 305f).segmented)
    }

    @Test
    fun `focus and interrupted animation frames reuse the book readability plan`() {
        val base = ReaderProgressLayout(List(38) { it / 38f }, 600f)
        assertTrue(base.segmented)
        var visible = base
        for (chapter in 0 until base.chapters.size) {
            val target = base.focused(chapter / 38f, expanded = true)
            assertSame(base.segmentPlan, target.segmentPlan)
            val transition = ReaderProgressTransition(visible, target)
            for (phase in listOf(0f, 0.25f, 0.75f, 1f)) {
                val frame = transition.frame(phase)
                assertSame(base.segmentPlan, frame.segmentPlan)
                assertTrue(frame.segmented)
            }
            visible = transition.frame(0.5f)
        }
    }

    @Test
    fun `fallback ticks keep real boundaries and collapse a tight cluster without changing book positions`() {
        val stops = listOf(0f, 0.05f, 0.051f, 0.052f, 0.053f, 0.054f, 0.055f, 0.2f, 0.5f, 0.9f, 1f)
        val base = ReaderProgressLayout(stops, 305f)
        assertFalse(base.segmented)
        assertEquals(listOf(0.05f, 0.2f, 0.5f, 0.9f), base.segmentPlan.fallbackTicks)
        for (fraction in listOf(0f, 0.051f, 0.3f, 0.9f, 1f)) {
            assertEquals(fraction, base.trackFraction(fraction), 0f)
            assertEquals(fraction, base.bookFraction(fraction), 0f)
            assertSame(base, base.focused(fraction, expanded = true))
        }
    }

    @Test
    fun `dense fallback ticks remain sparse and inside the rail at every screen width`() {
        for (width in listOf(232f, 305f, 720f)) {
            for (count in listOf(30, 38, 100, 200, 1000)) {
                val stops = List(count) { it / count.toFloat() } + 1f
                val base = ReaderProgressLayout(stops, width)
                val ticks = base.segmentPlan.fallbackTicks
                if (base.segmented) {
                    assertTrue(ticks.isEmpty())
                    continue
                }
                assertTrue(ticks.isNotEmpty())
                assertTrue(ticks.all { it in stops && it * width >= 4f && it * width <= width - 4f })
                // Leave ample breathing room even with a thousand chapter boundaries.
                assertTrue(ticks.zipWithNext().all { (left, right) -> (right - left) * width >= 8f })
                assertTrue(ticks.size <= width.toInt() / 8)
            }
        }
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(ReaderProgressLayout(listOf(0f, 0.5f, 1f), width).segmentPlan.fallbackTicks.isEmpty())
        }
        assertTrue(ReaderProgressLayout(emptyList(), 305f).segmentPlan.fallbackTicks.isEmpty())
    }

    @Test
    fun `unknown chapter structure remains a single full book rail`() {
        val chapters = ReaderProgressChapters(emptyList())
        assertEquals(1, chapters.size)
        assertEquals(0f, chapters.at(0.5f).start, 0f)
        assertEquals(1f, chapters.at(0.5f).end, 0f)
        assertEquals(0f, readerProgressSegmentDetail(chapters.size, 300f), 0f)
    }

    private fun stopsFor(volumes: List<Int>): List<Float> = volumes.runningFold(0, Int::plus).dropLast(1)
        .map { it.toFloat() / volumes.sum() }
}
