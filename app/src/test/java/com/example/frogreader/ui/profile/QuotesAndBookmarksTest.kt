package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import org.junit.Assert.*
import org.junit.Test

class QuotesAndBookmarksTest {
    private fun book(id: String, quotes: List<Quote> = emptyList()) =
        Book(id, "Title $id", "Леся Українка", BookFormat.EPUB, addedAtMillis = 0, quotes = quotes)

    @Test fun `collection excludes empty books and sorts by latest quote rather than last read`() {
        val a = book("a", listOf(Quote("a1", "old", 0, 1), Quote("a2", "new", 0, 20)))
        val b = book("b", listOf(Quote("b1", "middle", 0, 10))).copy(lastOpenedAtMillis = 999)
        assertEquals(listOf("a", "b"), booksWithQuotesAndBookmarks(listOf(b, book("empty"), a), "").map { it.id })
    }

    @Test fun `search finds thoughts and ignores case whitespace and missing files`() {
        val book = book("a", listOf(Quote("q1", "First", 0, 1, note = "Мої власні ДУМКИ"), Quote("q2", "Second", 0, 2)))
        assertNull(book.fileName)
        assertEquals(listOf("q1"), bookQuotesAndBookmarks(listOf(book), "a", "  думки ", false)!!.quotes.map { it.id })
        assertEquals(2, bookQuotesAndBookmarks(listOf(book), "a", "українка", false)!!.quotes.size)
        assertTrue(bookQuotesAndBookmarks(listOf(book), "a", "unknown", false)!!.quotes.isEmpty())
    }

    @Test fun `book order uses chapter then anchored position and character offset`() {
        val quotes = listOf(
            Quote("unlinked", "u", 1, 40),
            Quote("later", "l", 1, 20, startItem = 10, startChar = 30),
            Quote("early", "e", 1, 30, startItem = 10, startChar = 5),
            Quote("firstChapter", "f", 0, 10, startItem = 1, startChar = 0),
        )
        assertEquals(listOf("firstChapter", "early", "later", "unlinked"), sortedQuotes(quotes, false).map { it.id })
        assertEquals(listOf("unlinked", "early", "later", "firstChapter"), sortedQuotes(quotes, true).map { it.id })
    }

    @Test fun `bookmarks stay in selected book and are newest first including orphaned positions`() {
        val a = book("a").copy(bookmarks = listOf(
            Bookmark("old", 0, 0, "An old place", 1),
            Bookmark("new", 9, 0, "A new place", 20, orphaned = true)))
        val b = book("b").copy(bookmarks = listOf(Bookmark("foreign", 2, 0, "Foreign place", 30)))
        assertEquals(listOf("new", "old"), bookQuotesAndBookmarks(listOf(a, b), "a", "", false)!!.bookmarks.map { it.bookmark.id })
        assertEquals("new", bookQuotesAndBookmarks(listOf(a, b), "a", "NEW", false)!!.bookmarks.single().bookmark.id)
        assertTrue(bookQuotesAndBookmarks(listOf(a, b), "a", "Foreign", false)!!.bookmarks.isEmpty())
    }

    @Test fun `directory includes bookmark-only books and sorts by latest saved item`() {
        val a = book("a", listOf(Quote("q", "text", 0, 10)))
        val b = book("b").copy(bookmarks = listOf(Bookmark("b1", 0, 0, "Bookmark-only preview", 20)))
        val books = listOf(a, b, book("empty"))
        assertEquals(listOf("b", "a"), booksWithQuotesAndBookmarks(books, "").map { it.id })
        val result = booksWithQuotesAndBookmarks(books, "bookmark-only").single()
        assertEquals("b", result.id)
        assertEquals(0, result.quotes.size)
        assertEquals(1, result.bookmarks.size)
    }

    @Test fun `Alice has one quote and zero bookmarks regardless of library totals or search`() {
        val alice = book("alice", listOf(Quote("alice-q", "A rose tree", 12, 1)))
        val other = book("other", (1..5).map { Quote("other-$it", "another quote", 0, it.toLong()) })
            .copy(bookmarks = listOf(Bookmark("b1", 0, 0, "preview", 1), Bookmark("b2", 4, 0, "preview", 2)))
        for (query in listOf("", "rose", "another")) {
            val selected = bookQuotesAndBookmarks(listOf(alice, other), "alice", query, false)!!
            assertEquals(1, selected.quoteCount)
            assertEquals(0, selected.bookmarkCount)
            assertTrue(selected.bookmarks.isEmpty())
            assertTrue(selected.quotes.all { it.id == "alice-q" })
        }
    }

    @Test fun `missing selection never falls back to all books and deleting last item keeps detail scoped`() {
        val a = book("a", listOf(Quote("q", "text", 0, 1)))
        assertNull(bookQuotesAndBookmarks(listOf(a), null, "", false))
        assertNull(bookQuotesAndBookmarks(listOf(a), "deleted", "", false))
        val empty = bookQuotesAndBookmarks(listOf(a.copy(quotes = emptyList())), "a", "", false)!!
        assertEquals("a", empty.book.id)
        assertEquals(0, empty.quoteCount)
        assertTrue(empty.quotes.isEmpty())
        assertTrue(empty.bookmarks.isEmpty())
    }

    @Test fun `bookmark display marks a capped excerpt without changing stored text`() {
        val raw = ("A little sentence with enough words to reach the preview limit. " + "Another part of the sentence continues here.").take(90)
        val display = bookmarkExcerpt(raw)
        assertTrue(display.endsWith("…"))
        assertEquals(raw.substringBeforeLast(' ') + "…", display)
        assertEquals(90, raw.length)
    }

    @Test fun `complete or already ellipsized bookmark excerpts stay intact`() {
        assertEquals("A complete thought.", bookmarkExcerpt("A complete thought."))
        assertEquals("a".repeat(89) + ".", bookmarkExcerpt("a".repeat(89) + "."))
        assertEquals("a".repeat(89) + "…", bookmarkExcerpt("a".repeat(89) + "…"))
        assertEquals("A short paragraph", bookmarkExcerpt(" A short\nparagraph "))
        assertEquals("", bookmarkExcerpt(" "))
    }

}
