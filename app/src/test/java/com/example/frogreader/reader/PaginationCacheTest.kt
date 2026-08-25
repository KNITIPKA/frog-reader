package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.TableGrid
import com.example.frogreader.ui.reader.TableLayout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaginationCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `same-length text spans and table styles invalidate cached geometry`() {
        val original = table("AAAA", tableScale = 1f, cellScale = null)
        val originalItems = listOf(ReaderItem(0, original))
        val cache = tempFolder.newFile("pagination.json")
        PaginationCache.save(cache, "layout", originalItems, pagesFor(original))
        assertNotNull(PaginationCache.load(cache, "layout", originalItems))

        val changedText = table("BBBB", tableScale = 1f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedText))),
        )

        val changedTableStyle = table("AAAA", tableScale = 1.4f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedTableStyle))),
        )

        val changedCellStyle = table("AAAA", tableScale = 1f, cellScale = 0.8f)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedCellStyle))),
        )

        val bold = AnnotatedString.Builder("AAAA").apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 4)
        }.toAnnotatedString()
        val changedSpan = table(bold, tableScale = 1f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedSpan))),
        )
    }

    private fun table(
        text: String,
        tableScale: Float,
        cellScale: Float?,
    ): ContentElement.Table = table(AnnotatedString(text), tableScale, cellScale)

    private fun table(
        text: AnnotatedString,
        tableScale: Float,
        cellScale: Float?,
    ): ContentElement.Table = ContentElement.Table(
        rows = listOf(
            TableRow(
                cells = listOf(
                    TableCell(text, block = cellScale?.let { BlockStyle(fontScale = it) }),
                ),
                isHeader = false,
            ),
        ),
        block = BlockStyle(fontScale = tableScale),
    )

    private fun pagesFor(table: ContentElement.Table): List<BookPage> {
        val grid = TableGrid.build(table.rows)
        val layout = TableLayout(grid, intArrayOf(100), intArrayOf(24), 1f)
        return listOf(
            BookPage(
                parts = listOf(
                    PagePart(
                        itemIndex = 0,
                        element = table,
                        rowStart = 0,
                        rowEnd = 1,
                        tableLayout = layout,
                    ),
                ),
                firstItemIndex = 0,
            ),
        )
    }
}
