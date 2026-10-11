package com.example.frogreader.ui.reader

import com.example.frogreader.ui.components.FrogSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import com.example.frogreader.ui.translation.rememberTranslationAction
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.frogreader.MainActivity
import com.example.frogreader.R
import com.example.frogreader.ui.theme.appColorSchemeFor
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.ReaderHighlights
import com.example.frogreader.ui.reader.selection.QuoteHighlight
import com.example.frogreader.ui.reader.selection.quoteHighlights
import com.example.frogreader.ui.reader.selection.SelectionAutoAdvance
import com.example.frogreader.ui.reader.selection.SelectionAutoAdvanceEffect
import com.example.frogreader.ui.reader.selection.SelectionController
import com.example.frogreader.ui.reader.selection.SelectionHandles
import com.example.frogreader.ui.reader.selection.SelectionText
import com.example.frogreader.ui.reader.selection.bookSelectionGestures
import com.example.frogreader.ui.reader.selection.range
import com.example.frogreader.ui.reader.selection.readerHighlights
import com.example.frogreader.ui.reader.selection.rememberReaderHighlights
import com.example.frogreader.ui.reader.selection.rememberTextFragment
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.theme.isDark
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onExitToLibrary: () -> Unit = onBack,
    initialQuoteId: String? = null,
    initialBookmarkId: String? = null,
    contentMotion: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel =
        viewModel(key = "reader-$bookId", factory = ReaderViewModel.factory(bookId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val offerReread by viewModel.offerReread.collectAsStateWithLifecycle()
    val completionVisible by viewModel.completionVisible.collectAsStateWithLifecycle()
    val readingGeneration by viewModel.readingGeneration.collectAsStateWithLifecycle()
    val restartingReading by viewModel.restartingReading.collectAsStateWithLifecycle()
    val resumingReading by viewModel.resumingReading.collectAsStateWithLifecycle()
    val rereadError by viewModel.rereadError.collectAsStateWithLifecycle()
    val readingSaveError by viewModel.readingSaveError.collectAsStateWithLifecycle()
    var rereadChoiceHandled by rememberSaveable(bookId) { mutableStateOf(false) }
    LaunchedEffect(initialQuoteId, initialBookmarkId, offerReread, rereadChoiceHandled) {
        if (initialQuoteId != null || initialBookmarkId != null || rereadChoiceHandled) viewModel.keepPreviousReading()
    }

    // A book opens onto its text, not onto its menus.
    //
    // Owned HERE rather than in ReaderContent for two reasons. It survives the
    // Loading→Ready swap — ReaderContent is only composed once the book is
    // parsed, so its rememberSaveable was being recreated on every open. And
    // the system bars now start hiding on the reader's very first frame:
    // driven from ReaderContent they only went away once the text appeared,
    // leaving the status and navigation bars sitting through the whole opening
    // and then sliding out as a second, separate step.
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val readerTheme = appSettings.effectiveTheme(systemDark)
    val readerAppSettings = remember(appSettings, readerTheme) { appSettings.copy(theme = readerTheme) }
    SystemBarsEffect(readerTheme, chromeVisible || completionVisible)
    val opening = rememberReaderOpeningMotion(bookId)

    Box(Modifier.fillMaxSize().background(readerColors(readerTheme).background)) {
        when (val current = state) {
            ReaderState.Loading -> Unit

            ReaderState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.reader_error_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.reader_error_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back)) }
                }
            }

            is ReaderState.Ready -> key(readingGeneration) {
                ReaderContent(
                    ready = current,
                    initialQuoteId = initialQuoteId,
                    initialBookmarkId = initialBookmarkId,
                    settings = settings,
                    viewModel = viewModel,
                    appSettings = readerAppSettings,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onShowChrome = { chromeVisible = true },
                    onBack = onBack,
                    onExitToLibrary = onExitToLibrary,
                    opening = opening,
                    contentMotion = contentMotion,
                )
            }
        }
        if (state != ReaderState.Error) {
            // One loader stays composed through parsing, pagination and the entrance.
            if (state == ReaderState.Loading && opening.finished) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                ReaderOpeningOverlay(opening)
            }
        }
    }
    (state as? ReaderState.Ready)?.let { ready ->
        ReaderRereadDialog(
            visible = offerReread && !rereadChoiceHandled && opening.finished &&
                initialQuoteId == null && initialBookmarkId == null,
            book = ready.book,
            cover = viewModel.coverFileFor(ready.book),
            busy = restartingReading,
            resuming = resumingReading,
            error = rereadError,
            onBrowse = {
                if (viewModel.keepPreviousReading()) {
                    rereadChoiceHandled = true
                }
            },
            onReadAgain = viewModel::beginReadingAgain,
            onResume = viewModel::resumeReading,
        )
    }
    if (readingSaveError) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReadingSaveError,
            containerColor = MaterialTheme.colorScheme.floatingSurface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.reading_log_reader_save_error_title)) },
            text = { Text(stringResource(R.string.reading_log_reader_save_error)) },
            confirmButton = {
                TextButton(onClick = viewModel::retryReadingSave) { Text(stringResource(R.string.reading_log_reader_retry)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReadingSaveError) { Text(stringResource(R.string.metadata_close)) }
            },
        )
    }
}

