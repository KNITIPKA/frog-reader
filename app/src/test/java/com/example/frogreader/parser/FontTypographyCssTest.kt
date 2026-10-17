package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.INLINE_FONT_TYPOGRAPHY_TAG
import com.example.frogreader.data.model.decodeFontTypography
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontTypographyCssTest {
    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))

    @Test
    fun `axes inherit with case sensitive tags last duplicate and fractional negative values`() {
        val css = resolver("""
            body { --axes: 'wght' 475, 'GRAD' -12.5, 'GRAD' 2e1; font-variation-settings: var(--axes); }
            #replacement { font-variation-settings: "wdth" 87.5; }
        """)
        val document = Jsoup.parse("<body><p id='parent'>A<span id='inherit'>B</span><i id='replacement'>C</i></p></body>")
        val parent = css.computed(document.getElementById("parent")!!).fontTypography
        assertEquals(mapOf("wght" to 475f, "GRAD" to 20f), parent.variationAxes)
        assertEquals(parent, css.computed(document.getElementById("inherit")!!).fontTypography)
        assertEquals(mapOf("wdth" to 87.5f), css.computed(document.getElementById("replacement")!!).fontTypography.variationAxes)
    }

    @Test
    fun `normal and initial reset axes while inherited global values retain parent`() {
        val css = resolver("""
            body { font-variation-settings:'opsz' 24; font-width:87.5%; font-optical-sizing:none; }
            #normal { font-variation-settings:normal; font-stretch:normal; font-optical-sizing:auto; }
            #initial { font-variation-settings:initial; font-width:initial; font-optical-sizing:initial; }
            #inherit { font-variation-settings:inherit; font-stretch:inherit; font-optical-sizing:inherit; }
            #unset { font-variation-settings:unset; font-width:unset; font-optical-sizing:unset; }
            #revert { font-variation-settings:revert; font-stretch:revert; font-optical-sizing:revert; }
        """)
        val document = Jsoup.parse("<body><p id='normal'>A</p><p id='initial'>B</p><p id='inherit'>C</p><p id='unset'>D</p><p id='revert'>E</p></body>")
        listOf("normal", "initial").forEach { id ->
            assertEquals(id, FontTypography(emptyMap(), 100f, true), css.computed(document.getElementById(id)!!).fontTypography)
        }
        listOf("inherit", "unset", "revert").forEach { id ->
            assertEquals(id, FontTypography(mapOf("opsz" to 24f), 87.5f, false), css.computed(document.getElementById(id)!!).fontTypography)
        }
    }

    @Test
    fun `invalid axis lists and nonfinite values leave inherited settings intact`() {
        val css = resolver("body {font-variation-settings:'wght' 450}")
        listOf("'weight' 700", "'abc' 700", "wght 700", "'wght' NaN", "'wght' 1e99", "'wght' 700, bad", "'wght'700").forEach { value ->
            val document = Jsoup.parse("<body><p id='x'>A</p></body>")
            document.getElementById("x")!!.attr("style", "font-variation-settings:$value")
            assertEquals(value, mapOf("wght" to 450f), css.computed(document.getElementById("x")!!).fontTypography.variationAxes)
        }
    }

    @Test
    fun `font width aliases share cascade precedence and keyword conversion`() {
        val css = resolver("""
            #x { font-width:expanded; font-stretch:semi-condensed; }
            #y { font-stretch:50% !important; font-width:125%; }
            #z { font-width:extra-expanded; }
        """)
        val document = Jsoup.parse("<p id='x'>A</p><p id='y'>B</p><p id='z'>C</p>")
        assertEquals(87.5f, css.computed(document.getElementById("x")!!).fontTypography.stretchPercent)
        assertEquals(50f, css.computed(document.getElementById("y")!!).fontTypography.stretchPercent)
        assertEquals(150f, css.computed(document.getElementById("z")!!).fontTypography.stretchPercent)
    }

    @Test
    fun `font shorthand sets named width and resets inherited axes and features`() {
        val css = resolver("""
            body { font-variation-settings:'wght' 550; font-width:87.5%; font-optical-sizing:none; font-feature-settings:'ss01' 1; font-kerning:none; font-variant-ligatures:none; }
            #reset { font:condensed italic 1.5em serif; }
            #delayed { --font: expanded 1.2em sans-serif; font:var(--font); }
            #inherit { font:inherit; }
        """)
        val document = Jsoup.parse("<body><p id='reset'>A</p><p id='delayed'>B</p><p id='inherit'>C</p></body>")
        val reset = css.computed(document.getElementById("reset")!!)
        assertEquals(FontTypography(emptyMap(), 75f, true), reset.fontTypography)
        assertNull(reset.fontFeatureSettings)
        assertEquals(true, reset.italic)
        val delayed = css.computed(document.getElementById("delayed")!!)
        assertEquals(FontTypography(emptyMap(), 125f, true), delayed.fontTypography)
        assertNull(delayed.fontFeatureSettings)
        assertEquals(FontTypography(mapOf("wght" to 550f), 87.5f, false), css.computed(document.getElementById("inherit")!!).fontTypography)
    }

    @Test
    fun `kerning ligatures and explicit features use OpenType precedence`() {
        val css = resolver("""
            p { font-kerning:none; font-variant-ligatures:no-common-ligatures discretionary-ligatures; font-feature-settings:'liga' ON, 'GRAD' 2; }
            span { font-kerning:normal; font-variant-ligatures:none; font-feature-settings:normal; }
        """)
        val document = Jsoup.parse("<p id='p'>A<span id='s'>B</span></p>")
        val parent = css.computed(document.getElementById("p")!!).fontFeatureSettings!!
        assertTrue(parent.contains("'kern' 0"))
        assertTrue(parent.contains("'liga' 1"))
        assertTrue(parent.contains("'clig' 0"))
        assertTrue(parent.contains("'dlig' 1"))
        assertTrue(parent.contains("'GRAD' 2"))
        val child = css.computed(document.getElementById("s")!!).fontFeatureSettings!!
        assertTrue(child.contains("'kern' 1"))
        assertTrue(child.contains("'liga' 0"))
        assertTrue(child.contains("'calt' 0"))
        assertFalse(child.contains("GRAD"))
    }

    @Test
    fun `font face width ranges distinguish faces sharing source and weight`() {
        val css = resolver("""
            @font-face {font-family:Example;src:url(example.ttf);font-stretch:75% 100%;}
            @font-face {font-family:Example;src:url(example.ttf);font-width:expanded ultra-expanded;}
        """)
        assertEquals(2, css.fontFaces.size)
        assertEquals(75f, css.fontFaces[0].stretchMin)
        assertEquals(100f, css.fontFaces[0].stretchMax)
        assertEquals(125f, css.fontFaces[1].stretchMin)
        assertEquals(200f, css.fontFaces[1].stretchMax)
    }

    @Test
    fun `block inline generated and dropcap typography survive mapping and resets`() {
        val css = resolver("""
            p {font-variation-settings:'wght' 450; font-width:87.5%; font-optical-sizing:none;}
            span {font-variation-settings:normal; font-stretch:normal; font-optical-sizing:auto;}
            p::before {content:'Prefix ';font-variation-settings:'GRAD' 20;}
            p::first-letter {font-size:3em;float:left;font-variation-settings:'opsz' 48; font-kerning:none;}
        """)
        val document = Jsoup.parse("<p>First <span>reset</span> last.</p>")
        val paragraph = HtmlMapper(resolveImage = { null }, css = css).map(document.body())
            .filterIsInstance<ContentElement.Paragraph>().single()
        val block = paragraph.block!!
        assertEquals(FontTypography(mapOf("wght" to 450f), 87.5f, false), block.fontTypography)
        assertEquals(mapOf("opsz" to 48f), block.firstLetter!!.fontTypography!!.variationAxes)
        assertTrue(block.firstLetter.fontFeatureSettings!!.contains("'kern' 0"))
        val annotations = paragraph.text.getStringAnnotations(INLINE_FONT_TYPOGRAPHY_TAG, 0, paragraph.text.length)
        val resetPosition = paragraph.text.text.indexOf("reset")
        assertEquals(FontTypography(emptyMap(), 100f, true), decodeFontTypography(annotations.single { resetPosition in it.start until it.end }.item))
        val prefixPosition = paragraph.text.text.indexOf("Prefix")
        assertEquals(mapOf("GRAD" to 20f), decodeFontTypography(annotations.single { prefixPosition in it.start until it.end }.item)!!.variationAxes)
    }

    @Test
    fun `font capabilities activate publisher supports branches`() {
        val css = resolver("""
            @supports (font-variation-settings:'GRAD' 20) { p {font-variation-settings:'GRAD' 20;} }
            @supports (font-optical-sizing:auto) { p {font-optical-sizing:auto;} }
            @supports (font-width:87.5%) { p {font-stretch:87.5%;} }
        """)
        val document = Jsoup.parse("<p id='x'>A</p>")
        assertEquals(FontTypography(mapOf("GRAD" to 20f), 87.5f, true), css.computed(document.getElementById("x")!!).fontTypography)
    }

    @Test
    fun `table cell axes and explicit resets retain cell ownership`() {
        val css = resolver("""
            table {font-variation-settings:'wght' 450; font-width:87.5%;}
            td:last-child {font-variation-settings:normal; font-stretch:normal;}
        """)
        val document = Jsoup.parse("<table><tr><td>A</td><td>B</td></tr></table>")
        val table = HtmlMapper(resolveImage = { null }, css = css).map(document.body())
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(FontTypography(mapOf("wght" to 450f), 87.5f), table.block!!.fontTypography)
        val reset = table.rows.single().cells.last()
        assertEquals(FontTypography(emptyMap(), 100f), reset.block!!.fontTypography)
        val mark = reset.text.getStringAnnotations(INLINE_FONT_TYPOGRAPHY_TAG, 0, reset.text.length).single()
        assertEquals(FontTypography(emptyMap(), 100f), decodeFontTypography(mark.item))
    }

    @Test
    fun `responsive table keeps source layout when axes require different source annotations`() {
        val css = resolver("""
            table {font-variation-settings:'wght' 450;}
            @media(max-width:400px) {td {display:block;font-variation-settings:'wdth' 75;}}
        """)
        val document = Jsoup.parse("<table><tr><td>A</td><td>B</td></tr></table>")
        val table = HtmlMapper(resolveImage = { null }, css = css).map(document.body())
            .filterIsInstance<ContentElement.Table>().single()
        assertNull(table.stackCellsBelowCssPx)
        assertEquals(mapOf("wght" to 450f), table.block!!.fontTypography!!.variationAxes)
    }

    @Test
    fun `generated dropcap and table feature normal explicitly clear parent features`() {
        val css = resolver("""
            p, table {font-kerning:none;font-variant-ligatures:none;}
            p::before {content:'Before ';font-kerning:auto;font-variant-ligatures:normal;}
            p::first-letter {font-size:3em;font-kerning:auto;font-variant-ligatures:normal;}
            td:last-child {font-kerning:auto;font-variant-ligatures:normal;}
        """)
        val document = Jsoup.parse("<p id='p'>Book.</p><table><tr><td>A</td><td>B</td></tr></table>")
        val paragraph = document.getElementById("p")!!
        assertEquals("", css.generated(paragraph, false)!!.fontFeatureSettings)
        assertEquals("", css.firstLetter(paragraph)!!.fontFeatureSettings)
        val table = HtmlMapper(resolveImage = { null }, css = css).map(document.body())
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals("", table.rows.single().cells.last().block!!.fontFeatureSettings)
    }

    @Test
    fun `floated text initials preserve their own axes and features`() {
        val css = resolver("p {font-variation-settings:'wght' 450} span {float:left;font-size:3em;font-variation-settings:'opsz' 48;font-variant-ligatures:none}")
        val document = Jsoup.parse("<p id='p'><span id='s'>A</span> book.</p>")
        val cap = css.floatedTextInitial(document.getElementById("s")!!, document.getElementById("p")!!, 1)
        assertNotNull(cap)
        assertEquals(mapOf("opsz" to 48f), cap!!.fontTypography!!.variationAxes)
        assertTrue(cap.fontFeatureSettings!!.contains("'liga' 0"))
    }
}
