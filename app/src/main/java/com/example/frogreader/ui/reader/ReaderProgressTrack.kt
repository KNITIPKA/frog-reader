package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

private val ProgressThumbWidth = 24.dp
private const val ProgressFocusDurationMillis = 100

/** Material's handle keeps its hit/measurement geometry; the cursor is drawn with the capsule. */
@Composable
internal fun ReaderProgressThumb() {
    Box(Modifier.size(width = ProgressThumbWidth, height = 32.dp))
}

/** Material handles gestures; its visual coordinates are converted through the same scale as the track. */
@Composable
internal fun ReaderSegmentedProgressSlider(
    fraction: Float,
    chapterStops: List<Float>,
    positionCount: Int?,
    adjusting: Boolean,
    enabled: Boolean,
    colors: ReaderColors,
    interactionSource: MutableInteractionSource,
    label: String,
    progressDescription: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val trackWidth = (maxWidth - ProgressThumbWidth).value.coerceAtLeast(0f)
        val base = remember(chapterStops, trackWidth, positionCount) {
            ReaderProgressLayout(chapterStops, trackWidth, positionCount)
        }
        var anchor by remember(base) { mutableStateOf<ReaderProgressAnchor?>(null) }
        val layout = rememberReaderProgressLayout(base, fraction, adjusting, enabled, anchor.takeIf { adjusting })
        Slider(
            value = layout.trackFraction(fraction),
            onValueChange = { visual ->
                val book = layout.bookFraction(visual)
                if (base.segmented) {
                    val selected = base.chapters.indexAt(book)
                    if (!adjusting || anchor?.chapterIndex != selected) {
                        anchor = ReaderProgressAnchor(selected, book, visual)
                    }
                }
                onValueChange(book)
            },
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.track,
                disabledThumbColor = colors.onChromeVariant,
                disabledActiveTrackColor = colors.track,
                disabledInactiveTrackColor = colors.track,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = progressDescription
                // Accessibility percentages address real book coordinates.
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    if (!enabled || !target.isFinite() || target.coerceIn(0f, 1f) == fraction) false
                    else {
                        onValueChange(target.coerceIn(0f, 1f))
                        onValueChangeFinished()
                        true
                    }
                }
            },
            thumb = { ReaderProgressThumb() },
            track = {
                ReaderSegmentedProgressTrack(fraction, layout, adjusting, enabled, colors)
            },
        )
    }
}

@Composable
private fun rememberReaderProgressLayout(
    base: ReaderProgressLayout,
    fraction: Float,
    adjusting: Boolean,
    enabled: Boolean,
    seekAnchor: ReaderProgressAnchor?,
): ReaderProgressLayout {
    // Continuous mode has no chapter geometry animation or finger anchors.
    if (!base.segmented) return base
    val index = base.chapters.indexAt(fraction)
    val expanded = adjusting && base.chapters[index].span * base.trackWidthDp < 28f
    val focusFraction = if (seekAnchor == null && !expanded) {
        val chapter = base.chapters[index]
        chapter.start + chapter.span / 2f
    } else fraction
    val target = remember(base, index, expanded, seekAnchor, enabled, focusFraction.takeIf { expanded && seekAnchor == null }) {
        if (enabled) base.focused(focusFraction, expanded, seekAnchor) else base
    }
    val phase = remember(base) { Animatable(1f) }
    var transition by remember(base) { mutableStateOf(ReaderProgressTransition(target, target)) }
    LaunchedEffect(target) {
        if (!adjusting && transition.focusedIndex != target.focusedIndex) {
            transition = ReaderProgressTransition(target, target)
            phase.snapTo(1f)
        } else {
            val next = ReaderProgressTransition(transition.frame(phase.value), target)
            phase.snapTo(0f)
            transition = next
            phase.animateTo(1f, tween(ProgressFocusDurationMillis, easing = LinearOutSlowInEasing))
        }
    }
    // Normal page turns never wait for a shape animation to select a chapter.
    return transition.frame(phase.value, target, animateChapterChange = adjusting)
}

