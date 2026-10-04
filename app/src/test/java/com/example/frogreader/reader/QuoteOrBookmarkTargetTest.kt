package com.example.frogreader.reader

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.quoteOrBookmarkTarget
import org.junit.Assert.*
import org.junit.Test

class QuoteOrBookmarkTargetTest {
    private val quote = Quote("q", "text", 1, 0, startItem = 12, startChar = 45)
    private val bookmark = Bookmark("b", 30, 2, "preview", 0)
    private val book = Book("book", "Title", format = BookFormat.EPUB, addedAtMillis = 0,
        quotes = listOf(quote), bookmarks = listOf(bookmark))

    @Test fun `quote navigation retains exact character anchor`() {
        assertEquals(ReaderReturnLocation.Main(12, charOffset = 45), quoteOrBookmarkTarget(book, "q", null, 50))
    }
    @Test fun `bookmark navigation uses text location instead of obsolete page number`() {
        assertEquals(ReaderReturnLocation.Main(30), quoteOrBookmarkTarget(book, null, "b", 50))
    }
    @Test fun `missing detached and out of bounds targets never jump to an unrelated page`() {
        assertNull(quoteOrBookmarkTarget(book, "missing", null, 50))
        assertNull(quoteOrBookmarkTarget(book, "q", null, 10))
        assertNull(quoteOrBookmarkTarget(book.copy(quotes = listOf(quote.copy(startItem = -1))), "q", null, 50))
        assertNull(quoteOrBookmarkTarget(book.copy(bookmarks = listOf(bookmark.copy(orphaned = true))), null, "b", 50))
        assertNull(quoteOrBookmarkTarget(book, null, "b", 0))
    }
}
