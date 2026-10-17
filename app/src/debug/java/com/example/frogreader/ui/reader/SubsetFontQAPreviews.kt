package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.groupCustomFonts
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.customFontFamily
import java.io.File

// Local supplied-book QA input, kept outside application resources and backups.
private const val subsetQaFonts = "/Users/frog/AndroidStudioProjects/FrogReader/.artifacts/20261014-anne-font-regression/fonts"

@Preview(name = "Actual Anne subset · families", widthDp = 393, heightDp = 650)
@Composable
private fun AnneSubsetFamiliesQAPreview() = AnneSubsetDialogQA(AppTheme.WHITE)

@Preview(name = "Actual Anne subset · styles", widthDp = 393, heightDp = 750)
@Composable
private fun AnneSubsetStylesQAPreview() = AnneSubsetDialogQA(AppTheme.WHITE, styles = true)

@Preview(name = "Actual Anne subset · Beige styles", widthDp = 393, heightDp = 750)
@Composable
private fun AnneSubsetStylesBeigeQAPreview() = AnneSubsetDialogQA(AppTheme.SEPIA, styles = true)

@Preview(name = "Actual Anne subset · narrow large text", widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable
private fun AnneSubsetStylesNarrowQAPreview() = AnneSubsetDialogQA(AppTheme.WHITE, styles = true)

@Composable
private fun AnneSubsetDialogQA(theme: AppTheme, styles: Boolean = false) {
    val group = remember { groupCustomFonts(File(subsetQaFonts).listFiles().orEmpty().toList()).firstOrNull() }
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.fillMaxSize().background(colors.background).padding(24.dp), contentAlignment = Alignment.Center) {
            if (group == null) {
                Text("Extract the local supplied EPUB fonts into .artifacts/20261014-anne-font-regression/fonts to run this preview.")
            } else {
                CustomFontDialogSurface(Modifier.widthIn(max = 345.dp)) {
                    CustomFontsContent(
                        colors = colors,
                        fonts = emptyList(),
                        embedded = listOf(group),
                        opened = group.takeIf { styles },
                        fromBook = true,
                        bodyFontPath = group.preview.path,
                        bodyFontAuto = true,
                    )
                }
            }
        }
    }
}

@Preview(name = "Actual Anne subset · font chip selection", widthDp = 393, heightDp = 430)
@Composable
private fun AnneSubsetFontChipQAPreview() {
    val group = remember { groupCustomFonts(File(subsetQaFonts).listFiles().orEmpty().toList()).firstOrNull() }
    FrogReaderTheme(AppTheme.WHITE) {
        val colors = readerColors(AppTheme.WHITE)
        Column(Modifier.fillMaxSize().background(colors.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Publisher's formatting")
            ReaderFontPicker(
                settings = ReaderSettings(bookStyles = true),
                colors = colors,
                bookFontName = "Times new roman",
                bookFontPath = group?.preview?.path,
                bookFontFamily = group?.let { customFontFamily(it.preview.path, auto = true, familyPaths = it.faces.map { face -> face.path }.distinct()) },
                onSelect = {},
            )
            Text("Sans selected")
            ReaderFontPicker(ReaderSettings(font = ReaderFont.SANS), colors, onSelect = {})
            Text("Literata selected")
            ReaderFontPicker(ReaderSettings(font = ReaderFont.LITERATA), colors, onSelect = {})
        }
    }
}
