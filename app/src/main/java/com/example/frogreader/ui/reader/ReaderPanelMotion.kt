package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One target for taps, drags and content measurement; geometry is never the toggle state. */
internal class ReaderPanelMotion(private val scope: CoroutineScope) {
    val height = Animatable(0f)
    var targetHeight by mutableFloatStateOf(0f)
        private set
    val isOpen: Boolean get() = targetHeight > 0.5f
    private var job: Job? = null

    fun animateTo(target: Float) {
        job?.cancel()
        targetHeight = target.coerceAtLeast(0f)
        val destination = targetHeight
        job = scope.launch { height.animateTo(destination) }
    }

    fun snapTo(target: Float) {
        job?.cancel()
        targetHeight = target.coerceAtLeast(0f)
        val destination = targetHeight
        job = scope.launch { height.snapTo(destination) }
    }

    fun startDrag() {
        job?.cancel()
        targetHeight = height.value
    }

    /** A late measurement may resize an open peek, but must never reopen a closing panel. */
    fun updatePeek(previous: Float, next: Float) {
        if (isOpen && abs(targetHeight - previous) < 1f) animateTo(next)
    }
}
