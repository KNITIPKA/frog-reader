package com.example.frogreader.parser

import com.example.frogreader.testfixtures.LocalBookFixtures

import androidx.compose.ui.text.font.FontStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Optional original-package regressions; synthetic fixtures cover the same semantics in CI. */
class StandardEbooksCorpusTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun parse(name: String): com.example.frogreader.data.model.BookContent {
        val source = LocalBookFixtures.find("${name}_advanced.epub")
        assumeTrue("Original EPUB is available locally", source.isFile)
        val started = System.nanoTime()
        val content = EpubParser.parseContent(source, temporary.newFolder("images"))
        assertTrue(content.chapters.isNotEmpty())
        assertTrue(content.navigation.isNotEmpty())
        val elements = content.chapters.flatMap { it.elements }
        val images = elements.filterIsInstance<ContentElement.Image>()
        images.forEach { assertTrue("Missing extracted image ${it.path}", File(it.path).length() > 0) }
        content.fonts.forEach { assertTrue("Missing extracted font ${it.path}", File(it.path).length() > 0) }
        // All typed note links from these books must resolve to complete notes.
        elements.mapNotNull {
            when (it) {
                is ContentElement.Paragraph -> it.text
                is ContentElement.Heading -> it.styledText
                else -> null
            }
        }.forEach { text ->
            text.getStringAnnotations(com.example.frogreader.data.model.FOOTNOTE_TAG, 0, text.length)
                .forEach { assertTrue("Unresolved note ${it.item}", it.item in content.notes) }
        }
        println("EPUB_CORPUS $name chapters=${content.chapters.size} elements=${elements.size} " +
            "images=${images.size} tables=${elements.filterIsInstance<ContentElement.Table>().size} " +
            "notes=${content.notes.size} fonts=${content.fonts.size} " +
            "parseMs=${(System.nanoTime() - started) / 1_000_000}")
        return content
    }

    @Test fun `Dracula diary separators and small caps survive original CSS`() {
        val content = parse("bram-stoker_dracula")
        val paragraphs = content.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>()
        assertTrue(paragraphs.any { it.block?.fontFeatureSettings?.contains("smcp") == true ||
            it.text.spanStyles.any { span -> span.item.fontFeatureSettings?.contains("smcp") == true } })
        assertTrue(content.chapters.flatMap { it.publisherBoxes }.any { it.style.borderTop != null })
    }

    @Test fun `Flatland SVG illustrations and complete notes survive original package`() {
        val content = parse("edwin-a-abbott_flatland")
        assertEquals(4, content.notes.size)
        val images = content.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Image>()
        assertTrue(images.size >= 10)
        assertTrue(images.any { it.maxHeightViewportFrac == 0.5f })
        assertTrue(images.any { it.darkModeInvert })
    }

    @Test fun `Tangled Tale formulas tables and notes remain readable`() {
        val content = parse("lewis-carroll_a-tangled-tale")
        assertEquals(2, content.notes.size)
        val elements = content.chapters.flatMap { it.elements }
        assertEquals(3, elements.filterIsInstance<ContentElement.Table>().count { it.publisherRowLayout == null })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any { '√' in it.text.text })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any { paragraph ->
            paragraph.text.spanStyles.any { it.item.baselineShift != null }
        })
    }

    @Test fun `Looking Glass retains all illustrations verse and arithmetic rule`() {
        val content = parse("lewis-carroll_through-the-looking-glass_john-tenniel")
        val elements = content.chapters.flatMap { it.elements }
        assertTrue(elements.filterIsInstance<ContentElement.Image>().size >= 52)
        assertTrue(elements.filterIsInstance<ContentElement.Image>().any { it.darkModeBackgroundCurrentColor })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().count { it.block?.isCssTextLine == true } >= 300)
        val table = elements.filterIsInstance<ContentElement.Table>().single()
        assertEquals(3, table.rows.size)
        assertTrue(table.rows.last().cells.all { it.publisherBox?.borderTop != null })
        assertTrue(table.rows.dropLast(1).flatMap { it.cells }.all { it.publisherBox?.borderTop == null })
    }

    @Test fun `Tristram Shandy retains blackletter blank page and invisible alignment text`() {
        val content = parse("laurence-sterne_the-life-and-opinions-of-tristram-shandy-gentleman")
        assertEquals(39, content.notes.size)
        assertTrue(content.fonts.any { it.family.contains("blackletter") })
        val elements = content.chapters.flatMap { it.elements }
        assertTrue(elements.filterIsInstance<ContentElement.Spacer>().any { it.heightEm >= 10f })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any { paragraph ->
            paragraph.text.getStringAnnotations(HIDDEN_TEXT_TAG, 0, paragraph.text.length).isNotEmpty()
        })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any { paragraph ->
            paragraph.text.getStringAnnotations(INLINE_FONT_FAMILY_TAG, 0, paragraph.text.length)
                .any { it.item.contains("blackletter") }
        })
        assertTrue(content.chapters.flatMap { it.publisherBoxes }.any { it.style.pageBreakBefore && it.style.pageBreakAfter })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any { paragraph ->
            paragraph.text.text == "Agitating" &&
                paragraph.text.getStringAnnotations(INLINE_TEXT_BOX_TAG, 0, paragraph.text.length).isNotEmpty()
        })
        assertTrue(content.chapters.flatMap { it.publisherBoxes }.any { it.style.centerContentVertically &&
            it.style.minHeightViewportFrac == 0.98f && it.style.minHeightOffsetEm == -3f })
    }

    @Test fun `Exiles retains all rich dialogue rows and upright stage direction parentheses`() {
        val content = parse("james-joyce_exiles")
        val tables = content.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Table>()
        assertEquals(3, tables.size)
        assertEquals(1518, tables.sumOf { it.rows.size })
        assertEquals(listOf(682, 475, 361), tables.map { it.rows.size })
        assertTrue(tables.all { it.stackCellsBelowCssPx == 400f })
        val cells = tables.flatMap { it.rows }.flatMap { it.cells }
        assertTrue(cells.all { it.narrowStyle != null })
        assertTrue(cells.any { it.text.spanStyles.any { span -> span.item.fontStyle == FontStyle.Italic } })
        assertTrue(cells.any { it.text.spanStyles.any { span -> span.item.fontStyle == FontStyle.Normal } })
        assertTrue(cells.any { it.block?.fontFeatureSettings?.contains("c2sc") == true })
    }
}
