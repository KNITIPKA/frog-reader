package com.example.frogreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.frogreader.data.AppTheme

/**
 * App-wide theme: Material 3 Expressive with the springy motion scheme.
 * White and OLED keep dynamic (Material You) accents from the wallpaper;
 * Sepia uses a curated warm palette so nothing clashes with the paper tone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FrogReaderTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (theme) {
        AppTheme.WHITE -> dynamicLightColorScheme(context)
        AppTheme.SEPIA -> sepiaColorScheme(dynamicLightColorScheme(context))
        AppTheme.OLED -> oledColorScheme(dynamicDarkColorScheme(context))
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** True when the theme needs light status-bar icons. */
fun AppTheme.isDark(): Boolean = this == AppTheme.OLED

/** Warm paper tones over the dynamic scheme; accents become book-leather browns. */
private fun sepiaColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = Color(0xFF8B5E2A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDD9B4),
    onPrimaryContainer = Color(0xFF453723),
    inversePrimary = Color(0xFFD9B98A),
    secondary = Color(0xFF7E6E52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADCBF),
    onSecondaryContainer = Color(0xFF453723),
    tertiary = Color(0xFF6E5A3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DAB9),
    onTertiaryContainer = Color(0xFF443519),
    background = Color(0xFFF4E9D3),
    onBackground = Color(0xFF453723),
    surface = Color(0xFFF4E9D3),
    onSurface = Color(0xFF453723),
    surfaceVariant = Color(0xFFE7D8B8),
    onSurfaceVariant = Color(0xFF7E6E52),
    surfaceTint = Color(0xFF8B5E2A),
    inverseSurface = Color(0xFF453723),
    inverseOnSurface = Color(0xFFF4E9D3),
    outline = Color(0xFF9C8B6C),
    outlineVariant = Color(0xFFD9C9A8),
    surfaceBright = Color(0xFFFBF3E2),
    surfaceDim = Color(0xFFE0D2B4),
    surfaceContainerLowest = Color(0xFFFBF3E2),
    surfaceContainerLow = Color(0xFFF6ECD8),
    surfaceContainer = Color(0xFFEFE3C9),
    surfaceContainerHigh = Color(0xFFEADCBF),
    surfaceContainerHighest = Color(0xFFE4D4B4),
)

/** Pure-black surfaces for OLED; dynamic accents stay untouched. */
private fun oledColorScheme(base: ColorScheme): ColorScheme = base.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF222226),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF222226),
)
