package com.example.frogreader.ui.reader

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.ReaderHighlights
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
import com.example.frogreader.ui.theme.isDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val viewModel: ReaderViewModel =
        viewModel(key = "reader-$bookId", factory = ReaderViewModel.factory(bookId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()

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
    SystemBarsEffect(appSettings.theme, chromeVisible)

    when (val current = state) {
        ReaderState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }

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

        is ReaderState.Ready -> ReaderContent(
            ready = current,
            settings = settings,
            viewModel = viewModel,
            appSettings = appSettings,
            chromeVisible = chromeVisible,
            onToggleChrome = { chromeVisible = !chromeVisible },
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReaderContent(
    ready: ReaderState.Ready,
    settings: ReaderSettings,
    viewModel: ReaderViewModel,
    appSettings: AppSettings,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    val colors = readerColors(
        theme = appSettings.theme,
        // Resolve against the page's light/dark character, not the current
        // system mode: a Beige page still needs a light Material You accent
        // even when the surrounding app is following dark mode.
        chromeScheme = appColorSchemeFor(appSettings.theme, appSettings.dynamicColor),
    )
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
    var seekPosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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

    // Accumulate reading time while the reader is on screen and resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var sessionStart = System.currentTimeMillis()
        var running = true
        fun flush() {
            if (running) {
                viewModel.addReadingTime((System.currentTimeMillis() - sessionStart) / 1000)
                running = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    sessionStart = System.currentTimeMillis()
                    running = true
                }

                Lifecycle.Event.ON_PAUSE -> flush()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flush()
        }
    }

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
            if (searchVisible) return@handler false // search overlay is up
            turnEvents.tryEmit(forward)
        }
        mainActivity?.volumeKeyHandler = handler
        onDispose {
            if (mainActivity?.volumeKeyHandler == handler) mainActivity.volumeKeyHandler = null
        }
    }

    val context = LocalContext.current

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
    BackHandler(enabled = selection.active) { selection.clear() }

    // Tappable footnote references ([53] → bottom sheet with the note).
    var noteToShow by remember { mutableStateOf<AnnotatedString?>(null) }
    // Saved quotes, resolved to book coordinates — painted by the same path
    // machinery as the live selection, so each one marks exactly its own text.
    val quoteRanges = remember(liveBook) {
        liveBook?.quotes.orEmpty().mapNotNull { it.range() }
    }
    val footnotes = remember(ready) {
        if (ready.notes.isEmpty() && ready.linkTargets.isEmpty()) {
            null
        } else {
            FootnoteHandler(
                notes = ready.notes,
                onNote = { note ->
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    noteToShow = note
                },
                linkTargets = ready.linkTargets,
                onNavigate = { itemIndex ->
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    seekPosition = itemIndex to 0
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // The selection's coordinate space: every anchor, handle and hit test
        // is expressed relative to this box, and its gesture detector is the
        // one that owns text selection now.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { selection.space = it }
                .pointerInput(selection) { bookSelectionGestures(selection, haptics) }
                // The loupe over the character being chosen — a fingertip
                // covers about three words at reading size. The platform
                // magnifier is API 28+; this modifier is a no-op below that,
                // which is the right behaviour on our minSdk 26.
                .magnifier(sourceCenter = { selection.magnifierSource() }),
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
                    onSeekPositionConsumed = { seekPosition = null },
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
                    onSeekPositionConsumed = { seekPosition = null },
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
                )
            }

            SelectionAutoAdvanceEffect(selection)
            SelectionHandles(selection, colors.accent)
        }

        // Outside the reading box on purpose: it carries the selection
        // gesture, which swallows taps to dismiss the selection, and would
        // swallow taps on these buttons too. Declared after it, so it is
        // hit-tested first and drawn above the page.
        SelectionToolbar(
            controller = selection,
            colors = colors,
            onQuote = {
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
                    )
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                selection.clear()
            },
            onTranslate = {
                val selected = selectedText()
                if (selected.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }
                selection.clear()
            },
            onCopy = {
                val selected = selectedText()
                if (selected.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(selected))
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

        val pagination by viewModel.pagination.collectAsStateWithLifecycle()
        val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
        val fullPages = pagination?.takeIf { !it.partial }?.pages
        // The last complete pagination: keeps the "go to page" card in place
        // even during the brief partial re-pagination after a settings change.
        var lastFullPages by remember { mutableStateOf<List<BookPage>?>(null) }
        LaunchedEffect(fullPages) { if (fullPages != null) lastFullPages = fullPages }
        val searchPages = fullPages ?: lastFullPages

        var topBarHeightPx by remember { mutableIntStateOf(0) }
        val topBarHeight = with(LocalDensity.current) { topBarHeightPx.toDp() }

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
                seekPosition = match.itemIndex to match.charStart
                searchVisible = false
            },
            onGoToPage = { page ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                searchPages?.getOrNull(page)?.let { target ->
                    seekPosition = target.firstItemIndex to target.firstCharOffset
                }
                searchVisible = false
            },
            onClose = { searchVisible = false },
            topPadding = topBarHeight,
        )

        ReaderTopBar(
            visible = chromeVisible,
            title = ready.book.title,
            author = ready.book.author,
            colors = colors,
            searchVisible = searchVisible,
            searchQuery = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { searchVisible = true },
            onBack = onBack,
            onCloseSearch = { searchVisible = false },
            panelFraction = { panelFraction.floatValue },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeightPx = it.height },
        )

        val currentChapter = ready.chapterAt(displayedIndex.intValue)
        // True page progress in paged mode; element progress when scrolling.
        val pageInfo = pagePosition.value
        val livePage = livePagePosition.value
        val fraction = pageInfo?.let { (page, total) ->
            if (total <= 1) 1f else (livePage ?: page.toFloat()) / (total - 1)
        } ?: if (ready.items.size <= 1) {
            0f
        } else {
            (liveScrollPosition.value ?: displayedIndex.intValue.toFloat()) /
                (ready.items.size - 1)
        }

        // First page of every chapter — for the segmented book track and
        // page-accurate progress within the chapter.
        val chapterStartPages = remember(pagination, ready) {
            val pages = pagination?.takeIf { !it.partial }?.pages
            if (pages.isNullOrEmpty()) {
                null
            } else {
                ready.chapterStarts.map { start ->
                    pages.indexOfLast { it.firstItemIndex <= start }.coerceAtLeast(0)
                }
            }
        }
        val usePages = pageInfo != null && chapterStartPages != null
        val bookSegments = remember(ready, chapterStartPages, usePages) {
            if (usePages) {
                val total = ((pagination?.pages?.size ?: 1) - 1).coerceAtLeast(1)
                chapterStartPages.map { it.toFloat() / total }
            } else {
                val total = (ready.items.size - 1).coerceAtLeast(1)
                ready.chapterStarts.map { it.toFloat() / total }
            }
        }

        val chapterStart = ready.chapterStarts[currentChapter]
        val chapterEnd = ready.chapterStarts.getOrNull(currentChapter + 1) ?: ready.items.size
        val chapterFraction = if (usePages) {
            val page = livePage ?: pageInfo.first.toFloat()
            val startPage = chapterStartPages[currentChapter]
            val endPage = chapterStartPages.getOrNull(currentChapter + 1)?.minus(1)
                ?: (pageInfo.second - 1)
            if (endPage <= startPage) 1f else (page - startPage) / (endPage - startPage)
        } else {
            val span = chapterEnd - 1 - chapterStart
            val position = liveScrollPosition.value ?: displayedIndex.intValue.toFloat()
            if (span <= 0) 1f else ((position - chapterStart) / span).coerceIn(0f, 1f)
        }

        ReaderBottomBar(
            visible = chromeVisible,
            colors = colors,
            panelFraction = panelFraction,
            chapterStartPages = chapterStartPages,
            totalPages = pageInfo?.second,
            progressValuesReady = settings.readingMode == ReadingMode.SCROLL || usePages,
            ready = ready,
            book = liveBook,
            currentChapter = currentChapter,
            chapterLabel = ready.chapterTitles.getOrNull(currentChapter)
                ?: stringResource(R.string.reader_chapter_n, currentChapter + 1),
            chapterFraction = chapterFraction.coerceIn(0f, 1f),
            bookFraction = fraction.coerceIn(0f, 1f),
            bookSegments = bookSegments,
            onSeekChapter = { f ->
                if (usePages) {
                    val startPage = chapterStartPages[currentChapter]
                    val endPage = chapterStartPages.getOrNull(currentChapter + 1)?.minus(1)
                        ?: (pageInfo.second - 1)
                    val targetPage = startPage + (f * (endPage - startPage)).roundToInt()
                    seekFraction = targetPage.toFloat() / (pageInfo.second - 1).coerceAtLeast(1)
                } else {
                    seekTarget = chapterStart +
                        (f * (chapterEnd - 1 - chapterStart)).roundToInt().coerceAtLeast(0)
                }
            },
            onSeekBook = { f -> seekFraction = f },
            settings = settings,
            appSettings = appSettings,
            brightness = brightness,
            onUpdate = { transform -> viewModel.updateSettings(transform) },
            onUpdateApp = { transform -> viewModel.updateAppSettings(transform) },
            onBrightnessChange = setBrightness,
            onChapterClick = { chapterIndex ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                seekTarget = ready.chapterStarts[chapterIndex]
            },
            onBookmarkClick = { flatIndex ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                seekTarget = flatIndex
            },
            onRemoveBookmark = { viewModel.removeBookmark(it) },
            onCopyQuote = { text ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            onQuoteClick = { quote ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                // Anchored quotes know exactly where they are; the char-precise
                // seek lands on the right page even mid-paragraph.
                seekPosition = quote.startItem to quote.startChar
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
    }

    val paginationHolder by viewModel.pagination.collectAsStateWithLifecycle()
    if (settings.readingMode == ReadingMode.PAGES && paginationHolder == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }
    }

    noteToShow?.let { note ->
        NoteSheet(note = note, onDismiss = { noteToShow = null })
    }
}

