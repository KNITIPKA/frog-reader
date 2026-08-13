package com.example.frogreader.ui.reader.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which side of the selection the action pill hangs off. */
enum class PillSide { ABOVE, BELOW }

/**
 * Pure geometry of selection: which piece of text a finger is pointing at,
 * when a drag has reached the edge and how fast the page should follow.
 *
 * Kept apart from the Compose plumbing for the same reason `BreakRules` and
 * `TableGrid` are — these are the decisions that are worth pinning down in
 * tests, and none of them need a composition to be made.
 */
object SelectionHitRules {

    /** Radius of the round part of a selection handle. */
    val HANDLE_RADIUS = 10.dp

    /**
     * How far below the last selected line a handle reaches — it hangs by its
     * top corner, so its whole diameter is below the text. Anything else put
     * under a selection has to clear this or it lands on the handle.
     */
    val HANDLE_REACH = HANDLE_RADIUS * 2

    /** How far from a handle's center a touch may land and still grab it. */
    val HANDLE_GRAB_RADIUS = 28.dp

    /** Width of the screen-edge band that turns pages / scrolls while dragging. */
    val EDGE_BAND = 56.dp

    /** Fastest auto-scroll in scroll mode, per frame at 60 Hz. */
    val AUTO_SCROLL_MAX_PER_FRAME = 14.dp

    /**
     * Vertical distance counts this much more than horizontal.
     *
     * Text stacks vertically, so the line a reader points at is the one at
     * their finger's HEIGHT even when the finger is way out in the page
     * margin. Weighting them equally makes a drag through the margin snap
     * across to whatever paragraph happens to be geometrically nearest.
     */
    private const val VERTICAL_WEIGHT = 6f

    /**
     * How far from any text a long press may land and still start a selection.
     *
     * A press below the last line of a page should still catch that line; a
     * press in the middle of a full-width illustration should not reach for
     * whatever paragraph happens to be nearest. Only STARTING is limited —
     * extending a selection has to keep following the finger across pictures,
     * spacers and chapter gaps.
     */
    val START_MAX_GAP = 40.dp

    /**
     * Index of the fragment a pointer at [point] addresses, or -1 when there
     * is nothing to point at.
     *
     * Three tiers, in order: a fragment containing the point; a fragment whose
     * vertical band the point falls inside (the finger is beside the line, in
     * a margin or an inset); otherwise the weighted-nearest one, subject to
     * [maxVerticalGap]. Ties keep the earlier entry, so callers order [rects]
     * by preference — freshest layout epoch first.
     */
    fun pick(
        rects: List<Rect>,
        point: Offset,
        maxVerticalGap: Float = Float.MAX_VALUE,
    ): Int {
        var containing = -1
        var bandBest = -1
        var bandDistance = Float.MAX_VALUE
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        var nearestVerticalGap = Float.MAX_VALUE

        for (index in rects.indices) {
            val rect = rects[index]
            if (rect.isEmpty) continue // off-screen: clipped away to nothing
            if (rect.contains(point)) {
                if (containing < 0) containing = index
                continue
            }
            val dx = horizontalGap(rect, point.x)
            val dy = verticalGap(rect, point.y)
            if (dy == 0f && dx < bandDistance) {
                bandDistance = dx
                bandBest = index
            }
            val weighted = dy * VERTICAL_WEIGHT + dx
            if (weighted < nearestDistance) {
                nearestDistance = weighted
                nearestVerticalGap = dy
                nearest = index
            }
        }
        return when {
            containing >= 0 -> containing
            bandBest >= 0 -> bandBest
            nearestVerticalGap <= maxVerticalGap -> nearest
            else -> -1
        }
    }

    /**
     * -1 / 0 / +1: which way the reader should advance while a selection drag
     * sits at [point]. Paged mode watches the left/right edges (that is where
     * the pages are), scroll mode the top/bottom.
     */
    fun edgeAdvance(
        point: Offset,
        width: Float,
        height: Float,
        paged: Boolean,
        bandPx: Float,
    ): Int {
        val position = if (paged) point.x else point.y
        val extent = if (paged) width else height
        return when {
            position < bandPx -> -1
            position > extent - bandPx -> 1
            else -> 0
        }
    }

    /**
     * Signed pixels to scroll this frame in scroll mode — ramped by how deep
     * into the edge band the finger is, so a reader can creep or race.
     */
    fun autoScrollPx(y: Float, height: Float, bandPx: Float, maxPerFrame: Float): Float {
        if (bandPx <= 0f) return 0f
        val aboveTop = bandPx - y
        val belowBottom = y - (height - bandPx)
        val depth = when {
            aboveTop > 0f -> -aboveTop
            belowBottom > 0f -> belowBottom
            else -> return 0f
        }
        val ramp = (abs(depth) / bandPx).coerceIn(0.2f, 1f)
        return if (depth < 0f) -maxPerFrame * ramp else maxPerFrame * ramp
    }

