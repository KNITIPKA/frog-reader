@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.frogreader.ui.reader.pdf

import android.content.ClipData
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.compose.PdfViewerState
import com.example.frogreader.MainActivity
import com.example.frogreader.R
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.model.*
import com.example.frogreader.data.pdf.PdfCapabilities
import com.example.frogreader.data.pdf.annotations.PDF_YELLOW_HIGHLIGHT_ARGB
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.ui.components.FrogSnackbarHost
import com.example.frogreader.ui.reader.*
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface
import com.example.frogreader.ui.translation.rememberExternalTextAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.roundToInt

/** Reader route for fixed-layout books; the application still owns all chrome and personal data. */
@Composable
internal fun PdfReaderScreen(
    bookId: String,
    initialQuoteId: String?,
    initialBookmarkId: String?,
    onBack: () -> Unit,
    onExitToLibrary: () -> Unit,
    contentMotion: Modifier = Modifier,
) {
    val model: PdfReaderViewModel = viewModel(key = "pdf-reader-$bookId", factory = PdfReaderViewModel.factory(bookId))
    val notes = rememberPdfNotesState(bookId, model.writeSessionId)
    val state by model.state.collectAsStateWithLifecycle()
    val book by model.book.collectAsStateWithLifecycle()
    val appSettings by model.appSettings.collectAsStateWithLifecycle()
    val saveError by model.saveError.collectAsStateWithLifecycle()
    val pdfAnnotationError by model.pdfAnnotationError.collectAsStateWithLifecycle()
    val offerReread by model.offerReread.collectAsStateWithLifecycle()
    val rereadBusy by model.rereadBusy.collectAsStateWithLifecycle()
    val rereadError by model.rereadError.collectAsStateWithLifecycle()
    val finished by model.finished.collectAsStateWithLifecycle()
    val review by model.completionReview.collectAsStateWithLifecycle()
    val appTheme = appSettings.effectiveTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val theme = book?.readerSettings?.pdfTheme ?: appTheme
    var chrome by rememberSaveable(bookId) { mutableStateOf(false) }
    var panel by rememberSaveable(bookId) { mutableIntStateOf(0) }
    var searchVisible by rememberSaveable(bookId) { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    var brightness by remember { mutableStateOf<Float?>(null) }
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    SystemBarsEffect(
        theme = if (panel == 3) appTheme else theme,
        chromeVisible = chrome || drawerOpen || panel != 0 || searchVisible || finished ||
            notes.draft.value != null || notes.viewedId.value != null || state !is PdfReaderState.Ready,
        restoreTheme = appTheme,
        hideStatusBar = panel != 3,
    )
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pdfWriteErrorMessage = stringResource(R.string.pdf_note_write_error)
    val pdfWriteRetry = stringResource(R.string.reading_log_reader_retry)
    LaunchedEffect(pdfAnnotationError) {
        if (pdfAnnotationError && snackbar.showSnackbar(pdfWriteErrorMessage, pdfWriteRetry,
                duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) model.retryPdfAnnotations()
    }
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(book?.title, text))) } }

    LaunchedEffect(initialQuoteId, initialBookmarkId) {
        if (initialQuoteId != null || initialBookmarkId != null) model.keepPreviousReading()
    }
    DisposableEffect(owner, model) {
        resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                model.savePosition()
                model.setReadingActive(false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.savePosition()
            model.setReadingActive(false)
        }
    }
    DisposableEffect(activity, appSettings.keepScreenOn, resumed, state) {
        val window = activity?.window
        if (appSettings.keepScreenOn && resumed && state is PdfReaderState.Ready) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        val initialBrightness = activity?.window?.attributes?.screenBrightness
        onDispose { if (initialBrightness != null) activity?.window?.let { window ->
            window.attributes = window.attributes.apply { screenBrightness = initialBrightness }
        } }
    }
    val changeBrightness: (Float?) -> Unit = { value ->
        brightness = value
        activity?.window?.let { window -> window.attributes = window.attributes.apply {
            screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } }
    }

    BackHandler(panel != 0 || searchVisible || finished) {
        when {
            panel != 0 -> panel = 0
            searchVisible -> { searchVisible = false; model.setSearchInput("") }
            finished -> model.saveCompletionReview(onExitToLibrary)
        }
    }
    FrogReaderTheme(theme, appSettings.dynamicColor) {
        val scheme = MaterialTheme.colorScheme
        val colors = readerColors(theme, scheme)
        Box(Modifier.fillMaxSize().background(colors.background)) {
            when (val current = state) {
                PdfReaderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                is PdfReaderState.Password -> PdfPasswordPrompt(current.textAvailable, onBack, model::load)
                PdfReaderState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pdf_error_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.pdf_error_message), Modifier.padding(vertical = 12.dp))
                    Row { TextButton(onClick = { model.load() }) { Text(stringResource(R.string.reading_log_reader_retry)) }
                        TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back)) } }
                }
                is PdfReaderState.Ready -> key(current.document) {
                    book?.let { liveBook ->
                        PdfReadingSurface(
                            current.document, liveBook, model, colors, chrome, { chrome = !chrome },
                            searchVisible, { searchVisible = it }, panel, { panel = it },
                            resumed && panel == 0 && !searchVisible && !offerReread && !finished && !saveError,
                            initialQuoteId, initialBookmarkId, snackbar, copy, onBack, contentMotion,
                            drawerOpen, { drawerOpen = it }, brightness, changeBrightness,
                            interactionsEnabled = resumed && panel == 0 && !offerReread && !finished && !saveError,
                            notes = notes,
                        )
                        if (panel == 3) FrogReaderTheme(appTheme, appSettings.dynamicColor) {
                            com.example.frogreader.ui.library.BookInfoScreen(bookId = bookId, onBack = { panel = 0 })
                        }
                    }
                }
            }
            if (finished) {
                CompletionPage(book, colors, Modifier.fillMaxSize().background(colors.background)
                    .windowInsetsPadding(WindowInsets.pdfControlsSafeInsets),
                    cover = model.coverFile(), reviewState = review,
                    onRatingChange = model::setCompletionRating, onReviewChange = model::setCompletionReview,
                    onSaveReview = { model.saveCompletionReview() }, onExit = { model.saveCompletionReview(onExitToLibrary) })
            }
            FrogSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp))
        }
        book?.let { currentBook -> ReaderRereadDialog(
            visible = offerReread && state is PdfReaderState.Ready,
            book = currentBook, cover = model.coverFile(), busy = rereadBusy, error = rereadError,
            onBrowse = model::keepPreviousReading,
            onReadAgain = { model.chooseReading(false) }, onResume = { model.chooseReading(true) },
        ) }
        if (saveError) AlertDialog(
            onDismissRequest = {}, containerColor = scheme.floatingSurface,
            title = { Text(stringResource(R.string.reading_log_reader_save_error_title)) },
            text = { Text(stringResource(R.string.reading_log_reader_save_error)) },
            confirmButton = { TextButton(onClick = model::retryWrites) { Text(stringResource(R.string.reading_log_reader_retry)) } },
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.pdf_leave_unsaved)) } },
        )
    }
}

