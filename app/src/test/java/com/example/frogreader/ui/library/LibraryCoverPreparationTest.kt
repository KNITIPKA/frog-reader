package com.example.frogreader.ui.library

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCoverPreparationTest {
    private fun book(id: String, opened: Boolean = false, cover: Boolean = true) = Book(
        id = id,
        title = id,
        format = BookFormat.EPUB,
        addedAtMillis = 0L,
        fileName = "$id.epub",
        coverFileName = if (cover) "$id.img" else null,
        lastOpenedAtMillis = if (opened) 10L else null,
    )

    @Test
    fun `hero is warmed even when its folder is outside the first viewport`() {
        val loose = (1..13).map { book("loose$it") }
        val hero = book("hero", opened = true)
        val entries = loose.map { LibraryEntry.BookEntry(it) } +
            LibraryEntry.ShelfEntry(Shelf(id = "s", bookIds = listOf(hero.id), createdAtMillis = 0L), listOf(hero))
        val prepared = welcomeCoverBooks(loose + hero, entries)
        assertEquals(hero, prepared.first())
        assertFalse(prepared.any { it.id == "loose13" })
    }

    @Test
    fun `a hero in a visible folder is warmed once and only four mosaic covers are included`() {
        val members = (1..8).map { book("member$it", opened = it == 1) }
        val entries = listOf(LibraryEntry.ShelfEntry(Shelf(id = "s", createdAtMillis = 0L), members))
        assertEquals(members.take(4), welcomeCoverBooks(members, entries))
    }

    @Test
    fun `hidden loose hero does not consume a grid viewport slot`() {
        val books = (0..14).map { book("b$it", opened = it == 0) }
        assertEquals(books.take(13), welcomeCoverBooks(books, books.map { LibraryEntry.BookEntry(it) }))
    }

    @Test
    fun `coverless and empty libraries need no decodes`() {
        assertTrue(welcomeCoverBooks(emptyList(), emptyList()).isEmpty())
        val books = listOf(book("missing", cover = false))
        assertTrue(welcomeCoverBooks(books, books.map { LibraryEntry.BookEntry(it) }).isEmpty())
    }

    @Test
    fun `large folder viewports keep the cover memory budget bounded`() {
        val books = (1..48).map { book("b$it") }
        val entries = books.chunked(4).mapIndexed { index, members ->
            LibraryEntry.ShelfEntry(Shelf(id = "s$index", createdAtMillis = 0L), members)
        }
        assertEquals(32, welcomeCoverBooks(books, entries).size)
    }
}
