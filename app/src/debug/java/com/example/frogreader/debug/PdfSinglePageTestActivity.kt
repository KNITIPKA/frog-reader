@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.debug

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.PdfReaderState
import com.example.frogreader.ui.reader.pdf.PdfReaderViewModel
import com.example.frogreader.ui.reader.pdf.PdfReadingSurface
import com.example.frogreader.ui.reader.pdf.SinglePagePdfDocument
import com.example.frogreader.ui.reader.pdf.findPdfView
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.theme.FrogReaderTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Exercises the production reader against disposable library/progress stores in app cache. */
class PdfSinglePageTestActivity : ComponentActivity() {
    private var readerModel by mutableStateOf<PdfReaderViewModel?>(null)
    private val fixtureModels = ViewModelStore()
    private lateinit var fixtureContext: Context
    private lateinit var repository: BookRepository
    private var chromeState by mutableStateOf(false)
    private var searchState by mutableStateOf(false)
    private var drawerState by mutableStateOf(false)
    private var panelState by mutableIntStateOf(0)
    private val snackbar = SnackbarHostState()
    private val fastScrollLayoutRequested = CompletableDeferred<Unit>()
    private val fastScrollLayoutGate = CompletableDeferred<Unit>()
    private val fastScrollBitmapRequested = CompletableDeferred<Unit>()
    private val fastScrollBitmapGate = CompletableDeferred<Unit>()
    @Volatile var loadFailure: Throwable? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val source = intent.data ?: run { finish(); return }
        val fixtureRoot = File(cacheDir, "pdf-production-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        fixtureContext = object : ContextWrapper(applicationContext) {
            override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(fixtureRoot, "no-backup").apply { mkdirs() }
        }
        lifecycleScope.launch {
            try {
                val file = File(fixtureContext.filesDir, "books/fixture.pdf").apply { parentFile!!.mkdirs() }
                contentResolver.openInputStream(source)!!.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                repository = BookRepository(fixtureContext)
                val initialPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, -1)
                // Match an ordinary fit-to-width reading position. Zoom 1 would shrink the
                // four-page scroll fixture below the display and clamp its centre to page 1.
                val initialZoom = intent.getFloatExtra(EXTRA_INITIAL_ZOOM,
                    resources.displayMetrics.widthPixels / 320f)
                repository.addBookForRestore(Book(
                    id = BookId, title = "Disposable PDF paging fixture", format = BookFormat.PDF,
                    fileName = file.name, addedAtMillis = 1L,
                    readerSettings = ReaderSettings.defaultFor(BookFormat.PDF).copy(
                        pdfSinglePage = intent.getBooleanExtra(EXTRA_SINGLE_PAGE, true),
                    ),
                    progress = ReadingProgress(documentLocation = initialPage.takeIf { it >= 0 }?.let {
                        DocumentLocation.Pdf(it, 160f, 240f, initialZoom)
                    }),
                ))
                val session = PdfDocumentSession(openDocument = { uri, password ->
                    val document = openReaderPdfDocument(applicationContext, uri, password)
                    if (intent.getBooleanExtra(EXTRA_PAUSE_FAST_SCROLL_DESTINATION, false)) {
                        pauseFastScrollDestination(document)
                    } else document
                })
                val preferences = PreferenceDataStoreFactory.create(scope = lifecycleScope) {
                    File(fixtureRoot, "settings.preferences_pb")
                }
                val settings = SettingsRepository(fixtureContext, preferences)
                intent.getStringExtra(EXTRA_TOOLBAR_APPEARANCE)?.let { mode ->
                    settings.updateApp { it.copy(pdfToolbarAppearance = PdfToolbarAppearance.valueOf(mode)) }
                }
                readerModel = ViewModelProvider(fixtureModels, viewModelFactory {
                    initializer {
                        PdfReaderViewModel(
                            fixtureContext, repository, settings,
                            StatsRepository(fixtureContext), BookId, session,
                        )
                    }
                })["fixture", PdfReaderViewModel::class.java]
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { loadFailure = error }
        }
        setContent {
            readerModel?.let { model ->
                val state by model.state.collectAsStateWithLifecycle()
                val book by model.book.collectAsStateWithLifecycle()
                val theme = book?.readerSettings?.pdfTheme ?: AppTheme.WHITE
                FrogReaderTheme(theme = theme, dynamicColor = false) {
                    val opened = state as? PdfReaderState.Ready
                    if (opened != null && book != null) PdfReadingSurface(
                        document = opened.document, book = book!!, model = model,
                        colors = readerColors(theme), chrome = chromeState,
                        toggleChrome = { chromeState = !chromeState },
                        searchVisible = searchState, setSearchVisible = { searchState = it },
                        panel = panelState, setPanel = { panelState = it },
                        active = !searchState && !drawerState && panelState == 0,
                        interactionsEnabled = !drawerState && panelState == 0,
                        initialQuoteId = null, initialBookmarkId = null,
                        snackbar = snackbar, copy = {}, onBack = ::finish,
                        contentMotion = Modifier.fillMaxSize(), drawerOpen = drawerState,
                        onDrawerOpenChange = { drawerState = it }, brightness = null, changeBrightness = {},
                    )
                }
            }
        }
    }

    fun composedPageViewers(): Map<Int, PdfView> = buildMap {
        fun visit(view: android.view.View) {
            if (view is PdfView) {
                (view.pdfDocument as? SinglePagePdfDocument)?.let { put(it.sourcePageIndex, view) }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(window.decorView)
    }

    fun nativeViewer(): PdfView? = window.decorView.findPdfView()
    fun sourcePage(): Int? {
        val viewer = nativeViewer() ?: return null
        val document = viewer.pdfDocument ?: return null
        return (document as? SinglePagePdfDocument)?.sourcePageIndex
            ?: viewer.viewToPdfPoint(viewer.width / 2f, viewer.height / 2f)?.pageNum
    }
    fun singlePageEnabled(): Boolean = nativeViewer()?.pdfDocument is SinglePagePdfDocument
    fun setSinglePage(enabled: Boolean) { readerModel?.updateSettings { it.copy(pdfSinglePage = enabled) } }
    fun setChromeVisible(visible: Boolean) { chromeState = visible }
    fun searchVisible(): Boolean = searchState
    fun drawerOpen(): Boolean = drawerState
    fun savedLocation(): DocumentLocation.Pdf? = readerModel?.book?.value?.progress?.documentLocation as? DocumentLocation.Pdf
    fun savedPdfTheme(): AppTheme? = repository.bookById(BookId)?.readerSettings?.pdfTheme
    fun savedAppTheme(): AppTheme? = readerModel?.appSettings?.value?.theme
    /** Seeded fixture progress has zero totalPages; this changes only after a real viewport save. */
    fun progressObserved(): Boolean = (readerModel?.book?.value?.progress?.totalPages ?: 0) > 0
    fun diskSavedLocation(): DocumentLocation.Pdf? =
        BookRepository(fixtureContext).bookById(BookId)?.progress?.documentLocation as? DocumentLocation.Pdf
    fun searchPages(): List<Int> = readerModel?.search?.value?.matches?.map { it.page }.orEmpty()
    fun fastScrollLayoutWaiting(): Boolean = fastScrollLayoutRequested.isCompleted
    fun releaseFastScrollLayout() { fastScrollLayoutGate.complete(Unit) }
    fun fastScrollBitmapWaiting(): Boolean = fastScrollBitmapRequested.isCompleted
    fun releaseFastScrollBitmap() { fastScrollBitmapGate.complete(Unit) }

    private fun pauseFastScrollDestination(document: PdfDocument): PdfDocument = object : PdfDocument by document {
        private suspend fun pauseDimensions(pageNumber: Int) {
            if (pageNumber >= 24) {
                fastScrollLayoutRequested.complete(Unit)
                fastScrollLayoutGate.await()
            }
        }

        override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
            pauseDimensions(pageNumber)
            return document.getPageInfo(pageNumber)
        }

        override suspend fun getPageInfo(pageNumber: Int, pageInfoFlags: Long): PdfDocument.PageInfo {
            pauseDimensions(pageNumber)
            return document.getPageInfo(pageNumber, pageInfoFlags)
        }

        override fun getPageBitmapSource(pageNumber: Int): PdfDocument.BitmapSource {
            val source = document.getPageBitmapSource(pageNumber)
            if (pageNumber != pageCount - 1) return source
            return object : PdfDocument.BitmapSource by source {
                override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap {
                    fastScrollBitmapRequested.complete(Unit)
                    fastScrollBitmapGate.await()
                    return source.getBitmap(scaledPageSizePx, tileRegion)
                }
            }
        }
    }

    fun failed(): Boolean = loadFailure != null || readerModel?.state?.value == PdfReaderState.Error ||
        readerModel?.saveError?.value == true

    override fun onPause() {
        readerModel?.savePosition()
        readerModel?.setReadingActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        // The real native viewer must release its bitmap sources before the model closes the PDF.
        findViewById<ViewGroup>(android.R.id.content).removeAllViews()
        readerModel?.savePosition()
        readerModel?.setReadingActive(false)
        fixtureModels.clear()
        readerModel = null
        super.onDestroy()
    }

    companion object {
        private const val BookId = "pdf-production-fixture"
        const val EXTRA_INITIAL_PAGE = "pdf_fixture_initial_page"
        const val EXTRA_SINGLE_PAGE = "pdf_fixture_single_page"
        const val EXTRA_INITIAL_ZOOM = "pdf_fixture_initial_zoom"
        const val EXTRA_TOOLBAR_APPEARANCE = "pdf_fixture_toolbar_appearance"
        const val EXTRA_PAUSE_FAST_SCROLL_DESTINATION = "pdf_fixture_pause_fast_scroll"
    }
}
