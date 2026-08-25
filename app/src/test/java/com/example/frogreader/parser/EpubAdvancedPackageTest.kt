package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubAdvancedPackageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun epub(
        name: String,
        opf: String,
        entries: Map<String, String>,
    ): File {
        val file = tempFolder.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String, stored: Boolean = false) {
                val bytes = text.toByteArray()
                val zipEntry = ZipEntry(path)
                if (stored) {
                    zipEntry.method = ZipEntry.STORED
                    zipEntry.size = bytes.size.toLong()
                    zipEntry.compressedSize = bytes.size.toLong()
                    zipEntry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(zipEntry)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip", stored = true)
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry("OEBPS/content.opf", opf)
            entries.forEach { (path, text) -> entry("OEBPS/$path", text) }
        }
        return file
    }

    @Test
    fun `reflowable epub retains declared rtl page progression`() {
        val file = epub(
            "rtl-progression.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>RTL</dc:title><dc:language>ar</dc:language></metadata><manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine page-progression-direction="rtl"><itemref idref="ch"/></spine></package>""",
            mapOf(
                "ch.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" lang="ar" dir="rtl"><body><p>مرحبا بالعالم</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())

        assertEquals(PageProgression.RTL, content.pageProgression)
        assertEquals("مرحبا بالعالم", content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single().text.text)
    }

    @Test
    fun `EPUB 2 DTBook spine keeps hierarchy resources and navigation`() {
        val file = epub(
            "dtbook.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="bookid"><metadata><dc:title>DTBook</dc:title><dc:identifier id="bookid">urn:uuid:dtbook-test</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="chapter" href="chapter.xml" media-type="application/x-dtbook+xml"/><item id="css" href="book.css" media-type="text/css"/><item id="figure" href="figure.svg" media-type="image/svg+xml"/></manifest><spine toc="ncx"><itemref idref="chapter"/></spine></package>""",
            mapOf(
                "toc.ncx" to ncx("DTBook chapter", "chapter.xml#chapter"),
                "book.css" to ".lead { text-align: center; }",
                "figure.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"><rect width="20" height="10"/></svg>""",
                "chapter.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/" version="2005-2">
                      <head><link rel="stylesheet" type="text/css" href="book.css"/></head>
                      <book><bodymatter><level1 id="chapter">
                        <levelhd>DTBook <em>heading</em></levelhd>
                        <p class="lead"><a href="#target">Jump</a></p>
                        <list type="ol" enum="a"><li>Item</li></list>
                        <poem><line>Verse</line></poem>
                        <imggroup><img src="figure.svg" alt="Diagram"/></imggroup>
                        <table><tr><th>Head</th><td id="target">Cell</td></tr></table>
                      </level1></bodymatter></book>
                    </dtbook>
                """.trimIndent(),
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        val chapter = content.chapters.single()
        assertEquals("DTBook chapter", chapter.title)
        assertTrue(chapter.elements.any {
            it is ContentElement.Heading && it.level == 1 && it.text == "DTBook heading"
        })
        assertTrue(chapter.elements.any {
            it is ContentElement.Paragraph && it.text.text.startsWith("a. Item")
        })
        assertTrue(chapter.elements.any {
            it is ContentElement.Paragraph &&
                it.text.text == "Verse" && it.style == ParagraphStyle.QUOTE
        })
        val lead = chapter.elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text == "Jump" }
        assertEquals(BlockAlign.CENTER, lead.block?.align)
        assertTrue(chapter.elements.any {
            it is ContentElement.Image && it.altText == "Diagram" && File(it.path).exists()
        })
        assertTrue(chapter.elements.any { it is ContentElement.Table })
        assertTrue("OEBPS/chapter.xml#target" in content.linkTargets)
    }

    @Test
    fun `local css imports resolve from the importing stylesheet`() {
        val file = epub(
            "imports.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Imports</dc:title></metadata><manifest><item id="ch" href="text/ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            mapOf(
                "text/ch.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="../styles/main.css"/></head><body><p class="imported">Imported style</p></body></html>""",
                "styles/main.css" to """
                    @import url("nested/theme.css") screen;
                    @import "not-print.css" not print;
                    @import "print.css" print;
                    @import "speech.css" speech;
                    @import "not-screen.css" not screen;
                    /* @import "commented.css"; */
                    p { font-style: italic; }
                """.trimIndent(),
                "styles/nested/theme.css" to ".imported { text-align: center; }",
                "styles/not-print.css" to ".imported { font-size: 150%; }",
                "styles/print.css" to ".imported { font-size: 250%; }",
                "styles/speech.css" to ".imported { text-align: right; }",
                "styles/not-screen.css" to ".imported { text-align: right; }",
                "styles/commented.css" to ".imported { text-align: right; }",
            ),
        )

        val paragraph = EpubParser.parseContent(file, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        val block = requireNotNull(paragraph.block)
        assertEquals(BlockAlign.CENTER, block.align)
        assertEquals(1.5f, requireNotNull(block.fontScale), 0.001f)
        assertEquals(true, block.italic)
    }

    @Test
    fun `spine toc attribute chooses the declared ncx`() {
        val file = epub(
            "declared-ncx.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0"><metadata><dc:title>NCX</dc:title></metadata><manifest><item id="wrong" href="wrong.ncx" media-type="application/x-dtbncx+xml"/><item id="chosen" href="chosen.ncx" media-type="application/x-dtbncx+xml"/><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine toc="chosen"><itemref idref="ch"/></spine></package>""",
            mapOf(
                "wrong.ncx" to ncx("Wrong", "ch.xhtml"),
                "chosen.ncx" to ncx("Chosen", "ch.xhtml"),
                "ch.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Text</p></body></html>""",
            ),
        )

        assertEquals(
            "Chosen",
            EpubParser.parseContent(file, tempFolder.newFolder()).chapters.single().title,
        )
    }

    @Test
    fun `unsupported spine resource traverses its manifest fallback chain`() {
        val file = epub(
            "fallback.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Fallback</dc:title></metadata><manifest><item id="foreign" href="chapter.custom" media-type="application/x-example" fallback="second"/><item id="second" href="chapter.other" media-type="application/x-other" fallback="html"/><item id="html" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="foreign"/></spine></package>""",
            mapOf(
                "chapter.custom" to "unsupported",
                "chapter.other" to "also unsupported",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Fallback text</p></body></html>""",
            ),
        )

        val paragraph = EpubParser.parseContent(file, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("Fallback text", paragraph.text.text)
    }

    @Test
    fun `literal plus in an epub url remains part of the zip path`() {
        val file = epub(
            "plus-path.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Plus</dc:title></metadata><manifest><item id="ch" href="text/a+b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            mapOf(
                "text/a+b.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Plus path</p></body></html>""",
            ),
        )

        val text = EpubParser.parseContent(file, tempFolder.newFolder()).chapters.single()
            .elements.filterIsInstance<ContentElement.Paragraph>().single().text.text
        assertEquals("Plus path", text)
    }

    @Test
    fun `epub noteref opens a note while an ordinary fragment link navigates`() {
        val file = epub(
            "semantic-links.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Links</dc:title></metadata><manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            mapOf(
                "ch.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><p><a href="#section">section</a> <a epub:type="noteref" href="#note"><sup>1</sup></a></p><h2 id="section">Section</h2><aside epub:type="footnote"><p id="note">Note text</p></aside></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        val first = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().first()
        val section = first.text.getStringAnnotations(LINK_TAG, 0, first.text.length).single()
        val note = first.text.getStringAnnotations(FOOTNOTE_TAG, 0, first.text.length).single()
        assertEquals("OEBPS/ch.xhtml#section", section.item)
        assertEquals("OEBPS/ch.xhtml#note", note.item)
        assertTrue(section.item in content.linkTargets)
        assertEquals(setOf(note.item), content.notes.keys)
        assertEquals("Note text", content.notes.getValue(note.item).text)
    }

    @Test
    fun `fb2epub bracketed ch2 reference is a note without promoting cross references`() {
        val file = epub(
            "fb2epub-notes.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0"><metadata><dc:title>Legacy notes</dc:title><meta name="FB2EPUB.version" content="0.5.0"/></metadata><manifest><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="note" href="ch2-50.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="main"/><itemref idref="note"/></spine></package>""",
            mapOf(
                "main.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p><a href="ch2-50.xhtml#id325">[50]</a> <a href="#section">[51]</a> <a href="ch2-50.xhtml#id325">citation</a></p><h2 id="section">Section</h2></body></html>""",
                "ch2-50.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><span id="id325"><p>Legacy note text</p></span><p id="backlink"><a href="main.xhtml#section">back</a></p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        val paragraphs = content.chapters.flatMap { chapter ->
            chapter.elements.filterIsInstance<ContentElement.Paragraph>()
        }
        val footnotes = paragraphs.flatMap {
            it.text.getStringAnnotations(FOOTNOTE_TAG, 0, it.text.length)
        }
        val links = paragraphs.flatMap {
            it.text.getStringAnnotations(LINK_TAG, 0, it.text.length)
        }

        assertEquals(listOf("OEBPS/ch2-50.xhtml#id325"), footnotes.map { it.item })
        assertEquals("Legacy note text", content.notes.getValue(footnotes.single().item).text)
        assertEquals(3, links.size)
        assertTrue(links.count { it.item == "OEBPS/main.xhtml#section" } == 2)
        assertTrue(links.any { it.item == "OEBPS/ch2-50.xhtml#id325" })
    }

    @Test
    fun `standalone svg spine item becomes an image chapter`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><title>Vector alt</title><rect width="200" height="100"/></svg>"""
        val file = epub(
            "svg-spine.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>SVG</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="page" href="page.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="page"/></spine></package>""",
            mapOf(
                "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc other"><ol><li><a href="page.svg">Vector page</a></li></ol></nav></body></html>""",
                "page.svg" to svg,
            ),
        )

        val chapter = EpubParser.parseContent(file, tempFolder.newFolder()).chapters.single()
        assertEquals("Vector page", chapter.title)
        val image = chapter.elements.single() as ContentElement.Image
        assertEquals("Vector alt", image.altText)
        assertTrue(File(image.path).readText().contains("<rect"))
    }

    @Test
    fun `multiple toc fragments in one xhtml become distinct chapters`() {
        val file = epub(
            "fragmented-toc.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Fragments</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ch" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            mapOf(
                "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#one">Part one</a><ol><li><a href="chapter.xhtml#%D0%B4%D0%B2%D0%B0">Part two</a></li></ol></li></ol></nav></body></html>""",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="one">Heading one</h1><p><a href="#%D0%B4%D0%B2%D0%B0">Go two</a></p><p>First body.</p><h2 id="два">Heading two</h2><p>Second body.</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals(listOf("Part one", "Part two"), content.chapters.map { it.title })
        assertEquals(listOf(0, 1), content.chapters.map { it.depth })
        assertTrue(content.chapters[0].elements.any { it.visibleText() == "First body." })
        assertTrue(content.chapters[0].elements.none { it.visibleText() == "Second body." })
        assertTrue(content.chapters[1].elements.any { it.visibleText() == "Second body." })

        val target = content.linkTargets.getValue("OEBPS/chapter.xhtml#два")
        assertEquals(1, target.first)
        assertEquals("Heading two", content.chapters[target.first].elements[target.second].visibleText())
    }

    @Test
    fun `repeated toc destinations keep every author label and depth`() {
        val file = epub(
            "repeated-toc-target.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Repeated target</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ch" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            mapOf(
                "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#start">Primary label</a><ol><li><a href="chapter.xhtml#start">Alternate label</a></li></ol></li></ol></nav></body></html>""",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="start">Heading</h1><p>Body.</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals(listOf("Primary label"), content.chapters.map { it.title })
        assertEquals(
            listOf("Primary label", "Alternate label"),
            content.navigation.map { it.title },
        )
        assertEquals(listOf(0, 1), content.navigation.map { it.depth })
        val targets = content.navigation.map {
            it.target as BookNavigationTarget.ReadingOrder
        }
        assertTrue(targets.all { it.chapterIndex == 0 && it.elementIndex == 0 })
    }

    @Test
    fun `ncx percent encoded fragments split one xhtml without losing unicode anchors`() {
        val file = epub(
            "fragmented-ncx.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0"><metadata><dc:title>NCX fragments</dc:title></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="ch" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine toc="ncx"><itemref idref="ch"/></spine></package>""",
            mapOf(
                "toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>Один</text></navLabel><content src="chapter.xhtml#one"/></navPoint><navPoint><navLabel><text>Два</text></navLabel><content src="chapter.xhtml#%D0%B4%D0%B2%D0%B0"/></navPoint></navMap></ncx>""",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="one">One</h1><p>A</p><h2 id="два">Two</h2><p>B</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals(listOf("Один", "Два"), content.chapters.map { it.title })
        assertTrue(content.chapters[0].elements.none { it.visibleText() == "B" })
        assertTrue(content.chapters[1].elements.any { it.visibleText() == "B" })
    }

    @Test
    fun `linear no document stays outside reading order but remains a rich link target`() {
        val file = epub(
            "non-linear-link.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Non-linear</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="extra" href="extra.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="main"/><itemref idref="extra" linear="no"/></spine></package>""",
            mapOf(
                "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="main.xhtml#main">Main</a></li><li><a href="extra.xhtml#details">Details</a></li></ol></nav></body></html>""",
                "main.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="main">Main chapter</h1><p><a href="extra.xhtml#details">Open details</a></p></body></html>""",
                "extra.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><style>#details { text-align: right; font-style: italic; }</style></head><body><p><a href="main.xhtml#main">Back to main</a></p><p id="details">Linked details</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals(1, content.chapters.size)
        assertTrue(content.chapters.single().elements.any { it.visibleText() == "Main chapter" })
        assertTrue(content.chapters.single().elements.none { it.visibleText() == "Linked details" })

        val document = content.linkedDocuments.getValue("OEBPS/extra.xhtml")
        assertTrue(document.elements.any { it.visibleText() == "Linked details" })
        val target = content.linkedDocumentTargets.getValue("OEBPS/extra.xhtml#details")
        assertEquals(document.id, target.documentId)
        val paragraph = document.elements[target.elementIndex] as ContentElement.Paragraph
        assertEquals("Linked details", paragraph.text.text)
        val block = requireNotNull(paragraph.block)
        // CSS `right` is physical; logical `end` remains distinct for RTL.
        assertEquals(BlockAlign.RIGHT, block.align)
        assertEquals(true, block.italic)

        val source = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(
            "OEBPS/extra.xhtml#details",
            source.text.getStringAnnotations(LINK_TAG, 0, source.text.length).single().item,
        )
        // Links originating inside the non-linear document can return to the
        // normal reading order without making the extra document sequential.
        assertTrue("OEBPS/main.xhtml#main" in content.linkTargets)

        assertEquals(listOf("Main", "Details"), content.navigation.map { it.title })
        val mainNavigation = content.navigation[0].target as BookNavigationTarget.ReadingOrder
        assertEquals(0, mainNavigation.chapterIndex)
        val linkedNavigation = content.navigation[1].target as BookNavigationTarget.Linked
        assertEquals("OEBPS/extra.xhtml", linkedNavigation.documentId)
        assertEquals(target.elementIndex, linkedNavigation.elementIndex)
    }

    @Test
    fun `noteref can extract its note from a linear no endnotes document`() {
        val file = epub(
            "non-linear-note.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0"><metadata><dc:title>Endnotes</dc:title></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/></manifest><spine toc="ncx"><itemref idref="main"/><itemref idref="notes" linear="NO"/></spine></package>""",
            mapOf(
                "toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>Main</text></navLabel><content src="main.xhtml"/></navPoint><navPoint><navLabel><text>Notes</text></navLabel><content src="notes.xhtml#n1"/></navPoint></navMap></ncx>""",
                "main.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><p>Text<a epub:type="noteref" href="notes.xhtml#n1"><sup>1</sup></a></p></body></html>""",
                "notes.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><aside epub:type="footnote"><p id="n1">A non-linear note.</p></aside></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals(1, content.chapters.size)
        assertEquals("A non-linear note.", content.notes.getValue("OEBPS/notes.xhtml#n1").text)
        assertTrue("OEBPS/notes.xhtml" in content.linkedDocuments)
        val ncxTarget = content.navigation[1].target as BookNavigationTarget.Linked
        assertEquals("OEBPS/notes.xhtml", ncxTarget.documentId)
    }

    @Test
    fun `a spine containing only linear no items is not promoted by manifest fallback`() {
        val file = epub(
            "only-non-linear.epub",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"><metadata><dc:title>Only extra</dc:title></metadata><manifest><item id="extra" href="extra.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="extra" linear="no"/></spine></package>""",
            mapOf(
                "extra.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Not reading order.</p></body></html>""",
            ),
        )

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertTrue(content.chapters.isEmpty())
        assertEquals("Not reading order.", content.linkedDocuments.values.single()
            .elements.single().visibleText())
    }

    private fun ContentElement.visibleText(): String? = when (this) {
        is ContentElement.Paragraph -> text.text
        is ContentElement.Heading -> text
        is ContentElement.Table -> flatText()
        else -> null
    }

    private fun ncx(title: String, target: String): String =
        """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>$title</text></navLabel><content src="$target"/></navPoint></navMap></ncx>"""
}
