package com.example.frogreader.ui.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement

/**
 * Table measurement and drawing. The layout (column widths, row heights,
 * cell font scale) is computed ONCE — during pagination in paged mode, or
 * memoized locally in scroll mode — and rendering only places boxes at the
 * stored pixel sizes, so measure and render can never drift.
 */
class TableLayout(
    val grid: TableGrid.Grid,
    val colWidthsPx: IntArray,
    val rowHeightsPx: IntArray,
    /** Cell font scale chosen so the columns fit: 1, 0.85 or 0.7. */
    val fontScale: Float,
) {
    val tableWidthPx: Int get() = colWidthsPx.sum()
}

/**
 * Measures [table] for [contentWidthPx]: builds the span grid, gathers
 * per-column min (longest word) / max (single line) intrinsics, steps the
 * cell font down (1 → 0.85 → 0.7) until the minimum fits, distributes the
 * final column widths and measures row heights at them. Deterministic for
 * fixed inputs — the quick and full pagination passes agree by construction.
 */
fun measureTableLayout(
    table: ContentElement.Table,
    measurer: TextMeasurer,
    density: Density,
    settings: ReaderSettings,
    fontSize: Float,
    contentWidthPx: Int,
    language: String?,
): TableLayout = with(density) {
    val grid = TableGrid.build(table.rows)
    val columnCount = grid.columnCount.coerceAtLeast(1)
    val cellPadPx = ReaderMetrics.tableCellPadding.roundToPx()
    val availablePx = contentWidthPx.coerceAtLeast(1)

    class SpanIntrinsic(val col: Int, val span: Int, val minPx: Int, val maxPx: Int)

    var chosenScale = 1f
    var widths = IntArray(columnCount)
    for (scale in floatArrayOf(1f, 0.85f, 0.7f)) {
        chosenScale = scale
        val minW = IntArray(columnCount)
        val maxW = IntArray(columnCount)
        val spans = mutableListOf<SpanIntrinsic>()
        for ((r, row) in table.rows.withIndex()) {
            for ((ci, cell) in row.cells.withIndex()) {
                val col = grid.cellColumns[r][ci].coerceAtMost(columnCount - 1)
                val span = cell.colSpan.coerceIn(1, 10).coerceAtMost(columnCount - col)
                val style = ReaderMetrics.tableCellStyle(
                    settings, fontSize, scale, cell.header, language,
                )
                val maxIntrinsic = measurer
                    .measure(cell.text, style, constraints = Constraints())
                    .size.width + 2 * cellPadPx
                val minIntrinsic =
                    longestWordWidthPx(cell.text.text, style, measurer) + 2 * cellPadPx
                if (span <= 1) {
                    if (minIntrinsic > minW[col]) minW[col] = minIntrinsic
                    if (maxIntrinsic > maxW[col]) maxW[col] = maxIntrinsic
                } else {
                    spans += SpanIntrinsic(col, span, minIntrinsic, maxIntrinsic)
                }
            }
        }
        // Spanning cells only widen columns after plain cells settled.
        for (span in spans) {
            TableGrid.spreadSpan(minW, span.col, span.span, span.minPx)
            TableGrid.spreadSpan(maxW, span.col, span.span, span.maxPx)
        }
        for (i in 0 until columnCount) if (maxW[i] < minW[i]) maxW[i] = minW[i]
        widths = TableGrid.distributeColumns(minW, maxW, availablePx)
        var sumMin = 0L
        for (w in minW) sumMin += w
        if (sumMin <= availablePx) break // this scale fits without mid-word wraps
    }

    // Row heights at the final column widths.
    class SpanCell(val row: Int, val rowSpan: Int, val heightPx: Int)

    val rowHeights = IntArray(table.rows.size)
    val rowSpanCells = mutableListOf<SpanCell>()
    for ((r, row) in table.rows.withIndex()) {
        var height = 0
        for ((ci, cell) in row.cells.withIndex()) {
            val col = grid.cellColumns[r][ci].coerceAtMost(columnCount - 1)
            val span = cell.colSpan.coerceIn(1, 10).coerceAtMost(columnCount - col)
            var cellWidth = 0
            for (c in col until col + span) cellWidth += widths[c]
            val style = ReaderMetrics.tableCellStyle(
                settings, fontSize, chosenScale, cell.header, language,
            )
            val textHeight = measurer.measure(
                text = cell.text,
                style = style,
                constraints = Constraints(maxWidth = (cellWidth - 2 * cellPadPx).coerceAtLeast(1)),
            ).size.height
            val total = textHeight + 2 * cellPadPx
            val rowSpan = cell.rowSpan.coerceIn(1, 20).coerceAtMost(table.rows.size - r)
            if (rowSpan <= 1) {
                if (total > height) height = total
            } else {
                rowSpanCells += SpanCell(r, rowSpan, total)
            }
        }
        rowHeights[r] = height
    }
    // A rowspan cell taller than its rows: the excess goes to the LAST
    // spanned row (deterministic and keeps earlier break points stable).
    for (spanCell in rowSpanCells) {
        val end = spanCell.row + spanCell.rowSpan
        var sum = 0
        for (r in spanCell.row until end) sum += rowHeights[r]
        if (spanCell.heightPx > sum) rowHeights[end - 1] += spanCell.heightPx - sum
    }

    TableLayout(grid, widths, rowHeights, chosenScale)
}

