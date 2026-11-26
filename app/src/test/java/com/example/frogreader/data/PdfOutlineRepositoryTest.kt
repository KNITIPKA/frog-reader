package com.example.frogreader.data

import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.PdfOutlineCache
import com.example.frogreader.data.pdf.PdfOutlineRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOutlineRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cache get() = PdfOutlineCache(File(temporary.root, "outlines"))
    private fun source() = File(temporary.root, "source.pdf").apply { writeText("%PDF-test") }

    @Test fun `construction does not extract or create cache storage`() {
        var calls = 0
        PdfOutlineRepository(cache, { _, _ -> calls++; PdfOutline() })
        assertEquals(0, calls)
        assertFalse(File(temporary.root, "outlines").exists())
    }

    @Test fun `known-empty result avoids opening another worker`() = runTest {
        var calls = 0
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, password ->
            assertEquals("password", password)
            calls++
            PdfOutline()
        })
        assertEquals(PdfOutline(), repository.load(file, "hash", "password", 10))
        assertEquals(PdfOutline(), repository.load(file, "hash", "password", 10))
        assertEquals(1, calls)
    }

    @Test fun `failed extraction is retried instead of cached as no chapters`() = runTest {
        var calls = 0
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, _ ->
            calls++
            if (calls == 1) throw IOException("Worker disconnected")
            PdfOutline()
        })
        try {
            repository.load(file, "hash", null, 10)
            fail("Expected extraction failure")
        } catch (_: IOException) { }
        assertEquals(PdfOutline(), repository.load(file, "hash", null, 10))
        assertEquals(2, calls)
    }

    @Test fun `slow extraction is cancelled and leaves no cached empty result`() = runTest {
        var released = false
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, _ ->
            try { awaitCancellation() } finally { released = true }
        }, timeoutMillis = 100L)
        try {
            repository.load(file, "hash", null, 10)
            fail("Expected timeout")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("Timed out"))
        }
        assertTrue(released)
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `invalid worker destinations are never cached`() = runTest {
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, _ ->
            PdfOutline(listOf(PdfOutlineEntry("Outside", pageIndex = 10)))
        })
        try {
            repository.load(file, "hash", null, 10)
            fail("Expected invalid outline error")
        } catch (_: IOException) { }
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `reader cancellation stays cancellation instead of becoming a retryable error`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var released = false
        var retryableFailure = false
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { released = true }
        })
        val reader = launch {
            try { repository.load(file, "hash", null, 10) }
            catch (_: IOException) { retryableFailure = true }
        }
        entered.await()
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
        assertTrue(released)
        assertFalse(retryableFailure)
        assertNull(cache.read(file, "hash", 10))
    }

    @Test fun `source replaced during extraction is not cached against the new file`() = runTest {
        val file = source()
        val repository = PdfOutlineRepository(cache, { _, _ ->
            file.appendText("replacement bytes")
            PdfOutline(listOf(PdfOutlineEntry("Old document", 0)))
        })
        try {
            repository.load(file, "hash", null, 10)
            fail("Expected source replacement error")
        } catch (_: IOException) { }
        assertNull(cache.read(file, "hash", 10))
    }
}
