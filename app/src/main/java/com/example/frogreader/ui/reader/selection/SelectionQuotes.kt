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
    val ids = selectedQuoteIds(selection, quotes)
    val used = quotes.filter { it.id in ids }.mapTo(HashSet()) { it.color }
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

/**
 * Quotes touched by the selection, including a single word inside a quote.
 * Compare book positions: identical text elsewhere must never offer deletion
 * of an unrelated quote. Ranges are half-open, so merely touching an endpoint
 * is not an intersection.
 */
internal fun selectedQuoteIds(selection: BookSelection?, quotes: List<Quote>): Set<String> {
    if (selection == null || selection.isEmpty) return emptySet()
    return quotes.mapNotNull { quote ->
        val range = quote.range() ?: return@mapNotNull null
        quote.id.takeIf { selection.start < range.end && range.start < selection.end }
    }.toSet()
}
