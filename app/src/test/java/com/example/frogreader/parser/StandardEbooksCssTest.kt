package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.model.PublisherBoxAlign
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases extracted from the six Standard Ebooks EPUB stylesheets. */
class StandardEbooksCssTest {
    private fun resolver(css: String, viewport: CssResolver.Viewport? = null) =
        CssResolver(listOf(CssResolver.Sheet(css)), viewport)

    @Test
    fun `full page typography retains exact viewport minimum and centered child box`() {
        val css = resolver(
            "section {display:flex;flex-direction:column;justify-content:center;align-items:center;" +
                "box-sizing:border-box;min-height:calc(98vh - 3em);padding-top:3em;} " +
                "section > blockquote {display:inline-block;max-width:80%;margin:0;}",
        )
        val doc = Jsoup.parse("<section><blockquote><p>Greek epigraph</p></blockquote></section>")
        val section = doc.selectFirst("section")!!
        val computed = css.computed(section)
        assertNull(computed.minHeightEm)
        assertEquals(0.98f, computed.minHeightViewportFrac!!, 0.001f)
        assertEquals(-3f, computed.minHeightOffsetEm, 0.001f)
        assertTrue(computed.centerContentVertically)
        assertTrue(computed.borderBox)
        val box = css.publisherBoxStyle(section)!!
        assertEquals(3f, box.paddingTopEm, 0.001f)
        assertEquals(PublisherBoxAlign.CENTER, css.publisherBoxStyle(doc.selectFirst("blockquote")!!)!!.horizontalAlign)
    }

    @Test
    fun `viewport offset uses element em and unsupported nonlinear minima keep ordinary flow`() {
        val css = resolver(
            "#scaled {font-size:2em;min-height:calc(3em + 98vh);} #plain {min-height:80vh;} " +
                "#nonlinear {min-height:min(98vh, 30em);} #unknown {min-height:calc(98vh - 2vw);}",
        )
        val doc = Jsoup.parse("<p id='scaled'>A</p><p id='plain'>B</p><p id='nonlinear'>C</p><p id='unknown'>D</p>")
        assertEquals(6f, css.computed(doc.getElementById("scaled")!!).minHeightOffsetEm, 0.001f)
        assertEquals(0.8f, css.computed(doc.getElementById("plain")!!).minHeightViewportFrac!!, 0.001f)
        for (id in listOf("nonlinear", "unknown")) {
            val computed = css.computed(doc.getElementById(id)!!)
            assertNull(computed.minHeightEm)
            assertNull(computed.minHeightViewportFrac)
            assertFalse(computed.centerContentVertically)
        }
    }

    @Test
    fun `has restores heading group layout with anchored relative selectors`() {
        val css = resolver(
            """
            hgroup { margin-top: 1em; }
            hgroup:has(h2) { margin-top: 3em; }
            hgroup:has(> h1 + p.subtitle) { margin-top: 2em; }
            hgroup:has(+ p.after) { margin-bottom: 2em; }
            """.trimIndent(),
        )
        val doc = Jsoup.parse(
            "<hgroup id='a'><h2>Heading</h2></hgroup>" +
                "<hgroup id='b'><h1>Heading</h1><p class='subtitle'>Sub</p></hgroup>" +
                "<p class='after'>Text</p><hgroup id='c'><p>Plain</p></hgroup>",
        )
        assertEquals(3f, css.computed(doc.getElementById("a")!!).marginTopEm, 0.001f)
        assertEquals(2f, css.computed(doc.getElementById("b")!!).marginTopEm, 0.001f)
        assertEquals(2f, css.computed(doc.getElementById("b")!!).marginBottomEm, 0.001f)
        assertEquals(1f, css.computed(doc.getElementById("c")!!).marginTopEm, 0.001f)
    }

    @Test
    fun `real heading margins survive while extreme publisher block spacing is bounded`() {
        val css = resolver(
            "hgroup:has(h1) {margin:6em 0;} hgroup:has(h2) {margin:4.5em 0;}" +
                "#huge {margin-top:99999em;} #invalid {margin-top:calc(999999999999999999999999999999999999999999999999999999999999999em * 2);}",
        )
        val doc = Jsoup.parse("<hgroup id='h1'><h1>Title</h1></hgroup><hgroup id='h2'><h2>Heading</h2></hgroup><p id='huge'>A</p><p id='invalid'>B</p>")
        assertEquals(6f, css.computed(doc.getElementById("h1")!!).marginTopEm, 0.001f)
        assertEquals(4.5f, css.computed(doc.getElementById("h2")!!).marginBottomEm, 0.001f)
        assertEquals(12f, css.computed(doc.getElementById("huge")!!).marginTopEm, 0.001f)
        assertTrue(css.computed(doc.getElementById("invalid")!!).marginTopEm.isFinite())
    }

