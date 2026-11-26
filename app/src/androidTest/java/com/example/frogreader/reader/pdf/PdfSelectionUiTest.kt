package com.example.frogreader.reader.pdf

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
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
import com.example.frogreader.data.pdf.PdfCapabilities
import com.example.frogreader.debug.PdfSelectionTestActivity
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native gestures and the app's shared action bar, using a disposable cache document. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfSelectionUiTest {
    @Test(timeout = 60_000)
    fun compatibilityLongPressShowsActionsAndCopiesSelectedText() = exerciseSelection(forceCompatibility = true)

    @Test(timeout = 60_000)
    fun nativeLongPressShowsActionsAndCopiesSelectedText() {
        assumeTrue("Native text selection requires platform PDF text APIs", PdfCapabilities.canUseText())
        exerciseSelection(forceCompatibility = false)
    }

    private fun exerciseSelection(forceCompatibility: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = createTextPdf(context.cacheDir)
        var scenario: ActivityScenario<PdfSelectionTestActivity>? = null
        try {
            val matchPoint = runBlocking(Dispatchers.IO) {
                withTimeout(20_000) {
                    openReaderPdfDocument(context, Uri.fromFile(source), null, forceCompatibility).use { document ->
                        val bounds = document.searchDocument(WORD, 0..0)[0].orEmpty().first().bounds.first()
                        PdfPoint(0, bounds.centerX(), bounds.centerY())
                    }
                }
            }
            scenario = ActivityScenario.launch(Intent(context, PdfSelectionTestActivity::class.java)
                .setData(Uri.fromFile(source))
                .putExtra(PdfSelectionTestActivity.EXTRA_COMPATIBILITY_TEXT, forceCompatibility)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = scenario
            await("PDF content load") {
                var ready = false
                host.onActivity { activity ->
                    activity.loadFailure?.let { throw AssertionError("PDF fixture failed to load", it) }
                    ready = activity.contentReady
                }
                ready
            }
            instrumentation.waitForIdleSync()
            var screenPoint: PointF? = null
            host.onActivity { screenPoint = it.pointOnScreen(matchPoint) }
            assertNotNull("The visible PDF word must have screen coordinates", screenPoint)
            longPress(screenPoint!!)
            await("selection and app toolbar") {
                var selected = false
                host.onActivity { selected = it.toolbarSelectionText?.contains(WORD) == true }
                selected
            }
            val copyLabel = context.getString(R.string.selection_copy)
            await("Copy action") { clickAccessibilityText(copyLabel) }
            await("copied text and cleared selection") {
                var copied = false
                host.onActivity { activity ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    copied = activity.copiedText?.contains(WORD) == true && activity.selectionIsCleared() &&
                        clipboard.primaryClip?.getItemAt(0)?.text?.contains(WORD) == true
                }
                copied
            }
        } finally {
            try { scenario?.close() }
            finally { source.delete() }
        }
    }

    private fun longPress(point: PointF) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int) {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0).also { event ->
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.sendPointerSync(event)
                } finally { event.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
        send(MotionEvent.ACTION_UP)
    }

    private fun clickAccessibilityText(text: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        fun findAndClick(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val matches = node.text?.toString()?.equals(text, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.equals(text, ignoreCase = true) == true
            if (matches) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    current = current.parent
                }
            }
            for (i in 0 until node.childCount) {
                if (findAndClick(node.getChild(i))) return true
            }
            return false
        }
        return findAndClick(root)
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
        val file = File.createTempFile("pdf-selection-", ".pdf", directory)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
                    page.canvas.drawText("$WORD selection gesture", 20f, 110f, paint)
                } finally { document.finishPage(page) }
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private companion object { const val WORD = "FrogReader" }
}