private data class VisibleNote(
    val key: String,
    val document: NoteDocument,
    val itemIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** Main/linked surface that remains underneath this transient sheet. */
    val underlay: ReaderReturnLocation,
    val contextualReturn: Boolean = false,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    ready: ReaderState.Ready,
    initialQuoteId: String?,
    initialBookmarkId: String?,
    settings: ReaderSettings,
    viewModel: ReaderViewModel,
    appSettings: AppSettings,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onShowChrome: () -> Unit,
    onBack: () -> Unit,
    onExitToLibrary: () -> Unit,
    opening: ReaderOpeningMotion,
    contentMotion: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val resources = LocalResources.current

    val chromeScheme = appColorSchemeFor(appSettings.theme, appSettings.dynamicColor)
    val colors = remember(appSettings.theme, chromeScheme) {
        readerColors(
            theme = appSettings.theme,
            chromeScheme = chromeScheme,
        )
    }
    val liveBook by viewModel.book.collectAsStateWithLifecycle()

    // How far the settings drawer is pulled up (0 closed → 1 half-open and
    // beyond). The top bar slides out along this SAME value, so both move in
    // perfect sync with the drawer's animation.
    val panelFraction = remember { mutableFloatStateOf(0f) }

    // Live font size for pinch-to-zoom; committed to settings on gesture end.
    var liveFontSize by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }

    // Reading position shared between modes and with the chrome.
    val displayedIndex = remember { mutableIntStateOf(viewModel.currentFlatIndex) }
    var seekTarget by remember { mutableStateOf<Int?>(null) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    // Character-precise jump (item, char) — search results land on the exact
    // page even when the paragraph spans several pages.
    var seekPosition by remember { mutableStateOf<ReaderReturnLocation.Main?>(null) }
    var animateChapterSeek by remember { mutableStateOf(false) }
    val latestReadingMode by rememberUpdatedState(settings.readingMode)
    var initialQuoteOrBookmarkConsumed by rememberSaveable(ready.book.id, initialQuoteId, initialBookmarkId) {
        mutableStateOf(false)
    }
    fun consumePositionSeek(consumed: ReaderReturnLocation.Main) {
        // A completed animation must not clear a newer tap awaiting its frame.
        if (seekPosition != consumed) return
        if (animateChapterSeek) displayedIndex.intValue = consumed.flatItemIndex
        seekPosition = null
        animateChapterSeek = false
        initialQuoteOrBookmarkConsumed = true
    }
    fun interruptChapterSeek(
        interrupted: ReaderReturnLocation.Main,
        mode: ReadingMode,
        layoutChanged: Boolean = false,
    ) {
        if (seekPosition != interrupted || !animateChapterSeek) return
        // Layout/mode rebuilds must retry the anchor in the new reader. A
        // gesture interrupting the same reader instead takes over immediately.
        if (layoutChanged || latestReadingMode != mode) return
        seekPosition = null
        animateChapterSeek = false
    }

    // Parsing finishes before pagination and saved-anchor restoration. The
    // screen's persistent loader waits for both before handing over to text.
    var readingSurfaceReady by remember(ready.book.id) { mutableStateOf(false) }
    val openingPositionReady = readingSurfaceReady &&
        (initialQuoteId == null && initialBookmarkId == null || initialQuoteOrBookmarkConsumed) &&
        seekTarget == null && seekFraction == null && seekPosition == null
    LaunchedEffect(openingPositionReady) {
        if (openingPositionReady) opening.onContentReady()
    }
    // EPUB spine `linear="no"` content is a transient linked surface. It is
    // deliberately not inserted into the main item list, so opening it cannot
    // change reading progress or make it reachable by ordinary page turns.
    var linkedDocumentId by rememberSaveable(ready.book.id) { mutableStateOf<String?>(null) }
    var linkedDocumentItem by rememberSaveable(ready.book.id) { mutableIntStateOf(0) }
    var linkedDocumentScrollOffset by rememberSaveable(ready.book.id) { mutableIntStateOf(0) }
    var linkedDocumentSeek by remember { mutableStateOf<ReaderReturnLocation.Linked?>(null) }
    LaunchedEffect(ready) {
        val id = linkedDocumentId
        if (id != null && id !in ready.linkedDocuments) {
            linkedDocumentId = null
            linkedDocumentItem = 0
            linkedDocumentScrollOffset = 0
            linkedDocumentSeek = null
        }
    }

    // Book search: the query survives closing the overlay, the term found
    // last stays highlighted on the page until the reader taps the text.
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchHighlight by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchQuery) {
        delay(150.milliseconds) // debounce typing; the scan itself is fast
        viewModel.search(searchQuery)
    }

    // (current page, total pages) — set by paged mode for true page progress.
    val pagePosition = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Continuous positions: fractional page while a swipe is in flight, and
    // fractional element while scrolling — the progress bars glide along
    // with the finger instead of jumping when a page/paragraph settles.
    val livePagePosition = remember { mutableStateOf<Float?>(null) }
    val liveScrollPosition = remember { mutableStateOf<Float?>(null) }

    val pinchHandlers = PinchHandlers(
        onPinch = { zoom -> liveFontSize = (liveFontSize * zoom).coerceIn(12f, 32f) },
        onPinchEnd = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            val committed = liveFontSize.roundToInt().toFloat()
            viewModel.updateSettings { it.copy(fontSizeSp = committed) }
        },
    )

    // Screen brightness: vertical drag along the left edge of the screen.
    val activity = LocalActivity.current
    var brightness by remember { mutableStateOf<Float?>(null) }
    var brightnessVisibleUntil by remember { mutableLongStateOf(0L) }
    // Shared by the edge gesture and the settings panel; null = system level.
    val setBrightness: (Float?) -> Unit = { value ->
        activity?.window?.let { window ->
            brightness = value
            window.attributes = window.attributes.apply {
                screenBrightness = value
                    ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    val brightnessHandlers = remember(activity) {
        BrightnessHandlers { delta ->
            val window = activity?.window ?: return@BrightnessHandlers
            val current = brightness
                ?: window.attributes.screenBrightness.takeIf { it >= 0f }
                ?: 0.5f
            // Drag in perceptual space (see brightnessPosition) so the dim
            // range is controllable and the real minimum is reachable.
            val updated = brightnessFromPosition(brightnessPosition(current) + delta)
            brightness = updated
            window.attributes = window.attributes.apply { screenBrightness = updated }
            brightnessVisibleUntil = System.currentTimeMillis() + 900
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // Keep the screen awake while reading (app setting).
    DisposableEffect(activity, appSettings.keepScreenOn) {
        val window = activity?.window
        if (appSettings.keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Volume-key page turning: the activity forwards key presses into this
    // flow. A flow (not a state read by a LaunchedEffect key) so consuming an
    // event never restarts the effect and cancels the scroll it just started.
    val turnEvents = remember { MutableSharedFlow<Boolean>(extraBufferCapacity = 4) }
    val mainActivity = activity as? MainActivity
    DisposableEffect(mainActivity) {
        val handler: (Boolean) -> Boolean = handler@{ forward ->
            if (!viewModel.appSettings.value.volumeKeyPaging) return@handler false
            if (viewModel.completionVisible.value) return@handler false
            if (searchVisible) return@handler false // search overlay is up
            if (linkedDocumentId != null) return@handler false // never turn the hidden main book
            turnEvents.tryEmit(forward)
        }
        mainActivity?.volumeKeyHandler = handler
        onDispose {
            if (mainActivity?.volumeKeyHandler == handler) mainActivity.volumeKeyHandler = null
        }
    }

    val context = LocalContext.current
    var diagnosticViewport by remember { mutableStateOf(IntSize.Zero) }
    CaptureReaderDiagnostics(ready, viewModel, settings, appSettings, liveFontSize, diagnosticViewport)

    // Selection in book coordinates — see ui/reader/selection. It lives here,
    // above both reading modes, so a page turn or a scroll never disturbs it.
    val selection = remember { SelectionController() }
    selection.textAt = { index ->
        ready.items.getOrNull(index)?.element?.let(SelectionText::elementText)
    }
    fun selectedText(): String =
        selection.selectedText(ready.items.size) { ready.items[it].element }

    // A rebuilt item list (the hide-footnotes toggle rewrites element texts)
    // moves every character offset in the book, so old anchors mean nothing.
    LaunchedEffect(ready) { selection.clear() }
    // Switching between scroll and pages keeps the reading position but not
    // necessarily the view of the selection; carrying it over is confusing.
    LaunchedEffect(settings.readingMode) { selection.clear() }
    // Tappable footnote references ([53] → bottom sheet with the note).
    var noteToShow by remember { mutableStateOf<VisibleNote?>(null) }
    val completionVisible by viewModel.completionVisible.collectAsStateWithLifecycle()
    val lastPageVisible by viewModel.lastPageVisible.collectAsStateWithLifecycle()
    val offerReread by viewModel.offerReread.collectAsStateWithLifecycle()
    val readingSaveError by viewModel.readingSaveError.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var seekCompletion by remember { mutableStateOf(false) }
    val reviewKeyboardVisible = completionVisible && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var topChromeHeight by remember { mutableIntStateOf(0) }
    val completionPadding = WindowInsets.systemBarsIgnoringVisibility.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
    ).asPaddingValues()
    val reviewKeyboard = LocalSoftwareKeyboardController.current
    val reviewFocus = LocalFocusManager.current
    fun saveReviewAndExit() {
        reviewFocus.clearFocus()
        reviewKeyboard?.hide()
        viewModel.saveCompletionReview(onExitToLibrary)
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    viewModel.setReadingActive(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setReadingActive(false)
        }
    }
    val readingActive = opening.finished && resumed && !completionVisible && !offerReread && !seekCompletion && !readingSaveError &&
        !searchVisible && noteToShow == null && panelFraction.floatValue <= 0.01f
    LaunchedEffect(readingActive) { viewModel.setReadingActive(readingActive) }
    fun openReview() {
        selection.clear()
        seekCompletion = true
        viewModel.setReadingActive(false)
    }
    LaunchedEffect(completionVisible) {
        if (completionVisible) {
            selection.clear()
            searchVisible = false
            noteToShow = null
        }
    }
    BackHandler(enabled = completionVisible || seekCompletion) {
        if (reviewKeyboardVisible) {
            reviewFocus.clearFocus()
            reviewKeyboard?.hide()
        } else saveReviewAndExit()
    }
    val navigationReturnLocation by viewModel.navigationReturnLocation.collectAsStateWithLifecycle()
    val returnTimeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = ReaderNavigationSession.DEFAULT_TIMEOUT_MILLIS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: ReaderNavigationSession.DEFAULT_TIMEOUT_MILLIS
    LaunchedEffect(returnTimeout) { viewModel.setReturnTimeoutMillis(returnTimeout) }
    val navigationBackAvailable = navigationReturnLocation != null
    val snackbar = remember { SnackbarHostState() }
    val translateSelection = rememberTranslationAction(
        defaultTranslator = appSettings.defaultTranslator,
        saveDefault = viewModel::setDefaultTranslator,
        snackbar = snackbar,
    )
    val pagination by viewModel.pagination.collectAsStateWithLifecycle()
    val returnPageNumber = if (settings.readingMode == ReadingMode.PAGES) {
        val origin = navigationReturnLocation as? ReaderReturnLocation.Main
        val pages = pagination?.takeIf { !it.partial }?.pages
        if (origin != null && pages != null) {
            ReaderNavigationPolicy.pageIndexForLocation(pages, origin)?.plus(1)
        } else null
    } else null
    val currentReturnLabel = returnPageNumber?.let { stringResource(R.string.reader_return_page, it) }
        ?: stringResource(R.string.reader_return)
    // The final pop clears the destination before AnimatedVisibility finishes
    // its exit. Keep the last real label until that button leaves the screen.
    var returnLabel by remember { mutableStateOf(currentReturnLabel) }
    if (navigationBackAvailable) returnLabel = currentReturnLabel
    val readerDensity = LocalDensity.current
    val readingTopReferencePx = with(readerDensity) {
        (WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
            .calculateTopPadding() + 20.dp).toPx()
    }

    fun currentBookSurfaceLocation(): ReaderReturnLocation {
        val documentId = linkedDocumentId
        return if (documentId != null) {
            ReaderReturnLocation.Linked(
                documentId = documentId,
                itemIndex = linkedDocumentItem,
                scrollOffset = linkedDocumentScrollOffset,
            )
        } else {
            val logicalAnchor = if (settings.readingMode == ReadingMode.SCROLL) {
                selection.spaceSize?.let { size ->
                    selection.anchorAt(
                        point = Offset(size.width / 2f, readingTopReferencePx),
                        maxVerticalGap = with(readerDensity) { 96.dp.toPx() },
                    )
                }?.takeIf { it.itemIndex == viewModel.currentFlatIndex }
            } else {
                null
            }
            ReaderReturnLocation.Main(
                flatItemIndex = viewModel.currentFlatIndex,
                charOffset = if (settings.readingMode == ReadingMode.PAGES) {
                    viewModel.currentCharOffset
                } else {
                    logicalAnchor?.charOffset
                },
                scrollOffset = viewModel.currentScrollOffset,
            )
        }
    }

    fun currentReturnLocation(): ReaderReturnLocation = noteToShow?.let { visible ->
        ReaderReturnLocation.Note(
            noteKey = visible.key,
            itemIndex = visible.itemIndex,
            scrollOffset = visible.scrollOffset,
            underlay = visible.underlay,
            contextualReturn = visible.contextualReturn,
        )
    } ?: currentBookSurfaceLocation()

    fun restoreReturnLocation(location: ReaderReturnLocation) {
        animateChapterSeek = false
        noteToShow = null
        selection.clear()
        fun restoreBookSurface(surface: ReaderReturnLocation): Boolean = when (surface) {
            is ReaderReturnLocation.Main -> {
                linkedDocumentId = null
                linkedDocumentItem = 0
                linkedDocumentScrollOffset = 0
                linkedDocumentSeek = null
                seekPosition = surface
                true
            }

            is ReaderReturnLocation.Linked -> {
                if (surface.documentId !in ready.linkedDocuments) {
                    false
                } else {
                    linkedDocumentId = surface.documentId
                    linkedDocumentItem = surface.itemIndex
                    linkedDocumentScrollOffset = surface.scrollOffset
                    linkedDocumentSeek = surface
                    true
                }
            }

            is ReaderReturnLocation.Note -> restoreBookSurface(surface.underlay)
        }
        when (location) {
            is ReaderReturnLocation.Main,
            is ReaderReturnLocation.Linked -> restoreBookSurface(location)

            is ReaderReturnLocation.Note -> {
                if (!restoreBookSurface(location.underlay)) return
                val note = ready.notes[location.noteKey] ?: return
                noteToShow = VisibleNote(
                    key = location.noteKey,
                    document = note,
                    itemIndex = location.itemIndex,
                    scrollOffset = location.scrollOffset,
                    underlay = location.underlay,
                    contextualReturn = location.contextualReturn,
                )
            }
        }
    }

    fun navigateWithHistory(target: ReaderReturnLocation) {
        val origin = currentReturnLocation()
        if (origin == target) return
        viewModel.rememberNavigationOrigin(
            origin, expires = origin is ReaderReturnLocation.Main && target is ReaderReturnLocation.Main,
        )
        restoreReturnLocation(target)
        if (target is ReaderReturnLocation.Main) onShowChrome()
    }

    fun navigateBackInBook() {
        val target = viewModel.takeNavigationOrigin() ?: return
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        restoreReturnLocation(target)
        if (target is ReaderReturnLocation.Main) onShowChrome()
    }

    LaunchedEffect(ready.book.id, initialQuoteId, initialBookmarkId) {
        if (!initialQuoteOrBookmarkConsumed && (initialQuoteId != null || initialBookmarkId != null)) {
            val target = quoteOrBookmarkTarget(liveBook ?: ready.book, initialQuoteId, initialBookmarkId, ready.items.size)
            if (target == null) {
                initialQuoteOrBookmarkConsumed = true
                snackbar.showSnackbar(resources.getString(R.string.quotes_bookmarks_target_unavailable), duration = SnackbarDuration.Long)
            } else {
                val origin = currentReturnLocation()
                if (origin != target) viewModel.rememberNavigationOrigin(origin, expires = true)
                restoreReturnLocation(target)
                onShowChrome()
            }
        }
    }

    BackHandler(enabled = selection.active) { selection.clear() }
    // History belongs to the explicit return control. Android Back in the
    // main text must reach the app navigator, even after many in-book jumps.
    BackHandler(enabled = !selection.active && linkedDocumentId != null) {
        if (navigationBackAvailable) {
            navigateBackInBook()
        } else {
            linkedDocumentId = null
            linkedDocumentItem = 0
            linkedDocumentScrollOffset = 0
            linkedDocumentSeek = null
        }
    }
    // Turning "hide footnotes" on removes both their markers and any
    // transient note surface that was already open.
    LaunchedEffect(ready.notes) {
        if (ready.notes.isEmpty()) noteToShow = null
    }
    // Saved quotes, resolved to book coordinates — painted by the same path
    // machinery as the live selection, so each one marks exactly its own text.
    val quoteRanges = remember(liveBook) {
        quoteHighlights(liveBook?.quotes.orEmpty())
    }
    // RenderPart keeps one stable handler for the current immutable item stream,
    // but every action must call the latest composition. In particular the
    // reading mode and safe-top reference can change without rebuilding Ready;
    // retaining the original lambdas made Back capture a page/scroll origin
    // using stale coordinates after such a change.
    val latestOnNote = rememberUpdatedState<(String, NoteDocument) -> Unit> { key, note ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        val previous = noteToShow
        if (previous != null) {
            viewModel.rememberNavigationOrigin(currentReturnLocation())
        }
        noteToShow = VisibleNote(
            key = key,
            document = note,
            underlay = previous?.underlay ?: currentBookSurfaceLocation(),
            contextualReturn = previous != null,
        )
    }
    val latestOnNavigate = rememberUpdatedState<(Int) -> Unit> { itemIndex ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        navigateWithHistory(ReaderReturnLocation.Main(itemIndex))
    }
    val latestOnNavigateLinked = rememberUpdatedState<(LinkedDocumentTarget) -> Unit> { target ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        navigateWithHistory(
            ReaderReturnLocation.Linked(
                documentId = target.documentId,
                itemIndex = target.elementIndex,
            ),
        )
    }
    val footnotes = remember(ready) {
        if (ready.notes.isEmpty() && ready.linkTargets.isEmpty() &&
            ready.linkedDocumentTargets.isEmpty()
        ) {
            null
        } else {
            FootnoteHandler(
                notes = ready.notes,
                onNote = { key, note -> latestOnNote.value(key, note) },
                linkTargets = ready.linkTargets,
                onNavigate = { itemIndex -> latestOnNavigate.value(itemIndex) },
                linkedDocumentTargets = ready.linkedDocumentTargets,
                onNavigateLinked = { target -> latestOnNavigateLinked.value(target) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Navigation fades the full page; only its foreground shrinks.
            // Keeping paper outside the scale avoids a detached rectangular sheet.
            .background(colors.background)
            .then(contentMotion)
            .readerOpeningReveal(opening)
            .onSizeChanged { diagnosticViewport = it }
            .then(if (opening.finished) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        // The selection's coordinate space: every anchor, handle and hit test
        // is expressed relative to this box, and its gesture detector is the
        // one that owns text selection now.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { selection.space = it }
                .pointerInput(selection, completionVisible) {
                    if (!completionVisible) bookSelectionGestures(selection, haptics)
                }
                // The loupe over the character being chosen — a fingertip
                // covers about three words at reading size. The platform
                // magnifier is API 28+; Compose guards older devices and
                // applies the stronger zoom only where supported (API 29+).
                .magnifier(sourceCenter = { selection.magnifierSource() }, zoom = 1.6f),
        ) {
            when (settings.readingMode) {
                ReadingMode.SCROLL -> ScrollReader(
                    ready = ready,
                    settings = settings,
                    appSettings = appSettings,
                    fontSize = liveFontSize,
                    colors = colors,
                    viewModel = viewModel,
                    displayedIndex = displayedIndex,
                    livePosition = liveScrollPosition,
                    seekTarget = seekTarget,
                    onSeekConsumed = { seekTarget = null },
                    seekFraction = seekFraction,
                    onSeekFractionConsumed = { seekFraction = null },
                    seekPosition = seekPosition,
                    animateSeekPosition = animateChapterSeek,
                    onSeekPositionConsumed = ::consumePositionSeek,
                    onChapterSeekInterrupted = { interrupted ->
                        interruptChapterSeek(interrupted, ReadingMode.SCROLL)
                    },
                    onToggleChrome = {
                        onToggleChrome()
                        searchHighlight = null
                    },
                    searchHighlight = searchHighlight,
                    pinchHandlers = pinchHandlers,
                    brightnessHandlers = brightnessHandlers,
                    turnEvents = turnEvents,
                    footnotes = footnotes,
                    quoteRanges = quoteRanges,
                    selection = selection,
                    seekCompletion = seekCompletion,
                    onSeekCompletionConsumed = { seekCompletion = false },
                    completionPadding = completionPadding,
                    onSaveReview = ::saveReviewAndExit,
                    onExitReview = ::saveReviewAndExit,
                    onContentReady = { readingSurfaceReady = true },
                )

                ReadingMode.PAGES -> PagedReader(
                    ready = ready,
                    settings = settings,
                    appSettings = appSettings,
                    colors = colors,
                    viewModel = viewModel,
                    displayedIndex = displayedIndex,
                    pagePosition = pagePosition,
                    livePagePosition = livePagePosition,
                    seekTarget = seekTarget,
                    onSeekConsumed = { seekTarget = null },
                    seekFraction = seekFraction,
                    onSeekFractionConsumed = { seekFraction = null },
                    seekPosition = seekPosition,
                    animateSeekPosition = animateChapterSeek,
                    onSeekPositionConsumed = ::consumePositionSeek,
                    onChapterSeekInterrupted = { interrupted, layoutChanged ->
                        interruptChapterSeek(interrupted, ReadingMode.PAGES, layoutChanged)
                    },
                    onToggleChrome = {
                        onToggleChrome()
                        searchHighlight = null
                    },
                    searchHighlight = searchHighlight,
                    pinchHandlers = pinchHandlers,
                    brightnessHandlers = brightnessHandlers,
                    turnEvents = turnEvents,
                    footnotes = footnotes,
                    quoteRanges = quoteRanges,
                    selection = selection,
                    seekCompletion = seekCompletion,
                    onSeekCompletionConsumed = { seekCompletion = false },
                    completionPadding = completionPadding,
                    onSaveReview = ::saveReviewAndExit,
                    onExitReview = ::saveReviewAndExit,
                    onContentReady = { readingSurfaceReady = true },
                )
            }

            SelectionAutoAdvanceEffect(selection)
            if (settings.readingMode == ReadingMode.SCROLL) {
                SelectionHandles(selection, colors.accent)
            }
        }

        // Outside the reading box on purpose: it carries the selection
        // gesture, which swallows taps to dismiss the selection, and would
        // swallow taps on these buttons too. Declared after it, so it is
        // hit-tested first and drawn above the page.
        SelectionToolbar(
            controller = selection,
            colors = colors,
            quotes = liveBook?.quotes.orEmpty(),
            onRemoveQuotes = { quoteIds ->
                if (quoteIds.isNotEmpty()) {
                    viewModel.removeQuotes(quoteIds)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                selection.clear()
            },
            onQuote = { color ->
                val selected = selectedText()
                val range = selection.selection
                if (selected.isNotEmpty() && range != null) {
                    // The chapter of the SELECTION, not of the page being
                    // shown: an auto-turning drag can end several chapters
                    // away from where the quote began.
                    viewModel.addQuote(
                        text = selected,
                        chapterIndex = ready.chapterAt(range.start.itemIndex),
                        range = range,
                        color = color,
                    )
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                selection.clear()
            },
            onTranslate = {
                val selected = selectedText()
                if (selected.isNotEmpty()) {
                    translateSelection(selected)
                }
                selection.clear()
            },
            onCopy = {
                val selected = selectedText()
                if (selected.isNotEmpty()) {
                    clipboardScope.launch {
                        clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(ready.book.title, selected)))
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                selection.clear()
            },
        )

        BrightnessOverlay(
            brightness = brightness,
            visibleUntil = brightnessVisibleUntil,
            colors = colors,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        val bookmarked = liveBook?.bookmarks?.any {
            it.flatIndex in displayedIndex.intValue..(displayedIndex.intValue + viewModel.bookmarkWindow)
        } == true

        val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
        val fullPages = pagination?.takeIf { !it.partial }?.pages
        // The last complete pagination: keeps the "go to page" card in place
        // even during the brief partial re-pagination after a settings change.
        var lastFullPages by remember { mutableStateOf<List<BookPage>?>(null) }
        LaunchedEffect(fullPages) { if (fullPages != null) lastFullPages = fullPages }
        val searchPages = fullPages ?: lastFullPages

        val topBarHeight = with(LocalDensity.current) { topChromeHeight.toDp() }

        ReaderSearchOverlay(
            visible = searchVisible,
            query = searchQuery,
            colors = colors,
            chapterTitles = ready.chapterTitles,
            results = searchResults,
            pages = searchPages,
            onResultClick = { match ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                searchHighlight = match.match
                navigateWithHistory(
                    ReaderReturnLocation.Main(match.itemIndex, charOffset = match.charStart),
                )
                searchVisible = false
            },
            onGoToPage = { page ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                searchPages?.getOrNull(page)?.let { target ->
                    navigateWithHistory(
                        ReaderReturnLocation.Main(
                            target.firstItemIndex,
                            charOffset = target.firstCharOffset,
                        ),
                    )
                }
                searchVisible = false
            },
            onClose = { searchVisible = false },
            topPadding = topBarHeight,
        )

        ReaderTopBar(
            visible = chromeVisible && !completionVisible && !seekCompletion,
            title = ready.book.title,
            author = ready.book.author,
            colors = colors,
            chromeStyle = appSettings.readerChromeStyle,
            searchVisible = searchVisible,
            searchQuery = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { searchVisible = true },
            onBack = onBack,
            onCloseSearch = { searchVisible = false },
            panelFraction = { panelFraction.floatValue },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { if (it.height > 0) topChromeHeight = it.height },
        )

        // The menu and heading acknowledge the chosen destination on the tap,
        // while actual reading progress still follows the rendered page.
        val pendingChapter = seekPosition?.takeIf { animateChapterSeek }
        val chapterUiItem = pendingChapter?.flatItemIndex ?: displayedIndex.intValue
        val currentChapter = ready.chapterAt(chapterUiItem)
        val chapterContexts = remember(ready) {
            ReaderChapterContexts(
                ready.navigation, ready.chapterStarts, ready.chapterTitles,
                ready.chapterDepths, ready.items.size,
            )
        }
        val chapterContext = remember(chapterContexts, currentChapter, chapterUiItem) {
            chapterContexts.at(chapterUiItem, currentChapter)
        }
        // True page progress in paged mode; element progress when scrolling.
        val pageInfo = pagePosition.value
        val livePage = livePagePosition.value
        // The page starts remain available to the expanded table of contents.
        val chapterStartPages = remember(pagination, ready) {
            pagination?.takeIf { !it.partial }?.pages?.takeIf { it.isNotEmpty() }?.let { pages ->
                ready.chapterStarts.map { start ->
                    ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(start)) ?: 0
                }
            }
        }
        val usePages = pageInfo != null && chapterStartPages != null && settings.readingMode == ReadingMode.PAGES
        val bookFraction = if (usePages) {
            (livePage ?: pageInfo.first.toFloat()) / (pageInfo.second - 1).coerceAtLeast(1)
        } else {
            (liveScrollPosition.value ?: displayedIndex.intValue.toFloat()) / (ready.items.size - 1).coerceAtLeast(1)
        }
        val bookChapterStarts = remember(ready) {
            readerBookChapterStarts(ready.navigation, ready.chapterStarts, ready.chapterDepths, ready.items.size)
        }
        val bookChapterStops = remember(bookChapterStarts, fullPages, usePages, ready.items.size) {
            bookChapterStarts.map { start ->
                if (usePages && fullPages != null) {
                    (ReaderNavigationPolicy.pageIndexForLocation(fullPages, ReaderReturnLocation.Main(start)) ?: 0)
                        .toFloat() / (fullPages.size - 1).coerceAtLeast(1)
                } else start.toFloat() / (ready.items.size - 1).coerceAtLeast(1)
            }
        }

        val chapterAtFraction = remember(
            ready, chapterContexts, usePages, pageInfo, fullPages, chapterStartPages, resources,
            pendingChapter, chapterContext,
        ) {
            { f: Float ->
                if (pendingChapter != null) {
                    ReaderChapterPreview(
                        chapterId = chapterContext.navigationIndex?.let { "nav_$it" } ?: "chap_$currentChapter",
                        label = chapterContext.title
                            ?: resources.getString(R.string.reader_chapter_n, currentChapter + 1),
                        parents = readerVisibleChapterParents(ready.book.title, chapterContext.parents),
                    )
                } else resolveReaderChapterPreview(
                    fraction = f,
                    usePages = usePages,
                    pageCount = if (usePages) pageInfo.second else null,
                    fullPages = fullPages,
                    chapterStartPages = chapterStartPages,
                    chapterStarts = ready.chapterStarts,
                    itemCount = ready.items.size,
                    chapterAtItem = ready::chapterAt,
                    chapterContexts = chapterContexts,
                    bookTitle = ready.book.title,
                    defaultChapterName = { chap -> resources.getString(R.string.reader_chapter_n, chap + 1) },
                )
            }
        }

        fun commitProgressSeek(changed: Boolean, seek: () -> Unit): Boolean {
            if (!changed) return false
            // Capture the exact reading origin immediately before the jump is
            // committed. Starting or cancelling a thumb gesture must not add
            // a phantom browser-history entry.
            viewModel.rememberNavigationOrigin(currentReturnLocation(), expires = true)
            seek()
            return true
        }

        ReaderBottomBar(
            visible = chromeVisible && !completionVisible && !seekCompletion,
            showReturnButton = !completionVisible && (navigationBackAvailable || lastPageVisible) &&
                linkedDocumentId == null && noteToShow == null && !searchVisible && !selection.active,
            returnLabel = if (lastPageVisible) stringResource(R.string.reading_log_reader_review) else returnLabel,
            returnIcon = if (lastPageVisible) Icons.Rounded.Star else Icons.AutoMirrored.Rounded.Undo,
            onReturn = { if (lastPageVisible) openReview() else navigateBackInBook() },
            // The pull-up panel is physically above the selection toolbar.
            // Its later-composed BackHandler must therefore close the panel
            // first; a second Back can clear the still-valid selection.
            allowBackDismiss = !searchVisible && linkedDocumentId == null,
            colors = colors,
            panelFraction = panelFraction,
            chapterStartPages = chapterStartPages,
            bookPageCount = if (usePages) pageInfo.second else null,
            chapterStops = bookChapterStops,
            progressValuesReady = settings.readingMode == ReadingMode.SCROLL || usePages,
            ready = ready,
            book = liveBook,
            currentChapter = currentChapter,
            currentNavigationIndex = chapterContext.navigationIndex,
            chapterParents = readerVisibleChapterParents(ready.book.title, chapterContext.parents),
            chapterLabel = chapterContext.title
                ?: stringResource(R.string.reader_chapter_n, currentChapter + 1),
            bookFraction = bookFraction.coerceIn(0f, 1f),
            chapterAtFraction = chapterAtFraction,
            onSeekBook = { f ->
                if (usePages) {
                    val targetPage = ReaderNavigationPolicy.progressTargetIndex(
                        fraction = f,
                        firstIndex = 0,
                        lastIndexInclusive = pageInfo.second - 1,
                    )
                    val currentPage = (livePage ?: pageInfo.first.toFloat()).roundToInt()
                    commitProgressSeek(targetPage != currentPage) {
                        seekFraction = targetPage.toFloat() /
                            (pageInfo.second - 1).coerceAtLeast(1)
                    }
                } else {
                    val targetItem = ReaderNavigationPolicy.progressTargetIndex(
                        fraction = f,
                        firstIndex = 0,
                        lastIndexInclusive = ready.items.lastIndex,
                    )
                    commitProgressSeek(targetItem != displayedIndex.intValue) {
                        seekTarget = targetItem
                    }
                }
            },
            settings = settings,
            appSettings = appSettings,
            brightness = brightness,
            onUpdate = { transform -> viewModel.updateSettings(transform) },
            onUpdateApp = { transform -> viewModel.updateAppSettings(transform) },
            onBrightnessChange = setBrightness,
            onChapterClick = { target ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                when (target) {
                    is ReaderNavigationTarget.ReadingOrder -> {
                        navigateWithHistory(ReaderReturnLocation.Main(target.flatItemIndex))
                        animateChapterSeek = true
                    }

                    is ReaderNavigationTarget.Linked -> {
                        navigateWithHistory(
                            ReaderReturnLocation.Linked(
                                documentId = target.target.documentId,
                                itemIndex = target.target.elementIndex,
                            ),
                        )
                    }
                }
            },
            onBookmarkClick = { flatIndex ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                navigateWithHistory(ReaderReturnLocation.Main(flatIndex))
            },
            onRemoveBookmark = { viewModel.removeBookmark(it) },
            onCopyQuote = { text ->
                clipboardScope.launch {
                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(ready.book.title, text)))
                }
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            onQuoteClick = { quote ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                // Anchored quotes know exactly where they are; the char-precise
                // seek lands on the right page even mid-paragraph.
                navigateWithHistory(
                    ReaderReturnLocation.Main(quote.startItem, charOffset = quote.startChar),
                )
            },
            onRemoveQuote = { viewModel.removeQuote(it) },
            bookmarked = bookmarked,
            onToggleBookmark = {
                haptics.performHapticFeedback(
                    if (bookmarked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                )
                viewModel.toggleBookmarkAt(displayedIndex.intValue)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        FrogSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding().imePadding()
            .padding(bottom = if (chromeVisible) 112.dp else 16.dp))

    }

    linkedDocumentId?.let { id ->
        ready.linkedDocuments[id]?.let { document ->
            key(document.id) {
                LinkedDocumentOverlay(
                    document = document,
                    initialItem = linkedDocumentItem,
                    initialScrollOffset = linkedDocumentScrollOffset,
                    seek = linkedDocumentSeek,
                    onSeekConsumed = { linkedDocumentSeek = null },
                    settings = settings,
                    appSettings = appSettings,
                    fontSize = liveFontSize,
                    colors = colors,
                    bookFonts = ready.bookFonts,
                    language = ready.language,
                    footnotes = footnotes,
                    showReturnButton = navigationBackAvailable,
                    returnLabel = returnLabel,
                    onReturn = ::navigateBackInBook,
                    onBack = {
                        if (navigationBackAvailable) {
                            navigateBackInBook()
                        } else {
                            linkedDocumentId = null
                            linkedDocumentItem = 0
                            linkedDocumentScrollOffset = 0
                            linkedDocumentSeek = null
                        }
                    },
                    onPositionChanged = { item, offset ->
                        linkedDocumentItem = item
                        linkedDocumentScrollOffset = offset
                    },
                )
            }
        }
    }

    noteToShow?.let { note ->
        val returnToPreviousNote = shouldReturnToPreviousNote(
            contextualReturn = note.contextualReturn,
            navigationBackAvailable = navigationBackAvailable,
        )
        NoteSheet(
            noteKey = note.key,
            note = note.document,
            initialItem = note.itemIndex,
            initialScrollOffset = note.scrollOffset,
            settings = settings,
            appSettings = appSettings,
            fontSize = liveFontSize,
            colors = colors,
            bookFonts = ready.bookFonts,
            language = ready.language,
            footnotes = footnotes,
            systemBackReturnsToPreviousNote = returnToPreviousNote,
            showReturnButton = note.contextualReturn && navigationBackAvailable,
            returnLabel = returnLabel,
            onReturn = ::navigateBackInBook,
            onPositionChanged = { item, offset ->
                noteToShow = noteToShow?.let { current ->
                    if (current.key == note.key) {
                        current.copy(itemIndex = item, scrollOffset = offset)
                    } else {
                        current
                    }
                }
            },
            onDismiss = { noteToShow = null },
        )
    }
}

/** Lazy index 0 is the sheet title; the N rich elements occupy indices 1..N. */
internal fun noteInitialLazyIndex(savedIndex: Int, elementCount: Int): Int =
    savedIndex.coerceIn(0, elementCount.coerceAtLeast(0))

/** Only a nested note owns Back; an ordinary note should retain sheet-dismiss semantics. */
internal fun shouldReturnToPreviousNote(
    contextualReturn: Boolean,
    navigationBackAvailable: Boolean,
): Boolean = contextualReturn && navigationBackAvailable

/** Explicit in-book return; visually distinct from the reader's exit arrow. */
@Composable
private fun ReaderReturnAffordance(
    visible: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Rounded.Undo,
    containerColor: Color = colors.chrome,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    AnimatedVisibility(
        visibleState = transition,
        modifier = modifier,
        enter = scaleIn(
            initialScale = 0.9f,
            animationSpec = keyframes {
                durationMillis = 180
                0.9f at 0
                1.025f at 100
                1f at 180
            },
        ) + fadeIn(tween(70)),
        exit = scaleOut(targetScale = 0.94f, animationSpec = tween(100)) + fadeOut(tween(100)),
    ) {
        // 12dp of space keeps the explicit blur and its offset
        // entirely inside the animated layer instead of cutting off a
        // platform elevation shadow whose extent depends on screen position.
        Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.padding(12.dp)) {
            ReaderReturnButton(
                colors = colors, onClick = onClick, label = label, enabled = visible,
                icon = icon, containerColor = containerColor,
            )
        }
    }
}

@Composable
private fun ReaderReturnButton(
    colors: ReaderColors,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean,
    icon: ImageVector,
    containerColor: Color,
) {
    val shape = RoundedCornerShape(24.dp)
    val light = colors.background.luminance() > 0.5f
    // Even a non-clickable Material Surface installs a pointer-input barrier.
    // Use a drawing-only Box so the exit animation cannot intercept bar taps.
    Box(
        modifier = Modifier
            .dropShadow(
                shape,
                Shadow(
                    radius = if (light) 5.dp else 6.dp,
                    offset = DpOffset(0.dp, if (light) 1.dp else 2.dp),
                    color = colors.shadow.copy(alpha = if (light) 0.10f else 0.16f),
                ),
            )
            .background(containerColor, shape)
            .clip(shape)
            .then(
                if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.clearAndSetSemantics {},
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = colors.onChrome,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bottom sheet with the complete rich block stream of a tapped footnote. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(
    noteKey: String,
    note: NoteDocument,
    initialItem: Int,
    initialScrollOffset: Int,
    settings: ReaderSettings,
    appSettings: AppSettings,
    fontSize: Float,
    colors: ReaderColors,
    bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily>,
    language: String?,
    footnotes: FootnoteHandler?,
    systemBackReturnsToPreviousNote: Boolean,
    showReturnButton: Boolean,
    returnLabel: String,
    onReturn: () -> Unit,
    onPositionChanged: (itemIndex: Int, scrollOffset: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        contentColor = colors.text,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !systemBackReturnsToPreviousNote,
            shouldDismissOnClickOutside = true,
        ),
    ) {
        // This handler is inside the dialog and replaces its dismiss handler
        // only for a note opened from another note. Back then mirrors the
        // contextual return chip and leaves the underlying note visible.
        BackHandler(enabled = systemBackReturnsToPreviousNote, onBack = onReturn)
        // Keep the already-visible modal surface while giving every note its
        // own list state. Re-keying the whole sheet would make nested notes
        // disappear and slide in again instead of replacing their content.
        key(noteKey) {
            val notePlan = remember(note) {
                planPublisherChapter(note.elements, note.publisherBoxes)
            }
            // Lazy index 0 is the synthetic title; note elements occupy 1..N.
            // The saved position is already a LazyColumn index, so N is valid.
            val initialIndex = noteInitialLazyIndex(initialItem, note.elements.size)
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialIndex,
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0),
            )
            LaunchedEffect(noteKey, listState) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collect { (item, offset) -> onPositionChanged(item, offset) }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    // Notes may be longer than whole chapters. Keep the sheet
                    // bounded and let the block surface scroll independently of
                    // the book underneath.
                    .heightIn(max = sheetMaxContentHeight()),
            ) {
                val returnRightPadding = WindowInsets.navigationBars.asPaddingValues()
                    .calculateRightPadding(LayoutDirection.Ltr) + 16.dp
                val contentWidth = maxWidth -
                    ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 84.dp),
                ) {
                    item(key = "note-title") {
                        Text(
                            text = stringResource(R.string.reader_note_title),
                            style = MaterialTheme.typography.titleLarge.copy(color = colors.text),
                            modifier = Modifier.padding(
                                start = 24.dp,
                                end = 24.dp,
                                bottom = 12.dp,
                            ),
                        )
                    }
                    items(note.elements.size, key = { it }) { index ->
                        if (index in notePlan.suppressedElements && settings.bookStyles) {
                            return@items
                        }
                        val plannedFloat = notePlan.floatByTarget[index]
                        RenderPart(
                            element = note.elements[index],
                            textOverride = null,
                            isParagraphStart = true,
                            imageHeightPx = null,
                            settings = settings,
                            appSettings = appSettings,
                            fontSize = fontSize,
                            colors = colors,
                            contentWidth = contentWidth,
                            bookFonts = bookFonts,
                            language = language,
                            footnotes = footnotes,
                            publisherBoxes = notePlan.boxesByElement[index],
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
                        )
                    }
                }
                ReaderReturnAffordance(
                    visible = showReturnButton,
                    colors = colors,
                    onClick = onReturn,
                    label = returnLabel,
                    modifier = Modifier
                        .align(AbsoluteAlignment.BottomRight)
                        .absolutePadding(right = returnRightPadding, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * Full-size transient reader for a linked document outside EPUB reading
 * order. It deliberately has no completion page, book progress, search or
 * bookmarks: closing it returns to the exact main page underneath. Rendering
 * still goes through [RenderPart], so CSS, tables, SVG/GIF images, embedded
 * fonts and internal links use the same engine as ordinary chapters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedDocumentOverlay(
    document: ReaderLinkedDocument,
    initialItem: Int,
    initialScrollOffset: Int,
    seek: ReaderReturnLocation.Linked?,
    onSeekConsumed: () -> Unit,
    settings: ReaderSettings,
    appSettings: AppSettings,
    fontSize: Float,
    colors: ReaderColors,
    bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily>,
    language: String?,
    footnotes: FootnoteHandler?,
    showReturnButton: Boolean,
    returnLabel: String,
    onReturn: () -> Unit,
    onBack: () -> Unit,
    onPositionChanged: (itemIndex: Int, scrollOffset: Int) -> Unit,
) {
    val initialIndex = if (document.items.isEmpty()) {
        0
    } else {
        resolvePublisherFloatTarget(document.items, initialItem)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0),
    )
    LaunchedEffect(document.id, seek) {
        val target = seek
        if (target != null && target.documentId == document.id && document.items.isNotEmpty()) {
            listState.scrollToItem(
                resolvePublisherFloatTarget(document.items, target.itemIndex),
                target.scrollOffset.coerceAtLeast(0),
            )
            onSeekConsumed()
        }
    }
    LaunchedEffect(document.id, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (item, offset) -> onPositionChanged(item, offset) }
    }
    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val returnRightPadding = stableInsets.calculateRightPadding(LayoutDirection.Ltr) + 16.dp
        val contentWidth = maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = stableInsets.calculateTopPadding() + 76.dp,
                bottom = stableInsets.calculateBottomPadding() + 48.dp,
            ),
        ) {
            items(document.items.size, key = { it }) { index ->
                val readerItem = document.items[index]
                if (readerItem.suppressedByPublisherFloat && settings.bookStyles) {
                    return@items
                }
                RenderPart(
                    element = readerItem.element,
                    textOverride = null,
                    isParagraphStart = true,
                    imageHeightPx = null,
                    settings = settings,
                    appSettings = appSettings,
                    fontSize = fontSize,
                    colors = colors,
                    contentWidth = contentWidth,
                    bookFonts = bookFonts,
                    viewportHeight = maxHeight - stableInsets.calculateTopPadding() -
                        stableInsets.calculateBottomPadding(),
                    language = language,
                    footnotes = footnotes,
                    publisherBoxes = readerItem.publisherBoxes,
                    publisherFloat = readerItem.publisherFloat,
                )
            }
        }

        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = stableInsets.calculateTopPadding())
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.reader_back),
                    )
                }
                Text(
                    text = document.title ?: stringResource(R.string.reader_contents),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        ReaderReturnAffordance(
            visible = showReturnButton,
            colors = colors,
            onClick = onReturn,
            label = returnLabel,
            modifier = Modifier
                .align(AbsoluteAlignment.BottomRight)
                .absolutePadding(
                    right = returnRightPadding,
                    bottom = stableInsets.calculateBottomPadding() + 18.dp,
                ),
        )
    }
}

/**
 * Resolves link taps: a key with a note behind it opens the note popup,
 * anything else that names a place in the book jumps there (Contents
 * entries, cross-references).
 */
class FootnoteHandler(
    val notes: Map<String, NoteDocument>,
    val onNote: (key: String, note: NoteDocument) -> Unit,
    val linkTargets: Map<String, Int> = emptyMap(),
    val onNavigate: (Int) -> Unit = {},
    val linkedDocumentTargets: Map<String, LinkedDocumentTarget> = emptyMap(),
    val onNavigateLinked: (LinkedDocumentTarget) -> Unit = {},
) {
    // One listener per book, shared by all links and recompositions. New
    // lambdas per reference make otherwise identical AnnotatedStrings unequal
    // and force Compose to rebuild link layout during scrolling.
    internal val linkInteractionListener = LinkInteractionListener { link ->
        (link as? LinkAnnotation.Clickable)?.let { open(it.tag) }
    }

    /** True when tapping [key] does something. */
    fun handles(key: String): Boolean =
        key in notes || key in linkTargets || key in linkedDocumentTargets

    fun open(key: String) {
        val note = notes[key]
        if (note != null) {
            onNote(key, note)
            return
        }
        linkTargets[key]?.let {
            onNavigate(it)
            return
        }
        linkedDocumentTargets[key]?.let(onNavigateLinked)
    }
}

/**
 * Highlights footnote references with the accent color and makes them
 * tappable. Adding color/links does not change text metrics, so paginated
 * text still fits its measured page.
 */
internal fun AnnotatedString.withFootnoteLinks(
    accent: Color,
    handler: FootnoteHandler?,
): AnnotatedString {
    val internalRefs = if (handler == null) {
        emptyList()
    } else {
        (
            getStringAnnotations(FOOTNOTE_TAG, 0, length) +
                getStringAnnotations(LINK_TAG, 0, length)
            ).filter { handler.handles(it.item) }
    }
    val externalRefs = getStringAnnotations(EXTERNAL_LINK_TAG, 0, length)
    if (internalRefs.isEmpty() && externalRefs.isEmpty()) return this

    val builder = AnnotatedString.Builder(this)
    val styles = TextLinkStyles(
        style = SpanStyle(
            color = accent,
            textDecoration = TextDecoration.None,
        ),
        pressedStyle = SpanStyle(
            color = accent,
            textDecoration = TextDecoration.None,
            background = accent.copy(alpha = 0.18f),
        ),
    )
    for (ref in internalRefs) {
        builder.addLink(
            LinkAnnotation.Clickable(
                tag = ref.item,
                styles = styles,
                linkInteractionListener = handler?.linkInteractionListener,
            ),
            ref.start,
            ref.end,
        )
    }
    for (ref in externalRefs) {
        builder.addLink(
            LinkAnnotation.Url(url = ref.item, styles = styles),
            ref.start,
            ref.end,
        )
    }
    return builder.toAnnotatedString()
}

/**
 * Paints every occurrence of the search term (case-insensitively) after a
 * jump from search results. Background-only, so page metrics don't change.
 * Cleared when the reader taps the page.
 */
internal fun AnnotatedString.withSearchHighlight(
    term: String?,
    highlight: Color,
): AnnotatedString {
    if (term.isNullOrBlank()) return this
    var builder: AnnotatedString.Builder? = null
    var index = text.indexOf(term, ignoreCase = true)
    while (index >= 0) {
        val target = builder ?: AnnotatedString.Builder(this).also { builder = it }
        target.addStyle(SpanStyle(background = highlight), index, index + term.length)
        index = text.indexOf(term, index + term.length, ignoreCase = true)
    }
    return builder?.toAnnotatedString() ?: this
}

// -------------------------------------------------------------------- scroll

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrollReader(
    ready: ReaderState.Ready,
    settings: ReaderSettings,
    appSettings: AppSettings,
    fontSize: Float,
    colors: ReaderColors,
    viewModel: ReaderViewModel,
    displayedIndex: MutableIntState,
    livePosition: androidx.compose.runtime.MutableState<Float?>,
    seekTarget: Int?,
    onSeekConsumed: () -> Unit,
    seekFraction: Float?,
    onSeekFractionConsumed: () -> Unit,
    seekPosition: ReaderReturnLocation.Main?,
    animateSeekPosition: Boolean,
    onSeekPositionConsumed: (ReaderReturnLocation.Main) -> Unit,
    onChapterSeekInterrupted: (ReaderReturnLocation.Main) -> Unit,
    onToggleChrome: () -> Unit,
    pinchHandlers: PinchHandlers,
    brightnessHandlers: BrightnessHandlers,
    turnEvents: SharedFlow<Boolean>,
    footnotes: FootnoteHandler?,
    quoteRanges: List<QuoteHighlight>,
    searchHighlight: String?,
    selection: SelectionController,
    seekCompletion: Boolean,
    onSeekCompletionConsumed: () -> Unit,
    completionPadding: PaddingValues,
    onSaveReview: () -> Unit,
    onExitReview: () -> Unit,
    onContentReady: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val readingGeneration = remember { viewModel.readingGeneration.value }
    val liveBook by viewModel.book.collectAsStateWithLifecycle()
    val completionVisible by viewModel.completionVisible.collectAsStateWithLifecycle()
    val reviewState by viewModel.completionReview.collectAsStateWithLifecycle()
    val rawInitialIndex = viewModel.currentFlatIndex.coerceIn(0, ready.items.size - 1)
    val initialIndex = resolvePublisherFloatTarget(ready.items, rawInitialIndex)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = if (initialIndex == rawInitialIndex &&
            rawInitialIndex == ready.book.progress.elementIndex
        ) {
            ready.book.progress.scrollOffset
        } else {
            0
        },
    )
    LaunchedEffect(completionVisible) {
        // The final item becomes a fixed viewport with its own form scroll.
        if (completionVisible) listState.scrollToItem(ready.items.size)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.size > 0 } }
            .first { it }
        onContentReady()
    }
    val density = LocalDensity.current
    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val readingTopReferencePx = with(density) {
        (stableInsets.calculateTopPadding() + 20.dp).toPx()
    }
    fun currentScrollLocation(): ReaderReturnLocation.Main {
        val index = listState.firstVisibleItemIndex.coerceIn(ready.items.indices)
        val anchor = selection.spaceSize?.let { size ->
            selection.anchorAt(
                point = Offset(size.width / 2f, readingTopReferencePx),
                maxVerticalGap = with(density) { 96.dp.toPx() },
            )
        }?.takeIf { it.itemIndex == index }
        return ReaderReturnLocation.Main(
            flatItemIndex = index,
            charOffset = anchor?.charOffset,
            scrollOffset = listState.firstVisibleItemScrollOffset,
        )
    }

    LaunchedEffect(ready) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
                displayedIndex.intValue = index.coerceAtMost(ready.items.size - 1)
                viewModel.observeVisiblePosition(index.coerceAtMost(ready.items.size - 1), offset)
                viewModel.observeCompletionPosition(
                    lastPage = index == ready.items.size - 1,
                    completion = index >= ready.items.size,
                )
            }
    }
    // A single long fling can move several screens without using the TOC or
    // progress track. Offer the same safety net only after a genuinely large
    // physical displacement; ordinary reading, page-edge taps and volume-key
    // steps stay well below the threshold.
    LaunchedEffect(ready, listState) {
        var origin: ReaderReturnLocation.Main? = null
        var previousOffsets = emptyMap<Int, Int>()
        var travelledPx = 0f
        var viewportPx = 1
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.layoutInfo.visibleItemsInfo.map { it.index to it.offset },
                listState.layoutInfo.viewportSize.height.coerceAtLeast(1),
            )
        }.collect { (moving, visible, viewport) ->
            val currentOffsets = visible.toMap()
            viewportPx = viewport
            if (moving && origin == null) {
                origin = currentScrollLocation()
                travelledPx = 0f
            }
            if (moving) {
                val common = previousOffsets.keys.firstOrNull { it in currentOffsets }
                if (common != null) {
                    travelledPx += abs(
                        previousOffsets.getValue(common) - currentOffsets.getValue(common),
                    )
                }
            } else {
                val start = origin
                if (start != null && !selection.active &&
                    ReaderNavigationPolicy.isLargeScrollJump(travelledPx, viewportPx)
                ) {
                    val end = currentScrollLocation()
                    if (end != start) viewModel.rememberNavigationOrigin(start, expires = true)
                }
                origin = null
                travelledPx = 0f
            }
            previousOffsets = currentOffsets
        }
    }
    // Fractional position (item + how far it has scrolled past the top):
    // feeds the smooth progress bars. Index AND offset are read from the
    // same layoutInfo snapshot — firstVisibleItemIndex updates a frame
    // earlier during fast flings, which made the bar flick backwards.
    LaunchedEffect(ready) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (info == null || info.size <= 0) {
                null
            } else {
                info.index + ((-info.offset).toFloat() / info.size).coerceIn(0f, 1f)
            }
        }.collect { position -> position?.let { livePosition.value = it } }
    }
    LaunchedEffect(ready) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(400.milliseconds)
                viewModel.saveProgress(index, offset)
            }
    }
    LaunchedEffect(seekTarget) {
        val target = seekTarget ?: return@LaunchedEffect
        val sourceItem = target.coerceIn(0, ready.items.size - 1)
        val visibleItem = resolvePublisherFloatTarget(ready.items, sourceItem)
        livePosition.value = sourceItem.toFloat()
        listState.scrollToItem(visibleItem)
        onSeekConsumed()
    }
    LaunchedEffect(seekCompletion) {
        if (seekCompletion) {
            try {
                listState.animateScrollToItem(ready.items.size)
            } finally {
                onSeekCompletionConsumed()
            }
        }
    }
    LaunchedEffect(seekFraction) {
        val fraction = seekFraction ?: return@LaunchedEffect
        val item = (fraction * (ready.items.size - 1)).roundToInt()
            .coerceIn(0, ready.items.size - 1)
        livePosition.value = item.toFloat()
        listState.scrollToItem(resolvePublisherFloatTarget(ready.items, item))
        onSeekFractionConsumed()
    }
    LaunchedEffect(seekPosition) {
        val position = seekPosition ?: return@LaunchedEffect
        val sourceTarget = position.flatItemIndex.coerceIn(0, ready.items.size - 1)
        val visibleTarget = resolvePublisherFloatTarget(ready.items, sourceTarget)
        livePosition.value = sourceTarget.toFloat()
        val charOffset = position.charOffset
        if (charOffset == null) {
            if (animateSeekPosition) {
                try {
                    listState.animateScrollToItem(visibleTarget, position.scrollOffset.coerceAtLeast(0))
                } catch (cancelled: CancellationException) {
                    onChapterSeekInterrupted(position)
                    throw cancelled
                }
            } else {
                listState.scrollToItem(visibleTarget, position.scrollOffset.coerceAtLeast(0))
            }
        } else {
            // Source characters survive font, width, orientation and mode
            // changes; a stored pixel offset does not. Compose the item first,
            // then place the bidi-aware source caret back at the same safe-top
            // reference line. Pixel offset remains the non-text fallback.
            listState.scrollToItem(visibleTarget)
            var restored = false
            repeat(3) {
                withFrameNanos { }
                val caret = selection.caretAt(BookAnchor(sourceTarget, charOffset))
                if (caret != null) {
                    val correction = caret.top - readingTopReferencePx
                    if (abs(correction) > 0.5f) listState.scrollBy(correction)
                    restored = true
                }
            }
            if (!restored) {
                listState.scrollToItem(
                    visibleTarget,
                    position.scrollOffset.coerceAtLeast(0),
                )
            }
        }
        onSeekPositionConsumed(position)
    }
    LaunchedEffect(ready) {
        turnEvents.collect { forward ->
            val viewport = listState.layoutInfo.viewportSize.height * 0.88f
            listState.animateScrollBy(if (forward) viewport else -viewport)
        }
    }
    DisposableEffect(ready) {
        onDispose {
            if (readingGeneration == viewModel.readingGeneration.value) {
                viewModel.saveProgress(
                    listState.firstVisibleItemIndex.coerceAtMost(ready.items.size - 1),
                    listState.firstVisibleItemScrollOffset,
                )
            }
        }
    }

    val highlights = rememberReaderHighlights(
        controller = selection,
        quotes = quoteRanges,
        quoteColors = remember(colors) { QuoteColor.entries.associateWith(colors::quoteHighlight) },
        selectionColor = colors.selection,
    )
    // A selection drag held at the top or bottom edge scrolls the list along.
    DisposableEffect(selection, listState) {
        val mine = SelectionAutoAdvance(
            paged = false,
            step = { _, pixels -> listState.scrollBy(pixels) },
        )
        selection.advance = mine
        selection.surfaceMoving = { listState.isScrollInProgress }
        // Only clear what is still ours: switching reading modes composes the
        // other one's effect around this one's disposal.
        onDispose {
            if (selection.advance === mine) {
                selection.advance = null
                selection.surfaceMoving = { false }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().then(
        if (completionVisible) Modifier.navigationBarsPadding().imePadding() else Modifier,
    )) {
        val contentWidth = maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
        LazyColumn(
            state = listState,
            userScrollEnabled = !completionVisible,
            modifier = Modifier
                .fillMaxSize()
                .readerGestures(
                    enabled = !completionVisible,
                    onTapLeft = {
                        scope.launch {
                            val delta = ReaderProgression.horizontalTapDelta(
                                leftEdge = true,
                                progression = ready.pageProgression,
                            )
                            listState.animateScrollBy(
                                delta * listState.layoutInfo.viewportSize.height * 0.88f,
                            )
                        }
                    },
                    onTapRight = {
                        scope.launch {
                            val delta = ReaderProgression.horizontalTapDelta(
                                leftEdge = false,
                                progression = ready.pageProgression,
                            )
                            listState.animateScrollBy(
                                delta * listState.layoutInfo.viewportSize.height * 0.88f,
                            )
                        }
                    },
                    onTapCenter = onToggleChrome,
                    pinchHandlers = pinchHandlers,
                    brightnessHandlers = brightnessHandlers,
                ),
            contentPadding = if (completionVisible) PaddingValues() else PaddingValues(
                top = stableInsets.calculateTopPadding() + 20.dp,
                bottom = stableInsets.calculateBottomPadding() + 48.dp,
            ),
        ) {
            items(ready.items.size, key = { it }) { index ->
                val readerItem = ready.items[index]
                if (readerItem.suppressedByPublisherFloat && settings.bookStyles) {
                    return@items
                }
                RenderPart(
                    element = readerItem.element,
                    textOverride = null,
                    isParagraphStart = true,
                    imageHeightPx = null,
                    settings = settings,
                    appSettings = appSettings,
                    fontSize = fontSize,
                    colors = colors,
                    contentWidth = contentWidth,
                    bookFonts = ready.bookFonts,
                    viewportHeight = maxHeight - stableInsets.calculateTopPadding() -
                        stableInsets.calculateBottomPadding(),
                    language = ready.language,
                    footnotes = footnotes,
                    searchHighlight = searchHighlight,
                    highlights = highlights,
                    itemIndex = index,
                    publisherBoxes = readerItem.publisherBoxes,
                    publisherFloat = readerItem.publisherFloat,
                )
            }
            item(key = "completion") {
                CompletionPage(
                    book = liveBook,
                    colors = colors,
                    cover = liveBook?.let(viewModel::coverFileFor),
                    readingLogEntry = viewModel.readingLogEntry.collectAsStateWithLifecycle().value,
                    saved = viewModel.finishSaved.collectAsStateWithLifecycle().value,
                    reviewState = reviewState,
                    onRatingChange = viewModel::setCompletionRating,
                    onReviewChange = viewModel::setCompletionReview,
                    onSaveReview = onSaveReview,
                    onExit = onExitReview,
                    contentPadding = completionPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxHeight),
                )
            }
        }
    }
}

