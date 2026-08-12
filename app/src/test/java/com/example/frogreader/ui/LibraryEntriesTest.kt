package com.example.frogreader.ui

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.sortTs
import com.example.frogreader.ui.library.LibraryEntry
import com.example.frogreader.ui.library.buildEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid stream: shelves take their target's slot, and no book ever vanishes. */
class LibraryEntriesTest {

    private fun book(id: String, addedAt: Long) = Book(
        id = id,
        title = "Title $id",
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        addedAtMillis = addedAt,
    )

    private fun sorted(books: List<Book>) = books.sortedByDescending { it.sortTs }

    @Test
    fun testBooksOnlyKeepRepositoryOrder() {
        val books = sorted(listOf(book("a", 100), book("b", 300), book("c", 200)))

        val entries = buildEntries(books, emptyList())

        assertEquals(listOf("b:b", "b:c", "b:a"), entries.map { it.id })
    }

    @Test
    fun testShelfSitsWhereItsTargetBookWas() {
        val target = book("target", 200)
        val dragged = book("dragged", 400)
        val other = book("other", 300)
        val books = sorted(listOf(target, dragged, other))
        // sortKey seeded from the target, exactly as createShelf does.
        val shelf = Shelf(
            id = "s1",
            bookIds = listOf("target", "dragged"),
            createdAtMillis = 9_999,
            sortKey = target.sortTs,
        )

        val entries = buildEntries(books, listOf(shelf))

        // Was: dragged(400), other(300), target(200). Now the shelf holds
        // target's slot and both members left the top level.
        assertEquals(listOf("b:other", "s:s1"), entries.map { it.id })
        val shelfEntry = entries.last() as LibraryEntry.ShelfEntry
        assertEquals(listOf("target", "dragged"), shelfEntry.books.map { it.id })
    }

    @Test
    fun testAMemberThatResolvesToNothingIsDroppedNotFaked() {
        val books = sorted(listOf(book("a", 100), book("b", 200)))
        // Only "a" resolves; "ghost" must not become a blank tile in the folder.
        val shelf = Shelf(id = "s1", bookIds = listOf("a", "ghost"), createdAtMillis = 50)

        val entries = buildEntries(books, listOf(shelf))

        assertEquals(listOf("b:b", "s:s1"), entries.map { it.id })
        assertEquals(listOf("a"), (entries.last() as LibraryEntry.ShelfEntry).books.map { it.id })
    }

    /**
     * An empty folder is a real folder — the FAB makes one to fill later — so
     * it takes a slot in the grid instead of quietly disappearing.
     */
    @Test
    fun testAnEmptyShelfStillGetsASlot() {
        val books = sorted(listOf(book("a", 100)))
        val shelf = Shelf(id = "s1", bookIds = emptyList(), createdAtMillis = 500)

        val entries = buildEntries(books, listOf(shelf))

        assertEquals(listOf("s:s1", "b:a"), entries.map { it.id })
        assertTrue((entries.first() as LibraryEntry.ShelfEntry).books.isEmpty())
    }

    @Test
    fun testABookIsNeverClaimedByTwoShelves() {
        val books = sorted(listOf(book("a", 100), book("b", 200), book("c", 300)))
        val first = Shelf(id = "s1", bookIds = listOf("a", "b"), createdAtMillis = 10, sortKey = 100)
        // "a" is already taken, so s2 is left holding "c" and nothing else.
        val second = Shelf(id = "s2", bookIds = listOf("a", "c"), createdAtMillis = 20, sortKey = 300)

        val entries = buildEntries(books, listOf(first, second))

        assertEquals(listOf("s:s2", "s:s1"), entries.map { it.id })
        assertEquals(listOf("c"), (entries.first() as LibraryEntry.ShelfEntry).books.map { it.id })
    }

    @Test
    fun testEveryBookAppearsExactlyOnce() {
        val books = sorted(List(9) { book("b$it", (it + 1) * 100L) })
        val shelf = Shelf(
            id = "s1",
            bookIds = listOf("b0", "b1", "b2"),
            createdAtMillis = 1,
            sortKey = 100,
        )

        val entries = buildEntries(books, listOf(shelf))

        val seen = entries.flatMap { entry ->
            when (entry) {
                is LibraryEntry.BookEntry -> listOf(entry.book.id)
                is LibraryEntry.ShelfEntry -> entry.books.map { it.id }
            }
        }
        assertEquals(9, seen.size)
        assertEquals(9, seen.toSet().size)
        assertTrue(seen.containsAll(books.map { it.id }))
    }
}
