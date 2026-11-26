package com.example.frogreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.data.restoreBookFontSiblings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.EpubReadingAnchor
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.previewText
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.parser.EpubOpeningStructure
import com.example.frogreader.data.parser.EpubSourceDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicInteger

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
    /** A visible loading slot, never source text or a pagination input. */
    val isLoading: Boolean = false,
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
    data class EpubSource(val destination: EpubSourceDestination) : ReaderNavigationTarget
}

class ReaderNavigationEntry(
    val title: String?,
    val depth: Int,
    val target: ReaderNavigationTarget,
)

sealed interface ReaderState {
    data object Loading : ReaderState
    data object Error : ReaderState

    class Ready internal constructor(
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
        /** Every sequential item is available for whole-book layout. */
        val contentComplete: Boolean = true,
        /** Exact global coordinates can be known while only one native window is decoded. */
        val globalCoordinatesReady: Boolean = contentComplete,
        /** Non-null for an addressable native book with a bounded in-memory window. */
        val windowRange: IntRange? = null,
        val auxiliaryContentReady: Boolean = true,
        internal val epubOpening: EpubOpeningStructure? = null,
        internal val epubDestination: EpubSourceDestination? = null,
        internal val sourceGeneration: Int = 0,
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
        closeIndexedSession()
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
            (pending ?: current?.readerSettings ?: ReaderSettings.defaultFor(current?.format)).withAutomaticTypography()
        }.stateIn(viewModelScope, SharingStarted.Eagerly,
            (book.value?.readerSettings ?: ReaderSettings.defaultFor(book.value?.format)).withAutomaticTypography())

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
    class PaginationHolder internal constructor(
        val key: String,
        val pages: List<BookPage>,
        val settings: ReaderSettings,
        val partial: Boolean = false,
        internal val itemRange: IntRange? = null,
        /** All locally available items were paginated (independent of global page totals). */
        val rangeComplete: Boolean = true,
        internal val exactStartPage: Int? = null,
        internal val exactIndex: ReaderPageIndex? = null,
        internal val extraItems: Map<Int, ReaderItem> = emptyMap(),
    ) {
        internal fun exactPageOffset(page: Int): Int? = exactStartPage?.let { start ->
            (page - start).takeIf { it in pages.indices }
        }
    }

    private val _pagination = MutableStateFlow<PaginationHolder?>(null)
    val pagination = _pagination.asStateFlow()
    private var paginationJob: Job? = null
    private var paginationKeyInFlight: String? = null
    private val presentedPagination = MutableStateFlow<PaginationHolder?>(null)
    private val _debugLoadHud = MutableStateFlow<DebugLoadHudState?>(null)
    val debugLoadHud: StateFlow<DebugLoadHudState?> = _debugLoadHud.asStateFlow()
    private var hudCollectorJob: Job? = null
    private var openingTrace = ReaderLoadTrace()

