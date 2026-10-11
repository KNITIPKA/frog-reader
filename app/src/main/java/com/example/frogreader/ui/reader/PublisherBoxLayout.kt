package com.example.frogreader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherBoxStyle
import kotlin.math.roundToInt

/** Pixel-exact geometry shared by pagination and Compose drawing. */
internal data class PublisherBoxGeometry(
    val contentLeftPx: Int,
    val contentRightPx: Int,
    val topInsetPx: Int,
    val bottomInsetPx: Int,
    val boxes: List<PublisherDrawBox>,
    val hangingIndentPx: Int = 0,
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
    inlineWidths: PublisherInlineWidthResolver? = null,
    viewportHeightPx: Int? = null,
): PublisherBoxGeometry {
    val safeWidth = columnWidthPx.coerceAtLeast(1)
    if (boxes.isEmpty()) return PublisherBoxGeometry.empty(safeWidth)
    // Verse indentation and an inline-block stanza's position are content
    // structure, like <br>, even when the reader supplies the typography.
    // Decoration and surrounding publication margins still follow the toggle.
    val activeBoxes = if (enabled) boxes else boxes.mapNotNull { box ->
        box.takeIf { it.style.hangingIndentEm > 0f || it.style.shrinkToFit || it.style.fitTextFlow }?.copy(
            style = box.style.copy(
                marginTopEm = 0f, marginBottomEm = 0f,
                paddingTopEm = 0f, paddingBottomEm = 0f,
                backgroundColorArgb = null,
                borderTop = null, borderRight = null, borderBottom = null, borderLeft = null,
                marginLeftEm = if (box.style.hangingIndentEm > 0f) box.style.marginLeftEm else 0f,
                marginRightEm = if (box.style.hangingIndentEm > 0f) box.style.marginRightEm else 0f,
                marginLeftFrac = if (box.style.hangingIndentEm > 0f) box.style.marginLeftFrac else 0f,
                marginRightFrac = if (box.style.hangingIndentEm > 0f) box.style.marginRightFrac else 0f,
            ),
        )
    }
    if (activeBoxes.isEmpty()) return PublisherBoxGeometry.empty(safeWidth)
    val em = fontSizePx.coerceAtLeast(1f)

    var contentLeft = 0f
    var contentRight = safeWidth.toFloat()
    var topCursor = 0f
    var bottomCursor = 0f
    val drawBoxes = ArrayList<PublisherDrawBox>(boxes.size)

    for (box in activeBoxes) {
        val style = box.style
        val containingWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        // A hanging verse line starts before its content padding. Applying
        // the prose 45% cap to that padding flattens all the larger steps,
        // especially after the enclosing stanza has shrunk to fit its text.
        val edgeLimit = if (style.hangingIndentEm > 0f) 1f else MAX_HORIZONTAL_EDGE_FRACTION
        val marginLeft = edgePx(style.marginLeftEm, style.marginLeftFrac, containingWidth, em, edgeLimit)
        val marginRight = edgePx(style.marginRightEm, style.marginRightFrac, containingWidth, em, edgeLimit)
        val borderLeft = style.borderLeft.layoutWidthPx(em)
        val borderRight = style.borderRight.layoutWidthPx(em)
        val paddingLeft = edgePx(style.paddingLeftEm, style.paddingLeftFrac, containingWidth, em, edgeLimit)
        val paddingRight = edgePx(style.paddingRightEm, style.paddingRightFrac, containingWidth, em, edgeLimit)

        val availableLeft = contentLeft + marginLeft
        val availableRight = contentRight - marginRight
        val availableWidth = (availableRight - availableLeft).coerceAtLeast(1f)
        val horizontalFrame = borderLeft + paddingLeft + paddingRight + borderRight
        val maximumContentWidth = listOfNotNull(
            style.maxWidthFrac?.takeIf { it.isFinite() && it > 0f }
                ?.let { containingWidth * it },
            style.maxWidthEm?.takeIf { it.isFinite() && it > 0f }?.let { it * em },
        ).minOrNull()
        val availableContentWidth = (availableWidth - horizontalFrame).coerceAtLeast(1f)
            .let { if (maximumContentWidth != null) minOf(it, maximumContentWidth) else it }
        val requestedContentWidth = when {
            style.widthFrac != null -> containingWidth * style.widthFrac.coerceIn(0.01f, 1f)
            style.widthEm != null -> style.widthEm.coerceAtLeast(0.05f) * em
            style.shrinkToFit -> inlineWidths?.contentWidth(
                box,
                availableContentWidth.roundToInt().coerceAtLeast(1),
            )
            else -> null
        }
        val constrainedContentWidth = minOf(
            requestedContentWidth ?: availableContentWidth,
            maximumContentWidth ?: Float.MAX_VALUE,
        )
        val outerWidth = (constrainedContentWidth + horizontalFrame).coerceAtMost(availableWidth)
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
        val verticalFrame = style.borderTop.layoutWidthPx(em) + style.borderBottom.layoutWidthPx(em) +
            (style.paddingTopEm.coerceAtLeast(0f) + style.paddingBottomEm.coerceAtLeast(0f)) * em
        val centerInset = if (style.centerContentVertically && enabled) {
            inlineWidths?.contentHeight(box,
                (rectRight - rectLeft - horizontalFrame).roundToInt().coerceAtLeast(1))?.let { height ->
                publisherVerticalCenterInset(style, viewportHeightPx, em, height, verticalFrame)
            } ?: 0f
        } else 0f
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
                style.paddingTopEm.coerceAtLeast(0f) * em + centerInset
        }
        if (drawBottom) {
            bottomCursor = bottomInset + style.borderBottom.layoutWidthPx(em) +
                style.paddingBottomEm.coerceAtLeast(0f) * em + centerInset
        }
    }

    val textLeft = contentLeft.roundToInt().coerceIn(0, safeWidth - 1)
    val hangingIndent = ((activeBoxes.lastOrNull()?.style?.hangingIndentEm ?: 0f) * em)
        .roundToInt().coerceIn(0, textLeft)
    return PublisherBoxGeometry(
        contentLeftPx = textLeft - hangingIndent,
        contentRightPx = contentRight.roundToInt().coerceIn(1, safeWidth),
        topInsetPx = topCursor.roundToInt().coerceAtLeast(0),
        bottomInsetPx = bottomCursor.roundToInt().coerceAtLeast(0),
        boxes = drawBoxes,
        hangingIndentPx = hangingIndent,
    )
}

