package com.example.frogreader.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.QuoteColor

/**
 * App-wide theme: Material 3 Expressive with the springy motion scheme.
 *
 * The fixed Light palette uses clean neutral surfaces and a forest accent; see
 * docs/light-theme.md for role assignments. On Android 12+, Material You can own the full
 * Material palette, including neutral surfaces and backgrounds, for Light and
 * Midnight. Beige is deliberately excluded and always keeps its warm palette;
 * see docs/beige-theme.md for its component and contrast contract.
 *
 * Hex literals live ONLY in this file — composables read
 * `MaterialTheme.colorScheme.*` or [LocalFrogColors].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FrogReaderTheme(
    theme: AppTheme,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = appColorSchemeFor(theme, dynamicColor)
    val extras = frogColorsFor(theme, colorScheme, dynamicColor)

    CompositionLocalProvider(LocalFrogColors provides extras) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/**
 * Resolves the palette for app chrome. Dynamic colour deliberately returns the
 * complete system scheme: Material You's subtly tinted neutral roles are what
 * make backgrounds and cards feel related to the wallpaper without painting
 * the whole app in the accent colour.
 */
@Composable
fun appColorSchemeFor(theme: AppTheme, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(theme, dynamicColor, context, configuration) {
        val dynamicScheme = if (
            dynamicColor &&
            theme != AppTheme.SEPIA &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            if (theme.isDark()) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            null
        }
        resolveAppColorScheme(theme, dynamicScheme)
    }
}

internal fun resolveAppColorScheme(
    theme: AppTheme,
    dynamicScheme: ColorScheme?,
): ColorScheme = if (theme == AppTheme.SEPIA) {
    colorSchemeFor(theme)
} else {
    dynamicScheme ?: colorSchemeFor(theme)
}

/** True when the theme needs light status-bar icons. */
fun AppTheme.isDark(): Boolean = this == AppTheme.OLED

/**
 * The palette of a theme that is not necessarily the active one. Needed by the
 * reader, which paints its page from these roles and also previews all three
 * themes side by side in its settings sheet — so it cannot just read the
 * ambient `MaterialTheme.colorScheme`.
 */
fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.WHITE -> LightColorScheme
    AppTheme.SEPIA -> BeigeColorScheme
    AppTheme.OLED -> MidnightColorScheme
}

/** The name shown in the theme picker — the stored enum names never change. */
val AppTheme.displayNameRes: Int
    get() = when (this) {
        AppTheme.WHITE -> com.example.frogreader.R.string.theme_light
        AppTheme.SEPIA -> com.example.frogreader.R.string.theme_beige
        AppTheme.OLED -> com.example.frogreader.R.string.theme_midnight
    }

val AppTheme.descriptionRes: Int
    get() = when (this) {
        AppTheme.WHITE -> com.example.frogreader.R.string.theme_light_note
        AppTheme.SEPIA -> com.example.frogreader.R.string.theme_beige_note
        AppTheme.OLED -> com.example.frogreader.R.string.theme_midnight_note
    }

/** The two halves of the 44dp swatch in the theme picker: surface / accent. */
val AppTheme.swatch: Pair<Color, Color>
    get() = colorSchemeFor(this).let { it.surface to it.primary }