    /**
     * Whether two pieces of text stand in the same column, and so on the same
     * page: pages sit side by side and never overlap horizontally, while
     * everything within one page shares its text column.
     *
     * Horizontal only — two paragraphs of the same page are far apart
     * vertically and still belong together.
     */
    fun sharesColumn(a: Rect, b: Rect): Boolean = a.left < b.right && b.left < a.right

    /**
     * Center of the round handle hanging off a caret line.
     *
     * They hang OUTWARDS — the start handle to the left of the first selected
     * character, the end handle to the right of the last — so neither covers
     * the text it marks, which is the whole point of the shape.
     */
    fun handleCenter(caret: Rect, edge: SelectionEdge, radiusPx: Float): Offset = when (edge) {
        SelectionEdge.START -> Offset(caret.left - radiusPx, caret.bottom + radiusPx)
        SelectionEdge.END -> Offset(caret.right + radiusPx, caret.bottom + radiusPx)
    }

    /**
     * Which handle a touch at [point] grabs, or null for none. Both in range
     * (they meet on a one-word selection) → the nearer one wins.
     */
    fun grabbedHandle(
        start: Offset?,
        end: Offset?,
        point: Offset,
        radiusPx: Float,
    ): SelectionEdge? {
        val toStart = start?.let { (it - point).getDistance() } ?: Float.MAX_VALUE
        val toEnd = end?.let { (it - point).getDistance() } ?: Float.MAX_VALUE
        if (minOf(toStart, toEnd) > radiusPx) return null
        return if (toStart <= toEnd) SelectionEdge.START else SelectionEdge.END
    }

    /**
     * Which side of the selection the pill fits on: above it, or below when
     * the top of the screen is in the way.
     *
     * [insetTop] has to clear the status bar AND the camera cutout, which on
     * most phones is a hole punched through exactly the strip a pill above a
     * first-line selection would sit in — the lens lands on its middle button.
     */
    fun toolbarSide(
        bounds: Rect,
        pillHeight: Float,
        screenHeight: Float,
        gapPx: Float,
        handleReachPx: Float,
        insetTop: Float,
        insetBottom: Float,
    ): PillSide {
        if (bounds.top - gapPx - pillHeight >= insetTop) return PillSide.ABOVE
        val below = belowY(bounds, gapPx, handleReachPx)
        if (below + pillHeight <= screenHeight - insetBottom) return PillSide.BELOW
        return PillSide.ABOVE // a selection filling the screen: sit at the top
    }

    /**
     * Under a selection means under its handle too — the end handle hangs
     * below the last selected line, and a pill placed at the text's own
     * bottom edge lands right on it, covering the one thing the reader
     * reaches for to carry the selection further.
     */
    private fun belowY(bounds: Rect, gapPx: Float, handleReachPx: Float): Float =
        bounds.bottom + handleReachPx + gapPx

    /**
     * Top-left corner for the pill, given the side it goes on: centered over
     * the selection and kept fully on screen.
     *
     * The original toolbar was pinned to the selection's top-left minus a
     * fixed 64dp, which walked off the top for anything selected on the first
     * line and off the side for a selection near the right margin.
     */
    fun toolbarOffset(
        bounds: Rect,
        pillWidth: Float,
        pillHeight: Float,
        screenWidth: Float,
        gapPx: Float,
        handleReachPx: Float,
        insetTop: Float,
        insetLeft: Float,
        insetRight: Float,
        side: PillSide,
    ): Offset {
        val y = when (side) {
            PillSide.ABOVE -> bounds.top - gapPx - pillHeight
            PillSide.BELOW -> belowY(bounds, gapPx, handleReachPx)
        }
        val x = bounds.center.x - pillWidth / 2f
        val maxX = (screenWidth - insetRight - pillWidth).coerceAtLeast(insetLeft)
        return Offset(x.coerceIn(insetLeft, maxX), y.coerceAtLeast(insetTop))
    }

    private fun horizontalGap(rect: Rect, x: Float): Float = when {
        x < rect.left -> rect.left - x
        x > rect.right -> x - rect.right
        else -> 0f
    }

    private fun verticalGap(rect: Rect, y: Float): Float = when {
        y < rect.top -> rect.top - y
        y > rect.bottom -> y - rect.bottom
        else -> 0f
    }
}
