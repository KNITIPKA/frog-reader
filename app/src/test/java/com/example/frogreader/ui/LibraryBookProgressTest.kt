package com.example.frogreader.ui

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.library.LibraryReadingAction
import com.example.frogreader.ui.library.libraryProgressFraction
import com.example.frogreader.ui.library.libraryReadPercent
import com.example.frogreader.ui.library.libraryReadingAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBookProgressTest {
    private val book = Book("book", "Alice", format = BookFormat.EPUB, addedAtMillis = 0)

    @Test fun `new books and opened first pages both invite the user to start`() {
        assertEquals(LibraryReadingAction.START, book.libraryReadingAction)
        val opened = book.copy(
            lastOpenedAtMillis = 10, startedAtMillis = 10, readingSeconds = 45,
            status = ReadingStatus.READING,
            progress = ReadingProgress(pagesLeftInChapter = 1, totalPages = 600),
        )
        assertEquals(LibraryReadingAction.START, opened.libraryReadingAction)
    }

    @Test fun `actual progress continues even when it rounds to zero percent`() {
        val reading = book.copy(progress = ReadingProgress(elementIndex = 1, fraction = 0.001f))
        assertEquals(0, reading.libraryReadPercent)
        assertEquals(LibraryReadingAction.CONTINUE, reading.libraryReadingAction)
    }

    @Test fun `saved positions can be resumed before an overall fraction is available`() {
        listOf(
            ReadingProgress(chapterIndex = 1),
            ReadingProgress(elementIndex = 1),
            ReadingProgress(scrollOffset = 20),
            ReadingProgress(fraction = 0.42f),
        ).forEach { progress ->
            assertEquals(LibraryReadingAction.CONTINUE, book.copy(progress = progress).libraryReadingAction)
        }
    }

    @Test fun `a displayed hundred percent invites the user to read again`() {
        listOf(1f, 0.999f).forEach { fraction ->
            val complete = book.copy(progress = ReadingProgress(fraction = fraction))
            assertEquals(100, complete.libraryReadPercent)
            assertEquals(LibraryReadingAction.READ_AGAIN, complete.libraryReadingAction)
        }
    }

    @Test fun `browsing a completed reading preserves its completed library presentation`() {
        listOf(
            book.copy(status = ReadingStatus.FINISHED),
            book.copy(finishedAtMillis = 10),
        ).forEach { finished ->
            val browsing = finished.copy(progress = ReadingProgress(fraction = 0.42f))
            assertEquals(1f, browsing.libraryProgressFraction)
            assertEquals(100, browsing.libraryReadPercent)
            assertEquals(LibraryReadingAction.READ_AGAIN, browsing.libraryReadingAction)
        }
    }

    @Test fun `a reset rereading starts with the start label again`() {
        val restarted = book.copy(
            status = ReadingStatus.READING, lastOpenedAtMillis = 10, startedAtMillis = 20,
            progress = ReadingProgress(), finishedAtMillis = null,
        )
        assertEquals(0, restarted.libraryReadPercent)
        assertEquals(LibraryReadingAction.START, restarted.libraryReadingAction)
    }

    @Test fun `invalid fractions do not turn an untouched book into a reading`() {
        listOf(Float.NaN, -0.2f).forEach { fraction ->
            val untouched = book.copy(progress = ReadingProgress(fraction = fraction))
            assertEquals(0, untouched.libraryReadPercent)
            assertEquals(LibraryReadingAction.START, untouched.libraryReadingAction)
        }
    }
}