/** Floating controls stay light on light palettes, including Material You. */
val ColorScheme.floatingSurface: Color
    get() = if (surface.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHigh

/** Saved highlight colors share named inks across the page, picker and quote cards. */
fun ColorScheme.quoteInk(color: QuoteColor): Color {
    val dark = surface.luminance() < 0.5f
    return when (color) {
        QuoteColor.DEFAULT -> primary
        QuoteColor.BLUE -> if (dark) Color(0xFF8EBBE8) else Color(0xFF28689D)
        QuoteColor.ROSE -> if (dark) Color(0xFFEAA0B6) else Color(0xFFA34564)
        QuoteColor.AMBER -> if (dark) Color(0xFFE6BD72) else Color(0xFF976B13)
        QuoteColor.VIOLET -> if (dark) Color(0xFFC3ADE8) else Color(0xFF7754A2)
    }
}

/** Reading paper is stable even when app surfaces are redesigned. */
val AppTheme.readingPageBackground: Color
    get() = when (this) {
        AppTheme.WHITE -> Color.White
        AppTheme.SEPIA -> Color(0xFFF3E7D2)
        AppTheme.OLED -> Color.Black
    }

/**
 * Text laid over cover artwork, and the veil under it. The same in every
 * palette: what they sit on is the picture, not the theme, so a light theme's
 * dark ink would vanish into a dark cover just as often as it would not.
 */
val CoverCaptionInk = Color(0xFFFFFFFF)
val CoverCaptionScrim = Color(0xFF060908)

// ------------------------------------------------------------ extra colors

enum class ProfileLandscapeStyle { FOREST, DUNES, NIGHT }

/** Decorative art and its foreground controls belong to the profile only. */
@Immutable
data class ProfileLandscapeColors(
    val style: ProfileLandscapeStyle,
    val distant: Color,
    val middle: Color,
    val foreground: Color,
    val detail: Color,
    val celestial: Color,
    val action: Color,
    val onAction: Color,
    val actionBorder: Color,
    val actionIcon: Color = onAction,
    val actionIconBackground: Color = onAction.copy(alpha = .12f),
    val actionSecondary: Color = onAction,
)

/**
 * App-specific surfaces and decoration. Controls derive from the Material
 * roles; only scoped artwork and the header gradient need separate colors.
 * Read through [LocalFrogColors] so new components inherit the active theme.
 */
@Immutable
data class FrogExtraColors(
    /** Top / bottom of the library header gradient. */
    val headerTop: Color,
    val headerBottom: Color,
    /** Search field and gear button sitting on the colored header. */
    val glass: Color,
    /** Progress track and the hero's ⋮ button. */
    val chip: Color,
    /** The "85%" badge on a cover. */
    val pill: Color,
    /** The "+N" tile in a shelf's spine strip. */
    val pill60: Color,
    /** Veil drawn over a book that is currently being dragged. */
    val lift: Color,
    /** The floating nav bar pill. */
    val nav: Color,
    /** Shelf background, and the progress fill of a list row. */
    val folder: Color,
    /** Soft gold background reserved for completed books in the library list. */
    val completionFill: Color,
    /** Gold percentage text, readable on [completionFill] and cover [pill] badges. */
    val completionInk: Color,
    /** Text on the colored header. */
    val ink: Color,
    /** Secondary text and icons on the colored header. */
    val ink2: Color,
    /** Sky, lower haze and identity text of the profile landscape. */
    val profileTop: Color,
    val profileBottom: Color,
    val onProfile: Color,
    val profileLandscape: ProfileLandscapeColors,
)

// Completion is a semantic gold accent, including with Material You enabled.
private val LightCompletionFill = Color(0xFFF3E5BE)
private val LightCompletionInk = Color(0xFF805B10)
private val DarkCompletionFill = Color(0xFF342B16)
private val DarkCompletionInk = Color(0xFFE8C568)

// Lazy because the shared Material palette is declared below these extra roles.
private val LightExtraColors by lazy {
    val scheme = LightColorScheme
    FrogExtraColors(
        headerTop = Color(0xFFE5F1EB),
        headerBottom = scheme.surface,
        glass = scheme.surfaceContainerLowest.copy(alpha = 0.82f),
        chip = scheme.surfaceContainerHigh,
        pill = scheme.surfaceContainerLowest.copy(alpha = 0.96f),
        pill60 = scheme.surfaceContainerLowest.copy(alpha = 0.78f),
        lift = scheme.surface.copy(alpha = 0.82f),
        nav = scheme.surfaceContainerLowest,
        folder = scheme.primary.copy(alpha = 0.08f),
        completionFill = LightCompletionFill,
        completionInk = LightCompletionInk,
        ink = scheme.onSurface,
        ink2 = scheme.onSurfaceVariant,
        profileTop = Color(0xFFE5EBEA),
        profileBottom = Color(0xFFF8FAF8),
        onProfile = Color(0xFF202B29),
        profileLandscape = ProfileLandscapeColors(
            style = ProfileLandscapeStyle.FOREST,
            distant = Color(0xFFCEDBDB),
            middle = Color(0xFFAABFC1),
            foreground = Color(0xFF7F9D9F),
            detail = Color(0xFF557C7D),
            celestial = Color(0xFFFFFCF2),
            action = scheme.surfaceContainerLowest,
            onAction = scheme.onSurface,
            actionBorder = scheme.outlineVariant,
            actionIcon = scheme.primary,
            actionIconBackground = scheme.surfaceContainer,
            actionSecondary = scheme.onSurfaceVariant,
        ),
    )
}

private val BeigeExtraColors by lazy {
    val scheme = BeigeColorScheme
    FrogExtraColors(
        headerTop = Color(0xFFF0E3D0),
        headerBottom = scheme.surface,
        glass = scheme.surfaceContainerLowest.copy(alpha = 0.90f),
        chip = scheme.surfaceContainerHigh,
        pill = scheme.surfaceContainerLowest.copy(alpha = 0.96f),
        pill60 = scheme.surfaceContainerLowest.copy(alpha = 0.78f),
        lift = scheme.surface.copy(alpha = 0.82f),
        nav = scheme.surfaceContainerLowest,
        folder = scheme.primary.copy(alpha = 0.09f),
        completionFill = LightCompletionFill,
        completionInk = LightCompletionInk,
        ink = scheme.onSurface,
        ink2 = scheme.onSurfaceVariant,
        profileTop = Color(0xFFF0E5D8),
        profileBottom = scheme.surfaceContainerLowest,
        onProfile = scheme.onSurface,
        profileLandscape = ProfileLandscapeColors(
            style = ProfileLandscapeStyle.DUNES,
            distant = Color(0xFFE4D2BC),
            middle = Color(0xFFCEB597),
            foreground = Color(0xFFB4997B),
            detail = Color(0xFF85684D),
            celestial = Color(0xFFFFEED2),
            action = scheme.surfaceContainerLowest.copy(alpha = 0.96f),
            onAction = scheme.onSurface,
            actionBorder = scheme.outlineVariant,
            actionIcon = scheme.primary,
            actionIconBackground = scheme.surfaceContainer,
            actionSecondary = scheme.onSurfaceVariant,
        ),
    )
}

private val MidnightExtraColors = FrogExtraColors(
    headerTop = Color(0xFF131A14),
    headerBottom = Color(0xFF000000),
    glass = Color.White.copy(alpha = 0.08f),
    chip = Color(0xFF191D19),
    pill = Color(0xFF191D19).copy(alpha = 0.94f),
    pill60 = Color(0xFF191D19).copy(alpha = 0.60f),
    lift = Color(0xFF1E241E).copy(alpha = 0.84f),
    nav = Color(0xFF101410),
    folder = Color(0xFF7BD69B).copy(alpha = 0.14f),
    completionFill = DarkCompletionFill,
    completionInk = DarkCompletionInk,
    ink = Color(0xFFE8EDE7),
    ink2 = Color(0xFFB9C2B8),
    profileTop = Color(0xFF10171D),
    profileBottom = Color(0xFF070B0E),
    onProfile = Color(0xFFF0F3F3),
    profileLandscape = ProfileLandscapeColors(
        style = ProfileLandscapeStyle.NIGHT,
        distant = Color(0xFF293037),
        middle = Color(0xFF1D242A),
        foreground = Color(0xFF0B1015),
        detail = Color(0xFF444A4D),
        celestial = Color(0xFFE5CCA2),
        action = Color(0xFF24282B).copy(alpha = 0.92f),
        onAction = Color(0xFFF0F3F3),
        actionBorder = Color(0xFF62686C).copy(alpha = 0.36f),
    ),
)

val LocalFrogColors = staticCompositionLocalOf { LightExtraColors }

private fun fixedFrogColors(theme: AppTheme): FrogExtraColors = when (theme) {
    AppTheme.WHITE -> LightExtraColors
    AppTheme.SEPIA -> BeigeExtraColors
    AppTheme.OLED -> MidnightExtraColors
}

/** The same extra-token policy used by the live app and by theme previews. */
internal fun frogColorsFor(
    theme: AppTheme,
    scheme: ColorScheme,
    dynamicColor: Boolean,
): FrogExtraColors = if (
    dynamicColor &&
    theme != AppTheme.SEPIA &&
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
) {
    dynamicFrogColors(scheme, dark = theme.isDark())
} else {
    fixedFrogColors(theme)
}

/**
 * FrogReader-specific roles derived from the active Material You scheme. These
 * keep the library header, folders and floating controls in the same tonal
 * family as standard Material surfaces instead of leaving fixed green behind.
 */
internal fun dynamicFrogColors(scheme: ColorScheme, dark: Boolean): FrogExtraColors {
    val raisedSurface = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainerLowest
    return FrogExtraColors(
        headerTop = if (dark) scheme.primaryContainer else lerp(scheme.primaryContainer, scheme.surface, 0.55f),
        headerBottom = if (dark) scheme.surfaceContainerLow else scheme.surface,
        glass = if (dark) {
            scheme.onPrimaryContainer.copy(alpha = 0.10f)
        } else {
            scheme.surfaceContainerLowest.copy(alpha = 0.82f)
        },
        chip = scheme.surfaceContainerHigh,
        pill = raisedSurface.copy(alpha = 0.94f),
        pill60 = raisedSurface.copy(alpha = 0.62f),
        lift = scheme.surfaceContainerHigh.copy(alpha = 0.84f),
        nav = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
        folder = scheme.primary.copy(alpha = if (dark) 0.20f else 0.08f),
        completionFill = if (dark) DarkCompletionFill else LightCompletionFill,
        completionInk = if (dark) DarkCompletionInk else LightCompletionInk,
        ink = if (dark) scheme.onPrimaryContainer else scheme.onSurface,
        ink2 = if (dark) scheme.onPrimaryContainer.copy(alpha = 0.76f) else scheme.onSurfaceVariant,
        profileTop = lerp(scheme.surface, scheme.primaryContainer, if (dark) 0.18f else 0.30f),
        profileBottom = scheme.surfaceContainerLowest,
        onProfile = scheme.onSurface,
        profileLandscape = ProfileLandscapeColors(
            style = if (dark) ProfileLandscapeStyle.NIGHT else ProfileLandscapeStyle.FOREST,
            distant = lerp(scheme.surface, scheme.primary, if (dark) 0.19f else 0.22f),
            middle = lerp(scheme.surface, scheme.primary, if (dark) 0.12f else 0.34f),
            foreground = if (dark) scheme.surfaceContainerLowest else lerp(scheme.surface, scheme.primary, 0.46f),
            detail = lerp(scheme.surface, scheme.primary, if (dark) 0.30f else 0.60f),
            celestial = if (dark) scheme.tertiary else scheme.surfaceContainerLowest,
            action = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.94f) else scheme.surfaceContainerLowest,
            onAction = scheme.onSurface,
            actionBorder = scheme.outlineVariant.copy(alpha = 0.60f),
            actionIcon = if (dark) scheme.onSurface else scheme.primary,
            actionIconBackground = if (dark) scheme.onSurface.copy(alpha = .12f) else scheme.surfaceContainer,
            actionSecondary = if (dark) scheme.onSurface else scheme.onSurfaceVariant,
        ),
    )
}

