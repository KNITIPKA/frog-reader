@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.debug.PdfSinglePageTestActivity
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfZoomUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Optional on-device A/B measurement; timings are reported, never used as a CI speed gate. */
    @Test(timeout = 180_000) fun suppliedTextbookMeasuresPinchRefinementLatency() {
        val path = InstrumentationRegistry.getArguments().getString("pdfZoomFixture")
        assumeTrue(!path.isNullOrBlank())
        val report = StringBuilder("page,run,frames,partialFrames,blankFrames,pinchAndRefineMillis,afterReleaseMillis\n")
        for (page in listOf(26, 28)) withReader(File(path!!), false, page, 597f) { host ->
            repeat(3) { run ->
                host.onActivity {
                    it.nativeViewer()!!.apply { zoom = 3f; scrollToPosition(PdfPoint(page, 420f, 455f)) }
                }
                awaitReady(host)
                var frames = 0; var partial = 0; var blank = 0
                lateinit var observer: ViewTreeObserver.OnDrawListener
                host.onActivity { activity ->
                    observer = ViewTreeObserver.OnDrawListener {
                        activity.nativeViewer()?.let { view ->
                            frames++
                            if (!view.arePagesFullyRendered()) partial++
                            if (!view.hasVisiblePageContent()) blank++
                        }
                    }
                    activity.window.decorView.viewTreeObserver.addOnDrawListener(observer)
                }
                val start = SystemClock.uptimeMillis()
                try {
                    pinch(host)
                    val released = SystemClock.uptimeMillis()
                    awaitReady(host)
                    val end = SystemClock.uptimeMillis()
                    report.append("${page + 1},$run,$frames,$partial,$blank,${end - start},${end - released}\n")
                    assertEquals("Pinching must preserve already displayed page content", 0, blank)
                } finally {
                    host.onActivity { it.window.decorView.viewTreeObserver.removeOnDrawListener(observer) }
                }
            }
        }
        File(context.getExternalFilesDir(null), "pdf-pinch-latency.csv").writeText(report.toString())
    }

    @Test(timeout = 180_000) fun suppliedTextbookRefinesAfterFastZoomedPageCrossing() {
        val path = InstrumentationRegistry.getArguments().getString("pdfZoomFixture")
        assumeTrue(!path.isNullOrBlank())
        withReader(File(path!!), false, 26, 597f) { host ->
            var factor = 1f
            host.onActivity {
                it.nativeViewer()!!.apply {
                    scrollToPosition(PdfPoint(26, 420f, 650f))
                    factor = 6f / zoom
                }
            }
            // A raw zoom setter followed by scrollToPosition temporarily navigates to a different
            // page and evicts the real neighbours. Reproduce the user's continuous pinch instead.
            pinch(host, factor = factor)
            awaitReady(host)
            var frames = 0
            var partial = 0
            var blank = 0
            val missingBaseStates = mutableListOf<String>()
            val pages = mutableSetOf<Int>()
            lateinit var observer: ViewTreeObserver.OnDrawListener
            host.onActivity { activity ->
                observer = ViewTreeObserver.OnDrawListener {
                    activity.nativeViewer()?.let { view ->
                        frames++
                        if (!view.arePagesFullyRendered()) partial++
                        if (!view.hasVisiblePageContent()) {
                            blank++
                            if (missingBaseStates.size < 12) missingBaseStates += renderDiagnostic(view)
                        }
                        activity.sourcePage()?.let(pages::add)
                    }
                }
                activity.window.decorView.viewTreeObserver.addOnDrawListener(observer)
            }
            try {
                repeat(5) { dragVertically(host, stopUnderFinger = it == 4) }
                val stopped = SystemClock.uptimeMillis()
                awaitReady(host)
                val refineMillis = SystemClock.uptimeMillis() - stopped
                val metrics = "frames=$frames partial=$partial blank=$blank pages=${pages.sorted()} settleMillis=$refineMillis"
                Log.i("PdfZoom", metrics)
                File(context.getExternalFilesDir(null), "pdf-zoom-scroll-metrics.txt").writeText(metrics)
                File(context.getExternalFilesDir(null), "pdf-zoom-missing-base.txt").writeText(missingBaseStates.joinToString("\n"))
                assertTrue("Cross into previously unseen pages: $metrics", pages.size >= 2)
                assertEquals("A fast zoomed scroll must keep content behind the page: $metrics", 0, blank)
                host.onActivity { edgeEnergy(it.nativeViewer()!!, "focus-fast-zoomed-scroll.png") }
            } finally {
                host.onActivity { it.window.decorView.viewTreeObserver.removeOnDrawListener(observer) }
            }
        }
    }

    /** Inspect the debug renderer only on a failing frame; no production reflection or logging. */
    private fun renderDiagnostic(view: PdfView): String = runCatching {
        fun field(owner: Any?, name: String): Any? = owner?.javaClass?.getDeclaredField(name)?.let {
            it.isAccessible = true; it.get(owner)
        }
        val manager = field(view, "pageManager")!!
        val queue = field(manager, "renderQueue")!!
        val active = field(queue, "active")
        val pages = field(manager, "pages") as android.util.SparseArray<*>
        val states = (0 until pages.size()).joinToString { index ->
            val fetcher = field(pages.valueAt(index), "bitmapFetcher")
            val contents = field(fetcher, "contents")
            "${pages.keyAt(index)}:content=${contents?.javaClass?.simpleName},preview=${field(fetcher, "previewBitmap") != null},base=${if (contents?.javaClass?.simpleName == "TileBoard") field(contents, "fullPageBitmap") != null else null}"
        }
        "y=${view.scrollY} full=${view.arePagesFullyRendered()} active=${field(active, "page")}/${field(active, "preview")} [$states]"
    }.getOrElse { it.toString() }

    private fun dragVertically(host: ActivityScenario<PdfSinglePageTestActivity>, stopUnderFinger: Boolean) {
        var x = 0f; var from = 0f; var to = 0f
        host.onActivity {
            val view = it.nativeViewer()!!
            val origin = IntArray(2).also(view::getLocationOnScreen)
            x = origin[0] + view.width * .45f
            from = origin[1] + view.height * .82f
            to = origin[1] + view.height * .18f
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
                instrumentation.sendPointerSync(it); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        repeat(8) { step -> SystemClock.sleep(12); send(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 8) }
        if (stopUnderFinger) {
            SystemClock.sleep(150)
            send(MotionEvent.ACTION_MOVE, to)
        }
        send(MotionEvent.ACTION_UP, to)
    }

    @Test(timeout = 180_000) fun secondFingerPromotesAnAlreadyMovingPageToPinch() {
        val file = fixture()
        try {
            for (single in listOf(true, false)) withReader(file, single, 0, 600f) { host ->
                var before = 0f
                host.onActivity { before = it.nativeViewer()!!.zoom }
                pinch(host, leadDrag = true)
                host.onActivity {
                    assertTrue("Second finger must zoom after horizontal movement (Pages=$single): $before -> ${it.nativeViewer()!!.zoom}",
                        it.nativeViewer()!!.zoom > before * 1.45f)
                    assertEquals("Adding a finger cancels the page turn", 0, it.sourcePage())
                }
            }
        } finally { file.delete() }
    }

    @Test(timeout = 180_000) fun repeatedPinchesAndDirectionReversalsRemainResponsive() {
        val file = fixture()
        try {
            for (single in listOf(true, false)) withReader(file, single, 0, 600f) { host ->
                repeat(6) { index ->
                    var before = 0f
                    host.onActivity { before = it.nativeViewer()!!.zoom }
                    val expand = index % 2 == 0
                    pinch(host, expand = expand)
                    host.onActivity {
                        val after = it.nativeViewer()!!.zoom
                        assertTrue("Pinch $index must change zoom in the intended direction (Pages=$single): $before -> $after",
                            if (expand) after > before * 1.4f else after < before / 1.4f)
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test(timeout = 180_000) fun suppliedTextbookKeepsSharpTextOnTinyZoomAndWhileRefining() {
        val path = InstrumentationRegistry.getArguments().getString("pdfZoomFixture")
        assumeTrue("Supply a device path for the user's textbook", !path.isNullOrBlank())
        val file = File(path!!)
        assertTrue(file.isFile)
        for (single in listOf(true, false)) withReader(file, single, 28, 597f) { host ->
            host.onActivity {
                val view = it.nativeViewer()!!
                view.zoom = 6f
                view.scrollToPosition(PdfPoint(if (single) 0 else 28, 420f, 455f))
            }
            awaitReady(host)
            var before = 0.0
            var immediatelyAfter = 0.0
            host.onActivity {
                val view = it.nativeViewer()!!
                before = edgeEnergy(view)
                view.zoom *= 1.002f
                // This draw occurs before any new async native result can be published.
                immediatelyAfter = edgeEnergy(view, "focus-tiny-zoom-$single.png")
            }
            val ratio = immediatelyAfter / before
            assertTrue("Tiny zoom must preserve fine text before new tiles arrive: ratio=$ratio", ratio > .75)
            // Cross multiple raster levels with real finger input; save the final native view.
            pinch(host)
            awaitReady(host)
            host.onActivity { edgeEnergy(it.nativeViewer()!!, "focus-pinch-$single.png") }
            Log.i("PdfZoom", "textbook Pages=$single immediate edge-energy ratio=$ratio")
            File(context.getExternalFilesDir(null), "pdf-zoom-$single.txt").writeText("tinyZoomEdgeRatio=$ratio\n")
        }
    }

    /** Mean high-frequency contrast in a central text crop, independent of its pixel alignment. */
    private fun edgeEnergy(view: PdfView, output: String? = null): Double {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // View.draw does not apply its own scroll transform (normally supplied by its parent).
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        output?.let { name -> File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var energy = 0.0
        val width = bitmap.width
        for (y in bitmap.height / 5 until bitmap.height * 4 / 5) {
            for (x in width / 5 until width * 4 / 5) {
                val i = y * width + x
                val center = Color.red(pixels[i])
                val laplacian = 4 * center - Color.red(pixels[i - 1]) - Color.red(pixels[i + 1]) -
                    Color.red(pixels[i - width]) - Color.red(pixels[i + width])
                energy += abs(laplacian)
            }
        }
        bitmap.recycle()
        assertTrue("The sampled text crop must contain edges", energy > 1000)
        return energy
    }

    private fun pinch(host: ActivityScenario<PdfSinglePageTestActivity>, leadDrag: Boolean = false,
        expand: Boolean = true, factor: Float = 2f) {
        var cx = 0f; var cy = 0f; var half = 0f
        host.onActivity {
            val view = it.nativeViewer()!!
            val origin = IntArray(2).also(view::getLocationOnScreen)
            cx = origin[0] + view.width * .5f
            cy = origin[1] + view.height * .5f
            half = view.width * .13f
        }
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val start = if (expand) 1f else factor
        val end = if (expand) factor else 1f
        fun send(action: Int, span: Float, count: Int = 2, offset: Float = 0f) {
            val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = cx + (if (index == 0) -1 else 1) * half * span + offset
                y = cy; pressure = 1f; size = 1f
            } }
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).let {
                instrumentation.sendPointerSync(it); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start, 1, if (leadDrag) half * .65f else 0f)
        if (leadDrag) repeat(8) { step ->
            SystemClock.sleep(12)
            send(MotionEvent.ACTION_MOVE, start, 1, half * .65f * (7 - step) / 8f)
        }
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start)
        for (step in 1..24) {
            SystemClock.sleep(12)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 24f)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
        send(MotionEvent.ACTION_UP, end, 1)
        instrumentation.waitForIdleSync()
    }

    private fun withReader(file: File, single: Boolean, page: Int, pageWidth: Float,
        block: (ActivityScenario<PdfSinglePageTestActivity>) -> Unit) {
        val intent = Intent(context, PdfSinglePageTestActivity::class.java)
            .setData(Uri.fromFile(file))
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_PAGE, page)
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_ZOOM, context.resources.displayMetrics.widthPixels / pageWidth)
            .putExtra(PdfSinglePageTestActivity.EXTRA_SINGLE_PAGE, single)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PdfSinglePageTestActivity>(intent).use { host ->
            awaitReady(host)
            // Let production restore/fit effects finish before sending physical input.
            SystemClock.sleep(500)
            awaitReady(host)
            block(host)
            host.onActivity { assertFalse(it.failed()) }
        }
    }

    private fun awaitReady(host: ActivityScenario<PdfSinglePageTestActivity>) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            host.onActivity { ready = it.nativeViewer()?.let { v -> v.visiblePagesCount > 0 && v.arePagesFullyRendered() } == true }
            if (ready) return
            SystemClock.sleep(30)
        }
        fail("Timed out waiting for full-resolution PDF")
    }

    private fun fixture(): File {
        val file = File(context.cacheDir, "pinch-regression.pdf")
        val pdf = PdfDocument()
        try {
            repeat(3) { number ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 840, number).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
                repeat(50) { row -> page.canvas.drawText("PDF page $number / sharp vector text / row $row", 30f, 35f + row * 15, paint) }
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return file
    }
}
