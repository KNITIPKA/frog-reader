package com.example.frogreader.reader

import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.ui.reader.ReaderMetrics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMetricsTest {

    private val settings = ReaderSettings(font = ReaderFont.SERIF)

    @Test
    fun `all six default heading levels have distinct descending sizes`() {
        val baseFontSize = 20f
        val sizes = (1..6).map { level ->
            ReaderMetrics.textStyle(
                ContentElement.Heading("Heading $level", level),
                settings,
                baseFontSize,
            ).fontSize.value
        }

        sizes.zipWithNext().forEachIndexed { index, (larger, smaller) ->
            assertTrue("H${index + 1} must be larger than H${index + 2}", larger > smaller)
        }
        sizes.forEachIndexed { index, actual ->
            assertEquals(
                baseFontSize * ReaderMetrics.headingScale(index + 1),
                actual,
                0.001f,
            )
        }
    }

    @Test
    fun `heading hierarchy scales from the user base font size`() {
        for (baseFontSize in listOf(12f, 18f, 32f)) {
            for (level in 1..6) {
                val style = ReaderMetrics.textStyle(
                    ContentElement.Heading("Heading $level", level),
                    settings,
                    baseFontSize,
                )
                val expectedSize = baseFontSize * ReaderMetrics.headingScale(level)
                assertEquals(expectedSize, style.fontSize.value, 0.001f)
                assertEquals(expectedSize * 1.25f, style.lineHeight.value, 0.001f)
            }
        }
    }

    @Test
    fun `publisher heading scale overrides the level default relative to user base`() {
        val baseFontSize = 18f
        val publisherScale = 1.72f
        val heading = ContentElement.Heading(
            text = "Publisher heading",
            level = 6,
            block = BlockStyle(fontScale = publisherScale, lineHeightMult = 1.1f),
        )
        val publisherSettings = settings.copy(bookStyles = true)

        val style = ReaderMetrics.textStyle(heading, publisherSettings, baseFontSize)

        assertEquals(baseFontSize * publisherScale, style.fontSize.value, 0.001f)
        assertEquals(baseFontSize * publisherScale * 1.1f, style.lineHeight.value, 0.001f)
    }

    @Test
    fun `explicit one em heading size overrides semantic level default`() {
        val baseFontSize = 18f
        val heading = ContentElement.Heading(
            text = "Author-sized H6",
            level = 6,
            block = BlockStyle(fontScale = 1f),
        )

        val style = ReaderMetrics.textStyle(heading, settings, baseFontSize)

        assertEquals(baseFontSize, style.fontSize.value, 0.001f)
        assertTrue(style.fontSize.value > baseFontSize * ReaderMetrics.headingScale(6))
    }

    @Test
    fun `pagination and rendering inputs resolve the same heading metrics`() {
        val heading = ContentElement.Heading("Split heading", level = 5)
        // Pagination measures the whole element (paragraph start = true),
        // while rendering may draw a fragment with paragraph start = false.
        val measured = ReaderMetrics.textStyle(
            heading,
            settings,
            fontSize = 23f,
            isParagraphStart = true,
        )
        val rendered = ReaderMetrics.textStyle(
            heading,
            settings,
            fontSize = 23f,
            isParagraphStart = false,
        )

        assertEquals(measured.fontSize, rendered.fontSize)
        assertEquals(measured.lineHeight, rendered.lineHeight)
    }

    @Test
    fun `table cells inherit table typography before relative cell spans`() {
        val baseFontSize = 20f
        val block = BlockStyle(
            fontScale = 1.5f,
            lineHeightMult = 1.1f,
            italic = true,
            bold = true,
            language = "uk",
            direction = BookTextDirection.RTL,
        )

        val style = ReaderMetrics.tableCellStyle(
            settings = settings.copy(bookStyles = true),
            fontSize = baseFontSize,
            scale = 1f,
            header = false,
            language = "en",
            tableBlock = block,
        )

        // Tables deliberately use a 0.92 readability factor; publisher scale
        // is applied once on top of it, before an AnnotatedString's em spans.
        assertEquals(baseFontSize * 0.92f * 1.5f, style.fontSize.value, 0.001f)
        assertEquals(baseFontSize * 0.92f * 1.5f * 1.1f, style.lineHeight.value, 0.001f)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, style.fontStyle)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, style.fontWeight)
        assertEquals(androidx.compose.ui.text.style.TextDirection.Rtl, style.textDirection)
        assertEquals("uk", style.localeList?.firstOrNull()?.toLanguageTag())
    }

    @Test
    fun `table cell typography overrides table without double scaling`() {
        val style = ReaderMetrics.tableCellStyle(
            settings = settings.copy(bookStyles = true),
            fontSize = 20f,
            scale = 1f,
            header = true,
            language = "en",
            tableBlock = BlockStyle(
                fontScale = 1.5f,
                lineHeightMult = 1.2f,
                bold = true,
                language = "en",
            ),
            cellBlock = BlockStyle(
                fontScale = 0.8f,
                lineHeightMult = 1.6f,
                bold = false,
                italic = true,
                language = "uk",
                direction = BookTextDirection.RTL,
            ),
        )

        assertEquals(20f * 0.92f * 0.8f, style.fontSize.value, 0.001f)
        assertEquals(20f * 0.92f * 0.8f * 1.6f, style.lineHeight.value, 0.001f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, style.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, style.fontStyle)
        assertEquals("uk", style.localeList?.firstOrNull()?.toLanguageTag())
        assertEquals(androidx.compose.ui.text.style.TextDirection.Rtl, style.textDirection)
    }

    @Test
    fun `drop cap keeps structural direction but gates publisher font`() {
        val cap = FirstLetterStyle(
            scale = 3.4f,
            isDropCap = true,
            fontFamily = "cursive",
            direction = BookTextDirection.LTR,
            language = "ru",
            sourceTextLength = 1,
        )

        val readerTypography = ReaderMetrics.dropCapStyle(
            settings.copy(bookStyles = false, dropCaps = true),
            capFontSizeSp = 48f,
            cap = cap,
            bookFonts = emptyMap(),
            language = "uk",
        )
        val publisherTypography = ReaderMetrics.dropCapStyle(
            settings.copy(bookStyles = true),
            capFontSizeSp = 48f,
            cap = cap,
            bookFonts = emptyMap(),
            language = "uk",
        )

        assertEquals(FontFamily.Serif, readerTypography.fontFamily)
        assertEquals(FontFamily.Cursive, publisherTypography.fontFamily)
        assertEquals(TextDirection.Ltr, readerTypography.textDirection)
        assertEquals("ru", readerTypography.localeList?.firstOrNull()?.toLanguageTag())
    }
}
