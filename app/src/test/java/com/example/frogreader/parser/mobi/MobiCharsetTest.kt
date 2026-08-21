package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.PdbFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class MobiCharsetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun charsetOf(encoding: Int): Charset {
        val record0 = MobiBuilder.record0(
            compression = 1, textLength = 4, textRecords = 1, encoding = encoding,
        )
        val doc = MobiDoc.open(
            MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "cs", listOf(record0, "text".toByteArray())),
        )
        return doc.mobi6.mobi!!.charset
    }

    @Test
    fun `legacy codepages resolve to real charsets`() {
        assertEquals(Charset.forName("windows-1251"), charsetOf(1251))
        assertEquals(Charset.forName("windows-1252"), charsetOf(1252))
        assertEquals(Charset.forName("windows-31j"), charsetOf(932))
        assertEquals(Charset.forName("GBK"), charsetOf(936))
        assertEquals(Charset.forName("Big5"), charsetOf(950))
        assertEquals(Charset.forName("ISO-8859-5"), charsetOf(28595))
        assertEquals(Charsets.UTF_8, charsetOf(65001))
        // Unknown codepage degrades to UTF-8 instead of crashing.
        assertEquals(Charsets.UTF_8, charsetOf(12345))
    }

    @Test
    fun `cp1251 mobi decodes cyrillic text`() {
        val html = "<html><body>" +
            "<p>Привіт, світе! Це книга у windows-1251.</p>" +
            "<p>Ёлка и краткое содержание №5 — всё читается.</p>" +
            "</body></html>"
        val file = MobiBuilder.buildMobi6(
            target = temp.newFile("cp1251.mobi"),
            html = html,
            compress = true,
            encoding = 1251,
        )
        val content = MobiParser.parseContent(file, temp.newFolder())
        val texts = content.chapters.flatMap { chapter ->
            chapter.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        }
        assertTrue(texts.any { it.contains("Привіт, світе! Це книга") })
        assertTrue(texts.any { it.contains("№5 — всё") })
    }
}
