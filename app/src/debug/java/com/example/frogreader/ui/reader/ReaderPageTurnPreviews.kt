package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Page turns · Light", widthDp = 393)
@Composable
private fun PageTurnsLight() = PageTurnPreview(AppTheme.WHITE)

@Preview(name = "Page turns · Beige", widthDp = 393)
@Composable
private fun PageTurnsBeige() = PageTurnPreview(AppTheme.SEPIA)

@Preview(name = "Page turns · Midnight", widthDp = 393)
@Composable
private fun PageTurnsMidnight() = PageTurnPreview(AppTheme.OLED)

@Preview(name = "Page turns · narrow", widthDp = 320)
@Composable
private fun PageTurnsNarrow() = PageTurnPreview(AppTheme.WHITE)

@Preview(name = "Page turns · Beige · large text", widthDp = 360, fontScale = 1.5f)
@Composable
private fun PageTurnsLargeText() = PageTurnPreview(AppTheme.SEPIA)

@Preview(name = "Page turns · Light · Material You", widthDp = 393, apiLevel = 36)
@Composable
private fun PageTurnsDynamic() = PageTurnPreview(AppTheme.WHITE, dynamicColor = true)

@Preview(name = "Page turns · all themes", widthDp = 393)
@Composable
private fun PageTurnsOverview() {
    Column {
        AppTheme.entries.forEach { theme ->
            FrogReaderTheme(theme) {
                val colors = readerColors(theme, MaterialTheme.colorScheme)
                Surface(color = colors.chrome, contentColor = colors.onChrome) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = when (theme) {
                                AppTheme.WHITE -> "Light"
                                AppTheme.SEPIA -> "Beige"
                                AppTheme.OLED -> "Midnight"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        var selected by remember { mutableStateOf(PageTurnAnimation.SLIDE) }
                        ReaderPageTurnPicker(selected, colors, onSelected = { selected = it })
                    }
                }
            }
        }
    }
}

/** Every icon is shown selected once; interactive selections stay in memory. */
@Composable
private fun PageTurnPreview(theme: AppTheme, dynamicColor: Boolean = false) {
    FrogReaderTheme(theme, dynamicColor = dynamicColor) {
        val colors = readerColors(theme, MaterialTheme.colorScheme)
        Surface(color = colors.chrome, contentColor = colors.onChrome) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Page animation", style = MaterialTheme.typography.labelLarge)
                PageTurnAnimation.entries.forEach { animation ->
                    var selected by remember { mutableStateOf(animation) }
                    ReaderPageTurnPicker(selected, colors, onSelected = { selected = it })
                }
            }
        }
    }
}
