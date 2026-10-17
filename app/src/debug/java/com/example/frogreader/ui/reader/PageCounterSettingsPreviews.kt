package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 393, heightDp = 390)
@Composable
private fun PageCounterLightPreview() = CounterPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 390)
@Composable
private fun PageCounterBeigePreview() = CounterPreview(AppTheme.SEPIA, style = 1)

@Preview(widthDp = 393, heightDp = 390)
@Composable
private fun PageCounterMidnightPreview() = CounterPreview(AppTheme.OLED, style = 2)

@Preview(widthDp = 393, heightDp = 390, wallpaper = 1)
@Composable
private fun PageCounterMaterialYouLightPreview() = CounterPreview(AppTheme.WHITE, dynamic = true, style = 1)

@Preview(widthDp = 393, heightDp = 390, wallpaper = 2)
@Composable
private fun PageCounterMaterialYouDarkPreview() = CounterPreview(AppTheme.OLED, dynamic = true, style = 2)

@Preview(widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable
private fun PageCounterLargeTextPreview() = CounterPreview(AppTheme.SEPIA, style = 2)

@Preview(widthDp = 360, heightDp = 670)
@Composable
private fun PageCounterNarrowPreview() = CounterPreview(AppTheme.WHITE, style = 1)

@Preview(widthDp = 393, heightDp = 430)
@Composable
private fun PageCounterLoadingPreview() = CounterPreview(AppTheme.WHITE, info = null)

@Preview(widthDp = 393, heightDp = 200)
@Composable
private fun PageCounterOffPreview() = CounterPreview(AppTheme.SEPIA, enabled = false)

@Preview(widthDp = 393, heightDp = 200)
@Composable
private fun PageCounterScrollPreview() = CounterPreview(AppTheme.OLED, mode = ReadingMode.SCROLL)

@Preview(widthDp = 700, heightDp = 390)
@Composable
private fun PageCounterWidePreview() = CounterPreview(AppTheme.WHITE, style = 2, info = PageCounterInfo(12048, 12048, 0))

@Preview(widthDp = 393, heightDp = 390)
@Composable
private fun PageCounterThousandPreview() = CounterPreview(
    AppTheme.WHITE, style = 1, info = PageCounterInfo(1000, 1000, 0),
)

@Preview(widthDp = 393, heightDp = 390)
@Composable
private fun PageCounterThirtyThousandPreview() = CounterPreview(
    AppTheme.SEPIA, style = 1, info = PageCounterInfo(30000, 30000, 0),
)

@Preview(widthDp = 360, heightDp = 750)
@Composable
private fun PageCounterLongChapterPreview() = CounterPreview(
    AppTheme.OLED, style = 2, info = PageCounterInfo(1000, 30000, 29000),
)

@Preview(widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable
private fun PageCounterLargeNumbersTextPreview() = CounterPreview(
    AppTheme.SEPIA, style = 1, info = PageCounterInfo(30000, 30000, 0),
)

@Preview(widthDp = 393, heightDp = 750)
@Composable
private fun PageCounterExtremeNumbersPreview() = CounterPreview(
    AppTheme.WHITE, style = 1, info = PageCounterInfo(Int.MAX_VALUE, Int.MAX_VALUE, 0),
)

@Preview(widthDp = 393, heightDp = 760)
@Composable
private fun PageCounterInSettingsPreview() {
    var settings by remember { mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGES)) }
    FrogReaderTheme(AppTheme.SEPIA) {
        val colors = readerColors(AppTheme.SEPIA, MaterialTheme.colorScheme)
        ReaderSettingsPanel(
            settings = settings,
            appSettings = AppSettings(theme = AppTheme.SEPIA),
            colors = colors,
            brightness = null,
            onUpdate = { settings = it(settings) },
            onUpdateApp = {},
            onBrightnessChange = {},
            pageCounterInfo = PageCounterInfo(150, 932, 21),
            scrollState = rememberScrollState(Int.MAX_VALUE),
            modifier = Modifier.fillMaxSize().background(colors.chrome).padding(16.dp),
        )
    }
}

/** Fixtures are memory-only; runtime previews receive numbers from the open book. */
@Composable
private fun CounterPreview(
    theme: AppTheme,
    dynamic: Boolean = false,
    style: Int = 0,
    info: PageCounterInfo? = PageCounterInfo(150, 932, 21),
    enabled: Boolean = true,
    mode: ReadingMode = ReadingMode.PAGES,
) {
    var settings by remember {
        mutableStateOf(ReaderSettings(
            readingMode = mode,
            showPageCounter = enabled,
            showTotalPageCount = style == 1,
            showChapterPagesLeft = style == 2,
        ))
    }
    FrogReaderTheme(theme, dynamicColor = dynamic) {
        val colors = readerColors(theme, MaterialTheme.colorScheme)
        Column(
            Modifier.fillMaxSize().background(colors.chrome)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            ReaderPageCounterSettings(settings, colors, info) { settings = it(settings) }
        }
    }
}
