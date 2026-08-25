package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.parseChapterDocument
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperDtbookTest {

    @Test
    fun `DTBook chapter stays in XML mode with book as its reading root`() {
        val parsed = parseChapterDocument(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/">
              <book><bodymatter><level1><levelhd>Chapter</levelhd></level1></bodymatter></book>
            </dtbook>
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("Chapter", parsed?.selectFirst("book levelhd")?.text())
    }

    @Test
    fun `DTBook hierarchy lists verse images and tables retain reading semantics`() {
        val document = Jsoup.parse(
            """
            <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/">
              <book>
                <frontmatter><doctitle>Document title</doctitle></frontmatter>
                <bodymatter>
                  <level1 id="chapter">
                    <levelhd>First <em>chapter</em></levelhd>
                    <p>Lead <a href="#target">jump</a>.</p>
                    <level2><hd>Second level</hd>
                      <level><hd>Third level</hd><p id="target">Destination</p></level>
                    </level2>
                    <level6><level><hd>Clamped sixth level</hd></level></level6>
                    <list type="ol" enum="A" start="2"><li>Ordered
                      <list type="ol" enum="i" start="3"><li>Nested ordered</li></list>
                    </li></list>
                    <list type="pl"><li>Plain</li></list>
                    <poem><line>Verse one</line><line>Verse two</line></poem>
                    <imggroup><img src="figure.png" alt="Figure"/><caption>Caption</caption></imggroup>
                    <table><tr><th>Head</th><td>Cell</td></tr></table>
                  </level1>
                </bodymatter>
              </book>
            </dtbook>
            """.trimIndent(),
            "",
            Parser.xmlParser(),
        )
        val mapper = HtmlMapper(
            resolveImage = { if (it == "figure.png") "/tmp/figure.png" else null },
            resolveLink = { href -> if (href == "#target") "chapter.xml#target" else null },
        )

        val elements = mapper.map(document.selectFirst("book")!!)
        val headings = elements.filterIsInstance<ContentElement.Heading>()
        assertEquals(listOf(1, 1, 2, 3, 6), headings.map { it.level })
        assertEquals("First chapter", headings[1].styledText.text)
        assertTrue(
            headings[1].styledText.spanStyles.any {
                it.item.fontStyle == FontStyle.Italic &&
                    headings[1].styledText.substring(it.start, it.end) == "chapter"
            },
        )

        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        assertTrue(paragraphs.any { it.text.text.startsWith("B. Ordered") })
        val nested = paragraphs.single { it.text.text == "iii. Nested ordered" }
        assertEquals(1.2f, nested.block!!.indentStartEm, 0.01f)
        assertTrue(paragraphs.any { it.text.text == "Plain" })
        assertTrue(paragraphs.none { it.text.text.startsWith("• Plain") })
        assertEquals(
            listOf("Verse one", "Verse two"),
            paragraphs.filter { it.style == ParagraphStyle.QUOTE }.map { it.text.text },
        )
        assertTrue(elements.any { it is ContentElement.Image && it.path == "/tmp/figure.png" })
        assertTrue(elements.any { it is ContentElement.Table })
        assertEquals(setOf("chapter.xml#target"), mapper.linkTargets)
        assertTrue("target" in mapper.anchors)
    }
}
