package com.example.frogreader.ui.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.ui.reader.selection.ReaderHighlights
import com.example.frogreader.ui.reader.selection.SelectionText
import com.example.frogreader.ui.reader.selection.readerHighlights
import com.example.frogreader.ui.reader.selection.rememberTextFragment
import kotlin.math.roundToInt

/** The reader supplies a grid only when the publication leaves borders unspecified. */
internal enum class TableCellBorders { PUBLISHER, READER_GRID, NONE }

internal fun tableCellBorders(
    table: ContentElement.Table,
    cell: com.example.frogreader.data.model.TableCell,
    publisherStyles: Boolean,
    stacked: Boolean = false,
): TableCellBorders {
    val box = cell.publisherBox
    val visiblePublisherBorder = box?.let {
        it.borderTop != null || it.borderRight != null ||
            it.borderBottom != null || it.borderLeft != null
    } == true
    if (publisherStyles && (cell.publisherBorderSpecified || visiblePublisherBorder)) {
        return TableCellBorders.PUBLISHER
    }
    // Synthetic flex/poetry rows and authored stacked cells are text layouts.
    if (table.publisherRowLayout != null || stacked) return TableCellBorders.NONE
    return TableCellBorders.READER_GRID
}

private data class TableCellPaddingPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private fun tableCellPaddingPx(
    cell: com.example.frogreader.data.model.TableCell,
    settings: ReaderSettings,
    fontSizePx: Float,
    referenceWidthPx: Int,
    defaultPx: Int,
    includeMargins: Boolean = false,
): TableCellPaddingPx {
    if (!settings.bookStyles) {
        return TableCellPaddingPx(defaultPx, defaultPx, defaultPx, defaultPx)
    }
    val box = cell.publisherBox ?: com.example.frogreader.data.model.PublisherBoxStyle.DEFAULT
    val hasVisibleAuthoredPadding = (
        box.paddingLeftEm > 0f || box.paddingLeftFrac > 0f ||
            box.paddingRightEm > 0f || box.paddingRightFrac > 0f ||
            box.paddingTopEm > 0f || box.paddingBottomEm > 0f
        )
    if (!cell.publisherPaddingSpecified && !hasVisibleAuthoredPadding && !includeMargins) {
        return TableCellPaddingPx(defaultPx, defaultPx, defaultPx, defaultPx)
    }
    fun horizontal(em: Float, fraction: Float): Int =
        (em.coerceAtLeast(0f) * fontSizePx +
            fraction.coerceIn(0f, 0.45f) * referenceWidthPx)
            .roundToInt()
            .coerceAtLeast(0)
    return TableCellPaddingPx(
        left = horizontal(box.paddingLeftEm, box.paddingLeftFrac),
        top = ((box.paddingTopEm.coerceAtLeast(0f) +
            if (includeMargins) box.marginTopEm.coerceAtLeast(0f) else 0f) * fontSizePx).roundToInt(),
        right = horizontal(box.paddingRightEm, box.paddingRightFrac),
        bottom = ((box.paddingBottomEm.coerceAtLeast(0f) +
            if (includeMargins) box.marginBottomEm.coerceAtLeast(0f) else 0f) * fontSizePx).roundToInt(),
    )
}

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
    /** Physical starts of columns plus the outer table end, including CSS gaps. */
    val columnOffsetsPx: IntArray = tableColumnGeometry(colWidthsPx, colWidthsPx.sum(), null).offsetsPx,
    val tableWidthPx: Int = columnOffsetsPx.lastOrNull() ?: 0,
    /** Per-source-cell heights, when an authored narrow media query is active. */
    val stackedCellHeightsPx: List<IntArray>? = null,
) {
}

internal fun ContentElement.Table.shouldStackCells(widthPx: Int, density: Density, enabled: Boolean): Boolean {
    val limit = stackCellsBelowCssPx ?: return false
    return enabled && limit.isFinite() && limit > 0f && widthPx / density.density <= limit &&
        rows.all { row -> row.cells.all { it.narrowStyle != null && it.colSpan == 1 && it.rowSpan == 1 } }
}

private fun com.example.frogreader.data.model.TableCell.forTableLayout(stacked: Boolean):
    com.example.frogreader.data.model.TableCell {
    val style = narrowStyle?.takeIf { stacked } ?: return this
    return com.example.frogreader.data.model.TableCell(text, colSpan, rowSpan, style.align, header,
        style.block, style.publisherBox, style.publisherPaddingSpecified, style.publisherBorderSpecified)
}

