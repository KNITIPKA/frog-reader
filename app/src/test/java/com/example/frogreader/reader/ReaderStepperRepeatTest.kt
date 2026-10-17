package com.example.frogreader.reader

import com.example.frogreader.ui.reader.repeatReaderStepper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStepperRepeatTest {
    @Test
    fun `cancelling before the next tick does not add a delayed step`() = runTest {
        var steps = 0
        val held = launch { repeatReaderStepper { steps++ } }
        runCurrent()
        advanceTimeBy(99)
        held.cancelAndJoin()
        advanceTimeBy(1_000)
        assertEquals(0, steps)
    }

    @Test
    fun `holding accelerates without increasing the size of each step`() = runTest {
        var margin = 8
        val values = mutableListOf<Int>()
        val held = launch {
            repeatReaderStepper {
                margin += 2
                values += margin
            }
        }
        runCurrent()
        advanceTimeBy(501)
        val initialSteps = values.size
        advanceTimeBy(600)
        val acceleratedSteps = values.size - initialSteps
        assertTrue(initialSteps > 0)
        assertTrue(acceleratedSteps > initialSteps)
        assertTrue(values.zipWithNext().all { (before, after) -> after - before == 2 })
        held.cancelAndJoin()
    }

    @Test
    fun `release or scroll cancellation stops all pending repeats`() = runTest {
        var steps = 0
        val held = launch { repeatReaderStepper { steps++ } }
        advanceTimeBy(400)
        held.cancelAndJoin()
        val atRelease = steps
        advanceTimeBy(2_000)
        assertTrue(atRelease > 0)
        assertEquals(atRelease, steps)
    }

    @Test
    fun `a new hold starts at the gentle speed again`() = runTest {
        var steps = 0
        val first = launch { repeatReaderStepper { steps++ } }
        advanceTimeBy(1_000)
        first.cancelAndJoin()
        val atRelease = steps
        val second = launch { repeatReaderStepper { steps++ } }
        advanceTimeBy(99)
        assertEquals(atRelease, steps)
        advanceTimeBy(2)
        assertEquals(atRelease + 1, steps)
        second.cancelAndJoin()
    }
}
