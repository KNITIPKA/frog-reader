package com.example.frogreader.ui.reader

import kotlin.math.max
import kotlin.math.min

/** True book coordinates, independent of visual gaps or the expanded chapter focus. */
internal class ReaderProgressChapters(stops: List<Float>) {
    // A chapter starting on the final page has a point span at 1, not a span in the previous chapter.
    private val boundaries = (listOf(0f) + stops.filter { it.isFinite() && it > 0f && it <= 1f }
        .distinct().sorted() + 1f)

    val size: Int get() = boundaries.size - 1

    operator fun get(index: Int) = ReaderProgressChapter(boundaries[index], boundaries[index + 1])

    fun at(fraction: Float): ReaderProgressChapter {
        val value = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        var low = 0
        var high = size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (boundaries[middle] <= value) low = middle else high = middle - 1
        }
        return get(low)
    }
}

internal data class ReaderProgressChapter(val start: Float, val end: Float) {
    val span: Float get() = end - start
}

internal data class ReaderProgressFocus(val startPx: Float, val endPx: Float)

/**
 * Only the focus decoration grows. Pin its current position to the real cursor;
 * do not redistribute chapter widths or feed the decoration back into seeking.
 * Bounds use reading order, so the same result can be mirrored for RTL drawing.
 */
internal fun readerProgressFocus(
    chapter: ReaderProgressChapter,
    fraction: Float,
    trackWidthPx: Float,
    paddingPx: Float,
    minimumWidthPx: Float,
    centering: Float = 0f,
): ReaderProgressFocus {
    if (!trackWidthPx.isFinite() || trackWidthPx <= 0f) return ReaderProgressFocus(0f, 0f)
    val value = if (fraction.isFinite()) fraction.coerceIn(chapter.start, chapter.end) else chapter.start
    val local = if (chapter.span > 0f) ((value - chapter.start) / chapter.span).coerceIn(0f, 1f) else 1f
    val width = max(chapter.span * trackWidthPx + paddingPx * 2f, minimumWidthPx)
        .coerceIn(0f, trackWidthPx)
    val inset = paddingPx.coerceIn(0f, width / 2f)
    // Fully expanded focus follows the cursor, so neighbouring tiny chapters cannot make it jump.
    val anchor = local + (0.5f - local) * centering.coerceIn(0f, 1f)
    val start = (value * trackWidthPx - inset - anchor * (width - inset * 2f))
        .coerceIn(0f, trackWidthPx - width)
    return ReaderProgressFocus(start, start + width)
}

/** Dense TOCs gradually lose decorative gaps instead of inventing equally sized chapters. */
internal fun readerProgressSegmentDetail(chapterCount: Int, trackWidthDp: Float): Float {
    if (chapterCount <= 1 || !trackWidthDp.isFinite() || trackWidthDp <= 0f) return 0f
    val averageWidth = trackWidthDp / chapterCount
    return ((averageWidth - 4f) / 5f).coerceIn(0f, 1f)
}

/** Short chapters retain at least 74% of their true span even between two separators. */
internal fun readerProgressBoundaryGap(leftWidthPx: Float, rightWidthPx: Float, preferredGapPx: Float): Float =
    min(preferredGapPx, min(leftWidthPx, rightWidthPx) * 0.26f).coerceAtLeast(0f)
