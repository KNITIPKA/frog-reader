@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.pdf.PdfPoint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.R
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.debug.PdfSelectionTestActivity
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses native selection, the production toolbar/editor/viewer, and a disposable in-memory note. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfNoteUiTest {
    @Test(timeout = 75_000)
    fun sixActionsUseEqualTilesAndHighlightCapturesAmberSelection() = withSelection { host, _ ->
        val actionIds = listOf(R.string.selection_search, R.string.selection_define, R.string.selection_translate,
            R.string.pdf_selection_highlight, R.string.pdf_selection_add_note, R.string.selection_copy)
        val tiles = awaitStableActionTiles(host, actionIds)
        Log.i("PdfNoteUiTest", "Compose measured selection action tiles: ${actionIds.map(::resourceText).zip(tiles)}")
        tiles.forEach { tile ->
            assertEquals("All six actions have equal widths", tiles.first().width().toFloat(), tile.width().toFloat(), 1f)
            assertEquals("All six actions have equal heights", tiles.first().height().toFloat(), tile.height().toFloat(), 1f)
        }
        fun sameRow(row: List<Rect>): Boolean = row.all { kotlin.math.abs(it.top - row.first().top) <= 1 }
        val columns = when {
            sameRow(tiles.take(3)) -> 3
            sameRow(tiles.take(2)) -> 2 // The accessible narrow/large-text layout.
            else -> throw AssertionError("Selection actions must form a 3x2 or 2x3 grid: $tiles")
        }
        val rows = tiles.chunked(columns)
        rows.forEach { row ->
            assertTrue("Actions in each row share a top edge: $row", sameRow(row))
            row.zipWithNext().forEach { (left, right) ->
                assertTrue("Each row preserves action order with a gap between tiles", left.right < right.left)
            }
            row.forEachIndexed { column, tile ->
                assertEquals("Each column shares a left edge", rows.first()[column].left.toFloat(), tile.left.toFloat(), 1f)
            }
        }
        rows.zipWithNext().forEach { (upper, lower) ->
            assertTrue("Action rows remain distinct", upper.maxOf { it.bottom } < lower.minOf { it.top })
        }
        clickResource(R.string.pdf_selection_highlight)
        await("Highlight callback with detached bounds and cleared native selection") {
            var complete = false
            host.onActivity {
                complete = it.highlightedText?.contains(WORD) == true && it.highlightedBounds.isNotEmpty() &&
                    it.highlightedColor == QuoteColor.AMBER && it.selectionIsCleared()
            }
            complete
        }
    }

    @Test(timeout = 90_000)
    fun addNoteRemapsPageClearsSelectionOpensKeyboardAndReopensOnPassageTap() = withSelection(sourcePage = 7) { host, point ->
        clickResource(R.string.pdf_selection_add_note)
        var capturedBounds = emptyList<PdfTextBounds>()
        await("detached source-page selection in the note editor") {
            var complete = false
            host.onActivity {
                capturedBounds = it.addedNoteBounds
                complete = it.addedNoteText?.contains(WORD) == true && capturedBounds.isNotEmpty() &&
                    capturedBounds.all { bounds -> bounds.pageIndex == 7 } && it.selectionIsCleared()
            }
            complete && findNode { it.isEditable && it.isFocused } != null
        }
        await("note editor keyboard") {
            var visible = false
            host.onActivity { visible = it.imeVisibleForTest() }
            visible
        }
        assertNull("The selection menu disappears while editing a note", findText(resourceText(R.string.selection_copy)))
        assertFalse("An empty note cannot be saved", clickableAncestor(requireNotNull(findText(resourceText(R.string.pdf_note_save)))).isEnabled)
        setEditableText(NOTE)
        clickResource(R.string.pdf_note_save)
        await("saved note retains exact selection bounds") {
            var saved = false
            host.onActivity {
                saved = it.savedNoteForTest()?.let { quote -> quote.note == NOTE && quote.pdfSelection == capturedBounds } == true
            }
            saved && findNode { it.isEditable } == null
        }
        await("keyboard dismisses after saving") {
            var hidden = false
            host.onActivity { hidden = !it.imeVisibleForTest() }
            hidden
        }
        // Resolve again after IME insets settle: a keyboard can move the native viewport.
        var passagePoint: PointF? = null
        host.onActivity { passagePoint = it.pointOnScreen(point) }
        tap(requireNotNull(passagePoint))
        await("note sheet opened by tapping the actual PDF passage") {
            findText(NOTE) != null && findText(resourceText(R.string.pdf_note_edit)) != null
        }
        clickResource(R.string.pdf_note_edit)
        await("editing the reopened note retains its text") { findNode { it.isEditable }?.text?.toString() == NOTE }
        setEditableText("$NOTE Edited.")
        clickResource(R.string.pdf_note_save)
        await("edited note replaces the existing note") {
            var saved = false
            host.onActivity { saved = it.savedNoteForTest()?.note == "$NOTE Edited." }
            saved
        }
    }

    private fun withSelection(sourcePage: Int? = null, block: (ActivityScenario<PdfSelectionTestActivity>, PdfPoint) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = createTextPdf(context.cacheDir)
        var scenario: ActivityScenario<PdfSelectionTestActivity>? = null
        try {
            val matchPoint = runBlocking(Dispatchers.IO) {
                withTimeout(20_000) {
                    openReaderPdfDocument(context, Uri.fromFile(source), null, forceCompatibilityText = true).use { document ->
                        val bounds = document.searchDocument(WORD, 0..0)[0].orEmpty().first().bounds.first()
                        PdfPoint(0, bounds.centerX(), bounds.centerY())
                    }
                }
            }
            val intent = Intent(context, PdfSelectionTestActivity::class.java).setData(Uri.fromFile(source))
                .putExtra(PdfSelectionTestActivity.EXTRA_COMPATIBILITY_TEXT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            sourcePage?.let { intent.putExtra(PdfSelectionTestActivity.EXTRA_SOURCE_PAGE_INDEX, it) }
            scenario = ActivityScenario.launch(intent)
            val host = scenario
            await("native PDF content") {
                var ready = false
                host.onActivity {
                    it.loadFailure?.let { failure -> throw AssertionError("PDF fixture failed", failure) }
                    ready = it.contentReady
                }
                ready
            }
            instrumentation.waitForIdleSync()
            var point: PointF? = null
            host.onActivity { point = it.pointOnScreen(matchPoint) }
            longPress(requireNotNull(point))
            await("the six selection actions") {
                var selected = false
                host.onActivity { selected = it.toolbarSelectionText?.contains(WORD) == true }
                selected && findText(resourceText(R.string.pdf_selection_add_note)) != null
            }
            block(host, matchPoint)
        } finally {
            try { scenario?.close() }
            finally { source.delete() }
        }
    }

    private fun awaitStableActionTiles(host: ActivityScenario<PdfSelectionTestActivity>, actionIds: List<Int>): List<Rect> {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val started = SystemClock.uptimeMillis()
        var previous = emptyList<Rect>()
        var latest = emptyList<Rect>()
        var stableSamples = 0
        await("six enabled selection actions have stable Compose layout measurements") {
            var snapshot = emptyList<Rect>()
            host.onActivity { snapshot = it.actionTilesForTest() }
            val enabled = actionIds.all { resource ->
                findText(resourceText(resource))?.let(::clickableAncestor)?.isEnabled == true
            }
            if (snapshot.size != actionIds.size || snapshot.any { it.isEmpty } || !enabled) {
                stableSamples = 0
                false
            } else {
                latest = snapshot
                stableSamples = if (snapshot == previous) stableSamples + 1 else 1
                previous = snapshot
                // Accessibility still supplies the action nodes used for real clicks. Exact
                // grid assertions use Compose's measured sizes in the tray's coordinates,
                // avoiding stale accessibility screen bounds after graphics-layer animation.
                SystemClock.uptimeMillis() - started >= 220 && stableSamples >= 3
            }
        }
        return latest
    }

    private fun setEditableText(value: String) {
        val field = requireNotNull(findNode { it.isEditable })
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        await("note text entered") { findNode { it.isEditable }?.text?.toString() == value }
    }

    private fun clickResource(resource: Int) {
        await("click ${resourceText(resource)}") {
            findText(resourceText(resource))?.let(::clickableAncestor)?.takeIf { it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun resourceText(resource: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private fun findText(text: String) = findNode { it.text?.toString() == text || it.contentDescription?.toString() == text }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = current.parent ?: return current
        return current
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun longPress(point: PointF) = press(point, ViewConfiguration.getLongPressTimeout().toLong() + 200)
    private fun tap(point: PointF) = press(point, 40)

    private fun press(point: PointF, duration: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int) {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0).also { event ->
                try { event.source = InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(event) }
                finally { event.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(duration)
        send(MotionEvent.ACTION_UP)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $label")
    }

    private fun createTextPdf(directory: File): File {
        val file = File.createTempFile("pdf-note-ui-", ".pdf", directory)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
                    page.canvas.drawText("$WORD note gesture", 20f, 110f, paint)
                } finally { document.finishPage(page) }
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    private companion object {
        const val WORD = "FrogReader"
        const val NOTE = "Possible cause of workplace conflict."
    }
}
