package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.RustChapterMarkup
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CssResolverSharingTest {
    private fun resolver(css: String) = CssResolver(listOf(CssResolver.Sheet(css)))

    @Test
    fun `repeated prose shares resolved styles while tag defaults remain distinct`() {
        val css = resolver("p { display:block; margin:1em 0; }")
        val document = Jsoup.parse(buildString {
            append("<body>")
            repeat(2_000) { append("<p>Paragraph ").append(it).append(".</p>") }
            append("<em>Emphasis</em><strong>Strong</strong></body>")
        })
        val paragraphs = document.select("p")
        val first = css.computed(paragraphs.first()!!)
        for (paragraph in paragraphs) {
            assertSame(first, css.computed(paragraph))
        }
        assertEquals(1f, first.marginTopEm, 0f)
        assertEquals(1f, first.marginBottomEm, 0f)
        assertEquals(true, css.computed(document.selectFirst("em")!!).italic)
        assertEquals(true, css.computed(document.selectFirst("strong")!!).bold)
    }

    @Test
    fun `structural class id attribute and sibling selectors still match each element`() {
        val css = resolver("""
            p { margin:1em 0; }
            p:nth-child(even) { font-size:1.5em; }
            .emphasis { font-style:italic; }
            #special { font-weight:700; }
            p[data-align=center] { text-align:center; }
            p + p { text-indent:2em; }
        """.trimIndent())
        val document = Jsoup.parse("""<body>
            <p>First</p><p>Second</p><p class="emphasis">Third</p>
            <p id="special">Fourth</p><p data-align="center">Fifth</p><p>Sixth</p>
            </body>""")
        val styles = document.select("p").map(css::computed)
        assertEquals(listOf(1f, 1.5f, 1f, 1.5f, 1f, 1.5f), styles.map { it.fontSizeEm })
        assertNull(styles[0].textIndentEm)
        assertEquals(3f, styles[1].textIndentEm!!, 0f)
        assertEquals(true, styles[2].italic)
        assertNull(styles[4].italic)
        assertEquals(true, styles[3].bold)
        assertNull(styles[5].bold)
        assertEquals("center", styles[4].textAlign)
        assertNull(styles[5].textAlign)
        assertSame(styles[1], styles[5])
    }

    @Test
    fun `retained chapter matcher keeps structural indices across interleaved markup`() {
        val css = resolver("""
            #first.intro { font-style:italic; }
            section > p:nth-child(even) { font-size:1.5em; }
            p + p[data-position=second] { text-indent:2em; }
        """.trimIndent())
        val bytes = """
            <html><head><title>Chapter</title></head><body><section>
            <p ID="first" CLASS="intro intro">First</p>
            <!-- between siblings -->
            <p data-position="second">Second</p>
            </section></body></html>
        """.trimIndent().encodeToByteArray()
        checkNotNull(RustChapterMarkup.parseRetained(bytes)).use { chapter ->
            try {
                val styles = chapter.select("p").map(css::computed)
                assertEquals(true, styles[0].italic)
                assertEquals(1.5f, styles[1].fontSizeEm, 0f)
                assertEquals(3f, styles[1].textIndentEm!!, 0f)
            } finally {
                css.clearCache()
            }
        }
    }

    @Test
    fun `sharing retains inherited styles and custom property scopes`() {
        val css = resolver("""
            .small { --indent:1em; font-size:1em; color:red; }
            .large { --indent:2em; font-size:1.5em; color:blue; }
            p { text-indent:var(--indent); }
        """.trimIndent())
        val document = Jsoup.parse("""<body>
            <div class="small"><p>A</p><p>B</p></div>
            <div class="large"><p>C</p><p>D</p></div></body>""")
        val styles = document.select("p").map(css::computed)
        assertSame(styles[0], styles[1])
        assertSame(styles[2], styles[3])
        assertEquals(1f, styles[0].fontSizeEm, 0f)
        assertEquals(1f, styles[0].textIndentEm!!, 0f)
        assertEquals(0xffff0000.toInt(), styles[0].foregroundColorArgb)
        assertEquals(1.5f, styles[2].fontSizeEm, 0f)
        assertEquals(3f, styles[2].textIndentEm!!, 0f)
        assertEquals(0xff0000ff.toInt(), styles[2].foregroundColorArgb)
    }

    @Test
    fun `computed native styles reuse inherited variable scopes across chapter lifetimes`() {
        val css = resolver("body { --size:2em; } p { font-size:var(--size); }")
        val document = Jsoup.parse("<body><p>Inherited scope</p></body>")
        val body = css.computed(document.body())
        val paragraph = css.computed(document.selectFirst("p")!!)
        assertSame(body.customProps, paragraph.customProps)
        assertEquals(2f, paragraph.fontSizeEm, 0f)

        css.clearCache()
        document.body().attr("style", "--size:1.5em")
        val reopened = css.computed(document.selectFirst("p")!!)
        assertNotSame(paragraph, reopened)
        assertEquals(1.5f, reopened.fontSizeEm, 0f)
        assertSame(css.computed(document.body()).customProps, reopened.customProps)
        css.clearCache()
    }

    @Test
    fun `legacy presentational attributes participate in shared styles`() {
        val css = resolver("")
        val document = Jsoup.parse("""<body text="green">
            <p>Inherited</p><p align="center">Centered</p><p dir="rtl">RTL</p>
            <p color="red">Red</p><p bgcolor="blue">Blue background</p>
            <p>Inherited again</p></body>""")
        val styles = document.select("p").map(css::computed)
        assertEquals(0xff008000.toInt(), styles[0].foregroundColorArgb)
        assertEquals("center", styles[1].textAlign)
        assertEquals("rtl", styles[2].direction)
        assertEquals(0xffff0000.toInt(), styles[3].foregroundColorArgb)
        assertEquals(0xff0000ff.toInt(), styles[4].backgroundColorArgb)
        assertSame(styles[0], styles[5])
    }

    @Test
    fun `automatic text direction keeps logical and physical property resolution local`() {
        val css = resolver("p { margin-inline-start:2em; margin-left:3em; }")
        val document = Jsoup.parse("""<body>
            <p dir="auto">English</p><p dir="AUTO">مرحبا</p>
            <div dir="auto"><p>English</p><p>مرحبا</p></div></body>""")
        val styles = document.select("p").map(css::computed)
        assertEquals(listOf(0f, 2f, 0f, 2f), styles.map { it.marginInlineStartEm })
        assertEquals(listOf(3f, 3f, 3f, 3f), styles.map { it.marginStartEm })
    }

    @Test
    fun `the order of identical winning declarations is part of the shared key`() {
        // The comma selectors share Declaration instances. The class rule
        // wins later only on the second paragraph, changing the box axis.
        val css = resolver("p, .physical { margin-left:3em; } p { margin-inline-start:2em; }")
        val document = Jsoup.parse("<p>Logical wins</p><p class='physical'>Physical wins</p>")
        val styles = document.select("p").map(css::computed)
        assertEquals(0f, styles[0].marginStartEm, 0f)
        assertEquals(2f, styles[0].marginInlineStartEm, 0f)
        assertEquals(3f, styles[1].marginStartEm, 0f)
        assertEquals(0f, styles[1].marginInlineStartEm, 0f)
    }

    @Test
    fun `inline only cascade keeps winner order and reuses identical declarations`() {
        val css = CssResolver(emptyList())
        val inline = "margin-left:3em!important; margin-inline-start:2em!important; " +
            "margin-left:7em; text-align:center; text-align:right"
        val paragraphs = Jsoup.parse("<p style='$inline'>A</p><p style='$inline'>B</p>").select("p")
        val first = css.computed(paragraphs[0])
        assertEquals("right", first.textAlign)
        assertEquals(0f, first.marginStartEm, 0f)
        assertEquals(2f, first.marginInlineStartEm, 0f)
        assertSame(first, css.computed(paragraphs[1]))
    }

    @Test
    fun `clearing a chapter also drops shared inherited styles`() {
        val css = resolver("p { font-weight:bold; }")
        val document = Jsoup.parse("<body><p>First</p><p>Second</p></body>")
        val first = css.computed(document.selectFirst("p")!!)
        document.body().attr("color", "red")
        css.clearCache()
        val after = css.computed(document.selectFirst("p")!!)
        assertNotSame(first, after)
        assertEquals(0xffff0000.toInt(), after.foregroundColorArgb)
        assertSame(after, css.computed(document.select("p")[1]))
    }
}
