package com.example.frogreader.parser

import com.example.frogreader.data.parser.InlineTextBuilder
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.frogreader.data.model.ContentElement

class InlineWhitespaceFidelityTest {
    @Test fun `normal source whitespace collapses while typographic spaces retain their width`() {
        val builder = InlineTextBuilder()
        builder.text(" \n Mr.\u00a0")
        builder.text("  \u00a0Rowan\u202f&\u2003Bertha\u2060.\t\n Next")
        assertEquals("Mr.\u00a0 \u00a0Rowan\u202f&\u2003Bertha\u2060. Next", builder.build().text)
        assertEquals('t', builder.lastCharacter)
    }

    @Test fun `leading space trimming across inline boundaries preserves following NBSP`() {
        val builder = InlineTextBuilder()
        builder.text("Before ")
        builder.text(" \u00a0after")
        assertEquals("Before \u00a0after", builder.build().text)
    }

    @Test fun `EPUB name abbreviations retain their nonbreaking spaces across emphasis`() {
        val paragraph = HtmlMapper(resolveImage = { null }).map(Jsoup.parse(
            "<p><abbr>Mr.</abbr>&nbsp;<i>Rowan</i> and M.&thinsp;Dupont.</p>",
        ).body()).single() as ContentElement.Paragraph
        assertEquals("Mr.\u00a0Rowan and M.\u2009Dupont.", paragraph.text.text)
    }
}
