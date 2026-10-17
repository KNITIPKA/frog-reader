package com.example.frogreader.ui.reader

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.completeMathInlineMarks
import com.example.frogreader.data.model.decodeInlineTextBoxSpec

private data class ReaderInlineEntry(val start: Int, val end: Int, val id: String, val content: InlineTextContent)

private val readerInlineAnnotationTag = AnnotatedString.Builder().apply {
    appendInlineContent("reader-inline-probe")
}.toAnnotatedString().getStringAnnotations(0, 1).single().tag

/** A single native contract for measurement, page fragments and drawable text. */
private fun readerInlineEntries(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    bookFonts: Map<String, FontFamily>,
    maxWidthPx: Int?,
    publisherStyles: Boolean,
    invertImages: Boolean = false,
): List<ReaderInlineEntry> {
    val entries = ArrayList<ReaderInlineEntry>()
    val images = inlineImageContent(text, invertImages)
    for (mark in text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)) {
        images[mark.item]?.let { entries += ReaderInlineEntry(mark.start, mark.end, mark.item, it) }
    }
    for (math in mathInlinePlacements(text, style, measurer, density, maxWidthPx)) {
        entries += ReaderInlineEntry(math.start, math.end, math.id,
            InlineTextContent(math.placeholder) { math.Content() })
    }
    for (ruby in rubyInlineLayouts(text, style, measurer, density, bookFonts.takeIf { publisherStyles }.orEmpty(), publisherStyles)) {
        entries += ReaderInlineEntry(ruby.start, ruby.end, ruby.id,
            InlineTextContent(ruby.placeholder) { ruby.Content() })
    }
    if (publisherStyles) for (box in inlineTextBoxLayouts(text, style, measurer, density)) {
        entries += ReaderInlineEntry(box.start, box.end, box.id,
            InlineTextContent(box.placeholder) { box.Content() })
    }
    // Native placeholders cannot overlap. An unsupported nested composition
    // keeps its outer admitted layout; unadmitted ranges retain source text.
    val admitted = ArrayList<ReaderInlineEntry>(entries.size)
    var end = -1
    for (entry in entries.sortedWith(compareBy<ReaderInlineEntry> { it.start }.thenByDescending { it.end })) {
        if (entry.start < end || entry.start < 0 || entry.end > text.length || entry.start >= entry.end) continue
        admitted += entry.copy(id = "${entry.id}:range:${entry.start}:${entry.end}")
        end = entry.end
    }
    return admitted
}

internal fun readerInlinePlaceholders(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    bookFonts: Map<String, FontFamily> = emptyMap(),
    maxWidthPx: Int? = null,
    publisherStyles: Boolean = true,
): List<AnnotatedString.Range<Placeholder>> {
    val typography = readerTypographyPlan(text, style, density, publisherStyles, bookFonts)
    return readerInlineEntries(typography.text, typography.style, measurer, density, bookFonts, maxWidthPx, publisherStyles)
        .map { AnnotatedString.Range(it.content.placeholder, it.start, it.end) }
}

/** The wrapper prevents individual measurement paths from forgetting inline geometry. */
internal fun TextMeasurer.measureReader(
    text: AnnotatedString,
    style: TextStyle = TextStyle.Default,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    constraints: Constraints = Constraints(),
    density: Density,
    bookFonts: Map<String, FontFamily> = emptyMap(),
    publisherStyles: Boolean = true,
): TextLayoutResult {
    val typography = readerTypographyPlan(text, style, density, publisherStyles, bookFonts)
    val entries = readerInlineEntries(typography.text, typography.style, this, density, bookFonts,
        constraints.maxWidth.takeIf { it != Constraints.Infinity }, publisherStyles)
    val ranges = entries.map { it.start to it.end }
    val layoutStyle = readerInlineLayoutStyle(typography.style,
        entries.map { it.content.placeholder }, density, entries.any { it.end - it.start > 1 })
    return measure(readerInlineLayoutText(typography.text, ranges, entries.associate { (it.start to it.end) to it.id },
        naturalMetrics = typography.style.lineHeight != layoutStyle.lineHeight), layoutStyle, overflow, softWrap, maxLines,
        placeholders = entries.map { AnnotatedString.Range(it.content.placeholder, it.start, it.end) },
        constraints = constraints)
}

