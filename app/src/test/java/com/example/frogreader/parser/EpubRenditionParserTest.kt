package com.example.frogreader.parser

import com.example.frogreader.data.model.PagePlacement
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PrimaryWritingMode
import com.example.frogreader.data.model.PublisherFormat
import com.example.frogreader.data.model.PublisherProfile
import com.example.frogreader.data.model.PublisherViewportDimension
import com.example.frogreader.data.model.RenditionLayout
import com.example.frogreader.data.model.RenditionOrientation
import com.example.frogreader.data.model.RenditionSpread
import com.example.frogreader.data.model.ViewportSource
import com.example.frogreader.data.parser.EpubRenditionParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EpubRenditionParserTest {

    @Test
    fun `EPUB 3 defaults are reflowable auto and preserve declared progression`() {
        val parsed = packageRendition(version = "3.3", spineAttributes = "page-progression-direction='RTL'")

        assertSame(PublisherFormat.EPUB_3, parsed.format)
        assertSame(PublisherProfile.EPUB_STANDARD, parsed.profile)
        assertSame(RenditionLayout.REFLOWABLE, parsed.defaults.layout)
        assertSame(RenditionOrientation.AUTO, parsed.defaults.orientation)
        assertSame(RenditionSpread.AUTO, parsed.defaults.spread)
        assertSame(PageProgression.RTL, parsed.pageProgression)
        assertNull(parsed.defaults.viewport)
        assertNull(parsed.defaults.primaryWritingMode)
    }

    @Test
    fun `namespace-prefixed OPF elements are parsed by local name`() {
        val parsed = EpubRenditionParser.parsePackage(
            xml(
                """
                    <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="3.0">
                      <opf:metadata>
                        <opf:meta property="rendition:layout">pre-paginated</opf:meta>
                      </opf:metadata>
                      <opf:manifest/>
                      <opf:spine page-progression-direction="rtl"/>
                    </opf:package>
                """,
            ),
        )

        assertSame(PublisherFormat.EPUB_3, parsed.format)
        assertSame(RenditionLayout.PRE_PAGINATED, parsed.defaults.layout)
        assertSame(PageProgression.RTL, parsed.pageProgression)
    }

    @Test
    fun `global EPUB rendition metadata preserves deprecated portrait-only spread`() {
        val parsed = packageRendition(
            metadata = """
                <meta property="rendition:layout"> PRE-PAGINATED </meta>
                <meta property="rendition:orientation"> landscape </meta>
                <meta property="rendition:spread"> portrait </meta>
            """,
        )

        assertSame(RenditionLayout.PRE_PAGINATED, parsed.defaults.layout)
        assertSame(RenditionOrientation.LANDSCAPE, parsed.defaults.orientation)
        assertSame(RenditionSpread.PORTRAIT, parsed.defaults.spread)
    }

    @Test
    fun `first invalid global declaration degrades to spec default deterministically`() {
        val parsed = packageRendition(
            metadata = """
                <meta property="rendition:layout">fixed</meta>
                <meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:orientation">sideways</meta>
                <meta property="rendition:spread">wide</meta>
            """,
        )

        assertSame(RenditionLayout.REFLOWABLE, parsed.defaults.layout)
        assertSame(RenditionOrientation.AUTO, parsed.defaults.orientation)
        assertSame(RenditionSpread.AUTO, parsed.defaults.spread)
    }

    @Test
    fun `valid itemref overrides package rendition and content viewport`() {
        val parsed = packageRendition(
            metadata = """
                <meta property="rendition:layout">reflowable</meta>
                <meta property="rendition:orientation">portrait</meta>
                <meta property="rendition:spread">none</meta>
            """,
        )
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml("""<html><head><meta name="viewport" content="width=800,height=1200"/></head></html>"""),
        )
        val resolved = parsed.resolveItemRef(
            itemRef(
                "rendition:layout-pre-paginated rendition:orientation-landscape " +
                    "rendition:spread-both rendition:page-spread-right",
            ),
            viewport,
        )

        assertSame(RenditionLayout.PRE_PAGINATED, resolved.layout)
        assertSame(RenditionOrientation.LANDSCAPE, resolved.orientation)
        assertSame(RenditionSpread.BOTH, resolved.spread)
        assertSame(PagePlacement.RIGHT, resolved.placement)
        assertEquals(800f, resolved.viewport?.widthCssPx)
        assertEquals(1200f, resolved.viewport?.heightCssPx)
    }

    @Test
    fun `itemref deprecated portrait spread remains portrait-only`() {
        val resolved = packageRendition(
            metadata = """<meta property="rendition:spread">both</meta>""",
        ).resolveItemRef(itemRef("rendition:spread-portrait"))

        assertSame(RenditionSpread.PORTRAIT, resolved.spread)
    }

    @Test
    fun `conflicting itemref overrides fall back without arbitrary token ordering`() {
        val parsed = packageRendition(
            metadata = """
                <meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:orientation">landscape</meta>
                <meta property="rendition:spread">none</meta>
            """,
        )
        val resolved = parsed.resolveItemRef(
            itemRef(
                "rendition:layout-reflowable rendition:layout-pre-paginated " +
                    "rendition:orientation-auto rendition:orientation-portrait " +
                    "rendition:spread-auto rendition:spread-both",
            ),
        )

        assertSame(RenditionLayout.PRE_PAGINATED, resolved.layout)
        assertSame(RenditionOrientation.LANDSCAPE, resolved.orientation)
        assertSame(RenditionSpread.NONE, resolved.spread)
    }

    @Test
    fun `standard page placement wins over Amazon aliases and conflicts resolve to auto`() {
        val parsed = packageRendition()

        assertSame(
            PagePlacement.CENTER,
            parsed.resolveItemRef(
                itemRef("page-spread-left rendition:page-spread-center layout-blank"),
            ).placement,
        )
        assertEquals(
            true,
            parsed.resolveItemRef(itemRef("layout-blank")).layoutBlank,
        )
        assertSame(
            PagePlacement.LEFT,
            parsed.resolveItemRef(itemRef("page-spread-left facing-page-left")).placement,
        )
        assertSame(
            PagePlacement.AUTO,
            parsed.resolveItemRef(
                itemRef(
                    "rendition:page-spread-left rendition:page-spread-right page-spread-left",
                ),
            ).placement,
        )
        assertSame(
            PagePlacement.AUTO,
            parsed.resolveItemRef(itemRef("page-spread-left facing-page-right")).placement,
        )
    }

    @Test
    fun `Amazon EPUB 2 fixed profile maps resolution orientation and writing mode`() {
        val parsed = packageRendition(
            version = "2.0",
            metadata = """
                <meta name="fixed-layout" content="TRUE"/>
                <meta name="original-resolution" content=" 1024 X 600 "/>
                <meta name="orientation-lock" content="landscape"/>
                <meta name="primary-writing-mode" content="vertical-rl"/>
            """,
            spineAttributes = "page-progression-direction='ltr'",
        )

        assertSame(PublisherFormat.EPUB_2, parsed.format)
        assertSame(PublisherProfile.AMAZON_EPUB2_FIXED, parsed.profile)
        assertSame(RenditionLayout.PRE_PAGINATED, parsed.defaults.layout)
        assertSame(RenditionOrientation.LANDSCAPE, parsed.defaults.orientation)
        assertSame(PrimaryWritingMode.VERTICAL_RL, parsed.defaults.primaryWritingMode)
        assertSame(PageProgression.LTR, parsed.pageProgression)
        assertEquals(1024f, parsed.defaults.viewport?.widthCssPx)
        assertEquals(600f, parsed.defaults.viewport?.heightCssPx)
        assertSame(ViewportSource.AMAZON_ORIGINAL_RESOLUTION, parsed.defaults.viewport?.source)
    }

    @Test
    fun `Amazon EPUB 2 invalid values degrade without inventing fixed layout`() {
        val parsed = packageRendition(
            version = "2.0",
            metadata = """
                <meta name="fixed-layout" content="yes"/>
                <meta name="original-resolution" content="0x600"/>
                <meta name="orientation-lock" content="diagonal"/>
                <meta name="primary-writing-mode" content="upwards"/>
            """,
            spineAttributes = "page-progression-direction='sideways'",
        )

        assertSame(PublisherProfile.EPUB_STANDARD, parsed.profile)
        assertSame(RenditionLayout.REFLOWABLE, parsed.defaults.layout)
        assertSame(RenditionOrientation.AUTO, parsed.defaults.orientation)
        assertNull(parsed.defaults.viewport)
        assertNull(parsed.defaults.primaryWritingMode)
        assertSame(PageProgression.DEFAULT, parsed.pageProgression)
    }

    @Test
    fun `EPUB 3 ignores Amazon EPUB 2 layout metadata`() {
        val parsed = packageRendition(
            version = "3.0",
            metadata = """
                <meta name="fixed-layout" content="true"/>
                <meta name="original-resolution" content="1024x600"/>
                <meta name="orientation-lock" content="portrait"/>
                <meta name="primary-writing-mode" content="horizontal-rl"/>
            """,
        )

        assertSame(PublisherProfile.EPUB_STANDARD, parsed.profile)
        assertSame(RenditionLayout.REFLOWABLE, parsed.defaults.layout)
        assertSame(RenditionOrientation.AUTO, parsed.defaults.orientation)
        assertNull(parsed.defaults.viewport)
        assertNull(parsed.defaults.primaryWritingMode)
    }

    @Test
    fun `Amazon documented writing modes and orientation none are accepted`() {
        val expectations = mapOf(
            "horizontal-lr" to PrimaryWritingMode.HORIZONTAL_LR,
            "horizontal-rl" to PrimaryWritingMode.HORIZONTAL_RL,
            "vertical-lr" to PrimaryWritingMode.VERTICAL_LR,
            "verticalrl" to PrimaryWritingMode.VERTICAL_RL,
        )
        for ((raw, expected) in expectations) {
            val parsed = packageRendition(
                version = "2.0",
                metadata = """
                    <meta name="fixed-layout" content="true"/>
                    <meta name="orientation-lock" content="none"/>
                    <meta name="primary-writing-mode" content="$raw"/>
                """,
            )
            assertSame(expected, parsed.defaults.primaryWritingMode)
            assertSame(RenditionOrientation.AUTO, parsed.defaults.orientation)
        }
    }

    @Test
    fun `XHTML viewport uses first head meta and first strict numeric declarations`() {
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml(
                """
                    <html><head>
                      <meta name="VIEWPORT"
                            content="initial-scale=1; width=1200, width=999, height=9e2, height=1"/>
                      <meta name="viewport" content="width=1,height=1"/>
                    </head></html>
                """,
            ),
        )

        assertEquals(1200f, viewport?.widthCssPx)
        assertEquals(900f, viewport?.heightCssPx)
        assertSame(ViewportSource.XHTML_META, viewport?.source)
        assertEquals(false, viewport?.inferred)
    }

    @Test
    fun `XHTML viewport represents device-relative dimensions explicitly`() {
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml(
                """
                    <html><head>
                      <meta name="viewport" content="width=device-width,height=device-height"/>
                    </head></html>
                """,
            ),
        )

        assertSame(PublisherViewportDimension.Device, viewport?.width)
        assertSame(PublisherViewportDimension.Device, viewport?.height)
        assertNull(viewport?.widthCssPx)
        assertNull(viewport?.heightCssPx)
        assertEquals(false, viewport?.inferred)
    }

    @Test
    fun `XHTML exact px suffix is an explicit compatibility inference`() {
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml(
                """
                    <html><head>
                      <meta name="viewport" content="width=1200px,height=900 PX"/>
                    </head></html>
                """,
            ),
        )

        assertEquals(1200f, viewport?.widthCssPx)
        assertEquals(900f, viewport?.heightCssPx)
        assertEquals(true, viewport?.inferred)
    }

    @Test
    fun `namespace-prefixed XHTML viewport is found by local name`() {
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml(
                """
                    <x:html xmlns:x="http://www.w3.org/1999/xhtml">
                      <x:head><x:meta name="viewport" content="width=640,height=960"/></x:head>
                    </x:html>
                """,
            ),
        )

        assertEquals(640f, viewport?.widthCssPx)
        assertEquals(960f, viewport?.heightCssPx)
    }

    @Test
    fun `invalid first XHTML viewport never borrows a later viewport`() {
        val documents = listOf(
            """<html><head><meta name="viewport" content="width=1200junk,height=600"/><meta name="viewport" content="width=800,height=600"/></head></html>""",
            """<html><head><meta name="viewport" content="width=800,height=9e2junk"/></head></html>""",
            """<html><head><meta name="viewport" content="width=device-height,height=600"/></head></html>""",
            """<html><head><meta name="viewport" content="width=800,height=device-width"/></head></html>""",
            """<html><head><meta name="viewport" content="width=0,height=600"/></head></html>""",
            """<html><head><meta name="viewport" content="width=-800,height=600"/></head></html>""",
            """<html><head><meta name="viewport" content="width=800"/></head></html>""",
        )

        documents.forEach { assertNull(EpubRenditionParser.parseXhtmlViewport(xml(it))) }
    }

    @Test
    fun `XHTML viewport metadata outside head is ignored`() {
        val viewport = EpubRenditionParser.parseXhtmlViewport(
            xml(
                """
                    <html>
                      <head><title>Fixed page</title></head>
                      <body><meta name="viewport" content="width=800,height=600"/></body>
                    </html>
                """,
            ),
        )

        assertNull(viewport)
    }

    @Test
    fun `SVG viewBox supplies dimensions with comma exponent and arbitrary origin`() {
        val viewport = EpubRenditionParser.parseSvgViewport(
            xml("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="-12.5, 20, 8.44e2, 1.2E3"/>"""),
        )

        assertEquals(844f, viewport?.widthCssPx)
        assertEquals(1200f, viewport?.heightCssPx)
        assertSame(ViewportSource.SVG_VIEWBOX, viewport?.source)
    }

    @Test
    fun `namespace-prefixed SVG root is found by local name`() {
        val viewport = EpubRenditionParser.parseSvgViewport(
            xml(
                """<s:svg xmlns:s="http://www.w3.org/2000/svg" viewBox="0 0 320 480"/>""",
            ),
        )

        assertEquals(320f, viewport?.widthCssPx)
        assertEquals(480f, viewport?.heightCssPx)
    }

    @Test
    fun `invalid SVG viewBox does not fall back to width and height attributes`() {
        val documents = listOf(
            """<svg width="800" height="600"/>""",
            """<svg viewBox="0 0 0 600"/>""",
            """<svg viewBox="0 0 -800 600"/>""",
            """<svg viewBox="0 0 800 600 extra"/>""",
            """<svg viewBox="0 0 800"/>""",
            """<svg viewBox="0,,0,800,600"/>""",
            """<svg viewBox="0,0,800,600,"/>""",
        )

        documents.forEach { assertNull(EpubRenditionParser.parseSvgViewport(xml(it))) }
    }

    @Test
    fun `occurrence ids are stable unambiguous and duplicate resources stay distinct`() {
        val first = EpubRenditionParser.occurrenceId(2, "ref:m1", "same-resource")
        val same = EpubRenditionParser.occurrenceId(2, "ref:m1", "same-resource")
        val duplicate = EpubRenditionParser.occurrenceId(3, "ref:m1", "same-resource")
        val delimiterVariant = EpubRenditionParser.occurrenceId(2, "ref", "m1:m13:same-resource")

        assertEquals(first, same)
        assertNotEquals(first, duplicate)
        assertNotEquals(first, delimiterVariant)
        assertEquals(
            "s000002:r6:ref:m1:m13:same-resource",
            first,
        )
    }

    private fun packageRendition(
        version: String = "3.0",
        metadata: String = "",
        spineAttributes: String = "",
    ): EpubRenditionParser.PackageRendition = EpubRenditionParser.parsePackage(
        xml(
            """
                <package xmlns="http://www.idpf.org/2007/opf" version="$version">
                  <metadata>$metadata</metadata>
                  <manifest/>
                  <spine $spineAttributes><itemref idref="chapter"/></spine>
                </package>
            """,
        ),
    )

    private fun itemRef(properties: String) =
        xml("""<itemref id="occurrence" idref="chapter" properties="$properties"/>""")
            .getAllElements()
            .first { it.normalName() == "itemref" }

    private fun xml(markup: String): Document = Jsoup.parse(markup, "", Parser.xmlParser())
}
