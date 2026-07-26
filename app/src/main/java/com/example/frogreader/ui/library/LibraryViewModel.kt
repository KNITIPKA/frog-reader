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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface LibraryMessage {
    data class Imported(val title: String) : LibraryMessage
    data object ImportFailed : LibraryMessage
    data object ImportFailedDrm : LibraryMessage
}

class LibraryViewModel(
    private val repository: BookRepository,
) : ViewModel() {

    val books: StateFlow<List<Book>> = repository.books

    init {
        // One background sweep per app start (the library screen is the
        // entry point): drop caches orphaned by failed imports.
        viewModelScope.launch { runCatching { repository.cleanOrphanCaches() } }
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

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                LibraryViewModel(app.bookRepository)
            }
        }
    }
}
