package com.example.frogreader.reader

import androidx.compose.runtime.MonotonicFrameClock
import com.example.frogreader.ui.reader.ReaderOpeningMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOpeningMotionTest {
    private fun TestScope.frameClock() = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(testScheduler.currentTime * 1_000_000)
        }
    }

    @Test
    fun `loader stays visible until the page reports its restored layout`() = runTest {
        val motion = ReaderOpeningMotion()
        launch(frameClock()) { motion.play() }
        advanceUntilIdle()

        assertEquals(1f, motion.loaderScale.value, 0f)
        assertEquals(0f, motion.reveal.value, 0f)
        assertFalse(motion.finished)
    }

    @Test
    fun `loader collapses fully before text expands and both stages finish`() = runTest {
        val motion = ReaderOpeningMotion()
        motion.onContentReady()
        launch(frameClock()) { motion.play() }

        advanceTimeBy(128)
        runCurrent()
        assertTrue(motion.loaderScale.value in 0f..1f)
        assertTrue(motion.loaderScale.value < 1f && motion.loaderScale.value > 0f)
        assertEquals(0f, motion.reveal.value, 0f)

        advanceTimeBy(240)
        runCurrent()
        assertEquals(0f, motion.loaderScale.value, 0f)
        assertTrue(motion.reveal.value > 0f && motion.reveal.value < 1f)
        assertFalse(motion.finished)

        advanceUntilIdle()
        assertEquals(1f, motion.reveal.value, 0f)
        assertTrue(motion.finished)
    }

    @Test
    fun `later layout callbacks cannot replay the loader or hide the text`() = runTest {
        val motion = ReaderOpeningMotion()
        motion.onContentReady()
        launch(frameClock()) { motion.play() }
        advanceUntilIdle()

        motion.onContentReady()
        launch(frameClock()) { motion.play() }
        runCurrent()
        assertEquals(0f, motion.loaderScale.value, 0f)
        assertEquals(1f, motion.reveal.value, 0f)
        assertTrue(motion.finished)
    }

    @Test
    fun `restoring an already opened screen shows the entire page immediately`() {
        val motion = ReaderOpeningMotion(initiallyFinished = true)
        assertEquals(0f, motion.loaderScale.value, 0f)
        assertEquals(1f, motion.reveal.value, 0f)
        assertTrue(motion.finished)
    }
}
