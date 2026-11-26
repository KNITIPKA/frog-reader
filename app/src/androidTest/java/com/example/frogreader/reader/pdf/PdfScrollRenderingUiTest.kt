@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.InputDevice
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.debug.PdfSinglePageTestActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Samples actual draw frames during ordinary finger drags through the production reader. */
@RunWith(AndroidJUnit4::class)
class PdfScrollRenderingUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 180_000) fun mixedSizePagesKeepContentDuringForwardReverseAndZoomedScroll() {
        val file = File(instrumentation.targetContext.cacheDir, "scroll-render-fixture.pdf")
        val pdf = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(36) { index ->
                val width = if (index % 3 == 0) 720 else 600
                val height = if (index % 3 == 0) 600 else 900
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index).create())
                page.canvas.drawColor(Color.rgb(235, 245, 250))
                for (row in 0 until 50) {
                    paint.color = if (row % 2 == 0) Color.BLUE else Color.DKGRAY
                    paint.textSize = 9f
                    page.canvas.drawText("PAGE ${index + 1} / reading content / row $row", 15f, 25f + row * 10, paint)
                }
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        try { exercise(file, initialPage = 14, pageWidth = 600f, zoomed = true) }
        finally { file.delete() }
    }

    @Test(timeout = 180_000) fun suppliedTextbookKeepsContentDuringOrdinaryFingerScroll() {
        val path = InstrumentationRegistry.getArguments().getString("pdfScrollFixture")
        assumeTrue("Pass an on-device PDF path to run the supplied-book check", !path.isNullOrBlank())
        val file = File(path!!)
        assertTrue(file.isFile)
        for (page in listOf(76, 91)) exercise(file, initialPage = page, pageWidth = 888f, zoomed = false)
    }

    private fun exercise(file: File, initialPage: Int, pageWidth: Float, zoomed: Boolean) {
        val context = instrumentation.targetContext
        val intent = Intent(context, PdfSinglePageTestActivity::class.java)
            .setData(Uri.fromFile(file))
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_PAGE, initialPage)
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_ZOOM, context.resources.displayMetrics.widthPixels / pageWidth)
            .putExtra(PdfSinglePageTestActivity.EXTRA_SINGLE_PAGE, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PdfSinglePageTestActivity>(intent).use { host ->
            await("initial page and full detail") {
                var ready = false
                host.onActivity { activity ->
                    activity.loadFailure?.let { throw AssertionError(it) }
                    ready = activity.sourcePage() == initialPage && activity.nativeViewer()?.arePagesFullyRendered() == true
                }
                ready
            }
            // Begin immediately after visible detail is ready; do not wait for neighbours.
            var frames = 0
            var missing = 0
            var consecutive = 0
            var longest = 0
            val reached = mutableSetOf<Int>()
            var phase = "drag"
            lateinit var observer: ViewTreeObserver.OnDrawListener
            host.onActivity { activity ->
                observer = ViewTreeObserver.OnDrawListener {
                    activity.nativeViewer()?.let { view ->
                        frames++
                        reached += view.firstVisiblePage
                        if (!view.hasVisiblePageContent()) {
                            missing++; consecutive++; longest = maxOf(longest, consecutive)
                            Log.w("PdfScrollRendering", "missing phase=$phase first=${view.firstVisiblePage} count=${view.visiblePagesCount} zoom=${view.zoom} scroll=${view.scrollY}")
                        } else consecutive = 0
                    }
                }
                activity.window.decorView.viewTreeObserver.addOnDrawListener(observer)
            }
            try {
                // Same gesture on both directions. x=35% stays far away from the right thumb.
                repeat(5) { drag(host, forward = true, duration = 450) }
                repeat(7) { drag(host, forward = false, duration = 450) }
                repeat(3) { drag(host, forward = true, duration = 240) }
                phase = "fling"
                repeat(2) { drag(host, forward = true, duration = 240, stopUnderFinger = false); SystemClock.sleep(700) }
                repeat(2) { drag(host, forward = false, duration = 240, stopUnderFinger = false); SystemClock.sleep(700) }
                if (zoomed) {
                    phase = "pinch"
                    pinch(host)
                    phase = "zoomed drag"
                    SystemClock.sleep(500)
                    repeat(3) { drag(host, forward = true, duration = 450) }
                    repeat(3) { drag(host, forward = false, duration = 450) }
                }
                await("final full-resolution refinement") {
                    var ready = false
                    host.onActivity { ready = it.nativeViewer()?.arePagesFullyRendered() == true }
                    ready
                }
                val diagnostics = "initial=$initialPage frames=$frames missing=$missing longest=$longest pages=${reached.sorted()}"
                Log.i("PdfScrollRendering", diagnostics)
                File(context.getExternalFilesDir(null), "pdf-scroll-metrics-${if (zoomed) "mixed" else "textbook-$initialPage"}.txt").writeText(diagnostics)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "pdf-scroll-${if (zoomed) "mixed" else "textbook-$initialPage"}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertTrue("Must inspect moving frames: $diagnostics", frames > 60)
                assertTrue("Must cross several page boundaries: $diagnostics", reached.size >= 4)
                assertEquals("No exposed white sheet while dragging: $diagnostics", 0, missing)
                host.onActivity { assertFalse(it.failed()) }
            } finally {
                host.onActivity { it.window.decorView.viewTreeObserver.removeOnDrawListener(observer) }
            }
        }
    }

    private fun drag(host: ActivityScenario<PdfSinglePageTestActivity>, forward: Boolean, duration: Long, stopUnderFinger: Boolean = true) {
        var x = 0f; var from = 0f; var to = 0f
        host.onActivity { activity ->
            val view = activity.nativeViewer()!!
            val origin = IntArray(2).also(view::getLocationOnScreen)
            x = origin[0] + view.width * 0.35f
            val upper = origin[1] + view.height * 0.2f
            val lower = origin[1] + view.height * 0.8f
            from = if (forward) lower else upper
            to = if (forward) upper else lower
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
                instrumentation.sendPointerSync(it); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        val steps = (duration / 16).toInt()
        for (step in 1..steps) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * step / steps)
        }
        // Stop under the finger, isolating continuous dragging from fling physics.
        if (stopUnderFinger) {
            SystemClock.sleep(100)
            send(MotionEvent.ACTION_MOVE, to)
        }
        send(MotionEvent.ACTION_UP, to)
    }

    private fun pinch(host: ActivityScenario<PdfSinglePageTestActivity>) {
        var centerX = 0f; var centerY = 0f; var span = 0f
        host.onActivity {
            val view = it.nativeViewer()!!
            val origin = IntArray(2).also(view::getLocationOnScreen)
            centerX = origin[0] + view.width * 0.4f
            centerY = origin[1] + view.height * 0.5f
            span = view.width * 0.12f
        }
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        fun send(action: Int, scale: Float, count: Int = 2) {
            val points = Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = centerX + (if (index == 0) -1 else 1) * span * scale
                y = centerY; pressure = 1f; size = 1f
            } }
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, points,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).let {
                instrumentation.sendPointerSync(it); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 1f, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1f)
        for (step in 1..30) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, 1f + step / 30f)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2f)
        send(MotionEvent.ACTION_UP, 2f, 1)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description")
    }
}
