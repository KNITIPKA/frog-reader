@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.view.PdfView
import com.example.frogreader.ui.reader.ReaderColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PdfPrefetchPages = 2

/** One native viewport per composed sheet. Neighbours retain their rendered tiles and layout. */
internal class PdfPageViewport(val document: PdfDocument) {
    val viewer = PdfViewerState()
    var ready by mutableStateOf(false)
    var initialized by mutableStateOf(false)
    var pageWidth by mutableIntStateOf(0)
    var pageHeight by mutableIntStateOf(0)
    var fittedSize = IntSize.Zero
    var fallbackFitZoom by mutableFloatStateOf(0f)
    var nativeView by mutableStateOf<PdfView?>(null)

    /** Offscreen first-content callbacks require a draw; inspect the actual fitted bitmaps. */
    fun isRendered(size: IntSize): Boolean {
        val native = nativeView ?: return false
        return initialized && fittedSize == size && native.pdfDocument === document &&
            native.width == size.width && native.height == size.height &&
            native.visiblePagesCount > 0 && native.arePagesFullyRendered()
    }

}

/** Entries live exactly as long as the pager composes them; the full document is never owned here. */
internal class PdfPageViewports(private val source: PdfDocument) {
    private val pages = mutableMapOf<Int, PdfPageViewport>()
    private val attached = mutableMapOf<Int, PdfPageViewport>()
    fun page(index: Int): PdfPageViewport = attached[index] ?: pages.getOrPut(index) {
        PdfPageViewport(SinglePagePdfDocument(source, index))
    }
    fun composedPage(index: Int): PdfPageViewport? = attached[index]
    fun retain(index: Int, viewport: PdfPageViewport) {
        // Subcomposition can register after the parent's effects. The actual composed sheet,
        // rather than a later lookup by number, owns this entry until its own disposal.
        val previous = pages.put(index, viewport)
        if (previous !== viewport && attached[index] !== previous) previous?.document?.close()
        attached[index] = viewport
    }
    fun trim(active: Int) {
        // Keep the pending nearby window too: pager subcomposition applies its retain effects
        // after layout, so an unattached neighbour is not necessarily an abandoned viewport.
        pages.keys.filter { kotlin.math.abs(it - active) > PdfPrefetchPages && attached[it] !== pages[it] }.forEach { index ->
            pages.remove(index)?.document?.close()
        }
    }
    fun close() {
        pages.values.forEach { it.document.close() }
        pages.clear()
        attached.clear()
    }
    fun release(index: Int, viewport: PdfPageViewport) {
        // A stale slot may dispose after a replacement for this same page has attached.
        if (attached[index] === viewport) attached.remove(index)
        if (pages[index] === viewport) pages.remove(index)
        viewport.document.close()
    }
}

@Composable
internal fun PdfPageViewer(
    viewport: PdfPageViewport,
    colors: ReaderColors,
    size: IntSize,
    modifier: Modifier,
    onUrlLinkClicked: (Uri) -> Boolean,
) {
    val root = LocalView.current
    val currentLinkHandler by rememberUpdatedState(onUrlLinkClicked)
    // Prepare offscreen sheets too, before their first draw. Refit sheets on a viewport resize.
    LaunchedEffect(viewport, size, viewport.viewer.gestureState) {
        if (viewport.document !is SinglePagePdfDocument ||
            viewport.viewer.gestureState != PdfViewerState.GESTURE_STATE_IDLE ||
            viewport.fittedSize == size || size.width == 0 || size.height == 0) return@LaunchedEffect
        // A touch can cancel the initial fit's zoom/scroll mutation. Returning to idle starts
        // this effect again so that the sheet can finish initialization after the gesture.
        val info = try { withContext(Dispatchers.IO) { viewport.document.getPageInfo(0) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        // Restored scroll coordinates can put the centre outside local page zero. Waiting for
        // a centre-point conversion before fixing that position deadlocks offscreen sheets.
        // The public navigation APIs defer until metadata is available; only require a laid-out
        // native view before issuing the initial fit and local-page-zero position.
        val native = withTimeoutOrNull(20_000) {
            var candidate = root.findPdfView(viewport.document)
            while (candidate == null || candidate.width != size.width || candidate.height != size.height) {
                withFrameNanos { }
                candidate = root.findPdfView(viewport.document)
            }
            candidate
        } ?: return@LaunchedEffect
        native.isAutoScrollingEnabled = false
        viewport.nativeView = native
        if (info == null) {
            // Native rendering may recover from a transient metadata error; keep the page usable.
            viewport.fallbackFitZoom = viewport.viewer.zoom
            viewport.initialized = true
            return@LaunchedEffect
        }
        viewport.pageWidth = info.width
        viewport.pageHeight = info.height
        val fit = minOf(size.width.toFloat() / info.width, size.height.toFloat() / info.height)
        viewport.fallbackFitZoom = fit
        viewport.viewer.zoomScroll { zoomTo(fit) }
        viewport.viewer.scrollToPosition(PdfPoint(0, info.width / 2f, info.height / 2f))
        viewport.fittedSize = size
        viewport.initialized = true
    }
    PdfViewerStyle(colors, viewport.document) { attachDocument ->
        PdfViewer(
            pdfDocument = viewport.document.takeIf { attachDocument }, state = viewport.viewer,
            modifier = modifier,
            isFormFillingEnabled = false, isImageSelectionEnabled = false,
            filterContextMenuComponents = { viewport.viewer.currentSelection.toActionTextSelection() == null },
            onFirstContentLoad = {
                viewport.nativeView = root.findPdfView(viewport.document)?.also { it.isAutoScrollingEnabled = false }
                viewport.ready = true
            },
            onUrlLinkClicked = { currentLinkHandler(it) },
        )
    }
}
