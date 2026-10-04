package com.example.frogreader.ui

import com.example.frogreader.ui.library.LibraryDockScrollState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDockScrollStateTest {
    private fun state() = LibraryDockScrollState(hideThreshold = 20f, showThreshold = 12f)

    @Test fun dragHidesAndReverseDragRestoresWithoutAnyContentMovement() {
        val dock = state()
        assertTrue(dock.onDrag(-9f))
        assertFalse(dock.onDrag(-11f))
        dock.endGesture()
        assertFalse(dock.onDrag(5f))
        assertTrue(dock.onDrag(7f))
    }

    @Test fun smallDirectionChangesDoNotFlicker() {
        val dock = state()
        repeat(20) {
            assertTrue(dock.onDrag(-8f))
            assertTrue(dock.onDrag(8f))
        }
        assertFalse(dock.onDrag(-20f))
        repeat(20) {
            assertFalse(dock.onDrag(5f))
            assertFalse(dock.onDrag(-5f))
        }
    }

    @Test fun longForwardDragDoesNotDelayRevealAfterReversing() {
        val dock = state()
        repeat(100) { dock.onDrag(-50f) }
        assertTrue(dock.onDrag(12f))
    }

    @Test fun separateShortGesturesDoNotAccumulate() {
        val dock = state()
        repeat(10) {
            assertTrue(dock.onDrag(-10f))
            dock.endGesture()
        }
    }

    @Test fun returningFromOverlayOrAnotherTabResetsIntent() {
        val dock = state()
        dock.onDrag(-30f)
        dock.reset()
        assertTrue(dock.visible)
        assertTrue(dock.onDrag(-10f))
    }
}