/** Bottom sheet with the text of a tapped footnote. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(note: AnnotatedString, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Capped height + inner scroll: see sheetMaxContentHeight.
                .heightIn(max = sheetMaxContentHeight())
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_note_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Resolves link taps: a key with a note behind it opens the note popup,
 * anything else that names a place in the book jumps there (Contents
 * entries, cross-references).
 */
class FootnoteHandler(
    val notes: Map<String, AnnotatedString>,
    val onNote: (AnnotatedString) -> Unit,
    val linkTargets: Map<String, Int> = emptyMap(),
    val onNavigate: (Int) -> Unit = {},
) {
    /** True when tapping [key] does something. */
    fun handles(key: String): Boolean = key in notes || key in linkTargets

    fun open(key: String) {
        val note = notes[key]
        if (note != null) {
            onNote(note)
            return
        }
        linkTargets[key]?.let(onNavigate)
    }
}

/**
 * Highlights footnote references with the accent color and makes them
 * tappable. Adding color/links does not change text metrics, so paginated
 * text still fits its measured page.
 */
private fun AnnotatedString.withFootnoteLinks(
    accent: Color,
    handler: FootnoteHandler,
): AnnotatedString {
    val refs = (
        getStringAnnotations(FOOTNOTE_TAG, 0, length) +
            getStringAnnotations(LINK_TAG, 0, length)
        ).filter { handler.handles(it.item) }
    if (refs.isEmpty()) return this

    val builder = AnnotatedString.Builder(this)
    for (ref in refs) {
        builder.addLink(
            LinkAnnotation.Clickable(
                tag = FOOTNOTE_TAG,
                // Explicit styles: accent color, no underline, soft press glow.
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = accent,
                        textDecoration = TextDecoration.None,
                    ),
                    pressedStyle = SpanStyle(
                        color = accent,
                        textDecoration = TextDecoration.None,
                        background = accent.copy(alpha = 0.18f),
                    ),
                ),
                linkInteractionListener = LinkInteractionListener { handler.open(ref.item) },
            ),
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
private fun AnnotatedString.withSearchHighlight(
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
    seekPosition: Pair<Int, Int>?,
    onSeekPositionConsumed: () -> Unit,
    onToggleChrome: () -> Unit,
    pinchHandlers: PinchHandlers,
    brightnessHandlers: BrightnessHandlers,
    turnEvents: SharedFlow<Boolean>,
    footnotes: FootnoteHandler?,
    quoteRanges: List<BookSelection>,
    searchHighlight: String?,
    selection: SelectionController,
) {
    val scope = rememberCoroutineScope()
    val liveBook by viewModel.book.collectAsStateWithLifecycle()
    val initialIndex = viewModel.currentFlatIndex.coerceIn(0, ready.items.size - 1)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = if (initialIndex == ready.book.progress.elementIndex) {
            ready.book.progress.scrollOffset
        } else {
            0
        },
    )

    LaunchedEffect(ready) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                displayedIndex.intValue = index.coerceAtMost(ready.items.size - 1)
                if (index >= ready.items.size - 1) viewModel.markFinished()
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
        val item = target.coerceIn(0, ready.items.size - 1)
        livePosition.value = item.toFloat()
        listState.scrollToItem(item)
        onSeekConsumed()
    }
    LaunchedEffect(seekFraction) {
        val fraction = seekFraction ?: return@LaunchedEffect
        val item = (fraction * (ready.items.size - 1)).roundToInt()
            .coerceIn(0, ready.items.size - 1)
        livePosition.value = item.toFloat()
        listState.scrollToItem(item)
        onSeekFractionConsumed()
    }
    LaunchedEffect(seekPosition) {
        val (item, _) = seekPosition ?: return@LaunchedEffect
        val target = item.coerceIn(0, ready.items.size - 1)
        livePosition.value = target.toFloat()
        listState.scrollToItem(target)
        onSeekPositionConsumed()
    }
    LaunchedEffect(ready) {
        turnEvents.collect { forward ->
            val viewport = listState.layoutInfo.viewportSize.height * 0.88f
            listState.animateScrollBy(if (forward) viewport else -viewport)
        }
    }
    DisposableEffect(ready) {
        onDispose {
            viewModel.saveProgress(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }

    val highlights = rememberReaderHighlights(
        controller = selection,
        quotes = quoteRanges,
        quoteColor = colors.quoteHighlight,
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

    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .readerGestures(
                    onTapLeft = {
                        scope.launch {
                            listState.animateScrollBy(-listState.layoutInfo.viewportSize.height * 0.88f)
                        }
                    },
                    onTapRight = {
                        scope.launch {
                            listState.animateScrollBy(listState.layoutInfo.viewportSize.height * 0.88f)
                        }
                    },
                    onTapCenter = onToggleChrome,
                    pinchHandlers = pinchHandlers,
                    brightnessHandlers = brightnessHandlers,
                ),
            contentPadding = PaddingValues(
                top = stableInsets.calculateTopPadding() + 20.dp,
                bottom = stableInsets.calculateBottomPadding() + 48.dp,
            ),
        ) {
            items(ready.items.size, key = { it }) { index ->
                RenderPart(
                    element = ready.items[index].element,
                    textOverride = null,
                    isParagraphStart = true,
                    imageHeightPx = null,
                    settings = settings,
                    appSettings = appSettings,
                    fontSize = fontSize,
                    colors = colors,
                    contentWidth = contentWidth,
                    bookFonts = ready.bookFonts,
                    language = ready.language,
                    footnotes = footnotes,
                    searchHighlight = searchHighlight,
                    highlights = highlights,
                    itemIndex = index,
                )
            }
            item(key = "completion") {
                CompletionPage(
                    book = liveBook,
                    colors = colors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, bottom = 24.dp),
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
    seekPosition: Pair<Int, Int>?,
    onSeekPositionConsumed: () -> Unit,
    onToggleChrome: () -> Unit,
    pinchHandlers: PinchHandlers,
    brightnessHandlers: BrightnessHandlers,
    turnEvents: SharedFlow<Boolean>,
    footnotes: FootnoteHandler?,
    quoteRanges: List<BookSelection>,
    searchHighlight: String?,
    selection: SelectionController,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val liveBook by viewModel.book.collectAsStateWithLifecycle()

    // Element-based progress takes over again when paged mode leaves.
    DisposableEffect(Unit) {
        onDispose {
            pagePosition.value = null
            livePagePosition.value = null
        }
    }
    val density = LocalDensity.current
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

        val spec = remember(contentWidthPx, contentHeightPx, settings, ready) {
            PaginationSpec(
                contentWidthPx, contentHeightPx, density, settings, settings.fontSizeSp,
                bookFonts = ready.bookFonts,
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

        key(current.key, current.partial) {
            // A fresh layout generation per pager rebuild: a settings change
            // disposes this whole subtree and composes a new one, and for a
            // frame both are registered.
            val highlights = rememberReaderHighlights(
                controller = selection,
                quotes = quoteRanges,
                quoteColor = colors.quoteHighlight,
                selectionColor = colors.selection,
            )
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

            LaunchedEffect(pagerState) {
                snapshotFlow {
                    (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, (realPageCount - 1).coerceAtLeast(0).toFloat())
                }.collect { livePagePosition.value = it }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { settled ->
                    if (settled >= realPageCount) {
                        // Reached the completion screen.
                        pagePosition.value = (realPageCount - 1) to realPageCount
                        viewModel.markFinished()
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
                    pagePosition.value = settled to realPageCount
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
                    Triple(pagerState.targetPage, pagerState.isScrollInProgress, pagerState.currentPage)
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
                livePagePosition.value = page.toFloat()
                pagerState.scrollToPage(page)
                onSeekConsumed()
            }
            LaunchedEffect(seekFraction) {
                val fraction = seekFraction ?: return@LaunchedEffect
                val page = (fraction * (realPageCount - 1)).roundToInt()
                    .coerceIn(0, realPageCount - 1)
                livePagePosition.value = page.toFloat()
                pagerState.scrollToPage(page)
                onSeekFractionConsumed()
            }
            LaunchedEffect(seekPosition) {
                val (item, char) = seekPosition ?: return@LaunchedEffect
                val page = pages.indexOfLast { p ->
                    p.firstItemIndex < item ||
                        (p.firstItemIndex == item && p.firstCharOffset <= char)
                }.coerceAtLeast(0)
                livePagePosition.value = page.toFloat()
                pagerState.scrollToPage(page)
                onSeekPositionConsumed()
            }
            LaunchedEffect(pagerState) {
                turnEvents.collect { forward ->
                    val delta = if (forward) 1 else -1
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage + delta)
                            .coerceIn(0, realPageCount + extraPages - 1),
                    )
                }
            }

            // A selection drag held at the left or right edge turns pages.
            // Clamped to the real pages: auto-turning onto the completion
            // screen would mark the book finished and buzz for it.
            DisposableEffect(selection, pagerState, realPageCount) {
                val mine = SelectionAutoAdvance(
                    paged = true,
                    step = { direction, _ ->
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + direction)
                                .coerceIn(0, (realPageCount - 1).coerceAtLeast(0)),
                        )
                    },
                    settle = { pagerState.animateScrollToPage(pagerState.currentPage) },
                )
                selection.advance = mine
                selection.surfaceMoving = { pagerState.isScrollInProgress }
                onDispose {
                    if (selection.advance === mine) {
                        selection.advance = null
                        selection.surfaceMoving = { false }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .readerGestures(
                        onTapLeft = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        onTapRight = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        onTapCenter = onToggleChrome,
                        pinchHandlers = pinchHandlers,
                        brightnessHandlers = brightnessHandlers,
                    ),
            ) { pageIndex ->
                if (pageIndex < realPageCount) {
                    PageView(
                        page = pages[pageIndex],
                        settings = renderSettings,
                        appSettings = appSettings,
                        colors = colors,
                        topPadding = topPadding,
                        bottomPadding = bottomPadding,
                        contentWidth = maxWidth -
                            ReaderMetrics.horizontalPadding(renderSettings.pageMargins) * 2,
                        bookFonts = ready.bookFonts,
                        language = ready.language,
                        bookId = ready.book.id,
                        footnotes = footnotes,
                        searchHighlight = searchHighlight,
                        highlights = highlights,
                    )
                } else {
                    CompletionPage(
                        book = liveBook,
                        colors = colors,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPadding),
                    )
                }
            }

            val shownPage = pagerState.currentPage
            if (shownPage < realPageCount) {
                val footerText = if (settings.showChapterPagesLeft) {
                    val left = chapterLastPage[shownPage] - shownPage
                    if (left == 0) {
                        stringResource(R.string.reader_chapter_last_page)
                    } else {
                        stringResource(R.string.reader_chapter_pages_left, left)
                    }
                } else {
                    stringResource(R.string.reader_page_of, shownPage + 1, realPageCount)
                }
                Text(
                    text = footerText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = stableInsets.calculateBottomPadding() + 10.dp),
                )
            }
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
private fun PageView(
    page: BookPage,
    settings: ReaderSettings,
    appSettings: AppSettings,
    colors: ReaderColors,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    contentWidth: androidx.compose.ui.unit.Dp,
    bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily>,
    language: String?,
    bookId: String,
    footnotes: FootnoteHandler?,
    searchHighlight: String?,
    highlights: ReaderHighlights,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            // While a settings change is repaginating in the background, the
            // old page splits render with the new text style and can run past
            // the page bottom — clip at the real page boundary so the final
            // layout lands without a visible jump.
            .clipToBounds(),
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
): Modifier = this
    .pointerInput(Unit) {
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
private fun RenderPart(
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
) {
    val (vTop, vBottom) = ReaderMetrics.verticalPaddings(element, fontSize)
    val (startInset, endInset) =
        ReaderMetrics.horizontalInsets(element, contentWidth, fontSize)
    val basePadding = ReaderMetrics.horizontalPadding(settings.pageMargins)
    val startPadding = basePadding + startInset

    val actuallyInvert = appSettings.autoInvertImages && appSettings.theme == AppTheme.OLED

    // The text gets the EXACT pixel width pagination measured with. Sizing
    // it with fillMaxWidth + paddings rounds every inset separately and can
    // end up 1px narrower — a word then wraps to an extra line only on
    // screen and the page bottom gets clipped.
    val exactTextWidth = with(LocalDensity.current) {
        (contentWidth.roundToPx() - startInset.roundToPx() - endInset.roundToPx())
            .coerceAtLeast(1)
            .toDp()
    }

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
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter(actuallyInvert),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = basePadding, vertical = 12.dp)
                        .then(heightModifier),
                )
                return
            }

            val textColor = when (element.style) {
                ParagraphStyle.QUOTE -> colors.secondaryText
                else -> colors.text
            }
            fun decorated(raw: androidx.compose.ui.text.AnnotatedString) =
                (
                    if (footnotes != null) {
                        raw.withFootnoteLinks(colors.accent, footnotes)
                    } else {
                        raw
                    }
                    )
                    .withSearchHighlight(searchHighlight, colors.accent.copy(alpha = 0.3f))

            // Paged mode: a stored side-box composite part (drop cap/float).
            if (sideBox != null && textOverride != null) {
                val besideDisplay = remember(
                    textOverride, colors, footnotes, searchHighlight,
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
                    totalWidthPx = with(LocalDensity.current) { exactTextWidth.roundToPx() },
                    invertImages = actuallyInvert,
                    highlights = highlights,
                    itemIndex = itemIndex,
                    modifier = Modifier.padding(
                        start = startPadding, top = vTop, bottom = vBottom,
                    ),
                )
                return
            }

            // Scroll mode: plan the composite locally (whole paragraph in
            // one item — composite plus the full-width remainder below it).
            val isScrollMode = textOverride == null
            if (isScrollMode) {
                val scrollMeasurer = rememberTextMeasurer(cacheSize = 0)
                val density = LocalDensity.current
                val widthPx = with(density) { exactTextWidth.roundToPx() }
                val plan = remember(element, widthPx, settings, fontSize, language, bookFonts) {
                    planSideBox(
                        element, scrollMeasurer, density, settings, fontSize,
                        bookFonts, language, widthPx,
                    )
                }
                val floatBlock = element.block?.floatImage
                    ?.takeIf { !settings.bookStyles }
                if (plan != null || floatBlock != null) {
                    Column(Modifier.padding(start = startPadding, top = vTop, bottom = vBottom)) {
                        if (floatBlock != null) {
                            AsyncImage(
                                model = File(floatBlock.path),
                                contentDescription = null,
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
                            val beside = remember(element, plan, colors, footnotes, searchHighlight) {
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
                                totalWidthPx = widthPx,
                                invertImages = actuallyInvert,
                                highlights = highlights,
                                itemIndex = itemIndex,
                            )
                            if (plan.besideEndChar < element.text.length) {
                                val remainder = remember(element, plan, colors, footnotes, searchHighlight) {
                                    decorated(
                                        element.text.subSequence(
                                            plan.besideEndChar, element.text.length,
                                        ),
                                    )
                                }
                                val tail = rememberTextFragment(
                                    highlights, itemIndex,
                                    charStart = plan.besideEndChar,
                                    length = element.text.length - plan.besideEndChar,
                                )
                                Text(
                                    text = remainder,
                                    style = ReaderMetrics
                                        .textStyle(
                                            element, settings, fontSize,
                                            isParagraphStart = false,
                                            bookFonts = bookFonts,
                                            language = language,
                                        )
                                        .copy(color = textColor),
                                    onTextLayout = { tail?.layout = it },
                                    modifier = Modifier
                                        .width(exactTextWidth)
                                        .readerHighlights(tail, highlights),
                                    inlineContent = inlineImageContent(remainder, actuallyInvert),
                                )
                            }
                        } else {
                            val whole = remember(
                                element, colors, footnotes, searchHighlight,
                            ) {
                                decorated(element.text)
                            }
                            val fragment = rememberTextFragment(
                                highlights, itemIndex, charStart = 0, length = element.text.length,
                            )
                            Text(
                                text = whole,
                                style = ReaderMetrics
                                    .textStyle(
                                        element, settings, fontSize,
                                        isParagraphStart, bookFonts, language,
                                    )
                                    .copy(color = textColor),
                                onTextLayout = { fragment?.layout = it },
                                modifier = Modifier
                                    .width(exactTextWidth)
                                    .readerHighlights(fragment, highlights),
                                inlineContent = inlineImageContent(whole, actuallyInvert),
                            )
                        }
                    }
                    return
                }
            }

            val raw = textOverride ?: element.text
            val display = remember(raw, colors, footnotes, searchHighlight) {
                decorated(raw)
            }
            val fragment = rememberTextFragment(highlights, itemIndex, charStart, raw.length)
            Text(
                text = display,
                style = ReaderMetrics
                    .textStyle(element, settings, fontSize, isParagraphStart, bookFonts, language)
                    .copy(color = textColor),
                onTextLayout = { fragment?.layout = it },
                modifier = Modifier
                    .padding(start = startPadding, top = vTop, bottom = vBottom)
                    .width(exactTextWidth)
                    .readerHighlights(fragment, highlights),
                inlineContent = inlineImageContent(display, actuallyInvert),
            )
        }

        is ContentElement.Heading -> {
            val raw = textOverride ?: androidx.compose.ui.text.AnnotatedString(element.text)
            val fragment = rememberTextFragment(highlights, itemIndex, charStart, raw.length)
            Text(
                text = raw.withSearchHighlight(searchHighlight, colors.accent.copy(alpha = 0.3f)),
                style = ReaderMetrics
                    .textStyle(element, settings, fontSize, isParagraphStart, bookFonts, language)
                    .copy(color = colors.text),
                onTextLayout = { fragment?.layout = it },
                modifier = Modifier
                    .padding(start = startPadding, top = vTop, bottom = vBottom)
                    .width(exactTextWidth)
                    .readerHighlights(fragment, highlights),
            )
        }

        is ContentElement.Image -> {
            val heightModifier = when {
                imageHeightPx != null ->
                    with(LocalDensity.current) { Modifier.height(imageHeightPx.toDp()) }

                // Scroll mode has no measured height: honor the book's own
                // CSS size the same way pagination does.
                element.heightEm != null ->
                    Modifier.height((element.heightEm!! * fontSize).dp)

                else -> Modifier.heightIn(max = ReaderMetrics.maxImageHeight)
            }
            // No corner rounding: an illustration or a map is the book's own
            // artwork and must reach the reader exactly as it was drawn.
            AsyncImage(
                model = File(element.path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = imageColorFilter(actuallyInvert),
                modifier = Modifier
                    .fillMaxWidth(element.widthFrac ?: 1f)
                    .padding(horizontal = basePadding, vertical = vTop)
                    .then(heightModifier),
            )
        }

        ContentElement.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = vTop),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(
                color = colors.secondaryText.copy(alpha = 0.4f),
                modifier = Modifier.width(96.dp),
            )
        }

        is ContentElement.Spacer -> Spacer(
            Modifier.height(ReaderMetrics.spacerHeight(element, fontSize)),
        )

        is ContentElement.Table -> {
            // Paged mode delivers the measured layout with the part; scroll
            // mode measures once locally (no page-fit contract there).
            val scrollMeasurer = rememberTextMeasurer(cacheSize = 0)
            val density = LocalDensity.current
            val widthPx = with(density) { contentWidth.roundToPx() }
            val layout = tableLayout ?: remember(
                element, widthPx, settings, fontSize, language,
            ) {
                measureTableLayout(
                    element, scrollMeasurer, density, settings,
                    fontSize, widthPx, language,
                )
            }
            TableBlock(
                table = element,
                layout = layout,
                rowStart = if (tableRowStart >= 0) tableRowStart else 0,
                rowEnd = if (tableRowEnd >= 0) tableRowEnd else element.rows.size,
                headerRepeated = tableHeaderRepeated,
                settings = settings,
                fontSize = fontSize,
                language = language,
                colors = colors,
                highlights = highlights,
                itemIndex = itemIndex,
                modifier = Modifier.padding(
                    start = basePadding,
                    top = vTop,
                    bottom = vBottom,
                ),
            )
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
        }
    }
}

// -------------------------------------------------------------------- chrome

/** Icon in a soft rounded-square container, as in the redesign mockup. */
@Composable
private fun TonalBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    colors: ReaderColors,
    onClick: () -> Unit,
    tint: Color = colors.onChrome,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.onChrome.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun ReaderTopBar(
    visible: Boolean,
    title: String,
    author: String?,
    colors: ReaderColors,
    searchVisible: Boolean,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    panelFraction: () -> Float = { 0f },
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchVisible) {
        if (searchVisible) focusRequester.requestFocus()
    }

    AnimatedVisibility(
        visible = visible || searchVisible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 4.dp,
            modifier = Modifier
                .graphicsLayer {
                    // Ignore drawer pull when searching so the field stays put.
                    translationY = if (searchVisible) 0f else -panelFraction() * size.height * 1.25f
                }
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = searchVisible,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 2 }).togetherWith(
                        fadeOut() + slideOutVertically { -it / 2 }
                    ).using(SizeTransform(clip = false))
                },
                label = "topBarContent",
            ) { searching ->
                if (searching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        TonalBarButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                            colors = colors,
                            onClick = onCloseSearch,
                        )
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.reader_search_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.secondaryText,
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
                        if (searchQuery.isNotEmpty()) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        onQueryChange("")
                                        focusRequester.requestFocus()
                                        keyboard?.show()
                                    },
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.reader_search_clear),
                                    tint = colors.secondaryText,
                                )
                            }
                        } else {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = colors.secondaryText,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        TonalBarButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                            colors = colors,
                            onClick = onBack,
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!author.isNullOrBlank()) {
                                Text(
                                    text = author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        TonalBarButton(
                            icon = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.reader_search),
                            colors = colors,
                            onClick = onSearch,
                        )
                    }
                }
            }
        }
    }
}

