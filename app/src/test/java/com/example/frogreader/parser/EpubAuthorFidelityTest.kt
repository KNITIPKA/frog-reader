package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.readerTypographyPlan
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions found in the six supplied Standard Ebooks publications. */
class EpubAuthorFidelityTest {
    private fun mapper(stylesheet: String) = HtmlMapper(
        resolveImage = { "/book/$it" },
        resolveLink = { "book.xhtml$it" },
        css = CssResolver(listOf(CssResolver.Sheet(stylesheet))),
    )

    private fun HtmlMapper.parse(html: String) = map(
        Jsoup.parse("<html xmlns:epub='http://www.idpf.org/2007/ops'><body>$html</body></html>").body(),
    )

    private fun AnnotatedString.hasStyleAt(offset: Int, predicate: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
        spanStyles.any { offset in it.start until it.end && predicate(it.item) }

    @Test
    fun `long Exiles drama tables retain dialogue geometry and author punctuation`() {
        val mapper = mapper(
            """
            @namespace epub "http://www.idpf.org/2007/ops";
            td[epub|type~="z3998:persona"] { width: 20%; text-align: right; font-variant: all-small-caps; }
            [epub|type~="z3998:stage-direction"] { font-style: italic; }
            [epub|type~="z3998:stage-direction"]::before { content: "("; font-style: normal; }
            [epub|type~="z3998:stage-direction"]::after { content: ")"; font-style: normal; }
            q { font-style: italic; }
            q::before, q::after { content: ""; }
            """.trimIndent(),
        )
        val rows = (0 until 405).joinToString("") { index ->
            "<tr><td epub:type='z3998:persona'>Richard</td>" +
                "<td id='line-$index'><i epub:type='z3998:stage-direction'>Firmly.</i> " +
                "There is no <q>must</q> now.</td></tr>"
        }
        val table = mapper.parse("<table><caption>Act</caption><tbody>$rows</tbody></table>")
            .filterIsInstance<ContentElement.Table>().single()

        assertEquals(405, table.rows.size)
        assertEquals(1, mapper.anchors["line-404"])
        val speaker = table.rows.last().cells.first()
        assertEquals(BlockAlign.RIGHT, speaker.align)
        assertEquals(0.2f, requireNotNull(speaker.publisherBox?.widthFrac), 0.001f)
        assertTrue(speaker.block?.fontFeatureSettings.orEmpty().contains("smcp"))
        assertTrue(speaker.block?.fontFeatureSettings.orEmpty().contains("c2sc"))
        val dialogue = table.rows.last().cells.last().text
        assertEquals("(Firmly.) There is no must now.", dialogue.text)
        assertTrue(dialogue.hasStyleAt(0) { it.fontStyle == FontStyle.Normal })
        assertTrue(dialogue.hasStyleAt(2) { it.fontStyle == FontStyle.Italic })
        assertTrue(dialogue.hasStyleAt(dialogue.text.indexOf("must")) { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `Tristram hidden repeated phrase preserves indentation without visible duplication`() {
        val paragraph = mapper(
            ".followon::before { content: '’Tis all '; visibility: hidden; }",
        ).parse("<p><span class='followon'>one to me.</span></p>")
            .filterIsInstance<ContentElement.Paragraph>().single()

        assertEquals("’Tis all one to me.", paragraph.text.text)
        val hidden = paragraph.text.getStringAnnotations(HIDDEN_TEXT_TAG, 0, paragraph.text.length).single()
        assertEquals("’Tis all ", paragraph.text.text.substring(hidden.start, hidden.end))
        assertEquals(0, hidden.start)
    }

    @Test
    fun `visible descendant can override inherited CSS visibility`() {
        val text = mapper(
            ".hidden { visibility: hidden; } .shown { visibility: visible; }",
        ).parse("<p class='hidden'>Silent <span class='shown'>visible</span> silent.</p>")
            .filterIsInstance<ContentElement.Paragraph>().single().text

        val shownStart = text.text.indexOf("visible")
        val hidden = text.getStringAnnotations(HIDDEN_TEXT_TAG, 0, text.length)
        assertTrue(hidden.isNotEmpty())
        assertFalse(hidden.any { shownStart in it.start until it.end })
    }

    @Test
    fun `Dracula consecutive diary entries retain the empty generated quarter-width separator`() {
        val mapper = mapper(
            """
            blockquote + blockquote::before {
                content: ""; display: block; border-top: 1px solid;
                height: 0; margin: 1.5em auto; width: 25%;
            }
            """.trimIndent(),
        )
        val elements = mapper.parse("<blockquote><p>First diary.</p></blockquote><blockquote><p>Next diary.</p></blockquote>")
        val spacerIndex = elements.indexOfFirst { it is ContentElement.Spacer }
        assertEquals(1, spacerIndex)
        assertEquals(0f, (elements[spacerIndex] as ContentElement.Spacer).heightEm, 0f)
        val box = mapper.publisherBoxes.single { it.style.borderTop != null }
        assertEquals(spacerIndex, box.startElement)
        assertEquals(spacerIndex + 1, box.endElementExclusive)
        assertEquals(0.25f, requireNotNull(box.style.widthFrac), 0.001f)
        assertEquals(1.5f, box.style.marginTopEm, 0.001f)
    }

    @Test
    fun `author styled horizontal rule draws its CSS border without a second native divider`() {
        val mapper = mapper("hr { border: none; border-top: 1px solid; height: 0; margin: 1.5em auto; width: 25%; }")
        val elements = mapper.parse("<p>Before</p><hr><p>After</p>")
        assertTrue(elements[1] is ContentElement.Spacer)
        assertTrue(elements.none { it == ContentElement.Divider })
        val rule = mapper.publisherBoxes.single { it.style.borderTop != null }
        assertEquals(0.25f, requireNotNull(rule.style.widthFrac), 0.001f)
        assertEquals(1.5f, rule.style.marginTopEm, 0.001f)
    }

    @Test
    fun `Tristram blank drawing page survives as a sized box with both page breaks`() {
        val mapper = mapper(".drawing { min-height: 10em; break-before: page; break-after: page; }")
        val elements = mapper.parse("<p>Paint her to your own mind.</p><div class='drawing'>\n </div><p>Was ever anything so sweet!</p>")
        val gap = elements[1] as ContentElement.Spacer
        assertEquals(10f, gap.heightEm, 0f)
        val box = mapper.publisherBoxes.single { (it.style.minHeightEm ?: 0f) > 0f }
        assertEquals(1, box.startElement)
        assertTrue(box.style.pageBreakBefore)
        assertTrue(box.style.pageBreakAfter)
    }

    @Test
    fun `case conversion and small capitals retain nested normal case overrides`() {
        val paragraph = mapper(
            "p { text-transform: uppercase; font-variant: all-small-caps; } " +
                "span { text-transform: none; font-variant: normal; }",
        ).parse("<p lang='tr'>izmir <span>Mixed case</span></p>")
            .filterIsInstance<ContentElement.Paragraph>().single()

        assertEquals("İZMİR Mixed case", paragraph.text.text)
        assertTrue(paragraph.block?.fontFeatureSettings.orEmpty().contains("smcp"))
        assertTrue(paragraph.text.hasStyleAt(paragraph.text.text.indexOf("Mixed")) { it.fontFeatureSettings == "" })
    }

    @Test
    fun `styled link and generated markers retain one navigable annotation`() {
        val text = mapper(
            "a { font-style: italic; font-weight: bold; } a::before { content: '['; } a::after { content: ']'; }",
        ).parse("<p>See <a href='#target'>passage</a>.</p>")
            .filterIsInstance<ContentElement.Paragraph>().single().text

        assertEquals("See [passage].", text.text)
        val link = text.getStringAnnotations(LINK_TAG, 0, text.length).single()
        assertEquals("[passage]", text.text.substring(link.start, link.end))
        assertTrue(text.hasStyleAt(link.start + 1) { it.fontStyle == FontStyle.Italic && it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `illustration author maxima retain separate width and viewport height constraints`() {
        val image = mapper("img { display: block; max-width: 80%; max-height: 50vh; }")
            .parse("<figure><img src='illustration.svg' alt='Diagram'></figure>")
            .filterIsInstance<ContentElement.Image>().single()
        assertEquals(0.8f, requireNotNull(image.maxWidthFrac), 0.001f)
        assertEquals(0.5f, requireNotNull(image.maxHeightViewportFrac), 0.001f)
        assertEquals("Diagram", image.altText)
    }

    @Test
    fun `publisher dark rules distinguish monochrome and realistic images`() {
        val images = mapper(
            """
            @media (prefers-color-scheme: dark) {
                img { filter: invert(100%); }
                img.realistic { filter: none; background: currentColor; }
            }
            """.trimIndent(),
        ).parse("<figure><img src='diagram.svg'></figure><figure><img class='realistic' src='marble.jpg'></figure>")
            .filterIsInstance<ContentElement.Image>()
        assertTrue(images[0].darkModeInvert)
        assertFalse(images[1].darkModeInvert)
        assertTrue(images[1].darkModeBackgroundCurrentColor)
    }

    @Test
    fun `non-content script inside paragraph cannot leak into author text`() {
        val text = mapper("").parse("<p>Before<script>notBookText()</script>after.</p>")
            .filterIsInstance<ContentElement.Paragraph>().single().text.text
        assertEquals("Beforeafter.", text)
    }

    @Test
    fun `Tristram distributed star rows preserve horizontal relationships`() {
        val elements = mapper(
            "p > span { display: flex; justify-content: space-between; text-indent: 0; }",
        ).parse("<p>Before.<span><span>*</span> <span>*</span> <span>*</span> <span>*</span></span>After.</p>")

        assertEquals("Before.", (elements.first() as ContentElement.Paragraph).text.text)
        assertEquals("After.", (elements.last() as ContentElement.Paragraph).text.text)
        val table = elements.filterIsInstance<ContentElement.Table>().single()
        assertEquals(PublisherRowLayout.SPACE_BETWEEN, table.publisherRowLayout)
        assertEquals(listOf("*", "*", "*", "*"), table.rows.single().cells.map { it.text.text })
        assertTrue(table.rows.single().cells.all { it.publisherBorderSpecified && it.publisherPaddingSpecified })
    }

    @Test
    fun `Tristram brace joins two alternatives without flattening the lines`() {
        val table = mapper(
            "p { display: flex; align-items: center; } " +
                "p > span:first-child > span { display: block; } " +
                "p > span:nth-child(2) { font-size: 2em; margin: 0 .25em; }",
        ).parse("<p><span><span>God bless</span><span>Deuce take</span></span><span>}</span><span>’em all</span></p>")
            .filterIsInstance<ContentElement.Table>().single()

        assertTrue(table.centerCellsVertically)
        assertEquals(PublisherRowLayout.NATURAL, table.publisherRowLayout)
        assertEquals("God bless\nDeuce take", table.rows.single().cells[0].text.text)
        assertEquals("}", table.rows.single().cells[1].text.text)
        assertEquals(2f, requireNotNull(table.rows.single().cells[1].block?.fontScale), 0.001f)
        assertEquals("’em all", table.rows.single().cells[2].text.text)
    }

    @Test
    fun `CSS table cells remain a native row when flex is unavailable`() {
        val table = mapper(
            ".row { display: table; width: 100%; } .row > span { display: table-cell; }",
        ).parse("<p><span class='row'><span id='first'>*</span><span>*</span><span>*</span></span></p>")
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(3, table.rows.single().cells.size)
        assertEquals(PublisherRowLayout.NATURAL, table.publisherRowLayout)
    }

    @Test
    fun `Looking Glass single-column arithmetic keeps authored dividing borders`() {
        val table = mapper("td { text-align: right; padding: .5em; } tr:last-child td { border-top: 1px solid; }")
            .parse("<table><tr><td>2</td></tr><tr><td>2</td></tr><tr><td>4</td></tr></table>")
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(3, table.rows.size)
        assertEquals(BlockAlign.RIGHT, table.rows.last().cells.single().align)
        assertTrue(table.rows.last().cells.single().publisherBox?.borderTop != null)
    }

    @Test
    fun `wrapping and column flex retain readable order without inventing table geometry`() {
        val mapper = mapper(".column { display: flex; flex-direction: column; } .wrap { display: flex; flex-wrap: wrap; }")
        val elements = mapper.parse("<div class='column'><p>First</p><p>Second</p></div><div class='wrap'><span>Third</span><span>Fourth</span></div>")
        assertTrue(elements.none { it is ContentElement.Table })
        assertEquals(listOf("First", "Second", "ThirdFourth"), elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text })
    }

    @Test
    fun `capitalize follows one word through nested inline styling`() {
        val text = mapper("p { text-transform: capitalize; }")
            .parse("<p><span>t</span>ext with <i>n</i>ested formatting</p>")
            .filterIsInstance<ContentElement.Paragraph>().single().text.text
        assertEquals("Text With Nested Formatting", text)
    }

    @Test
    fun `Tristram inline blackletter names retained embedded face with generic reset`() {
        val text = mapper(".blackletter { font-family: 'Standard Blackletter', serif; font-weight: bold; } .plain { font-family: serif; }")
            .parse("<p>Before <b class='blackletter'>Indenture <span class='plain'>normal</span></b> after.</p>")
            .filterIsInstance<ContentElement.Paragraph>().single().text
        val families = text.getStringAnnotations(INLINE_FONT_FAMILY_TAG, 0, text.length)
        val embedded = families.single { it.item == "standard blackletter" }
        assertEquals("Indenture ", text.text.substring(embedded.start, embedded.end))
        val reset = families.single { it.item == "serif" }
        assertEquals("normal", text.text.substring(reset.start, reset.end))
        assertEquals(2, families.size)
        val normalStart = text.text.indexOf("normal")
        assertFalse(normalStart in embedded.start until embedded.end)
        assertFalse(text.text.lastIndex in embedded.start until embedded.end)
        val style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
        val publisher = readerTypographyPlan(text, style, Density(2f), true,
            mapOf("standard blackletter" to FontFamily.Cursive))
        fun familyAt(offset: Int) = publisher.text.spanStyles.lastOrNull {
            offset in it.start until it.end && it.item.fontFamily != null
        }?.item?.fontFamily ?: publisher.style.fontFamily
        assertEquals(FontFamily.Cursive, familyAt(embedded.start))
        assertEquals(FontFamily.Serif, familyAt(normalStart))
        assertEquals(FontFamily.SansSerif, familyAt(text.text.lastIndex))
        val reader = readerTypographyPlan(text, style, Density(2f), false,
            mapOf("standard blackletter" to FontFamily.Cursive))
        assertTrue(reader.text.spanStyles.none { it.item.fontFamily != null })
        assertEquals(FontFamily.SansSerif, reader.style.fontFamily)
    }
}
