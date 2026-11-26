package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.ReaderNavigationPolicy
import com.example.frogreader.ui.reader.ReaderPageIndex
import com.example.frogreader.ui.reader.ReaderPagerWindow
import com.example.frogreader.ui.reader.ReaderPaginationRange
import com.example.frogreader.ui.reader.ReaderProgressDraftAction
import com.example.frogreader.ui.reader.ReaderPublisherBox
import com.example.frogreader.ui.reader.ReaderPublisherFloat
import com.example.frogreader.ui.reader.ReaderPublisherFloatContent
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.isSameSpecWindowKey
import com.example.frogreader.ui.reader.readerProgressDraftAction
import com.example.frogreader.ui.reader.resolveExactQuickPaginationStart
import com.example.frogreader.ui.reader.resolvePreviewAnchorForSeek
import com.example.frogreader.ui.reader.resolveSeekInitialPagerPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSeekResolutionTest {

    private val paragraph = ContentElement.Paragraph(AnnotatedString("x".repeat(600)))

    private fun textPage(item: Int, start: Int = 0, end: Int = 600) = BookPage(
        parts = listOf(
            PagePart(
                itemIndex = item,
                element = paragraph,
                text = paragraph.text.subSequence(start, end),
                charStart = start,
                charEnd = end,
                isParagraphStart = start == 0,
                publisherStartsElement = start == 0,
                publisherEndsElement = end == paragraph.text.length,
            ),
        ),
        firstItemIndex = item,
        firstCharOffset = start,
    )

    @Test
    fun `forward slider seekFraction lands directly on target page inside newly loaded window`() {
        // Book has 10_000 items; 0.43f -> item 4300.
        // Newly loaded chapter 325 window covers items 4243..4314 (72 pages, 1 item per page).
        val itemCount = 10_001
        val expectedItem = ReaderNavigationPolicy.progressTargetIndex(0.43f, 0, itemCount - 1)
        assertEquals(4300, expectedItem)

        val chapterStart = 4243
        val pages = List(72) { pageIdx -> textPage(chapterStart + pageIdx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        val pagerPage = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = chapterStart..(chapterStart + 71),
            partial = true,
            rangeComplete = false,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = itemCount,
            seekPosition = null,
            seekTarget = null,
            seekFraction = 0.43f,
            seekCompletion = false,
        )

        // Page 57 in chapter 325 -> pager page 58 (due to loadingBefore = true), NOT page 0/1!
        assertEquals(pageWindow.pagerPage(57), pagerPage)
        assertEquals(58, pagerPage)
    }

    @Test
    fun `forward slider seekTarget lands directly on exact target page not page 0`() {
        val chapterStart = 14_900
        val pages = List(72) { pageIdx -> textPage(chapterStart + pageIdx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        val pagerPage = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = chapterStart..(chapterStart + 71),
            partial = true,
            rangeComplete = false,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = 35_000,
            seekPosition = null,
            seekTarget = chapterStart + 57,
            seekFraction = null,
            seekCompletion = false,
        )

        assertEquals(pageWindow.pagerPage(57), pagerPage)
    }

    @Test
    fun `backward slider seek from end of book lands on target page not pages lastIndex`() {
        // Earlier window covering items 100..119 (20 pages).
        val pages = List(20) { idx -> textPage(100 + idx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        // Target is item 103 (page 3), even though the user's previous position was at the end of the book.
        val byTarget = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = 100..119,
            partial = true,
            rangeComplete = true,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = 10_000,
            seekPosition = null,
            seekTarget = 103,
            seekFraction = null,
            seekCompletion = false,
        )
        assertEquals(pageWindow.pagerPage(3), byTarget)

        val byFraction = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = 100..119,
            partial = true,
            rangeComplete = true,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = 10_001,
            seekPosition = null,
            seekTarget = null,
            seekFraction = 103f / 10_000f,
            seekCompletion = false,
        )
        assertEquals(pageWindow.pagerPage(3), byFraction)
    }

    @Test
    fun `TOC seekPosition into middle chapter of 3-chapter window opens on chapter 325 first page`() {
        // Multi-chapter window:
        // ch 324: items 3240..3249 (10 pages: indices 0..9)
        // ch 325: items 3250..3264 (15 pages: indices 10..24)
        // ch 326: items 3265..3274 (10 pages: indices 25..34)
        val pages = List(35) { idx -> textPage(3240 + idx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        val pagerPage = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = 3240..3274,
            partial = true,
            rangeComplete = true,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = 10_000,
            seekPosition = ReaderReturnLocation.Main(3250),
            seekTarget = null,
            seekFraction = null,
            seekCompletion = false,
        )

        // First page of ch 325 is content page 10 -> pager page 11, NOT ch 324 page 0 or ch 326 last page!
        assertEquals(pageWindow.pagerPage(10), pagerPage)
        assertEquals(11, pagerPage)
    }

    @Test
    fun `seekPosition with mid-paragraph charOffset resolves exact continuation page`() {
        val pages = listOf(
            textPage(500, 0, 200),
            textPage(500, 200, 400),
            textPage(500, 400, 600),
            textPage(501, 0, 600),
        )
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = false,
            completionAfter = true,
        )

        val pagerPage = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = 500..501,
            partial = true,
            rangeComplete = false,
            indexedWindow = true,
            globalCoordinatesReady = true,
            completeAtBookEnd = false,
            itemCount = 1000,
            seekPosition = ReaderReturnLocation.Main(500, charOffset = 250),
            seekTarget = null,
            seekFraction = null,
            seekCompletion = false,
        )

        assertEquals(1, pagerPage)
    }

    @Test
    fun `seekCompletion resolves to afterPage with loadingBefore offset only when completeAtBookEnd`() {
        val pages = List(12) { idx -> textPage(988 + idx) }
        val windowWithLoadingBefore = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = true,
        )
        val windowWithoutLoadingBefore = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = false,
            completionAfter = true,
        )

        // Complete at book end with loadingBefore = true -> afterPage = 1 + 12 = 13.
        assertEquals(
            13,
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = windowWithLoadingBefore,
                preparedRange = 988..999,
                partial = true,
                rangeComplete = true,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = true,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = true,
            ),
        )

        // Complete at book end without loadingBefore -> afterPage = 12.
        assertEquals(
            12,
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = windowWithoutLoadingBefore,
                preparedRange = null,
                partial = false,
                rangeComplete = true,
                indexedWindow = false,
                globalCoordinatesReady = true,
                completeAtBookEnd = true,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = true,
            ),
        )

        // Not complete at book end (e.g. earlier window or quick preview) -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = windowWithLoadingBefore,
                preparedRange = 988..999,
                partial = true,
                rangeComplete = false,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = true,
            ),
        )
    }

    @Test
    fun `non-matching layout or target outside partial window returns null`() {
        val pages = List(10) { idx -> textPage(100 + idx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        // Stale window: matchesLayout == false -> must return null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = false,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = true,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = ReaderReturnLocation.Main(105),
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
            ),
        )

        // Continuous layout active -> handled by exactInitialPage, returns null here.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = true,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = true,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = ReaderReturnLocation.Main(105),
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
            ),
        )

        // seekPosition not yet covered by partial window -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = false,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = ReaderReturnLocation.Main(500),
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
            ),
        )

        // seekTarget not yet covered by partial window -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = false,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = 500,
                seekFraction = null,
                seekCompletion = false,
            ),
        )

        // seekFraction not yet covered by indexed window -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = true,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = null,
                seekFraction = 0.8f,
                seekCompletion = false,
            ),
        )

        // Non-indexed partial book cannot consume whole-book seekFraction -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..109,
                partial = true,
                rangeComplete = false,
                indexedWindow = false,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = null,
                seekFraction = 0.5f,
                seekCompletion = false,
            ),
        )
    }

    @Test
    fun `complete non-indexed book resolves seekFraction in page space`() {
        val pages = List(101) { idx -> textPage(idx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = false,
            completionAfter = true,
        )

        val pagerPage = resolveSeekInitialPagerPage(
            matchesLayout = true,
            usesContinuousLayout = false,
            pages = pages,
            pageWindow = pageWindow,
            preparedRange = null,
            partial = false,
            rangeComplete = true,
            indexedWindow = false,
            globalCoordinatesReady = true,
            completeAtBookEnd = true,
            itemCount = 101,
            seekPosition = null,
            seekTarget = null,
            seekFraction = 0.43f,
            seekCompletion = false,
        )

        assertEquals(43, pagerPage)
    }

    @Test
    fun `rangeComplete window resolves trailing whitespace item inside preparedRange`() {
        // Items 100..109 are drawn, item 110 is a trailing spacer with no PagePart.
        val pages = List(10) { idx -> textPage(100 + idx) }
        val pageWindow = ReaderPagerWindow(
            realPageCount = pages.size,
            loadingBefore = true,
            completionAfter = false,
        )

        // When rangeComplete = false (preview), item 110 is not yet proven complete -> null.
        assertNull(
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..110,
                partial = true,
                rangeComplete = false,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = 110,
                seekFraction = null,
                seekCompletion = false,
            ),
        )

        // When rangeComplete = true, preparedPagesContainLocation accepts item 110 in preparedRange.
        assertEquals(
            pageWindow.pagerPage(9),
            resolveSeekInitialPagerPage(
                matchesLayout = true,
                usesContinuousLayout = false,
                pages = pages,
                pageWindow = pageWindow,
                preparedRange = 100..110,
                partial = true,
                rangeComplete = true,
                indexedWindow = true,
                globalCoordinatesReady = true,
                completeAtBookEnd = false,
                itemCount = 1000,
                seekPosition = null,
                seekTarget = 110,
                seekFraction = null,
                seekCompletion = false,
            ),
        )
    }

    @Test
    fun `resolvePreviewAnchorForSeek honors seekFraction and seekCompletion instead of stale currentFlatIndex`() {
        // Slider seekFraction with global coordinates ready overrides stale currentFlatIndex = 0.
        assertEquals(
            ReaderReturnLocation.Main(4300),
            resolvePreviewAnchorForSeek(
                itemCount = 10_001,
                globalCoordinatesReady = true,
                seekPosition = null,
                seekTarget = null,
                seekFraction = 0.43f,
                seekCompletion = false,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 0,
                currentCharOffset = 0,
            ),
        )

        // seekCompletion with global coordinates ready anchors to book end, not stale currentFlatIndex.
        assertEquals(
            ReaderReturnLocation.Main(10_000, Int.MAX_VALUE),
            resolvePreviewAnchorForSeek(
                itemCount = 10_001,
                globalCoordinatesReady = true,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = true,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 0,
                currentCharOffset = 0,
            ),
        )

        // seekTarget uses Int.MAX_VALUE so quick preview covers the whole target element.
        assertEquals(
            ReaderReturnLocation.Main(3250, Int.MAX_VALUE),
            resolvePreviewAnchorForSeek(
                itemCount = 10_001,
                globalCoordinatesReady = true,
                seekPosition = null,
                seekTarget = 3250,
                seekFraction = null,
                seekCompletion = false,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 0,
                currentCharOffset = 0,
            ),
        )

        // seekPosition keeps its exact charOffset.
        assertEquals(
            ReaderReturnLocation.Main(3250, 140),
            resolvePreviewAnchorForSeek(
                itemCount = 10_001,
                globalCoordinatesReady = true,
                seekPosition = ReaderReturnLocation.Main(3250, 140),
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 0,
                currentCharOffset = 0,
            ),
        )

        // Before global coordinates are ready, seekFraction/seekCompletion do not invent an item index.
        assertEquals(
            ReaderReturnLocation.Main(12, 34),
            resolvePreviewAnchorForSeek(
                itemCount = 50,
                globalCoordinatesReady = false,
                seekPosition = null,
                seekTarget = null,
                seekFraction = 0.43f,
                seekCompletion = false,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 12,
                currentCharOffset = 34,
            ),
        )
        assertEquals(
            ReaderReturnLocation.Main(12, 34),
            resolvePreviewAnchorForSeek(
                itemCount = 50,
                globalCoordinatesReady = false,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = true,
                pendingBackwardTurn = null,
                pendingForwardTurn = null,
                currentFlatIndex = 12,
                currentCharOffset = 34,
            ),
        )

        // Pending turns take precedence over fallback currentFlatIndex.
        val backTurn = ReaderReturnLocation.Main(99, Int.MAX_VALUE)
        val fwdTurn = ReaderReturnLocation.Main(200)
        assertEquals(
            backTurn,
            resolvePreviewAnchorForSeek(
                itemCount = 1000,
                globalCoordinatesReady = true,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
                pendingBackwardTurn = backTurn,
                pendingForwardTurn = fwdTurn,
                currentFlatIndex = 150,
                currentCharOffset = 0,
            ),
        )
        assertEquals(
            fwdTurn,
            resolvePreviewAnchorForSeek(
                itemCount = 1000,
                globalCoordinatesReady = true,
                seekPosition = null,
                seekTarget = null,
                seekFraction = null,
                seekCompletion = false,
                pendingBackwardTurn = null,
                pendingForwardTurn = fwdTurn,
                currentFlatIndex = 150,
                currentCharOffset = 0,
            ),
        )
    }

    @Test
    fun `isSameSpecWindowKey keeps whole-book index across window swaps and rejects layout changes`() {
        val spec = "w=1080|h=2100|fs=18.0"
        val env = "rtl=false|locale=en-US|fp"
        val src = "|src=0"

        // Same spec, env, source, different chapter window -> true (prevents return chip flicker).
        assertTrue(isSameSpecWindowKey("$spec|window=0:500|environment=$env$src", spec, env, src))
        assertTrue(isSameSpecWindowKey("$spec|window=14500:15800|environment=$env$src", spec, env, src))

        // Settings / spec change -> false.
        assertFalse(isSameSpecWindowKey("w=1080|h=2100|fs=20.0|window=0:500|environment=$env$src", spec, env, src))
        // Environment change -> false.
        assertFalse(isSameSpecWindowKey("$spec|window=0:500|environment=rtl=true|locale=en-US|fp$src", spec, env, src))
        // Source generation change -> false.
        assertFalse(isSameSpecWindowKey("$spec|window=0:500|environment=$env|src=1", spec, env, src))
        // Non-window keys (continuous / prefix / full) -> false.
        assertFalse(isSameSpecWindowKey("$spec|continuous|environment=$env$src", spec, env, src))
        assertFalse(isSameSpecWindowKey("$spec|prefix=100|environment=$env$src", spec, env, src))
        assertFalse(isSameSpecWindowKey("$spec|environment=$env$src", spec, env, src))
    }

    @Test
    fun `readerProgressDraftAction holds draft while seek is loading and clears cleanly when resolved`() {
        // Active drag / adjustment always holds.
        assertEquals(
            ReaderProgressDraftAction.HOLD,
            readerProgressDraftAction(
                adjusting = true,
                hasDraft = true,
                seekPending = false,
                externalSeekActive = false,
                seekReached = true,
            ),
        )
        assertEquals(
            ReaderProgressDraftAction.HOLD,
            readerProgressDraftAction(
                adjusting = true,
                hasDraft = true,
                seekPending = false,
                externalSeekActive = true,
                seekReached = false,
            ),
        )

        // No active draft -> HOLD (no-op).
        assertEquals(
            ReaderProgressDraftAction.HOLD,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = false,
                seekPending = false,
                externalSeekActive = true,
                seekReached = true,
            ),
        )

        // Slow chapter load (>600ms) with seek still in flight -> HOLD (prevents snap-back).
        assertEquals(
            ReaderProgressDraftAction.HOLD,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = true,
                seekPending = true,
                externalSeekActive = false,
                seekReached = false,
            ),
        )

        // Seek finished and target fraction reached -> CLEAR_IMMEDIATELY.
        assertEquals(
            ReaderProgressDraftAction.CLEAR_IMMEDIATELY,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = true,
                seekPending = false,
                externalSeekActive = false,
                seekReached = true,
            ),
        )

        // Post-seek settle / page-start rounding -> CLEAR_AFTER_GRACE.
        assertEquals(
            ReaderProgressDraftAction.CLEAR_AFTER_GRACE,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = true,
                seekPending = false,
                externalSeekActive = false,
                seekReached = false,
            ),
        )

        // External seek (TOC, Back button, or completion) while draft active -> CLEAR_IMMEDIATELY.
        assertEquals(
            ReaderProgressDraftAction.CLEAR_IMMEDIATELY,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = true,
                seekPending = false,
                externalSeekActive = true,
                seekReached = false,
            ),
        )
        assertEquals(
            ReaderProgressDraftAction.CLEAR_IMMEDIATELY,
            readerProgressDraftAction(
                adjusting = false,
                hasDraft = true,
                seekPending = true,
                externalSeekActive = true,
                seekReached = false,
            ),
        )
    }

    @Test
    fun `resolveExactQuickPaginationStart picks clean anchor leadingPages before targetPage`() {
        // Items 0..19 belong to chapter 0 (pages 0..3), items 20..79 belong to chapter 1 (pages 4..15).
        val items = List(80) { idx ->
            ReaderItem(chapterIndex = if (idx < 20) 0 else 1, element = paragraph)
        }
        val anchors = intArrayOf(
            // Chapter 0: pages 0..3
            0, 0,
            5, 0,
            10, 0,
            15, 0,
            // Chapter 1: pages 4..15 (items 20, 25, ..., 75)
            20, 0,
            25, 0,
            30, 0,
            35, 0,
            40, 0,
            45, 0,
            50, 0,
            55, 0,
            60, 0,
            65, 0,
            70, 0,
            75, 0,
        )
        val exactIndex = ReaderPageIndex(anchors)
        val quickRange = ReaderPaginationRange(fromIndex = 20, toIndex = 80)

        // Target on page 12 (item 60) -> leadingPages=2 -> page 10 -> item 50.
        assertEquals(
            exactIndex.itemAt(10),
            resolveExactQuickPaginationStart(
                exactIndex = exactIndex,
                items = items,
                quickRange = quickRange,
                target = ReaderReturnLocation.Main(60),
            ),
        )
        assertEquals(50, resolveExactQuickPaginationStart(exactIndex, items, quickRange, ReaderReturnLocation.Main(62)))

        // Custom leadingPages = 3 -> page 9 -> item 45.
        assertEquals(
            exactIndex.itemAt(9),
            resolveExactQuickPaginationStart(
                exactIndex = exactIndex,
                items = items,
                quickRange = quickRange,
                target = ReaderReturnLocation.Main(60),
                leadingPages = 3,
            ),
        )

        // Target on page 5 (1 page after chapter start page 4) with leadingPages=2 clamps to chapterStartPage -> quickRange.fromIndex.
        assertEquals(
            20,
            resolveExactQuickPaginationStart(
                exactIndex = exactIndex,
                items = items,
                quickRange = quickRange,
                target = ReaderReturnLocation.Main(25),
            ),
        )
    }

    @Test
    fun `resolveExactQuickPaginationStart walks back past split duplicate loading float and open box pages`() {
        val openBox = ReaderPublisherBox(
            id = "box-open",
            parentId = null,
            style = PublisherBoxStyle(),
            floatSide = null,
            startsAtElement = false,
            endsAtElement = true,
        )
        val startingBox = ReaderPublisherBox(
            id = "box-start",
            parentId = null,
            style = PublisherBoxStyle(),
            floatSide = null,
            startsAtElement = true,
            endsAtElement = false,
        )
        val floatSpec = ReaderPublisherFloat(
            side = PublisherFloatSide.LEFT,
            style = PublisherBoxStyle(),
            contents = listOf(ReaderPublisherFloatContent(sourceItemIndex = 12, element = paragraph)),
        )

        val items = MutableList(40) { ReaderItem(chapterIndex = 1, element = paragraph) }
        // Safe anchor at item 14 starts its own publisher box (startsAtElement = true -> allowed).
        items[14] = ReaderItem(chapterIndex = 1, element = paragraph, publisherBoxes = listOf(startingBox))
        // Unsafe anchors on pages walking down from targetPage - 2:
        items[16] = ReaderItem(chapterIndex = 1, element = paragraph, publisherBoxes = listOf(openBox))
        items[18] = ReaderItem(chapterIndex = 1, element = paragraph, suppressedByPublisherFloat = true)
        items[20] = ReaderItem(chapterIndex = 1, element = paragraph, publisherFloat = floatSpec)
        items[22] = ReaderItem(chapterIndex = 1, element = paragraph, isLoading = true)

        val anchors = intArrayOf(
            10, 0,   // p=0 chapter start
            14, 0,   // p=1 CLEAN (startsAtElement=true box)
            16, 0,   // p=2 UNSAFE: open multi-element publisher box (startsAtElement=false)
            18, 0,   // p=3 UNSAFE: suppressedByPublisherFloat
            20, 0,   // p=4 UNSAFE: publisherFloat != null
            22, 0,   // p=5 UNSAFE: isLoading = true
            24, 0,   // p=6 (item 24)
            24, 0,   // p=7 UNSAFE: duplicate itemAt(p-1) >= itemAt(p)
            26, 120, // p=8 UNSAFE: split paragraph (charAt > 0)
            28, 0,   // p=9
            30, 0,   // p=10 target
        )
        val exactIndex = ReaderPageIndex(anchors)
        val quickRange = ReaderPaginationRange(fromIndex = 10, toIndex = 40)

        // Target at p=10 (item 30), leadingPages=2 -> starts scan at p=8 (charAt=120),
        // skips p=8 (split), p=7 (duplicate item 24) -> lands on p=6 (item 24, clean!).
        assertEquals(
            24,
            resolveExactQuickPaginationStart(exactIndex, items, quickRange, ReaderReturnLocation.Main(30)),
        )

        // Mark item 24 as loading too so scan must walk past p=8..p=2 and land on p=1 (item 14).
        items[24] = ReaderItem(chapterIndex = 1, element = paragraph, isLoading = true)
        assertEquals(
            14,
            resolveExactQuickPaginationStart(exactIndex, items, quickRange, ReaderReturnLocation.Main(30)),
        )
    }

    @Test
    fun `resolveExactQuickPaginationStart falls back to quickRange fromIndex on edge cases`() {
        val items = List(30) { ReaderItem(chapterIndex = 1, element = paragraph) }
        val quickRange = ReaderPaginationRange(fromIndex = 10, toIndex = 30)
        val target = ReaderReturnLocation.Main(25)

        // Null index -> fallback.
        assertEquals(10, resolveExactQuickPaginationStart(null, items, quickRange, target))

        // Empty index -> fallback.
        assertEquals(10, resolveExactQuickPaginationStart(ReaderPageIndex(IntArray(0)), items, quickRange, target))

        // Empty items -> fallback.
        val validIndex = ReaderPageIndex(intArrayOf(10, 0, 15, 0, 20, 0, 25, 0))
        assertEquals(10, resolveExactQuickPaginationStart(validIndex, emptyList(), quickRange, target))

        // Empty quickRange -> fallback.
        assertEquals(
            15,
            resolveExactQuickPaginationStart(validIndex, items, ReaderPaginationRange(15, 15), target),
        )

        // chapterStartPage >= exactIndex.size -> fallback.
        val earlierOnlyIndex = ReaderPageIndex(intArrayOf(0, 0, 5, 0))
        assertEquals(10, resolveExactQuickPaginationStart(earlierOnlyIndex, items, quickRange, target))

        // targetPage <= chapterStartPage -> fallback.
        assertEquals(
            10,
            resolveExactQuickPaginationStart(validIndex, items, quickRange, ReaderReturnLocation.Main(10)),
        )
        assertEquals(
            10,
            resolveExactQuickPaginationStart(validIndex, items, quickRange, ReaderReturnLocation.Main(5)),
        )

        // No clean anchor between startPage and chapterStartPage -> fallback.
        val allSplitIndex = ReaderPageIndex(
            intArrayOf(
                10, 0,   // chapter start
                12, 50,  // split
                14, 80,  // split
                16, 120, // split
                25, 0,   // target
            ),
        )
        assertEquals(10, resolveExactQuickPaginationStart(allSplitIndex, items, quickRange, target))

        // Candidate with startItem > target.flatItemIndex is skipped.
        val outOfOrderTargetIndex = ReaderPageIndex(
            intArrayOf(
                10, 0,
                14, 0,
                26, 0,
                27, 0,
                28, 0,
            ),
        )
        assertEquals(
            14,
            resolveExactQuickPaginationStart(
                exactIndex = outOfOrderTargetIndex,
                items = items,
                quickRange = quickRange,
                target = ReaderReturnLocation.Main(25),
                leadingPages = 0,
            ),
        )
    }
}
