package com.example.frogreader.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class QuoteThoughtEditor(
    val bookId: String,
    val quoteId: String,
    val original: String,
    val text: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
) { val dirty: Boolean get() = text != original }

internal sealed interface QuotesAndBookmarksEvent {
    data class Deleted(val bookId: String, val quote: Quote? = null, val bookmark: Bookmark? = null) : QuotesAndBookmarksEvent
    data object Failed : QuotesAndBookmarksEvent
}

internal class QuotesAndBookmarksViewModel(
    private val repository: BookRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val books = repository.books
    private val _editor = MutableStateFlow(restoreEditor())
    val editor = _editor.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val eventsChannel = Channel<QuotesAndBookmarksEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private val mutationMutex = Mutex()
    private var pendingMutations = 0

    fun coverFor(book: Book) = repository.coverFileFor(book)

    private fun restoreEditor(): QuoteThoughtEditor? {
        val bookId = savedState.get<String>("thoughtBook") ?: return null
        val quoteId = savedState.get<String>("thoughtQuote") ?: return null
        return QuoteThoughtEditor(bookId, quoteId, savedState["thoughtOriginal"] ?: "", savedState["thoughtText"] ?: "")
    }

    fun edit(book: Book, quote: Quote) {
        setEditor(QuoteThoughtEditor(book.id, quote.id, quote.note.orEmpty(), quote.note.orEmpty()))
    }

    fun changeThought(text: String) {
        _editor.value?.takeUnless { it.saving }?.let { setEditor(it.copy(text = text, failed = false)) }
    }

    fun discardThought() { if (_editor.value?.saving != true) setEditor(null) }

    private fun setEditor(editor: QuoteThoughtEditor?) {
        savedState["thoughtBook"] = editor?.bookId
        savedState["thoughtQuote"] = editor?.quoteId
        savedState["thoughtOriginal"] = editor?.original
        savedState["thoughtText"] = editor?.text
        _editor.value = editor
    }

    fun saveThought() {
        val current = _editor.value?.takeUnless { it.saving } ?: return
        setEditor(current.copy(saving = true, failed = false))
        viewModelScope.launch {
            try {
                check(repository.bookById(current.bookId)?.quotes?.any { it.id == current.quoteId } == true)
                repository.setQuoteNote(current.bookId, current.quoteId, current.text)
                setEditor(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setEditor(current.copy(failed = true))
            }
        }
    }

    fun deleteQuote(book: Book, quote: Quote) = mutate {
        repository.removeQuote(book.id, quote.id)
        eventsChannel.send(QuotesAndBookmarksEvent.Deleted(book.id, quote = quote))
    }

    fun deleteBookmark(book: Book, bookmark: Bookmark) = mutate {
        repository.removeBookmark(book.id, bookmark.id)
        eventsChannel.send(QuotesAndBookmarksEvent.Deleted(book.id, bookmark = bookmark))
    }

    fun undo(event: QuotesAndBookmarksEvent.Deleted) = mutate {
        check(repository.bookById(event.bookId) != null)
        event.quote?.let { repository.restoreQuote(event.bookId, it) }
        event.bookmark?.let { repository.restoreBookmark(event.bookId, it) }
    }

    private fun mutate(action: suspend () -> Unit) {
        pendingMutations++
        _busy.value = true
        viewModelScope.launch {
            try { mutationMutex.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { eventsChannel.send(QuotesAndBookmarksEvent.Failed) }
            finally { pendingMutations--; _busy.value = pendingMutations > 0 }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                QuotesAndBookmarksViewModel(app.bookRepository, createSavedStateHandle())
            }
        }
    }
}
