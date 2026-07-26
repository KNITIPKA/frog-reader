package com.example.frogreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.AppTheme

/** Colors of the reading surface; chrome = the bars floating above the text. */
class ReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val chrome: Color,
    val onChrome: Color,
    val accent: Color,
    /** Background of text saved as a quote. */
    val quoteHighlight: Color,
)

@Composable
fun readerColors(theme: AppTheme): ReaderColors = when (theme) {
    // Pure white and neutral greys on purpose: the warm "paper" tone is
    // what SEPIA is for, so the two themes stay clearly different.
    AppTheme.WHITE -> ReaderColors(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1A1A1A),
        secondaryText = Color(0xFF6B6B6B),
        chrome = Color(0xFFF3F3F3),
        onChrome = Color(0xFF1A1A1A),
        accent = Color(0xFF7D5E3C),
        quoteHighlight = Color(0x4DFFC107),
    )

    AppTheme.SEPIA -> ReaderColors(
        background = Color(0xFFF4E9D3),
        text = Color(0xFF453723),
        secondaryText = Color(0xFF7E6E52),
        chrome = Color(0xFFEADCBF),
        onChrome = Color(0xFF453723),
        accent = Color(0xFF8B5E2A),
        quoteHighlight = Color(0x4DFFB300),
    )

    AppTheme.OLED -> ReaderColors(
        background = Color(0xFF000000),
        text = Color(0xFFC9C9C9),
        secondaryText = Color(0xFF8A8A8A),
        chrome = Color(0xFF121212),
        onChrome = Color(0xFFC9C9C9),
        accent = Color(0xFFA89878),
        quoteHighlight = Color(0x38FFC107),
    )
}
