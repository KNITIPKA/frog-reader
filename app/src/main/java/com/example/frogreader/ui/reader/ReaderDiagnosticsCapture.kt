package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.diagnostics.ReaderDiagnosticSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureReaderDiagnostics(
    ready: ReaderState.Ready,
    viewModel: ReaderViewModel,
    settings: ReaderSettings,
    appSettings: AppSettings,
    liveFontSize: Float,
    viewport: IntSize,
) {
    val tracker = (LocalContext.current.applicationContext as? FrogReaderApp)?.readerDiagnostics
    val density = LocalDensity.current
    val insets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val topInset = insets.calculateTopPadding()
    val bottomInset = insets.calculateBottomPadding()
    val fonts = remember(ready) { ready.bookFonts.keys.sorted() }
    val record = rememberUpdatedState {
        val holder = viewModel.pagination.value.takeIf { settings.readingMode == ReadingMode.PAGES }
        val rendered = holder?.settings ?: settings.copy(fontSizeSp = liveFontSize)
        val top = topInset + if (settings.readingMode == ReadingMode.PAGES) 16.dp else 20.dp
        val bottom = bottomInset + if (settings.readingMode == ReadingMode.PAGES) 40.dp else 48.dp
        tracker?.record(
            ReaderDiagnosticSnapshot(
                bookId = ready.book.id,
                capturedAtMillis = System.currentTimeMillis(),
                viewportWidthPx = viewport.width, viewportHeightPx = viewport.height,
                contentWidthPx = with(density) {
                    (viewport.width.toDp() - ReaderMetrics.horizontalPadding(rendered.pageMargins) * 2)
                        .roundToPx().coerceAtLeast(0)
                },
                contentHeightPx = with(density) {
                    (viewport.height.toDp() - top - bottom).roundToPx().coerceAtLeast(0)
                },
                density = density.density, fontScale = density.fontScale,
                baseFontSizePx = with(density) { rendered.fontSizeSp.sp.toPx() },
                renderSettings = rendered, theme = appSettings.theme,
                imagesInverted = appSettings.autoInvertImages && appSettings.theme == AppTheme.OLED,
                flatItemIndex = viewModel.currentFlatIndex,
                charOffset = viewModel.currentCharOffset.takeIf { settings.readingMode == ReadingMode.PAGES },
                scrollOffsetPx = viewModel.currentScrollOffset,
                itemCount = ready.items.size, chapterCount = ready.chapterStarts.size,
                language = ready.language, pageProgression = ready.pageProgression.name,
                embeddedFontFamilies = fonts,
                calculatedPages = holder?.pages?.size, paginationPartial = holder?.partial,
            ),
        )
    }
    LaunchedEffect(ready, viewport, settings, appSettings.theme, appSettings.autoInvertImages,
        liveFontSize, density, topInset, bottomInset) {
        record.value()
    }
    DisposableEffect(ready.book.id) {
        // Read the final live position when leaving, before returning to Settings/About.
        onDispose { record.value() }
    }
}
