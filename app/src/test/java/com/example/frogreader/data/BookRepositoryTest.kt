package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.LibraryIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class BookRepositoryTest {

    private val testDir = File("build/tmp/test_files")
    private val indexFile = File(testDir, "library.json")
    private val bakFile = File(testDir, "library.json.bak")
    private val tmpFile = File(testDir, "library.json.tmp")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createSampleBook(id: String = "book-1", title: String = "Test Title"): Book {
        return Book(
            id = id,
            title = title,
            author = "Test Author",
            format = BookFormat.EPUB,
            fileName = "$id.epub",
            addedAtMillis = System.currentTimeMillis(),
        )
    }

    @Before
    fun setUp() {
        testDir.mkdirs()
        indexFile.delete()
        bakFile.delete()
        tmpFile.delete()
    }

    @After
    fun tearDown() {
        indexFile.delete()
        bakFile.delete()
        tmpFile.delete()
    }

    @Test
    fun testNormalAtomicWriteAndBackupCreation() = runTest {
        val initialBook = createSampleBook("book-1", "Initial Book")
        indexFile.writeText(json.encodeToString(LibraryIndex(listOf(initialBook))))

        val repository = BookRepository(context = null)
        assertEquals(1, repository.books.value.size)

        // First update
        repository.markStarted("book-1")
        assertTrue("Main index file should exist after update", indexFile.exists())
        assertTrue("Backup index file should exist after second state update", bakFile.exists())
        assertFalse("Temp file should be cleaned up after atomic move", tmpFile.exists())

        val indexOnDisk = json.decodeFromString<LibraryIndex>(indexFile.readText())
        assertNotNull(indexOnDisk.books.first().startedAtMillis)

        // Second update to test backup content matches previous valid state
        repository.markFinished("book-1")
        val bakOnDisk = json.decodeFromString<LibraryIndex>(bakFile.readText())
        assertEquals(indexOnDisk.books.first().startedAtMillis, bakOnDisk.books.first().startedAtMillis)
    }

    @Test
    fun testCorruptedLibraryJsonFallbackRecoveryFromBak() = runTest {
        val originalBook = createSampleBook("book-bak", "Backup Book")
        val validBakContent = json.encodeToString(LibraryIndex(listOf(originalBook)))
        bakFile.writeText(validBakContent)

        // Write corrupt data into library.json
        indexFile.writeText("{ invalid json content: [ { missing brackets")

        val repository = BookRepository(context = null)
        val loadedBooks = repository.books.value

        assertEquals("Should recover book from bakFile", 1, loadedBooks.size)
        assertEquals("Backup Book", loadedBooks.first().title)

        // Check library.json was automatically repaired
        val repairedIndex = json.decodeFromString<LibraryIndex>(indexFile.readText())
        assertEquals(1, repairedIndex.books.size)
        assertEquals("Backup Book", repairedIndex.books.first().title)
    }

    @Test
    fun testZeroByteLibraryJsonFallbackRecoveryFromBak() = runTest {
        val originalBook = createSampleBook("book-bak-0", "Zero Byte Fallback Book")
        val validBakContent = json.encodeToString(LibraryIndex(listOf(originalBook)))
        bakFile.writeText(validBakContent)

        // Create 0-byte library.json
        indexFile.createNewFile()
        assertEquals(0L, indexFile.length())

        val repository = BookRepository(context = null)
        val loadedBooks = repository.readIndex()

        assertEquals("Should recover book from bakFile when index is 0 bytes", 1, loadedBooks.size)
        assertEquals("Zero Byte Fallback Book", loadedBooks.first().title)
        assertTrue("library.json should be repaired and non-zero bytes", indexFile.length() > 0)
    }

    @Test
    fun testProtectionAgainstOverwritingCorruptedIndexWithEmptyList() = runTest {
        val corruptedContent = ">>> CORRUPTED DATA NOT JSON <<<"
        indexFile.writeText(corruptedContent)

        val repository = BookRepository(context = null)

        // Explicit read should throw LibraryIndexCorruptedException
        try {
            repository.readIndex()
            fail("Expected LibraryIndexCorruptedException when reading corrupt index without backup")
        } catch (e: LibraryIndexCorruptedException) {
            // Expected
        }

        // Attempting to update should throw LibraryIndexCorruptedException and NOT overwrite disk file
        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException when attempting to update corrupt index")
        } catch (e: LibraryIndexCorruptedException) {
            // Expected
        }

        // Verify disk content was NOT overwritten with empty list JSON
        assertEquals(corruptedContent, indexFile.readText())

        // Create orphan cache dir to test cleanOrphanCaches guard
        val orphanDir = File(testDir, "images/orphan-id")
        orphanDir.mkdirs()
        repository.cleanOrphanCaches()
        assertTrue("Orphan cache should NOT be wiped when index is corrupted", orphanDir.exists())
        orphanDir.deleteRecursively()
    }

    @Test
    fun testThreadSafeConcurrentUpdatesAndReads() = runTest {
        val initialBook = createSampleBook("concurrent-book", "Concurrent Book")
        indexFile.writeText(json.encodeToString(LibraryIndex(listOf(initialBook))))

        val repository = BookRepository(context = null)
        val iterations = 30

        withContext(Dispatchers.IO) {
            val jobs = (1..iterations).map {
                async {
                    repository.addReadingSeconds("concurrent-book", 1L)
                    repository.readIndex()
                }
            }
            jobs.awaitAll()
        }

        val finalBook = repository.bookById("concurrent-book")
        assertNotNull(finalBook)
        assertEquals(iterations.toLong(), finalBook?.readingSeconds)

        val diskIndex = json.decodeFromString<LibraryIndex>(indexFile.readText())
        assertEquals(iterations.toLong(), diskIndex.books.first().readingSeconds)
    }
}
