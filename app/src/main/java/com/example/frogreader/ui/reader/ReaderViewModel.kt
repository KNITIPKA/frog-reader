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
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.data.restoreBookFontSiblings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.previewText
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.withFootnoteRefStyle
import com.example.frogreader.data.model.withoutFootnotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.frogreader.ui.reader.selection.BookSelection
import java.io.File
import java.util.UUID

/** One element of the flattened book, tagged with its chapter. */
class ReaderItem(
    val chapterIndex: Int,
    val element: ContentElement,
    /** Nested publisher boxes, ordered outermost to innermost. */
    val publisherBoxes: List<ReaderPublisherBox> = emptyList(),
    /** Figure-like float rendered beside this paragraph. */
    val publisherFloat: ReaderPublisherFloat? = null,
    /** Source leaf retained for anchors/search but removed from normal flow. */
    val suppressedByPublisherFloat: Boolean = false,
    val publisherClearBefore: com.example.frogreader.data.model.PublisherClear =
        com.example.frogreader.data.model.PublisherClear.NONE,
)

/** One transient document that is intentionally outside normal reading order. */
class ReaderLinkedDocument(
    val id: String,
    val title: String?,
    val items: List<ReaderItem>,
)

/** Destination used by the reader's Contents UI. */
sealed interface ReaderNavigationTarget {
    data class ReadingOrder(
        val flatItemIndex: Int,
        val chapterIndex: Int,
    ) : ReaderNavigationTarget

    data class Linked(val target: LinkedDocumentTarget) : ReaderNavigationTarget
}

