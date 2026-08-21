package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.TableGrid
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
}
