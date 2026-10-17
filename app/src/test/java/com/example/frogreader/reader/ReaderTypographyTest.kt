package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.ui.theme.fontTypographyRequest
import com.example.frogreader.ui.theme.resolveFontTypography
import com.example.frogreader.ui.theme.publisherFontFamily
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/** Parser → shared measurement/drawing plan → actual lazy Compose font descriptors. */
class ReaderTypographyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val settings = ReaderSettings(font = ReaderFont.LITERATA, bookStyles = true)

    private fun mapped(body: String, css: String): List<ContentElement> = HtmlMapper(
        resolveImage = { null }, resolveLink = { it },
        css = CssResolver(listOf(CssResolver.Sheet(css))),
    ).map(Jsoup.parse(body).body())

    private fun paragraph(body: String, css: String) = mapped(body, css)
        .filterIsInstance<ContentElement.Paragraph>().single()

    private fun plan(paragraph: ContentElement.Paragraph, preferences: ReaderSettings = settings,
        fontScale: Float = 1f, size: Float = 20f): ReaderTypographyPlan = readerTypographyPlan(
        paragraph.text, ReaderMetrics.textStyle(paragraph, preferences, size), Density(2f, fontScale), preferences.bookStyles,
    )

    private fun familyAt(plan: ReaderTypographyPlan, word: String): FontFamily {
        val offset = plan.text.text.indexOf(word)
        assertTrue("Missing word $word", offset >= 0)
        return plan.text.spanStyles.lastOrNull { offset in it.start until it.end && it.item.fontFamily != null }
            ?.item?.fontFamily ?: requireNotNull(plan.style.fontFamily)
    }

    private fun variation(family: FontFamily, weight: Int = 400, italic: Boolean = false) =
        (family as FontListFontFamily).fonts.single {
            it.weight == FontWeight(weight) && it.style == if (italic) FontStyle.Italic else FontStyle.Normal
        }.variationSettings

    private fun axes(vararg values: Pair<String, Float>) = FontVariation.Settings(
        *values.sortedBy { it.first }.map { FontVariation.Setting(it.first, it.second) }.toTypedArray(),
    )

    @Test
    fun `block explicit axes and inline normal select real Literata coordinates`() {
        val paragraph = paragraph("<p>Body <span>reset</span> end.</p>", """
            p {font-variation-settings:'wght' 550, 'opsz' 9;}
            span {font-variation-settings:normal;}
        """)
        val result = plan(paragraph)
        assertEquals(axes("opsz" to 9f, "wght" to 550f), variation(familyAt(result, "Body")))
        assertEquals(axes("opsz" to 20f, "wght" to 400f), variation(familyAt(result, "reset")))
        assertEquals(axes("opsz" to 9f, "wght" to 550f), variation(familyAt(result, "end")))
        assertEquals(emptyMap<String, Float>(), fontTypographyRequest(familyAt(result, "reset"))!!.variationAxes)
    }

    @Test
    fun `optical auto none and explicit opsz retain CSS precedence at actual text size`() {
        val paragraph = paragraph("<p>Fixed <span>automatic</span> <em>explicit</em>.</p>", """
            p {font-optical-sizing:none;}
            span {font-size:1.5em;font-optical-sizing:auto;}
            em {font-size:1.5em;font-variation-settings:'opsz' 8;}
        """)
        val result = plan(paragraph, fontScale = 1.2f)
        assertEquals(axes("opsz" to 12f, "wght" to 400f), variation(familyAt(result, "Fixed")))
        assertEquals(axes("opsz" to 36f, "wght" to 400f), variation(familyAt(result, "automatic")))
        assertEquals(axes("opsz" to 8f, "wght" to 400f), variation(familyAt(result, "explicit"), italic = true))
    }

    @Test
    fun `nested relative sizes and Android font scale set optical size for every run`() {
        val paragraph = paragraph("<p>Root <span>outer <i>inner</i></span>.</p>", """
            p {font-size:1.2em;} span {font-size:1.5em;} i {font-size:.5em;}
        """)
        val result = plan(paragraph, fontScale = 1.25f)
        assertEquals(axes("opsz" to 30f, "wght" to 400f), variation(familyAt(result, "Root")))
        assertEquals(axes("opsz" to 45f, "wght" to 400f), variation(familyAt(result, "outer")))
        assertEquals(axes("opsz" to 22.5f, "wght" to 400f), variation(familyAt(result, "inner"), italic = true))
    }

    @Test
    fun `IBM high level width and low level width use actual supported bounds`() {
        val paragraph = paragraph("<p>Compressed <span>normal</span> <em>coordinate</em>.</p>", """
            p {font-stretch:50%;font-weight:450;}
            span {font-width:normal;}
            em {font-width:normal;font-variation-settings:'wdth' 91, 'wght' 525;}
        """)
        val result = plan(paragraph, preferences = settings.copy(font = ReaderFont.SANS))
        assertEquals(axes("wdth" to 85f, "wght" to 450f), variation(familyAt(result, "Compressed"), weight = 450))
        assertEquals(axes("wdth" to 100f, "wght" to 450f), variation(familyAt(result, "normal"), weight = 450))
        assertEquals(axes("wdth" to 91f, "wght" to 525f), variation(familyAt(result, "coordinate"), weight = 450, italic = true))
    }

    @Test
    fun `publisher styles off ignore authored axes while app optical preference remains active`() {
        val paragraph = paragraph("<p>Body <span>inline</span>.</p>", """
            p {font-variation-settings:'wght' 550, 'opsz' 8;font-optical-sizing:none;}
            span {font-variation-settings:'wght' 650, 'opsz' 9;}
        """)
        val preferences = settings.copy(bookStyles = false)
        val result = plan(paragraph, preferences, fontScale = 1.25f)
        assertEquals(axes("opsz" to 25f, "wght" to 400f), variation(familyAt(result, "Body")))
        assertEquals(axes("opsz" to 25f, "wght" to 400f), variation(familyAt(result, "inline")))
        val legacyDisabled = plan(paragraph, preferences.copy(fontOpticalSizing = false), fontScale = 1.25f)
        assertEquals(axes("opsz" to 25f, "wght" to 400f), variation(familyAt(legacyDisabled, "inline")))
    }

    @Test
    fun `retired manual width stays normal while publisher CSS remains available`() {
        val paragraph = paragraph("<p>Default <span>publisher</span>.</p>", "span {font-stretch:normal;}")
        val result = plan(paragraph, settings.copy(font = ReaderFont.SANS, fontStretchPercent = 90f))
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(result, "Default")))
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(result, "publisher")))
    }

    @Test
    fun `source text links and all parser annotations remain unchanged after planning`() {
        val paragraph = paragraph("<p>Read <a href='chapter.xhtml#part'>this <b>chapter</b></a>.</p>", """
            p {font-optical-sizing:auto;} b {font-variation-settings:'wght' 600;}
        """)
        val original = paragraph.text
        val result = plan(paragraph, fontScale = 1.2f)
        assertEquals(original.text, result.text.text)
        assertEquals(original.getStringAnnotations(0, original.length), result.text.getStringAnnotations(0, result.text.length))
        assertEquals(original.paragraphStyles, result.text.paragraphStyles)
        assertEquals(axes("opsz" to 24f, "wght" to 600f), variation(familyAt(result, "chapter"), weight = 700))
    }

    @Test
    fun `table cells reset inherited coordinates and dropcaps use fitted size`() {
        val elements = mapped("<p>First book.</p><table><tr><td>Wide</td><td>reset</td></tr></table>", """
            p {font-variation-settings:'opsz' 9;}
            p::first-letter {float:left;font-size:3em;font-variation-settings:normal;font-optical-sizing:auto;}
            table {font-stretch:90%;font-variation-settings:'wght' 475;}
            td:last-child {font-stretch:normal;font-variation-settings:normal;}
        """)
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        val capStyle = ReaderMetrics.dropCapStyle(settings, 40f, paragraph.block!!.firstLetter, emptyMap(), null)
        val cap = readerTypographyPlan(AnnotatedString("F"), capStyle, Density(2f, 1.5f), true)
        assertEquals(axes("opsz" to 60f, "wght" to 400f), variation(cap.style.fontFamily!!))
        val table = elements.filterIsInstance<ContentElement.Table>().single()
        val preferences = settings.copy(font = ReaderFont.SANS)
        val cells = table.rows.single().cells
        fun plannedCell(index: Int): ReaderTypographyPlan {
            val cell = cells[index]
            val style = ReaderMetrics.tableCellStyle(preferences, 20f, 1f, false, null, table.block, cell.block)
            return readerTypographyPlan(cell.text, style, Density(2f), true)
        }
        assertEquals(axes("wdth" to 90f, "wght" to 475f), variation(familyAt(plannedCell(0), "Wide")))
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(plannedCell(1), "reset")))
    }

    @Test
    fun `retired typography preferences preserve page keys while font scale changes layout`() {
        fun key(preferences: ReaderSettings = settings, fontScale: Float = 1f) = PaginationSpec(
            contentWidthPx = 800, contentHeightPx = 1200, density = Density(2f, fontScale), settings = preferences, fontSize = 20f,
        ).key
        assertEquals(key(), key(settings.copy(fontStretchPercent = 90f)))
        assertEquals(key(), key(settings.copy(fontOpticalSizing = false)))
        assertNotEquals(key(), key(fontScale = 1.25f))
    }

    @Test
    fun `resolved inherited optical none survives later size changes`() {
        val paragraph = paragraph("<p>Book.</p>", "p {font-optical-sizing:none;}")
        val style: TextStyle = ReaderMetrics.textStyle(paragraph, settings, 12f)
        val family = resolveFontTypography(style.fontFamily!!, 36f)
        assertEquals(FontTypography(opticalSizing = false), fontTypographyRequest(family))
        assertEquals(axes("opsz" to 12f, "wght" to 400f), variation(family))
    }

    @Test
    fun `EPUB variable font survives extraction reuse summary and full rendering pipeline`() {
        val fixture = listOf(File("src/main/res/font/ibm_plex_sans.ttf"),
            File("app/src/main/res/font/ibm_plex_sans.ttf")).first { it.isFile }.readBytes()
        val epub = temporary.newFile("typography.epub")
        writeVariableEpub(epub, fixture)
        val cache = temporary.newFolder("epub-cache")
        val first = EpubParser.parseContent(epub, cache)
        val initialFace = first.fonts.single()
        assertEquals("book face", initialFace.family)
        assertEquals(100, initialFace.weightMin)
        assertEquals(700, initialFace.weightMax)
        assertEquals(85f, initialFace.stretchMin)
        assertEquals(100f, initialFace.stretchMax)
        val extracted = File(initialFace.path)
        assertArrayEquals(fixture, extracted.readBytes())
        assertTrue(extracted.setLastModified(1_234_000L))
        val modified = extracted.lastModified()

        val reopened = EpubParser.parseContent(epub, cache)
        val face = reopened.fonts.single()
        assertEquals(initialFace.path, face.path)
        assertEquals(modified, extracted.lastModified())
        assertEquals(85f, face.stretchMin)
        assertEquals(100f, face.stretchMax)
        val publisher = publisherStyleOf(reopened)!!
        assertEquals("Book face", publisher.fontName)
        assertEquals("book face", publisher.fontCss)
        assertEquals(listOf(face), publisher.fontFaces)
        assertEquals(FontTypography(stretchPercent = 90f), publisher.fontTypography)

        val family = publisherFontFamily(reopened.fonts)!!
        val families = mapOf("book face" to family)
        val paragraph = reopened.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>().single()
        val style = ReaderMetrics.textStyle(paragraph, settings, 20f, bookFonts = families)
        val result = readerTypographyPlan(paragraph.text, style, Density(2f), true, families)
        assertEquals(axes("wdth" to 90f, "wght" to 450f), variation(familyAt(result, "Body"), weight = 450))
        assertEquals(axes("wdth" to 95f, "wght" to 450f), variation(familyAt(result, "coordinate"), weight = 450))
        assertEquals(axes("wdth" to 100f, "wght" to 450f), variation(familyAt(result, "reset"), weight = 450))
    }

    @Test
    fun `unembedded CSS bundled family aliases resolve block inline and publisher summary`() {
        val paragraph = paragraph("<p>Literata <span>IBM</span> <em>alias</em>.</p>", """
            p {font-family:'Literata';font-variation-settings:'wght' 475;}
            span {font-family:'IBM Plex Sans';font-width:90%;}
            em {font-family:'IBM Plex Sans Var';font-width:95%;}
        """)
        val style = ReaderMetrics.textStyle(paragraph, settings, 20f)
        val result = readerTypographyPlan(paragraph.text, style, Density(2f), true)
        assertEquals(axes("opsz" to 20f, "wght" to 475f), variation(familyAt(result, "Literata")))
        assertEquals(axes("wdth" to 90f, "wght" to 475f), variation(familyAt(result, "IBM")))
        assertEquals(axes("wdth" to 95f, "wght" to 475f), variation(familyAt(result, "alias"), italic = true))
        val publisher = publisherStyleOf(BookContent(listOf(Chapter(null, listOf(paragraph)))))!!
        assertEquals("Literata", publisher.fontName)
        assertEquals(FontTypography(variationAxes = mapOf("wght" to 475f)), publisher.fontTypography)
        assertTrue(publisher.fontFaces.isEmpty())
        assertNull(publisher.fontPath)

        val preferences = settings.copy(bookStyles = false)
        assertEquals(paragraph.text, paragraph.text.withBookFonts(emptyMap(), enabled = false))
        val ignored = readerTypographyPlan(paragraph.text,
            ReaderMetrics.textStyle(paragraph, preferences, 20f), Density(2f), false)
        assertEquals(axes("opsz" to 20f, "wght" to 400f), variation(familyAt(ignored, "IBM")))

        val sansParagraph = this.paragraph("<p>Named body.</p>", "p {font-family:'IBM Plex Sans Var';font-width:90%;}")
        val sans = plan(sansParagraph)
        assertEquals(axes("wdth" to 90f, "wght" to 400f), variation(familyAt(sans, "Named")))
        val sansPublisher = publisherStyleOf(BookContent(listOf(Chapter(null, listOf(sansParagraph)))))!!
        assertEquals("IBM Plex Sans", sansPublisher.fontName)
        assertEquals(FontTypography(stretchPercent = 90f), sansPublisher.fontTypography)
    }

    @Test
    fun `same shared book source follows publisher on off and Sans choices without stale faces`() {
        val paragraph = paragraph("<p>Пані Рейчел <b>Лінд</b> дивується.</p>",
            "p {font-family:'Times New Roman';}")
        val original = paragraph.text
        val families = mapOf("times new roman" to FontFamily.Serif)
        fun display(preferences: ReaderSettings) = readerTypographyPlan(
            original, ReaderMetrics.textStyle(paragraph, preferences, 20f, bookFonts = families),
            Density(2f), preferences.bookStyles, families,
        )
        val publisher = display(settings)
        assertEquals(FontFamily.Serif, familyAt(publisher, "Рейчел"))
        val sans = display(settings.copy(bookStyles = false, font = ReaderFont.SANS))
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(sans, "Рейчел")))
        assertEquals(axes("wdth" to 100f, "wght" to 700f), variation(familyAt(sans, "Лінд"), weight = 700))
        assertEquals(FontFamily.Serif, familyAt(display(settings), "Рейчел"))
        assertTrue(original.spanStyles.none { it.item.fontFamily != null })
        assertEquals(original, paragraph.text)
        assertEquals(original.text, sans.text.text)
        assertEquals(original.getStringAnnotations(0, original.length),
            sans.text.getStringAnnotations(0, sans.text.length))
    }

    @Test
    fun `publisher off ignores generic CSS families while preserving semantic code and pre`() {
        val paragraph = paragraph("<p>Base <span>Serif</span> <i>Mono</i> <code>code</code>.</p>", """
            p {font-family:'Literata';} span {font-family:serif;} i {font-family:monospace;}
        """)
        val publisher = plan(paragraph)
        assertEquals(FontFamily.Serif, familyAt(publisher, "Serif"))
        assertEquals(FontFamily.Monospace, familyAt(publisher, "Mono"))
        assertEquals(FontFamily.Monospace, familyAt(publisher, "code"))
        val preferences = settings.copy(bookStyles = false, font = ReaderFont.SANS)
        val reader = plan(paragraph, preferences)
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(reader, "Serif")))
        assertEquals(axes("wdth" to 100f, "wght" to 400f), variation(familyAt(reader, "Mono"), italic = true))
        assertEquals(FontFamily.Monospace, familyAt(reader, "code"))
        val pre = this.paragraph("<pre>preformatted</pre>", "pre {font-family:serif;}")
        assertEquals(FontFamily.Serif, familyAt(plan(pre), "preformatted"))
        assertEquals(FontFamily.Monospace, familyAt(plan(pre, preferences), "preformatted"))
    }

    @Test
    fun `semantic code and pre keep monospace under inherited serif while explicit family overrides`() {
        val paragraphs = mapped("""
            <p>Body <code>code <span>nested</span></code> <code class='plain'>reset</code>.</p>
            <pre>preformatted</pre>
        """, "body {font-family:serif;} code.plain {font-family:serif;}")
            .filterIsInstance<ContentElement.Paragraph>()
        val code = paragraphs.first()
        val publisher = plan(code)
        assertEquals(FontFamily.Monospace, familyAt(publisher, "code"))
        assertEquals(FontFamily.Monospace, familyAt(publisher, "nested"))
        assertEquals(FontFamily.Serif, familyAt(publisher, "reset"))
        assertEquals(FontFamily.Monospace, familyAt(plan(paragraphs.last()), "preformatted"))
        val preferences = settings.copy(bookStyles = false, font = ReaderFont.SANS)
        assertEquals(FontFamily.Monospace, familyAt(plan(code, preferences), "reset"))
    }

    @Test
    fun `generated code glyphs and ruby readings keep semantic family and authored resets`() {
        val paragraph = paragraph("<p>Body <code>code <ruby>漢<rt>reading</rt></ruby></code>.</p>", """
            p {font-family:serif;}
            code::before {content:'[';}
            code::after {content:']';font-family:serif;}
        """)
        val publisher = plan(paragraph)
        assertEquals(FontFamily.Monospace, familyAt(publisher, "["))
        assertEquals(FontFamily.Monospace, familyAt(publisher, "reading"))
        assertEquals(FontFamily.Serif, familyAt(publisher, "]"))
        val reading = rubyInlineSourceRanges(paragraph.text).single().ruby.readingText()
        val readingStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        assertEquals(FontFamily.Monospace, familyAt(
            readerTypographyPlan(reading, readingStyle, Density(2f), true), "reading"))
        val reader = plan(paragraph, settings.copy(bookStyles = false, font = ReaderFont.SANS))
        assertEquals(FontFamily.Monospace, familyAt(reader, "]"))
    }

    private fun writeVariableEpub(target: File, font: ByteArray) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun text(name: String, value: String) = entry(name, value.toByteArray(Charsets.UTF_8))
            text("mimetype", "application/epub+zip")
            text("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            text("OPS/content.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="id">
                  <metadata><dc:identifier id="id">font-typography-test</dc:identifier><dc:title>Typography</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="style" href="style.css" media-type="text/css"/>
                    <item id="font" href="fonts/book.ttf" media-type="font/ttf"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent())
            text("OPS/style.css", """
                @font-face {font-family:'Book Face';src:url(fonts/book.ttf);font-weight:100 700;font-stretch:85% 100%;}
                p {font-family:'Book Face';font-weight:450;font-width:90%;}
                span {font-variation-settings:'wdth' 95;}
                em {font-style:normal;font-variation-settings:normal;font-stretch:normal;}
            """.trimIndent())
            text("OPS/chapter.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Typography</title>
                  <link rel="stylesheet" type="text/css" href="style.css"/></head>
                  <body><p>Body <span>coordinate</span> <em>reset</em>.</p></body>
                </html>
            """.trimIndent())
            entry("OPS/fonts/book.ttf", font)
        }
    }
}
