package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderLoadTrace
import com.example.frogreader.ui.reader.formatHudDuration
import com.example.frogreader.ui.reader.formatHudRate
import com.example.frogreader.ui.reader.matchesPresentedWindowKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLoadTraceTest {

    @Test
    fun beginUpdateEndStageUpdatesHudStateAndAllDoneTotalMs() {
        var nowMs = 1_000L
        val trace = ReaderLoadTrace(
            bookFormat = "EPUB",
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        val since = trace.beginStage("exact_page_index", "Book pagination", progress = "0/677 ch")
        assertEquals(1, trace.hudState.value.active.size)
        assertEquals("0/677 ch", trace.hudState.value.active.single().progress)
        assertNull(trace.hudState.value.allDoneTotalMs)

        nowMs += 250L
        trace.updateStage("exact_page_index", "120/677 ch")
        assertEquals("120/677 ch", trace.hudState.value.active.single().progress)

        nowMs += 750L
        trace.endStage(
            id = "exact_page_index",
            since = since,
            count = 412,
            detail = "412 pg",
            logName = "exact_page_index_build",
        )

        val state = trace.hudState.value
        assertEquals("EPUB", state.bookFormat)
        assertTrue(state.active.isEmpty())
        assertEquals(1, state.completed.size)
        val completed = state.completed.single()
        assertEquals("exact_page_index", completed.id)
        assertEquals("Book pagination", completed.label)
        assertEquals(1_000L, completed.durationMs)
        assertEquals(1_000L, completed.totalElapsedMs)
        assertEquals("412 pg", completed.detail)
        assertEquals(412, completed.count)
        assertEquals(1_000L, state.allDoneTotalMs)
    }

    @Test
    fun surfaceReadyIsRecordedOnceAndSetsSurfaceReadyMs() {
        var nowMs = 5_000L
        val trace = ReaderLoadTrace(
            bookFormat = "FB2",
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        nowMs += 180L
        trace.surfaceReady()
        nowMs += 400L
        trace.surfaceReady()

        val state = trace.hudState.value
        assertEquals(180L, state.surfaceReadyMs)
        assertEquals(180L, state.initialOpenTotalMs)
        assertEquals(1, state.completed.size)
        assertEquals("surface_ready", state.completed.single().id)
        assertEquals("First paint", state.completed.single().label)
        assertEquals(180L, state.allDoneTotalMs)
    }

    @Test
    fun overlappingStagesKeepAllDoneNullUntilAllFinishAndHandleCancellation() {
        var nowMs = 10_000L
        val trace = ReaderLoadTrace(
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )
        trace.setBookFormat("EPUB")

        val frbSince = trace.beginStage("epub_frb_build", "Build FRB (Rust)")
        nowMs += 100L
        val windowSince = trace.beginStage("target_window", "Chapter window", progress = "ch 5")

        assertEquals(2, trace.hudState.value.active.size)
        assertNull(trace.hudState.value.allDoneTotalMs)

        // Cancel target_window while FRB build is still running
        nowMs += 50L
        trace.endStage("target_window", windowSince, detail = "canceled")

        assertEquals(1, trace.hudState.value.active.size)
        assertEquals("epub_frb_build", trace.hudState.value.active.single().id)
        assertNull(trace.hudState.value.allDoneTotalMs)
        assertEquals("canceled", trace.hudState.value.completed.single().detail)

        // Finish FRB build -> allDoneTotalMs is now populated
        nowMs += 350L
        trace.endStage("epub_frb_build", frbSince, count = 2000)

        assertTrue(trace.hudState.value.active.isEmpty())
        assertEquals(500L, trace.hudState.value.allDoneTotalMs)
        assertEquals(2, trace.hudState.value.completed.size)
        assertEquals(2000, trace.hudState.value.completed.last().count)

        // Starting another stage clears allDoneTotalMs again
        nowMs += 100L
        val nextSince = trace.beginStage("page_cache", "Page cache (.rpi)")
        assertNull(trace.hudState.value.allDoneTotalMs)

        nowMs += 40L
        trace.endStage("page_cache", nextSince, detail = "miss")
        assertEquals(640L, trace.hudState.value.allDoneTotalMs)
    }

    @Test
    fun legacyStageRecordsEnglishLabelAndCountInHud() {
        var nowMs = 2_000L
        val trace = ReaderLoadTrace(
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        val since = nowMs * 1_000_000L
        nowMs += 120L
        trace.stage("preview", since, count = 4, detail = "4 pg")

        val completed = trace.hudState.value.completed.single()
        assertEquals("preview", completed.id)
        assertEquals("Quick layout", completed.label)
        assertEquals(120L, completed.durationMs)
        assertEquals(4, completed.count)
    }

    @Test
    fun disabledHudPerformsNoHudStateUpdates() {
        var nowMs = 1_000L
        val trace = ReaderLoadTrace(
            bookFormat = "EPUB",
            trackHud = false,
            clockNanos = { nowMs * 1_000_000L },
        )

        val since = trace.beginStage("parse", "Parse book")
        trace.updateStage("parse", "10 ch")
        nowMs += 300L
        trace.endStage("parse", since, count = 100)
        trace.surfaceReady()

        val state = trace.hudState.value
        assertEquals("", state.bookFormat)
        assertTrue(state.active.isEmpty())
        assertTrue(state.completed.isEmpty())
        assertNull(state.surfaceReadyMs)
        assertNull(state.allDoneTotalMs)
        assertNull(state.initialOpenTotalMs)
        assertNull(state.lastActionDurationMs)
    }

    @Test
    fun matchesPresentedWindowKeyAcceptsBaseAndSourceSuffixedKeysAndRejectsMismatches() {
        val specKey = "1080x2400|18.0"
        val env = "light|ltr"

        assertTrue(matchesPresentedWindowKey("$specKey|window=0:45|environment=$env", specKey, env))
        assertTrue(matchesPresentedWindowKey("$specKey|window=12:89|environment=$env|source=1", specKey, env))
        assertTrue(matchesPresentedWindowKey("$specKey|window=0:10|environment=$env|source=3", specKey, env))

        assertFalse(matchesPresentedWindowKey(null, specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|prefix=40|environment=$env", specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|prefix=40|environment=$env|source=1", specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|environment=$env", specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|continuous|environment=$env|source=1", specKey, env))
        assertFalse(matchesPresentedWindowKey("other|window=0:45|environment=$env", specKey, env))
        assertFalse(matchesPresentedWindowKey("other|window=0:45|environment=$env|source=1", specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|window=0:45|environment=dark", specKey, env))
        assertFalse(matchesPresentedWindowKey("$specKey|window=0:45|environment=${env}2|source=1", specKey, env))
    }

    @Test
    fun pageIndexWaitHandOffToExactPageIndexKeepsHudActiveWithoutDoneGap() {
        var nowMs = 1_000L
        val trace = ReaderLoadTrace(
            bookFormat = "EPUB",
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        val waitSince = trace.beginStage("page_index_wait", "Window wait")
        assertEquals(listOf("page_index_wait"), trace.hudState.value.active.map { it.id })
        assertNull(trace.hudState.value.allDoneTotalMs)

        nowMs += 150L
        val scanSince = trace.beginStage("exact_page_index", "Book pagination", progress = "0/12 ch")
        trace.endStage("page_index_wait", waitSince, detail = null)

        assertEquals(listOf("exact_page_index"), trace.hudState.value.active.map { it.id })
        assertEquals("Book pagination", trace.hudState.value.active.single().label)
        assertNull(trace.hudState.value.allDoneTotalMs)

        nowMs += 300L
        trace.endStage("exact_page_index", scanSince, count = 80, detail = "80 pg")
        assertTrue(trace.hudState.value.active.isEmpty())
        assertEquals(450L, trace.hudState.value.allDoneTotalMs)
        assertEquals(80, trace.hudState.value.completed.last().count)
    }

    @Test
    fun initialOpenTotalMsFreezesAndPostOpenJumpTracksLastActionDuration() {
        var nowMs = 1_000L
        val trace = ReaderLoadTrace(
            bookFormat = "EPUB",
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        val scanSince = trace.beginStage("exact_page_index", "Book pagination")
        nowMs += 360L
        trace.surfaceReady()
        assertEquals(360L, trace.hudState.value.surfaceReadyMs)
        // Still paginating, so initialOpenTotalMs is not frozen yet
        assertNull(trace.hudState.value.initialOpenTotalMs)

        nowMs += 22_940L
        trace.endStage("exact_page_index", scanSince, count = 35_929, detail = "35929 pg")
        val afterOpen = trace.hudState.value
        assertEquals(23_300L, afterOpen.initialOpenTotalMs)
        assertNull(afterOpen.lastActionDurationMs)

        // User sits on a page for 3 minutes (175.6s) and then jumps via slider
        nowMs += 175_618L
        val jumpStartNanos = nowMs * 1_000_000L
        val jumpSince = trace.beginStage("target_window", "Chapter window", progress = "ch 240")
        assertEquals(jumpStartNanos, trace.hudState.value.batchStartedAtNanos)
        assertEquals(23_300L, trace.hudState.value.initialOpenTotalMs)

        nowMs += 15L
        val layoutSince = trace.beginStage("window_pagination", "Window layout")
        trace.endStage("target_window", jumpSince, count = 120, detail = "ch 240")
        // Still active (window_pagination running), lastActionDurationMs still null
        assertNull(trace.hudState.value.lastActionDurationMs)

        nowMs += 27L
        trace.endStage("window_pagination", layoutSince, count = 48, detail = "48 pg")

        val afterJump = trace.hudState.value
        assertEquals(23_300L, afterJump.initialOpenTotalMs)
        assertEquals(42L, afterJump.lastActionDurationMs)
    }

    @Test
    fun clearCompletedClearsHistoryAndLastActionWhilePreservingSummaryAndActiveStages() {
        var nowMs = 1_000L
        val trace = ReaderLoadTrace(
            bookFormat = "FB2",
            trackHud = true,
            clockNanos = { nowMs * 1_000_000L },
        )

        val openSince = trace.beginStage("parse", "Parse book")
        nowMs += 200L
        trace.surfaceReady()
        nowMs += 100L
        trace.endStage("parse", openSince, count = 500, detail = "12 ch")
        assertEquals(300L, trace.hudState.value.initialOpenTotalMs)

        nowMs += 5_000L
        val jumpSince = trace.beginStage("target_window", "Chapter window")
        nowMs += 35L
        trace.endStage("target_window", jumpSince, count = 60, detail = "ch 4")
        assertEquals(35L, trace.hudState.value.lastActionDurationMs)
        assertEquals(3, trace.hudState.value.completed.size)

        // Start a new active stage and clear completed history
        nowMs += 100L
        trace.beginStage("window_pagination", "Window layout")
        trace.clearCompleted()

        val cleared = trace.hudState.value
        assertEquals("FB2", cleared.bookFormat)
        assertEquals(200L, cleared.surfaceReadyMs)
        assertEquals(300L, cleared.initialOpenTotalMs)
        assertNull(cleared.lastActionDurationMs)
        assertTrue(cleared.completed.isEmpty())
        assertEquals(listOf("window_pagination"), cleared.active.map { it.id })
    }

    @Test
    fun hudFormattersRenderSubSecondMsAndThroughputRate() {
        assertEquals("0ms", formatHudDuration(0L))
        assertEquals("42ms", formatHudDuration(42L))
        assertEquals("999ms", formatHudDuration(999L))
        assertEquals("1.00s", formatHudDuration(1_000L))
        assertEquals("21.57s", formatHudDuration(21_570L))
        assertEquals("1,666", formatHudRate(35_929, 21_566L))
    }
}
