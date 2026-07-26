package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.TableRow

/**
 * Pure table-grid logic: colspan/rowspan occupancy, column-width
 * distribution and row packing for page breaks. No Compose dependencies, so
 * every decision is JVM-testable and deterministic — the same guarantees the
 * text pagination gets from [BreakRules].
 */
object TableGrid {

    class Grid(
        val columnCount: Int,
        /** `cellColumns[row][cellIndex]` → starting column of that cell. */
        val cellColumns: List<IntArray>,
        /** True when a page break must NOT fall after this row (an active rowspan). */
        val noBreakAfterRow: BooleanArray,
    )

    /** Standard occupancy-carry layout of the rows into grid columns. */
    fun build(rows: List<TableRow>): Grid {
        val cellColumns = ArrayList<IntArray>(rows.size)
        val noBreak = BooleanArray(rows.size)
        var pending = IntArray(8) // pending[col] = rows below still occupied
        var columnCount = 0

        fun ensure(size: Int) {
            if (pending.size < size) pending = pending.copyOf(maxOf(size, pending.size * 2))
        }

        for ((rowIndex, row) in rows.withIndex()) {
            // 1. Place this row's cells, skipping columns still occupied by
            //    rowspans hanging down from earlier rows.
            val columns = IntArray(row.cells.size)
            var col = 0
            val newSpans = mutableListOf<Pair<Int, Int>>() // col range start/end
            var newSpanRows = mutableListOf<Int>()
            for ((cellIndex, cell) in row.cells.withIndex()) {
                ensure(col + 1)
                while (col < pending.size && pending[col] > 0) col++
                columns[cellIndex] = col
                val span = cell.colSpan.coerceIn(1, 10)
                val rowSpan = cell.rowSpan.coerceIn(1, 20)
                ensure(col + span)
                if (rowSpan > 1) {
                    newSpans += col to (col + span)
                    newSpanRows += rowSpan - 1
                }
                col += span
                if (col > columnCount) columnCount = col
            }
            cellColumns += columns
            // 2. Old carries now hang one row less below.
            for (c in pending.indices) if (pending[c] > 0) pending[c]--
            // 3. This row's own rowspans start hanging below it.
            for (i in newSpans.indices) {
                val (from, until) = newSpans[i]
                for (c in from until until) {
                    pending[c] = maxOf(pending[c], newSpanRows[i])
                }
            }
            // 4. A break is unsafe while anything hangs below this row.
            noBreak[rowIndex] = pending.any { it > 0 }
        }
        return Grid(columnCount, cellColumns, noBreak)
    }

    /**
     * Final column widths for [availablePx]. Three regimes: the table's
     * natural width when it fits; linear interpolation between min and max
     * intrinsics when only the minimum fits; proportional-to-minimum when
     * even the minimum overflows (cells then wrap mid-word as a last
     * resort). Integer math throughout — measure and render cannot drift.
     */
    fun distributeColumns(minW: IntArray, maxW: IntArray, availablePx: Int): IntArray {
        val n = minW.size
        if (n == 0) return IntArray(0)
        var sumMin = 0L
        var sumMax = 0L
        for (i in 0 until n) {
            sumMin += minW[i]
            sumMax += maxW[i]
        }
        if (sumMax <= availablePx) return maxW.copyOf() // natural width

        val out = IntArray(n)
        if (sumMin <= availablePx) {
            val slack = availablePx - sumMin
            val range = sumMax - sumMin
            if (range <= 0L) {
                for (i in 0 until n) out[i] = (availablePx.toLong() / n).toInt()
            } else {
                for (i in 0 until n) {
                    out[i] = (minW[i] + slack * (maxW[i] - minW[i]) / range).toInt()
                }
            }
        } else if (sumMin > 0L) {
            for (i in 0 until n) {
                out[i] = (availablePx.toLong() * minW[i] / sumMin).toInt()
            }
        } else {
            for (i in 0 until n) out[i] = (availablePx.toLong() / n).toInt()
        }
        // Hand out leftover pixels one by one, left to right.
        var used = 0L
        for (w in out) used += w
        var i = 0
        while (used < availablePx) {
            out[i % n]++
            used++
            i++
        }
        return out
    }

    /**
     * Distributes a spanning cell's intrinsic width over its columns:
     * any deficit beyond what the columns already carry is split evenly.
     */
    fun spreadSpan(into: IntArray, col: Int, span: Int, requiredPx: Int) {
        if (col >= into.size) return
        val end = minOf(col + span, into.size)
        if (end <= col) return
        var current = 0
        for (c in col until end) current += into[c]
        val deficit = requiredPx - current
        if (deficit <= 0) return
        val count = end - col
        val each = deficit / count
        var extra = deficit % count
        for (c in col until end) {
            into[c] += each + if (extra > 0) { extra--; 1 } else 0
        }
    }

    /**
     * Greedy packing: how many rows from [from] fit into [remainingPx],
     * honoring rowspan break barriers. Returns the end-exclusive row of the
     * last allowed break point; == [from] when nothing (safely) fits.
     */
    fun rowsThatFit(
        rowHeightsPx: IntArray,
        noBreakAfterRow: BooleanArray,
        from: Int,
        remainingPx: Int,
    ): Int {
        var used = 0L
        var end = from
        var lastSafe = from
        while (end < rowHeightsPx.size) {
            used += rowHeightsPx[end]
            if (used > remainingPx) break
            end++
            if (end >= rowHeightsPx.size || !noBreakAfterRow[end - 1]) lastSafe = end
        }
        return lastSafe
    }
}
