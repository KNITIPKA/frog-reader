package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.BOOKMARK_PREVIEW_CHARS
import com.example.frogreader.data.model.Quote

internal data class SavedBookmark(val book: Book, val bookmark: Bookmark)

/** Counts belong to the selected book, independent of search filtering. */
internal data class BookQuotesAndBookmarks(val book: Book, val quotes: List<Quote>, val bookmarks: List<SavedBookmark>) {
    val quoteCount: Int get() = book.quotes.size
    val bookmarkCount: Int get() = book.bookmarks.size
}

/** Display-only: the raw preview also anchors file replacement and must remain untouched. */
internal fun bookmarkExcerpt(preview: String): String {
    val text = preview.trim().replace(Regex("\\s+"), " ")
    if (preview.length < BOOKMARK_PREVIEW_CHARS || text.isEmpty() || text.last() in ".!?…。！？\"”»") return text
    val safeText = text.trimEnd { it.isHighSurrogate() }
    val lastSpace = safeText.lastIndexOf(' ')
    // Stored previews stop at 90 characters, possibly halfway through a word.
    val wholeWords = if (lastSpace > 0 && !preview.last().isWhitespace()) safeText.substring(0, lastSpace) else safeText
    return wholeWords.trimEnd(',', ';', ':', ' ') + "…"
}

private fun Book.matchesIdentity(query: String) =
    title.contains(query, ignoreCase = true) || author.orEmpty().contains(query, ignoreCase = true)

/** Only books with saved content; bookmark-only books must be reachable too. */
internal fun booksWithQuotesAndBookmarks(books: List<Book>, search: String): List<Book> {
    val query = search.trim()
    return books.filter { book ->
        (book.quotes.isNotEmpty() || book.bookmarks.isNotEmpty()) &&
            (query.isEmpty() || book.matchesIdentity(query) ||
                book.quotes.any { it.matches(query) } ||
                book.bookmarks.any { it.preview.contains(query, ignoreCase = true) })
    }.sortedWith(compareByDescending<Book> { book ->
        maxOf(book.quotes.maxOfOrNull { it.createdAtMillis } ?: Long.MIN_VALUE,
            book.bookmarks.maxOfOrNull { it.createdAtMillis } ?: Long.MIN_VALUE)
    }.thenBy { it.title }.thenBy { it.id })
}

private fun Quote.matches(query: String) =
    text.contains(query, ignoreCase = true) || note.orEmpty().contains(query, ignoreCase = true)

/** No selected book means no detail content, never a fallback to the whole library. */
internal fun bookQuotesAndBookmarks(books: List<Book>, bookId: String?, search: String, newestFirst: Boolean): BookQuotesAndBookmarks? {
    val book = books.firstOrNull { it.id == bookId } ?: return null
    val query = search.trim()
    val identityMatches = query.isEmpty() || book.matchesIdentity(query)
    return BookQuotesAndBookmarks(
        book = book,
        quotes = sortedQuotes(book.quotes.filter { identityMatches || it.matches(query) }, newestFirst),
        bookmarks = book.bookmarks.filter { identityMatches || it.preview.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<Bookmark> { it.createdAtMillis }.thenBy { it.id })
            .map { SavedBookmark(book, it) },
    )
}

internal fun sortedQuotes(quotes: List<Quote>, newestFirst: Boolean): List<Quote> =
    if (newestFirst) quotes.sortedWith(compareByDescending<Quote> { it.createdAtMillis }.thenBy { it.id })
    else quotes.sortedWith(compareBy<Quote> { it.chapterIndex }
        .thenBy { if (it.startItem >= 0) it.startItem else Int.MAX_VALUE }
        .thenBy { it.startChar }.thenBy { it.createdAtMillis }.thenBy { it.id })