/** Decorative cover swatches belong only to the abstract theme-picker specimen. */
@Immutable
internal data class ThemePreviewCoverColors(
    val featured: Color,
    val first: Color,
    val second: Color,
    val third: Color,
)

internal fun themePreviewCoverColorsFor(
    theme: AppTheme,
    scheme: ColorScheme,
    dynamicColor: Boolean,
): ThemePreviewCoverColors {
    if (dynamicColor && theme != AppTheme.SEPIA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return ThemePreviewCoverColors(
            featured = scheme.primaryContainer,
            first = scheme.primaryContainer,
            second = scheme.secondaryContainer,
            third = scheme.tertiaryContainer,
        )
    }
    return when (theme) {
        AppTheme.WHITE -> ThemePreviewCoverColors(
            featured = Color(0xFFD1DDD5), first = Color(0xFFD1DDD5),
            second = Color(0xFFE9E6DF), third = Color(0xFFD3DCE6),
        )
        AppTheme.SEPIA -> ThemePreviewCoverColors(
            featured = Color(0xFFDDCFC0), first = Color(0xFFDDCDB7),
            second = Color(0xFFDDBFB6), third = Color(0xFFC8CCBC),
        )
        AppTheme.OLED -> ThemePreviewCoverColors(
            featured = Color(0xFF32594B), first = Color(0xFF304F43),
            second = Color(0xFF3E4F62), third = Color(0xFF5B435A),
        )
    }
}

