package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Large FRB entries share the existing global budgets and book-resource leases. */
class ChunkedCacheBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = temporary.root

    @Test fun `entries above eight MiB survive unpinned while both global budgets have room`() {
        val old = put("reader-content/old/content.frb", 24 * MIB)
        val recent = put("reader-content/recent/content.frb", 24 * MIB)
        val images = put("images/image-only/source/image.jpg", 60 * MIB)
        val budget = ReaderCacheBudget(root)

        val result = budget.trim(setOf("old", "recent", "image-only"))

        assertEquals(64 * MIB, budget.limits.contentEntryBytes)
        assertEquals(64 * MIB, budget.limits.contentBytes)
        assertEquals(128 * MIB, budget.limits.totalBytes)
        assertEquals(48 * MIB, result.contentBytes)
        assertEquals(108 * MIB, result.afterBytes)
        assertTrue(result.evictedBookIds.isEmpty())
        assertTrue(old.isFile)
        assertTrue(recent.isFile)
        assertTrue(images.isFile)
    }

    @Test fun `parsed quota still evicts the oldest complete FRB before sixty four MiB is exceeded`() {
        val old = put("reader-content/old/content.frb", 36 * MIB)
        val recent = put("reader-content/recent/content.frb", 36 * MIB)
        val images = put("images/image-only/source/image.jpg", 32 * MIB)
        var now = 1_000L
        val budget = ReaderCacheBudget(root, clock = { now })
        budget.touch("image-only")
        now = 2_000L
        budget.touch("old")
        now = 3_000L
        budget.touch("recent")

        val result = budget.trim(setOf("old", "recent", "image-only"))

        assertEquals(104 * MIB, result.beforeBytes)
        assertEquals(setOf("old"), result.evictedBookIds)
        assertEquals(36 * MIB, result.contentBytes)
        assertEquals(68 * MIB, result.afterBytes)
        assertFalse(old.exists())
        assertTrue(recent.isFile)
        assertTrue("A parsed-only overage must preserve unrelated extracted images", images.isFile)
    }

    @Test fun `combined quota remains one hundred twenty eight MiB and honors the active book pin`() {
        val oldContent = put("reader-content/old/content.frb", 24 * MIB)
        val oldImage = put("images/old/source/image.jpg", 56 * MIB)
        val recentContent = put("reader-content/recent/content.frb", 24 * MIB)
        val recentImage = put("images/recent/source/image.jpg", 40 * MIB)
        var now = 1_000L
        val budget = ReaderCacheBudget(root, clock = { now })
        budget.touch("old")
        now = 2_000L
        budget.touch("recent")

        budget.pin("old").use {
            val pinned = budget.trim(setOf("old", "recent"))
            assertEquals(144 * MIB, pinned.beforeBytes)
            assertEquals(80 * MIB, pinned.pinnedBytes)
            assertEquals(80 * MIB, pinned.afterBytes)
            assertEquals(setOf("recent"), pinned.evictedBookIds)
            assertTrue(oldContent.isFile)
            assertTrue(oldImage.isFile)
            assertFalse(recentContent.exists())
            assertFalse(recentImage.exists())
        }
        put("reader-content/recent/content.frb", 24 * MIB)
        put("images/recent/source/image.jpg", 40 * MIB)
        now = 3_000L
        budget.touch("recent")

        val unpinned = budget.trim(setOf("old", "recent"))
        assertEquals(setOf("old"), unpinned.evictedBookIds)
        assertEquals(64 * MIB, unpinned.afterBytes)
        assertEquals(24 * MIB, unpinned.contentBytes)
        assertEquals(0L, unpinned.pinnedBytes)
        assertFalse(oldContent.exists())
        assertFalse(oldImage.exists())
        assertTrue(recentContent.isFile)
        assertTrue(recentImage.isFile)
    }

    @Test fun `active pinned book and open IndexedBookSession survive multi-book LRU eviction over 64 and 128 MiB budgets`() = runBlocking {
        val activeSource = File(root, "books/active-book.epub").apply {
            parentFile!!.mkdirs()
            writeText("Pinned active book source")
        }
        val cache = ChunkedContentCache(File(root, "reader-content"))
        val chapters = List(6) { chapterIndex ->
            Chapter("Chapter $chapterIndex", List(4) { item ->
                ContentElement.Paragraph(AnnotatedString("Active chapter $chapterIndex paragraph $item"))
            })
        }
        assertTrue(cache.save("active-book", activeSource, BookContent(chapters)))
        val activeFrb = cache.entryFile("active-book")
        assertTrue(activeFrb.isFile)
        val activeOpening = put("reader-content/active-book/opening.frb", 24 * MIB - activeFrb.length())
        val activeImage = put("images/active-book/source/hero.jpg", 30 * MIB)
        val activePageIndex = put("pagination/active-book.rpi", 4 * MIB)

        val oldestContent = put("reader-content/lru-oldest/content.frb", 22 * MIB)
        val oldestImage = put("images/lru-oldest/source/image.jpg", 24 * MIB)
        val middleContent = put("reader-content/lru-middle/content.frb", 22 * MIB)
        val middleImage = put("images/lru-middle/source/image.jpg", 24 * MIB)
        val recentContent = put("reader-content/lru-recent/content.frb", 20 * MIB)
        val recentImage = put("images/lru-recent/source/image.jpg", 16 * MIB)

        var now = 1_000L
        val budget = ReaderCacheBudget(root, clock = { now })
        budget.touch("active-book")
        now = 2_000L
        budget.touch("lru-oldest")
        now = 3_000L
        budget.touch("lru-middle")
        now = 4_000L
        budget.touch("lru-recent")

        val session = requireNotNull(IndexedBookSession.open(budget.pin("active-book")) {
            cache.openIndexed("active-book", activeSource)
        })
        try {
            val trimmed = budget.trim(setOf("active-book", "lru-oldest", "lru-middle", "lru-recent"))
            assertEquals(186 * MIB, trimmed.beforeBytes)
            assertEquals(58 * MIB, trimmed.pinnedBytes)
            assertEquals(setOf("lru-oldest", "lru-middle"), trimmed.evictedBookIds)
            assertEquals(44 * MIB, trimmed.contentBytes)
            assertEquals(94 * MIB, trimmed.afterBytes)

            assertTrue(activeFrb.isFile)
            assertTrue(activeOpening.isFile)
            assertTrue(activeImage.isFile)
            assertTrue(activePageIndex.isFile)
            assertFalse(oldestContent.exists())
            assertFalse(oldestImage.exists())
            assertFalse(middleContent.exists())
            assertFalse(middleImage.exists())
            assertTrue(recentContent.isFile)
            assertTrue(recentImage.isFile)

            assertEquals("Chapter 4", session.readChapter(4)?.title)
            val rawBlock = requireNotNull(session.readChapterBlockBytes(4))
            assertEquals(
                "Active chapter 4 paragraph 2",
                (FrbModelCodec.decode(rawBlock, Chapter.serializer()).elements[2] as ContentElement.Paragraph).text.text,
            )
            val window = requireNotNull(session.loadWindow(10))
            assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), window.chapters.map { it.title })
        } finally {
            session.close()
        }

        val newestContent = put("reader-content/lru-newest/content.frb", 28 * MIB)
        val newestImage = put("images/lru-newest/source/image.jpg", 20 * MIB)
        now = 5_000L
        budget.touch("lru-newest")

        val afterClose = budget.trim(setOf("active-book", "lru-recent", "lru-newest"))
        assertEquals(0L, afterClose.pinnedBytes)
        assertEquals(setOf("active-book"), afterClose.evictedBookIds)
        assertEquals(48 * MIB, afterClose.contentBytes)
        assertEquals(84 * MIB, afterClose.afterBytes)
        assertFalse(activeFrb.exists())
        assertFalse(activeImage.exists())
        assertFalse(activePageIndex.exists())
        assertTrue(recentContent.isFile)
        assertTrue(newestContent.isFile)
        assertTrue(newestImage.isFile)
    }

    private fun put(path: String, bytes: Long) = File(root, path).apply {
        parentFile!!.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
