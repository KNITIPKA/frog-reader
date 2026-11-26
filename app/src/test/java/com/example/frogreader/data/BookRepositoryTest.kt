package com.example.frogreader.data

import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class BookRepositoryTest {

    private val testDir = File("build/tmp/test_files")

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
        StoreFixture.clear(testDir)
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
    }

    @Test
    fun testNormalSqliteWriteAndPersistenceAcrossReopen() = runTest {
        val initialBook = createSampleBook("book-1", "Initial Book")
        StoreFixture.seed(testDir, listOf(initialBook))

        val repository = BookRepository(context = null)
        assertEquals(1, repository.books.value.size)

        repository.markStarted("book-1")
        val afterStart = StoreFixture.bookEntityOnDisk(testDir, "book-1")
        assertNotNull(afterStart?.startedAtMillis)
        assertNotNull(afterStart?.lastOpenedAtMillis)
        assertNull(afterStart?.finishedAtMillis)

        repository.markFinished("book-1")
        val afterFinish = StoreFixture.bookEntityOnDisk(testDir, "book-1")
        assertEquals(afterStart?.startedAtMillis, afterFinish?.startedAtMillis)
        assertNotNull(afterFinish?.finishedAtMillis)

        val reopened = BookRepository(context = null)
        assertEquals(afterFinish?.finishedAtMillis, reopened.bookById("book-1")?.finishedAtMillis)
    }

    @Test
    fun testSavingProgressDoesNotRewriteQuotesOrBookDocId() = runTest {
        val book = createSampleBook("book-1", "Initial Book")
        StoreFixture.seed(testDir, listOf(book))

        val repository = BookRepository(context = null)
        repository.addQuote("book-1", Quote("q1", "a line worth keeping", 0, 1_000L))

        val docIdBefore = StoreFixture.bookEntityOnDisk(testDir, "book-1")?.docId
        val quoteStampBefore = StoreFixture.quotesOnDisk(testDir, "book-1").single().updatedAtMillis

        repository.saveProgress("book-1", ReadingProgress(chapterIndex = 3, fraction = 0.4f))
        repository.saveProgress("book-1", ReadingProgress(chapterIndex = 4, fraction = 0.5f))

        assertEquals(docIdBefore, StoreFixture.bookEntityOnDisk(testDir, "book-1")?.docId)
        assertEquals(4, StoreFixture.progressEntityOnDisk(testDir, "book-1")?.chapterIndex)
        val quotesAfter = StoreFixture.quotesOnDisk(testDir, "book-1")
        assertEquals("the quote is still there", 1, quotesAfter.size)
        assertEquals(quoteStampBefore, quotesAfter.single().updatedAtMillis)
    }

    @Test
    fun testDamagedDatabaseRefusesToWriteRatherThanLoseQuotes() = runTest {
        val damaged = ">>> CORRUPTED SQLITE DATABASE <<<"
        StoreFixture.corruptDatabase(testDir, damaged)

        val reopened = BookRepository(context = null)
        try {
            reopened.addQuote("book-1", Quote("q2", "another", 0, 2_000L))
            fail("Expected a write to be refused while database is unreadable")
        } catch (_: LibraryIndexCorruptedException) {
            // Expected: an unreadable SQLite file must never be replaced with an empty DB.
        }
        assertEquals("the damaged file is left alone", damaged, StoreFixture.databaseFile(testDir).readText())
    }

    @Test
    fun testOpeningSqliteDoesNotReadOrDeleteUnrelatedJsonArtifacts() = runTest {
        val jsonArtifact = File(testDir, "library.json").apply { writeText("retained file, not a supported store") }
        val userArtifact = File(testDir, "userdata.json").apply { writeText("retained user artifact") }
        StoreFixture.corruptDatabase(testDir, ">>> CORRUPTED <<<")
        try {
            DatabaseManager.getDatabase(testDir)
            fail("Expected open to fail on a corrupt SQLite file")
        } catch (_: LibraryIndexCorruptedException) {
            // An open failure must not trigger cleanup of other files.
        }
        assertEquals("retained file, not a supported store", jsonArtifact.readText())
        assertEquals("retained user artifact", userArtifact.readText())

        DatabaseManager.closeAndDeleteForFilesRoot(testDir)
        val repository = BookRepository(context = null)
        assertTrue(repository.books.value.isEmpty())
        assertEquals("retained file, not a supported store", jsonArtifact.readText())
        assertEquals("retained user artifact", userArtifact.readText())
    }

    @Test
    fun testProtectionAgainstOverwritingCorruptedIndexWithEmptyList() = runTest {
        val corruptedContent = ">>> CORRUPTED DATA NOT SQLITE <<<"
        StoreFixture.corruptDatabase(testDir, corruptedContent)

        val repository = BookRepository(context = null)

        try {
            repository.readIndex()
            fail("Expected LibraryIndexCorruptedException when reading corrupt database")
        } catch (_: LibraryIndexCorruptedException) {
            // Expected
        }

        try {
            repository.addReadingSeconds("nonexistent-book", 10)
            fail("Expected LibraryIndexCorruptedException when attempting to update corrupt database")
        } catch (_: LibraryIndexCorruptedException) {
            // Expected
        }

        assertEquals(corruptedContent, StoreFixture.databaseFile(testDir).readText())

        val orphanDir = File(testDir, "images/orphan-id")
        orphanDir.mkdirs()
        repository.cleanOrphanCaches()
        assertTrue("Orphan cache should NOT be wiped when database is corrupted", orphanDir.exists())
        orphanDir.deleteRecursively()
    }

    @Test
    fun testThreadSafeConcurrentUpdatesAndReads() = runTest {
        val initialBook = createSampleBook("concurrent-book", "Concurrent Book")
        StoreFixture.seed(testDir, listOf(initialBook))

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

        assertEquals(
            iterations.toLong(),
            StoreFixture.bookEntityOnDisk(testDir, "concurrent-book")?.readingSeconds,
        )
    }
}
