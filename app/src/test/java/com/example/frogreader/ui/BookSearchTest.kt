package com.example.frogreader.ui

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.library.LibraryEntry
import com.example.frogreader.ui.library.filterEntries
import com.example.frogreader.ui.library.searchBooks
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search reaches past the title, but never at the title's expense. */
class BookSearchTest {

    private fun book(
        id: String,
        title: String = "Title $id",
        author: String? = null,
        series: String? = null,
        description: String? = null,
        addedAt: Long = 1_000L,
    ) = Book(
        id = id,
        title = title,
        author = author,
        series = series,
        description = description,
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        addedAtMillis = addedAt,
    )

    @Test
    fun testATitleMatchOutranksADescriptionMentioningTheSameWord() {
        val mentioned = book("blurb", title = "The Gunslinger", description = "Roland walks on.")
        val named = book("named", title = "Roland")

        val found = searchBooks(listOf(mentioned, named), "roland")

        assertEquals(listOf("named", "blurb"), found.map { it.id })
    }

    @Test
    fun testTheOrderIsTitleAuthorSeriesDescription() {
        val byDescription = book("d", description = "a Roland of a book")
        val bySeries = book("c", series = "Roland cycle")
        val byAuthor = book("b", author = "Roland Barthes")
        val byTitle = book("a", title = "Roland")

        val found = searchBooks(listOf(byDescription, bySeries, byAuthor, byTitle), "Roland")

        assertEquals(listOf("a", "b", "c", "d"), found.map { it.id })
    }

    @Test
    fun testABookThatMatchesNothingIsLeftOut() {
        val found = searchBooks(listOf(book("a", title = "Dune")), "roland")

        assertEquals(emptyList<String>(), found.map { it.id })
    }

    @Test
    fun testAnEmptyQueryKeepsTheListAsItWas() {
        val books = listOf(book("a"), book("b"))

        assertEquals(books, searchBooks(books, "   "))
    }

    /**
     * The grid keeps its own ordering for the direct hits — a shelf name is as
     * direct as a title — and hangs the blurb matches off the end.
     */
    @Test
    fun testTheGridPutsBlurbMatchesLast() {
        val named = book("named", title = "Roland", addedAt = 100)
        val mentioned = book("blurb", title = "Elsewhere", description = "Roland", addedAt = 900)
        val shelved = book("shelved", title = "Roland again", addedAt = 500)
        val books = listOf(mentioned, shelved, named)
        val shelf = Shelf(id = "s1", name = "Roland", bookIds = listOf("shelved"), createdAtMillis = 1)

        val entries = filterEntries(
            entries = listOf(
                LibraryEntry.ShelfEntry(shelf, listOf(shelved)),
                LibraryEntry.BookEntry(mentioned),
                LibraryEntry.BookEntry(named),
            ),
            allBooks = books,
            query = "roland",
        )

        // The shelf (sortTs 1) and the titled book (100) sort by time between
        // themselves; the book found only by its blurb comes after both.
        assertEquals(listOf("b:named", "s:s1", "b:blurb"), entries.map { it.id })
    }
}