/** Extra space on each edge of a short authored vertical flex page. */
internal fun publisherVerticalCenterInset(
    style: PublisherBoxStyle,
    viewportHeightPx: Int?,
    emPx: Float,
    contentHeightPx: Int,
    verticalFramePx: Float,
): Float {
    if (!style.centerContentVertically) return 0f
    val minimumHeight = style.minHeightViewportFrac?.takeIf { it.isFinite() && it > 0f }?.let { fraction ->
        viewportHeightPx?.takeIf { it > 0 }?.let { viewport ->
            (fraction * viewport + style.minHeightOffsetEm * emPx).coerceAtLeast(0f)
        }
    } ?: style.minHeightEm?.takeIf { it.isFinite() && it > 0f }?.let { it * emPx }
        ?: return 0f
    return ((minimumHeight - contentHeightPx - if (style.borderBox) verticalFramePx else 0f) / 2f)
        .coerceAtLeast(0f)
}

/**
 * Android's negative first-line margin moves glyphs but does not reliably
 * enlarge their wrapping width. Move the text frame left instead, then give
 * continuation lines a positive inset to reproduce the same CSS hanging box.
 */
internal fun TextStyle.withPublisherHangingIndent(
    geometry: PublisherBoxGeometry,
    density: Density,
    isParagraphStart: Boolean = true,
): TextStyle {
    if (geometry.hangingIndentPx == 0) return this
    val rest = with(density) { geometry.hangingIndentPx.toSp() }
    return copy(textIndent = TextIndent(firstLine = if (isParagraphStart) 0.sp else rest, restLine = rest))
}

private fun edgePx(
    emValue: Float,
    fraction: Float,
    containingWidthPx: Float,
    emPx: Float,
    limit: Float = MAX_HORIZONTAL_EDGE_FRACTION,
): Float =
    (emValue.coerceAtLeast(0f) * emPx +
        fraction.coerceIn(0f, MAX_HORIZONTAL_EDGE_FRACTION) * containingWidthPx)
        .coerceAtMost(containingWidthPx * limit)

private fun PublisherBorderSide?.layoutWidthPx(emPx: Float): Float = when {
    this == null || style == PublisherBorderStyle.NONE -> 0f
    else -> (widthEm.coerceIn(0f, MAX_BORDER_EM) * emPx).coerceAtLeast(HAIRLINE_PX)
}

/** Paint backgrounds and physical borders without affecting measured size. */
internal fun Modifier.drawPublisherBoxes(
    geometry: PublisherBoxGeometry,
    columnLeftPx: Float,
    fontSizePx: Float,
    currentColor: Color? = null,
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

            drawPublisherBorder(style.borderLeft, left, top, left, bottom, fontSizePx, currentColor)
            drawPublisherBorder(style.borderRight, right, top, right, bottom, fontSizePx, currentColor)
            if (box.drawTop) {
                drawPublisherBorder(style.borderTop, left, top, right, top, fontSizePx, currentColor)
            }
            if (box.drawBottom) drawPublisherBorder(
                style.borderBottom,
                left,
                bottom,
                right,
                bottom,
                fontSizePx,
                currentColor,
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
    currentColor: Color? = null,
) {
    if (side == null || side.style == PublisherBorderStyle.NONE) return
    val width = side.layoutWidthPx(fontSizePx.coerceAtLeast(1f)).coerceAtLeast(HAIRLINE_PX)
    val color = publisherBorderColor(side, currentColor)
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

internal fun publisherBorderColor(side: PublisherBorderSide, currentColor: Color?): Color =
    if (side.usesCurrentColor && currentColor != null) currentColor else Color(side.colorArgb)

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
