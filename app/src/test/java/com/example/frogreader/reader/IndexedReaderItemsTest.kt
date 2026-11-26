package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.BookChapterWindow
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.ui.reader.IndexedReaderItems
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IndexedReaderItemsTest {
    @Test fun `distant window retains full coordinates and prepares only explicit chapters`() {
        val descriptors = List(100) { BookChapterIndex("Chapter $it", it % 3, 10, it * 10) }
        val index = bookIndex(descriptors)
        val chapters = (84..86).map { chapter -> chapter(chapter, 10, depth = chapter % 3) }
        val adjusted = AtomicInteger()
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val items = IndexedReaderItems(index, BookChapterWindow(84, chapters, 840, 1000), cover) {
            adjusted.incrementAndGet(); it
        }

        assertEquals(1001, items.size)
        assertEquals((0 until 100).map { it * 10 + 1 }, items.chapterStarts)
        assertEquals(841..870, items.loadedRange)
        assertEquals(30, adjusted.get())
        assertSame(cover, items[0])
        assertTrue(items.isLoaded(0))
        assertFalse(items.isLoaded(1))
        assertTrue(items[1].isLoading)
        assertEquals(0, items[1].chapterIndex)
        assertEquals(85, items[856].chapterIndex)
        assertEquals("Chapter 85 paragraph 5", text(items[856]))
        assertFalse(items[856].isLoading)
        assertEquals(851..860, items.chapterRange(85))
        assertEquals(99, items[1000].chapterIndex)
        assertTrue(items[1000].isLoading)
        assertFalse(items.isLoaded(1000))
        assertFalse(items.isLoaded(-1))
        assertFalse(items.isLoaded(1001))

        // Main/render lookups, including unavailable source positions, never
        // run the adjustment or any loading callback after construction.
        repeat(4) { items.forEach { _ -> Unit } }
        assertEquals(30, adjusted.get())
        assertTrue(runCatching { items[-1] }.exceptionOrNull() is IndexOutOfBoundsException)
        assertTrue(runCatching { items[1001] }.exceptionOrNull() is IndexOutOfBoundsException)
    }

    @Test fun `empty chapters and cover keep exact source positions`() {
        val chapters = listOf(chapter(0, 0), chapter(1, 2), chapter(2, 0), chapter(3, 3), chapter(4, 0))
        val index = indexFor(chapters)
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val first = IndexedReaderItems(index, BookChapterWindow(0, chapters.take(2), 0, 5), cover)
        assertEquals(listOf(1, 1, 3, 3, 6), first.chapterStarts)
        assertEquals(0..2, first.loadedRange)
        assertEquals(IntRange.EMPTY, first.chapterRange(0))
        assertEquals(1, first[1].chapterIndex)
        assertTrue(first[3].isLoading)
        assertEquals(3, first[3].chapterIndex)

        val last = IndexedReaderItems(index, BookChapterWindow(2, chapters.drop(2), 2, 5), cover)
        assertEquals(3..5, last.loadedRange)
        assertEquals(3, last[3].chapterIndex)
        assertEquals("Chapter 3 paragraph 2", text(last[5]))
        assertTrue(last[1].isLoading)
        assertSame(cover, last[0])
        assertTrue(last.isLoaded(0))
        assertEquals(6, last.size)
    }

    @Test fun `window publisher floats match full preparation in global coordinates`() {
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val before = chapter(0, 2)
        val image = ContentElement.Image("/figure.png")
        val floating = Chapter(
            title = "Float", depth = 2,
            elements = listOf(image, paragraph("Caption"), paragraph("Prose"), paragraph("After")),
            publisherBoxes = listOf(
                PublisherBoxSpan(id = "panel", startElement = 0, endElementExclusive = 4),
                PublisherBoxSpan(id = "figure", parentId = "panel", startElement = 0, endElementExclusive = 2,
                    floatSide = PublisherFloatSide.LEFT),
                PublisherBoxSpan(id = "clear", startElement = 3, endElementExclusive = 3, clear = PublisherClear.LEFT),
            ),
        )
        val all = listOf(before, floating, chapter(2, 1))
        val indexed = IndexedReaderItems(indexFor(all), BookChapterWindow(1, listOf(floating), 2, 7), cover)
        val full = ReaderChapterItems(all, cover)

        assertEquals(3..6, indexed.loadedRange)
        for (position in indexed.loadedRange) {
            val expected = full[position]
            val actual = indexed[position]
            assertEquals(expected.chapterIndex, actual.chapterIndex)
            assertSame(expected.element, actual.element)
            assertEquals(expected.suppressedByPublisherFloat, actual.suppressedByPublisherFloat)
            assertEquals(expected.publisherClearBefore, actual.publisherClearBefore)
            assertEquals(expected.publisherBoxes, actual.publisherBoxes)
            assertEquals(expected.publisherFloat, actual.publisherFloat)
        }
        assertEquals(5, indexed.publisherFloatTargetForSource(3))
        assertEquals(5, indexed.publisherFloatTargetForSource(4))
        assertNull(indexed.publisherFloatTargetForSource(1))
        assertNull(indexed.publisherFloatTargetForSource(7))
        assertSame(image, indexed[5].publisherFloat?.contents?.first()?.element)
    }

    @Test fun `published snapshots are independent and concurrent lookups are memory only`() {
        val source = chapter(0, 3)
        val index = indexFor(listOf(source, chapter(1, 4)))
        val count = AtomicInteger()
        val first = IndexedReaderItems(index, BookChapterWindow(0, listOf(source), 0, 7)) { element ->
            count.incrementAndGet()
            (element as ContentElement.Paragraph).copy(text = AnnotatedString("Adjusted"))
        }
        val second = IndexedReaderItems(index, BookChapterWindow(1, listOf(chapter(1, 4)), 3, 7))
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll(List(40) { Callable { first[1] } }).map { it.get() }
            results.forEach { assertSame(results.first(), it) }
            assertEquals(3, count.get())
            assertEquals("Adjusted", text(first[1]))
            assertTrue(second[1].isLoading)
            assertTrue(first[4].isLoading)
            assertEquals("Chapter 1 paragraph 1", text(second[4]))
            assertEquals("Chapter 0 paragraph 1", (source.elements[1] as ContentElement.Paragraph).text.text)
        } finally { workers.shutdownNow() }
    }

    @Test fun `invalid windows and overflowing cover coordinates fail before publication`() {
        val source = chapter(0, 2)
        val index = indexFor(listOf(source))
        val invalid = listOf(
            BookChapterWindow(0, listOf(source), 1, 2),
            BookChapterWindow(0, listOf(source), 0, 3),
            BookChapterWindow(1, listOf(source), 2, 2),
            BookChapterWindow(0, listOf(chapter(0, 1)), 0, 2),
            BookChapterWindow(0, listOf(Chapter("Wrong title", source.elements)), 0, 2),
            BookChapterWindow(0, listOf(Chapter(source.title, source.elements, depth = 1)), 0, 2),
            BookChapterWindow(0, emptyList(), 0, 2),
        )
        invalid.forEach { window ->
            assertTrue(runCatching { IndexedReaderItems(index, window) }.exceptionOrNull() is IllegalArgumentException)
        }
        val oversized = List(4) { chapter(it, 1) }
        assertTrue(runCatching {
            IndexedReaderItems(indexFor(oversized), BookChapterWindow(0, oversized, 0, 4))
        }.exceptionOrNull() is IllegalArgumentException)
        val largest = bookIndex(listOf(BookChapterIndex("Huge", 0, Int.MAX_VALUE, 0)))
        assertTrue(runCatching {
            IndexedReaderItems(largest, BookChapterWindow(0, emptyList(), 0, Int.MAX_VALUE),
                ReaderItem(0, ContentElement.Image("/cover.png")))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `empty publication supports cover without inventing sequential text`() {
        val index = bookIndex(emptyList())
        val window = BookChapterWindow(0, emptyList(), 0, 0)
        val empty = IndexedReaderItems(index, window)
        assertEquals(0, empty.size)
        assertTrue(empty.loadedRange.isEmpty())
        assertFalse(empty.isLoaded(0))
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val covered = IndexedReaderItems(index, window, cover)
        assertEquals(1, covered.size)
        assertEquals(0..0, covered.loadedRange)
        assertSame(cover, covered.single())
    }

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun chapter(number: Int, size: Int, depth: Int = 0) =
        Chapter("Chapter $number", List(size) { paragraph("Chapter $number paragraph $it") }, depth)
    private fun text(item: ReaderItem) = (item.element as ContentElement.Paragraph).text.text
    private fun indexFor(chapters: List<Chapter>): BookIndex {
        var start = 0
        return bookIndex(chapters.map { chapter ->
            BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also { start += chapter.elements.size }
        })
    }
    private fun bookIndex(chapters: List<BookChapterIndex>) = BookIndex(
        chapters, emptyList(), emptyMap(), emptyMap(), emptyList(), null, PageProgression.LTR,
        chapters.sumOf { it.elementCount }, emptyList(),
    )
}
