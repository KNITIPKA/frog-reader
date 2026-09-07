package com.example.frogreader.parser

import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.testfixtures.PublisherLayoutFixture
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PublisherBoxStyleResolverTest {

    @Test
    fun `textbook fixture retains one continuous physical panel box`() {
        val resolver = CssResolver(listOf(CssResolver.Sheet(PublisherLayoutFixture.css)))
        val document = Jsoup.parse(PublisherLayoutFixture.html)
        val panelElement = document.getElementById("publisher-panel")!!
        val computed = resolver.computed(panelElement)
        val panel = resolver.publisherBoxStyle(panelElement)

        assertNotNull(panel)
        panel!!
        assertEquals(1f, panel.marginTopEm, 0.001f)
        assertEquals(0f, panel.marginRightEm, 0.001f)
        assertEquals(1f, panel.marginBottomEm, 0.001f)
        assertEquals(0f, panel.marginLeftEm, 0.001f)
        assertEquals(0.5f, panel.paddingTopEm, 0.001f)
        assertEquals(0.75f, panel.paddingRightEm, 0.001f)
        assertEquals(0.6f, panel.paddingBottomEm, 0.001f)
        assertEquals(0.75f, panel.paddingLeftEm, 0.001f)
        assertEquals(PublisherLayoutFixture.panelBackgroundArgb, panel.backgroundColorArgb)

        listOf(panel.borderTop, panel.borderRight, panel.borderBottom, panel.borderLeft)
            .forEach { side ->
                assertNotNull(side)
                assertEquals(0.125f, side!!.widthEm, 0.001f)
                assertEquals(PublisherLayoutFixture.panelBorderArgb, side.colorArgb)
                assertEquals(PublisherBorderStyle.SOLID, side.style)
            }

        // Padding is no longer irreversibly folded into the legacy margin fields.
        assertEquals(0f, computed.marginStartEm, 0.001f)
        assertEquals(0f, computed.marginEndEm, 0.001f)
        assertEquals(0.75f, computed.paddingStartEm, 0.001f)
        assertEquals(0.75f, computed.paddingEndEm, 0.001f)

        val ruledHeading = resolver.publisherBoxStyle(
            document.getElementById("publisher-heading")!!,
        )!!
        assertNull(ruledHeading.borderTop)
        assertNotNull(ruledHeading.borderBottom)
        assertEquals(
            PublisherLayoutFixture.headingBorderArgb,
            ruledHeading.borderBottom!!.colorArgb,
        )

        val table = resolver.publisherBoxStyle(document.getElementById("publisher-grid")!!)!!
        assertEquals(0.72f, table.widthFrac!!, 0.001f)
        assertEquals(PublisherBoxAlign.CENTER, table.horizontalAlign)
    }

    @Test
    fun `four-side shorthands retain styles widths colors and flow constraints`() {
        val resolver = CssResolver(
            listOf(
                CssResolver.Sheet(
                    """
                    #box {
                        color: #123456;
                        background: #abcdef;
                        padding: 1em 2em 3em 4em;
                        border-width: thin medium thick 4px;
                        border-style: solid dashed dotted double;
                        border-color: currentColor #ff0000 #00ff00 #0000ff;
                        clear: both;
                        page-break-inside: avoid;
                        width: 40%;
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val element = Jsoup.parse("<div id='box'>content</div>").getElementById("box")!!
        val computed = resolver.computed(element)
        val box = resolver.publisherBoxStyle(element)!!

        assertEquals(1f, box.paddingTopEm, 0.001f)
        assertEquals(2f, box.paddingRightEm, 0.001f)
        assertEquals(3f, box.paddingBottomEm, 0.001f)
        assertEquals(4f, box.paddingLeftEm, 0.001f)
        assertEquals(0xffabcdef.toInt(), box.backgroundColorArgb)
        assertEquals(0.4f, box.widthFrac!!, 0.001f)
        assertEquals("both", computed.clear)
        assertEquals(true, computed.breakInsideAvoid)
        assertEquals(true, box.breakInsideAvoid)

        assertBorder(
            box.borderTop, 1f / 16f, 0xff123456.toInt(), PublisherBorderStyle.SOLID,
        )
        assertBorder(
            box.borderRight, 2f / 16f, 0xffff0000.toInt(), PublisherBorderStyle.DASHED,
        )
        assertBorder(
            box.borderBottom, 3f / 16f, 0xff00ff00.toInt(), PublisherBorderStyle.DOTTED,
        )
        assertBorder(
            box.borderLeft, 4f / 16f, 0xff0000ff.toInt(), PublisherBorderStyle.DOUBLE,
        )
    }

    @Test
    fun `logical padding is converted once into physical rtl edges`() {
        val resolver = CssResolver(
            listOf(
                CssResolver.Sheet(
                    """
                    #rtl {
                        direction: rtl;
                        padding-left: 1em;
                        padding-inline-start: 2em;
                        padding-inline-end: 3em;
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val element = Jsoup.parse("<div id='rtl'>نص</div>").getElementById("rtl")!!
        val box = resolver.publisherBoxStyle(element)!!

        assertEquals(3f, box.paddingLeftEm, 0.001f)
        assertEquals(2f, box.paddingRightEm, 0.001f)
    }

    @Test
    fun `horizontal percentage insets remain fractions rather than fake em`() {
        val resolver = CssResolver(
            listOf(
                CssResolver.Sheet(
                    """
                    #percent {
                        margin-left: 6%;
                        margin-right: 7%;
                        padding-left: 8%;
                        padding-right: 9%;
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val element = Jsoup.parse("<div id='percent'>content</div>")
            .getElementById("percent")!!
        val box = resolver.publisherBoxStyle(element)!!

        assertEquals(0.06f, box.marginLeftFrac, 0.001f)
        assertEquals(0.07f, box.marginRightFrac, 0.001f)
        assertEquals(0.08f, box.paddingLeftFrac, 0.001f)
        assertEquals(0.09f, box.paddingRightFrac, 0.001f)
        assertEquals(0f, box.marginLeftEm, 0.001f)
        assertEquals(0f, box.paddingLeftEm, 0.001f)
    }

    @Test
    fun `element relative lengths normalize against computed font size once`() {
        val resolver = CssResolver(
            listOf(
                CssResolver.Sheet(
                    """
                    #scaled {
                        font-size: 2em;
                        text-indent: .75em;
                        margin: .5em 1rem 1ch 8px;
                        padding: calc(.25em + 8px) 1ex;
                        border: .125em solid #123456;
                        width: 5em;
                        height: 2ch;
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val element = Jsoup.parse("<div id='scaled'>content</div>")
            .getElementById("scaled")!!
        val computed = resolver.computed(element)
        val box = resolver.publisherBoxStyle(element)!!

        assertEquals(2f, computed.fontSizeEm, 0.001f)
        assertEquals(1.5f, computed.textIndentEm!!, 0.001f)
        assertEquals(1f, box.marginTopEm, 0.001f) // .5em at 2 root-em
        assertEquals(1f, box.marginRightEm, 0.001f) // rem remains root-relative
        assertEquals(1f, box.marginBottomEm, 0.001f) // 1ch = .5 local-em
        assertEquals(0.5f, box.marginLeftEm, 0.001f) // px remains absolute
        assertEquals(1f, box.paddingTopEm, 0.001f) // .25em + 8px
        assertEquals(1f, box.paddingRightEm, 0.001f) // 1ex = .5 local-em
        assertEquals(10f, box.widthEm!!, 0.001f)
        assertEquals(2f, computed.heightEm!!, 0.001f)
        listOf(box.borderTop, box.borderRight, box.borderBottom, box.borderLeft)
            .forEach { side ->
                assertNotNull(side)
                assertEquals(0.25f, side!!.widthEm, 0.001f)
            }
    }

    @Test
    fun `semantic heading font scale also governs its own em geometry`() {
        val resolver = CssResolver(
            listOf(CssResolver.Sheet("h2 { padding-top: .5em; }")),
        )
        val heading = Jsoup.parse("<h2 id='heading'>Heading</h2>")
            .getElementById("heading")!!

        val computed = resolver.computed(heading)
        val box = resolver.publisherBoxStyle(heading)!!

        assertEquals(1.32f, computed.fontSizeEm, 0.001f)
        assertEquals(0.66f, box.paddingTopEm, 0.001f)
    }

    @Test
    fun `plain element does not allocate a publisher box style`() {
        val resolver = CssResolver(emptyList())
        val element = Jsoup.parse("<p id='plain'>text</p>").getElementById("plain")!!

        assertNull(resolver.publisherBoxStyle(element))
    }

    @Test
    fun `inline image keeps containing block alignment when lifted into native flow`() {
        val resolver = CssResolver(
            listOf(
                CssResolver.Sheet(
                    ".center { text-align:center; } .right { text-align:right; } " +
                        ".rtl-start { direction:rtl; text-align:start; } " +
                        "img { width:80%; } img.block { display:block; }",
                ),
            ),
        )
        val document = Jsoup.parse(
            "<p class='center'><img id='inline' src='diagram.png'></p>" +
                "<p class='center'><img id='block' class='block' src='diagram.png'></p>" +
                "<p class='right'><img id='right' src='diagram.png'></p>" +
                "<p class='rtl-start'><img id='rtl-start' src='diagram.png'></p>",
        )

        val inline = document.getElementById("inline")!!
        assertEquals(PublisherBoxAlign.CENTER, resolver.publisherBoxStyle(inline)?.horizontalAlign)
        assertNull(resolver.computed(inline).display)

        val block = document.getElementById("block")!!
        assertEquals(PublisherBoxAlign.START, resolver.publisherBoxStyle(block)?.horizontalAlign)
        assertEquals("block", resolver.computed(block).display)

        assertEquals(
            PublisherBoxAlign.END,
            resolver.publisherBoxStyle(document.getElementById("right")!!)?.horizontalAlign,
        )
        assertEquals(
            PublisherBoxAlign.END,
            resolver.publisherBoxStyle(document.getElementById("rtl-start")!!)?.horizontalAlign,
        )
    }

    private fun assertBorder(
        actual: com.example.frogreader.data.model.PublisherBorderSide?,
        widthEm: Float,
        colorArgb: Int,
        style: PublisherBorderStyle,
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(widthEm, actual.widthEm, 0.001f)
        assertEquals(colorArgb, actual.colorArgb)
        assertEquals(style, actual.style)
    }
}