// -------------------------------------------------------------------- pages

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PagedReader(
    ready: ReaderState.Ready,
    settings: ReaderSettings,
    appSettings: AppSettings,
    colors: ReaderColors,
    viewModel: ReaderViewModel,
    displayedIndex: MutableIntState,
    pagePosition: androidx.compose.runtime.MutableState<Pair<Int, Int>?>,
    livePagePosition: androidx.compose.runtime.MutableState<Float?>,
    seekTarget: Int?,
    onSeekConsumed: () -> Unit,
    seekFraction: Float?,
    onSeekFractionConsumed: () -> Unit,
    seekPosition: ReaderReturnLocation.Main?,
    animateSeekPosition: Boolean,
    onSeekPositionConsumed: (ReaderReturnLocation.Main) -> Unit,
    onChapterSeekInterrupted: (ReaderReturnLocation.Main, Boolean) -> Unit,
    onToggleChrome: () -> Unit,
    pinchHandlers: PinchHandlers,
    brightnessHandlers: BrightnessHandlers,
    turnEvents: SharedFlow<Boolean>,
    footnotes: FootnoteHandler?,
    quoteRanges: List<QuoteHighlight>,
    searchHighlight: String?,
    selection: SelectionController,
    seekCompletion: Boolean,
    onSeekCompletionConsumed: () -> Unit,
    completionPadding: PaddingValues,
    onSaveReview: () -> Unit,
    onExitReview: () -> Unit,
    onContentReady: () -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val liveBook by viewModel.book.collectAsStateWithLifecycle()
    val completionVisible by viewModel.completionVisible.collectAsStateWithLifecycle()
    val reviewState by viewModel.completionReview.collectAsStateWithLifecycle()
    var pageFooterText by remember { mutableStateOf("") }
    var pageFooterVisible by remember { mutableStateOf(false) }
    val paperCurl = settings.pageTurnAnimation == PageTurnAnimation.PAGE_CURL
    val cascade = settings.pageTurnAnimation == PageTurnAnimation.CASCADE
    val noAnimation = settings.pageTurnAnimation == PageTurnAnimation.NONE
    val sheetPages = paperCurl || cascade

    // Element-based progress takes over again when paged mode leaves.
    DisposableEffect(Unit) {
        onDispose {
            pagePosition.value = null
            livePagePosition.value = null
        }
    }
    val density = LocalDensity.current
    val uiLayoutIsRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stableInsets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
        val topPadding = stableInsets.calculateTopPadding() + 16.dp
        val bottomPadding = stableInsets.calculateBottomPadding() + 40.dp

        val contentWidthPx = with(density) {
            (maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2).roundToPx()
        }
        val contentHeightPx = with(density) {
            (maxHeight - topPadding - bottomPadding).roundToPx()
        }

        val spec = remember(
            contentWidthPx,
            contentHeightPx,
            density.density,
            density.fontScale,
            settings,
            ready,
        ) {
            PaginationSpec(
                contentWidthPx, contentHeightPx, density, settings, settings.fontSizeSp,
                bookFonts = ready.bookFonts,
                embeddedFontSignature = ready.embeddedFontSignature,
                language = ready.language,
            )
        }
        LaunchedEffect(spec.key, ready) {
            // Quick pass: repaginate just the chapter being read (fast) so a
            // settings change restyles the page near-instantly; the full book
            // follows in the background. With chapters starting on fresh
            // pages both passes produce identical in-chapter splits, so the
            // swap is invisible. Without that setting page boundaries differ
            // — the quick pass is skipped and the change lands in one step.
            val quick: (suspend () -> List<BookPage>)? =
                if (settings.startChaptersOnNewPage && viewModel.pagination.value != null) {
                    val chapter = ready.chapterAt(viewModel.currentFlatIndex)
                    val from = ready.chapterStarts[chapter]
                    val to = ready.chapterStarts.getOrNull(chapter + 1) ?: ready.items.size
                    if (from == 0 && to == ready.items.size) {
                        null // single-chapter book: the full pass is the quick pass
                    } else {
                        { paginateBook(ready.items, measurer, spec, from, to) }
                    }
                } else {
                    null
                }
            viewModel.ensurePagination(spec.key, settings, ready.items, quick) {
                paginateBook(ready.items, measurer, spec)
            }
        }

        val holder by viewModel.pagination.collectAsStateWithLifecycle()
        val current = holder
        if (current == null || current.pages.isEmpty()) {
            SideEffect {
                pagePosition.value = null
                livePagePosition.value = null
                pageFooterVisible = false
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return@BoxWithConstraints
        }
        // Stale pages stay on screen while the new layout computes in the
        // background. They render with the settings they were MEASURED with,
        // so nothing shifts twice: the text changes exactly once, when the
        // new page layout lands (the corner spinner shows the wait).
        val recomputing = current.key != spec.key || current.partial
        val renderSettings = if (current.key != spec.key) current.settings else settings
        val renderContentWidth = maxWidth - ReaderMetrics.horizontalPadding(renderSettings.pageMargins) * 2

        key(current.key, current.partial) {
            // A fresh layout generation per pager rebuild: a settings change
            // disposes this whole subtree and composes a new one, and for a
            // frame both are registered.
            val pages = current.pages
            val realPageCount = pages.size
            // Land on the page holding the exact CHARACTER the reader was at:
            // element granularity alone jumps back to the paragraph start
            // when a page began mid-paragraph and the book is re-paginated.
            val initialPage = pages.indexOfLast { page ->
                page.firstItemIndex < viewModel.currentFlatIndex ||
                    (
                        page.firstItemIndex == viewModel.currentFlatIndex &&
                            page.firstCharOffset <= viewModel.currentCharOffset
                        )
            }.coerceAtLeast(0)
            // One extra virtual page after the end: the completion screen.
            // (Not while a partial chapter holder is shown — swiping past its
            // last page must not count as finishing the book.)
            val extraPages = if (current.partial) 0 else 1
            val pagerState = rememberPagerState(initialPage = initialPage) {
                realPageCount + extraPages
            }
            val curlTouch = remember(pagerState) { PageCurlTouch() }
            LaunchedEffect(pagerState, curlTouch) {
                snapshotFlow {
                    Triple(
                        pagerState.currentPage,
                        pagerState.currentPageOffsetFraction,
                        pagerState.isScrollInProgress || curlTouch.pressed,
                    )
                }.collect { (page, offset, moving) ->
                    curlTouch.onPagerState(page, offset, moving)
                }
            }
            val pageTurnSpec = remember(paperCurl, cascade, noAnimation) {
                when {
                    paperCurl -> paperPageTurnSpec()
                    cascade -> cascadePageTurnSpec()
                    noAnimation -> snap<Float>()
                    else -> spring<Float>()
                }
            }
            val cascadeSnapSpec = remember(pagerState) {
                CascadePageSnapSpec { pagerState.layoutInfo.pageSize }
            }
            val selectionTurnSpec = remember { selectionPageTurnSpec() }
            LaunchedEffect(pagerState, noAnimation) {
                if (noAnimation) {
                    curlTouch.reset(pagerState.currentPage)
                    pagerState.scrollToPage(pagerState.currentPage)
                }
            }
            LaunchedEffect(pagerState, recomputing) {
                if (recomputing) return@LaunchedEffect
                snapshotFlow {
                    pagerState.layoutInfo.visiblePagesInfo.any { it.index == pagerState.currentPage }
                }.first { it }
                onContentReady()
            }

            // Last page of the chapter each page belongs to (for the footer).
            val chapterLastPage = remember(current) {
                val chapterOf = IntArray(realPageCount) { ready.chapterAt(pages[it].firstItemIndex) }
                val last = IntArray(realPageCount)
                for (i in realPageCount - 1 downTo 0) {
                    last[i] = if (i == realPageCount - 1 || chapterOf[i + 1] != chapterOf[i]) {
                        i
                    } else {
                        last[i + 1]
                    }
                }
                last
            }

            LaunchedEffect(pagerState, recomputing) {
                snapshotFlow {
                    (curlTouch.visualPosition ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction))
                        .coerceIn(0f, (realPageCount - 1).coerceAtLeast(0).toFloat())
                }.collect { livePagePosition.value = if (recomputing) null else it }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { settled ->
                    viewModel.observeCompletionPosition(
                        lastPage = !current.partial && settled == realPageCount - 1,
                        completion = !current.partial && settled >= realPageCount,
                    )
                    if (settled >= realPageCount) {
                        // Reached the completion screen.
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        return@collect
                    }
                    if (current.partial) {
                        // Chapter-only pages: keep global progress untouched,
                        // just remember the exact reading position.
                        val flat = pages[settled].firstItemIndex
                        displayedIndex.intValue = flat
                        viewModel.saveProgress(flat, 0, pages[settled].firstCharOffset)
                        return@collect
                    }
                    val flatIndex = pages[settled].firstItemIndex
                    displayedIndex.intValue = flatIndex
                    viewModel.saveProgress(flatIndex, 0, pages[settled].firstCharOffset)
                }
            }

            /**
             * The chapter tick belongs to the gesture, not to the animation
             * that follows it: `settledPage` only arrives once the page has
             * finished gliding into place, which is felt as a late buzz.
             * `targetPage` is known the moment the turn is committed.
             */
            LaunchedEffect(pagerState, pages) {
                var initialChapter = -1
                var gestureTickedChapter = -1
                var lastTargetPage = -1
                snapshotFlow {
                    Triple(
                        curlTouch.releasePage ?: pagerState.targetPage,
                        pagerState.isScrollInProgress || curlTouch.gesture != null,
                        pagerState.currentPage,
                    )
                }.collect { (target, inProgress, current) ->
                    if (!inProgress) {
                        initialChapter = -1
                        gestureTickedChapter = -1
                        lastTargetPage = -1
                        return@collect
                    }

                    if (initialChapter == -1) {
                        initialChapter = pages.getOrNull(current)?.let {
                            ready.chapterAt(it.firstItemIndex)
                        } ?: -1
                        lastTargetPage = target
                    }

                    val page = pages.getOrNull(target) ?: return@collect
                    val chapter = ready.chapterAt(page.firstItemIndex)

                    // Only page turns (swipes/taps) tick; jumps from the TOC or
                    // search carry their own feedback and must not buzz twice.
                    // We also gate by the chapter we've already ticked for in
                    // this gesture to avoid "triple vibrations" from pager
                    // flicker at the chapter boundary.
                    val isStep = lastTargetPage != -1 && abs(target - lastTargetPage) <= 1
                    if (isStep && chapter != initialChapter && chapter != gestureTickedChapter) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        gestureTickedChapter = chapter
                    }

                    lastTargetPage = target
                }
            }
            LaunchedEffect(seekTarget) {
                val target = seekTarget ?: return@LaunchedEffect
                val page = pages.indexOfLast { it.firstItemIndex <= target }.coerceAtLeast(0)
                curlTouch.reset(pagerState.currentPage)
                livePagePosition.value = page.toFloat()
                pagerState.scrollToPage(page)
                onSeekConsumed()
            }
            LaunchedEffect(seekCompletion, current.partial) {
                if (seekCompletion && !current.partial) {
                    try {
                        curlTouch.reset(pagerState.currentPage)
                        pagerState.turnReaderPage(realPageCount, settings.pageTurnAnimation, readerChapterSwitchSpec())
                        onSeekCompletionConsumed()
                    } catch (cancelled: CancellationException) {
                        if (viewModel.pagination.value === current) onSeekCompletionConsumed()
                        throw cancelled
                    }
                }
            }
            LaunchedEffect(seekFraction, current.partial) {
                val fraction = seekFraction ?: return@LaunchedEffect
                if (!ReaderNavigationPolicy.canConsumeBookFractionSeek(current.partial)) {
                    return@LaunchedEffect
                }
                val page = (fraction * (realPageCount - 1)).roundToInt()
                    .coerceIn(0, realPageCount - 1)
                curlTouch.reset(pagerState.currentPage)
                livePagePosition.value = page.toFloat()
                pagerState.scrollToPage(page)
                onSeekFractionConsumed()
            }
            LaunchedEffect(seekPosition) {
                val position = seekPosition ?: return@LaunchedEffect
                val item = position.flatItemIndex
                if (current.partial) {
                    // The quick holder contains only the chapter that was on
                    // screen when re-pagination began. Keep the typed target
                    // pending instead of clamping a cross-chapter link/back to
                    // the wrong edge; the full holder recreates this effect.
                    if (!ReaderNavigationPolicy.partialHolderContainsTarget(
                            chapterStarts = ready.chapterStarts,
                            itemCount = ready.items.size,
                            partialFirstItem = pages.firstOrNull()?.firstItemIndex,
                            targetItem = item,
                        )
                    ) {
                        return@LaunchedEffect
                    }
                }
                val page = ReaderNavigationPolicy.pageIndexForLocation(pages, position)
                    ?: return@LaunchedEffect
                curlTouch.reset(pagerState.currentPage)
                if (animateSeekPosition) {
                    try {
                        pagerState.turnReaderPage(page, settings.pageTurnAnimation, readerChapterSwitchSpec())
                    } catch (cancelled: CancellationException) {
                        onChapterSeekInterrupted(position, viewModel.pagination.value !== current)
                        throw cancelled
                    }
                } else {
                    livePagePosition.value = page.toFloat()
                    pagerState.scrollToPage(page)
                }
                onSeekPositionConsumed(position)
            }
            LaunchedEffect(pagerState, pageTurnSpec) {
                turnEvents.collect { forward ->
                    curlTouch.reset(pagerState.currentPage)
                    val delta = if (forward) 1 else -1
                    pagerState.turnReaderPage(
                        (pagerState.currentPage + delta)
                            .coerceIn(0, realPageCount + extraPages - 1),
                        animation = settings.pageTurnAnimation,
                        animationSpec = pageTurnSpec,
                    )
                }
            }

            // A selection drag held at the left or right edge turns pages.
            // Clamped to the real pages: auto-turning onto the completion
            // screen would mark the book finished and buzz for it.
            DisposableEffect(selection, pagerState, realPageCount, pageTurnSpec, selectionTurnSpec) {
                val mine = SelectionAutoAdvance(
                    paged = true,
                    step = { direction, _ ->
                        curlTouch.reset(pagerState.currentPage)
                        val logicalDirection = ReaderProgression.selectionPageDelta(
                            direction,
                            ready.pageProgression,
                        )
                        pagerState.turnReaderPage(
                            (pagerState.currentPage + logicalDirection)
                                .coerceIn(0, (realPageCount - 1).coerceAtLeast(0)),
                            animation = settings.pageTurnAnimation,
                            animationSpec = selectionTurnSpec,
                        )
                    },
                    settle = {
                        curlTouch.reset(pagerState.currentPage)
                        pagerState.turnReaderPage(pagerState.currentPage, settings.pageTurnAnimation, pageTurnSpec)
                    },
                )
                selection.advance = mine
                selection.surfaceMoving = { pagerState.isScrollInProgress || curlTouch.gesture != null }
                onDispose {
                    if (selection.advance === mine) {
                        selection.advance = null
                        selection.surfaceMoving = { false }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !paperCurl && !noAnimation,
                beyondViewportPageCount = 1,
                flingBehavior = when {
                    paperCurl -> PagerDefaults.flingBehavior(
                        pagerState,
                        snapAnimationSpec = tween(280, easing = FastOutSlowInEasing),
                    )
                    cascade -> PagerDefaults.flingBehavior(
                        pagerState,
                        snapAnimationSpec = cascadeSnapSpec,
                    )
                    else -> PagerDefaults.flingBehavior(pagerState)
                },
                overscrollEffect = if (sheetPages || noAnimation) null else androidx.compose.foundation.rememberOverscrollEffect(),
                reverseLayout = ReaderProgression.usesReversePagerLayout(
                    ready.pageProgression,
                    uiLayoutIsRtl = uiLayoutIsRtl,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (paperCurl) {
                        Modifier
                            .pageCurlGestures(curlTouch, pagerState, ready.pageProgression == PageProgression.RTL)
                            .paperPageSemantics(pagerState, curlTouch, ready.pageProgression == PageProgression.RTL, scope)
                    } else if (noAnimation) {
                        Modifier
                            .instantPageGestures(pagerState, ready.pageProgression == PageProgression.RTL)
                            .instantPageSemantics(pagerState, ready.pageProgression == PageProgression.RTL)
                    } else Modifier)
                    .readerGestures(
                        enabled = !completionVisible,
                        tapGestureKey = pageTurnSpec,
                        onTapLeft = {
                            scope.launch {
                                val delta = ReaderProgression.horizontalTapDelta(
                                    leftEdge = true,
                                    progression = ready.pageProgression,
                                )
                                pagerState.turnReaderPage(
                                    (pagerState.currentPage + delta)
                                        .coerceIn(0, realPageCount + extraPages - 1),
                                    animation = settings.pageTurnAnimation,
                                    animationSpec = pageTurnSpec,
                                )
                            }
                        },
                        onTapRight = {
                            scope.launch {
                                val delta = ReaderProgression.horizontalTapDelta(
                                    leftEdge = false,
                                    progression = ready.pageProgression,
                                )
                                pagerState.turnReaderPage(
                                    (pagerState.currentPage + delta)
                                        .coerceIn(0, realPageCount + extraPages - 1),
                                    animation = settings.pageTurnAnimation,
                                    animationSpec = pageTurnSpec,
                                )
                            }
                        },
                        onTapCenter = onToggleChrome,
                        pinchHandlers = pinchHandlers,
                        brightnessHandlers = brightnessHandlers,
                    ),
            ) { pageIndex ->
                val pageContent: @Composable () -> Unit = {
                    if (pageIndex < realPageCount) {
                        // Both the text and its handles are captured into the
                        // same sheet, including Stack parallax and Curl folds.
                        val highlights = rememberReaderHighlights(
                            controller = selection,
                            quotes = quoteRanges,
                            quoteColors = remember(colors) { QuoteColor.entries.associateWith(colors::quoteHighlight) },
                            selectionColor = colors.selection,
                        )
                        Box(Modifier.fillMaxSize()) {
                            PageView(
                                page = pages[pageIndex],
                                settings = renderSettings,
                                appSettings = appSettings,
                                colors = colors,
                                topPadding = topPadding,
                                bottomPadding = bottomPadding,
                                contentWidth = renderContentWidth,
                                viewportHeight = with(density) { contentHeightPx.toDp() },
                                bookFonts = ready.bookFonts,
                                language = ready.language,
                                bookId = ready.book.id,
                                footnotes = footnotes,
                                searchHighlight = searchHighlight,
                                highlights = highlights,
                            )
                            SelectionHandles(selection, colors.accent, pageEpoch = highlights.epoch)
                            if (sheetPages && !recomputing && settings.showPageCounter) {
                                // The footer belongs to this particular sheet,
                                // so it follows the turn together with the text.
                                Text(
                                    text = readerPageFooterText(
                                        pageIndex, chapterLastPage,
                                        settings.showChapterPagesLeft,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.pageCounterText,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .padding(bottom = stableInsets.calculateBottomPadding() + 10.dp),
                                )
                            }
                        }
                    } else {
                        CompletionPage(
                            book = liveBook,
                            colors = colors,
                            cover = liveBook?.let(viewModel::coverFileFor),
                            readingLogEntry = viewModel.readingLogEntry.collectAsStateWithLifecycle().value,
                            saved = viewModel.finishSaved.collectAsStateWithLifecycle().value,
                            reviewState = reviewState,
                            onRatingChange = viewModel::setCompletionRating,
                            onReviewChange = viewModel::setCompletionReview,
                            onSaveReview = onSaveReview,
                            onExit = onExitReview,
                            contentPadding = completionPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .imePadding(),
                        )
                    }
                }
                if (paperCurl) {
                    PaperPage(
                        pager = pagerState,
                        pageIndex = pageIndex,
                        rtl = ready.pageProgression == PageProgression.RTL,
                        touch = curlTouch,
                        colors = colors,
                        content = pageContent,
                    )
                } else if (cascade) {
                    CascadePage(
                        pager = pagerState,
                        pageIndex = pageIndex,
                        rtl = ready.pageProgression == PageProgression.RTL,
                        touch = curlTouch,
                        colors = colors,
                        content = pageContent,
                    )
                } else {
                    pageContent()
                }
            }

            val shownPage = pagerState.currentPage
            val settledPage = pagerState.settledPage
            val footerText = if (settings.showPageCounter && !recomputing && shownPage < realPageCount) {
                readerPageFooterText(shownPage, chapterLastPage, settings.showChapterPagesLeft)
            } else null
            SideEffect {
                // Old complete pages and quick chapter pages remain renderable,
                // but publish counts only for the complete requested layout.
                pagePosition.value = if (recomputing) null else {
                    settledPage.coerceAtMost(realPageCount - 1) to realPageCount
                }
                pageFooterVisible = !sheetPages && footerText != null
                if (footerText != null) pageFooterText = footerText
            }
        }

        // Keep this outside the pager's layout key so a quick/full holder swap
        // cannot interrupt the counter's exit or flash a chapter-only total.
        if (!sheetPages) AnimatedVisibility(
            visible = pageFooterVisible,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = stableInsets.calculateBottomPadding() + 10.dp),
            enter = if (noAnimation) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = if (noAnimation) ExitTransition.None else fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 3 },
        ) {
            Text(
                text = pageFooterText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.pageCounterText,
                modifier = if (pageFooterVisible) Modifier else Modifier.clearAndSetSemantics {},
            )
        }

        // Subtle corner hint while pages are being recomputed for new settings.
        if (recomputing) {
            LoadingIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topPadding, end = 20.dp)
                    .size(28.dp),
            )
        }
    }
}

