package com.example.frogreader.ui.library

import com.example.frogreader.data.model.Book

/**
 * How well a book answers a search, or null when it does not answer at all.
 *
 * Lower is better, so the ranks read in the order they are tried. The split
 * exists because searching the description turns up matches that are perfectly
 * real but never what someone typing an author's name is after: type "Roland"
 * and the book called Roland must come first, with the one that merely mentions
 * him in its blurb underneath.
 */
private const val RankTitle = 0
private const val RankAuthor = 1
private const val RankSeries = 2
private const val RankDescription = 3

internal fun searchRank(book: Book, needle: String): Int? = when {
    book.title.contains(needle, ignoreCase = true) -> RankTitle
    book.author?.contains(needle, ignoreCase = true) == true -> RankAuthor
    book.series?.contains(needle, ignoreCase = true) == true -> RankSeries
    book.description?.contains(needle, ignoreCase = true) == true -> RankDescription
    else -> null
}

/**
 * The books a query turns up, best answers first.
 *
 * Within a rank the library's own order is kept, so a search never reshuffles
 * books that are equally good answers.
 */
internal fun searchBooks(books: List<Book>, query: String): List<Book> {
    val needle = query.trim()
    if (needle.isEmpty()) return books
    return books
        .mapNotNull { book -> searchRank(book, needle)?.let { book to it } }
        .sortedBy { (_, rank) -> rank }
        .map { (book, _) -> book }
}
