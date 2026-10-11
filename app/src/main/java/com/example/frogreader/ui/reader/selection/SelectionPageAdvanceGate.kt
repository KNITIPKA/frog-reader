package com.example.frogreader.ui.reader.selection

import androidx.compose.ui.geometry.Offset

/**
 * A page turns after moving towards its outer edge and staying in its margin.
 * Long-pressing a margin word, or dragging vertically through a paragraph,
 * must not be enough. Once armed, movement along the margin keeps the timer;
 * the finger does not need to stop moving to continue onto the next page.
 * Scroll mode does not use this gate: its motion remains continuous.
 */
internal class SelectionPageAdvanceGate(
    initialPoint: Offset,
    private val bandPx: Float,
    private val minTravelPx: Float,
    private val exitSlopPx: Float,
) {
    private var minX = initialPoint.x
    private var maxX = initialPoint.x
    private var heldDirection = 0
    private var heldSince = 0L
    private var repeating = false

    fun direction(point: Offset, width: Float, nowMillis: Long): Int {
        minX = minOf(minX, point.x)
        maxX = maxOf(maxX, point.x)
        // Entry remains in the outer margin, away from words. After entry,
        // tolerate a small move back across that boundary instead of making
        // the reader start the full hold again for every finger adjustment.
        val edge = when {
            heldDirection == -1 && point.x < bandPx + exitSlopPx -> -1
            heldDirection == 1 && point.x > width - bandPx - exitSlopPx -> 1
            else -> SelectionHitRules.edgeAdvance(point, width, 0f, paged = true, bandPx = bandPx)
        }
        if (edge == 0) {
            restartHold()
            return 0
        }
        if (heldDirection != edge) {
            val travel = if (edge < 0) maxX - point.x else point.x - minX
            if (travel < minTravelPx) {
                restartHold()
                return 0
            }
            heldDirection = edge
            heldSince = nowMillis
            repeating = false
            return 0
        }
        val hold = if (repeating) REPEAT_MILLIS else HOLD_MILLIS
        return if (nowMillis - heldSince >= hold) edge else 0
    }

    /** Count repeat time from the start of a turn, including its animation. */
    fun didAdvance(nowMillis: Long) {
        heldSince = nowMillis
        repeating = true
    }

    fun restartHold() {
        heldDirection = 0
        repeating = false
    }

    companion object {
        const val HOLD_MILLIS = 300L
        const val REPEAT_MILLIS = 600L
    }
}
