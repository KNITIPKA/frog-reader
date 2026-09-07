package com.example.frogreader.reader

import androidx.compose.runtime.MonotonicFrameClock
import com.example.frogreader.ui.reader.ReaderPanelMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPanelMotionTest {
    private fun TestScope.panel(): ReaderPanelMotion {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return ReaderPanelMotion(CoroutineScope(coroutineContext + clock))
    }

    @Test
    fun `dragging fully closed allows the very next button press to open`() = runTest {
        val panel = panel()
        panel.animateTo(400f)
        advanceUntilIdle()
        panel.startDrag()
        panel.snapTo(0f) // Includes nested-scroll reaching its zero anchor exactly.
        advanceUntilIdle()

        assertFalse(panel.isOpen)
        if (!panel.isOpen) panel.animateTo(400f) else panel.animateTo(0f)
        assertTrue(panel.isOpen)
        advanceUntilIdle()
        assertEquals(400f, panel.height.value, 0.5f)
    }

    @Test
    fun `closing intent takes effect immediately even while the panel is still tall`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        panel.animateTo(0f)

        assertEquals(400f, panel.height.value, 0.5f)
        assertFalse(panel.isOpen)
        // A second press during closing reverses the motion instead of closing again.
        panel.animateTo(400f)
        advanceUntilIdle()
        assertTrue(panel.isOpen)
        assertEquals(400f, panel.height.value, 0.5f)
    }

    @Test
    fun `late settings measurement cannot reopen a panel after a chapter was selected`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        panel.animateTo(0f)
        panel.updatePeek(previous = 400f, next = 460f)
        advanceUntilIdle()

        assertFalse(panel.isOpen)
        assertEquals(0f, panel.height.value, 0.5f)
    }

    @Test
    fun `settings measurement resizes only an open peek and preserves full height`() = runTest {
        val panel = panel()
        panel.animateTo(400f)
        panel.updatePeek(previous = 400f, next = 460f)
        advanceUntilIdle()
        assertEquals(460f, panel.height.value, 0.5f)

        panel.animateTo(800f)
        panel.updatePeek(previous = 460f, next = 500f)
        advanceUntilIdle()
        assertEquals(800f, panel.height.value, 0.5f)
    }
}
