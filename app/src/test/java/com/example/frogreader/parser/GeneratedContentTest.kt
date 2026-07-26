package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.svgAspectRatio
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedContentTest {

    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))

    private fun mapped(html: String, css: String? = null): List<ContentElement> {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = css?.let { resolver(it) },
        )
        return mapper.map(Jsoup.parse(html).body())
    }

    @Test
    fun `static string content is captured with styles`() {
        val r = resolver("p.note::before { content: \"NB: \"; font-style: italic; font-size: 0.8em; }")
        val element = Jsoup.parse("<p class='note' id='p'>text</p>").selectFirst("#p")!!

        val run = r.generated(element, after = false)!!
        assertEquals("NB: ", run.text)
        assertEquals(true, run.italic)
        assertEquals(0.8f, run.scale, 0.01f)
        assertNull(r.generated(element, after = true))
    }

    @Test
    fun `escapes and concatenation work, dynamic content degrades`() {
        val r = resolver(
            """
            p.dash::before { content: "\2014 "; }
            p.two::after { content: "«" "»"; }
            p.counter::before { content: counter(ch) ". "; }
            p.attr::before { content: attr(title); }
            p.none::before { content: none; }
            """.trimIndent(),
        )
        val doc = Jsoup.parse(
            "<p class='dash' id='a'>x</p><p class='two' id='b'>x</p>" +
                "<p class='counter' id='c'>x</p><p class='attr' id='d'>x</p>" +
                "<p class='none' id='e'>x</p>",
        )
        // Per CSS, the single space after a hex escape is its delimiter and
        // is consumed — "\2014 " yields the bare em-dash, like in browsers.
        assertEquals("—", r.generated(doc.selectFirst("#a")!!, false)!!.text)
        assertEquals("«»", r.generated(doc.selectFirst("#b")!!, true)!!.text)
        assertNull(r.generated(doc.selectFirst("#c")!!, false))
        assertNull(r.generated(doc.selectFirst("#d")!!, false))
        assertNull(r.generated(doc.selectFirst("#e")!!, false))
    }

    @Test
    fun `pseudo element rules do not leak into normal computed styles`() {
        val r = resolver("p::before { content: \"x\"; font-style: italic; }")
        val element = Jsoup.parse("<p id='p'>text</p>").selectFirst("#p")!!
        assertNull(r.computed(element).italic)
    }

    @Test
    fun `paragraph gets before and after runs baked into its text`() {
        val elements = mapped(
            "<p class='q'>Мысль</p>",
            css = "p.q::before { content: \"— \"; } p.q::after { content: \" —\"; }",
        )
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("— Мысль —", paragraph.text.text)
    }

    @Test
    fun `li before marker replaces a none-style list marker`() {
        val elements = mapped(
            "<ul class='fancy'><li>первый</li><li>второй</li></ul>",
            css = "ul.fancy { list-style-type: none; } ul.fancy li::before { content: \"→ \"; }",
        )
        val texts = elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        assertEquals(listOf("→ первый", "→ второй"), texts)
    }

    @Test
    fun `div separator before content becomes its own centered paragraph`() {
        val elements = mapped(
            "<div class='sep'></div><p>Дальше текст.</p>",
            css = ".sep { text-align: center; } .sep::before { content: \"* * *\"; }",
        )
        val separator = elements.filterIsInstance<ContentElement.Paragraph>().first()
        assertEquals("* * *", separator.text.text)
        assertEquals(BlockAlign.CENTER, separator.block?.align)
    }

    @Test
    fun `inline span after run is styled`() {
        val elements = mapped(
            "<p>Смотри<span class='ref'>сноску</span> тут.</p>",
            css = "span.ref::after { content: \"*\"; font-style: italic; }",
        )
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("Смотрисноску* тут.", paragraph.text.text)
        val italic = paragraph.text.spanStyles
            .single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("*", paragraph.text.text.substring(italic.start, italic.end))
    }

    @Test
    fun `heading before content is prepended`() {
        val elements = mapped(
            "<h2 class='ch'>Первая</h2>",
            css = "h2.ch::before { content: \"Глава: \"; }",
        )
        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        assertEquals("Глава: Первая", heading.text)
    }

    @Test
    fun `scale run changes font size`() {
        val elements = mapped(
            "<p class='big'>текст</p>",
            css = "p.big::before { content: \"◆ \"; font-size: 1.5em; }",
        )
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        val sized = paragraph.text.spanStyles
            .single { it.item.fontSize == TextUnit(1.5f, TextUnitType.Em) }
        assertTrue(paragraph.text.text.substring(sized.start, sized.end).startsWith("◆"))
    }

    // ---------------------------------------------------------------- svg

    @Test
    fun `inline vector svg becomes an image via the callback`() {
        var captured: String? = null
        val mapper = HtmlMapper(
            resolveImage = { null },
            resolveInlineSvg = { markup ->
                captured = markup
                "/fake/path.svg"
            },
        )
        val elements = mapper.map(
            Jsoup.parse(
                "<p>До.</p><svg viewBox=\"0 0 100 50\"><path d=\"M0 0 L100 50\"/></svg><p>После.</p>",
            ).body(),
        )
        val image = elements.filterIsInstance<ContentElement.Image>().single()
        assertEquals("/fake/path.svg", image.path)
        assertTrue(captured!!.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(captured!!.contains("path"))
    }

    @Test
    fun `svg wrapped raster image still uses the raster`() {
        val mapper = HtmlMapper(
            resolveImage = { src -> "/raster/$src" },
            resolveInlineSvg = { "/should/not/happen.svg" },
        )
        val elements = mapper.map(
            Jsoup.parse(
                "<svg viewBox=\"0 0 10 10\"><image xlink:href=\"cover.jpg\"/></svg>",
            ).body(),
        )
        val image = elements.filterIsInstance<ContentElement.Image>().single()
        assertEquals("/raster/cover.jpg", image.path)
    }

    @Test
    fun `svg aspect ratio from attributes and viewbox`() {
        assertEquals(0.5f, svgAspectRatio("<svg width=\"100\" height=\"50\">")!!, 0.001f)
        assertEquals(0.5f, svgAspectRatio("<svg width=\"100px\" height=\"50px\">")!!, 0.001f)
        assertEquals(2f, svgAspectRatio("<svg viewBox=\"0 0 50 100\">")!!, 0.001f)
        // Percentages fall through to the viewBox.
        assertEquals(1.5f, svgAspectRatio("<svg width=\"100%\" viewBox=\"0 0 10 15\">")!!, 0.001f)
        assertNull(svgAspectRatio("<svg>"))
        assertNull(svgAspectRatio("<svg viewBox=\"0 0 0 10\">"))
    }
}
