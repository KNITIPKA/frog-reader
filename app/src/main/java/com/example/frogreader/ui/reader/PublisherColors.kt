package com.example.frogreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.example.frogreader.data.model.BlockStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Author colors after publisher-toggle and readability policy are applied. */
internal data class PublisherColorPair(
    val foreground: Color,
    /** Raw author layer to paint, or null when the reader surface remains visible. */
    val background: Color?,
    /** What text actually sits on after alpha compositing. */
    val effectiveBackground: Color,
)

/**
 * Resolve one native block without letting a lone author color disappear into
 * the user's light/sepia/OLED surface. A complete author foreground/background
 * pair is kept verbatim; when only one side exists, the missing side comes from
 * the reader and the foreground is minimally moved toward black/white until it
 * reaches WCAG normal-text contrast.
 */
internal fun publisherColorPair(
    block: BlockStyle?,
    enabled: Boolean,
    defaultForeground: Color,
    surroundingBackground: Color,
): PublisherColorPair = publisherColorPair(
    foregroundArgb = block?.foregroundColorArgb,
    backgroundArgb = block?.backgroundColorArgb,
    enabled = enabled,
    defaultForeground = defaultForeground,
    surroundingBackground = surroundingBackground,
)

internal fun publisherColorPair(
    foregroundArgb: Int?,
    backgroundArgb: Int?,
    enabled: Boolean,
    defaultForeground: Color,
    surroundingBackground: Color,
): PublisherColorPair {
    if (!enabled) {
        return PublisherColorPair(defaultForeground, null, surroundingBackground)
    }
    val authorForeground = foregroundArgb?.let(::Color)
    val authorBackground = backgroundArgb?.let(::Color)?.takeIf { it.alpha > 0f }
    val effectiveBackground = authorBackground
        ?.let { compositeOver(it, surroundingBackground) }
        ?: surroundingBackground
    val foreground = when {
        authorForeground != null && authorBackground != null -> authorForeground
        authorForeground != null -> readableForeground(authorForeground, effectiveBackground)
        authorBackground != null -> readableForeground(defaultForeground, effectiveBackground)
        else -> defaultForeground
    }
    return PublisherColorPair(foreground, authorBackground, effectiveBackground)
}

/**
 * Gate author span colors without touching links, image/string annotations or
 * geometry. Call this before adding reader-owned link/search/quote highlights.
 */
