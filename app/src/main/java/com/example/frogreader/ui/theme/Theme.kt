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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.frogreader.data.AppTheme

/**
 * App-wide theme: Material 3 Expressive with the springy motion scheme.
 *
 * The three fixed palettes (Light / Beige / Midnight) come straight from the
 * library design mock. On Android 12+, Material You can instead own the full
 * Material palette, including neutral surfaces and backgrounds, for Light and
 * Midnight. Beige is deliberately excluded and always keeps its warm palette.
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
    get() = when (this) {
        AppTheme.WHITE -> Color(0xFFF7FBF7) to Color(0xFF2A6A47)
        AppTheme.SEPIA -> Color(0xFFF3E7D2) to Color(0xFF7E5326)
        AppTheme.OLED -> Color(0xFF000000) to Color(0xFF7BD69B)
    }

// ------------------------------------------------------------ extra colors

/**
 * Colors the mock uses that have no role in [ColorScheme]. Kept in a
 * CompositionLocal instead of being derived from `colorScheme` because several
 * of them are not derivable: Beige's `folder` is an umber wash, not the primary
 * at low alpha, and its `chip` sits a shade off `surfaceContainerHigh`.
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
    /** Text on the colored header. */
    val ink: Color,
    /** Secondary text and icons on the colored header. */
    val ink2: Color,
)

private val LightExtraColors = FrogExtraColors(
    headerTop = Color(0xFFA5DDBB),
    headerBottom = Color(0xFFE1F5E8),
    glass = Color.White.copy(alpha = 0.55f),
    chip = Color(0xFFDFE9E1),
    pill = Color.White.copy(alpha = 0.94f),
    pill60 = Color.White.copy(alpha = 0.62f),
    lift = Color(0xFFF7FBF7).copy(alpha = 0.82f),
    nav = Color(0xFFDFE9E1),
    folder = Color(0xFF2A6A47).copy(alpha = 0.13f),
    ink = Color(0xFF101A13),
    ink2 = Color(0xFF3D4F42),
)

private val BeigeExtraColors = FrogExtraColors(
    headerTop = Color(0xFFE7D3AC),
    headerBottom = Color(0xFFF1E4CC),
    glass = Color.White.copy(alpha = 0.50f),
    chip = Color(0xFFEBDFC4),
    pill = Color(0xFFFBF4E6).copy(alpha = 0.94f),
    pill60 = Color(0xFFFBF4E6).copy(alpha = 0.60f),
    lift = Color(0xFFFBF4E6).copy(alpha = 0.82f),
    nav = Color(0xFFE9DBC0),
    folder = Color(0xFFBEA06E).copy(alpha = 0.40f),
    ink = Color(0xFF2E2416),
    ink2 = Color(0xFF4C3E29),
)

private val MidnightExtraColors = FrogExtraColors(
    headerTop = Color(0xFF131A14),
    headerBottom = Color(0xFF000000),
    glass = Color.White.copy(alpha = 0.08f),
    chip = Color(0xFF191D19),
    pill = Color(0xFF191D19).copy(alpha = 0.94f),
    pill60 = Color(0xFF191D19).copy(alpha = 0.60f),
    lift = Color(0xFF1E241E).copy(alpha = 0.84f),
    nav = Color(0xFF181C18),
    folder = Color(0xFF7BD69B).copy(alpha = 0.14f),
    ink = Color(0xFFE8EDE7),
    ink2 = Color(0xFFB9C2B8),
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
        headerTop = scheme.primaryContainer,
        headerBottom = scheme.surfaceContainerLow,
        glass = if (dark) {
            scheme.onPrimaryContainer.copy(alpha = 0.10f)
        } else {
            scheme.surfaceContainerLowest.copy(alpha = 0.62f)
        },
        chip = scheme.surfaceContainerHigh,
        pill = raisedSurface.copy(alpha = 0.94f),
        pill60 = raisedSurface.copy(alpha = 0.62f),
        lift = scheme.surfaceContainerHigh.copy(alpha = 0.84f),
        nav = scheme.surfaceContainerHigh,
        folder = scheme.primary.copy(alpha = if (dark) 0.20f else 0.14f),
        ink = scheme.onPrimaryContainer,
        ink2 = scheme.onPrimaryContainer.copy(alpha = 0.76f),
    )
}

// --------------------------------------------------------------- palettes

/** Light — warm white, forest green accents. Stored as [AppTheme.WHITE]. */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A6A47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC3EBD2),
    onPrimaryContainer = Color(0xFF06281A),
    inversePrimary = Color(0xFF8FD3AC),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D8),
    onSecondaryContainer = Color(0xFF0C2417),
    tertiary = Color(0xFF3A6470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEE9F7),
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF7FBF7),
    onBackground = Color(0xFF131C15),
    surface = Color(0xFFF7FBF7),
    onSurface = Color(0xFF131C15),
    surfaceVariant = Color(0xFFDDE6DE),
    onSurfaceVariant = Color(0xFF5C6B5F),
    surfaceTint = Color(0xFF2A6A47),
    inverseSurface = Color(0xFF2A322B),
    inverseOnSurface = Color(0xFFEEF3EE),
    error = Color(0xFF8C4030),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3A0A02),
    outline = Color(0xFF6F7F72),
    outlineVariant = Color(0xFFB3C4B6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8E1D9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F7F2),
    surfaceContainer = Color(0xFFEDF3EE),
    surfaceContainerHigh = Color(0xFFDFE9E1),
    surfaceContainerHighest = Color(0xFFD9E3DB),
)

/** Beige — warm paper, umber accents. Stored as [AppTheme.SEPIA]. */
private val BeigeColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF7E5326),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9D6B2),
    onPrimaryContainer = Color(0xFF3E2E1A),
    inversePrimary = Color(0xFFE0BC8C),
    secondary = Color(0xFF6E5C42),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE0C4),
    onSecondaryContainer = Color(0xFF2A2013),
    tertiary = Color(0xFF5A5A33),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E2B6),
    onTertiaryContainer = Color(0xFF1B1B00),
    background = Color(0xFFF3E7D2),
    onBackground = Color(0xFF3B2F1E),
    surface = Color(0xFFF3E7D2),
    onSurface = Color(0xFF3B2F1E),
    surfaceVariant = Color(0xFFE5D6B8),
    onSurfaceVariant = Color(0xFF7C6B51),
    surfaceTint = Color(0xFF7E5326),
    inverseSurface = Color(0xFF38301F),
    inverseOnSurface = Color(0xFFF7EEDC),
    error = Color(0xFF9A3B2C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3A0A02),
    outline = Color(0xFF8E7A5C),
    outlineVariant = Color(0xFFCDB894),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF4E6),
    surfaceDim = Color(0xFFDCCFB5),
    surfaceContainerLowest = Color(0xFFFBF4E6),
    surfaceContainerLow = Color(0xFFF5EBD7),
    surfaceContainer = Color(0xFFF7EEDC),
    surfaceContainerHigh = Color(0xFFE9DBC0),
    surfaceContainerHighest = Color(0xFFE3D3B6),
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
