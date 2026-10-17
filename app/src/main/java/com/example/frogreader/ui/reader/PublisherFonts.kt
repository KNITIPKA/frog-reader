package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.ui.theme.bundledFontFamilyForCss

/** Resolve authored inline faces for the current measurement/drawing settings. */
internal fun AnnotatedString.withBookFonts(
    bookFonts: Map<String, FontFamily>,
    enabled: Boolean = true,
): AnnotatedString {
    if (!enabled) return this
    val marks = getStringAnnotations(INLINE_FONT_FAMILY_TAG, 0, length)
    if (marks.isEmpty()) return this
    val resolved = marks.mapNotNull { mark -> publisherFamilyForCss(mark.item, bookFonts)?.let { mark to it } }
    if (resolved.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        resolved.forEach { (mark, family) ->
            addStyle(SpanStyle(fontFamily = family), mark.start, mark.end)
        }
    }.toAnnotatedString()
}

/** Named and generic CSS families share the same publisher-toggle boundary. */
private fun publisherFamilyForCss(name: String, bookFonts: Map<String, FontFamily>): FontFamily? =
    bookFonts[name] ?: bundledFontFamilyForCss(name) ?: when (name) {
        "serif" -> FontFamily.Serif
        "sans-serif", "sans" -> FontFamily.SansSerif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> null
    }
