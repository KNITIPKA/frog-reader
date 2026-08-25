package com.example.frogreader.ui.reader

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure policies shared by reader surfaces and covered without Compose UI. */
internal object ReaderNavigationPolicy {

    /** A contextual return is useful only after more than two screenfuls. */
    fun isLargeScrollJump(travelledPx: Float, viewportPx: Int): Boolean =
        travelledPx.isFinite() && viewportPx > 0 &&
            abs(travelledPx) >= viewportPx * LARGE_SCROLL_VIEWPORTS

    /**
     * Whether a target belongs to the sole chapter in a quick/partial page
     * holder. A false result means the target must remain pending until the
     * complete pagination replaces it.
     */
    fun partialHolderContainsTarget(
        chapterStarts: List<Int>,
        itemCount: Int,
        partialFirstItem: Int?,
        targetItem: Int,
    ): Boolean {
        if (partialFirstItem == null || itemCount <= 0 || chapterStarts.isEmpty()) return false
        val chapter = chapterStarts.indexOfLast { it <= partialFirstItem }
        if (chapter < 0) return false
        val from = chapterStarts[chapter]
        val to = chapterStarts.getOrNull(chapter + 1)?.minus(1) ?: (itemCount - 1)
        return targetItem in from..to
    }

    /**
     * A whole-book fraction is expressed in the complete pagination's page
     * coordinates. A quick chapter-only holder must leave it pending for the
     * full holder instead of consuming it against its local page count.
     */
    fun canConsumeBookFractionSeek(partialPagination: Boolean): Boolean =
        !partialPagination

    /**
     * Keeps a whole-book request in normalized book coordinates while page
     * coordinates are only chapter-local. Returning null suppresses a tap on
     * the current thumb position without quantizing through the partial page
     * count (which would corrupt the later full-book destination).
     */
    fun pendingBookFractionSeek(
        currentFraction: Float,
        requestedFraction: Float,
    ): Float? {
        if (!requestedFraction.isFinite()) return null
        val requested = requestedFraction.coerceIn(0f, 1f)
        val current = currentFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        return requested.takeUnless { current != null && abs(it - current) <= FRACTION_NO_OP_EPSILON }
    }

    /** Converts a track fraction to the exact discrete page/item it addresses. */
    fun progressTargetIndex(
        fraction: Float,
        firstIndex: Int,
        lastIndexInclusive: Int,
    ): Int {
        val span = (lastIndexInclusive - firstIndex).coerceAtLeast(0)
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        return firstIndex + (safeFraction * span).roundToInt()
    }

    private const val LARGE_SCROLL_VIEWPORTS = 2.25f
    private const val FRACTION_NO_OP_EPSILON = 0.0005f
}
