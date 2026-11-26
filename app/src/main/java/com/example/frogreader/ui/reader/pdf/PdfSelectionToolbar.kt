@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.pdf.PdfPoint
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.selection.model.TextSelection
import com.example.frogreader.R
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.ReaderColors
import com.example.frogreader.ui.reader.QuoteColorChoices
import com.example.frogreader.ui.reader.SelectionToolbarPalette
import com.example.frogreader.ui.reader.selectionToolbarPalette
import com.example.frogreader.ui.reader.selection.PillSide
import com.example.frogreader.ui.reader.selection.SelectionHitRules
import kotlin.math.roundToInt

private data class ToolbarSelection(val text: String, val bounds: List<PdfTextBounds>, val anchor: Rect)

/** Measured and positioned in one layout pass, including the very first visible frame. */
@Composable
internal fun PdfSelectionToolbar(
    selection: TextSelection?, visible: Boolean, viewer: PdfViewerState, viewport: IntSize,
    colors: ReaderColors, quotes: List<Quote>, onRemoveQuotes: (Set<String>) -> Unit,
    onQuote: (String, List<PdfTextBounds>, QuoteColor) -> Unit,
    onAddNote: (String, List<PdfTextBounds>) -> Unit, copy: (String) -> Unit,
    translate: (String) -> Unit, define: (String) -> Unit, externalSearch: (String) -> Unit,
    sourcePageIndex: Int? = null,
    onActionLayout: ((index: Int, positionInTray: Offset, size: IntSize) -> Unit)? = null,
) {
    val palette = remember(colors) { selectionToolbarPalette(colors) }
    val current = selection?.takeIf { visible && it.text.isNotBlank() }?.let { selected ->
        val visibleBounds = selected.bounds.mapNotNull { rect ->
            val start = viewer.pdfPointToVisibleOffset(PdfPoint(rect.pageNum, rect.left, rect.top))
            val end = viewer.pdfPointToVisibleOffset(PdfPoint(rect.pageNum, rect.right, rect.bottom))
            if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) null
            else Rect(start, end).intersect(Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()))
                .takeUnless { it.isEmpty }
        }
        if (visibleBounds.isEmpty()) null else ToolbarSelection(
            selected.text.toString(),
            selected.bounds.map { PdfTextBounds(sourcePageIndex ?: it.pageNum, it.left, it.top, it.right, it.bottom) },
            Rect(visibleBounds.minOf { it.left }, visibleBounds.minOf { it.top },
                visibleBounds.maxOf { it.right }, visibleBounds.maxOf { it.bottom }),
        )
    }
    // Keep the last content and anchor for the short exit, even after native selection is cleared.
    var retained by remember { mutableStateOf<ToolbarSelection?>(null) }
    SideEffect { if (current != null) retained = current }
    val displayed = current ?: retained
    val enabled = current != null
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.pdfControlsSafeInsets
    val gap = with(density) { 12.dp.toPx() }
    val handle = with(density) { SelectionHitRules.HANDLE_REACH.toPx() }
    val top = insets.getTop(density) + gap
    val bottom = insets.getBottom(density) + gap
    val left = insets.getLeft(density, direction) + gap
    val right = insets.getRight(density, direction) + gap

    AnimatedVisibility(visible = enabled, enter = fadeIn(tween(100)), exit = fadeOut(tween(90))) {
        if (displayed == null) return@AnimatedVisibility
        val overlapping = quotes.filter { quote -> quote.pdfSelection.any { saved -> displayed.bounds.any { bounds ->
            bounds.pageIndex == saved.pageIndex && bounds.left < saved.right && saved.left < bounds.right &&
                bounds.top < saved.bottom && saved.top < bounds.bottom
        } } }
        val scale by transition.animateFloat(
            transitionSpec = { tween(if (targetState == EnterExitState.Visible) 180 else 90, easing = FastOutSlowInEasing) },
            label = "PDF selection menu scale",
        ) { if (it == EnterExitState.Visible) 1f else 0.92f }
        Layout(
            modifier = Modifier.fillMaxSize().then(if (enabled) Modifier else Modifier.clearAndSetSemantics {}),
            content = {
                Surface(shape = RoundedCornerShape(24.dp), color = palette.container, contentColor = colors.onChrome,
                    shadowElevation = 6.dp) {
                    PdfSelectionActions(
                        colors, overlapping.map { it.id }.toSet(),
                        QuoteColor.entries.filter { color -> overlapping.none { it.color == color } },
                        onRemoveQuotes = { if (enabled) { onRemoveQuotes(it); viewer.clearCurrentSelection() } },
                        onQuote = { if (enabled) { onQuote(displayed.text, displayed.bounds, it); viewer.clearCurrentSelection() } },
                        onAddNote = { if (enabled) {
                            onAddNote(displayed.text, displayed.bounds)
                            viewer.clearCurrentSelection()
                        } },
                        onTranslate = { if (enabled) translate(displayed.text) },
                        onCopy = { if (enabled) { copy(displayed.text); viewer.clearCurrentSelection() } },
                        onDefine = { if (enabled) define(displayed.text) },
                        onExternalSearch = { if (enabled) externalSearch(displayed.text) },
                        onActionLayout = onActionLayout,
                    )
                }
            },
        ) { measurables, constraints ->
            // Never feed onSizeChanged back into composition: that gives placement a stale size
            // for one frame. Measure the actual actions (including the optional delete row) here.
            val pill = measurables.single().measure(constraints.copy(
                minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth - left - right).toInt().coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - top - bottom).toInt().coerceAtLeast(0),
            ))
            val side = SelectionHitRules.toolbarSide(displayed.anchor, pill.height.toFloat(),
                constraints.maxHeight.toFloat(), gap, handle, top, bottom)
            val position = SelectionHitRules.toolbarOffset(displayed.anchor, pill.width.toFloat(), pill.height.toFloat(),
                constraints.maxWidth.toFloat(), gap, handle, top, left, right, side)
            val y = position.y.coerceAtMost((constraints.maxHeight - bottom - pill.height).coerceAtLeast(top))
            layout(constraints.maxWidth, constraints.maxHeight) {
                pill.placeWithLayer(position.x.roundToInt(), y.roundToInt()) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(
                        ((displayed.anchor.center.x - position.x) / pill.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        if (side == PillSide.ABOVE) 1f else 0f,
                    )
                }
            }
        }
    }
}

