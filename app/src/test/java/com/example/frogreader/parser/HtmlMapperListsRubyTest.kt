package com.example.frogreader.parser

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperListsRubyTest {

    private fun mapped(html: String, css: String? = null): List<ContentElement> {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = css?.let { CssResolver(listOf(CssResolver.Sheet(it))) },
        )
        return mapper.map(Jsoup.parse(html).body())
    }

    private fun paragraphTexts(elements: List<ContentElement>): List<String> =
        elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }

    @Test
    fun `ordered list honors type and start attributes`() {
        val texts = paragraphTexts(
            mapped("<ol type='i' start='3'><li>alpha</li><li>beta</li></ol>"),
        )
        assertEquals(listOf("iii. alpha", "iv. beta"), texts)
    }

    @Test
    fun `li value attribute resets the counter`() {
        val texts = paragraphTexts(
            mapped("<ol><li>one</li><li value='10'>ten</li><li>eleven</li></ol>"),
        )
        assertEquals(listOf("1. one", "10. ten", "11. eleven"), texts)
    }

    @Test
    fun `css list-style-type drives markers`() {
        val texts = paragraphTexts(
            mapped(
                "<ol class='roman'><li>uno</li></ol><ul class='naked'><li>plain</li></ul>",
                css = "ol.roman { list-style-type: upper-roman; } ul.naked { list-style-type: none; }",
            ),
        )
        assertEquals(listOf("I. uno", "plain"), texts)
    }

    @Test
    fun `nested lists cycle bullets and indent by depth`() {
        val elements = mapped(
            "<ul><li>outer</li><li>x<ul><li>inner</li></ul></li></ul>",
        )
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals("• outer", paragraphs[0].text.text)
        val inner = paragraphs.single { it.text.text == "◦ inner" }
        assertEquals(1.2f, inner.block!!.indentStartEm, 0.01f)
        assertEquals(false, inner.block!!.firstLineIndent)
        assertEquals(0f, paragraphs[0].block!!.indentStartEm, 0.01f)
    }

    @Test
    fun `nested ordered lists restart numbering`() {
        val texts = paragraphTexts(
            mapped("<ol><li>a</li><li>b<ol><li>b1</li><li>b2</li></ol></li><li>c</li></ol>"),
        )
        assertEquals(listOf("1. a", "2. b", "1. b1", "2. b2", "3. c"), texts)
    }

    @Test
    fun `floated image travels with its paragraph instead of a block element`() {
        val mapper = HtmlMapper(
            resolveImage = { src -> "/resolved/$src" },
            css = CssResolver(
                listOf(CssResolver.Sheet("img.small { float: left; width: 30%; }")),
            ),
        )
        val elements = mapper.map(
            Jsoup.parse(
                "<p><img class='small' src='pic.png' alt='Small portrait'/>" +
                    "Текст, который обтекает картинку слева.</p>" +
                    "<p><img src='big.png'/>Обычная картинка.</p>",
            ).body(),
        )

        val floated = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Текст") }
        val float = floated.block!!.floatImage!!
        assertEquals("/resolved/pic.png", float.path)
        assertEquals(0.30f, float.widthFrac, 0.001f)
        assertTrue(float.left)
        assertEquals("Small portrait", float.altText)

        // Neither image is duplicated as a block. A normal image inside a
        // paragraph stays exactly at its source position in the text flow.
        val images = elements.filterIsInstance<ContentElement.Image>().map { it.path }
        assertTrue(images.isEmpty())
        val plain = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.contains("Обычная картинка") }
        val annotation = plain.text
            .getStringAnnotations(INLINE_IMAGE_TAG, 0, plain.text.length)
            .single()
        assertEquals("/resolved/big.png", annotation.item)
    }

    @Test
    fun `ruby reading becomes a small superscript and rp is dropped`() {
        val elements = mapped(
            "<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby> дальше.</p>",
        )
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("漢字かんじ дальше.", paragraph.text.text)
        assertTrue("no ( ) fallback", !paragraph.text.text.contains("("))

        val small = paragraph.text.spanStyles.single {
            it.item.fontSize == TextUnit(0.6f, TextUnitType.Em)
        }
        assertEquals("かんじ", paragraph.text.text.substring(small.start, small.end))
    }
}
