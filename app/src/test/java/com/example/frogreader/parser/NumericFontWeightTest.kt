package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.ui.reader.ReaderMetrics
import com.example.frogreader.ui.reader.embeddedFontSignature
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NumericFontWeightTest {
    @get:Rule val temp = TemporaryFolder()
    private val weights = listOf(1, 300, 400, 500, 550, 600, 700, 900, 1000)
    private val settings = ReaderSettings(font = ReaderFont.SERIF)

    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))
    private fun map(css: String, html: String): List<ContentElement> = HtmlMapper(
        resolveImage = { it },
        resolveLink = { it },
        css = resolver(css),
    ).map(Jsoup.parse("<body>$html</body>").body())

    @Test
    fun `numeric paragraph inline heading and table weights survive shared measurement styles`() {
        weights.forEach { weight ->
            val elements = map("p, h2, table { font-weight: $weight; }", """
                <p>Кириллица Latin <span style="font-weight:500"><em>mixed</em></span></p>
                <h2>Heading</h2><table><tr><td>Cell</td><td>Other</td></tr></table>
                """)
            val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().first()
            val heading = elements.filterIsInstance<ContentElement.Heading>().single()
            val table = elements.filterIsInstance<ContentElement.Table>().single()
            assertEquals(weight, paragraph.block?.fontWeight)
            assertEquals(weight, heading.block?.fontWeight)
            assertEquals(weight, table.block?.fontWeight)
            for (publisherStyles in listOf(false, true)) {
                val selected = settings.copy(bookStyles = publisherStyles)
                assertEquals(FontWeight(weight), ReaderMetrics.textStyle(paragraph, selected, 20f).fontWeight)
                assertEquals(FontWeight(weight), ReaderMetrics.textStyle(heading, selected, 20f).fontWeight)
                assertEquals(FontWeight(weight), ReaderMetrics.tableCellStyle(selected, 20f, 1f, false, null,
                    tableBlock = table.block).fontWeight)
            }
            val mixed = paragraph.text.text.indexOf("mixed")
            val inlineWeight = paragraph.text.spanStyles.lastOrNull {
                mixed in it.start until it.end && it.item.fontWeight != null
            }?.item?.fontWeight?.weight ?: paragraph.block?.fontWeight
            assertEquals(500, inlineWeight)
            assertTrue(paragraph.text.spanStyles.any {
                mixed in it.start until it.end && it.item.fontStyle == FontStyle.Italic
            })
        }
    }

    @Test
    fun `nested overrides inheritance normal reset and relative weights retain actual numeric values`() {
        val r = resolver("body { font-weight:500; }")
        val document = Jsoup.parse("""
            <div style="font-weight:300"><p id="inherited">Inherited
            <span id="medium" style="font-weight:500"><span id="semi" style="font-weight:600">
            <strong id="bold"><em id="both">Both</em></strong>
            <span id="normal" style="font-weight:normal">Normal</span>
            <span id="inherit" style="font-weight:inherit">Inherit</span>
            <span id="initial" style="font-weight:initial">Initial</span>
            <span id="bolder" style="font-weight:bolder">Bolder</span>
            <span id="lighter" style="font-weight:lighter">Lighter</span>
            <span id="invalid" style="font-weight:1001">Invalid</span>
            <em id="plain-em" style="font-style:initial">Plain</em>
            <strong id="reverted" style="font-weight:revert">Reverted</strong>
            </span></span></p></div>
            """)
        val expected = mapOf("inherited" to 300, "medium" to 500, "semi" to 600,
            "bold" to 700, "both" to 700, "normal" to 400, "inherit" to 600,
            "initial" to 400, "bolder" to 900, "lighter" to 400, "invalid" to 600, "reverted" to 700)
        expected.forEach { (id, weight) -> assertEquals(id, weight, r.computed(document.getElementById(id)!!).fontWeight) }
        assertEquals(true, r.computed(document.getElementById("both")!!).italic)
        assertEquals(false, r.computed(document.getElementById("plain-em")!!).italic)
    }

    @Test
    fun `inline normal reset remains explicit inside bold blocks and publisher off preserves semantics`() {
        val paragraph = map("p { font-weight:900; }", """
            <p>Black <span style="font-weight:normal">Normal</span>
            <strong>Bold <em>Both</em></strong> <em>Italic</em></p>
            """).filterIsInstance<ContentElement.Paragraph>().single()
        val normal = paragraph.text.text.indexOf("Normal")
        val bold = paragraph.text.text.indexOf("Bold")
        val both = paragraph.text.text.indexOf("Both")
        assertTrue(paragraph.text.spanStyles.any { normal in it.start until it.end && it.item.fontWeight == FontWeight.Normal })
        assertTrue(paragraph.text.spanStyles.any { bold in it.start until it.end && it.item.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.text.spanStyles.any { both in it.start until it.end && it.item.fontStyle == FontStyle.Italic })
        val style = ReaderMetrics.textStyle(paragraph, settings.copy(bookStyles = false), 20f)
        assertEquals(FontWeight(900), style.fontWeight)
    }

    @Test
    fun `font face descriptors preserve separate weights and variable ranges for the same file`() {
        val r = resolver("""
            @font-face { font-family: Book; src:url(book.ttf); font-weight:300; }
            @font-face { font-family: Book; src:url(book.ttf); font-weight:400; }
            @font-face { font-family: Book; src:url(book.ttf); font-weight:500; }
            @font-face { font-family: Book; src:url(book.ttf); font-weight:600; }
            @font-face { font-family: Book; src:url(book.ttf); font-weight:700; }
            @font-face { font-family: Book; src:url(book.ttf); font-weight:900; }
            @font-face { font-family: Book; src:url(variable.ttf); font-weight:100 900; font-style:italic; }
            """)
        assertEquals(listOf(300, 400, 500, 600, 700, 900, 400), r.fontFaces.map { it.weight })
        val variable = r.fontFaces.last()
        assertEquals(100, variable.weightMin)
        assertEquals(900, variable.weightMax)
        assertEquals(true, variable.italic)
    }

    @Test
    fun `generated content drop caps and cell overrides keep nonstandard numeric weights`() {
        val r = resolver("p::before { content:'Mark'; font-weight:550; } p::first-letter { font-size:3em; font-weight:650; }")
        val p = Jsoup.parse("<p>Text</p>").selectFirst("p")!!
        assertEquals(550, r.generated(p, after = false)?.fontWeight)
        assertEquals(650, r.firstLetter(p)?.fontWeight)
        assertEquals(FontWeight(650), ReaderMetrics.dropCapStyle(settings.copy(bookStyles = true), 50f,
            FirstLetterStyle(scale = 3f, isDropCap = true, fontWeight = 650), emptyMap(), null).fontWeight)
        val table = map("table { font-weight:900; } td { font-weight:500; }", "<table><tr><td>Cell</td><td>Other</td></tr></table>")
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(FontWeight(500), ReaderMetrics.tableCellStyle(settings, 20f, 1f, false, null,
            tableBlock = table.block, cellBlock = table.rows.single().cells.first().block).fontWeight)
    }

    @Test
    fun `publisher font toggle only changes family while retaining numeric emphasis`() {
        val block = BlockStyle(fontFamily = "book", fontWeight = 500)
        val paragraph = ContentElement.Paragraph(AnnotatedString("Latin Кириллица"), block = block)
        val families = mapOf("book" to FontFamily.Monospace)
        val publisher = ReaderMetrics.textStyle(paragraph, settings.copy(bookStyles = true), 20f, bookFonts = families)
        val user = ReaderMetrics.textStyle(paragraph, settings.copy(bookStyles = false), 20f, bookFonts = families)
        assertEquals(FontFamily.Monospace, publisher.fontFamily)
        assertEquals(FontFamily.Serif, user.fontFamily)
        assertEquals(FontWeight(500), publisher.fontWeight)
        assertEquals(publisher.fontWeight, user.fontWeight)
    }

    @Test
    fun `EPUB extraction preserves static descriptor weights and a variable range across repeated opens`() {
        val epub = temp.newFile("weights.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun text(name: String, content: String) = entry(name, content.toByteArray())
            text("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""")
            text("OPS/content.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                <metadata><dc:title>Weights</dc:title><dc:language>uk</dc:language></metadata>
                <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
                <item id="font" href="font.otf" media-type="font/otf"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
                """)
            text("OPS/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="style.css"/></head><body><p>Текст Latin</p></body></html>""")
            text("OPS/style.css", """
                @font-face { font-family: Book; src:url(font.otf); font-weight:300; }
                @font-face { font-family: Book; src:url(font.otf); font-weight:500; }
                @font-face { font-family: Book; src:url(font.otf); font-weight:600; }
                @font-face { font-family: Book; src:url(font.otf); font-weight:900; }
                @font-face { font-family: Book; src:url(font.otf); font-weight:100 900; font-style:italic; }
                p { font-family:Book; font-weight:500; }
                """)
            // The parser's SFNT fixture verifies descriptors/extraction, without loading glyphs.
            entry("OPS/font.otf", "OTTO".toByteArray() + ByteArray(64))
        }
        val extracted = temp.newFolder()
        repeat(2) {
            val content = EpubParser.parseContent(epub, extracted)
            assertEquals(listOf(300, 500, 600, 900, 400), content.fonts.map { it.weight })
            assertEquals(100, content.fonts.last().weightMin)
            assertEquals(900, content.fonts.last().weightMax)
            assertTrue(content.fonts.all { File(it.path).isFile })
            assertEquals(500, content.chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single().block?.fontWeight)
        }
    }

    @Test
    fun `changing a numeric embedded face weight invalidates font signature even with identical bytes`() {
        val file = temp.newFile("book.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val face = BookFont("book", file.absolutePath, false, false, weight = 300)
        val light = embeddedFontSignature(listOf(face))
        val medium = embeddedFontSignature(listOf(face.copy(weight = 500)))
        val variable = embeddedFontSignature(listOf(face.copy(weightMin = 100, weightMax = 900)))
        assertNotEquals(light, medium)
        assertNotEquals(light, variable)
    }
}
