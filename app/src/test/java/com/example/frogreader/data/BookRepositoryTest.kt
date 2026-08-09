package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.UserDataStore
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
import org.junit.Assert.assertNull
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
    private val userFile = File(testDir, "userdata.json")
    private val userBakFile = File(testDir, "userdata.json.bak")
    private val userTmpFile = File(testDir, "userdata.json.tmp")
    private val progressFile = File(testDir, "progress.json")

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
        StoreFixture.clear(testDir)
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
    }

    @Test
    fun testNormalAtomicWriteAndBackupCreation() = runTest {
        val initialBook = createSampleBook("book-1", "Initial Book")
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(initialBook))))

        val repository = BookRepository(context = null)
        assertEquals(1, repository.books.value.size)

        // markStarted stamps startedAtMillis (user data) and lastOpenedAtMillis
        // (progress). It says nothing about the book itself, so library.json is
        // not rewritten — that is the point of the split.
        repository.markStarted("book-1")
        assertTrue("userdata.json should exist after the first stamp", userFile.exists())
        assertNotNull(StoreFixture.userDataOnDisk(testDir, "book-1")?.startedAtMillis)
        assertNotNull(StoreFixture.progressOnDisk(testDir, "book-1")?.lastOpenedAtMillis)
        assertFalse("Temp file should be cleaned up after atomic move", userTmpFile.exists())

        // A second write is what creates the backup: it holds the state before
        // this write, not after it.
        val startedFirst = StoreFixture.userDataOnDisk(testDir, "book-1")?.startedAtMillis
        repository.markFinished("book-1")
        assertTrue("userdata.json.bak should exist after the second write", userBakFile.exists())

        val bakOnDisk = json.decodeFromString<UserDataStore>(userBakFile.readText())
        assertEquals(startedFirst, bakOnDisk.userData["book-1"]?.startedAtMillis)
        assertNull("the backup predates markFinished", bakOnDisk.userData["book-1"]?.finishedAtMillis)
        assertNotNull(StoreFixture.userDataOnDisk(testDir, "book-1")?.finishedAtMillis)
    }

    @Test
    fun testSavingProgressDoesNotRewriteTheLibraryOrTheUserData() = runTest {
        val book = createSampleBook("book-1", "Initial Book")
        StoreFixture.seed(testDir, listOf(book))

        val repository = BookRepository(context = null)
        repository.addQuote("book-1", Quote("q1", "a line worth keeping", 0, 1_000L))

        val libraryBefore = indexFile.readText()
        val userDataBefore = userFile.readText()

        repository.saveProgress("book-1", ReadingProgress(chapterIndex = 3, fraction = 0.4f))
        repository.saveProgress("book-1", ReadingProgress(chapterIndex = 4, fraction = 0.5f))

        assertEquals("library.json must not be touched by a page turn", libraryBefore, indexFile.readText())
        assertEquals("userdata.json must not be touched by a page turn", userDataBefore, userFile.readText())
        assertEquals(4, StoreFixture.progressOnDisk(testDir, "book-1")?.position?.chapterIndex)
        assertEquals(
            "the quote is still there",
            1,
            StoreFixture.userDataOnDisk(testDir, "book-1")?.quotes?.size,
        )
    }

    @Test
    fun testDamagedProgressLosesPositionsButKeepsQuotes() = runTest {
        val book = createSampleBook("book-1", "Initial Book")
        StoreFixture.seed(testDir, listOf(book))
        val repository = BookRepository(context = null)
        repository.addQuote("book-1", Quote("q1", "a line worth keeping", 0, 1_000L))
        repository.saveProgress("book-1", ReadingProgress(chapterIndex = 7))

        // Both copies of the position map are gone.
        progressFile.writeText("}{ truncated")
        File(testDir, "progress.json.bak").writeText("also broken")

        val reopened = BookRepository(context = null)
        val reloaded = reopened.bookById("book-1")
        assertNotNull(reloaded)
        assertEquals("the position is lost, as expected", 0, reloaded?.progress?.chapterIndex)
        assertEquals("the quote is not", 1, reloaded?.quotes?.size)
        assertEquals("a line worth keeping", reloaded?.quotes?.first()?.text)
    }

    @Test
    fun testDamagedUserDataRefusesToWriteRatherThanLoseQuotes() = runTest {
        val book = createSampleBook("book-1", "Initial Book")
        StoreFixture.seed(testDir, listOf(book))
        val repository = BookRepository(context = null)
        repository.addQuote("book-1", Quote("q1", "a line worth keeping", 0, 1_000L))

        val damaged = ">>> CORRUPTED <<<"
        userFile.writeText(damaged)
        File(testDir, "userdata.json.bak").writeText(damaged)

        val reopened = BookRepository(context = null)
        try {
            reopened.addQuote("book-1", Quote("q2", "another", 0, 2_000L))
            fail("Expected a write to be refused while user data is unreadable")
        } catch (e: LibraryIndexCorruptedException) {
            // Expected: quotes are irreplaceable, so an unreadable store must
            // never be treated as an empty one and written back.
        }
        assertEquals("the damaged file is left alone", damaged, userFile.readText())
    }

    @Test
    fun testCorruptedLibraryJsonFallbackRecoveryFromBak() = runTest {
        val originalBook = createSampleBook("book-bak", "Backup Book")
        val validBakContent = json.encodeToString(StoreFixture.indexOf(listOf(originalBook)))
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
        val validBakContent = json.encodeToString(StoreFixture.indexOf(listOf(originalBook)))
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
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(initialBook))))

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
            StoreFixture.progressOnDisk(testDir, "concurrent-book")?.readingSeconds,
        )
    }
}
