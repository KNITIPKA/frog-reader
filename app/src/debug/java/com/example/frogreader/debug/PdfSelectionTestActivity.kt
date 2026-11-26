@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.PdfNoteDraft
import com.example.frogreader.ui.reader.pdf.PdfNoteEditor
import com.example.frogreader.ui.reader.pdf.PdfNoteViewer
import com.example.frogreader.ui.reader.pdf.pdfChromeTapObserver
import com.example.frogreader.ui.reader.pdf.pdfNoteAt
import com.example.frogreader.ui.reader.pdf.PdfSelectionToolbar
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import com.example.frogreader.ui.reader.pdf.toActionTextSelection
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Debug-only PDF gesture fixture. It never opens the library or writes reader data. */
class PdfSelectionTestActivity : ComponentActivity() {
    private var document by mutableStateOf<PdfDocument?>(null)
    private var session: PdfDocumentSession? = null
    private var viewer: PdfViewerState? = null
    private var viewerOrigin = Offset.Zero
    @Volatile var contentReady: Boolean = false
        private set
    @Volatile var loadFailure: Throwable? = null
        private set
    @Volatile var toolbarSelectionText: String? = null
        private set
    @Volatile var copiedText: String? = null
        private set
    @Volatile var addedNoteText: String? = null
        private set
    @Volatile var addedNoteBounds: List<PdfTextBounds> = emptyList()
        private set
    @Volatile var highlightedText: String? = null
        private set
    @Volatile var highlightedBounds: List<PdfTextBounds> = emptyList()
        private set
    @Volatile var highlightedColor: QuoteColor? = null
        private set
    @Volatile private var actionTileLayouts: Map<Int, Rect> = emptyMap()
    private var savedNote by mutableStateOf<Quote?>(null)
    private var originalClipboard: ClipData? = null
    private var changedClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like the paging fixture, allow unattended device tests after the screen has slept.
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.data ?: run { finish(); return }
        val forceCompatibility = intent.getBooleanExtra(EXTRA_COMPATIBILITY_TEXT, false)
        val ownedSession = PdfDocumentSession(openDocument = { source, password ->
            openReaderPdfDocument(applicationContext, source, password, forceCompatibility)
        }).also { session = it }
        lifecycleScope.launch {
            try { document = ownedSession.load(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { loadFailure = error }
        }
        setContent {
            FrogReaderTheme(theme = AppTheme.WHITE, dynamicColor = false) {
                val state = rememberPdfViewerState()
                val selected = state.currentSelection.toActionTextSelection()
                val colors = readerColors(AppTheme.WHITE)
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                var draft by remember { mutableStateOf<PdfNoteDraft?>(null) }
                var showingNote by remember { mutableStateOf(false) }
                val sourcePage = intent.getIntExtra(EXTRA_SOURCE_PAGE_INDEX, -1).takeIf { it >= 0 }
                SideEffect {
                    viewer = state
                    toolbarSelectionText = selected?.text?.toString()
                    if (selected == null) actionTileLayouts = emptyMap()
                }
                DisposableEffect(state) {
                    onDispose { if (viewer === state) viewer = null }
                }
                Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
                    PdfViewer(
                        pdfDocument = document,
                        state = state,
                        modifier = Modifier.fillMaxSize()
                            .onGloballyPositioned { viewerOrigin = it.positionOnScreen() }
                            .pdfChromeTapObserver(
                                selectionActive = selected != null || draft != null || showingNote,
                                onTap = { offset ->
                                    val point = state.visibleOffsetToPdfPoint(offset - viewerOrigin)
                                    val found = point?.let { pdfNoteAt(listOfNotNull(savedNote), sourcePage ?: it.pageNum, it.x, it.y) }
                                    showingNote = found != null
                                    found != null
                                }, onToggle = {},
                            ),
                        isFormFillingEnabled = false,
                        isImageSelectionEnabled = false,
                        filterContextMenuComponents = { false },
                        onFirstContentLoad = { contentReady = true },
                    )
                    PdfSelectionToolbar(
                        selection = selected, visible = state.gestureState == PdfViewerState.GESTURE_STATE_IDLE && draft == null && !showingNote,
                        viewer = state, viewport = viewport, colors = colors, quotes = emptyList(),
                        onRemoveQuotes = {}, onQuote = { text, bounds, color ->
                            highlightedText = text
                            highlightedBounds = bounds
                            highlightedColor = color
                        }, onAddNote = { text, bounds ->
                            addedNoteText = text
                            addedNoteBounds = bounds
                            draft = PdfNoteDraft(text, bounds)
                        },
                        translate = {}, define = {}, externalSearch = {},
                        copy = { text ->
                            val clipboard = getSystemService(ClipboardManager::class.java)
                            if (!changedClipboard) originalClipboard = clipboard.primaryClip
                            clipboard.setPrimaryClip(ClipData.newPlainText("PDF gesture test", text))
                            changedClipboard = true
                            copiedText = text
                        },
                        sourcePageIndex = sourcePage,
                        onActionLayout = { index, position, size ->
                            val left = position.x.roundToInt()
                            val top = position.y.roundToInt()
                            actionTileLayouts = actionTileLayouts + (index to
                                Rect(left, top, left + size.width, top + size.height))
                        },
                    )
                }
                draft?.let { editing ->
                    PdfNoteEditor(editing, colors, saving = false, onSave = { note ->
                        savedNote = Quote("fixture-note", editing.text, -1, 0L, note = note,
                            color = QuoteColor.AMBER, documentLocation = DocumentLocation.Pdf(sourcePage ?: 0),
                            pdfSelection = editing.bounds)
                        draft = null
                    }, onDismiss = { draft = null })
                }
                if (showingNote) savedNote?.let { quote ->
                    PdfNoteViewer(quote, colors, onEdit = {
                        showingNote = false
                        draft = PdfNoteDraft(quote.text, quote.pdfSelection, quote.id, quote.note.orEmpty())
                    }, onDismiss = { showingNote = false })
                }
            }
        }
    }

    fun savedNoteForTest(): Quote? = savedNote

    /** Actual Compose measurements, before the toolbar's ancestor animation transform. */
    fun actionTilesForTest(): List<Rect> = (0..5).mapNotNull { index -> actionTileLayouts[index]?.let(::Rect) }

    fun imeVisibleForTest(): Boolean = ViewCompat.getRootWindowInsets(window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Called on the main thread after first content load. */
    fun pointOnScreen(point: PdfPoint): PointF? {
        val offset = viewer?.pdfPointToVisibleOffset(point) ?: return null
        if (!offset.x.isFinite() || !offset.y.isFinite()) return null
        return PointF(offset.x + viewerOrigin.x, offset.y + viewerOrigin.y)
    }

    fun selectionIsCleared(): Boolean = viewer?.currentSelection == null

    override fun onDestroy() {
        // Detach the native viewer before closing the document it renders.
        findViewById<ViewGroup>(android.R.id.content).removeAllViews()
        session?.close()
        session = null
        if (changedClipboard) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(originalClipboard ?: ClipData.newPlainText("", ""))
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COMPATIBILITY_TEXT = "pdf_test_compatibility_text"
        const val EXTRA_SOURCE_PAGE_INDEX = "pdf_test_source_page_index"
    }
}
