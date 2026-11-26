package com.example.frogreader.data.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.BreakIterator
import java.util.Locale

/** Half-open UTF-16 range `[start, endExclusive)` of a matched highlight in original source text. */
data class MatchSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0 && endExclusive >= start) { "Invalid MatchSpan($start, $endExclusive)" }
    }
}

/** Context snippet around a match (`…how we choose to spend our time…`) with shifted [spans]. */
data class HighlightedSnippet(
    val text: String,
    val spans: List<MatchSpan>,
)

/**
 * Script-safe match locator, context snippet builder, and Compose [AnnotatedString] highlighter.
 *
 * Uses [SearchTextNormalizer.normalizeWithMapping] + ICU/JDK [BreakIterator] grapheme boundaries so:
 * - Stripped Arabic harakat/tatweel, Hebrew niqqud, Ukrainian apostrophe variants, and Latin accents
 *   all map back cleanly to exact UTF-16 ranges in the original unstripped text.
 * - Highlight spans apply ONLY foreground [Color] and background [Color] (never `fontWeight`,
 *   `fontSize`, `fontFamily`, or `letterSpacing`), keeping HarfBuzz font runs unified so Arabic
 *   cursive ligatures never disconnect at highlight boundaries.
 */
object SearchMatchHighlighter {

    /**
     * Finds all merged, non-overlapping UTF-16 [MatchSpan]s in [text] where a token of [query]
     * begins a word (the same rule as [SearchTextNormalizer.findWordPrefixMatches] and FTS5
     * `"token"*`). Only the typed part is highlighted (`[Tim]e`), never stray letters mid-word.
     */
    fun findMatchSpans(text: String, query: String): List<MatchSpan> {
        if (text.isEmpty() || query.isBlank()) return emptyList()
        val tokens = SearchTextNormalizer.tokenize(query)
            .filter { it.isNotEmpty() }
            .distinct()
        if (tokens.isEmpty()) return emptyList()

        val mapped = SearchTextNormalizer.normalizeWithMapping(text)
        val norm = mapped.normalized
        if (norm.isEmpty()) return emptyList()

        val rawRanges = ArrayList<MatchSpan>()
        for (token in tokens) {
            for (match in SearchTextNormalizer.findWordPrefixMatches(norm, token)) {
                val origStart = mapped.origStarts[match.start]
                val origEnd = mapped.origEnds[match.endExclusive - 1]
                if (origEnd > origStart) {
                    rawRanges += snapToGraphemeBoundaries(text, origStart, origEnd)
                }
            }
        }

        return mergeSpans(rawRanges)
    }

    /**
     * Builds a compact single-paragraph snippet centered around the first match in [text],
     * adding leading/trailing `…` ellipses when truncated and shifting all [MatchSpan]s accordingly.
     */
    fun extractContextSnippet(
        text: String,
        query: String,
        beforeChars: Int = 28,
        afterChars: Int = 34,
        maxFallbackChars: Int = 72,
    ): HighlightedSnippet {
        val collapsed = collapseInlineWhitespace(text)
        if (collapsed.isEmpty()) return HighlightedSnippet("", emptyList())

        val spans = findMatchSpans(collapsed, query)
        if (spans.isEmpty()) {
            if (collapsed.length <= maxFallbackChars) {
                return HighlightedSnippet(collapsed, emptyList())
            }
            val cut = snapWordOrGraphemeEnd(collapsed, maxFallbackChars)
            return HighlightedSnippet(collapsed.substring(0, cut).trimEnd() + "…", emptyList())
        }

        val first = spans.first()
        val rawStart = (first.start - beforeChars).coerceAtLeast(0)
        val rawEnd = (first.endExclusive + afterChars).coerceAtMost(collapsed.length)

        val winStart = if (rawStart == 0) 0 else snapWordStart(collapsed, rawStart, first.start)
        val winEnd = if (rawEnd == collapsed.length) collapsed.length else snapWordEnd(collapsed, rawEnd, first.endExclusive)

        val rawSlice = collapsed.substring(winStart, winEnd)
        val trimLead = rawSlice.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val trimTrail = rawSlice.indexOfLast { !it.isWhitespace() }.let { if (it < 0) rawSlice.length else it + 1 }

        val effectiveStart = winStart + trimLead
        val effectiveEnd = winStart + trimTrail
        if (effectiveEnd <= effectiveStart) {
            return HighlightedSnippet(collapsed, spans)
        }

        val prefix = if (effectiveStart > 0) "…" else ""
        val suffix = if (effectiveEnd < collapsed.length) "…" else ""
        val body = collapsed.substring(effectiveStart, effectiveEnd)
        val snippetText = prefix + body + suffix
        val shift = prefix.length - effectiveStart

        val shiftedSpans = spans.mapNotNull { span ->
            val clippedStart = maxOf(span.start, effectiveStart)
            val clippedEnd = minOf(span.endExclusive, effectiveEnd)
            if (clippedEnd > clippedStart) {
                MatchSpan(clippedStart + shift, clippedEnd + shift)
            } else {
                null
            }
        }
        return HighlightedSnippet(snippetText, shiftedSpans)
    }

