package com.example.frogreader.ui.reader.pdf

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.*
import com.example.frogreader.data.model.*
import com.example.frogreader.data.pdf.PdfCapabilities
import com.example.frogreader.data.pdf.PdfOutlineRepository
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.ui.reader.ReaderReadingSession
import com.example.frogreader.ui.reader.ReaderReadingWriteQueue
import com.example.frogreader.ui.reader.ReaderCompletionReviewState
import com.example.frogreader.ui.reader.ReaderReviewDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.time.LocalDate
import java.util.UUID

internal sealed interface PdfReaderState {
    data object Loading : PdfReaderState
    data class Ready(val document: PdfDocument) : PdfReaderState
    data class Password(val textAvailable: Boolean) : PdfReaderState
    data object Error : PdfReaderState
}

internal data class PdfSearchMatch(val page: Int, val bounds: List<PdfTextBounds>)
internal data class PdfSearchState(
    val query: String = "",
    val matches: List<PdfSearchMatch> = emptyList(),
    val searching: Boolean = false,
    val limited: Boolean = false,
    val error: Boolean = false,
)

/** The PDF engine owns rendering only; all personal data uses the shared repositories. */
internal class PdfReaderViewModel(
    context: Context,
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: StatsRepository,
    private val bookId: String,
    private val session: PdfDocumentSession = PdfDocumentSession(context),
    nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val textAvailable: (() -> Boolean)? = null,
    private val outlineLoader: suspend (File, String?, String?, Int) -> PdfOutline = PdfOutlineRepository(context)::load,
) : ViewModel() {
    /** In-memory generation for restoring a pending note editor after process recreation. */
    val writeSessionId: String = UUID.randomUUID().toString()
    val book = repository.books.map { books -> books.firstOrNull { it.id == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.bookById(bookId))
    val settings = book.map { it?.readerSettings ?: ReaderSettings.defaultFor(BookFormat.PDF) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, book.value?.readerSettings ?: ReaderSettings.defaultFor(BookFormat.PDF))
    val appSettings = settingsRepository.appSettings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _state = MutableStateFlow<PdfReaderState>(PdfReaderState.Loading)
    val state = _state.asStateFlow()
    private val _search = MutableStateFlow(PdfSearchState())
    val search = _search.asStateFlow()
    private val _searchInput = MutableStateFlow("")
    val searchInput = _searchInput.asStateFlow()
    private val _outline = MutableStateFlow<PdfOutlineState>(PdfOutlineState.Idle)
    val outline = _outline.asStateFlow()
    private val _saveError = MutableStateFlow(false)
    val saveError = _saveError.asStateFlow()
    val pdfAnnotationError = repository.pdfAnnotationErrors.map { bookId in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, bookId in repository.pdfAnnotationErrors.value)
    private val _offerReread = MutableStateFlow(book.value?.let { it.hasFinishedReading || it.status == ReadingStatus.ABANDONED } == true)
    val offerReread = _offerReread.asStateFlow()
    private val _rereadBusy = MutableStateFlow(false)
    val rereadBusy = _rereadBusy.asStateFlow()
    private val _rereadError = MutableStateFlow(false)
    val rereadError = _rereadError.asStateFlow()
    private val _navigation = MutableStateFlow<DocumentLocation.Pdf?>(null)
    val navigation = _navigation.asStateFlow()
    private val _finished = MutableStateFlow(false)
    val finished = _finished.asStateFlow()
    private val _completionReview = MutableStateFlow(ReaderCompletionReviewState(
        ReaderReviewDraft(book.value?.effectiveRatingHalfStars, book.value?.review.orEmpty()),
    ))
    val completionReview = _completionReview.asStateFlow()
    private var browsing = _offerReread.value
    private var readingId = book.value?.activeReadingLogId
    private var started = false
    private var finishRequested = false
    private var currentLocation: DocumentLocation.Pdf? = null
    private var currentPageOffsetFraction = 0f
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var outlineJob: Job? = null
    private var outlineSource: OutlineSource? = null
    private var writer: Job? = null
    private val writes = ReaderReadingWriteQueue()
    private val clock = ReaderReadingSession(nowMillis)

    fun load(password: String? = null) {
        loadJob?.cancel()
        searchJob?.cancel()
        outlineJob?.cancel()
        outlineSource = null
        _outline.value = PdfOutlineState.Idle
        _search.value = PdfSearchState()
        _searchInput.value = ""
        loadJob = viewModelScope.launch {
            _state.value = PdfReaderState.Loading
            try {
                val currentBook = repository.bookById(bookId) ?: throw IOException("Book is missing")
                val file = repository.bookFileFor(currentBook) ?: throw IOException("PDF file is missing")
                val document = session.load(Uri.fromFile(file), password)
                if (document.pageCount <= 0) {
                    session.releaseDocument(document)
                    throw IOException("PDF has no pages")
                }
                repository.rememberPdfPassword(bookId, password)
                outlineSource = OutlineSource(file, currentBook.contentHash, password, document.pageCount)
                _state.value = PdfReaderState.Ready(document)
                // Repair an interrupted PDF/data commit only after successful unlock/rendering.
                // A failed repair uses the normal retry queue and never prevents reading.
                repository.schedulePdfAnnotations(bookId)
                if (!started) {
                    started = true
                    if (!browsing) enqueue {
                        repository.markStarted(bookId)
                        readingId = repository.bookById(bookId)?.activeReadingLogId
                    }
                    enqueue { repository.noteOpened(bookId) }
                }
                // Small first-page cover from the isolated renderer; never parse the PDF in-app.
                if (currentBook.coverFileName == null && currentBook.generatePdfCover) launch(Dispatchers.IO) {
                    try {
                        val page = document.getPageInfo(0)
                        if (page.width <= 0 || page.height <= 0) return@launch
                        val scale = minOf(360f / page.width, 540f / page.height)
                        val size = Size((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                        val bytes = document.getPageBitmapSource(0).use { source ->
                            val bitmap = source.getBitmap(size)
                            try { ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                out.toByteArray()
                            } } finally { bitmap.recycle() }
                        }
                        repository.savePdfCoverIfMissing(bookId, bytes, currentBook.contentHash)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A missing cover never prevents reading. */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PdfPasswordException) { _state.value = PdfReaderState.Password(textAvailable?.invoke() ?: PdfCapabilities.canUseText()) }
            catch (_: Exception) { _state.value = PdfReaderState.Error }
        }
    }

    /** Called only when Contents is shown, never during application or document startup. */
    fun loadOutline() {
        if (_outline.value != PdfOutlineState.Idle || _state.value !is PdfReaderState.Ready) return
        val source = outlineSource ?: return
        _outline.value = PdfOutlineState.Loading
        outlineJob = viewModelScope.launch {
            try {
                val result = outlineLoader(source.file, source.contentHash, source.password, source.pageCount)
                ensureActive()
                if (outlineSource === source) _outline.value = PdfOutlineState.Ready(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (outlineSource === source) _outline.value = PdfOutlineState.Error
            }
        }
    }

    fun retryOutline() {
        if (_outline.value != PdfOutlineState.Error) return
        _outline.value = PdfOutlineState.Idle
        loadOutline()
    }

    private class OutlineSource(val file: File, val contentHash: String?, val password: String?, val pageCount: Int)

    fun observePosition(location: DocumentLocation.Pdf, pageOffsetFraction: Float = 0f) {
        if (_rereadBusy.value || _navigation.value != null) return
        val pageCount = (_state.value as? PdfReaderState.Ready)?.document?.pageCount ?: return
        currentLocation = PdfCapabilities.normalizeLocation(location, pageCount)
        currentPageOffsetFraction = pageOffsetFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    }

    fun savePosition() {
        if (browsing || _offerReread.value || _rereadBusy.value || _navigation.value != null || finishRequested) return
        val document = (_state.value as? PdfReaderState.Ready)?.document ?: return
        val location = currentLocation ?: return
        val progress = ReadingProgress(
            fraction = PdfCapabilities.progressFraction(location.pageIndex, document.pageCount, currentPageOffsetFraction),
            totalPages = document.pageCount,
            documentLocation = location,
        )
        enqueue { repository.saveProgressForReading(bookId, progress, readingId) }
    }

    fun setReadingActive(active: Boolean) {
        clock.setRunning(active && !browsing && !_offerReread.value && !_rereadBusy.value && !finishRequested)
        if (!active) flushTime()
    }

    fun flushTime() {
        val seconds = clock.drainSeconds()
        if (seconds <= 0 || browsing || _offerReread.value || _rereadBusy.value || finishRequested) return
        val date = LocalDate.now()
        val lastRead = System.currentTimeMillis().takeIf { clock.qualifiesAsReading }
        val currentReadingId = readingId
        enqueue {
            // First-open bookkeeping precedes this interval in the queue; its id may still
            // be unresolved when the visible reader first flushes foreground time.
            repository.recordReadingTimeForReading(bookId, seconds, currentReadingId ?: readingId, date, lastRead, statsRepository)
        }
    }

    fun keepPreviousReading() { _offerReread.value = false }

    fun chooseReading(resume: Boolean) {
        if (_rereadBusy.value) return
        _rereadBusy.value = true
        _rereadError.value = false
        enqueue {
            try {
                if (resume) repository.resumeReading(bookId) else repository.beginReadingAgain(bookId)
                readingId = repository.bookById(bookId)?.activeReadingLogId
                browsing = false
                finishRequested = false
                _finished.value = false
                clock.reset()
                if (!resume) {
                    currentLocation = null
                    currentPageOffsetFraction = 0f
                    _completionReview.value = ReaderCompletionReviewState()
                    _navigation.value = DocumentLocation.Pdf()
                }
                _offerReread.value = false
            } catch (failure: Exception) {
                _rereadError.value = true
                throw failure
            } finally { _rereadBusy.value = false }
        }
    }

    fun consumeNavigation() { _navigation.value = null }

    fun finishReading() {
        if (finishRequested || browsing || _offerReread.value) return
        setReadingActive(false)
        val document = (_state.value as? PdfReaderState.Ready)?.document ?: return
        val location = currentLocation ?: return
        finishRequested = true
        enqueue {
            repository.saveProgressForReading(bookId, ReadingProgress(
                fraction = 1f, totalPages = document.pageCount, documentLocation = location,
            ), readingId)
            repository.markFinishedForReading(bookId, readingId)
            _finished.value = true
        }
    }

    fun setCompletionRating(rating: Int?) {
        if (!_completionReview.value.saving) _completionReview.value = _completionReview.value.let {
            it.copy(draft = it.draft.copy(ratingHalfStars = rating), error = false)
        }
    }
    fun setCompletionReview(text: String) {
        if (!_completionReview.value.saving) _completionReview.value = _completionReview.value.let {
            it.copy(draft = it.draft.copy(review = text.take(20_000)), error = false)
        }
    }
    fun saveCompletionReview(onSaved: () -> Unit = {}) {
        val state = _completionReview.value
        if (state.saving || !_finished.value) return
        if (!state.dirty) { onSaved(); return }
        val draft = state.draft.clean()
        _completionReview.value = state.copy(saving = true, error = false)
        enqueue {
            try {
                repository.saveReviewForReading(bookId, readingId, draft.ratingHalfStars, draft.review)
                _completionReview.value = state.copy(draft = draft, saved = draft, savedGeneration = state.savedGeneration + 1)
                onSaved()
            } catch (failure: Exception) {
                _completionReview.value = _completionReview.value.copy(saving = false, error = true)
                throw failure
            }
        }
    }

    fun toggleBookmark() {
        val location = currentLocation ?: return
        enqueue { repository.toggleBookmark(bookId, Bookmark(
            id = UUID.randomUUID().toString(), flatIndex = -1, chapterIndex = -1,
            preview = "", createdAtMillis = System.currentTimeMillis(), documentLocation = location,
        )) }
    }
    fun removeBookmark(id: String) = enqueue { repository.removeBookmark(bookId, id) }
    fun removeQuote(id: String) = enqueue { repository.removeQuote(bookId, id) }
    fun removeQuotes(ids: Set<String>) = enqueue { repository.removeQuotes(bookId, ids) }
    fun setQuoteNote(id: String, note: String) = enqueue { repository.setQuoteNote(bookId, id, note) }
    fun setQuoteColor(id: String, color: QuoteColor) = enqueue { repository.setQuoteColor(bookId, id, color) }
    fun addQuote(text: String, bounds: List<PdfTextBounds>, color: QuoteColor) {
        addSavedPassage(text, bounds, color, null)
    }

    /** The returned identity lets the editor wait for the durable repository acknowledgement. */
    fun addNote(text: String, bounds: List<PdfTextBounds>, color: QuoteColor, note: String, id: String = UUID.randomUUID().toString()): String? {
        val clean = note.trim().take(20_000)
        if (clean.isEmpty()) return null
        return addSavedPassage(text, bounds, color, clean, id)
    }

    private fun addSavedPassage(text: String, bounds: List<PdfTextBounds>, color: QuoteColor, note: String?, id: String = UUID.randomUUID().toString()): String? {
        val pageCount = (_state.value as? PdfReaderState.Ready)?.document?.pageCount ?: return null
        val valid = PdfCapabilities.normalizeBounds(bounds, pageCount)
        if (text.isBlank() || valid.isEmpty()) return null
        val first = valid.first()
        val quote = Quote(
            id = id, text = text, chapterIndex = -1,
            createdAtMillis = System.currentTimeMillis(), color = color, note = note,
            documentLocation = DocumentLocation.Pdf(first.pageIndex, (first.left + first.right) / 2f, (first.top + first.bottom) / 2f,
                currentLocation?.zoom ?: 1f), pdfSelection = valid,
        )
        enqueue { if (note == null) repository.addQuote(bookId, quote) else repository.addPdfNote(bookId, quote) }
        return quote.id
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        enqueue { repository.saveReaderSettings(bookId, transform(repository.bookById(bookId)?.readerSettings ?: settings.value)) }
    }
    fun updateAppSettings(transform: (AppSettings) -> AppSettings) = enqueue { settingsRepository.updateApp(transform) }
    suspend fun setDefaultTextApp(action: ExternalTextAction, id: String) = settingsRepository.updateApp {
        when (action) {
            ExternalTextAction.TRANSLATE -> it.copy(defaultTranslator = id)
            ExternalTextAction.DEFINE -> it.copy(defaultDictionary = id)
            ExternalTextAction.SEARCH -> it.copy(defaultSearchApp = id)
        }
    }

    fun setSearchInput(input: String) = search(input)

    fun search(query: String) {
        searchJob?.cancel()
        _searchInput.value = query.take(256)
        val normalized = query.trim().take(256)
        _search.value = PdfSearchState(query = normalized)
        val document = (_state.value as? PdfReaderState.Ready)?.document ?: return
        if (normalized.isEmpty() || !(textAvailable?.invoke() ?: document.supportsTextSearch())) return
        searchJob = viewModelScope.launch {
            _search.value = PdfSearchState(normalized, searching = true)
            delay(250)
            val matches = ArrayList<PdfSearchMatch>()
            try {
                for (page in 0 until document.pageCount) {
                    ensureActive()
                    val found = withContext(Dispatchers.IO) { document.searchDocument(normalized, page..page) }
                    val pageMatches = found[page].orEmpty()
                    for (match in pageMatches) {
                        if (matches.size >= 2_000) break
                        matches += PdfSearchMatch(page, match.bounds.take(256).map { PdfTextBounds(page, it.left, it.top, it.right, it.bottom) })
                    }
                    if (matches.size >= 2_000) {
                        _search.value = PdfSearchState(normalized, matches.toList(), limited = true)
                        return@launch
                    }
                    if (page % 8 == 0) _search.value = PdfSearchState(normalized, matches.toList(), searching = true)
                }
                _search.value = PdfSearchState(normalized, matches.toList())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _search.value = PdfSearchState(normalized, matches.toList(), error = true) }
        }
    }

    fun coverFile() = book.value?.let(repository::coverFileFor)
    fun retryWrites() { _saveError.value = false; drainWrites() }
    fun retryPdfAnnotations() = repository.schedulePdfAnnotations(bookId)
    private fun enqueue(write: suspend () -> Unit) { writes.add(write); if (!_saveError.value) drainWrites() }
    private fun drainWrites() {
        if (writer?.isActive == true || writes.isEmpty) return
        writer = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { writes.drain() } catch (_: Exception) { _saveError.value = true }
            }
        }
    }
    override fun onCleared() {
        outlineSource = null
        outlineJob?.cancel()
        session.close()
        super.onCleared()
    }

    init { load() }
    companion object {
        fun factory(bookId: String) = viewModelFactory { initializer {
            val app = this[APPLICATION_KEY] as FrogReaderApp
            PdfReaderViewModel(app, app.bookRepository, app.settingsRepository, app.statsRepository, bookId)
        } }
    }
}
