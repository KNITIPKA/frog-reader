package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.Quote

/** Legacy element anchors belong only to reflowable books, never to PDF pages. */
internal fun quoteDocumentLocation(book: Book, quote: Quote): DocumentLocation? =
    matchingDocumentLocation(book, quote.documentLocation ?: if (book.format != BookFormat.PDF &&
        quote.startItem >= 0 && quote.startChar >= 0) {
        DocumentLocation.Reflowable(quote.chapterIndex, quote.startItem, quote.startChar)
    } else null)

internal fun bookmarkDocumentLocation(book: Book, bookmark: Bookmark): DocumentLocation? =
    if (bookmark.orphaned) null else matchingDocumentLocation(book, bookmark.documentLocation ?:
        if (book.format != BookFormat.PDF && bookmark.flatIndex >= 0) {
            DocumentLocation.Reflowable(bookmark.chapterIndex, bookmark.flatIndex)
        } else null)

private fun matchingDocumentLocation(book: Book, location: DocumentLocation?): DocumentLocation? =
    when (location) {
        is DocumentLocation.Pdf -> location.takeIf {
            book.format == BookFormat.PDF && it.pageIndex >= 0 && it.x.isFinite() &&
                it.y.isFinite() && it.zoom.isFinite() && it.zoom > 0f
        }
        is DocumentLocation.Reflowable -> location.takeIf {
            book.format != BookFormat.PDF && it.chapterIndex >= 0 && it.elementIndex >= 0 &&
                it.charOffset >= 0 && it.scrollOffset >= 0
        }
        null -> null
    }

private fun savedDocumentLocation(book: Book, quoteId: String?, bookmarkId: String?): DocumentLocation? {
    if (quoteId != null) {
        return book.quotes.firstOrNull { it.id == quoteId }?.let { quoteDocumentLocation(book, it) }
    }
    return book.bookmarks.firstOrNull { it.id == bookmarkId }?.let { bookmarkDocumentLocation(book, it) }
}

/** Resolve against the loaded book; never guess a position for detached or removed records. */
internal fun quoteOrBookmarkTarget(
    book: Book, quoteId: String?, bookmarkId: String?, itemCount: Int,
): ReaderReturnLocation.Main? {
    val location = savedDocumentLocation(book, quoteId, bookmarkId) as? DocumentLocation.Reflowable ?: return null
    if (location.elementIndex !in 0 until itemCount) return null
    return ReaderReturnLocation.Main(
        location.elementIndex,
        charOffset = if (quoteId != null || book.bookmarks.firstOrNull { it.id == bookmarkId }?.documentLocation != null)
            location.charOffset else null,
        scrollOffset = location.scrollOffset,
    )
}

/** Resolve saved PDF destinations only after the real document supplies its page count. */
internal fun pdfQuoteOrBookmarkTarget(
    book: Book, quoteId: String?, bookmarkId: String?, pageCount: Int,
): DocumentLocation.Pdf? = (savedDocumentLocation(book, quoteId, bookmarkId) as? DocumentLocation.Pdf)
    ?.takeIf { it.pageIndex in 0 until pageCount }