/** "Розділ 14", "Глава XIV.", "Chapter 4:", "14." at the start of a title. */
private val chapterHeadPattern = Regex(
    """^((?:розділ|частина|глава|часть|раздел|том|книга|chapter|part|book)\s+[0-9ivxlcdm]+[.:)]?|\d+[.:)]?)\s+(.+)$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Splits a chapter title into the two header lines of the bottom bar:
 * - at the newline the FB2 parser preserves between title paragraphs;
 * - at the first sentence boundary ("Chapter 4. On the porch at dusk");
 * - after a leading "chapter word + number" ("Розділ 14 Остерігайтеся
 *   чудес") — EPUB TOC titles arrive as one flat string with no punctuation.
 * The second line is null when the title has just one part.
 */
private fun splitChapterTitle(title: String): Pair<String, String?> {
    val cleaned = title.trim()
    val newline = cleaned.indexOf('\n')
    if (newline > 0) {
        val second = cleaned.substring(newline + 1).replace('\n', ' ').trim()
        return cleaned.take(newline).trim() to second.ifEmpty { null }
    }
    val boundary = Regex("""[.!?…]\s+""").find(cleaned)
    if (boundary != null) {
        val first = cleaned.substring(0, boundary.range.first + 1).trimEnd('.').trim()
        val second = cleaned.substring(boundary.range.last + 1).trim()
        if (second.isNotEmpty()) return first to second
    }
    chapterHeadPattern.matchEntire(cleaned)?.let { match ->
        return match.groupValues[1].trimEnd('.', ':', ')') to match.groupValues[2].trim()
    }
    return cleaned to null
}

/** What the pull-up panel of the bottom bar is currently showing. */
private enum class BarPanel { SETTINGS, CONTENTS }

@Composable
private fun ReaderBottomBar(
    visible: Boolean,
    colors: ReaderColors,
    panelFraction: androidx.compose.runtime.MutableFloatState,
    /** First page of each chapter (paged mode, full pagination) or null. */
    chapterStartPages: List<Int>?,
    /** Total page count in paged mode, or null when scrolling. */
    totalPages: Int?,
    /** False while paged-mode page counts are still being computed. */
    progressValuesReady: Boolean,
    ready: ReaderState.Ready,
    book: Book?,
    currentChapter: Int,
    chapterLabel: String,
    chapterFraction: Float,
    bookFraction: Float,
    bookSegments: List<Float>,
    onSeekChapter: (Float) -> Unit,
    onSeekBook: (Float) -> Unit,
    settings: ReaderSettings,
    appSettings: AppSettings,
    brightness: Float?,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onUpdateApp: ((AppSettings) -> AppSettings) -> Unit,
    onBrightnessChange: (Float?) -> Unit,
    onChapterClick: (Int) -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onCopyQuote: (String) -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onRemoveQuote: (String) -> Unit,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // The panel pulls out of the bar itself (no modal sheet). Two stops:
    // half a screen by default (the page stays visible for live preview),
    // and fully open — the bar's top edge aligned with the top bar's pill.
    // The chrome around the panel is measured, not guessed, so the full
    // height is exact on any screen.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // Half-open height: starts as an estimate, then snaps to the measured
    // height of the settings content up to the margins/mode row, so exactly
    // that much is visible when the panel opens.
    var halfPx by remember {
        mutableFloatStateOf(with(density) { (screenHeight * 0.48f).toPx() })
    }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val statusTopPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    val navBottomPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }
    var barChromePx by remember { mutableFloatStateOf(with(density) { 190.dp.toPx() }) }
    val fullPx = (
        windowHeightPx - statusTopPx - navBottomPx -
            with(density) { 16.dp.toPx() } - barChromePx
        ).coerceAtLeast(halfPx)
    val panelHeight = remember { Animatable(0f) }
    val panelOpen = panelHeight.value > halfPx / 2
    var panelContent by remember { mutableStateOf(BarPanel.SETTINGS) }

    fun settlePanel(targetPx: Float) {
        scope.launch { panelHeight.animateTo(targetPx) }
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

    // One draggable shared by the handle and the chapter-title header.
    val panelDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            scope.launch {
                panelHeight.snapTo((panelHeight.value - delta).coerceIn(0f, fullPx))
            }
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
                    scope.launch { panelHeight.snapTo(target) }
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
            !panelOpen -> {
                panelContent = content
                settlePanel(halfPx)
            }

            panelContent != content -> panelContent = content
            else -> settlePanel(0f)
        }
    }

    // Hiding the chrome (center tap) also puts the panel away.
    LaunchedEffect(visible) { if (!visible) panelHeight.snapTo(0f) }

    // Share the pull progress with the rest of the chrome (top bar).
    LaunchedEffect(halfPx) {
        snapshotFlow { (panelHeight.value / halfPx).coerceIn(0f, 1f) }
            .collect { panelFraction.floatValue = it }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 4.dp,
            modifier = Modifier
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .onSizeChanged { size -> barChromePx = size.height - panelHeight.value },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                // Drag handle: pull up to stretch the bar into the panel
                // (half screen, then all the way up), pull down or tap to
                // collapse it back into the bar.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .then(panelDrag)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            settlePanel(if (panelOpen) 0f else halfPx)
                        },
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(colors.onChrome.copy(alpha = 0.35f)),
                    )
                }

                // Panel revealed by the pull: settings or contents/bookmarks/
                // quotes, depending on which button summoned it.
                val panelDp = with(density) { panelHeight.value.toDp() }
                if (panelDp > 0.dp) {
                    Box(
                        Modifier
                            .height(panelDp)
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
                                            halfPx = target
                                            // Opening or parked at the old
                                            // half: glide to the exact height.
                                            if (panelHeight.value > 0f &&
                                                panelHeight.value < fullPx * 0.75f
                                            ) {
                                                settlePanel(target)
                                            }
                                        }
                                    },
                                )
                            }

                            BarPanel.CONTENTS -> ReaderPanelsContent(
                                ready = ready,
                                book = book,
                                currentChapter = currentChapter,
                                chapterStartPages = chapterStartPages,
                                dragModifier = panelDrag,
                                onChapterClick = { index ->
                                    settlePanel(0f)
                                    onChapterClick(index)
                                },
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

                // Header: list button — chapter title over its name — gear.
                // Also draggable, so the pull can start from here too.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = panelDrag,
                ) {
                    TonalBarButton(
                        icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                        contentDescription = stringResource(R.string.reader_contents),
                        colors = colors,
                        onClick = { togglePanel(BarPanel.CONTENTS) },
                        tint = if (panelOpen && panelContent == BarPanel.CONTENTS) {
                            colors.accent
                        } else {
                            colors.onChrome
                        },
                    )
                    // Two-part chapter titles ("Розділ 14" + its name) split
                    // across the two header lines; single-part ones get one.
                    val titleLines = remember(chapterLabel) { splitChapterTitle(chapterLabel) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = titleLines.first,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        titleLines.second?.let { secondLine ->
                            // Long names shrink (down to 7sp) to stay on one
                            // line; only extreme ones still ellipsize.
                            Text(
                                text = secondLine.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondaryText,
                                letterSpacing = 1.2.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 7.sp,
                                    maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    stepSize = 0.5.sp,
                                ),
                            )
                        }
                    }
                    TonalBarButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.reader_settings),
                        colors = colors,
                        onClick = { togglePanel(BarPanel.SETTINGS) },
                        tint = if (panelOpen && panelContent == BarPanel.SETTINGS) {
                            colors.accent
                        } else {
                            colors.onChrome
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Progress in the current chapter, then in the whole book
                // (the book track is segmented by chapters). In paged mode
                // the right edge counts PAGES left; scrolling falls back to
                // percent (there are no pages to count).
                val chapterSpan = if (chapterStartPages != null && totalPages != null) {
                    val startPage = chapterStartPages.getOrNull(currentChapter) ?: 0
                    val endPage = chapterStartPages.getOrNull(currentChapter + 1)?.minus(1)
                        ?: (totalPages - 1)
                    (endPage - startPage).coerceAtLeast(0)
                } else {
                    null
                }
                ProgressRow(
                    label = stringResource(R.string.reader_track_chapter).uppercase(),
                    fraction = chapterFraction,
                    onSeek = onSeekChapter,
                    colors = colors,
                    pagesLeftOf = chapterSpan?.let { span ->
                        { f -> ((1f - f) * span).roundToInt() }
                    },
                    showValue = progressValuesReady,
                )
                Spacer(Modifier.height(6.dp))
                ProgressRow(
                    label = stringResource(R.string.reader_track_book).uppercase(),
                    fraction = bookFraction,
                    onSeek = onSeekBook,
                    colors = colors,
                    segments = bookSegments,
                    pagesLeftOf = totalPages?.let { total ->
                        { f -> ((1f - f) * (total - 1).coerceAtLeast(0)).roundToInt() }
                    },
                    showValue = progressValuesReady,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * One progress line of the bottom bar: caps label, a draggable track with a
 * vertical-pill thumb, and "N% left". [segments] (fractions in 0..1) split
 * the track into chapter blocks, as in the redesign mockup.
 */
@Composable
private fun ProgressRow(
    label: String,
    fraction: Float,
    onSeek: (Float) -> Unit,
    colors: ReaderColors,
    segments: List<Float> = emptyList(),
    /** Maps a track position to "pages left"; null shows percent instead. */
    pagesLeftOf: ((Float) -> Int)? = null,
    /** False shows a quiet placeholder until real values are computed. */
    showValue: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val shown = (dragValue ?: fraction).coerceIn(0f, 1f)

    // After a release/tap the thumb HOLDS the chosen spot until the real
    // position catches up — no flash of the old position while the reader
    // performs the jump.
    LaunchedEffect(fraction, dragging, dragValue) {
        val held = dragValue
        if (!dragging && held != null) {
            if (abs(fraction - held) < 0.02f) {
                dragValue = null
            } else {
                delay(600.milliseconds)
                dragValue = null
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        val value = (offset.x / size.width).coerceIn(0f, 1f)
                        dragValue = value
                        onSeek(value)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            dragging = false
                            dragValue?.let(onSeek)
                        },
                        onDragCancel = {
                            dragging = false
                            dragValue = null
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val previous = dragValue ?: 0f
                        val updated = (previous + dragAmount / size.width).coerceIn(0f, 1f)
                        if ((updated * 100).roundToInt() != (previous * 100).roundToInt()) {
                            haptics.performHapticFeedback(
                                HapticFeedbackType.SegmentFrequentTick,
                            )
                        }
                        dragValue = updated
                    }
                },
        ) {
            val active = colors.accent
            val inactive = colors.secondaryText.copy(alpha = 0.25f)
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val trackHeight = 6.dp.toPx()
                val top = (size.height - trackHeight) / 2
                val radius = CornerRadius(trackHeight / 2)

                val minStep = 4.dp.toPx()
                val bounds = mutableListOf(0f)
                val raw = segments.filter { it > 0.001f && it < 0.999f }.distinct().sorted()
                var lastBound = 0f
                for (s in raw) {
                    if ((s - lastBound) * w >= minStep) {
                        bounds.add(s)
                        lastBound = s
                    }
                }
                if ((1f - lastBound) * w >= minStep || bounds.size == 1) {
                    bounds.add(1f)
                } else {
                    bounds[bounds.lastIndex] = 1f
                }

                val position = shown * w
                // 1. Draw the background (full track)
                drawRoundRect(
                    color = inactive,
                    topLeft = Offset(0f, top),
                    size = Size(w, trackHeight),
                    cornerRadius = radius,
                )

                // 2. Draw the progress (active part)
                if (position > 0) {
                    drawRoundRect(
                        color = active,
                        topLeft = Offset(0f, top),
                        size = Size(position, trackHeight),
                        cornerRadius = radius,
                    )
                }

                // 3. Draw thin vertical stripes (ticks) at chapter boundaries
                val tickWidth = 1.dp.toPx()
                for (i in 1 until bounds.size - 1) {
                    val x = bounds[i] * w
                    drawRect(
                        color = colors.chrome.copy(alpha = 0.7f),
                        topLeft = Offset(x - tickWidth / 2, top),
                        size = Size(tickWidth, trackHeight)
                    )
                }

                // Vertical-pill thumb.
                val thumbWidth = 4.dp.toPx()
                val thumbHeight = 18.dp.toPx()
                val thumbX = position.coerceIn(thumbWidth / 2, w - thumbWidth / 2)
                drawRoundRect(
                    color = active,
                    topLeft = Offset(thumbX - thumbWidth / 2, (size.height - thumbHeight) / 2),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2),
                )
            }
        }
        val leftWord = stringResource(R.string.reader_percent_left)
        Text(
            text = buildAnnotatedString {
                if (!showValue) {
                    withStyle(SpanStyle(color = colors.secondaryText)) { append("…") }
                    return@buildAnnotatedString
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onChrome)) {
                    append(
                        pagesLeftOf?.let { "${it(shown)}" }
                            ?: "${((1f - shown) * 100).roundToInt()}%",
                    )
                }
                withStyle(SpanStyle(color = colors.secondaryText)) {
                    append(" ")
                    append(leftWord)
                }
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(84.dp),
        )
    }
}
