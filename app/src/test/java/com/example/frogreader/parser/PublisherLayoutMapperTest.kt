package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.testfixtures.PublisherLayoutFixture
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherLayoutMapperTest {

    private data class Result(
        val elements: List<ContentElement>,
        val boxes: List<PublisherBoxSpan>,
    )

    private fun mapped(): Result {
        val mapper = HtmlMapper(
            resolveImage = { source ->
                source.takeIf { it == "figure.png" }?.let { "/tmp/$it" }
            },
            css = CssResolver(listOf(CssResolver.Sheet(PublisherLayoutFixture.css))),
            publisherBoxIdPrefix = "fixture",
        )
        val elements = mapper.map(Jsoup.parse(PublisherLayoutFixture.html).body())
        return Result(elements, mapper.publisherBoxes.toList())
    }

    @Test
    fun `compound publisher layout retains flat semantic leaf order`() {
        val elements = mapped().elements

        assertEquals(8, elements.size)
        assertEquals(
            "Before the panel.",
            (elements[0] as ContentElement.Paragraph).text.text,
        )
        assertEquals("Worked example", (elements[1] as ContentElement.Heading).text)
        assertEquals("/tmp/figure.png", (elements[2] as ContentElement.Image).path)
        assertEquals(
            "Figure caption.",
            (elements[3] as ContentElement.Paragraph).text.text,
        )
        assertEquals(
            "First paragraph beside the floated figure.",
            (elements[4] as ContentElement.Paragraph).text.text,
        )
        assertEquals(
            "Second paragraph remains in the same decorated box.",
            (elements[5] as ContentElement.Paragraph).text.text,
        )
        val table = elements[6] as ContentElement.Table
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Term", "Meaning"), table.rows.first().cells.map { it.text.text })
        assertEquals(
            "After the panel.",
            (elements[7] as ContentElement.Paragraph).text.text,
        )
    }

    @Test
    fun `mapper emits nested spans for container heading float image and table`() {
        val boxes = mapped().boxes

        val panel = boxes.single {
            it.style.backgroundColorArgb == PublisherLayoutFixture.panelBackgroundArgb
        }
        assertEquals(1, panel.startElement)
        assertEquals(7, panel.endElementExclusive)
        assertNull(panel.parentId)
        assertTrue(panel.drawStart)
        assertTrue(panel.drawEnd)
        assertEquals(0.5f, panel.style.paddingTopEm, 0.001f)
        assertEquals(0.75f, panel.style.paddingRightEm, 0.001f)
        assertEquals(0.6f, panel.style.paddingBottomEm, 0.001f)
        assertEquals(0.75f, panel.style.paddingLeftEm, 0.001f)
        listOf(
            panel.style.borderTop,
            panel.style.borderRight,
            panel.style.borderBottom,
            panel.style.borderLeft,
        ).forEach { border ->
            requireNotNull(border)
            assertEquals(0.125f, border.widthEm, 0.001f)
            assertEquals(PublisherLayoutFixture.panelBorderArgb, border.colorArgb)
            assertEquals(PublisherBorderStyle.SOLID, border.style)
        }

        val heading = boxes.single {
            it.style.borderBottom?.colorArgb == PublisherLayoutFixture.headingBorderArgb
        }
        assertEquals(1, heading.startElement)
        assertEquals(2, heading.endElementExclusive)
        assertEquals(panel.id, heading.parentId)
        assertNull(heading.style.borderTop)
        assertNull(heading.style.borderRight)
        assertNull(heading.style.borderLeft)

        val floated = boxes.single { it.floatSide == PublisherFloatSide.LEFT }
        assertEquals(2, floated.startElement)
        assertEquals(4, floated.endElementExclusive)
        assertEquals(panel.id, floated.parentId)
        assertEquals(0.38f, floated.style.widthFrac ?: 0f, 0.001f)

        val image = boxes.single {
            it.startElement == 2 && it.endElementExclusive == 3 &&
                it.style.widthFrac == 1f
        }
        assertEquals(floated.id, image.parentId)

        val table = boxes.single { it.style.widthFrac == 0.72f }
        assertEquals(6, table.startElement)
        assertEquals(7, table.endElementExclusive)
        assertEquals(panel.id, table.parentId)
    }

    @Test
    fun `ancestor panel decoration is not smeared onto its prose leaves`() {
        val result = mapped()
        val first = result.elements[4] as ContentElement.Paragraph
        val second = result.elements[5] as ContentElement.Paragraph

        assertNull(first.block?.backgroundColorArgb)
        assertNull(second.block?.backgroundColorArgb)
        assertEquals(PublisherLayoutFixture.FONT_FAMILY, first.block?.fontFamily)
        assertEquals(PublisherLayoutFixture.FONT_FAMILY, second.block?.fontFamily)
    }

    @Test
    fun `styled structural div with direct text still creates a semantic paragraph`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(
                listOf(
                    CssResolver.Sheet(
                        ".callout { padding: 0.5em; background: #eeeeee; " +
                            "border: 1px solid #333333; }",
                    ),
                ),
            ),
            publisherBoxIdPrefix = "direct-text",
        )

        val elements = mapper.map(
            Jsoup.parse("<div class='callout'>Direct <strong>publisher text</strong>.</div>")
                .body(),
        )

        val paragraph = elements.single() as ContentElement.Paragraph
        assertEquals("Direct publisher text.", paragraph.text.text)
        val block = requireNotNull(paragraph.block)
        assertEquals(false, block.firstLineIndent)
        assertTrue(block.spaceBeforeSpecified)
        assertTrue(block.spaceAfterSpecified)
        val box = mapper.publisherBoxes.single()
        assertEquals(0, box.startElement)
        assertEquals(1, box.endElementExclusive)
        assertEquals(0xffeeeeee.toInt(), box.style.backgroundColorArgb)
    }

    @Test
    fun `direct structural text keeps an authored first line indent`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(listOf(CssResolver.Sheet(".lead { text-indent: 2em; }"))),
        )

        val paragraph = mapper.map(
            Jsoup.parse("<div class='lead'>Intentionally indented.</div>").body(),
        ).single() as ContentElement.Paragraph

        assertEquals(true, paragraph.block?.firstLineIndent)
        assertEquals(2f, requireNotNull(paragraph.block?.firstLineIndentEm), 0.001f)
    }

    @Test
    fun `next sibling box never captures preceding loose inline text`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(
                listOf(CssResolver.Sheet("#boxed { background: #eeeeee; padding: .5em; }")),
            ),
            publisherBoxIdPrefix = "sibling-boundary",
        )

        val elements = mapper.map(
            Jsoup.parse("<div>Loose text.<p id='boxed'>Boxed text.</p></div>").body(),
        )

        assertEquals(
            listOf("Loose text.", "Boxed text."),
            elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text },
        )
        val box = mapper.publisherBoxes.single()
        assertEquals(1, box.startElement)
        assertEquals(2, box.endElementExclusive)
    }

    @Test
    fun `own zero margins remain distinguishable from absent margins`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(
                listOf(CssResolver.Sheet("#reset { margin: 0; }")),
            ),
            publisherBoxIdPrefix = "margin-presence",
        )

        val elements = mapper.map(
            Jsoup.parse("<p id='reset'>Reset.</p><p>Default.</p>").body(),
        ).filterIsInstance<ContentElement.Paragraph>()

        assertTrue(requireNotNull(elements[0].block).spaceBeforeSpecified)
        assertTrue(requireNotNull(elements[0].block).spaceAfterSpecified)
        assertFalse(elements[1].block?.spaceBeforeSpecified ?: false)
        assertFalse(elements[1].block?.spaceAfterSpecified ?: false)
        // Explicit zero needs no geometry span; its semantic presence bit is
        // sufficient to replace the reader's built-in paragraph gap.
        assertTrue(mapper.publisherBoxes.isEmpty())
    }

    @Test
    fun `standalone image retains explicit zero margin presence`() {
        val mapper = HtmlMapper(
            resolveImage = { source -> "/tmp/$source" },
            css = CssResolver(
                listOf(CssResolver.Sheet("#reset { margin: 0; }")),
            ),
            publisherBoxIdPrefix = "image-margin-presence",
        )

        val images = mapper.map(
            Jsoup.parse("<img id='reset' src='reset.png'><img src='default.png'>").body(),
        ).filterIsInstance<ContentElement.Image>()

        assertTrue(images[0].spaceBeforeSpecified)
        assertTrue(images[0].spaceAfterSpecified)
        assertFalse(images[1].spaceBeforeSpecified)
        assertFalse(images[1].spaceAfterSpecified)
        assertTrue(mapper.publisherBoxes.isEmpty())
    }

    @Test
    fun `empty clear element becomes a zero length flow marker`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(listOf(CssResolver.Sheet(".clear-flow { clear: both; }"))),
            publisherBoxIdPrefix = "clear",
        )

        val elements = mapper.map(
            Jsoup.parse(
                "<p>Before.</p><div class='clear-flow'></div><p>After.</p>",
            ).body(),
        )

        assertEquals(
            listOf("Before.", "After."),
            elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text },
        )
        val marker = mapper.publisherBoxes.single()
        assertEquals(1, marker.startElement)
        assertEquals(1, marker.endElementExclusive)
        assertEquals(PublisherClear.BOTH, marker.clear)
        assertTrue(marker.style.isDefault)
        assertNull(marker.floatSide)
    }

    @Test
    fun `rich note publisher boxes are rebased to note local leaf coordinates`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(
                listOf(
                    CssResolver.Sheet(
                        ".note-panel { padding: 0.4em; background-color: #fff4cc; " +
                            "border: 0.1em solid #aa7700; }",
                    ),
                ),
            ),
            publisherBoxIdPrefix = "notes",
        )

        mapper.map(
            Jsoup.parse(
                """
                <p>Before note.</p>
                <aside id="note-1" epub:type="footnote">
                  <div class="note-panel">
                    <p>First note paragraph.</p>
                    <p>Second note paragraph.</p>
                  </div>
                </aside>
                <p>After note.</p>
                """.trimIndent(),
            ).body(),
        )

        val note = mapper.noteDocuments.getValue("note-1")
        assertEquals(
            listOf("First note paragraph.", "Second note paragraph."),
            note.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text },
        )
        val localBox = note.publisherBoxes.single()
        assertEquals(0, localBox.startElement)
        assertEquals(2, localBox.endElementExclusive)
        assertEquals(0xfffff4cc.toInt(), localBox.style.backgroundColorArgb)
    }

    @Test
    fun `explicit zero cell padding and no border retain authored presence`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(
                listOf(CssResolver.Sheet("td.reset { padding: 0; border: none; }")),
            ),
            publisherBoxIdPrefix = "cell-reset",
        )

        val table = mapper.map(
            Jsoup.parse(
                "<table><tr><td class='reset'>Reset</td><td>Plain</td></tr></table>",
            ).body(),
        ).single() as ContentElement.Table

        val reset = table.rows.single().cells[0]
        assertTrue(reset.publisherPaddingSpecified)
        assertTrue(reset.publisherBorderSpecified)
        assertTrue(requireNotNull(reset.publisherBox).isDefault)

        val plain = table.rows.single().cells[1]
        assertFalse(plain.publisherPaddingSpecified)
        assertFalse(plain.publisherBorderSpecified)
        assertNull(plain.publisherBox)
    }
}
