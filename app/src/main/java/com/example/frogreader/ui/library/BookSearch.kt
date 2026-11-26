package com.example.frogreader.ui.library

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.search.LibrarySearchRepository
import com.example.frogreader.data.search.SearchTextNormalizer

/**
 * The books a query turns up, best answers first (`Title > Author > Series > Description`),
 * with whole-word matches ahead of word-prefix matches and the library's own order preserved
 * within each group.
 *
 * Every query token must begin a word (the same rule as SQLite FTS5 `books_fts`), evaluated
 * over [SearchTextNormalizer]-normalized text so Ukrainian apostrophes, Arabic harakat and
 * clitics, Hebrew niqqud and prefixes, and Latin accents all match consistently.
 */
internal fun searchBooks(books: List<Book>, query: String): List<Book> {
    val tokens = SearchTextNormalizer.tokenize(query)
    if (tokens.isEmpty()) return books
    val order = HashMap<String, Int>(books.size * 2)
    books.forEachIndexed { index, book -> order[book.id] = index }
    return books
        .mapNotNull { LibrarySearchRepository.rankBook(it, tokens) }
        .sortedWith(LibrarySearchRepository.rankingComparator { order[it.id] ?: Int.MAX_VALUE })
        .map { it.book }
}
