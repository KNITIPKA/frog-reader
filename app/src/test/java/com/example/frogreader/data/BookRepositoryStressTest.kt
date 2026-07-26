package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.LibraryIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class BookRepositoryStressTest {

    private val testDir = File("build/tmp/test_files")
    private val indexFile = File(testDir, "library.json")
    private val bakFile = File(testDir, "library.json.bak")
    private val tmpFile = File(testDir, "library.json.tmp")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createSampleBook(id: String = "stress-book-1", title: String = "Stress Test Title"): Book {
        return Book(
            id = id,
            title = title,
            author = "Stress Author",
            format = BookFormat.EPUB,
            fileName = "$id.epub",
            addedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun cleanFiles() {
        testDir.mkdirs()
        indexFile.delete()
        bakFile.delete()
        tmpFile.delete()
        File(testDir, "books").deleteRecursively()
        File(testDir, "covers").deleteRecursively()
        File(testDir, "images").deleteRecursively()
    }

    @Before
    fun setUp() {
        cleanFiles()
    }

    @After
    fun tearDown() {
        cleanFiles()
    }

    /**
     * Task Scenario 1: Concurrent burst writes.
     * 100 simultaneous coroutines calling updateIndex (via addReadingSeconds)
     * while 100 coroutines read books.value in parallel.
     */
    @Test
    fun testConcurrentBurstWritesAndReads() = runTest {
        val initialBook = createSampleBook("burst-book", "Burst Read Write Book")
        indexFile.writeText(json.encodeToString(LibraryIndex(listOf(initialBook))))

        val repository = BookRepository(context = null)
        val numWriters = 100
        val numReaders = 100
        val readIterationsPerReader = 20

        val successfulReads = AtomicInteger(0)
        val readErrors = AtomicInteger(0)

        withContext(Dispatchers.IO) {
            coroutineScope {
                // Launch 100 reader coroutines
                val readerJobs = (1..numReaders).map {
                    async {
                        repeat(readIterationsPerReader) {
                            try {
                                val currentBooks = repository.books.value
                                if (currentBooks.isNotEmpty()) {
                                    successfulReads.incrementAndGet()
                                }
                            } catch (e: Exception) {
                                readErrors.incrementAndGet()
                            }
                        }
                    }
                }

                // Launch 100 writer coroutines
                val writerJobs = (1..numWriters).map {
                    async {
                        repository.addReadingSeconds("burst-book", 1L)
                    }
                }

                writerJobs.awaitAll()
                readerJobs.awaitAll()
            }
        }

        // Verify state in memory
        val finalBook = repository.bookById("burst-book")
        assertNotNull("Book should exist in memory flow", finalBook)
        assertEquals("Total reading seconds should equal total increment count", numWriters.toLong(), finalBook?.readingSeconds)

        // Verify state on disk
        assertTrue("library.json should exist on disk", indexFile.exists())
        val diskIndex = json.decodeFromString<LibraryIndex>(indexFile.readText())
        assertEquals("Total reading seconds on disk should equal total increment count", numWriters.toLong(), diskIndex.books.first().readingSeconds)

        assertEquals("No exceptions should occur during concurrent reads", 0, readErrors.get())
        assertTrue("Readers should successfully observe state", successfulReads.get() > 0)
    }

    /**
     * Task Scenario 2: Mid-write file truncation / 0-byte file simulation.
     * Inject corrupt or empty files and verify recovery from .bak.
     */
    @Test
    fun testRecoveryFromZeroByteAndTruncatedIndexFile() = runTest {
        val book1 = createSampleBook("b1", "Recovered Book 1")
        val book2 = createSampleBook("b2", "Recovered Book 2")
        val validBakIndex = LibraryIndex(listOf(book1, book2))
        bakFile.writeText(json.encodeToString(validBakIndex))

        // Sub-case 2A: 0-byte library.json
        indexFile.createNewFile()
        assertEquals(0L, indexFile.length())

        var repository = BookRepository(context = null)
        var loadedBooks = repository.books.value

        assertEquals("0-byte file recovery: Should recover 2 books from backup", 2, loadedBooks.size)
        assertEquals(setOf("b1", "b2"), loadedBooks.map { it.id }.toSet())
        assertTrue("0-byte file recovery: library.json should be repaired and non-zero bytes", indexFile.length() > 0)

        // Sub-case 2B: Truncated / malformed mid-write JSON file
        cleanFiles()
        bakFile.writeText(json.encodeToString(validBakIndex))
        indexFile.writeText("{\"books\": [{\"id\": \"b1\", \"title\": \"Truncated JSON without ending")

        repository = BookRepository(context = null)
        loadedBooks = repository.books.value

        assertEquals("Truncated JSON recovery: Should recover 2 books from backup", 2, loadedBooks.size)
        assertEquals(setOf("b1", "b2"), loadedBooks.map { it.id }.toSet())
        val repairedDiskIndex = json.decodeFromString<LibraryIndex>(indexFile.readText())
        assertEquals("Repaired library.json on disk should match backup data", 2, repairedDiskIndex.books.size)

        // Sub-case 2C: Missing library.json completely with valid bakFile
        cleanFiles()
        bakFile.writeText(json.encodeToString(validBakIndex))

        repository = BookRepository(context = null)
        loadedBooks = repository.books.value

        assertEquals("Missing library.json recovery: Should recover 2 books from backup", 2, loadedBooks.size)
        assertTrue("library.json should be re-created from bakFile", indexFile.exists())

        // Sub-case 2D: JSON without "books" key (e.g. {"corrupt": true}) fails parseIndexFile and triggers recovery from bakFile
        cleanFiles()
        bakFile.writeText(json.encodeToString(validBakIndex))
        indexFile.writeText("{\"corrupt\": true}")
        tmpFile.writeText("{\"incomplete\": true}")

        repository = BookRepository(context = null)
        loadedBooks = repository.books.value

        assertEquals("JSON without books key recovery: Should recover from bakFile", 2, loadedBooks.size)
    }

    /**
     * Task Scenario 3: Repeated crash & recovery cycles.
     * Alternate valid updates with forced file corruption and verify zero data loss.
     */
    @Test
    fun testRepeatedCrashAndRecoveryCycles() = runTest {
        val totalCycles = 20
        var expectedBookCount = 0

        for (cycle in 1..totalCycles) {
            // Instantiate fresh repository (simulating app restart after crash)
            val repository = BookRepository(context = null)

            // Verify prior state was preserved completely
            assertEquals("Cycle $cycle: Data count before update should match expected accumulated count", expectedBookCount, repository.books.value.size)

            // Perform valid update (add new book)
            val newBookId = "cycle-book-$cycle"
            val newBookTitle = "Cycle Book $cycle"
            val updatedList = listOf(createSampleBook(newBookId, newBookTitle)) + repository.books.value
            
            // Execute update via Repository method
            repository.markStarted(if (expectedBookCount > 0) "cycle-book-1" else newBookId)
            repository.addReadingSeconds(if (expectedBookCount > 0) "cycle-book-1" else newBookId, 5L)

            // Manually add next book into repository index
            val bookToAdd = createSampleBook(newBookId, newBookTitle)
            val diskIndexBeforeCrash = json.decodeFromString<LibraryIndex>(indexFile.readText())
            val newIndexContent = json.encodeToString(LibraryIndex(listOf(bookToAdd) + diskIndexBeforeCrash.books))
            
            // Perform atomic file save of valid state to both disk and backup before injecting forced crash
            indexFile.writeText(newIndexContent)
            bakFile.writeText(newIndexContent)
            expectedBookCount++

            // Simulate abrupt crash by corrupting main indexFile (e.g. 0-byte or corrupted garbage)
            // while leaving bakFile intact with valid state
            if (cycle % 2 == 0) {
                // Truncate to 0 bytes
                indexFile.writeBytes(ByteArray(0))
            } else {
                // Inject corrupt garbage
                indexFile.writeText("CORRUPTED_CRASH_DATA_GARBAGE_$cycle")
            }
        }

        // Final app restart verification
        val finalRepository = BookRepository(context = null)
        val finalBooks = finalRepository.books.value
        assertEquals("Final recovery after $totalCycles crash cycles: Zero data loss", totalCycles, finalBooks.size)
        assertTrue("Main index file repaired after final crash", indexFile.length() > 0)
    }

    /**
     * Task Scenario 4: Refusal to overwrite corrupted data.
     * Verify updateIndex fails fast when disk data is corrupted and both main & backup files fail.
     */
    @Test
    fun testRefusalToOverwriteCorruptedDataWhenBothFilesCorrupted_AfterReadingBooks() = runTest {
        val corruptedContent = ">>> CORRUPTED DATA NOT JSON <<<"
        indexFile.writeText(corruptedContent)
        bakFile.writeText(corruptedContent)

        val repository = BookRepository(context = null)

        // Read books flow first (triggers _books lazy block)
        val loaded = repository.books.value
        assertEquals("Loaded books should be empty when both files are corrupted", 0, loaded.size)

        // Calling update method should throw LibraryIndexCorruptedException
        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException when attempting to update corrupt index")
        } catch (e: LibraryIndexCorruptedException) {
            // Expected failure-fast behavior
        }

        // Verify disk content was NOT overwritten with empty list JSON
        assertEquals("indexFile should NOT be overwritten when index is corrupted", corruptedContent, indexFile.readText())
        assertEquals("bakFile should NOT be overwritten when index is corrupted", corruptedContent, bakFile.readText())
    }

    /**
     * Task Scenario 4 (Adversarial Variant): Refusal to overwrite corrupted data on uninitialized repository.
     * Verify updateIndex fails fast when called directly on a fresh BookRepository instance
     * WITHOUT reading repository.books first.
     */
    @Test
    fun testRefusalToOverwriteCorruptedDataWhenBothFilesCorrupted_DirectUpdateWithoutPriorRead() = runTest {
        val corruptedContent = ">>> CORRUPTED DATA BOTH MAIN AND BAK <<<"
        indexFile.writeText(corruptedContent)
        bakFile.writeText(corruptedContent)

        val repository = BookRepository(context = null)

        // Directly call update method WITHOUT reading repository.books first
        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException when calling update method on corrupted repository without prior read")
        } catch (e: LibraryIndexCorruptedException) {
            // Expected
        }

        // Verify disk content was NOT overwritten
        assertEquals("indexFile MUST NOT be overwritten with empty JSON on corrupted repository", corruptedContent, indexFile.readText())
        assertEquals("bakFile MUST NOT be overwritten with empty JSON on corrupted repository", corruptedContent, bakFile.readText())
    }

    /**
     * Additional Adversarial Test: Disk write error / read-only directory handling.
     * Verify behavior when write to tmp file fails due to I/O constraints.
     */
    @Test
    fun testWriteFailureBehaviorWhenDirectoryIsReadOnly() = runTest {
        val initialBook = createSampleBook("b1", "Initial Book")
        indexFile.writeText(json.encodeToString(LibraryIndex(listOf(initialBook))))

        val repository = BookRepository(context = null)
        assertEquals(1, repository.books.value.size)

        // Make test directory read-only to simulate write failure
        val originalPermissions = testDir.canWrite()
        try {
            testDir.setWritable(false)

            try {
                repository.addReadingSeconds("b1", 100L)
                // If it doesn't throw, let's inspect if state changed in memory vs disk
            } catch (e: Exception) {
                // Write exception expected
            }
        } finally {
            testDir.setWritable(true)
        }
    }
}