private class ReaderInlineTextPlan(val text: AnnotatedString, val style: TextStyle,
    val content: Map<String, InlineTextContent>)

/** Android's fixed LineHeightSpan overwrites a later inline object's expanded metrics. */
internal fun readerInlineLayoutStyle(style: TextStyle, placeholders: List<Placeholder>, density: Density,
    hasRichReplacement: Boolean = false): TextStyle {
    // Android's justified caret positions can omit expanded word gaps around
    // multi-character ReplacementSpan runs. Keep native objects and visible
    // text in the same horizontal coordinate system until that platform path
    // can provide reliable justified positions.
    // High-quality line breaking may also leave a full-column replacement
    // after preceding words. Simple breaking accounts for its complete width
    // and moves it intact to the next line.
    val aligned = if (hasRichReplacement)
        style.copy(textAlign = if (style.textAlign == TextAlign.Justify) TextAlign.Start else style.textAlign,
            lineBreak = LineBreak.Simple) else style
    if (style.lineHeight == TextUnit.Unspecified || style.fontSize.type != TextUnitType.Sp ||
        placeholders.isEmpty()) return aligned
    val fontPx = with(density) { style.fontSize.toPx() }
    val linePx = if (style.lineHeight.type == TextUnitType.Em) style.lineHeight.value * fontPx
        else with(density) { style.lineHeight.toPx() }
    val needsNaturalMetrics = placeholders.any { placeholder ->
        val height = if (placeholder.height.type == TextUnitType.Em) placeholder.height.value * fontPx
            else with(density) { placeholder.height.toPx() }
        height > linePx
    }
    // Only these paragraphs need per-line ascent/descent from ReplacementSpan.
    // A fixed height (including Minimum mode) caches the first line's metrics
    // on Android, so later formula/ruby lines cannot expand safely.
    return if (needsNaturalMetrics) aligned.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null) else aligned
}

@Composable
internal fun readerInlineContent(
    text: AnnotatedString,
    style: TextStyle,
    invertImages: Boolean = false,
    bookFonts: Map<String, FontFamily> = emptyMap(),
    maxWidthPx: Int? = null,
    publisherStyles: Boolean = true,
): Map<String, InlineTextContent> = readerInlineTextPlan(text, style, invertImages, bookFonts,
    maxWidthPx, publisherStyles).content

@Composable
private fun readerInlineTextPlan(
    text: AnnotatedString,
    style: TextStyle,
    invertImages: Boolean,
    bookFonts: Map<String, FontFamily>,
    maxWidthPx: Int?,
    publisherStyles: Boolean,
): ReaderInlineTextPlan {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, measurer, density, bookFonts, maxWidthPx, publisherStyles, invertImages) {
        val typography = readerTypographyPlan(text, style, density, publisherStyles, bookFonts)
        val entries = readerInlineEntries(typography.text, typography.style, measurer, density, bookFonts, maxWidthPx, publisherStyles, invertImages)
        val layoutStyle = readerInlineLayoutStyle(typography.style, entries.map { it.content.placeholder }, density,
            entries.any { it.end - it.start > 1 })
        ReaderInlineTextPlan(readerInlineLayoutText(typography.text, entries.map { it.start to it.end },
            entries.associate { (it.start to it.end) to it.id }, naturalMetrics = typography.style.lineHeight != layoutStyle.lineHeight),
            layoutStyle,
            entries.associate { it.id to it.content })
    }
}

@Composable
internal fun ReaderText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    invertImages: Boolean = false,
    bookFonts: Map<String, FontFamily> = emptyMap(),
    maxWidthPx: Int? = null,
    publisherStyles: Boolean = true,
) {
    val plan = readerInlineTextPlan(text, style, invertImages, bookFonts, maxWidthPx, publisherStyles)
    Text(text = plan.text, style = plan.style, modifier = modifier, overflow = overflow, onTextLayout = onTextLayout,
        inlineContent = plan.content)
}

/**
 * Android ReplacementSpan must own one uninterrupted shaping run. Internal
 * fallback styles otherwise split a rich multi-character placeholder. The
 * native child receives the original styled text. Source text and every
 * annotation offset remain unchanged for selection and accessibility.
 */
