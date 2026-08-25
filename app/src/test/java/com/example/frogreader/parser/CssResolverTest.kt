package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssResolverTest {

    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))

    private fun doc(html: String): Document = Jsoup.parse(html)

    @Test
    fun `foreground inherits background stays local and legacy attributes join cascade`() {
        val r = resolver(
            """
            body { color: #123456; background-color: linen; }
            div { background-color: aliceblue; }
            #reset { color: initial; background-color: currentColor; }
            #sheetWins { color: blue; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <body><div><p id="plain">plain <span id="reset">reset</span></p></div>
            <font id="legacy" color="red" bgcolor="yellow">legacy</font>
            <font id="sheetWins" color="red">winner</font></body>
            """.trimIndent(),
        )

        val plain = r.computed(d.selectFirst("#plain")!!)
        assertEquals(0xff123456.toInt(), plain.foregroundColorArgb)
        assertNull(plain.backgroundColorArgb)
        assertEquals(0xfff0f8ff.toInt(), r.visualBackground(d.selectFirst("#plain")!!))

        val reset = r.computed(d.selectFirst("#reset")!!)
        assertEquals(0xff000000.toInt(), reset.foregroundColorArgb)
        assertEquals(0xff000000.toInt(), reset.backgroundColorArgb)

        val legacy = r.computed(d.selectFirst("#legacy")!!)
        assertEquals(0xffff0000.toInt(), legacy.foregroundColorArgb)
        assertEquals(0xffffff00.toInt(), legacy.backgroundColorArgb)
        assertEquals(0xff0000ff.toInt(), r.computed(d.selectFirst("#sheetWins")!!).foregroundColorArgb)
    }

    @Test
    fun `child combinator does not match grandchildren`() {
        val r = resolver("div > p { font-style: italic; }")
        val d = doc("<div><p id='direct'>a</p><section><p id='deep'>b</p></section></div>")

        assertEquals(true, r.computed(d.selectFirst("#direct")!!).italic)
        assertNull(r.computed(d.selectFirst("#deep")!!).italic)
    }

    @Test
    fun `descendant combinator still matches any depth`() {
        val r = resolver("div p { font-weight: bold; }")
        val d = doc("<div><section><p id='deep'>b</p></section></div><p id='out'>c</p>")

        assertEquals(true, r.computed(d.selectFirst("#deep")!!).bold)
        assertNull(r.computed(d.selectFirst("#out")!!).bold)
    }

    @Test
    fun `adjacent sibling combinator`() {
        val r = resolver("h1 + p { font-style: italic; }")
        val d = doc("<h1>t</h1><p id='first'>a</p><p id='second'>b</p>")

        assertEquals(true, r.computed(d.selectFirst("#first")!!).italic)
        assertNull(r.computed(d.selectFirst("#second")!!).italic)
    }

    @Test
    fun `general sibling combinator`() {
        val r = resolver("h1 ~ p { font-style: italic; }")
        val d = doc("<p id='before'>x</p><h1>t</h1><p id='a'>a</p><div></div><p id='b'>b</p>")

        assertNull(r.computed(d.selectFirst("#before")!!).italic)
        assertEquals(true, r.computed(d.selectFirst("#a")!!).italic)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).italic)
    }

    @Test
    fun `attribute selectors - all operators`() {
        val r = resolver(
            """
            p[align] { text-align: center; }
            p[data-kind="note"] { font-style: italic; }
            p[class~="lead"] { font-weight: bold; }
            p[lang|="ru"] { hyphens: auto; }
            p[title^="pre"] { text-decoration: underline; }
            p[title${'$'}="fix"] { text-decoration: line-through; }
            p[data-x*="mid"] { font-size: 2em; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <p id='a' align="center">1</p>
            <p id='b' data-kind="note">2</p>
            <p id='c' class="intro lead">3</p>
            <p id='d' lang="ru-RU">4</p>
            <p id='e' title="preamble">5</p>
            <p id='f' title="postfix">6</p>
            <p id='g' data-x="amidst">7</p>
            <p id='h'>8</p>
            """.trimIndent(),
        )

        assertEquals("center", r.computed(d.selectFirst("#a")!!).textAlign)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).italic)
        assertEquals(true, r.computed(d.selectFirst("#c")!!).bold)
        assertEquals(true, r.computed(d.selectFirst("#d")!!).hyphensAuto)
        assertTrue(r.computed(d.selectFirst("#e")!!).underline)
        assertTrue(r.computed(d.selectFirst("#f")!!).strike)
        assertEquals(2f, r.computed(d.selectFirst("#g")!!).fontSizeEm, 0.01f)
        val plain = r.computed(d.selectFirst("#h")!!)
        assertNull(plain.textAlign)
        assertNull(plain.italic)
    }

    @Test
    fun `structural pseudo-classes`() {
        val r = resolver(
            """
            p:first-child { font-weight: bold; }
            p:last-child { font-style: italic; }
            p:first-of-type { text-decoration: underline; }
            """.trimIndent(),
        )
        // A leading text node must not break :first-child (element semantics).
        val d = doc("<div>text<h2 id='h'>t</h2><p id='a'>1</p><p id='b'>2</p></div>")

        val a = r.computed(d.selectFirst("#a")!!)
        assertNull(a.bold) // h2 is the first element child
        assertTrue(a.underline) // but #a is the first <p>
        val b = r.computed(d.selectFirst("#b")!!)
        assertEquals(true, b.italic)
        assertFalse(b.underline)

        val single = doc("<div><p id='only'>x</p></div>")
        val only = r.computed(single.selectFirst("#only")!!)
        assertEquals(true, only.bold)
        assertEquals(true, only.italic)
    }

    @Test
    fun `one id outweighs many classes`() {
        val r = resolver(
            """
            .a .b .c .d .e .f .g .h .i .j .k p { text-align: end; }
            #x p { text-align: center; }
            """.trimIndent(),
        )
        val html = StringBuilder()
        for (cls in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")) {
            html.append("<div class='$cls' ")
            if (cls == "a") html.append("id='x'")
            html.append(">")
        }
        html.append("<p id='p'>t</p>")
        repeat(11) { html.append("</div>") }

        assertEquals("center", resolverComputedAlign(r, html.toString()))
    }

    private fun resolverComputedAlign(r: CssResolver, html: String): String? =
        r.computed(doc(html).selectFirst("#p")!!).textAlign

    @Test
    fun `important beats higher specificity and inline`() {
        val r = resolver(
            """
            p { text-align: center !important; }
            #strong p.special { text-align: end; }
            """.trimIndent(),
        )
        val d = doc("<div id='strong'><p id='p' class='special' style='text-align: left'>t</p></div>")
        assertEquals("center", r.computed(d.selectFirst("#p")!!).textAlign)
    }

    @Test
    fun `inline important beats sheet important`() {
        val r = resolver("p { text-align: center !important; }")
        val d = doc("<p id='p' style='text-align: right !important'>t</p>")
        assertEquals("right", r.computed(d.selectFirst("#p")!!).textAlign)
    }

    @Test
    fun `inline style wins over normal sheet declarations`() {
        val r = resolver("#p { font-style: italic; }")
        val d = doc("<p id='p' style='font-style: normal'>t</p>")
        assertEquals(false, r.computed(d.selectFirst("#p")!!).italic)
    }

    @Test
    fun `first-letter rules are captured separately and do not leak`() {
        val r = resolver(
            """
            p.opener::first-letter { font-size: 3em; float: left; font-weight: bold;
                color: rebeccapurple; background-color: #ff08; }
            p.legacy:first-letter { font-size: 2em; float: right; direction: rtl; }
            """.trimIndent(),
        )
        val d = doc("<p id='a' class='opener'>Мы</p><p id='b' class='legacy'>Он</p><p id='c'>x</p>")

        // Normal computed style is untouched by the pseudo-element rule.
        assertEquals(1f, r.computed(d.selectFirst("#a")!!).fontSizeEm, 0.01f)
        assertNull(r.computed(d.selectFirst("#a")!!).bold)

        val cap = r.firstLetter(d.selectFirst("#a")!!)
        assertNotNull(cap)
        assertEquals(3f, cap!!.scale, 0.01f)
        assertTrue(cap.isDropCap)
        assertEquals(true, cap.bold)
        assertEquals(0xff663399.toInt(), cap.foregroundColorArgb)
        assertEquals(0x88ffff00.toInt(), cap.backgroundColorArgb)

        val legacy = r.firstLetter(d.selectFirst("#b")!!)
        assertNotNull(legacy)
        assertEquals(2f, legacy!!.scale, 0.01f)
        assertTrue(legacy.isDropCap)
        assertEquals(false, legacy.leftSide)
        assertEquals(
            com.example.frogreader.data.model.BookTextDirection.RTL,
            legacy.direction,
        )

        assertNull(r.firstLetter(d.selectFirst("#c")!!))
    }

    @Test
    fun `unsupported pseudo drops only its comma-group member`() {
        // :nth-child is supported since stage 6 — :nth-last-child is not.
        val r = resolver("a:hover, p.note, li:nth-last-child(1) { font-style: italic; }")
        val d = doc("<p id='p' class='note'>t</p><li id='li'>x</li>")

        assertEquals(true, r.computed(d.selectFirst("#p")!!).italic)
        assertNull(r.computed(d.selectFirst("#li")!!).italic)
    }

    @Test
    fun `list-style-type is parsed and inherited`() {
        val r = resolver("ol.roman { list-style-type: upper-roman; } ul { list-style: square inside; }")
        val d = doc("<ol class='roman'><li id='a'>1</li></ol><ul><li id='b'>2</li></ul>")

        assertEquals("upper-roman", r.computed(d.selectFirst("#a")!!).listStyleType)
        assertEquals("square", r.computed(d.selectFirst("#b")!!).listStyleType)
    }

    @Test
    fun `page break before is detected`() {
        val r = resolver(".chapter { page-break-before: always; } .part { break-before: page; }")
        val d = doc("<div class='chapter' id='a'>x</div><div class='part' id='b'>y</div><div id='c'>z</div>")

        assertTrue(r.computed(d.selectFirst("#a")!!).pageBreakBefore)
        assertTrue(r.computed(d.selectFirst("#b")!!).pageBreakBefore)
        assertFalse(r.computed(d.selectFirst("#c")!!).pageBreakBefore)
    }

    @Test
    fun `float and width are consumed`() {
        val r = resolver("img.small { float: left; width: 30%; } img.em { width: 8em; }")
        val d = doc("<img id='a' class='small'/><img id='b' class='em'/>")

        val a = r.computed(d.selectFirst("#a")!!)
        assertEquals("left", a.floatSide)
        assertEquals(0.30f, a.widthFrac!!, 0.001f)
        val b = r.computed(d.selectFirst("#b")!!)
        assertNull(b.floatSide)
        assertEquals(8f, b.widthEm!!, 0.001f)
    }

    @Test
    fun `media print rules stay excluded and font faces still parse`() {
        val r = resolver(
            """
            @media print { p { font-style: italic; } }
            @media screen { p { font-weight: bold; } }
            @font-face { font-family: "Lit"; src: url(fonts/lit.ttf); font-weight: bold; }
            """.trimIndent(),
        )
        val d = doc("<p id='p'>t</p>")

        val computed = r.computed(d.selectFirst("#p")!!)
        assertNull(computed.italic)
        assertEquals(true, computed.bold)
        val face = r.fontFaces.single()
        assertEquals("lit", face.family)
        assertEquals("fonts/lit.ttf", face.src)
        assertTrue(face.bold)
    }

    @Test
    fun `media types honor screen negation and comma alternatives`() {
        val css = """
            @media not screen { p { font-style: italic; } }
            @media speech { p { text-align: right; } }
            @media not print { p { font-weight: bold; } }
            @media print, screen and (min-width: 20em) { p { text-align: center; } }
        """.trimIndent()
        val resolver = CssResolver(listOf(CssResolver.Sheet(css)))
        val paragraph = Jsoup.parse("<p>text</p>").selectFirst("p")!!

        val computed = resolver.computed(paragraph)
        assertEquals(null, computed.italic)
        assertEquals(true, computed.bold)
        assertEquals("center", computed.textAlign)
    }
}
