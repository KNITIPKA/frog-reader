package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG

/** Resolve authored inline faces once, before both pagination and rendering see the text. */
internal fun AnnotatedString.withBookFonts(
    bookFonts: Map<String, FontFamily>,
    enabled: Boolean = true,
): AnnotatedString {
    if (!enabled || bookFonts.isEmpty()) return this
    val marks = getStringAnnotations(INLINE_FONT_FAMILY_TAG, 0, length)
    if (marks.isEmpty()) return this
    val resolved = marks.mapNotNull { mark -> bookFonts[mark.item]?.let { mark to it } }
    if (resolved.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        resolved.forEach { (mark, family) ->
            addStyle(SpanStyle(fontFamily = family), mark.start, mark.end)
        }
    }.toAnnotatedString()
}
