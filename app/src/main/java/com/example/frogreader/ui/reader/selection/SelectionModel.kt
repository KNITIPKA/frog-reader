package com.example.frogreader.ui.reader.selection

import com.example.frogreader.data.model.Quote

/**
 * Where one edge of a selection sits **in the book** — flat element index plus
 * a character offset inside that element's own text.
 *
 * This is the whole reason selection can cross a page boundary. Compose's own
 * selection addresses positions inside live composables, so it dies the moment
 * the composable holding it leaves the composition — which is exactly what a
 * page turn (and every `LazyColumn` recycle) does. Book coordinates are
 * independent of what is on screen, so a selection survives page turns, list
 * recycling, a scroll/paged switch and a full re-pagination without anyone
 * having to remap anything.
 *
 * The coordinate space is the same one the paginator and the search already
 * speak: `BookPage.firstItemIndex`/`firstCharOffset` and `SearchMatch`.
 */
data class BookAnchor(val itemIndex: Int, val charOffset: Int) : Comparable<BookAnchor> {
    override fun compareTo(other: BookAnchor): Int = when {
        itemIndex != other.itemIndex -> itemIndex.compareTo(other.itemIndex)
        else -> charOffset.compareTo(other.charOffset)
    }
}

/** Half-open character span `[start, end)`, local to one text fragment. */
data class CharSpan(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** Which end of a selection a handle belongs to. */
enum class SelectionEdge { START, END }

/**
 * A selection, normalized so [start] never comes after [end].
 *
 * Build it with [of] rather than the constructor: a drag routinely produces a
 * backwards pair (the finger moves above where it started) and every consumer
 * wants document order.
 */
data class BookSelection(val start: BookAnchor, val end: BookAnchor) {

    init {
        require(start <= end) { "BookSelection must be normalized: $start > $end" }
    }

    val isEmpty: Boolean get() = start == end

    /** True while the selection lies inside a single element. */
    val isSingleElement: Boolean get() = start.itemIndex == end.itemIndex

    /**
     * The part of this selection a fragment has to paint, in the fragment's
     * own character coordinates — or null when the fragment is outside it.
     *
     * A "fragment" is one rendered `Text`: in paged mode the slice of an
     * element that landed on this page (`PagePart.charStart`), in scroll mode
     * usually the whole element. Intersecting per fragment is what lets the
     * same selection paint correctly on both sides of a page break.
     */
    fun intersect(itemIndex: Int, charStart: Int, length: Int): CharSpan? {
        if (isEmpty) return null
        if (itemIndex < start.itemIndex || itemIndex > end.itemIndex) return null

        val fragmentEnd = charStart + length
        val from = if (itemIndex == start.itemIndex) start.charOffset else charStart
        val to = if (itemIndex == end.itemIndex) end.charOffset else fragmentEnd

        val low = maxOf(from, charStart)
        val high = minOf(to, fragmentEnd)
        if (high <= low) return null
        return CharSpan(low - charStart, high - charStart)
    }

    operator fun contains(anchor: BookAnchor): Boolean = anchor >= start && anchor <= end

    /** The same selection with [edge] moved to [anchor] (re-normalizing). */
    fun move(edge: SelectionEdge, anchor: BookAnchor): BookSelection = when (edge) {
        SelectionEdge.START -> of(anchor, end)
        SelectionEdge.END -> of(start, anchor)
    }

    /** Grows to cover [other] as well — how a word-granularity drag extends. */
    fun union(other: BookSelection): BookSelection =
        BookSelection(minOf(start, other.start), maxOf(end, other.end))

    companion object {
        fun of(a: BookAnchor, b: BookAnchor): BookSelection =
            if (a <= b) BookSelection(a, b) else BookSelection(b, a)

        fun of(itemIndex: Int, span: CharSpan): BookSelection =
            BookSelection(
                BookAnchor(itemIndex, span.start),
                BookAnchor(itemIndex, span.end),
            )
    }
}

/**
 * Where a saved quote is in the book, or null when it carries no anchors —
 * one saved before quotes had any, or one whose book file was replaced.
 */
fun Quote.range(): BookSelection? {
    if (startItem < 0 || startChar < 0 || endItem < 0 || endChar < 0) return null
    val range = BookSelection.of(
        BookAnchor(startItem, startChar),
        BookAnchor(endItem, endChar),
    )
    return range.takeUnless { it.isEmpty }
}
