package com.example.frogreader.data

import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.PdfOutlineCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOutlineCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cacheRoot get() = File(temporary.root, "outlines")
    private fun source(name: String = "source.pdf") = File(temporary.root, name).apply {
        writeText("%PDF-test")
        assertTrue(setLastModified(1_000_000L))
    }
    private fun outline(title: String = "Chapter one", page: Int? = 0) =
        PdfOutline(listOf(PdfOutlineEntry(title, page)))
    private fun cacheFiles() = cacheRoot.listFiles().orEmpty().filter { it.extension == "json" }

    @Test fun `successful empty outline persists across cache instances`() = runTest {
        val file = source()
        assertTrue(PdfOutlineCache(cacheRoot).write(file, "hash", PdfOutline()))
        assertEquals(PdfOutline(), PdfOutlineCache(cacheRoot).read(file, "hash", 10))
    }

    @Test fun `known content hash survives staged file rename`() = runTest {
        val file = source("staged.pdf")
        val expected = outline()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", expected))
        val committed = File(temporary.root, "committed.pdf")
        assertTrue(file.renameTo(committed))
        assertEquals(expected, cache.read(committed, "hash", 10))
    }

    @Test fun `a changed source fingerprint invalidates even a known content hash`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        assertTrue(file.setLastModified(file.lastModified() + 1000L))
        assertNull(cache.read(file, "hash", 10))
        // Old generations remain disposable LRU entries, independently keyed by fingerprint.
        assertEquals(1, cacheFiles().size)
    }

    @Test fun `identical copies with different timestamps keep independent reusable entries`() = runTest {
        val first = source("first-copy.pdf")
        val second = source("second-copy.pdf")
        assertTrue(second.setLastModified(2_000_000L))
        val cache = PdfOutlineCache(cacheRoot)
        val expected = outline()
        assertTrue(cache.write(first, "same-content-hash", expected))
        assertTrue(cache.write(second, "same-content-hash", expected))
        assertEquals(expected, cache.read(first, "same-content-hash", 10))
        assertEquals(expected, cache.read(second, "same-content-hash", 10))
        assertEquals(expected, PdfOutlineCache(cacheRoot).read(first, "same-content-hash", 10))
        assertEquals(2, cacheFiles().size)
    }

    @Test fun `a changed source length invalidates an unchanged timestamp`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        file.appendText("changed")
        assertTrue(file.setLastModified(1_000_000L))
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `different content hashes do not share an outline`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "first-hash", outline()))
        assertNull(cache.read(file, "different-hash", 10))
    }

    @Test fun `hashless sources use their path as well as their fingerprint`() = runTest {
        val first = source("first.pdf")
        val second = source("second.pdf")
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(first, null, outline()))
        assertNotNull(cache.read(first, null, 10))
        assertNull(cache.read(second, null, 10))
    }

    @Test fun `a destination outside the opened document is not exposed`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline(page = 12)))
        assertNull(cache.read(file, "hash", pageCount = 10))
    }

    @Test fun `corrupt live file recovers a valid atomic backup`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        val expected = outline()
        assertTrue(cache.write(file, "hash", expected))
        assertTrue(cache.write(file, "hash", expected))
        cacheFiles().single().writeText("broken")
        assertEquals(expected, cache.read(file, "hash", 10))
    }

    @Test fun `corrupt live and backup files behave like a cache miss`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        assertTrue(cache.write(file, "hash", outline()))
        cacheRoot.listFiles().orEmpty().forEach { it.writeText("broken") }
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `oversized records are rejected before reading them into memory`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        cacheFiles().single().outputStream().use { it.write(ByteArray(1024 * 1024 + 1)) }
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `escaped titles at the worker character limit still fit the cache`() = runTest {
        val file = source()
        val manyEscapes = PdfOutline(List(64) {
            PdfOutlineEntry("\u0001".repeat(PdfOutline.MAX_TITLE_CHARS), 0)
        })
        assertTrue(manyEscapes.isValidFor(10))
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", manyEscapes))
        assertEquals(manyEscapes, cache.read(file, "hash", 10))
    }

    @Test fun `entry eviction keeps the most recently used outline`() = runTest {
        val file = source()
        var now = 1_000L
        val cache = PdfOutlineCache(cacheRoot, maxEntries = 2, nowMillis = { now++ })
        assertTrue(cache.write(file, "old", outline("Old")))
        assertTrue(cache.write(file, "recent", outline("Recent")))
        assertNotNull(cache.read(file, "old", 10))
        assertTrue(cache.write(file, "new", outline("New")))
        assertNull(cache.read(file, "recent", 10))
        assertNotNull(cache.read(file, "old", 10))
        assertNotNull(cache.read(file, "new", 10))
        assertEquals(2, cacheFiles().size)
    }

    @Test fun `byte budget includes backup copies`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot, maxBytes = 600L)
        val expected = outline("x".repeat(150))
        assertTrue(cache.write(file, "hash", expected))
        assertFalse(cache.write(file, "hash", expected))
        assertTrue(cacheRoot.listFiles().orEmpty().sumOf { it.length() } <= 600L)
    }

    @Test fun `independent cache instances serialize writes to the same atomic store`() = runTest {
        val file = source()
        val results = coroutineScope {
            (1..16).map { index ->
                async(Dispatchers.Default) {
                    PdfOutlineCache(cacheRoot).write(file, "hash", outline("Chapter $index"))
                }
            }.awaitAll()
        }
        assertTrue(results.all { it })
        val cached = PdfOutlineCache(cacheRoot).read(file, "hash", 10)
        assertNotNull(cached)
        assertTrue(cached!!.entries.single().title.startsWith("Chapter "))
        assertFalse(cacheRoot.listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test fun `unknown cache schema is discarded`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        val record = cacheFiles().single()
        record.writeText(record.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":999"))
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `a record missing its outline is a cache miss instead of false known-empty`() = runTest {
        val file = source()
        val cache = PdfOutlineCache(cacheRoot)
        assertTrue(cache.write(file, "hash", outline()))
        val record = cacheFiles().single()
        record.writeText(record.readText().substringBefore(",\"outline\"") + "}")
        assertNull(cache.read(file, "hash", 10))
    }
}
