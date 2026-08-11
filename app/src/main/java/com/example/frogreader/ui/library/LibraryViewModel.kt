package com.example.frogreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.FolderScanner
import com.example.frogreader.data.ImportMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.parser.mobi.MobiDrmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.SettingsRepository

sealed interface LibraryMessage {
    data class Imported(val title: String) : LibraryMessage
    data class Replaced(val title: String) : LibraryMessage
    data class ImportedMany(val added: Int, val failed: Int) : LibraryMessage
    data object ImportCancelled : LibraryMessage
    data object ImportFailed : LibraryMessage
    data object ImportFailedDrm : LibraryMessage
}

class LibraryViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository? = null,
    /** Null in unit tests, which have no Context and need none of what it is for. */
    private val application: Application? = null,
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
        // entry point): drop caches orphaned by failed imports, and hand back
        // the folder permissions the old scan took and never released.
        viewModelScope.launch { runCatching { repository.cleanOrphanCaches() } }
        application?.let { app ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { FolderScanner.releaseLegacyGrants(app) }
            }
        }
        preloadContinueReading()
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

    /**
     * Parses the book behind the "continue reading" card in the background, so
     * the most likely next tap opens instantly instead of spending a second or
     * two on the same work.
     *
     * After a beat on purpose: the library's own first frame comes first, and
     * this competes for the same cores. Tapping the book before the delay is
     * up costs nothing — the repository hands both callers the same parse.
     */
    private fun preloadContinueReading() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(PRELOAD_DELAY_MILLIS)
            val next = repository.books.value.firstOrNull { it.lastOpenedAtMillis != null }
                ?: return@launch
            runCatching { repository.loadContent(next) }
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

    /**
     * `bookId -> (coverFileName, resolved file)`. The repository's own
     * `coverFileFor` ends in `File.exists()`, a disk stat, and the grid calls it
     * during composition once per tile — plus four more times inside every shelf
     * tile. Caching it keeps the main thread off the filesystem while scrolling.
     *
     * The file name is part of the cached value, not just the key: a new cover
     * always gets a fresh `<id>-<timestamp>.img` name (BookRepository.kt:250),
     * so a changed name is exactly the signal to look again.
     *
     * Only resolved entries are stored. A name that does not resolve yet — an
     * import whose index write beat its cover write — must stay retryable.
     */
    private val coverCache = HashMap<String, Pair<String, File?>>()

    fun coverFileFor(book: Book): File? {
        val name = book.coverFileName ?: return null
        coverCache[book.id]?.let { (cachedName, cachedFile) ->
            if (cachedName == name) return cachedFile
        }
        val file = repository.coverFileFor(book)
        if (file != null) coverCache[book.id] = name to file
        return file
    }

    /** Questions this import needs answered before it can finish. */
    val conflicts = ConflictPrompt()

    /** A book from outside the app, waiting to be looked at before it is kept. */
    val offers = ImportOffer()

    private val _arrived = MutableStateFlow<String?>(null)

    /**
     * The book that has just been added, for the library to grow into place.
     *
     * An id rather than a flag: the grid has to know WHICH tile is the new one,
     * and by the time the library sees it the book is already just another
     * entry in a sorted list.
     */
    val arrived: StateFlow<String?> = _arrived.asStateFlow()

    fun consumeArrival() {
        _arrived.value = null
    }

    fun answerConflict(choice: ConflictChoice, applyToRest: Boolean = false) {
        conflicts.answer(choice, applyToRest)
    }

    fun answerOffer(add: Boolean) {
        offers.answer(add)
    }

    /**
     * Reports a folder scan's batch. The scan screen runs its own import loop —
     * it needs per-row results, which a shared one could not give it — but the
     * library owns the snackbar, so the outcome comes back here.
     */
    fun reportBatchImport(added: Int, failed: Int) {
        if (added == 0 && failed == 0) return
        viewModelScope.launch { _messages.send(LibraryMessage.ImportedMany(added, failed)) }
    }

    fun importBook(uri: Uri?) {
        importBooks(listOfNotNull(uri))
    }

    /**
     * Adds every picked file, one at a time.
     *
     * Sequential because a conflict can only be put to the user one at a time,
     * and because every commit serializes on the repository's index lock
     * anyway — running them together would buy nothing and interleave the
     * questions.
     */
    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            var added = 0
            var failed = 0
            try {
                uris.forEachIndexed { index, uri ->
                    runCatching {
                        addOne(
                            uri = uri,
                            remaining = uris.size - index - 1,
                            // One file is a decision still being made, and the
                            // book gets shown before it is kept. Several is a
                            // decision already taken in bulk — a preview each
                            // would be ten screens to dismiss.
                            offerPreview = uris.size == 1,
                        )
                    }
                        .onSuccess { message ->
                            if (message !is LibraryMessage.ImportCancelled) added++
                            // One file gets a name; a batch gets a count, sent
                            // once at the end rather than as a queue of
                            // snackbars nobody can read.
                            if (uris.size == 1) _messages.send(message)
                        }
                        .onFailure { error ->
                            failed++
                            if (uris.size == 1) _messages.send(error.toMessage())
                        }
                }
                if (uris.size > 1) _messages.send(LibraryMessage.ImportedMany(added, failed))
            } finally {
                _importing.value = false
                conflicts.reset()
                offers.reset()
            }
        }
    }

    /**
     * Stage, ask if there is anything to ask, then commit or throw away.
     *
     * The discard is NonCancellable because the alternative is a book-sized
     * file left in staging every time someone backs out mid-question. It would
     * be swept eventually; "eventually" is half an hour of the user's storage.
     */
    private suspend fun addOne(
        uri: Uri,
        remaining: Int,
        offerPreview: Boolean = false,
    ): LibraryMessage {
        val staged = repository.stageImport(uri)
        var committed = false
        try {
            val duplicate = staged.duplicateOf
            val mode = if (duplicate == null) {
                if (offerPreview && !offers.ask(staged)) null else ImportMode.New
            } else {
                when (conflicts.ask(repository.conflictFor(staged, duplicate, remaining))) {
                    ConflictChoice.CANCEL -> null
                    ConflictChoice.CLONE -> ImportMode.Clone
                    ConflictChoice.REPLACE -> ImportMode.Replace(duplicate.book.id)
                }
            } ?: return LibraryMessage.ImportCancelled

            val book = repository.commitImport(staged, mode)
            committed = true
            _arrived.value = book.id
            return if (mode is ImportMode.Replace) {
                LibraryMessage.Replaced(book.title)
            } else {
                LibraryMessage.Imported(book.title)
            }
        } finally {
            if (!committed) withContext(NonCancellable) { repository.discardImport(staged) }
        }
    }

    /**
     * "Open with Frog Reader" from a browser, a messenger, a file manager.
     *
     * The same path as one file picked in the app, because it is the same act:
     * a book the user has not seen yet, offered before it is kept. It used to
     * be added on the spot and opened, so a mis-tap silently became a library
     * entry and a book they wanted to look at first was decided for them.
     */
    fun importFromIntent(uri: Uri) {
        importBooks(listOf(uri))
    }

    private fun Throwable.toMessage(): LibraryMessage =
        if (this is MobiDrmException) LibraryMessage.ImportFailedDrm else LibraryMessage.ImportFailed

    fun deleteBook(book: Book) {
        coverCache.remove(book.id)
        viewModelScope.launch { repository.deleteBook(book.id) }
    }

    // --------------------------------------------------------------- shelves

    /**
     * A book was dropped onto another book. The TARGET goes first: the new
     * shelf inherits its position so it appears exactly where the target was.
     * Emits the new shelf id — not to open the shelf (a launcher doesn't), but
     * so the screen can play the arrival animation on that one tile.
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
        /** Long enough for the library to have drawn itself first. */
        private const val PRELOAD_DELAY_MILLIS = 1_200L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                LibraryViewModel(app.bookRepository, app.settingsRepository, app)
            }
        }
    }
}
