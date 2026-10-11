package com.example.frogreader.parser

import androidx.compose.ui.text.style.TextAlign
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.parser.mobi.MobiBuilder
import com.example.frogreader.ui.reader.ReaderMetrics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Identical numbered heading cases pass through each actual format parser. */
class HeadingFormatTest {
    @get:Rule val temp = TemporaryFolder()
    private val settings = ReaderSettings(bookStyles = true)
    private val authorCss = "text-align:center; font-size:120%; line-height:1.1; margin:0; font-weight:normal"

    @Test fun `EPUB heading defaults and author styles`() = checkFormat("epub")
    @Test fun `MOBI6 heading defaults and author styles`() = checkFormat("mobi")
    @Test fun `KF8 heading defaults and author styles`() = checkFormat("azw3")
    @Test fun `FB2 heading defaults and author styles`() = checkFormat("fb2")

    @Test fun `Markdown uses the same heading hierarchy without inventing alignment`() {
        val source = temp.newFile("headings.md").apply {
            writeText((1..6).joinToString("\n\n") { "${"#".repeat(it)} Case $it\n\nBody $it." })
        }
        assertDefaultHierarchy(BookParsers.parseContent(source, BookFormat.MD, temp.root))
    }

    private fun checkFormat(format: String) {
        // Native FB2 titles are centered; HTML headings use logical start.
        assertDefaultHierarchy(parse(format, styled = false), if (format == "fb2") TextAlign.Center else TextAlign.Start)
        // 02: Authored centering, size, line spacing and zero margins survive.
        val headings = parse(format, styled = true).chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Heading>()
        assertEquals((1..6).toList(), headings.map { it.level })
        for (heading in headings) {
            val style = ReaderMetrics.textStyle(heading, settings, 20f)
            assertEquals("$format ${heading.text}", TextAlign.Center, style.textAlign)
            assertEquals(24f, style.fontSize.value, 0.001f)
            assertEquals(26.4f, style.lineHeight.value, 0.001f)
            assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, style.fontWeight)
            val (top, bottom) = ReaderMetrics.verticalPaddings(heading, 20f, bookStyles = true)
            assertEquals(0f, top.value, 0.001f)
            assertEquals(0f, bottom.value, 0.001f)
        }
    }

    private fun assertDefaultHierarchy(content: BookContent, alignment: TextAlign = TextAlign.Start) {
        val headings = content.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>()
        assertEquals((1..6).toList(), headings.map { it.level })
        val sizes = headings.map { heading ->
            val style = ReaderMetrics.textStyle(heading, settings, 20f)
            assertEquals(alignment, style.textAlign)
            assertTrue(style.fontSize.value >= 20f)
            style.fontSize.value
        }
        assertTrue(sizes.zipWithNext().all { (first, second) -> first > second })
    }

    private fun parse(format: String, styled: Boolean): BookContent {
        if (format == "fb2") {
            var sections = ""
            for (level in 6 downTo 2) {
                sections = "<section><title><p>Case $level</p></title><p>Body $level.</p>$sections</section>"
            }
            val stylesheet = if (styled) "<stylesheet type='text/css'>title { $authorCss }</stylesheet>" else ""
            val xml = """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                $stylesheet<description><title-info><book-title>Headings</book-title></title-info></description>
                <body><title><p>Case 1</p></title><p>Body 1.</p>$sections</body>
                </FictionBook>"""
            return Fb2Parser.parseContent({ xml.byteInputStream() }, temp.root)
        }
        val css = if (styled) "h1,h2,h3,h4,h5,h6 { $authorCss }" else ""
        val body = (1..6).joinToString("") { "<h$it>Case $it</h$it><p>Body $it.</p>" }
        return parseHtml(format, body, css)
    }

    private fun parseHtml(format: String, body: String, css: String = ""): BookContent {
        val html = "<html><head><style>$css</style></head><body>$body</body></html>"
        val source = temp.newFile("${temp.root.listFiles()!!.size}.$format")
        when (format) {
            "epub" -> ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                entry("content.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">headings</dc:identifier><dc:title>Headings</dc:title><dc:language>en</dc:language></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>""")
                entry("text.xhtml", html)
            }
            "mobi" -> MobiBuilder.buildMobi6(target = source, html = html, compress = true)
            "azw3" -> MobiBuilder.buildKf8(source, MobiBuilder.Kf8Spec(
                skeletons = listOf("<html><head><link rel='stylesheet' href='kindle:flow:0001?mime=text/css'/></head><body></body></html>"),
                fragments = listOf(listOf(body)),
                css = css,
            ))
        }
        return BookParsers.parseContent(source, if (format == "epub") BookFormat.EPUB else BookFormat.MOBI, temp.root)
    }

    @Test
    fun `EPUB MOBI6 and KF8 preserve inherited and explicit heading alignments`() {
        val alignments = listOf(
            "center" to TextAlign.Center, "left" to TextAlign.Left, "right" to TextAlign.Right,
            "start" to TextAlign.Start, "end" to TextAlign.End, "justify" to TextAlign.Justify,
        )
        for (format in listOf("epub", "mobi", "azw3")) for ((cssAlign, expected) in alignments) {
            val body = (1..6).joinToString("") { "<h$it>Inherited $it</h$it>" }
            val headings = parseHtml(format, body, "body { text-align:$cssAlign }")
                .chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>()
            assertEquals(6, headings.size)
            for (heading in headings) for (publisher in listOf(false, true)) {
                assertEquals("$format ${heading.text} $cssAlign", expected,
                    ReaderMetrics.textStyle(heading, settings.copy(bookStyles = publisher), 20f).textAlign)
            }
        }
    }

    @Test
    fun `legacy heading hints override inherited CSS and yield to their own CSS`() {
        val body = """
            <h1 align="center">Own hint</h1>
            <div align="right"><h2>Wrapper hint</h2></div>
            <center><h3>Center element</h3><h4 style="text-align:left">CSS wins</h4></center>
            <div align="center" style="text-align:right"><h5>Wrapper CSS wins</h5></div>
            <h6 align="center" style="text-align:inherit">Explicit inheritance</h6>
        """.trimIndent()
        val expected = listOf(TextAlign.Center, TextAlign.Right, TextAlign.Center, TextAlign.Left, TextAlign.Right, TextAlign.Justify)
        for (format in listOf("epub", "mobi", "azw3")) {
            val headings = parseHtml(format, body, "body { text-align:justify }")
                .chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>()
            assertEquals(6, headings.size)
            for (publisher in listOf(false, true)) {
                assertEquals(format, expected, headings.map {
                    ReaderMetrics.textStyle(it, settings.copy(bookStyles = publisher), 20f).textAlign
                })
            }
        }
    }

    @Test
    fun `FB2 subtitles and poem titles retain their native centering`() {
        val xml = """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Titles</book-title></title-info></description>
            <body><section><title><p>Chapter</p></title><subtitle>Subtitle</subtitle>
            <poem><title><p>Poem</p></title><stanza><title><p>Stanza</p></title><v>Verse</v></stanza></poem>
            </section></body></FictionBook>"""
        val headings = Fb2Parser.parseContent({ xml.byteInputStream() }, temp.root)
            .chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>()
        assertEquals(listOf("Chapter", "Subtitle", "Poem", "Stanza"), headings.map { it.text })
        for (heading in headings) for (publisher in listOf(false, true)) {
            assertEquals(TextAlign.Center, ReaderMetrics.textStyle(heading, settings.copy(bookStyles = publisher), 20f).textAlign)
        }
    }

    @Test
    fun `FB2 preserves each authored title paragraph alignment and styled words`() {
        val xml = """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css">title { text-align:right } subtitle { text-align:initial }</stylesheet>
            <description><title-info><book-title>Titles</book-title></title-info></description>
            <body><section><title><p style="text-align:left">Left <emphasis>word</emphasis></p>
            <p>Inherited right</p><p style="text-align:center">Centered</p></title><subtitle>Start</subtitle>
            </section></body></FictionBook>"""
        val headings = Fb2Parser.parseContent({ xml.byteInputStream() }, temp.root)
            .chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Heading>()
        val title = headings.first()
        assertEquals("Left word\nInherited right\nCentered", title.text)
        assertEquals(listOf(TextAlign.Left, TextAlign.Right, TextAlign.Center), title.styledText.paragraphStyles.map { it.item.textAlign })
        assertEquals(listOf("Left word", "Inherited right", "Centered"), title.styledText.paragraphStyles.map {
            title.text.substring(it.start, it.end)
        })
        assertTrue(title.styledText.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertEquals(TextAlign.Right, ReaderMetrics.textStyle(title, settings, 20f).textAlign)
        assertEquals(TextAlign.Start, ReaderMetrics.textStyle(headings.last(), settings, 20f).textAlign)
    }
}
