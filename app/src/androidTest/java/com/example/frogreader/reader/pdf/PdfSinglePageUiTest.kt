@file:Suppress("RestrictedApi")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.debug.PdfSinglePageTestActivity
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.reader.pdf.PdfPrefetchPages
import com.example.frogreader.ui.reader.pdf.PdfToolbarColors
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Source-page regression checks through the production surface and real, isolated progress stores. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfSinglePageUiTest {
    private var pageDiagnostics = ""
    private var fixtureLaunchedAtMillis = 0L
    private var currentFixture: ActivityScenario<PdfSinglePageTestActivity>? = null

    @Test(timeout = 90_000)
    fun defaultHighlightStaysYellowOverPageTextInBothModes() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (singlePage in listOf(false, true)) {
            withFixture(initialPage = 0, singlePage = singlePage) { host ->
                awaitPage(host, 0, singlePage = singlePage)
                var word = PointF()
                val region = Rect()
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    fun screenPoint(x: Float, y: Float): PointF =
                        requireNotNull(view.pdfToViewPoint(PdfPoint(0, x, y))).apply {
                            offset(origin[0].toFloat(), origin[1].toFloat())
                        }
                    word = screenPoint(47f, 65f)
                    val start = screenPoint(24f, 57f)
                    val end = screenPoint(69f, 75f)
                    region.set(start.x.toInt(), start.y.toInt(), end.x.toInt(), end.y.toInt())
                }
                val down = SystemClock.uptimeMillis()
                sendTouch(down, MotionEvent.ACTION_DOWN, word)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120)
                sendTouch(down, MotionEvent.ACTION_UP, word)
                clickText(resource(R.string.pdf_selection_highlight))
                await("yellow highlight and black text on the actual PDF page, singlePage=$singlePage") {
                    val image = requireNotNull(automation.takeScreenshot())
                    try {
                        val pixels = IntArray(region.width() * region.height())
                        image.getPixels(pixels, 0, region.width(), region.left, region.top,
                            region.width(), region.height())
                        val yellow = pixels.count {
                            Color.red(it) >= 245 && Color.green(it) >= 245 && Color.blue(it) in 130..200
                        }
                        val black = pixels.count {
                            Color.red(it) <= 20 && Color.green(it) <= 20 && Color.blue(it) <= 20
                        }
                        yellow >= 32 && black >= 32
                    } finally {
                        image.recycle()
                    }
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun neighboursAreRenderedBeforeTurningAndReusedOnReturn() = withFixture { host ->
        awaitPage(host, 0)
        var first: androidx.pdf.view.PdfView? = null
        var next: androidx.pdf.view.PdfView? = null
        await("next native sheet is fitted and fully rendered offscreen") {
            var prepared = false
            host.onActivity { activity ->
                val pages = activity.composedPageViewers()
                first = pages[0]
                next = pages[1]
                prepared = next?.let { it.width > 0 && it.visiblePagesCount > 0 && it.arePagesFullyRendered() } == true
            }
            prepared
        }
        swipe(host, forward = true) {
            await("both prepared native sheets follow the finger before it is lifted") {
                var sliding = false
                host.onActivity {
                    val outgoing = IntArray(2).also(first!!::getLocationOnScreen)
                    val incoming = IntArray(2).also(next!!::getLocationOnScreen)
                    sliding = outgoing[0] < 0 && incoming[0] in 1 until next!!.width &&
                        first!!.arePagesFullyRendered() && next!!.arePagesFullyRendered()
                }
                sliding
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "pdf-finger-drag.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        awaitPage(host, 1)
        host.onActivity { activity ->
            assertSame("Use the next page's prepared native tiles", next, activity.nativeViewer())
            assertSame("Keep the previous page alive for reverse navigation", first, activity.composedPageViewers()[0])
            assertTrue(activity.nativeViewer()!!.arePagesFullyRendered())
            assertTrue("Bound retained viewports to the nearby sheets",
                activity.composedPageViewers().size <= PdfPrefetchPages * 2 + 1)
        }
        swipe(host, forward = false)
        awaitPage(host, 0)
        host.onActivity { activity ->
            assertSame("Going back must not recreate the previous native viewer", first, activity.nativeViewer())
            assertTrue(activity.nativeViewer()!!.arePagesFullyRendered())
        }
        swipe(host, forward = true, distanceFraction = 0.15f, pauseMillis = 180)
        await("short drag has finished animating back to its original position") {
            var settled = false
            host.onActivity { activity ->
                val view = activity.nativeViewer()
                val origin = IntArray(2)
                view?.getLocationOnScreen(origin)
                settled = view === first && origin[0] == 0
            }
            settled
        }
        awaitPage(host, 0)
        host.onActivity { activity ->
            assertSame("A short slow drag returns without recreating the current sheet", first, activity.nativeViewer())
            val origin = IntArray(2).also(activity.nativeViewer()!!::getLocationOnScreen)
            assertEquals("The cancelled drag fully settles back", 0, origin[0])
            activity.nativeViewer()!!.zoom *= 2f
        }
        swipe(host, forward = true)
        assertStillOnPage(host, 0)
    }

    @Test(timeout = 120_000)
    fun swipesTurnOnePhysicalPageAndModeChangesKeepItsSavedLocation() = withFixture { host ->
        awaitPage(host, 0)
        host.onActivity { activity ->
            val view = requireNotNull(activity.nativeViewer())
            assertTrue("An ordinary single-page turn fits the whole tall sheet",
                view.zoom * TallPageHeight <= view.height + 2f)
        }
        swipe(host, forward = false)
        assertStillOnPage(host, 0)
        for (page in 1 until PageCount) {
            swipe(host, forward = true)
            awaitPage(host, page)
        }
        swipe(host, forward = true)
        assertStillOnPage(host, PageCount - 1)
        swipe(host, forward = false)
        awaitPage(host, 2)
        host.onActivity { it.setSinglePage(false) }
        awaitPage(host, 2, singlePage = false)
        host.onActivity { it.setSinglePage(true) }
        awaitPage(host, 2)
        host.onActivity { activity ->
            assertEquals("Persist source page 2 rather than the adapter's page 0", 2,
                activity.diskSavedLocation()?.pageIndex)
            assertFalse("The isolated production model has no rendering or write errors", activity.failed())
        }
    }

    @Test(timeout = 120_000)
    fun restoredLocationPageInputAndSearchKeepGlobalPdfPageNumbers() = withFixture(initialPage = 1) { host ->
        awaitPage(host, 1)
        host.onActivity { it.setChromeVisible(true) }
        clickText(resource(R.string.pdf_page_indicator, 2, PageCount))
        await("focused page-jump field") { findNode { it.isEditable && it.isFocused } != null }
        setEditableText("4")
        clickText(resource(R.string.pdf_panel_go))
        awaitPage(host, 3)
        // Exercise global text navigation from the same real PDF field used for page input.
        clickText(resource(R.string.pdf_search_mode_text))
        await("focused text-search field") {
            findNode { it.isEditable && it.isFocused && it.text?.toString().orEmpty().isEmpty() } != null
        }
        setEditableText("destination")
        awaitPage(host, 2)
        await("search result retains its source page") {
            var pages = emptyList<Int>()
            host.onActivity { pages = it.searchPages() }
            pages == listOf(2)
        }
        host.onActivity { activity ->
            assertEquals(2, activity.diskSavedLocation()?.pageIndex)
            assertFalse(activity.failed())
        }
    }

    @Test(timeout = 120_000)
    fun scrollAndPagesSettingsApplyWithoutReopeningTheBook() =
        withFixture(initialPage = 2, singlePage = false) { host ->
            awaitPage(host, 2, singlePage = false)
            var originalActivity: PdfSinglePageTestActivity? = null
            host.onActivity { originalActivity = it; it.setChromeVisible(true) }
            clickText(resource(R.string.pdf_panel_settings))
            clickText(resource(R.string.pdf_mode_pages))
            awaitPage(host, 2)
            host.onActivity {
                assertSame("Apply reading mode in the existing reader", originalActivity, it)
            }
            clickText(resource(R.string.pdf_panel_settings))
            await("settings drawer has closed before the swipe") {
                var closed = false
                host.onActivity { closed = !it.drawerOpen() }
                closed
            }
            swipe(host, forward = true)
            awaitPage(host, 3)
            host.onActivity { it.setChromeVisible(true) }
            clickText(resource(R.string.pdf_panel_settings))
            clickText(resource(R.string.pdf_mode_scroll))
            awaitPage(host, 3, singlePage = false)
            host.onActivity {
                assertSame("Both directions of the mode change keep the reader open", originalActivity, it)
                assertEquals(3, it.diskSavedLocation()?.pageIndex)
            }
        }

    @Test(timeout = 90_000)
    fun pickingAPdfThemeKeepsTheLocationAndAppTheme() = withFixture(initialPage = 2, singlePage = false) { host ->
        awaitPage(host, 2, singlePage = false)
        var before = PointF()
        var zoom = 0f
        var appTheme: AppTheme? = null
        var width = 0
        host.onActivity { activity ->
            val view = requireNotNull(activity.nativeViewer())
            val center = requireNotNull(view.viewToPdfPoint(view.width / 2f, view.height / 2f))
            before = PointF(center.x, center.y)
            zoom = view.zoom
            appTheme = activity.savedAppTheme()
            width = view.width
            activity.setChromeVisible(true)
        }
        var footer = Rect()
        await("reader chrome is visible before the tap") {
            val frame = fastScrollFrame()
            if (!frame.topBar || !frame.bottomBar || frame.pillBounds.isEmpty) return@await false
            SystemClock.sleep(150)
            footer = fastScrollFrame().pillBounds
            footer == frame.pillBounds
        }
        val settingsButton = PointF(width * 0.86f, footer.exactCenterY())
        val openTime = SystemClock.uptimeMillis()
        sendTouch(openTime, MotionEvent.ACTION_DOWN, settingsButton)
        sendTouch(openTime, MotionEvent.ACTION_UP, settingsButton)
        await("PDF settings drawer opens") {
            var opened = false
            host.onActivity { opened = it.drawerOpen() }
            opened
        }
        fun boundsOf(label: Int): Rect? = findNode { it.text?.toString().equals(resource(label), ignoreCase = true) }
            ?.let { Rect().also(it::getBoundsInScreen) }
        val sections = listOf(R.string.pdf_reader_theme, R.string.reader_brightness, R.string.pdf_reading_mode, R.string.pdf_toolbar_appearance)
        var sectionBounds = emptyList<Rect>()
        var themeButton: Rect? = null
        await("theme, brightness, reading mode and toolbar appearance are shown") {
            sectionBounds = sections.mapNotNull(::boundsOf)
            themeButton = boundsOf(R.string.theme_midnight)
            sectionBounds.size == sections.size && themeButton != null
        }
        assertTrue("PDF settings sections are in order", sectionBounds.zipWithNext().all { (a, b) -> a.top < b.top })
        val themeBounds = requireNotNull(themeButton)
        val midnight = PointF(themeBounds.exactCenterX(), themeBounds.exactCenterY())
        val themeTime = SystemClock.uptimeMillis()
        sendTouch(themeTime, MotionEvent.ACTION_DOWN, midnight)
        sendTouch(themeTime, MotionEvent.ACTION_UP, midnight)
        await("Midnight is applied on the same PDF page") {
            var themed = false
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                val center = view.viewToPdfPoint(view.width / 2f, view.height / 2f)
                val labelInk = android.util.TypedValue()
                val theme = view.context.theme
                val label = theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, labelInk, true)
                val actualInk = if (labelInk.resourceId != 0) view.context.getColor(labelInk.resourceId) else labelInk.data
                themed = activity.savedPdfTheme() == AppTheme.OLED && activity.drawerOpen() &&
                    label && actualInk == readerColors(AppTheme.OLED).onChrome.toArgb() && center?.pageNum == 2 &&
                    kotlin.math.abs(center.x - before.x) < 2f && kotlin.math.abs(center.y - before.y) < 2f &&
                    kotlin.math.abs(view.zoom - zoom) < 0.01f
            }
            themed
        }
        host.onActivity { assertEquals("The app theme stays in its own settings", appTheme, it.savedAppTheme()) }
    }

    @Test(timeout = 120_000)
    fun fastScrollShowsOnlyTheLivePagePillAndRestoresControlsAfterReleaseOrCancel() =
        withFixture(initialPage = 4, singlePage = false, pageCount = 12) { host ->
            awaitPage(host, 4, singlePage = false)
            host.onActivity {
                it.setChromeVisible(true)
                requireNotNull(it.nativeViewer()).fastScrollVisibility =
                    androidx.pdf.view.PdfView.FastScrollVisibility.ALWAYS_SHOW
            }
            for (endAction in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                var before: FastScrollFrame? = null
                await("both reader bars and the page pill are drawn") {
                    val first = fastScrollFrame()
                    if (!first.topBar || !first.bottomBar || first.pillBounds.isEmpty) return@await false
                    SystemClock.sleep(140)
                    before = fastScrollFrame().takeIf { it.pillBounds == first.pillBounds }
                    before != null // The opening slide has settled.
                }
                val initialFrame = requireNotNull(before)
                var from = PointF()
                var to = PointF()
                var startScroll = 0
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    val bounds = view.fastScrollVerticalThumbDrawable.bounds
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    from = PointF(origin[0] + bounds.exactCenterX(), origin[1] + view.paddingTop + bounds.exactCenterY())
                    to = PointF(from.x, origin[1] + view.height * if (endAction == MotionEvent.ACTION_UP) 0.7f else 0.4f)
                    startScroll = view.scrollY
                }
                val downTime = SystemClock.uptimeMillis()
                sendTouch(downTime, MotionEvent.ACTION_DOWN, from)
                var last = from
                try {
                    await("standalone page pill with both bars hidden") {
                        val frame = fastScrollFrame()
                        !frame.topBar && !frame.bottomBar && !frame.pillBounds.isEmpty
                    }
                    val heldFrame = fastScrollFrame()
                    val heldBounds = heldFrame.pillBounds
                    val originalBounds = initialFrame.pillBounds
                    assertEquals("Same pill left", originalBounds.left.toFloat(), heldBounds.left.toFloat(), 1f)
                    assertEquals("Same pill top", originalBounds.top.toFloat(), heldBounds.top.toFloat(), 1f)
                    assertEquals("Same pill right", originalBounds.right.toFloat(), heldBounds.right.toFloat(), 1f)
                    assertEquals("Same pill bottom", originalBounds.bottom.toFloat(), heldBounds.bottom.toFloat(), 1f)
                    last = to
                    sendTouch(downTime, MotionEvent.ACTION_MOVE, last)
                    await("standalone pill follows the new page before release") {
                        var moved = false
                        host.onActivity { moved = requireNotNull(it.nativeViewer()).scrollY != startScroll }
                        moved && fastScrollFrame().pillPixels != heldFrame.pillPixels
                    }
                } finally {
                    sendTouch(downTime, endAction, last)
                }
                await("both reader bars return after release or cancel") {
                    val frame = fastScrollFrame()
                    frame.topBar && frame.bottomBar && frame.pillBounds == initialFrame.pillBounds
                }
            }
        }

    private data class FastScrollFrame(val topBar: Boolean, val bottomBar: Boolean, val pillBounds: Rect, val pillPixels: Int)

    @Test(timeout = 120_000)
    fun fastScrollThumbCanBeGrabbedAtBothEndsBetweenThePanels() {
        for (appearance in PdfToolbarAppearance.entries) {
            withFixture(initialPage = 0, singlePage = false, pageCount = 12, toolbarAppearance = appearance) { host ->
                awaitPage(host, 0, singlePage = false)
                host.onActivity {
                    requireNotNull(it.nativeViewer()).fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_SHOW
                    it.setChromeVisible(true)
                }
                fun controlBounds(label: Int): Rect? {
                    val text = resource(label)
                    val node = findNode { it.text?.toString() == text || it.contentDescription?.toString() == text }
                    return generateSequence(node) { it.parent }
                        .firstOrNull { it.isClickable && it.isEnabled }
                        ?.let { Rect().also(it::getBoundsInScreen) }
                }

                fun thumbBounds(): Rect {
                    var bounds = Rect()
                    host.onActivity {
                        val view = requireNotNull(it.nativeViewer())
                        val origin = IntArray(2).also(view::getLocationOnScreen)
                        bounds = Rect(view.fastScrollVerticalThumbDrawable.bounds)
                        if (!bounds.isEmpty) bounds.offset(origin[0], origin[1] + view.paddingTop)
                    }
                    return bounds
                }

                fun bookmarkInkBounds(): Rect {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val density = instrumentation.targetContext.resources.displayMetrics.density
                    val ink = readerColors(AppTheme.WHITE).onChrome.toArgb()
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val bounds = Rect()
                        // The fixture has no page content in this strip beside the right handle.
                        val left = bitmap.width - (60 * density).toInt()
                        val right = bitmap.width - (32 * density).toInt()
                        for (y in bitmap.height / 20 until bitmap.height / 5) {
                            for (x in left until right) {
                                if (bitmap.getPixel(x, y) == ink) bounds.union(x, y, x + 1, y + 1)
                            }
                        }
                        return bounds
                    } finally {
                        bitmap.recycle()
                    }
                }

                fun awaitThumbBetweenPanels(): Rect {
                    var bounds = Rect()
                    await("$appearance thumb is fully between the panels") {
                        val header = bookmarkInkBounds()
                        val footer = controlBounds(R.string.pdf_panel_settings) ?: return@await false
                        bounds = thumbBounds()
                        if (header.isEmpty || bounds.isEmpty || bounds.top <= header.bottom ||
                            bounds.bottom >= footer.top
                        ) return@await false
                        SystemClock.sleep(120)
                        bounds == thumbBounds()
                    }
                    return bounds
                }

                fun dragToEnd(from: Rect, expectedScrollY: Int) {
                    val start = PointF(from.exactCenterX(), from.exactCenterY())
                    // Drag past the track end into the space vacated by that panel.
                    val panel = if (expectedScrollY > 0) requireNotNull(controlBounds(R.string.pdf_panel_settings))
                        else bookmarkInkBounds()
                    val end = PointF(start.x, panel.exactCenterY())
                    val downTime = SystemClock.uptimeMillis()
                    sendTouch(downTime, MotionEvent.ACTION_DOWN, start)
                    try {
                        await("$appearance reader recognizes a grab at the track endpoint") {
                            bookmarkInkBounds().isEmpty
                        }
                        assertEquals("The thumb stays under the finger when the panels hide", from, thumbBounds())
                        sendTouch(downTime, MotionEvent.ACTION_MOVE, end)
                        await("$appearance drag reaches the document endpoint") {
                            var atEnd = false
                            host.onActivity {
                                val view = requireNotNull(it.nativeViewer())
                                atEnd = view.scrollY == expectedScrollY
                            }
                            atEnd
                        }
                    } finally {
                        sendTouch(downTime, MotionEvent.ACTION_UP, end)
                    }
                    awaitThumbBetweenPanels()
                }

                host.onActivity { requireNotNull(it.nativeViewer()).scrollTo(0, 0) }
                val first = awaitThumbBetweenPanels()
                host.onActivity { requireNotNull(it.nativeViewer()).scrollTo(0, Int.MAX_VALUE) }
                val last = awaitThumbBetweenPanels()
                var endScrollY = 0
                host.onActivity { endScrollY = requireNotNull(it.nativeViewer()).scrollY }
                assertTrue("The track spans the space between the panels", last.top > first.bottom)
                assertTrue(endScrollY > 0)
                dragToEnd(last, expectedScrollY = 0)
                dragToEnd(awaitThumbBetweenPanels(), expectedScrollY = endScrollY)
            }
        }
    }

    /** Check the rendered chrome over the generated white PDF, independent of the accessibility service. */
    private fun fastScrollFrame(): FastScrollFrame {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val width = bitmap.width
            val height = bitmap.height
            val colors = readerColors(AppTheme.WHITE)
            fun containsInk(area: Rect, ink: Int): Boolean {
                for (y in area.top until area.bottom) for (x in area.left until area.right) {
                    if (bitmap.getPixel(x, y) == ink) return true
                }
                return false
            }
            val top = containsInk(Rect(0, height / 20, width / 5, height / 5), colors.onChrome.toArgb())
            val bottom = containsInk(Rect(0, height * 4 / 5, width / 5, height), colors.onChrome.toArgb())
            val pill = Rect()
            val toolbar = PdfToolbarColors(colors, PdfToolbarAppearance.GLASS)
            val fill = toolbar.counterTint.compositeOver(androidx.compose.ui.graphics.Color.White).toArgb()
            val greenOffset = Color.green(fill) - Color.red(fill)
            val blueOffset = Color.blue(fill) - Color.red(fill)
            for (y in height * 4 / 5 until height) for (x in width * 2 / 5 until width * 3 / 5) {
                // The PDF fixture is grayscale. Identify the pill by its tint across light
                // and dark areas, including when the toolbar surface disappears for scrubbing.
                val pixel = bitmap.getPixel(x, y)
                if (kotlin.math.abs(Color.green(pixel) - Color.red(pixel) - greenOffset) <= 2 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.red(pixel) - blueOffset) <= 2
                ) pill.union(x, y, x + 1, y + 1)
            }
            val center = Rect(pill).apply { inset(width() / 4, height() / 10) }
            val pixels = IntArray(center.width() * center.height())
            if (!center.isEmpty) {
                bitmap.getPixels(pixels, 0, center.width(), center.left, center.top, center.width(), center.height())
            }
            val counterInk = toolbar.counterInk.toArgb()
            return FastScrollFrame(top, bottom, pill, pixels.map { it == counterInk }.hashCode())
        } finally {
            bitmap.recycle()
        }
    }

    @Test(timeout = 90_000)
    fun draggingToTheEndMovesBeforeDimensionsAndShowsLoadingUntilTheBitmapArrives() =
        withFixture(initialPage = 0, singlePage = false, pageCount = 150, pageMarkers = true,
            pauseFastScrollDestination = true) { host ->
            awaitRenderedMarker(host, 0)
            var from = PointF()
            var to = PointF()
            host.onActivity {
                requireNotNull(it.nativeViewer()).fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_SHOW
                it.setChromeVisible(true)
            }
            await("the cover is available while later page dimensions are blocked") {
                var ready = false
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    val thumb = view.fastScrollVerticalThumbDrawable.bounds
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    from = PointF(origin[0] + thumb.exactCenterX(), origin[1] + view.paddingTop + thumb.exactCenterY())
                    to = PointF(from.x, origin[1] + view.height * 0.96f)
                    ready = activity.fastScrollLayoutWaiting() && !thumb.isEmpty && thumb.top > view.height * 0.10f
                }
                ready
            }
            val downTime = SystemClock.uptimeMillis()

            fun hasDestinationPlaceholder(): Boolean {
                var atEnd = false
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    atEnd = view.scrollY > 0 && view.firstVisiblePage + view.visiblePagesCount == 150
                }
                if (!atEnd) return false
                val colors = displayedMarkerColors()
                return markerColor(0) !in colors && markerColor(149) !in colors && colors.any {
                    Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) &&
                        Color.red(it) in 230..250
                }
            }

            try {
                sendTouch(downTime, MotionEvent.ACTION_DOWN, from)
                try {
                    val coverLabel = resource(R.string.pdf_scrub_page_count, 1, 150)
                    await("the thumb has captured the gesture") {
                        findNode { it.contentDescription?.toString() == coverLabel } != null
                    }
                    val middle = PointF(from.x, (from.y + to.y) / 2f)
                    sendTouch(downTime, MotionEvent.ACTION_MOVE, middle)
                    await("the viewport follows the first MOVE while dimensions are blocked") {
                        var advanced = false
                        host.onActivity { advanced = requireNotNull(it.nativeViewer()).firstVisiblePage in 40..120 }
                        advanced
                    }
                    sendTouch(downTime, MotionEvent.ACTION_MOVE, to)
                    val draggingPageLabel = resource(R.string.pdf_scrub_page_count, 150, 150)
                    await("the viewport moves to the placeholder and the pill shows page 150 during the drag") {
                        hasDestinationPlaceholder() &&
                            findNode { it.contentDescription?.toString() == draggingPageLabel } != null
                    }
                } finally {
                    sendTouch(downTime, MotionEvent.ACTION_UP, to)
                }
                await("the destination placeholder persists after release") { hasDestinationPlaceholder() }
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    val firstFrame = displayedMarkerColors()
                    await("the loading animation changes pixels while dimensions are still blocked") {
                        displayedMarkerColors() != firstFrame
                    }
                }
                host.onActivity { it.releaseFastScrollLayout() }
                await("the viewport settles at the real last page while its bitmap is blocked") {
                    var waitingAtEnd = false
                    host.onActivity {
                        waitingAtEnd = it.fastScrollBitmapWaiting() && it.sourcePage() == 149
                    }
                    waitingAtEnd && hasDestinationPlaceholder()
                }
                host.onActivity { it.releaseFastScrollBitmap() }
                awaitRenderedMarker(host, 149)
                val lastPageLabel = resource(R.string.pdf_page_indicator, 150, 150)
                await("the footer shows page 150 of 150") {
                    findNode { it.contentDescription?.toString() == lastPageLabel } != null
                }
            } finally {
                host.onActivity {
                    it.releaseFastScrollLayout()
                    it.releaseFastScrollBitmap()
                }
            }
        }

    @Test(timeout = 120_000)
    fun farPageJumpKeepsSearchOpenAndImmediatelyRendersTheNextPhysicalPages() =
        withFixture(initialPage = 89, pageCount = 96, pageMarkers = true) { host ->
            awaitPage(host, 89)
            awaitRenderedMarker(host, 89)
            host.onActivity { it.setChromeVisible(true) }
            clickText(resource(R.string.pdf_page_indicator, 90, 96))
            await("focused page-jump field") { findNode { it.isEditable && it.isFocused } != null }
            setEditableText("2")
            val jumpStarted = SystemClock.uptimeMillis()
            clickText(resource(R.string.pdf_panel_go))
            // Start the turn as soon as the destination is selected, before its save debounce
            // or neighbour rendering can hide a stale/closed native viewport after a far jump.
            awaitPage(host, 1, requireSaved = false)
            recordTiming("Pages far jump 90→2 selected", jumpStarted)
            host.onActivity { assertTrue("Go keeps the search controls available", it.searchVisible()) }
            val nextPageStarted = SystemClock.uptimeMillis()
            swipe(host, forward = true)
            awaitPage(host, 2)
            awaitRenderedMarker(host, 2)
            recordTiming("Pages next sheet 3 rendered after far jump", nextPageStarted)
            host.onActivity { assertTrue("Swipe does not require closing search", it.searchVisible()) }
            swipe(host, forward = true)
            awaitPage(host, 3)
            awaitRenderedMarker(host, 3)
            swipe(host, forward = false)
            awaitPage(host, 2)
            awaitRenderedMarker(host, 2)
            host.onActivity { assertTrue(it.searchVisible()); assertFalse(it.failed()) }
        }

    @Test(timeout = 120_000)
    fun restoredScrollOpeningNeverShowsTheCoverInSampledDisplayFrames() =
        withFixture(initialPage = 89, singlePage = false, pageCount = 96, pageMarkers = true) { host ->
            var sampledFrames = 0
            await("the restored scroll page is visible without displaying the cover") {
                val colors = displayedMarkerColors()
                sampledFrames++
                assertFalse("A displayed opening frame must never show the first-page cover; sampled=$sampledFrames",
                    markerColor(0) in colors)
                var restored = false
                host.onActivity { restored = it.sourcePage() == 89 }
                restored && markerColor(89) in colors
            }
            assertTrue("Inspect actual composited display frames during opening", sampledFrames > 0)
            recordTiming("Scroll page 90 visible, sampledFrames=$sampledFrames", fixtureLaunchedAtMillis)
            awaitPage(host, 89, singlePage = false)
            host.onActivity { assertEquals(89, it.diskSavedLocation()?.pageIndex); assertFalse(it.failed()) }
        }

    /** Explicit device check with an installed real PDF; no waiting for neighbours between turns. */
    @Test(timeout = 90_000)
    fun rapidTurnsNeverExposeAnUnrenderedPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val fileName = arguments.getString("pdfFixtureFile")
        org.junit.Assume.assumeTrue("Pass pdfFixtureFile for this real-document check", !fileName.isNullOrBlank())
        val source = File(instrumentation.targetContext.filesDir, "books/$fileName")
        assertTrue("The supplied PDF must already be installed", source.isFile)
        val initialPage = arguments.getString("pdfFixturePage")?.toInt() ?: 0
        val intent = Intent(instrumentation.targetContext, PdfSinglePageTestActivity::class.java)
            .setData(Uri.fromFile(source))
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_PAGE, initialPage)
            .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_ZOOM,
                arguments.getString("pdfFixtureZoom")?.toFloat() ?: 1f)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PdfSinglePageTestActivity>(intent).use { host ->
            awaitPage(host, initialPage)
            host.onActivity { it.setSinglePage(false) }
            awaitPage(host, initialPage, singlePage = false)
            host.onActivity { it.setSinglePage(true) }
            awaitPage(host, initialPage)
            var checkedMovingFrames = 0
            val checkVisibleSheets: () -> Unit = {
                host.onActivity { activity ->
                    val window = activity.window.decorView
                    val windowOrigin = IntArray(2).also(window::getLocationOnScreen)
                    val windowLeft = windowOrigin[0]
                    val windowRight = windowLeft + window.width
                    val visible = activity.composedPageViewers().filterValues { view ->
                        val origin = IntArray(2).also(view::getLocationOnScreen)
                        minOf(origin[0] + view.width, windowRight) - maxOf(origin[0], windowLeft) > 16
                    }
                    if (visible.size > 1) {
                        checkedMovingFrames++
                        visible.forEach { (page, view) ->
                            assertTrue("Incoming PDF page ${page + 1} must have its bitmap during the drag",
                                view.visiblePagesCount > 0 && view.arePagesFullyRendered())
                        }
                    }
                }
            }
            // No save debounce, neighbour wait or artificial pause before each swipe.
            repeat(6) { swipe(host, forward = true, prepareWindow = false, onMoved = checkVisibleSheets) }
            repeat(6) { swipe(host, forward = false, prepareWindow = false, onMoved = checkVisibleSheets) }
            var diagnostic = ""
            host.onActivity { activity ->
                diagnostic = activity.composedPageViewers().mapValues { (_, view) ->
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    "x=${position[0]}, size=${view.width}x${view.height}, rendered=${view.arePagesFullyRendered()}"
                }.toString()
            }
            assertTrue("Inspect partially exposed pages during real movement: $diagnostic", checkedMovingFrames > 0)
        }
    }

    private fun awaitPage(
        host: ActivityScenario<PdfSinglePageTestActivity>, page: Int, singlePage: Boolean = true,
        requireSaved: Boolean = true, requireRendered: Boolean = requireSaved,
    ) {
        await("source PDF page $page in mode singlePage=$singlePage, including debounced saved progress") {
            var reached = false
            host.onActivity { activity ->
                activity.loadFailure?.let { throw AssertionError("Production PDF fixture failed", it) }
                assertFalse("The production model must remain readable", activity.failed())
                val view = activity.nativeViewer()
                pageDiagnostics = "source=${activity.sourcePage()}, saved=${activity.savedLocation()}, " +
                    "progressObserved=${activity.progressObserved()}, " +
                    "native=${view?.hashCode()}, composed=" + activity.composedPageViewers().mapValues { (_, v) ->
                        "${v.hashCode()}: ${v.width}x${v.height}, zoom=${v.zoom}, rendered=${v.arePagesFullyRendered()}"
                    }
                reached = view != null && activity.singlePageEnabled() == singlePage && activity.sourcePage() == page &&
                    (!requireSaved || activity.progressObserved() && activity.savedLocation()?.pageIndex == page) &&
                    (!requireRendered || view.arePagesFullyRendered()) && view.width > 0 && view.height > 0 &&
                    view.viewToPdfPoint(view.width / 2f, view.height / 2f) != null
            }
            reached
        }
    }

    private fun awaitRenderedMarker(host: ActivityScenario<PdfSinglePageTestActivity>, page: Int) {
        await("source page ${page + 1} has native tiles and its actual coloured sheet on screen") {
            var rendered = false
            host.onActivity {
                rendered = it.sourcePage() == page && it.nativeViewer()?.arePagesFullyRendered() == true
            }
            rendered && markerColor(page) in displayedMarkerColors()
        }
    }

    private fun recordTiming(label: String, started: Long) {
        Log.i("PdfReaderRegression", "$label: ${SystemClock.uptimeMillis() - started} ms")
    }

    /** Inspect the composited display; a laid-out PdfView can still contain a white/old tile. */
    private fun displayedMarkerColors(): Set<Int> {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "The physical display must be available to inspect the rendered PDF"
        }
        return try {
            val counts = mutableMapOf<Int, Int>()
            for (y in bitmap.height * 3 / 10 until bitmap.height * 7 / 10 step 8) {
                for (x in bitmap.width * 3 / 10 until bitmap.width * 7 / 10 step 8) {
                    val pixel = bitmap.getPixel(x, y) or (0xff shl 24)
                    counts[pixel] = (counts[pixel] ?: 0) + 1
                }
            }
            counts.filterValues { it >= 24 }.keys
        } finally { bitmap.recycle() }
    }

    private fun assertStillOnPage(host: ActivityScenario<PdfSinglePageTestActivity>, page: Int) {
        SystemClock.sleep(250)
        host.onActivity {
            assertEquals("A swipe past the document boundary must stay on the same page", page, it.sourcePage())
            assertEquals(page, it.savedLocation()?.pageIndex)
        }
    }

    private fun swipe(host: ActivityScenario<PdfSinglePageTestActivity>, forward: Boolean,
        distanceFraction: Float = 0.6f, pauseMillis: Long = 0, prepareWindow: Boolean = true,
        onMoved: (() -> Unit)? = null, whilePressed: (() -> Unit)? = null,
    ) {
        if (prepareWindow) {
            host.onActivity { it.setChromeVisible(false) }
            // Let the overlay leave the touch path before measuring or injecting native input.
            SystemClock.sleep(350)
            await("the production PDF fixture owns the focused input window") {
                var focused = false
                host.onActivity { activity ->
                    focused = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        activity.window.decorView.hasWindowFocus()
                }
                focused
            }
        }
        var from = PointF()
        var to = PointF()
        host.onActivity { activity ->
            assertTrue("Stop injecting gestures if the PDF window loses focus", activity.window.decorView.hasWindowFocus())
            val view = requireNotNull(activity.nativeViewer())
            val origin = IntArray(2).also(view::getLocationOnScreen)
            // An interrupted animation shifts the native sheet outside the window. Anchor
            // subsequent touches to the window, keeping them away from system Back edges.
            val window = activity.window.decorView
            val windowOrigin = IntArray(2).also(window::getLocationOnScreen)
            val left = windowOrigin[0] + window.width * 0.20f
            val right = windowOrigin[0] + window.width * 0.80f
            val y = origin[1] + view.height * 0.50f
            from = PointF(if (forward) right else left, y)
            to = PointF(from.x + window.width * distanceFraction * if (forward) -1f else 1f, y)
        }
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_DOWN, from)
        for (step in 1..8) {
            SystemClock.sleep(16)
            host.onActivity { assertTrue("Stop injecting gestures if the PDF window loses focus",
                it.window.decorView.hasWindowFocus()) }
            val fraction = step / 8f
            sendTouch(downTime, MotionEvent.ACTION_MOVE,
                PointF(from.x + (to.x - from.x) * fraction, from.y))
            onMoved?.invoke()
        }
        whilePressed?.invoke()
        if (pauseMillis > 0) SystemClock.sleep(pauseMillis)
        sendTouch(downTime, MotionEvent.ACTION_UP, to)
    }

    private fun sendTouch(downTime: Long, action: Int, point: PointF) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("The system accepts the native PDF touch ${MotionEvent.actionToString(action)}",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    private fun setEditableText(value: String) {
        val field = requireNotNull(findNode { it.isEditable })
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        await("actual PDF field contains $value") { findNode { it.isEditable }?.text?.toString() == value }
    }

    private fun clickText(value: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.packageName?.toString() == packageName &&
                (node.text?.toString() == value || node.contentDescription?.toString() == value)) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.isEnabled == true && clickable.isVisibleToUser) return clickable
            }
            // Several windows or fading children can expose the same label. A nonclickable
            // match must not hide the real PDF toolbar button later in the hierarchy.
            for (index in 0 until node.childCount) findClickable(node.getChild(index))?.let { return it }
            return null
        }
        await("clickable PDF control $value") {
            appRoots().firstNotNullOfOrNull(::findClickable)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun appRoots(): List<AccessibilityNodeInfo> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName
        // The keyboard may own the active window while the PDF's Go/Close controls remain visible.
        return buildList<AccessibilityNodeInfo> {
            automation.windows.sortedByDescending { it.layer }.forEach { window -> window.root?.let { add(it) } }
            automation.rootInActiveWindow?.let { add(it) }
        }.filter { it.packageName?.toString() == packageName }.distinctBy { it.windowId }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.packageName?.toString() == packageName && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return appRoots().firstNotNullOfOrNull(::find)
    }

    private fun windowDiagnostics(): String = runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName
        fun labels(root: AccessibilityNodeInfo?): String {
            val entries = mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null || entries.size >= 40) return
                val text = listOfNotNull(node.text, node.contentDescription).joinToString(" / ").take(100)
                if (node.isVisibleToUser && (text.isNotEmpty() || node.isEditable)) {
                    entries += "$text[focused=${node.isFocused}, editable=${node.isEditable}, " +
                        "clickable=${node.isClickable}, enabled=${node.isEnabled}]"
                }
                for (index in 0 until node.childCount) collect(node.getChild(index))
            }
            collect(root)
            return entries.joinToString("; ")
        }
        val active = automation.rootInActiveWindow
        val windows = automation.windows
        val focusedPackages = windows.filter { it.isFocused }.map { it.root?.packageName }.joinToString()
        val snapshot = windows.take(8).joinToString(" | ") { window ->
            val root = window.root
            "window=${window.id}, type=${window.type}, active=${window.isActive}, focused=${window.isFocused}, " +
                "package=${root?.packageName}: " +
                if (window.isActive || root?.packageName?.toString() == packageName) labels(root) else ""
        }
        "activePackage=${active?.packageName}, focusedPackages=$focusedPackages; " +
            "activeLabels=${labels(active)}; windows=$snapshot"
    }.getOrElse { "Unable to capture window diagnostics: ${it.message}" }

    private fun resource(id: Int, vararg arguments: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *arguments)

    private fun withFixture(
        initialPage: Int = -1, singlePage: Boolean = true, pageCount: Int = PageCount,
        pageMarkers: Boolean = false, toolbarAppearance: PdfToolbarAppearance? = null,
        pauseFastScrollDestination: Boolean = false,
        test: (ActivityScenario<PdfSinglePageTestActivity>) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = createPdf(context.cacheDir, pageCount, pageMarkers)
        val automation = instrumentation.uiAutomation
        val originalServiceFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var host: ActivityScenario<PdfSinglePageTestActivity>? = null
        try {
            fixtureLaunchedAtMillis = SystemClock.uptimeMillis()
            host = ActivityScenario.launch(Intent(context, PdfSinglePageTestActivity::class.java)
                .setData(Uri.fromFile(source))
                .putExtra(PdfSinglePageTestActivity.EXTRA_INITIAL_PAGE, initialPage)
                .putExtra(PdfSinglePageTestActivity.EXTRA_SINGLE_PAGE, singlePage)
                .putExtra(PdfSinglePageTestActivity.EXTRA_TOOLBAR_APPEARANCE, toolbarAppearance?.name)
                .putExtra(PdfSinglePageTestActivity.EXTRA_PAUSE_FAST_SCROLL_DESTINATION, pauseFastScrollDestination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val scenario = host
            currentFixture = scenario
            await("production PDF window receives input focus") {
                var focused = false
                scenario.onActivity {
                    it.loadFailure?.let { error -> throw AssertionError("PDF fixture creation failed", error) }
                    focused = it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        it.window.decorView.hasWindowFocus()
                }
                focused
            }
            test(scenario)
        } finally {
            try { host?.close() }
            finally {
                currentFixture = null
                source.delete()
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
            }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        val windows = windowDiagnostics()
        val artifacts = captureFailure(label, windows)
        fail("Timed out waiting for $label. $pageDiagnostics. $windows. $artifacts")
    }

    /** Keep the failing display and native bounds before ActivityScenario closes its window. */
    private fun captureFailure(label: String, windows: String): String = runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.getExternalFilesDir(null) ?: context.cacheDir
        val screenshot = File(directory, "pdf-regression-failure.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                screenshot.outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        val tree = StringBuilder()
        currentFixture?.onActivity { activity ->
            fun visit(view: View, depth: Int) {
                if (depth > 16 || tree.length > 24_000) return
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val visible = Rect()
                val hasVisibleBounds = view.getGlobalVisibleRect(visible)
                tree.append("  ".repeat(depth)).append(view.javaClass.name)
                    .append(" bounds=").append(view.left).append(',').append(view.top)
                    .append('-').append(view.right).append(',').append(view.bottom)
                    .append(" screen=").append(origin.contentToString())
                    .append(" visible=").append(view.visibility)
                    .append(" shown=").append(view.isShown)
                    .append(" alpha=").append(view.alpha)
                    .append(" globalVisible=").append(if (hasVisibleBounds) visible else "none")
                    .append(" layoutRequested=").append(view.isLayoutRequested)
                    .appendLine()
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
            visit(activity.window.decorView, 0)
        }
        val diagnostics = File(directory, "pdf-regression-failure.txt")
        diagnostics.bufferedWriter().use {
            it.appendLine(label).appendLine(pageDiagnostics).appendLine(windows).appendLine(tree)
        }
        "Failure screenshot=${screenshot.absolutePath}; bounds=${diagnostics.absolutePath}".also {
            Log.e("PdfReaderRegression", it)
        }
    }.getOrElse { "Unable to preserve failure artifacts: ${it.message}" }

    private fun createPdf(directory: File, pageCount: Int, pageMarkers: Boolean): File {
        val file = File.createTempFile("pdf-single-production-", ".pdf", directory)
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val height = if (index == 0) TallPageHeight else 480
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, height, index + 1).create())
                try {
                    page.canvas.drawColor(if (pageMarkers) markerColor(index) else Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f }
                    page.canvas.drawText(if (index == 2) "destination" else "PDF page ${index + 1}", 24f, 75f, paint)
                    page.canvas.drawRect(10f, height - 35f, 310f, height - 10f, paint)
                } finally { document.finishPage(page) }
            }
            file.outputStream().use(document::writeTo)
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally { document.close() }
    }

    private companion object {
        const val PageCount = 4
        const val TallPageHeight = 1_200

        // All 96 fixture sheets have a distinct flat colour; index zero is a conspicuous cover.
        // The selected test pages have well-separated channels so old/white tiles cannot pass.
        fun markerColor(index: Int): Int = if (index == 0) Color.MAGENTA else Color.rgb(
            48 + index * 37 % 160,
            64 + index * 59 % 160,
            80 + index * 83 % 160,
        )
    }
}
