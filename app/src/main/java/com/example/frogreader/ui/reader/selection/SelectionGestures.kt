package com.example.frogreader.ui.reader.selection

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/** What the quiet phase before any consumption turned out to be. */
private enum class Intent { TAP, ELSEWHERE, LONG_PRESS }

/**
 * The reader's whole selection gesture, as one state machine.
 *
 * Attached to the box that WRAPS both reading modes, so it can keep a
 * selection alive across a page turn — and therefore it sits *above* the pager
 * and the list in the tree. That is why everything here runs in the **Initial**
 * pass: Main-pass dispatch goes leaf → root, so the pager's `scrollable` would
 * see every move first, claim it at the touch slop and cut the drag off. The
 * brightness edge-drag in `readerGestures` runs on Initial for exactly the same
 * reason.
 *
 * Running first comes with the duty to stay out of the way, so nothing is
 * consumed until a long press has actually fired. Until then a swipe turns the
 * page, a drag scrolls the list, taps hit the page-turn zones and two fingers
 * still pinch — all unchanged. From the long press onwards every change is
 * consumed, so the page cannot slide out from under the selection.
 */
suspend fun PointerInputScope.bookSelectionGestures(
    controller: SelectionController,
    haptics: HapticFeedback,
) {
    val grabRadius = SelectionHitRules.HANDLE_GRAB_RADIUS.toPx()
    val startGap = SelectionHitRules.START_MAX_GAP.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val hadSelection = controller.selection != null

        // ── Grabbing a handle: no long press needed, it is already ours ──
        val grabbed = if (hadSelection) {
            SelectionHitRules.grabbedHandle(
                start = controller.handleCenter(SelectionEdge.START, grabRadius),
                end = controller.handleCenter(SelectionEdge.END, grabRadius),
                point = down.position,
                radiusPx = grabRadius,
            )
        } else {
            null
        }
        if (grabbed != null) {
            down.consume()
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            dragEdge(controller, down, grabbed, haptics)
            return@awaitEachGesture
        }

        // ── The quiet phase: watch, consume nothing ──────────────────────
        when (awaitIntent(down, hadSelection)) {
            Intent.ELSEWHERE -> return@awaitEachGesture // a swipe, a scroll, a pinch

            Intent.TAP -> {
                // Only reachable with a selection up (see awaitIntent): the
                // first tap puts it away and must not also turn the page.
                controller.clear()
                return@awaitEachGesture
            }

            Intent.LONG_PRESS -> Unit
        }

        val word = controller.wordAt(down.position, maxVerticalGap = startGap)
        if (word == null) {
            // Pressed somewhere with no text under it — an illustration, the
            // empty half of a chapter's last page. Leave the gesture alone
            // rather than reaching for the nearest paragraph.
            return@awaitEachGesture
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.set(word)
        dragWords(controller, down, word, haptics)
    }
}

/**
 * Waits out the long-press timeout without consuming anything, and reports
 * what the finger turned out to be doing.
 *
 * Movement is what disqualifies a long press — never `isConsumed`. A touch
 * that lands on a flinging list is consumed by `scrollable` to stop the fling,
 * and refusing to select in that case would mean every press right after a
 * scroll silently did nothing.
 */
private suspend fun AwaitPointerEventScope.awaitIntent(
    down: PointerInputChange,
    hadSelection: Boolean,
): Intent = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        val slop = viewConfiguration.touchSlop
        var outcome: Intent? = null
        while (outcome == null) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            outcome = when {
                change == null -> Intent.ELSEWHERE
                event.changes.count { it.pressed } > 1 -> Intent.ELSEWHERE
                !change.pressed -> if (hadSelection) {
                    // A tap with a selection up. Swallow the UP — not the
                    // down — so the page-turn zones stay silent while a
                    // SWIPE with a selection up still turns the page.
                    change.consume()
                    Intent.TAP
                } else {
                    Intent.ELSEWHERE
                }

                (change.position - down.position).getDistance() > slop -> Intent.ELSEWHERE
                else -> null // still down, still still: keep waiting
            }
        }
        outcome
    }
} catch (_: PointerEventTimeoutCancellationException) {
    Intent.LONG_PRESS
}

/**
 * Extending a fresh selection: whole words, anchored to the word the long
 * press landed on. Word granularity is what a finger can actually aim at, and
 * it is what every reader on both platforms does.
 */
private suspend fun AwaitPointerEventScope.dragWords(
    controller: SelectionController,
    down: PointerInputChange,
    anchorWord: BookSelection,
    haptics: HapticFeedback,
) {
    consumeDrag(controller, down) { position ->
        val word = controller.wordAt(position) ?: return@consumeDrag
        val next = anchorWord.union(word)
        if (next != controller.selection) {
            controller.set(next)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
}

/**
 * Dragging one handle: character granularity, because the reader is now
 * correcting an edge and means the exact letter.
 *
 * Dragging an edge past the other one swaps their roles, the way both
 * platforms do it — the finger keeps holding "the edge it is dragging", which
 * after the crossing is the other end of the selection.
 */
private suspend fun AwaitPointerEventScope.dragEdge(
    controller: SelectionController,
    down: PointerInputChange,
    grabbed: SelectionEdge,
    haptics: HapticFeedback,
) {
    var edge = grabbed
    // Keep the distance between the finger and the character it grabbed, so
    // the selection does not jump on the first pixel of movement. Measured to
    // the MIDDLE of the caret: its bottom edge belongs to the next line as
    // often as to this one.
    val caret = controller.caretOf(edge)
    val grabOffset = caret?.let { down.position - it.center } ?: Offset.Zero

    consumeDrag(controller, down) { position ->
        val current = controller.selection ?: return@consumeDrag
        val anchor = controller.anchorAt(position - grabOffset) ?: return@consumeDrag
        val fixed = if (edge == SelectionEdge.START) current.end else current.start
        if (anchor == fixed) return@consumeDrag // would collapse to nothing
        val next = BookSelection.of(anchor, fixed)
        if (next != current) {
            controller.set(next)
            edge = if (anchor < fixed) SelectionEdge.START else SelectionEdge.END
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
}

/**
 * The shared drag loop. Every change is consumed in the Initial pass, which is
 * what keeps the pager, the list, the tap zones and the brightness edge-drag
 * quiet for as long as the selection owns the finger.
 *
 * A second finger ENDS the drag instead of being consumed: `calculateZoom`
 * ignores consumption, so a pinch would otherwise repaginate the book in the
 * middle of a selection.
 */
private suspend fun AwaitPointerEventScope.consumeDrag(
    controller: SelectionController,
    down: PointerInputChange,
    onMove: (Offset) -> Unit,
) {
    controller.lastPoint = down.position
    // Published so the auto-advance loop can re-extend the selection after a
    // page turn, with the same granularity this drag uses.
    controller.extend = onMove
    controller.dragging = true
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.count { it.pressed } > 1) break
            event.changes.forEach { it.consume() }
            if (!change.pressed) break
            if (change.position != controller.lastPoint) {
                controller.lastPoint = change.position
                onMove(change.position)
            }
        }
    } finally {
        controller.dragging = false
        controller.extend = null
    }
}
