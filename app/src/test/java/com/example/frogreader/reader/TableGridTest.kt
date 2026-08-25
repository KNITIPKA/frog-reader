package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.parser.InlineTextBuilder
import com.example.frogreader.ui.reader.TableGrid
import com.example.frogreader.ui.reader.tableCellInlineContent
import com.example.frogreader.ui.reader.tableCellMinIntrinsicWidthPx
import com.example.frogreader.ui.reader.tableCellPlaceholders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableGridTest {

    private fun cell(colSpan: Int = 1, rowSpan: Int = 1) =
        TableCell(AnnotatedString("x"), colSpan = colSpan, rowSpan = rowSpan)

    private fun row(vararg cells: TableCell) = TableRow(cells.toList(), isHeader = false)

    @Test
    fun `plain grid occupancy`() {
        val grid = TableGrid.build(
            listOf(
                row(cell(), cell(), cell()),
                row(cell(), cell(), cell()),
            ),
        )
        assertEquals(3, grid.columnCount)
        assertArrayEquals(intArrayOf(0, 1, 2), grid.cellColumns[0])
        assertArrayEquals(intArrayOf(0, 1, 2), grid.cellColumns[1])
        assertFalse(grid.noBreakAfterRow[0])
        assertFalse(grid.noBreakAfterRow[1])
    }

    @Test
    fun `colspan shifts following cells`() {
        val grid = TableGrid.build(
            listOf(
                row(cell(colSpan = 2), cell()),
                row(cell(), cell(), cell()),
            ),
        )
        assertEquals(3, grid.columnCount)
        assertArrayEquals(intArrayOf(0, 2), grid.cellColumns[0])
        assertArrayEquals(intArrayOf(0, 1, 2), grid.cellColumns[1])
    }

    @Test
    fun `rowspan occupies the column below and forbids the break`() {
        // Classic: [A(rowspan2) | B] / [C] — C must land in column 1.
        val grid = TableGrid.build(
            listOf(
                row(cell(rowSpan = 2), cell()),
                row(cell()),
                row(cell(), cell()),
            ),
        )
        assertEquals(2, grid.columnCount)
        assertArrayEquals(intArrayOf(0, 1), grid.cellColumns[0])
        assertArrayEquals(intArrayOf(1), grid.cellColumns[1])
        assertArrayEquals(intArrayOf(0, 1), grid.cellColumns[2])
        assertTrue(grid.noBreakAfterRow[0]) // A hangs into row 1
        assertFalse(grid.noBreakAfterRow[1])
        assertFalse(grid.noBreakAfterRow[2])
    }

    @Test
    fun `distributeColumns natural width when everything fits`() {
        val widths = TableGrid.distributeColumns(
            intArrayOf(20, 30),
            intArrayOf(100, 150),
            availablePx = 400,
        )
        assertArrayEquals(intArrayOf(100, 150), widths)
    }

    @Test
    fun `distributeColumns interpolates between min and max`() {
        val widths = TableGrid.distributeColumns(
            intArrayOf(50, 50),
            intArrayOf(150, 250),
            availablePx = 200,
        )
        // slack 100 over range 300 → 50+33=83, 50+66=116, remainder 1 → left column.
        assertEquals(200, widths.sum())
        assertTrue("wider max column must get more: ${widths.toList()}", widths[1] > widths[0])
        assertTrue(widths[0] >= 50 && widths[1] >= 50)
    }

    @Test
    fun `distributeColumns proportional when even minimum overflows`() {
        val widths = TableGrid.distributeColumns(
            intArrayOf(300, 100),
            intArrayOf(600, 200),
            availablePx = 200,
        )
        assertEquals(200, widths.sum())
        assertEquals(150, widths[0])
        assertEquals(50, widths[1])
    }

    @Test
    fun `distributeColumns is deterministic with remainders`() {
        val a = TableGrid.distributeColumns(intArrayOf(10, 10, 10), intArrayOf(50, 50, 50), 100)
        val b = TableGrid.distributeColumns(intArrayOf(10, 10, 10), intArrayOf(50, 50, 50), 100)
        assertArrayEquals(a, b)
        assertEquals(100, a.sum())
    }

    @Test
    fun `spreadSpan only adds the deficit`() {
        val widths = intArrayOf(40, 40, 20)
        TableGrid.spreadSpan(widths, col = 0, span = 2, requiredPx = 100)
        assertArrayEquals(intArrayOf(50, 50, 20), widths)
        // Already satisfied → untouched.
        TableGrid.spreadSpan(widths, col = 0, span = 2, requiredPx = 90)
        assertArrayEquals(intArrayOf(50, 50, 20), widths)
    }

    @Test
    fun `rowsThatFit honors heights and barriers`() {
        val heights = intArrayOf(100, 100, 100, 100)
        val noBarriers = BooleanArray(4)
        assertEquals(2, TableGrid.rowsThatFit(heights, noBarriers, 0, 250))
        assertEquals(4, TableGrid.rowsThatFit(heights, noBarriers, 0, 1000))
        assertEquals(0, TableGrid.rowsThatFit(heights, noBarriers, 0, 50))
        assertEquals(3, TableGrid.rowsThatFit(heights, noBarriers, 1, 250))

        // A rowspan barrier after row 0: the break falls back before it.
        val barrier = booleanArrayOf(true, false, false, false)
        assertEquals(0, TableGrid.rowsThatFit(heights, barrier, 0, 150))
        assertEquals(2, TableGrid.rowsThatFit(heights, barrier, 0, 250))
    }

    @Test
    fun `table cell inline images share placeholder geometry with drawing`() {
        val builder = InlineTextBuilder()
        builder.text("Before ")
        builder.inlineImage("/tmp/table-cap.png", "decorative cap")
        builder.text(" after")
        val text = builder.build()

        val placeholders = tableCellPlaceholders(text)
        val content = tableCellInlineContent(text, invertImages = false)

        assertEquals(1, placeholders.size)
        assertEquals(setOf("/tmp/table-cap.png"), content.keys)
        assertEquals(placeholders.single().item, content.getValue("/tmp/table-cap.png").placeholder)
    }

    @Test
    fun `inline image placeholder is an unbreakable table intrinsic`() {
        val builder = InlineTextBuilder()
        builder.text("wideword ")
        builder.inlineImage("/tmp/panorama.png", "panorama")
        builder.text(" tail")
        val text = builder.build()
        var measuredPlaceholder = false

        val minWidth = tableCellMinIntrinsicWidthPx(text) { run, placeholders ->
            if (placeholders.isNotEmpty()) {
                measuredPlaceholder = true
                // A panoramic image is much wider than its one-character
                // model representation and must win the min intrinsic.
                240
            } else {
                run.length * 8
            }
        }

        assertTrue(measuredPlaceholder)
        assertEquals(240, minWidth)
    }

    @Test
    fun `many inline images select intrinsic runs in linear work`() {
        val builder = InlineTextBuilder()
        repeat(1_000) { index ->
            builder.text("w$index ")
            builder.inlineImage("/tmp/image-$index.png", null)
            builder.text(" ")
        }
        val text = builder.build()
        var measurements = 0

        tableCellMinIntrinsicWidthPx(text) { run, placeholders ->
            measurements++
            if (placeholders.isEmpty()) run.length else 100
        }

        // Each image run is measured once; the three longest ordinary-word
        // candidates add at most three callbacks.
        assertTrue(measurements <= 1_003)
    }
}
