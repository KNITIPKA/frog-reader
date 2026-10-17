package com.example.frogreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReaderCacheBudgetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root

    @Test
    fun `combined quota evicts the least recently opened whole book`() {
        val oldestImage = put("images/old/source/image.jpg", 12)
        val oldestContent = put("reader-content/old/content.bin", 8)
        val oldestPages = put("pagination/old.json", 5)
        val recentImage = put("images/recent/source/image.jpg", 15)
        val recentPages = put("pagination/recent.json", 5)
        var time = 1_000L
        val budget = budget(total = 30, clock = { time })
        budget.touch("old")
        time = 2_000L
        budget.touch("recent")

        val result = budget.trim(setOf("old", "recent"))

        assertEquals(45L, result.beforeBytes)
        assertEquals(20L, result.afterBytes)
        assertEquals(setOf("old"), result.evictedBookIds)
        assertFalse(oldestImage.exists())
        assertFalse(oldestContent.exists())
        assertFalse(oldestPages.exists())
        assertTrue(recentImage.exists())
        assertTrue(recentPages.exists())
    }

    @Test
    fun `two independent pins protect active resources until both are released`() {
        val image = put("images/active/source/image.jpg", 40)
        val budget = budget(total = 10)
        val reader = budget.pin("active")
        val parser = budget.pin("active")
        reader.close()
        reader.close()

        val pinned = budget.trim()
        assertEquals(40L, pinned.afterBytes)
        assertEquals(40L, pinned.pinnedBytes)
        assertTrue(image.exists())

        parser.close()
        val unpinned = budget.trim()
        assertEquals(0L, unpinned.afterBytes)
        assertEquals(setOf("active"), unpinned.evictedBookIds)
        assertFalse(image.exists())
    }

    @Test
    fun `parsed subset quota leaves books with only image caches available`() {
        val imageOnly = put("images/image-only/source/image.jpg", 12)
        val oldContent = put("reader-content/old/content.bin", 7)
        val oldImage = put("images/old/source/image.jpg", 3)
        val recentContent = put("reader-content/recent/content.bin", 7)
        var time = 1_000L
        val budget = budget(total = 100, content = 10, clock = { time })
        budget.touch("image-only")
        time = 2_000L
        budget.touch("old")
        time = 3_000L
        budget.touch("recent")

        val result = budget.trim()

        assertEquals(7L, result.contentBytes)
        assertEquals(19L, result.afterBytes)
        assertEquals(setOf("old"), result.evictedBookIds)
        assertTrue(imageOnly.exists())
        assertFalse(oldContent.exists())
        assertFalse(oldImage.exists())
        assertTrue(recentContent.exists())
    }

    @Test
    fun `oversized parsed entry is evicted even below the global quota`() {
        val content = put("reader-content/oversized/content.bin", 11)
        val image = put("images/oversized/source/image.jpg", 3)
        val budget = budget(total = 100, content = 100, entry = 10)

        val result = budget.trim()

        assertEquals(setOf("oversized"), result.evictedBookIds)
        assertEquals(0L, result.afterBytes)
        assertFalse(content.exists())
        assertFalse(image.exists())
    }

    @Test
    fun `orphan cleanup uses trusted IDs and never removes a pinned orphan`() {
        val kept = put("images/kept/source/image.jpg", 4)
        val orphan = put("reader-content/orphan/content.bin", 5)
        val pending = put("images/pending/source/image.jpg", 6)
        val budget = budget()
        budget.touch("orphan")
        budget.touch("pending")
        val pin = budget.pin("pending")

        val first = budget.trim(setOf("kept"))
        assertEquals(setOf("orphan"), first.orphanBookIds)
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertFalse(File(root, "reader-cache-usage/orphan").exists())
        assertTrue(pending.exists())
        assertTrue(File(root, "reader-cache-usage/pending").exists())

        pin.close()
        val second = budget.trim(setOf("kept"))
        assertEquals(setOf("pending"), second.orphanBookIds)
        assertFalse(pending.exists())
    }

    @Test
    fun `unknown library does not remove otherwise affordable caches`() {
        val image = put("images/unknown/source/image.jpg", 4)
        val pages = put("pagination/unknown.json", 5)

        val result = budget().trim()

        assertEquals(9L, result.afterBytes)
        assertTrue(result.orphanBookIds.isEmpty())
        assertTrue(image.exists())
        assertTrue(pages.exists())
    }

    @Test
    fun `preexisting caches without recency markers are counted and evicted`() {
        val image = put("images/legacy/source/image.jpg", 11)
        val pages = put("pagination/legacy.json", 7)

        val result = budget(total = 10).trim(setOf("legacy"))

        assertEquals(18L, result.beforeBytes)
        assertEquals(0L, result.afterBytes)
        assertEquals(setOf("legacy"), result.evictedBookIds)
        assertFalse(image.exists())
        assertFalse(pages.exists())
    }

    @Test
    fun `atomic pagination scratch files count toward their owner's quota`() {
        val pages = put("pagination/book.json", 5)
        val scratch = put("pagination/book.json.tmp", 6)
        val backup = put("pagination/book.json.bak", 7)

        val result = budget(total = 10).trim()

        assertEquals(18L, result.beforeBytes)
        assertEquals(setOf("book"), result.evictedBookIds)
        assertFalse(pages.exists())
        assertFalse(scratch.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `reanchor scratch shares its owner's pin and eviction`() {
        val image = put("images/book/source/image.jpg", 3)
        val scratch = put("images/book-reanchor/chapter/image.jpg", 8)
        val budget = budget(total = 5)
        val pin = budget.pin("book")

        val active = budget.trim(setOf("book"))
        assertEquals(11L, active.pinnedBytes)
        assertTrue(scratch.exists())

        pin.close()
        val closed = budget.trim(setOf("book"))
        assertEquals(setOf("book"), closed.evictedBookIds)
        assertFalse(image.exists())
        assertFalse(scratch.exists())
    }

    @Test
    fun `budget only touches derived roots and recognized cache entries`() {
        val originals = listOf(
            "books/original.epub", "covers/cover.jpg", "fonts/custom.ttf",
            "library.json", "userdata.json", "progress.json", "pagination/README",
            "images/README", "reader-content/README",
        ).map { put(it, 30) }
        put("images/cache/source/image.jpg", 5)

        val result = budget(total = 0).trim(emptySet())

        assertEquals(5L, result.beforeBytes)
        assertEquals(0L, result.afterBytes)
        originals.forEach { assertTrue(it.path, it.exists()) }
    }

    @Test
    fun `symbolic links inside caches never traverse or delete source data`() {
        val original = put("books/original.epub", 30)
        val targetDir = File(root, "books")
        val derived = File(root, "images/book/source").apply { mkdirs() }
        Files.createSymbolicLink(File(derived, "outside").toPath(), targetDir.toPath())
        val rootLink = File(root, "reader-content/link")
        rootLink.parentFile!!.mkdirs()
        Files.createSymbolicLink(rootLink.toPath(), targetDir.toPath())

        budget(total = 0).trim(emptySet())

        assertTrue(original.exists())
        assertTrue(targetDir.exists())
        assertTrue(Files.isSymbolicLink(rootLink.toPath()))
        assertFalse(derived.exists())
    }

    @Test
    fun `explicit removal preserves pins unless source replacement requests force`() {
        val content = put("reader-content/book/content.bin", 5)
        val image = put("images/book/source/image.jpg", 4)
        val budget = budget()
        budget.touch("book")
        val pin = budget.pin("book")

        assertFalse(budget.remove("book"))
        assertTrue(content.exists())
        assertTrue(budget.remove("book", force = true))
        assertFalse(content.exists())
        assertFalse(image.exists())
        assertFalse(File(root, "reader-cache-usage/book").exists())
        pin.close()
    }

    @Test
    fun `pin acquired after a scan prevents the pending eviction commit`() {
        val content = put("reader-content/book/content.bin", 5)
        val image = put("images/book/source/image.jpg", 4)
        val pages = put("pagination/book.json", 3)
        val budget = budget(total = 0)
        val readyToDetach = CountDownLatch(1)
        val continueTrim = CountDownLatch(1)
        budget.beforeDetachForTests = {
            readyToDetach.countDown()
            check(continueTrim.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val trimming = executor.submit<ReaderCacheBudget.Result> { budget.trim() }
            assertTrue(readyToDetach.await(5, TimeUnit.SECONDS))
            // Pin acquisition must remain available while maintenance waits.
            val pin = executor.submit<java.io.Closeable> { budget.pin("book") }.get(2, TimeUnit.SECONDS)
            continueTrim.countDown()
            val result = trimming.get(5, TimeUnit.SECONDS)

            assertTrue(content.exists())
            assertTrue(image.exists())
            assertTrue(pages.exists())
            assertEquals(12L, result.pinnedBytes)
            assertTrue(result.evictedBookIds.isEmpty())
            pin.close()
        } finally {
            continueTrim.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pin after detach sees clean misses and old deletion cannot remove rebuilt paths`() {
        val content = put("reader-content/book/content.bin", 5)
        val image = put("images/book/source/image.jpg", 4)
        val pages = put("pagination/book.json", 3)
        val budget = budget(total = 0)
        val detached = CountDownLatch(1)
        val continueDeletion = CountDownLatch(1)
        budget.afterDetachForTests = {
            detached.countDown()
            check(continueDeletion.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val trimming = executor.submit<ReaderCacheBudget.Result> { budget.trim() }
            assertTrue(detached.await(5, TimeUnit.SECONDS))
            val pin = executor.submit<java.io.Closeable> { budget.pin("book") }.get(2, TimeUnit.SECONDS)
            // The commit retires every top-level cache path before exposing a
            // new pin. A new parse can now safely reuse the original filenames.
            assertFalse(content.exists())
            assertFalse(image.exists())
            assertFalse(pages.exists())
            put("reader-content/book/content.bin", 7)
            put("images/book/source/image.jpg", 6)
            put("pagination/book.json", 5)
            continueDeletion.countDown()
            val result = trimming.get(5, TimeUnit.SECONDS)

            assertEquals(7L, content.length())
            assertEquals(6L, image.length())
            assertEquals(5L, pages.length())
            assertEquals(18L, result.afterBytes)
            assertEquals(18L, result.pinnedBytes)
            assertTrue(File(root, "reader-cache-trash").listFiles().orEmpty().isEmpty())
            pin.close()
        } finally {
            continueDeletion.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted eviction trash is counted and swept on the next maintenance run`() {
        val retiredContent = put("reader-cache-trash/interrupted/content-0/content.bin", 7)
        val retiredImage = put("reader-cache-trash/interrupted/resource-1/source/image.jpg", 9)
        val activeImage = put("images/book/source/image.jpg", 3)

        val result = budget().trim(setOf("book"))

        assertEquals(19L, result.beforeBytes)
        assertEquals(3L, result.afterBytes)
        assertEquals(0L, result.contentBytes)
        assertFalse(retiredContent.exists())
        assertFalse(retiredImage.exists())
        assertTrue(activeImage.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal IDs are rejected before creating any file`() {
        budget().touch("../books/original.epub")
    }

    private fun budget(
        total: Long = 100,
        content: Long = 100,
        entry: Long = 100,
        clock: () -> Long = { 1_000L },
    ) = ReaderCacheBudget(root, ReaderCacheBudget.Limits(total, content, entry), clock)

    private fun put(path: String, size: Long): File = File(root, path).apply {
        parentFile!!.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(size) }
    }
}
