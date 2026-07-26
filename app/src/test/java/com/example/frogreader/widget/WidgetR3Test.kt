package com.example.frogreader.widget

import android.content.Context
import android.content.Intent
import com.example.frogreader.MainActivity
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WidgetR3Test {

    private fun createSampleBook(id: String = "book-r3-1", title: String = "R3 Test Book"): Book {
        return Book(
            id = id,
            title = title,
            author = "Test Author",
            format = BookFormat.EPUB,
            fileName = "$id.epub",
            addedAtMillis = System.currentTimeMillis(),
        )
    }

    // --- 1. Intent Construction Tests (ContinueReadingWidget.createOpenIntent) ---

    @Test
    fun testCreateOpenIntent_withNonNullBook_setsActionAndExtraBookId() {
        val mockContext = mock<Context>()
        val mockIntent = mock<Intent>()
        val book = createSampleBook(id = "book-123", title = "Kotlin Guide")

        val resultIntent = ContinueReadingWidget.createOpenIntent(mockContext, book, mockIntent)

        assertEquals(mockIntent, resultIntent)
        verify(mockIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        verify(mockIntent).action = ContinueReadingWidget.ACTION_OPEN_BOOK
        verify(mockIntent).putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, "book-123")
    }

    @Test
    fun testCreateOpenIntent_withNullBook_doesNotSetActionOpenBookOrExtra() {
        val mockContext = mock<Context>()
        val mockIntent = mock<Intent>()

        val resultIntent = ContinueReadingWidget.createOpenIntent(mockContext, null, mockIntent)

        assertEquals(mockIntent, resultIntent)
        verify(mockIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        verify(mockIntent, never()).action = ContinueReadingWidget.ACTION_OPEN_BOOK
        verify(mockIntent, never()).putExtra(eq(MainActivity.EXTRA_OPEN_BOOK_ID), any<String>())
    }

    // --- 2. Intent Handling Tests (MainActivity.processIntentForNavigation) ---

    @Test
    fun testProcessIntentForNavigation_validActionAndExistingBook_returnsBookId() {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook(id = "book-456", title = "Found Book")
        whenever(mockRepository.bookById("book-456")).thenReturn(book)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(ContinueReadingWidget.ACTION_OPEN_BOOK)
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn("book-456")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertEquals("book-456", result)
    }

    @Test
    fun testProcessIntentForNavigation_validActionButMissingBookId_returnsNull() {
        val mockRepository = mock<BookRepository>()

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(ContinueReadingWidget.ACTION_OPEN_BOOK)
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn(null)

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when book ID extra is missing/null", result)
    }

    @Test
    fun testProcessIntentForNavigation_validActionButBlankBookId_returnsNull() {
        val mockRepository = mock<BookRepository>()

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(ContinueReadingWidget.ACTION_OPEN_BOOK)
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn("   ")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when book ID extra is blank", result)
    }

    @Test
    fun testProcessIntentForNavigation_validActionButNonExistentBookId_returnsNull() {
        val mockRepository = mock<BookRepository>()
        whenever(mockRepository.bookById("missing-id")).thenReturn(null)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(ContinueReadingWidget.ACTION_OPEN_BOOK)
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn("missing-id")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when book does not exist in repository", result)
    }

    @Test
    fun testProcessIntentForNavigation_nullAction_returnsNull() {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook(id = "book-789")
        whenever(mockRepository.bookById("book-789")).thenReturn(book)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(null)
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn("book-789")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when intent action is null", result)
    }

    @Test
    fun testProcessIntentForNavigation_differentAction_returnsNull() {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook(id = "book-789")
        whenever(mockRepository.bookById("book-789")).thenReturn(book)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn("android.intent.action.VIEW")
        whenever(intent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenReturn("book-789")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when intent action is not ACTION_OPEN_BOOK", result)
    }
}
