package com.example.frogreader.parser

import com.example.frogreader.testfixtures.LocalBookFixtures

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_MATH_TAG
import com.example.frogreader.data.model.InlineMathCodec
import com.example.frogreader.data.model.completeMathInlineMarks
import com.example.frogreader.data.model.mathInlineRanges
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class HtmlMapperNativeMathTest {
    private fun paragraph(source: String): AnnotatedString {
        val document = Jsoup.parse("<body><p>$source</p></body>", "", Parser.xmlParser())
        return HtmlMapper(resolveImage = { null }).map(document.selectFirst("body")!!)
            .filterIsInstance<ContentElement.Paragraph>().single().text
    }

    @Test fun `fractions roots and scripts carry native presentation without changing fallback text`() {
        val text = paragraph("Formula: <math><mi>y</mi><mo>=</mo><mfrac><mrow><mi>x</mi><mo>−</mo><mn>3</mn><mi>y</mi></mrow><mn>6</mn></mfrac><mo>+</mo><msqrt><msup><mi>x</mi><mn>2</mn></msup></msqrt></math>.")
        assertEquals("Formula: y = (x − 3y)⁄6 + √x2.", text.text)
        val mark = completeMathInlineMarks(text).single()
        assertEquals("y = (x − 3y)⁄6 + √x2", mark.math.fallback)
        assertEquals(listOf("token", "token", "frac", "token", "sqrt"), mark.math.root.children.map { it.kind })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertEquals(1, mathInlineRanges(text).size)
    }

    @Test fun `simple math also keeps atomic semantics and operator spacing`() {
        val text = paragraph("A<math><mi>x</mi><mo>+</mo><mn>1</mn></math>B")
        assertEquals("Ax + 1B", text.text)
        val root = completeMathInlineMarks(text).single().math.root
        assertEquals(0.18f, root.children[1].leftSpaceEm)
        assertEquals(0.18f, root.children[1].rightSpaceEm)
    }

    @Test fun `padded leading operator retains formatter whitespace next to preceding prose`() {
        val text = paragraph("A<math><mo>+</mo><mn>1</mn></math>B")
        assertEquals("A + 1B", text.text)
        assertEquals(" + 1", completeMathInlineMarks(text).single().math.fallback)
    }

    @Test fun `partial substrings cannot render the whole source formula twice`() {
        val text = paragraph("Before <math><mfrac><mn>15</mn><mn>4</mn></mfrac></math> after")
        val mark = completeMathInlineMarks(text).single()
        assertTrue(completeMathInlineMarks(text.subSequence(mark.start + 1, mark.end)).isEmpty())
        assertTrue(completeMathInlineMarks(text.subSequence(mark.start, mark.end - 1)).isEmpty())
        assertEquals(1, completeMathInlineMarks(text.subSequence(mark.start, mark.end)).size)
    }

    @Test fun `unsupported color and layout retain rich readable fallback`() {
        val text = paragraph("<math><mfrac><mi mathcolor='#f00'>x</mi><mn>2</mn></mfrac></math> <math><mtable><mtr><mtd><mn>1</mn></mtd></mtr></mtable></math>")
        assertEquals("x⁄2 [1]", text.text)
        assertTrue(text.getStringAnnotations(INLINE_MATH_TAG, 0, text.length).isEmpty())
        assertTrue(text.spanStyles.isNotEmpty())
    }

    @Test fun `deep or oversized metadata is rejected before native layout`() {
        assertNull(InlineMathCodec.decode("[".repeat(100) + "]".repeat(100)))
        assertNull(InlineMathCodec.decode(" ".repeat(InlineMathCodec.MAX_PAYLOAD + 1)))
        val source = "<math>" + "<mrow>".repeat(33) + "<mn>1</mn>" + "</mrow>".repeat(33) + "</math>"
        assertTrue(paragraph(source).getStringAnnotations(INLINE_MATH_TAG, 0, 1).isEmpty())
    }

    @Test fun `native metadata roundtrips after reader adds color and font spans`() {
        val text = paragraph("<math style='font-size: 150%'><mfrac><mn>1</mn><mn>2</mn></mfrac></math>")
        val source = completeMathInlineMarks(text).single()
        val modified = AnnotatedString.Builder(text).apply {
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), 0, text.length)
        }.toAnnotatedString()
        val decoded = completeMathInlineMarks(modified).single().math
        assertEquals(source.math, decoded)
        assertEquals(1.5f, decoded.root.style.fontSizeEm)
        assertFalse(decoded.wholeStyles.isEmpty())
        assertNotNull(InlineMathCodec.decode(source.payload))
    }

    @Test fun `authored non-stretchy fences and OpenType features are retained in native tree`() {
        val text = paragraph("<math style=\"font-variant: small-caps\"><mo fence='true' stretchy='false'>(</mo><mfrac><mn>1</mn><mn>2</mn></mfrac><mo fence='true'>)</mo></math>")
        val root = completeMathInlineMarks(text).single().math.root
        assertFalse(root.children.first().stretchy)
        assertTrue(root.children.last().stretchy)
        assertTrue(root.style.fontFeatures?.contains("smcp") == true)
    }

    @Test fun `all 125 Tangled Tale formulas are admitted as native presentation`() {
        val book = LocalBookFixtures.find("lewis-carroll_a-tangled-tale_advanced.epub")
        assumeTrue(book.isFile)
        var sourceCount = 0
        var nativeCount = 0
        var rawMarks = 0
        var firstFailure = ""
        ZipFile(book).use { archive ->
            for (entry in archive.entries().asSequence().filter { it.name.endsWith(".xhtml") }) {
                val source = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                val document = Jsoup.parse(source, "", Parser.xmlParser())
                sourceCount += document.getAllElements().count { it.normalName().substringAfterLast(':') == "math" }
                val elements = HtmlMapper(resolveImage = { null }).map(document.selectFirst("body") ?: continue)
                for (element in elements) {
                    val texts = when (element) {
                        is ContentElement.Paragraph -> listOf(element.text)
                        is ContentElement.Heading -> listOf(element.styledText)
                        is ContentElement.Table -> element.rows.flatMap { row -> row.cells.map { it.text } }
                        else -> emptyList()
                    }
                    nativeCount += texts.sumOf { completeMathInlineMarks(it).size }
                    rawMarks += texts.sumOf { it.getStringAnnotations(INLINE_MATH_TAG, 0, it.length).size }
                    if (firstFailure.isEmpty()) texts.firstOrNull {
                        it.text.contains('⁄') || it.text.contains('√')
                    }?.let { firstFailure = it.text.take(240) }
                }
            }
        }
        assertEquals(125, sourceCount)
        assertEquals("Raw annotations=$rawMarks; first formula=$firstFailure", sourceCount, nativeCount)
    }
}
