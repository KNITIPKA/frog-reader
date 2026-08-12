package com.example.frogreader.ui.library

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.bookOrderKey
import com.example.frogreader.data.model.shelfOrderKey
import com.example.frogreader.data.model.sortTs

/**
 * One slot in the library grid: either a loose book or a shelf. The screen gets
 * a single stream of these so it never has to splice two lists itself.
 */
sealed interface LibraryEntry {
    /**
     * UI identity — the LazyGrid key and the drag bounds-map key. Type-prefixed
     * so book ids and shelf ids can never collide, and so the key changes when
     * a book joins a shelf.
     */
    val id: String

    /** Position in the single descending stream shared by books and shelves. */
    val sortTs: Long

    data class BookEntry(val book: Book) : LibraryEntry {
        override val id: String get() = bookOrderKey(book.id)
        override val sortTs: Long get() = book.sortTs
    }

    data class ShelfEntry(val shelf: Shelf, val books: List<Book>) : LibraryEntry {
        override val id: String get() = shelfOrderKey(shelf.id)
        override val sortTs: Long get() = shelf.sortTs
    }
}

/**
 * Merges books and shelves into the grid order. Defensive by design: a member
 * id that no longer resolves is dropped rather than faked, so a torn read
 * (books updated, shelves not yet) can never hide a book.
 *
 * A shelf with one book, or none, is still a shelf — the user can make an empty
 * one and fill it later — so nothing here dissolves a shelf for being small.
 */
internal fun buildEntries(books: List<Book>, shelves: List<Shelf>): List<LibraryEntry> {
    if (shelves.isEmpty()) {
        // `books` already arrives sorted from the repository.
        return books.map { LibraryEntry.BookEntry(it) }
    }

    val byId = books.associateBy { it.id }
    val shelved = HashSet<String>()
    val shelfEntries = ArrayList<LibraryEntry>(shelves.size)

    for (shelf in shelves) {
        val members = shelf.bookIds.mapNotNull { byId[it] }.filter { it.id !in shelved }
        members.forEach { shelved += it.id }
        shelfEntries += LibraryEntry.ShelfEntry(shelf, members)
    }

    val loose: List<LibraryEntry> = books
        .filter { it.id !in shelved }
        .map { LibraryEntry.BookEntry(it) }

    // thenBy(id) matters: a book taken out of a shelf keeps its own timestamp
    // and can tie with the shelf's seeded sortKey, and TimSort stability only
    // preserves the incoming order, which is disk order.
    return (loose + shelfEntries).sortedWith(
        compareByDescending<LibraryEntry> { it.sortTs }.thenBy { it.id },
    )
}
