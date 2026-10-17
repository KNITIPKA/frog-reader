package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.resolvePublisherFloatTarget
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterItemsTest {
    @Test
    fun `counts cover and empty chapters without preparing distant text`() {
        val adjusted = AtomicInteger()
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val first = chapter("First", "Second")
        val last = chapter("Third", "Fourth")
        val items = ReaderChapterItems(
            listOf(first, chapter(), last), cover,
        ) { element -> adjusted.incrementAndGet(); element }

        assertEquals(5, items.size)
        assertEquals(listOf(1, 3, 3), items.chapterStarts)
        assertEquals(0, adjusted.get())
        assertSame(cover, items[0])
        assertEquals(0, adjusted.get())

        assertSame(last.elements[1], items[4].element)
        assertEquals(2, items[4].chapterIndex)
        assertSame(last.elements[0], items[3].element)
        assertEquals(2, adjusted.get())

        // Even a cover-position open prepares the first text chapter on the
        // worker before Ready is handed to the UI.
        items.prepareAt(0)
        assertEquals(4, adjusted.get())
        assertSame(first.elements[0], items[1].element)
        assertEquals(0, items[1].chapterIndex)
        assertEquals(4, adjusted.get())
    }

    @Test
    fun `concurrent UI and pagination readers share one prepared chapter`() {
        val adjusted = AtomicInteger()
        val chapter = chapter(*Array(100) { "Paragraph $it" })
        val items = ReaderChapterItems(listOf(chapter)) { element ->
            adjusted.incrementAndGet()
            element
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(32) { Callable { items[37] } }).map { it.get() }

            assertEquals(100, adjusted.get())
            results.forEach { assertSame(results.first(), it) }
            assertSame(chapter.elements[37], results.first().element)
            assertEquals(listOf(0), items.chapterStarts)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `publisher float coordinates include preceding chapters and cover`() {
        val cover = ReaderItem(0, ContentElement.Image("/cover.png"))
        val image = ContentElement.Image("/figure.png")
        val floatChapter = Chapter(
            title = "Float",
            elements = listOf(image, paragraph("Caption"), paragraph("Prose"), paragraph("After")),
            publisherBoxes = listOf(
                PublisherBoxSpan(id = "panel", startElement = 0, endElementExclusive = 4),
                PublisherBoxSpan(
                    id = "figure", parentId = "panel", startElement = 0, endElementExclusive = 2,
                    floatSide = PublisherFloatSide.LEFT,
                ),
                PublisherBoxSpan(
                    id = "clear", startElement = 3, endElementExclusive = 3, clear = PublisherClear.LEFT,
                ),
            ),
        )
        val items = ReaderChapterItems(listOf(chapter("Before", "Before again"), floatChapter), cover)
        items.prepareAt(4)

        assertEquals(listOf(1, 3), items.chapterStarts)
        assertTrue(items[3].suppressedByPublisherFloat)
        assertTrue(items[4].suppressedByPublisherFloat)
        assertFalse(items[5].suppressedByPublisherFloat)
        assertEquals(listOf(3, 4), items[5].publisherFloat?.contents?.map { it.sourceItemIndex })
        assertEquals(5, items.publisherFloatTargetForSource(3))
        assertEquals(5, resolvePublisherFloatTarget(items, 4))
        assertEquals(0, resolvePublisherFloatTarget(items, 0))
        assertEquals(6, resolvePublisherFloatTarget(items, 6))
        assertSame(image, items[5].publisherFloat?.contents?.first()?.element)
        assertEquals(PublisherClear.LEFT, items[6].publisherClearBefore)
        assertEquals(1, items[5].chapterIndex)
        assertTrue(items[3].publisherBoxes.first().startsAtElement)
        assertTrue(items[6].publisherBoxes.first().endsAtElement)
        assertSame(items[5], items.subList(3, 7)[2])
    }

    @Test
    fun `independent ready snapshots retain their own adjusted text`() {
        val source = chapter("Original")
        val first = ReaderChapterItems(listOf(source)) { element ->
            (element as ContentElement.Paragraph).copy(text = AnnotatedString("First setting"))
        }
        val second = ReaderChapterItems(listOf(source)) { element ->
            (element as ContentElement.Paragraph).copy(text = AnnotatedString("Second setting"))
        }

        assertEquals("First setting", (first.single().element as ContentElement.Paragraph).text.text)
        assertEquals("Second setting", (second.single().element as ContentElement.Paragraph).text.text)
        assertEquals("Original", (source.elements.single() as ContentElement.Paragraph).text.text)
    }

    @Test
    fun `empty content has no items and rejects invalid indices`() {
        val items = ReaderChapterItems(listOf(chapter()))
        items.prepareAt(0)
        assertEquals(0, items.size)
        assertTrue(runCatching { items[0] }.exceptionOrNull() is IndexOutOfBoundsException)
        assertTrue(runCatching { items[-1] }.exceptionOrNull() is IndexOutOfBoundsException)
    }

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun chapter(vararg paragraphs: String) = Chapter(null, paragraphs.map(::paragraph))
}
