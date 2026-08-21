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
        // h1 tag default ×1.5, then max(1em·1.5, 8px·1.5/16) = 1.5.
        assertEquals(1.5f, r.computed(d.selectFirst("#h")!!).fontSizeEm, 0.001f)
        assertEquals(2f, r.computed(d.selectFirst("#b")!!).textIndentEm!!, 0.001f)
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
