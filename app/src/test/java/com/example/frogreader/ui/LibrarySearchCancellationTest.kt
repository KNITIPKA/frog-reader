package com.example.frogreader.ui

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.search.LibrarySearchRepository
import com.example.frogreader.ui.library.searchLibraryWithFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class LibrarySearchCancellationTest {
    @Test fun `cancelled SQLite search does not start the full library fallback`() = runBlocking {
        val book = mock(Book::class.java)
        val engine = mock(LibrarySearchRepository::class.java)
        val cancelled = CancellationException("Superseded input")
        `when`(engine.searchBooks(any(), any())).thenThrow(cancelled)
        try {
            searchLibraryWithFallback(engine, "time", listOf(book))
            fail("Cancellation must propagate to mapLatest")
        } catch (expected: CancellationException) {
            assertSame(cancelled, expected)
        }
        // Only this error boundary is injected; native SQL/FTS behavior is covered separately.
        verify(book, never()).title
        Unit
    }
}
