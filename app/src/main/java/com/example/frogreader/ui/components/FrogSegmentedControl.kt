package com.example.frogreader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PendingSegment(val from: Int, val to: Int)

/** Shared moving selection. Dragging previews a choice; only a successful release commits it. */
@Composable
internal fun FrogSegmentedControl(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    containerColor: Color,
    indicatorColor: Color,
    contentColor: Color,
    selectedContentColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    weights: List<Float> = List(count) { 1f },
    gap: Dp = 0.dp,
    padding: Dp = 4.dp,
    containerRadius: Dp = 24.dp,
    indicatorRadius: Dp = 20.dp,
    indicatorBorder: Color = Color.Transparent,
    minItemHeight: Dp = 40.dp,
    content: @Composable (index: Int, selected: Boolean, color: Color) -> Unit,
) {
    if (count == 0) return
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gapPx = with(density) { gap.roundToPx() }
    val radiusPx = with(density) { indicatorRadius.toPx() }
    val actualWeights = List(count) { weights.getOrElse(it) { 1f } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val slots = remember(size.width, actualWeights, gapPx, rtl) {
        segmentSlots(size.width, actualWeights, gapPx, rtl)
    }
    val motion = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }
    var dragPill by remember { mutableStateOf<Rect?>(null) }
    var pending by remember { mutableStateOf<PendingSegment?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val effectiveIndex = pending?.to ?: selectedIndex
    val target = slots.firstOrNull { it.index == effectiveIndex }?.rect(size.height.toFloat())
    val dragging = dragPill != null
    LaunchedEffect(target, dragging) {
        if (target == null || dragging) return@LaunchedEffect
        if (!initialized) {
            motion.snapTo(target)
            initialized = true
        } else {
            motion.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 550f))
        }
    }
    // External changes, rotation and disabling a control cancel an in-flight grab.
    LaunchedEffect(slots, selectedIndex, enabled) { dragPill = null }
    LaunchedEffect(pending, selectedIndex) {
        val request = pending ?: return@LaunchedEffect
        if (selectedIndex == request.from) delay(450)
        // A guarded owner may reject a choice. Return to its value rather than
        // leaving an optimistic selection on screen indefinitely.
        pending = null
    }
    fun renderedPill(drawSize: Size): Rect {
        val current = dragPill ?: if (initialized && target != null) motion.value else target ?: Rect.Zero
        if (current.width > 0f && current.height > 0f) return current
        // Drawing already has measured bounds on the first frame, before the
        // size callback can recompose animation state (also true in previews).
        return segmentSlots(drawSize.width.toInt(), actualWeights, gapPx, rtl)
            .firstOrNull { it.index == effectiveIndex }?.rect(drawSize.height) ?: Rect.Zero
    }

    fun finishDrag(destination: Int?) {
        val released = dragPill ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            motion.snapTo(released)
            initialized = true
            pending = destination?.takeIf { it != selectedIndex }?.let { PendingSegment(selectedIndex, it) }
            dragPill = null
            if (destination != null && destination != selectedIndex) currentOnSelect(destination)
        }
    }

    Box(
        modifier.selectableGroup().clip(RoundedCornerShape(containerRadius))
            .background(containerColor).padding(padding),
    ) {
        Box(
            Modifier.pointerInput(enabled, slots, selectedIndex, size.height) {
                if (!enabled || slots.size < 2) return@pointerInput
                awaitEachGesture {
                    // Read Main after the child selectable has seen its down.
                    // Its consumed down must not be mistaken for a cancelled drag.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grabbed = renderedPill(Size(size.width.toFloat(), size.height.toFloat()))
                    if (grabbed.width <= 0f || !grabbed.contains(down.position)) return@awaitEachGesture
                    val gesture = SegmentPillDrag(slots, grabbed, down.position, viewConfiguration.touchSlop)
                    var started = false
                    var finished = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                finishDrag(null)
                                finished = true
                                break
                            }
                            val update = gesture.update(
                                change.position, change.pressed, change.isConsumed,
                                otherPointerPressed = event.changes.any { it.id != down.id && it.pressed },
                            )
                            update.pill?.let {
                                dragPill = it
                                started = true
                            }
                            if (update.consume) change.consume()
                            if (update.finished) {
                                finishDrag(update.selection)
                                finished = true
                                break
                            }
                        }
                    } finally {
                        if (started && !finished) finishDrag(null)
                    }
                }
            },
        ) {
            SegmentContentRow(
                actualWeights, gapPx, rtl,
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).onSizeChanged { size = it },
            ) { index ->
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    Modifier.heightIn(min = minItemHeight)
                        .clip(RoundedCornerShape(indicatorRadius))
                        // A bounded ripple paints a second capsule over the moving selection,
                        // leaving a visible trail on taps and drags.
                        .selectable(
                            selected = index == selectedIndex,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { currentOnSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) { content(index, false, contentColor) }
            }
            Canvas(Modifier.matchParentSize()) {
                val visiblePill = renderedPill(this.size)
                if (visiblePill.width > 0f && visiblePill.height > 0f) {
                    drawRoundRect(indicatorColor, visiblePill.topLeft, visiblePill.size, CornerRadius(radiusPx))
                    if (indicatorBorder != Color.Transparent) {
                        drawRoundRect(indicatorBorder, visiblePill.topLeft, visiblePill.size, CornerRadius(radiusPx), style = Stroke(1.dp.toPx()))
                    }
                }
            }
            // Clip the selected ink to the *moving* capsule. Text stays readable
            // throughout travel, including when the pill covers parts of two labels.
            SegmentContentRow(
                actualWeights, gapPx, rtl,
                Modifier.matchParentSize().clearAndSetSemantics {}.drawWithContent {
                    val layer = this
                    val visiblePill = renderedPill(this.size)
                    val mask = Path().apply { addRoundRect(RoundRect(visiblePill, CornerRadius(radiusPx))) }
                    if (visiblePill.width > 0f) clipPath(mask) { layer.drawContent() }
                },
            ) { index ->
                Box(contentAlignment = Alignment.Center) {
                    content(index, true, selectedContentColor)
                }
            }
        }
    }
}

/** Uses the same rounded pixel bounds for drawing, hit testing, RTL and layout. */
@Composable
private fun SegmentContentRow(
    weights: List<Float>,
    gap: Int,
    rtl: Boolean,
    modifier: Modifier,
    content: @Composable (Int) -> Unit,
) {
    Layout(modifier = modifier, content = { weights.indices.forEach { content(it) } }) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            measurables.sumOf { it.maxIntrinsicWidth(Int.MAX_VALUE) } + gap * (measurables.size - 1)
        }
        val slots = segmentSlots(width, weights, gap, rtl).associateBy { it.index }
        val placeables = measurables.mapIndexed { index, measurable ->
            val itemWidth = slots[index]?.width?.toInt() ?: 0
            measurable.measure(constraints.copy(minWidth = itemWidth, maxWidth = itemWidth))
        }
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            placeables.forEachIndexed { index, item ->
                item.place(slots.getValue(index).left.toInt(), (height - item.height) / 2)
            }
        }
    }
}
