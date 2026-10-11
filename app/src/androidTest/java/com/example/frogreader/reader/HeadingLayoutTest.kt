package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.ui.reader.BidiLayoutText
import com.example.frogreader.ui.reader.ReaderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android text layout: JVM model assertions cannot detect extra Compose paragraphs. */
@RunWith(AndroidJUnit4::class)
class HeadingLayoutTest {
    private fun title(paragraphs: String): ContentElement.Heading {
        val xml = """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Layout</book-title></title-info></description>
            <body><section><title style="text-align:right">$paragraphs</title></section></body>
            </FictionBook>"""
        return Fb2Parser.parseContent(
            { xml.byteInputStream() }, InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        ).chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>().single()
    }

    private fun measure(heading: ContentElement.Heading, publisher: Boolean, fontSize: Float = 20f): TextLayoutResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: TextLayoutResult? = null
        instrumentation.runOnMainSync {
            val measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(instrumentation.targetContext),
                defaultDensity = Density(1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
            result = measurer.measure(
                text = BidiLayoutText.of(heading.styledText).display,
                style = ReaderMetrics.textStyle(heading, ReaderSettings(font = ReaderFont.SERIF, bookStyles = publisher), fontSize),
                constraints = Constraints(minWidth = 360, maxWidth = 360),
            )
        }
        return requireNotNull(result)
    }

    @Test
    fun titleParagraphsKeepTheirAlignmentWithoutExtraLines() {
        val heading = title("""<p style="text-align:left">Left</p><p>Right</p><p style="text-align:center">Center</p>""")
        assertEquals("Left\nRight\nCenter", heading.text)
        for (publisher in listOf(false, true)) {
            val layout = measure(heading, publisher)
            assertEquals(3, layout.lineCount)
            assertEquals(0f, layout.getLineLeft(0), 1f)
            assertEquals(360f, layout.getLineRight(1), 1f)
            assertTrue(layout.getLineLeft(2) > 0f)
            assertEquals(360f, layout.getLineLeft(2) + layout.getLineRight(2), 1f)
        }
    }

    @Test
    fun authoredEmptyTitleLineIsPreservedAtDifferentFontSizes() {
        val heading = title("<p>One</p><empty-line/><p>Two</p><p>Three</p>")
        assertEquals("One\n\nTwo\nThree", heading.text)
        for (publisher in listOf(false, true)) for (fontSize in listOf(16f, 32f)) {
            assertEquals(4, measure(heading, publisher, fontSize).lineCount)
        }
    }

    @Test
    fun uniformlyAlignedTitlesKeepTheOriginalLineMetrics() {
        val heading = title("<p>One</p><empty-line/><p>Two</p><p>Three</p>")
        val plain = heading.copy(styledText = AnnotatedString(heading.text))
        for (publisher in listOf(false, true)) for (fontSize in listOf(16f, 32f)) {
            val aligned = measure(heading, publisher, fontSize)
            val original = measure(plain, publisher, fontSize)
            assertEquals(original.size.height, aligned.size.height)
            for (line in 0 until original.lineCount) {
                assertEquals(original.getLineBaseline(line), aligned.getLineBaseline(line), 0.01f)
            }
        }
    }

    @Test
    fun bidiControlsStayInsideTheStyledTitleParagraph() {
        val text = AnnotatedString.Builder("One\nאב").apply {
            addStyle(ParagraphStyle(textAlign = TextAlign.Left), 0, 3)
            addStyle(ParagraphStyle(textAlign = TextAlign.Right), 4, 6)
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 4, 6)
        }.toAnnotatedString()
        val heading = ContentElement.Heading(text, level = 2)
        val layout = measure(heading, publisher = false)
        assertEquals(2, layout.lineCount)
        assertEquals(360f, layout.getLineRight(1), 1f)
    }
}
