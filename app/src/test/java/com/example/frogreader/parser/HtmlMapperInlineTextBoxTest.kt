package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.decodeInlineTextBoxSpec
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlMapperInlineTextBoxTest {
    private fun paragraphs(css: String, html: String): List<ContentElement.Paragraph> =
        HtmlMapper(resolveImage = { null }, css = CssResolver(listOf(CssResolver.Sheet(css))))
            .map(Jsoup.parse(html).body()).filterIsInstance<ContentElement.Paragraph>()

    @Test fun `authored alphabet initials and stars retain source text with exact widths`() {
        val paragraphs = paragraphs(
            "#alphabet p > span {display:inline-block;width:1.2em;} " +
                "#stars p > span {display:inline-block;width:3em;text-align:center;}",
            "<section id='alphabet'><p><span>A</span>gitating</p></section>" +
                "<section id='stars'><p>Prose <span>*</span> <span>*.</span></p></section>",
        )
        assertEquals(listOf("Agitating", "Prose * *."), paragraphs.map { it.text.text })
        val initial = paragraphs[0].text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, paragraphs[0].text.length).single()
        assertEquals(0, initial.start)
        assertEquals(1, initial.end)
        assertEquals(1.2f, decodeInlineTextBoxSpec(initial.item)!!.widthEm, 0.001f)
        val stars = paragraphs[1].text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, paragraphs[1].text.length)
        assertEquals(listOf("*", "*."), stars.map { paragraphs[1].text.text.substring(it.start, it.end) })
        assertTrue(stars.all { decodeInlineTextBoxSpec(it.item)!!.align == "center" })
        assertTrue(stars.all { decodeInlineTextBoxSpec(it.item)!!.widthEm == 3f })
    }

    @Test fun `inline box width accounts for nested inherited font size only once`() {
        val paragraph = paragraphs(
            "p {font-size:150%;} .large {font-size:200%;} .initial {display:inline-block;width:1.2em;font-style:italic;}",
            "<p><span class='large'><span class='initial'>A</span>fter</span></p>",
        ).single()
        assertEquals("After", paragraph.text.text)
        val mark = paragraph.text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, paragraph.text.length).single()
        assertEquals(2.4f, decodeInlineTextBoxSpec(mark.item)!!.widthEm, 0.001f)
        assertTrue(paragraph.text.spanStyles.isNotEmpty())
        assertEquals(1.5f, paragraph.block!!.fontScale!!, 0.001f)
    }

    @Test fun `complex or decorated inline boxes retain readable ordinary rich text`() {
        val paragraphs = paragraphs(
            "span {display:inline-block;width:3em;} .border {border:1px solid;} .generated::before {content:'[';}",
            "<p><span>Long text</span> <span><em>A</em></span> <span class='border'>B</span> <span class='generated'>C</span></p>",
        )
        val text = paragraphs.single().text
        assertEquals("Long text A B [C", text.text)
        assertTrue(text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, text.length).isEmpty())
    }
}
