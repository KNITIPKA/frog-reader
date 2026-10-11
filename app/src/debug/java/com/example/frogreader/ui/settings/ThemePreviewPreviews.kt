package com.example.frogreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Themes · Light", widthDp = 393, heightDp = 852, showSystemUi = true)
@Preview(name = "Themes · narrow", widthDp = 320, heightDp = 740, showSystemUi = true)
@Preview(name = "Themes · compact cards", widthDp = 360, heightDp = 800)
@Preview(name = "Themes · landscape", widthDp = 800, heightDp = 480)
@Composable
private fun LightThemePickerPreview() = InteractiveThemePage(AppTheme.WHITE)

@Preview(name = "Themes · Beige", widthDp = 393, heightDp = 852, showSystemUi = true)
@Preview(name = "Themes · Beige · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun BeigeThemePickerPreview() = InteractiveThemePage(AppTheme.SEPIA)

@Preview(name = "Themes · Midnight", widthDp = 393, heightDp = 852, showSystemUi = true)
@Preview(name = "Themes · Midnight · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun MidnightThemePickerPreview() = InteractiveThemePage(AppTheme.OLED)

@Preview(name = "Themes · follows system", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun AutomaticThemePickerPreview() = InteractiveThemePage(AppTheme.WHITE, followSystem = true)

@Preview(name = "Themes · Material You", widthDp = 393, heightDp = 852, showSystemUi = true, apiLevel = 36)
@Composable
private fun MaterialYouThemePickerPreview() = InteractiveThemePage(AppTheme.WHITE, dynamicColor = true)

/** Memory-only state lets interactive preview exercise selection and interrupted transitions. */
@Composable
private fun InteractiveThemePage(
    initialTheme: AppTheme,
    followSystem: Boolean = false,
    dynamicColor: Boolean = false,
) {
    var settings by remember {
        mutableStateOf(AppSettings(theme = initialTheme, followSystemTheme = followSystem, dynamicColor = dynamicColor))
    }
    FrogReaderTheme(settings.effectiveTheme(systemDark = false), settings.dynamicColor) {
        ThemeSettingsPage(settings, onUpdate = { settings = it(settings) }, onBack = {})
    }
}
