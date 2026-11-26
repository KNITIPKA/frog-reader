@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)
@file:Suppress("RestrictedApi")

package com.example.frogreader.reader.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfDocument as GeneratedPdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
import androidx.pdf.content.PdfPageTextContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.debug.PdfSelectionTestActivity
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.SinglePagePdfDocument
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import com.example.frogreader.ui.reader.pdf.pdfSinglePageSwipe
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real multi-page files through AndroidX and the isolated text service, without library writes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class SinglePagePdfIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90_000)
    fun sourcePagesRenderAndRemainSearchableThroughLocalPageZero() = exerciseAdapters(forceCompatibilityText = false)

    @Test(timeout = 90_000)
    fun compatibilitySearchAndSelectionUseTheChosenSourcePage() = exerciseAdapters(forceCompatibilityText = true)

    private fun exerciseAdapters(forceCompatibilityText: Boolean) = runBlocking(Dispatchers.IO) {
        val source = createMultiPagePdf()
        try {
            withTimeout(60_000) {
                PdfDocumentSession(openDocument = { uri, password ->
                    openReaderPdfDocument(context, uri, password, forceCompatibilityText)
                }).use { session ->
                    val document = session.load(Uri.fromFile(source))
                    assertEquals(PAGES.size, document.pageCount)
                    // Deliberately start on page 2, then move backwards and forwards. A wrapper that
                    // accidentally renders source page 0 will fail both text and exact marker checks.
                    for (sourcePage in listOf(1, 0, 2, 1)) {
                        SinglePagePdfDocument(document, sourcePage).use { page ->
                            assertEquals(sourcePage, page.sourcePageIndex)
                            assertEquals(1, page.pageCount)
                            val info = page.getPageInfo(0)
                            assertEquals(0, info.pageNum)
                            assertEquals(pageWidth(sourcePage), info.width)
                            assertEquals(pageHeight(sourcePage), info.height)
                            assertEquals(listOf(0), page.getPageInfos(0..0).map { it.pageNum })
                            assertMarker(page, sourcePage)
                            assertTextMappedToLocalPage(page, sourcePage)
                        }
                        assertSame("Closing a page adapter must leave the document owned by its session", document, session.document)
                        assertEquals(PAGES.size, document.pageCount)
                        // A fresh renderer and text request after adapter close must still work.
                        assertEquals(pageWidth(sourcePage), document.getPageInfo(sourcePage).width)
                        assertTrue(document.searchDocument(PAGES[sourcePage].word, sourcePage..sourcePage)[sourcePage].orEmpty().isNotEmpty())
                    }
                }
            }
        } finally { source.delete() }
    }

    @Test(timeout = 90_000)
    fun nativeViewerSwipesAdvanceOnceAndReverseWithoutClosingSession() {
        val source = createMultiPagePdf()
        var scenario: ActivityScenario<PdfSelectionTestActivity>? = null
        val session = PdfDocumentSession(openDocument = { uri, password ->
            openReaderPdfDocument(context, uri, password, forceCompatibilityText = true)
        })
        val adapters = mutableListOf<SinglePagePdfDocument>()
        try {
            val document = runBlocking(Dispatchers.IO) { withTimeout(30_000) { session.load(Uri.fromFile(source)) } }
            scenario = ActivityScenario.launch(Intent(context, PdfSelectionTestActivity::class.java)
                .setData(Uri.fromFile(source))
                .putExtra(PdfSelectionTestActivity.EXTRA_COMPATIBILITY_TEXT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = scenario
            host.onActivity { activity ->
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            await("fixture window focus") {
                var focused = false
                host.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            await("fixture document load") {
                var ready = false
                host.onActivity { activity ->
                    activity.loadFailure?.let { throw AssertionError("Fixture could not load", it) }
                    ready = activity.contentReady
                }
                ready
            }
            val initial = SinglePagePdfDocument(document, 1).also { adapters += it }
            val current = mutableStateOf(initial)
            val loadedAdapters = ConcurrentHashMap.newKeySet<SinglePagePdfDocument>()
            val pageTurns = AtomicInteger(0)
            var viewerState: PdfViewerState? = null
            var viewerOrigin = Offset.Zero
            var viewerSize = IntSize.Zero
            host.onActivity { activity ->
                // Reuse the existing debug activity without changing it. Its original viewer is
                // disposed before this independent, session-owned integration viewer is installed.
                activity.setContent {
                    val page = current.value
                    key(page) {
                        val state = rememberPdfViewerState()
                        SideEffect { viewerState = state }
                        PdfViewer(
                            pdfDocument = page,
                            state = state,
                            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                viewerOrigin = it.positionOnScreen()
                                viewerSize = it.size
                            }.pdfSinglePageSwipe(
                                enabled = true,
                                selectionActive = state.currentSelection != null,
                                onTurnPage = { direction ->
                                    pageTurns.incrementAndGet()
                                    val next = (current.value.sourcePageIndex + direction).coerceIn(0, document.pageCount - 1)
                                    if (next != current.value.sourcePageIndex) {
                                        current.value = SinglePagePdfDocument(document, next).also { adapters += it }
                                    }
                                },
                            ),
                            isFormFillingEnabled = false,
                            isImageSelectionEnabled = false,
                            filterContextMenuComponents = { false },
                            onFirstContentLoad = { loadedAdapters += page },
                        )
                    }
                }
            }
            var previousPage = 1
            for ((turn, sourcePage) in listOf(1, 2, 1, 0).withIndex()) {
                if (turn > 0) {
                    await("window focus before swipe $turn") {
                        var focused = false
                        host.onActivity { focused = it.hasWindowFocus() }
                        focused
                    }
                    var start = Offset.Zero
                    var stop = Offset.Zero
                    host.onActivity {
                        assertTrue("The native viewer must have a laid-out swipe area", viewerSize.width > 0 && viewerSize.height > 0)
                        val next = sourcePage > previousPage
                        start = viewerOrigin + Offset(viewerSize.width * if (next) 0.8f else 0.2f, viewerSize.height * 0.65f)
                        stop = viewerOrigin + Offset(viewerSize.width * if (next) 0.2f else 0.8f, viewerSize.height * 0.65f)
                    }
                    swipe(start, stop)
                }
                var expectedAdapter: SinglePagePdfDocument? = null
                host.onActivity {
                    assertEquals("Swipe $turn must trigger exactly one page-turn callback before rendering", turn, pageTurns.get())
                    assertEquals("Swipe $turn must choose source page ${sourcePage + 1} before rendering", sourcePage, current.value.sourcePageIndex)
                    expectedAdapter = current.value
                }
                await("native viewer content for source page ${sourcePage + 1} after $turn recognized swipes") {
                    expectedAdapter?.let(loadedAdapters::contains) == true
                }
                var lastRenderDiagnostic = "No marker sample was available"
                try {
                    await("rendered marker for source page ${sourcePage + 1}") {
                        var point: Offset? = null
                        var windowFocused = false
                        var zoom: Float? = null
                        host.onActivity {
                            point = viewerState?.pdfPointToVisibleOffset(PdfPoint(0, MARKER_X, MARKER_Y))
                                ?.let { it + viewerOrigin }
                            windowFocused = it.hasWindowFocus()
                            zoom = viewerState?.zoom
                        }
                        lastRenderDiagnostic = "point=$point, origin=$viewerOrigin, viewerSize=$viewerSize, focused=$windowFocused, zoom=$zoom"
                        val target = point ?: return@await false
                        if (!target.x.isFinite() || !target.y.isFinite()) return@await false
                        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                        try {
                            val x = target.x.toInt()
                            val y = target.y.toInt()
                            val inBounds = x in 0 until screenshot.width && y in 0 until screenshot.height
                            val actual = if (inBounds) screenshot.getPixel(x, y) else null
                            lastRenderDiagnostic += ", screenshot=${screenshot.width}x${screenshot.height}, expected=${colorHex(PAGES[sourcePage].color)}, actual=${actual?.let(::colorHex)}"
                            inBounds && windowFocused && similarColor(PAGES[sourcePage].color, requireNotNull(actual))
                        } finally { screenshot.recycle() }
                    }
                } catch (failure: AssertionError) {
                    val screenshotPath = saveFailureScreenshot("pdf-single-page-source-${sourcePage + 1}.png")
                    throw AssertionError("${failure.message}; $lastRenderDiagnostic; screenshot=$screenshotPath", failure)
                }
                instrumentation.waitForIdleSync()
                host.onActivity {
                    assertEquals("A physical swipe must turn exactly one page", sourcePage, current.value.sourcePageIndex)
                    assertEquals("Each completed physical swipe must trigger one page turn", turn, pageTurns.get())
                }
                assertSame(document, session.document)
                previousPage = sourcePage
            }
        } finally {
            try { scenario?.close() }
            finally {
                adapters.forEach { it.close() }
                session.close()
                source.delete()
            }
        }
    }

    private suspend fun assertMarker(document: PdfDocument, sourcePage: Int) {
        document.getPageBitmapSource(0).use { source ->
            assertEquals(0, source.pageNumber)
            val bitmap = source.getBitmap(Size(pageWidth(sourcePage), pageHeight(sourcePage)))
            try {
                assertTrue("Local page zero must render the selected source page ${sourcePage + 1}",
                    similarColor(PAGES[sourcePage].color, bitmap.getPixel(MARKER_X.toInt(), MARKER_Y.toInt())))
            } finally { bitmap.recycle() }
        }
    }

    private suspend fun assertTextMappedToLocalPage(document: PdfDocument, sourcePage: Int) {
        val word = PAGES[sourcePage].word
        val found = document.searchDocument(word, 0..0)
        assertEquals(1, found.size())
        assertEquals(0, found.keyAt(0))
        val matches = found[0].orEmpty()
        assertTrue("The selected source page must remain searchable", matches.isNotEmpty())
        val bounds = matches.first().bounds.first { it.width() > 0 && it.height() > 0 }
        val point = PointF(bounds.centerX(), bounds.centerY())
        val selection = requireNotNull(document.getSelectionBounds(0, point, point))
        assertEquals("Point selection must report the viewer's local page", 0, selection.page)
        assertTrue(selection.selectedContents.filterIsInstance<PdfPageTextContent>().any { it.text.contains(word) })
        val all = requireNotNull(document.getSelectAllSelectionBounds(0))
        assertEquals(0, all.page)
        assertTrue(all.selectedContents.filterIsInstance<PdfPageTextContent>().any { it.text.contains(word) })
        assertTrue(document.getPageContent(0)?.textContents.orEmpty().any { it.text.contains(word) })
        val otherWord = PAGES[(sourcePage + 1) % PAGES.size].word
        assertEquals("Text from other source pages must not leak into local page zero", 0,
            document.searchDocument(otherWord, 0..0).size())
    }

    private fun createMultiPagePdf(): File {
        val file = File.createTempFile("frog-pdf-single-page-", ".pdf", context.cacheDir)
        try {
            val document = GeneratedPdfDocument()
            try {
                PAGES.forEachIndexed { index, fixture ->
                    val page = document.startPage(GeneratedPdfDocument.PageInfo.Builder(pageWidth(index), pageHeight(index), index + 1).create())
                    try {
                        page.canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fixture.color }
                        page.canvas.drawRect(MARKER_X - 40, MARKER_Y - 40, MARKER_X + 40, MARKER_Y + 40, paint)
                        paint.color = Color.BLACK
                        paint.textSize = 18f
                        page.canvas.drawText("${fixture.word} page ${index + 1}", 20f, 110f, paint)
                    } finally { document.finishPage(page) }
                }
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun saveFailureScreenshot(name: String): String? {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return try {
            val file = File(context.cacheDir, name)
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } finally { screenshot.recycle() }
    }

    private fun colorHex(color: Int) = "#%08x".format(color)

    private fun swipe(start: Offset, stop: Offset) {
        val downTime = SystemClock.uptimeMillis()
        val durationMillis = 120L
        val steps = 8
        fun send(action: Int, fraction: Float, elapsedMillis: Long) {
            val x = start.x + (stop.x - start.x) * fraction
            val y = start.y + (stop.y - start.y) * fraction
            // Input dispatch can pause under instrumentation. Keep gesture timestamps bounded
            // instead of accidentally making a deliberate swipe look like a long press.
            val event = MotionEvent.obtain(downTime, downTime + elapsedMillis, action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f, 0L)
        for (step in 1..steps) {
            val elapsed = durationMillis * step / steps
            val wait = downTime + elapsed - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            send(MotionEvent.ACTION_MOVE, step.toFloat() / steps, elapsed)
        }
        send(MotionEvent.ACTION_UP, 1f, durationMillis)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $label")
    }

    private fun similarColor(expected: Int, actual: Int): Boolean =
        kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 12 &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 12 &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 12

    private fun pageWidth(index: Int) = 300 + index * 20
    private fun pageHeight(index: Int) = 420 + index * 30

    private data class PageFixture(val word: String, val color: Int)

    private companion object {
        const val MARKER_X = 120f
        const val MARKER_Y = 220f
        val PAGES = listOf(
            PageFixture("FirstFrog", Color.rgb(180, 40, 40)),
            PageFixture("SecondFrog", Color.rgb(30, 145, 65)),
            PageFixture("ThirdFrog", Color.rgb(40, 75, 190)),
        )
    }
}
