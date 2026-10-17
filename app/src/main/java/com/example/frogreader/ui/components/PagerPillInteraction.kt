package com.example.frogreader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal class PagerPillInteraction(
    val modifier: Modifier,
    val progress: () -> Float,
    val select: (Int) -> Unit,
)

/** Keep ordinary pager swipes in lockstep; only a capsule grab owns a temporary preview. */
@Composable
internal fun rememberPagerPillInteraction(
    selectedIndex: Int,
    pagerProgress: () -> Float,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
): PagerPillInteraction {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var size by remember { mutableStateOf(IntSize.Zero) }
    val slots = remember(size.width, rtl) { segmentSlots(size.width, listOf(1f, 1f), 0, rtl) }
    val motion = remember { Animatable(0f) }
    var overrideProgress by remember { mutableStateOf(false) }
    var dragPill by remember { mutableStateOf<Rect?>(null) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentPager by rememberUpdatedState(pagerProgress)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelection by rememberUpdatedState(selectedIndex)
    val progress: () -> Float = {
        dragPill?.let { segmentPillProgress(slots, it) }
            ?: if (overrideProgress) motion.value else currentPager().coerceIn(0f, 1f)
    }
    val currentProgress by rememberUpdatedState(progress)

    fun settle(destination: Int?) {
        val from = currentProgress()
        settling?.cancel()
        settling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            motion.snapTo(from)
            overrideProgress = true
            dragPill = null
            if (destination != null) currentSelect(destination)
            val target = destination?.toFloat() ?: currentPager()
            motion.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 550f))
            // The page can finish after the capsule. Hand back only when their
            // positions agree, so the old pager position never pulls it backwards.
            if (destination != null) {
                withTimeoutOrNull(1000) {
                    snapshotFlow { abs(currentPager() - target) < 0.001f && currentSelection == destination }
                        .first { it }
                }
            }
            // A guarded owner or interrupted page transition may reject the target.
            // Follow its actual position smoothly before restoring direct tracking.
            do {
                val actual = currentPager()
                motion.animateTo(actual, spring(dampingRatio = 0.86f, stiffness = 550f))
            } while (abs(motion.value - currentPager()) > 0.001f)
            overrideProgress = false
        }
    }

    return PagerPillInteraction(
        modifier = Modifier.onSizeChanged { size = it }
            .pointerInput(enabled, slots, size.height, selectedIndex) {
                if (!enabled || slots.size != 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grabbed = segmentPillAtProgress(slots, currentProgress(), size.height.toFloat())
                    if (!grabbed.contains(down.position)) return@awaitEachGesture
                    val gesture = SegmentPillDrag(slots, grabbed, down.position, viewConfiguration.touchSlop)
                    var started = false
                    var finished = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                if (started) settle(null)
                                finished = true
                                break
                            }
                            val update = gesture.update(
                                change.position, change.pressed, change.isConsumed,
                                event.changes.any { it.id != down.id && it.pressed },
                            )
                            update.pill?.let {
                                if (!started) settling?.cancel()
                                dragPill = it
                                started = true
                            }
                            if (update.consume) change.consume()
                            if (update.finished) {
                                if (started) settle(update.selection)
                                finished = true
                                break
                            }
                        }
                    } finally {
                        if (started && !finished) settle(null)
                    }
                }
            },
        progress = progress,
        select = { destination ->
            if (overrideProgress || dragPill != null) settle(destination)
            else currentSelect(destination)
        },
    )
}

/** Logical progress stays 0 = first, 1 = second in either layout direction. */
internal fun segmentPillAtProgress(slots: List<SegmentSlot>, progress: Float, height: Float): Rect {
    val first = slots.firstOrNull { it.index == 0 } ?: return Rect.Zero
    val second = slots.firstOrNull { it.index == 1 } ?: return first.rect(height)
    val fraction = progress.coerceIn(0f, 1f)
    return Rect(
        first.left + (second.left - first.left) * fraction, 0f,
        first.right + (second.right - first.right) * fraction, height,
    )
}

internal fun segmentPillProgress(slots: List<SegmentSlot>, pill: Rect): Float {
    val first = slots.firstOrNull { it.index == 0 } ?: return 0f
    val second = slots.firstOrNull { it.index == 1 } ?: return 0f
    if (second.center == first.center) return 0f
    return ((pill.center.x - first.center) / (second.center - first.center)).coerceIn(0f, 1f)
}