    @Test
    fun `has is bounded nested invalid selectors drop and specificity uses strongest argument`() {
        val css = resolver(
            "section:has(.match, #special) { font-style: italic; }" +
                "section.wrapper { font-style: normal; }" +
                "p:has(span:has(i)), p.kept { font-weight: bold; }",
        )
        val doc = Jsoup.parse(
            "<section class='wrapper'><span class='match'>Text</span></section>" +
                "<p id='invalid'><span><i>Text</i></span></p><p id='kept' class='kept'>Text</p>",
        )
        assertEquals(true, css.computed(doc.selectFirst("section")!!).italic)
        assertNull(css.computed(doc.getElementById("invalid")!!).bold)
        assertEquals(true, css.computed(doc.getElementById("kept")!!).bold)
    }

    @Test
    fun `reverse structural selectors distinguish child and type positions`() {
        val css = resolver(
            "#outer > p:nth-last-child(3) { font-style: italic; }" +
                "#outer > p:nth-last-of-type(2) { font-weight: bold; }" +
                "#outer > p:last-of-type { text-align: right; }" +
                "#outer > em:only-of-type { text-align: center; }",
        )
        val doc = Jsoup.parse("<div id='outer'><p>A</p><p id='target'>B</p><em>E</em><p id='last'>C</p></div>")
        val target = css.computed(doc.getElementById("target")!!)
        assertEquals(true, target.italic)
        assertEquals(true, target.bold)
        assertEquals("right", css.computed(doc.getElementById("last")!!).textAlign)
        assertEquals("center", css.computed(doc.selectFirst("em")!!).textAlign)
    }

    @Test
    fun `clearCache drops structural sibling and has caches between chapters`() {
        val css = resolver("p:nth-last-child(1) { font-weight:bold; } div:has(em) { font-style:italic; }")
        val doc = Jsoup.parse("<div id='parent'><p id='a'>A</p></div>")
        val parent = doc.getElementById("parent")!!
        val paragraph = doc.getElementById("a")!!
        assertEquals(true, css.computed(paragraph).bold)
        assertNull(css.computed(parent).italic)
        parent.append("<em>E</em>")
        css.clearCache()
        assertNull(css.computed(paragraph).bold)
        assertEquals(true, css.computed(parent).italic)
    }

    @Test
    fun `font variants preserve capitals and numeric OpenType features with resets`() {
        val css = resolver(
            "body {font-variant-numeric: oldstyle-nums;}" +
                ".caps {font-variant-caps:all-small-caps;}" +
                ".table {font-variant-numeric:tabular-nums;}" +
                ".normal {font-variant:normal;}" +
                ".override {font-feature-settings: 'smcp' off, 'liga' on;}" +
                ".shorthand {font:small-caps 1em serif;}",
        )
        val doc = Jsoup.parse(
            "<body><div class='caps'><span id='inherit'>A</span><span id='reset' class='normal'>B</span>" +
                "<span id='explicit' class='override'>C</span><span id='font' class='shorthand'>D</span></div>" +
                "<table class='table'><tr><td id='cell'>1</td></tr></table></body>",
        )
        assertEquals("'smcp' 1, 'c2sc' 1, 'onum' 1", css.computed(doc.getElementById("inherit")!!).fontFeatureSettings)
        assertNull(css.computed(doc.getElementById("reset")!!).fontFeatureSettings)
        assertEquals("'smcp' 0, 'c2sc' 1, 'onum' 1, 'liga' 1", css.computed(doc.getElementById("explicit")!!).fontFeatureSettings)
        assertEquals("'smcp' 1", css.computed(doc.getElementById("font")!!).fontFeatureSettings)
        assertEquals("'tnum' 1", css.computed(doc.getElementById("cell")!!).fontFeatureSettings)
    }

