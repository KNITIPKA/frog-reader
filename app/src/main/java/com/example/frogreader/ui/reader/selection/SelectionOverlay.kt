package com.example.frogreader.ui.reader.selection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val HANDLE_RADIUS = SelectionHitRules.HANDLE_RADIUS

/**
 * The two selection handles.
 *
 * They carry no `pointerInput` of their own — the gesture on the reader box
 * hit-tests their positions instead, which keeps every question of z-order and
 * hit-test ordering from arising at all.
 *
 * A handle is drawn only at the character it marks. When that character is on
 * another page the handle is simply absent: an earlier version parked it at
 * the edge of the visible selection instead, and on screen that read as a
 * stray dot half off the edge rather than as anything grabbable.
 *
 * Positions are re-read every frame while a selection is up, which tracks list
 * scrolling and the pager settling into place exactly, and costs nothing the
 * rest of the time (the whole composable is absent when nothing is selected).
 */
@Composable
fun SelectionHandles(controller: SelectionController, color: Color) {
    if (!controller.active) return
    val radius = with(LocalDensity.current) { HANDLE_RADIUS.toPx() }
    val frame = rememberFrameTicker()

    Canvas(Modifier.fillMaxSize()) {
        // Read the tick, then the positions, INSIDE the draw block. Caching
        // them in state a frame earlier is what made the handles wobble as
        // the page moved: the text was placed in this frame and the handles
        // still carried the previous frame's coordinates.
        frame.longValue
        controller.caretOf(SelectionEdge.START)?.let {
            drawHandle(it, SelectionEdge.START, radius, color)
        }
        controller.caretOf(SelectionEdge.END)?.let {
            drawHandle(it, SelectionEdge.END, radius, color)
        }
    }
}

/**
 * A value that changes once per frame.
 *
 * Read it inside a draw or a graphics-layer block to keep that block running
 * in step with the page moving underneath: the tick lands at the start of the
 * frame, invalidates the block, and the block then runs after layout has put
 * the text where it belongs for THIS frame.
 */
@Composable
internal fun rememberFrameTicker(): MutableLongState {
    val ticker = remember { mutableLongStateOf(0L) }
    LaunchedEffect(ticker) {
        while (true) withFrameNanos { ticker.longValue = it }
    }
    return ticker
}

/**
 * A Material selection handle: a circle hanging below the caret with one
 * square corner pointing back at the character it marks.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    caret: Rect,
    edge: SelectionEdge,
    radius: Float,
    color: Color,
) {
    val center = SelectionHitRules.handleCenter(caret, edge, radius)
    drawCircle(color = color, radius = radius, center = center)
    // The square corner fills the quadrant facing the caret, so the handle
    // reads as pinned to the exact character rather than floating near it.
    val corner = if (edge == SelectionEdge.START) {
        Offset(center.x, center.y - radius)
    } else {
        Offset(center.x - radius, center.y - radius)
    }
    drawRect(color = color, topLeft = corner, size = Size(radius, radius))
}

/**
 * Whether any part of the selection is on screen at all.
 *
 * Only the coarse yes/no lives in composition — the action pill reads its
 * exact position live, inside its own graphics layer, because a position
 * cached in state is always one frame behind the text it belongs to.
 */
@Composable
fun rememberSelectionOnScreen(controller: SelectionController): Boolean {
    var onScreen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val next = controller.toolbarBounds() != null
            if (next != onScreen) onScreen = next
        }
    }
    return onScreen
}
