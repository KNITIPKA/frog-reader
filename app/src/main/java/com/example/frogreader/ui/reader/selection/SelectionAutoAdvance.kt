package com.example.frogreader.ui.reader.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long to wait after an auto page turn before considering the next one.
 * Together with the turn's own animation this is about half a second a page —
 * fast enough to sweep through a chapter, slow enough to stop where intended.
 */
private val PAGE_HOLD = 150.milliseconds

/**
 * How the reading surface follows a selection drag that has reached the edge
 * of the screen. Supplied by whichever mode is on screen, because only it
 * knows whether "forward" means a pager page or a few hundred pixels.
 */
@Stable
class SelectionAutoAdvance(
    /** Paged mode watches the left and right edges; scroll mode top and bottom. */
    val paged: Boolean,
    /** Paged: turn one page towards [direction]. Scroll: scroll by [pixels]. */
    val step: suspend (direction: Int, pixels: Float) -> Unit,
    /** Leaves the surface settled when the drag ends mid-step. */
    val settle: suspend () -> Unit = {},
)

/**
 * Turns pages (or scrolls) while a selection drag rests against the edge of
 * the screen, and re-reads the character under the finger after every step.
 *
 * This is the thing that makes a selection able to span pages at all in the
 * one-handed case: the finger never leaves the glass, the book moves beneath
 * it, and because the selection is held in book coordinates everything the
 * page turn swept past is inside it by definition.
 *
 * Driven by a frame loop rather than by pointer events — a finger held still
 * in the corner produces no events at all, and it is precisely the finger held
 * still in the corner that means "keep going".
 */
@Composable
fun SelectionAutoAdvanceEffect(controller: SelectionController) {
    val density = LocalDensity.current
    val bandPx = with(density) { SelectionHitRules.EDGE_BAND.toPx() }
    val maxScrollPx = with(density) { SelectionHitRules.AUTO_SCROLL_MAX_PER_FRAME.toPx() }

    LaunchedEffect(controller.dragging) {
        if (!controller.dragging) return@LaunchedEffect
        var advanced = false
        try {
            while (true) {
                withFrameNanos { }
                val advance = controller.advance ?: continue
                val point = controller.lastPoint ?: continue
                val size = controller.spaceSize ?: continue
                val direction = SelectionHitRules.edgeAdvance(
                    point = point,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                    paged = advance.paged,
                    bandPx = bandPx,
                )
                if (direction == 0) continue

                advanced = true
                if (advance.paged) {
                    advance.step(direction, 0f)
                    delay(PAGE_HOLD)
                } else {
                    advance.step(
                        direction,
                        SelectionHitRules.autoScrollPx(
                            y = point.y,
                            height = size.height.toFloat(),
                            bandPx = bandPx,
                            maxPerFrame = maxScrollPx,
                        ),
                    )
                }
                // The finger has not moved; the text under it has.
                controller.reExtend()
            }
        } finally {
            // Lifting a finger in the middle of a page turn would otherwise
            // leave the pager parked between two pages, with nothing left to
            // snap it — the fling behaviour only runs after a real drag. Only
            // when we actually moved something: settling otherwise would take
            // the scroll away from a swipe started right after the drag ended.
            if (advanced) withContext(NonCancellable) { controller.advance?.settle() }
        }
    }
}
