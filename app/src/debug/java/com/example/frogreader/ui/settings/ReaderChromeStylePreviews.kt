package com.example.frogreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Reading · Light · Floating", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun LightReadingChromePreview() = InteractiveReadingPage(AppTheme.WHITE)

@Preview(name = "Reading · Beige · Classic", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun BeigeReadingChromePreview() = InteractiveReadingPage(AppTheme.SEPIA, ReaderChromeStyle.REGULAR)

@Preview(name = "Reading · Midnight · Floating", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun MidnightReadingChromePreview() = InteractiveReadingPage(AppTheme.OLED)

@Preview(name = "Reading · narrow", widthDp = 320, heightDp = 740)
@Composable
private fun NarrowReadingChromePreview() = InteractiveReadingPage(AppTheme.WHITE)

@Preview(name = "Reading · Beige · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun LargeTextReadingChromePreview() = InteractiveReadingPage(AppTheme.SEPIA)

@Preview(name = "Reading · landscape", widthDp = 800, heightDp = 480)
@Composable
private fun LandscapeReadingChromePreview() = InteractiveReadingPage(AppTheme.WHITE, ReaderChromeStyle.REGULAR)

@Preview(name = "Reading · Material You", widthDp = 393, heightDp = 852, showSystemUi = true, apiLevel = 36)
@Composable
private fun MaterialYouReadingChromePreview() = InteractiveReadingPage(AppTheme.WHITE, dynamicColor = true)

/** Interactive preview only updates memory and never writes the user's settings. */
@Composable
private fun InteractiveReadingPage(
    theme: AppTheme,
    style: ReaderChromeStyle = ReaderChromeStyle.FLOATING,
    dynamicColor: Boolean = false,
) {
    var settings by remember {
        mutableStateOf(AppSettings(
            theme = theme,
            followSystemTheme = false,
            dynamicColor = dynamicColor,
            readerChromeStyle = style,
        ))
    }
    FrogReaderTheme(theme, dynamicColor) {
        ReadingSettingsPage(settings, onUpdate = { settings = it(settings) }, onBack = {})
    }
}
