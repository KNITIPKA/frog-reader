package com.example.frogreader.ui.reader

import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.colorSchemeFor
import com.example.frogreader.ui.theme.isDark

/** Colors of the reading surface; chrome = the bars floating above the text. */
class ReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val chrome: Color,
    val onChrome: Color,
    val accent: Color,
    /** Background of text saved as a quote. */
    val quoteHighlight: Color,
)

/**
 * The reading page, derived from the app palette rather than a table of its own.
 *
 * It used to carry hardcoded hexes written for the pre-redesign theme — a pure
 * white page with a brown accent, beige tones from the old sepia scheme, and a
 * warm grey accent in the dark theme — so the reader drifted further from the
 * rest of the app with every palette change. Deriving keeps them in step for
 * good.
 *
 * Not a @Composable and not reading the ambient scheme on purpose: the reader's
 * settings sheet previews all three themes at once, so it must be able to ask
 * for a palette that is not the active one.
 */
fun readerColors(theme: AppTheme): ReaderColors {
    val scheme = colorSchemeFor(theme)
    return ReaderColors(
        // The page is the same surface the library sits on, so switching
        // between the two no longer shifts the paper tone.
        background = scheme.surface,
        text = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        // Floating bars match the nav bar's tone.
        chrome = scheme.surfaceContainerHigh,
        onChrome = scheme.onSurface,
        accent = scheme.primary,
        quoteHighlight = scheme.primary.copy(alpha = if (theme.isDark()) 0.28f else 0.22f),
    )
}
