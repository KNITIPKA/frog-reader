package com.example.frogreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.INLINE_RUBY_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.INLINE_MATH_TAG
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.InlineRuby
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

/** Text layout and drawable share precisely the same measured geometry. */
internal class RubyInlineLayout(
    val start: Int,
    val end: Int,
    val id: String,
    val placeholder: Placeholder,
    private val base: TextLayoutResult,
    private val reading: TextLayoutResult,
    private val baseLeftPx: Float,
    private val readingLeftPx: Float,
    private val baseTopPx: Float,
    private val description: String,
) {
    @Composable
    fun Content() {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            drawText(reading, topLeft = Offset(readingLeftPx, 0f))
            drawText(base, topLeft = Offset(baseLeftPx, baseTopPx))
        }
    }
}

/** Presentation coordinates only; the original ruby/source annotations stay unchanged. */
internal data class RubyInlineSourceRange(
    val start: Int,
    val end: Int,
    val markStart: Int,
    val markEnd: Int,
    val baseText: AnnotatedString,
    /** Authored ruby base within the complete visible word. */
    val baseStart: Int,
    val baseEnd: Int,
    val ruby: InlineRuby,
)

/**
 * A ReplacementSpan is a legal Android line break boundary even in the middle
 * of a Latin word. Join a short adjacent word into the same native run, keeping
 * the reading over the author's base, rather than over the whole joined word.
 * No reading/source characters are removed from the parent document.
 */
internal fun rubyInlineSourceRanges(text: AnnotatedString): List<RubyInlineSourceRange> {
    val marks = text.getStringAnnotations(INLINE_RUBY_TAG, 0, text.length)
    if (marks.isEmpty()) return emptyList()
    val nativeTags = setOf(INLINE_RUBY_TAG, INLINE_IMAGE_TAG, INLINE_MATH_TAG, INLINE_TEXT_BOX_TAG)
    val barriers = text.getStringAnnotations(0, text.length).filter { it.tag in nativeTags }
    return marks.mapNotNull { mark ->
        val ruby = decodedRuby(mark.item) ?: return@mapNotNull null
        if (mark.end - mark.start != ruby.sourceLength || mark.start < 0 || mark.end > text.length) {
            return@mapNotNull null
        }
        val base = text.subSequence(mark.start, mark.start + ruby.baseLength)
        if ('\n' in base.text || base.getStringAnnotations(INLINE_IMAGE_TAG, 0, base.length).isNotEmpty() ||
            base.getStringAnnotations(INLINE_RUBY_TAG, 0, base.length)
                .any { it.start != 0 || it.end != base.length }) return@mapNotNull null

        var start = mark.start
        var end = mark.end
        if (base.text.all { it.isLatinWordCharacter() || it == ' ' } &&
            base.first().isLatinWordCharacter() && base.last().isLatinWordCharacter()) {
            val others = barriers.filter { it.start != mark.start || it.end != mark.end }
            val leftLimit = others.filter { it.end <= mark.start }.maxOfOrNull { it.end } ?: 0
            val rightLimit = others.filter { it.start >= mark.end }.minOfOrNull { it.start } ?: text.length
            while (start > leftLimit && mark.start - start < MAX_JOINED_LATIN_LENGTH &&
                text[start - 1].isLatinWordCharacter()) start--
            while (end < rightLimit && end - mark.end < MAX_JOINED_LATIN_LENGTH &&
                text[end].isLatinWordCharacter()) end++
            // Do not partially absorb an unbounded word, or claim the same
            // intervening letters as another native object in that word.
            if (start > 0 && text[start - 1].isLatinWordCharacter()) start = mark.start
            if (end < text.length && text[end].isLatinWordCharacter()) end = mark.end
            if (mark.start - start + base.length + end - mark.end > MAX_JOINED_LATIN_LENGTH) {
                start = mark.start
                end = mark.end
            }
        }
        val visible = AnnotatedString.Builder().apply {
            append(text.subSequence(start, mark.start))
            append(base)
            append(text.subSequence(mark.end, end))
        }.toAnnotatedString()
        val baseStart = mark.start - start
        RubyInlineSourceRange(start, end, mark.start, mark.end, visible,
            baseStart, baseStart + base.length, ruby)
    }
}

private const val MAX_JOINED_LATIN_LENGTH = 64

private fun Char.isLatinWordCharacter(): Boolean {
    val script = Character.UnicodeScript.of(code)
    return when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() ->
            script == Character.UnicodeScript.INHERITED || script == Character.UnicodeScript.LATIN
        else -> isLetter() && script == Character.UnicodeScript.LATIN
    }
}

