package com.example.frogreader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

/** Normalized horizontal travel and velocity; a short slow drag can still be cancelled. */
internal fun instantPageSwipeDelta(displacement: Float, velocity: Float, rtl: Boolean): Int {
    if (!displacement.isFinite()) return 0
    val distance = abs(displacement)
    val flick = distance >= 0.03f && velocity.isFinite() && abs(velocity) > 0.7f &&
        (velocity > 0f) == (displacement > 0f)
    if (distance < 0.1f && !flick) return 0
    val physicalDelta = if (displacement < 0f) 1 else -1
    return if (rtl) -physicalDelta else physicalDelta
}

internal fun instantPageNavigate(pager: PagerState, target: Int): Boolean {
    if (target !in 0 until pager.pageCount || target == pager.currentPage) return false
    pager.requestScrollToPage(target)
    return true
}

/**
 * Native Pager dragging is disabled for None: not even the held finger slides
 * the page. Selection, vertical scrolling, brightness and pinch claim input
 * first; a horizontal swipe changes one page only on an unconsumed UP.
 */
internal fun Modifier.instantPageGestures(pager: PagerState, rtl: Boolean): Modifier =
    pointerInput(pager, rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val width = size.width.coerceAtLeast(1).toFloat()
            val origin = pager.currentPage
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var claimed = false
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                if (pointer == null || pointer.isConsumed || event.changes.count { it.pressed } > 1) break
                velocity.addPosition(pointer.uptimeMillis, pointer.position)
                val travel = pointer.position - down.position
                if (!pointer.pressed) {
                    if (claimed) {
                        pointer.consume()
                        val delta = instantPageSwipeDelta(travel.x / width, velocity.calculateVelocity().x / width, rtl)
                        if (delta != 0 && pager.currentPage == origin) instantPageNavigate(pager, origin + delta)
                    }
                    break
                }
                if (!claimed && travel.getDistance() > viewConfiguration.touchSlop) {
                    if (abs(travel.x) <= abs(travel.y)) break
                    claimed = true
                }
                if (claimed) pointer.consume()
            }
        }
    }

/** Keep accessibility paging available when Pager's animated scroll actions are disabled. */
internal fun Modifier.instantPageSemantics(pager: PagerState, rtl: Boolean): Modifier = semantics {
    pageLeft { instantPageNavigate(pager, pager.currentPage + if (rtl) 1 else -1) }
    pageRight { instantPageNavigate(pager, pager.currentPage + if (rtl) -1 else 1) }
    scrollBy { x, _ ->
        val logical = if (rtl) -x else x
        if (!logical.isFinite() || logical == 0f) false
        else instantPageNavigate(pager, pager.currentPage + if (logical > 0f) 1 else -1)
    }
    scrollToIndex { index ->
        if (index !in 0 until pager.pageCount) false
        else index == pager.currentPage || instantPageNavigate(pager, index)
    }
}