// --------------------------------------------------------------- palettes

/** Light — clean whites, neutral controls, fresh green accents. See docs/light-theme.md. */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF246B50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF0E6),
    onPrimaryContainer = Color(0xFF20513C),
    inversePrimary = Color(0xFFA3D6BB),
    secondary = Color(0xFF50615B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEF2F4),
    onSecondaryContainer = Color(0xFF3B5549),
    tertiary = Color(0xFF786039),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0E4CC),
    onTertiaryContainer = Color(0xFF574321),
    background = Color(0xFFF4F6F7),
    onBackground = Color(0xFF222B27),
    surface = Color(0xFFF4F6F7),
    onSurface = Color(0xFF222B27),
    surfaceVariant = Color(0xFFE3E8EB),
    onSurfaceVariant = Color(0xFF58635E),
    surfaceTint = Color(0xFF246B50),
    inverseSurface = Color(0xFF2B3630),
    inverseOnSurface = Color(0xFFF3F7F5),
    error = Color(0xFFAC4138),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5DF),
    onErrorContainer = Color(0xFF742D27),
    outline = Color(0xFF78847E),
    outlineVariant = Color(0xFFD8DFE2),
    scrim = Color(0xFF18251E),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEE3E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFE9EDEF),
    surfaceContainerHighest = Color(0xFFE1E6E9),
    primaryFixed = Color(0xFFDDF0E6),
    primaryFixedDim = Color(0xFFB5D9C5),
    onPrimaryFixed = Color(0xFF183C2B),
    onPrimaryFixedVariant = Color(0xFF20513C),
    secondaryFixed = Color(0xFFEEF2F4),
    secondaryFixedDim = Color(0xFFCCD6D2),
    onSecondaryFixed = Color(0xFF253A30),
    onSecondaryFixedVariant = Color(0xFF40594D),
    tertiaryFixed = Color(0xFFF0E4CC),
    tertiaryFixedDim = Color(0xFFDCCCAC),
    onTertiaryFixed = Color(0xFF392C12),
    onTertiaryFixedVariant = Color(0xFF624D29),
)

