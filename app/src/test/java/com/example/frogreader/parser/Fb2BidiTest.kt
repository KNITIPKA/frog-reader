package com.example.frogreader.parser

import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.parser.Fb2Parser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Fb2BidiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `xml language supplies fb2 block and inline bidi fallback`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>RTL</book-title><lang>ar</lang></title-info></description>
              <body xml:lang="ar"><section>
                <title><p>عنوان الفصل</p></title>
                <p>فقرة عربية 2026 (ABC).</p>
                <p xml:lang="he">שָׁלוֹם 2026 (ABC).</p>
                <p>رمز <style xml:lang="en">SKU-2026</style> داخل السطر.</p>
                <table xml:lang="he"><tr><td>תא</td><td xml:lang="en">ABC-12</td></tr></table>
              </section></body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent(
            open = { xml.byteInputStream() },
            imagesDir = tempFolder.newFolder(),
        )
        val elements = content.chapters.flatMap { it.elements }
        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        val hebrew = paragraphs.single { it.text.text.startsWith("שָׁ") }
        val mixed = paragraphs.single { "SKU-2026" in it.text.text }
        val table = elements.filterIsInstance<ContentElement.Table>().single()

        assertEquals(BookTextDirection.RTL, heading.block?.direction)
        assertEquals(BookTextDirection.RTL, paragraphs.first().block?.direction)
        assertEquals(BookTextDirection.RTL, hebrew.block?.direction)
        val inline = mixed.text.getStringAnnotations(BIDI_TAG, 0, mixed.text.length).single()
        assertEquals(InlineBidiMode.ISOLATE_LTR.name, inline.item)
        assertEquals("SKU-2026", mixed.text.text.substring(inline.start, inline.end))
        assertEquals(BookTextDirection.RTL, table.block?.direction)
        assertEquals(BookTextDirection.RTL, table.rows.single().cells[0].block?.direction)
        assertEquals(BookTextDirection.LTR, table.rows.single().cells[1].block?.direction)
    }
}
