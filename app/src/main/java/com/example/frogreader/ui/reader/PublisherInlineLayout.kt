package com.example.frogreader.ui.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Immutable source leaves, shared by all fragments of one inline-block. */
class PublisherInlineContent internal constructor(internal val leaves: List<PublisherInlineLeaf>) {
    internal val widths = ConcurrentHashMap<PublisherInlineWidthKey, Float>()
    internal val heights = ConcurrentHashMap<PublisherInlineWidthKey, Int>()
}

internal data class PublisherInlineLeaf(
    val element: ContentElement,
    val boxes: List<ReaderPublisherBox>,
)

internal data class PublisherInlineWidthKey(
    val availableWidthPx: Int,
    val density: Float,
    val fontScale: Float,
    val settings: ReaderSettings,
    val fontSize: Float,
    val bookFonts: Map<String, FontFamily>,
    val language: String?,
)

/** The same content measurement is used by paged and scrolling surfaces. */
internal class PublisherInlineWidthResolver(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val settings: ReaderSettings,
    private val fontSize: Float,
    private val bookFonts: Map<String, FontFamily>,
    private val language: String?,
) {
    fun contentWidth(box: ReaderPublisherBox, availableWidthPx: Int): Float? {
        return preferredWidth(box, availableWidthPx)?.let(::ceil)?.coerceAtMost(availableWidthPx.toFloat())
    }

    fun atFontSize(size: Float) = if (size == fontSize) this else
        PublisherInlineWidthResolver(measurer, density, settings, size, bookFonts, language)

    /** Whole short text group, measured with the same leaf geometry as the reader. */
    fun contentHeight(box: ReaderPublisherBox, availableWidthPx: Int): Int? {
        val content = box.inlineContent ?: return null
        if (content.leaves.size > 64) return null
        val key = PublisherInlineWidthKey(availableWidthPx, density.density, density.fontScale,
            settings, fontSize, bookFonts, language)
        content.heights[key]?.let { return it }
        var height = 0
        for (leaf in content.leaves) {
            val text = when (val element = leaf.element) {
                is ContentElement.Paragraph -> element.text
                is ContentElement.Heading -> element.styledText
                else -> return null
            }
            val size = fittedFontSize(leaf.boxes, availableWidthPx)
            val resolver = atFontSize(size)
            val geometry = publisherBoxGeometry(leaf.boxes, availableWidthPx,
                with(density) { size.sp.toPx() }, true, true, settings.bookStyles, resolver)
            val widthDp = with(density) { geometry.contentWidthPx.toDp() }
            val (left, right) = ReaderMetrics.horizontalInsets(leaf.element, widthDp, size)
            val (top, bottom) = ReaderMetrics.verticalPaddings(leaf.element, size, settings.bookStyles)
            val width = with(density) {
                (geometry.contentWidthPx - left.roundToPx() - right.roundToPx()).coerceAtLeast(1)
            }
            val style = ReaderMetrics.textStyle(leaf.element, settings, size,
                bookFonts = bookFonts, language = language)
                .withPublisherHangingIndent(geometry, density)
            val bidi = BidiLayoutText.of(text)
            val layout = measurer.measureReader(bidi.display, style,
                constraints = Constraints(maxWidth = width),
                density = density,
                bookFonts = bookFonts,
                publisherStyles = settings.bookStyles,
            )
            height += with(density) { top.roundToPx() + bottom.roundToPx() } +
                geometry.topInsetPx + geometry.bottomInsetPx + layout.size.height
        }
        if (content.heights.size >= 16) content.heights.clear()
        content.heights[key] = height
        return height
    }

    /** Keep shaped text together when its authored insets would leave a glyph-wide column. */
    fun fittedFontSize(boxes: List<ReaderPublisherBox>, columnWidthPx: Int): Float {
        val em = with(density) { fontSize.sp.toPx() }
        var scale = 1f
        boxes.forEachIndexed { index, box ->
            if (!box.style.fitTextFlow || box.inlineContent == null) return@forEachIndexed
            val available = publisherBoxGeometry(
                boxes.take(index + 1).map { it.copy(style = it.style.copy(shrinkToFit = false)) },
                columnWidthPx, em,
                partStartsElement = true, partEndsElement = true, enabled = settings.bookStyles,
            ).contentWidthPx
            val preferred = preferredWidth(box, available) ?: return@forEachIndexed
            if (preferred > available) {
                scale = minOf(scale, (available - 2f).coerceAtLeast(1f) / preferred)
            }
        }
        return fontSize * scale
    }

    private fun preferredWidth(box: ReaderPublisherBox, availableWidthPx: Int): Float? {
        val content = box.inlineContent ?: return null
        val key = PublisherInlineWidthKey(
            availableWidthPx, density.density, density.fontScale,
            settings, fontSize, bookFonts, language,
        )
        content.widths[key]?.let { return it }
        val em = with(density) { fontSize.sp.toPx() }
        val width = content.leaves.maxOfOrNull { leaf ->
            val text = when (val element = leaf.element) {
                is ContentElement.Paragraph -> element.text
                is ContentElement.Heading -> element.styledText
                else -> return@maxOfOrNull 0f
            }
            // Measure the natural width before responsive edge clamps. If
            // clipped geometry were measured here, fitting would falsely
            // conclude that even an overflowing verse already fits.
            val insets = leaf.boxes.sumOf { child ->
                val style = child.style
                if (!settings.bookStyles && style.hangingIndentEm == 0f) 0.0 else {
                    ((style.marginLeftEm + style.marginRightEm + style.paddingLeftEm + style.paddingRightEm) * em +
                        (style.marginLeftFrac + style.marginRightFrac + style.paddingLeftFrac + style.paddingRightFrac) * availableWidthPx)
                        .toDouble()
                }
            }.toFloat()
            val style = ReaderMetrics.textStyle(
                leaf.element, settings, fontSize,
                bookFonts = bookFonts, language = language,
            ).copy(textAlign = TextAlign.Left)
            val bidi = BidiLayoutText.of(text)
            val firstLineOffset = with(density) { (style.textIndent ?: TextIndent.None).firstLine.toPx() }
            val layout = measurer.measureReader(
                text = bidi.display,
                style = style.copy(textIndent = TextIndent.None),
                softWrap = false,
                density = density,
                bookFonts = bookFonts,
                publisherStyles = settings.bookStyles,
            )
            insets + layout.multiParagraph.maxIntrinsicWidth +
                if ('\n' in text.text) firstLineOffset.coerceAtLeast(0f) else firstLineOffset
        }?.coerceAtLeast(1f) ?: return null
        // Pinch zoom can generate many transient sizes; retain a small cache.
        if (content.widths.size >= 16) content.widths.clear()
        content.widths[key] = width
        return width
    }
}
