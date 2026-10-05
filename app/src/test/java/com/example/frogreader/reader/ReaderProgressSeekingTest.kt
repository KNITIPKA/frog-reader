package com.example.frogreader.reader

import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.ReaderChapterContexts
import com.example.frogreader.ui.reader.ReaderNavigationPolicy
import com.example.frogreader.ui.reader.ReaderProgressLayout
import com.example.frogreader.ui.reader.ReaderProgressAnchor
import com.example.frogreader.ui.reader.resolveReaderChapterPreview
import com.example.frogreader.ui.reader.readerProgressSeekReached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderProgressSeekingTest {
    @Test
    fun `selected capsule actually grows and its outline contains no neighbouring capsule`() {
        val base = ReaderProgressLayout(listOf(0f, 100f / 201f, 102f / 201f), 300f, 202)
        val rest = base.focused(100f / 201f, expanded = false)
        val expanded = base.focused(100f / 201f, expanded = true)
        assertEquals(16f, rest.segment(1).span * 300f, 0.001f)
        assertEquals(32f, expanded.segment(1).span * 300f, 0.001f)
        assertTrue(expanded.segment(0).end <= expanded.segment(1).start)
        assertTrue(expanded.segment(2).start >= expanded.segment(1).end)
        assertTrue(expanded.segment(2).start > rest.segment(2).start)
        // The fill grows as well, rather than merely drawing a larger outline.
        assertEquals(24f, (expanded.segment(1).span - expanded.inset(1) * 2f) * 300f, 0.001f)
    }

    @Test
    fun `stationary finger stays in each short chapter throughout expansion`() {
        val starts = listOf(0, 2, 100, 101, 102, 103, 104, 199)
        val base = ReaderProgressLayout(starts.map { it / 200f }, 300f, 201)
        for (chapter in starts.indices) {
            val original = base.segment(chapter)
            for (local in listOf(0.1f, 0.5f, 0.9f)) {
                val finger = original.start + local * original.span
                val selected = base.bookFraction(finger)
                val target = base.focused(selected, true, ReaderProgressAnchor(chapter, selected, finger))
                for (step in 0..20) {
                    val t = step / 20f
                    val frame = target.withGeometry(
                        base.boundaries.zip(target.boundaries) { a, b -> a + (b - a) * t },
                        base.insets.zip(target.insets) { a, b -> a + (b - a) * t },
                    )
                    val page = ReaderNavigationPolicy.progressTargetIndex(frame.bookFraction(finger), 0, 200)
                    assertEquals("chapter $chapter local $local frame $step", chapter, starts.indexOfLast { it <= page })
                    assertEquals("stationary page at frame $step", ReaderNavigationPolicy.progressTargetIndex(selected, 0, 200), page)
                    for (neighbour in 0 until starts.lastIndex) {
                        assertTrue(frame.segment(neighbour).end <= frame.segment(neighbour + 1).start)
                    }
                }
            }
        }
    }

    @Test
    fun `switching between expanded neighbours keeps the selected page through every frame`() {
        val starts = (0..37).map { it * 18 }
        val base = ReaderProgressLayout(starts.map { it / 706f }, 305f, 707)
        for (previous in starts.indices) {
            val old = base.focused(starts[previous] / 706f, true)
            for (next in listOf(previous - 1, previous + 1).filter { it in starts.indices }) {
                val segment = old.segment(next)
                for (local in listOf(0.1f, 0.5f, 0.9f)) {
                    val finger = segment.start + local * segment.span
                    val selected = old.bookFraction(finger)
                    val target = base.focused(selected, true, ReaderProgressAnchor(next, selected, finger))
                    for (step in 0..20) {
                        val t = step / 20f
                        val frame = target.withGeometry(
                            old.boundaries.zip(target.boundaries) { a, b -> a + (b - a) * t },
                            old.insets.zip(target.insets) { a, b -> a + (b - a) * t },
                        )
                        assertEquals("$previous to $next frame $step", ReaderNavigationPolicy.progressTargetIndex(selected, 0, 706),
                            ReaderNavigationPolicy.progressTargetIndex(frame.bookFraction(finger), 0, 706))
                    }
                }
            }
        }
    }

    @Test
    fun `expanded capsules preserve all page destinations and cursor clearance`() {
        val starts = listOf(0, 1, 100, 102, 103, 203)
        for (width in listOf(240f, 300f, 720f)) {
            val base = ReaderProgressLayout(starts.map { it / 203f }, width, 204)
            for (page in 0..203) {
                val fraction = page / 203f
                val layout = base.focused(fraction, true)
                assertEquals(page, ReaderNavigationPolicy.progressTargetIndex(layout.bookFraction(layout.trackFraction(fraction)), 0, 203))
                val index = layout.chapters.indexAt(fraction)
                val segment = layout.segment(index)
                val cursor = layout.trackFraction(fraction)
                assertTrue((cursor - segment.start) * width >= 4f - 0.001f)
                assertTrue((segment.end - cursor) * width >= 4f - 0.001f)
            }
        }
    }

    @Test
    fun `nearby short chapter seek holds its preview until the exact destination is reached`() {
        assertFalse(readerProgressSeekReached(100f / 201f, 101f / 201f, 202))
        assertTrue(readerProgressSeekReached(101f / 201f, 101f / 201f, 202))
        assertFalse(readerProgressSeekReached(100f / 9999f, 101f / 9999f, 10000))
        assertTrue(readerProgressSeekReached(101.1f / 9999f, 101f / 9999f, 10000))
    }

    @Test
    fun `all of a two page capsule selects that chapter including its right edge`() {
        val starts = listOf(0, 100, 102)
        val layout = ReaderProgressLayout(starts.map { it / 201f }, 300f, 202)
        val pages = List(202) { BookPage(emptyList(), firstItemIndex = it * 10) }
        val itemStarts = starts.map { it * 10 }
        val titles = listOf("Long first", "Two page interlude", "Long last")
        val contexts = ReaderChapterContexts(emptyList(), itemStarts, titles, listOf(0, 0, 0), 2020)
        for (chapter in starts.indices) {
            val segment = layout.segment(chapter)
            assertTrue(segment.span * 300f >= 8f - 0.0001f)
            for (local in listOf(0.001f, 0.1f, 0.49f, 0.75f, 0.99f, 0.999f)) {
                val fraction = layout.bookFraction(segment.start + segment.span * local)
                val page = ReaderNavigationPolicy.progressTargetIndex(fraction, 0, 201)
                val actualChapter = starts.indexOfLast { it <= page }
                assertEquals("tap inside capsule $chapter at $local", chapter, actualChapter)
                val preview = resolveReaderChapterPreview(
                    fraction, true, 202, pages, starts, itemStarts, 2020,
                    { item -> itemStarts.indexOfLast { it <= item } }, contexts, "Book", { "Chapter $it" },
                )
                assertEquals(titles[chapter], preview.label)
            }
        }
        val short = layout.segment(1)
        assertEquals(100, ReaderNavigationPolicy.progressTargetIndex(layout.bookFraction(short.start + short.span * 0.1f), 0, 201))
        assertEquals(101, ReaderNavigationPolicy.progressTargetIndex(layout.bookFraction(short.start + short.span * 0.99f), 0, 201))
    }

    @Test
    fun `one pixel drag visits consecutive one page chapters in both directions`() {
        val starts = listOf(0, 100, 101, 102, 103, 104)
        val layout = ReaderProgressLayout(starts.map { it / 203f }, 300f, 204)
        fun chaptersVisited(positions: IntProgression) = positions.map { x ->
            val page = ReaderNavigationPolicy.progressTargetIndex(layout.bookFraction(x / 300f), 0, 203)
            starts.indexOfLast { it <= page }
        }.distinct()
        assertEquals(starts.indices.toList(), chaptersVisited(0..300))
        assertEquals(starts.indices.reversed().toList(), chaptersVisited(300 downTo 0))
        for (chapter in 1..4) {
            val segment = layout.segment(chapter)
            for (local in listOf(0.01f, 0.5f, 0.99f)) {
                val fraction = layout.bookFraction(segment.start + segment.span * local)
                assertEquals(starts[chapter], ReaderNavigationPolicy.progressTargetIndex(fraction, 0, 203))
            }
        }
    }

    @Test
    fun `every Last Wish interlude is reachable across its visible capsule`() {
        // Text volumes from the supplied FB2, including the very short opening.
        val volumes = listOf(1565, 52588, 10252, 52673, 8504, 68713, 8156, 73682, 11809, 73725, 11696, 96014, 15406)
        val starts = volumes.runningFold(0, Int::plus).dropLast(1)
        val lastItem = volumes.sum()
        val layout = ReaderProgressLayout(starts.map { it.toFloat() / lastItem }, 305f, lastItem + 1)
        for (chapter in starts.indices) {
            val segment = layout.segment(chapter)
            assertTrue(segment.span * 305f >= 8f - 0.0001f)
            for (local in listOf(0.01f, 0.5f, 0.99f)) {
                val item = ReaderNavigationPolicy.progressTargetIndex(
                    layout.bookFraction(segment.start + segment.span * local), 0, lastItem,
                )
                assertEquals(chapter, starts.indexOfLast { it <= item })
            }
        }
    }

    @Test
    fun `book positions round trip through the widened scale without changing their destination`() {
        val starts = listOf(0, 100, 102, 103)
        for (width in listOf(240f, 300f, 720f)) {
            val layout = ReaderProgressLayout(starts.map { it / 202f }, width, 203)
            for (page in 0..202) {
                val fraction = page / 202f
                assertEquals(page, ReaderNavigationPolicy.progressTargetIndex(
                    layout.bookFraction(layout.trackFraction(fraction)), 0, 202,
                ))
            }
        }
    }

    @Test
    fun `a final one page chapter has a tap region instead of an unreachable point`() {
        val layout = ReaderProgressLayout(listOf(0f, 0.6f, 1f), 300f, 101)
        val last = layout.segment(2)
        assertTrue(last.span * 300f >= 8f - 0.0001f)
        for (local in listOf(0.01f, 0.5f, 1f)) {
            assertEquals(1f, layout.bookFraction(last.start + last.span * local), 0f)
        }
        assertEquals(1f, layout.trackFraction(1f), 0f)
    }

    @Test
    fun `dense and unknown chapter maps retain the continuous book scale`() {
        val dense = ReaderProgressLayout(List(200) { it / 200f }, 300f)
        val unknown = ReaderProgressLayout(emptyList(), 300f)
        for (value in listOf(0f, 0.1f, 0.555f, 0.999f, 1f)) {
            assertEquals(value, dense.trackFraction(value), 0.00001f)
            assertEquals(value, dense.bookFraction(value), 0.00001f)
            assertEquals(value, unknown.trackFraction(value), 0f)
            assertEquals(value, unknown.bookFraction(value), 0f)
        }
    }
}