class ReaderNavigationEntry(
    val title: String?,
    val depth: Int,
    val target: ReaderNavigationTarget,
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
        /** Footnote key → rich transient document for tappable references. */
        val notes: Map<String, NoteDocument>,
        /** Link key → flat item index, for Contents entries and references. */
        val linkTargets: Map<String, Int> = emptyMap(),
        /** EPUB `linear="no"` documents, rendered only after an explicit link. */
        val linkedDocuments: Map<String, ReaderLinkedDocument> = emptyMap(),
        /** Link key → location inside [linkedDocuments]. */
        val linkedDocumentTargets: Map<String, LinkedDocumentTarget> = emptyMap(),
        /** Typed author-defined TOC; may point into either content surface. */
        val navigation: List<ReaderNavigationEntry> = emptyList(),
        /** What the book's own formatting asks for (publisher's mode). */
        val publisherStyle: com.example.frogreader.data.model.PublisherStyle? = null,
        /** Embedded font family name → loaded font (publisher's formatting). */
        val bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
        /** Stable family/style + file-content signature for pagination caches. */
        val embeddedFontSignature: String = embeddedFontSignature(emptyList()),
        /** The book's language tag ("ru", "uk", …) — drives hyphenation. */
        val language: String? = null,
        /** Resolved logical page order; never DEFAULT inside the active reader. */
        val pageProgression: PageProgression = PageProgression.LTR,
        /** False while an EPUB prefix is readable and its remaining documents are parsing. */
        val contentComplete: Boolean = true,
    ) : ReaderState {
        fun chapterAt(flatIndex: Int): Int =
            ReaderNavigationPolicy.chapterIndexAt(chapterStarts, flatIndex)
    }
}

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: com.example.frogreader.data.StatsRepository,
    private val bookId: String,
    private val paginationCacheDir: File,
) : ViewModel() {

    private val readerResources = repository.retainReaderResources(bookId)

    override fun onCleared() {
        readerResources?.close()
        repository.trimReaderCaches()
        super.onCleared()
    }

    /** Live book from the library index (declared before [settings] uses it). */
    val book: StateFlow<Book?> = repository.books
        .map { books -> books.firstOrNull { it.id == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.bookById(bookId))

    val readingLogEntry: StateFlow<ReadingLogEntry?> = kotlinx.coroutines.flow.combine(
        book, repository.readingLog,
    ) { current, entries ->
        if (current?.suppressReadingLog == true) null
        else entries.firstOrNull { it.id == current?.activeReadingLogId && it.linkedBookId == bookId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        if (repository.bookById(bookId)?.suppressReadingLog == true) null else repository.readingLogEntryFor(bookId))

    private val settingsWriter = ReaderSettingsWriter(viewModelScope) { updated ->
        repository.saveReaderSettings(bookId, updated)
    }

    /** Each book starts with code defaults; edits belong only to that book. */
    val settings: StateFlow<ReaderSettings> =
        kotlinx.coroutines.flow.combine(book, settingsWriter.pending) { current, pending ->
            (pending ?: current?.readerSettings ?: ReaderSettings()).withAutomaticTypography()
        }.stateIn(viewModelScope, SharingStarted.Eagerly,
            (book.value?.readerSettings ?: ReaderSettings()).withAutomaticTypography())

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
     * [partial] marks the quick nearby-chapters pass, available on first
     * open and after settings changes. The full-book result replaces it
     * with identical in-chapter page splits.
     */
    class PaginationHolder(
        val key: String,
        val pages: List<BookPage>,
        val settings: ReaderSettings,
        val partial: Boolean = false,
        internal val itemRange: IntRange? = null,
    )

    private val _pagination = MutableStateFlow<PaginationHolder?>(null)
    val pagination = _pagination.asStateFlow()
    private var paginationJob: Job? = null
    private var paginationKeyInFlight: String? = null
    private val presentedPagination = MutableStateFlow<PaginationHolder?>(null)
    private var openingTrace = ReaderLoadTrace()

    internal fun recordOpeningSurfaceReady() = openingTrace.surfaceReady()

    internal fun onPaginationPresented(holder: PaginationHolder) {
        if (_pagination.value === holder) presentedPagination.value = holder
    }

    /** Last known reading position (flat item index), shared by both modes. */
    var currentFlatIndex: Int = 0
        private set

    /**
     * Character inside [currentFlatIndex]'s text the current page starts at
     * (paged mode). Keeps the same text on screen across re-pagination.
     */
    var currentCharOffset: Int = 0
        private set

    /** Pixel offset of the first visible item in scroll mode (0 in pages). */
    var currentScrollOffset: Int = 0
        private set

    private val navigationSession = ReaderNavigationSession(
        viewModelScope, android.os.SystemClock::elapsedRealtime,
    )
    private var returnTimeoutMillis = ReaderNavigationSession.DEFAULT_TIMEOUT_MILLIS
    private var expirePageReturns = AppSettings().autoHideReturnButton
    /** The next return destination, also used to label the return control. */
    val navigationReturnLocation = navigationSession.returnLocation

    fun rememberNavigationOrigin(location: ReaderReturnLocation, expires: Boolean = false) {
        navigationSession.remember(location, expires)
    }

    fun takeNavigationOrigin(): ReaderReturnLocation? = navigationSession.take()

    fun clearNavigationHistory() = navigationSession.clear()

    fun setReturnTimeoutMillis(timeoutMillis: Long) {
        returnTimeoutMillis = timeoutMillis
        navigationSession.configureExpiry(returnTimeoutMillis.takeIf { expirePageReturns })
    }

    /** Updates the exact transient position without scheduling a disk write. */
    fun observeVisiblePosition(flatIndex: Int, scrollOffset: Int, charOffset: Int = 0) {
        if (_restartingReading.value) return
        currentFlatIndex = flatIndex
        currentScrollOffset = scrollOffset
        currentCharOffset = charOffset
    }

    /**
     * Recomputes pages in the background unless [key] is already available.
     * [quick] (optional) paginates nearby complete chapters; its result can
     * be read while [full] finishes behind it. [quickRange] retains their
     * source coordinates so cross-chapter seeks cannot clamp to a preview.
     */
    fun ensurePagination(
        key: String,
        settings: ReaderSettings,
        items: List<ReaderItem>,
        quick: (suspend () -> List<BookPage>)? = null,
        quickRange: IntRange? = null,
        awaitPreviewPresentation: Boolean = false,
        contentComplete: Boolean = true,
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
            val paginationResources = repository.retainReaderResources(bookId)
            try {
                val cacheFile = PaginationCache.fileFor(paginationCacheDir, bookId)
                // A full cache may contain tens of thousands of pages. Even a
                // hit validates every source item, so show nearby pages before
                // decoding/validating it and materialising the rest of Ready.
                if (quick != null && _pagination.value?.key != key) {
                    val started = System.nanoTime()
                    val quickPages = closedPrefixPages(quick(), items, contentComplete)
                    openingTrace.stage("preview", started, quickPages.size)
                    if (quickPages.isNotEmpty()) {
                        val preview = PaginationHolder(
                            key, quickPages, settings, partial = true,
                            itemRange = quickRange,
                        )
                        withContext(Dispatchers.Main.immediate) { _pagination.value = preview }
                        // Cache validation can allocate the rest of a huge book.
                        // Let the preview reach a frame before starting that work.
                        if (awaitPreviewPresentation) presentedPagination.first { it === preview }
                        // Keep extending the readable prefix after its first frame.
                        // Three quick pages are a head start, not a navigation wall.
                    }
                }
                val cacheStarted = System.nanoTime()
                val cached = if (contentComplete) PaginationCache.load(cacheFile, key, items) else null
                openingTrace.stage("page_cache", cacheStarted, cached?.size ?: 0)
                if (!cached.isNullOrEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        _pagination.value = PaginationHolder(key, cached, settings)
                        paginationKeyInFlight = null
                    }
                    return@launch
                }
                val fullStarted = System.nanoTime()
                val pages = closedPrefixPages(full(), items, contentComplete)
                openingTrace.stage("full_pagination", fullStarted, pages.size)
                // Publish on Main: a job cancelled mid-compute never gets here,
                // and the bookkeeping above stays single-threaded.
                withContext(Dispatchers.Main.immediate) {
                    _pagination.value = PaginationHolder(key, pages, settings,
                        partial = !contentComplete, itemRange = items.indices.takeUnless { contentComplete })
                    paginationKeyInFlight = null
                }
                if (contentComplete) {
                    PaginationCache.save(cacheFile, key, items, pages)
                }
            } finally {
                // A cancelled screen can leave a native measure/atomic write
                // finishing. Its resources stay pinned until that work exits.
                paginationResources?.close()
                repository.trimReaderCaches()
            }
        }
    }

    private fun closedPrefixPages(
        pages: List<BookPage>, items: List<ReaderItem>, complete: Boolean,
    ): List<BookPage> = if (!complete && pages.size > 1 &&
        pages.last().parts.lastOrNull()?.itemIndex == items.lastIndex) {
        // Appending a spine fragment can add text to the final open page.
        // Keep it off the stable prefix so a queued turn cannot skip that text.
        pages.dropLast(1)
    } else pages

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
    private var loadedContentComplete = true
    private var readyBuildGeneration = 0L
    private val _offerReread = MutableStateFlow(false)
    val offerReread = _offerReread.asStateFlow()
    private val _readingGeneration = MutableStateFlow(0)
    val readingGeneration = _readingGeneration.asStateFlow()
    private val _completionVisible = MutableStateFlow(false)
    val completionVisible = _completionVisible.asStateFlow()
    private val _lastPageVisible = MutableStateFlow(false)
    val lastPageVisible = _lastPageVisible.asStateFlow()
    private val _rereadError = MutableStateFlow(false)
    val rereadError = _rereadError.asStateFlow()
    private val _restartingReading = MutableStateFlow(false)
    val restartingReading = _restartingReading.asStateFlow()
    private val _resumingReading = MutableStateFlow(false)
    val resumingReading = _resumingReading.asStateFlow()
    private var currentReadingLogId: String? = null
    private var viewingPreviousReading = false
    private var finishRequested = false
    private val _finishSaved = MutableStateFlow(false)
    val finishSaved = _finishSaved.asStateFlow()
    private val _completionReview = MutableStateFlow(ReaderCompletionReviewState())
    internal val completionReview = _completionReview.asStateFlow()
    private var committedReadingChoice: Boolean? = null
    private val readingSession = ReaderReadingSession(android.os.SystemClock::elapsedRealtime)
    private var readingWrite: Job? = null
    private val pendingReadingWrites = ReaderReadingWriteQueue()
    private val _readingSaveError = MutableStateFlow(false)
    val readingSaveError = _readingSaveError.asStateFlow()

    fun load() {
        viewModelScope.launch {
            openingTrace = ReaderLoadTrace()
            _state.value = ReaderState.Loading
            val book = repository.bookById(bookId)
            if (book == null) {
                _state.value = ReaderState.Error
                return@launch
            }
            currentFlatIndex = book.progress.elementIndex
            currentScrollOffset = book.progress.scrollOffset
            currentReadingLogId = book.activeReadingLogId
            initializeCompletionReview(book)
            viewingPreviousReading = book.hasFinishedReading || book.status == ReadingStatus.ABANDONED
            _finishSaved.value = viewingPreviousReading
            _offerReread.value = viewingPreviousReading
            clearNavigationHistory()
            val parseStarted = System.nanoTime()
            var firstContent = true
            suspend fun acceptContent(content: BookContent, complete: Boolean) {
                    val first = firstContent
                    firstContent = false
                    loadedBook = book
                    loadedContent = content
                    loadedContentComplete = complete
                    // Older EPUB adoptions copied only the selected subset.
                    // Restore its genuine siblings before first pagination;
                    // never replace a preference edited while IO was running.
                    val fontSettings = settingsWriter.pending.value ?: settings.value
                    val repairedFontSettings = withContext(Dispatchers.IO) {
                        fontSettings.restoreBookFontSiblings(repository.fontLibraryDirectory, content.fonts)
                    }
                    if (repairedFontSettings != fontSettings) {
                        settingsWriter.update(settings.value) { current ->
                            if (current == fontSettings) repairedFontSettings else current
                        }
                    }
                    // Guarantee the start stamp precedes every time and finish write,
                    // while keeping its fsync off the reader's first rendered frame.
                    if (first && !viewingPreviousReading) enqueueReadingWrite {
                        repository.markStarted(bookId)
                        currentReadingLogId = repository.bookById(bookId)?.activeReadingLogId
                    }
                    val readyStarted = System.nanoTime()
                    rebuildReady(resetNavigation = first)
                    openingTrace.stage("prepare_reader", readyStarted, content.totalElements)
                    // Only now. It is fire-and-forget on a scope that outlives
                    // this screen, but starting it first put a full index
                    // rewrite — fsync, .bak copy, widget rebuild — onto the IO
                    // pool that the book itself was about to be read from, and
                    // the open simply queued behind it.
                    if (first) repository.noteOpened(bookId)
            }
            runCatching {
                repository.loadContentProgressively(book) { preview ->
                    openingTrace.stage("parse_preview", parseStarted, preview.totalElements)
                    acceptContent(preview, complete = false)
                }
            }
                .onSuccess { content ->
                    openingTrace.stage("parse", parseStarted, content.totalElements)
                    acceptContent(content, complete = true)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.value = ReaderState.Error
                }
        }
    }

    /**
     * Builds the Ready state, applying footnote display settings.
     *
     * The bookkeeping stays on Main so this VM's plain `var`s keep their
     * single-threaded invariant. Font statistics, publisher-style summaries
     * and preparation of the current chapter go to Default. Distant chapter
     * items remain lazy until the reader, search or pagination needs them.
     */
    private suspend fun rebuildReady(resetNavigation: Boolean = true) {
        val book = loadedBook ?: return
        val content = loadedContent ?: return
        val contentComplete = loadedContentComplete
        val readerSettings = settings.value
        val initialFlatIndex = currentFlatIndex
        val generation = ++readyBuildGeneration
        withContext(Dispatchers.Main.immediate) {
            // Item texts are about to change — cached match offsets go stale.
            searchJob?.cancel()
            _searchResults.value = null
            // Character anchors in the jump stack belong to the old item
            // stream (notably when hide-footnotes rewrites inline text).
            if (resetNavigation) clearNavigationHistory()
        }
        val next = withContext(Dispatchers.Default) {
            buildReady(book, content, readerSettings.hideFootnotes, initialFlatIndex, contentComplete)
        }
        withContext(Dispatchers.Main.immediate) {
            // A setting change can prepare an older prefix concurrently with
            // the complete publication. Never let that late result replace it.
            if (generation == readyBuildGeneration && content === loadedContent &&
                contentComplete == loadedContentComplete &&
                readerSettings.hideFootnotes == settings.value.hideFootnotes
            ) _state.value = next
        }
    }

    private fun buildReady(
        book: Book,
        content: BookContent,
        hideFootnotes: Boolean,
        initialFlatIndex: Int,
        contentComplete: Boolean,
    ): ReaderState {
        // A cover is chrome around the reading order, not a substitute for
        // one.  In particular an EPUB whose entire spine is linear="no" must
        // not become Ready with an empty chapterStarts list: ReaderScreen's
        // chapter/progress coordinate space would be undefined.
        if (!content.hasSequentialContent()) return ReaderState.Error

        // Title page: the cover opens the book before the text.
        val coverItem = repository.coverFileFor(book)?.let { cover: File ->
            ReaderItem(0, ContentElement.Image(cover.absolutePath))
        }

        val chapterTitles = content.chapters.map { it.title }
        val chapterDepths = content.chapters.map { it.depth }
        val bookFonts = loadBookFonts(content)
        fun adjustedText(text: androidx.compose.ui.text.AnnotatedString) =
            if (hideFootnotes) text.withoutFootnotes() else text.withFootnoteRefStyle()
        fun adjusted(element: ContentElement): ContentElement = when (element) {
            is ContentElement.Paragraph -> element.copy(text = adjustedText(element.text))
            is ContentElement.Heading -> element.copy(
                styledText = adjustedText(element.styledText),
            )
            is ContentElement.Table -> element.copy(
                rows = element.rows.map { row ->
                    com.example.frogreader.data.model.TableRow(
                        cells = row.cells.map { cell ->
                            com.example.frogreader.data.model.TableCell(
                                text = adjustedText(cell.text),
                                colSpan = cell.colSpan,
                                rowSpan = cell.rowSpan,
                                align = cell.align,
                                header = cell.header,
                                block = cell.block,
                                publisherBox = cell.publisherBox,
                                publisherPaddingSpecified = cell.publisherPaddingSpecified,
                                publisherBorderSpecified = cell.publisherBorderSpecified,
                                narrowStyle = cell.narrowStyle,
                            )
                        },
                        isHeader = row.isHeader,
                    )
                },
            )
            else -> element
        }
        val items = ReaderChapterItems(content.chapters, coverItem, ::adjusted)
        val chapterStarts = items.chapterStarts
        items.prepareAt(initialFlatIndex)

        val linkedDocuments = content.linkedDocuments.mapValues { (_, document) ->
            val adjustedElements = document.elements.map(::adjusted)
            val plan = planPublisherChapter(adjustedElements, document.publisherBoxes)
            ReaderLinkedDocument(
                id = document.id,
                title = document.title,
                items = adjustedElements.mapIndexed { elementIndex, element ->
                    val plannedFloat = plan.floatByTarget[elementIndex]
                    ReaderItem(
                        chapterIndex = 0,
                        element = element,
                        publisherBoxes = plan.boxesByElement[elementIndex],
                        publisherFloat = plannedFloat?.let { float ->
                            ReaderPublisherFloat(
                                side = float.side,
                                style = float.style,
                                contents = float.contents.map { content ->
                                    ReaderPublisherFloatContent(
                                        sourceItemIndex = content.sourceElementIndex,
                                        element = content.element,
                                        publisherBoxes = content.publisherBoxes,
                                    )
                                },
                            )
                        },
                        suppressedByPublisherFloat = elementIndex in plan.suppressedElements,
                        publisherClearBefore = plan.clearBefore[elementIndex],
                    )
                },
            )
        }
        val navigation = content.navigation.mapNotNull { entry ->
            val target = when (val source = entry.target) {
                is BookNavigationTarget.ReadingOrder -> {
                    val start = chapterStarts.getOrNull(source.chapterIndex)
                        ?: return@mapNotNull null
                    ReaderNavigationTarget.ReadingOrder(
                        flatItemIndex = (start + source.elementIndex)
                            .coerceIn(0, items.lastIndex.coerceAtLeast(0)),
                        chapterIndex = source.chapterIndex,
                    )
                }

                is BookNavigationTarget.Linked -> {
                    val document = linkedDocuments[source.documentId]
                        ?: return@mapNotNull null
                    if (source.elementIndex !in document.items.indices) return@mapNotNull null
                    ReaderNavigationTarget.Linked(
                        LinkedDocumentTarget(source.documentId, source.elementIndex),
                    )
                }
            }
            ReaderNavigationEntry(entry.title, entry.depth, target)
        }.ifEmpty {
            // FB2/MOBI and EPUBs without nav/NCX retain the historical flat
            // chapter list, now expressed through the same typed target.
            chapterTitles.mapIndexed { index, title ->
                ReaderNavigationEntry(
                    title = title,
                    depth = chapterDepths[index],
                    target = ReaderNavigationTarget.ReadingOrder(
                        flatItemIndex = chapterStarts[index],
                        chapterIndex = index,
                    ),
                )
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
                notes = if (hideFootnotes) {
                    emptyMap()
                } else {
                    LazyMappedValuesMap(content.notes) { _, note ->
                        NoteDocument(
                            elements = note.elements.map(::adjusted),
                            publisherBoxes = note.publisherBoxes,
                        )
                    }
                },
                // (chapter, element) → flat index the readers actually seek to.
                linkTargets = content.linkTargets.mapNotNull { (key, target) ->
                    val (chapter, element) = target
                    chapterStarts.getOrNull(chapter)?.let { start ->
                        key to (start + element).coerceIn(0, items.lastIndex.coerceAtLeast(0))
                    }
                }.toMap(),
                linkedDocuments = linkedDocuments,
                linkedDocumentTargets = content.linkedDocumentTargets.filterValues { target ->
                    val document = linkedDocuments[target.documentId]
                    document != null && target.elementIndex in document.items.indices
                },
                navigation = navigation,
                bookFonts = bookFonts,
                embeddedFontSignature = embeddedFontSignature(content.fonts),
                language = content.language,
                pageProgression = ReaderProgression.resolve(
                    content.pageProgression,
                    content.language,
                ),
                contentComplete = contentComplete,
                publisherStyle = com.example.frogreader.data.model.publisherStyleOf(content),
            )
        }
    }

    /** Groups the book's extracted font faces into loadable font families. */
    private fun loadBookFonts(
        content: BookContent,
    ): Map<String, androidx.compose.ui.text.font.FontFamily> {
        val availableFaces = content.fonts.filter { File(it.path).isFile }
        if (availableFaces.isEmpty()) return emptyMap()
        return availableFaces.groupBy { it.family }.mapNotNull { (family, faces) ->
            com.example.frogreader.ui.theme.publisherFontFamily(faces)?.let { family to it }
        }.toMap()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settingsWriter.update(settings.value) { transform(it).withAutomaticTypography() }
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.updateApp(transform) }
    }

    suspend fun setDefaultTextApp(action: ExternalTextAction, id: String) {
        settingsRepository.updateApp {
            when (action) {
                ExternalTextAction.TRANSLATE -> it.copy(defaultTranslator = id)
                ExternalTextAction.DEFINE -> it.copy(defaultDictionary = id)
                ExternalTextAction.SEARCH -> it.copy(defaultSearchApp = id)
            }
        }
    }

    fun saveProgress(firstVisibleIndex: Int, scrollOffset: Int, charOffset: Int = 0) {
        // The old reader can still emit a frame while a new reading is being saved.
        // Do not let that frame overwrite the reset position or the undecided one.
        if (_offerReread.value || _restartingReading.value) return
        val ready = _state.value as? ReaderState.Ready ?: return
        observeVisiblePosition(firstVisibleIndex, scrollOffset, charOffset)
        val fraction = if (!ready.contentComplete) {
            this.book.value?.progress?.fraction ?: ready.book.progress.fraction
        } else if (ready.items.size <= 1) {
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
        val readingId = currentReadingLogId
        if (readingId == null) {
            // A fresh book has no id until its queued first-open write finishes.
            enqueueReadingWrite { repository.saveProgressForReading(bookId, progress, currentReadingLogId) }
        } else {
            viewModelScope.launch { repository.saveProgressForReading(bookId, progress, readingId) }
        }
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

    fun coverFileFor(book: Book): File? = repository.coverFileFor(book)

    /** Looking at an old completion or a saved passage never changes that reading. */
    fun keepPreviousReading(): Boolean {
        if (_restartingReading.value || committedReadingChoice != null) return false
        _rereadError.value = false
        _offerReread.value = false
        return true
    }

    fun beginReadingAgain() = chooseReading(resume = false)

    fun resumeReading() = chooseReading(resume = true)

    private fun chooseReading(resume: Boolean) {
        if (_restartingReading.value || !_offerReread.value) return
        _restartingReading.value = true
        val continuePrevious = committedReadingChoice ?: resume
        _resumingReading.value = continuePrevious
        viewModelScope.launch {
            _rereadError.value = false
            runCatching {
                if (committedReadingChoice == null) {
                    readingWrite?.join()
                    pendingReadingWrites.drain()
                    _readingSaveError.value = false
                    if (continuePrevious) repository.resumeReading(bookId)
                    else repository.beginReadingAgain(bookId, addToLog = true)
                    // A later rendering failure retries only rendering, not this write.
                    committedReadingChoice = continuePrevious
                    loadedBook = repository.bookById(bookId)
                    currentReadingLogId = loadedBook?.activeReadingLogId
                    loadedBook?.let(::initializeCompletionReview)
                    if (!continuePrevious) {
                        currentFlatIndex = 0
                        currentCharOffset = 0
                        currentScrollOffset = 0
                    }
                    clearNavigationHistory()
                    readingSession.reset()
                    finishRequested = false
                    _finishSaved.value = false
                    viewingPreviousReading = false
                    _completionVisible.value = false
                    _lastPageVisible.value = false
                }
                rebuildReady()
                _readingGeneration.value += 1
                _offerReread.value = false
                committedReadingChoice = null
            }.onFailure { _rereadError.value = true }
            _restartingReading.value = false
        }
    }

    fun observeCompletionPosition(lastPage: Boolean, completion: Boolean) {
        if ((_state.value as? ReaderState.Ready)?.contentComplete == false) {
            _lastPageVisible.value = false
            _completionVisible.value = false
            return
        }
        _lastPageVisible.value = lastPage
        _completionVisible.value = completion
        if (completion) markFinished()
    }

    fun setReadingActive(active: Boolean) {
        readingSession.setRunning(active && !viewingPreviousReading && !_offerReread.value && !finishRequested)
        if (!active) flushReadingTime()
    }

    private fun flushReadingTime() {
        val seconds = readingSession.drainSeconds()
        if (seconds > 0) addReadingTime(seconds)
    }

    // Writes finish in order even when navigation immediately clears this ViewModel.
    private fun enqueueReadingWrite(write: suspend () -> Unit) {
        pendingReadingWrites.add(write)
        if (!_readingSaveError.value) drainReadingWrites()
    }

    private fun drainReadingWrites() {
        if (readingWrite?.isActive == true || pendingReadingWrites.isEmpty) return
        readingWrite = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { pendingReadingWrites.drain() }
                catch (_: Exception) { _readingSaveError.value = true }
            }
        }
    }

    fun retryReadingSave() {
        _readingSaveError.value = false
        drainReadingWrites()
    }

    fun dismissReadingSaveError() { _readingSaveError.value = false }

    private fun initializeCompletionReview(book: Book) {
        val draft = ReaderReviewDraft(book.effectiveRatingHalfStars, book.review.orEmpty())
        _completionReview.value = ReaderCompletionReviewState(draft)
    }

    internal fun setCompletionRating(ratingHalfStars: Int?) {
        require(ratingHalfStars == null || ratingHalfStars in 2..10)
        val state = _completionReview.value
        if (!state.saving) _completionReview.value = state.copy(
            draft = state.draft.copy(ratingHalfStars = ratingHalfStars), error = false,
        )
    }

    internal fun setCompletionReview(review: String) {
        val state = _completionReview.value
        if (!state.saving) _completionReview.value = state.copy(
            draft = state.draft.copy(review = review.take(20_000)), error = false,
        )
    }

    internal fun saveCompletionReview(onSaved: () -> Unit = {}) {
        val state = _completionReview.value
        if (state.saving || (!_finishSaved.value && !finishRequested)) return
        if (_finishSaved.value && !state.dirty) { onSaved(); return }
        val draft = state.draft.clean()
        _completionReview.value = state.copy(saving = true, error = false)
        // Finish this atomic user-data write even when Back immediately clears the reader.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    // Back can arrive before completion (or the initial reading id)
                    // has reached disk. Finish those writes before saving or exiting.
                    readingWrite?.join()
                    if (!_finishSaved.value) {
                        _completionReview.value = _completionReview.value.copy(saving = false)
                        return@withContext
                    }
                    if (!state.dirty) {
                        _completionReview.value = _completionReview.value.copy(saving = false)
                        onSaved()
                        return@withContext
                    }
                    val readingId = currentReadingLogId
                    repository.saveReviewForReading(bookId, readingId, draft.ratingHalfStars, draft.review)
                    _completionReview.value = _completionReview.value.copy(
                        draft = draft, saved = draft, saving = false, error = false,
                        savedGeneration = state.savedGeneration + 1,
                    )
                    onSaved()
                } catch (_: Exception) {
                    _completionReview.value = _completionReview.value.copy(saving = false, error = true)
                }
            }
        }
    }

    fun markFinished() {
        if (viewingPreviousReading || _offerReread.value || finishRequested) return
        finishRequested = true
        readingSession.setRunning(false)
        flushReadingTime()
        enqueueReadingWrite {
            val ready = _state.value as? ReaderState.Ready
            if (ready != null) {
                repository.saveProgressForReading(bookId, ReadingProgress(
                    chapterIndex = (ready.chapterStarts.size - 1).coerceAtLeast(0),
                    elementIndex = (ready.items.size - 1).coerceAtLeast(0),
                    fraction = 1f,
                    pagesLeftInChapter = 0,
                    totalPages = pagination.value?.takeIf { !it.partial }?.pages?.size ?: 0,
                ), currentReadingLogId)
            }
            repository.markFinishedForReading(bookId, currentReadingLogId)
            _finishSaved.value = true
        }
    }

    /** Adds a finished reading session to the daily and per-book counters. */
    fun addReadingTime(seconds: Long) {
        if (viewingPreviousReading || _offerReread.value || _restartingReading.value) return
        if (seconds < 1 || seconds > 24 * 60 * 60) return
        val date = java.time.LocalDate.now()
        // Capture the actual reading time before a delayed/retried disk write.
        val lastReadAt = System.currentTimeMillis().takeIf { readingSession.qualifiesAsReading }
        val write = ReaderReadingTimeWrite(
            saveBook = { repository.addReadingSecondsForReading(bookId, seconds, currentReadingLogId) },
            saveStats = { statsRepository.addSeconds(date, seconds, lastReadAtMillis = lastReadAt) },
        )
        enqueueReadingWrite(write::commit)
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

    fun addQuote(
        text: String,
        chapterIndex: Int,
        range: BookSelection,
        color: QuoteColor = QuoteColor.DEFAULT,
    ) {
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
                    startItem = range.start.itemIndex,
                    startChar = range.start.charOffset,
                    endItem = range.end.itemIndex,
                    endChar = range.end.charOffset,
                    color = color,
                ),
            )
        }
    }

    fun removeQuote(quoteId: String) {
        viewModelScope.launch { repository.removeQuote(bookId, quoteId) }
    }

    fun removeQuotes(quoteIds: Set<String>) {
        viewModelScope.launch { repository.removeQuotes(bookId, quoteIds) }
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
        viewModelScope.launch {
            settingsRepository.appSettings.map { it.autoHideReturnButton }.distinctUntilChanged()
                .collect { enabled ->
                    expirePageReturns = enabled
                    navigationSession.configureExpiry(returnTimeoutMillis.takeIf { enabled })
                }
        }
        // Font choices are resolved at measurement/drawing time; only footnote
        // visibility rewrites the shared source text.
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

/** True only when the publication has something in its sequential surface. */
internal fun BookContent.hasSequentialContent(): Boolean =
    chapters.any { it.elements.isNotEmpty() }