/** Width of the longest word (top-3 by length measured; proportional-safe). */
private fun longestWordWidthPx(
    text: String,
    style: TextStyle,
    measurer: TextMeasurer,
): Int {
    if (text.isBlank()) return 0
    val candidates = text.split(' ', '\n', '\t')
        .filter { it.isNotEmpty() }
        .sortedByDescending { it.length }
        .take(3)
    var max = 0
    for (word in candidates) {
        val width = measurer
            .measure(AnnotatedString(word), style, constraints = Constraints())
            .size.width
        if (width > max) max = width
    }
    return max
}

/**
 * Draws rows [rowStart] until [rowEnd] of a measured table: absolutely
 * positioned cells at the stored pixel offsets, hairline grid borders via
 * drawBehind (zero layout impact), header row repeated on continuations.
 */
@Composable
fun TableBlock(
    table: ContentElement.Table,
    layout: TableLayout,
    rowStart: Int,
    rowEnd: Int,
    headerRepeated: Boolean,
    settings: ReaderSettings,
    fontSize: Float,
    language: String?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    class CellPlacement(
        val row: Int,
        val cellIndex: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    val placements = remember(table, layout, rowStart, rowEnd, headerRepeated) {
        val columnCount = layout.grid.columnCount.coerceAtLeast(1)
        val colOffsets = IntArray(columnCount + 1)
        for (c in 1..columnCount) colOffsets[c] = colOffsets[c - 1] + layout.colWidthsPx[c - 1]

        val drawRows = buildList {
            if (headerRepeated && rowStart > 0) add(0)
            for (r in rowStart.coerceAtLeast(0) until rowEnd.coerceAtMost(table.rows.size)) add(r)
        }
        val rowOffsets = IntArray(drawRows.size)
        var acc = 0
        for (i in drawRows.indices) {
            rowOffsets[i] = acc
            acc += layout.rowHeightsPx[drawRows[i]]
        }

        val list = mutableListOf<CellPlacement>()
        for ((visual, r) in drawRows.withIndex()) {
            val row = table.rows[r]
            for ((ci, cell) in row.cells.withIndex()) {
                val col = layout.grid.cellColumns[r][ci].coerceAtMost(columnCount - 1)
                val span = cell.colSpan.coerceIn(1, 10).coerceAtMost(columnCount - col)
                val rowSpan = cell.rowSpan.coerceIn(1, 20)
                // Visible height: the spanned rows that are on THIS page.
                val spanEnd = if (r == 0 && headerRepeated && rowStart > 0) {
                    1
                } else {
                    (r + rowSpan).coerceAtMost(rowEnd)
                }
                var height = 0
                for (rr in r until spanEnd) height += layout.rowHeightsPx[rr]
                if (height == 0) height = layout.rowHeightsPx[r]
                list += CellPlacement(
                    row = r,
                    cellIndex = ci,
                    x = colOffsets[col],
                    y = rowOffsets[visual],
                    width = colOffsets[col + span] - colOffsets[col],
                    height = height,
                )
            }
        }
        list
    }

    val borderColor = colors.secondaryText.copy(alpha = 0.35f)
    Layout(
        modifier = modifier.drawBehind {
            val stroke = Stroke(width = 1f)
            for (p in placements) {
                drawRect(
                    color = borderColor,
                    topLeft = Offset(p.x.toFloat(), p.y.toFloat()),
                    size = Size(p.width.toFloat(), p.height.toFloat()),
                    style = stroke,
                )
            }
        },
        content = {
            for (p in placements) {
                val cell = table.rows[p.row].cells[p.cellIndex]
                val align = cell.align ?: if (cell.header) BlockAlign.CENTER else null
                Text(
                    text = cell.text,
                    style = ReaderMetrics
                        .tableCellStyle(settings, fontSize, layout.fontScale, cell.header, language)
                        .copy(
                            color = colors.text,
                            textAlign = when (align) {
                                BlockAlign.CENTER -> TextAlign.Center
                                BlockAlign.END -> TextAlign.End
                                else -> TextAlign.Start
                            },
                        ),
                    overflow = TextOverflow.Clip,
                )
            }
        },
    ) { measurables, _ ->
        val pad = with(density) { ReaderMetrics.tableCellPadding.roundToPx() }
        var totalWidth = 0
        for (w in layout.colWidthsPx) totalWidth += w
        var totalHeight = 0
        if (headerRepeated && rowStart > 0) totalHeight += layout.rowHeightsPx[0]
        for (r in rowStart.coerceAtLeast(0) until rowEnd.coerceAtMost(table.rows.size)) {
            totalHeight += layout.rowHeightsPx[r]
        }

        val placeables = measurables.mapIndexed { i, measurable ->
            val p = placements[i]
            measurable.measure(
                Constraints(
                    minWidth = (p.width - 2 * pad).coerceAtLeast(1),
                    maxWidth = (p.width - 2 * pad).coerceAtLeast(1),
                    maxHeight = (p.height - 2 * pad).coerceAtLeast(1),
                ),
            )
        }
        layout(totalWidth, totalHeight) {
            placeables.forEachIndexed { i, placeable ->
                val p = placements[i]
                placeable.place(p.x + pad, p.y + pad)
            }
        }
    }
}
