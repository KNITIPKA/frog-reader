package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.reader.selectionToolbarPalette
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "PDF selection · Light", widthDp = 360)
@Composable private fun PdfSelectionLight() = PdfSelectionPreview(AppTheme.WHITE)

@Preview(name = "PDF selection · Midnight", widthDp = 360)
@Composable private fun PdfSelectionMidnight() = PdfSelectionPreview(AppTheme.OLED)

@Preview(name = "PDF selection · Beige · narrow large text", widthDp = 320, fontScale = 1.5f)
@Composable private fun PdfSelectionBeigeLarge() = PdfSelectionPreview(AppTheme.SEPIA)

@Preview(name = "PDF selection · overlap · Light", widthDp = 360)
@Composable private fun PdfSelectionOverlap() = PdfSelectionPreview(AppTheme.WHITE, overlap = true)

@Composable private fun PdfSelectionPreview(theme: AppTheme, overlap: Boolean = false) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.padding(12.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = selectionToolbarPalette(colors).container) {
                PdfSelectionActions(
                    colors = colors,
                    selectedQuotes = if (overlap) setOf("preview") else emptySet(),
                    availableColors = QuoteColor.entries.filter { !overlap || it != QuoteColor.AMBER },
                    onRemoveQuotes = {}, onQuote = {}, onAddNote = {},
                    onTranslate = {}, onCopy = {}, onDefine = {}, onExternalSearch = {},
                )
            }
        }
    }
}