internal fun AnnotatedString.withPublisherColors(
    enabled: Boolean,
    base: PublisherColorPair,
): AnnotatedString {
    val colored = spanStyles.withIndex().filter { (_, range) ->
            range.item.color != Color.Unspecified ||
                range.item.background != Color.Unspecified
        }
    if (colored.isEmpty()) return this

    val mapped = if (!enabled) {
        mapAnnotations { range ->
            val span = range.item as? SpanStyle ?: return@mapAnnotations range
            AnnotatedString.Range(
                span.copy(color = Color.Unspecified, background = Color.Unspecified),
                range.start,
                range.end,
                range.tag,
            )
        }
    } else {
        this
    }
    if (!enabled) return mapped

    // Resolve overlaps as Compose does: later SpanStyle ranges override the
    // same property. A sweep keeps this O(ranges log ranges), including books
    // with thousands of nested spans, and lets a foreground in an inner span
    // see a background supplied by an outer span (or vice versa).
    val starts = HashMap<Int, MutableList<Pair<Int, SpanStyle>>>()
    val ends = HashMap<Int, MutableList<Int>>()
    val boundaries = java.util.TreeSet<Int>()
    for ((index, range) in colored) {
        if (range.start >= range.end) continue
        starts.getOrPut(range.start) { mutableListOf() }.add(index to range.item)
        ends.getOrPut(range.end) { mutableListOf() }.add(index)
        boundaries += range.start
        boundaries += range.end
    }
    if (boundaries.size < 2) return mapped
    val activeForeground = java.util.TreeMap<Int, Color>()
    val activeBackground = java.util.TreeMap<Int, Color>()
    val positions = boundaries.toList()
    val builder = AnnotatedString.Builder(mapped)
    var pendingColor: Color? = null
    var pendingStart = -1
    var pendingEnd = -1
    fun flush() {
        val color = pendingColor ?: return
        builder.addStyle(SpanStyle(color = color), pendingStart, pendingEnd)
        pendingColor = null
    }
    for (positionIndex in 0 until positions.lastIndex) {
        val position = positions[positionIndex]
        ends[position].orEmpty().forEach { index ->
            activeForeground.remove(index)
            activeBackground.remove(index)
        }
        starts[position].orEmpty().sortedBy { it.first }.forEach { (index, span) ->
            if (span.color != Color.Unspecified) activeForeground[index] = span.color
            if (span.background != Color.Unspecified && span.background.alpha > 0f) {
                activeBackground[index] = span.background
            }
        }
        val end = positions[positionIndex + 1]
        if (position >= end) continue
        val authorForeground = activeForeground.lastEntry()?.value
        val authorBackground = activeBackground.lastEntry()?.value
        val correction = when {
            // A complete pair is the publisher's deliberate choice.
            authorForeground != null && authorBackground != null -> null
            authorForeground != null -> readableForeground(
                authorForeground,
                base.effectiveBackground,
            ).takeIf { it != authorForeground }
            authorBackground != null -> readableForeground(
                base.foreground,
                compositeOver(authorBackground, base.effectiveBackground),
            ).takeIf { it != base.foreground }
            else -> null
        }
        if (correction != null && correction == pendingColor && position == pendingEnd) {
            pendingEnd = end
        } else {
            flush()
            if (correction != null) {
                pendingColor = correction
                pendingStart = position
                pendingEnd = end
            }
        }
    }
    flush()
    return builder.toAnnotatedString()
}

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val visibleForeground = compositeOver(foreground, background)
    val lighter = max(relativeLuminance(visibleForeground), relativeLuminance(background))
    val darker = min(relativeLuminance(visibleForeground), relativeLuminance(background))
    return ((lighter + 0.05) / (darker + 0.05)).toFloat()
}

internal fun readableReaderForeground(foreground: Color, background: Color): Color =
    readableForeground(foreground, background)

private fun readableForeground(foreground: Color, background: Color): Color {
    if (contrastRatio(foreground, background) >= MIN_TEXT_CONTRAST) return foreground
    val opaque = Color(foreground.red, foreground.green, foreground.blue, 1f)
    val black = Color.Black
    val white = Color.White
    val target = if (contrastRatio(black, background) >= contrastRatio(white, background)) {
        black
    } else {
        white
    }
    if (contrastRatio(target, background) < MIN_TEXT_CONTRAST) return target
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (contrastRatio(lerp(opaque, target, middle), background) >= MIN_TEXT_CONTRAST) {
            high = middle
        } else {
            low = middle
        }
    }
    return lerp(opaque, target, high)
}

private fun lerp(start: Color, end: Color, fraction: Float): Color = Color(
    red = start.red + (end.red - start.red) * fraction,
    green = start.green + (end.green - start.green) * fraction,
    blue = start.blue + (end.blue - start.blue) * fraction,
    alpha = start.alpha + (end.alpha - start.alpha) * fraction,
)

private fun compositeOver(foreground: Color, background: Color): Color {
    val outAlpha = foreground.alpha + background.alpha * (1f - foreground.alpha)
    if (outAlpha <= 0f) return Color.Transparent
    fun channel(foregroundChannel: Float, backgroundChannel: Float): Float =
        (foregroundChannel * foreground.alpha +
            backgroundChannel * background.alpha * (1f - foreground.alpha)) / outAlpha
    return Color(
        red = channel(foreground.red, background.red),
        green = channel(foreground.green, background.green),
        blue = channel(foreground.blue, background.blue),
        alpha = outAlpha,
    )
}

private fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val value = channel.toDouble().coerceIn(0.0, 1.0)
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) +
        0.7152 * linear(color.green) +
        0.0722 * linear(color.blue)
}

private const val MIN_TEXT_CONTRAST = 4.5f
