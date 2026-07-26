package com.example.frogreader.ui.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Holds the unlocked flag across configuration changes (dies with the
 * process, so a fresh app start is always locked).
 */
class LockViewModel : ViewModel() {
    var unlocked by mutableStateOf(false)
    var lastStoppedAtMillis: Long = 0L

    /** Re-lock only after the app has been in background for a while. */
    fun onAppStopped() {
        lastStoppedAtMillis = System.currentTimeMillis()
    }

    fun onAppStarted() {
        if (unlocked && System.currentTimeMillis() - lastStoppedAtMillis > RELOCK_AFTER_MILLIS) {
            unlocked = false
        }
    }

    private companion object {
        const val RELOCK_AFTER_MILLIS = 30_000L
    }
}
