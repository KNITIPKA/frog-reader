package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.ReaderMetrics
import com.example.frogreader.ui.reader.EpubPoemFixtures
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.ui.reader.planPublisherChapter
import com.example.frogreader.ui.reader.publisherBoxGeometry
import com.example.frogreader.ui.reader.withPublisherHangingIndent
import androidx.compose.ui.unit.Density
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source fragments from the two Alice EPUBs that exposed lost CSS poem lines. */
class EpubPoemLayoutTest {
    private fun map(number: Int): Pair<List<ContentElement.Paragraph>, List<PublisherBoxSpan>> {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(listOf(CssResolver.Sheet(EpubPoemFixtures.css(number)))),
        )
        val elements = mapper.map(Jsoup.parse(EpubPoemFixtures.html(number)).body())
        return elements.filterIsInstance<ContentElement.Paragraph>() to mapper.publisherBoxes
    }

    @Test
    fun `standard ebooks poem keeps its separate lines and stepped padding`() {
        val (paragraphs, boxes) = map(1)
        assertEquals("Before the poem.", paragraphs.first().text.text.trim())
        assertEquals("After the poem.", paragraphs.last().text.text.trim())
        val lines = paragraphs.drop(1).dropLast(1)
        val stanza = boxes.single { it.style.shrinkToFit }
        assertEquals(PublisherBoxAlign.CENTER, stanza.style.horizontalAlign)
        assertEquals(51, lines.size)
        assertEquals(listOf("“Fury said to", "a mouse, That", "he met", "in the"),
            lines.take(4).map { it.text.text.trim() })
        lines.forEach { line ->
            assertEquals(BlockAlign.START, line.block?.align)
            assertFalse(line.text.text.contains('\n'))
            assertEquals(-1f, requireNotNull(line.block?.firstLineIndentEm), 0.001f)
            for (publisher in listOf(false, true)) {
                val style = ReaderMetrics.textStyle(line, ReaderSettings(bookStyles = publisher), 20f)
                assertEquals(FontStyle.Normal, style.fontStyle)
                assertEquals(-20f, style.textIndent!!.firstLine.value, 0.001f)
                assertEquals(0f, ReaderMetrics.verticalPaddings(line, 20f, publisher).first.value, 0.001f)
                assertEquals(0f, ReaderMetrics.verticalPaddings(line, 20f, publisher).second.value, 0.001f)
            }
        }
        val paddings = boxes.filter { it.endElementExclusive == it.startElement + 1 }
            .filter { it.style.paddingLeftEm > 0f }.associate { it.startElement to it.style.paddingLeftEm }
        assertEquals(listOf(1f, 2f, 3f, 4f), (1..4).map { paddings[it] })
        assertTrue(lines[8].text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `gutenberg poem keeps nested line wrappers indents and decreasing type sizes`() {
        val (paragraphs, boxes) = map(2)
        assertEquals("Before the poem.", paragraphs.first().text.text.trim())
        assertEquals("After the poem.", paragraphs.last().text.text.trim())
        val lines = paragraphs.drop(1).dropLast(1)
        assertEquals(46, lines.size)
        val group = boxes.single { it.style.fitTextFlow }
        assertEquals(1, group.startElement)
        assertEquals(47, group.endElementExclusive)
        assertEquals(listOf("\"Fury said to", "a mouse, That", "he met in the", "house, 'Let"),
            lines.take(4).map { it.text.text.trim() })
        assertEquals("death.'\"", lines.last().text.text.trim())
        assertEquals(0.6f, requireNotNull(lines.last().block?.fontScale), 0.001f)
        assertEquals(-1.8f, requireNotNull(lines.last().block?.firstLineIndentEm), 0.001f)
        val margins = boxes.filter { it.endElementExclusive == it.startElement + 1 }
            .filter { it.style.paddingLeftEm > 0f }.associate { it.startElement to it.style.marginLeftEm }
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f), (1..6).map { margins[it] })
        assertTrue(lines[5].text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        lines.forEach { assertFalse(it.text.text.contains('\n')) }
    }

    @Test
    fun `CSS block children preserve surrounding text links and exact anchors`() {
        val mapper = HtmlMapper(resolveImage = { null })
        val elements = mapper.map(Jsoup.parse("""
            <p>Before <a href="https://example.com"><em>lead
            <span id="line" style="display:block;padding-left:2em;text-indent:-1em">line <b>bold</b></span>
            tail</em></a> after</p>
        """).body()).filterIsInstance<ContentElement.Paragraph>()
        assertEquals(listOf("Before", "lead", "line bold", "tail", "after"),
            elements.map { it.text.text.trim() })
        assertEquals(2, mapper.anchors["line"])
        elements.slice(1..3).forEach { paragraph ->
            assertEquals("https://example.com",
                paragraph.text.getStringAnnotations(EXTERNAL_LINK_TAG, 0, paragraph.text.length).single().item)
            assertEquals(true, paragraph.block?.italic)
        }
        assertTrue(elements[2].text.spanStyles.any { it.item.fontWeight?.weight == 700 })
    }

    @Test
    fun `EPUB namespace selectors use namespace URI rather than the document prefix`() {
        val document = Jsoup.parse("""
            <html xmlns:publication="http://www.idpf.org/2007/ops"><body>
            <blockquote publication:type="z3998:poem"><span lang="en-GB">line</span></blockquote>
            </body></html>
        """)
        val resolver = CssResolver(listOf(CssResolver.Sheet("""
            @namespace epub "http://www.idpf.org/2007/ops";
            [epub|type~="z3998:poem"] span { display: block; text-indent: calc(-2em + 0.5em); }
            [lang|=en] { font-style: italic; }
        """)))
        val style = resolver.computed(document.selectFirst("span")!!)
        assertEquals("block", style.display)
        assertEquals(-1.5f, requireNotNull(style.textIndentEm), 0.001f)
        assertEquals(true, style.italic)
    }

    @Test
    fun `large verse steps are retained with and without publisher decorations`() {
        val (elements, boxes) = map(1)
        val plan = planPublisherChapter(elements, boxes)
        // "For" is seven em of padding minus its one-em first-line outdent.
        val index = elements.indexOfFirst { it.text.text.trim() == "For" }
        val lineBox = plan.boxesByElement[index].last()
        for (publisher in listOf(false, true)) {
            val geometry = publisherBoxGeometry(
                boxes = listOf(lineBox), columnWidthPx = 200, fontSizePx = 20f,
                partStartsElement = true, partEndsElement = true, enabled = publisher,
            )
            assertEquals(120, geometry.contentLeftPx)
            assertEquals(80, geometry.contentWidthPx)
            assertEquals(20, geometry.hangingIndentPx)
            val style = ReaderMetrics.textStyle(elements[index], ReaderSettings(bookStyles = publisher), 20f)
                .withPublisherHangingIndent(geometry, Density(1f))
            assertEquals(0f, style.textIndent!!.firstLine.value, 0.001f)
            assertEquals(20f, style.textIndent!!.restLine.value, 0.001f)
            val continued = style.withPublisherHangingIndent(geometry, Density(1f), isParagraphStart = false)
            assertEquals(20f, continued.textIndent!!.firstLine.value, 0.001f)
        }
        val stanza = plan.boxesByElement[index].first { it.style.shrinkToFit }
        assertEquals(51, requireNotNull(stanza.inlineContent).leaves.size)
        assertTrue(plan.boxesByElement[index + 1].any { it.inlineContent === stanza.inlineContent })
        assertFalse(plan.boxesByElement.first().any { it.style.fitTextFlow })
        assertFalse(plan.boxesByElement.last().any { it.style.fitTextFlow })
    }
}