/** Beige — linen canvas, ivory cards, walnut actions. See docs/beige-theme.md.
 * Stored as [AppTheme.SEPIA]; never rename that persistence key.
 */
private val BeigeColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF745031),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDDDC9),
    onPrimaryContainer = Color(0xFF49321F),
    inversePrimary = Color(0xFFDFBC98),
    secondary = Color(0xFF665746),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBE0D1),
    onSecondaryContainer = Color(0xFF574736),
    tertiary = Color(0xFF805854),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DED8),
    onTertiaryContainer = Color(0xFF563530),
    background = Color(0xFFF2EBE0),
    onBackground = Color(0xFF30291F),
    surface = Color(0xFFF2EBE0),
    onSurface = Color(0xFF30291F),
    surfaceVariant = Color(0xFFE6DACA),
    onSurfaceVariant = Color(0xFF645443),
    surfaceTint = Color(0xFF745031),
    inverseSurface = Color(0xFF382D23),
    inverseOnSurface = Color(0xFFFAF4EB),
    error = Color(0xFF98392F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE2DC),
    onErrorContainer = Color(0xFF742B24),
    outline = Color(0xFF887661),
    outlineVariant = Color(0xFFD3C3AF),
    scrim = Color(0xFF241C14),
    surfaceBright = Color(0xFFFFFAF2),
    surfaceDim = Color(0xFFDCCDB9),
    surfaceContainerLowest = Color(0xFFFFFAF2),
    surfaceContainerLow = Color(0xFFFAF4EB),
    surfaceContainer = Color(0xFFEEE5D8),
    surfaceContainerHigh = Color(0xFFE6DACA),
    surfaceContainerHighest = Color(0xFFDCCDB9),
    // Explicit fixed roles prevent new Material components inheriting purple defaults.
    primaryFixed = Color(0xFFEDDDC9),
    primaryFixedDim = Color(0xFFDEC3A5),
    onPrimaryFixed = Color(0xFF302114),
    onPrimaryFixedVariant = Color(0xFF49321F),
    secondaryFixed = Color(0xFFEBE0D1),
    secondaryFixedDim = Color(0xFFD6C7B3),
    onSecondaryFixed = Color(0xFF30291F),
    onSecondaryFixedVariant = Color(0xFF574736),
    tertiaryFixed = Color(0xFFF3DED8),
    tertiaryFixedDim = Color(0xFFE0BDB4),
    onTertiaryFixed = Color(0xFF39201C),
    onTertiaryFixedVariant = Color(0xFF563530),
)

