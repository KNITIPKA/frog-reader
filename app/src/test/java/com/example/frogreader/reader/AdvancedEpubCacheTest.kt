package com.example.frogreader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdvancedEpubCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `responsive drama stacks by CSS width independently of font scale and retains cache coordinates`() {
        val narrow = TableCellStyle(publisherPaddingSpecified = true)
        val table = ContentElement.Table(listOf(TableRow(listOf(
            TableCell(AnnotatedString("Speaker"), narrowStyle = narrow),
            TableCell(AnnotatedString("Reply"), narrowStyle = narrow)), false)),
            stackCellsBelowCssPx = 400f)
        assertTrue(table.shouldStackCells(800, Density(2f, 1.3f), true))
        assertFalse(table.shouldStackCells(802, Density(2f, 1f), true))
        assertFalse(table.shouldStackCells(640, Density(2f), false))
        val layout = TableLayout(TableGrid.build(table.rows), intArrayOf(160, 160), intArrayOf(90),
            1f, tableWidthPx = 320, stackedCellHeightsPx = listOf(intArrayOf(30, 60)))
        val file = temporary.newFile("responsive.json")
        val items = listOf(ReaderItem(0, table))
        PaginationCache.save(file, "narrow", items, listOf(BookPage(listOf(
            PagePart(0, table, rowStart = 0, rowEnd = 1, tableLayout = layout)), 0, 0)))
        val restored = requireNotNull(PaginationCache.load(file, "narrow", items)).single().parts.single()
        assertEquals(0, restored.rowStart)
        assertEquals(1, restored.rowEnd)
        assertArrayEquals(intArrayOf(30, 60), restored.tableLayout!!.stackedCellHeightsPx!!.single())
        assertNotEquals(PaginationCache.contentSignature(items), PaginationCache.contentSignature(
            listOf(ReaderItem(0, table.copy(stackCellsBelowCssPx = 300f)))))
    }

    @Test fun `inline embedded fonts resolve in shared source coordinates without leaking to siblings`() {
        val source = AnnotatedString.Builder("First Blackletter Last").apply {
            addStringAnnotation(INLINE_FONT_FAMILY_TAG, "standard blackletter", 6, 16)
        }.toAnnotatedString()
        val display = source.withBookFonts(mapOf("standard blackletter" to FontFamily.Serif))
        assertEquals(source.text, display.text)
        val span = display.spanStyles.single()
        assertEquals(6, span.start)
        assertEquals(16, span.end)
        assertEquals(FontFamily.Serif, span.item.fontFamily)
        assertSame(source, source.withBookFonts(emptyMap()))
        assertSame(source, source.withBookFonts(mapOf("standard blackletter" to FontFamily.Serif), enabled = false))
    }

    @Test fun `cached drama table layout is stored once and shared across pages`() {
        val table = ContentElement.Table(List(600) { row ->
            TableRow(listOf(TableCell(AnnotatedString("Speaker $row")), TableCell(AnnotatedString("Reply $row"))), false)
        })
        val layout = TableLayout(TableGrid.build(table.rows), intArrayOf(80, 240), IntArray(600) { 40 }, 1f)
        val pages = (0 until 600 step 10).map { start ->
            BookPage(listOf(PagePart(0, table, rowStart = start, rowEnd = start + 10, tableLayout = layout)), 0, start)
        }
        val items = listOf(ReaderItem(0, table))
        val file = temporary.newFile("pages.json")
        PaginationCache.save(file, "drama", items, pages)
        val loaded = requireNotNull(PaginationCache.load(file, "drama", items))
        assertEquals(60, loaded.size)
        assertEquals(1, Regex("\\\"rowH\\\"").findAll(file.readText()).count())
        assertTrue(file.length() < 30_000)
        val first = loaded.first().parts.single().tableLayout
        loaded.forEach { assertSame(first, it.parts.single().tableLayout) }
        assertArrayEquals(layout.rowHeightsPx, requireNotNull(first).rowHeightsPx)
    }

    @Test fun `hidden alignment text stays transparent in both typography modes`() {
        val source = AnnotatedString.Builder("HiddenVisible").apply {
            addStringAnnotation(HIDDEN_TEXT_TAG, "hidden", 0, 6)
            addStyle(SpanStyle(color = Color.Red), 0, 6)
        }.toAnnotatedString()
        val pair = publisherColorPair(null, true, Color.Black, Color.White)
        for (publisher in listOf(false, true)) {
            val display = source.withPublisherColors(publisher, pair)
            assertEquals(source.text, display.text)
            assertEquals(Color.Transparent, display.spanStyles.last().item.color)
            assertEquals(0, display.spanStyles.last().start)
            assertEquals(6, display.spanStyles.last().end)
        }
    }

    @Test fun `image caps hidden scopes and CSS row arrangements invalidate cached geometry`() {
        fun hash(element: ContentElement) = PaginationCache.contentSignature(listOf(ReaderItem(0, element)))
        val image = ContentElement.Image("/missing/illustration.svg")
        assertNotEquals(hash(image), hash(image.copy(maxHeightViewportFrac = 0.5f)))
        assertNotEquals(hash(image), hash(image.copy(maxWidthEm = 25f)))
        val plain = ContentElement.Paragraph(AnnotatedString("ghost"))
        val hidden = plain.copy(text = AnnotatedString.Builder(plain.text).apply {
            addStringAnnotation(HIDDEN_TEXT_TAG, "hidden", 0, 5)
        }.toAnnotatedString())
        assertNotEquals(hash(plain), hash(hidden))
        val table = ContentElement.Table(listOf(TableRow(listOf(TableCell(AnnotatedString("*"))), false)))
        assertNotEquals(hash(table), hash(table.copy(publisherRowLayout = PublisherRowLayout.SPACE_BETWEEN)))
    }

    @Test fun `interval sweep preserves many disjoint boxes and shared enclosing ranges`() {
        val elements = List(4000) { ContentElement.Paragraph(AnnotatedString("Line $it")) }
        val spans = (0 until 4000).map { index ->
            PublisherBoxSpan("line-$index", parentId = "outer", startElement = index, endElementExclusive = index + 1,
                style = PublisherBoxStyle(paddingLeftEm = 1f))
        } + PublisherBoxSpan("outer", startElement = 0, endElementExclusive = 4000)
        val plan = planPublisherChapter(elements, spans)
        for (index in elements.indices) {
            assertEquals(listOf("outer", "line-$index"), plan.boxesByElement[index].map { it.id })
            assertTrue(plan.boxesByElement[index].last().startsAtElement)
            assertTrue(plan.boxesByElement[index].last().endsAtElement)
        }
    }
}
