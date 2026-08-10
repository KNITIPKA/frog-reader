package com.example.frogreader.ui.reader

import androidx.compose.material3.ColorScheme
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
    /** Background of the text being selected right now, and its handles. */
    val selection: Color,
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
fun readerColors(
    theme: AppTheme,
    chromeScheme: ColorScheme = colorSchemeFor(theme),
): ReaderColors {
    val pageScheme = colorSchemeFor(theme)
    return ReaderColors(
        // Pure white in the light theme, not the library's very slightly green
        // surface: a book's own artwork is almost always on a white ground, and
        // against anything else every illustration sits in a faint box of its
        // own. Sepia and OLED keep their surface — the beige page IS the theme,
        // and OLED's surface is already pure black.
        background = if (theme == AppTheme.WHITE) Color.White else pageScheme.surface,
        text = pageScheme.onSurface,
        secondaryText = pageScheme.onSurfaceVariant,
        // The page remains a stable reading surface, while its controls and
        // interactive colour can follow Material You like the rest of the app.
        chrome = chromeScheme.surfaceContainerHigh,
        onChrome = chromeScheme.onSurface,
        accent = chromeScheme.primary,
        quoteHighlight = chromeScheme.primary.copy(
            alpha = if (theme.isDark()) 0.28f else 0.22f,
        ),
        // Stronger than a saved quote on purpose: while the reader is dragging,
        // the selection has to be readable ON TOP of a quote it overlaps.
        selection = chromeScheme.primary.copy(
            alpha = if (theme.isDark()) 0.45f else 0.34f,
        ),
    )
}
