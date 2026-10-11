package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperResponsiveTableTest {
    private val dramaCss = """
        @namespace epub "http://www.idpf.org/2007/ops";
        table {margin:1em auto;width:100%;}
        td {padding:.5em;vertical-align:top;}
        td[epub|type~="z3998:persona"] {text-align:right;width:20%;font-variant:all-small-caps;}
        @media(max-width:400px) {
            td, td[epub|type~="z3998:persona"] {display:block;padding:0;text-align:initial;width:100%;}
            td[epub|type~="z3998:persona"] {break-after:avoid;margin-top:1.5em;}
            td:first-child:not([epub|type]) + td {margin-top:1.5em;}
        }
    """.trimIndent()

    private fun mapped(css: String, body: String): ContentElement.Table {
        val resolver = CssResolver(listOf(CssResolver.Sheet(css)))
        val doc = Jsoup.parse("<html xmlns:epub='http://www.idpf.org/2007/ops'><body>$body</body></html>")
        return HtmlMapper(resolveImage = { null }, css = resolver).map(doc.body())
            .filterIsInstance<ContentElement.Table>().single()
    }

    @Test
    fun `drama keeps original cells and stores exact authored narrow overrides`() {
        val table = mapped(
            dramaCss,
            "<table><tr><td></td><td><em>Stage direction</em></td></tr>" +
                "<tr><td epub:type='z3998:persona'>Richard</td><td>Dialogue.</td></tr></table>",
        )
        assertEquals(400f, table.stackCellsBelowCssPx!!, 0.001f)
        assertEquals(2, table.rows.size)
        assertEquals(listOf(2, 2), table.rows.map { it.cells.size })
        assertEquals("  Stage direction\nRichard  Dialogue.", table.flatText())
        val persona = table.rows[1].cells[0]
        assertEquals(BlockAlign.RIGHT, persona.align)
        assertEquals(0.2f, persona.publisherBox!!.widthFrac!!, 0.001f)
        assertEquals(0.5f, persona.publisherBox!!.paddingTopEm, 0.001f)
        val narrow = persona.narrowStyle!!
        assertEquals(BlockAlign.START, narrow.align)
        assertEquals(1f, narrow.publisherBox!!.widthFrac!!, 0.001f)
        assertEquals(0f, narrow.publisherBox!!.paddingTopEm, 0.001f)
        assertEquals(1.5f, narrow.publisherBox!!.marginTopEm, 0.001f)
        assertTrue(narrow.publisherPaddingSpecified)
        assertTrue(narrow.block!!.fontFeatureSettings!!.contains("c2sc"))
        assertEquals(1.5f, table.rows[0].cells[1].narrowStyle!!.publisherBox!!.marginTopEm, 0.001f)
    }

    @Test
    fun `media em breakpoint uses initial CSS pixels independent from reader font`() {
        val table = mapped(
            "td {padding:1em;} @media only screen and (max-width:25em) {td {display:block;padding:0;}}",
            "<table><tr><td>A</td><td>B</td></tr></table>",
        )
        assertEquals(400f, table.stackCellsBelowCssPx!!, 0.001f)
        assertTrue(table.rows.single().cells.all { it.narrowStyle!!.publisherPaddingSpecified })
    }

    @Test
    fun `partial responsive columns and spanning grids retain the standard layout`() {
        val partial = mapped(
            "@media(max-width:400px) {td:first-child {display:block;}}",
            "<table><tr><td>A</td><td>B</td></tr></table>",
        )
        assertNull(partial.stackCellsBelowCssPx)
        assertTrue(partial.rows.single().cells.all { it.narrowStyle == null })
        val spanning = mapped(
            "@media(max-width:400px) {td {display:block;}}",
            "<table><tr><td rowspan='2'>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>",
        )
        assertNull(spanning.stackCellsBelowCssPx)
        assertEquals(2, spanning.rows.first().cells.first().rowSpan)
    }

    @Test
    fun `incompatible multiple narrow variants keep source grid rather than guessing`() {
        val table = mapped(
            "@media(max-width:400px) {td {display:block;}}" +
                "@media(max-width:200px) {td {margin-top:3em;}}",
            "<table><tr><td>A</td><td>B</td></tr></table>",
        )
        assertNull(table.stackCellsBelowCssPx)
        assertEquals("A  B", table.flatText())
    }

    @Test
    fun `media changes to descendant visibility or generated text reject shared text stacking`() {
        val hidden = mapped(
            "@media(max-width:400px) {td {display:block;} td em {display:none;}}",
            "<table><tr><td>A</td><td><em>Visible source</em></td></tr></table>",
        )
        assertNull(hidden.stackCellsBelowCssPx)
        assertEquals("A  Visible source", hidden.flatText())
        val generated = mapped(
            "td em::before {content:'Before: ';} " +
                "@media(max-width:400px) {td {display:block;} td em::before {content:'Narrow: ';}}",
            "<table><tr><td>A</td><td><em>Source</em></td></tr></table>",
        )
        assertNull(generated.stackCellsBelowCssPx)
        assertEquals("A  Before: Source", generated.flatText())
    }

    @Test
    fun `media named family changes reject inherited source font annotations`() {
        val table = mapped(
            "td {font-family:'Wide Face';} " +
                "@media(max-width:400px) {td {display:block;font-family:'Narrow Face';}}",
            "<table><tr><td>A</td><td>B</td></tr></table>",
        )
        assertNull(table.stackCellsBelowCssPx)
        assertTrue(table.rows.single().cells.all { it.narrowStyle == null })
        assertEquals("A  B", table.flatText())
    }

    @Test
    fun `responsive resolver caches clear with the chapter cache`() {
        val resolver = CssResolver(listOf(CssResolver.Sheet(
            "@media(max-width:400px) {.stack {display:block;}}",
        )))
        val cell = Jsoup.parse("<table><tr><td class='stack'>A</td></tr></table>").selectFirst("td")!!
        val narrow = resolver.resolverAtMaxWidth(400f)!!
        assertEquals("block", narrow.computed(cell).display)
        cell.removeClass("stack")
        resolver.clearCache()
        assertNull(narrow.computed(cell).display)
        assertNotNull(resolver.resolverAtMaxWidth(400f))
    }
}
