package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.Book

/** Resolve against the loaded book; never guess a position for detached or removed records. */
internal fun quoteOrBookmarkTarget(
    book: Book, quoteId: String?, bookmarkId: String?, itemCount: Int,
): ReaderReturnLocation.Main? {
    if (quoteId != null) {
        val quote = book.quotes.firstOrNull { it.id == quoteId } ?: return null
        if (quote.startItem !in 0 until itemCount || quote.startChar < 0) return null
        return ReaderReturnLocation.Main(quote.startItem, charOffset = quote.startChar)
    }
    val bookmark = book.bookmarks.firstOrNull { it.id == bookmarkId } ?: return null
    if (bookmark.orphaned || bookmark.flatIndex !in 0 until itemCount) return null
    return ReaderReturnLocation.Main(bookmark.flatIndex)
}