    /**
     * Builds a Compose [AnnotatedString] applying ONLY [highlightBackground] and [highlightTextColor]
     * to each [MatchSpan] so cursive scripts (Arabic) preserve continuous HarfBuzz shaping.
     */
    fun buildHighlightedAnnotatedString(
        text: String,
        spans: List<MatchSpan>,
        highlightBackground: Color,
        highlightTextColor: Color,
    ): AnnotatedString {
        if (spans.isEmpty() || text.isEmpty()) return AnnotatedString(text)
        val highlightStyle = SpanStyle(
            color = highlightTextColor,
            background = highlightBackground,
        )
        return buildAnnotatedString {
            append(text)
            for (span in spans) {
                val safeStart = span.start.coerceIn(0, text.length)
                val safeEnd = span.endExclusive.coerceIn(safeStart, text.length)
                if (safeEnd > safeStart) {
                    addStyle(highlightStyle, start = safeStart, end = safeEnd)
                }
            }
        }
    }

    private fun collapseInlineWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var inSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) inSpace = true
            } else {
                if (inSpace) {
                    sb.append(' ')
                    inSpace = false
                }
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun snapToGraphemeBoundaries(text: String, start: Int, endExclusive: Int): MatchSpan {
        val iter = BreakIterator.getCharacterInstance(Locale.ROOT)
        iter.setText(text)
        val snappedStart = if (iter.isBoundary(start)) start else iter.preceding(start).coerceAtLeast(0)
        val snappedEnd = if (iter.isBoundary(endExclusive)) {
            endExclusive
        } else {
            val next = iter.following(endExclusive)
            if (next == BreakIterator.DONE) text.length else next
        }
        return MatchSpan(snappedStart, snappedEnd.coerceAtLeast(snappedStart))
    }

    private fun snapWordStart(text: String, target: Int, maxBound: Int): Int {
        var idx = target
        while (idx > 0 && idx < maxBound && !text[idx - 1].isWhitespace()) {
            idx++
        }
        return if (idx < maxBound) {
            snapToGraphemeBoundaries(text, idx, maxBound).start
        } else {
            snapToGraphemeBoundaries(text, target, maxBound).start
        }
    }

    private fun snapWordEnd(text: String, target: Int, minBound: Int): Int {
        var idx = target
        while (idx < text.length && idx > minBound && !text[idx].isWhitespace()) {
            idx--
        }
        return if (idx > minBound) {
            snapToGraphemeBoundaries(text, minBound, idx).endExclusive
        } else {
            snapToGraphemeBoundaries(text, minBound, target).endExclusive
        }
    }

    private fun snapWordOrGraphemeEnd(text: String, maxChars: Int): Int {
        val clamped = maxChars.coerceIn(0, text.length)
        var idx = clamped
        while (idx > maxChars / 2 && !text[idx - 1].isWhitespace()) {
            idx--
        }
        val candidate = if (idx > maxChars / 2) idx else clamped
        return snapToGraphemeBoundaries(text, 0, candidate).endExclusive
    }

    private fun mergeSpans(spans: List<MatchSpan>): List<MatchSpan> {
        if (spans.size <= 1) return spans
        val sorted = spans.sortedWith(compareBy<MatchSpan> { it.start }.thenByDescending { it.endExclusive })
        val merged = ArrayList<MatchSpan>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.start <= current.endExclusive) {
                current = MatchSpan(current.start, maxOf(current.endExclusive, next.endExclusive))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }
}