@Composable
private fun readerPageFooterText(
    pageIndex: Int,
    chapterLastPage: IntArray,
    showChapterPagesLeft: Boolean,
): String {
    if (!showChapterPagesLeft) return stringResource(R.string.reader_page_number, pageIndex + 1)
    val left = chapterLastPage[pageIndex] - pageIndex
    return if (left == 0) {
        stringResource(R.string.reader_chapter_last_page)
    } else {
        pluralStringResource(R.plurals.reader_chapter_pages_left, left, left)
    }
}

@Composable
private fun PageView(
    page: BookPage,
    settings: ReaderSettings,
    appSettings: AppSettings,
    colors: ReaderColors,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    contentWidth: androidx.compose.ui.unit.Dp,
    viewportHeight: androidx.compose.ui.unit.Dp,
    bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily>,
    language: String?,
    bookId: String,
    footnotes: FootnoteHandler?,
    searchHighlight: String?,
    highlights: ReaderHighlights,
) {
    val allowsVerticalScroll = page.parts.any(PagePart::allowsVerticalScroll)
    val overflowScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .then(
                if (allowsVerticalScroll) {
                    // A single table row may be taller than the viewport and
                    // cannot be split without corrupting rowspan semantics.
                    // Keep every cell reachable instead of clipping it away.
                    Modifier.verticalScroll(overflowScroll)
                } else {
                    // While settings repaginate, old splits can briefly draw
                    // with new metrics; contain them to the page boundary.
                    Modifier.clipToBounds()
                },
            ),
    ) {
        page.parts.forEach { part ->
            RenderPart(
                element = part.element,
                textOverride = part.text,
                isParagraphStart = part.isParagraphStart,
                imageHeightPx = part.imageHeightPx,
                settings = settings,
                appSettings = appSettings,
                fontSize = settings.fontSizeSp,
                colors = colors,
                contentWidth = contentWidth,
                viewportHeight = viewportHeight,
                bookFonts = bookFonts,
                language = language,
                footnotes = footnotes,
                searchHighlight = searchHighlight,
                tableLayout = part.tableLayout,
                tableRowStart = part.rowStart,
                tableRowEnd = part.rowEnd,
                tableHeaderRepeated = part.headerRepeated,
                sideBox = part.sideBox,
                floatImagePath = part.floatImagePath,
                highlights = highlights,
                itemIndex = part.itemIndex,
                // charStart is only a character offset for text parts — a
                // table part stores its row range in the very same fields.
                charStart = if (part.text != null) part.charStart.coerceAtLeast(0) else 0,
                publisherBoxes = part.publisherBoxes,
                publisherStartsElement = part.publisherStartsElement,
                publisherEndsElement = part.publisherEndsElement,
            )
        }
    }
}

