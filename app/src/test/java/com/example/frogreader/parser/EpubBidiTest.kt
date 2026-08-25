package com.example.frogreader.parser

import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBidiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `epub package retains html and css bidi semantics`() {
        val epub = tempFolder.newFile("rtl.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/package.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">rtl</dc:identifier><dc:title>RTL</dc:title><dc:language>ar</dc:language></metadata><manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>""",
            )
            entry(
                "OPS/ch.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>RTL</title>
                <style>.override{direction:rtl;unicode-bidi:isolate-override}</style></head>
                <body><h4 lang="he" dir="rtl">כותרת</h4>
                <p dir="rtl">اسم <bdi>Frog-42</bdi> <span class="override">ABC 12</span>.</p>
                <ul dir="rtl"><li>عنصر 12</li></ul>
                <table dir="rtl"><tr><td>خلية</td><td dir="ltr">SKU-12</td></tr></table>
                </body></html>
                """.trimIndent(),
            )
        }

        val elements = EpubParser.parseContent(epub, tempFolder.newFolder())
            .chapters.flatMap { it.elements }
        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        val mixed = paragraphs.single { "Frog-42" in it.text.text }
        val list = paragraphs.single { "عنصر" in it.text.text }
        val table = elements.filterIsInstance<ContentElement.Table>().single()

        assertEquals(BookTextDirection.RTL, heading.block?.direction)
        assertEquals(BookTextDirection.RTL, mixed.block?.direction)
        assertEquals(BookTextDirection.RTL, list.block?.direction)
        assertFalse(mixed.text.text.any(::isBidiControl))
        assertEquals(
            setOf(
                InlineBidiMode.ISOLATE_AUTO.name,
                InlineBidiMode.ISOLATE_OVERRIDE_RTL.name,
            ),
            mixed.text.getStringAnnotations(BIDI_TAG, 0, mixed.text.length)
                .map { it.item }.toSet(),
        )
        assertEquals(BookTextDirection.RTL, table.block?.direction)
        assertEquals(BookTextDirection.LTR, table.rows.single().cells[1].block?.direction)
    }

    private fun isBidiControl(char: Char): Boolean = char in setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
