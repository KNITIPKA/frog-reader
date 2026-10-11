package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.R
import com.example.frogreader.data.ReaderFont
import java.io.File

/**
 * Literata — the open-source book face designed for long-form reading.
 * Official release 3.103, bundled unchanged as variable fonts.
 */
val LiterataFamily: FontFamily = bundledVariableFamily(
    regular = R.font.literata,
    italic = R.font.literata_italic,
    weights = 200..900 step 100,
)

/** IBM's complete Latin, Greek and Cyrillic fonts from Plex Sans Variable 0.2.0. */
val IBMPlexSansFamily: FontFamily = bundledVariableFamily(
    regular = R.font.ibm_plex_sans,
    italic = R.font.ibm_plex_sans_italic,
    weights = 100..700 step 100,
)

/** Register both real styles at every supported weight instead of synthesizing them. */
private fun bundledVariableFamily(
    regular: Int,
    italic: Int,
    weights: IntProgression,
): FontFamily = FontFamily(
    weights.flatMap { weight ->
        listOf(
            Font(
                regular,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            ),
            Font(
                italic,
                weight = FontWeight(weight),
                style = FontStyle.Italic,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            ),
        )
    },
)

/** Loaded user font files, keyed by path (a new pick gets a new path). */
private val customFamilies = HashMap<String, FontFamily>()

/** The user's own font file as a family, or null when unusable. */
fun customFontFamily(path: String?): FontFamily? = customFamilyFor(path)

private fun customFamilyFor(path: String?): FontFamily? {
    if (path == null) return null
    synchronized(customFamilies) {
        customFamilies[path]?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        val family = runCatching { FontFamily(Font(file)) }.getOrNull() ?: return null
        customFamilies[path] = family
        return family
    }
}

fun fontFamilyFor(font: ReaderFont, customFontPath: String? = null): FontFamily = when (font) {
    ReaderFont.LITERATA -> LiterataFamily
    ReaderFont.SERIF -> FontFamily.Serif
    // Keep the stored SANS identifier so existing settings and backups stay readable.
    ReaderFont.SANS -> IBMPlexSansFamily
    ReaderFont.CUSTOM -> customFamilyFor(customFontPath) ?: LiterataFamily
}
