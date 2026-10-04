package com.example.frogreader.ui.profile

import androidx.lifecycle.SavedStateHandle
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuotesAndBookmarksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val quote = Quote("q", "A passage", 0, 1, note = "Original thought")
    private val book = Book("b", "Title", format = BookFormat.EPUB, addedAtMillis = 0, quotes = listOf(quote))

    private class NotesRepository(book: Book) : BookRepository(null) {
        override val books = MutableStateFlow(listOf(book))
        var fail = false
        override fun bookById(bookId: String) = books.value.firstOrNull { it.id == bookId }
        override suspend fun setQuoteNote(bookId: String, quoteId: String, note: String?) {
            if (fail) throw IOException("disk full")
            books.value = books.value.map { it.copy(quotes = it.quotes.map { q -> if (q.id == quoteId) q.copy(note = note) else q }) }
        }
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `failed write retains editable draft and retry saves it`() = runTest(dispatcher) {
        val repository = NotesRepository(book)
        val model = QuotesAndBookmarksViewModel(repository, SavedStateHandle())
        model.edit(book, quote)
        model.changeThought("Нові думки 🐸")
        repository.fail = true
        model.saveThought()
        assertTrue(model.editor.value!!.saving)
        advanceUntilIdle()
        assertTrue(model.editor.value!!.failed)
        assertEquals("Нові думки 🐸", model.editor.value!!.text)
        assertEquals(quote.note, repository.books.value.single().quotes.single().note)
        repository.fail = false
        model.saveThought()
        advanceUntilIdle()
        assertNull(model.editor.value)
        assertEquals("Нові думки 🐸", repository.books.value.single().quotes.single().note)
    }

    @Test fun `draft restores from saved state without being written as a saved thought`() {
        val repository = NotesRepository(book)
        val handle = SavedStateHandle()
        val model = QuotesAndBookmarksViewModel(repository, handle)
        model.edit(book, quote)
        model.changeThought("Unfinished draft")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = QuotesAndBookmarksViewModel(repository, restoredHandle)
        assertEquals("Unfinished draft", restored.editor.value!!.text)
        assertTrue(restored.editor.value!!.dirty)
        assertEquals("Original thought", restored.editor.value!!.original)
        restored.discardThought()
        assertNull(restored.editor.value)
        assertEquals(quote.note, repository.books.value.single().quotes.single().note)
    }

    @Test fun `save does not pretend success when quote disappeared`() = runTest(dispatcher) {
        val repository = NotesRepository(book)
        val model = QuotesAndBookmarksViewModel(repository, SavedStateHandle())
        model.edit(book, quote)
        model.changeThought("Keep this draft")
        repository.books.value = emptyList()
        model.saveThought()
        advanceUntilIdle()
        assertTrue(model.editor.value!!.failed)
        assertEquals("Keep this draft", model.editor.value!!.text)
    }
}
