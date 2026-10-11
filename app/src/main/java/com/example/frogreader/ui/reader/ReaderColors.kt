package com.example.frogreader.ui.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.theme.colorSchemeFor
import com.example.frogreader.ui.theme.floatingSurface
import com.example.frogreader.ui.theme.isDark
import com.example.frogreader.ui.theme.readingPageBackground
import com.example.frogreader.ui.theme.quoteInk

/** Colors of the reading surface and its controls. */
@Immutable
data class ReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val chrome: Color,
    val onChrome: Color,
    val onChromeVariant: Color,
    val accent: Color,
    val onAccent: Color,
    /** Filled selected settings share the action palette in every theme. */
    val selectedControl: Color,
    val onSelectedControl: Color,
    val selectedControlBorder: Color,
    /** Progress has its own accent so the rail need not compete with actions. */
    val progress: Color,
    val onProgress: Color,
    /** Opaque shadow ink; drawing components choose the appropriate opacity. */
    val shadow: Color,
    /** Inset control groups, nested buttons, and inactive progress tracks. */
    val control: Color,
    val controlStrong: Color,
    /** Distinct button faces within an inset stepper group. */
    val controlButton: Color,
    val track: Color,
    /** Background of text saved as a quote. */
    val quoteHighlight: Color,
    /** Background of the text being selected right now, and its handles. */
    val selection: Color,
    /** Opaque swatches shared with the color picker. */
    val quoteInks: Map<QuoteColor, Color> = emptyMap(),
    /** Page fills preserve text contrast independently of the swatch brightness. */
    val quoteHighlights: Map<QuoteColor, Color> = emptyMap(),
) {
    /** Quiet, opaque ink for the counter printed on the reading page. */
    val pageCounterText: Color get() = lerp(secondaryText, background, 0.12f)

    fun quoteInk(color: QuoteColor): Color = quoteInks[color] ?: accent

    fun quoteHighlight(color: QuoteColor): Color =
        quoteHighlights[color] ?: quoteInk(color).copy(alpha = quoteHighlight.alpha)
}

/** Material You accents can be much brighter than our fixed inks. */
private fun readableHighlight(ink: Color, opacity: Float, background: Color, text: Color): Color {
    fun readable(alpha: Float): Boolean {
        val fill = ink.copy(alpha = alpha).compositeOver(background).luminance()
        val foreground = text.luminance()
        return (maxOf(fill, foreground) + 0.05f) / (minOf(fill, foreground) + 0.05f) >= 4.6f
    }
    if (readable(opacity)) return ink.copy(alpha = opacity)
    var lower = 0f
    var upper = opacity
    repeat(12) {
        val middle = (lower + upper) / 2f
        if (readable(middle)) lower = middle else upper = middle
    }
    return ink.copy(alpha = lower)
}

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
    val beige = theme == AppTheme.SEPIA
    val control = if (beige) chromeScheme.surfaceContainerHigh
        else if (!theme.isDark()) chromeScheme.surfaceContainer
        else chromeScheme.onSurface.copy(alpha = 0.06f)
    val quoteInks = QuoteColor.entries.associateWith { chromeScheme.quoteInk(it) }
    val quoteHighlights = quoteInks.mapValues { (_, ink) ->
        readableHighlight(ink, if (theme.isDark()) 0.50f else 0.22f,
            theme.readingPageBackground, pageScheme.onSurface)
    }
    return ReaderColors(
        // Paper remains independent of app surfaces and Material You.
        // Light is pure white so white-backed book illustrations blend in.
        background = theme.readingPageBackground,
        text = pageScheme.onSurface,
        secondaryText = pageScheme.onSurfaceVariant,
        // The page remains a stable reading surface, while its controls and
        // interactive colour can follow Material You like the rest of the app.
        // Beige panels extend the paper in both styles and every drawer state.
        // Raised buttons and inset groups provide the surface hierarchy.
        chrome = if (beige) theme.readingPageBackground else chromeScheme.floatingSurface,
        onChrome = chromeScheme.onSurface,
        onChromeVariant = chromeScheme.onSurfaceVariant,
        accent = chromeScheme.primary,
        onAccent = chromeScheme.onPrimary,
        selectedControl = chromeScheme.primary,
        onSelectedControl = chromeScheme.onPrimary,
        selectedControlBorder = Color.Transparent,
        progress = if (beige) chromeScheme.secondary else chromeScheme.primary,
        onProgress = if (beige) chromeScheme.onSecondary else chromeScheme.onPrimary,
        shadow = chromeScheme.scrim,
        control = control,
        controlStrong = if (beige) chromeScheme.surfaceContainerHighest
            else if (!theme.isDark()) chromeScheme.surfaceContainerHigh
            else chromeScheme.onSurface.copy(alpha = 0.08f),
        controlButton = if (beige) theme.readingPageBackground
            else if (!theme.isDark()) chromeScheme.surfaceContainerLowest
            else chromeScheme.onSurface.copy(alpha = 0.08f),
        track = if (beige) chromeScheme.surfaceContainerHighest
            else if (!theme.isDark()) chromeScheme.outlineVariant
            else chromeScheme.onSurfaceVariant.copy(alpha = 0.25f),
        quoteHighlight = quoteHighlights.getValue(QuoteColor.DEFAULT),
        // Paint one readable selection fill, even across several saved colors.
        // Stacking translucent fills would wash out text on the brighter dark quotes.
        selection = readableHighlight(chromeScheme.primary, if (theme.isDark()) 0.54f else 0.34f,
            theme.readingPageBackground, pageScheme.onSurface).compositeOver(theme.readingPageBackground),
        quoteInks = quoteInks,
        quoteHighlights = quoteHighlights,
    )
}
