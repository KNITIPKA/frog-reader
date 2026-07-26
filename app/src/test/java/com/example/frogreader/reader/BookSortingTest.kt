package com.example.frogreader.reader

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSortingTest {

    @Test
    fun lastOpenedBookIsAlwaysFirst() {
        val now = System.currentTimeMillis()

        val book1 = Book(
            id = "1",
            title = "Book 1",
            format = BookFormat.EPUB,
            fileName = "1.epub",
            addedAtMillis = now - 10000,
            lastOpenedAtMillis = now - 5000,
        )

        val book2 = Book(
            id = "2",
            title = "Book 2 (Most Recently Opened)",
            format = BookFormat.EPUB,
            fileName = "2.epub",
            addedAtMillis = now - 20000,
            lastOpenedAtMillis = now - 100, // opened most recently
        )

        val book3 = Book(
            id = "3",
            title = "Book 3 (Never Opened)",
            format = BookFormat.FB2,
            fileName = "3.fb2",
            addedAtMillis = now - 3000,
            lastOpenedAtMillis = null,
        )

        val books = listOf(book1, book2, book3)
        val sorted = books.sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }

        assertEquals("2", sorted[0].id)
        assertEquals("3", sorted[1].id)
        assertEquals("1", sorted[2].id)
    }
}
