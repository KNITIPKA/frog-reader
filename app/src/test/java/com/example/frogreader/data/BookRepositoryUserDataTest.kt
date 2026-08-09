package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The reading lists, ratings and reviews, plus the two things that only a
 * future sync will read: per-record timestamps and tombstones. Those are tested
 * now precisely because nothing consumes them yet — a stamp that silently stops
 * being written would otherwise go unnoticed until the first merge.
 */
class BookRepositoryUserDataTest {

    private val testDir = File("build/tmp/test_files")

    private fun book(id: String, title: String = "Book $id") = Book(
        id = id,
        title = title,
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        addedAtMillis = 1_000L,
    )

    @Before
    fun setUp() {
        testDir.mkdirs()
        StoreFixture.clear(testDir)
    }

    @After
    fun tearDown() = StoreFixture.clear(testDir)

    // ------------------------------------------------------------- the lists

    @Test
    fun `opening a book moves it off the want-to-read list`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.setStatus("a", ReadingStatus.WANT_TO_READ)

        repository.markStarted("a")

        assertEquals(ReadingStatus.READING, repository.bookById("a")?.status)
    }

    @Test
    fun `reaching the end marks the book finished`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.markStarted("a")

        repository.markFinished("a")

        assertEquals(ReadingStatus.FINISHED, repository.bookById("a")?.status)
        assertNotNull(repository.bookById("a")?.finishedAtMillis)
    }

    @Test
    fun `reopening an abandoned book does not quietly un-abandon it`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.setStatus("a", ReadingStatus.ABANDONED)

        repository.markStarted("a")

        assertEquals(ReadingStatus.ABANDONED, repository.bookById("a")?.status)
    }

    @Test
    fun `ratings are clamped to one through five and survive a restart`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        BookRepository(context = null).apply {
            setRating("a", 9)
            setReview("a", "  Worth the time.  ")
        }

        val reopened = BookRepository(context = null)
        assertEquals(5, reopened.bookById("a")?.rating)
        assertEquals("  Worth the time.  ", reopened.bookById("a")?.review)
        assertNotNull(reopened.bookById("a")?.reviewUpdatedAtMillis)
    }

    @Test
    fun `a note can be attached to a quote`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.addQuote("a", Quote("q1", "the line", 0, 1_000L))

        repository.setQuoteNote("a", "q1", "why this matters")

        val reopened = BookRepository(context = null)
        assertEquals("why this matters", reopened.bookById("a")?.quotes?.first()?.note)
    }

    // -------------------------------------------------------- books with no file

    @Test
    fun `a wanted book can exist with no file at all`() = runTest {
        val repository = BookRepository(context = null)
        val wanted = repository.addWishlistBook("Hadji Murat", "Tolstoy")

        assertNull(wanted.fileName)
        assertEquals(ReadingStatus.WANT_TO_READ, wanted.status)
        assertEquals("Hadji Murat", BookRepository(context = null).bookById(wanted.id)?.title)
    }

    @Test
    fun `a book with no file can still be annotated and deleted`() = runTest {
        val repository = BookRepository(context = null)
        val wanted = repository.addWishlistBook("Hadji Murat")
        repository.setRating(wanted.id, 4)
        repository.addQuote(wanted.id, Quote("q1", "remembered from paper", 0, 1_000L))

        assertEquals(4, repository.bookById(wanted.id)?.rating)
        assertEquals(1, repository.bookById(wanted.id)?.quotes?.size)

        repository.deleteBook(wanted.id)
        assertNull(repository.bookById(wanted.id))
    }

    @Test
    fun `opening a book with no file reports it instead of crashing`() = runTest {
        val repository = BookRepository(context = null)
        val wanted = repository.addWishlistBook("Hadji Murat")

        try {
            repository.loadContent(repository.bookById(wanted.id)!!)
            fail("Expected loadContent to refuse a book with no file")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no file"))
        }
    }

    // ------------------------------------------------ substrate for syncing

    @Test
    fun `deleting a book leaves a tombstone that survives a restart`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)
        repository.addQuote("a", Quote("q1", "the line", 0, 1_000L))

        repository.deleteBook("a")

        val tombstones = StoreFixture.tombstonesOnDisk(testDir)
        assertTrue("the book is buried", tombstones.containsKey("a"))
        assertTrue("so is its quote", tombstones.containsKey("q1"))
        assertTrue("the surviving book is not", !tombstones.containsKey("b"))
        assertTrue("and the stamp is a real time", tombstones.getValue("a") > 0L)

        assertTrue(BookRepository(context = null).let {
            StoreFixture.tombstonesOnDisk(testDir).containsKey("a")
        })
    }

    @Test
    fun `removing a single quote buries only that quote`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.addQuote("a", Quote("q1", "one", 0, 1_000L))
        repository.addQuote("a", Quote("q2", "two", 0, 2_000L))

        repository.removeQuote("a", "q1")

        val tombstones = StoreFixture.tombstonesOnDisk(testDir)
        assertTrue(tombstones.containsKey("q1"))
        assertTrue(!tombstones.containsKey("q2"))
        assertEquals(1, StoreFixture.quotesOnDisk(testDir, "a").size)
    }

    @Test
    fun `removing a bookmark buries it too`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.toggleBookmark("a", Bookmark("m1", 10, 0, "preview", 1_000L))

        repository.removeBookmark("a", "m1")

        assertTrue(StoreFixture.tombstonesOnDisk(testDir).containsKey("m1"))
    }

    @Test
    fun `an id that comes back is no longer buried`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)
        repository.deleteBook("a")
        assertTrue(StoreFixture.tombstonesOnDisk(testDir).containsKey("a"))

        // Restoring a backup brings the same id back. Leaving the tombstone in
        // place would let a later merge delete the book all over again.
        repository.addBookForRestore(book("a"))

        assertTrue(
            "a resurrected id must not stay in the graveyard",
            !StoreFixture.tombstonesOnDisk(testDir).containsKey("a"),
        )
        assertNotNull(repository.bookById("a"))
    }

    @Test
    fun `a record is stamped when it changes and left alone when it does not`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)

        repository.setRating("a", 4)
        val stampA = StoreFixture.userDataOnDisk(testDir, "a")?.updatedAtMillis
        assertNotNull(stampA)
        assertTrue("a changed record carries a real time", stampA!! > 0L)

        // Touching a different book must not restamp this one.
        Thread.sleep(2)
        repository.setRating("b", 2)
        assertEquals(stampA, StoreFixture.userDataOnDisk(testDir, "a")?.updatedAtMillis)
    }

    @Test
    fun `a page turn stamps the position and not the quotes`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        repository.addQuote("a", Quote("q1", "the line", 0, 1_000L))
        val quoteStamp = StoreFixture.quotesOnDisk(testDir, "a").first().updatedAtMillis

        Thread.sleep(2)
        repository.saveProgress("a", ReadingProgress(chapterIndex = 2))

        assertTrue((StoreFixture.progressOnDisk(testDir, "a")?.updatedAtMillis ?: 0L) > 0L)
        assertEquals(
            "the quote was not touched",
            quoteStamp,
            StoreFixture.quotesOnDisk(testDir, "a").first().updatedAtMillis,
        )
    }
}
