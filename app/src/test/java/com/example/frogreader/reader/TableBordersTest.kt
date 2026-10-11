package com.example.frogreader.reader

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.TableCellBorders
import com.example.frogreader.ui.reader.tableCellBorders
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TableBordersTest {
    @get:Rule val temporary = TemporaryFolder()

    private val html = "<table><tr><td>A</td><td>B</td></tr></table>"

    private fun table(css: String? = null, body: String = html): ContentElement.Table =
        HtmlMapper(resolveImage = { null }, css = css?.let {
            CssResolver(listOf(CssResolver.Sheet(it)))
        }).map(Jsoup.parse(body).body()).filterIsInstance<ContentElement.Table>().single()

    private fun modes(table: ContentElement.Table, publisher: Boolean = true): List<TableCellBorders> =
        table.rows.flatMap { it.cells }.map { tableCellBorders(table, it, publisher) }

    @Test fun `unstyled HTML tables show the reader grid with publisher styles enabled or disabled`() {
        for (css in listOf(null, "", "table { width: 100%; } td { padding: .25em; color: navy; }")) {
            val table = table(css)
            for (publisher in listOf(true, false)) {
                assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID),
                    modes(table, publisher))
            }
        }
    }

    @Test fun `explicit no border zero width and hidden table policies suppress fallback lines`() {
        for (border in listOf("border: none", "border: 0", "border: 0 solid red", "border-style: hidden")) {
            val table = table("table { $border; }")
            assertTrue(border, table.rows.single().cells.all { it.publisherBorderSpecified })
            assertEquals(border, listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
            assertTrue(table.rows.single().cells.all { cell ->
                cell.publisherBox == null || cell.publisherBox.isDefault
            })
            assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID), modes(table, false))
        }
    }

    @Test fun `row and row group no border apply only to their cells`() {
        val table = table("tr.reset, tbody.reset { border: none; }", """
            <table><tbody class='reset'><tr><td>A</td><td>B</td></tr></tbody>
            <tbody><tr class='reset'><td>C</td><td>D</td></tr>
            <tr><td>E</td><td>F</td></tr></tbody></table>
        """.trimIndent())
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER,
            TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER,
            TableCellBorders.READER_GRID, TableCellBorders.READER_GRID), modes(table))
    }

    @Test fun `partial authored cell border retains exactly its painted side without adding a full grid`() {
        val table = table("td:first-child { border-bottom: 2px dashed red; } td:last-child { border: none; }")
        val box = requireNotNull(table.rows.single().cells.first().publisherBox)
        assertNotNull(box.borderBottom)
        assertNull(box.borderTop)
        assertNull(box.borderRight)
        assertNull(box.borderLeft)
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
    }

    @Test fun `authored table frame stays outside cells and does not invent internal lines`() {
        val resolver = CssResolver(listOf(CssResolver.Sheet("table { border: 1px solid blue; }")))
        val mapper = HtmlMapper(resolveImage = { null }, css = resolver)
        val table = mapper.map(Jsoup.parse(html).body()).filterIsInstance<ContentElement.Table>().single()
        val outer = mapper.publisherBoxes.single().style
        assertNotNull(outer.borderLeft)
        assertNotNull(outer.borderRight)
        assertNotNull(outer.borderTop)
        assertNotNull(outer.borderBottom)
        assertTrue(table.rows.single().cells.all { it.publisherBox == null })
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
    }

    @Test fun `legacy zero border suppresses a grid while positive border leaves the default grid`() {
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER),
            modes(table(body = html.replace("<table>", "<table border='0'>"))))
        assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID),
            modes(table(body = html.replace("<table>", "<table border='1'>"))))
    }

    @Test fun `synthetic poetry rows and authored stacked cells do not gain grid lines`() {
        val plain = table()
        val poetry = plain.copy(publisherRowLayout = PublisherRowLayout.SPACE_BETWEEN)
        for (publisher in listOf(true, false)) {
            assertEquals(listOf(TableCellBorders.NONE, TableCellBorders.NONE), modes(poetry, publisher))
        }
        assertEquals(TableCellBorders.NONE, tableCellBorders(plain, plain.rows.single().cells.first(),
            publisherStyles = true, stacked = true))
        val responsive = table("table { border: none; } @media(max-width:400px) {td {display:block;}}")
        assertNotNull(responsive.stackCellsBelowCssPx)
        assertTrue(responsive.rows.single().cells.all { it.narrowStyle!!.publisherBorderSpecified })
    }

    @Test fun `border policy participates in pagination cache content identity`() {
        val plain = table()
        val borderless = table("table { border: none; }")
        fun signature(table: ContentElement.Table) = PaginationCache.contentSignature(listOf(ReaderItem(0, table)))
        assertNotEquals(signature(plain), signature(borderless))
        assertEquals(signature(plain), signature(table()))
    }

    private fun fb2(css: String = "", body: String = html): ContentElement.Table {
        val xml = """
            <FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'>
            <stylesheet type='text/css'><![CDATA[$css]]></stylesheet>
            <description><title-info><book-title>Table</book-title></title-info></description>
            <body><section>$body</section></body>
            </FictionBook>
        """.trimIndent()
        return Fb2Parser.parseContent({ xml.byteInputStream() }, temporary.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Table>().single()
    }

    @Test fun `positive row borders cannot erase the grid when row frames are not retained`() {
        for (selector in listOf("tr", "tbody")) {
            val table = table("$selector { border-bottom: 1px solid red; }")
            assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID), modes(table))
        }
        assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID),
            modes(fb2("tr { border-bottom: 1px solid red; }")))
    }

    @Test fun `FB2 table cell and row can explicitly request no border`() {
        for (selector in listOf("table", "tr", "td")) {
            for (border in listOf("none", "0", "hidden")) {
                val table = fb2("$selector { border: $border; }")
                assertEquals("$selector: $border",
                    listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
                assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID), modes(table, false))
            }
        }
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.READER_GRID),
            modes(fb2(body = "<table><tr><td style='border: none'>A</td><td>B</td></tr></table>")))
    }

    @Test fun `FB2 authored table frame and partial cell borders survive image resolution`() {
        val table = fb2("table { border: 1px solid blue; } td { border-bottom: 2px dashed red; }")
        val frame = requireNotNull(table.publisherBox)
        assertNotNull(frame.borderLeft)
        assertNotNull(frame.borderRight)
        assertNotNull(frame.borderTop)
        assertNotNull(frame.borderBottom)
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
        table.rows.single().cells.forEach { cell ->
            val box = requireNotNull(cell.publisherBox)
            assertNotNull(box.borderBottom)
            assertNull(box.borderTop)
            assertNull(box.borderLeft)
            assertNull(box.borderRight)
        }
        val hash = PaginationCache.contentSignature(listOf(ReaderItem(0, table)))
        assertNotEquals(hash, PaginationCache.contentSignature(listOf(ReaderItem(0,
            table.copy(publisherBox = frame.copy(borderTop = null))))))
    }

    @Test fun `FB2 border shorthand cascade respects alias specificity and important declarations`() {
        val table = fb2("td { border: 1px solid blue; } .td { border-bottom: none; }",
            "<table><tr><td>A</td><td style='border: 0 !important'>B</td></tr></table>")
        val first = requireNotNull(table.rows.single().cells.first().publisherBox)
        assertNull(first.borderBottom)
        assertNotNull(first.borderTop)
        assertNotNull(first.borderLeft)
        assertNull(table.rows.single().cells.last().publisherBox)
        assertEquals(listOf(TableCellBorders.PUBLISHER, TableCellBorders.PUBLISHER), modes(table))
    }

    @Test fun `unstyled FB2 table uses the same fallback grid as HTML`() {
        val xml = """
            <FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'>
            <description><title-info><book-title>Table</book-title></title-info></description>
            <body><section><table><tr><td>A</td><td>B</td></tr></table></section></body>
            </FictionBook>
        """.trimIndent()
        val table = Fb2Parser.parseContent({ xml.byteInputStream() }, temporary.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Table>().single()
        assertEquals(listOf(TableCellBorders.READER_GRID, TableCellBorders.READER_GRID), modes(table))
    }
}
