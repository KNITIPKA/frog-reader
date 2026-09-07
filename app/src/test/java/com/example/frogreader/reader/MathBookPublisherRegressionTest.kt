package com.example.frogreader.reader

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.planPublisherChapter
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Conditional smoke test for the illustrated textbook that exposed the gap.
 * Production logic remains class/title agnostic; this test only proves that
 * the generic CSS model recognises structures from the original publication.
 */
class MathBookPublisherRegressionTest {

    private val book = File(
        "/Users/frog/Downloads/" +
            "The_math_book_big_ideas_simply_explained_Karl_Warsi;_Janet_Dangerfield;.epub",
    )

    private data class Mapped(
        val elements: List<ContentElement>,
        val boxes: List<com.example.frogreader.data.model.PublisherBoxSpan>,
    )

    private fun map(zip: ZipFile, entry: String, cssText: String): Mapped {
        val markup = zip.getInputStream(zip.getEntry(entry)).bufferedReader().use { it.readText() }
        val mapper = HtmlMapper(
            resolveImage = { source -> "/virtual/OEBPS/xhtml/$source" },
            css = CssResolver(listOf(CssResolver.Sheet(cssText, "OEBPS/styles"))),
            publisherBoxIdPrefix = entry,
        )
        val elements = mapper.map(Jsoup.parse(markup).body())
        return Mapped(elements, mapper.publisherBoxes.toList())
    }

    @Test
    fun `textbook panels rules floats images and real tables retain authored structure`() {
        assumeTrue(book.isFile && book.canRead())
        ZipFile(book).use { zip ->
            val cssText = zip.getInputStream(zip.getEntry("OEBPS/styles/stylesheet.css"))
                .bufferedReader()
                .use { it.readText() }
            val numerals = map(zip, "OEBPS/xhtml/ch02_022-027.xhtml", cssText)
            val parabolas = map(zip, "OEBPS/xhtml/ch03_028-031.xhtml", cssText)
            val tableChapter = map(zip, "OEBPS/xhtml/ch11_052-057.xhtml", cssText)

            val panelBackgrounds = (numerals.boxes + parabolas.boxes)
                .mapNotNull { it.style.backgroundColorArgb }
                .toSet()
            assertTrue(0xffe3e3e3.toInt() in panelBackgrounds)
            assertTrue(0xffd2e6c5.toInt() in panelBackgrounds)
            assertTrue(0xfff2d4e7.toInt() in panelBackgrounds)

            assertTrue((numerals.boxes + parabolas.boxes).any { box ->
                box.style.borderTop?.let { border ->
                    border.style == PublisherBorderStyle.SOLID &&
                        border.colorArgb == 0xffffffff.toInt()
                } == true
            })

            val floatBox = (numerals.boxes + parabolas.boxes).first { box ->
                box.floatSide == PublisherFloatSide.LEFT && box.style.widthEm != null
            }
            assertTrue((floatBox.style.widthEm ?: 0f) > 10f)
            val plan = planPublisherChapter(numerals.elements, numerals.boxes)
            assertTrue(plan.floatByTarget.isNotEmpty())
            assertTrue(plan.suppressedElements.isNotEmpty())

            assertTrue((numerals.elements + parabolas.elements)
                .filterIsInstance<ContentElement.Image>()
                .any { image -> kotlin.math.abs((image.widthFrac ?: 0f) - 0.9f) < 0.01f })

            val expectedContextLabels = setOf("KEY CIVILIZATIONS", "FIELD", "BEFORE", "AFTER")
            val contextLabels = parabolas.elements
                .filterIsInstance<ContentElement.Paragraph>()
                .filter { it.text.text in expectedContextLabels }
            assertEquals(expectedContextLabels, contextLabels.map { it.text.text }.toSet())
            assertTrue(contextLabels.all { it.block?.firstLineIndent == false })

            val numberGridIndex = numerals.elements.indexOfFirst { element ->
                element is ContentElement.Image && element.path.endsWith("page26-1.jpg")
            }
            assertTrue(numberGridIndex >= 0)
            assertTrue(numerals.boxes.any { box ->
                box.startElement == numberGridIndex &&
                    box.endElementExclusive == numberGridIndex + 1 &&
                    kotlin.math.abs((box.style.widthFrac ?: 0f) - 0.8f) < 0.01f &&
                    box.style.horizontalAlign == PublisherBoxAlign.CENTER
            })

            // Container backgrounds are no longer copied onto every text leaf.
            assertFalse((numerals.elements + parabolas.elements)
                .filterIsInstance<ContentElement.Paragraph>()
                .any { it.block?.backgroundColorArgb in panelBackgrounds })

            val tables = tableChapter.elements.filterIsInstance<ContentElement.Table>()
            assertTrue(tables.isNotEmpty())
            val styledCells = tables.flatMap { table -> table.rows.flatMap { it.cells } }
                .mapNotNull { it.publisherBox }
            assertTrue(styledCells.any { it.paddingLeftEm > 0f || it.paddingRightEm > 0f })
            assertTrue(styledCells.any {
                it.borderTop != null || it.borderRight != null ||
                    it.borderBottom != null || it.borderLeft != null
            })

            // The source is reflowable; no book-specific fixed page assumptions
            // are needed to retain the visual hierarchy.
            assertEquals(0, numerals.boxes.count { it.id.isBlank() })
        }
    }
}
