package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.R

/** Unmodified Liberation 2.1.5: complete faces, available offline on API 26+. */
internal val LiberationSerifFamily = liberationFamily(R.font.liberationserif_regular,
    R.font.liberationserif_italic, R.font.liberationserif_bold, R.font.liberationserif_bold_italic)
internal val LiberationSansFamily = liberationFamily(R.font.liberationsans_regular,
    R.font.liberationsans_italic, R.font.liberationsans_bold, R.font.liberationsans_bold_italic)
internal val LiberationMonoFamily = liberationFamily(R.font.liberationmono_regular,
    R.font.liberationmono_italic, R.font.liberationmono_bold, R.font.liberationmono_bold_italic)

private fun liberationFamily(regular: Int, italic: Int, bold: Int, boldItalic: Int) = FontFamily(
    Font(regular, FontWeight.Normal, FontStyle.Normal),
    Font(italic, FontWeight.Normal, FontStyle.Italic),
    Font(bold, FontWeight.Bold, FontStyle.Normal),
    Font(boldItalic, FontWeight.Bold, FontStyle.Italic),
)

internal fun liberationFamilyFor(category: String): FontFamily = when (category) {
    "serif" -> LiberationSerifFamily
    "monospace" -> LiberationMonoFamily
    else -> LiberationSansFamily
}
