package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.selection.PillSide
import com.example.frogreader.ui.reader.selection.SelectionController
import com.example.frogreader.ui.reader.selection.SelectionHitRules
import com.example.frogreader.ui.reader.selection.availableQuoteColors
import com.example.frogreader.ui.reader.selection.rememberFrameTicker
import com.example.frogreader.ui.reader.selection.rememberSelectionOnScreen
import com.example.frogreader.ui.reader.selection.selectedQuoteIds

/** Gap between the selection and the pill. */
private val TOOLBAR_GAP = 10.dp

/** How close the pill may come to the screen edges. */
private val TOOLBAR_INSET = 8.dp

/** Default target width of the context menu card grid. */
private val TOOLBAR_DEFAULT_WIDTH = 284.dp

/** Corner radius of the outer floating menu container. */
private val TOOLBAR_CORNER_RADIUS = 24.dp

/** Height of the triangular pointer tip facing the selected text. */
private val POINTER_HEIGHT = 7.dp

/** Width of the base of the triangular pointer tip. */
private val POINTER_WIDTH = 16.dp

/** How small the pill starts before springing to full size. */
private const val APPEAR_FROM = 0.86f

/**
 * Floating quote, delete, external text and copy actions above the selection.
 *
 * It rides on the reader's own selection, not on `LocalTextToolbar`: the
 * platform's toolbar callback only exists while Compose owns the selection,
 * and the whole point of the reader's own model is that a selection outlives
 * the composables — and the pages — it was made on.
 *
 * **Must be a sibling of the reading surface, not a child of it.** The reading
 * box carries the selection gesture, which swallows taps in order to dismiss
 * the selection; a pill inside it would have its own buttons eaten. As a later
 * sibling it is hit-tested first instead, so its buttons work and the gesture
 * never sees those touches.
 *
 * It used to be a `Popup` for that same reason, which cost it the ability to
 * keep up: a popup is its own window, moved by the window manager, and it
 * visibly lagged and wobbled behind the text during a page turn or a scroll.
 * In-window and positioned from a graphics layer, it moves in the same frame
 * as the text it belongs to.
 *
 * Shown only over text that is standing still: hidden while a finger is
 * dragging, so it never covers what is being chosen, and hidden while the
 * page turns or scrolls.
 */
