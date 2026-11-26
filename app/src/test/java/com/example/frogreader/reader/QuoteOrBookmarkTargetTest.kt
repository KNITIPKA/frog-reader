package com.example.frogreader.reader

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.bookmarkDocumentLocation
import com.example.frogreader.ui.reader.pdfQuoteOrBookmarkTarget
import com.example.frogreader.ui.reader.quoteDocumentLocation
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

    @Test fun `typed reflowable anchors take precedence over legacy fields`() {
        val position = DocumentLocation.Reflowable(3, 20, charOffset = 7, scrollOffset = 120)
        val typedBook = book.copy(quotes = listOf(quote.copy(documentLocation = position)))
        assertEquals(position, quoteDocumentLocation(typedBook, typedBook.quotes.single()))
        assertEquals(ReaderReturnLocation.Main(20, charOffset = 7, scrollOffset = 120),
            quoteOrBookmarkTarget(typedBook, "q", null, 50))
    }

    @Test fun `pdf quotes and bookmarks resolve page coordinates without element anchors`() {
        val quotePosition = DocumentLocation.Pdf(4, x = 13f, y = 210f, zoom = 1.5f)
        val bookmarkPosition = DocumentLocation.Pdf(7, y = 105f)
        val pdf = book.copy(format = BookFormat.PDF,
            quotes = listOf(quote.copy(startItem = -1, startChar = -1, chapterIndex = -1, documentLocation = quotePosition)),
            bookmarks = listOf(bookmark.copy(flatIndex = -1, chapterIndex = -1, documentLocation = bookmarkPosition)))
        assertEquals(quotePosition, pdfQuoteOrBookmarkTarget(pdf, "q", null, 10))
        assertEquals(bookmarkPosition, pdfQuoteOrBookmarkTarget(pdf, null, "b", 10))
        assertEquals(bookmarkPosition, bookmarkDocumentLocation(pdf, pdf.bookmarks.single()))
        assertNull(quoteOrBookmarkTarget(pdf, "q", null, 100))
        assertNull(quoteOrBookmarkTarget(pdf, null, "b", 100))
    }

    @Test fun `pdf requires a valid typed page and never reuses legacy chapter positions`() {
        val pdf = book.copy(format = BookFormat.PDF)
        assertNull(pdfQuoteOrBookmarkTarget(pdf, "q", null, 100))
        assertNull(pdfQuoteOrBookmarkTarget(pdf, null, "b", 100))
        for (location in listOf(
            DocumentLocation.Pdf(-1), DocumentLocation.Pdf(100), DocumentLocation.Pdf(1, zoom = 0f),
            DocumentLocation.Pdf(1, x = Float.NaN), DocumentLocation.Reflowable(0, 1),
        )) {
            val changed = pdf.copy(quotes = listOf(quote.copy(documentLocation = location)))
            assertNull(pdfQuoteOrBookmarkTarget(changed, "q", null, 10))
        }
        val anchored = pdf.copy(bookmarks = listOf(bookmark.copy(orphaned = true, documentLocation = DocumentLocation.Pdf(2))))
        assertNull(pdfQuoteOrBookmarkTarget(anchored, null, "b", 10))
    }

    @Test fun `a location from another document kind cannot silently fall back`() {
        val wrongType = book.copy(quotes = listOf(quote.copy(documentLocation = DocumentLocation.Pdf(2))))
        assertNull(quoteOrBookmarkTarget(wrongType, "q", null, 50))
        assertNull(pdfQuoteOrBookmarkTarget(wrongType, "q", null, 50))
    }
}
