@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.frogreader.ui.reader.pdf

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Observes a deliberate horizontal swipe without consuming AndroidX zoom, pan or selection.
 * The caller enables page turns only while the native page is at its fitted zoom.
 */
@Composable
internal fun Modifier.pdfSinglePageSwipe(
    enabled: Boolean,
    selectionActive: Boolean,
    onTurnPage: (Int) -> Unit,
): Modifier {
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val gesture = remember(distance, longPressTimeout) {
        PdfSinglePageSwipeGesture(distance, minOf(700L, longPressTimeout))
    }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelectionActive by rememberUpdatedState(selectionActive)
    val currentTurnPage by rememberUpdatedState(onTurnPage)
    // Selection or zoom can change between native events. Once ineligible, this press stays so.
    SideEffect { if (!enabled || selectionActive) gesture.cancel() }
    DisposableEffect(gesture) { onDispose { gesture.cancel() } }
    return motionEventSpy { event ->
        val canTurn = currentEnabled && !currentSelectionActive
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesture.begin(event.x, event.y, event.eventTime, canTurn)
            MotionEvent.ACTION_MOVE -> gesture.update(event.x, event.y, event.eventTime, event.pointerCount, canTurn)
            MotionEvent.ACTION_UP -> gesture.end(event.x, event.y, event.eventTime, canTurn)?.let(currentTurnPage)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> gesture.cancel()
        }
    }
}

/** Physical PDF order stays the same in every UI language: swipe left for the next page. */
internal class PdfSinglePageSwipeGesture(
    private val minimumDistancePx: Float,
    private val maximumDurationMillis: Long,
) {
    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var startedAt = 0L

    fun begin(x: Float, y: Float, eventTime: Long, canTurn: Boolean) {
        active = canTurn && x.isFinite() && y.isFinite()
        startX = x
        startY = y
        startedAt = eventTime
    }

    fun update(x: Float, y: Float, eventTime: Long, pointerCount: Int, canTurn: Boolean) {
        if (!active) return
        val elapsed = eventTime - startedAt
        if (!canTurn || pointerCount != 1 || !x.isFinite() || !y.isFinite() ||
            elapsed < 0L || elapsed >= maximumDurationMillis) {
            cancel()
            return
        }
        // A vertical pan must not turn into a page swipe if the user later curves sideways.
        val horizontal = abs(x - startX)
        val vertical = abs(y - startY)
        if (vertical >= minimumDistancePx && vertical > horizontal * DirectionBias) cancel()
    }

    fun end(x: Float, y: Float, eventTime: Long, canTurn: Boolean): Int? {
        update(x, y, eventTime, pointerCount = 1, canTurn = canTurn)
        val eligible = active
        cancel()
        if (!eligible) return null
        val delta = x - startX
        if (abs(delta) < minimumDistancePx || abs(delta) <= abs(y - startY) * DirectionBias) return null
        return if (delta < 0f) 1 else -1
    }

    fun cancel() { active = false }

    private companion object {
        const val DirectionBias = 1.5f
    }
}