@Composable
fun SelectionToolbar(
    controller: SelectionController,
    colors: ReaderColors,
    quotes: List<Quote>,
    onRemoveQuotes: (Set<String>) -> Unit,
    onQuote: (QuoteColor) -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onExternalSearch: () -> Unit,
) {
    if (!controller.active || controller.dragging) return

    // Read the range only in this small subtree, after the drag has ended.
    // Character-by-character changes must not recompose the reading surface.
    val selectedQuotes = remember(controller.selection, quotes) {
        selectedQuoteIds(controller.selection, quotes)
    }
    val availableColors = remember(controller.selection, quotes) {
        availableQuoteColors(controller.selection, quotes)
    }
    val onScreen = rememberSelectionOnScreen(controller)
    val frame = rememberFrameTicker()
    val density = LocalDensity.current
    var pill by remember { mutableStateOf(IntSize.Zero) }

    // Composed only when it should be seen, so it never leaves invisible
    // buttons behind for the page to be tapped through — and so that every
    // reappearance is a fresh composition, and animates again.
    val visible = onScreen && !controller.surfaceMoving()

    // The pill must clear the status bar and, above all, the camera cutout —
    // ignoringVisibility because the reader hides the system bars while
    // reading and their strip is still not somewhere to put a button.
    val safeArea = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gapPx = with(density) { TOOLBAR_GAP.toPx() }
        val handleReachPx = with(density) { SelectionHitRules.HANDLE_REACH.toPx() }
        val marginPx = with(density) { TOOLBAR_INSET.toPx() }
        val insetTop = safeArea.getTop(density) + marginPx
        val insetBottom = safeArea.getBottom(density) + marginPx
        val insetLeft = safeArea.getLeft(density, layoutDirection) + marginPx
        val insetRight = safeArea.getRight(density, layoutDirection) + marginPx
        val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
        val availableWidth = with(density) {
            (screen.width - insetLeft - insetRight).coerceAtLeast(0f).toDp()
        }
        val targetWidth = minOf(TOOLBAR_DEFAULT_WIDTH, availableWidth)

        if (!visible) return@BoxWithConstraints

        // Springs out of the edge of the selection the moment the finger
        // lifts: quick, with just enough overshoot to feel like it arrived
        // rather than blinked into existence.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
        }

        val currentBounds = run {
            frame.longValue
            controller.toolbarBounds()
        }
        val currentSide = if (currentBounds != null && pill != IntSize.Zero) {
            SelectionHitRules.toolbarSide(
                bounds = currentBounds,
                pillHeight = pill.height.toFloat(),
                screenHeight = screen.height.toFloat(),
                gapPx = gapPx,
                handleReachPx = handleReachPx,
                insetTop = insetTop,
                insetBottom = insetBottom,
            )
        } else {
            PillSide.ABOVE
        }
        val pointerFractionX = if (currentBounds != null && pill.width > 0) {
            val where = SelectionHitRules.toolbarOffset(
                bounds = currentBounds,
                pillWidth = pill.width.toFloat(),
                pillHeight = pill.height.toFloat(),
                screenWidth = screen.width.toFloat(),
                gapPx = gapPx,
                handleReachPx = handleReachPx,
                insetTop = insetTop,
                insetLeft = insetLeft,
                insetRight = insetRight,
                side = currentSide,
            )
            ((currentBounds.center.x - where.x) / pill.width.toFloat()).coerceIn(0.18f, 0.82f)
        } else {
            0.5f
        }

        val palette = remember(colors) { selectionToolbarPalette(colors) }
        val bubbleShape = remember(currentSide, pointerFractionX) {
            SelectionBubbleShape(
                cornerRadius = TOOLBAR_CORNER_RADIUS,
                pointerWidth = POINTER_WIDTH,
                pointerHeight = POINTER_HEIGHT,
                side = currentSide,
                pointerFractionX = pointerFractionX,
            )
        }

        Surface(
            color = palette.container,
            contentColor = colors.onChrome,
            shape = bubbleShape,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(targetWidth)
                .onSizeChanged { pill = it }
                .graphicsLayer {
                    // Read the tick and the selection bounds INSIDE the layer
                    // block: it runs after this frame's layout, so the pill
                    // lands where the text is now, not where it was.
                    frame.longValue
                    val bounds = controller.toolbarBounds()
                    // The pill is only ever shown over text that is standing
                    // still. Carrying it along was tried several ways and
                    // none of them read well: there is no honest place for it
                    // while a selection spanning a page break has text on two
                    // pages sliding past each other, and following a scroll
                    // just puts a moving object over moving words.
                    if (bounds == null || pill == IntSize.Zero) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val side = SelectionHitRules.toolbarSide(
                        bounds = bounds,
                        pillHeight = pill.height.toFloat(),
                        screenHeight = screen.height.toFloat(),
                        gapPx = gapPx,
                        handleReachPx = handleReachPx,
                        insetTop = insetTop,
                        insetBottom = insetBottom,
                    )
                    val where = SelectionHitRules.toolbarOffset(
                        bounds = bounds,
                        pillWidth = pill.width.toFloat(),
                        pillHeight = pill.height.toFloat(),
                        screenWidth = screen.width.toFloat(),
                        gapPx = gapPx,
                        handleReachPx = handleReachPx,
                        insetTop = insetTop,
                        insetLeft = insetLeft,
                        insetRight = insetRight,
                        side = side,
                    )
                    translationX = where.x
                    translationY = where.y

                    val grown = appear.value
                    // The fade finishes in the first half of the spring, so
                    // the pill is solid while it is still settling instead of
                    // ghosting through the whole of it.
                    alpha = (grown * 2f).coerceIn(0f, 1f)
                    scaleX = APPEAR_FROM + (1f - APPEAR_FROM) * grown
                    scaleY = scaleX
                    // Grows out of the selection: from its bottom edge when the
                    // pill sits above the text, from its top edge when below.
                    transformOrigin = TransformOrigin(
                        pivotFractionX = pointerFractionX,
                        pivotFractionY = if (side == PillSide.BELOW) 0f else 1f,
                    )
                },
        ) {
            Box(
                modifier = Modifier.padding(
                    top = if (currentSide == PillSide.BELOW) POINTER_HEIGHT else 0.dp,
                    bottom = if (currentSide == PillSide.ABOVE) POINTER_HEIGHT else 0.dp,
                ),
            ) {
                SelectionToolbarActions(
                    colors = colors,
                    selectedQuotes = selectedQuotes,
                    availableColors = availableColors,
                    onRemoveQuotes = onRemoveQuotes,
                    onQuote = onQuote,
                    onTranslate = onTranslate,
                    onCopy = onCopy,
                    onDefine = onDefine,
                    onExternalSearch = onExternalSearch,
                )
            }
        }
    }
}

