package com.example.frogreader.data

import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class BookRepositoryStressTest {

    private val testDir = File("build/tmp/test_files")

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
        StoreFixture.clear(testDir)
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

    @Test
    fun testConcurrentBurstWritesAndReads() = runTest {
        val initialBook = createSampleBook("burst-book", "Burst Read Write Book")
        StoreFixture.seed(testDir, listOf(initialBook))

        val repository = BookRepository(context = null)
        val numWriters = 100
        val numReaders = 100
        val readIterationsPerReader = 20

        val successfulReads = AtomicInteger(0)
        val readErrors = AtomicInteger(0)

        withContext(Dispatchers.IO) {
            coroutineScope {
                val readerJobs = (1..numReaders).map {
                    async {
                        repeat(readIterationsPerReader) {
                            try {
                                val currentBooks = repository.books.value
                                if (currentBooks.isNotEmpty()) {
                                    successfulReads.incrementAndGet()
                                }
                            } catch (_: Exception) {
                                readErrors.incrementAndGet()
                            }
                        }
                    }
                }

                val writerJobs = (1..numWriters).map {
                    async {
                        repository.addReadingSeconds("burst-book", 1L)
                    }
                }

                writerJobs.awaitAll()
                readerJobs.awaitAll()
            }
        }

        val finalBook = repository.bookById("burst-book")
        assertNotNull("Book should exist in memory flow", finalBook)
        assertEquals("Total reading seconds should equal total increment count", numWriters.toLong(), finalBook?.readingSeconds)

        assertTrue("SQLite database should exist on disk", StoreFixture.databaseFile(testDir).exists())
        assertEquals(
            "Total reading seconds on disk should equal total increment count",
            numWriters.toLong(),
            StoreFixture.bookEntityOnDisk(testDir, "burst-book")?.readingSeconds,
        )

        assertEquals("No exceptions should occur during concurrent reads", 0, readErrors.get())
        assertTrue("Readers should successfully observe state", successfulReads.get() > 0)
    }

    @Test
    fun testSqliteTransactionRollbackOnWriteFailure() = runTest {
        val initialBook = createSampleBook("b1", "Initial Book")
        StoreFixture.seed(testDir, listOf(initialBook))
        val repository = BookRepository(context = null)
        assertEquals(1, repository.books.value.size)

        StoreFixture.blockWrites(testDir)
        try {
            try {
                repository.addReadingSeconds("b1", 100L)
                fail("Expected IOException when SQLite writes are blocked")
            } catch (_: IOException) {
                // Expected
            }
            assertEquals("In-memory readingSeconds must not advance on rolled-back write", 0L, repository.bookById("b1")?.readingSeconds)
            assertEquals("On-disk readingSeconds must remain 0", 0L, StoreFixture.bookEntityOnDisk(testDir, "b1")?.readingSeconds)

            try {
                repository.addQuote("b1", Quote("q-fail", "Blocked quote", 0, 1_000L))
                fail("Expected IOException when quote write is blocked")
            } catch (_: IOException) {
                // Expected
            }
            assertTrue(repository.bookById("b1")!!.quotes.isEmpty())
            assertTrue(StoreFixture.quotesOnDisk(testDir, "b1").isEmpty())
        } finally {
            StoreFixture.unblockWrites(testDir)
        }

        // Once unblocked, writes succeed cleanly:
        repository.addReadingSeconds("b1", 42L)
        assertEquals(42L, StoreFixture.bookEntityOnDisk(testDir, "b1")?.readingSeconds)
    }

    @Test
    fun testVacuumIntoSnapshotAndPreRestoreRollback() = runTest {
        val b1 = createSampleBook("b1", "Live Book 1")
        val b2 = createSampleBook("b2", "Live Book 2")
        StoreFixture.seed(testDir, listOf(b1, b2))
        val repository = BookRepository(context = null)
        repository.addQuote("b1", Quote("q1", "Snapshot quote", 0, 1_000L))

        val snapshotFile = File(testDir, "snapshot_vacuum.db")
        DatabaseManager.vacuumInto(testDir, snapshotFile)
        DatabaseManager.validateStagedDatabase(snapshotFile)

        // Mutate live DB after snapshot:
        repository.deleteBook("b2")
        assertEquals(listOf("b1"), repository.books.value.map { it.id })

        // Simulate post-open failure during staged swap -> must roll back to live DB ("b1" only):
        try {
            DatabaseManager.replaceDatabaseFromStaged(testDir, snapshotFile) {
                throw IOException("Simulated post-open reconciliation failure")
            }
            fail("Expected failure to propagate")
        } catch (_: IOException) {
            // Expected
        }
        repository.reloadFromDatabase()
        assertEquals("Live DB must roll back cleanly from .pre_restore_bak", listOf("b1"), repository.books.value.map { it.id })

        // Now perform a clean swap from snapshot -> restores both "b1" and "b2":
        DatabaseManager.replaceDatabaseFromStaged(testDir, snapshotFile)
        repository.reloadFromDatabase()
        assertEquals(setOf("b1", "b2"), repository.books.value.map { it.id }.toSet())
        assertEquals(1, repository.bookById("b1")?.quotes?.size)
        snapshotFile.delete()
    }

    @Test
    fun testRefusalToOverwriteCorruptedData_AfterReadingBooks() = runTest {
        val corruptedContent = ">>> CORRUPTED SQLITE DATA <<<"
        StoreFixture.corruptDatabase(testDir, corruptedContent)

        val repository = BookRepository(context = null)
        val loaded = repository.books.value
        assertEquals(0, loaded.size)

        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException")
        } catch (_: LibraryIndexCorruptedException) {
            // Expected
        }

        assertEquals(corruptedContent, StoreFixture.databaseFile(testDir).readText())
    }

    @Test
    fun testRefusalToOverwriteCorruptedData_DirectUpdateWithoutPriorRead() = runTest {
        val corruptedContent = ">>> CORRUPTED SQLITE HEADER DIRECT <<<"
        StoreFixture.corruptDatabase(testDir, corruptedContent)

        val repository = BookRepository(context = null)
        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException")
        } catch (_: LibraryIndexCorruptedException) {
            // Expected
        }

        assertEquals(corruptedContent, StoreFixture.databaseFile(testDir).readText())
    }

    @Test
    fun testScale1500BooksAnd5000QuotesWithFastProgressUpdates() = runTest {
        val bookCount = 1_500
        val totalQuotes = 5_000
        val books = (1..bookCount).map { idx ->
            val quotesForBook = mutableListOf<Quote>()
            // Distribute 5,000 quotes across the 1,500 books (3 quotes each + 500 extras):
            val count = if (idx <= 500) 4 else 3
            for (qIdx in 1..count) {
                val globalId = "q-$idx-$qIdx"
                quotesForBook += Quote(
                    id = globalId,
                    text = " Memorable passage $qIdx from volume $idx about astronomy and time ",
                    chapterIndex = qIdx % 5,
                    createdAtMillis = 1_000L + qIdx,
                    note = if (qIdx == 1) "Note on book $idx" else null,
                    bookId = "book-$idx",
                )
            }
            Book(
                id = "book-$idx",
                title = "Volume $idx: Chronicles of Alexandria",
                author = "Author ${(idx % 120) + 1}",
                description = "Detailed historical monograph number $idx.",
                series = if (idx % 3 == 0) "Alexandria Series" else null,
                seriesNumber = if (idx % 3 == 0) (idx / 3).toFloat() else null,
                format = if (idx % 4 == 0) BookFormat.PDF else BookFormat.EPUB,
                fileName = "book-$idx.epub",
                addedAtMillis = 10_000L + idx,
                quotes = quotesForBook,
                bookmarks = listOf(
                    Bookmark(
                        id = "bm-$idx",
                        flatIndex = idx % 50,
                        chapterIndex = 0,
                        preview = "Bookmark preview on volume $idx",
                        createdAtMillis = 2_000L + idx,
                        bookId = "book-$idx",
                    ),
                ),
            )
        }
        val queue = (1..50).map { i ->
            ReadingQueueEntry(
                id = "rq-$i",
                title = "Queued Book $i",
                author = "Queue Author $i",
                note = "Recommended for chapter $i",
                createdAtMillis = 5_000L + i,
            )
        }
        val log = (1..100).map { i ->
            ReadingLogEntry(
                id = "rl-$i",
                title = "Logged Book $i",
                author = "Logged Author $i",
                status = ReadingLogStatus.FINISHED,
                review = "Finished reading volume $i with great enjoyment.",
                createdAtMillis = 6_000L + i,
            )
        }

        StoreFixture.seed(testDir, books = books, readingQueue = queue, readingLog = log)

        val repository = BookRepository(context = null)
        assertEquals(bookCount, repository.books.value.size)
        assertEquals(totalQuotes, repository.books.value.sumOf { it.quotes.size })
        assertEquals(bookCount, repository.books.value.sumOf { it.bookmarks.size })
        assertEquals(50, repository.readingQueue.value.size)
        assertEquals(100, repository.readingLog.value.size)

        // Rapid page turns on 1 book must only update 1 row in book_progress + lastOpenedAtMillis:
        repeat(25) { step ->
            repository.saveProgress(
                "book-750",
                ReadingProgress(chapterIndex = step, elementIndex = step * 3, fraction = (step + 1) / 25f),
            )
        }
        repository.addReadingSeconds("book-750", 180L)

        assertEquals(24, StoreFixture.progressEntityOnDisk(testDir, "book-750")?.chapterIndex)
        assertEquals(180L, StoreFixture.bookEntityOnDisk(testDir, "book-750")?.readingSeconds)

        // Verify FTS5 indexes hold all seeded documents:
        val searchDao = DatabaseManager.getDatabase(testDir).searchDao()
        assertEquals(bookCount, searchDao.findBooksByNormalizedSubstring("alexandria").size)
        assertEquals(totalQuotes, searchDao.findQuotesByNormalizedSubstring("astronomy").size)
    }
}
