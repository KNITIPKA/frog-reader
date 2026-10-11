package com.example.frogreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.decodeInlineTextBoxSpec
import kotlin.math.ceil

/** Shaped author text within an inline-block's fixed advance width. */
internal class InlineTextBoxLayout(
    val start: Int,
    val end: Int,
    val id: String,
    val placeholder: Placeholder,
    private val text: TextLayoutResult,
    private val x: Float,
) {
    @Composable
    fun Content() {
        Canvas(Modifier.fillMaxSize()) { drawText(text, topLeft = Offset(x, 0f)) }
    }
}

/** Both text measurement and rendering use these exact pixel dimensions. */
internal fun inlineTextBoxLayouts(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
): List<InlineTextBoxLayout> {
    val marks = text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, text.length)
    if (marks.isEmpty()) return emptyList()
    val baseFontPx = runCatching { with(density) { style.fontSize.toPx() } }.getOrNull()
        ?.takeIf { it.isFinite() && it > 0f } ?: return emptyList()
    val results = ArrayList<InlineTextBoxLayout>(marks.size.coerceAtMost(1_024))
    for (mark in marks.take(1_024)) {
        val spec = decodeInlineTextBoxSpec(mark.item) ?: continue
        if (mark.start !in 0 until text.length || mark.end !in (mark.start + 1)..text.length ||
            mark.end - mark.start != spec.sourceLength) continue
        val source = text.subSequence(mark.start, mark.end)
        // Source/link/content IDs do not affect glyph geometry. Removing
        // them here lets repeated stars reuse the TextMeasurer's shape cache.
        val fragment = AnnotatedString(source.text, source.spanStyles, source.paragraphStyles)
        if ('\n' in fragment.text || fragment.text.codePointCount(0, fragment.length) > 2) continue
        val shaped = measurer.measure(fragment, style.copy(textAlign = TextAlign.Start,
            textIndent = TextIndent.None, lineHeight = TextUnit.Unspecified, lineHeightStyle = null), softWrap = false)
        if (shaped.lineCount != 1) continue
        val widthPx = ceil(baseFontPx * spec.widthEm).coerceAtLeast(1f)
        // The box ends on the surrounding baseline; ordinary descenders
        // extend into the parent line's descent without adding extra leading.
        val heightPx = shaped.firstBaseline.coerceAtLeast(1f)
        val free = widthPx - shaped.size.width
        val rtl = shaped.getParagraphDirection(0) == ResolvedTextDirection.Rtl
        val x = when (spec.align) {
            "center" -> free / 2f
            "right" -> free
            "left" -> 0f
            "end" -> if (rtl) 0f else free
            else -> if (rtl) free else 0f
        }
        results += InlineTextBoxLayout(mark.start, mark.end, spec.id,
            Placeholder(
                width = with(density) { widthPx.toSp() },
                height = with(density) { heightPx.toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
            ), shaped, x)
    }
    return results
}