/** The PDF actions keep two equal rows; notes capture the same anchored selection as highlights. */
@Composable
internal fun PdfSelectionActions(
    colors: ReaderColors,
    selectedQuotes: Set<String>,
    availableColors: List<QuoteColor>,
    onRemoveQuotes: (Set<String>) -> Unit,
    onQuote: (QuoteColor) -> Unit,
    onAddNote: () -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onExternalSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onActionLayout: ((index: Int, positionInTray: Offset, size: IntSize) -> Unit)? = null,
) {
    val palette = remember(colors) { selectionToolbarPalette(colors) }
    var choosingColor by remember { mutableStateOf(false) }
    val labels = listOf(
        stringResource(R.string.selection_search), stringResource(R.string.selection_define),
        stringResource(R.string.selection_translate), stringResource(R.string.pdf_selection_highlight),
        stringResource(R.string.pdf_selection_add_note), stringResource(R.string.selection_copy),
    )
    val icons = listOf(Icons.Rounded.Search, Icons.Rounded.ImportContacts, Icons.Rounded.Translate,
        Icons.Rounded.Highlight, Icons.Rounded.EditNote, Icons.Rounded.ContentCopy)
    val actions = listOf(onExternalSearch, onDefine, onTranslate, {
        if (selectedQuotes.isEmpty()) onQuote(QuoteColor.AMBER) else choosingColor = true
    }, onAddNote, onCopy)
    val fontScale = LocalDensity.current.fontScale
    // Only debug fixtures request layout measurements. Measuring in the tray's own
    // coordinates removes its animated ancestor scale while retaining actual tile sizes.
    val layoutReporter = if (onActionLayout != null) remember { PdfActionLayoutReporter(onActionLayout) } else null
    SideEffect { layoutReporter?.callback = onActionLayout }
    BoxWithConstraints(modifier.widthIn(max = 324.dp).heightIn(max = 420.dp).then(
        if (layoutReporter != null) Modifier.onGloballyPositioned(layoutReporter::onTrayPositioned) else Modifier,
    )) {
        // Reserve enough width for whole labels as fonts grow. Regular text keeps the
        // reference's 3x2 grid; narrow large-text menus use two columns and three rows.
        val columns = if ((maxWidth - 36.dp) / 3 < 82.dp * fontScale) 2 else 3
        Column(
            // Short landscape viewports scroll instead of clipping the final actions.
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (labels.indices).chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { index ->
                        Box(Modifier.weight(1f).fillMaxHeight().then(
                            if (layoutReporter != null) Modifier.onGloballyPositioned {
                                layoutReporter.onActionPositioned(index, it)
                            } else Modifier,
                        )) {
                            PdfSelectionTile(labels[index], icons[index], palette, actions[index],
                                Modifier.fillMaxSize(), enabled = index != 3 || availableColors.isNotEmpty())
                            if (index == 3) DropdownMenu(
                                expanded = choosingColor,
                                onDismissRequest = { choosingColor = false },
                                containerColor = colors.chrome,
                            ) {
                                QuoteColorChoices(colors, availableColors) { color ->
                                    choosingColor = false
                                    onQuote(color)
                                }
                            }
                        }
                    }
                }
            }
            if (selectedQuotes.isNotEmpty()) {
                Surface(
                    onClick = { onRemoveQuotes(selectedQuotes) },
                    shape = RoundedCornerShape(16.dp),
                    color = palette.tile,
                    contentColor = palette.label,
                    border = BorderStroke(1.dp, palette.tileBorder),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(20.dp), tint = palette.icon)
                        Spacer(Modifier.width(8.dp))
                        Text(pluralStringResource(R.plurals.pdf_selection_delete_highlights, selectedQuotes.size,
                            selectedQuotes.size), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Layout diagnostics stay separate from accessibility's rounded, transformed screen bounds. */
private class PdfActionLayoutReporter(
    var callback: ((Int, Offset, IntSize) -> Unit)?,
) {
    private var tray: LayoutCoordinates? = null
    private val actions = mutableMapOf<Int, LayoutCoordinates>()

    fun onTrayPositioned(coordinates: LayoutCoordinates) {
        tray = coordinates
        actions.forEach { (index, action) -> report(index, action) }
    }

    fun onActionPositioned(index: Int, coordinates: LayoutCoordinates) {
        actions[index] = coordinates
        report(index, coordinates)
    }

    private fun report(index: Int, action: LayoutCoordinates) {
        val root = tray?.takeIf { it.isAttached } ?: return
        if (!action.isAttached) return
        callback?.invoke(index, root.localPositionOf(action, Offset.Zero), action.size)
    }
}

@Composable
private fun PdfSelectionTile(
    label: String,
    icon: ImageVector,
    palette: SelectionToolbarPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = palette.tile,
        contentColor = palette.label,
        border = BorderStroke(1.dp, palette.tileBorder),
        tonalElevation = 0.dp,
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.42f },
    ) {
        Column(
            Modifier.heightIn(min = 78.dp).padding(horizontal = 5.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = palette.icon)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
