package com.example.frogreader.ui.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** True book coordinates, independent of visual gaps or the expanded chapter focus. */
internal class ReaderProgressChapters(stops: List<Float>) {
    // A chapter starting on the final page has a point span at 1, not a span in the previous chapter.
    private val boundaries = (listOf(0f) + stops.filter { it.isFinite() && it > 0f && it <= 1f }
        .distinct().sorted() + 1f)

    val size: Int get() = boundaries.size - 1

    operator fun get(index: Int) = ReaderProgressChapter(boundaries[index], boundaries[index + 1])

    fun indexAt(fraction: Float): Int {
        val value = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        var low = 0
        var high = size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (boundaries[middle] <= value) low = middle else high = middle - 1
        }
        return low
    }

    fun at(fraction: Float): ReaderProgressChapter = get(indexAt(fraction))
}

internal data class ReaderProgressChapter(val start: Float, val end: Float) {
    val span: Float get() = end - start
}

internal data class ReaderProgressAnchor(val chapterIndex: Int, val bookFraction: Float, val trackFraction: Float)

/**
 * One scale for drawing, the cursor and touch input, including intermediate
 * animation frames. A focused chapter reserves space rather than covering its
 * neighbours. Its expansion is anchored to the finger when entering a chapter.
 */
internal class ReaderProgressLayout(
    private val stops: List<Float>,
    val trackWidthDp: Float,
    private val positionCount: Int? = null,
    private val animatedBoundaries: List<Float>? = null,
    val focusedIndex: Int? = null,
    private val animatedInsets: List<Float>? = null,
    private val seekAnchor: ReaderProgressAnchor? = null,
) {
    val chapters = ReaderProgressChapters(stops)
    val detail = readerProgressSegmentDetail(chapters.size, trackWidthDp)
    private val minimumSpan = if (detail > 0f && trackWidthDp > 0f) {
        min(8f / trackWidthDp, 0.8f / chapters.size)
    } else 0f
    val boundaries: List<Float> = animatedBoundaries ?: run {
        if (minimumSpan == 0f) {
            List(chapters.size) { chapters[it].start } + 1f
        } else {
            // Water filling: reserve a minimum for small chapters, then fit
            // the remaining widths with one common factor for long chapters.
            var low = 0f
            var high = 1f
            repeat(32) {
                val scale = (low + high) / 2f
                val sum = (0 until chapters.size).sumOf {
                    max(minimumSpan, chapters[it].span * scale).toDouble()
                }
                if (sum > 1.0) high = scale else low = scale
            }
            var start = 0f
            List(chapters.size) { index ->
                start.also { start += max(minimumSpan, chapters[index].span * low) }
            } + 1f
        }
    }

    fun segment(index: Int) = ReaderProgressChapter(boundaries[index], boundaries[index + 1])

    val insets: List<Float> = animatedInsets ?: List(chapters.size) { index ->
        if (focusedIndex == index && trackWidthDp > 0f) min(4f / trackWidthDp, segment(index).span / 2f) else 0f
    }

    fun inset(index: Int): Float {
        val segment = segment(index)
        var padding = min(insets[index], segment.span / 2f)
        val anchor = seekAnchor?.takeIf { it.chapterIndex == index }
        if (anchor != null) {
            val local = chapterLocal(index, anchor.bookFraction)
            if (local > 0f && local < 1f) {
                // Near a rail edge, keep a nonzero interval on both sides of
                // the finger instead of changing pages as the capsule grows.
                padding = min(padding, min(anchor.trackFraction - segment.start, segment.end - anchor.trackFraction).coerceAtLeast(0f) / 2f)
            }
        }
        return padding
    }

    fun focused(fraction: Float, expanded: Boolean, anchor: ReaderProgressAnchor? = null): ReaderProgressLayout {
        if (chapters.size <= 1 || trackWidthDp <= 0f) return this
        val index = chapters.indexAt(fraction)
        val chapter = chapters[index]
        val original = segment(index)
        val width = max(original.span + 8f / trackWidthDp, if (expanded) 32f / trackWidthDp else 0f)
            .coerceAtMost(0.95f)
        val padding = min(4f / trackWidthDp, width / 2f)
        val pivot = anchor?.takeIf { it.chapterIndex == index }
            ?: ReaderProgressAnchor(index, fraction, trackFraction(fraction))
        val local = if (chapter.span > 0f) {
            ((pivot.bookFraction - chapter.start) / chapter.span).coerceIn(0f, 1f)
        } else 1f
        // Keep chapters on either side reachable, even near the ends of the rail.
        val reserve = min(4f / trackWidthDp, (1f - width) / (chapters.size - 1) / 2f)
        val start = (pivot.trackFraction - padding - local * (width - padding * 2f))
            .coerceIn(index * reserve, 1f - width - (chapters.size - index - 1) * reserve)
        val end = start + width
        val bounds = List(chapters.size + 1) { boundary ->
            when {
                boundary <= index -> if (original.start > 0f) boundaries[boundary] / original.start * start else 0f
                boundary == index + 1 -> end
                original.end < 1f -> end + (boundaries[boundary] - original.end) / (1f - original.end) * (1f - end)
                else -> 1f
            }
        }
        return ReaderProgressLayout(stops, trackWidthDp, positionCount, bounds, index, seekAnchor = anchor)
    }

    fun withGeometry(bounds: List<Float>, padding: List<Float>) =
        ReaderProgressLayout(stops, trackWidthDp, positionCount, bounds, focusedIndex, padding, seekAnchor)

    private fun chapterLocal(index: Int, fraction: Float): Float {
        val chapter = chapters[index]
        return if (chapter.span > 0f) ((fraction - chapter.start) / chapter.span).coerceIn(0f, 1f) else 1f
    }

    fun trackFraction(bookFraction: Float): Float {
        val value = safeFraction(bookFraction)
        val index = chapters.indexAt(value)
        val segment = segment(index)
        val local = chapterLocal(index, value)
        val padding = inset(index)
        val left = segment.start + padding
        val right = segment.end - padding
        val anchor = seekAnchor?.takeIf { it.chapterIndex == index }
        if (anchor != null) {
            val pivot = chapterLocal(index, anchor.bookFraction)
            val x = anchor.trackFraction.coerceIn(left, right)
            return if (local <= pivot) {
                if (pivot > 0f) left + local / pivot * (x - left) else x
            } else if (pivot < 1f) x + (local - pivot) / (1f - pivot) * (right - x) else x
        }
        return (left + local * (right - left)).coerceIn(0f, 1f)
    }

    fun bookFraction(trackFraction: Float): Float {
        val value = safeFraction(trackFraction)
        var low = 0
        var high = chapters.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (boundaries[middle] <= value) low = middle else high = middle - 1
        }
        val chapter = chapters[low]
        val segment = segment(low)
        val padding = inset(low)
        val left = segment.start + padding
        val right = segment.end - padding
        val anchor = seekAnchor?.takeIf { it.chapterIndex == low }
        val local = if (anchor != null) {
            val pivot = chapterLocal(low, anchor.bookFraction)
            val x = anchor.trackFraction.coerceIn(left, right)
            if (value <= x) {
                if (x > left) pivot * ((value - left) / (x - left)).coerceIn(0f, 1f) else pivot
            } else if (right > x) pivot + (1f - pivot) * ((value - x) / (right - x)).coerceIn(0f, 1f) else pivot
        } else if (right > left) ((value - left) / (right - left)).coerceIn(0f, 1f) else 0.5f
        val fraction = chapter.start + local * chapter.span
        val lastPosition = positionCount?.minus(1)?.takeIf { it > 0 } ?: return fraction
        val first = (chapter.start * lastPosition).roundToInt()
        val last = if (low == chapters.size - 1) lastPosition else {
            ((chapter.end * lastPosition).roundToInt() - 1).coerceAtLeast(first)
        }
        // Rounding near the right edge must not seek into the next chapter.
        return (fraction * lastPosition).roundToInt().coerceIn(first, last).toFloat() / lastPosition
    }

    private fun safeFraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

/** A short chapter jump still waits for navigation to reach the selected page/item. */
internal fun readerProgressSeekReached(actual: Float, target: Float, positionCount: Int?): Boolean {
    if (!actual.isFinite() || !target.isFinite()) return false
    return if (positionCount != null && positionCount > 0) {
        ReaderNavigationPolicy.progressTargetIndex(actual, 0, positionCount - 1) ==
            ReaderNavigationPolicy.progressTargetIndex(target, 0, positionCount - 1)
    } else abs(actual - target) < 0.00001f
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
