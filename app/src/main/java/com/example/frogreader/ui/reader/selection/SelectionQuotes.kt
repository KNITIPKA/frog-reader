package com.example.frogreader.ui.reader.selection

import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor

/** One saved highlight, kept separate even when another quote uses the same text. */
data class QuoteHighlight(val range: BookSelection, val color: QuoteColor)

/** Oldest first, used to break ties between identical or crossing quote ranges. */
fun quoteHighlights(quotes: List<Quote>): List<QuoteHighlight> =
    quotes.sortedBy { it.createdAtMillis }.mapNotNull { quote ->
        quote.range()?.let { QuoteHighlight(it, quote.color) }
    }

internal fun availableQuoteColors(selection: BookSelection?, quotes: List<Quote>): List<QuoteColor> {
    if (selection == null || selection.isEmpty) return QuoteColor.entries
    val used = quotes.mapNotNullTo(HashSet()) { quote ->
        val range = quote.range() ?: return@mapNotNullTo null
        quote.color.takeIf { selection.overlaps(range) }
    }
    return QuoteColor.entries.filterNot { it in used }
}

internal data class QuoteHighlightSpan(val span: CharSpan, val color: QuoteColor)

/**
 * Resolve overlaps once per fragment, so drawing never blends two saved colors together.
 * Inner quotes retain their colors even when a newer quote encloses them. Compare the
 * original book ranges here: page or paragraph clipping can make nested ranges look equal.
 * Identical and crossing ranges retain the newer quote's color in their overlap.
 */
internal fun quoteHighlightSpans(
    quotes: List<QuoteHighlight>,
    itemIndex: Int,
    charStart: Int,
    length: Int,
): List<QuoteHighlightSpan> {
    val intersections = quotes.mapNotNull { quote ->
        quote.range.intersect(itemIndex, charStart, length)?.let { quote to it }
    }
    val boundaries = intersections.flatMap { listOf(it.second.start, it.second.end) }.distinct().sorted()
    val result = ArrayList<QuoteHighlightSpan>()
    for (index in 0 until boundaries.lastIndex) {
        val start = boundaries[index]
        val end = boundaries[index + 1]
        val covering = intersections.filter { (_, span) -> span.start <= start && span.end >= end }
        val color = covering.lastOrNull { (quote, _) ->
            covering.none { (other, _) -> quote.range.strictlyContains(other.range) }
        }?.first?.color ?: continue
        val previous = result.lastOrNull()
        if (previous?.color == color && previous.span.end == start) {
            result[result.lastIndex] = previous.copy(span = CharSpan(previous.span.start, end))
        } else {
            result += QuoteHighlightSpan(CharSpan(start, end), color)
        }
    }
    return result
}

private fun BookSelection.strictlyContains(other: BookSelection): Boolean =
    this != other && start <= other.start && end >= other.end

private fun BookSelection.overlaps(other: BookSelection): Boolean =
    start < other.end && other.start < end

/**
 * Quotes whose visible highlight is touched by [selection].
 *
 * When a smaller quote is nested inside a larger one, selecting strictly inside
 * the inner quote targets only that inner quote — the outer quote's highlight is
 * not directly under the selection. Selecting part of the outer quote without
 * entering the inner one targets only the outer quote, and a selection that spans
 * across both targets both (works for any nesting depth of 2, 3, 4+ quotes).
 * Identical ranges target all quotes sharing that exact range.
 */
internal fun selectedQuoteIds(selection: BookSelection?, quotes: List<Quote>): Set<String> {
    if (selection == null || selection.isEmpty) return emptySet()
    val overlapping = quotes
        .sortedBy { it.createdAtMillis }
        .mapNotNull { quote ->
            val range = quote.range() ?: return@mapNotNull null
            if (selection.overlaps(range)) quote to range else null
        }
    if (overlapping.isEmpty()) return emptySet()

    val boundaries = buildList {
        add(selection.start)
        add(selection.end)
        overlapping.forEach { (_, range) ->
            add(maxOf(selection.start, range.start))
            add(minOf(selection.end, range.end))
        }
    }.distinct().sorted()

    val result = LinkedHashSet<String>()
    for (index in 0 until boundaries.lastIndex) {
        val start = boundaries[index]
        val end = boundaries[index + 1]
        val covering = overlapping.filter { (_, range) -> range.start <= start && range.end >= end }
        val innermost = covering.filter { (_, range) ->
            covering.none { (_, otherRange) -> range.strictlyContains(otherRange) }
        }
        val winnerRange = innermost.lastOrNull()?.second ?: continue
        innermost.forEach { (quote, range) ->
            if (range == winnerRange) result += quote.id
        }
    }
    return result
}
