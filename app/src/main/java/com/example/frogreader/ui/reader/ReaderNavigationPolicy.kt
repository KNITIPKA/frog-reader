package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.ContentElement
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure policies shared by reader surfaces and covered without Compose UI. */
internal object ReaderNavigationPolicy {

    /** Shared by the return label and the actual seek, including split paragraphs. */
    fun pageIndexForLocation(pages: List<BookPage>, location: ReaderReturnLocation.Main): Int? {
        if (pages.isEmpty()) return null
        val item = location.flatItemIndex
        val char = location.charOffset ?: 0
        // Pagination emits source anchors in order. A collapsed float uses
        // its preceding, fully suppressed source range as the first anchor;
        // paragraph/table continuations then advance within the same item.
        // Choose the last equal anchor, including duplicate chapter starts.
        return lastSortedIndex(pages.size) { index ->
            val page = pages[index]
            page.firstItemIndex < item ||
                (page.firstItemIndex == item && page.firstCharOffset <= char)
        }.coerceAtLeast(0)
    }

    /** Empty chapters can share a start; the last such chapter owns the item. */
    fun chapterIndexAt(chapterStarts: List<Int>, itemIndex: Int): Int =
        chapterIndexAtOrBefore(chapterStarts, itemIndex).coerceAtLeast(0)

    private fun chapterIndexAtOrBefore(chapterStarts: List<Int>, itemIndex: Int): Int =
        lastSortedIndex(chapterStarts.size) { chapterStarts[it] <= itemIndex }

    /** Upper bound over a sorted list without allocating a key/anchor array. */
    private inline fun lastSortedIndex(size: Int, atOrBefore: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (atOrBefore(middle)) low = middle + 1 else high = middle
        }
        return low - 1
    }

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
        val chapter = chapterIndexAtOrBefore(chapterStarts, partialFirstItem)
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

    /** The first source position not yet drawn, including split table rows. */
    fun continuationAfter(page: BookPage): ReaderReturnLocation.Main? {
        val last = page.parts.lastOrNull() ?: return null
        val unfinishedOffset = when (last.element) {
            is ContentElement.Paragraph, is ContentElement.Heading -> when {
                last.text == null -> 0 // A float's fallback image precedes its text.
                !last.publisherEndsElement && last.charEnd >= 0 -> last.charEnd
                else -> null
            }
            is ContentElement.Table -> last.rowEnd.takeIf { !last.publisherEndsElement && it >= 0 }
            else -> null
        }
        return unfinishedOffset?.let { ReaderReturnLocation.Main(last.itemIndex, it) }
            ?: ReaderReturnLocation.Main((last.itemIndex.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Resume one forward turn at the first unread source position. A short
     * initial preview can end on an open page: expansion may add text to that
     * same page, so blindly advancing one page would skip its new remainder.
     * Only a complete holder may resolve a turn onto the completion screen.
     */
    fun pageForPreparedContinuation(
        pages: List<BookPage>,
        unread: ReaderReturnLocation.Main,
        partialPagination: Boolean,
    ): Int? {
        if (paginationContainsLocation(pages, unread)) return pageIndexForLocation(pages, unread)
        if (partialPagination) return null
        val end = pages.lastOrNull()?.let(::continuationAfter) ?: return null
        return pages.size.takeIf {
            unread.flatItemIndex > end.flatItemIndex ||
                unread.flatItemIndex == end.flatItemIndex &&
                (unread.charOffset ?: 0) >= (end.charOffset ?: 0)
        }
    }

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