    private fun resetOpeningTrace(): ReaderLoadTrace {
        val trace = ReaderLoadTrace()
        openingTrace = trace
        hudCollectorJob?.cancel()
        _debugLoadHud.value = trace.hudState.value
        hudCollectorJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            trace.hudState.collect { state ->
                if (openingTrace === trace) _debugLoadHud.value = state
            }
        }
        return trace
    }

    fun clearDebugLoadHudHistory() {
        openingTrace.clearCompleted()
    }

    internal class ExactPageIndex(
        val specKey: String,
        val environment: String,
        val index: ReaderPageIndex,
        val geometryKey: ByteArray? = null,
        val rebuildGeometry: (() -> Unit)? = null,
    )
    private val _exactPageIndex = MutableStateFlow<ExactPageIndex?>(null)
    internal val exactPageIndex = _exactPageIndex.asStateFlow()
    private val _pageIndexCalculation = MutableStateFlow<Pair<String, String>?>(null)
    internal val pageIndexCalculation = _pageIndexCalculation.asStateFlow()
    private data class LayoutProbe(val request: Pair<String, String>, val hit: Boolean)
    private val layoutProbe = MutableStateFlow<LayoutProbe?>(null)
    private var pageIndexJob: Job? = null
    private var pageIndexRequest: Pair<String, String>? = null
    private var exactWindowTarget: Int? = null

    internal suspend fun hasCachedPageLayout(specKey: String, environment: String): Boolean {
        val request = specKey to environment
        return layoutProbe.first { it?.request == request }?.hit == true
    }

    internal fun ensureExactPageIndex(
        spec: PaginationSpec,
        environment: String,
        cover: ReaderItem?,
        measurers: List<androidx.compose.ui.text.TextMeasurer>,
        rebuildGeometry: Boolean = false,
    ) {
        val session = indexedSession ?: return
        val book = loadedBook ?: return
        val request = spec.key to environment
        if (!rebuildGeometry && pageIndexRequest == request) return
        cancelPageIndex()
        pageIndexRequest = request
        val itemCount = session.index.totalElements + if (cover == null) 0 else 1
        val coverPath = (cover?.element as? ContentElement.Image)?.path
        val trace = openingTrace
        val job = viewModelScope.launch(Dispatchers.Default) {
            val file = ReaderPageIndexCache.fileFor(paginationCacheDir, book.id)
            val continuous = !spec.settings.startChaptersOnNewPage
            val geometryFile = ReaderPageLayoutCache.fileFor(paginationCacheDir, book.id)
            val cacheStarted = trace.beginStage("page_cache", "Page cache (.rpi)")
            var cacheCount = 0
            var cacheDetail: String? = "canceled"
            var cacheLog = "exact_page_index_cancel"
            lateinit var key: ByteArray
            val cached = try {
                key = withContext(Dispatchers.IO) {
                    ReaderPageIndexCache.key(session.contentKey, spec, coverPath, environment)
                }
                val loaded = if (rebuildGeometry || session.startedFromEpubSource) null else withContext(Dispatchers.IO) {
                    if (continuous) ReaderPageLayoutCache.loadIndex(geometryFile, key, itemCount)
                    else ReaderPageIndexCache.load(file, key, itemCount)
                }
                if (loaded != null) {
                    cacheCount = loaded.size
                    cacheDetail = "${loaded.size} pg"
                    cacheLog = "exact_page_index_cache"
                } else {
                    cacheDetail = "miss"
                    cacheLog = "exact_page_index_miss"
                }
                loaded
            } finally {
                trace.endStage(
                    id = "page_cache",
                    since = cacheStarted,
                    count = cacheCount,
                    detail = cacheDetail,
                    logName = cacheLog,
                )
            }
            val index = if (cached != null) {
                cached
            } else {
                val waitStarted = trace.beginStage("page_index_wait", "Window wait")
                var waitDetail: String? = "canceled"
                var scanStarted = 0L
                val scanSession = try {
                    withContext(Dispatchers.Main.immediate) {
                        if (indexedSession === session && pageIndexRequest == request) {
                            _pageIndexCalculation.value = request
                            if (continuous) layoutProbe.value = LayoutProbe(request, false)
                        }
                    }
                    // Keep the first page and interactive window requests ahead of a full layout.
                    presentedPagination.first { holder ->
                        matchesPresentedWindowKey(holder?.key, spec.key, environment)
                    }
                    if (session.startedFromEpubSource) {
                        if (!awaitPreparedSource(session, book)) return@launch
                        key = withContext(Dispatchers.IO) {
                            // Newly extracted font faces cannot be measured with the old resolver.
                            if (embeddedFontSignature(session.index.fonts) != spec.embeddedFontSignature) return@withContext null
                            ReaderPageIndexCache.key(session.contentKey, spec, coverPath, environment)
                        } ?: return@launch
                    }
                    val opened = repository.openIndexedContent(book) ?: return@launch
                    waitDetail = null
                    val totalChapters = opened.index.chapters.size
                    val initialProgress = if (!continuous && totalChapters > 0) "0/$totalChapters ch" else null
                    scanStarted = trace.beginStage("exact_page_index", "Book pagination", progress = initialProgress)
                    opened
                } finally {
                    trace.endStage(
                        id = "page_index_wait",
                        since = waitStarted,
                        detail = waitDetail,
                    )
                }
                var scanCount = 0
                var scanDetail: String? = "canceled"
                var scanLog = "exact_page_index_abort"
                val measured = try {
                    if (scanSession.contentKey != session.contentKey) return@launch
                    val built = if (continuous) {
                        val onPageCount: (Int) -> Unit = { pages ->
                            trace.updateStage("exact_page_index", "$pages pg")
                        }
                        ReaderPageLayoutCache.build(geometryFile, key, itemCount) { emit ->
                            paginateIndexedBookPages(scanSession, cover, measurers.single(), spec, onPageCount = onPageCount, emit = emit)
                        }
                    } else {
                        buildCheckpointedPageIndex(scanSession, book.id, file, key, itemCount, cover, measurers, spec, trace)
                    }
                    if (built != null) {
                        scanCount = built.size
                        scanDetail = "${built.size} pg"
                        scanLog = "exact_page_index_build"
                    }
                    built
                } catch (_: java.io.IOException) {
                    scanDetail = "error"
                    return@launch
                } finally {
                    repository.closeIndexedContent(scanSession)
                    trace.endStage(
                        id = "exact_page_index",
                        since = scanStarted,
                        count = scanCount,
                        detail = scanDetail,
                        logName = scanLog,
                    )
                } ?: return@launch
                measured
            }
            withContext(Dispatchers.Main.immediate) {
                if (indexedSession === session && pageIndexRequest == request) {
                    val rebuild: (() -> Unit)? = if (continuous && !rebuildGeometry) {
                        { ensureExactPageIndex(spec, environment, cover, measurers, rebuildGeometry = true) }
                    } else null
                    _exactPageIndex.value = ExactPageIndex(spec.key, environment, index, key.takeIf { continuous }, rebuild)
                    if (continuous && cached != null) layoutProbe.value = LayoutProbe(request, true)
                }
            }
        }
        pageIndexJob = job
        job.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (pageIndexJob === job) _pageIndexCalculation.value = null
            }
        }
    }

    private suspend fun buildCheckpointedPageIndex(
        session: IndexedBookSession,
        bookId: String,
        file: File,
        key: ByteArray,
        itemCount: Int,
        cover: ReaderItem?,
        measurers: List<androidx.compose.ui.text.TextMeasurer>,
        spec: PaginationSpec,
        trace: ReaderLoadTrace,
    ): ReaderPageIndex? {
        val coverCount = if (cover == null) 0 else 1
        val starts = session.index.chapters.map { it.startElement + coverCount }
        val totalChapters = starts.size
        var ownedCheckpoint: ReaderPageCheckpoint? = null
        var committed = false
        try {
            val checkpointStarted = trace.beginStage("page_checkpoint", "Checkpoint")
            var checkpointCount = 0
            var checkpointDetail: String? = "canceled"
            val checkpoint = try {
                val opened = withContext(NonCancellable + Dispatchers.IO) {
                    ReaderPageCheckpoint.open(File(paginationCacheDir, bookId), key, starts, itemCount)
                        .also { ownedCheckpoint = it }
                }
                checkpointCount = opened.completed.size
                checkpointDetail = if (checkpointCount > 0) "$checkpointCount/$totalChapters ch" else "new"
                opened
            } finally {
                trace.endStage(
                    id = "page_checkpoint",
                    since = checkpointStarted,
                    count = checkpointCount,
                    detail = checkpointDetail,
                    logName = "page_checkpoint_resume",
                )
            }
            val resumed = checkpoint.completed
            var completedChapters = resumed.size
            if (totalChapters > 0) {
                trace.updateStage("exact_page_index", "$completedChapters/$totalChapters ch")
            }
            val anchors = paginateIndexedBookAnchors(
                session, cover, measurers, spec,
                preferredChapter = { ReaderNavigationPolicy.chapterIndexAt(starts, pageScanPosition.get()) },
                completed = resumed,
                onChapterReady = { chapterIndex, chapterAnchors ->
                    checkpoint.record(chapterIndex, chapterAnchors)
                    completedChapters++
                    if (totalChapters > 0) {
                        trace.updateStage("exact_page_index", "$completedChapters/$totalChapters ch")
                    }
                },
            )
            if (anchors.isEmpty()) return null
            committed = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                ReaderPageIndexCache.save(file, key, itemCount, anchors) { context.isActive }
            }
            return ReaderPageIndex(anchors)
        } finally {
            ownedCheckpoint?.let { checkpoint ->
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (committed) checkpoint.remove() else checkpoint.flush()
                    } finally {
                        checkpoint.close()
                    }
                }
            }
        }
    }

    internal fun cancelPageIndex() {
        if (exactWindowTarget != null) {
            paginationJob?.cancel()
            paginationKeyInFlight = null
            exactWindowTarget = null
        }
        pageIndexJob?.cancel()
        pageIndexJob = null
        pageIndexRequest = null
        layoutProbe.value = null
        _pageIndexCalculation.value = null
        _exactPageIndex.value = null
    }

    /** Load a bounded canonical page window by ordinal, including any cross-chapter source leaves. */
    internal fun requestExactPageWindow(layout: ExactPageIndex, key: String, settings: ReaderSettings, page: Int) {
        val geometryKey = layout.geometryKey ?: return
        val session = indexedSession ?: return
        val ready = _state.value as? ReaderState.Ready ?: return
        if (_exactPageIndex.value !== layout || page !in 0 until layout.index.size) return
        val existing = _pagination.value
        val existingStart = existing?.exactStartPage
        if (existing?.exactIndex === layout.index && existingStart != null &&
            page in existingStart until existingStart + existing.pages.size) {
            if (exactWindowTarget != null) {
                paginationJob?.cancel()
                paginationKeyInFlight = null
                exactWindowTarget = null
            }
            return
        }
        if (exactWindowTarget == page && paginationJob?.isActive == true) return
        paginationJob?.cancel()
        paginationKeyInFlight = null
        exactWindowTarget = page
        val first = (page - 5).coerceAtLeast(0)
        val end = (page + 6).coerceAtMost(layout.index.size)
        paginationJob = viewModelScope.launch(Dispatchers.Default) {
            val file = ReaderPageLayoutCache.fileFor(paginationCacheDir, ready.book.id)
            val batches = withContext(Dispatchers.IO) {
                ReaderPageLayoutCache.loadBatches(file, geometryKey, ready.items.size, layout.index, first, end)
            }
            val window = batches?.let { restoreIndexedPageWindow(it, ready, session, settings.hideFootnotes) }
            withContext(Dispatchers.Main.immediate) {
                if (_exactPageIndex.value !== layout || session !== indexedSession) return@withContext
                exactWindowTarget = null
                if (window == null) {
                    _exactPageIndex.value = null
                    // Rebuild atomically without unlinking a layout another reader may have published.
                    layout.rebuildGeometry?.invoke()
                    return@withContext
                }
                val startItem = window.pages.first().firstItemIndex
                val lastItem = window.pages.last().parts.lastOrNull()?.itemIndex ?: startItem
                _pagination.value = PaginationHolder(key, window.pages, settings, partial = true,
                    itemRange = startItem..maxOf(startItem, lastItem), exactStartPage = first,
                    exactIndex = layout.index, extraItems = window.extraItems)
            }
        }
    }

    internal fun itemAt(ready: ReaderState.Ready, item: Int): ReaderItem? {
        val local = ready.items.getOrNull(item)
        if (local?.isLoading == false) return local
        if (settings.value.readingMode != com.example.frogreader.data.ReadingMode.PAGES) return null
        val holder = _pagination.value ?: return null
        return holder.extraItems[item].takeIf { holder.exactIndex === _exactPageIndex.value?.index }
    }

    /** Keep the exact anchors already measured during an opening from source. */
    internal suspend fun cacheExactPageIndex(
        ready: ReaderState.Ready,
        spec: PaginationSpec,
        environment: String,
        pages: List<BookPage>,
    ) {
        if (ready.windowRange != null || !ready.contentComplete || pages.size !in 1..MAX_INDEXED_PAGE_COUNT) return
        repository.awaitContentCacheWrites(ready.book.id)
        val session = repository.openIndexedContent(ready.book) ?: return
        try {
            val coverCount = ready.chapterStarts.first()
            if (session.index.totalElements != ready.items.size - coverCount) return
            val coverPath = if (coverCount == 0) null else (ready.items[0].element as? ContentElement.Image)?.path
            if (!spec.settings.startChaptersOnNewPage) {
                val key = withContext(Dispatchers.IO) {
                    ReaderPageIndexCache.key(session.contentKey, spec, coverPath, environment)
                }
                val geometryFile = ReaderPageLayoutCache.fileFor(paginationCacheDir, ready.book.id)
                ReaderPageLayoutCache.build(geometryFile, key, ready.items.size) { emit -> pages.forEach(emit) }
                return
            }
            val anchors = IntArray(pages.size * 2)
            pages.forEachIndexed { pageIndex, page ->
                anchors[pageIndex * 2] = page.firstItemIndex
                anchors[pageIndex * 2 + 1] = page.firstCharOffset
            }
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val key = ReaderPageIndexCache.key(session.contentKey, spec, coverPath, environment)
                val file = ReaderPageIndexCache.fileFor(paginationCacheDir, ready.book.id)
                ReaderPageIndexCache.save(file, key, ready.items.size, anchors) { context.isActive }
            }
        } finally {
            repository.closeIndexedContent(session)
        }
    }

    internal fun recordOpeningSurfaceReady() {
        openingTrace.surfaceReady()
        val book = loadedBook ?: return
        if (epubOpening != null) {
            prepareEpubOpening(book)
            return
        }
        val session = indexedSession ?: return
        if (session.startedFromEpubSource) prepareEpubSource(session, book)
    }

    internal fun onPaginationPresented(holder: PaginationHolder) {
        if (_pagination.value === holder) presentedPagination.value = holder
    }

    /** Last known reading position (flat item index), shared by both modes. */
    private val pageScanPosition = AtomicInteger()
    var currentFlatIndex: Int = 0
        private set(value) {
            field = value
            pageScanPosition.set(value)
        }

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
    fun observeVisiblePosition(flatIndex: Int, scrollOffset: Int, charOffset: Int = 0, sourceGeneration: Int? = null) {
        if (_restartingReading.value || !hasCurrentSourceCoordinates(sourceGeneration)) return
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
        preparedRange: IntRange? = null,
        onWholeBookLayout: suspend (List<BookPage>) -> Unit = {},
        full: suspend () -> List<BookPage>,
    ) {
        if (paginationKeyInFlight == key) return
        // Anything else in flight computes an outdated key — cancel it even
        // when returning early, or its stale result overwrites the pages the
        // UI is asking for and the pager waits for them forever.
        paginationJob?.cancel()
        exactWindowTarget = null
        paginationKeyInFlight = null
        if (_pagination.value?.let { it.key == key && it.rangeComplete } == true) return
        paginationKeyInFlight = key
        val trace = openingTrace
        paginationJob = viewModelScope.launch(Dispatchers.Default) {
            val paginationResources = repository.retainReaderResources(bookId)
            try {
                val cacheFile = PaginationCache.fileFor(paginationCacheDir, bookId)
                // A full cache may contain tens of thousands of pages. Even a
                // hit validates every source item, so show nearby pages before
                // decoding/validating it and materialising the rest of Ready.
                if (quick != null && _pagination.value?.key != key) {
                    val started = trace.beginStage("preview", "Quick layout")
                    var quickCount = 0
                    var quickDetail: String? = "canceled"
                    val quickPages = try {
                        val computed = closedPrefixPages(quick(), items, contentComplete)
                        quickCount = computed.size
                        quickDetail = "${computed.size} pg"
                        computed
                    } finally {
                        trace.endStage("preview", started, quickCount, detail = quickDetail)
                    }
                    if (quickPages.isNotEmpty()) {
                        val preview = PaginationHolder(
                            key, quickPages, settings, partial = true,
                            itemRange = quickRange, rangeComplete = false,
                        )
                        withContext(Dispatchers.Main.immediate) { _pagination.value = preview }
                        // Cache validation can allocate the rest of a huge book.
                        // Let the preview reach a frame before starting that work.
                        if (awaitPreviewPresentation) presentedPagination.first { it === preview }
                        // Keep extending the readable prefix after its first frame.
                        // Three quick pages are a head start, not a navigation wall.
                    }
                }
                val cached = if (contentComplete) {
                    val cacheStarted = trace.beginStage("page_cache", "Page cache (.rpi)")
                    var cacheCount = 0
                    var cacheDetail: String? = "canceled"
                    try {
                        val loaded = PaginationCache.load(cacheFile, key, items)
                        cacheCount = loaded?.size ?: 0
                        cacheDetail = if (!loaded.isNullOrEmpty()) "$cacheCount pg" else "miss"
                        loaded
                    } finally {
                        trace.endStage("page_cache", cacheStarted, cacheCount, detail = cacheDetail)
                    }
                } else null
                if (!cached.isNullOrEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        _pagination.value = PaginationHolder(key, cached, settings)
                        paginationKeyInFlight = null
                    }
                    onWholeBookLayout(cached)
                    return@launch
                }
                val isWindowPass = preparedRange != null || !contentComplete
                val paginationStageId = if (isWindowPass) "window_pagination" else "full_pagination"
                val paginationStageLabel = if (isWindowPass) "Window layout" else "Book pagination"
                val fullStarted = trace.beginStage(paginationStageId, paginationStageLabel)
                var fullCount = 0
                var fullDetail: String? = "canceled"
                val pages = try {
                    val computed = if (preparedRange != null) full()
                        else closedPrefixPages(full(), items, contentComplete)
                    fullCount = computed.size
                    fullDetail = "$fullCount pg"
                    computed
                } finally {
                    trace.endStage(paginationStageId, fullStarted, fullCount, detail = fullDetail)
                }
                // Publish on Main: a job cancelled mid-compute never gets here,
                // and the bookkeeping above stays single-threaded.
                withContext(Dispatchers.Main.immediate) {
                    _pagination.value = PaginationHolder(key, pages, settings,
                        partial = !contentComplete,
                        itemRange = preparedRange ?: items.indices.takeUnless { contentComplete })
                    paginationKeyInFlight = null
                }
                if (contentComplete) {
                    onWholeBookLayout(pages)
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
        val session = indexedSession
        val hideFootnotes = settings.value.hideFootnotes
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val results = try {
                if (session != null) {
                    if (session.startedFromEpubSource && !awaitPreparedSource(session, ready.book)) {
                        return@launch
                    }
                    searchIndexedBook(session, query, ready.chapterStarts.first(), hideFootnotes)
                } else searchBook(ready.items, query)
            } catch (_: java.io.IOException) {
                withContext(Dispatchers.Main.immediate) {
                    if (session != null && session === indexedSession) {
                        repository.discardIndexedContent(ready.book)
                        if (session === indexedSession) load()
                    }
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) { _searchResults.value = results }
        }
    }

    private var indexedSession: IndexedBookSession? = null
    private var epubPreparation: Deferred<Boolean>? = null
    private var epubOpening: ReaderEpubOpening? = null
    private var epubOpeningPreparation: Job? = null
    private var epubOpeningSeek: Job? = null
    private var epubOpeningSeekRequest = 0
    private val _epubOpeningSeekPending = MutableStateFlow(false)
    internal val epubOpeningSeekPending = _epubOpeningSeekPending.asStateFlow()
    private var sourceGeneration = 0
    private var loadedWindow: com.example.frogreader.data.BookChapterWindow? = null
    private var windowCenterChapter: Int? = null
    private var requestedWindowChapter: Int? = null
    private var windowJob: Job? = null
    private var auxiliaryJob: Job? = null

    private fun savedEpubAnchor(book: Book): EpubReadingAnchor? =
        (book.progress.documentLocation as? DocumentLocation.Reflowable)?.epubSource
            ?.takeIf { it.sourceIdentity == book.epubSourceIdentity() }

    private fun sourceAnchor(ready: ReaderState.Ready, flatIndex: Int): EpubReadingAnchor? {
        val destination = ready.epubDestination ?: return null
        val identity = ready.book.epubSourceIdentity() ?: return null
        val documentElement = (flatIndex - ready.chapterStarts.first()).coerceAtLeast(0)
        return EpubReadingAnchor(identity, destination.spineOrdinal, destination.path, documentElement)
    }

    private fun exactEpubAnchor(ready: ReaderState.Ready, flatIndex: Int, chapter: Int): EpubReadingAnchor? {
        val identity = ready.book.epubSourceIdentity() ?: return null
        val local = flatIndex - ready.chapterStarts[chapter]
        if (local < 0) return null
        val ranges = indexedSession?.index?.epubSourceRanges ?: loadedContent?.epubSourceRanges ?: return null
        val range = ranges.lastOrNull {
            it.chapterIndex == chapter && local >= it.chapterStart && local - it.chapterStart < it.elementCount
        } ?: return null
        return EpubReadingAnchor(identity, range.spineOrdinal, range.path, range.documentStart + local - range.chapterStart)
    }

    private fun hasCurrentSourceCoordinates(generation: Int?): Boolean {
        val ready = _state.value as? ReaderState.Ready ?: return false
        return ready.sourceGeneration == sourceGeneration && (generation == null || generation == sourceGeneration)
    }

    internal fun epubOpeningFraction(flatIndex: Int): Float? {
        val ready = _state.value as? ReaderState.Ready ?: return null
        val structure = ready.epubOpening ?: return null
        val destination = ready.epubDestination ?: return null
        val cover = ready.chapterStarts.first()
        val local = (flatIndex - cover).coerceAtLeast(0).toFloat() / (ready.items.size - cover - 1).coerceAtLeast(1)
        return structure.fraction(destination, local.coerceIn(0f, 1f))
    }

    internal fun seekEpubOpening(fraction: Float) {
        if (!fraction.isFinite()) return
        val opening = epubOpening ?: return
        val (destination, local) = opening.structure.locate(fraction)
        loadEpubOpeningDestination(destination, local)
    }

    internal fun openEpubDestination(destination: EpubSourceDestination) =
        loadEpubOpeningDestination(destination, null)

    internal fun advanceEpubOpening(forward: Boolean) {
        val opening = epubOpening ?: return
        val documents = opening.structure.documents
        val ordinal = documents.indexOfFirst { it.spineOrdinal == opening.destination.spineOrdinal }
        if (ordinal < 0) return
        val next = documents.getOrNull(ordinal + if (forward) 1 else -1) ?: return
        loadEpubOpeningDestination(next, if (forward) 0f else 1f)
    }

    private fun loadEpubOpeningDestination(destination: EpubSourceDestination, localFraction: Float?) {
        val opening = epubOpening ?: return
        val book = loadedBook ?: return
        if (_offerReread.value || _restartingReading.value) return
        val request = ++epubOpeningSeekRequest
        epubOpeningSeek?.cancel()
        _epubOpeningSeekPending.value = true
        val trace = openingTrace
        epubOpeningSeek = viewModelScope.launch {
            val started = trace.beginStage("target_window", "Chapter window")
            var seekCount = 0
            var seekDetail: String? = "canceled"
            try {
                val document = repository.readEpubOpeningDocument(book, destination) ?: return@launch
                if (request != epubOpeningSeekRequest || epubOpening !== opening) return@launch
                val cover = destination.spineOrdinal == opening.structure.first.spineOrdinal
                val hasCover = cover && repository.coverFileFor(book) != null
                val element = localFraction?.let { (it * (document.content.totalElements - 1)).toInt() }
                    ?: document.firstElement
                loadedContent = document.content
                epubOpening = ReaderEpubOpening(opening.structure, document.destination, cover)
                sourceGeneration++
                currentFlatIndex = element + if (hasCover) 1 else 0
                currentCharOffset = 0
                currentScrollOffset = 0
                rebuildReady()
                if (hasCurrentSourceCoordinates(sourceGeneration)) saveProgress(currentFlatIndex, 0)
                seekCount = document.content.totalElements
                seekDetail = null
            } finally {
                trace.endStage("target_window", started, seekCount, detail = seekDetail, logName = "epub_package_seek")
                if (request == epubOpeningSeekRequest) _epubOpeningSeekPending.value = false
            }
        }
    }

    private fun prepareEpubOpening(book: Book) {
        if (epubOpeningPreparation != null) return
        val trace = openingTrace
        epubOpeningPreparation = viewModelScope.launch {
            val started = trace.beginStage("epub_frb_build", "Build FRB (Rust)")
            var builtCount = 0
            var builtDetail: String? = "canceled"
            var session: IndexedBookSession? = null
            try {
                session = repository.prepareEpubOpeningIndex(book)
                val content = if (session == null) repository.loadContent(book) else null
                val index = session?.index
                val exactContent = content ?: requireNotNull(index).readerSummary()
                val cover = withContext(Dispatchers.IO) { repository.coverFileFor(book) }
                val coverCount = if (cover == null) 0 else 1
                // A seek started before the FRB was ready still owns its destination.
                while (epubOpening != null) {
                    val seek = epubOpeningSeek
                    seek?.join()
                    if (seek !== epubOpeningSeek) continue
                    val ready = _state.value as? ReaderState.Ready ?: return@launch
                    val anchor = sourceAnchor(ready, currentFlatIndex) ?: return@launch
                    val sourceFlat = index?.let(anchor::resolve) ?: content?.let(anchor::resolve) ?: return@launch
                    val window = session?.loadWindow(sourceFlat)
                    if (session != null && window == null) return@launch
                    val hideFootnotes = settings.value.hideFootnotes
                    val nextGeneration = sourceGeneration + 1
                    val next = withContext(Dispatchers.Default) {
                        buildReaderReady(
                            book, exactContent, cover, hideFootnotes, sourceFlat + coverCount,
                            contentComplete = index == null, index = index, window = window,
                            auxiliaryContentReady = index == null, sourceGeneration = nextGeneration,
                        )
                    } as? ReaderState.Ready ?: return@launch
                    if (seek !== epubOpeningSeek || _state.value !== ready) continue
                    if (settings.value.hideFootnotes != hideFootnotes) continue
                    val latest = sourceAnchor(ready, currentFlatIndex) ?: return@launch
                    val latestSourceFlat = index?.let(latest::resolve) ?: content?.let(latest::resolve) ?: return@launch
                    if (window != null && latestSourceFlat - window.firstElement !in
                        0 until window.chapters.sumOf { it.elements.size }
                    ) continue
                    val onCover = currentFlatIndex < ready.chapterStarts.first()
                    currentFlatIndex = if (onCover) 0 else latestSourceFlat + coverCount
                    loadedContent = exactContent
                    loadedContentComplete = index == null
                    auxiliaryLoaded = index == null
                    indexedSession = session
                    loadedWindow = window
                    windowCenterChapter = index?.chapters?.indexOfLast { it.startElement <= latestSourceFlat }
                    epubOpening = null
                    sourceGeneration = nextGeneration
                    readyBuildGeneration++
                    clearNavigationHistory()
                    searchJob?.cancel()
                    _searchResults.value = null
                    _state.value = next
                    saveProgress(currentFlatIndex, currentScrollOffset, currentCharOffset)
                    builtCount = index?.totalElements ?: content?.totalElements ?: 0
                    builtDetail = null
                    return@launch
                }
            } catch (_: java.io.IOException) {
                builtDetail = "error"
                // Keep the open source window and its saved local reading position.
            } catch (_: IllegalArgumentException) {
                builtDetail = "error"
                // The publisher's package could not be mapped to an exact index.
            } finally {
                trace.endStage("epub_frb_build", started, builtCount, detail = builtDetail, logName = "epub_package_promoted")
                session?.takeIf { it !== indexedSession }?.let(repository::closeIndexedContent)
            }
        }
    }
    private var auxiliaryLoaded = true
    private var loadJob: Job? = null
    private var windowGeneration = 0L

    private suspend fun awaitPreparedSource(session: IndexedBookSession, book: Book): Boolean =
        withContext(Dispatchers.Main.immediate) { prepareEpubSource(session, book) }?.await() == true

    private fun prepareEpubSource(session: IndexedBookSession, book: Book): Deferred<Boolean>? {
        if (session !== indexedSession) return null
        val trace = openingTrace
        return epubPreparation ?: viewModelScope.async {
            val started = trace.beginStage("epub_frb_build", "Build FRB (Rust)")
            var builtCount = 0
            var builtDetail: String? = "canceled"
            try {
                repository.prepareEpubSourceContent(book, session).also { complete ->
                    if (complete) {
                        builtCount = session.index.totalElements
                        builtDetail = null
                    } else {
                        builtDetail = "skip"
                    }
                }
            } catch (_: java.io.IOException) {
                builtDetail = "error"
                false
            } catch (_: IllegalArgumentException) {
                builtDetail = "error"
                false
            } finally {
                trace.endStage("epub_frb_build", started, builtCount, detail = builtDetail, logName = "epub_source_prepared")
            }
        }.also { epubPreparation = it }
    }

    /** Explicit requests never perform IO on Main; only the latest window is published. */
    fun requestWindow(flatIndex: Int) {
        val session = indexedSession ?: return
        val ready = _state.value as? ReaderState.Ready ?: return
        val target = flatIndex.coerceIn(ready.items.indices)
        val chapter = ready.chapterAt(target)
        if (requestedWindowChapter == chapter && windowJob?.isActive == true) return
        windowJob?.cancel()
        val generation = ++windowGeneration
        if (ready.windowRange == ready.items.indices ||
            windowCenterChapter == chapter && !ready.items[target].isLoading) {
            requestedWindowChapter = null
            return
        }
        requestedWindowChapter = chapter
        val trace = openingTrace
        windowJob = viewModelScope.launch {
            val coverCount = ready.chapterStarts.first()
            val sourceTarget = (target - coverCount).coerceIn(0, session.index.totalElements - 1)
            val started = trace.beginStage("target_window", "Chapter window", progress = "ch ${chapter + 1}")
            var windowCount = 0
            var windowDetail: String? = "canceled"
            try {
                val window = session.loadWindow(sourceTarget) ?: if (session.startedFromEpubSource &&
                    awaitPreparedSource(session, ready.book)
                ) session.loadWindow(sourceTarget) else null
                if (session !== indexedSession || generation != windowGeneration) return@launch
                if (window == null) {
                    windowDetail = "error"
                    if (session.startedFromEpubSource) {
                        requestedWindowChapter = null
                        return@launch
                    }
                    // A damaged derived block is repaired through the current source parser.
                    // It must never become an empty successful navigation destination.
                    repository.discardIndexedContent(ready.book)
                    if (session === indexedSession && generation == windowGeneration) load()
                    return@launch
                }
                loadedWindow = window
                windowCenterChapter = chapter
                rebuildReady(resetNavigation = false)
                if (generation == windowGeneration) requestedWindowChapter = null
                windowCount = window.chapters.sumOf { it.elements.size }
                windowDetail = "ch ${chapter + 1}"
            } finally {
                trace.endStage("target_window", started, windowCount, detail = windowDetail, logName = "target_window")
            }
        }
    }

    /** Heavy note/appendix bodies are requested by an explicit user action. */
    fun requestAuxiliaryContent() {
        val session = indexedSession ?: return
        if (auxiliaryLoaded || auxiliaryJob?.isActive == true) return
        auxiliaryJob = viewModelScope.launch {
            if (session.startedFromEpubSource) {
                val book = loadedBook ?: return@launch
                if (!awaitPreparedSource(session, book)) return@launch
            }
            val globals = session.readGlobals()
            if (session !== indexedSession) return@launch
            if (globals == null) {
                loadedBook?.let { repository.discardIndexedContent(it) }
                if (session === indexedSession) load()
                return@launch
            }
            loadedContent = globals
            auxiliaryLoaded = true
            rebuildReady(resetNavigation = false)
        }
    }

    private fun closeIndexedSession() {
        cancelPageIndex()
        windowGeneration++
        windowJob?.cancel()
        auxiliaryJob?.cancel()
        epubPreparation?.cancel()
        epubPreparation = null
        epubOpeningPreparation?.cancel()
        epubOpeningPreparation = null
        epubOpeningSeekRequest++
        epubOpeningSeek?.cancel()
        epubOpeningSeek = null
        _epubOpeningSeekPending.value = false
        if (epubOpening != null) sourceGeneration++
        epubOpening = null
        val session = indexedSession
        indexedSession = null
        loadedWindow = null
        windowCenterChapter = null
        requestedWindowChapter = null
        if (session != null) repository.closeIndexedContent(session)
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
        loadJob?.cancel()
        searchJob?.cancel()
        paginationJob?.cancel()
        paginationKeyInFlight = null
        _pagination.value = null
        presentedPagination.value = null
        closeIndexedSession()
        loadedBook = null
        loadedContent = null
        val trace = resetOpeningTrace()
        loadJob = viewModelScope.launch {
            _state.value = ReaderState.Loading
            val book = repository.bookById(bookId)
            if (book == null) {
                _state.value = ReaderState.Error
                return@launch
            }
            trace.setBookFormat(book.format.name)
            currentFlatIndex = book.progress.elementIndex
            val savedLocation = book.progress.documentLocation as? DocumentLocation.Reflowable
            val sourcePosition = savedEpubAnchor(book) != null
            currentScrollOffset = if (sourcePosition) savedLocation?.scrollOffset ?: 0 else book.progress.scrollOffset
            currentCharOffset = savedLocation?.takeIf { sourcePosition || it.elementIndex == currentFlatIndex }
                ?.charOffset?.coerceAtLeast(0) ?: 0
            currentReadingLogId = book.activeReadingLogId
            initializeCompletionReview(book)
            viewingPreviousReading = book.hasFinishedReading || book.status == ReadingStatus.ABANDONED
            _finishSaved.value = viewingPreviousReading
            _offerReread.value = viewingPreviousReading
            clearNavigationHistory()
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
                val readyStarted = trace.beginStage("prepare_reader", "Prepare reader")
                var readyCount = 0
                var readyDetail: String? = "canceled"
                try {
                    rebuildReady(resetNavigation = first, invalidateSearch = true)
                    readyCount = content.totalElements
                    readyDetail = null
                } finally {
                    trace.endStage("prepare_reader", readyStarted, readyCount, detail = readyDetail)
                }
                // Only now. It is fire-and-forget on a scope that outlives
                // this screen, but starting it first put a full index
                // rewrite — fsync, .bak copy, widget rebuild — onto the IO
                // pool that the book itself was about to be read from, and
                // the open simply queued behind it.
                if (first) repository.noteOpened(bookId)
            }
            runCatching {
                val indexStarted = trace.beginStage("index_open", "Open FRB index")
                var indexCount = 0
                var indexDetail: String? = "canceled"
                var indexLog = "book_index"
                var indexLabel = "Open FRB index"
                val session = try {
                    val opened = repository.openIndexedContent(book) ?: repository.openEpubSourceContent(book)
                    if (opened != null && opened.index.totalElements > 0) {
                        indexCount = opened.index.chapters.size
                        indexDetail = "$indexCount ch"
                        indexLog = if (opened.startedFromEpubSource) "epub_source_index" else "book_index"
                        indexLabel = if (opened.startedFromEpubSource) "Open source" else "Open FRB index"
                    } else {
                        indexDetail = "miss"
                        indexLog = "book_index_miss"
                    }
                    opened
                } finally {
                    trace.endStage(
                        id = "index_open",
                        since = indexStarted,
                        count = indexCount,
                        detail = indexDetail,
                        logName = indexLog,
                        label = indexLabel,
                    )
                }
                if (session != null && session.index.totalElements > 0) {
                    indexedSession = session
                    val coverCount = withContext(Dispatchers.IO) {
                        if (repository.coverFileFor(book) == null) 0 else 1
                    }
                    savedEpubAnchor(book)?.resolve(session.index)?.let { currentFlatIndex = it + coverCount }
                    val target = (currentFlatIndex - coverCount).coerceIn(0, session.index.totalElements - 1)
                    val windowStage = if (session.startedFromEpubSource) "epub_targeted_window" else "opening_window"
                    val windowStarted = trace.beginStage("opening_window", "Opening chapters")
                    var windowCount = 0
                    var windowDetail: String? = "canceled"
                    val window = try {
                        val loaded = session.loadWindow(target, neighbors = if (session.startedFromEpubSource) 0 else 1)
                        if (loaded != null) {
                            windowCount = loaded.chapters.sumOf { it.elements.size }
                            windowDetail = "${loaded.chapters.size} ch"
                        } else {
                            windowDetail = "error"
                        }
                        loaded
                    } finally {
                        trace.endStage(
                            id = "opening_window",
                            since = windowStarted,
                            count = windowCount,
                            detail = windowDetail,
                            logName = windowStage,
                        )
                    }
                    if (window != null) {
                        loadedWindow = window
                        windowCenterChapter = session.index.chapters.indexOfLast { it.startElement <= target }
                        auxiliaryLoaded = false
                        val index = session.index
                        acceptContent(index.readerSummary(), complete = false)
                        return@launch
                    }
                    closeIndexedSession()
                } else if (session != null) repository.closeIndexedContent(session)
                if (!viewingPreviousReading && (currentFlatIndex == 0 || savedEpubAnchor(book) != null)) {
                    val openingStarted = trace.beginStage("opening_window", "Opening chapters")
                    var openingCount = 0
                    var openingDetail: String? = "canceled"
                    var openedDoc: Pair<EpubOpeningStructure, com.example.frogreader.data.parser.EpubOpeningDocument>? = null
                    try {
                        val structure = repository.readEpubOpeningStructure(book)
                        if (structure != null) {
                            val saved = savedEpubAnchor(book)
                            val destination = saved?.let { EpubSourceDestination(it.spineOrdinal, it.path) }
                            val candidates = if (destination != null) listOf(destination) else structure.documents.take(3)
                            for (candidate in candidates) {
                                val document = repository.readEpubOpeningDocument(book, candidate) ?: continue
                                val savedElement = saved?.documentElement
                                if (savedElement != null && savedElement !in 0 until document.content.totalElements) break
                                openedDoc = structure to document
                                openingCount = document.content.totalElements
                                openingDetail = null
                                break
                            }
                        }
                        if (openedDoc == null) openingDetail = "skip"
                    } finally {
                        trace.endStage(
                            id = "opening_window",
                            since = openingStarted,
                            count = openingCount,
                            detail = openingDetail,
                            logName = "epub_package_window",
                        )
                    }
                    if (openedDoc != null) {
                        val (structure, document) = openedDoc
                        val saved = savedEpubAnchor(book)
                        val includesCover = document.destination.spineOrdinal == structure.first.spineOrdinal
                        val coverCount = if (includesCover && repository.coverFileFor(book) != null) 1 else 0
                        epubOpening = ReaderEpubOpening(structure, document.destination, includesCover)
                        sourceGeneration++
                        currentFlatIndex = saved?.documentElement?.plus(coverCount) ?: 0
                        auxiliaryLoaded = true
                        acceptContent(document.content, complete = false)
                        return@launch
                    }
                }
                auxiliaryLoaded = true
                val parseStarted = trace.beginStage("parse", "Parse book")
                var parseCount = 0
                var parseDetail: String? = "canceled"
                try {
                    val content = repository.loadContentProgressively(book) { preview ->
                        trace.updateStage("parse", "${preview.chapters.size} ch")
                        trace.stage("parse_preview", parseStarted, preview.totalElements, detail = "${preview.chapters.size} ch")
                        acceptContent(preview, complete = false)
                    }
                    parseCount = content.totalElements
                    parseDetail = "${content.chapters.size} ch"
                    content
                } catch (error: Throwable) {
                    if (error !is kotlinx.coroutines.CancellationException) parseDetail = "error"
                    throw error
                } finally {
                    trace.endStage("parse", parseStarted, parseCount, detail = parseDetail, logName = "parse")
                }
            }
                .onSuccess { content ->
                    savedEpubAnchor(book)?.resolve(content)?.let { item ->
                        currentFlatIndex = item + if (repository.coverFileFor(book) == null) 0 else 1
                    }
                    acceptContent(content, complete = true)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    closeIndexedSession()
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
    private suspend fun rebuildReady(resetNavigation: Boolean = true, invalidateSearch: Boolean = resetNavigation) {
        val book = loadedBook ?: return
        val content = loadedContent ?: return
        val contentComplete = loadedContentComplete
        val index = indexedSession?.index
        val window = loadedWindow
        val auxiliaryContentReady = auxiliaryLoaded
        val opening = epubOpening
        val coordinates = sourceGeneration
        val readerSettings = settings.value
        val initialFlatIndex = currentFlatIndex
        val generation = ++readyBuildGeneration
        withContext(Dispatchers.Main.immediate) {
            // Item texts are about to change — cached match offsets go stale.
            // Character anchors in the jump stack belong to the old item
            // stream (notably when hide-footnotes rewrites inline text).
            if (invalidateSearch) {
                searchJob?.cancel()
                _searchResults.value = null
            }
            if (resetNavigation) clearNavigationHistory()
        }
        val next = withContext(Dispatchers.Default) {
            buildReaderReady(book, content, repository.coverFileFor(book), readerSettings.hideFootnotes,
                initialFlatIndex, contentComplete, index, window, auxiliaryContentReady, opening, coordinates)
        }
        withContext(Dispatchers.Main.immediate) {
            // A setting change can prepare an older prefix concurrently with
            // the complete publication. Never let that late result replace it.
            if (generation == readyBuildGeneration && content === loadedContent &&
                contentComplete == loadedContentComplete && window === loadedWindow &&
                coordinates == sourceGeneration &&
                readerSettings.hideFootnotes == settings.value.hideFootnotes
            ) _state.value = next
        }
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

    fun saveProgress(
        firstVisibleIndex: Int,
        scrollOffset: Int,
        charOffset: Int = 0,
        exactPages: PageCounterInfo? = null,
        sourceGeneration: Int? = null,
    ) {
        // The old reader can still emit a frame while a new reading is being saved.
        // Do not let that frame overwrite the reset position or the undecided one.
        if (_offerReread.value || _restartingReading.value || !hasCurrentSourceCoordinates(sourceGeneration)) return
        val ready = _state.value as? ReaderState.Ready ?: return
        if (itemAt(ready, firstVisibleIndex) == null) return
        observeVisiblePosition(firstVisibleIndex, scrollOffset, charOffset, sourceGeneration)
        if (ready.epubOpening != null) {
            val anchor = sourceAnchor(ready, firstVisibleIndex) ?: return
            val previous = this.book.value?.progress ?: ready.book.progress
            val progress = if (firstVisibleIndex < ready.chapterStarts.first()) ReadingProgress()
            else previous.copy(documentLocation = DocumentLocation.Reflowable(
                previous.chapterIndex, previous.elementIndex, charOffset, scrollOffset, epubSource = anchor,
            ))
            if (progress != previous) writeReadingProgress(progress, serialize = true)
            return
        }
        val fraction = if (!ready.globalCoordinatesReady) {
            this.book.value?.progress?.fraction ?: ready.book.progress.fraction
        } else if (ready.items.size <= 1) {
            0f
        } else {
            (firstVisibleIndex.toFloat() / (ready.items.size - 1)).coerceIn(0f, 1f)
        }
        val chapter = ready.chapterAt(firstVisibleIndex)
        val pageCounts = exactPages?.let { it.pagesLeftInChapter to it.totalPages }
            ?: pageCountsFor(ready, chapter, firstVisibleIndex)
        val progress = ReadingProgress(
            chapterIndex = chapter,
            elementIndex = firstVisibleIndex,
            scrollOffset = scrollOffset,
            fraction = fraction,
            pagesLeftInChapter = pageCounts.first,
            totalPages = pageCounts.second,
            documentLocation = DocumentLocation.Reflowable(
                chapter, firstVisibleIndex, charOffset, scrollOffset,
                epubSource = exactEpubAnchor(ready, firstVisibleIndex, chapter),
            ),
        )
        writeReadingProgress(progress, serialize = ready.sourceGeneration != 0)
    }

    private fun writeReadingProgress(progress: ReadingProgress, serialize: Boolean) {
        val readingId = currentReadingLogId
        if (readingId == null || serialize) {
            // A fresh book has no id until its queued first-open write finishes.
            enqueueReadingWrite { repository.saveProgressForReading(bookId, progress, readingId ?: currentReadingLogId) }
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
                if (!continuePrevious) {
                    val session = indexedSession
                    if (session != null) {
                        windowJob?.cancel()
                        windowGeneration++
                        requestedWindowChapter = null
                        loadedWindow = session.loadWindow(0) ?: throw java.io.IOException("Cannot load first chapter")
                        windowCenterChapter = session.index.chapters.indexOfFirst { it.elementCount > 0 }
                    }
                    paginationJob?.cancel()
                    paginationKeyInFlight = null
                    _pagination.value = null
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
        if ((_state.value as? ReaderState.Ready)?.globalCoordinatesReady == false) {
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
        val readingId = currentReadingLogId
        enqueueReadingWrite {
            // A fresh reading obtains its id from the first-open write ahead of this one.
            repository.recordReadingTimeForReading(bookId, seconds, readingId ?: currentReadingLogId, date, lastReadAt, statsRepository)
        }
    }

    // ------------------------------------------------------------- bookmarks

    /** How close (in elements) a bookmark must be to count as "current". */
    val bookmarkWindow = 4

    fun bookmarkNear(flatIndex: Int): Bookmark? =
        book.value?.bookmarks?.takeIf { (_state.value as? ReaderState.Ready)?.epubOpening == null }?.firstOrNull {
            it.flatIndex in flatIndex..(flatIndex + bookmarkWindow)
        }

    /** Adds a bookmark at [flatIndex], or removes the one already there. */
    fun toggleBookmarkAt(flatIndex: Int) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.epubOpening != null) return
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
        if (trimmed.isEmpty() || (_state.value as? ReaderState.Ready)?.epubOpening != null) return
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
            val item = itemAt(ready, i) ?: break
            item.element.previewText()?.let { return it }
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

/** Matches a presented window pagination key with or without a `|source=<gen>` suffix. */
internal fun matchesPresentedWindowKey(holderKey: String?, specKey: String, environment: String): Boolean {
    if (holderKey == null || !holderKey.startsWith("$specKey|window=")) return false
    val envMarker = "|environment=$environment"
    return holderKey.endsWith(envMarker) || holderKey.contains("$envMarker|source=")
}

/** True only when the publication has something in its sequential surface. */
internal fun BookContent.hasSequentialContent(): Boolean =
    chapters.any { it.elements.isNotEmpty() }
