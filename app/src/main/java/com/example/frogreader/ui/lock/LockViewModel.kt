package com.example.frogreader.ui.lock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.frogreader.data.AppLockDelay

/**
 * Holds the unlocked flag across configuration changes (dies with the
 * process, so a fresh app start is always locked).
 */
class LockViewModel(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    var unlocked by mutableStateOf(false)
    private var lastStoppedAtMillis: Long? = null

    /** Starts one background interval; a later ON_START consumes it once. */
    fun onAppStopped() {
        lastStoppedAtMillis = clockMillis()
    }

    fun onAppStarted(delay: AppLockDelay) {
        val stoppedAt = lastStoppedAtMillis ?: return
        lastStoppedAtMillis = null
        if (
            unlocked &&
            (delay == AppLockDelay.IMMEDIATE || clockMillis() - stoppedAt >= delay.durationMillis)
        ) {
            unlocked = false
        }
    }
}