internal data class TableColumnGeometry(val offsetsPx: IntArray, val widthPx: Int)

/** CSS row distribution adds gaps between cells rather than widening their text. */
internal fun tableColumnGeometry(
    columnWidthsPx: IntArray,
    availablePx: Int,
    rowLayout: PublisherRowLayout?,
): TableColumnGeometry {
    val count = columnWidthsPx.size
    val natural = columnWidthsPx.sum()
    val free = (availablePx - natural).coerceAtLeast(0)
    val fillsWidth = count > 0 && rowLayout != null && rowLayout != PublisherRowLayout.NATURAL
    val offsets = IntArray(count + 1)
    var contentBefore = 0
    for (column in columnWidthsPx.indices) {
        val spaceBefore = when (rowLayout) {
            PublisherRowLayout.SPACE_BETWEEN -> if (count > 1) {
                (free.toLong() * column / (count - 1)).toInt()
            } else 0
            PublisherRowLayout.SPACE_AROUND ->
                (free.toLong() * (column * 2 + 1) / (count * 2)).toInt()
            PublisherRowLayout.NATURAL, null -> 0
        }
        offsets[column] = contentBefore + spaceBefore
        contentBefore += columnWidthsPx[column]
    }
    val width = if (fillsWidth) maxOf(availablePx, natural) else natural
    offsets[count] = width
    return TableColumnGeometry(offsets, width)
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
    bookFonts: Map<String, FontFamily> = emptyMap(),
): TableLayout = with(density) {
    val grid = TableGrid.build(table.rows)
    val columnCount = grid.columnCount.coerceAtLeast(1)
    val defaultCellPadPx = if (table.publisherRowLayout != null) 0 else {
        ReaderMetrics.tableCellPadding.roundToPx()
    }
    val fontSizePx = fontSize.sp.toPx()
    val availablePx = contentWidthPx.coerceAtLeast(1)

    if (table.shouldStackCells(availablePx, density, settings.bookStyles)) {
        val heights = table.rows.map { row ->
            row.cells.map { sourceCell ->
                val cell = sourceCell.forTableLayout(stacked = true)
                if (cell.text.isEmpty()) return@map 0
                val padding = tableCellPaddingPx(cell, settings, fontSizePx, availablePx, 0,
                    includeMargins = true)
                val style = ReaderMetrics.tableCellStyle(settings, fontSize, 1f, cell.header,
                    language, table.block, cell.block, bookFonts)
                val bidi = BidiLayoutText.of(cell.text)
                measurer.measureReader(bidi.display, style,
                    constraints = Constraints(maxWidth =
                        (availablePx - padding.left - padding.right).coerceAtLeast(1)),
                    density = density,
                    bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles,
                ).size.height +
                    padding.top + padding.bottom
            }.toIntArray()
        }
        val columns = IntArray(columnCount) { availablePx / columnCount }
        columns[0] += availablePx % columnCount
        return@with TableLayout(grid, columns, heights.map { it.sum() }.toIntArray(), 1f,
            tableWidthPx = availablePx, stackedCellHeightsPx = heights)
    }

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
                val padding = tableCellPaddingPx(
                    cell,
                    settings,
                    fontSizePx,
                    availablePx,
                    defaultCellPadPx,
                )
                val style = ReaderMetrics.tableCellStyle(
                    settings, fontSize, scale, cell.header, language,
                    table.block, cell.block, bookFonts,
                    semanticFontScale = if (table.publisherRowLayout != null) 1f else 0.92f,
                )
                val bidiCell = BidiLayoutText.of(cell.text)
                val maxIntrinsic = measurer
                    .measureReader(
                        bidiCell.display,
                        style,
                        constraints = Constraints(),
                        density = density,
                        bookFonts = bookFonts,
                        publisherStyles = settings.bookStyles,
                    )
                    .size.width + padding.left + padding.right
                val minIntrinsic = tableCellMinIntrinsicWidthPx(cell.text) { run, _ ->
                    val bidiRun = BidiLayoutText.of(run)
                    measurer.measureReader(
                        text = bidiRun.display,
                        style = style,
                        constraints = Constraints(),
                        density = density,
                        bookFonts = bookFonts,
                        publisherStyles = settings.bookStyles,
                    ).size.width
                } + padding.left + padding.right
                if (span <= 1) {
                    val maximumWidth = maximumTableCellWidthPx(
                        cell.publisherBox, settings.bookStyles, availablePx, fontSizePx,
                    )
                    val constrainedMin = minOf(minIntrinsic, maximumWidth ?: Int.MAX_VALUE)
                    val constrainedMax = minOf(maxIntrinsic, maximumWidth ?: Int.MAX_VALUE)
                    val authoredWidth = authoredTableCellWidthPx(
                        box = cell.publisherBox,
                        enabled = settings.bookStyles,
                        availableWidthPx = availablePx,
                        fontSizePx = fontSizePx,
                    )
                    if (constrainedMin > minW[col]) minW[col] = constrainedMin
                    if (constrainedMax > maxW[col]) maxW[col] = constrainedMax
                    if (authoredWidth != null) {
                        if (authoredWidth > minW[col]) minW[col] = authoredWidth
                        if (authoredWidth > maxW[col]) maxW[col] = authoredWidth
                    }
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
            val padding = tableCellPaddingPx(
                cell,
                settings,
                fontSizePx,
                cellWidth,
                defaultCellPadPx,
            )
            val style = ReaderMetrics.tableCellStyle(
                settings, fontSize, chosenScale, cell.header, language,
                table.block, cell.block, bookFonts,
                semanticFontScale = if (table.publisherRowLayout != null) 1f else 0.92f,
            )
            val bidiCell = BidiLayoutText.of(cell.text)
            val textHeight = measurer.measureReader(
                text = bidiCell.display,
                style = style,
                constraints = Constraints(
                    maxWidth = (cellWidth - padding.left - padding.right).coerceAtLeast(1),
                ),
                density = density,
                bookFonts = bookFonts,
                publisherStyles = settings.bookStyles,
            ).size.height
            val total = textHeight + padding.top + padding.bottom
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

    val columns = tableColumnGeometry(widths, availablePx, table.publisherRowLayout)
    TableLayout(grid, widths, rowHeights, chosenScale, columns.offsetsPx, columns.widthPx)
}

internal fun authoredTableCellWidthPx(
    box: com.example.frogreader.data.model.PublisherBoxStyle?,
    enabled: Boolean,
    availableWidthPx: Int,
    fontSizePx: Float,
): Int? {
    if (!enabled || box == null || availableWidthPx <= 0) return null
    val requested = when {
        box.widthFrac != null -> availableWidthPx * box.widthFrac.coerceIn(0.05f, 1f)
        box.widthEm != null -> box.widthEm.coerceAtLeast(0.05f) * fontSizePx
        else -> return null
    }
    return requested.roundToInt().coerceIn(
        1,
        maximumTableCellWidthPx(box, enabled, availableWidthPx, fontSizePx) ?: availableWidthPx,
    )
}

private fun maximumTableCellWidthPx(
    box: com.example.frogreader.data.model.PublisherBoxStyle?,
    enabled: Boolean,
    availableWidthPx: Int,
    fontSizePx: Float,
): Int? {
    if (!enabled || box == null || availableWidthPx <= 0) return null
    return listOfNotNull(
        box.maxWidthFrac?.takeIf { it.isFinite() && it > 0f }?.let { availableWidthPx * it },
        box.maxWidthEm?.takeIf { it.isFinite() && it > 0f }?.let { fontSizePx * it },
    ).minOrNull()?.roundToInt()?.coerceIn(1, availableWidthPx)
}

/**
 * Width of the widest unbreakable run in a cell. Besides the three longest
 * ordinary runs, every run containing an inline-image placeholder is measured
 * even when it is only one U+FFFC character. Treating that character as a
 * normal ~1em glyph made the table allocator believe a panoramic image fitted
 * in a narrow column; the real placeholder was then clipped at draw time.
 *
 * The measuring callback keeps this policy independently unit-testable while
 * production still uses the exact same [TextMeasurer], [TextStyle] and
 * placeholder geometry as final row measurement and rendering.
 */
internal fun tableCellMinIntrinsicWidthPx(
    text: AnnotatedString,
    measure: (AnnotatedString, List<AnnotatedString.Range<Placeholder>>) -> Int,
): Int {
    if (text.isEmpty()) return 0

    val runs = readerUnbreakableRuns(text)
    if (runs.isEmpty()) return 0
    val imageMarks = readerAtomicRanges(text)
    val candidateRanges = linkedSetOf<IntRange>()

    runs.sortedByDescending { it.last - it.first }
        .take(3)
        .forEach { candidateRanges += it }

    // Include the complete non-breaking run around every placeholder. This
    // also handles an image directly adjacent to text without a break point.
    if (imageMarks.isNotEmpty()) {
        val orderedMarks = imageMarks
        var markIndex = 0
        for (run in runs) {
            val runStart = run.first
            val runEnd = run.last + 1
            while (markIndex < orderedMarks.size && orderedMarks[markIndex].second <= runStart) {
                markIndex++
            }
            if (markIndex < orderedMarks.size &&
                orderedMarks[markIndex].first < runEnd &&
                orderedMarks[markIndex].second > runStart
            ) {
                candidateRanges += run
            }
        }
    }

    var widest = 0
    for (range in candidateRanges) {
        val fragment = text.subSequence(range.first, range.last + 1)
        val width = measure(fragment, tableCellPlaceholders(fragment))
        if (width > widest) widest = width
    }
    return widest
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
    modifier: Modifier = Modifier,
    bookFonts: Map<String, FontFamily> = emptyMap(),
    invertImages: Boolean = false,
    footnotes: FootnoteHandler? = null,
    searchHighlight: String? = null,
    colors: ReaderColors,
    /** Effective container surface, including translucent publisher boxes. */
    surroundingBackground: Color = colors.background,
    /** The table's retained publisher box already painted its own background. */
    publisherBackgroundAlreadyApplied: Boolean = false,
    highlights: ReaderHighlights? = null,
    itemIndex: Int = -1,
) {
    val density = LocalDensity.current
    val stacked = layout.stackedCellHeightsPx != null
    val cellSpans = remember(table) { SelectionText.tableCellSpans(table) }
    val tableColors = publisherColorPair(
        table.block,
        settings.bookStyles,
        colors.text,
        surroundingBackground,
        backgroundAlreadyApplied = publisherBackgroundAlreadyApplied,
    )

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
        val colOffsets = layout.columnOffsetsPx
        val rtlColumns = ReaderMetrics.isRtl(table)

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
            var stackedY = rowOffsets[visual]
            for ((ci, cell) in row.cells.withIndex()) {
                if (stacked) {
                    val height = layout.stackedCellHeightsPx[r][ci]
                    if (height > 0) list += CellPlacement(r, ci, 0, stackedY, layout.tableWidthPx, height)
                    stackedY += height
                    continue
                }
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
                    x = if (rtlColumns) {
                        layout.tableWidthPx - colOffsets[col + span - 1] - layout.colWidthsPx[col + span - 1]
                    } else colOffsets[col],
                    y = rowOffsets[visual],
                    width = colOffsets[col + span - 1] + layout.colWidthsPx[col + span - 1] - colOffsets[col],
                    height = height,
                )
            }
        }
        list
    }
    val placementCells = remember(table, placements, stacked) {
        placements.map { table.rows[it.row].cells[it.cellIndex].forTableLayout(stacked) }
    }

    val defaultCellPaddingPx = with(density) {
        if (table.publisherRowLayout != null) 0 else ReaderMetrics.tableCellPadding.roundToPx()
    }
    val fontSizePx = with(density) { fontSize.sp.toPx() }
    val placementPaddings = remember(
        table,
        placements,
        settings.bookStyles,
        fontSizePx,
        defaultCellPaddingPx,
    ) {
        placements.mapIndexed { index, placement ->
            tableCellPaddingPx(
                placementCells[index],
                settings,
                fontSizePx,
                placement.width,
                defaultCellPaddingPx,
                includeMargins = stacked,
            )
        }
    }
    Layout(
        modifier = modifier.drawBehind {
            val stroke = Stroke(width = 1f)
            for ((index, p) in placements.withIndex()) {
                val cell = placementCells[index]
                val publisherBox = cell.publisherBox?.takeIf { settings.bookStyles }
                if (settings.bookStyles) {
                    // [cell.block] contains the composited tbody/tr/cell
                    // surface. Prefer it over the cell's own raw box layer so
                    // row backgrounds are not lost when a td is transparent.
                    (cell.block?.backgroundColorArgb
                        ?: publisherBox?.backgroundColorArgb)
                        ?.let { Color(it) }
                        ?.takeIf { it.alpha > 0f }
                        ?.let { cellBackground ->
                            drawRect(
                                color = cellBackground,
                                topLeft = Offset(p.x.toFloat(), p.y.toFloat()),
                                size = Size(p.width.toFloat(), p.height.toFloat()),
                            )
                        }
                }
                val authoredBorder = publisherBox
                    ?: com.example.frogreader.data.model.PublisherBoxStyle.DEFAULT
                val borders = tableCellBorders(table, cell, settings.bookStyles, stacked)
                if (borders == TableCellBorders.PUBLISHER) {
                    val cellInk = publisherColorPair(
                        foregroundArgb = cell.block?.foregroundColorArgb ?: table.block?.foregroundColorArgb,
                        backgroundArgb = cell.block?.backgroundColorArgb ?: publisherBox?.backgroundColorArgb,
                        enabled = true,
                        defaultForeground = tableColors.foreground,
                        surroundingBackground = tableColors.effectiveBackground,
                    ).foreground
                    val left = p.x.toFloat()
                    val top = p.y.toFloat()
                    val right = left + p.width
                    val bottom = top + p.height
                    drawPublisherBorder(
                        authoredBorder.borderLeft,
                        left,
                        top,
                        left,
                        bottom,
                        fontSizePx,
                        cellInk,
                    )
                    drawPublisherBorder(
                        authoredBorder.borderRight,
                        right,
                        top,
                        right,
                        bottom,
                        fontSizePx,
                        cellInk,
                    )
                    drawPublisherBorder(
                        authoredBorder.borderTop,
                        left,
                        top,
                        right,
                        top,
                        fontSizePx,
                        cellInk,
                    )
                    drawPublisherBorder(
                        authoredBorder.borderBottom,
                        left,
                        bottom,
                        right,
                        bottom,
                        fontSizePx,
                        cellInk,
                    )
                } else if (borders == TableCellBorders.READER_GRID) {
                    // Use the cell's readable ink: page secondary text can
                    // disappear on OLED or an authored cell background.
                    val gridInk = publisherColorPair(
                        foregroundArgb = cell.block?.foregroundColorArgb ?: table.block?.foregroundColorArgb,
                        backgroundArgb = cell.block?.backgroundColorArgb ?: publisherBox?.backgroundColorArgb,
                        enabled = settings.bookStyles,
                        defaultForeground = tableColors.foreground,
                        surroundingBackground = tableColors.effectiveBackground,
                    ).foreground
                    drawRect(
                        color = gridInk.copy(alpha = 0.35f),
                        topLeft = Offset(p.x.toFloat(), p.y.toFloat()),
                        size = Size(p.width.toFloat(), p.height.toFloat()),
                        style = stroke,
                    )
                }
            }
            table.publisherBox?.takeIf { settings.bookStyles }?.let { frame ->
                val right = layout.tableWidthPx.toFloat()
                val bottom = size.height
                drawPublisherBorder(frame.borderLeft, 0f, 0f, 0f, bottom, fontSizePx, tableColors.foreground)
                drawPublisherBorder(frame.borderRight, right, 0f, right, bottom, fontSizePx, tableColors.foreground)
                if (rowStart == 0 || headerRepeated) {
                    drawPublisherBorder(frame.borderTop, 0f, 0f, right, 0f, fontSizePx, tableColors.foreground)
                }
                if (rowEnd >= table.rows.size) {
                    drawPublisherBorder(frame.borderBottom, 0f, bottom, right, bottom, fontSizePx, tableColors.foreground)
                }
            }
        },
        content = {
            for ((index, p) in placements.withIndex()) {
                val cell = placementCells[index]
                val align = cell.align ?: if (cell.header) BlockAlign.CENTER else null
                val cellBackgroundArgb = cell.block
                    ?.takeIf { settings.bookStyles }
                    ?.backgroundColorArgb
                    ?: cell.publisherBox
                        ?.takeIf { settings.bookStyles }
                        ?.backgroundColorArgb
                val cellColors = publisherColorPair(
                    foregroundArgb = cell.block?.foregroundColorArgb
                        ?: table.block?.foregroundColorArgb,
                    backgroundArgb = cellBackgroundArgb
                        ?: table.block?.backgroundColorArgb,
                    enabled = settings.bookStyles,
                    defaultForeground = tableColors.foreground,
                    surroundingBackground = tableColors.effectiveBackground,
                )
                val linkColor = readableReaderForeground(
                    colors.accent,
                    cellColors.effectiveBackground,
                )
                val decorated = remember(
                    cell.text, settings.bookStyles, cellColors, linkColor,
                    footnotes, searchHighlight, colors.accent,
                ) {
                    cell.text.withPublisherColors(settings.bookStyles, cellColors)
                        .withFootnoteLinks(linkColor, footnotes)
                        .withSearchHighlight(searchHighlight, colors.accent.copy(alpha = 0.3f))
                }
                val bidiDisplay = remember(decorated) { BidiLayoutText.of(decorated) }
                val display = bidiDisplay.display
                // A table's character space is its flattened text, the same
                // one search and bookmark previews already use, so a cell is
                // addressable like any other run of text in the book.
                val fragment = rememberTextFragment(
                    highlights = highlights,
                    itemIndex = itemIndex,
                    charStart = cellSpans.getOrNull(p.row)?.getOrNull(p.cellIndex)?.start ?: 0,
                    length = cell.text.length,
                    bidi = bidiDisplay,
                )
                ReaderText(
                    text = display,
                    invertImages = invertImages, bookFonts = bookFonts,
                    publisherStyles = settings.bookStyles, maxWidthPx = (p.width - placementPaddings[index].left - placementPaddings[index].right).coerceAtLeast(1),
                    style = ReaderMetrics
                        .tableCellStyle(
                            settings,
                            fontSize,
                            layout.fontScale,
                            cell.header,
                            language,
                            table.block,
                            cell.block,
                            bookFonts,
                            semanticFontScale = if (table.publisherRowLayout != null) 1f else 0.92f,
                        )
                        .copy(
                            color = cellColors.foreground,
                            textAlign = when (align) {
                                BlockAlign.CENTER -> TextAlign.Center
                                BlockAlign.END -> TextAlign.End
                                BlockAlign.LEFT -> TextAlign.Left
                                BlockAlign.RIGHT -> TextAlign.Right
                                else -> TextAlign.Start
                            },
                        ),
                    overflow = TextOverflow.Clip,
                    onTextLayout = { fragment?.layout = it },
                    modifier = Modifier.readerHighlights(fragment, highlights),
                )
            }
        },
    ) { measurables, _ ->
        val totalWidth = layout.tableWidthPx
        var totalHeight = 0
        if (headerRepeated && rowStart > 0) totalHeight += layout.rowHeightsPx[0]
        for (r in rowStart.coerceAtLeast(0) until rowEnd.coerceAtMost(table.rows.size)) {
            totalHeight += layout.rowHeightsPx[r]
        }

        val placeables = measurables.mapIndexed { i, measurable ->
            val p = placements[i]
            val padding = placementPaddings[i]
            measurable.measure(
                Constraints(
                    minWidth = (p.width - padding.left - padding.right).coerceAtLeast(1),
                    maxWidth = (p.width - padding.left - padding.right).coerceAtLeast(1),
                    maxHeight = (p.height - padding.top - padding.bottom).coerceAtLeast(1),
                ),
            )
        }
        layout(totalWidth, totalHeight) {
            placeables.forEachIndexed { i, placeable ->
                val p = placements[i]
                val padding = placementPaddings[i]
                val centeredInset = if (table.centerCellsVertically) {
                    (p.height - padding.top - padding.bottom - placeable.height).coerceAtLeast(0) / 2
                } else 0
                placeable.place(p.x + padding.left, p.y + padding.top + centeredInset)
            }
        }
    }
}

/** First authored table column is physically rightmost in an RTL table. */
internal fun tableCellPhysicalX(
    columnOffsets: IntArray,
    logicalColumn: Int,
    span: Int,
    rtl: Boolean,
): Int {
    val start = logicalColumn.coerceIn(0, columnOffsets.lastIndex)
    val end = (start + span.coerceAtLeast(1)).coerceIn(start, columnOffsets.lastIndex)
    return if (rtl) columnOffsets.last() - columnOffsets[end] else columnOffsets[start]
}

/** Shared table-cell image geometry for measurement and drawing. */
internal fun tableCellPlaceholders(
    text: AnnotatedString,
) = inlineImagePlaceholders(text)

/** Drawable counterpart of [tableCellPlaceholders]. */
internal fun tableCellInlineContent(
    text: AnnotatedString,
    invertImages: Boolean,
) = inlineImageContent(text, invertImages)
