package com.example.frogreader.ui

import com.example.frogreader.ui.nav.TabSwitchController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabSwitchControllerTest {
    @Test fun repeatedTapKeepsTheTransitionAlreadyInFlight() = runTest {
        val starts = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val controller = TabSwitchController(this) { page ->
            starts += page
            delay(300)
            completed += page
        }
        controller.select(1)
        runCurrent()
        repeat(10) { controller.select(1) }
        advanceUntilIdle()
        assertEquals(listOf(1), starts)
        assertEquals(listOf(1), completed)
    }

    @Test fun backOrOppositeTabCancelsPreviousDestination() = runTest {
        val cancelled = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val controller = TabSwitchController(this) { page ->
            try {
                delay(300)
                completed += page
            } catch (e: CancellationException) {
                cancelled += page
                throw e
            }
        }
        controller.select(1)
        runCurrent()
        controller.select(0)
        advanceUntilIdle()
        assertEquals(listOf(1), cancelled)
        assertEquals(listOf(0), completed)
    }

    @Test fun rapidAlternatingTapsFinishAtTheLastRequestedPage() = runTest {
        val completed = mutableListOf<Int>()
        val controller = TabSwitchController(this) { page ->
            delay(300)
            completed += page
        }
        repeat(20) { index ->
            controller.select(index % 2)
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(listOf(1), completed)
    }

    @Test fun gestureCancellationDoesNotPreventRetryingTheSameTab() = runTest {
        var attempts = 0
        val completed = mutableListOf<Int>()
        val controller = TabSwitchController(this) { page ->
            attempts++
            if (attempts == 1) throw CancellationException("Interrupted by user drag")
            completed += page
        }
        controller.select(1)
        runCurrent()
        controller.select(1)
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(listOf(1), completed)
    }
}
