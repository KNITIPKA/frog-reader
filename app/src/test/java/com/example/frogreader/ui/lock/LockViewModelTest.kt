package com.example.frogreader.ui.lock

import com.example.frogreader.data.AppLockDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockViewModelTest {

    @Test
    fun `delay values are exact`() {
        assertEquals(0L, AppLockDelay.IMMEDIATE.durationMillis)
        assertEquals(60_000L, AppLockDelay.ONE_MINUTE.durationMillis)
        assertEquals(900_000L, AppLockDelay.FIFTEEN_MINUTES.durationMillis)
    }

    @Test
    fun `first start cannot relock before the app has stopped`() {
        var now = 90_000L
        val viewModel = LockViewModel { now }
        viewModel.unlocked = true

        viewModel.onAppStarted(AppLockDelay.IMMEDIATE)

        assertTrue(viewModel.unlocked)
    }

    @Test
    fun `immediate relocks on the first return after stop`() {
        var now = 1_000L
        val viewModel = LockViewModel { now }
        viewModel.unlocked = true
        viewModel.onAppStopped()

        viewModel.onAppStarted(AppLockDelay.IMMEDIATE)

        assertFalse(viewModel.unlocked)
    }

    @Test
    fun `one minute relocks at the exact boundary but not before`() {
        var now = 10_000L
        val beforeBoundary = LockViewModel { now }
        beforeBoundary.unlocked = true
        beforeBoundary.onAppStopped()
        now += 59_999L

        beforeBoundary.onAppStarted(AppLockDelay.ONE_MINUTE)

        assertTrue(beforeBoundary.unlocked)

        now = 10_000L
        val atBoundary = LockViewModel { now }
        atBoundary.unlocked = true
        atBoundary.onAppStopped()
        now += 60_000L

        atBoundary.onAppStarted(AppLockDelay.ONE_MINUTE)

        assertFalse(atBoundary.unlocked)
    }

    @Test
    fun `fifteen minutes uses its exact duration`() {
        var now = 50_000L
        val viewModel = LockViewModel { now }
        viewModel.unlocked = true
        viewModel.onAppStopped()
        now += 900_000L

        viewModel.onAppStarted(AppLockDelay.FIFTEEN_MINUTES)

        assertFalse(viewModel.unlocked)
    }
}