// -------------------------------------------------------------------- shared

class PinchHandlers(
    val onPinch: (Float) -> Unit,
    val onPinchEnd: () -> Unit,
)

class BrightnessHandlers(
    /** delta is a fraction of full brightness (drag up → positive). */
    val onDelta: (Float) -> Unit,
)

/**
 * Tap zones (left/right/center), two-finger pinch for font size and a
 * brightness drag along the left screen edge.
 */
private fun Modifier.readerGestures(
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onTapCenter: () -> Unit,
    pinchHandlers: PinchHandlers,
    brightnessHandlers: BrightnessHandlers,
    enabled: Boolean = true,
    tapGestureKey: Any? = Unit,
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(tapGestureKey) {
            detectTapGestures { offset ->
                val width = size.width.toFloat()
                when {
                    offset.x < width * 0.22f -> onTapLeft()
                    offset.x > width * 0.78f -> onTapRight()
                    else -> onTapCenter()
                }
            }
        }
        .pointerInput(pinchHandlers) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var zoomed = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) {
                            pinchHandlers.onPinch(zoom)
                            zoomed = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (event.changes.none { it.pressed }) break
                }
                if (zoomed) pinchHandlers.onPinchEnd()
            }
        }
        .pointerInput(brightnessHandlers) {
            // Runs in the Initial pass so the edge drag wins over list scrolling.
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                if (down.position.x > size.width * 0.12f) return@awaitEachGesture

                val slop = viewConfiguration.touchSlop
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (event.changes.count { it.pressed } > 1) break // pinch takes over
                    // Text selection runs on Initial too, one level up, and the
                    // page margin it starts in is inside this edge zone. Once it
                    // claims the gesture, the brightness is not ours to change.
                    if (change.isConsumed) break

                    val totalDx = change.position.x - down.position.x
                    val totalDy = change.position.y - down.position.y
                    if (!dragging) {
                        if (abs(totalDy) > slop && abs(totalDy) > abs(totalDx) * 1.5f) {
                            dragging = true
                        } else if (abs(totalDx) > slop) {
                            break // horizontal → let the pager/scroll have it
                        }
                    }
                    if (dragging) {
                        val dy = change.position.y - change.previousPosition.y
                        brightnessHandlers.onDelta(-dy / (size.height * 0.7f))
                        change.consume()
                    }
                }
            }
        }
}

