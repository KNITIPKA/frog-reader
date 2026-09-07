package com.example.frogreader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import kotlin.math.roundToInt

/** Pixel-exact geometry shared by pagination and Compose drawing. */
internal data class PublisherBoxGeometry(
    val contentLeftPx: Int,
    val contentRightPx: Int,
    val topInsetPx: Int,
    val bottomInsetPx: Int,
    val boxes: List<PublisherDrawBox>,
) {
    val contentWidthPx: Int get() = (contentRightPx - contentLeftPx).coerceAtLeast(1)

    companion object {
        fun empty(columnWidthPx: Int) = PublisherBoxGeometry(
            contentLeftPx = 0,
            contentRightPx = columnWidthPx.coerceAtLeast(1),
            topInsetPx = 0,
            bottomInsetPx = 0,
            boxes = emptyList(),
        )
    }
}

/** One nested box rectangle. Bottom is stored as an inset from part bottom. */
internal data class PublisherDrawBox(
    val source: ReaderPublisherBox,
    val leftPx: Float,
    val rightPx: Float,
    val topPx: Float,
    val bottomInsetPx: Float,
    val drawTop: Boolean,
    val drawBottom: Boolean,
)

/**
 * Resolve the retained CSS box tree for one rendered element/page fragment.
 * Percentages use the current containing block and em lengths use the reader's
 * base font, matching the parser's normalized CSS-length contract.
 */
internal fun publisherBoxGeometry(
    boxes: List<ReaderPublisherBox>,
    columnWidthPx: Int,
    fontSizePx: Float,
    partStartsElement: Boolean,
    partEndsElement: Boolean,
    enabled: Boolean,
): PublisherBoxGeometry {
    val safeWidth = columnWidthPx.coerceAtLeast(1)
    if (!enabled || boxes.isEmpty()) return PublisherBoxGeometry.empty(safeWidth)
    val em = fontSizePx.coerceAtLeast(1f)

    var contentLeft = 0f
    var contentRight = safeWidth.toFloat()
    var topCursor = 0f
    var bottomCursor = 0f
    val drawBoxes = ArrayList<PublisherDrawBox>(boxes.size)

    for (box in boxes) {
        val style = box.style
        val containingWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        val marginLeft = edgePx(style.marginLeftEm, style.marginLeftFrac, containingWidth, em)
        val marginRight = edgePx(style.marginRightEm, style.marginRightFrac, containingWidth, em)
        val borderLeft = style.borderLeft.layoutWidthPx(em)
        val borderRight = style.borderRight.layoutWidthPx(em)
        val paddingLeft = edgePx(style.paddingLeftEm, style.paddingLeftFrac, containingWidth, em)
        val paddingRight = edgePx(style.paddingRightEm, style.paddingRightFrac, containingWidth, em)

        val availableLeft = contentLeft + marginLeft
        val availableRight = contentRight - marginRight
        val availableWidth = (availableRight - availableLeft).coerceAtLeast(1f)
        val requestedContentWidth = when {
            style.widthFrac != null -> containingWidth * style.widthFrac.coerceIn(0.01f, 1f)
            style.widthEm != null -> style.widthEm.coerceAtLeast(0.05f) * em
            else -> null
        }
        val requestedOuterWidth = requestedContentWidth?.let {
            it + borderLeft + paddingLeft + paddingRight + borderRight
        }
        val outerWidth = requestedOuterWidth?.coerceAtMost(availableWidth) ?: availableWidth
        val spare = (availableWidth - outerWidth).coerceAtLeast(0f)
        val alignOffset = when (style.horizontalAlign) {
            PublisherBoxAlign.START -> 0f
            PublisherBoxAlign.CENTER -> spare / 2f
            PublisherBoxAlign.END -> spare
        }
        val rectLeft = availableLeft + alignOffset
        val rectRight = (rectLeft + outerWidth).coerceAtMost(availableRight)

        val drawTop = partStartsElement && box.startsAtElement
        val drawBottom = partEndsElement && box.endsAtElement
        val marginTop = if (drawTop) style.marginTopEm.coerceAtLeast(0f) * em else 0f
        val marginBottom = if (drawBottom) {
            style.marginBottomEm.coerceAtLeast(0f) * em
        } else {
            0f
        }
        val top = topCursor + marginTop
        val bottomInset = bottomCursor + marginBottom
        drawBoxes += PublisherDrawBox(
            source = box,
            leftPx = rectLeft,
            rightPx = rectRight,
            topPx = top,
            bottomInsetPx = bottomInset,
            drawTop = drawTop,
            drawBottom = drawBottom,
        )

        contentLeft = rectLeft + borderLeft + paddingLeft
        contentRight = rectRight - borderRight - paddingRight
        if (contentRight <= contentLeft) contentRight = contentLeft + 1f

        if (drawTop) {
            topCursor = top + style.borderTop.layoutWidthPx(em) +
                style.paddingTopEm.coerceAtLeast(0f) * em
        }
        if (drawBottom) {
            bottomCursor = bottomInset + style.borderBottom.layoutWidthPx(em) +
                style.paddingBottomEm.coerceAtLeast(0f) * em
        }
    }

    return PublisherBoxGeometry(
        contentLeftPx = contentLeft.roundToInt().coerceIn(0, safeWidth - 1),
        contentRightPx = contentRight.roundToInt().coerceIn(1, safeWidth),
        topInsetPx = topCursor.roundToInt().coerceAtLeast(0),
        bottomInsetPx = bottomCursor.roundToInt().coerceAtLeast(0),
        boxes = drawBoxes,
    )
}

