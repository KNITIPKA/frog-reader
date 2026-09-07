package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.testfixtures.PublisherLayoutFixture
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CSS-cascade preconditions for the richer container-model tests. */
class PublisherLayoutCssTest {

    private val resolver = CssResolver(
        listOf(CssResolver.Sheet(PublisherLayoutFixture.css)),
    )
    private val document = Jsoup.parse(PublisherLayoutFixture.html)

    @Test
    fun `named publication font inherits through containers into mixed children`() {
        val ids = listOf(
            "publisher-panel",
            "publisher-heading",
            "publisher-caption",
            "panel-first",
            "panel-second",
            "publisher-grid",
        )

        ids.forEach { id ->
            assertEquals(
                "$id must inherit the publication font",
                PublisherLayoutFixture.FONT_FAMILY,
                resolver.computed(document.getElementById(id)!!).fontFamilyName,
            )
        }
        assertEquals(PublisherLayoutFixture.FONT_FAMILY, resolver.fontFaces.single().family)
    }

    @Test
    fun `container background stays owned by the container in the cascade`() {
        val panel = resolver.computed(document.getElementById("publisher-panel")!!)
        val firstChild = resolver.computed(document.getElementById("panel-first")!!)
        val secondChild = resolver.computed(document.getElementById("panel-second")!!)

        assertEquals(PublisherLayoutFixture.panelBackgroundArgb, panel.backgroundColorArgb)
        assertNull(firstChild.backgroundColorArgb)
        assertNull(secondChild.backgroundColorArgb)
    }

    @Test
    fun `nested float and percentage table widths survive CSS computation`() {
        val floated = resolver.computed(document.getElementById("publisher-float")!!)
        val table = resolver.computed(document.getElementById("publisher-grid")!!)
        val label = resolver.computed(document.selectFirst(".label-column")!!)
        val value = resolver.computed(document.selectFirst(".value-column")!!)

        assertEquals("left", floated.floatSide)
        assertEquals(0.38f, floated.widthFrac ?: 0f, 0.001f)
        assertEquals(0.72f, table.widthFrac ?: 0f, 0.001f)
        assertEquals(0.25f, label.widthFrac ?: 0f, 0.001f)
        assertEquals(0.75f, value.widthFrac ?: 0f, 0.001f)
    }
}
