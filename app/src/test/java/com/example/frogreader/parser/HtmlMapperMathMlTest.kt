package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperMathMlTest {

    private fun mapped(
        source: String,
        resolveLink: (String) -> String? = { null },
    ): Pair<List<ContentElement>, HtmlMapper> {
        val document = Jsoup.parse(
            "<body xmlns:m='http://www.w3.org/1998/Math/MathML'>$source</body>",
            "",
            Parser.xmlParser(),
        )
        val mapper = HtmlMapper(resolveImage = { null }, resolveLink = resolveLink)
        return mapper.map(document.selectFirst("body")!!) to mapper
    }

    private fun onlyParagraph(source: String): ContentElement.Paragraph =
        mapped("<p>$source</p>").first.filterIsInstance<ContentElement.Paragraph>().single()

    @Test
    fun `inline scripts fraction root and operator relationships stay readable`() {
        val paragraph = onlyParagraph(
            """
            Formula:
            <math>
              <mi>f</mi><mo>(</mo><mi>x</mi><mo>)</mo><mo>=</mo>
              <mfrac>
                <mrow><mi>a</mi><mo>+</mo><mi>b</mi></mrow>
                <mi>c</mi>
              </mfrac>
              <mo>,</mo>
              <msqrt><msup><mi>x</mi><mn>2</mn></msup></msqrt>
            </math>.
            """.trimIndent(),
        )

        assertEquals("Formula: f(x) = (a + b)⁄c, √x2.", paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.baselineShift == BaselineShift.Superscript &&
                paragraph.text.substring(range.start, range.end) == "2"
        })
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontSize == TextUnit(0.88f, TextUnitType.Em) &&
                paragraph.text.substring(range.start, range.end) == "(a + b)"
        })
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontStyle == FontStyle.Italic &&
                paragraph.text.substring(range.start, range.end) == "x"
        })
    }

    @Test
    fun `subsup indexed root fences limits and math table remain unambiguous`() {
        val paragraph = onlyParagraph(
            """
            <math>
              <msubsup><mo>∑</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></msubsup>
              <mo>+</mo><mroot><mi>x</mi><mn>3</mn></mroot>
              <mo>+</mo><mfenced open="{" close="}" separators=";"><mi>a</mi><mi>b</mi></mfenced>
              <mo>=</mo>
              <mtable>
                <mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr>
                <mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr>
              </mtable>
            </math>
            """.trimIndent(),
        )

        assertEquals("∑(i = 1)n + 3√x + {a; b} = [1, 2; 3, 4]", paragraph.text.text)
        val shifts = paragraph.text.spanStyles.map { it.item }
        assertTrue(shifts.any { it.baselineShift == BaselineShift.Subscript })
        assertTrue(shifts.any { it.baselineShift == BaselineShift.Superscript })
        assertTrue(shifts.any { it.baselineShift == BaselineShift(0.55f) })
    }

    @Test
    fun `semantics prefers presentation and uses accessible annotation only when empty`() {
        val paragraph = onlyParagraph(
            """
            <math>
              <semantics>
                <msup><mi>x</mi><mn>2</mn></msup>
                <annotation encoding="application/x-tex">x^2</annotation>
              </semantics>
            </math>
            then
            <math>
              <semantics>
                <mrow/>
                <annotation encoding="text/plain">empty set equation</annotation>
              </semantics>
            </math>
            and <math aria-label="formula unavailable"/>.
            """.trimIndent(),
        )

        assertEquals("x2 then empty set equation and formula unavailable.", paragraph.text.text)
        assertFalse(paragraph.text.text.contains("x^2"))
    }

    @Test
    fun `MathML attributes and author CSS retain relative typography`() {
        val paragraph = onlyParagraph(
            """
            <math style="font-size: 150%">
              <mi mathvariant="italic" style="font-style: normal">x</mi>
              <mo>+</mo><mi mathvariant="bold">vector</mi>
            </math>
            """.trimIndent(),
        )

        assertEquals("x + vector", paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontSize == TextUnit(1.5f, TextUnitType.Em) &&
                paragraph.text.substring(range.start, range.end) == "x + vector"
        })
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontStyle == FontStyle.Normal &&
                paragraph.text.substring(range.start, range.end) == "x"
        })
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontWeight == FontWeight.Bold &&
                paragraph.text.substring(range.start, range.end) == "vector"
        })
    }

    @Test
    fun `display math is centered no-indent and keeps anchors and links`() {
        val (elements, mapper) = mapped(
            """
            <p>Before.</p>
            <math display="block" id="equation">
              <mrow id="expression" href="#target"><mi>x</mi><mo>=</mo><mn>1</mn></mrow>
            </math>
            <p id="target">After.</p>
            """.trimIndent(),
            resolveLink = { if (it == "#target") "chapter.xhtml#target" else null },
        )

        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(listOf("Before.", "x = 1", "After."), paragraphs.map { it.text.text })
        assertEquals(BlockAlign.CENTER, paragraphs[1].block?.align)
        assertEquals(false, paragraphs[1].block?.firstLineIndent)
        assertEquals(setOf("chapter.xhtml#target"), mapper.linkTargets)
        assertTrue("equation" in mapper.anchors)
        assertTrue("expression" in mapper.anchors)
        assertEquals(
            "chapter.xhtml#target",
            paragraphs[1].text.getStringAnnotations(LINK_TAG, 0, paragraphs[1].text.length)
                .single().item,
        )
    }

    @Test
    fun `rare fallbacks preserve strings actions errors links and hide phantom content`() {
        val paragraph = onlyParagraph(
            """
            <math>
              <ms lquote="«" rquote="»">текст</ms><mo>;</mo>
              <maction selection="2"><mi>wrong</mi><mi>chosen</mi></maction><mo>;</mo>
              <mover><mi>x</mi><mo>^</mo></mover><mo>;</mo>
              <munder><mo>lim</mo><mrow><mi>x</mi><mo>→</mo><mn>0</mn></mrow></munder><mo>;</mo>
              <merror><mtext>bad input</mtext></merror><mo>;</mo>
              <mphantom><mtext>secret</mtext></mphantom>
              <mi href="https://example.com/math">link</mi>
            </math>
            """.trimIndent(),
        )

        assertEquals("«текст»; chosen; x^; lim(x → 0); ⟦bad input⟧; link", paragraph.text.text)
        assertFalse(paragraph.text.text.contains("secret"))
        assertEquals(
            "https://example.com/math",
            paragraph.text.getStringAnnotations(EXTERNAL_LINK_TAG, 0, paragraph.text.length)
                .single().item,
        )
    }

    @Test
    fun `namespaced deeply nested MathML obeys the shared structure bound`() {
        val nested = buildString {
            repeat(2_000) { append("<m:mrow>") }
            append("<m:mi>too deep</m:mi>")
            repeat(2_000) { append("</m:mrow>") }
        }
        val paragraph = onlyParagraph("before <m:math>$nested</m:math> after")

        assertEquals("before after", paragraph.text.text)
    }

}
