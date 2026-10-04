package com.example.frogreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookSharing
import com.example.frogreader.data.SharedBookFile
import com.example.frogreader.data.metadata.MetadataFileInfo
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class BookInfoState(
    val book: Book? = null,
    val cover: File? = null,
    val loading: Boolean = true,
    val fileLoading: Boolean = true,
    val fileAvailable: Boolean = false,
    val fileInfo: MetadataFileInfo? = null,
    val sizeBytes: Long = 0,
    val sharing: Boolean = false,
    val preparedShare: SharedBookFile? = null,
    val shareError: String? = null,
    val readingLogEntry: ReadingLogEntry? = null,
    val readingHistory: List<ReadingLogEntry> = emptyList(),
    val logOnly: Boolean = false,
    val reviewSaving: Boolean = false,
    val reviewError: Boolean = false,
    val reviewSavedGeneration: Int = 0,
)

internal class BookInfoViewModel(private val app: FrogReaderApp, private val bookId: String, private val readingLogId: String? = null) : ViewModel() {
    private val repository = app.bookRepository
    private val _state = run {
        val initialBook = repository.books.value.firstOrNull { it.id == bookId }
        val initialCover = initialBook?.let(repository::coverFileFor)
        val initialFile = initialBook?.let(repository::bookFileFor)
        MutableStateFlow(
            BookInfoState(
                book = initialBook,
                cover = initialCover,
                loading = initialBook == null,
                fileLoading = initialBook != null,
                fileAvailable = initialFile != null,
                sizeBytes = initialFile?.length() ?: (initialBook?.sizeBytes ?: 0L),
            )
        )
    }
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.books, repository.readingLog) { books, log ->
                val selected = readingLogId?.let { id -> log.firstOrNull { it.id == id } }
                val libraryBook = if (readingLogId == null) books.firstOrNull { it.id == bookId }
                    else selected?.linkedBookId?.let { id -> books.firstOrNull { it.id == id } }
                val book = if (selected == null) libraryBook else libraryBook?.copy(title = selected.title,
                    author = selected.author, series = selected.series.takeIf { it.isNotBlank() }) ?: Book(
                    "reading-log:${selected.id}", selected.title, selected.author, BookFormat.EPUB,
                    addedAtMillis = selected.createdAtMillis, series = selected.series.takeIf { it.isNotBlank() })
                val historyId = libraryBook?.id ?: selected?.linkedBookId
                ResolvedBook(book, selected, log.filter { historyId != null && it.linkedBookId == historyId }, libraryBook == null && selected != null)
            }.distinctUntilChanged().collectLatest { resolved ->
                val book = resolved.book
                _state.update { it.copy(readingLogEntry = resolved.entry, readingHistory = resolved.history, logOnly = resolved.logOnly) }
                val previous = _state.value
                if (book == null) {
                    _state.update {
                        it.copy(
                            book = null,
                            cover = null,
                            loading = false,
                            fileLoading = false,
                            fileAvailable = false,
                            fileInfo = null,
                            sizeBytes = 0,
                        )
                    }
                    return@collectLatest
                }
                val currentFile = if (resolved.logOnly) null else repository.bookFileFor(book)
                val fileMismatch = previous.book?.fileName != book.fileName
                val changedFile = previous.loading || previous.fileLoading || fileMismatch
                val availableNow = currentFile != null

                _state.update {
                    val resolvedSize = currentFile?.length()
                        ?: (if (!fileMismatch && it.sizeBytes > 0) it.sizeBytes else book.sizeBytes)
                    it.copy(
                        book = book,
                        cover = if (resolved.entry != null) repository.coverFileFor(resolved.entry) else repository.coverFileFor(book),
                        loading = false,
                        fileLoading = changedFile,
                        fileAvailable = availableNow,
                        sizeBytes = resolvedSize,
                    )
                }

                if (changedFile) {
                    val (file, size, info) = withContext(Dispatchers.IO) {
                        val f = currentFile?.takeIf { it.isFile } ?: if (resolved.logOnly) null else repository.bookFileFor(book)?.takeIf { it.isFile }
                        val s = f?.length() ?: book.sizeBytes
                        val i = if (f == null) null else try {
                            repository.metadataFileInfo(book)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null // Stored metadata is still useful even if the file header is damaged.
                        }
                        Triple(f, s, i)
                    }
                    _state.update {
                        it.copy(
                            fileLoading = false,
                            fileAvailable = file != null,
                            fileInfo = info,
                            sizeBytes = size,
                        )
                    }
                }
            }
        }
    }

    fun share() {
        if (_state.value.sharing || !_state.value.fileAvailable) return
        val sharedBookId = _state.value.book?.id ?: return
        _state.update { it.copy(sharing = true, shareError = null) }
        viewModelScope.launch {
            try {
                val file = BookSharing.prepare(repository, app.cacheDir, sharedBookId)
                _state.update { it.copy(preparedShare = file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                shareFinished(error.message ?: "Could not prepare the book file.")
            }
        }
    }

    fun shareFinished(error: String? = null) {
        _state.update { it.copy(sharing = false, preparedShare = null, shareError = error) }
    }

    fun dismissError() { _state.update { it.copy(shareError = null) } }

    fun saveReview(ratingHalfStars: Int?, review: String) {
        if (_state.value.reviewSaving || bookId.isBlank()) return
        _state.update { it.copy(reviewSaving = true, reviewError = false) }
        viewModelScope.launch {
            try {
                repository.saveBookReview(bookId, ratingHalfStars, review)
                _state.update { it.copy(reviewSavedGeneration = it.reviewSavedGeneration + 1) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(reviewError = true) } }
            finally { _state.update { it.copy(reviewSaving = false) } }
        }
    }

    private data class ResolvedBook(val book: Book?, val entry: ReadingLogEntry?, val history: List<ReadingLogEntry>, val logOnly: Boolean)

    companion object {
        fun factory(bookId: String, readingLogId: String? = null) = viewModelFactory {
            initializer { BookInfoViewModel(this[APPLICATION_KEY] as FrogReaderApp, bookId, readingLogId) }
        }
    }
}