@Composable
internal fun PdfReadingSurface(
    document: PdfDocument,
    book: Book,
    model: PdfReaderViewModel,
    colors: ReaderColors,
    chrome: Boolean,
    toggleChrome: () -> Unit,
    searchVisible: Boolean,
    setSearchVisible: (Boolean) -> Unit,
    panel: Int,
    setPanel: (Int) -> Unit,
    active: Boolean,
    initialQuoteId: String?,
    initialBookmarkId: String?,
    snackbar: SnackbarHostState,
    copy: (String) -> Unit,
    onBack: () -> Unit,
    contentMotion: Modifier,
    drawerOpen: Boolean,
    onDrawerOpenChange: (Boolean) -> Unit,
    brightness: Float?,
    changeBrightness: (Float?) -> Unit,
    interactionsEnabled: Boolean = active,
    notes: PdfNotesState = rememberPdfNotesState(book.id, model.writeSessionId),
) {
    val readerSettings by model.settings.collectAsStateWithLifecycle()
    val background = colors.background
    val initialLocation = remember(document) {
        (pdfQuoteOrBookmarkTarget(book, initialQuoteId, initialBookmarkId, document.pageCount)
            ?: (book.progress.documentLocation as? DocumentLocation.Pdf))?.let {
                PdfCapabilities.normalizeLocation(it, document.pageCount)
            }
    }
    var currentLocation by remember { mutableStateOf(initialLocation) }
    var singlePageIndex by remember { mutableIntStateOf(initialLocation?.pageIndex ?: 0) }
    var singlePage by remember { mutableStateOf(readerSettings.pdfSinglePage) }
    var pendingLocation by remember { mutableStateOf<DocumentLocation.Pdf?>(null) }
    val pageViewports = remember(document, singlePage) { PdfPageViewports(document) }
    DisposableEffect(pageViewports) { onDispose { pageViewports.close() } }
    val continuousViewport = remember(document, singlePage) { PdfPageViewport(document) }
    val pager = key(document, singlePage) {
        rememberPagerState(initialPage = singlePageIndex) { document.pageCount }
    }
    val currentPager by rememberUpdatedState(pager)
    val currentPageViewports by rememberUpdatedState(pageViewports)
    SideEffect { pageViewports.trim(pager.currentPage) }
    val viewport = if (singlePage) pageViewports.page(singlePageIndex) else continuousViewport
    val currentViewport by rememberUpdatedState(viewport)
    val renderDocument = viewport.document
    val viewer = viewport.viewer
    var size by remember { mutableStateOf(IntSize.Zero) }
    var ready by viewport::ready
    var viewportInitialized by viewport::initialized
    var pageWidth by viewport::pageWidth
    var fallbackFitZoom by viewport::fallbackFitZoom
    var restored by remember { mutableStateOf(false) }
    var openingSlow by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var draggingPage by remember { mutableStateOf(false) }
    var pageTurnJob by remember { mutableStateOf<Job?>(null) }
    val composeView = androidx.compose.ui.platform.LocalView.current
    var loadedViewer by viewport::nativeView
    var fastScrolling by remember(loadedViewer) { mutableStateOf(false) }
    var fastScrollTargetPage by remember(loadedViewer) { mutableStateOf<Int?>(null) }
    DisposableEffect(loadedViewer) {
        val native = loadedViewer
        native?.onFastScrollDragChanged = { fastScrolling = it }
        native?.onFastScrollTargetPageChanged = { fastScrollTargetPage = it }
        onDispose {
            native?.onFastScrollDragChanged = null
            native?.onFastScrollTargetPageChanged = null
        }
    }
    LaunchedEffect(ready, colors) {
        if (ready) composeView.findPdfView(renderDocument)?.let { it.isAutoScrollingEnabled = false }
    }
    val query by model.searchInput.collectAsStateWithLifecycle()
    var matchIndex by remember { mutableIntStateOf(0) }
    val search by model.search.collectAsStateWithLifecycle()
    val outline by model.outline.collectAsStateWithLifecycle()
    val navigation by model.navigation.collectAsStateWithLifecycle()
    val app by model.appSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var searchMode by rememberSaveable(book.id) { mutableStateOf(PdfSearchMode.TEXT) }
    val panelFraction = remember { mutableFloatStateOf(0f) }
    var closeDrawerRequest by remember { mutableIntStateOf(0) }
    val textAvailable = remember(document) { document.supportsTextSearch() }
    val selectionFailureMessage = stringResource(R.string.pdf_selection_error)
    LaunchedEffect(document) {
        val failures = (document as? StabilizedPdfDocument)?.selectionFailures
            ?: (document as? CompatibilityPdfDocument)?.selectionFailures
        failures?.collect {
            snackbar.showSnackbar(selectionFailureMessage)
        }
    }
    val rawSelection = viewer.currentSelection
    val selection = rawSelection.toActionTextSelection()
    var noteDraft by notes.draft
    var viewedNoteId by notes.viewedId
    var pendingNoteId by notes.pendingId
    var pendingNoteText by notes.pendingText
    val noteOpen = noteDraft != null || viewedNoteId != null
    LaunchedEffect(book.quotes, pendingNoteId, pendingNoteText) {
        if (pendingNoteId != null && book.quotes.any { it.id == pendingNoteId && it.note == pendingNoteText }) {
            noteDraft = null
            pendingNoteId = null
            pendingNoteText = null
        }
    }
    LaunchedEffect(book.quotes) {
        val draft = noteDraft
        if (draft?.quoteId == null && draft != null && book.quotes.any { it.id == draft.saveId && it.note == draft.note.trim() }) {
            noteDraft = null
        }
        if (viewedNoteId != null && book.quotes.none { it.id == viewedNoteId && !it.note.isNullOrBlank() }) viewedNoteId = null
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(readerSettings.pdfSinglePage) {
        if (singlePage != readerSettings.pdfSinglePage) {
            pageTurnJob?.cancel()
            pendingLocation = currentLocation ?: DocumentLocation.Pdf(singlePageIndex)
            singlePageIndex = currentLocation?.pageIndex ?: singlePageIndex
            restored = false
            // A pager that is not composed has no layout: scrolling it before enabling
            // Pages suspends indefinitely and prevents the mode change from taking effect.
            singlePage = readerSettings.pdfSinglePage
            // The new mode gets a fresh pager at this source page. Its old, detached layout
            // and any gesture callbacks must not retain the previous mode's viewports.
        }
    }
    val page by remember(viewer, document, composeView, singlePage, singlePageIndex) {
        derivedStateOf {
            if (singlePage) singlePageIndex else viewer.pageAtCenter(
                size = size,
                pageCount = document.pageCount,
                viewportReady = loadedViewer != null && loadedViewer === composeView.findPdfView(renderDocument),
                lastLocation = currentLocation,
            )
        }
    }
    val requestGate = remember { Mutex() }
    val translate = rememberExternalTextAction(ExternalTextAction.TRANSLATE, app.defaultTranslator,
        { model.setDefaultTextApp(ExternalTextAction.TRANSLATE, it) }, snackbar, requestGate, viewer::clearCurrentSelection)
    val define = rememberExternalTextAction(ExternalTextAction.DEFINE, app.defaultDictionary,
        { model.setDefaultTextApp(ExternalTextAction.DEFINE, it) }, snackbar, requestGate, viewer::clearCurrentSelection)
    val externalSearch = rememberExternalTextAction(ExternalTextAction.SEARCH, app.defaultSearchApp,
        { model.setDefaultTextApp(ExternalTextAction.SEARCH, it) }, snackbar, requestGate, viewer::clearCurrentSelection)
    val currentToggle by rememberUpdatedState(toggleChrome)

    fun closeSearch() {
        setSearchVisible(false)
        model.setSearchInput("")
        focus.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(searchVisible) {
        // The route's Back handler closes search too, without going through the close button.
        if (!searchVisible) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(rawSelection) {
        // The native viewer can start a selection while the search field is open. Give that
        // selection its action bar instead of hiding both sets of controls.
        if (rawSelection != null && searchVisible) closeSearch()
    }

    suspend fun applyLocation(location: DocumentLocation.Pdf) {
        if (!singlePage) preparePdfPageLayout(document, location.pageIndex)
        val targetViewer = currentViewport.viewer
        targetViewer.zoomScroll { zoomTo(location.zoom) }
        targetViewer.scrollToPosition(PdfPoint(if (singlePage) 0 else location.pageIndex, location.x, location.y))
    }
    suspend fun navigate(location: DocumentLocation.Pdf) {
        pageTurnJob?.cancel()
        currentViewport.viewer.clearCurrentSelection()
        val safe = PdfCapabilities.normalizeLocation(location, document.pageCount)
        if (singlePage && safe.pageIndex != singlePageIndex) {
            pendingLocation = safe
            currentLocation = safe
            model.observePosition(safe)
            singlePageIndex = safe.pageIndex
            currentPager.scrollToPage(safe.pageIndex)
        } else if (!currentViewport.initialized) {
            pendingLocation = safe
        } else applyLocation(safe)
    }
    fun adoptPage(target: Int) {
        if (target != singlePageIndex) {
            pendingLocation = null
            currentLocation = DocumentLocation.Pdf(target)
            model.observePosition(DocumentLocation.Pdf(target))
            singlePageIndex = target
        }
    }
    fun canShowPage(index: Int): Boolean = currentPageViewports.composedPage(index)?.isRendered(size) == true
    val pageRenderError = stringResource(R.string.pdf_page_render_error)
    fun goToPage(target: Int, fingerVelocity: Float = 0f) {
        val safe = target.coerceIn(0, document.pageCount - 1)
        pageTurnJob?.cancel()
        currentViewport.viewer.clearCurrentSelection()
        pageTurnJob = scope.launch {
            if (singlePage) {
                val targetPager = currentPager
                if (kotlin.math.abs(safe - targetPager.currentPage) > 1) targetPager.scrollToPage(safe)
                else {
                    // Composition/layout alone does not mean the incoming page has an image.
                    // A fast sequence may catch up with prefetch, particularly on scanned PDFs.
                    val rendered = withTimeoutOrNull(20_000) {
                        while (!canShowPage(safe)) withFrameNanos { }
                        true
                    } ?: false
                    if (!rendered) {
                        snackbar.showSnackbar(pageRenderError)
                        return@launch
                    }
                    settlePdfPage(targetPager, safe, fingerVelocity)
                }
                adoptPage(safe)
            } else {
                preparePdfPageLayout(document, safe)
                currentViewport.viewer.scrollToPage(safe)
            }
        }
    }
    fun turnPage(delta: Int) {
        if (restored && !draggingPage) goToPage((if (singlePage) currentPager.currentPage else currentLocation?.pageIndex ?: page) + delta)
    }
    LaunchedEffect(ready, renderDocument, size, pendingLocation, viewportInitialized) {
        if (!ready || size.width == 0 || size.height == 0) return@LaunchedEffect
        if (singlePage && !viewportInitialized) return@LaunchedEffect
        if (viewportInitialized && pendingLocation == null && restored) return@LaunchedEffect
        val target = pendingLocation ?: initialLocation.takeIf { !restored }
        if (singlePage && target != null &&
            (renderDocument as? SinglePagePdfDocument)?.sourcePageIndex != target.pageIndex) return@LaunchedEffect
        fallbackFitZoom = viewer.zoom
        val info = try {
            withContext(Dispatchers.IO) { renderDocument.getPageInfo(if (singlePage) 0 else page) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null } // A transient metadata failure must not take down the reader.
        pageWidth = info?.width ?: 0
        if (target != null) {
            applyLocation(target)
            // AndroidX may defer a distant scroll until preceding page sizes arrive. Keep
            // the opening cover in place until the destination, rather than page zero, renders.
            val rendered = withTimeoutOrNull(20_000) {
                while (true) {
                    val native = loadedViewer
                    val targetPage = if (singlePage) 0 else target.pageIndex
                    if (native?.pdfDocument === renderDocument &&
                        targetPage >= native.firstVisiblePage &&
                        targetPage < native.firstVisiblePage + native.visiblePagesCount &&
                        native.arePagesFullyRendered()) break
                    withFrameNanos { }
                }
                true
            } ?: false
            if (!rendered) { openingSlow = true; return@LaunchedEffect }
            withFrameNanos { }
        }
        pendingLocation = null
        viewportInitialized = true
        restored = true
    }
    LaunchedEffect(ready, viewportInitialized, restored, renderDocument) {
        openingSlow = false
        if (!ready || !viewportInitialized || !restored) { delay(20_000); openingSlow = true }
    }
    LaunchedEffect(retrying) {
        if (retrying) {
            // Remove AndroidView before the session closes its previous document.
            withFrameNanos { }
            model.load()
        }
    }
    LaunchedEffect(navigation, restored) {
        if (restored && navigation != null) {
            val target = PdfCapabilities.normalizeLocation(navigation!!, document.pageCount)
            navigate(target)
            model.consumeNavigation()
            model.observePosition(target)
        }
    }
    LaunchedEffect(restored, size, renderDocument, viewportInitialized) {
        if (!restored || !viewportInitialized || size.width == 0 || size.height == 0) return@LaunchedEffect
        var heightPage = -1
        var pageHeight = 0
        snapshotFlow {
            val window = renderDocument as? SinglePagePdfDocument
            val switching = (window != null) != singlePage ||
                (window != null && window.sourcePageIndex != singlePageIndex)
            if (fastScrolling || switching || loadedViewer == null || loadedViewer !== composeView.findPdfView(renderDocument) ||
                loadedViewer?.pdfDocument !== renderDocument) null
            else viewer.locationAtCenter(size).let {
                if (window != null) it.copy(pageIndex = window.sourcePageIndex) else it
            }
        }.distinctUntilChanged().collectLatest { location ->
            if (location == null) return@collectLatest
            currentLocation = location
            // Publish coordinates immediately so pause/back always sees the latest viewport.
            model.observePosition(location)
            if (heightPage != location.pageIndex) {
                pageHeight = try { withContext(Dispatchers.IO) { document.getPageInfo(location.pageIndex).height } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { 0 }
                heightPage = location.pageIndex
            }
            val top = location.y - size.height / (2f * location.zoom)
            model.observePosition(location, if (pageHeight > 0) (top / pageHeight).coerceIn(0f, 0.999f) else 0f)
            delay(500)
            model.savePosition()
        }
    }
    DisposableEffect(active, ready, restored, viewportInitialized, rawSelection, drawerOpen, noteOpen, model) {
        model.setReadingActive(active && ready && restored && viewportInitialized && rawSelection == null && !drawerOpen && !noteOpen)
        onDispose { model.setReadingActive(false) }
    }
    LaunchedEffect(active, restored) {
        while (active && restored) { delay(15_000); model.flushTime() }
    }
    fun pageHighlights(visiblePages: IntRange, localPage: Boolean): List<Highlight> {
        fun PdfTextBounds.viewerRect() = toPdfRect().let {
            if (localPage) PdfRect(0, it.left, it.top, it.right, it.bottom) else it
        }
        val saved = book.quotes.flatMap { quote ->
            val color = if (quote.color == QuoteColor.AMBER) PDF_YELLOW_HIGHLIGHT_ARGB
                else colors.quoteHighlight(quote.color).toArgb()
            val bounds = quote.pdfSelection.filter { it.pageIndex in visiblePages }
            PdfCapabilities.normalizeBounds(bounds, document.pageCount).map {
                Highlight(it.viewerRect(), color)
            }
        }
        val draft = noteDraft?.takeIf { it.quoteId == null && book.quotes.none { quote -> quote.id == it.saveId } }
            ?.bounds.orEmpty().filter { it.pageIndex in visiblePages }.map {
                Highlight(it.viewerRect(), PDF_YELLOW_HIGHLIGHT_ARGB)
            }
        val found = search.matches.flatMapIndexed { index, match -> match.bounds.filter { it.pageIndex in visiblePages }.map {
            Highlight(it.viewerRect(), (if (index == matchIndex) colors.selection else colors.quoteHighlight).toArgb())
        } }
        return saved + draft + found
    }
    LaunchedEffect(book.quotes, noteDraft?.bounds, search.matches, matchIndex, colors, ready, loadedViewer, renderDocument, viewer.firstVisiblePage, viewer.visiblePagesCount) {
        val native = loadedViewer ?: return@LaunchedEffect
        if (singlePage || !ready || !native.isAttachedToWindow || native.pdfDocument !== renderDocument) return@LaunchedEffect
        // The Compose state can already point at the empty replacement during a palette change.
        // Continuous mode also limits overlays to the nearby source pages.
        native.setHighlights(pageHighlights((native.firstVisiblePage - 1).coerceAtLeast(0)..
            (native.firstVisiblePage + native.visiblePagesCount).coerceAtMost(document.pageCount - 1), false))
    }
    LaunchedEffect(search.query) { matchIndex = 0 }
    LaunchedEffect(search.query, search.matches.firstOrNull()) {
        val first = search.matches.firstOrNull()?.bounds?.firstOrNull() ?: return@LaunchedEffect
        matchIndex = 0
        navigate(DocumentLocation.Pdf(first.pageIndex, (first.left + first.right) / 2, (first.top + first.bottom) / 2,
            currentLocation?.zoom ?: 1f))
    }
    val mainActivity = LocalActivity.current as? MainActivity
    val keysEnabled by rememberUpdatedState(app.volumeKeyPaging && interactionsEnabled && restored && ready && viewportInitialized && rawSelection == null && !noteOpen)
    val currentTurnPage by rememberUpdatedState(::turnPage)
    DisposableEffect(mainActivity) {
        val handler: (Boolean) -> Boolean = handler@{ forward ->
            if (!keysEnabled) return@handler false
            currentTurnPage(if (forward) 1 else -1)
            true
        }
        mainActivity?.volumeKeyHandler = handler
        onDispose { if (mainActivity?.volumeKeyHandler == handler) mainActivity.volumeKeyHandler = null }
    }
    BackHandler(rawSelection != null) { viewer.clearCurrentSelection() }

    val controlsLabel = stringResource(R.string.pdf_show_controls)
    val previousLabel = stringResource(R.string.pdf_previous_page)
    val nextLabel = stringResource(R.string.pdf_next_page)
    val noteOpenLabel = stringResource(R.string.pdf_note_open, "%s")
    val pageFitsHorizontally by remember(viewer, pageWidth, size, fallbackFitZoom) {
        derivedStateOf {
            if (pageWidth > 0) viewer.zoom * pageWidth <= size.width * 1.02f
            else viewer.zoom <= fallbackFitZoom * 1.02f
        }
    }
    var pressedNoteId by remember { mutableStateOf<String?>(null) }
    val chromeVisible = (chrome || searchVisible) && rawSelection == null
    val chromeMotion = remember { PdfChromeMotion(chromeVisible) }
    val backdrop = rememberPdfChromeBackdrop(
        appearance = app.pdfToolbarAppearance,
        // Keep the PDF source while the panels are still drawing their exit animation.
        enabled = (chromeVisible || chromeMotion.mounted) && !fastScrolling,
    )
    fun noteAtScreen(screenOffset: Offset): Quote? {
        val native = currentViewport.nativeView ?: return null
        if (!interactionsEnabled || drawerOpen || !restored) return null
        val origin = IntArray(2)
        native.getLocationOnScreen(origin)
        val point = currentViewport.viewer.visibleOffsetToPdfPoint(
            screenOffset - Offset(origin[0].toFloat(), origin[1].toFloat())) ?: return null
        return pdfNoteAt(book.quotes, if (singlePage) singlePageIndex else point.pageNum, point.x, point.y)
    }
    Box(Modifier.fillMaxSize().background(background)) {
        val viewerModifier = Modifier.fillMaxSize().then(contentMotion)
            .pdfChromeSource(backdrop, background)
            .semantics { customActions = buildList {
                add(CustomAccessibilityAction(controlsLabel) { if (!chrome) currentToggle(); true })
                if (singlePage && page > 0) add(CustomAccessibilityAction(previousLabel) { turnPage(-1); true })
                if (singlePage && page < document.pageCount - 1) add(CustomAccessibilityAction(nextLabel) { turnPage(1); true })
                book.quotes.filter { !it.note.isNullOrBlank() && it.pdfSelection.any { bound -> bound.pageIndex == page } }
                    .forEach { quote ->
                        add(CustomAccessibilityAction(noteOpenLabel.replace("%s", quote.text.take(80))) {
                            viewedNoteId = quote.id
                            true
                        })
                    }
            } }
            .pdfSinglePageDrag(
                pager = pager,
                canShowPage = ::canShowPage,
                enabled = singlePage && interactionsEnabled && restored && !drawerOpen && !noteOpen &&
                    rawSelection == null && (!viewportInitialized || pageFitsHorizontally),
                onGrab = {
                    pageTurnJob?.cancel()
                    adoptPage(pager.currentPage)
                },
                onDragging = { draggingPage = it },
                onRelease = ::goToPage,
            )
            .pdfChromeTapObserver(
                selectionActive = rawSelection != null || draggingPage || noteOpen,
                onPointerDown = { pointerDown = it },
                onPress = { pressedNoteId = noteAtScreen(it)?.id },
                onTap = { screenOffset ->
                    val note = noteAtScreen(screenOffset)
                    if (note != null) viewedNoteId = note.id
                    note != null
                },
                onToggle = toggleChrome,
            )
        fun openLink(uri: android.net.Uri, pageDocument: PdfDocument): Boolean {
            // Native link recognition and our tap observer can confirm the same press in either
            // order. A note on linked text must open its note sheet without also leaving the page.
            pressedNoteId?.let { viewedNoteId = it; pressedNoteId = null; return true }
            val destination = (pageDocument as? SinglePagePdfDocument)?.resolveInternalLink(uri)
            return if (destination != null) {
                scope.launch { navigate(DocumentLocation.Pdf(destination.pageNumber, destination.xCoordinate,
                    destination.yCoordinate, destination.zoom.takeIf { it > 0f } ?: viewer.zoom)) }
                true
            } else uri.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")
        }
        if (!retrying) {
            if (singlePage) {
                // PDF page order is physical and does not reverse with the UI language.
                CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    HorizontalPager(
                        state = pager, beyondViewportPageCount = PdfPrefetchPages, userScrollEnabled = false,
                        modifier = viewerModifier.onSizeChanged { size = it },
                        key = { it },
                    ) { index ->
                        // Compose may prefetch beyond the requested window. Keep that extra
                        // composition lightweight instead of opening another native renderer.
                        if (kotlin.math.abs(index - pager.currentPage) > PdfPrefetchPages) {
                            Spacer(Modifier.fillMaxSize())
                            return@HorizontalPager
                        }
                        key(pageViewports, index) {
                            val sheet = pageViewports.page(index)
                            DisposableEffect(sheet) {
                                pageViewports.retain(index, sheet)
                                onDispose { pageViewports.release(index, sheet) }
                            }
                            LaunchedEffect(sheet, sheet.initialized, sheet.nativeView, book.quotes, noteDraft?.bounds, search.matches, matchIndex, colors) {
                                val native = sheet.nativeView
                                if (sheet.initialized && native?.isAttachedToWindow == true && native.pdfDocument === sheet.document) {
                                    native.setHighlights(pageHighlights(index..index, true))
                                }
                            }
                            PdfPageViewer(sheet, colors, size, Modifier.fillMaxSize()) { openLink(it, sheet.document) }
                        }
                    }
                }
            } else key(renderDocument) {
                PdfPageViewer(viewport, colors, size, viewerModifier.onSizeChanged { size = it }) {
                    openLink(it, renderDocument)
                }
            }
        }
        if (!restored || ((!ready || !viewportInitialized) && (!singlePage || openingSlow))) Box(
            Modifier.fillMaxSize().background(background).pointerInput(Unit) {
                // Do not let a hidden native viewer move away from a pending restore.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }, contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                if (openingSlow) {
                    Text(stringResource(R.string.pdf_opening_slow), Modifier.padding(vertical = 16.dp))
                    Row {
                        TextButton(onClick = { retrying = true }) { Text(stringResource(R.string.reading_log_reader_retry)) }
                        TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back)) }
                    }
                }
            }
        }
        fun moveMatch(delta: Int) {
            if (search.matches.isEmpty()) return
            matchIndex = Math.floorMod(matchIndex + delta, search.matches.size)
            val match = search.matches[matchIndex]
            val rect = match.bounds.firstOrNull() ?: return
            focus.clearFocus()
            keyboard?.hide()
            scope.launch { navigate(DocumentLocation.Pdf(match.page, (rect.left + rect.right) / 2,
                (rect.top + rect.bottom) / 2, currentLocation?.zoom ?: 1f)) }
        }

        PdfChromeOverlay(
            visible = chromeVisible,
            motion = chromeMotion,
            systemBarsVisible = chrome || drawerOpen || panel != 0 || searchVisible,
            keepMounted = fastScrolling,
        ) { progress ->
            var headerHeight by remember { mutableIntStateOf(0) }
            var footerHeight by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            val clearance = with(density) { 4.dp.roundToPx() }
            loadedViewer?.takeUnless { singlePage }?.let { native ->
                DisposableEffect(native) {
                    onDispose { native.setFastScrollTrackInsets(0, 0) }
                }
                LaunchedEffect(native, clearance, searchVisible, app.showPdfTopBar) {
                    snapshotFlow {
                        val shown = progress.value
                        val headerShown = if (fastScrolling) 0f else
                            shown - if (searchVisible) 0f else panelFraction.floatValue * 1.25f
                        val top = if (app.showPdfTopBar) {
                            (headerHeight * headerShown).roundToInt().coerceAtLeast(0)
                        } else 0
                        val footerShown = if (fastScrolling) 1f else shown
                        val bottom = (footerHeight * footerShown).roundToInt()
                        (if (top > 0) top + clearance else 0) to
                            (if (bottom > 0) bottom + clearance else 0)
                    }.collect { (top, bottom) -> native.setFastScrollTrackInsets(top, bottom) }
                }
            }
            Box(
                Modifier.align(Alignment.TopCenter)
                    .onSizeChanged { headerHeight = it.height }
                    .graphicsLayer {
                        val shown = if (fastScrolling) 0f else progress.value
                        translationY = -this.size.height * (1f - shown)
                        alpha = if (fastScrolling) 0f else 1f
                    },
            ) {
                val bookmarked = book.bookmarks.any {
                    (it.documentLocation as? DocumentLocation.Pdf)?.pageIndex == page && !it.orphaned
                }
                PdfBookHeader(
                    title = book.title,
                    author = book.author,
                    colors = colors,
                    appearance = app.pdfToolbarAppearance,
                    backdrop = backdrop,
                    bookmarked = bookmarked,
                    onBack = onBack,
                    onBookmark = model::toggleBookmark,
                    visible = app.showPdfTopBar,
                    modifier = Modifier.graphicsLayer {
                        translationY = if (searchVisible) 0f else -panelFraction.floatValue * size.height * 1.25f
                    },
                )
            }
            Box(Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { footerHeight = it.height }
                .graphicsLayer {
                    val shown = if (fastScrolling) 1f else progress.value
                    translationY = this.size.height * (1f - shown)
                }) {
                PdfBottomBar(
                    colors = colors,
                    appearance = app.pdfToolbarAppearance,
                    backdrop = backdrop,
                    scrubbing = fastScrolling,
                    page = fastScrollTargetPage ?: page,
                    pageCount = document.pageCount,
                    showCollapsedHandle = app.showCollapsedReaderHandle,
                    expandOnSwipe = app.expandReaderPanelsOnSwipe,
                    showFinish = (if (singlePage) page == document.pageCount - 1 else
                        viewer.firstVisiblePage + viewer.visiblePagesCount >= document.pageCount) &&
                        book.status == ReadingStatus.READING && !book.hasFinishedReading,
                    onFinish = model::finishReading,
                    onPageIndicator = {
                        searchMode = PdfSearchMode.PAGE
                        viewer.clearCurrentSelection()
                        setSearchVisible(true)
                    },
                    onSearch = {
                        searchMode = PdfSearchMode.TEXT
                        viewer.clearCurrentSelection()
                        setSearchVisible(true)
                    },
                    onPanelOpenChange = onDrawerOpenChange,
                    panelFraction = panelFraction,
                    closeRequest = closeDrawerRequest,
                    searchVisible = searchVisible,
                    searchContent = { panelColors ->
                        PdfSearchContent(
                            colors = panelColors,
                            active = searchVisible,
                            mode = searchMode,
                            onModeChange = { searchMode = it },
                            query = query,
                            onQueryChange = model::setSearchInput,
                            textStatus = when {
                                !textAvailable -> stringResource(R.string.pdf_text_unavailable)
                                search.error -> stringResource(R.string.pdf_search_error)
                                search.searching -> stringResource(R.string.pdf_searching, search.matches.size)
                                search.matches.isEmpty() -> stringResource(R.string.pdf_search_empty)
                                else -> stringResource(R.string.pdf_search_count, (matchIndex + 1).coerceAtMost(search.matches.size), search.matches.size) + if (search.limited) "+" else ""
                            },
                            hasMatches = search.matches.isNotEmpty(),
                            onPrevious = { moveMatch(-1) },
                            onNext = { moveMatch(1) },
                            currentPage = page,
                            pageCount = document.pageCount,
                            onGoToPage = { target ->
                                scope.launch {
                                    goToPage(target)
                                }
                            },
                            onClose = ::closeSearch,
                        )
                    },
                ) { currentPanel, panelColors ->
                    when (currentPanel) {
                        PdfBarPanel.CONTENTS -> PdfContentsPanel(
                            bookId = book.id,
                            pageCount = document.pageCount,
                            currentPage = page,
                            colors = panelColors,
                            onPageNavigate = { target ->
                                closeDrawerRequest++
                                scope.launch {
                                    goToPage(target)
                                }
                            },
                            outlineState = outline,
                            onLoadOutline = model::loadOutline,
                            onRetryOutline = model::retryOutline,
                        )
                        PdfBarPanel.SAVED -> PdfSavedPanel(
                            book = book,
                            pageCount = document.pageCount,
                            currentPage = page,
                            colors = panelColors,
                            onNavigate = { target ->
                                closeDrawerRequest++
                                scope.launch { navigate(target) }
                            },
                            onBookmark = model::toggleBookmark,
                            onRemoveBookmark = model::removeBookmark,
                            onRemoveQuote = model::removeQuote,
                            onCopy = copy,
                            onQuoteNote = model::setQuoteNote,
                            onQuoteColor = model::setQuoteColor,
                        )
                        PdfBarPanel.SETTINGS -> PdfReaderSettings(
                            colors = panelColors,
                            appSettings = app,
                            settings = readerSettings,
                            onUpdateSettings = model::updateSettings,
                            onUpdateAppSettings = model::updateAppSettings,
                            brightness = brightness,
                            onBrightness = changeBrightness,
                            onBookInfo = { setPanel(3) },
                        )
                    }
                }
            }

        }
        key(viewer) {
            PdfSelectionToolbar(
                selection = selection,
                visible = !pointerDown && viewer.gestureState == PdfViewerState.GESTURE_STATE_IDLE && !searchVisible && panel == 0 && !noteOpen,
                viewer = viewer, viewport = size, colors = colors, quotes = book.quotes,
                onRemoveQuotes = model::removeQuotes, onQuote = model::addQuote,
                onAddNote = { text, bounds -> noteDraft = PdfNoteDraft(text, bounds) },
                copy = copy, translate = translate, define = define, externalSearch = externalSearch,
                sourcePageIndex = singlePageIndex.takeIf { singlePage },
            )
        }
    }
    noteDraft?.let { draft ->
        PdfNoteEditor(draft, colors, saving = pendingNoteId != null, onSave = { note ->
            pendingNoteText = note
            if (draft.quoteId != null) {
                if (book.quotes.any { it.id == draft.quoteId && it.note == note }) noteDraft = null
                else {
                    pendingNoteId = draft.quoteId
                    model.setQuoteNote(draft.quoteId, note)
                }
            } else pendingNoteId = model.addNote(draft.text, draft.bounds, QuoteColor.AMBER, note, id = draft.saveId)
        }, onDismiss = { noteDraft = null; pendingNoteId = null; pendingNoteText = null },
            onDraftChange = { noteDraft = draft.copy(note = it) })
    }
    book.quotes.firstOrNull { it.id == viewedNoteId && !it.note.isNullOrBlank() }?.let { quote ->
        PdfNoteViewer(quote, colors, onEdit = {
            viewedNoteId = null
            noteDraft = PdfNoteDraft(quote.text, quote.pdfSelection, quote.id, quote.note.orEmpty())
        }, onDismiss = { viewedNoteId = null })
    }
}

@Composable
private fun PdfPasswordPrompt(textAvailable: Boolean, onBack: () -> Unit, onUnlock: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pdf_password_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (textAvailable) R.string.pdf_password_message else R.string.pdf_password_unsupported), Modifier.padding(vertical = 12.dp))
        if (textAvailable) {
            OutlinedTextField(password, { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.pdf_password_label)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(onClick = { val submitted = password; password = ""; onUnlock(submitted) }) { Text(stringResource(R.string.pdf_unlock)) }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back)) }
    }
}

private fun PdfTextBounds.toPdfRect() = PdfRect(pageIndex, left, top, right, bottom)