/** Midnight — true black, mint green accents. Stored as [AppTheme.OLED]. */
private val MidnightColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF7BD69B),
    onPrimary = Color(0xFF06140B),
    primaryContainer = Color(0xFF7BD69B),
    onPrimaryContainer = Color(0xFF06140B),
    inversePrimary = Color(0xFF2A6A47),
    secondary = Color(0xFFA9C9B3),
    onSecondary = Color(0xFF14301F),
    secondaryContainer = Color(0xFF2B4636),
    onSecondaryContainer = Color(0xFFC5E5CE),
    tertiary = Color(0xFFA0CFD8),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF1E4C55),
    onTertiaryContainer = Color(0xFFBCEBF4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8EDE7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE8EDE7),
    surfaceVariant = Color(0xFF2A332B),
    onSurfaceVariant = Color(0xFF8D998C),
    surfaceTint = Color(0xFF7BD69B),
    inverseSurface = Color(0xFFE8EDE7),
    inverseOnSurface = Color(0xFF1A1F1A),
    error = Color(0xFFFF9E93),
    onError = Color(0xFF5A1408),
    errorContainer = Color(0xFF7A2A1C),
    onErrorContainer = Color(0xFFFFDAD3),
    outline = Color(0xFF56655A),
    outlineVariant = Color(0xFF354036),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF262B26),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF121612),
    surfaceContainerLow = Color(0xFF0A0C0A),
    surfaceContainer = Color(0xFF101310),
    surfaceContainerHigh = Color(0xFF191D19),
    surfaceContainerHighest = Color(0xFF222722),
)
