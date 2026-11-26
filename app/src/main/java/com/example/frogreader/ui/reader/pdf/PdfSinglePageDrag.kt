package com.example.frogreader.ui.reader.pdf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Observe a fitted page's horizontal drag without cancelling the native multi-touch stream. */
@Composable
internal fun Modifier.pdfSinglePageDrag(
    pager: PagerState,
    enabled: Boolean,
    canShowPage: (Int) -> Boolean,
    onGrab: () -> Unit,
    onDragging: (Boolean) -> Unit,
    onRelease: (Int, Float) -> Unit,
): Modifier {
    val canDrag by rememberUpdatedState(enabled)
    val pageReady by rememberUpdatedState(canShowPage)
    val grabbed by rememberUpdatedState(onGrab)
    val draggingChanged by rememberUpdatedState(onDragging)
    val released by rememberUpdatedState(onRelease)
    val flingVelocity = with(LocalDensity.current) { 500.dp.toPx() }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    return pointerInput(pager, flingVelocity, longPressTimeout) {
        var dragging = false
        var startPage = pager.currentPage
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!canDrag) return@awaitEachGesture
                val interrupted = pager.isScrollInProgress
                grabbed() // Cancel settling immediately, keeping the exact on-screen offset.
                startPage = pager.currentPage
                var intendedOffset = pager.currentPageOffsetFraction
                val start = down.position
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, start) }
                var previousX = start.x
                var cancelled = false
                var handedToPinch = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1) {
                        // AndroidView keeps the original DOWN and receives POINTER_DOWN. Restore
                        // the touched sheet synchronously, before the native detector sees it;
                        // an asynchronous page-settle animation would move the pinch pivot.
                        val pageSize = pager.layoutInfo.pageSize.toFloat()
                        pager.dispatchRawDelta((startPage - pager.currentPage - pager.currentPageOffsetFraction) * pageSize)
                        if (dragging) draggingChanged(false)
                        dragging = false
                        handedToPinch = true
                        break
                    }
                    if (change == null || event.changes.size != 1 || (!dragging && !canDrag)) {
                        cancelled = true
                        break
                    }
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val dx = change.position.x - start.x
                    val dy = change.position.y - start.y
                    if (!dragging) {
                        if (!change.pressed || change.uptimeMillis - down.uptimeMillis >= longPressTimeout ||
                            abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                        if (abs(dx) <= viewConfiguration.touchSlop || abs(dx) <= abs(dy) * 1.5f) continue
                        dragging = true
                        draggingChanged(true)
                        // The fitted page cannot pan horizontally. Observe the same complete
                        // stream as AndroidView so a later second finger can still start zooming.
                        previousX = start.x + viewConfiguration.touchSlop * if (dx > 0f) 1f else -1f
                    }
                    val pageSize = pager.layoutInfo.pageSize.toFloat()
                    if (pageSize > 0f) {
                        val position = pager.currentPage + pager.currentPageOffsetFraction - startPage
                        val requested = -(change.position.x - previousX)
                        intendedOffset = (intendedOffset + requested / pageSize).coerceIn(-1f, 1f)
                        val neighbour = startPage + when {
                            intendedOffset > 0f -> 1
                            intendedOffset < 0f -> -1
                            else -> 0
                        }
                        // A slow renderer must not expose an empty incoming sheet. Keep the
                        // gesture's intended distance so release can complete once it is ready.
                        val shownOffset = if (pageReady(neighbour)) intendedOffset else 0f
                        pager.dispatchRawDelta((shownOffset - position) * pageSize)
                    }
                    previousX = change.position.x
                    if (!change.pressed) break
                }
                if (dragging) {
                    val targetDelta = if (cancelled) 0 else pdfDragTarget(intendedOffset, velocity.calculateVelocity().x, flingVelocity)
                    dragging = false
                    draggingChanged(false)
                    released(startPage + targetDelta, if (cancelled) 0f else velocity.calculateVelocity().x)
                } else if (interrupted && !handedToPinch) {
                    released(startPage, 0f)
                }
            }
        } finally {
            if (dragging) {
                draggingChanged(false)
                released(startPage, 0f)
            }
        }
    }
}

/** A quick flick follows velocity; a slow drag must expose at least a quarter of the next sheet. */
internal fun pdfDragTarget(pageOffset: Float, velocityX: Float, minimumVelocity: Float): Int = when {
    !pageOffset.isFinite() || !velocityX.isFinite() -> 0
    abs(velocityX) >= minimumVelocity && abs(pageOffset) > 0.01f -> when {
        velocityX < 0f && pageOffset > 0f -> 1
        velocityX > 0f && pageOffset < 0f -> -1
        else -> 0 // Reversing the flick returns to the sheet where the drag began.
    }
    pageOffset >= 0.25f -> 1
    pageOffset <= -0.25f -> -1
    else -> 0
}

/** Short, velocity-aware settling; a new press cancels the owning job without snapping. */
internal suspend fun settlePdfPage(pager: PagerState, target: Int, fingerVelocity: Float) {
    val pageSize = pager.layoutInfo.pageSize.toFloat()
    if (pageSize <= 0f) { pager.scrollToPage(target); return }
    val pages = target - pager.currentPage - pager.currentPageOffsetFraction
    if (abs(pages) > 1.5f) {
        pager.animateScrollToPage(target, animationSpec = tween(180))
        return
    }
    val distance = pages * pageSize
    if (abs(distance) < 0.5f) { pager.scrollToPage(target); return }
    val forwardVelocity = (-fingerVelocity * if (distance > 0f) 1f else -1f).coerceAtLeast(0f)
    val baseDuration = (60f + abs(pages) * 140f).coerceIn(60f, 200f)
    val duration = if (forwardVelocity > 0f) {
        minOf(baseDuration, 1800f * abs(distance) / forwardVelocity).coerceAtLeast(60f)
    } else baseDuration
    // Cubic Hermite interpolation carries the release velocity into a zero-velocity endpoint.
    // A stationary release gets a prompt ease-out, rather than restarting a slow ease-in.
    val tangent = if (forwardVelocity > 0f) {
        (forwardVelocity * duration / (1000f * abs(distance))).coerceIn(0f, 3f)
    } else 1.5f
    pager.scroll {
        var previous = 0f
        animate(0f, 1f, animationSpec = tween(duration.toInt(), easing = LinearEasing)) { t, _ ->
            val t2 = t * t
            val t3 = t2 * t
            val position = distance * (-2f * t3 + 3f * t2 + (t3 - 2f * t2 + t) * tangent)
            scrollBy(position - previous)
            previous = position
        }
    }
    pager.scrollToPage(target)
}
