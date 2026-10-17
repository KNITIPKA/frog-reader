package com.example.frogreader.reader

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.example.frogreader.ui.reader.ReaderPanelMotion
import com.example.frogreader.ui.reader.ReaderPanelNestedScrollConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
class ReaderPanelGesturesTest {
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
    fun `upward drag expands before scrolling and passes boundary remainder to the list`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        val connection = ReaderPanelNestedScrollConnection(panel, 800f) {}
        assertEquals(Offset(0f, -100f), connection.onPreScroll(Offset(12f, -100f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, -100f), connection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput))
        assertEquals(600f, panel.targetHeight, 0f)
        assertEquals(Offset(0f, -200f), connection.onPreScroll(Offset(0f, -350f), NestedScrollSource.UserInput))
        assertEquals(800f, panel.targetHeight, 0f)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -80f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, -1900f)))
        assertFalse(panel.isDragging)
        advanceUntilIdle()
        assertEquals(800f, panel.height.value, 0f)
    }

    @Test
    fun `downward drag scrolls to the top before shrinking the drawer`() = runTest {
        val panel = panel()
        panel.snapTo(800f)
        advanceUntilIdle()
        val connection = ReaderPanelNestedScrollConnection(panel, 800f) {}
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, 80f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, 80f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(800f, panel.targetHeight, 0f)
        assertEquals(Offset(0f, 50f), connection.onPostScroll(Offset(0f, 30f), Offset(7f, 50f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, 40f), connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput))
        advanceUntilIdle()
        assertEquals(710f, panel.height.value, 0f)
    }

    @Test
    fun `fling updates cannot resize or reopen a closing drawer`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        val settlements = mutableListOf<Float>()
        val connection = ReaderPanelNestedScrollConnection(panel, 800f, settlements::add)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -200f), NestedScrollSource.SideEffect))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(100f, -1000f)))
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity(0f, 600f), Velocity(0f, 400f)))
        assertEquals(400f, panel.targetHeight, 0f)
        assertTrue(settlements.isEmpty())
        panel.animateTo(0f)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -200f), NestedScrollSource.UserInput))
        advanceUntilIdle()
        assertFalse(panel.isOpen)
    }

    @Test
    fun `partial drag settles exactly once including slow release and post fling fallback`() = runTest {
        for (postOnly in listOf(false, true)) {
            val panel = panel()
            panel.snapTo(400f)
            advanceUntilIdle()
            val settlements = mutableListOf<Float>()
            val connection = ReaderPanelNestedScrollConnection(panel, 800f) {
                settlements += it
                panel.animateTo(400f)
            }
            connection.onPreScroll(Offset(0f, -70f), NestedScrollSource.UserInput)
            if (!postOnly) assertEquals(Velocity.Zero, connection.onPreFling(Velocity.Zero))
            val velocity = if (postOnly) Velocity(75f, -500f) else Velocity.Zero
            assertEquals(Velocity(0f, velocity.y), connection.onPostFling(Velocity.Zero, velocity))
            assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, velocity))
            assertEquals(listOf(velocity.y), settlements)
            advanceUntilIdle()
            assertEquals(400f, panel.height.value, 0.5f)
        }
    }

    @Test
    fun `new content drag interrupts animation at its visible height`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        panel.animateTo(800f)
        advanceTimeBy(64)
        runCurrent()
        val visible = panel.height.value
        assertTrue(visible > 400f && visible < 800f)
        val connection = ReaderPanelNestedScrollConnection(panel, 800f) {}
        connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 20f), NestedScrollSource.UserInput)
        advanceUntilIdle()
        assertEquals(visible - 50f, panel.height.value, 0.01f)
    }

    @Test
    fun `late peek measurement cannot interrupt drag and new bounds and callbacks take effect`() = runTest {
        val panel = panel()
        panel.snapTo(400f)
        advanceUntilIdle()
        val oldSettlements = mutableListOf<Float>()
        val newSettlements = mutableListOf<Float>()
        val connection = ReaderPanelNestedScrollConnection(panel, 800f, oldSettlements::add)
        connection.onPreScroll(Offset(0f, -0.5f), NestedScrollSource.UserInput)
        panel.updatePeek(400f, 460f)
        assertEquals(400.5f, panel.targetHeight, 0f)
        connection.update(900f, newSettlements::add)
        connection.onPreScroll(Offset(0f, -450f), NestedScrollSource.UserInput)
        assertEquals(850.5f, panel.targetHeight, 0f)
        connection.onPreFling(Velocity.Zero)
        assertTrue(oldSettlements.isEmpty())
        assertEquals(listOf(0f), newSettlements)
        assertFalse(panel.isDragging)
        advanceUntilIdle()
    }

    @Test
    fun `pulling past closed consumes only distance to the zero anchor`() = runTest {
        val panel = panel()
        panel.snapTo(40f)
        advanceUntilIdle()
        val connection = ReaderPanelNestedScrollConnection(panel, 800f) {}
        assertEquals(Offset(0f, 40f), connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput))
        assertEquals(0f, panel.targetHeight, 0f)
        advanceUntilIdle()
        assertFalse(panel.isOpen)
    }
}
