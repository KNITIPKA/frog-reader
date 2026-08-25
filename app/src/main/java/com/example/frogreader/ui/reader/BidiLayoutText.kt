package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.InlineBidiMode

/**
 * A layout-only representation of authored text containing inline bidi
 * scopes. [source] is always the exact searchable/copyable book text;
 * [display] additionally contains Unicode Bidirectional Algorithm controls.
 * The two maps make selection, pagination and highlighting stay in source
 * coordinates even though Compose lays out the display copy.
 */
class BidiLayoutText private constructor(
    val source: AnnotatedString,
    val display: AnnotatedString,
    private val sourceStarts: IntArray,
    private val sourceEnds: IntArray,
    private val displayOffsets: IntArray,
) {
    val hasControls: Boolean get() = source !== display

    /** Source boundary used at the inclusive start of a painted/measured range. */
    fun layoutStart(sourceOffset: Int): Int =
        sourceStarts[sourceOffset.coerceIn(0, source.length)]

    /** Source boundary used at the exclusive end of a painted/measured range. */
    fun layoutEnd(sourceOffset: Int): Int =
        sourceEnds[sourceOffset.coerceIn(0, source.length)]

    /** A Compose layout/caret offset back in clean source-text coordinates. */
    fun sourceOffset(layoutOffset: Int): Int =
        displayOffsets[layoutOffset.coerceIn(0, display.length)]

    companion object {
        fun of(source: AnnotatedString): BidiLayoutText {
            val rawRanges = source.getStringAnnotations(BIDI_TAG, 0, source.length)
            if (rawRanges.isEmpty()) return identity(source)

            val scopes = rawRanges.mapIndexedNotNull { order, range ->
                val mode = runCatching { InlineBidiMode.valueOf(range.item) }.getOrNull()
                    ?: return@mapIndexedNotNull null
                val start = range.start.coerceIn(0, source.length)
                val end = range.end.coerceIn(start, source.length)
                if (start == end) null else Scope(start, end, mode, order)
            }.distinctBy { listOf(it.start, it.end, it.mode) }
            if (scopes.isEmpty()) return identity(source)

            val opens = scopes.groupBy { it.start }
            val closes = scopes.groupBy { it.end }
            val sourceStarts = IntArray(source.length + 1)
            val sourceEnds = IntArray(source.length + 1)
            val displayToSource = ArrayList<Int>(source.length + scopes.size * 2 + 1)
            val text = StringBuilder(source.length + scopes.size * 2)
            displayToSource += 0

            fun appendControls(value: String, sourceBoundary: Int) {
                for (char in value) {
                    text.append(char)
                    displayToSource += sourceBoundary
                }
            }

            for (sourceOffset in 0..source.length) {
                // An exclusive end stops before its closing controls. An
                // inclusive start begins after all controls at the boundary.
                sourceEnds[sourceOffset] = text.length
                closes[sourceOffset]
                    ?.sortedWith(compareByDescending<Scope> { it.start }.thenByDescending { it.order })
                    ?.forEach { appendControls(it.mode.close, sourceOffset) }
                opens[sourceOffset]
                    ?.sortedWith(compareByDescending<Scope> { it.end }.thenBy { it.order })
                    ?.forEach { appendControls(it.mode.open, sourceOffset) }
                sourceStarts[sourceOffset] = text.length
                if (sourceOffset < source.length) {
                    text.append(source[sourceOffset])
                    displayToSource += sourceOffset + 1
                }
            }

            val display = copyAnnotations(
                source = source,
                displayText = text.toString(),
                starts = sourceStarts,
                ends = sourceEnds,
            )
            return BidiLayoutText(
                source,
                display,
                sourceStarts,
                sourceEnds,
                displayToSource.toIntArray(),
            )
        }

        private fun identity(source: AnnotatedString): BidiLayoutText {
            val offsets = IntArray(source.length + 1) { it }
            return BidiLayoutText(source, source, offsets, offsets, offsets)
        }

        /** Copies every public Compose annotation except our private bidi metadata. */
        private fun copyAnnotations(
            source: AnnotatedString,
            displayText: String,
            starts: IntArray,
            ends: IntArray,
        ): AnnotatedString {
            val builder = AnnotatedString.Builder(displayText)
            fun mappedStart(offset: Int) = starts[offset.coerceIn(0, source.length)]
            fun mappedEnd(offset: Int) = ends[offset.coerceIn(0, source.length)]

            for (range in source.spanStyles) {
                builder.addStyle(range.item, mappedStart(range.start), mappedEnd(range.end))
            }
            for (range in source.paragraphStyles) {
                builder.addStyle(range.item, mappedStart(range.start), mappedEnd(range.end))
            }
            for (range in source.getStringAnnotations(0, source.length)) {
                if (range.tag == BIDI_TAG) continue
                builder.addStringAnnotation(
                    range.tag,
                    range.item,
                    mappedStart(range.start),
                    mappedEnd(range.end),
                )
            }
            for (range in source.getTtsAnnotations(0, source.length)) {
                builder.addTtsAnnotation(
                    range.item,
                    mappedStart(range.start),
                    mappedEnd(range.end),
                )
            }
            for (range in source.getLinkAnnotations(0, source.length)) {
                when (val link = range.item) {
                    is LinkAnnotation.Url -> builder.addLink(
                        link, mappedStart(range.start), mappedEnd(range.end),
                    )
                    is LinkAnnotation.Clickable -> builder.addLink(
                        link, mappedStart(range.start), mappedEnd(range.end),
                    )
                }
            }
            return builder.toAnnotatedString()
        }
    }

    private class Scope(
        val start: Int,
        val end: Int,
        val mode: InlineBidiMode,
        val order: Int,
    )
}

private val InlineBidiMode.open: String
    get() = when (this) {
        InlineBidiMode.ISOLATE_AUTO, InlineBidiMode.PLAINTEXT -> "\u2068" // FSI
        InlineBidiMode.ISOLATE_LTR -> "\u2066" // LRI
        InlineBidiMode.ISOLATE_RTL -> "\u2067" // RLI
        InlineBidiMode.EMBED_LTR -> "\u202A" // LRE
        InlineBidiMode.EMBED_RTL -> "\u202B" // RLE
        InlineBidiMode.OVERRIDE_LTR -> "\u202D" // LRO
        InlineBidiMode.OVERRIDE_RTL -> "\u202E" // RLO
        InlineBidiMode.ISOLATE_OVERRIDE_LTR -> "\u2066\u202D" // LRI LRO
        InlineBidiMode.ISOLATE_OVERRIDE_RTL -> "\u2067\u202E" // RLI RLO
    }

private val InlineBidiMode.close: String
    get() = when (this) {
        InlineBidiMode.ISOLATE_AUTO,
        InlineBidiMode.ISOLATE_LTR,
        InlineBidiMode.ISOLATE_RTL,
        InlineBidiMode.PLAINTEXT -> "\u2069" // PDI
        InlineBidiMode.EMBED_LTR,
        InlineBidiMode.EMBED_RTL,
        InlineBidiMode.OVERRIDE_LTR,
        InlineBidiMode.OVERRIDE_RTL -> "\u202C" // PDF
        InlineBidiMode.ISOLATE_OVERRIDE_LTR,
        InlineBidiMode.ISOLATE_OVERRIDE_RTL -> "\u202C\u2069" // PDF PDI
    }
