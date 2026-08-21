package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
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
        assertEquals(1.5f, block.fontScale, 0.001f)
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

    private fun ncx(title: String, target: String): String =
        """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>$title</text></navLabel><content src="$target"/></navPoint></navMap></ncx>"""
}