internal data class RubyInlineHorizontalGeometry(val width: Float, val baseLeft: Float, val readingLeft: Float)

/** Reserve both annotation overhangs without moving its center off the base. */
internal fun rubyInlineHorizontalGeometry(baseWidth: Float, baseStart: Float, baseEnd: Float,
    readingWidth: Float): RubyInlineHorizontalGeometry {
    val readingLeft = (baseStart + baseEnd) / 2f - readingWidth / 2f
    val left = min(0f, readingLeft)
    val right = max(baseWidth, readingLeft + readingWidth)
    return RubyInlineHorizontalGeometry((right - left).coerceAtLeast(1f), -left, readingLeft - left)
}

/**
 * Native ruby runs are atomic. A paginated/copy subsequence that cuts through
 * a run retains only its readable fallback, never duplicates a whole reading.
 */
internal fun rubyInlineLayouts(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    bookFonts: Map<String, FontFamily> = emptyMap(),
): List<RubyInlineLayout> {
    return rubyInlineSourceRanges(text).map { source ->
        val ruby = source.ruby
        val base = source.baseText
        // Container styles inherit to rt. Styles affecting only part of the
        // base stay on the base, rather than leaking into the annotation.
        var inherited = style
        for (span in text.spanStyles) {
            if (span.start <= source.markStart && span.end >= source.markEnd) {
                val fontSize = span.item.fontSize
                val resolved = if (fontSize.type == TextUnitType.Em) {
                    span.item.copy(fontSize = (inherited.fontSize.takeUnless { it == TextUnit.Unspecified }
                        ?.value ?: 16f).times(fontSize.value).sp)
                } else span.item
                inherited = inherited.merge(resolved)
            }
        }
        val runStyle = style.copy(
            textAlign = TextAlign.Left,
            textIndent = TextIndent.None,
            lineHeight = TextUnit.Unspecified,
            lineHeightStyle = null,
        )
        val readingStyle = inherited.copy(
            textAlign = TextAlign.Left,
            textIndent = TextIndent.None,
            lineHeight = TextUnit.Unspecified,
            lineHeightStyle = null,
        )
        val rawReading = ruby.readingText().withBookFonts(bookFonts)
        val hiddenReading = rawReading.getStringAnnotations(HIDDEN_TEXT_TAG, 0, rawReading.length)
        val readingText = if (hiddenReading.isEmpty()) rawReading else AnnotatedString.Builder(rawReading).apply {
            hiddenReading.forEach { addStyle(SpanStyle(color = Color.Transparent, background = Color.Transparent), it.start, it.end) }
        }.toAnnotatedString()
        val baseLayout = measurer.measure(base, style = runStyle, softWrap = false, maxLines = 1)
        val readingLayout = measurer.measure(readingText, style = readingStyle, softWrap = false, maxLines = 1)
        val geometry = rubyInlineHorizontalGeometry(baseLayout.size.width.toFloat(),
            baseLayout.getHorizontalPosition(source.baseStart, true),
            baseLayout.getHorizontalPosition(source.baseEnd, true), readingLayout.size.width.toFloat())
        val gap = with(density) { inherited.fontSize.takeUnless { it == TextUnit.Unspecified }?.toPx() ?: 16.sp.toPx() } * 0.05f
        val baseTop = readingLayout.size.height + gap
        // AboveBaseline's bottom is the parent baseline. Leave the ordinary
        // glyph descender below it, as surrounding text does. Canvas does not
        // clip that small descent; the text line retains its normal descent.
        val ascent = baseTop + baseLayout.firstBaseline
        RubyInlineLayout(
            start = source.start,
            end = source.end,
            id = ruby.id,
            placeholder = with(density) {
                Placeholder(geometry.width.toSp(), ascent.coerceAtLeast(1f).toSp(), PlaceholderVerticalAlign.AboveBaseline)
            },
            base = baseLayout,
            reading = readingLayout,
            baseLeftPx = geometry.baseLeft,
            readingLeftPx = geometry.readingLeft,
            baseTopPx = baseTop,
            description = "${base.text}, ${ruby.reading}",
        )
    }
}

/** Payload decoding repeats during pagination; a bounded cache avoids JSON churn. */
private val rubyCache = object : LinkedHashMap<String, InlineRuby?>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InlineRuby?>?): Boolean = size > 128
}

private fun decodedRuby(payload: String): InlineRuby? = synchronized(rubyCache) {
    if (rubyCache.containsKey(payload)) rubyCache[payload]
    else InlineRuby.decode(payload).also { rubyCache[payload] = it }
}
