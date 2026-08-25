package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
