package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
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
 * The reading queues, ratings and reviews, plus the two things that only a
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

    @Test
    fun `overlapping quotes keep their own colors and notes across restart and deletion undo`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        val original = Quote("q1", "the entire paragraph", 0, 1_000L,
            startItem = 1, startChar = 0, endItem = 1, endChar = 100, note = "original")
        val nested = Quote("q2", "part of the paragraph", 0, 2_000L,
            startItem = 1, startChar = 10, endItem = 1, endChar = 30,
            note = "another thought", color = QuoteColor.BLUE)
        repository.addQuote("a", original)
        repository.addQuote("a", nested)
        val reopened = BookRepository(context = null)
        assertEquals(listOf(QuoteColor.DEFAULT, QuoteColor.BLUE), reopened.bookById("a")!!.quotes.map { it.color })
        reopened.removeQuote("a", nested.id)
        assertEquals(listOf(original.id), reopened.bookById("a")!!.quotes.map { it.id })
        reopened.restoreQuote("a", nested)
        val restored = BookRepository(context = null).bookById("a")!!.quotes
        assertEquals("original", restored.single { it.id == original.id }.note)
        assertEquals(QuoteColor.BLUE, restored.single { it.id == nested.id }.color)
        assertEquals("another thought", restored.single { it.id == nested.id }.note)
    }

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

    @Test
    fun `undo quote deletion restores thoughts and anchors without reverting later book changes`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        val quote = Quote("q1", "the line", 2, 1000, startItem = 12, startChar = 5,
            endItem = 13, endChar = 10, note = "Мої думки", bookId = "a")
        repository.addQuote("a", quote)
        repository.removeQuote("a", quote.id)
        repository.setRating("a", 4)
        repository.restoreQuote("a", quote)
        repository.restoreQuote("a", quote)
        val reopened = BookRepository(context = null).bookById("a")!!
        assertEquals(4, reopened.rating)
        assertEquals(quote, reopened.quotes.single().copy(updatedAtMillis = 0))
        assertTrue(!StoreFixture.tombstonesOnDisk(testDir).containsKey(quote.id))
    }

    @Test
    fun `undo bookmark deletion is idempotent and never toggles off a new bookmark`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        val bookmark = Bookmark("b1", 10, 0, "preview", 1000)
        repository.toggleBookmark("a", bookmark)
        repository.removeBookmark("a", bookmark.id)
        repository.restoreBookmark("a", bookmark)
        repository.restoreBookmark("a", bookmark)
        assertEquals(listOf("b1"), BookRepository(context = null).bookById("a")!!.bookmarks.map { it.id })
        assertTrue(!StoreFixture.tombstonesOnDisk(testDir).containsKey("b1"))
        repository.removeBookmark("a", "b1")
        repository.toggleBookmark("a", bookmark.copy(id = "b2"))
        repository.restoreBookmark("a", bookmark)
        assertEquals(listOf("b2"), repository.bookById("a")!!.bookmarks.map { it.id })
    }

    @Test
    fun `clearing thoughts preserves the quote and its location after restart`() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)
        val quote = Quote("q1", "the line", 2, 1000, startItem = 12, startChar = 5, note = "draft")
        repository.addQuote("a", quote)
        repository.setQuoteNote("a", "q1", "   ")
        val restored = BookRepository(context = null).bookById("a")!!.quotes.single()
        assertNull(restored.note)
        assertEquals(12, restored.startItem)
        assertEquals(5, restored.startChar)
        assertEquals("the line", restored.text)
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
    fun `removing selected quotes preserves other passages and book data on disk`() = runTest {
        val first = Quote("q1", "one", 0, 1_000L)
        val second = Quote("q2", "two", 0, 2_000L)
        val kept = Quote("q3", "three", 0, 3_000L, note = "Keep this note")
        val other = Quote("q4", "another book", 0, 4_000L)
        val original = book("a").copy(
            quotes = listOf(first, second, kept),
            rating = 4,
            review = "Keep this review",
            progress = ReadingProgress(fraction = 0.4f),
            bookmarks = listOf(Bookmark("m1", 10, 0, "preview", 1_000L)),
        )
        StoreFixture.seed(testDir, listOf(original, book("b").copy(quotes = listOf(other))))
        val repository = BookRepository(context = null)
        val libraryBefore = File(testDir, "library.json").readText()
        val progressBefore = File(testDir, "progress.json").readText()

        repository.removeQuotes("a", setOf("q1", "q2", "missing", "q4"))

        val reopened = BookRepository(context = null)
        val result = requireNotNull(reopened.bookById("a"))
        assertEquals(listOf("q3"), result.quotes.map { it.id })
        assertEquals(kept.note, result.quotes.single().note)
        assertEquals(original.rating, result.rating)
        assertEquals(original.review, result.review)
        assertEquals(original.progress, result.progress)
        assertEquals(listOf("m1"), result.bookmarks.map { it.id })
        assertEquals(listOf("q4"), reopened.bookById("b")?.quotes?.map { it.id })
        assertEquals(setOf("q1", "q2"), StoreFixture.tombstonesOnDisk(testDir).keys)
        assertEquals(libraryBefore, File(testDir, "library.json").readText())
        assertEquals(progressBefore, File(testDir, "progress.json").readText())
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