/** Small sun pill shown near the left edge while brightness changes. */
@Composable
private fun BrightnessOverlay(
    brightness: Float?,
    visibleUntil: Long,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visibleUntil) {
        if (visibleUntil == 0L) return@LaunchedEffect
        visible = true
        delay((visibleUntil - System.currentTimeMillis()).coerceAtLeast(0).milliseconds)
        visible = false
    }
    AnimatedVisibility(
        visible = visible && brightness != null,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.padding(start = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Rounded.WbSunny,
                    contentDescription = null,
                    tint = colors.accent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(brightnessPosition(brightness ?: 0f) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Renders one element (or a paginated fragment of it). */
@Composable
internal fun RenderPart(
    element: ContentElement,
    textOverride: androidx.compose.ui.text.AnnotatedString?,
    isParagraphStart: Boolean,
    imageHeightPx: Int?,
    settings: ReaderSettings,
    appSettings: AppSettings,
    fontSize: Float,
    colors: ReaderColors,
    contentWidth: androidx.compose.ui.unit.Dp,
    bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
    language: String? = null,
    footnotes: FootnoteHandler? = null,
    searchHighlight: String? = null,
    /** Selection/quote painting — null outside the reader (previews). */
    highlights: ReaderHighlights? = null,
    /** Flat element index this part belongs to, for selection anchors. */
    itemIndex: Int = -1,
    /** Where this part's text starts inside the element (paged splits). */
    charStart: Int = 0,
    tableLayout: TableLayout? = null,
    tableRowStart: Int = -1,
    tableRowEnd: Int = -1,
    tableHeaderRepeated: Boolean = false,
    sideBox: SideBoxSpec? = null,
    floatImagePath: String? = null,
    publisherBoxes: List<ReaderPublisherBox> = emptyList(),
    publisherFloat: ReaderPublisherFloat? = null,
    publisherStartsElement: Boolean = true,
    publisherEndsElement: Boolean = true,
    /** Reading viewport for publisher vh image limits in scrolling mode. */
    viewportHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val density = LocalDensity.current
    val viewportHeightPx = with(density) { viewportHeight?.roundToPx() }
    val columnWidthPx = with(density) { contentWidth.roundToPx() }
    val inlineMeasurer = rememberTextMeasurer()
    val fittingWidths = remember(inlineMeasurer, density, settings, fontSize, bookFonts, language) {
        PublisherInlineWidthResolver(inlineMeasurer, density, settings, fontSize, bookFonts, language)
    }
    val fontSize = remember(publisherBoxes, columnWidthPx, fittingWidths) {
        fittingWidths.fittedFontSize(publisherBoxes, columnWidthPx)
    }
    val inlineWidths = remember(fittingWidths, fontSize) { fittingWidths.atFontSize(fontSize) }
    val fontSizePx = with(density) { fontSize.sp.toPx() }
    val (vTop, vBottom) = ReaderMetrics.verticalPaddings(element, fontSize, settings.bookStyles)
    val boxGeometry = remember(
        publisherBoxes,
        columnWidthPx,
        fontSizePx,
        publisherStartsElement,
        publisherEndsElement,
        settings.bookStyles,
        inlineWidths,
        viewportHeightPx,
    ) {
        publisherBoxGeometry(
            boxes = publisherBoxes,
            columnWidthPx = columnWidthPx,
            fontSizePx = fontSizePx,
            partStartsElement = publisherStartsElement,
            partEndsElement = publisherEndsElement,
            enabled = settings.bookStyles,
            inlineWidths = inlineWidths,
            viewportHeightPx = viewportHeightPx,
        )
    }
    val boxContentWidth = with(density) { boxGeometry.contentWidthPx.toDp() }
    val (startInset, endInset) =
        ReaderMetrics.horizontalInsets(element, boxContentWidth, fontSize)
    val (leftInset, _) =
        ReaderMetrics.physicalHorizontalInsets(element, boxContentWidth, fontSize)
    val basePadding = ReaderMetrics.horizontalPadding(settings.pageMargins)
    val publisherLeftInset = with(density) { boxGeometry.contentLeftPx.toDp() }
    val publisherTopInset = with(density) { boxGeometry.topInsetPx.toDp() }
    val publisherBottomInset = with(density) { boxGeometry.bottomInsetPx.toDp() }
    val leftPadding = basePadding + publisherLeftInset + leftInset
    val effectiveVTop = vTop + publisherTopInset
    val effectiveVBottom = vBottom + publisherBottomInset
    val publisherOwnsBackground = settings.bookStyles &&
        hasPublisherBoxBackground(publisherBoxes)
    val surroundingBackground = if (settings.bookStyles) {
        publisherBoxEffectiveBackground(publisherBoxes, colors.background)
    } else {
        colors.background
    }

    val actuallyInvert = appSettings.autoInvertImages && appSettings.theme == AppTheme.OLED
    val publisherInk = publisherColorPair(
        block = when (element) {
            is ContentElement.Paragraph -> element.block
            is ContentElement.Heading -> element.block
            is ContentElement.Table -> element.block
            else -> null
        },
        enabled = settings.bookStyles,
        defaultForeground = colors.text,
        surroundingBackground = surroundingBackground,
        backgroundAlreadyApplied = publisherOwnsBackground,
    ).foreground

    // The text gets the EXACT pixel width pagination measured with. Sizing
    // it with fillMaxWidth + paddings rounds every inset separately and can
    // end up 1px narrower — a word then wraps to an extra line only on
    // screen and the page bottom gets clipped.
    val exactTextWidth = with(density) {
        (boxGeometry.contentWidthPx - startInset.roundToPx() - endInset.roundToPx())
            .coerceAtLeast(1)
            .toDp()
    }

    // The book surface owns a deterministic physical coordinate system even
    // when Android's UI locale is RTL. The direct child remains full-width,
    // so an ambient RTL LazyColumn cannot cross-axis-align a narrow paragraph
    // to the opposite edge; paragraph TextDirection still comes from the book.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val columnLeftPx = with(density) { basePadding.toPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .drawPublisherBoxes(boxGeometry, columnLeftPx, fontSizePx, publisherInk),
        ) {
            when (element) {
        is ContentElement.Paragraph -> {
            // A float rendered as a plain block image above its paragraph
            // (publisher formatting off) — its own synthesized page part.
            if (floatImagePath != null) {
                val heightModifier = if (imageHeightPx != null) {
                    with(LocalDensity.current) { Modifier.height(imageHeightPx.toDp()) }
                } else {
                    Modifier.heightIn(max = ReaderMetrics.maxImageHeight)
                }
                AsyncImage(
                    model = File(floatImagePath),
                    contentDescription = element.block?.floatImage?.altText,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter(actuallyInvert),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = basePadding, vertical = 12.dp)
                        .then(heightModifier),
                )
                return@Box
            }

            val defaultTextColor = when (element.style) {
                ParagraphStyle.QUOTE -> colors.secondaryText
                else -> colors.text
            }
            val colorPair = publisherColorPair(
                element.block,
                settings.bookStyles,
                defaultTextColor,
                surroundingBackground,
                backgroundAlreadyApplied = publisherOwnsBackground,
            )
            val linkColor = readableReaderForeground(colors.accent, colorPair.effectiveBackground)
            fun decorated(raw: androidx.compose.ui.text.AnnotatedString) =
                raw.withPublisherColors(settings.bookStyles, colorPair)
                    .withFootnoteLinks(linkColor, footnotes)
                    .withSearchHighlight(searchHighlight, colors.accent.copy(alpha = 0.3f))

            // Paged mode: a stored side-box composite part (drop cap/float).
            if (sideBox != null && textOverride != null) {
                val besideDisplay = remember(
                    textOverride, settings.bookStyles, colorPair,
                    colors, footnotes, searchHighlight,
                ) { decorated(textOverride) }
                SideBoxComposite(
                    element = element,
                    sideBox = sideBox,
                    besideText = besideDisplay,
                    settings = settings,
                    fontSize = fontSize,
                    bookFonts = bookFonts,
                    language = language,
                    colors = colors,
                    surroundingBackground = surroundingBackground,
                    publisherBackgroundAlreadyApplied = publisherOwnsBackground,
                    totalWidthPx = with(LocalDensity.current) { exactTextWidth.roundToPx() },
                    invertImages = actuallyInvert,
                    highlights = highlights,
                    itemIndex = itemIndex,
                    footnotes = footnotes,
                    searchHighlight = searchHighlight,
                    modifier = Modifier.absolutePadding(
                        left = leftPadding,
                        top = effectiveVTop,
                        bottom = effectiveVBottom,
                    ).then(
                        colorPair.background
                            ?.takeUnless { publisherOwnsBackground }
                            ?.let { Modifier.background(it) }
                            ?: Modifier,
                    ),
                )
                return@Box
            }

            // Scroll mode: plan the composite locally (whole paragraph in
            // one item — composite plus the full-width remainder below it).
            val isScrollMode = textOverride == null
            if (isScrollMode) {
                val scrollMeasurer = rememberTextMeasurer(cacheSize = 0)
                val density = LocalDensity.current
                val widthPx = with(density) { exactTextWidth.roundToPx() }
                val plan = remember(
                    element,
                    publisherFloat,
                    widthPx,
                    viewportHeightPx,
                    density.density,
                    density.fontScale,
                    settings,
                    fontSize,
                    language,
                    bookFonts,
                ) {
                    planSideBox(
                        element, scrollMeasurer, density, settings, fontSize,
                        bookFonts, language, widthPx,
                        publisherFloat = publisherFloat,
                        viewportHeightPx = viewportHeightPx,
                    )
                }
                val floatBlock = element.block?.floatImage
                    ?.takeIf { !settings.bookStyles }
                if (plan != null || floatBlock != null) {
                    Column(
                        Modifier
                            .absolutePadding(
                                left = leftPadding,
                                top = effectiveVTop,
                                bottom = effectiveVBottom,
                            )
                            .width(exactTextWidth)
                            .then(
                                colorPair.background
                                    ?.takeUnless { publisherOwnsBackground }
                                    ?.let { Modifier.background(it) }
                                    ?: Modifier,
                            ),
                    ) {
                        if (floatBlock != null) {
                            AsyncImage(
                                model = File(floatBlock.path),
                                contentDescription = floatBlock.altText,
                                contentScale = ContentScale.Fit,
                                colorFilter = imageColorFilter(actuallyInvert),
                                modifier = Modifier
                                    .width(exactTextWidth)
                                    .heightIn(max = ReaderMetrics.maxImageHeight)
                                    .padding(bottom = 12.dp),
                            )
                        }
                        if (plan != null) {
                            val capLen = plan.capText?.length ?: 0
                            val beside = remember(
                                element, plan, settings.bookStyles, colorPair,
                                colors, footnotes, searchHighlight,
                            ) {
                                decorated(element.text.subSequence(capLen, plan.besideEndChar))
                            }
                            SideBoxComposite(
                                element = element,
                                sideBox = plan,
                                besideText = beside,
                                settings = settings,
                                fontSize = fontSize,
                                bookFonts = bookFonts,
                                language = language,
                                colors = colors,
                                surroundingBackground = surroundingBackground,
                                publisherBackgroundAlreadyApplied = publisherOwnsBackground,
                                totalWidthPx = widthPx,
                                invertImages = actuallyInvert,
                                highlights = highlights,
                                itemIndex = itemIndex,
                                footnotes = footnotes,
                                searchHighlight = searchHighlight,
                            )
                            if (plan.besideEndChar < element.text.length) {
                                val remainder = remember(
                                    element, plan, settings.bookStyles, colorPair,
                                    colors, footnotes, searchHighlight,
                                ) {
                                    decorated(
                                        element.text.subSequence(
                                            plan.besideEndChar, element.text.length,
                                        ),
                                    )
                                }
                                val remainderBidi = remember(remainder) {
                                    BidiLayoutText.of(remainder)
                                }
                                val tail = rememberTextFragment(
                                    highlights, itemIndex,
                                    charStart = plan.besideEndChar,
                                    length = element.text.length - plan.besideEndChar,
                                    bidi = remainderBidi,
                                )
                                ReaderText(
                                    text = remainderBidi.display,
                                    style = ReaderMetrics
                                        .textStyle(
                                            element, settings, fontSize,
                                            isParagraphStart = false,
                                            bookFonts = bookFonts,
                                            language = language,
                                        )
                                        .copy(color = colorPair.foreground),
                                    onTextLayout = { tail?.layout = it },
                                    modifier = Modifier
                                        .width(exactTextWidth)
                                        .readerHighlights(tail, highlights),
                                    invertImages = actuallyInvert, bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles, maxWidthPx = with(density) { exactTextWidth.roundToPx() },
                                )
                            }
                        } else {
                            val whole = remember(
                                element, settings.bookStyles, colorPair,
                                colors, footnotes, searchHighlight,
                            ) {
                                decorated(element.text)
                            }
                            val wholeBidi = remember(whole) { BidiLayoutText.of(whole) }
                            val fragment = rememberTextFragment(
                                highlights, itemIndex, charStart = 0, length = element.text.length,
                                bidi = wholeBidi,
                            )
                            ReaderText(
                                text = wholeBidi.display,
                                style = ReaderMetrics
                                    .textStyle(
                                        element, settings, fontSize,
                                        isParagraphStart, bookFonts, language,
                                    )
                                    .copy(color = colorPair.foreground),
                                onTextLayout = { fragment?.layout = it },
                                modifier = Modifier
                                    .width(exactTextWidth)
                                    .readerHighlights(fragment, highlights),
                                invertImages = actuallyInvert, bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles, maxWidthPx = with(density) { exactTextWidth.roundToPx() },
                            )
                        }
                    }
                    return@Box
                }
            }

            val raw = textOverride ?: element.text
            val decoratedDisplay = remember(
                raw, settings.bookStyles, colorPair, colors, footnotes, searchHighlight,
            ) {
                decorated(raw)
            }
            val bidiDisplay = remember(decoratedDisplay) {
                BidiLayoutText.of(decoratedDisplay)
            }
            val display = bidiDisplay.display
            val fragment = rememberTextFragment(
                highlights, itemIndex, charStart, raw.length, bidiDisplay,
            )
            ReaderText(
                text = display,
                style = ReaderMetrics
                    .textStyle(element, settings, fontSize, isParagraphStart, bookFonts, language)
                    .withPublisherHangingIndent(boxGeometry, density, isParagraphStart)
                    .copy(color = colorPair.foreground),
                onTextLayout = { fragment?.layout = it },
                modifier = Modifier
                    .absolutePadding(
                        left = leftPadding,
                        top = effectiveVTop,
                        bottom = effectiveVBottom,
                    )
                    .width(exactTextWidth)
                    .then(
                        colorPair.background
                            ?.takeUnless { publisherOwnsBackground }
                            ?.let { Modifier.background(it) }
                            ?: Modifier,
                    )
                    .readerHighlights(fragment, highlights),
                invertImages = actuallyInvert, bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles, maxWidthPx = with(density) { exactTextWidth.roundToPx() },
            )
        }

        is ContentElement.Heading -> {
            val raw = textOverride ?: element.styledText
            val colorPair = publisherColorPair(
                element.block,
                settings.bookStyles,
                colors.text,
                surroundingBackground,
                backgroundAlreadyApplied = publisherOwnsBackground,
            )
            val linkColor = readableReaderForeground(colors.accent, colorPair.effectiveBackground)
            val decoratedDisplay = remember(
                raw, settings.bookStyles, colorPair, linkColor, footnotes, searchHighlight, colors.accent,
            ) {
                raw.withPublisherColors(settings.bookStyles, colorPair)
                    .withFootnoteLinks(linkColor, footnotes)
                    .withSearchHighlight(searchHighlight, colors.accent.copy(alpha = 0.3f))
            }
            val bidiDisplay = remember(decoratedDisplay) {
                BidiLayoutText.of(decoratedDisplay)
            }
            val display = bidiDisplay.display
            val fragment = rememberTextFragment(
                highlights, itemIndex, charStart, raw.length, bidiDisplay,
            )
            ReaderText(
                text = display,
                style = ReaderMetrics
                    .textStyle(element, settings, fontSize, isParagraphStart, bookFonts, language)
                    .withPublisherHangingIndent(boxGeometry, density, isParagraphStart)
                    .copy(color = colorPair.foreground),
                onTextLayout = { fragment?.layout = it },
                modifier = Modifier
                    .absolutePadding(
                        left = leftPadding,
                        top = effectiveVTop,
                        bottom = effectiveVBottom,
                    )
                    .width(exactTextWidth)
                    .then(
                        colorPair.background
                            ?.takeUnless { publisherOwnsBackground }
                            ?.let { Modifier.background(it) }
                            ?: Modifier,
                    )
                    .readerHighlights(fragment, highlights),
                invertImages = actuallyInvert, bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles, maxWidthPx = with(density) { exactTextWidth.roundToPx() },
            )
        }

        is ContentElement.Image -> {
            val imageAppearance = publisherImageAppearance(
                element, settings.bookStyles, surroundingBackground, publisherInk, actuallyInvert,
            )
            val availableImageWidthPx = with(density) { exactTextWidth.roundToPx() }
            val publisherOwnsImageWidth = publisherBoxesOwnImageWidth(
                publisherBoxes,
                settings.bookStyles,
            )
            val displayImageWidthPx = imageWidthPx(
                element = element,
                contentWidthPx = availableImageWidthPx,
                fontSizePx = fontSizePx,
                publisherOwnsWidth = publisherOwnsImageWidth,
            )
            val displayImageHeightPx = imageHeightPx ?:
                com.example.frogreader.ui.reader.imageHeightPx(
                element = element,
                contentWidthPx = availableImageWidthPx,
                fontSizePx = fontSizePx,
                maxHeightPx = with(density) { ReaderMetrics.maxImageHeight.roundToPx() },
                publisherOwnsWidth = publisherOwnsImageWidth,
                viewportHeightPx = viewportHeightPx.takeIf { settings.bookStyles },
                )
            // Percentage-width block images are centered in their containing
            // publisher box. Full-width artwork is unchanged.
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .absolutePadding(
                        left = leftPadding,
                        top = effectiveVTop,
                        bottom = effectiveVBottom,
                    )
                    .width(exactTextWidth),
            ) {
                // No corner rounding: an illustration or a map is the book's
                // own artwork and must arrive exactly as authored.
                AsyncImage(
                    model = File(element.path),
                    contentDescription = element.altText,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter(imageAppearance.invert),
                    modifier = Modifier
                        .width(with(density) { displayImageWidthPx.toDp() })
                        .height(with(density) { displayImageHeightPx.coerceAtLeast(1).toDp() })
                        .then(imageAppearance.background?.let { Modifier.background(it) } ?: Modifier),
                )
            }
        }

        ContentElement.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = effectiveVTop, bottom = effectiveVBottom),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(
                color = colors.secondaryText.copy(alpha = 0.4f),
                modifier = Modifier.width(96.dp),
            )
        }

        is ContentElement.Spacer -> Spacer(
            Modifier.height(
                ReaderMetrics.spacerHeight(element, fontSize) +
                    effectiveVTop + effectiveVBottom,
            ),
        )

        is ContentElement.Table -> {
            // Paged mode delivers the measured layout with the part; scroll
            // mode measures once locally (no page-fit contract there).
            val scrollMeasurer = rememberTextMeasurer(cacheSize = 0)
            val density = LocalDensity.current
            val widthPx = boxGeometry.contentWidthPx
            val layout = tableLayout ?: remember(
                element,
                widthPx,
                density.density,
                density.fontScale,
                settings,
                fontSize,
                language,
                bookFonts,
            ) {
                measureTableLayout(
                    element, scrollMeasurer, density, settings,
                    fontSize, widthPx, language, bookFonts,
                )
            }
            val centeredTableInset = if (settings.bookStyles && publisherBoxes.lastOrNull()?.style?.horizontalAlign ==
                com.example.frogreader.data.model.PublisherBoxAlign.CENTER) {
                with(density) { ((widthPx - layout.tableWidthPx).coerceAtLeast(0) / 2f).toDp() }
            } else 0.dp
            TableBlock(
                table = element,
                layout = layout,
                rowStart = if (tableRowStart >= 0) tableRowStart else 0,
                rowEnd = if (tableRowEnd >= 0) tableRowEnd else element.rows.size,
                headerRepeated = tableHeaderRepeated,
                settings = settings,
                fontSize = fontSize,
                language = language,
                bookFonts = bookFonts,
                invertImages = actuallyInvert,
                footnotes = footnotes,
                searchHighlight = searchHighlight,
                colors = colors,
                surroundingBackground = surroundingBackground,
                publisherBackgroundAlreadyApplied = publisherOwnsBackground,
                highlights = highlights,
                itemIndex = itemIndex,
                modifier = Modifier.absolutePadding(
                    left = basePadding + publisherLeftInset + centeredTableInset,
                    top = effectiveVTop,
                    bottom = effectiveVBottom,
                ).then(
                    publisherColorPair(
                        element.block,
                        settings.bookStyles,
                        colors.text,
                        surroundingBackground,
                        backgroundAlreadyApplied = publisherOwnsBackground,
                    ).background
                        ?.takeUnless { publisherOwnsBackground }
                        ?.let { Modifier.background(it) }
                        ?: Modifier,
                ),
            )
            }
        }
    }
}
}

