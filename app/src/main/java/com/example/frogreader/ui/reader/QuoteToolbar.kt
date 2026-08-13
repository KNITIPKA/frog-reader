package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.ui.reader.selection.PillSide
import com.example.frogreader.ui.reader.selection.SelectionController
import com.example.frogreader.ui.reader.selection.SelectionHitRules
import com.example.frogreader.ui.reader.selection.rememberFrameTicker
import com.example.frogreader.ui.reader.selection.rememberSelectionOnScreen

/** Gap between the selection and the pill. */
private val TOOLBAR_GAP = 12.dp

/** How close the pill may come to the screen edges. */
private val TOOLBAR_INSET = 8.dp

/** How small the pill starts before springing to full size. */
private const val APPEAR_FROM = 0.86f

/**
 * Floating Quote / Translate / Copy pill above the selection.
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionToolbar(
    controller: SelectionController,
    colors: ReaderColors,
    onQuote: () -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
) {
    if (!controller.active || controller.dragging) return

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

        if (!visible) return@BoxWithConstraints

        // Springs out of the edge of the selection the moment the finger
        // lifts: quick, with just enough overshoot to feel like it arrived
        // rather than blinked into existence.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
        }

        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
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
                        pivotFractionX = 0.5f,
                        pivotFractionY = if (side == PillSide.BELOW) 0f else 1f,
                    )
                },
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                ToolbarAction(
                    label = stringResource(R.string.selection_quote),
                    icon = { Icon(Icons.Rounded.FormatQuote, contentDescription = null) },
                    onClick = onQuote,
                )
                ToolbarAction(
                    label = stringResource(R.string.selection_translate),
                    icon = { Icon(Icons.Rounded.Translate, contentDescription = null) },
                    onClick = onTranslate,
                )
                ToolbarAction(
                    label = stringResource(R.string.selection_copy),
                    icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = onCopy,
                )
            }
        }
    }
}

@Composable
private fun ToolbarAction(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
