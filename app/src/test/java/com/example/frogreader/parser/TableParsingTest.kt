package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TableParsingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun mapped(html: String, css: String? = null): List<ContentElement> {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = css?.let { CssResolver(listOf(CssResolver.Sheet(it))) },
        )
        return mapper.map(Jsoup.parse(html).body())
    }

    @Test
    fun `html table becomes a grid with spans and header`() {
        val elements = mapped(
            """
            <table>
              <caption>Сравнение изданий</caption>
              <thead><tr><th>Год</th><th colspan="2">Тираж</th></tr></thead>
              <tbody>
                <tr><td>1984</td><td>Москва</td><td rowspan="2" align="right">100</td></tr>
                <tr><td>1985</td><td>Ленинград</td></tr>
              </tbody>
            </table>
            """.trimIndent(),
        )

        val caption = elements[0] as ContentElement.Paragraph
        assertEquals("Сравнение изданий", caption.text.text)
        assertEquals(BlockAlign.CENTER, caption.block?.align)

        val table = elements[1] as ContentElement.Table
        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0].isHeader)
        assertEquals(2, table.rows[0].cells[1].colSpan)
        assertTrue(table.rows[0].cells[1].header)
        assertEquals(2, table.rows[1].cells[2].rowSpan)
        assertEquals(BlockAlign.END, table.rows[1].cells[2].align)
        assertEquals(2, table.rows[2].cells.size)
        assertEquals("Ленинград", table.rows[2].cells[1].text.text)
    }

    @Test
    fun `single-column layout table degrades to paragraphs`() {
        val elements = mapped(
            "<table><tr><td>Первый блок.</td></tr><tr><td>Второй блок.</td></tr></table>",
        )
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(listOf("Первый блок.", "Второй блок."), paragraphs.map { it.text.text })
        assertTrue(elements.none { it is ContentElement.Table })
    }

    @Test
    fun `nested table flattens into its cell`() {
        val elements = mapped(
            """
            <table>
              <tr><td>Внешняя</td><td><table><tr><td>Внутренняя</td><td>таблица</td></tr></table></td></tr>
              <tr><td>a</td><td>b</td></tr>
            </table>
            """.trimIndent(),
        )
        val table = elements.filterIsInstance<ContentElement.Table>().single()
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0].cells[1].text.text.contains("Внутренняя"))
    }

    @Test
    fun `fb2 table parses rows cells and spans`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>T</book-title><lang>ru</lang></title-info></description>
            <body><section>
              <p>До таблицы.</p>
              <table>
                <tr><th>Имя</th><th>Возраст</th></tr>
                <tr><td colspan="2" align="center">Объединённая</td></tr>
                <tr><td>Роланд</td><td>лет 300</td></tr>
              </table>
              <p>После таблицы.</p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val chapters = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters
        val table = chapters.single().elements
            .filterIsInstance<ContentElement.Table>().single()

        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0].isHeader)
        assertEquals("Имя", table.rows[0].cells[0].text.text)
        assertEquals(2, table.rows[1].cells[0].colSpan)
        assertEquals(BlockAlign.CENTER, table.rows[1].cells[0].align)
        assertEquals("лет 300", table.rows[2].cells[1].text.text)

        val texts = chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        assertTrue("До таблицы." in texts && "После таблицы." in texts)
    }
}