/** Hides/shows system bars with the chrome and keeps icon contrast correct. */
@Composable
private fun SystemBarsEffect(theme: AppTheme, chromeVisible: Boolean) {
    val activity = LocalActivity.current
    val currentTheme by rememberUpdatedState(theme)

    LaunchedEffect(theme, chromeVisible) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !theme.isDark()
        controller.isAppearanceLightNavigationBars = !theme.isDark()
        // Transient-by-swipe, not BEHAVIOR_DEFAULT: under DEFAULT anything that
        // reveals the bars leaves them up for good, and immersion measurably
        // lasted about three seconds before some system event ended it.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            // Reset the BEHAVIOUR first, and not just the visibility. Left in
            // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, the status bar comes back
            // as a TRANSIENT bar — which Android draws with its own translucent
            // dark scrim — and only loses it once the system settles the bar
            // into a normal one. That was the dark band flashing across the top
            // of the library for a tenth of a second after closing a book.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !currentTheme.isDark()
            controller.isAppearanceLightNavigationBars = !currentTheme.isDark()
        }
    }
}

// -------------------------------------------------------------------- chrome

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTopBar(
    visible: Boolean,
    title: String,
    author: String?,
    colors: ReaderColors,
    chromeStyle: ReaderChromeStyle,
    searchVisible: Boolean,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    panelFraction: () -> Float = { 0f },
) {
    val floating = chromeStyle == ReaderChromeStyle.FLOATING
    val safeInsets = WindowInsets.systemBarsIgnoringVisibility.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
    ).asPaddingValues()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val headerAlpha by animateFloatAsState(
        targetValue = if (searchVisible) 0f else 1f,
        animationSpec = tween(120),
        label = "readerHeaderAlpha",
    )

    AnimatedVisibility(
        visible = visible || searchVisible,
        modifier = modifier,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
    ) {
        ReaderBarSurface(
            colors = colors,
            chromeStyle = chromeStyle,
            topBar = true,
            modifier = readerBarFade()
                .graphicsLayer {
                    // Ignore drawer pull when searching so the field stays put.
                    translationY = if (searchVisible) 0f else -panelFraction() * size.height * 1.25f
                }
                .then(
                    if (floating) Modifier.padding(safeInsets)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                    else Modifier,
                )
                .fillMaxWidth(),
        ) {
            // The book header always measures the bar. Search fills that exact
            // space, including wrapped metadata, without changing its height.
            Box(modifier = if (floating) Modifier else Modifier.padding(safeInsets)) {
                ReaderBookHeader(
                    title, author, colors, onBack, onSearch, chromeStyle,
                    enabled = !searchVisible,
                    modifier = Modifier
                        .graphicsLayer { alpha = headerAlpha }
                        .then(if (searchVisible) Modifier.clearAndSetSemantics {} else Modifier),
                )
                AnimatedVisibility(
                    visible = searchVisible,
                    modifier = Modifier.matchParentSize(),
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(100)),
                ) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                            .padding(horizontal = if (floating) 6.dp else 12.dp, vertical = 6.dp),
                    ) {
                        ReaderChromeButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                            colors = colors,
                            onClick = onCloseSearch,
                            chromeStyle = chromeStyle,
                        )
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.reader_search_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onChromeVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onChrome),
                                cursorBrush = SolidColor(colors.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                            )
                        }
                        ReaderChromeButton(
                            icon = if (searchQuery.isNotEmpty()) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = stringResource(
                                if (searchQuery.isNotEmpty()) R.string.reader_search_clear else R.string.reader_search,
                            ),
                            colors = colors,
                            onClick = {
                                if (searchQuery.isNotEmpty()) onQueryChange("")
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                            chromeStyle = chromeStyle,
                        )
                    }
                }
            }
        }

    }
}