    @Test
    fun `visibility retains geometry and descendants can become visible`() {
        val css = resolver(".hidden {visibility:hidden;text-transform:uppercase;} .visible {visibility:visible;text-transform:none;}")
        val doc = Jsoup.parse("<div class='hidden'><span id='hidden'>Text</span><span id='visible' class='visible'>Text</span></div>")
        val hidden = css.computed(doc.getElementById("hidden")!!)
        assertFalse(hidden.hidden)
        assertTrue(hidden.visibilityHidden)
        assertEquals("uppercase", hidden.textTransform)
        assertFalse(css.computed(doc.getElementById("visible")!!).visibilityHidden)
        assertNull(css.computed(doc.getElementById("visible")!!).textTransform)
    }

    @Test
    fun `generated empty quotation overrides and empty border separators survive`() {
        val css = resolver(
            "q::before, q::after {content:'';}" +
                "blockquote + blockquote::before {content:'';display:block;border-top:1px solid;margin:1.5em auto;width:25%;}",
        )
        val doc = Jsoup.parse("<q>Text</q><blockquote>A</blockquote><blockquote id='entry'>B</blockquote>")
        assertEquals("", css.generated(doc.selectFirst("q")!!, false)!!.text)
        val run = css.generated(doc.getElementById("entry")!!, false)!!
        assertEquals("", run.text)
        val box = requireNotNull(run.publisherBox)
        assertNotNull(box.borderTop)
        assertEquals(0.25f, box.widthFrac!!, 0.001f)
        assertEquals(1.5f, box.marginTopEm, 0.001f)
    }

    @Test
    fun `generated stage direction parentheses reset italics and hidden prefixes retain text`() {
        val css = resolver(
            ".stage {font-style:italic;} .stage::before {content:'(';font-style:normal;}" +
                ".followon::before {content:'quoted prefix';visibility:hidden;}",
        )
        val doc = Jsoup.parse("<i class='stage'>Text</i><p class='followon'>Continuation</p>")
        assertEquals(false, css.generated(doc.selectFirst("i")!!, false)!!.italic)
        val run = css.generated(doc.selectFirst("p")!!, false)!!
        assertEquals("quoted prefix", run.text)
        assertTrue(run.visibilityHidden)
    }

    @Test
    fun `image maxima and intentionally blank page box retain authored dimensions`() {
        val css = resolver(
            "img {max-width:100%;max-height:100vh;}" +
                "#half {max-width:50%;max-height:50vh;}" +
                "#ornament {font-size:2em;max-width:10em;max-height:3em;}" +
                "#blank {min-height:10em;break-before:page;break-after:page;}",
        )
        val doc = Jsoup.parse("<img id='full'><img id='half'><img id='ornament'><div id='blank'></div>")
        assertEquals(1f, css.computed(doc.getElementById("full")!!).maxWidthFrac!!, 0.001f)
        val half = css.computed(doc.getElementById("half")!!)
        assertEquals(0.5f, half.maxWidthFrac!!, 0.001f)
        assertEquals(0.5f, half.maxHeightViewportFrac!!, 0.001f)
        val ornament = css.computed(doc.getElementById("ornament")!!)
        assertEquals(20f, ornament.maxWidthEm!!, 0.001f)
        assertEquals(6f, ornament.maxHeightEm!!, 0.001f)
        val box = css.publisherBoxStyle(doc.getElementById("blank")!!)!!
        assertEquals(10f, box.minHeightEm!!, 0.001f)
        assertTrue(box.pageBreakBefore)
        assertTrue(box.pageBreakAfter)
    }

    @Test
    fun `constrained media keeps base drama table without a viewport and respects actual viewport`() {
        val sheet = "td {display:table-cell;} @media(max-width:400px) {td {display:block;}}"
        val cell = Jsoup.parse("<table><tr><td>Text</td></tr></table>").selectFirst("td")!!
        assertEquals("table-cell", resolver(sheet).computed(cell).display)
        assertEquals("block", resolver(sheet, CssResolver.Viewport(360f)).computed(cell).display)
        assertEquals("table-cell", resolver(sheet, CssResolver.Viewport(800f)).computed(cell).display)
        val combined = "@media screen and (min-width:20em) and (orientation:landscape) {td {font-weight:bold;}}"
        assertEquals(true, resolver(combined, CssResolver.Viewport(800f, 400f)).computed(cell).bold)
        assertNull(resolver(combined, CssResolver.Viewport(400f, 800f)).computed(cell).bold)
    }

