package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.INLINE_FONT_TYPOGRAPHY_TAG
import com.example.frogreader.data.model.decodeFontTypography
import com.example.frogreader.ui.theme.fileFontTextRuns
import com.example.frogreader.ui.theme.fontTypographyRequest
import com.example.frogreader.ui.theme.resolveFontTypography
import java.util.TreeMap

internal data class ReaderTypographyPlan(val text: AnnotatedString, val style: TextStyle)

private val decodedTypography = object : LinkedHashMap<String, FontTypography?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FontTypography?>?): Boolean = size > 128
}

private fun typographyOf(value: String): FontTypography? = synchronized(decodedTypography) {
    if (decodedTypography.containsKey(value)) decodedTypography[value]
    else decodeFontTypography(value).also { decodedTypography[value] = it }
}

/** Same family/size/axis resolution for native measurement and the displayed text. */
internal fun readerTypographyPlan(
    sourceText: AnnotatedString,
    style: TextStyle,
    density: Density,
    publisherStyles: Boolean,
    bookFonts: Map<String, FontFamily> = emptyMap(),
): ReaderTypographyPlan {
    // Ready/PagePart keep the parser's settings-independent annotations. An
    // old Ready can therefore be measured with a new preference immediately,
    // without carrying the previous publisher face into the new page cache.
    val text = sourceText.withBookFonts(bookFonts, enabled = publisherStyles)
    val baseSize = style.fontSize.takeIf { it.type == TextUnitType.Sp }?.value ?: 14f
    val baseFamily = style.fontFamily
    val baseRequest = baseFamily?.let(::fontTypographyRequest)
    val resolvedBase = baseFamily?.let { resolveFontTypography(it, baseSize * density.fontScale) }
    val resolvedStyle = if (resolvedBase != baseFamily) style.copy(fontFamily = resolvedBase) else style
    val marks = if (publisherStyles) text.getStringAnnotations(INLINE_FONT_TYPOGRAPHY_TAG, 0, text.length) else emptyList()
    if (text.isEmpty()) return ReaderTypographyPlan(text, resolvedStyle)

    // Sweep boundaries once. The active ranges are kept in Compose's source
    // order, including relative font-size spans from nested emphasis/ruby.
    val starts = HashMap<Int, MutableList<Int>>()
    val ends = HashMap<Int, MutableList<Int>>()
    val markStarts = HashMap<Int, MutableList<Int>>()
    val markEnds = HashMap<Int, MutableList<Int>>()
    val boundaries = sortedSetOf(0, text.length)
    text.spanStyles.forEachIndexed { index, span ->
        if (span.start < span.end) {
            starts.getOrPut(span.start) { ArrayList() }.add(index)
            ends.getOrPut(span.end) { ArrayList() }.add(index)
            boundaries += span.start; boundaries += span.end
        }
    }
    marks.forEachIndexed { index, mark ->
        if (mark.start < mark.end) {
            markStarts.getOrPut(mark.start) { ArrayList() }.add(index)
            markEnds.getOrPut(mark.end) { ArrayList() }.add(index)
            boundaries += mark.start; boundaries += mark.end
        }
    }
    val active = TreeMap<Int, SpanStyle>()
    val activeMarks = TreeMap<Int, FontTypography?>()
    data class Run(val start: Int, var end: Int, val family: FontFamily)
    val runs = ArrayList<Run>()
    data class FileRun(val start: Int, var end: Int, val family: FontFamily,
        val size: Float, val weight: Int, val italic: Boolean)
    val fileRuns = ArrayList<FileRun>()
    val fallbackRuns = ArrayList<AnnotatedString.Range<SpanStyle>>()
    val points = boundaries.toList()
    for (pointIndex in 0 until points.lastIndex) {
        val start = points[pointIndex]
        val end = points[pointIndex + 1]
        ends[start]?.forEach(active::remove)
        markEnds[start]?.forEach(activeMarks::remove)
        starts[start]?.forEach { active[it] = text.spanStyles[it].item }
        markStarts[start]?.forEach { activeMarks[it] = typographyOf(marks[it].item) }
        var family = baseFamily
        var size = baseSize
        var weight = style.fontWeight?.weight ?: 400
        var italic = style.fontStyle == FontStyle.Italic
        active.values.forEach { span ->
            span.fontFamily?.let { family = it }
            span.fontWeight?.let { weight = it.weight }
            span.fontStyle?.let { italic = it == FontStyle.Italic }
            when (span.fontSize.type) {
                TextUnitType.Sp -> size = span.fontSize.value
                TextUnitType.Em -> size *= span.fontSize.value
                else -> Unit
            }
        }
        val source = family ?: continue
        val authored = activeMarks.lastEntry()?.value
        val request = if (authored == null) baseRequest else FontTypography(
            variationAxes = authored.variationAxes ?: baseRequest?.variationAxes,
            stretchPercent = authored.stretchPercent ?: baseRequest?.stretchPercent,
            opticalSizing = authored.opticalSizing ?: baseRequest?.opticalSizing,
        )
        val resolved = resolveFontTypography(source, size * density.fontScale, request)
        val fileRun = fileRuns.lastOrNull()
        val physicalSize = size * density.fontScale
        if (fileRun != null && fileRun.end == start && fileRun.family === resolved &&
            fileRun.size == physicalSize && fileRun.weight == weight && fileRun.italic == italic) fileRun.end = end
        else fileRuns += FileRun(start, end, resolved, physicalSize, weight, italic)
        if (resolved == resolvedBase && source == baseFamily) continue
        val last = runs.lastOrNull()
        if (last != null && last.end == start && last.family == resolved) last.end = end
        else runs += Run(start, end, resolved)
    }
    // Resolve adjacent equivalent font requests together, retaining the original color/link spans.
    fileRuns.forEach { fileRun ->
        fileFontTextRuns(fileRun.family, fileRun.size, fileRun.weight, fileRun.italic,
            text.text, fileRun.start, fileRun.end)?.forEach { run ->
            fallbackRuns += AnnotatedString.Range(SpanStyle(fontFamily = run.family,
                fontWeight = run.fallback?.weight?.let(::FontWeight),
                fontStyle = run.fallback?.let { if (it.italic) FontStyle.Italic else FontStyle.Normal }),
                run.start, run.end)
        }
    }
    if (runs.isEmpty() && fallbackRuns.isEmpty()) return ReaderTypographyPlan(text, resolvedStyle)
    val result = AnnotatedString.Builder(text).apply {
        runs.forEach { addStyle(SpanStyle(fontFamily = it.family), it.start, it.end) }
        fallbackRuns.forEach { addStyle(it.item, it.start, it.end) }
    }.toAnnotatedString()
    return ReaderTypographyPlan(result, resolvedStyle)
}
