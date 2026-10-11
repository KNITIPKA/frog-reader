package com.example.frogreader.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReadingLogPageMotionTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun backDuringEntranceReversesFromTheDisplayedFrameAndFinishes() = runTest {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000L)
            }
        }
        val progress = Animatable(0f)
        val opening = launch(clock) {
            progress.animateTo(1f, tween(ReadingLogPageEnterMillis, easing = FastOutSlowInEasing))
        }
        advanceTimeBy(112)
        runCurrent()
        val displayed = progress.value
        assertTrue(displayed > 0f && displayed < 1f)
        opening.cancelAndJoin()
        launch(clock) {
            progress.animateTo(0f, tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing))
        }
        runCurrent()
        assertEquals(displayed, progress.value, 0f)
        advanceUntilIdle()
        assertEquals(0f, progress.value, 0f)
        assertFalse(readingLogPagePresentation(false, progress.value).blocksList)
    }

    @Test fun openingBlocksTheListBeforeTheFirstAnimationFrame() {
        val state = readingLogPagePresentation(editorPresent = true, progress = 0f)
        assertTrue(state.pageMounted)
        assertTrue(state.blocksList)
        assertFalse(state.pageInteractive)
        assertFalse(state.fabVisible)
    }

    @Test fun saveOrBackRetainsTheOutgoingPageAndDefersFabAppearance() {
        val start = readingLogPagePresentation(editorPresent = false, progress = 1f)
        val middle = readingLogPagePresentation(editorPresent = false, progress = .5f)
        for (state in listOf(start, middle)) {
            assertTrue(state.pageMounted)
            assertTrue(state.blocksList)
            assertFalse(state.pageInteractive)
            assertFalse(state.fabVisible)
        }
    }

    @Test fun fabCanFadeInLateInTheExitButCannotReceiveAnotherAdd() {
        val state = readingLogPagePresentation(editorPresent = false, progress = .2f)
        assertTrue(state.fabVisible)
        assertTrue(state.blocksList)
        assertFalse(state.pageInteractive)
    }

    @Test fun exitReleasesTheListWithoutAnInvisiblePageInterceptingIt() {
        val state = readingLogPagePresentation(editorPresent = false, progress = 0f)
        assertFalse(state.pageMounted)
        assertFalse(state.blocksList)
        assertTrue(state.fabVisible)
    }

    @Test fun reversingAnExitHidesTheFabAndDoesNotJumpThePageGeometry() {
        val exiting = readingLogPagePresentation(editorPresent = false, progress = .2f)
        val reopening = readingLogPagePresentation(editorPresent = true, progress = .2f)
        assertEquals(exiting.pageScale, reopening.pageScale, 0f)
        assertEquals(exiting.progress, reopening.progress, 0f)
        assertFalse(reopening.fabVisible)
        assertTrue(reopening.blocksList)
        assertFalse(reopening.pageInteractive)
    }

    @Test fun stableEditorEnablesFieldsWithoutRevealingTheFab() {
        val state = readingLogPagePresentation(editorPresent = true, progress = 1f)
        assertTrue(state.pageInteractive)
        assertFalse(state.fabVisible)
        assertEquals(1f, state.pageScale, 0f)
    }
}