    @Test
    fun `supported native flex and table branches retain row layout axes`() {
        val css = resolver(
            "@supports(display:flex) {.row {display:flex;justify-content:space-between;align-items:center;}}" +
                "@supports(display: table) {.table {display:table;}}" +
                "@supports(display:grid) {.row {display:none;}}",
        )
        val doc = Jsoup.parse("<div class='row'><span id='child'>A</span><span>B</span></div><div class='table'>T</div>")
        val row = css.computed(doc.selectFirst(".row")!!)
        assertEquals("flex", row.display)
        assertEquals("row", row.flexDirection)
        assertEquals("nowrap", row.flexWrap)
        assertEquals("space-between", row.justifyContent)
        assertEquals("center", row.alignItems)
        assertFalse(row.hidden)
        assertNull(css.computed(doc.getElementById("child")!!).justifyContent)
        assertEquals("table", css.computed(doc.selectFirst(".table")!!).display)
    }

    @Test
    fun `not supports uses known native capability and malformed conditions preserve fallback`() {
        val css = resolver(
            "@supports not (display: grid) {p {font-weight:bold;}}" +
                "@supports not (display: flex) {p {font-style:italic;}}" +
                "@supports not ((display: grid) or (display: flex)) {p {display:none;}}",
        )
        val paragraph = css.computed(Jsoup.parse("<p>Text</p>").selectFirst("p")!!)
        assertEquals(true, paragraph.bold)
        assertNull(paragraph.italic)
        assertFalse(paragraph.hidden)
    }

    @Test
    fun `far offscreen positioned accessibility copies hide without dropping ordinary positioned text`() {
        val css = resolver(
            ".hidden {position:absolute;left:-999em;} .near {position:absolute;left:-2em;}" +
                ".static {left:-999em;} .fixed {position:fixed;top:-999em;}",
        )
        val doc = Jsoup.parse("<h1 class='hidden'>Title</h1><p class='near'>Near</p><p class='static'>Text</p><p class='fixed'>Copy</p>")
        assertTrue(css.computed(doc.selectFirst(".hidden")!!).hidden)
        assertTrue(css.computed(doc.selectFirst(".fixed")!!).hidden)
        assertFalse(css.computed(doc.selectFirst(".near")!!).hidden)
        assertFalse(css.computed(doc.selectFirst(".static")!!).hidden)
    }

    @Test
    fun `currentColor border stays tied to reader ink when publisher supplied no foreground`() {
        val css = resolver(
            ".rule {border-top:1px solid;} .explicit {border-top:1px solid blue;}" +
                ".authored {color:red;border-top:1px solid currentColor;}",
        )
        val doc = Jsoup.parse("<p class='rule'>A</p><p class='explicit'>B</p><p class='authored'>C</p>")
        assertTrue(css.computed(doc.selectFirst(".rule")!!).borderTop!!.usesCurrentColor)
        assertFalse(css.computed(doc.selectFirst(".explicit")!!).borderTop!!.usesCurrentColor)
        val authored = css.computed(doc.selectFirst(".authored")!!).borderTop!!
        assertFalse(authored.usesCurrentColor)
        assertEquals(0xffff0000.toInt(), authored.colorArgb)
    }

    @Test
    fun `dark image media honors exact inversion and realistic background with independent cache`() {
        val css = resolver(
            """
            @namespace epub "http://www.idpf.org/2007/ops";
            @media all and (prefers-color-scheme:dark) {
                img[epub|type~="se:image.color-depth.black-on-transparent"] {filter:invert(100%);}
                img[epub|type~="se:image.color-depth.black-on-transparent"][epub|type~="se:image.style.realistic"] {
                    filter:none;background:currentColor;
                }
            }
            """.trimIndent(),
        )
        val doc = Jsoup.parse(
            "<html xmlns:epub='http://www.idpf.org/2007/ops'><body>" +
                "<img id='line' epub:type='se:image.color-depth.black-on-transparent'>" +
                "<img id='real' epub:type='se:image.color-depth.black-on-transparent se:image.style.realistic'></body></html>",
        )
        val line = doc.getElementById("line")!!
        assertFalse(css.computed(line).imageInvert)
        assertTrue(css.darkImageStyle(line).imageInvert)
        val real = css.darkImageStyle(doc.getElementById("real")!!)
        assertFalse(real.imageInvert)
        assertTrue(real.backgroundUsesCurrentColor)
        line.attr("epub:type", "se:image.color-depth.black-on-transparent se:image.style.realistic")
        css.clearCache()
        assertFalse(css.darkImageStyle(line).imageInvert)
        assertTrue(css.darkImageStyle(line).backgroundUsesCurrentColor)
    }
}