internal fun readerInlineLayoutText(text: AnnotatedString, ranges: List<Pair<Int, Int>>,
    contentIds: Map<Pair<Int, Int>, String> = emptyMap(), naturalMetrics: Boolean = false): AnnotatedString {
    if (ranges.isEmpty()) return text
    val ordered = ranges.sortedBy { it.first }
    // One-character images keep inherited em sizing. There is no internal
    // font boundary that could split their ReplacementSpan.
    val masked = ordered.filter { it.second - it.first > 1 }
    return AnnotatedString.Builder(text.text).apply {
        for (span in text.spanStyles) {
            var start = span.start
            for ((first, last) in masked) {
                if (last <= start) continue
                if (first >= span.end) break
                if (first > start) addStyle(span.item, start, minOf(first, span.end))
                start = maxOf(start, last)
                if (start >= span.end) break
            }
            if (start < span.end) addStyle(span.item, start, span.end)
        }
        for (range in text.paragraphStyles) {
            val rich = masked.any { it.first < range.end && it.second > range.start }
            val item = if (rich) range.item.copy(
                textAlign = if (range.item.textAlign == TextAlign.Justify) TextAlign.Start else range.item.textAlign,
                lineHeight = if (naturalMetrics) TextUnit.Unspecified else range.item.lineHeight,
                lineHeightStyle = if (naturalMetrics) null else range.item.lineHeightStyle,
            ) else range.item
            addStyle(item, range.start, range.end)
        }
        for (range in text.getStringAnnotations(0, text.length)) {
            // Ruby can own an adjacent Latin word while keeping its source
            // annotation on the original base/reading. The admitted native
            // range is the authoritative Foundation placeholder range.
            if (range.tag == readerInlineAnnotationTag &&
                contentIds.keys.any { it.first < range.end && it.second > range.start }) continue
            addStringAnnotation(range.tag, range.item, range.start, range.end)
        }
        for ((range, id) in contentIds) addStringAnnotation(readerInlineAnnotationTag, id, range.first, range.second)
        for (range in text.getTtsAnnotations(0, text.length)) addTtsAnnotation(range.item, range.start, range.end)
        for (range in text.getLinkAnnotations(0, text.length)) when (val link = range.item) {
            is LinkAnnotation.Url -> addLink(link, range.start, range.end)
            is LinkAnnotation.Clickable -> addLink(link, range.start, range.end)
        }
    }.toAnnotatedString()
}

/** Retreat an otherwise legal word break out of an atomic authored inline run. */
internal fun readerAtomicRanges(text: AnnotatedString): List<Pair<Int, Int>> {
    val marks = ArrayList<Pair<Int, Int>>()
    text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length).forEach { marks += it.start to it.end }
    completeMathInlineMarks(text).forEach { marks += it.start to it.end }
    rubyInlineSourceRanges(text).forEach { marks += it.start to it.end }
    text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, text.length).forEach { mark ->
        val box = decodeInlineTextBoxSpec(mark.item)
        if (box != null && mark.end - mark.start == box.sourceLength) marks += mark.start to mark.end
    }
    return marks.sortedBy { it.first }
}

internal fun readerAtomicBreakOffset(text: AnnotatedString, candidate: Int, start: Int = 0): Int {
    val marks = readerAtomicRanges(text)
    var offset = candidate.coerceIn(start, text.length)
    for ((first, last) in marks.sortedByDescending { it.second - it.first }) {
        if (offset > first && offset < last) offset = if (first > start) first else last
    }
    return offset
}

/** Whitespace inside an inline object does not create a column break opportunity. */
internal fun readerUnbreakableRuns(text: AnnotatedString): List<IntRange> {
    val atoms = readerAtomicRanges(text)
    val result = ArrayList<IntRange>()
    var atom = 0
    var cursor = 0
    var start = -1
    while (cursor < text.length) {
        while (atom < atoms.size && atoms[atom].second <= cursor) atom++
        if (atom < atoms.size && atoms[atom].first == cursor && atoms[atom].second > cursor) {
            if (start < 0) start = cursor
            cursor = atoms[atom].second
            continue
        }
        if (text[cursor] in " \t\n\r\u000C") {
            if (start >= 0) result += start until cursor
            start = -1
        } else if (start < 0) start = cursor
        cursor++
    }
    if (start >= 0) result += start until cursor
    return result
}
