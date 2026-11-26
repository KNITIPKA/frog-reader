package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.FontObfuscation
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import com.example.frogreader.data.parser.RustEpubPackage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildTestEpub(target: File) {
        val coverBytes = byteArrayOf(9, 8, 7)
        val picBytes = byteArrayOf(1, 2, 3, 4)

        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }

            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="id">
                  <metadata>
                    <dc:title>Test Book</dc:title>
                    <dc:creator>John Doe</dc:creator>
                    <dc:language>en</dc:language>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                    <item id="pic" href="images/pic.png" media-type="image/png"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="p1"><navLabel><text>Chapter One</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
                    <navPoint id="p2"><navLabel><text>Chapter Two</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/text/ch1.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>ignored</title></head>
                <body>
                  <h1>Chapter One</h1>
                  <p>Plain and <strong>bold</strong> words.</p>
                  <p id="publisher-colors" style="color:#123456;background-color:rgb(250 240 230)">
                    Color <span style="color:hsl(300 100% 25%);background-color:#ff08">span</span>.
                  </p>
                  <p><img src="../images/pic.png" alt=""/></p>
                  <blockquote><p>Quoted line.</p></blockquote>
                </body>
                </html>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/text/ch2.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body><p>Second chapter text.</p></body>
                </html>
                """.trimIndent().toByteArray(),
            )
            entry("OEBPS/images/cover.jpg", coverBytes)
            entry("OEBPS/images/pic.png", picBytes)
        }
    }

    @Test
    fun `parses epub metadata with epub2 cover`() {
        val epub = tempFolder.newFile("test.epub")
        buildTestEpub(epub)

        val metadata = EpubParser.parseMetadata(epub)
        assertEquals("Test Book", metadata.title)
        assertEquals("John Doe", metadata.author)
        assertArrayEquals(byteArrayOf(9, 8, 7), metadata.coverBytes)
    }

    @Test
    fun `epub keeps inline publisher foreground and background without a stylesheet`() {
        val epub = tempFolder.newFile("publisher-colors.epub")
        buildTestEpub(epub)

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        val paragraph = content.chapters.first().elements
            .filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Color") }

        assertEquals(0xff123456.toInt(), paragraph.block?.foregroundColorArgb)
        assertEquals(0xfffaf0e6.toInt(), paragraph.block?.backgroundColorArgb)
        val spanOffset = paragraph.text.text.indexOf("span")
        val span = paragraph.text.spanStyles.last {
            it.start <= spanOffset && it.end >= spanOffset + 4 &&
                it.item.color != Color.Unspecified
        }.item
        assertEquals(0xff800080.toInt(), span.color.toArgb())
        assertEquals(0x88ffff00.toInt(), span.background.toArgb())
    }

    private fun writeMetadataEpub(target: File, metadataXml: String) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            entry(
                "content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata>
                  $metadataXml
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
                """.trimIndent(),
            )
            entry(
                "ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Text.</p></body></html>",
            )
        }
    }

    @Test
    fun `parses extended opf metadata with calibre series`() {
        val epub = tempFolder.newFile("richmeta.epub")
        writeMetadataEpub(
            epub,
            """
            <dc:title>Rich Meta</dc:title>
            <dc:creator opf:role="aut">Main Author</dc:creator>
            <dc:creator>Second Author</dc:creator>
            <dc:creator opf:role="ill">Illustrator</dc:creator>
            <dc:contributor opf:role="trl">Translator One</dc:contributor>
            <dc:subject>Fantasy</dc:subject>
            <dc:subject>Adventure</dc:subject>
            <dc:publisher>Fine Books</dc:publisher>
            <dc:date>2013-05-01</dc:date>
            <dc:identifier opf:scheme="ISBN">978-5-17-030411-1</dc:identifier>
            <dc:description>&lt;p&gt;Blurb with &lt;b&gt;markup&lt;/b&gt;.&lt;/p&gt;</dc:description>
            <dc:language>uk</dc:language>
            <meta name="calibre:series" content="Great Saga"/>
            <meta name="calibre:series_index" content="2.5"/>
            """.trimIndent(),
        )

        val metadata = EpubParser.parseMetadata(epub)

        assertEquals("Rich Meta", metadata.title)
        assertEquals(listOf("Main Author", "Second Author"), metadata.authors)
        assertEquals("Main Author", metadata.author)
        assertEquals(listOf("Translator One"), metadata.translators)
        assertEquals(listOf("Fantasy", "Adventure"), metadata.genres)
        assertEquals("Fine Books", metadata.publisher)
        assertEquals("2013", metadata.year)
        assertEquals("978-5-17-030411-1", metadata.isbn)
        assertEquals("Blurb with markup.", metadata.description)
        assertEquals("uk", metadata.language)
        assertEquals("Great Saga", metadata.series)
        assertEquals(2.5f, metadata.seriesNumber!!, 0.0001f)
    }

    @Test
    fun `parses epub3 collection series and refined roles`() {
        val epub = tempFolder.newFile("epub3meta.epub")
        writeMetadataEpub(
            epub,
            """
            <dc:title>Collection Book</dc:title>
            <dc:creator id="tr">Someone</dc:creator>
            <meta refines="#tr" property="role" scheme="marc:relators">trl</meta>
            <dc:creator id="au">Real Author</dc:creator>
            <meta refines="#au" property="role">aut</meta>
            <dc:identifier>urn:isbn:9785170304111</dc:identifier>
            <meta property="belongs-to-collection" id="c1">Epic Cycle</meta>
            <meta refines="#c1" property="collection-type">series</meta>
            <meta refines="#c1" property="group-position">3</meta>
            """.trimIndent(),
        )

        val metadata = EpubParser.parseMetadata(epub)

        assertEquals(listOf("Real Author"), metadata.authors)
        assertEquals(listOf("Someone"), metadata.translators)
        assertEquals("9785170304111", metadata.isbn)
        assertEquals("Epic Cycle", metadata.series)
        assertEquals(3f, metadata.seriesNumber!!, 0.0001f)
    }

    @Test
    fun `unpacks woff2 embedded fonts to raw sfnt`() {
        val epub = tempFolder.newFile("woff2.epub")
        val woff2 = Woff2TestFonts.buildWoff2(
            Woff2TestFonts.minimalTtfTables().map { (tag, data) -> Woff2TestFonts.Spec(tag, data) },
        )
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            entry(
                "content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="id">
                  <metadata><dc:title>Woff2 Book</dc:title></metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="font" href="fonts/book.woff2" media-type="font/woff2"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            entry(
                "ch1.xhtml",
                (
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
                        "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>" +
                        "</head><body><p>Text in the book face.</p></body></html>"
                    ).toByteArray(),
            )
            entry(
                "style.css",
                (
                    "@font-face { font-family: BookFace; src: url(fonts/book.woff2); }\n" +
                        "p { font-family: BookFace; }"
                    ).toByteArray(),
            )
            entry("fonts/book.woff2", woff2)
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        val font = content.fonts.single()
        assertEquals("bookface", font.family)
        val stored = File(font.path).readBytes()
        // WOFF2 unpacked to raw TrueType sfnt.
        assertEquals(0x00, stored[0].toInt())
        assertEquals(0x01, stored[1].toInt())
        assertEquals(0x00, stored[2].toInt())
        assertEquals(0x00, stored[3].toInt())
    }

    @Test
    fun `parses chapters with titles from ncx, formatting and images`() {
        val epub = tempFolder.newFile("test.epub")
        buildTestEpub(epub)
        val imagesDir = tempFolder.newFolder("images")

        val chapters = EpubParser.parseContent(epub, imagesDir).chapters
        assertEquals(2, chapters.size)
        assertEquals("Chapter One", chapters[0].title)
        assertEquals("Chapter Two", chapters[1].title)

        val elements = chapters[0].elements
        val heading = elements[0] as ContentElement.Heading
        assertEquals("Chapter One", heading.text)
        assertEquals(1, heading.level)

        val paragraph = elements[1] as ContentElement.Paragraph
        assertEquals("Plain and bold words.", paragraph.text.text)
        val bold = paragraph.text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size)
        assertEquals("bold", paragraph.text.text.substring(bold[0].start, bold[0].end))

        val image = elements.filterIsInstance<ContentElement.Image>().single()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(image.path).readBytes())

        val quote = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "Quoted line." }
        assertEquals(
            com.example.frogreader.data.model.ParagraphStyle.QUOTE,
            quote.style,
        )
    }

    @Test
    fun `self-closed title tag must not swallow chapter text`() {
        // Regression: real books use XHTML with <title/> in <head>; the HTML
        // parser treats it as unterminated and loses the whole body.
        val epub = tempFolder.newFile("selfclosed.epub")
        val paragraphs = (1..40).joinToString("") {
            "<p class=\"p1\">Абзац номер $it із досить довгим текстом усередині.</p>"
        }
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="id">
                  <metadata><dc:title>SelfClosed</dc:title></metadata>
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>""",
            )
            entry(
                "OPS/ch1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title/><link rel="stylesheet" href="style.css" type="text/css"/></head>
                <body class="z"><span><span id="id1"><div class="title4"><p>Розділ 1</p></div>$paragraphs</span></span></body>
                </html>""",
            )
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        val chapters = content.chapters
        val totalChars = chapters.sumOf { ch ->
            ch.elements.filterIsInstance<ContentElement.Paragraph>().sumOf { it.text.text.length }
        }
        assertTrue("expected full text, got $totalChars chars", totalChars > 1500)
        val paragraphCount = chapters.sumOf { ch ->
            ch.elements.count { it is ContentElement.Paragraph }
        }
        assertTrue("expected ~40 paragraphs, got $paragraphCount", paragraphCount >= 40)
        // No dc:language in this fixture — the Ukrainian text decides.
        assertEquals("uk", content.language)
    }

    @Test
    fun `reads dc language from the opf`() {
        val epub = tempFolder.newFile("lang.epub")
        buildTestEpub(epub)
        assertEquals("en", EpubParser.parseContent(epub, tempFolder.newFolder()).language)
    }

    @Test
    fun `extracts footnotes referenced from chapter text`() {
        val epub = tempFolder.newFile("notes.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="id">
                  <metadata><dc:title>Notes</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nt" href="notes.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="nt"/></spine>
                </package>""",
            )
            entry(
                "OPS/ch1.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>
                <p>Обычный текст<a href="notes.xhtml#n53"><sup>[53]</sup></a> с примечанием.</p>
                </body></html>""",
            )
            entry(
                "OPS/notes.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>
                <p id="n53">[53] Это текст примечания из конца книги.</p>
                <p id="n54">[54] Другое примечание.</p>
                </body></html>""",
            )
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())

        val note = content.notes["OPS/notes.xhtml#n53"]
        assertEquals("[53] Это текст примечания из конца книги.", note?.text)

        val paragraph = content.chapters.first().elements
            .filterIsInstance<ContentElement.Paragraph>().first()
        val annotation = paragraph.text
            .getStringAnnotations(
                com.example.frogreader.data.model.FOOTNOTE_TAG,
                0,
                paragraph.text.text.length,
            )
            .single()
        assertEquals("OPS/notes.xhtml#n53", annotation.item)
        assertEquals("[53]", paragraph.text.text.substring(annotation.start, annotation.end))
    }

    @Test
    fun `EPUB semantic note keeps full rich container and exact sibling boundary`() {
        val epub = tempFolder.newFile("rich-notes.epub")
        val longText = "Long EPUB note text ".repeat(50)
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            fun entry(name: String, content: String) = entry(name, content.toByteArray())
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Rich notes</dc:title></metadata><manifest><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="pic" href="pic.png" media-type="image/png"/></manifest><spine><itemref idref="main"/><itemref idref="notes" linear="no"/></spine></package>""",
            )
            entry(
                "OPS/main.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><p>Main<a epub:type="noteref" href="notes.xhtml#n1">1</a>.</p></body></html>""",
            )
            entry(
                "OPS/notes.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><style>.styled { text-align: center; font-size: 1.2em; }</style></head><body>
                  <aside epub:type="footnote" id="n1">
                    <h4>Authored heading</h4>
                    <p class="styled">$longText</p><p>Second.</p><p>Third.</p><p>Fourth.</p><p>Fifth.</p>
                    <blockquote><p>Quoted.</p></blockquote>
                    <ul><li>Listed.</li></ul>
                    <table><tr><th>Key</th><th>Value</th></tr><tr><td>A</td><td>B</td></tr></table>
                    <img src="pic.png" alt="Rich diagram"/>
                    <p>Inline <img src="pic.png" alt="Inline diagram"/> tail.</p>
                    <p>See <a epub:type="noteref" href="#n2">next note</a>.</p>
                  </aside>
                  <aside epub:type="footnote" id="n2"><p>Second note only.</p></aside>
                </body></html>""",
            )
            entry("OPS/pic.png", byteArrayOf(1, 2, 3, 4))
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        val note = content.notes.getValue("OPS/notes.xhtml#n1")

        assertTrue(note.text.length > 700)
        assertTrue(note.elements.first() is ContentElement.Heading)
        assertTrue(note.elements.count { it is ContentElement.Paragraph } >= 8)
        assertTrue(note.elements.any { it is ContentElement.Table })
        val styled = note.elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Long EPUB") }
        assertEquals(BlockAlign.CENTER, styled.block?.align)
        assertEquals(1.2f, styled.block?.fontScale ?: 0f, 0.001f)
        val image = note.elements.filterIsInstance<ContentElement.Image>().single()
        assertTrue(File(image.path).isFile)
        assertEquals("Rich diagram", image.altText)
        val inline = note.elements.filterIsInstance<ContentElement.Paragraph>()
            .flatMap { paragraph ->
                paragraph.text.getStringAnnotations(
                    com.example.frogreader.data.model.INLINE_IMAGE_TAG,
                    0,
                    paragraph.text.length,
                )
            }
            .single()
        assertTrue(File(inline.item).isFile)
        assertTrue(!note.text.contains("Second note only."))
        assertEquals("Second note only.", content.notes.getValue("OPS/notes.xhtml#n2").text)
        assertTrue("OPS/notes.xhtml" in content.linkedDocuments)
    }

    @Test
    fun `deobfuscates idpf-mangled embedded fonts`() {
        val uid = "urn:uuid:aaaabbbb-cccc-dddd-eeee-ffff00001111"
        val realFont = "OTTO".toByteArray() + ByteArray(1500) { (it % 100).toByte() }
        val mangled = com.example.frogreader.data.parser.FontObfuscation.deobfuscate(
            realFont,
            com.example.frogreader.data.parser.FontObfuscation.idpfKey(uid),
            com.example.frogreader.data.parser.FontObfuscation.IDPF_PREFIX,
        )

        val epub = tempFolder.newFile("obfuscated.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            entry(
                "META-INF/encryption.xml",
                """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                    <enc:CipherData><enc:CipherReference URI="OPS/fonts/lit.otf"/></enc:CipherData>
                  </enc:EncryptedData>
                </encryption>""".toByteArray(),
            )
            entry(
                "OPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="uid">
                  <metadata>
                    <dc:title>Mangled</dc:title>
                    <dc:identifier id="uid">$uid</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="f1" href="fonts/lit.otf" media-type="application/vnd.ms-opentype"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>""".toByteArray(),
            )
            entry(
                "OPS/style.css",
                """@font-face { font-family: "Lit"; src: url(fonts/lit.otf); }""".toByteArray(),
            )
            entry(
                "OPS/ch1.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head>
                <link rel="stylesheet" type="text/css" href="style.css"/>
                </head><body><p>Текст главы достаточной длины.</p></body></html>""".toByteArray(),
            )
            entry("OPS/fonts/lit.otf", mangled)
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())

        val font = content.fonts.single()
        assertEquals("lit", font.family)
        val extracted = File(font.path).readBytes()
        assertArrayEquals(realFont, extracted)
    }

    @Test
    fun `detects and stores plain epub and zipped fb2`() {
        val booksDir = tempFolder.newFolder("books")

        val epub = tempFolder.newFile("book.epub")
        buildTestEpub(epub)
        val (epubFormat, epubStored) = BookParsers.detectAndStore(epub, booksDir, "id-epub")
        assertEquals(BookFormat.EPUB, epubFormat)
        assertEquals("id-epub.epub", epubStored.name)
        assertTrue(epubStored.exists())

        val fb2Content = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Zipped</book-title></title-info></description>
            <body><section><p>Text.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val zippedFb2 = tempFolder.newFile("book.fb2.zip")
        ZipOutputStream(zippedFb2.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("book.fb2"))
            zip.write(fb2Content.toByteArray())
            zip.closeEntry()
        }
        val (fb2Format, fb2Stored) = BookParsers.detectAndStore(zippedFb2, booksDir, "id-fb2")
        assertEquals(BookFormat.FB2, fb2Format)
        assertEquals("id-fb2.fb2", fb2Stored.name)
        assertEquals(fb2Content, fb2Stored.readText())

        val plainFb2 = tempFolder.newFile("plain.fb2")
        plainFb2.writeText(fb2Content)
        val (plainFormat, plainStored) = BookParsers.detectAndStore(plainFb2, booksDir, "id-plain")
        assertEquals(BookFormat.FB2, plainFormat)
        assertTrue(plainStored.readText().contains("FictionBook"))
    }

    private fun buildMultiChapterParallelParityEpub(
        target: File,
        chapterCount: Int = 24,
        includeInlineSvg: Boolean = true,
    ) {
        val sharedDividerBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4)
        val fakeFontA = ByteArray(128) { i -> i.toByte() }.apply {
            this[0] = 'O'.code.toByte(); this[1] = 'T'.code.toByte()
            this[2] = 'T'.code.toByte(); this[3] = 'O'.code.toByte()
        }
        val fakeFontB = ByteArray(128) { i -> (i + 7).toByte() }.apply {
            this[0] = 0; this[1] = 1; this[2] = 0; this[3] = 0
        }

        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }

            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )

            val manifestItems = buildString {
                appendLine("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
                appendLine("""<item id="base-css" href="styles/base.css" media-type="text/css"/>""")
                appendLine("""<item id="main-css" href="styles/main.css" media-type="text/css"/>""")
                appendLine("""<item id="font-a" href="fonts/alpha.otf" media-type="font/otf"/>""")
                appendLine("""<item id="font-b" href="fonts/beta.ttf" media-type="font/ttf"/>""")
                appendLine("""<item id="divider" href="images/divider.png" media-type="image/png"/>""")
                for (i in 1..chapterCount) {
                    appendLine("""<item id="ch$i" href="text/ch$i.xhtml" media-type="application/xhtml+xml"/>""")
                    if (i % 4 == 0) {
                        appendLine("""<item id="img$i" href="images/fig$i.png" media-type="image/png"/>""")
                    }
                }
                appendLine("""<item id="endnotes" href="text/endnotes.xhtml" media-type="application/xhtml+xml"/>""")
            }
            val spineRefs = buildString {
                for (i in 1..chapterCount) {
                    appendLine("""<itemref idref="ch$i"/>""")
                }
                appendLine("""<itemref idref="endnotes" linear="no"/>""")
            }
            entry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="uid">
                  <metadata>
                    <dc:title>Parallel Parity Corpus</dc:title>
                    <dc:creator>Test Author</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    $manifestItems
                  </manifest>
                  <spine toc="ncx">
                    $spineRefs
                  </spine>
                </package>
                """.trimIndent().toByteArray(),
            )

            // Mix single-chapter files, multi-section TOC splits within one spine item, and unlisted merge-with-previous items.
            val navPoints = buildString {
                var navOrder = 1
                for (i in 1..chapterCount) {
                    if (i % 6 == 0) {
                        // Unlisted in TOC: merges into previous chapter!
                        continue
                    }
                    if (i % 5 == 0) {
                        // Multi-chapter split inside one spine file!
                        appendLine(
                            """<navPoint id="np${navOrder++}"><navLabel><text>Part $i A</text></navLabel><content src="text/ch$i.xhtml#secA"/></navPoint>""",
                        )
                        appendLine(
                            """<navPoint id="np${navOrder++}"><navLabel><text>Part $i B</text></navLabel><content src="text/ch$i.xhtml#secB"/></navPoint>""",
                        )
                    } else {
                        appendLine(
                            """<navPoint id="np${navOrder++}"><navLabel><text>Chapter $i</text></navLabel><content src="text/ch$i.xhtml"/></navPoint>""",
                        )
                    }
                }
            }
            entry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    $navPoints
                  </navMap>
                </ncx>
                """.trimIndent().toByteArray(),
            )

            entry(
                "OEBPS/styles/base.css",
                """
                @font-face { font-family: "Alpha"; src: url(../fonts/alpha.otf); }
                .lead { font-weight: bold; }
                .tagged::before { content: "Note: "; }
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/styles/main.css",
                """
                @import url("base.css");
                .callout { margin-left: 1em; padding-left: 0.5em; border-left: 2px solid #335577; background-color: #f4f6f8; }
                .epigraph { font-style: italic; text-align: right; }
                """.trimIndent().toByteArray(),
            )
            entry("OEBPS/fonts/alpha.otf", fakeFontA)
            entry("OEBPS/fonts/beta.ttf", fakeFontB)
            entry("OEBPS/images/divider.png", sharedDividerBytes)

            for (i in 1..chapterCount) {
                if (i % 4 == 0) {
                    entry("OEBPS/images/fig$i.png", ByteArray(16) { b -> (i + b).toByte() })
                }
                val inlineCss = if (i % 3 == 0) {
                    val extraFontFace = if (i == 12) {
                        """@font-face { font-family: "Beta"; src: url(../fonts/beta.ttf); }"""
                    } else {
                        ""
                    }
                    """<style type="text/css">$extraFontFace .local$i { color: #11${(10 + i).toString().padStart(2, '0')}55; }</style>"""
                } else {
                    ""
                }
                val nextChapter = if (i < chapterCount) i + 1 else 1
                val extraImage = if (i % 4 == 0) {
                    """<p><img src="../images/fig$i.png" alt="Figure $i"/></p>"""
                } else {
                    ""
                }
                val inlineSvg = if (includeInlineSvg && i % 7 == 0) {
                    """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><circle cx="8" cy="8" r="6"/></svg>"""
                } else {
                    ""
                }
                val bodyHtml = if (i % 5 == 0) {
                    """
                    <p class="epigraph">Preamble before split in spine $i.</p>
                    <h2 id="secA">Section $i A</h2>
                    <p class="lead local$i">Paragraph A in chapter $i with <a href="endnotes.xhtml#en$i" epub:type="noteref">[e$i]</a> and <a href="#fn$i" epub:type="noteref">[$i]</a>.</p>
                    <div class="callout"><p class="tagged">Boxed callout $i linking to <a href="ch$nextChapter.xhtml">next</a>.</p></div>
                    <h2 id="secB">Section $i B</h2>
                    <p>Paragraph B in chapter $i.</p>
                    <p><img src="../images/divider.png" alt="divider"/></p>
                    $extraImage
                    $inlineSvg
                    <aside id="fn$i" epub:type="footnote"><p>Inline footnote body for chapter $i.</p></aside>
                    """.trimIndent()
                } else {
                    """
                    <h1>Heading $i</h1>
                    <p class="lead local$i">Opening paragraph of chapter $i with <a href="endnotes.xhtml#en$i" epub:type="noteref">[e$i]</a> and <a href="#fn$i" epub:type="noteref">[$i]</a>.</p>
                    <div class="callout"><p class="tagged">Body callout $i linking to <a href="ch$nextChapter.xhtml">chapter $nextChapter</a>.</p></div>
                    <p><img src="../images/divider.png" alt="divider"/></p>
                    $extraImage
                    $inlineSvg
                    <aside id="fn$i" epub:type="footnote"><p>Inline footnote body for chapter $i.</p></aside>
                    """.trimIndent()
                }
                entry(
                    "OEBPS/text/ch$i.xhtml",
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <head>
                      <title>Doc $i</title>
                      <link rel="stylesheet" type="text/css" href="../styles/main.css"/>
                      $inlineCss
                    </head>
                    <body>
                      $bodyHtml
                    </body>
                    </html>
                    """.trimIndent().toByteArray(),
                )
            }

            val endnoteItems = buildString {
                for (i in 1..chapterCount) {
                    appendLine("""<aside id="en$i" epub:type="footnote"><p>Endnote text for chapter $i.</p></aside>""")
                }
            }
            entry(
                "OEBPS/text/endnotes.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Endnotes</title></head>
                <body>
                  <h1>Endnotes</h1>
                  $endnoteItems
                </body>
                </html>
                """.trimIndent().toByteArray(),
            )
        }
    }

    private fun canonicalizeMaps(content: BookContent): BookContent = BookContent(
        chapters = content.chapters,
        notes = content.notes.toSortedMap(),
        linkTargets = content.linkTargets.toSortedMap(),
        fonts = content.fonts,
        language = content.language,
        linkedDocuments = content.linkedDocuments.toSortedMap(),
        linkedDocumentTargets = content.linkedDocumentTargets.toSortedMap(),
        navigation = content.navigation,
        publisherPublication = content.publisherPublication,
        pageProgression = content.pageProgression,
        epubSourceRanges = content.epubSourceRanges,
    )

    private fun assertBookContentBitParity(
        expected: BookContent,
        expectedImagesDir: File,
        actual: BookContent,
        actualImagesDir: File,
    ) {
        val json = Json { encodeDefaults = true }
        val expectedJson = json.encodeToString(BookContent.serializer(), canonicalizeMaps(expected))
            .replace(expectedImagesDir.absolutePath, "<IMGDIR>")
        val actualJson = json.encodeToString(BookContent.serializer(), canonicalizeMaps(actual))
            .replace(actualImagesDir.absolutePath, "<IMGDIR>")

        assertEquals(expected.chapters.size, actual.chapters.size)
        assertEquals(expected.totalElements, actual.totalElements)
        assertEquals(expected.language, actual.language)
        assertEquals(expected.pageProgression, actual.pageProgression)
        assertEquals(expected.epubSourceRanges, actual.epubSourceRanges)
        assertEquals(expected.linkTargets, actual.linkTargets)
        assertEquals(expected.linkedDocumentTargets, actual.linkedDocumentTargets)
        assertEquals(expected.notes.keys, actual.notes.keys)
        assertEquals(expected.linkedDocuments.keys, actual.linkedDocuments.keys)
        assertEquals(expected.fonts.map { it.family }, actual.fonts.map { it.family })
        assertEquals(expected.navigation.size, actual.navigation.size)
        assertEquals(expectedJson, actualJson)
    }

    @Test
    fun `bounded-batch parallel epub parsing matches sequential parsing bit-for-bit`() {
        val epub = tempFolder.newFile("parallel-parity-24.epub")
        buildMultiChapterParallelParityEpub(epub, chapterCount = 24)

        val seqDir = tempFolder.newFolder("seq-images")
        val parDir = tempFolder.newFolder("par-images")

        val sequential = EpubParser.parseContent(
            file = epub,
            imagesDir = seqDir,
            limits = ReaderResourceLimits.DEFAULT,
            maxParallelism = 1,
        )
        val parallel = EpubParser.parseContent(
            file = epub,
            imagesDir = parDir,
            limits = ReaderResourceLimits.DEFAULT,
            maxParallelism = 4,
        )

        assertTrue("Expected >= 20 logical chapters after TOC splits and merges", sequential.chapters.size >= 20)
        assertEquals(2, sequential.fonts.size)
        assertTrue(sequential.notes.size >= 40)
        assertTrue(sequential.epubSourceRanges.isNotEmpty())
        assertBookContentBitParity(sequential, seqDir, parallel, parDir)

        // Verify progressive onPreview publishes the exact same prefix and final result in parallel mode.
        val previewSeqDir = tempFolder.newFolder("preview-seq-images")
        val previewParDir = tempFolder.newFolder("preview-par-images")
        var seqPreview: BookContent? = null
        var parPreview: BookContent? = null
        var parPreviewCount = 0

        val seqWithPreview = EpubParser.parseContent(
            file = epub,
            imagesDir = previewSeqDir,
            limits = ReaderResourceLimits.DEFAULT,
            onPreview = { seqPreview = it },
            previewElementIndex = 0,
            maxParallelism = 1,
        )
        val parWithPreview = EpubParser.parseContent(
            file = epub,
            imagesDir = previewParDir,
            limits = ReaderResourceLimits.DEFAULT,
            onPreview = {
                parPreviewCount++
                parPreview = it
            },
            previewElementIndex = 0,
            maxParallelism = 4,
        )

        assertEquals(1, parPreviewCount)
        assertBookContentBitParity(requireNotNull(seqPreview), previewSeqDir, requireNotNull(parPreview), previewParDir)
        assertBookContentBitParity(seqWithPreview, previewSeqDir, parWithPreview, previewParDir)
    }

    @Test
    fun `bounded-batch parallel epub parsing falls back cleanly when css or html expansion budgets trigger`() {
        val epub = tempFolder.newFile("parallel-budget-24.epub")
        buildMultiChapterParallelParityEpub(epub, chapterCount = 24)

        val cssLimitedLimits = ReaderResourceLimits.DEFAULT.copy(maxEpubCssExpandedSheets = 5)
        val cssSeqDir = tempFolder.newFolder("css-limit-seq")
        val cssParDir = tempFolder.newFolder("css-limit-par")
        val cssSeq = EpubParser.parseContent(epub, cssSeqDir, cssLimitedLimits, maxParallelism = 1)
        val cssPar = EpubParser.parseContent(epub, cssParDir, cssLimitedLimits, maxParallelism = 4)
        assertTrue(cssSeq.epubSourceRanges.isEmpty())
        assertBookContentBitParity(cssSeq, cssSeqDir, cssPar, cssParDir)

        val htmlLimitedLimits = ReaderResourceLimits.DEFAULT.copy(maxHtmlGeneratedTotalChars = 30L)
        val htmlSeqDir = tempFolder.newFolder("html-limit-seq")
        val htmlParDir = tempFolder.newFolder("html-limit-par")
        val htmlSeq = EpubParser.parseContent(epub, htmlSeqDir, htmlLimitedLimits, maxParallelism = 1)
        val htmlPar = EpubParser.parseContent(epub, htmlParDir, htmlLimitedLimits, maxParallelism = 4)
        assertTrue(htmlSeq.epubSourceRanges.isEmpty())
        assertBookContentBitParity(htmlSeq, htmlSeqDir, htmlPar, htmlParDir)

        val readBudgetLimits = ReaderResourceLimits.DEFAULT.copy(maxArchiveReadBytes = 12_000L)
        val seqReadError = runCatching {
            EpubParser.parseContent(epub, tempFolder.newFolder("read-limit-seq"), readBudgetLimits, maxParallelism = 1)
        }.exceptionOrNull()
        val parReadError = runCatching {
            EpubParser.parseContent(epub, tempFolder.newFolder("read-limit-par"), readBudgetLimits, maxParallelism = 4)
        }.exceptionOrNull()
        assertTrue(seqReadError is ResourceLimitException && seqReadError.kind == ResourceLimitKind.ACTUAL_AGGREGATE)
        assertTrue(parReadError is ResourceLimitException && parReadError.kind == ResourceLimitKind.ACTUAL_AGGREGATE)
    }

    @Test
    fun `sliding-window parallel epub parsing handles skewed giant chapter with bit-for-bit parity`() {
        val epub = tempFolder.newFile("parallel-skewed-21.epub")
        val chapterCount = 21
        val giantChapterIndex = 4
        val fakeFont = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte()) +
            ByteArray(32) { it.toByte() }
        val sharedDivider = ByteArray(16) { (it + 3).toByte() }

        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }

            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            val manifestItems = buildString {
                appendLine("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
                appendLine("""<item id="css" href="styles/main.css" media-type="text/css"/>""")
                appendLine("""<item id="font1" href="fonts/book.otf" media-type="application/font-sfnt"/>""")
                appendLine("""<item id="divimg" href="images/divider.png" media-type="image/png"/>""")
                for (i in 1..chapterCount) {
                    appendLine("""<item id="ch$i" href="text/ch$i.xhtml" media-type="application/xhtml+xml"/>""")
                }
            }
            val spineRefs = buildString {
                for (i in 1..chapterCount) {
                    appendLine("""<itemref idref="ch$i"/>""")
                }
            }
            entry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:skewed-sliding-window-parity</dc:identifier>
                    <dc:title>Skewed Sliding Window Book</dc:title>
                    <dc:language>he</dc:language>
                  </metadata>
                  <manifest>$manifestItems</manifest>
                  <spine toc="ncx">$spineRefs</spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            val navPoints = buildString {
                for (i in 1..chapterCount) {
                    appendLine(
                        """<navPoint id="np$i"><navLabel><text>Book $i</text></navLabel><content src="text/ch$i.xhtml"/></navPoint>""",
                    )
                }
            }
            entry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>$navPoints</navMap>
                </ncx>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/styles/main.css",
                """
                @font-face { font-family: "Ezra"; src: url(../fonts/book.otf); }
                .verse { text-align: right; }
                .vnum { color: #556677; font-size: 0.8em; }
                .both { text-decoration: underline line-through; }
                """.trimIndent().toByteArray(),
            )
            entry("OEBPS/fonts/book.otf", fakeFont)
            entry("OEBPS/images/divider.png", sharedDivider)

            for (i in 1..chapterCount) {
                val paragraphCount = if (i == giantChapterIndex) 500 else 3
                val paragraphs = buildString {
                    for (p in 1..paragraphCount) {
                        appendLine(
                            """<p id="v$i-$p" class="verse"><sup class="vnum">$p</sup><span><span>שלום</span> <a href="#fn$i"><span>עולם</span></a> <span class="both">פסוק</span> $i-$p</span></p>""",
                        )
                    }
                }
                entry(
                    "OEBPS/text/ch$i.xhtml",
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" dir="rtl" lang="he">
                    <head>
                      <title>Book $i</title>
                      <link rel="stylesheet" type="text/css" href="../styles/main.css"/>
                    </head>
                    <body>
                      <h1>פרק $i</h1>
                      $paragraphs
                      <p><img src="../images/divider.png" alt="divider"/></p>
                      <aside id="fn$i" epub:type="footnote"><p>Note for chapter $i.</p></aside>
                    </body>
                    </html>
                    """.trimIndent().toByteArray(),
                )
            }
        }

        val seqDir = tempFolder.newFolder("skewed-seq")
        val par2Dir = tempFolder.newFolder("skewed-par2")
        val par4Dir = tempFolder.newFolder("skewed-par4")

        val sequential = EpubParser.parseContent(epub, seqDir, ReaderResourceLimits.DEFAULT, maxParallelism = 1)
        val parallel2 = EpubParser.parseContent(epub, par2Dir, ReaderResourceLimits.DEFAULT, maxParallelism = 2)
        val parallel4 = EpubParser.parseContent(epub, par4Dir, ReaderResourceLimits.DEFAULT, maxParallelism = 4)

        assertEquals(chapterCount, sequential.chapters.size)
        assertTrue(sequential.chapters[giantChapterIndex - 1].elements.size >= 500)
        assertEquals(1, sequential.fonts.size)
        assertBookContentBitParity(sequential, seqDir, parallel2, par2Dir)
        assertBookContentBitParity(sequential, seqDir, parallel4, par4Dir)
    }

    @Test
    fun `native rust epub pipeline matches kotlin oracle bit-for-bit across reflow corpus`() {
        val epub = tempFolder.newFile("native-reflow-24.epub")
        buildMultiChapterParallelParityEpub(epub, chapterCount = 24, includeInlineSvg = false)

        val probeDir = tempFolder.newFolder("native-probe-images")
        val nativeProbe = RustEpubPackage.parseEpubContent(epub, probeDir, ReaderResourceLimits.DEFAULT)
        assertTrue(
            "Expected reflow corpus to execute natively in Rust, got $nativeProbe",
            nativeProbe is RustEpubPackage.NativeContentResult.Ready,
        )

        val seqDir = tempFolder.newFolder("native-seq-images")
        val parDir = tempFolder.newFolder("native-par-images")
        val oracle = EpubParser.parseContent(
            file = epub,
            imagesDir = seqDir,
            limits = ReaderResourceLimits.DEFAULT,
            maxParallelism = 1,
        )
        val nativeContent = EpubParser.parseContent(
            file = epub,
            imagesDir = parDir,
            limits = ReaderResourceLimits.DEFAULT,
            maxParallelism = 4,
        )

        assertTrue("Expected >= 20 logical chapters after TOC splits and merges", oracle.chapters.size >= 20)
        assertEquals(2, oracle.fonts.size)
        assertTrue(oracle.notes.size >= 40)
        assertTrue(oracle.epubSourceRanges.isNotEmpty())
        assertBookContentBitParity(oracle, seqDir, nativeContent, parDir)
    }

    @Test
    fun `native rust epub pipeline handles utf16 unicode lists blockquotes and idpf obfuscated fonts`() {
        val epub = tempFolder.newFile("native-rich-idpf.epub")
        val uniqueId = "urn:uuid:12345678-90ab-cdef-1234-567890abcdef"
        val rawFont = ByteArray(1200) { i -> (i and 0xff).toByte() }.apply {
            this[0] = 'O'.code.toByte(); this[1] = 'T'.code.toByte()
            this[2] = 'T'.code.toByte(); this[3] = 'O'.code.toByte()
        }
        val mangledFont = FontObfuscation.deobfuscate(
            rawFont,
            FontObfuscation.idpfKey(uniqueId),
            FontObfuscation.IDPF_PREFIX,
        )
        val imgBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 42, 43, 44, 45)

        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            entry(
                "META-INF/encryption.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="${FontObfuscation.IDPF_ALGORITHM}"/>
                    <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/obf.otf"/></enc:CipherData>
                  </enc:EncryptedData>
                </encryption>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/package.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">$uniqueId</dc:identifier>
                    <dc:title>Rich Native EPUB</dc:title>
                    <dc:language>ru</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="css" href="styles/book.css" media-type="text/css"/>
                    <item id="font" href="fonts/obf.otf" media-type="font/otf"/>
                    <item id="pic" href="images/hero.png" media-type="image/png"/>
                    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine page-progression-direction="ltr">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/nav.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body>
                  <nav epub:type="toc"><ol>
                    <li><span>Часть 1. Начало</span><ol>
                      <li><a href="text/c1.xhtml">Глава 1</a></li>
                      <li><a href="text/c2.xhtml">Глава 2</a></li>
                    </ol></li>
                  </ol></nav>
                </body>
                </html>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/styles/book.css",
                """
                @font-face { font-family: "ObfFont"; src: url(../fonts/obf.otf); font-weight: 400; }
                body { font-family: "ObfFont", serif; }
                .box { margin: 1em 0; padding: 0.5em 1em; border-left: 2px solid #224466; background-color: #eef2f5; }
                .accent { color: #aa2233; font-style: italic; }
                """.trimIndent().toByteArray(),
            )
            entry("OEBPS/fonts/obf.otf", mangledFont)
            entry("OEBPS/images/hero.png", imgBytes)
            entry(
                "OEBPS/text/c1.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ru">
                <head><link rel="stylesheet" type="text/css" href="../styles/book.css"/></head>
                <body>
                  <h1>Глава 1 🐸</h1>
                  <p>Привет <strong>мир</strong> 🚀 и <em class="accent">курсив</em>, <code>код</code>, <s>зачёркнутый</s>, <a href="c2.xhtml#target">ссылка</a> и <a href="#fn1" epub:type="noteref">[1]</a>.</p>
                  <div class="box"><p>Цитата в рамке с <b>жирным</b> текстом.</p></div>
                  <blockquote><p>Блок цитаты.</p></blockquote>
                  <ul><li>Пункт один</li><li>Пункт два</li></ul>
                  <ol><li>Первый</li><li>Второй</li></ol>
                  <hr/>
                  <p><img src="../images/hero.png" alt="Герой"/></p>
                  <aside id="fn1" epub:type="footnote"><p>Сноска к первой главе.</p></aside>
                </body>
                </html>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/text/c2.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="he">
                <head><link rel="stylesheet" type="text/css" href="../styles/book.css"/></head>
                <body>
                  <h2 id="target">פרק שני</h2>
                  <p dir="rtl">שלום <strong>עולם</strong> 🌍 וברוכים הבאים.</p>
                </body>
                </html>
                """.trimIndent().toByteArray(),
            )
        }

        val probeDir = tempFolder.newFolder("rich-probe")
        val nativeProbe = RustEpubPackage.parseEpubContent(epub, probeDir, ReaderResourceLimits.DEFAULT)
        assertTrue(
            "Expected rich reflow EPUB to execute natively in Rust, got $nativeProbe",
            nativeProbe is RustEpubPackage.NativeContentResult.Ready,
        )

        val seqDir = tempFolder.newFolder("rich-seq")
        val parDir = tempFolder.newFolder("rich-par")
        val oracle = EpubParser.parseContent(epub, seqDir, ReaderResourceLimits.DEFAULT, maxParallelism = 1)
        val nativeContent = EpubParser.parseContent(epub, parDir, ReaderResourceLimits.DEFAULT, maxParallelism = 4)

        assertEquals(1, nativeContent.fonts.size)
        assertEquals("obffont", nativeContent.fonts.single().family)
        assertBookContentBitParity(oracle, seqDir, nativeContent, parDir)
    }

    @Test
    fun `native rust writeEpubFrb and saveEpubDirectly match ChunkedContentCache save bit-for-bit`() {
        val epub = tempFolder.newFile("direct-frb-24.epub")
        buildMultiChapterParallelParityEpub(epub, chapterCount = 24, includeInlineSvg = false)

        val cacheRoot = tempFolder.newFolder("frb-cache-root")
        val saveCache = ChunkedContentCache(File(cacheRoot, "save-cache"))
        val directCache = ChunkedContentCache(File(cacheRoot, "direct-cache"))
        val saveImgDir = tempFolder.newFolder("save-images")
        val directImgDir = tempFolder.newFolder("direct-images")

        val expectedContent = EpubParser.parseContent(
            file = epub,
            imagesDir = saveImgDir,
            limits = ReaderResourceLimits.DEFAULT,
            maxParallelism = 1,
        )
        assertTrue(saveCache.save("book-save", epub, expectedContent, complete = true))
        assertTrue(
            "saveEpubDirectly should succeed natively in Rust",
            directCache.saveEpubDirectly("book-direct", epub, directImgDir, ReaderResourceLimits.DEFAULT),
        )

        val loadedFromSave = requireNotNull(saveCache.load("book-save", epub))
        val loadedFromDirect = requireNotNull(directCache.load("book-direct", epub))
        assertBookContentBitParity(loadedFromSave, saveImgDir, loadedFromDirect, directImgDir)

        // Verify durable source index (index.frb) was published alongside content.frb.
        val sourceIndex = directCache.loadEpubSourceIndex("book-direct", epub)
        assertNotNull("saveEpubDirectly should also publish durable index.frb", sourceIndex)
        assertEquals(expectedContent.epubSourceRanges, sourceIndex!!.epubSourceRanges)
        assertEquals(expectedContent.chapters.size, sourceIndex.chapters.size)
        assertEquals(expectedContent.totalElements, sourceIndex.totalElements)

        // Verify indexed session opens and reads chapters & globals cleanly.
        val session = requireNotNull(directCache.openIndexed("book-direct", epub))
        session.use {
            assertEquals(expectedContent.chapters.size, it.index.chapters.size)
            assertEquals(expectedContent.totalElements, it.index.totalElements)
            assertNotNull(it.readGlobals())
            assertNotNull(it.readChapter(0))
            assertNotNull(it.readChapter(it.index.chapters.lastIndex))
        }
    }

    @Test
    fun `native rust epub pipeline handles inline svg natively and enforces resource limits`() {
        val svgEpub = tempFolder.newFile("complex-svg.epub")
        buildMultiChapterParallelParityEpub(svgEpub, chapterCount = 8, includeInlineSvg = true)
        val probe = RustEpubPackage.parseEpubContent(
            svgEpub,
            tempFolder.newFolder("complex-probe"),
            ReaderResourceLimits.DEFAULT,
        )
        assertTrue(
            "Inline SVG chapter should now execute natively in Rust, got $probe",
            probe is RustEpubPackage.NativeContentResult.Ready,
        )
        val seqDir = tempFolder.newFolder("complex-seq-img")
        val parDir = tempFolder.newFolder("complex-par-img")
        val oracle = EpubParser.parseContent(svgEpub, seqDir, ReaderResourceLimits.DEFAULT, maxParallelism = 1)
        val nativeContent = EpubParser.parseContent(svgEpub, parDir, ReaderResourceLimits.DEFAULT, maxParallelism = 4)
        assertBookContentBitParity(oracle, seqDir, nativeContent, parDir)

        val directFrb = tempFolder.newFile("inline-svg-target.frb").apply { delete() }
        val directResult = RustEpubPackage.writeEpubFrbWithReason(
            sourceFile = svgEpub,
            imagesDir = tempFolder.newFolder("complex-direct-img"),
            destFrbFile = directFrb,
            sourceMetadata = ByteArray(16),
            limits = ReaderResourceLimits.DEFAULT,
        )
        assertTrue(
            "Inline SVG EPUB should write FRB natively in Rust, got $directResult",
            directResult is RustEpubPackage.NativeWriteResult.Written,
        )
        assertTrue(directFrb.isFile)

        // ResourceLimitException must propagate directly from writeEpubFrb on entry-count violations.
        val strictLimits = ReaderResourceLimits.DEFAULT.copy(maxArchiveEntries = 2)
        val limitError = runCatching {
            EpubParser.writeEpubFrb(
                sourceFile = svgEpub,
                imagesDir = tempFolder.newFolder("limit-img"),
                destFrbFile = tempFolder.newFile("limit-target.frb").apply { delete() },
                sourceMetadata = ByteArray(16),
                limits = strictLimits,
            )
        }.exceptionOrNull()
        assertTrue(
            "Expected ResourceLimitException(ENTRY_COUNT), got $limitError",
            limitError is ResourceLimitException && limitError.kind == ResourceLimitKind.ENTRY_COUNT,
        )
    }

    @Test
    fun `native rust epub pipeline decodes woff2 fonts via jvm callback with bit-for-bit parity`() {
        val epub = tempFolder.newFile("native-woff2.epub")
        val woff2 = Woff2TestFonts.buildWoff2(
            Woff2TestFonts.minimalTtfTables().map { (tag, data) -> Woff2TestFonts.Spec(tag, data) },
        )
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="uid">
                  <metadata><dc:title>Native Woff2</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="font" href="fonts/book.woff2" media-type="font/woff2"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            entry(
                "OEBPS/style.css",
                """@font-face { font-family: "NativeWoff2"; src: url(fonts/book.woff2); } p { font-family: "NativeWoff2"; }""".toByteArray(),
            )
            entry(
                "OEBPS/ch1.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="style.css"/></head><body><p>Rendered with WOFF2.</p></body></html>""".toByteArray(),
            )
            entry("OEBPS/fonts/book.woff2", woff2)
        }

        val probeDir = tempFolder.newFolder("native-woff2-probe")
        val probe = RustEpubPackage.parseEpubContent(epub, probeDir, ReaderResourceLimits.DEFAULT)
        assertTrue(
            "Expected WOFF2 EPUB to execute natively in Rust, got $probe",
            probe is RustEpubPackage.NativeContentResult.Ready,
        )

        val seqDir = tempFolder.newFolder("native-woff2-seq")
        val parDir = tempFolder.newFolder("native-woff2-par")
        val oracle = EpubParser.parseContent(epub, seqDir, ReaderResourceLimits.DEFAULT, maxParallelism = 1)
        val nativeContent = EpubParser.parseContent(epub, parDir, ReaderResourceLimits.DEFAULT, maxParallelism = 4)
        assertEquals(1, nativeContent.fonts.size)
        val stored = File(nativeContent.fonts.single().path).readBytes()
        assertEquals(0x00, stored[0].toInt())
        assertEquals(0x01, stored[1].toInt())
        assertEquals(0x00, stored[2].toInt())
        assertEquals(0x00, stored[3].toInt())
        assertBookContentBitParity(oracle, seqDir, nativeContent, parDir)

        val cache = ChunkedContentCache(tempFolder.newFolder("native-woff2-cache"))
        val directImgDir = tempFolder.newFolder("native-woff2-direct-img")
        val writeResult = cache.saveEpubDirectlyWithReason("woff2-book", epub, directImgDir)
        assertTrue(
            "Expected direct streaming FRB write to succeed on WOFF2 EPUB, got $writeResult",
            writeResult is RustEpubPackage.NativeWriteResult.Written,
        )
        val loaded = requireNotNull(cache.load("woff2-book", epub))
        assertBookContentBitParity(oracle, seqDir, loaded, directImgDir)
    }

    @Test
    fun `native rust epub pipeline handles standalone svg and dtbook spine items with bit-for-bit parity`() {
        val epub = tempFolder.newFile("native-svg-dtbook.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent(),
            )
            entry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="uid">
                  <metadata><dc:title>SVG and DTBook</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="css" href="book.css" media-type="text/css"/>
                    <item id="svg1" href="plate1.svg" media-type="image/svg+xml"/>
                    <item id="svg2" href="plate2.svg" media-type="image/svg+xml"/>
                    <item id="svglink" href="linked.svg" media-type="image/svg+xml"/>
                    <item id="dtb" href="chapter.xml" media-type="application/x-dtbook+xml"/>
                    <item id="ch" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="svg1"/>
                    <itemref idref="svg2"/>
                    <itemref idref="dtb"/>
                    <itemref idref="ch"/>
                    <itemref idref="svglink" linear="no"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            entry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="p1"><navLabel><text>Cover Plate</text></navLabel><content src="plate1.svg"/></navPoint>
                    <navPoint id="p2"><navLabel><text>DTBook Chapter</text></navLabel><content src="chapter.xml#sec1"/></navPoint>
                    <navPoint id="p3"><navLabel><text>HTML Chapter</text></navLabel><content src="ch1.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """.trimIndent(),
            )
            entry("OEBPS/book.css", ".lead { text-align: center; }")
            entry(
                "OEBPS/plate1.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" aria-label="  Front   Plate "><title>Ignored</title><rect width="10" height="10"/></svg>""",
            )
            entry(
                "OEBPS/plate2.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><title>  Second   Illustration </title><circle cx="10" cy="10" r="8"/></svg>""",
            )
            entry(
                "OEBPS/linked.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" aria-label="Appendix Map"><rect width="5" height="5"/></svg>""",
            )
            entry(
                "OEBPS/chapter.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/" version="2005-2">
                  <head><link rel="stylesheet" type="text/css" href="book.css"/></head>
                  <book><bodymatter><level1 id="sec1">
                    <levelhd>DTBook <em>Heading</em></levelhd>
                    <p class="lead">Paragraph inside DTBook.</p>
                    <poem><line>Poetic line.</line></poem>
                  </level1></bodymatter></book>
                </dtbook>
                """.trimIndent(),
            )
            entry(
                "OEBPS/ch1.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>After DTBook</h1><p>Final <a href="linked.svg">map</a>.</p></body></html>""",
            )
        }

        val probeDir = tempFolder.newFolder("svg-dtb-probe")
        val probe = RustEpubPackage.parseEpubContent(epub, probeDir, ReaderResourceLimits.DEFAULT)
        assertTrue(
            "Expected standalone SVG + DTBook EPUB to execute natively in Rust, got $probe",
            probe is RustEpubPackage.NativeContentResult.Ready,
        )

        val seqDir = tempFolder.newFolder("svg-dtb-seq")
        val parDir = tempFolder.newFolder("svg-dtb-par")
        val oracle = EpubParser.parseContent(epub, seqDir, ReaderResourceLimits.DEFAULT, maxParallelism = 1)
        val nativeContent = EpubParser.parseContent(epub, parDir, ReaderResourceLimits.DEFAULT, maxParallelism = 4)
        assertBookContentBitParity(oracle, seqDir, nativeContent, parDir)

        val cache = ChunkedContentCache(tempFolder.newFolder("svg-dtb-cache"))
        val directImgDir = tempFolder.newFolder("svg-dtb-direct-img")
        val writeResult = cache.saveEpubDirectlyWithReason("svg-dtb-book", epub, directImgDir)
        assertTrue(
            "Expected direct FRB write to succeed on standalone SVG + DTBook EPUB, got $writeResult",
            writeResult is RustEpubPackage.NativeWriteResult.Written,
        )
        val loaded = requireNotNull(cache.load("svg-dtb-book", epub))
        assertBookContentBitParity(oracle, seqDir, loaded, directImgDir)
    }

    @Test
    fun `writeEpubFrbWithReason and saveEpubDirectlyWithReason report exact fallback reason`() {
        val fixedEpub = tempFolder.newFile("fixed-layout.epub")
        ZipOutputStream(fixedEpub.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                  <metadata>
                    <dc:title>Fixed</dc:title>
                    <meta property="rendition:layout">pre-paginated</meta>
                  </metadata>
                  <manifest><item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="p1"/></spine>
                </package>
                """.trimIndent(),
            )
            entry("p1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Page 1</p></body></html>""")
        }

        val dest = tempFolder.newFile("fixed.frb").apply { delete() }
        val writeRes = RustEpubPackage.writeEpubFrbWithReason(
            sourceFile = fixedEpub,
            imagesDir = tempFolder.newFolder("fixed-img"),
            destFrbFile = dest,
            sourceMetadata = ByteArray(16),
            limits = ReaderResourceLimits.DEFAULT,
        )
        assertTrue(writeRes is RustEpubPackage.NativeWriteResult.Fallback)
        assertEquals("fixed-layout epub", (writeRes as RustEpubPackage.NativeWriteResult.Fallback).reason)

        val cache = ChunkedContentCache(tempFolder.newFolder("fixed-cache"))
        val cacheRes = cache.saveEpubDirectlyWithReason("fixed-book", fixedEpub, tempFolder.newFolder("fixed-cache-img"))
        assertTrue(cacheRes is RustEpubPackage.NativeWriteResult.Fallback)
        assertEquals("fixed-layout epub", (cacheRes as RustEpubPackage.NativeWriteResult.Fallback).reason)
    }
}
