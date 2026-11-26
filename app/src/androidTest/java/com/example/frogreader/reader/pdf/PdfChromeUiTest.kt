@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.R
import com.example.frogreader.debug.PdfChromeTestActivity
import com.example.frogreader.debug.PdfChromeTestActivity.ChromeMotionFrame
import com.example.frogreader.ui.reader.readerColors
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering and touch regression coverage, using disposable PDFs and no library writes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
@SuppressLint("RestrictedApi") // Keep the real native scroller visible while inspecting and dragging it.
class PdfChromeUiTest {
    @Test(timeout = 60_000)
    fun appearanceAppliesToPdfHeaderToolbarAndSettings() =
        withFixture(AppTheme.OLED, rightPageColor = Color.rgb(28, 80, 155)) { host ->
            host.onActivity { it.setChromeVisible(true) }
            val baseline = awaitChromeSettled(host, visible = true, systemBarsVisible = true)
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            var safeTop = 0
            host.onActivity {
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(it.window.decorView))
                safeTop = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                ).top
            }
            fun viewport(): List<Number> {
                var result = emptyList<Number>()
                host.onActivity {
                    val view = requireNotNull(it.nativeViewer())
                    result = listOf(view.width, view.height, view.zoom, view.scrollX, view.scrollY)
                }
                return result
            }

            fun checkSurface(bitmap: Bitmap, y: Float, mode: PdfToolbarAppearance, surface: String) {
                val center = bitmap.width / 2
                fun difference(dp: Int): Int {
                    val offset = (dp * density).toInt()
                    return Color.red(bitmap.getPixel(center - offset, y.toInt())) -
                        Color.red(bitmap.getPixel(center + offset, y.toInt()))
                }
                val spread = difference(36)
                if (mode == PdfToolbarAppearance.SOLID) {
                    assertTrue("$surface is opaque: $spread", abs(spread) <= 2)
                } else {
                    assertTrue("$surface in $mode shows the PDF colors: $spread", spread > 3)
                    val sharpness = difference(3).toFloat() / spread
                    if (mode == PdfToolbarAppearance.CLEAR) {
                        assertTrue("$surface in Clear keeps a sharp edge: $sharpness", sharpness > 0.85f)
                    } else if (Build.VERSION.SDK_INT >= 31) {
                        assertTrue("$surface in Glass softens the PDF edge: $sharpness", sharpness < 0.7f)
                    }
                }
            }

            val originalViewport = viewport()
            for (mode in PdfToolbarAppearance.entries) {
                host.onActivity { it.setToolbarAppearance(mode) }
                val frame = awaitStableBar(host, searchVisible = false)
                assertSameBarHeight(baseline, frame)
                assertEquals(requireNotNull(baseline.bottomY), requireNotNull(frame.bottomY), 2f)
                assertEquals(originalViewport, viewport())
                val bitmap = requireNotNull(automation.takeScreenshot())
                try {
                    if (mode == PdfToolbarAppearance.SOLID) {
                        val x = bitmap.width * 3 / 4
                        val solid = readerColors(AppTheme.OLED).chrome.toArgb()
                        val firstSolidRow = (safeTop..safeTop + (20 * density).roundToInt())
                            .firstOrNull { bitmap.getPixel(x, it) == solid }
                        assertNotNull("The header starts directly below the safe area", firstSolidRow)
                        val expectedRow = safeTop + (4 * density).roundToInt()
                        assertEquals(expectedRow.toFloat(), firstSolidRow!!.toFloat(), 3f)
                    }
                    checkSurface(bitmap, requireNotNull(frame.topY) + frame.topHeight - 16 * density, mode, "Header")
                    val y = requireNotNull(frame.bottomY) + 12 * density
                    checkSurface(bitmap, y, mode, "Toolbar")
                    if (mode == PdfToolbarAppearance.CLEAR && Build.VERSION.SDK_INT >= 33) {
                        val blue = bitmap.getPixel(bitmap.width / 2 + (36 * density).toInt(), y.toInt())
                        assertTrue("Adaptive Clear preserves color in areas that already have enough contrast",
                            Color.blue(blue) - Color.green(blue) > 50)
                    }
                    if (mode == PdfToolbarAppearance.GLASS && Build.VERSION.SDK_INT >= 33) {
                        val white = bitmap.getPixel(bitmap.width / 2 - (36 * density).toInt(), y.toInt())
                        val blue = bitmap.getPixel(bitmap.width / 2 + (36 * density).toInt(), y.toInt())
                        val ink = readerColors(AppTheme.OLED).onChrome.toArgb()
                        val contrast = (Color.luminance(ink) + 0.05f) / (Color.luminance(white) + 0.05f)
                        assertTrue("Glass keeps light labels readable over white paper: $contrast", contrast >= 4.5f)
                        assertTrue("Glass retains color where the page is already dark enough",
                            Color.blue(blue) - Color.green(blue) > 50)
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            clickAccessibilityResource(R.string.pdf_panel_settings)
            clickAccessibilityResource(R.string.reader_panel_expand)
            await("toolbar appearance options") {
                hasAccessibilityText(resourceText(R.string.pdf_toolbar_appearance).uppercase())
            }
            for (mode in PdfToolbarAppearance.entries) {
                host.onActivity { it.setToolbarAppearance(mode) }
                val frame = awaitStableBar(host, searchVisible = false)
                val bitmap = requireNotNull(automation.takeScreenshot())
                try {
                    val y = requireNotNull(frame.bottomY) + 12 * density
                    checkSurface(bitmap, y, mode, "Settings")
                    if (mode == PdfToolbarAppearance.CLEAR) {
                        val left = bitmap.getPixel(bitmap.width / 2 - (36 * density).toInt(), y.toInt())
                        val right = bitmap.getPixel(bitmap.width / 2 + (36 * density).toInt(), y.toInt())
                        assertTrue("Clear settings subdue the page beneath the controls",
                            Color.red(left) - Color.red(right) < 35)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            assertEquals(originalViewport, viewport())
        }

    @Test(timeout = 60_000)
    @SdkSuppress(minSdkVersion = 31)
    fun glassSuppressesMovingTextLinesWhileThePdfKeepsScrolling() =
        withFixture(AppTheme.WHITE, showTextLines = true) { host ->
            host.onActivity {
                it.setToolbarAppearance(PdfToolbarAppearance.GLASS)
                it.setChromeVisible(true)
            }
            val frame = awaitChromeSettled(host, visible = true, systemBarsVisible = true)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val display = instrumentation.targetContext.resources.displayMetrics
            val density = display.density
            val headerCenter = (requireNotNull(frame.topY) + frame.topHeight - 40 * density).toInt()
            val radius = (12 * density).toInt()
            val point = PointF(display.widthPixels / 2f, display.heightPixels * 0.60f)
            var startScroll = 0
            host.onActivity { startScroll = requireNotNull(it.nativeViewer()).scrollY }
            val downTime = SystemClock.uptimeMillis()
            sendTouch(downTime, MotionEvent.ACTION_DOWN, point)
            try {
                for (step in 1..8) {
                    SystemClock.sleep(45)
                    point.y -= 5 * density
                    sendTouch(downTime, MotionEvent.ACTION_MOVE, point)
                    if (step < 3) continue
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val x = bitmap.width / 4
                        fun lineContrast(center: Int): Int {
                            val levels = (center - radius..center + radius).map { Color.red(bitmap.getPixel(x, it)) }
                            return levels.max() - levels.min()
                        }
                        val paper = lineContrast(bitmap.height / 2)
                        val glass = lineContrast(headerCenter)
                        assertTrue("The moving PDF still contains strong text lines: $paper", paper > 120)
                        assertTrue("Glass suppresses moving lines in the header: $glass", glass <= 22)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                sendTouch(downTime, MotionEvent.ACTION_UP, point)
            }
            host.onActivity {
                assertTrue("The slow drag scrolls the PDF under the glass",
                    requireNotNull(it.nativeViewer()).scrollY - startScroll > 15 * density)
            }
        }

    @Test(timeout = 90_000)
    fun unifiedSearchKeepsBarHeightForBlankResultsInvalidPageAndClose() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        val baseline = awaitStableBar(host, searchVisible = false)
        host.onActivity { it.startMotionCapture() }
        clickAccessibilityResource(R.string.pdf_panel_search)
        awaitFocusedSearchField(R.string.pdf_search_placeholder)
        val blank = awaitStableBar(host, searchVisible = true, imeVisible = true)
        assertWideField(requireNotNull(findEditableField()), blank)

        setEditableText("none")
        await("empty search results") { hasAccessibilityText(resourceText(R.string.pdf_search_empty)) }
        assertSameBarHeight(blank, awaitStableBar(host, searchVisible = true, imeVisible = true))
        setEditableText("frog")
        await("three synthetic results") { hasAccessibilityText(resourceText(R.string.pdf_search_count, 1, 3)) }
        clickAccessibilityResource(R.string.pdf_next_result)
        await("next synthetic result") { hasAccessibilityText(resourceText(R.string.pdf_search_count, 2, 3)) }
        assertSameBarHeight(blank, awaitStableBar(host, searchVisible = true, imeVisible = true))

        clickAccessibilityResource(R.string.pdf_search_mode_page)
        awaitFocusedSearchField(R.string.pdf_search_mode_page)
        setEditableText("999")
        await("page input range hint") { hasAccessibilityText(resourceText(R.string.pdf_panel_page_range, 12)) }
        assertSameBarHeight(blank, awaitStableBar(host, searchVisible = true, imeVisible = true))
        assertFalse("An invalid page must disable Go", enabledInHierarchy(requireNotNull(findAccessibilityNode {
            it.contentDescription?.toString() == resourceText(R.string.pdf_panel_go)
        })))

        setEditableText("7")
        clickAccessibilityResource(R.string.pdf_panel_go)
        await("visible PDF page jump from the bottom field") {
            var reached = false
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                reached = view.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum == 6 && activity.displayedPage == 6
            }
            reached
        }
        clickAccessibilityResource(R.string.pdf_search_close)
        await("the same toolbar returns after closing search") { hasPageDescription(6) && findEditableField() == null }
        val closed = awaitStableBar(host, searchVisible = false, imeVisible = false)
        assertSameBarHeight(baseline, closed)
        assertEquals("Closing search returns the same bar to its original position", baseline.bottomY!!, closed.bottomY!!, 2f)
        host.onActivity { it.stopMotionCapture() }
        saveScreenshot("pdf-unified-search-closed.png")
    }

    @Test(timeout = 90_000)
    fun searchModeSwitchRetainsOneFocusedFieldAndNeverCollapsesIme() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        clickAccessibilityResource(R.string.pdf_panel_search)
        awaitFocusedSearchField(R.string.pdf_search_placeholder)
        awaitStableBar(host, searchVisible = true, imeVisible = true)
        val longQuery = "A long PDF query with spaces, punctuation, and Ukrainian: читаємо книжку — 1234567890"
        setEditableText(longQuery)
        await("full long query in the real editable field") { findEditableField()?.text?.toString() == longQuery }
        val originalField = requireNotNull(findEditableField())
        var captured = emptyList<ChromeMotionFrame>()
        host.onActivity { it.startMotionCapture() }
        try {
            repeat(3) {
                clickAccessibilityResource(R.string.pdf_search_mode_page)
                val pageField = awaitFocusedSearchField(R.string.pdf_search_mode_page)
                assertEquals("Changing keyboard type keeps the same accessibility text-field node", originalField, pageField)
                assertImeAndFocusRemainVisible(host, 220)
                clickAccessibilityResource(R.string.pdf_search_mode_text)
                val textField = awaitFocusedSearchField(R.string.pdf_search_placeholder)
                assertEquals("Returning to Text keeps the same focused field", originalField, textField)
                assertEquals("The complete long query survives mode changes", longQuery, textField.text?.toString())
                assertImeAndFocusRemainVisible(host, 220)
            }
        } finally {
            host.onActivity { captured = it.stopMotionCapture() }
        }
        assertTrue("Mode switching must render diagnostic IME frames", captured.size >= 6)
        for (frame in captured) {
            assertTrue("IME must never collapse while switching Text/Page: $frame", frame.imeBottom > frame.navigationBottom)
        }
        assertWideField(requireNotNull(findEditableField()), awaitStableBar(host, searchVisible = true, imeVisible = true))
        saveScreenshot("pdf-unified-search-long-query.png")
    }

    @Test(timeout = 60_000)
    fun pageIndicatorOpensUnifiedSearchInPageMode() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        val baseline = awaitStableBar(host, searchVisible = false)
        clickAccessibilityText(resourceText(R.string.pdf_page_indicator, 1, 12))
        val pageField = awaitFocusedSearchField(R.string.pdf_search_mode_page)
        assertEquals("Page mode starts at the current PDF page", "1", pageField.text?.toString())
        val page = awaitStableBar(host, searchVisible = true, imeVisible = true)
        assertTrue("The two-row page search is taller than the compact toolbar",
            page.barContentHeight > baseline.barContentHeight)
        assertWideField(pageField, page)
        clickAccessibilityResource(R.string.pdf_search_close)
        await("page indicator returns") { hasPageDescription(0) }
    }

    @Test(timeout = 60_000)
    fun rapidSearchReentryRestoresFocusBeforeOutgoingContentDisposes() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        clickAccessibilityResource(R.string.pdf_panel_search)
        val originalField = awaitFocusedSearchField(R.string.pdf_search_placeholder)
        val originalBar = awaitStableBar(host, searchVisible = true, imeVisible = true)
        setEditableText("Close and reopen the outgoing editor")
        var captured = emptyList<ChromeMotionFrame>()
        host.onActivity { it.startMotionCapture(); it.rapidlyReopenSearch() }
        try {
            await("a new search entry within the outgoing transition") {
                var reopened = false
                host.onActivity { reopened = it.lastRapidSearchReopenTime > 0L }
                reopened
            }
            val reopenedField = awaitFocusedSearchField(R.string.pdf_search_placeholder)
            val reopenedBar = awaitStableBar(host, searchVisible = true, imeVisible = true)
            assertEquals("The regression requires reusing the outgoing editor, rather than creating another field",
                originalField, reopenedField)
            assertEquals("A new entry clears the prior search query", "", reopenedField.text?.toString())
            assertSameBarHeight(originalBar, reopenedBar)
            assertImeAndFocusRemainVisible(host, 220)
            host.onActivity {
                val elapsed = it.lastRapidSearchReopenTime - it.lastRapidSearchCloseTime
                assertTrue("Close and reopen must be separate frames less than 100 ms apart; was $elapsed ms", elapsed in 16L..99L)
            }
        } finally {
            host.onActivity { captured = it.stopMotionCapture() }
        }
        assertTrue("The outgoing inactive state must actually reach a rendered frame before reentry",
            captured.any { !it.searchVisible })
        assertTrue("The reopened search must be rendered with the keyboard visible",
            captured.any { it.searchVisible && it.imeBottom > it.navigationBottom })
    }

    @Test(timeout = 60_000)
    fun composingInputSurvivesTextPageSwitchWithoutImeDismissal() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        clickAccessibilityResource(R.string.pdf_panel_search)
        val originalField = awaitFocusedSearchField(R.string.pdf_search_placeholder)
        awaitStableBar(host, searchVisible = true, imeVisible = true)
        val composingWord = "composingword"
        var captured = emptyList<ChromeMotionFrame>()
        host.onActivity { it.startMotionCapture() }
        try {
            host.onActivity {
                assertTrue("The focused Compose editor must accept native IME composing text",
                    it.setComposingTextForTest(composingWord))
            }
            await("uncommitted word in the actual editable field") {
                findEditableField()?.text?.toString() == composingWord
            }
            repeat(2) {
                clickAccessibilityResource(R.string.pdf_search_mode_page)
                val pageField = awaitFocusedSearchField(R.string.pdf_search_mode_page)
                assertEquals("Composing input does not replace the persistent editor", originalField, pageField)
                assertImeAndFocusRemainVisible(host, 220)
                clickAccessibilityResource(R.string.pdf_search_mode_text)
                val textField = awaitFocusedSearchField(R.string.pdf_search_placeholder)
                assertEquals("The complete composing word survives the page draft", composingWord, textField.text?.toString())
                assertImeAndFocusRemainVisible(host, 220)
            }
        } finally {
            host.onActivity { captured = it.stopMotionCapture() }
        }
        assertTrue("Native composing input and mode changes must render diagnostic frames", captured.size >= 6)
        for (frame in captured) {
            assertTrue("IME must remain visible when a composing span is finished by a mode switch: $frame",
                frame.imeBottom > frame.navigationBottom)
        }
    }

    private fun awaitStableBar(
        host: ActivityScenario<PdfChromeTestActivity>,
        searchVisible: Boolean,
        imeVisible: Boolean? = null,
    ): ChromeMotionFrame {
        var previous: ChromeMotionFrame? = null
        var stableSince = 0L
        return awaitMotionFrame(host, "stable bottom surface, search=$searchVisible, IME=$imeVisible") { frame ->
            val imeMatches = imeVisible == null || (frame.imeBottom > frame.navigationBottom) == imeVisible
            val old = previous
            val matches = frame.searchVisible == searchVisible && frame.bottomHeight > 0f && imeMatches &&
                abs(frame.progress - 1f) <= 0.001f
            if (!matches || old == null || abs(old.bottomHeight - frame.bottomHeight) > 1f ||
                old.imeBottom != frame.imeBottom || abs((old.bottomY ?: 0f) - (frame.bottomY ?: 0f)) > 1f) {
                stableSince = SystemClock.uptimeMillis()
            }
            previous = frame
            matches && SystemClock.uptimeMillis() - stableSince >= 160
        }
    }

    private fun assertSameBarHeight(expected: ChromeMotionFrame, actual: ChromeMotionFrame) {
        val tolerance = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density * 2f
        assertEquals("The shared bar keeps its content height after subtracting its single IME/nav inset",
            expected.barContentHeight, actual.barContentHeight, tolerance)
    }

    private fun assertWideField(field: AccessibilityNodeInfo, frame: ChromeMotionFrame) {
        val bounds = Rect().also(field::getBoundsInScreen)
        assertTrue("The editable field must occupy more than 60% of the bottom bar width; field=$bounds, bar=${frame.bottomWidth}",
            bounds.width() > frame.bottomWidth * 0.60f)
    }

    private fun assertImeAndFocusRemainVisible(host: ActivityScenario<PdfChromeTestActivity>, durationMillis: Long) {
        val deadline = SystemClock.uptimeMillis() + durationMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var frame: ChromeMotionFrame? = null
            host.onActivity { frame = it.latestChromeMotionFrame }
            assertTrue("The IME remains visible after keyboard-type changes", requireNotNull(frame).let { it.imeBottom > it.navigationBottom })
            assertTrue("The persistent text field retains focus during mode changes", requireNotNull(findEditableField()).isFocused)
            SystemClock.sleep(16)
        }
    }

    private fun awaitFocusedSearchField(descriptionResource: Int): AccessibilityNodeInfo {
        var field: AccessibilityNodeInfo? = null
        try {
            await("focused search field ${resourceText(descriptionResource)}") {
                field = findEditableField()
                field?.isFocused == true && field?.hintText?.toString() == resourceText(descriptionResource)
            }
        } catch (failure: AssertionError) {
            val current = findAccessibilityNode {
                it.isEditable || it.className?.toString() == "android.widget.EditText"
            }
            fun describe(node: AccessibilityNodeInfo?): String {
                if (node == null) return "none"
                val bounds = Rect().also(node::getBoundsInScreen)
                return "class=${node.className}, text=${node.text}, hint=${node.hintText}, " +
                    "focused=${node.isFocused}, accessibilityFocused=${node.isAccessibilityFocused}, " +
                    "editable=${node.isEditable}, enabled=${node.isEnabled}, visible=${node.isVisibleToUser}, " +
                    "bounds=$bounds, actions=${node.actionList.map { it.id }}"
            }
            throw AssertionError("Search editor did not become focused. Expected hint=${resourceText(descriptionResource)}; " +
                "current input=[${describe(current)}]; last editable=[${describe(field)}]", failure)
        }
        return requireNotNull(field)
    }

    private fun setEditableText(value: String) {
        val field = requireNotNull(findEditableField())
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Set text in the actual Compose input field", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        await("editable field contains the entered value") { findEditableField()?.text?.toString() == value }
    }

    private fun resourceText(resource: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *args)

    private fun findEditableField(): AccessibilityNodeInfo? = findAccessibilityNode { it.isEditable }

    private fun hasAccessibilityText(value: String): Boolean = findAccessibilityNode {
        it.text?.toString() == value || it.contentDescription?.toString() == value
    } != null

    private fun clickAccessibilityResource(resource: Int) = clickAccessibilityText(resourceText(resource))

    private fun clickAccessibilityText(value: String) {
        var clicked = false
        await("clickable accessibility control $value") {
            val node = findAccessibilityNode { it.text?.toString() == value || it.contentDescription?.toString() == value }
            clicked = node?.let(::clickableAncestor)?.takeIf { it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
        assertTrue("The real control handles its accessibility click", clicked)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = current.parent ?: return current
        return current
    }

    private fun enabledInHierarchy(node: AccessibilityNodeInfo): Boolean {
        var current = node
        while (current.isEnabled) current = current.parent ?: return true
        return false
    }

    private fun findAccessibilityNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    @Test(timeout = 60_000)
    @SdkSuppress(minSdkVersion = 30)
    fun panelsSlideTogetherWithNativeSystemBars() = withFixture(AppTheme.WHITE) { host ->
        awaitChromeSettled(host, visible = false, systemBarsVisible = false)
        val entering = captureChromeTransition(host, visible = true)
        assertSharedSlide(entering, entering = true)
        assertFollowsNativeInsets(entering, "status") { it.statusBarFraction }
        assertFollowsNativeInsets(entering, "navigation") { it.navigationBarFraction }

        val exiting = captureChromeTransition(host, visible = false)
        assertSharedSlide(exiting, entering = false)
        assertFollowsNativeInsets(exiting, "status") { it.statusBarFraction }
        assertFollowsNativeInsets(exiting, "navigation") { it.navigationBarFraction }
    }

    @Test(timeout = 60_000)
    fun panelsStillSlideWhenSystemBarsAreAlreadyVisible() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.keepSystemBarsVisible(true) }
        awaitChromeSettled(host, visible = false, systemBarsVisible = true)
        val entering = captureChromeTransition(host, visible = true, systemBarsVisible = true)
        val exiting = captureChromeTransition(host, visible = false, systemBarsVisible = true)
        assertSharedSlide(entering, entering = true)
        assertSharedSlide(exiting, entering = false)
        for (frame in entering + exiting) {
            frame.statusBarFraction?.let { assertTrue("Fallback keeps the status bar visible", it >= 0.98f) }
            frame.navigationBarFraction?.let { assertTrue("Fallback keeps the navigation bar visible", it >= 0.98f) }
        }
    }

    @Test(timeout = 60_000)
    fun interruptedSlidesReverseContinuouslyWithoutPanelDesynchronization() = withFixture(AppTheme.WHITE) { host ->
        awaitChromeSettled(host, visible = false, systemBarsVisible = false)
        host.onActivity { it.startMotionCapture(); it.setChromeVisible(true) }
        val beforeHide = awaitMotionFrame(host, "partially revealed panels") {
            it.requestedVisible && (it.measuredTopProgress ?: -1f) in 0.25f..0.65f
        }
        host.onActivity { it.setChromeVisible(false) }
        val beforeReveal = awaitMotionFrame(host, "panels reversing before they finish hiding") {
            !it.requestedVisible && (it.measuredTopProgress ?: -1f) in 0.05f..(beforeHide.measuredTopProgress!! - 0.025f)
        }
        host.onActivity { it.setChromeVisible(true) }
        awaitChromeSettled(host, visible = true, systemBarsVisible = true)
        var frames = emptyList<ChromeMotionFrame>()
        host.onActivity { frames = it.stopMotionCapture() }
        val geometry = frames.filter { it.measuredTopProgress != null && it.measuredBottomProgress != null }
        assertRenderedLayersAgree(geometry)
        assertTrue("A hide request must reverse actual layer motion", beforeReveal.topY!! < beforeHide.topY!!)
        assertTrue("The bottom panel reverses in the opposite physical direction", beforeReveal.bottomY!! > beforeHide.bottomY!!)
        assertTrue("The interrupted hide must remain on screen", beforeReveal.measuredTopProgress!! > 0f)
        for ((previous, current) in geometry.zipWithNext()) {
            if (current.timeMillis - previous.timeMillis <= 45) {
                assertTrue("A reversal must not teleport a panel between rendered frames: $previous -> $current",
                    abs(current.measuredTopProgress!! - previous.measuredTopProgress!!) <= 0.30f)
            }
        }
        assertTrue("Both target reversals must be represented in rendered frames",
            frames.zipWithNext().count { (a, b) -> a.requestedVisible != b.requestedVisible } >= 2)
    }

    private fun captureChromeTransition(
        host: ActivityScenario<PdfChromeTestActivity>,
        visible: Boolean,
        systemBarsVisible: Boolean = visible,
    ): List<ChromeMotionFrame> {
        host.onActivity { it.startMotionCapture(); it.setChromeVisible(visible) }
        awaitChromeSettled(host, visible, systemBarsVisible)
        var frames = emptyList<ChromeMotionFrame>()
        host.onActivity { frames = it.stopMotionCapture() }
        return frames
    }

    private fun awaitChromeSettled(
        host: ActivityScenario<PdfChromeTestActivity>,
        visible: Boolean,
        systemBarsVisible: Boolean,
    ): ChromeMotionFrame = awaitMotionFrame(host, "settled chrome=$visible, system bars=$systemBarsVisible") { frame ->
        val target = if (visible) 1f else 0f
        val geometrySettled = if (visible) {
            abs((frame.measuredTopProgress ?: -1f) - 1f) <= 0.02f &&
                abs((frame.measuredBottomProgress ?: -1f) - 1f) <= 0.02f
        } else {
            (frame.measuredTopProgress == null || frame.measuredTopProgress!! <= 0.02f) &&
                (frame.measuredBottomProgress == null || frame.measuredBottomProgress!! <= 0.02f)
        }
        fun settledInset(value: Float?): Boolean = value == null ||
            abs(value - if (systemBarsVisible) 1f else 0f) <= 0.02f
        frame.requestedVisible == visible && abs(frame.progress - target) <= 0.001f && geometrySettled &&
            settledInset(frame.statusBarFraction) && settledInset(frame.navigationBarFraction)
    }

    private fun awaitMotionFrame(
        host: ActivityScenario<PdfChromeTestActivity>,
        label: String,
        condition: (ChromeMotionFrame) -> Boolean,
    ): ChromeMotionFrame {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var latest: ChromeMotionFrame? = null
        while (SystemClock.uptimeMillis() < deadline) {
            host.onActivity { latest = it.latestChromeMotionFrame }
            latest?.takeIf(condition)?.let { return it }
            SystemClock.sleep(8)
        }
        throw AssertionError("Timed out waiting for $label; last rendered frame=$latest")
    }

    private fun assertSharedSlide(frames: List<ChromeMotionFrame>, entering: Boolean) {
        val geometry = frames.filter { it.measuredTopProgress != null && it.measuredBottomProgress != null }
        assertRenderedLayersAgree(geometry)
        val intermediate = geometry.filter { it.measuredTopProgress!! in 0.02f..0.98f }
        assertTrue("A slide must render at least three intermediate physical positions: $geometry", intermediate.size >= 3)
        val uniquePositions = intermediate.map { (it.topY!! * 10).toInt() }.distinct()
        assertTrue("Progress state alone cannot substitute for moving layers", uniquePositions.size >= 3)
        val first = geometry.first()
        val last = geometry.last()
        val change = last.measuredTopProgress!! - first.measuredTopProgress!!
        assertTrue("The rendered panel must travel its own height", if (entering) change >= 0.70f else change <= -0.70f)
        assertTrue("Top panel moves ${if (entering) "down" else "up"}", if (entering) last.topY!! > first.topY!! else last.topY!! < first.topY!!)
        assertTrue("Bottom panel moves ${if (entering) "up" else "down"}", if (entering) last.bottomY!! < first.bottomY!! else last.bottomY!! > first.bottomY!!)
        for ((previous, current) in geometry.zipWithNext()) {
            val delta = current.measuredTopProgress!! - previous.measuredTopProgress!!
            assertTrue("An uninterrupted slide must keep its direction", if (entering) delta >= -0.03f else delta <= 0.03f)
        }
        assertTrue("A slide must finish promptly", last.timeMillis - first.timeMillis <= 1_000)
    }

    private fun assertRenderedLayersAgree(frames: List<ChromeMotionFrame>) {
        assertTrue("Motion capture needs rendered layer coordinates", frames.size >= 3)
        for (frame in frames) {
            val top = requireNotNull(frame.measuredTopProgress)
            val bottom = requireNotNull(frame.measuredBottomProgress)
            assertTrue("Top layer remains between hidden and shown positions: $frame", top in -0.03f..1.03f)
            assertTrue("Bottom layer remains between hidden and shown positions: $frame", bottom in -0.03f..1.03f)
            assertEquals("Both actual layers use the same normalized movement", top, bottom, 0.03f)
            assertEquals("Top layer transformation is applied to rendered coordinates", frame.progress, top, 0.03f)
            assertEquals("Bottom layer transformation is applied to rendered coordinates", frame.progress, bottom, 0.03f)
        }
    }

    private fun assertFollowsNativeInsets(
        frames: List<ChromeMotionFrame>,
        name: String,
        insetFraction: (ChromeMotionFrame) -> Float?,
    ) {
        if (frames.none { insetFraction(it) != null }) return // A device can have no bar on this edge.
        val nativeMotion = frames.filter { (insetFraction(it) ?: -1f) in 0.02f..0.98f }
        assertTrue("The $name bar must supply intermediate native animation frames", nativeMotion.size >= 3)
        val panelMotion = frames.filter { (it.measuredTopProgress ?: -1f) in 0.05f..0.95f }
        assertTrue("Panels must move during the $name animation", panelMotion.size >= 3)
        for (frame in panelMotion) {
            val adjacent = frames.filter { abs(it.timeMillis - frame.timeMillis) <= 25 }
                .mapNotNull(insetFraction)
            assertTrue("Actual panel position must track the $name insets within one rendered frame: $frame",
                adjacent.any { abs(it - frame.measuredTopProgress!!) <= 0.08f })
        }
    }

    @Test(timeout = 90_000)
    fun nativeHandleMatchesPanelAndDragsWithoutASideLabelAcrossReaderThemes() {
        for (theme in AppTheme.entries) withFixture(theme) { host ->
            var thumb = PointF()
            var targetY = 0f
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                view.fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_SHOW
                view.invalidate()
            }
            await("native fast-scroll layout in $theme") {
                var drawn = false
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    drawn = !view.fastScrollVerticalThumbDrawable.bounds.isEmpty
                }
                drawn
            }
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                val colors = readerColors(theme)
                val handle = view.fastScrollVerticalThumbDrawable
                assertTrue("Handle needs a grabbable width", handle.intrinsicWidth >= 48 * view.resources.displayMetrics.density - 1)
                assertTrue("Handle needs a visible, grabbable height", handle.intrinsicHeight >= 56 * view.resources.displayMetrics.density - 1)
                assertEquals("Handle fill matches the panels in $theme", colors.chrome.toArgb(), fillPixel(handle))
                assertTrue("No page bubble is drawn next to the handle", view.fastScrollPageIndicatorBackgroundDrawable.bounds.isEmpty)
                assertEquals("", view.currentPageIndicatorLabel)

                val origin = IntArray(2).also(view::getLocationOnScreen)
                thumb = PointF(origin[0] + handle.bounds.exactCenterX(),
                    origin[1] + view.paddingTop + handle.bounds.exactCenterY())
                targetY = origin[1] + view.height * 0.8f
                assertEquals(0, activity.chromeToggleCount)
            }
            tap(thumb)
            host.onActivity { assertEquals("Tapping the handle must not toggle controls", 0, it.chromeToggleCount) }
            drag(thumb, PointF(thumb.x, targetY))
            await("native handle advances pages in $theme") {
                var advanced = false
                host.onActivity { advanced = requireNotNull(it.nativeViewer()).firstVisiblePage >= 3 }
                advanced
            }
            host.onActivity { assertEquals("Dragging the handle must not toggle controls", 0, it.chromeToggleCount) }
        }
    }

    @Test(timeout = 60_000)
    fun singleTapChangesChromeOnlyWhenConfirmed() = withFixture(AppTheme.WHITE) { host ->
        val center = readerCenter(host)
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_DOWN, center)
        SystemClock.sleep(30)
        sendTouch(downTime, MotionEvent.ACTION_UP, center)
        host.onActivity {
            assertEquals("First release must not expose panels before double-tap detection finishes", 0, it.chromeToggleCount)
            assertFalse(it.chromeVisible)
        }
        await("confirmed single tap") {
            var confirmed = false
            host.onActivity { confirmed = it.chromeToggleCount == 1 }
            confirmed
        }
        host.onActivity {
            assertTrue(it.chromeVisible)
            assertTrue("Single tap must wait for the system confirmation window",
                it.lastChromeToggleTime >= downTime + ViewConfiguration.getDoubleTapTimeout())
        }
        waitPastDoubleTapWindow()
        host.onActivity { assertEquals("Single tap produces exactly one callback", 1, it.chromeToggleCount) }
    }

    @Test(timeout = 60_000)
    fun nativeDoubleTapZoomsWithoutAnyChromeCallbacksFromEitherState() {
        for (visible in listOf(false, true)) assertDoubleTapPreservesChrome(visible, secondTapGap = 80L)
    }

    @Test(timeout = 60_000)
    fun slowNativeDoubleTapZoomsWithoutAnyChromeCallbacksFromEitherState() {
        // Keep the second DOWN inside the actual platform window, including a 30 ms first tap.
        val gap = ViewConfiguration.getDoubleTapTimeout().toLong() - 60L
        for (visible in listOf(false, true)) assertDoubleTapPreservesChrome(visible, secondTapGap = gap)
    }

    @Test(timeout = 60_000)
    fun nativeQuickScaleZoomsWithoutChangingChrome() = withFixture(AppTheme.WHITE) { host ->
        val center = readerCenter(host)
        var initialZoom = 0f
        var distance = 0f
        host.onActivity {
            val view = requireNotNull(it.nativeViewer())
            initialZoom = view.zoom
            distance = view.height * 0.18f
        }
        tap(center)
        SystemClock.sleep(80)
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_DOWN, center)
        var point = center
        try {
            for (step in 1..12) {
                SystemClock.sleep(16)
                point = PointF(center.x, center.y + distance * step / 12f)
                sendTouch(downTime, MotionEvent.ACTION_MOVE, point)
            }
        } finally {
            sendTouch(downTime, MotionEvent.ACTION_UP, point)
        }
        await("native double-tap-and-drag zoom") {
            var zoomed = false
            host.onActivity { zoomed = kotlin.math.abs(requireNotNull(it.nativeViewer()).zoom - initialZoom) > initialZoom * 0.05f }
            zoomed
        }
        waitPastDoubleTapWindow()
        host.onActivity {
            assertEquals("Quick scale must never expose and then hide panels", 0, it.chromeToggleCount)
            assertFalse(it.chromeVisible)
        }
    }

    @Test(timeout = 60_000)
    fun cancelledTouchAndLongPressNeverToggleChrome() = withFixture(AppTheme.WHITE) { host ->
        val center = readerCenter(host)
        val cancelledDown = SystemClock.uptimeMillis()
        sendTouch(cancelledDown, MotionEvent.ACTION_DOWN, center)
        SystemClock.sleep(30)
        sendTouch(cancelledDown, MotionEvent.ACTION_CANCEL, center)
        waitPastDoubleTapWindow()
        host.onActivity { assertEquals("Cancelled contact must not confirm a single tap", 0, it.chromeToggleCount) }

        val heldDown = SystemClock.uptimeMillis()
        sendTouch(heldDown, MotionEvent.ACTION_DOWN, center)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 80L)
        sendTouch(heldDown, MotionEvent.ACTION_UP, center)
        waitPastDoubleTapWindow()
        host.onActivity {
            assertEquals("Long press must not confirm a single tap", 0, it.chromeToggleCount)
            assertFalse(it.chromeVisible)
        }
    }

    private fun assertDoubleTapPreservesChrome(visible: Boolean, secondTapGap: Long) = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(visible) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val center = readerCenter(host)
        var initialZoom = 0f
        host.onActivity { initialZoom = requireNotNull(it.nativeViewer()).zoom }
        val firstUp = tap(center)
        val secondDown = firstUp + secondTapGap
        SystemClock.sleep((secondDown - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        tap(center)
        await("native double-tap zoom with controls visible=$visible and gap=$secondTapGap ms") {
            var zoomed = false
            host.onActivity { zoomed = requireNotNull(it.nativeViewer()).zoom > initialZoom * 1.1f }
            zoomed
        }
        // Cumulative count detects even a one-frame flash; final visibility alone would not.
        // Keep waiting after the pair so an incorrectly pending single tap cannot escape the test.
        waitPastDoubleTapWindow()
        host.onActivity {
            assertEquals("Double tap must produce no chrome callbacks at all", 0, it.chromeToggleCount)
            assertEquals("Double tap preserves the original controls state", visible, it.chromeVisible)
        }
    }

    @Test(timeout = 60_000)
    fun liveThemeChangesRefreshNativeLabelAndPreservePdfViewport() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { activity ->
            requireNotNull(activity.nativeViewer()).apply {
                zoom *= 1.5f
                scrollToPage(6)
            }
        }
        await("zoomed middle page") {
            var positioned = false
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                positioned = view.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum == 6
            }
            positioned
        }
        var expectedZoom = 0f
        var expectedPage = 0
        var expectedCenter = PointF()
        host.onActivity { activity ->
            val view = requireNotNull(activity.nativeViewer())
            val center = requireNotNull(view.viewToPdfPoint(view.width / 2f, view.height / 2f))
            expectedZoom = view.zoom
            expectedPage = center.pageNum
            expectedCenter = PointF(center.x, center.y)
        }
        for (theme in listOf(AppTheme.OLED, AppTheme.SEPIA)) {
            var oldViewer: PdfView? = null
            host.onActivity {
                oldViewer = it.nativeViewer()
                it.switchTheme(theme)
            }
            await("native viewer recreated and loaded in $theme") {
                var reloaded = false
                host.onActivity { activity ->
                    activity.loadFailure?.let { throw AssertionError("PDF theme switch failed", it) }
                    reloaded = activity.contentReady && activity.nativeViewer() !== oldViewer
                }
                reloaded
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            host.onActivity { activity ->
                val view = requireNotNull(activity.nativeViewer())
                val label = TypedValue()
                assertTrue(view.context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, label, true))
                val labelColor = if (label.resourceId != 0) view.context.getColor(label.resourceId) else label.data
                assertEquals("Existing PDF updates the native label ink", readerColors(theme).onChrome.toArgb(), labelColor)
                val center = requireNotNull(view.viewToPdfPoint(view.width / 2f, view.height / 2f))
                assertEquals("Theme switch preserves zoom", expectedZoom, view.zoom, 0.01f)
                assertEquals("Theme switch preserves the current page", expectedPage, center.pageNum)
                assertEquals("Toolbar follows the restored viewport", expectedPage, activity.displayedPage)
                assertEquals("Theme switch preserves horizontal position", expectedCenter.x, center.x, 2f)
                assertEquals("Theme switch preserves vertical position", expectedCenter.y, center.y, 2f)
                assertEquals("Changing theme does not toggle controls", 0, activity.chromeToggleCount)
            }
        }
    }

    @Test(timeout = 60_000)
    fun toolbarPageTracksNativeJumpsAndDraggingBeforeRelease() = withFixture(AppTheme.WHITE) { host ->
        host.onActivity { it.setChromeVisible(true) }
        await("visible toolbar page indicator") { hasPageDescription(0) }

        for (target in listOf(6, 2)) {
            host.onActivity { requireNotNull(it.nativeViewer()).scrollToPage(target) }
            await("native viewport reaches page $target") {
                var reached = false
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    reached = view.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum == target
                }
                reached
            }
            assertPageShownSoon(host, target)
        }

        host.onActivity {
            requireNotNull(it.nativeViewer()).apply {
                fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_SHOW
                invalidate()
            }
        }
        await("fast-scroll handle layout") {
            var drawn = false
            host.onActivity { drawn = !requireNotNull(it.nativeViewer()).fastScrollVerticalThumbDrawable.bounds.isEmpty }
            drawn
        }
        var from = PointF()
        var to = PointF()
        host.onActivity { activity ->
            val view = requireNotNull(activity.nativeViewer())
            val bounds = view.fastScrollVerticalThumbDrawable.bounds
            val origin = IntArray(2).also(view::getLocationOnScreen)
            from = PointF(origin[0] + bounds.exactCenterX(), origin[1] + view.paddingTop + bounds.exactCenterY())
            to = PointF(from.x, origin[1] + view.height * 0.75f)
        }
        val downTime = SystemClock.uptimeMillis()
        var lastPoint = from
        var finalPage = 2
        sendTouch(downTime, MotionEvent.ACTION_DOWN, from)
        try {
            for (step in 1..4) {
                SystemClock.sleep(16)
                lastPoint = PointF(from.x, from.y + (to.y - from.y) * step / 4f)
                sendTouch(downTime, MotionEvent.ACTION_MOVE, lastPoint)
                host.onActivity { activity ->
                    val view = requireNotNull(activity.nativeViewer())
                    finalPage = view.viewToPdfPoint(view.width / 2f, view.height / 2f)?.pageNum ?: view.firstVisiblePage
                }
                // Check while the finger is still down; neither scroll settling nor progress saving
                // may be required before the actual toolbar describes the current viewport page.
                assertPageShownSoon(host, finalPage)
            }
        } finally {
            sendTouch(downTime, MotionEvent.ACTION_UP, lastPoint)
        }
        assertTrue("Dragging must advance the native viewport", finalPage > 2)
    }

    private fun assertPageShownSoon(host: ActivityScenario<PdfChromeTestActivity>, expectedPage: Int) {
        val deadline = SystemClock.uptimeMillis() + 300
        var displayedPage = -1
        while (SystemClock.uptimeMillis() < deadline) {
            host.onActivity { displayedPage = it.displayedPage }
            if (displayedPage == expectedPage && hasPageDescription(expectedPage)) return
            SystemClock.sleep(10)
        }
        fail("Toolbar must show native page $expectedPage within 300 ms, before the 500 ms save debounce; last page was $displayedPage")
    }

    private fun readerCenter(host: ActivityScenario<PdfChromeTestActivity>): PointF {
        var center = PointF()
        host.onActivity { activity ->
            val view = requireNotNull(activity.nativeViewer())
            val origin = IntArray(2).also(view::getLocationOnScreen)
            center = PointF(origin[0] + view.width / 2f, origin[1] + view.height / 2f)
        }
        return center
    }

    private fun waitPastDoubleTapWindow() {
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 80L)
    }

    private fun hasPageDescription(page: Int): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = instrumentation.targetContext.getString(R.string.pdf_page_indicator, page + 1, 12)
        val automation = instrumentation.uiAutomation
        // A fast series of page changes can outpace accessibility event delivery.
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        fun find(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.contentDescription?.toString() == expected) return true
            for (index in 0 until node.childCount) if (find(node.getChild(index))) return true
            return false
        }
        return find(automation.rootInActiveWindow)
    }

    private fun withFixture(
        theme: AppTheme,
        rightPageColor: Int? = null,
        showTextLines: Boolean = false,
        test: (ActivityScenario<PdfChromeTestActivity>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = createPdf(context.cacheDir, rightPageColor, showTextLines)
        var scenario: ActivityScenario<PdfChromeTestActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(context, PdfChromeTestActivity::class.java)
                .setData(Uri.fromFile(source))
                .putExtra(PdfChromeTestActivity.EXTRA_THEME, theme.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = scenario
            await("PDF content load") {
                var ready = false
                host.onActivity {
                    it.loadFailure?.let { error -> throw AssertionError("PDF fixture failed", error) }
                    ready = it.contentReady
                }
                ready
            }
            await("resumed PDF window with input focus") {
                var focused = false
                host.onActivity {
                    focused = it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        it.window.decorView.hasWindowFocus()
                }
                focused
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5_000)
            test(host)
        } finally {
            try { scenario?.close() }
            finally { source.delete() }
        }
    }

    /** Sample an inset fill, away from the grip and the rounded corners. */
    private fun fillPixel(drawable: Drawable): Int {
        val oldBounds = Rect(drawable.bounds)
        val width = maxOf(drawable.intrinsicWidth, oldBounds.width(), 1)
        val height = maxOf(drawable.intrinsicHeight, oldBounds.height(), 1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            return bitmap.getPixel(width * 3 / 4, height / 2)
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private fun tap(point: PointF): Long {
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_DOWN, point)
        SystemClock.sleep(30)
        val released = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_UP, point, released)
        return released
    }

    private fun drag(from: PointF, to: PointF) {
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, MotionEvent.ACTION_DOWN, from)
        for (step in 1..12) {
            SystemClock.sleep(16)
            val fraction = step / 12f
            sendTouch(downTime, MotionEvent.ACTION_MOVE, PointF(
                from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction,
            ))
        }
        sendTouch(downTime, MotionEvent.ACTION_UP, to)
    }

    private fun sendTouch(downTime: Long, action: Int, point: PointF, time: Long = SystemClock.uptimeMillis()) {
        MotionEvent.obtain(downTime, time, action, point.x, point.y, 0).also { event ->
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
            } finally { event.recycle() }
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for $label")
    }

    private fun createPdf(directory: File, rightPageColor: Int?, showTextLines: Boolean): File {
        val file = File.createTempFile("pdf-chrome-", ".pdf", directory)
        try {
            val document = PdfDocument()
            try {
                repeat(12) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, index + 1).create())
                    try {
                        page.canvas.drawColor(Color.WHITE)
                        if (rightPageColor != null) {
                            page.canvas.drawRect(160f, 0f, 320f, 480f, Paint().apply { color = rightPageColor })
                        }
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f }
                        if (showTextLines) {
                            for (line in 0 until 480 step 12) {
                                page.canvas.drawRect(0f, line.toFloat(), 320f, line + 4f, paint)
                            }
                        } else {
                            page.canvas.drawText("PDF page ${index + 1}", 24f, 75f, paint)
                        }
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
}
