package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage-6 features: @-statements, var(), calc(), new units, new selectors. */
class CssResolverAdvancedTest {

    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))

    private fun doc(html: String): Document = Jsoup.parse(html)

    // ------------------------------------------------------------ @-statements

    @Test
    fun `rule after @charset is not swallowed`() {
        val r = resolver("""@charset "utf-8"; p { font-weight: bold; }""")
        val d = doc("<p id='x'>a</p>")
        assertEquals(true, r.computed(d.selectFirst("#x")!!).bold)
    }

    @Test
    fun `rule after @import and @namespace is not swallowed`() {
        val r = resolver(
            """
            @import url("other.css");
            @namespace epub "http://www.idpf.org/2007/ops";
            h1 { text-align: center; }
            p { font-style: italic; }
            """.trimIndent(),
        )
        val d = doc("<h1 id='h'>t</h1><p id='p'>a</p>")
        assertEquals("center", r.computed(d.selectFirst("#h")!!).textAlign)
        assertEquals(true, r.computed(d.selectFirst("#p")!!).italic)
    }

    // ------------------------------------------------------------ var()

    @Test
    fun `var substitutes custom properties with inheritance`() {
        val r = resolver(
            """
            body { --accent-size: 2em; }
            p { font-size: var(--accent-size); }
            """.trimIndent(),
        )
        val d = doc("<body><p id='x'>a</p></body>")
        assertEquals(2f, r.computed(d.selectFirst("#x")!!).fontSizeEm, 0.001f)
    }

    @Test
    fun `var fallback is used when the property is missing`() {
        val r = resolver("p { font-size: var(--missing, 1.5em); }")
        val d = doc("<p id='x'>a</p>")
        assertEquals(1.5f, r.computed(d.selectFirst("#x")!!).fontSizeEm, 0.001f)
    }

    @Test
    fun `unresolvable var drops only that declaration`() {
        val r = resolver("p { font-size: var(--nope); font-weight: bold; }")
        val d = doc("<p id='x'>a</p>")
        val computed = r.computed(d.selectFirst("#x")!!)
        assertEquals(1f, computed.fontSizeEm, 0.001f)
        assertEquals(true, computed.bold)
    }

    @Test
    fun `chained vars resolve and cycles stop`() {
        val r = resolver(
            """
            body { --a: var(--b); --b: 2em; --x: var(--y); --y: var(--x); }
            p { font-size: var(--a); }
            em { font-size: var(--x, 3em); }
            """.trimIndent(),
        )
        val d = doc("<body><p id='p'>a<em id='e'>b</em></p></body>")
        assertEquals(2f, r.computed(d.selectFirst("#p")!!).fontSizeEm, 0.001f)
        // The cyclic var itself resolves to a cyclic value → declaration
        // dropped, font size inherited from the paragraph.
        assertEquals(2f, r.computed(d.selectFirst("#e")!!).fontSizeEm, 0.001f)
    }

    @Test
    fun `child custom property shadows the parent`() {
        val r = resolver(
            """
            body { --size: 2em; }
            div { --size: 3em; }
            p { font-size: var(--size); }
            """.trimIndent(),
        )
        val d = doc("<body><p id='outer'>a</p><div><p id='inner'>b</p></div></body>")
        assertEquals(2f, r.computed(d.selectFirst("#outer")!!).fontSizeEm, 0.001f)
        assertEquals(3f, r.computed(d.selectFirst("#inner")!!).fontSizeEm, 0.001f)
    }

    // ------------------------------------------------------------ calc()

    @Test
    fun `calc mixes units into em space`() {
        val r = resolver("p { text-indent: calc(1em + 16px); }")
        val d = doc("<p id='x'>a</p>")
        assertEquals(2f, r.computed(d.selectFirst("#x")!!).textIndentEm!!, 0.001f)
    }

    @Test
    fun `calc multiplication needs a scalar and division works`() {
        val r = resolver(
            """
            p { margin-top: calc(2 * 0.75em); }
            h1 { margin-top: calc(3em / 2); }
            div { margin-top: calc(1em * 2em); }
            """.trimIndent(),
        )
        val d = doc("<div id='d'><h1 id='h'>t</h1><p id='p'>a</p></div>")
        assertEquals(1.5f, r.computed(d.selectFirst("#p")!!).marginTopEm, 0.001f)
        assertEquals(1.5f, r.computed(d.selectFirst("#h")!!).marginTopEm, 0.001f)
        // em × em has no meaning: declaration ignored.
        assertEquals(0f, r.computed(d.selectFirst("#d")!!).marginTopEm, 0.001f)
    }

    @Test
    fun `min max clamp evaluate`() {
        val r = resolver(
            """
            p { font-size: min(2em, 150%) }
            h1 { font-size: max(1em, 8px) }
            blockquote { text-indent: clamp(1em, 5em, 2em) }
            """.trimIndent(),
        )
        val d = doc("<h1 id='h'>t</h1><p id='p'>a</p><blockquote id='b'><p>q</p></blockquote>")
        assertEquals(1.5f, r.computed(d.selectFirst("#p")!!).fontSizeEm, 0.001f)
        // Author font-size replaces the h1 UA size and resolves against the
        // inherited body size: max(1em, 8px) = 1em.
        assertEquals(1f, r.computed(d.selectFirst("#h")!!).fontSizeEm, 0.001f)
        assertEquals(2f, r.computed(d.selectFirst("#b")!!).textIndentEm!!, 0.001f)
    }

    @Test
    fun `all heading UA sizes are distinct and author size overrides them`() {
        val r = resolver(
            """
            body { font-size: 125%; }
            h4 { font-size: 1em; }
            h5 { font-size: 16px; }
            """.trimIndent(),
        )
        val d = doc(
            "<body><h1 id='h1'>1</h1><h2 id='h2'>2</h2><h3 id='h3'>3</h3>" +
                "<h4 id='h4'>4</h4><h5 id='h5'>5</h5><h6 id='h6'>6</h6></body>",
        )

        assertEquals(1.25f * 1.50f, r.computed(d.selectFirst("#h1")!!).fontSizeEm, 0.001f)
        assertEquals(1.25f * 1.32f, r.computed(d.selectFirst("#h2")!!).fontSizeEm, 0.001f)
        assertEquals(1.25f * 1.18f, r.computed(d.selectFirst("#h3")!!).fontSizeEm, 0.001f)
        // 1em is relative to the parent and overrides the h4 UA scale.
        assertEquals(1.25f, r.computed(d.selectFirst("#h4")!!).fontSizeEm, 0.001f)
        // Absolute px is root-relative, not multiplied by the parent.
        assertEquals(1f, r.computed(d.selectFirst("#h5")!!).fontSizeEm, 0.001f)
        assertEquals(1.25f * 0.88f, r.computed(d.selectFirst("#h6")!!).fontSizeEm, 0.001f)
    }

    @Test
    fun `unitless line height inherits as a multiplier of each child font size`() {
        val r = resolver(
            """
            #parent { font-size: 20px; line-height: 1.5; }
            #child { font-size: 2em; }
            #grandchild { font-size: 50%; }
            #math { font-size: 2em; line-height: min(1.2, 1.5); }
            #math-child { font-size: 50%; }
            """.trimIndent(),
        )
        val d = doc(
            "<div id='parent'><p id='child'><span id='grandchild'>text</span></p></div>" +
                "<div id='math'><span id='math-child'>math</span></div>",
        )

        val parent = r.computed(d.selectFirst("#parent")!!)
        val child = r.computed(d.selectFirst("#child")!!)
        val grandchild = r.computed(d.selectFirst("#grandchild")!!)
        assertEquals(1.25f, parent.fontSizeEm, 0.001f)
        assertEquals(2.5f, child.fontSizeEm, 0.001f)
        assertEquals(1.25f, grandchild.fontSizeEm, 0.001f)
        assertEquals(1.5f, parent.lineHeightMult!!, 0.001f)
        assertEquals(1.5f, child.lineHeightMult!!, 0.001f)
        assertEquals(1.5f, grandchild.lineHeightMult!!, 0.001f)
        assertEquals(1.2f, r.computed(d.selectFirst("#math")!!).lineHeightMult!!, 0.001f)
        assertEquals(1.2f, r.computed(d.selectFirst("#math-child")!!).lineHeightMult!!, 0.001f)
    }

    @Test
    fun `percentage em and absolute line heights inherit as computed lengths`() {
        val r = resolver(
            """
            #percent { font-size: 2em; line-height: 150%; }
            #percent-child { font-size: 50%; }
            #em { font-size: 1.5em; line-height: 2em; }
            #em-child { font-size: 2em; }
            #px { font-size: 2em; line-height: 24px; }
            #px-child { font-size: 50%; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='percent'><span id='percent-child'>p</span></div>
            <div id='em'><span id='em-child'>e</span></div>
            <div id='px'><span id='px-child'>x</span></div>
            """.trimIndent(),
        )

        // 150% of 2em computes to an absolute 3em. The 1em child inherits
        // that same height, so its effective multiplier is 3, not 1.5.
        assertEquals(1.5f, r.computed(d.selectFirst("#percent")!!).lineHeightMult!!, 0.001f)
        assertEquals(3f, r.computed(d.selectFirst("#percent-child")!!).lineHeightMult!!, 0.001f)
        // 2em against a 1.5em parent computes to 3em; the 3em child sees 1x.
        assertEquals(2f, r.computed(d.selectFirst("#em")!!).lineHeightMult!!, 0.001f)
        assertEquals(1f, r.computed(d.selectFirst("#em-child")!!).lineHeightMult!!, 0.001f)
        // 24px is 1.5 root-em regardless of either element's font size.
        assertEquals(0.75f, r.computed(d.selectFirst("#px")!!).lineHeightMult!!, 0.001f)
        assertEquals(1.5f, r.computed(d.selectFirst("#px-child")!!).lineHeightMult!!, 0.001f)
    }

    @Test
    fun `line height uses final font size regardless of declaration order`() {
        val r = resolver(
            """
            #before { line-height: calc(100% + 8px); font-size: 32px; }
            #after { font-size: 32px; line-height: calc(100% + 8px); }
            #before > span, #after > span { font-size: 16px; }
            #reset { line-height: normal; font-size: 16px; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='before'><span id='before-child'>a</span><i id='reset'>r</i></div>
            <div id='after'><span id='after-child'>b</span></div>
            """.trimIndent(),
        )

        // 100% of 32px + 8px = 40px: 1.25x on the declaring element,
        // inherited as the same 40px = 2.5x on a 16px child.
        assertEquals(1.25f, r.computed(d.selectFirst("#before")!!).lineHeightMult!!, 0.001f)
        assertEquals(1.25f, r.computed(d.selectFirst("#after")!!).lineHeightMult!!, 0.001f)
        assertEquals(2.5f, r.computed(d.selectFirst("#before-child")!!).lineHeightMult!!, 0.001f)
        assertEquals(2.5f, r.computed(d.selectFirst("#after-child")!!).lineHeightMult!!, 0.001f)
        assertNull(r.computed(d.selectFirst("#reset")!!).lineHeightMult)
    }

    @Test
    fun `hostile line heights remain finite and inside the paginator envelope`() {
        val r = resolver(
            """
            #number { line-height: 999999; }
            #math { line-height: calc(999999 * 999999); }
            #overflow { line-height: calc(99999999999999999999999999999999999999 * 100); }
            #small { line-height: 0.01; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='number'><span id='number-child'>n</span></div>
            <div id='math'>m</div>
            <div id='overflow'>o</div>
            <div id='small'>s</div>
            """.trimIndent(),
        )

        val number = r.computed(d.selectFirst("#number")!!).lineHeightMult
        val child = r.computed(d.selectFirst("#number-child")!!).lineHeightMult
        val math = r.computed(d.selectFirst("#math")!!).lineHeightMult
        val small = r.computed(d.selectFirst("#small")!!).lineHeightMult
        assertEquals(4f, number!!, 0.001f)
        assertEquals(4f, child!!, 0.001f)
        assertEquals(4f, math!!, 0.001f)
        assertEquals(0.5f, small!!, 0.001f)
        assertTrue(listOf(number, child, math, small).all(Float::isFinite))
        // Arithmetic overflow is invalid rather than leaking infinity.
        assertNull(r.computed(d.selectFirst("#overflow")!!).lineHeightMult)
    }

    @Test
    fun `calc division by zero and dangling operator are ignored`() {
        val r = resolver(
            """
            p { font-size: calc(2em / 0); }
            h2 { font-size: calc(2em +); }
            """.trimIndent(),
        )
        val d = doc("<p id='p'>a</p><h2 id='h'>t</h2>")
        assertEquals(1f, r.computed(d.selectFirst("#p")!!).fontSizeEm, 0.001f)
        assertEquals(1.32f, r.computed(d.selectFirst("#h")!!).fontSizeEm, 0.001f)
    }

    @Test
    fun `calc with var works`() {
        val r = resolver(
            """
            body { --base: 1em; }
            p { text-indent: calc(var(--base) * 2); }
            """.trimIndent(),
        )
        val d = doc("<body><p id='x'>a</p></body>")
        assertEquals(2f, r.computed(d.selectFirst("#x")!!).textIndentEm!!, 0.001f)
    }

    @Test
    fun `margin shorthand and longhands share importance specificity and source order`() {
        val r = resolver(
            """
            #within-a { margin-left: 1em; margin: 2em; }
            #within-b { margin: 2em; margin-left: 1em; }

            .rule-a { margin-left: 1em; }
            .rule-a { margin: 2em; }
            .rule-b { margin: 2em; }
            .rule-b { margin-left: 1em; }

            #specific-a.target { margin: 3em; }
            #specific-a { margin-left: 1em; }
            #specific-b { margin: 3em; }
            #specific-b.target { margin-left: 1em; }

            #important-a { margin: 2em !important; margin-left: 1em; }
            #important-b { margin: 2em; margin-left: 1em !important; }

            #variable { --publisher-margin: 1em 2em 3em 4em;
                        margin: var(--publisher-margin); }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='within-a'>a</div><div id='within-b'>b</div>
            <div id='rule-a' class='rule-a'>a</div><div id='rule-b' class='rule-b'>b</div>
            <div id='specific-a' class='target'>a</div>
            <div id='specific-b' class='target'>b</div>
            <div id='important-a'>a</div><div id='important-b'>b</div>
            <div id='variable'>v</div>
            """.trimIndent(),
        )

        fun start(id: String) = r.computed(d.selectFirst("#$id")!!).marginStartEm

        // Declaration order inside one block, then rule source order.
        assertEquals(2f, start("within-a"), 0.001f)
        assertEquals(1f, start("within-b"), 0.001f)
        assertEquals(2f, start("rule-a"), 0.001f)
        assertEquals(1f, start("rule-b"), 0.001f)
        // Either shorthand or longhand can win by specificity.
        assertEquals(3f, start("specific-a"), 0.001f)
        assertEquals(1f, start("specific-b"), 0.001f)
        // Either shorthand or longhand can win by importance.
        assertEquals(2f, start("important-a"), 0.001f)
        assertEquals(1f, start("important-b"), 0.001f)

        // Expansion retains the original shorthand until var() substitution,
        // including a custom property that supplies all four components.
        val variable = r.computed(d.selectFirst("#variable")!!)
        assertEquals(1f, variable.marginTopEm, 0.001f)
        assertEquals(2f, variable.marginEndEm, 0.001f)
        assertEquals(3f, variable.marginBottomEm, 0.001f)
        assertEquals(4f, variable.marginStartEm, 0.001f)
    }

    @Test
    fun `font shorthand resets inherited font components and supports a whole value var`() {
        val r = resolver(
            """
            #parent { font-style: italic; font-weight: bold; font-size: 24px;
                      line-height: 2; font-family: Old; }
            #child { font: normal 12px/1.2 "New Face", serif; }
            #var-parent { --child-font: normal 10px/1.1 Variable; font-weight: bold; }
            #var-child { font: var(--child-font); }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='parent'><span id='child'>c</span></div>
            <div id='var-parent'><span id='var-child'>v</span></div>
            """.trimIndent(),
        )

        val child = r.computed(d.selectFirst("#child")!!)
        assertEquals(false, child.italic)
        assertEquals(false, child.bold)
        assertEquals(0.75f, child.fontSizeEm, 0.001f)
        assertEquals(1.2f, child.lineHeightMult!!, 0.001f)
        assertEquals("new face", child.fontFamilyName)

        val variable = r.computed(d.selectFirst("#var-child")!!)
        assertEquals(false, variable.bold)
        assertEquals(0.625f, variable.fontSizeEm, 0.001f)
        assertEquals(1.1f, variable.lineHeightMult!!, 0.001f)
        assertEquals("variable", variable.fontFamilyName)
    }

    @Test
    fun `font shorthand competes with longhands by cascade rank in both directions`() {
        val r = resolver(
            """
            #short.target { font: italic bold 20px/1.5 High; }
            #short { font-style: normal; font-weight: normal; font-size: 12px;
                     line-height: 1.1; font-family: Low; }

            #long { font: normal 12px/1.1 Low; }
            #long.target { font-style: italic; font-weight: bold; font-size: 20px;
                           line-height: 1.5; font-family: High; }

            #order-a { font-style: italic; font: normal 12px/1.2 Ordered; }
            #order-b { font: normal 12px/1.2 Ordered; font-style: italic; }
            #important-a { font: italic bold 20px/1.5 Important !important;
                           font-style: normal; font-weight: normal; }
            #important-b { font: normal 12px/1.2 Plain;
                           font-style: italic !important; }
            """.trimIndent(),
        )
        val d = doc(
            """
            <div id='short' class='target'>s</div><div id='long' class='target'>l</div>
            <div id='order-a'>a</div><div id='order-b'>b</div>
            <div id='important-a'>a</div><div id='important-b'>b</div>
            """.trimIndent(),
        )

        for (id in listOf("short", "long")) {
            val computed = r.computed(d.selectFirst("#$id")!!)
            assertEquals(true, computed.italic)
            assertEquals(true, computed.bold)
            assertEquals(1.25f, computed.fontSizeEm, 0.001f)
            assertEquals(1.5f, computed.lineHeightMult!!, 0.001f)
            assertEquals("high", computed.fontFamilyName)
        }
        assertEquals(false, r.computed(d.selectFirst("#order-a")!!).italic)
        assertEquals(true, r.computed(d.selectFirst("#order-b")!!).italic)
        val importantA = r.computed(d.selectFirst("#important-a")!!)
        assertEquals(true, importantA.italic)
        assertEquals(true, importantA.bold)
        assertEquals(true, r.computed(d.selectFirst("#important-b")!!).italic)
    }

    @Test
    fun `computed fills a very deep ancestor chain iteratively`() {
        val r = resolver(
            """
            body { font-weight: bold; line-height: 1.5; }
            div { font-size: 100%; }
            """.trimIndent(),
        )
        val d = Document("")
        var deepest = d.appendElement("html").appendElement("body")
        repeat(2_000) { deepest = deepest.appendElement("div") }

        val computed = r.computed(deepest)
        assertEquals(true, computed.bold)
        assertEquals(1f, computed.fontSizeEm, 0.001f)
        assertEquals(1.5f, computed.lineHeightMult!!, 0.001f)
        // The same cache also serves an already-resolved intermediate node.
        assertEquals(1.5f, r.computed(deepest.parent()!!).lineHeightMult!!, 0.001f)
    }

    @Test
    fun `deep media groups stop safely and parsing resumes after the group`() {
        val css = buildString {
            repeat(2_000) { append("@media screen {") }
            append("p { font-weight: bold; }")
            repeat(2_000) { append('}') }
            append("p.safe { font-style: italic; }")
        }
        val r = resolver(css)
        val d = doc("<p id='x' class='safe'>a</p>")
        val computed = r.computed(d.selectFirst("#x")!!)

        // The rule beyond the supported group depth is ignored, while the
        // independent rule after the complete group remains available.
        assertNull(computed.bold)
        assertEquals(true, computed.italic)
    }

    @Test
    fun `declaration and selector complexity caps drop only hostile input`() {
        val declarations = buildString {
            append("p.safe {")
            repeat(512) { append("--v$it: $it;") }
            append("font-weight: bold; }")
            append("p.safe { font-style: italic; }")
        }
        val overlongSelector = buildString {
            repeat(65) { append("div ") }
            append("p.safe { text-decoration: underline; }")
        }
        val oversizedGroup = buildString {
            repeat(256) { append(".unused$it,") }
            append("p.safe { text-align: center; }")
        }
        val r = resolver(declarations + overlongSelector + oversizedGroup)
        val d = doc("<p id='x' class='safe'>a</p>")
        val computed = r.computed(d.selectFirst("#x")!!)

        assertNull(computed.bold)
        assertEquals(true, computed.italic)
        assertTrue(!computed.underline)
        assertNull(computed.textAlign)
    }

    @Test
    fun `font faces are deduplicated and capped`() {
        val css = buildString {
            append("@font-face{font-family:'f0';src:url('f0.ttf')}")
            append("@font-face{font-family:'f0';src:url('f0.ttf')}")
            for (index in 1..600) {
                append("@font-face{font-family:'f$index';src:url('f$index.ttf')}")
            }
        }
        val faces = resolver(css).fontFaces

        assertEquals(512, faces.size)
        assertEquals(1, faces.count { it.family == "f0" && it.src == "f0.ttf" })
    }

    // ------------------------------------------------------------ units

    @Test
    fun `absolute and half-em units convert`() {
        val r = resolver(
            """
            p { margin-top: 1pc; }
            h1 { margin-top: 0.5in; }
            h2 { margin-top: 10mm; }
            h3 { text-indent: 4ch; }
            """.trimIndent(),
        )
        val d = doc("<p id='p'>a</p><h1 id='h1'>t</h1><h2 id='h2'>t</h2><h3 id='h3'>t</h3>")
        assertEquals(1f, r.computed(d.selectFirst("#p")!!).marginTopEm, 0.001f)
        assertEquals(3f, r.computed(d.selectFirst("#h1")!!).marginTopEm, 0.001f)
        assertEquals(2.3622f, r.computed(d.selectFirst("#h2")!!).marginTopEm, 0.01f)
        assertEquals(2f, r.computed(d.selectFirst("#h3")!!).textIndentEm!!, 0.001f)
    }

    @Test
    fun `viewport units map to the content-width convention`() {
        val r = resolver(
            """
            p { text-indent: 10vw; }
            h1 { margin-top: 2vh; }
            h2 { font-size: 10vw; }
            """.trimIndent(),
        )
        val d = doc("<p id='p'>a</p><h1 id='h1'>t</h1><h2 id='h2'>t</h2>")
        assertEquals(3f, r.computed(d.selectFirst("#p")!!).textIndentEm!!, 0.001f)
        assertEquals(1f, r.computed(d.selectFirst("#h1")!!).marginTopEm, 0.001f)
        // font-size: 10vw = 3× base — root-relative, not cumulative.
        assertEquals(3f, r.computed(d.selectFirst("#h2")!!).fontSizeEm, 0.001f)
    }

    // ------------------------------------------------------------ selectors

    @Test
    fun `nth-child matches an+b even odd and integers`() {
        val r = resolver(
            """
            li:nth-child(2n+1) { font-weight: bold; }
            li:nth-child(even) { font-style: italic; }
            li:nth-child(3) { text-decoration: underline; }
            """.trimIndent(),
        )
        val d = doc("<ul><li id='a'>1</li><li id='b'>2</li><li id='c'>3</li><li id='d'>4</li></ul>")
        assertEquals(true, r.computed(d.selectFirst("#a")!!).bold)
        assertNull(r.computed(d.selectFirst("#b")!!).bold)
        assertEquals(true, r.computed(d.selectFirst("#c")!!).bold)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).italic)
        assertNull(r.computed(d.selectFirst("#a")!!).italic)
        assertTrue(r.computed(d.selectFirst("#c")!!).underline)
        assertTrue(!r.computed(d.selectFirst("#d")!!).underline)
    }

    @Test
    fun `nth-of-type counts only same-tag siblings`() {
        val r = resolver("p:nth-of-type(2) { font-weight: bold; }")
        val d = doc("<div><h1>t</h1><p id='a'>1</p><span>x</span><p id='b'>2</p></div>")
        assertNull(r.computed(d.selectFirst("#a")!!).bold)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).bold)
    }

    @Test
    fun `attribute nth selector stays linear across a large sibling list`() {
        val r = resolver("[data-row]:nth-child(2000) { font-weight: bold; }")
        val d = Document("")
        val parent = d.appendElement("html").appendElement("body").appendElement("div")
        repeat(2_000) { index ->
            parent.appendElement("span").attr("data-row", index.toString())
        }
        val children = parent.children()

        children.forEach { r.computed(it) }
        assertNull(r.computed(children.first()!!).bold)
        assertEquals(true, r.computed(children.last()!!).bold)
    }

    @Test
    fun `negative-a nth-child selects the first n`() {
        val r = resolver("li:nth-child(-n+2) { font-weight: bold; }")
        val d = doc("<ul><li id='a'>1</li><li id='b'>2</li><li id='c'>3</li></ul>")
        assertEquals(true, r.computed(d.selectFirst("#a")!!).bold)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).bold)
        assertNull(r.computed(d.selectFirst("#c")!!).bold)
    }

    @Test
    fun `not excludes by class tag and attribute`() {
        val r = resolver(
            """
            p:not(.intro) { font-style: italic; }
            div:not([data-keep]) { display: none; }
            """.trimIndent(),
        )
        val d = doc(
            "<p id='a' class='intro'>1</p><p id='b'>2</p>" +
                "<div id='c' data-keep='1'><p>x</p></div><div id='d'><p>y</p></div>",
        )
        assertNull(r.computed(d.selectFirst("#a")!!).italic)
        assertEquals(true, r.computed(d.selectFirst("#b")!!).italic)
        assertTrue(!r.computed(d.selectFirst("#c")!!).hidden)
        assertTrue(r.computed(d.selectFirst("#d")!!).hidden)
    }

    @Test
    fun `unsupported functional pseudo drops only its comma member`() {
        val r = resolver("p:has(em), p.plain { font-weight: bold; }")
        val d = doc("<p id='a' class='plain'>1</p><p id='b'><em>2</em></p>")
        assertEquals(true, r.computed(d.selectFirst("#a")!!).bold)
        assertNull(r.computed(d.selectFirst("#b")!!).bold)
    }

    @Test
    fun `not specificity counts its argument`() {
        // p:not(.x) = (0,1,1) must beat plain p = (0,0,1).
        val r = resolver(
            """
            p:not(.x) { font-style: italic; }
            p { font-style: normal; }
            """.trimIndent(),
        )
        val d = doc("<p id='a'>1</p>")
        assertEquals(true, r.computed(d.selectFirst("#a")!!).italic)
    }
}
