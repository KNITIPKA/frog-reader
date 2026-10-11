package com.example.frogreader.reader

import androidx.compose.foundation.interaction.DragInteraction
import com.example.frogreader.ui.reader.ReaderProgressCaptionIdleMillis
import com.example.frogreader.ui.reader.ReaderProgressCaptionMotion
import com.example.frogreader.ui.reader.ReaderProgressCaptionPolicy
import com.example.frogreader.ui.reader.ReaderProgressCaptionBlurSteps
import com.example.frogreader.ui.reader.ReaderSeekInteractionSource
import com.example.frogreader.ui.reader.readerProgressCaptionBlurIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderProgressCaptionTest {
    @Test
    fun `motion blur switches off at rest and remains active during sustained movement`() {
        for (amount in listOf(0f, 0.005f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0, readerProgressCaptionBlurIndex(amount))
        }
        assertTrue(readerProgressCaptionBlurIndex(0.5f) > 0)
        assertEquals(ReaderProgressCaptionBlurSteps, readerProgressCaptionBlurIndex(1f))
        assertEquals(ReaderProgressCaptionBlurSteps, readerProgressCaptionBlurIndex(2f))
    }

    @Test
    fun `continuous motion uses only the bounded blur levels`() {
        val visited = mutableSetOf<Int>()
        repeat(100) {
            for (step in 0..100) {
                val amount = step / 100f
                val index = readerProgressCaptionBlurIndex(amount)
                assertTrue(index in 0..ReaderProgressCaptionBlurSteps)
                assertEquals(index, readerProgressCaptionBlurIndex(amount))
                visited += index
            }
        }
        assertEquals((0..ReaderProgressCaptionBlurSteps).toSet(), visited)
    }

    @Test
    fun `fast directional travel across chapters enables blur after a short motion window`() {
        val policy = ReaderProgressCaptionPolicy()
        assertEquals(0f, policy.update(0.10f, 30f, 0, 0), 0f)
        assertEquals(0f, policy.update(0.14f, 42f, 0, 16), 0f)
        assertTrue(policy.update(0.18f, 54f, 1, 32) > 0f)
        assertTrue(policy.update(0.22f, 66f, 2, 48) > 0f)
    }

    @Test
    fun `detection uses speed rather than event frequency`() {
        for (interval in listOf(33L, 16L, 8L, 4L)) {
            val policy = ReaderProgressCaptionPolicy()
            for (step in 0..(160L / interval).toInt()) {
                val time = step * interval
                val fraction = 0.1f + time / 1000f
                val amount = policy.update(fraction, fraction * 300f, (fraction * 40).toInt(), time)
                if (time >= 48L) assertTrue("interval=$interval, time=$time", amount > 0f)
            }
        }
    }

    @Test
    fun `fast movement entirely within one chapter stays clear`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..20) {
            assertEquals(0f, policy.update(0.1f + step * 0.03f, step * 9f, 0, step * 16L), 0f)
        }
    }

    @Test
    fun `slow finger stays clear even when compressed chapters change many pages`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..20) {
            assertEquals(0f, policy.update(step * 0.04f, step * 0.5f, step, step * 16L), 0f)
        }
    }

    @Test
    fun `slow travel across the book never accumulates enough old evidence to blur`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..100) {
            assertEquals(0f, policy.update(step / 100f, step * 3f, step / 2, step * 50L), 0f)
        }
    }

    @Test
    fun `expanded small chapters stay clear despite fast finger movement`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..6) {
            assertEquals(0f, policy.update(0.1f + step * 0.001f, step * 12f, step / 2, step * 16L), 0f)
        }
    }

    @Test
    fun `tiny corrections at chapter boundaries do not flash blur`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..100) {
            val fraction = if (step % 2 == 0) 0.399f else 0.401f
            assertEquals(0f, policy.update(fraction, fraction * 300f, step % 2, step.toLong()), 0f)
        }
    }

    @Test
    fun `blur clears on slowing even before the preview leaves the current page`() {
        val policy = burst()
        assertEquals(0f, policy.update(0.18f, 54.3f, 1, 48), 0f)
        assertEquals(0f, policy.blurAmount, 0f)
    }

    @Test
    fun `direction reversal clears old motion and a new sweep can activate independently`() {
        val policy = ReaderProgressCaptionPolicy()
        policy.update(0.50f, 150f, 5, 0)
        policy.update(0.54f, 162f, 5, 16)
        assertTrue(policy.update(0.58f, 174f, 6, 32) > 0f)
        assertEquals(0f, policy.update(0.54f, 162f, 5, 48), 0f)
        policy.update(0.50f, 150f, 5, 64)
        assertTrue(policy.update(0.46f, 138f, 4, 80) > 0f)
    }

    @Test
    fun `old chapter crossings expire when fast travel continues inside a long chapter`() {
        val policy = burst()
        assertTrue(policy.update(0.20f, 60f, 1, 48) > 0f)
        for (step in 2..10) {
            policy.update(0.18f + step * 0.02f, 54f + step * 6f, 1, 32 + step * 16L)
        }
        assertEquals(0f, policy.blurAmount, 0f)
    }

    @Test
    fun `idle timeout clears blur and a resumed gesture starts with fresh evidence`() {
        val policy = burst()
        assertTrue(policy.settle(32 + ReaderProgressCaptionIdleMillis - 1) > 0f)
        assertEquals(0f, policy.settle(32 + ReaderProgressCaptionIdleMillis), 0f)
        assertEquals(0f, policy.update(0.22f, 66f, 2, 97), 0f)
    }

    @Test
    fun `a late motion event clears the burst without waiting for its idle callback`() {
        val policy = burst()
        assertEquals(0f, policy.update(0.22f, 66f, 2, 32 + ReaderProgressCaptionIdleMillis), 0f)
    }

    @Test
    fun `duplicate event timestamps cannot manufacture a burst`() {
        val policy = ReaderProgressCaptionPolicy()
        for (step in 0..10) {
            assertEquals(0f, policy.update(step / 10f, step * 30f, step, 100), 0f)
        }
    }

    @Test
    fun `invalid motion and backwards timestamps clear the active effect`() {
        val invalid = listOf(Triple(Float.NaN, 70f, 2), Triple(0.22f, Float.POSITIVE_INFINITY, 2), Triple(0.22f, 70f, -1))
        for ((book, track, chapter) in invalid) {
            val policy = burst()
            assertEquals(0f, policy.update(book, track, chapter, 48), 0f)
        }
        assertEquals(0f, burst().update(0.22f, 66f, 2, 31), 0f)
    }

    @Test
    fun `intensity follows velocity and remains bounded`() {
        fun amount(speed: Float): Float {
            val policy = ReaderProgressCaptionPolicy()
            policy.update(0.10f, 0f, 0, 0)
            policy.update(0.13f, speed * 0.024f, 1, 24)
            return policy.update(0.16f, speed * 0.048f, 2, 48)
        }
        val moderate = amount(300f)
        val fast = amount(900f)
        assertTrue(moderate > 0f)
        assertTrue(fast > moderate)
        assertEquals(1f, amount(2000f), 0f)
    }

    @Test
    fun `release clears observable layer state synchronously without running a coroutine`() = runTest {
        val motion = ReaderProgressCaptionMotion(this) { testScheduler.currentTime }
        motion.update(0.10f, 30f, 0)
        advanceTimeBy(16)
        motion.update(0.14f, 42f, 0)
        advanceTimeBy(16)
        motion.update(0.18f, 54f, 1)
        assertTrue(motion.blurAmount > 0f)

        motion.reset()
        assertEquals(0f, motion.blurAmount, 0f)
        motion.update(0.22f, 66f, 2)
        assertEquals(0f, motion.blurAmount, 0f)
        advanceUntilIdle()
        assertEquals(0f, motion.blurAmount, 0f)
    }

    @Test
    fun `sustained input extends the idle deadline and holding still clears automatically`() = runTest {
        val motion = ReaderProgressCaptionMotion(this) { testScheduler.currentTime }
        for (step in 0..15) {
            if (step > 0) advanceTimeBy(16)
            motion.update(0.1f + step * 0.03f, 30f + step * 9f, step / 2)
            runCurrent()
            if (step >= 2) assertTrue(motion.blurAmount > 0f)
        }
        advanceTimeBy(ReaderProgressCaptionIdleMillis - 1)
        runCurrent()
        assertTrue(motion.blurAmount > 0f)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(0f, motion.blurAmount, 0f)
    }

    @Test
    fun `a cancelled old idle watcher cannot clear a newly started burst`() = runTest {
        val motion = ReaderProgressCaptionMotion(this) { testScheduler.currentTime }
        for (step in 0..2) {
            advanceTimeBy(16)
            motion.update(0.1f + step * 0.04f, 30f + step * 12f, step / 2)
            runCurrent()
        }
        motion.reset()
        for (step in 0..6) {
            advanceTimeBy(16)
            motion.update(0.3f + step * 0.04f, 90f + step * 12f, 3 + step / 2)
            runCurrent()
            if (step >= 2) assertTrue(motion.blurAmount > 0f)
        }
        motion.reset()
        advanceUntilIdle()
        assertEquals(0f, motion.blurAmount, 0f)
    }

    @Test
    fun `drag stop and cancellation clear the effect before interaction collectors run`() = runTest {
        for (cancel in listOf(false, true)) {
            val motion = ReaderProgressCaptionMotion(this) { testScheduler.currentTime }
            var cancelled = false
            val source = ReaderSeekInteractionSource(
                onCancel = { cancelled = true; motion.reset() },
                onStop = motion::reset,
            )
            assertFalse(source.isDragging)
            val start = DragInteraction.Start()
            source.emit(start)
            assertTrue(source.isDragging)
            for (step in 0..2) {
                advanceTimeBy(16)
                motion.update(0.1f + step * 0.04f, 30f + step * 12f, step / 2)
            }
            assertTrue(motion.blurAmount > 0f)
            if (cancel) source.emit(DragInteraction.Cancel(start))
            else source.tryEmit(DragInteraction.Stop(start))
            assertEquals(0f, motion.blurAmount, 0f)
            assertFalse(source.isDragging)
            assertEquals(cancel, cancelled)
        }
        advanceUntilIdle()
    }

    private fun burst(): ReaderProgressCaptionPolicy = ReaderProgressCaptionPolicy().apply {
        update(0.10f, 30f, 0, 0)
        update(0.14f, 42f, 0, 16)
        assertTrue(update(0.18f, 54f, 1, 32) > 0f)
    }
}