/** What the pull-up panel of the bottom bar is currently showing. */
private enum class BarPanel { SETTINGS, CONTENTS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBottomBar(
    visible: Boolean,
    showReturnButton: Boolean,
    returnLabel: String,
    returnIcon: ImageVector,
    onReturn: () -> Unit,
    allowBackDismiss: Boolean,
    colors: ReaderColors,
    panelFraction: androidx.compose.runtime.MutableFloatState,
    /** First page of each chapter (paged mode, full pagination) or null. */
    chapterStartPages: List<Int>?,
    /** Whole-book page count, unavailable while pagination is pending or in scroll mode. */
    bookPageCount: Int?,
    chapterStops: List<Float>,
    /** False while paged-mode page counts are still being computed. */
    progressValuesReady: Boolean,
    ready: ReaderState.Ready,
    book: Book?,
    currentChapter: Int,
    currentNavigationIndex: Int?,
    chapterParents: List<String>,
    chapterLabel: String,
    bookFraction: Float,
    modifier: Modifier = Modifier,
    chapterAtFraction: ((Float) -> ReaderChapterPreview)? = null,
    /** Returns true only when the committed value changes the discrete destination. */
    onSeekBook: (Float) -> Boolean,
    settings: ReaderSettings,
    appSettings: AppSettings,
    brightness: Float?,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onUpdateApp: ((AppSettings) -> AppSettings) -> Unit,
    onBrightnessChange: (Float?) -> Unit,
    onChapterClick: (ReaderNavigationTarget) -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onCopyQuote: (String) -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onRemoveQuote: (String) -> Unit,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
) {
    val chromeStyle = appSettings.readerChromeStyle
    val floating = chromeStyle == ReaderChromeStyle.FLOATING
    val panelScheme = appColorSchemeFor(appSettings.theme, appSettings.dynamicColor)
    val safeInsets = WindowInsets.systemBarsIgnoringVisibility.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    ).asPaddingValues()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // The panel pulls out of the bar itself (no modal sheet). Two stops:
    // half a screen by default (the page stays visible for live preview),
    // and fully open — the bar's top edge aligned with the top bar's pill.
    // The chrome around the panel is measured, not guessed, so the full
    // height is exact on any screen.
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val screenHeight = with(density) { windowHeightPx.toDp() }
    // Half-open height: starts as an estimate, then snaps to the measured
    // height of the settings content up to the margins/mode row, so exactly
    // that much is visible when the panel opens.
    var preferredPeekPx by remember {
        mutableFloatStateOf(with(density) { (screenHeight * 0.48f).toPx() })
    }
    val statusTopPx = with(density) {
        WindowInsets.systemBarsIgnoringVisibility.asPaddingValues().calculateTopPadding().toPx()
    }
    val navBottomPx = with(density) {
        WindowInsets.systemBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding().toPx()
    }
    var barChromePx by remember { mutableFloatStateOf(with(density) { 110.dp.toPx() }) }
    val fullPx = (
        windowHeightPx - statusTopPx - navBottomPx -
            with(density) { if (floating) 16.dp.toPx() else 0f } - barChromePx
        ).coerceAtLeast(0f)
    val halfPx = preferredPeekPx.coerceAtMost(fullPx)
    val panelMotion = remember(scope) { ReaderPanelMotion(scope) }
    val panelHeight = panelMotion.height
    val panelOpen = panelMotion.isOpen
    var panelContent by remember { mutableStateOf(BarPanel.SETTINGS) }
    var contentsOpenRequest by remember { mutableIntStateOf(0) }

    // Wrapped metadata and rotation can reduce the room available to the drawer.
    LaunchedEffect(fullPx) {
        if (panelMotion.targetHeight > fullPx) panelMotion.animateTo(fullPx)
    }

    fun settlePanel(targetPx: Float) = panelMotion.animateTo(targetPx)

    // This panel is visually above the book, so Android Back must dismiss it
    // before leaving the reader. Its handler follows the reader-level
    // handler, which gives the topmost surface the expected priority.
    PredictiveBackHandler(enabled = allowBackDismiss && visible && panelOpen) { progress ->
        panelMotion.beginBack()
        try {
            progress.collect { event -> panelMotion.seekBack(event.progress) }
            panelMotion.finishBack(committed = true)
        } catch (cancelled: CancellationException) {
            panelMotion.finishBack(committed = false)
            throw cancelled
        }
    }

    fun settleWithVelocity(velocity: Float) {
        val position = panelHeight.value
        val anchors = listOf(0f, halfPx, fullPx)
        val target = when {
            // A long/strong fling skips straight to the end position.
            velocity < -2500f -> fullPx
            velocity > 2500f -> 0f
            velocity < -400f -> anchors.filter { it > position + 1f }.minOrNull() ?: fullPx
            velocity > 400f -> anchors.filter { it < position - 1f }.maxOrNull() ?: 0f
            else -> anchors.minByOrNull { abs(it - position) } ?: 0f
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        settlePanel(target)
    }

    // The chapter heading and the expanded contents header drag the panel.
    val panelDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        onDragStarted = { panelMotion.startDrag() },
        state = rememberDraggableState { delta ->
            panelMotion.snapTo((panelHeight.value - delta).coerceIn(0f, fullPx))
        },
        onDragStopped = { velocity -> settleWithVelocity(velocity) },
    )

    // Lets the panel be dragged by its own content (theme chips, lists…):
    // pulling up grows the panel before the content scrolls, pulling down
    // shrinks it once the content is at its top — like a bottom sheet.
    val panelNestedScroll = remember(halfPx, fullPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = available.y
                if (dy > 0f && panelHeight.value > 0f) {
                    val target = (panelHeight.value - dy).coerceAtLeast(0f)
                    val used = panelHeight.value - target
                    panelMotion.snapTo(target)
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val anchors = listOf(0f, halfPx, fullPx)
                if (anchors.none { abs(it - panelHeight.value) < 1f }) {
                    settleWithVelocity(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    /** Opens [content], or closes the panel when its button is tapped again. */
    fun togglePanel(content: BarPanel) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        when {
            !panelMotion.isOpen -> {
                if (content == BarPanel.CONTENTS) contentsOpenRequest++
                panelContent = content
                settlePanel(halfPx)
            }

            panelContent != content -> {
                if (content == BarPanel.CONTENTS) contentsOpenRequest++
                panelContent = content
            }
            else -> settlePanel(0f)
        }
    }

    // Hiding the chrome (center tap) also puts the panel away.
    LaunchedEffect(visible) {
        if (!visible) {
            panelMotion.snapTo(0f)
        }
    }

    // Share the pull progress with the rest of the chrome (top bar).
    LaunchedEffect(halfPx) {
        snapshotFlow { (panelHeight.value / halfPx.coerceAtLeast(1f)).coerceIn(0f, 1f) }
            .collect { panelFraction.floatValue = it }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Column(
            modifier = readerBarFade().then(
                if (floating) Modifier.padding(safeInsets)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                else Modifier.fillMaxWidth(),
            ),
        ) {
            // Share chrome motion, but cancel the panel's upward growth for
            // the exiting button. It fades at its original position while
            // settings open immediately underneath it.
            ReaderReturnAffordance(
                // Reveal as soon as the panel clears the button, without
                // waiting for the spring's almost invisible settling tail.
                visible = showReturnButton && !panelOpen &&
                    panelHeight.value <= with(density) { 12.dp.toPx() },
                colors = colors,
                onClick = onReturn,
                label = returnLabel,
                icon = returnIcon,
                containerColor = if (appSettings.theme == AppTheme.SEPIA) colors.controlButton else colors.chrome,
                modifier = Modifier
                    .align(AbsoluteAlignment.Right)
                    .then(
                        if (floating) Modifier else Modifier.padding(
                            WindowInsets.systemBarsIgnoringVisibility.only(
                                WindowInsetsSides.Horizontal,
                            ).asPaddingValues(),
                        ).absolutePadding(right = 8.dp),
                    )
                    // Align the button face, excluding the shadow's invisible
                    // horizontal gutter from the width used by the Column.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val gutter = 12.dp.roundToPx()
                        layout((placeable.width - gutter * 2).coerceAtLeast(0), placeable.height) {
                            placeable.place(-gutter, 0)
                        }
                    }
                    .zIndex(1f)
                    .graphicsLayer { translationY = panelHeight.value },
            )
            ReaderBarSurface(
                colors = colors,
                chromeStyle = chromeStyle,
                topBar = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        barChromePx = size.height - panelHeight.value - if (floating) 0f else navBottomPx
                    },
            ) {
                Column(
                    modifier = (if (floating) Modifier else Modifier.padding(safeInsets))
                        .padding(start = 12.dp, end = 12.dp, top = if (floating) 8.dp else 6.dp, bottom = 8.dp),
                ) {
                    ReaderChapterFooter(
                        chapterLabel = chapterLabel,
                        chapterParents = chapterParents,
                        bookFraction = bookFraction,
                        bookPageCount = bookPageCount,
                        chapterStops = chapterStops,
                        chapterStartPages = chapterStartPages,
                        chapterStarts = ready.chapterStarts,
                        itemCount = ready.items.size,
                        chapterAtFraction = chapterAtFraction,
                        progressValuesReady = progressValuesReady,
                        colors = colors,
                        chromeStyle = chromeStyle,
                        contentsSelected = panelOpen && panelContent == BarPanel.CONTENTS,
                        settingsSelected = panelOpen && panelContent == BarPanel.SETTINGS,
                        onContents = { togglePanel(BarPanel.CONTENTS) },
                        onSettings = { togglePanel(BarPanel.SETTINGS) },
                        onSeekBook = onSeekBook,
                        modifier = panelDrag,
                        panelContent = {
                            // Panel revealed by the pull: settings or contents/bookmarks/
                            // quotes, depending on which button summoned it.
                            val panelDp = with(density) { panelHeight.value.toDp() }
                            if (panelDp > 0.dp) {
                                Box(
                                    Modifier
                                        .height(panelDp)
                                        .background(colors.chrome)
                                        .nestedScroll(panelNestedScroll),
                                ) {
                                    when (panelContent) {
                                        BarPanel.SETTINGS -> Column(
                                            modifier = Modifier.verticalScroll(rememberScrollState()),
                                        ) {
                                            ReaderSettingsControls(
                                                settings = settings,
                                                appSettings = appSettings,
                                                colors = colors,
                                                publisherStyle = ready.publisherStyle,
                                                brightness = brightness,
                                                onUpdate = onUpdate,
                                                onUpdateApp = onUpdateApp,
                                                onBrightnessChange = onBrightnessChange,
                                                onPeekHeight = { measured ->
                                                    val target = measured +
                                                        with(density) { 10.dp.toPx() }
                                                    if (abs(target - halfPx) > 2f) {
                                                        val previous = halfPx
                                                        preferredPeekPx = target
                                                        panelMotion.updatePeek(previous, target.coerceAtMost(fullPx))
                                                    }
                                                },
                                            )
                                        }

                                        BarPanel.CONTENTS -> ReaderPanelsContent(
                                            colorScheme = panelScheme,
                                            ready = ready,
                                            book = book,
                                            currentChapter = currentChapter,
                                            currentNavigationIndex = currentNavigationIndex,
                                            openRequest = contentsOpenRequest,
                                            chapterStartPages = chapterStartPages,
                                            modifier = panelDrag,
                                            onChapterClick = onChapterClick,
                                            onBookmarkClick = { flatIndex ->
                                                settlePanel(0f)
                                                onBookmarkClick(flatIndex)
                                            },
                                            onRemoveBookmark = onRemoveBookmark,
                                            onCopyQuote = onCopyQuote,
                                            onQuoteClick = { quote ->
                                                settlePanel(0f)
                                                onQuoteClick(quote)
                                            },
                                            onRemoveQuote = onRemoveQuote,
                                            bookmarked = bookmarked,
                                            onToggleBookmark = onToggleBookmark,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
