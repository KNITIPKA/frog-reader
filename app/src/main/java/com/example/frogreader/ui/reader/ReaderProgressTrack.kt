package com.example.frogreader.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Keep the cursor inside the chapter outline; its layout box still supplies the original seek geometry. */
@Composable
internal fun ReaderProgressThumb(colors: ReaderColors, enabled: Boolean) {
    Box(Modifier.size(width = 24.dp, height = 32.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 6.dp, height = 9.dp)
                .background(colors.chrome, RoundedCornerShape(3.dp))
                .padding(1.dp),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    if (enabled) colors.onChrome else colors.onChromeVariant,
                    RoundedCornerShape(2.dp),
                ),
            )
        }
    }
}

/** Decorative track only: Material Slider retains touch, keyboard and accessibility seeking. */
@Composable
internal fun ReaderSegmentedProgressTrack(
    fraction: Float,
    chapterStops: List<Float>,
    adjusting: Boolean,
    enabled: Boolean,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val chapters = remember(chapterStops) { ReaderProgressChapters(chapterStops) }
    val value = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val chapter = chapters.at(value)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.fillMaxWidth().height(16.dp)) {
        val shortChapter = chapter.span * maxWidth.value < 28f
        val expansion by animateFloatAsState(
            targetValue = if (enabled && adjusting && shortChapter) 1f else 0f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "readerShortChapterFocus",
        )
        val detail = readerProgressSegmentDetail(chapters.size, maxWidth.value)
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            if (width <= 0f) return@Canvas
            val y = size.height / 2f
            val trackHeight = 5.dp.toPx()
            val cursor = width * if (rtl) 1f - value else value

            fun capsule(start: Float, end: Float, height: Float) {
                val left = if (rtl) width - end else start
                val right = if (rtl) width - start else end
                if (right <= left) return
                val topLeft = Offset(left, y - height / 2f)
                val capsuleSize = Size(right - left, height)
                val radius = CornerRadius(min(height, right - left) / 2f)
                drawRoundRect(colors.track, topLeft, capsuleSize, radius)
                if (enabled) {
                    clipRect(left = if (rtl) cursor else 0f, right = if (rtl) width else cursor) {
                        drawRoundRect(colors.accent, topLeft, capsuleSize, radius)
                    }
                }
            }

            if (detail == 0f) {
                capsule(0f, width, trackHeight)
            } else {
                val gap = 1.6.dp.toPx() * detail
                for (index in 0 until chapters.size) {
                    val segment = chapters[index]
                    val spanPx = segment.span * width
                    val leftGap = if (index == 0) 0f else readerProgressBoundaryGap(
                        chapters[index - 1].span * width, spanPx, gap,
                    ) / 2f
                    val rightGap = if (index == chapters.size - 1) 0f else readerProgressBoundaryGap(
                        spanPx, chapters[index + 1].span * width, gap,
                    ) / 2f
                    capsule(segment.start * width + leftGap, segment.end * width - rightGap, trackHeight)
                }
            }

            if (enabled && chapters.size > 1) {
                val inset = 4.dp.toPx()
                val focus = readerProgressFocus(
                    chapter = chapter,
                    fraction = value,
                    trackWidthPx = width,
                    paddingPx = inset,
                    minimumWidthPx = (8f + 20f * expansion).dp.toPx(),
                    centering = expansion,
                )
                val left = if (rtl) width - focus.endPx else focus.startPx
                val right = if (rtl) width - focus.startPx else focus.endPx
                val focusHeight = (11f + expansion).dp.toPx()
                val stroke = 1.dp.toPx()
                // Opaque backing gives the focused chapter breathing room without moving its neighbours.
                drawRoundRect(
                    colors.chrome, Offset(left, y - focusHeight / 2f), Size(right - left, focusHeight),
                    CornerRadius(focusHeight / 2f),
                )
                capsule(focus.startPx + inset, focus.endPx - inset, 6.dp.toPx())
                drawRoundRect(
                    colors.accent,
                    Offset(left + stroke / 2f, y - focusHeight / 2f + stroke / 2f),
                    Size((right - left - stroke).coerceAtLeast(0f), focusHeight - stroke),
                    CornerRadius(focusHeight / 2f),
                    style = Stroke(stroke),
                )
            }
        }
    }
}