private fun edgePx(emValue: Float, fraction: Float, containingWidthPx: Float, emPx: Float): Float =
    (emValue.coerceAtLeast(0f) * emPx +
        fraction.coerceIn(0f, MAX_HORIZONTAL_EDGE_FRACTION) * containingWidthPx)
        .coerceAtMost(containingWidthPx * MAX_HORIZONTAL_EDGE_FRACTION)

private fun PublisherBorderSide?.layoutWidthPx(emPx: Float): Float = when {
    this == null || style == PublisherBorderStyle.NONE -> 0f
    else -> (widthEm.coerceIn(0f, MAX_BORDER_EM) * emPx).coerceAtLeast(HAIRLINE_PX)
}

/** Paint backgrounds and physical borders without affecting measured size. */
internal fun Modifier.drawPublisherBoxes(
    geometry: PublisherBoxGeometry,
    columnLeftPx: Float,
    fontSizePx: Float,
): Modifier {
    if (geometry.boxes.isEmpty()) return this
    return drawBehind {
        for (box in geometry.boxes) {
            val left = columnLeftPx + box.leftPx
            val right = columnLeftPx + box.rightPx
            val top = box.topPx
            val bottom = size.height - box.bottomInsetPx
            if (right <= left || bottom <= top) continue
            val style = box.source.style
            style.backgroundColorArgb
                ?.takeIf { (it ushr 24) != 0 }
                ?.let { drawRect(Color(it), Offset(left, top), Size(right - left, bottom - top)) }

            drawPublisherBorder(style.borderLeft, left, top, left, bottom, fontSizePx)
            drawPublisherBorder(style.borderRight, right, top, right, bottom, fontSizePx)
            if (box.drawTop) {
                drawPublisherBorder(style.borderTop, left, top, right, top, fontSizePx)
            }
            if (box.drawBottom) drawPublisherBorder(
                style.borderBottom,
                left,
                bottom,
                right,
                bottom,
                fontSizePx,
            )
        }
    }
}

internal fun DrawScope.drawPublisherBorder(
    side: PublisherBorderSide?,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    fontSizePx: Float,
) {
    if (side == null || side.style == PublisherBorderStyle.NONE) return
    val width = side.layoutWidthPx(fontSizePx.coerceAtLeast(1f)).coerceAtLeast(HAIRLINE_PX)
    val color = Color(side.colorArgb)
    when (side.style) {
        PublisherBorderStyle.NONE -> Unit
        PublisherBorderStyle.SOLID -> drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = width,
        )

        PublisherBorderStyle.DASHED,
        PublisherBorderStyle.DOTTED,
        -> {
            val on = if (side.style == PublisherBorderStyle.DOTTED) width else width * 4f
            val off = if (side.style == PublisherBorderStyle.DOTTED) width * 1.5f else width * 2.5f
            drawLine(
                color = color,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off)),
            )
        }

        PublisherBorderStyle.DOUBLE -> {
            val stroke = (width / 3f).coerceAtLeast(HAIRLINE_PX)
            val offset = width / 3f
            val vertical = x1 == x2
            repeat(2) { index ->
                val sign = if (index == 0) -1f else 1f
                drawLine(
                    color = color,
                    start = if (vertical) {
                        Offset(x1 + sign * offset, y1)
                    } else {
                        Offset(x1, y1 + sign * offset)
                    },
                    end = if (vertical) {
                        Offset(x2 + sign * offset, y2)
                    } else {
                        Offset(x2, y2 + sign * offset)
                    },
                    strokeWidth = stroke,
                )
            }
        }
    }
}

/** True when a retained box already owns the leaf's painted background. */
internal fun hasPublisherBoxBackground(boxes: List<ReaderPublisherBox>): Boolean =
    boxes.any { box ->
        box.style.backgroundColorArgb?.let { (it ushr 24) != 0 } == true
    }

/**
 * Effective surface behind a leaf, including every translucent outer-to-inner
 * publisher layer. Contrast decisions must use this color rather than the raw
 * innermost ARGB value, while drawing still paints the original layers.
 */
internal fun publisherBoxEffectiveBackground(
    boxes: List<ReaderPublisherBox>,
    surroundingBackground: Color,
): Color = boxes.fold(surroundingBackground) { background, box ->
    box.style.backgroundColorArgb
        ?.takeIf { (it ushr 24) != 0 }
        ?.let(::Color)
        ?.let { compositePublisherColorOver(it, background) }
        ?: background
}

private const val MAX_HORIZONTAL_EDGE_FRACTION = 0.45f
private const val MAX_BORDER_EM = 1.5f
private const val HAIRLINE_PX = 1f