@Composable
internal fun SelectionToolbarActions(
    colors: ReaderColors,
    selectedQuotes: Set<String>,
    availableColors: List<QuoteColor>,
    onRemoveQuotes: (Set<String>) -> Unit,
    onQuote: (QuoteColor) -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onExternalSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(colors) { selectionToolbarPalette(colors) }
    var choosingColor by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .widthIn(max = TOOLBAR_DEFAULT_WIDTH)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Top row: Search, Define, Translate
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarTile(
                label = stringResource(R.string.selection_search),
                icon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
                palette = palette,
                onClick = onExternalSearch,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ToolbarTile(
                label = stringResource(R.string.selection_define),
                icon = { Icon(Icons.Rounded.ImportContacts, contentDescription = null, modifier = Modifier.size(22.dp)) },
                palette = palette,
                onClick = onDefine,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ToolbarTile(
                label = stringResource(R.string.selection_translate),
                icon = { Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(22.dp)) },
                palette = palette,
                onClick = onTranslate,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // Second row: Quote, Copy
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedQuotes.isNotEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ToolbarTile(
                        label = stringResource(R.string.selection_quote),
                        icon = { Icon(Icons.Rounded.FormatQuote, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        palette = palette,
                        enabled = availableColors.isNotEmpty(),
                        onClick = { choosingColor = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DropdownMenu(
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
            } else {
                ToolbarTile(
                    label = stringResource(R.string.selection_quote),
                    icon = { Icon(Icons.Rounded.FormatQuote, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    palette = palette,
                    onClick = { onQuote(QuoteColor.DEFAULT) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            ToolbarTile(
                label = stringResource(R.string.selection_copy),
                icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(22.dp)) },
                palette = palette,
                onClick = onCopy,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // Optional third row: Delete quote(s) when selection intersects existing highlights
        if (selectedQuotes.isNotEmpty()) {
            ToolbarWideTile(
                label = pluralStringResource(
                    R.plurals.selection_delete_quotes,
                    selectedQuotes.size,
                    selectedQuotes.size,
                ),
                icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
                palette = palette,
                onClick = { onRemoveQuotes(selectedQuotes) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun QuoteColorChoices(colors: ReaderColors, availableColors: List<QuoteColor>, onQuote: (QuoteColor) -> Unit) {
    Text(
        stringResource(R.string.selection_quote_another_color),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onChromeVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    availableColors.forEach { color ->
        DropdownMenuItem(
            text = { Text(stringResource(color.labelRes), color = colors.onChrome) },
            leadingIcon = { Canvas(Modifier.size(20.dp)) { drawCircle(colors.quoteInk(color)) } },
            onClick = { onQuote(color) },
        )
    }
}

private val QuoteColor.labelRes: Int
    get() = when (this) {
        QuoteColor.DEFAULT -> R.string.quote_color_default
        QuoteColor.BLUE -> R.string.quote_color_blue
        QuoteColor.ROSE -> R.string.quote_color_rose
        QuoteColor.AMBER -> R.string.quote_color_amber
        QuoteColor.VIOLET -> R.string.quote_color_violet
    }

internal data class SelectionToolbarPalette(
    val container: Color,
    val tile: Color,
    val tileBorder: Color,
    val icon: Color,
    val label: Color,
)

internal fun selectionToolbarPalette(colors: ReaderColors): SelectionToolbarPalette {
    val dark = colors.background.luminance() < 0.5f
    val beige = !dark && colors.chrome == colors.background
    return if (dark) {
        // Dark / Midnight / Dynamic dark: deep dark outer tray with subtly tinted
        // elevated tiles and a crisp accent hairline border matching the design.
        val container = colors.chrome
        val baseTile = colors.controlStrong.compositeOver(container)
        val tile = colors.accent.copy(alpha = 0.05f).compositeOver(baseTile)
        val border = colors.accent.copy(alpha = 0.16f).compositeOver(tile)
        SelectionToolbarPalette(
            container = container,
            tile = tile,
            tileBorder = border,
            icon = colors.accent,
            label = colors.onChrome,
        )
    } else if (beige) {
        // Beige: warm taupe outer tray with reading-paper beige button faces,
        // matching stepper buttons and reader control groups.
        SelectionToolbarPalette(
            container = colors.control,
            tile = colors.controlButton,
            tileBorder = colors.track.copy(alpha = 0.65f).compositeOver(colors.controlButton),
            icon = colors.accent,
            label = colors.onChrome,
        )
    } else {
        // Light & Dynamic Light: white floating card with clean neutral tiles.
        SelectionToolbarPalette(
            container = colors.chrome,
            tile = colors.control,
            tileBorder = colors.track.copy(alpha = 0.70f).compositeOver(colors.control),
            icon = colors.accent,
            label = colors.onChrome,
        )
    }
}

@Composable
private fun ToolbarTile(
    label: String,
    icon: @Composable () -> Unit,
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 11.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.LocalContentColor.provides(palette.icon).let {
                    androidx.compose.runtime.CompositionLocalProvider(it) {
                        icon()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.label,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolbarWideTile(
    label: String,
    icon: @Composable () -> Unit,
    palette: SelectionToolbarPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = palette.tile,
        contentColor = palette.label,
        border = BorderStroke(1.dp, palette.tileBorder),
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides palette.icon,
            ) {
                icon()
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.label,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Rounded rectangle with a small triangular pointer on the top or bottom edge
 * pointing toward the selected text.
 */
private class SelectionBubbleShape(
    private val cornerRadius: Dp,
    private val pointerWidth: Dp,
    private val pointerHeight: Dp,
    private val side: PillSide,
    private val pointerFractionX: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = with(density) {
        val radius = cornerRadius.toPx()
        val tipW = pointerWidth.toPx()
        val tipH = pointerHeight.toPx().coerceAtMost(size.height / 4f)
        val bodyRect = when (side) {
            PillSide.ABOVE -> Rect(0f, 0f, size.width, (size.height - tipH).coerceAtLeast(0f))
            PillSide.BELOW -> Rect(0f, tipH, size.width, size.height)
        }
        val bodyPath = Path().apply {
            addRoundRect(RoundRect(bodyRect, CornerRadius(radius, radius)))
        }
        val minCenterX = (radius + tipW / 2f).coerceAtMost(size.width / 2f)
        val maxCenterX = (size.width - radius - tipW / 2f).coerceAtLeast(minCenterX)
        val centerX = (size.width * pointerFractionX).coerceIn(minCenterX, maxCenterX)

        val pointerPath = Path().apply {
            when (side) {
                PillSide.ABOVE -> {
                    moveTo(centerX - tipW / 2f, bodyRect.bottom - 1f)
                    lineTo(centerX, size.height)
                    lineTo(centerX + tipW / 2f, bodyRect.bottom - 1f)
                    close()
                }
                PillSide.BELOW -> {
                    moveTo(centerX - tipW / 2f, bodyRect.top + 1f)
                    lineTo(centerX, 0f)
                    lineTo(centerX + tipW / 2f, bodyRect.top + 1f)
                    close()
                }
            }
        }
        bodyPath.op(bodyPath, pointerPath, PathOperation.Union)
        Outline.Generic(bodyPath)
    }
}
