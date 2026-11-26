package com.example.frogreader.storage

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.toBookEntity
import com.example.frogreader.data.db.toEntity
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.search.LibrarySearchRepository
import com.example.frogreader.data.search.MatchSpan
import com.example.frogreader.data.search.SearchMatchHighlighter
import com.example.frogreader.data.search.SearchTextNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses disposable cache databases only; never opens the application's library. */
@RunWith(AndroidJUnit4::class)
class SearchSqliteDeviceTest {
    private lateinit var temporary: File
    private val live get() = File(temporary, "live")
    private val source get() = File(temporary, "source")

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        temporary = File(context.cacheDir, "storage-audit-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        DatabaseManager.closeForFilesRoot(live)
        DatabaseManager.closeForFilesRoot(source)
        temporary.deleteRecursively()
    }

    private suspend fun seed(root: File, books: List<Book>) {
        DatabaseManager.withSwapLock(root) { db ->
            db.withWriteTransaction {
                for (book in books) {
                    db.bookDao().saveBookEntity(book.toBookEntity(updatedAtMillis = 1L))
                    for (quote in book.quotes) db.passagesDao().saveQuote(quote.toEntity(book.id))
                }
            }
        }
    }

    @Test fun sqliteUnicodeCandidatesAndHighlightsAgreeOnAndroid() = runBlocking {
        val titles = listOf(
            "Їжак йорж Київ", "І\u0308жак и\u0306орж Киі\u0308в", "Іжак иорж",
            "Пам’ять Café Straße", "وَالكِتَابُ بالقلم", "וּבַסֵּפֶר", "Sometimes Time", "𐐀𐐁 alpha 😀beta",
        )
        val books = titles.mapIndexed { i, title -> Book(id = "b$i", title = title, format = BookFormat.EPUB, addedAtMillis = i.toLong()) }
        seed(live, books)
        for (query in listOf("їж", "і\u0308ж", "йор", "київ", "іж", "пам'я", "cafe", "strass", "كتاب قلم", "ספר", "time", "𐐨", "alpha beta")) {
            val tokens = SearchTextNormalizer.tokenize(query)
            val expected = books.filter { SearchTextNormalizer.matchesAllTokens(listOf(SearchTextNormalizer.normalize(it.title)), tokens) }.map { it.id }.toSet()
            val actual = LibrarySearchRepository(live).searchBooks(query, books)
            assertEquals(query, expected, actual.map { it.book.id }.toSet())
            assertTrue(actual.all { SearchMatchHighlighter.findMatchSpans(it.book.title, query).isNotEmpty() })
        }
        assertEquals(listOf(MatchSpan(0, 3)), SearchMatchHighlighter.findMatchSpans("І\u0308жак", "їж"))
        assertEquals(listOf(MatchSpan(2, 6)), SearchMatchHighlighter.findMatchSpans("الكتاب", "كتاب"))
    }

    @Test fun vacuumSnapshotAndFailedSwapKeepCommittedWalData() = runBlocking {
        val old = Book(id = "old", title = "Original library", format = BookFormat.EPUB, addedAtMillis = 0L, quotes = listOf(Quote("q-old", "Preserve this quote", 0, 1L)))
        val replacement = Book(id = "replacement", title = "Incoming library", format = BookFormat.EPUB, addedAtMillis = 0L)
        seed(live, listOf(old))
        seed(source, listOf(replacement))
        val snapshot = File(temporary, "snapshot.db")
        DatabaseManager.vacuumInto(source, snapshot)
        try {
            DatabaseManager.replaceDatabaseFromStaged(live, snapshot) { throw IOException("Injected failure after Room reopened") }
            throw AssertionError("Restore should fail")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("Injected failure"))
        }
        DatabaseManager.withSwapLock(live) { db ->
            assertEquals(listOf("old"), db.bookDao().getAllBookIds())
            assertEquals("Preserve this quote", db.passagesDao().getQuoteById("q-old")?.text)
        }
        assertEquals(listOf("old"), LibrarySearchRepository(live).searchBooks("original", listOf(old)).map { it.book.id })
    }
}
