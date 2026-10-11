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

    private val settings = ReaderSettings(font = ReaderFont.SERIF, bookStyles = false)

    @Test
    fun `publisher font features reach paragraphs and tables through shared measurement styles`() {
        val features = "\"smcp\" 1, \"c2sc\" 1"
        val block = BlockStyle(fontFeatureSettings = features)
        val paragraph = ContentElement.Paragraph(androidx.compose.ui.text.AnnotatedString("Type"), block = block)
        val publisher = settings.copy(bookStyles = true)
        assertEquals(features, ReaderMetrics.textStyle(paragraph, publisher, 20f).fontFeatureSettings)
        assertEquals(null, ReaderMetrics.textStyle(paragraph, settings.copy(bookStyles = false), 20f).fontFeatureSettings)
        assertEquals(features, ReaderMetrics.tableCellStyle(publisher, 20f, 1f, false, null,
            tableBlock = block).fontFeatureSettings)
        assertEquals("\"smcp\" 0", ReaderMetrics.tableCellStyle(publisher, 20f, 1f, false, null,
            tableBlock = block, cellBlock = BlockStyle(fontFeatureSettings = "\"smcp\" 0")).fontFeatureSettings)
    }

    @Test
    fun `authored blank page spacing retains ten em while invalid spacing stays bounded`() {
        assertEquals(200f, ReaderMetrics.spacerHeight(ContentElement.Spacer(10f), 20f).value, 0.001f)
        assertEquals(20f, ReaderMetrics.spacerHeight(ContentElement.Spacer(Float.NaN), 20f).value, 0.001f)
        assertEquals(6f, ReaderMetrics.spacerHeight(ContentElement.Spacer(-10f), 20f).value, 0.001f)
        assertEquals(0f, ReaderMetrics.spacerHeight(ContentElement.Spacer(0f), 20f).value, 0.001f)
    }

    @Test
    fun `css horizontal prose rows retain body font size`() {
        assertEquals(18f, ReaderMetrics.tableCellStyle(settings, 18f, 1f, false, null,
            semanticFontScale = 1f).fontSize.value, 0.001f)
    }

    @Test
    fun `publisher paragraphs preserve full line boxes without adding margins`() {
        for (fontSize in listOf(12f, 18f, 32f)) {
            for (lineHeight in listOf(1.2f, 1.5f, 1.8f)) {
                val paragraph = ContentElement.Paragraph(
                    androidx.compose.ui.text.AnnotatedString("A paragraph with zero author margins."),
                    block = BlockStyle(spaceBeforeSpecified = true, spaceAfterSpecified = true),
                )
                val publisher = settings.copy(bookStyles = true, lineHeight = lineHeight)
                for (firstFragment in listOf(true, false)) {
                    val style = ReaderMetrics.textStyle(paragraph, publisher, fontSize, firstFragment)
                    assertEquals(androidx.compose.ui.text.style.LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
                    assertEquals(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, style.lineHeightStyle?.alignment)
                    assertEquals(false, style.platformStyle?.paragraphStyle?.includeFontPadding)
                    assertEquals(fontSize * lineHeight, style.lineHeight.value, 0.001f)
                }
                val (top, bottom) = ReaderMetrics.verticalPaddings(paragraph, fontSize, bookStyles = true)
                assertEquals(0f, top.value, 0f)
                assertEquals(0f, bottom.value, 0f)
                // The user's existing non-publisher layout is unchanged.
                org.junit.Assert.assertNull(
                    ReaderMetrics.textStyle(paragraph, publisher.copy(bookStyles = false), fontSize).lineHeightStyle,
                )
            }
        }
    }

    @Test
    fun `explicit publisher line height retains leading at paragraph boundaries`() {
        val paragraph = ContentElement.Paragraph(
            androidx.compose.ui.text.AnnotatedString("Text"),
            block = BlockStyle(fontScale = 0.95f, lineHeightMult = 1.4f),
        )
        val style = ReaderMetrics.textStyle(paragraph, settings.copy(bookStyles = true), 20f)
        assertEquals(26.6f, style.lineHeight.value, 0.001f)
        assertEquals(androidx.compose.ui.text.style.LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
    }

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
    fun `heading defaults respect text direction and never reduce readable body size`() {
        for (level in 1..6) {
            for (language in listOf("ru", "uk", "en", "ar", "he")) {
                val style = ReaderMetrics.textStyle(
                    ContentElement.Heading("Heading", level), settings, 20f, language = language,
                )
                assertEquals(androidx.compose.ui.text.style.TextAlign.Start, style.textAlign)
                assertTrue(style.fontSize.value >= 20f)
                assertEquals(androidx.compose.ui.text.style.Hyphens.None, style.hyphens)
            }
        }
    }

    @Test
    fun `scene ornaments stay centered but author alignment still wins`() {
        for (text in listOf("* * *", "⁂", "❦")) {
            val ornament = ContentElement.Heading(text, 4)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Center,
                ReaderMetrics.textStyle(ornament, settings, 20f).textAlign)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Left,
                ReaderMetrics.textStyle(ornament.copy(block = BlockStyle(align = com.example.frogreader.data.model.BlockAlign.LEFT)), settings, 20f).textAlign)
        }
        assertEquals(androidx.compose.ui.text.style.TextAlign.Start,
            ReaderMetrics.textStyle(ContentElement.Heading("Chapter *", 1), settings, 20f).textAlign)
    }

    @Test
    fun `heading spacing follows font size and keeps heading closer to its content`() {
        for (level in 1..6) {
            val heading = ContentElement.Heading("Heading", level)
            val small = ReaderMetrics.verticalPaddings(heading, 16f)
            val large = ReaderMetrics.verticalPaddings(heading, 32f)
            assertTrue(small.first > small.second)
            assertTrue(small.second.value > 0f)
            assertEquals(small.first.value * 2f, large.first.value, 0.001f)
            assertEquals(small.second.value * 2f, large.second.value, 0.001f)
        }
        val h2 = ReaderMetrics.verticalPaddings(ContentElement.Heading("Section", 2), 20f)
        assertTrue("Compact spacing replaces the old 28dp on each side", h2.first.value + h2.second.value < 56f)
    }

    @Test
    fun `authored heading alignment wins for every level regardless of publisher typography`() {
        val alignments = listOf(
            com.example.frogreader.data.model.BlockAlign.CENTER to androidx.compose.ui.text.style.TextAlign.Center,
            com.example.frogreader.data.model.BlockAlign.LEFT to androidx.compose.ui.text.style.TextAlign.Left,
            com.example.frogreader.data.model.BlockAlign.RIGHT to androidx.compose.ui.text.style.TextAlign.Right,
            com.example.frogreader.data.model.BlockAlign.START to androidx.compose.ui.text.style.TextAlign.Start,
            com.example.frogreader.data.model.BlockAlign.END to androidx.compose.ui.text.style.TextAlign.End,
            com.example.frogreader.data.model.BlockAlign.JUSTIFY to androidx.compose.ui.text.style.TextAlign.Justify,
        )
        for (level in 1..6) for ((author, expected) in alignments) for (publisher in listOf(false, true)) {
            val heading = ContentElement.Heading("Heading", level, BlockStyle(align = author))
            assertEquals(expected, ReaderMetrics.textStyle(heading, settings.copy(bookStyles = publisher), 20f).textAlign)
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
            text = "Author-sized H1",
            level = 1,
            block = BlockStyle(fontScale = 1f),
        )

        val style = ReaderMetrics.textStyle(heading, settings, baseFontSize)

        assertEquals(baseFontSize, style.fontSize.value, 0.001f)
        assertTrue(style.fontSize.value < baseFontSize * ReaderMetrics.headingScale(1))
    }

    @Test
    fun `authored vertical margins replace native gaps only in publisher mode`() {
        val paragraph = ContentElement.Paragraph(
            androidx.compose.ui.text.AnnotatedString("Publisher spaced"),
            block = BlockStyle(
                spaceBeforeSpecified = true,
                spaceAfterSpecified = true,
            ),
        )

        val readerGaps = ReaderMetrics.verticalPaddings(
            paragraph,
            fontSize = 18f,
            bookStyles = false,
        )
        val publisherGaps = ReaderMetrics.verticalPaddings(
            paragraph,
            fontSize = 18f,
            bookStyles = true,
        )

        assertEquals(3f, readerGaps.first.value, 0.001f)
        assertEquals(3f, readerGaps.second.value, 0.001f)
        assertEquals(0f, publisherGaps.first.value, 0.001f)
        assertEquals(0f, publisherGaps.second.value, 0.001f)
    }

    @Test
    fun `authored margin presence is edge specific and legacy spacing remains intact`() {
        val oneAuthoredEdge = ContentElement.Heading(
            "One edge",
            level = 2,
            block = BlockStyle(spaceBeforeSpecified = true),
        )
        val authored = ReaderMetrics.verticalPaddings(
            oneAuthoredEdge,
            fontSize = 20f,
            bookStyles = true,
        )
        assertEquals(0f, authored.first.value, 0.001f)
        assertEquals(10f, authored.second.value, 0.001f)

        val legacySemanticSpacing = ContentElement.Paragraph(
            androidx.compose.ui.text.AnnotatedString("Legacy spacing"),
            block = BlockStyle(spaceBeforeEm = 2f),
        )
        val legacy = ReaderMetrics.verticalPaddings(
            legacySemanticSpacing,
            fontSize = 20f,
            bookStyles = true,
        )
        assertEquals(40f, legacy.first.value, 0.001f)
        assertEquals(3f, legacy.second.value, 0.001f)
    }

    @Test
    fun `standalone image authored margins replace native image gaps`() {
        val image = ContentElement.Image(
            path = "/nonexistent/image.png",
            spaceBeforeSpecified = true,
            spaceAfterSpecified = true,
        )

        val readerGaps = ReaderMetrics.verticalPaddings(
            image,
            fontSize = 18f,
            bookStyles = false,
        )
        val publisherGaps = ReaderMetrics.verticalPaddings(
            image,
            fontSize = 18f,
            bookStyles = true,
        )

        assertEquals(12f, readerGaps.first.value, 0.001f)
        assertEquals(12f, readerGaps.second.value, 0.001f)
        assertEquals(0f, publisherGaps.first.value, 0.001f)
        assertEquals(0f, publisherGaps.second.value, 0.001f)
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
