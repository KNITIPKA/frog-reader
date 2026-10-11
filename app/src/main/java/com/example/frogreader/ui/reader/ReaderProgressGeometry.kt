package com.example.frogreader.ui.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The 13 dp cursor needs clearance from the rounded 17–19 dp focus outline.
private const val ReaderProgressFocusPaddingDp = 6f

/** True book coordinates, independent of visual gaps or the expanded chapter focus. */
internal class ReaderProgressChapters(stops: List<Float>) {
    // A chapter starting on the final page has a point span at 1, not a span in the previous chapter.
    private val boundaries = (listOf(0f) + stops.filter { it.isFinite() && it > 0f && it <= 1f }
        .distinct().sorted() + 1f)

    val size: Int get() = boundaries.size - 1

    private val ranges = List(size) { ReaderProgressChapter(boundaries[it], boundaries[it + 1]) }

    operator fun get(index: Int) = ranges[index]

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

/** Resting geometry and its readability decision, shared by every focus/animation frame. */
internal class ReaderProgressSegmentPlan(
    val detail: Float,
    val boundaries: List<Float>,
    val minimumScale: Float,
    val fallbackTicks: List<Float> = emptyList(),
)

/** Keep actual chapter boundaries, but merge markers too close to read on the rail. */
private fun readerProgressFallbackTicks(chapters: ReaderProgressChapters, widthDp: Float): List<Float> {
    if (!widthDp.isFinite() || widthDp <= 0f) return emptyList()
    val spacingDp = 10f
    val edgePaddingDp = 4f
    return buildList {
        var previousDp = -spacingDp
        for (index in 1 until chapters.size) {
            val fraction = chapters[index].start
            val positionDp = fraction * widthDp
            if (positionDp < edgePaddingDp || positionDp > widthDp - edgePaddingDp) continue
            if (positionDp - previousDp < spacingDp - 0.001f) continue
            add(fraction)
            previousDp = positionDp
        }
    }
}

private fun readerProgressSegmentPlan(
    chapters: ReaderProgressChapters,
    widthDp: Float,
    forceContinuous: Boolean = false,
): ReaderProgressSegmentPlan {
    val detail = if (forceContinuous) 0f else readerProgressSegmentDetail(chapters.size, widthDp)
    fun continuous() = ReaderProgressSegmentPlan(0f, List(chapters.size) { chapters[it].start } + 1f, 1f,
        readerProgressFallbackTicks(chapters, widthDp))
    if (detail == 0f) return continuous()

    // Water filling: reserve a minimum for short chapters, then scale the long ones to fit.
    val minimumSpan = min(8f / widthDp, 0.8f / chapters.size)
    var low = 0f
    var high = 1f
    repeat(32) {
        val scale = (low + high) / 2f
        val sum = (0 until chapters.size).sumOf { max(minimumSpan, chapters[it].span * scale).toDouble() }
        if (sum > 1.0) high = scale else low = scale
    }
    var start = 0f
    val bounds = List(chapters.size) { index ->
        start.also { start += max(minimumSpan, chapters[index].span * low) }
    } + 1f
    val minimumScale = (0 until chapters.size).minOf { index ->
        val span = bounds[index + 1] - bounds[index]
        val largestFocus = max(span + ReaderProgressFocusPaddingDp * 2f / widthDp, 32f / widthDp).coerceAtMost(0.95f)
        if (span < 1f) ((1f - largestFocus) / (1f - span)).coerceIn(0f, 1f) else 1f
    }

    // Judge the visible bodies after gaps and the worst permitted focus compression.
    // A 9 dp tall body narrower than 12 dp starts looking like a tick, not a chapter.
    val widths = List(chapters.size) { (bounds[it + 1] - bounds[it]) * widthDp * minimumScale }
    val gap = 2f * detail
    var thinCount = 0
    var thinRun = 0
    var longestThinRun = 0
    for (index in widths.indices) {
        val leftGap = if (index == 0) 0f else readerProgressBoundaryGap(widths[index - 1], widths[index], gap) / 2f
        val rightGap = if (index == widths.lastIndex) 0f else readerProgressBoundaryGap(widths[index], widths[index + 1], gap) / 2f
        val thin = widths[index] - leftGap - rightGap < 12f
        if (thin) thinCount++
        thinRun = if (thin) thinRun + 1 else 0
        longestThinRun = max(longestThinRun, thinRun)
    }
    // Preserve isolated interludes and small TOCs. Reject a picket fence or a book
    // whose segmented rail consists mostly of ticks, even when its average width fits.
    val crowded = longestThinRun >= 5 || (chapters.size >= 12 && thinCount * 3 >= chapters.size * 2)
    return if (crowded) continuous() else ReaderProgressSegmentPlan(detail, bounds, minimumScale)
}

/**
 * One scale for drawing, the cursor and touch input, including intermediate
 * animation frames. A focused chapter reserves space rather than covering its
 * neighbours. Its expansion is anchored to the finger when entering a chapter.
 */
internal class ReaderProgressLayout private constructor(
    val chapters: ReaderProgressChapters,
    val trackWidthDp: Float,
    private val positionCount: Int? = null,
    private val animatedBoundaries: List<Float>? = null,
    val focusedIndex: Int? = null,
    private val animatedInsets: List<Float>? = null,
    private val seekAnchor: ReaderProgressAnchor? = null,
    forceContinuous: Boolean = false,
    val segmentPlan: ReaderProgressSegmentPlan = readerProgressSegmentPlan(chapters, trackWidthDp, forceContinuous),
) {
    constructor(
        stops: List<Float>,
        trackWidthDp: Float,
        positionCount: Int? = null,
        forceContinuous: Boolean = false,
    ) : this(ReaderProgressChapters(stops), trackWidthDp, positionCount, forceContinuous = forceContinuous)

    val detail = segmentPlan.detail
    val segmented: Boolean get() = detail > 0f
    val boundaries: List<Float> = animatedBoundaries ?: segmentPlan.boundaries

    fun segment(index: Int) = ReaderProgressChapter(boundaries[index], boundaries[index + 1])

    private val minimumScale: Float get() = segmentPlan.minimumScale

    val insets: List<Float> = animatedInsets ?: List(chapters.size) { index ->
        if (focusedIndex == index && trackWidthDp > 0f) min(ReaderProgressFocusPaddingDp / trackWidthDp, segment(index).span / 2f) else 0f
    }

    fun inset(index: Int): Float {
        val start = boundaries[index]
        val end = boundaries[index + 1]
        var padding = min(insets[index], (end - start) / 2f)
        val anchor = seekAnchor?.takeIf { it.chapterIndex == index }
        if (anchor != null) {
            val local = chapterLocal(index, anchor.bookFraction)
            if (local > 0f && local < 1f) {
                // Near a rail edge, keep a nonzero interval on both sides of
                // the finger instead of changing pages as the capsule grows.
                padding = min(padding, min(anchor.trackFraction - start, end - anchor.trackFraction).coerceAtLeast(0f) / 2f)
            }
        }
        return padding
    }

    fun focused(fraction: Float, expanded: Boolean, anchor: ReaderProgressAnchor? = null): ReaderProgressLayout {
        // Dense books use a linear whole-book rail, including during a drag.
        // Hiding gaps alone would still leave chapter expansion and remapping active.
        if (!segmented) return this
        val index = chapters.indexAt(fraction)
        val chapter = chapters[index]
        val original = segment(index)
        val width = max(original.span + ReaderProgressFocusPaddingDp * 2f / trackWidthDp, if (expanded) 32f / trackWidthDp else 0f)
            .coerceAtMost(0.95f)
        val padding = min(ReaderProgressFocusPaddingDp / trackWidthDp, width / 2f)
        val pivot = anchor?.takeIf { it.chapterIndex == index }
            ?: ReaderProgressAnchor(index, fraction, trackFraction(fraction))
        val local = if (chapter.span > 0f) {
            ((pivot.bookFraction - chapter.start) / chapter.span).coerceIn(0f, 1f)
        } else 1f
        // Reserve a share of every chapter's resting width. A fixed reserve
        // per side lets successive finger anchors squeeze the far chapters
        // more and more. This common lower bound is independent of the
        // selected chapter and remains valid throughout a continuous drag.
        val minimumStart = original.start * minimumScale
        val maximumStart = (1f - width - (1f - original.end) * minimumScale).coerceAtLeast(0f)
        val start = (pivot.trackFraction - padding - local * (width - padding * 2f))
            .coerceIn(min(minimumStart, maximumStart), maximumStart)
        val end = start + width
        val bounds = List(chapters.size + 1) { boundary ->
            when {
                boundary <= index -> if (original.start > 0f) boundaries[boundary] / original.start * start else 0f
                boundary == index + 1 -> end
                original.end < 1f -> end + (boundaries[boundary] - original.end) / (1f - original.end) * (1f - end)
                else -> 1f
            }
        }
        return ReaderProgressLayout(chapters, trackWidthDp, positionCount, bounds, index, seekAnchor = anchor, segmentPlan = segmentPlan)
    }

    fun withGeometry(bounds: List<Float>, padding: List<Float>) =
        if (segmented) ReaderProgressLayout(chapters, trackWidthDp, positionCount, bounds, focusedIndex, padding, seekAnchor, segmentPlan = segmentPlan)
        else this

    private fun chapterLocal(index: Int, fraction: Float): Float {
        val chapter = chapters[index]
        return if (chapter.span > 0f) ((fraction - chapter.start) / chapter.span).coerceIn(0f, 1f) else 1f
    }

    fun trackFraction(bookFraction: Float): Float {
        val value = safeFraction(bookFraction)
        if (!segmented) return value
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
        if (!segmented) {
            val lastPosition = positionCount?.minus(1)?.takeIf { it > 0 } ?: return value
            return ReaderNavigationPolicy.progressTargetIndex(value, 0, lastPosition).toFloat() / lastPosition
        }
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

/** One animation clock moves every boundary and inset together, including interrupted drags. */
internal class ReaderProgressTransition(from: ReaderProgressLayout, private val to: ReaderProgressLayout) {
    // Flatten the interrupted frame once. Keeping an interpolated list here
    // would accumulate a chain of old transitions during a fast drag.
    private val fromBounds = from.boundaries.toFloatArray()
    private val toBounds = to.boundaries.toFloatArray()
    private val fromInsets = from.insets.toFloatArray()
    private val toInsets = to.insets.toFloatArray()

    val focusedIndex: Int? get() = to.focusedIndex

    fun frame(
        progress: Float,
        selection: ReaderProgressLayout = to,
        animateChapterChange: Boolean = true,
    ): ReaderProgressLayout {
        if (!selection.segmented) return selection
        // Page turns switch the chapter immediately. Interpolation is for
        // a dragged capsule or its collapse after releasing the finger.
        if (!animateChapterChange && selection.focusedIndex != to.focusedIndex) return selection
        val amount = progress.coerceIn(0f, 1f)
        if (amount == 1f) return if (selection === to) to else selection.withGeometry(to.boundaries, to.insets)
        return selection.withGeometry(
            InterpolatedCoordinates(fromBounds, toBounds, amount),
            InterpolatedCoordinates(fromInsets, toInsets, amount),
        )
    }

    private class InterpolatedCoordinates(
        private val from: FloatArray,
        private val to: FloatArray,
        private val amount: Float,
    ) : AbstractList<Float>() {
        override val size: Int get() = to.size
        override fun get(index: Int): Float = from[index] + (to[index] - from[index]) * amount
    }
}

/** Keep the pager's fractional position throughout a drag, settle or cancelled turn. */
internal fun readerProgressVisualFraction(fraction: Float): Float =
    if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f

/** A short chapter jump still waits for navigation to reach the selected page/item. */
internal fun readerProgressSeekReached(actual: Float, target: Float, positionCount: Int?): Boolean {
    if (!actual.isFinite() || !target.isFinite()) return false
    return if (positionCount != null && positionCount > 0) {
        ReaderNavigationPolicy.progressTargetIndex(actual, 0, positionCount - 1) ==
            ReaderNavigationPolicy.progressTargetIndex(target, 0, positionCount - 1)
    } else abs(actual - target) < 0.00001f
}

/** Cheap capacity gate; the segment plan also checks the actual distribution and focused geometry. */
internal fun readerProgressSegmentDetail(chapterCount: Int, trackWidthDp: Float): Float {
    if (chapterCount <= 1 || !trackWidthDp.isFinite() || trackWidthDp <= 0f) return 0f
    val averageWidth = trackWidthDp / chapterCount
    if (averageWidth < 8f) return 0f
    return ((averageWidth - 4f) / 5f).coerceIn(0f, 1f)
}

/** Short chapters retain at least 74% of their true span even between two separators. */
internal fun readerProgressBoundaryGap(leftWidthPx: Float, rightWidthPx: Float, preferredGapPx: Float): Float =
    min(preferredGapPx, min(leftWidthPx, rightWidthPx) * 0.26f).coerceAtLeast(0f)
