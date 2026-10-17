package com.example.frogreader.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SegmentSlot(val index: Int, val left: Float, val right: Float) {
    val width: Float get() = right - left
    val center: Float get() = (left + right) / 2f
    fun rect(height: Float) = Rect(left, 0f, right, height)
}

/** Physical bounds in visual order; indices retain the caller's logical order in RTL. */
internal fun segmentSlots(width: Int, weights: List<Float>, gap: Int, rtl: Boolean): List<SegmentSlot> {
    if (weights.isEmpty() || width <= 0) return emptyList()
    val safeWeights = weights.map { if (it.isFinite() && it > 0f) it else 1f }
    val total = safeWeights.sum()
    val spacing = gap.coerceAtLeast(0).coerceAtMost(width / weights.size)
    val available = (width - spacing * (weights.size - 1)).coerceAtLeast(0)
    var used = 0f
    return safeWeights.mapIndexed { index, weight ->
        val start = (available * used / total).roundToInt() + index * spacing
        used += weight
        val end = (available * used / total).roundToInt() + index * spacing
        if (rtl) SegmentSlot(index, (width - end).toFloat(), (width - start).toFloat())
        else SegmentSlot(index, start.toFloat(), end.toFloat())
    }.sortedBy { it.left }
}

/** Preserve the point grabbed on the pill while smoothly adapting to unequal option widths. */
internal fun draggedSegmentPill(
    slots: List<SegmentSlot>,
    pointerX: Float,
    gripFraction: Float,
    initialWidth: Float,
    height: Float,
): Rect {
    if (slots.isEmpty() || !pointerX.isFinite()) return Rect.Zero
    val grip = gripFraction.coerceIn(0f, 1f) - 0.5f
    var center = (pointerX - grip * initialWidth).coerceIn(slots.first().center, slots.last().center)
    fun widthAt(x: Float): Float {
        val right = slots.indexOfFirst { it.center >= x }
        if (right <= 0) return if (right == 0) slots.first().width else slots.last().width
        val before = slots[right - 1]
        val after = slots[right]
        val progress = ((x - before.center) / (after.center - before.center)).coerceIn(0f, 1f)
        return before.width + (after.width - before.width) * progress
    }
    // Solve pointer = center + grip * width(center). Bisection remains stable
    // when adjacent segments have very different widths and the grip is at an edge.
    var low = slots.first().center
    var high = slots.last().center
    repeat(18) {
        center = (low + high) / 2f
        if (center + grip * widthAt(center) < pointerX) low = center else high = center
    }
    center = (low + high) / 2f
    val width = widthAt(center)
    return Rect(center - width / 2f, 0f, center + width / 2f, height)
}

/** Most visible overlap wins; the center and finally the finger resolve exact boundary ties. */
internal fun segmentUnderPill(slots: List<SegmentSlot>, pill: Rect, pointerX: Float): Int? =
    slots.maxWithOrNull(
        compareBy<SegmentSlot> { (minOf(it.right, pill.right) - maxOf(it.left, pill.left)).coerceAtLeast(0f) }
            .thenBy { -abs(it.center - pill.center.x) }
            .thenBy { -abs(it.center - pointerX) },
    )?.index

internal data class SegmentDragUpdate(
    val pill: Rect? = null,
    val consume: Boolean = false,
    val finished: Boolean = false,
    val selection: Int? = null,
)

/** Gesture decisions are independent of Compose event delivery and settings persistence. */
internal class SegmentPillDrag(
    private val slots: List<SegmentSlot>,
    private val initialPill: Rect,
    private val down: Offset,
    private val touchSlop: Float,
) {
    private val grip = ((down.x - initialPill.left) / initialPill.width).coerceIn(0f, 1f)
    private var dragging = false
    private var finished = false

    fun update(
        position: Offset,
        pressed: Boolean,
        consumed: Boolean = false,
        otherPointerPressed: Boolean = false,
    ): SegmentDragUpdate {
        if (finished) return SegmentDragUpdate(finished = true)
        if (consumed || otherPointerPressed || !position.x.isFinite() || !position.y.isFinite()) {
            finished = true
            return SegmentDragUpdate(finished = true)
        }
        val delta = position - down
        if (!dragging) {
            // A vertical intention belongs to the surrounding scrolling panel.
            if (abs(delta.y) > touchSlop && abs(delta.y) >= abs(delta.x)) {
                finished = true
                return SegmentDragUpdate(finished = true)
            }
            if (abs(delta.x) <= touchSlop) {
                finished = !pressed
                return SegmentDragUpdate(finished = finished)
            }
            dragging = true
        }
        val pill = draggedSegmentPill(slots, position.x, grip, initialPill.width, initialPill.height)
        finished = !pressed
        return SegmentDragUpdate(
            pill = pill,
            consume = true,
            finished = finished,
            selection = if (finished) segmentUnderPill(slots, pill, position.x) else null,
        )
    }
}
