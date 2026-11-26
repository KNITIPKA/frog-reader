package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EpubSourceRange
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PublisherFormat
import com.example.frogreader.data.model.PublisherProfile
import com.example.frogreader.data.model.PublisherPublication
import com.example.frogreader.data.model.PublisherRendition
import com.example.frogreader.data.model.PublisherSourceDescriptor
import com.example.frogreader.data.model.RenditionLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubSourceIndexCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun cache(maxEntryBytes: Long = ChunkedContentCache.MAX_ENTRY_BYTES) =
        ChunkedContentCache(File(temporary.root, "reader-content"), maxEntryBytes = maxEntryBytes)

    @Test fun `source index and exact TOC survive eviction of native chapters and extracted images`() {
        val cache = cache()
        val source = temporary.newFile("book.epub").apply { writeText("Original book") }
        val image = File(temporary.root, "images/book/illustration.img").apply {
            parentFile!!.mkdirs()
            writeText("Extracted illustration")
        }
        val content = content(first = ContentElement.Image(image.path))
        assertTrue(cache.save("book", source, content))
        assertEquals(ranges, requireNotNull(cache.load("book", source)).epubSourceRanges)
        requireNotNull(cache.openIndexed("book", source)).use { session ->
            assertEquals(ranges, session.index.epubSourceRanges)
            assertTrue(requireNotNull(session.readGlobals()).epubSourceRanges.isEmpty())
        }

        assertTrue(ReaderCacheBudget(temporary.root).remove("book"))
        assertFalse(image.exists())
        assertNull(cache.openIndexed("book", source))
        val index = requireNotNull(cache.loadEpubSourceIndex("book", source))
        assertEquals(ranges, index.epubSourceRanges)
        assertEquals(listOf(0, 1, 1), index.chapters.map { it.startElement })
        assertEquals(3, index.totalElements)
        assertEquals(BookNavigationTarget.ReadingOrder(2, 1), index.navigation.single().target)
    }

    @Test fun `source index publishes independently of the chapter cache quota`() {
        val cache = cache(maxEntryBytes = 1)
        val source = temporary.newFile("book.epub").apply { writeText("Original book") }
        assertFalse(cache.save("book", source, content()))
        assertEquals(ranges, requireNotNull(cache.loadEpubSourceIndex("book", source)).epubSourceRanges)
    }

    @Test fun `previews invalid provenance and fixed publications cannot publish a source index`() {
        val cache = cache()
        val source = temporary.newFile("book.epub").apply { writeText("Original book") }
        val fixed = PublisherPublication(
            format = PublisherFormat.EPUB_3,
            profile = PublisherProfile.EPUB_STANDARD,
            source = PublisherSourceDescriptor.EpubArchive("OPS/book.opf"),
            defaults = PublisherRendition(layout = RenditionLayout.PRE_PAGINATED),
            pageProgression = PageProgression.DEFAULT,
            spine = emptyList(),
            resources = emptyMap(),
        )
        assertTrue(cache.save("preview", source, content(), complete = false))
        assertTrue(cache.save("gap", source, content(sourceRanges = ranges.dropLast(1))))
        assertTrue(cache.save("unsafe", source, content(sourceRanges = ranges.map { it.copy(path = "../outside.xhtml") })))
        assertTrue(cache.save("fixed", source, content(publication = fixed)))
        listOf("preview", "gap", "unsafe", "fixed").forEach { bookId ->
            assertNull(cache.loadEpubSourceIndex(bookId, source))
        }
    }

    @Test fun `changed bytes invalidate retained source index and maintenance removes deleted books`() {
        val cache = cache()
        val source = temporary.newFile("book.epub").apply { writeText("Original book") }
        assertTrue(cache.save("keep", source, content()))
        assertTrue(cache.save("prune", source, content()))
        assertTrue(cache.save("remove", source, content()))
        cache.pruneEpubSourceIndexes(setOf("keep", "remove"))
        assertNull(cache.loadEpubSourceIndex("prune", source))
        cache.removeEpubSourceIndex("remove")
        assertNull(cache.loadEpubSourceIndex("remove", source))
        assertEquals(ranges, requireNotNull(cache.loadEpubSourceIndex("keep", source)).epubSourceRanges)

        val timestamp = source.lastModified()
        source.writeText("X".repeat(source.length().toInt()))
        assertTrue(source.setLastModified(timestamp))
        assertNull(cache.loadEpubSourceIndex("keep", source))
    }

    private fun content(
        first: ContentElement = ContentElement.Paragraph(AnnotatedString("First occurrence")),
        sourceRanges: List<EpubSourceRange> = ranges,
        publication: PublisherPublication? = null,
    ) = BookContent(
        chapters = listOf(
            Chapter("Chapter one", listOf(first)),
            Chapter("Empty", emptyList()),
            Chapter("Chapter two", listOf(
                ContentElement.Paragraph(AnnotatedString("Second fragment")),
                ContentElement.Paragraph(AnnotatedString("Repeated document")),
            )),
        ),
        navigation = listOf(BookNavigationEntry("Destination", 1, BookNavigationTarget.ReadingOrder(2, 1))),
        publisherPublication = publication,
        epubSourceRanges = sourceRanges,
    )

    private companion object {
        val ranges = listOf(
            EpubSourceRange(0, "OPS/chapter.xhtml", 0, 0, 0, 1),
            EpubSourceRange(0, "OPS/chapter.xhtml", 1, 2, 0, 1),
            EpubSourceRange(2, "OPS/chapter.xhtml", 0, 2, 1, 1),
        )
    }
}
