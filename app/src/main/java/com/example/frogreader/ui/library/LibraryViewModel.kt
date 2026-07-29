package com.example.frogreader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.SettingsRepository

sealed interface LibraryMessage {
    data class Imported(val title: String) : LibraryMessage
    data object ImportFailed : LibraryMessage
    data object ImportFailedDrm : LibraryMessage
}

class LibraryViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    val books: StateFlow<List<Book>> = repository.books

    /**
     * The grid's single source of truth: loose books and shelves in one order.
     * Kept separate from [books], which the widget and stats still read raw.
     */
    val entries: StateFlow<List<LibraryEntry>> =
        combine(repository.books, repository.shelves, ::buildEntries)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = buildEntries(repository.books.value, repository.shelves.value),
            )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    private val _viewMode = MutableStateFlow(LibraryViewMode.GRID)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    fun setViewMode(mode: LibraryViewMode) {
        _viewMode.value = mode
        settingsRepository?.let { settings ->
            viewModelScope.launch {
                runCatching { settings.setLibraryViewMode(mode) }
            }
        }
    }

    fun toggleViewMode() {
        setViewMode(if (_viewMode.value == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID)
    }

    init {
        // One background sweep per app start (the library screen is the
        // entry point): drop caches orphaned by failed imports.
        viewModelScope.launch { runCatching { repository.cleanOrphanCaches() } }
        if (settingsRepository != null) {
            viewModelScope.launch {
                runCatching {
                    settingsRepository.libraryViewMode.collect { mode ->
                        _viewMode.value = mode
                    }
                }
            }
        }
    }

    fun updateBookDetails(bookId: String, title: String, author: String?, newCoverUri: Uri?) {
        viewModelScope.launch {
            runCatching { repository.updateBookDetails(bookId, title, author, newCoverUri) }
        }
    }

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun coverFileFor(book: Book): File? = repository.coverFileFor(book)

    fun importBook(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _importing.value = true
            runCatching { repository.importBook(uri) }
                .onSuccess { _messages.send(LibraryMessage.Imported(it.title)) }
                .onFailure { error ->
                    _messages.send(
                        if (error is com.example.frogreader.data.parser.mobi.MobiDrmException) {
                            LibraryMessage.ImportFailedDrm
                        } else {
                            LibraryMessage.ImportFailed
                        },
                    )
                }
            _importing.value = false
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch { repository.deleteBook(book.id) }
    }

    // --------------------------------------------------------------- shelves

    /**
     * A book was dropped onto another book. The TARGET goes first: the new
     * shelf inherits its position so it appears exactly where the target was.
     * Emits the new shelf id so the screen can open it for renaming.
     */
    fun createShelf(draggedBookId: String, targetBookId: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.createShelf(listOf(targetBookId, draggedBookId)) }
                .getOrNull()
                ?.let { onCreated(it.id) }
        }
    }

    fun addToShelf(shelfId: String, bookId: String) {
        viewModelScope.launch { runCatching { repository.addToShelf(shelfId, bookId) } }
    }

    fun removeFromShelf(shelfId: String, bookId: String) {
        viewModelScope.launch { runCatching { repository.removeFromShelf(shelfId, bookId) } }
    }

    fun renameShelf(shelfId: String, name: String) {
        viewModelScope.launch { runCatching { repository.renameShelf(shelfId, name) } }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                LibraryViewModel(app.bookRepository, app.settingsRepository)
            }
        }
    }
}
