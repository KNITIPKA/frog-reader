package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubUnicodeRangeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `same style and source retain complementary unicode ranges in CSS order`() {
        val resolver = CssResolver(listOf(CssResolver.Sheet("""
            @font-face { font-family: Book; src:url(book.otf); unicode-range:U+0-7f; }
            @font-face { font-family: Book; src:url(book.otf); unicode-range:u+4??; }
            @font-face { font-family: Book; src:url(book.otf); unicode-range:U+0400-04FF; }
            @font-face { font-family: Other; src:url(other.otf); unicode-range:U+500-400; }
            """)))
        assertEquals(3, resolver.fontFaces.size)
        assertEquals(listOf("U+0-7F", "U+400-4FF", null), resolver.fontFaces.map { it.unicodeRange })
        assertNull(resolver.fontFaces.last().unicodeRange)
    }

    @Test
    fun `EPUB font ranges survive extraction and reuse without collapsing same file declarations`() {
        val epub = temp.newFile("ranges.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun text(name: String, content: String) = entry(name, content.toByteArray())
            text("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""")
            text("OPS/content.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                <metadata><dc:title>Ranges</dc:title><dc:language>uk</dc:language></metadata>
                <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
                <item id="font" href="font.otf" media-type="font/otf"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
                """)
            text("OPS/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="style.css"/></head><body><p>Latin Кирилиця</p></body></html>""")
            text("OPS/style.css", """
                @font-face { font-family: Book; src:url(font.otf); unicode-range:U+0-7F; }
                @font-face { font-family: Book; src:url(font.otf); unicode-range:U+4??; }
                p { font-family:Book; }
                """)
            // The parser only requires a supported sfnt signature; glyph selection is tested separately.
            entry("OPS/font.otf", byteArrayOf(0x4f, 0x54, 0x54, 0x4f, 0, 0, 0, 0))
        }
        val extracted = temp.newFolder("fonts")
        repeat(2) {
            val fonts = EpubParser.parseContent(epub, extracted).fonts
            assertEquals(listOf("U+0-7F", "U+400-4FF"), fonts.map { it.unicodeRange })
            assertEquals(1, fonts.map { it.path }.distinct().size)
        }
    }
}
