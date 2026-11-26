@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.debug

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.rememberPdfViewerState
import androidx.pdf.view.PdfView
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.R
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.ui.reader.SystemBarsEffect
import com.example.frogreader.ui.reader.pdf.PdfChromeMotion
import com.example.frogreader.ui.reader.pdf.PdfChromeOverlay
import com.example.frogreader.ui.reader.pdf.PdfBookHeader
import com.example.frogreader.ui.reader.pdf.PdfBottomBar
import com.example.frogreader.ui.reader.pdf.PdfBarPanel
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.PdfSearchContent
import com.example.frogreader.ui.reader.pdf.PdfReaderSettings
import com.example.frogreader.ui.reader.pdf.PdfSearchMode
import com.example.frogreader.ui.reader.pdf.PdfViewerStyle
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import com.example.frogreader.ui.reader.pdf.locationAtCenter
import com.example.frogreader.ui.reader.pdf.pageAtCenter
import com.example.frogreader.ui.reader.pdf.pdfChromeTapObserver
import com.example.frogreader.ui.reader.pdf.pdfChromeSource
import com.example.frogreader.ui.reader.pdf.rememberPdfChromeBackdrop
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Disposable native PDF fixture; uses production styling and tap handling without reader stores. */
class PdfChromeTestActivity : ComponentActivity() {
    private var document by mutableStateOf<PdfDocument?>(null)
    private var readerTheme by mutableStateOf(AppTheme.WHITE)
    private var session: PdfDocumentSession? = null
    @Volatile var contentReady = false
        private set
    @Volatile var loadFailure: Throwable? = null
        private set
    private var chromeState by mutableStateOf(false)
    private var appearance by mutableStateOf(AppSettings())
    val chromeVisible: Boolean get() = chromeState
    private var searchState by mutableStateOf(false)
    private var searchModeState by mutableStateOf(PdfSearchMode.TEXT)
    private var searchQueryState by mutableStateOf("")
    private var searchResultIndex by mutableIntStateOf(0)
    val searchVisible: Boolean get() = searchState
    val searchModeName: String get() = searchModeState.name
    val searchQuery: String get() = searchQueryState
    private var closeSearchCallback: (() -> Unit)? = null
    private var reopenSearchCallback: Runnable? = null
    private var composingTestConnection: InputConnection? = null
    @Volatile var lastRapidSearchCloseTime = 0L
        private set
    @Volatile var lastRapidSearchReopenTime = 0L
        private set
    @Volatile var chromeToggleCount = 0
        private set
    @Volatile var lastChromeToggleTime = 0L
        private set
    @Volatile var displayedPage = 0
        private set
    private var keepSystemBarsVisible by mutableStateOf(false)
    private var captureMotion by mutableStateOf(false)
    private val capturedMotion = mutableListOf<ChromeMotionFrame>()
    @Volatile var latestChromeMotionFrame: ChromeMotionFrame? = null
        private set
    @Volatile var latestMotionCapturePath: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val uri = intent.data ?: run { finish(); return }
        readerTheme = AppTheme.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_THEME) }
            ?: AppTheme.WHITE
        val ownedSession = PdfDocumentSession(openDocument = { source, password ->
            openReaderPdfDocument(applicationContext, source, password)
        }).also { session = it }
        lifecycleScope.launch {
            try { document = ownedSession.load(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { loadFailure = error }
        }
        setContent {
            val theme = readerTheme
            FrogReaderTheme(theme = theme, dynamicColor = false) {
                val state = rememberPdfViewerState()
                val colors = readerColors(theme)
                val chromeVisible = chromeState || searchState
                val chromeMotion = remember { PdfChromeMotion(chromeVisible) }
                val backdrop = rememberPdfChromeBackdrop(
                    appearance = appearance.pdfToolbarAppearance,
                    enabled = chromeVisible || chromeMotion.mounted,
                )
                val scope = rememberCoroutineScope()
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val panelFraction = remember { mutableFloatStateOf(0f) }
                var size by remember { mutableStateOf(IntSize.Zero) }
                var loadedViewer by remember { mutableStateOf<PdfView?>(null) }
                var lastLocation by remember { mutableStateOf<DocumentLocation.Pdf?>(null) }
                var topCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var bottomCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var renderedProgress by remember { mutableStateOf<State<Float>?>(null) }
                val density = LocalDensity.current
                val statusBars = WindowInsets.statusBars
                val fullStatusBars = WindowInsets.statusBarsIgnoringVisibility
                val navigationBars = WindowInsets.navigationBars
                val fullNavigationBars = WindowInsets.navigationBarsIgnoringVisibility
                val ime = WindowInsets.ime
                val systemBarsVisible = chromeState || searchState || keepSystemBarsVisible
                SystemBarsEffect(theme, systemBarsVisible)
                val viewportReady = loadedViewer != null && loadedViewer === nativeViewer()
                val page = state.pageAtCenter(size, document?.pageCount ?: 1, viewportReady, lastLocation)
                val closeSearch = {
                    searchState = false
                    searchQueryState = ""
                    focus.clearFocus()
                    keyboard?.hide()
                    Unit
                }
                SideEffect {
                    displayedPage = page
                    closeSearchCallback = closeSearch
                    if (viewportReady && size != IntSize.Zero) lastLocation = state.locationAtCenter(size)
                }
                Box(Modifier.fillMaxSize().onGloballyPositioned { rootCoordinates = it }.drawWithContent {
                    drawContent()
                    fun WindowInsets.extent(): Int = maxOf(
                        getTop(density), getBottom(density),
                        getLeft(density, layoutDirection), getRight(density, layoutDirection),
                    )
                    val top = topCoordinates?.takeIf { it.isAttached }
                    val bottom = bottomCoordinates?.takeIf { it.isAttached }
                    val root = rootCoordinates?.takeIf { it.isAttached }
                    val fullStatus = fullStatusBars.extent()
                    val fullNavigation = fullNavigationBars.extent()
                    val frame = ChromeMotionFrame(
                        timeMillis = SystemClock.uptimeMillis(),
                        requestedVisible = chromeState || searchState,
                        // Reading in draw invalidates this probe on every motion frame even when
                        // a layer-property update correctly avoids recomposition and relayout.
                        progress = renderedProgress?.value ?: 0f,
                        rootY = root?.localToWindow(Offset.Zero)?.y ?: 0f,
                        rootHeight = root?.size?.height?.toFloat() ?: size.height.toFloat(),
                        topY = top?.localToWindow(Offset.Zero)?.y,
                        topHeight = top?.size?.height?.toFloat() ?: 0f,
                        bottomY = bottom?.localToWindow(Offset.Zero)?.y,
                        bottomHeight = bottom?.size?.height?.toFloat() ?: 0f,
                        bottomWidth = bottom?.size?.width?.toFloat() ?: 0f,
                        imeBottom = ime.getBottom(density),
                        navigationBottom = fullNavigationBars.getBottom(density),
                        searchVisible = searchState,
                        searchMode = searchModeState.name,
                        statusBarFraction = if (fullStatus > 0) statusBars.extent().toFloat() / fullStatus else null,
                        navigationBarFraction = if (fullNavigation > 0) navigationBars.extent().toFloat() / fullNavigation else null,
                    )
                    latestChromeMotionFrame = frame
                    if (captureMotion && capturedMotion.size < 2_000) capturedMotion += frame
                }) {
                    PdfViewerStyle(colors) { attachDocument ->
                        PdfViewer(
                            pdfDocument = document.takeIf { attachDocument },
                            state = state,
                            modifier = Modifier.fillMaxSize().pdfChromeSource(backdrop, colors.background)
                                .onSizeChanged { size = it }.pdfChromeTapObserver(
                                selectionActive = state.currentSelection != null,
                                onToggle = {
                                    chromeState = !chromeState
                                    chromeToggleCount++
                                    lastChromeToggleTime = SystemClock.uptimeMillis()
                                },
                            ),
                            isFormFillingEnabled = false,
                            isImageSelectionEnabled = false,
                            onFirstContentLoad = { contentReady = true; loadedViewer = nativeViewer() },
                        )
                    }
                    PdfChromeOverlay(chromeVisible, chromeMotion, systemBarsVisible = systemBarsVisible) { progress ->
                        SideEffect { renderedProgress = progress }
                        Box(Modifier.align(Alignment.TopCenter)
                            .graphicsLayer { translationY = -this.size.height * (1f - progress.value) }
                            // Probe inside the graphics layer so localToWindow includes translation.
                            .onGloballyPositioned { topCoordinates = it }) {
                            PdfBookHeader(
                                title = "PDF test",
                                author = "FrogReader",
                                colors = colors,
                                appearance = appearance.pdfToolbarAppearance,
                                backdrop = backdrop,
                                bookmarked = false,
                                onBack = { finish() },
                                onBookmark = {},
                                visible = appearance.showPdfTopBar,
                            )
                        }
                        Box(Modifier.align(Alignment.BottomCenter)
                            .graphicsLayer { translationY = this.size.height * (1f - progress.value) }
                            .onGloballyPositioned { bottomCoordinates = it }
                        ) {
                            PdfBottomBar(
                                colors = colors, appearance = appearance.pdfToolbarAppearance,
                                backdrop = backdrop,
                                page = page, pageCount = document?.pageCount ?: 1,
                                showCollapsedHandle = false, expandOnSwipe = false, showFinish = false,
                                onFinish = {},
                                onPageIndicator = { openSearch(PdfSearchMode.PAGE) },
                                onSearch = { openSearch(PdfSearchMode.TEXT) },
                                onPanelOpenChange = {},
                                panelFraction = panelFraction,
                                searchVisible = searchState,
                                searchContent = { panelColors ->
                                    val matches = if (searchQueryState.isBlank() || searchQueryState == "none") 0 else 3
                                    PdfSearchContent(
                                        colors = panelColors,
                                        active = searchState,
                                        mode = searchModeState,
                                        onModeChange = { searchModeState = it },
                                        query = searchQueryState,
                                        onQueryChange = { searchQueryState = it; searchResultIndex = 0 },
                                        textStatus = when {
                                            searchQueryState.isBlank() -> null
                                            matches == 0 -> stringResource(R.string.pdf_search_empty)
                                            else -> stringResource(R.string.pdf_search_count, searchResultIndex + 1, matches)
                                        },
                                        hasMatches = matches > 0,
                                        onPrevious = { if (matches > 0) searchResultIndex = (searchResultIndex + matches - 1) % matches },
                                        onNext = { if (matches > 0) searchResultIndex = (searchResultIndex + 1) % matches },
                                        currentPage = page,
                                        pageCount = document?.pageCount ?: 1,
                                        onGoToPage = { target -> scope.launch { state.scrollToPage(target) } },
                                        onClose = closeSearch,
                                    )
                                },
                            ) { panel, panelColors ->
                                if (panel == PdfBarPanel.SETTINGS) PdfReaderSettings(
                                    colors = panelColors,
                                    appSettings = appearance.copy(theme = theme, followSystemTheme = false),
                                    onUpdateAppSettings = { appearance = it(appearance) },
                                    settings = ReaderSettings(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun switchTheme(theme: AppTheme) {
        contentReady = false
        readerTheme = theme
    }

    fun setChromeVisible(visible: Boolean) { chromeState = visible }
    fun setToolbarAppearance(mode: PdfToolbarAppearance) {
        appearance = appearance.copy(pdfToolbarAppearance = mode)
    }

    private fun openSearch(mode: PdfSearchMode) {
        searchModeState = mode
        searchState = true
    }

    /** Exercise a second entry while AnimatedContent still retains the outgoing search editor. */
    fun rapidlyReopenSearch() {
        reopenSearchCallback?.let(window.decorView::removeCallbacks)
        lastRapidSearchCloseTime = SystemClock.uptimeMillis()
        lastRapidSearchReopenTime = 0L
        checkNotNull(closeSearchCallback).invoke()
        val reopen = Runnable {
            lastRapidSearchReopenTime = SystemClock.uptimeMillis()
            openSearch(PdfSearchMode.TEXT)
            reopenSearchCallback = null
        }
        reopenSearchCallback = reopen
        window.decorView.postDelayed(reopen, 48)
    }

    /** Feed an uncommitted IME word through the focused View's public native input bridge. */
    fun setComposingTextForTest(text: String): Boolean {
        val editorView = currentFocus ?: return false
        if (!editorView.onCheckIsTextEditor()) return false
        val connection = editorView.onCreateInputConnection(EditorInfo()) ?: return false
        // Compose supports concurrent connections in one active session. Keep the probe alive;
        // closing it or restarting input here would interfere with the keyboard being tested.
        composingTestConnection = connection
        return connection.setComposingText(text, 1)
    }

    fun keepSystemBarsVisible(visible: Boolean) { keepSystemBarsVisible = visible }

    fun startMotionCapture() {
        capturedMotion.clear()
        captureMotion = true
    }

    fun stopMotionCapture(): List<ChromeMotionFrame> {
        captureMotion = false
        val frames = capturedMotion.toList()
        val diagnostic = cacheDir.resolve("pdf-motion-${SystemClock.uptimeMillis()}.csv")
        diagnostic.bufferedWriter().use { writer ->
            writer.appendLine("timeMillis,requestedVisible,progress,rootY,rootHeight,topY,topHeight,bottomY,bottomHeight,bottomWidth,imeBottom,navigationBottom,searchVisible,searchMode,statusBarFraction,navigationBarFraction,measuredTopProgress,measuredBottomProgress,barContentHeight")
            frames.forEach { frame ->
                writer.appendLine(listOf(
                    frame.timeMillis, frame.requestedVisible, frame.progress, frame.rootY, frame.rootHeight,
                    frame.topY, frame.topHeight, frame.bottomY, frame.bottomHeight,
                    frame.bottomWidth, frame.imeBottom, frame.navigationBottom, frame.searchVisible, frame.searchMode,
                    frame.statusBarFraction, frame.navigationBarFraction,
                    frame.measuredTopProgress, frame.measuredBottomProgress, frame.barContentHeight,
                ).joinToString(",") { it?.toString().orEmpty() })
            }
        }
        latestMotionCapturePath = diagnostic.absolutePath
        return frames
    }

    /** Geometry comes from the rendered layer; bar fractions come from animated Compose insets. */
    data class ChromeMotionFrame(
        val timeMillis: Long,
        val requestedVisible: Boolean,
        val progress: Float,
        val rootY: Float,
        val rootHeight: Float,
        val topY: Float?,
        val topHeight: Float,
        val bottomY: Float?,
        val bottomHeight: Float,
        val bottomWidth: Float,
        val imeBottom: Int,
        val navigationBottom: Int,
        val searchVisible: Boolean,
        val searchMode: String,
        val statusBarFraction: Float?,
        val navigationBarFraction: Float?,
    ) {
        /** Normalized movement inferred from transformed geometry, independently of motion state. */
        val measuredTopProgress: Float? get() = topY?.takeIf { topHeight > 0f }?.let {
            1f + (it - rootY) / topHeight
        }
        val measuredBottomProgress: Float? get() = bottomY?.takeIf { bottomHeight > 0f }?.let {
            (rootY + rootHeight - it) / bottomHeight
        }
        val barContentHeight: Float get() = bottomHeight - maxOf(imeBottom, navigationBottom)
    }

    fun nativeViewer(): PdfView? {
        fun find(view: View): PdfView? {
            if (view is PdfView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return find(window.decorView)
    }

    override fun onDestroy() {
        reopenSearchCallback?.let(window.decorView::removeCallbacks)
        reopenSearchCallback = null
        closeSearchCallback = null
        // Preserve the probe when an assertion aborts a transition before the normal stop call.
        if (captureMotion) stopMotionCapture()
        findViewById<ViewGroup>(android.R.id.content).removeAllViews()
        composingTestConnection = null
        session?.close()
        session = null
        super.onDestroy()
    }

    companion object { const val EXTRA_THEME = "pdf_chrome_test_theme" }
}
