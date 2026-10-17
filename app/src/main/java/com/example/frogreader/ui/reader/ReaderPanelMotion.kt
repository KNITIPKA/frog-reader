package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
    var isDragging = false
        private set
    private var job: Job? = null
    private var backStartHeight: Float? = null
    private var backRestoreHeight = 0f

    fun animateTo(target: Float) {
        isDragging = false
        backStartHeight = null
        job?.cancel()
        targetHeight = target.coerceAtLeast(0f)
        val destination = targetHeight
        job = scope.launch {
            if (destination == 0f) height.animateTo(0f, tween(180, easing = LinearOutSlowInEasing))
            else height.animateTo(destination)
        }
    }

    fun snapTo(target: Float) {
        isDragging = false
        snapHeightTo(target)
    }

    private fun snapHeightTo(target: Float) {
        backStartHeight = null
        job?.cancel()
        targetHeight = target.coerceAtLeast(0f)
        val destination = targetHeight
        job = scope.launch { height.snapTo(destination) }
    }

    fun startDrag() {
        isDragging = true
        backStartHeight = null
        job?.cancel()
        targetHeight = height.value
    }

    /** Accumulate every pointer delta, even when several arrive before the next frame. */
    fun dragBy(delta: Float, maxHeight: Float): Float {
        if (!delta.isFinite()) return 0f
        val start = targetHeight
        val destination = (start - delta).coerceIn(0f, maxHeight.coerceAtLeast(0f))
        snapHeightTo(destination)
        return start - destination
    }

    fun finishDrag() {
        isDragging = false
    }

    /** Keep the drawer's open intent until the system gesture is committed. */
    fun beginBack() {
        isDragging = false
        job?.cancel()
        backStartHeight = height.value
        backRestoreHeight = targetHeight
    }

    suspend fun seekBack(progress: Float) {
        val start = backStartHeight ?: return
        val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        height.snapTo(start * (1f - fraction))
    }

    fun finishBack(committed: Boolean) {
        if (backStartHeight == null) return
        val destination = if (committed) 0f else backRestoreHeight
        animateTo(destination)
    }

    /** A late measurement may resize an open peek, but must never reopen a closing panel. */
    fun updatePeek(previous: Float, next: Float) {
        if (!isDragging && backStartHeight == null && isOpen && abs(targetHeight - previous) < 1f) animateTo(next)
    }
}
