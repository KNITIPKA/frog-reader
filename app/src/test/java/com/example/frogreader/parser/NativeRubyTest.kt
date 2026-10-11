package com.example.frogreader.parser

import com.example.frogreader.testfixtures.LocalBookFixtures

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_RUBY_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.InlineRuby
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.InlineTextBuilder
import com.example.frogreader.ui.reader.rubyInlineLayouts
import com.example.frogreader.ui.reader.rubyInlineSourceRanges
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.io.File
import java.util.zip.ZipFile

class NativeRubyTest {
    private fun parsed(html: String, css: String = ""): AnnotatedString = HtmlMapper(
        resolveImage = { null },
        css = CssResolver(listOf(CssResolver.Sheet(css))),
    ).map(Jsoup.parse(html).body()).filterIsInstance<ContentElement.Paragraph>().single().text

    @Test
    fun `native ruby retains selectable fallback and styled author reading`() {
        val text = parsed("<p>Excommunicamus <ruby>hunc<rt><i>vel</i> os</rt></ruby> furem.</p>",
            "rt {font-size:1em; ruby-align:center;}")
        assertEquals("Excommunicamus huncvel os furem.", text.text)
        val annotation = text.getStringAnnotations(INLINE_RUBY_TAG, 0, text.length).single()
        val ruby = requireNotNull(InlineRuby.decode(annotation.item))
        assertEquals("huncvel os", text.text.substring(annotation.start, annotation.end))
        assertEquals(4, ruby.baseLength)
        assertEquals(10, ruby.sourceLength)
        assertEquals("vel os", ruby.reading)
        val reading = ruby.readingText()
        assertTrue(reading.spanStyles.any { it.start == 0 && it.end == 3 && it.item.fontStyle == FontStyle.Italic })
        assertTrue(reading.spanStyles.none { it.item.baselineShift != null })
        assertTrue(reading.spanStyles.none { it.item.fontSize.value == 0.6f })
        val inline = text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length).single()
        assertEquals(ruby.id, inline.item)
        assertEquals(annotation.start, inline.start)
        assertEquals(annotation.end, inline.end)
    }

    @Test
    fun `ruby pairs remain separate and rp fallback parentheses are omitted`() {
        val text = parsed("<p><ruby><rb>漢</rb><rp>(</rp><rt>かん</rt><rp>)</rp><rb>字</rb><rt>じ</rt></ruby></p>")
        assertEquals("漢かん字じ", text.text)
        val rubies = text.getStringAnnotations(INLINE_RUBY_TAG, 0, text.length)
            .map { requireNotNull(InlineRuby.decode(it.item)) }
        assertEquals(listOf("かん", "じ"), rubies.map { it.reading })
        assertEquals(2, rubies.map { it.id }.distinct().size)
        assertTrue(rubies.all { it.readingText().spanStyles.any { span -> span.item.fontSize.value == 0.5f } })
    }

    @Test
    fun `a fragment inside ruby cannot draw the complete original reading again`() {
        val text = parsed("<p><ruby>hunc<rt>vel os</rt></ruby></p>")
        val fragment = text.subSequence(0, 3)
        val measurer = mock(TextMeasurer::class.java)
        assertTrue(rubyInlineLayouts(fragment, TextStyle(fontSize = 20.sp), measurer, Density(1f)).isEmpty())
        verifyNoInteractions(measurer)
    }

    @Test
    fun `malformed and oversized ruby payloads fall back safely`() {
        assertNull(InlineRuby.decode("not json"))
        assertNull(InlineRuby.decode(InlineRuby(id = "ruby", baseLength = 2, sourceLength = 1, reading = "a").encode()))
        assertNull(InlineRuby.from("ruby", 2, AnnotatedString("a".repeat(InlineRuby.MAX_READING_LENGTH + 1))))
        assertNull(InlineRuby.from("ruby", 2, AnnotatedString("line\nbreak")))
    }

    @Test
    fun `display none rt omits only the hidden reading`() {
        val text = parsed("<p><ruby>hunc<rt>vel os</rt></ruby> furem.</p>", "rt {display:none;}")
        assertEquals("hunc furem.", text.text)
        assertTrue(text.getStringAnnotations(INLINE_RUBY_TAG, 0, text.length).isEmpty())
    }

    @Test
    fun `mixed flow preserves whitespace between ruby and abbreviation siblings`() {
        val html = """<p>Maledict<ruby>us<rt>i</rt></ruby> <ruby>sit<rt>n</rt></ruby> <abbr>N.</abbr> <abbr>N.</abbr> vivendo.<span class="row"><span>⸻</span><span>⸻</span></span>Next.</p>"""
        val elements = HtmlMapper(resolveImage = { null }, css = CssResolver(listOf(CssResolver.Sheet(
            ".row {display:flex; justify-content:space-between;} rt {font-size:1em;}"
        )))).map(Jsoup.parse(html).body())
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals("Maledictusi sitn N. N. vivendo.", paragraphs.first().text.text.trim())
        assertEquals("Next.", paragraphs.last().text.text.trim())
        assertEquals(1, elements.filterIsInstance<ContentElement.Table>().size)
    }

    @Test
    fun `inline trim retains authored nonbreaking and typographic edge spaces`() {
        val html = "<h2> \n\u00A0Heading\u2009\t </h2>" +
            "<p><ruby> \n\u00A0base\u2009\t<rt> \n\u202Fread\u00A0\t </rt></ruby></p>"
        val elements = HtmlMapper(resolveImage = { null }, css = CssResolver(emptyList()))
            .map(Jsoup.parse(html).body())
        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        assertEquals("\u00A0Heading\u2009", heading.text)
        val text = elements.filterIsInstance<ContentElement.Paragraph>().single().text
        assertEquals("\u00A0base\u2009\u202Fread\u00A0", text.text)
        val ruby = requireNotNull(InlineRuby.decode(
            text.getStringAnnotations(INLINE_RUBY_TAG, 0, text.length).single().item))
        assertEquals("\u202Fread\u00A0", ruby.reading)
        assertEquals(6, ruby.baseLength)
    }

    @Test
    fun `rich ruby image fallback retains inline images without emitting duplicate block art`() {
        val elements = HtmlMapper(resolveImage = { "/book/$it" }, css = CssResolver(emptyList())).map(
            Jsoup.parse("<p>Before <ruby><img src='base.png'/>x<rt>reading</rt></ruby> after.</p>" +
                "<p>Before <ruby>x<rt><img src='reading.png'/>reading</rt></ruby> after.</p>").body())
        assertTrue(elements.filterIsInstance<ContentElement.Image>().isEmpty())
        val texts = elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertEquals(2, texts.size)
        assertTrue(texts.all { it.getStringAnnotations(INLINE_RUBY_TAG, 0, it.length).isEmpty() })
        assertEquals(listOf("/book/base.png", "/book/reading.png"), texts.map {
            it.getStringAnnotations(INLINE_IMAGE_TAG, 0, it.length).single().item
        })
        assertTrue(texts.all { it.text.contains("x") && it.text.contains("reading") })
    }

    @Test
    fun `annotated image transfer preserves image only paragraph classification`() {
        val source = InlineTextBuilder().apply { inlineImage("/book/initial.png") }.build()
        val target = InlineTextBuilder().apply { appendAnnotated(source) }
        assertTrue(target.isBlank)
        assertEquals(listOf("/book/initial.png"), target.imageRefs)
    }

    @Test
    fun `all 36 Shandy readings preserve prior paragraph source coordinates`() {
        val file = LocalBookFixtures.find("laurence-sterne_the-life-and-opinions-of-tristram-shandy-gentleman_advanced.epub")
        assumeTrue(file.exists())
        ZipFile(file).use { zip ->
            val html = zip.getInputStream(zip.getEntry("epub/text/chapter-3-11-la.xhtml")).bufferedReader().readText()
            val stylesheet = zip.getInputStream(zip.getEntry("epub/css/local.css")).bufferedReader().readText()
            // Isolate inline paragraphs from unrelated new chapter CSS rows.
            // Clone their existing DOM directly: serializing/renaming tags
            // would change whitespace or accidentally activate span CSS.
            val paragraphs = Jsoup.parse(html).select("p:has(ruby)")
            fun body(paragraph: Element) = Element("body").appendChild(paragraph.clone())
            // Golden captured by running the original pre-audit HtmlMapper
            // against these exact DOM paragraphs, before adding native ruby.
            // No tag renaming or HTML serialization was used for the oracle.
            val legacy = requireNotNull(javaClass.getResourceAsStream("/epub/shandy-ruby-fallback.json")).use {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(it.bufferedReader().readText())
            }
            val native = paragraphs.map { paragraph ->
                HtmlMapper(resolveImage = { null },
                    css = CssResolver(listOf(CssResolver.Sheet(stylesheet))))
                    .map(body(paragraph)).filterIsInstance<ContentElement.Paragraph>().single().text
            }
            assertEquals(legacy, native.map { it.text })
            val marks = native.flatMap { it.getStringAnnotations(INLINE_RUBY_TAG, 0, it.length) }
            assertEquals(36, marks.size)
            assertTrue(marks.all { it.end - it.start == requireNotNull(InlineRuby.decode(it.item)).sourceLength })
            val wordRuns = native.flatMap(::rubyInlineSourceRanges)
            assertEquals(36, wordRuns.size)
            assertEquals(marks.map { InlineRuby.decode(it.item)?.reading }, wordRuns.map { it.ruby.reading })
            assertTrue(wordRuns.any { it.baseText.text == "furem" && it.baseStart == 4 && it.baseEnd == 5 })
            assertTrue(wordRuns.any { it.baseText.text == "malefactorem" && it.baseStart == 10 && it.baseEnd == 12 })
        }
    }
}
