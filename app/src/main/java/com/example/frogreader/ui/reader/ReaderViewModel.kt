package com.example.frogreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.previewText
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.withFootnoteRefStyle
import com.example.frogreader.data.model.withoutFootnotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One element of the flattened book, tagged with its chapter. */
class ReaderItem(
    val chapterIndex: Int,
    val element: ContentElement,
)

sealed interface ReaderState {
    data object Loading : ReaderState
    data object Error : ReaderState

    class Ready(
        val book: Book,
        val items: List<ReaderItem>,
        /** Flat index where each chapter starts (for TOC jumps and progress). */
        val chapterStarts: List<Int>,
        val chapterTitles: List<String?>,
        /** Nesting depth of each chapter (0 = top level) — the TOC tree. */
        val chapterDepths: List<Int>,
        /** Footnote key → note text, for tappable [53]-style references. */
        val notes: Map<String, androidx.compose.ui.text.AnnotatedString>,
        /** Link key → flat item index, for Contents entries and references. */
        val linkTargets: Map<String, Int> = emptyMap(),
        /** What the book's own formatting asks for (publisher's mode). */
        val publisherStyle: com.example.frogreader.data.model.PublisherStyle? = null,
        /** Embedded font family name → loaded font (publisher's formatting). */
        val bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
        /** The book's language tag ("ru", "uk", …) — drives hyphenation. */
        val language: String? = null,
    ) : ReaderState {
        fun chapterAt(flatIndex: Int): Int =
            chapterStarts.indexOfLast { it <= flatIndex }.coerceAtLeast(0)
    }
}

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: com.example.frogreader.data.StatsRepository,
    private val bookId: String,
    private val paginationCacheDir: File,
) : ViewModel() {

    /** Live book from the library index (declared before [settings] uses it). */
    val book: StateFlow<Book?> = repository.books
        .map { books -> books.firstOrNull { it.id == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.bookById(bookId))

    /**
     * Effective reading settings: this book's own, or the app-wide
     * "last used" ones until the user changes something in this book.
     */
    val settings: StateFlow<ReaderSettings> =
        kotlinx.coroutines.flow.combine(settingsRepository.settings, book) { global, current ->
            current?.readerSettings ?: global
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state = _state.asStateFlow()

    /**
     * Pages for one pagination key, together with the [settings] they were
     * measured with. While a settings change recomputes pages in the
     * background, the stale pages keep rendering with THESE settings — the
     * text changes exactly once, when the new layout lands.
     *
     * [partial] marks the quick current-chapter pass: it appears within
     * ~100 ms of a settings change so the reader restyles instantly; the
     * full-book result replaces it with identical in-chapter page splits.
     */
    class PaginationHolder(
        val key: String,
        val pages: List<BookPage>,
        val settings: ReaderSettings,
        val partial: Boolean = false,
    )

    private val _pagination = MutableStateFlow<PaginationHolder?>(null)
    val pagination = _pagination.asStateFlow()
    private var paginationJob: Job? = null
    private var paginationKeyInFlight: String? = null

    /** Last known reading position (flat item index), shared by both modes. */
    var currentFlatIndex: Int = 0
        private set

    /**
     * Character inside [currentFlatIndex]'s text the current page starts at
     * (paged mode). Keeps the same text on screen across re-pagination.
     */
    var currentCharOffset: Int = 0
        private set

    /**
     * Recomputes pages in the background unless [key] is already available.
     * [quick] (optional) paginates just the chapter being read; its result
     * shows almost immediately while [full] finishes behind it.
     */
    fun ensurePagination(
        key: String,
        settings: ReaderSettings,
        items: List<ReaderItem>,
        quick: (suspend () -> List<BookPage>)? = null,
        full: suspend () -> List<BookPage>,
    ) {
        if (paginationKeyInFlight == key) return
        // Anything else in flight computes an outdated key — cancel it even
        // when returning early, or its stale result overwrites the pages the
        // UI is asking for and the pager waits for them forever.
        paginationJob?.cancel()
        paginationKeyInFlight = null
        if (_pagination.value?.key == key && _pagination.value?.partial == false) return
        paginationKeyInFlight = key
        paginationJob = viewModelScope.launch(Dispatchers.Default) {
            // Reopening the book with unchanged layout: pages come straight
            // from disk, no measurement pass at all.
            val cacheFile = PaginationCache.fileFor(paginationCacheDir, bookId)
            val cached = PaginationCache.load(cacheFile, key, items)
            if (!cached.isNullOrEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    _pagination.value = PaginationHolder(key, cached, settings)
                    paginationKeyInFlight = null
                }
                return@launch
            }
            if (quick != null && _pagination.value?.key != key) {
                val quickPages = quick()
                withContext(Dispatchers.Main.immediate) {
                    _pagination.value = PaginationHolder(key, quickPages, settings, partial = true)
                }
            }
            val pages = full()
            // Publish on Main: a job cancelled mid-compute never gets here,
            // and the bookkeeping above stays single-threaded.
            withContext(Dispatchers.Main.immediate) {
                _pagination.value = PaginationHolder(key, pages, settings)
                paginationKeyInFlight = null
            }
            PaginationCache.save(cacheFile, key, items, pages)
        }
    }

    // -------------------------------------------------------------- search

    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults = _searchResults.asStateFlow()
    private var searchJob: Job? = null

    /** Kicks off a background full-book scan; newer queries cancel older. */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        val ready = _state.value as? ReaderState.Ready
        if (ready == null || query.length < 2) {
            _searchResults.value = null
            return
        }
        if (_searchResults.value?.query == query) return
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val results = searchBook(ready.items, query)
            withContext(Dispatchers.Main.immediate) { _searchResults.value = results }
        }
    }

    private var loadedBook: Book? = null
    private var loadedContent: BookContent? = null

    fun load() {
        viewModelScope.launch {
            _state.value = ReaderState.Loading
            val book = repository.bookById(bookId)
            if (book == null) {
                _state.value = ReaderState.Error
                return@launch
            }
            currentFlatIndex = book.progress.elementIndex
            runCatching { repository.loadContent(book) }
                .onSuccess { content ->
                    loadedBook = book
                    loadedContent = content
                    rebuildReady()
                    // Only now. It is fire-and-forget on a scope that outlives
                    // this screen, but starting it first put a full index
                    // rewrite — fsync, .bak copy, widget rebuild — onto the IO
                    // pool that the book itself was about to be read from, and
                    // the open simply queued behind it.
                    repository.noteOpened(bookId) { settingsRepository.settings.first() }
                }
                .onFailure { _state.value = ReaderState.Error }
        }
    }

    /**
     * Builds the Ready state, applying footnote display settings.
     *
     * The bookkeeping stays on Main so this VM's plain `var`s keep their
     * single-threaded invariant; the walk over every element of the book, the
     * font stats and `publisherStyleOf`'s second pass go to Default. It is only
     * tens of milliseconds, but they used to land on the very frame the reader
     * was trying to draw.
     */
    private suspend fun rebuildReady() {
        val book = loadedBook ?: return
        val content = loadedContent ?: return
        val hideFootnotes = settings.value.hideFootnotes
        withContext(Dispatchers.Main.immediate) {
            // Item texts are about to change — cached match offsets go stale.
            searchJob?.cancel()
            _searchResults.value = null
        }
        val next = withContext(Dispatchers.Default) { buildReady(book, content, hideFootnotes) }
        withContext(Dispatchers.Main.immediate) { _state.value = next }
    }

    private fun buildReady(
        book: Book,
        content: BookContent,
        hideFootnotes: Boolean,
    ): ReaderState {
        val items = mutableListOf<ReaderItem>()

        // Title page: the cover opens the book before the text.
        repository.coverFileFor(book)?.let { cover: File ->
            items += ReaderItem(0, ContentElement.Image(cover.absolutePath))
        }

        val chapterStarts = mutableListOf<Int>()
        val chapterTitles = mutableListOf<String?>()
        val chapterDepths = mutableListOf<Int>()
        content.chapters.forEachIndexed { index, chapter ->
            chapterStarts += items.size
            chapterTitles += chapter.title
            chapterDepths += chapter.depth
            chapter.elements.forEach { element ->
                val adjusted = if (element is ContentElement.Paragraph) {
                    element.copy(
                        text = if (hideFootnotes) {
                            element.text.withoutFootnotes()
                        } else {
                            element.text.withFootnoteRefStyle()
                        },
                    )
                } else {
                    element
                }
                items += ReaderItem(index, adjusted)
            }
        }

        if (items.isEmpty()) {
            return ReaderState.Error
        } else {
            return ReaderState.Ready(
                book = book,
                items = items,
                chapterStarts = chapterStarts,
                chapterTitles = chapterTitles,
                chapterDepths = chapterDepths,
                notes = if (hideFootnotes) emptyMap() else content.notes,
                // (chapter, element) → flat index the readers actually seek to.
                linkTargets = content.linkTargets.mapNotNull { (key, target) ->
                    val (chapter, element) = target
                    chapterStarts.getOrNull(chapter)?.let { start ->
                        key to (start + element).coerceIn(0, items.lastIndex.coerceAtLeast(0))
                    }
                }.toMap(),
                bookFonts = loadBookFonts(content),
                language = content.language,
                publisherStyle = com.example.frogreader.data.model.publisherStyleOf(content),
            )
        }
    }

    /** Groups the book's extracted font faces into loadable font families. */
    private fun loadBookFonts(
        content: BookContent,
    ): Map<String, androidx.compose.ui.text.font.FontFamily> =
        content.fonts
            .filter { File(it.path).exists() }
            .groupBy { it.family }
            .mapNotNull { (family, faces) ->
                runCatching {
                    family to androidx.compose.ui.text.font.FontFamily(
                        faces.map { face ->
                            androidx.compose.ui.text.font.Font(
                                file = File(face.path),
                                weight = if (face.bold) {
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                } else {
                                    androidx.compose.ui.text.font.FontWeight.Normal
                                },
                                style = if (face.italic) {
                                    androidx.compose.ui.text.font.FontStyle.Italic
                                } else {
                                    androidx.compose.ui.text.font.FontStyle.Normal
                                },
                            )
                        },
                    )
                }.getOrNull()
            }
            .toMap()

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            val updated = transform(settings.value)
            // The book keeps its own settings from now on; the global copy
            // becomes the "last used" default that NEW books start from.
            repository.saveReaderSettings(bookId, updated)
            settingsRepository.update { updated }
        }
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.updateApp(transform) }
    }

    fun saveProgress(firstVisibleIndex: Int, scrollOffset: Int, charOffset: Int = 0) {
        val ready = _state.value as? ReaderState.Ready ?: return
        currentFlatIndex = firstVisibleIndex
        currentCharOffset = charOffset
        val fraction = if (ready.items.size <= 1) {
            0f
        } else {
            (firstVisibleIndex.toFloat() / (ready.items.size - 1)).coerceIn(0f, 1f)
        }
        val chapter = ready.chapterAt(firstVisibleIndex)
        val pageCounts = pageCountsFor(ready, chapter, firstVisibleIndex)
        val progress = ReadingProgress(
            chapterIndex = chapter,
            elementIndex = firstVisibleIndex,
            scrollOffset = scrollOffset,
            fraction = fraction,
            pagesLeftInChapter = pageCounts.first,
            totalPages = pageCounts.second,
        )
        viewModelScope.launch { repository.saveProgress(bookId, progress) }
    }

    /**
     * (pages left in [chapter], pages in the book) for the library's hero card,
     * or (-1, 0) when there is nothing to count — scroll mode never paginates,
     * and a partial pass only covers the chapter being read.
     *
     * Called on every scroll save, so both lookups are binary searches: `pages`
     * is sorted by [BookPage.firstItemIndex] and can run to thousands of entries.
     */
    private fun pageCountsFor(
        ready: ReaderState.Ready,
        chapter: Int,
        firstVisibleIndex: Int,
    ): Pair<Int, Int> {
        val holder = _pagination.value ?: return -1 to 0
        if (holder.partial) return -1 to 0
        val pages = holder.pages
        if (pages.isEmpty()) return -1 to 0

        val currentPage = lastPageStartingAtOrBefore(pages, firstVisibleIndex)
        val chapterEnd = ready.chapterStarts.getOrNull(chapter + 1) ?: ready.items.size
        // The chapter's last page is the last one that starts before the next
        // chapter does; `chapterEnd - 1` turns "before" into "at or before".
        val lastPageOfChapter = lastPageStartingAtOrBefore(pages, chapterEnd - 1)

        return (lastPageOfChapter - currentPage).coerceAtLeast(0) to pages.size
    }

    /** Index of the last page whose first item is at or before [flatIndex]. */
    private fun lastPageStartingAtOrBefore(pages: List<BookPage>, flatIndex: Int): Int {
        var low = 0
        var high = pages.size - 1
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pages[mid].firstItemIndex <= flatIndex) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    // ------------------------------------------------------------- stats

    fun markFinished() {
        viewModelScope.launch { repository.markFinished(bookId) }
    }

    /** Adds a finished reading session to the daily and per-book counters. */
    fun addReadingTime(seconds: Long) {
        if (seconds < 1 || seconds > 24 * 60 * 60) return
        viewModelScope.launch {
            statsRepository.addSeconds(java.time.LocalDate.now(), seconds)
            repository.addReadingSeconds(bookId, seconds)
        }
    }

    // ------------------------------------------------------------- bookmarks

    /** How close (in elements) a bookmark must be to count as "current". */
    val bookmarkWindow = 4

    fun bookmarkNear(flatIndex: Int): Bookmark? =
        book.value?.bookmarks?.firstOrNull {
            it.flatIndex in flatIndex..(flatIndex + bookmarkWindow)
        }

    /** Adds a bookmark at [flatIndex], or removes the one already there. */
    fun toggleBookmarkAt(flatIndex: Int) {
        val ready = _state.value as? ReaderState.Ready ?: return
        val existing = bookmarkNear(flatIndex)
        viewModelScope.launch {
            if (existing != null) {
                repository.removeBookmark(bookId, existing.id)
            } else {
                repository.toggleBookmark(
                    bookId,
                    Bookmark(
                        id = UUID.randomUUID().toString(),
                        flatIndex = flatIndex,
                        chapterIndex = ready.chapterAt(flatIndex),
                        preview = previewAt(ready, flatIndex),
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun removeBookmark(bookmarkId: String) {
        viewModelScope.launch { repository.removeBookmark(bookId, bookmarkId) }
    }

    fun addQuote(text: String, chapterIndex: Int) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addQuote(
                bookId,
                Quote(
                    id = UUID.randomUUID().toString(),
                    text = trimmed,
                    chapterIndex = chapterIndex,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun removeQuote(quoteId: String) {
        viewModelScope.launch { repository.removeQuote(bookId, quoteId) }
    }

    private fun previewAt(ready: ReaderState.Ready, flatIndex: Int): String {
        for (i in flatIndex until ready.items.size) {
            ready.items[i].element.previewText()?.let { return it }
        }
        return ""
    }

    /**
     * LAST in the class body, and it has to stay last.
     *
     * `load()` starts on Dispatchers.Main.immediate, so whatever runs before
     * its first real suspension runs INSIDE this constructor — and Kotlin
     * initialises properties in declaration order. Sitting above
     * `_searchResults`, `loadedBook` and `loadedContent`, it used to get away
     * with it only because the open always suspended early on a library-index
     * write. The moment a cached book made `loadContent` return without
     * suspending at all, `rebuildReady` reached a `_searchResults` that was
     * still null and every re-open crashed.
     */
    init {
        load()
        // Re-render the book when the footnote visibility setting changes.
        viewModelScope.launch {
            settings
                .map { it.hideFootnotes }
                .distinctUntilChanged()
                .collect { if (loadedContent != null) rebuildReady() }
        }
    }

    companion object {
        fun factory(bookId: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                ReaderViewModel(
                    repository = app.bookRepository,
                    settingsRepository = app.settingsRepository,
                    statsRepository = app.statsRepository,
                    bookId = bookId,
                    paginationCacheDir = File(app.filesDir, "pagination"),
                )
            }
        }
    }
}