@Composable
internal fun ReaderSegmentedProgressTrack(
    fraction: Float,
    layout: ReaderProgressLayout,
    adjusting: Boolean,
    enabled: Boolean,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val chapters = layout.chapters
    val value = layout.trackFraction(fraction)
    val index = if (layout.segmented) chapters.indexAt(fraction) else null
    val chapter = index?.let(layout::segment)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.fillMaxWidth().height(22.dp)) {
        val expansion = if (index != null) animateFloatAsState(
            targetValue = if (enabled && adjusting && chapters[index].span * layout.trackWidthDp < 28f) 1f else 0f,
            animationSpec = tween(ProgressFocusDurationMillis, easing = LinearOutSlowInEasing),
            label = "readerShortChapterFocus",
        ).value else 0f
        val detail = layout.detail
        val activeTick = colors.onAccent.copy(alpha = 0.45f)
        val inactiveTick = colors.onChromeVariant.copy(alpha = 0.45f)
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            if (width <= 0f) return@Canvas
            val y = size.height / 2f
            val trackHeight = 9.dp.toPx()
            val cursor = width * if (rtl) 1f - value else value

            fun capsule(start: Float, end: Float, height: Float) {
                val left = if (rtl) width - end else start
                val right = if (rtl) width - start else end
                if (right <= left) return
                val topLeft = Offset(left, y - height / 2f)
                val capsuleSize = Size(right - left, height)
                val radius = CornerRadius(min(1.5.dp.toPx(), min(height, right - left) / 2f))
                drawRoundRect(colors.track, topLeft, capsuleSize, radius)
                if (enabled) {
                    clipRect(left = if (rtl) cursor else 0f, right = if (rtl) width else cursor) {
                        drawRoundRect(colors.accent, topLeft, capsuleSize, radius)
                    }
                }
            }

            fun marker() {
                val outerWidth = 6.dp.toPx()
                val outerHeight = 13.dp.toPx()
                drawRoundRect(
                    colors.chrome, Offset(cursor - outerWidth / 2f, y - outerHeight / 2f),
                    Size(outerWidth, outerHeight), CornerRadius(3.dp.toPx()),
                )
                val innerWidth = 4.dp.toPx()
                val innerHeight = 11.dp.toPx()
                drawRoundRect(
                    if (enabled) colors.onChrome else colors.onChromeVariant,
                    Offset(cursor - innerWidth / 2f, y - innerHeight / 2f),
                    Size(innerWidth, innerHeight), CornerRadius(2.dp.toPx()),
                )
            }

            if (detail == 0f) {
                capsule(0f, width, trackHeight)
                // Inset marks preserve the uninterrupted book scale. Their
                // sparse positions are cached with the fallback geometry.
                val tickWidth = 0.75.dp.toPx()
                val tickHeight = 5.dp.toPx()
                val cursorClearance = 4.dp.toPx()
                for (stop in layout.segmentPlan.fallbackTicks) {
                    val x = width * if (rtl) 1f - stop else stop
                    if (abs(x - cursor) < cursorClearance) continue
                    drawRoundRect(
                        if (enabled && stop <= value) activeTick else inactiveTick,
                        Offset(x - tickWidth / 2f, y - tickHeight / 2f),
                        Size(tickWidth, tickHeight), CornerRadius(tickWidth / 2f),
                    )
                }
            } else {
                val gap = 2.dp.toPx() * detail
                for (index in 0 until chapters.size) {
                    val start = layout.boundaries[index]
                    val end = layout.boundaries[index + 1]
                    val spanPx = (end - start) * width
                    val leftGap = if (index == 0) 0f else readerProgressBoundaryGap(
                        (start - layout.boundaries[index - 1]) * width, spanPx, gap,
                    ) / 2f
                    val rightGap = if (index == chapters.size - 1) 0f else readerProgressBoundaryGap(
                        spanPx, (layout.boundaries[index + 2] - end) * width, gap,
                    ) / 2f
                    if (!enabled || index != layout.focusedIndex) {
                        val inset = layout.inset(index) * width
                        capsule(start * width + max(leftGap, inset), end * width - max(rightGap, inset), trackHeight)
                    }
                }
            }

            if (enabled && layout.focusedIndex != null && index != null && chapter != null && chapter.span > 0f) {
                val left = width * if (rtl) 1f - chapter.end else chapter.start
                val right = width * if (rtl) 1f - chapter.start else chapter.end
                val focusHeight = (17f + expansion * 2f).dp.toPx()
                val stroke = 1.dp.toPx()
                val focusRadius = min(4.dp.toPx(), min(focusHeight, right - left) / 2f)
                // The outline and its backing stay inside this chapter's allocated span.
                drawRoundRect(
                    colors.chrome, Offset(left, y - focusHeight / 2f), Size(right - left, focusHeight),
                    CornerRadius(focusRadius),
                )
                val inset = layout.inset(index) * width
                capsule(chapter.start * width + inset, chapter.end * width - inset, trackHeight)
                // At a finger anchor the padding can briefly shrink during
                // expansion. Clip the cursor to the capsule interior, never
                // to a separate thumb box extending into its neighbours.
                if (right - left > stroke * 2f) {
                    val interior = Path().apply {
                        addRoundRect(RoundRect(
                            left + stroke, y - focusHeight / 2f + stroke,
                            right - stroke, y + focusHeight / 2f - stroke,
                            CornerRadius((focusRadius - stroke).coerceAtLeast(0f)),
                        ))
                    }
                    clipPath(interior) { marker() }
                }
                drawRoundRect(
                    colors.accent,
                    Offset(left + stroke / 2f, y - focusHeight / 2f + stroke / 2f),
                    Size((right - left - stroke).coerceAtLeast(0f), focusHeight - stroke),
                    CornerRadius(focusRadius),
                    style = Stroke(stroke),
                )
            } else {
                marker()
            }
        }
    }
}
