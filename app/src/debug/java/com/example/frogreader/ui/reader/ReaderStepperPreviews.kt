package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 393, heightDp = 480)
@Composable
private fun ReaderSteppersLightPreview() = SteppersPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 480)
@Composable
private fun ReaderSteppersBeigePreview() = SteppersPreview(AppTheme.SEPIA)

@Preview(widthDp = 393, heightDp = 480)
@Composable
private fun ReaderSteppersMidnightPreview() = SteppersPreview(AppTheme.OLED)

@Preview(widthDp = 393, heightDp = 480, wallpaper = 1)
@Composable
private fun ReaderSteppersMaterialYouPreview() = SteppersPreview(AppTheme.WHITE, dynamic = true)

@Preview(widthDp = 320, heightDp = 760, fontScale = 1.5f)
@Composable
private fun ReaderSteppersLargeTextPreview() = SteppersPreview(AppTheme.SEPIA)

@Preview(widthDp = 393, heightDp = 480)
@Composable
private fun ReaderSteppersBoundsPreview() = SteppersPreview(AppTheme.OLED, bounds = true)

/** Interactive memory-only panel, including all six repeatable buttons. */
@Composable
private fun SteppersPreview(theme: AppTheme, dynamic: Boolean = false, bounds: Boolean = false) {
    var settings by remember {
        mutableStateOf(ReaderSettings(
            readingMode = ReadingMode.PAGES,
            lineHeight = if (bounds) 1.2f else 1.5f,
            fontSizeSp = if (bounds) 32f else 18f,
            pageMarginDp = if (bounds) ReaderSettings.MIN_SIDE_MARGIN_DP else 34,
        ))
    }
    var appSettings by remember { mutableStateOf(AppSettings(theme = theme, dynamicColor = dynamic)) }
    FrogReaderTheme(theme, dynamicColor = dynamic) {
        val colors = readerColors(theme, MaterialTheme.colorScheme)
        ReaderSettingsPanel(
            settings, appSettings, colors, 0.5f,
            onUpdate = { settings = it(settings) },
            onUpdateApp = { appSettings = it(appSettings) },
            onBrightnessChange = {},
            modifier = Modifier.fillMaxSize().background(colors.chrome).padding(16.dp),
            scrollState = rememberScrollState(120),
        )
    }
}
