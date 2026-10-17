package com.example.frogreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.InlineMathNode
import com.example.frogreader.data.model.InlineMathStyle
import com.example.frogreader.data.model.RubySpan
import com.example.frogreader.data.model.completeMathInlineMarks
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.max

internal data class MathGlyphMetrics(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val layout: TextLayoutResult? = null,
)

internal data class NativeMathGlyph(val layout: TextLayoutResult?, val topLeft: Offset, val scaleY: Float = 1f)
internal data class NativeMathRule(val start: Offset, val end: Offset, val width: Float)

/** Immutable glyph positions/rules shared between Compose measurement and Canvas. */
internal class NativeMathLayout(
    val widthPx: Float,
    val heightPx: Float,
    val glyphs: List<NativeMathGlyph>,
    val rules: List<NativeMathRule>,
    val scale: Float = 1f,
) {
    fun fitted(maxWidthPx: Int?): NativeMathLayout {
        if (maxWidthPx == null || maxWidthPx <= 0 || widthPx <= maxWidthPx) return this
        val ratio = maxWidthPx / widthPx
        return NativeMathLayout(widthPx * ratio, heightPx * ratio, glyphs, rules, scale * ratio)
    }
}

internal class MathInlinePlacement(
    val start: Int,
    val end: Int,
    val id: String,
    val placeholder: Placeholder,
    val layout: NativeMathLayout,
    private val color: Color,
    private val description: String,
) {
    @Composable
    fun Content() {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            drawInlineMath(layout, color = color)
        }
    }
}

/** The primitive typesetter also accepts synthetic metrics for platform-free geometry tests. */
internal class NativeMathTypesetter(
    private val density: Density,
    private val baseStyle: TextStyle,
    private val measure: (String, TextStyle) -> MathGlyphMetrics,
) {
    private data class Box(
        val width: Float,
        val ascent: Float,
        val descent: Float,
        val glyphs: List<NativeMathGlyph> = emptyList(),
        val rules: List<NativeMathRule> = emptyList(),
    ) {
        fun shifted(x: Float, baseline: Float): Box = copy(
            glyphs = glyphs.map { it.copy(topLeft = it.topLeft + Offset(x, baseline)) },
            rules = rules.map { it.copy(start = it.start + Offset(x, baseline), end = it.end + Offset(x, baseline)) },
        )
    }

    fun layout(root: InlineMathNode): NativeMathLayout? {
        val rootEm = with(density) { baseStyle.fontSize.toPx() }
        val box = visit(root, baseStyle)
        if (!rootEm.isFinite() || rootEm <= 0f || !box.width.isFinite() ||
            box.width <= 0f || box.width > rootEm * 512f ||
            box.ascent + box.descent > rootEm * 32f) return null
        // TextCenter aligns the formula's mathematical axis with the ordinary
        // text's center, keeping denominators below the surrounding baseline.
        val axis = -rootEm * 0.25f
        val half = max(box.ascent + axis, box.descent - axis).coerceAtLeast(rootEm * 0.45f)
        val top = axis - half
        val height = ceil(half * 2f).coerceAtLeast(1f)
        val shifted = box.shifted(0f, -top)
        return NativeMathLayout(ceil(box.width), height, shifted.glyphs, shifted.rules)
    }

    private fun visit(node: InlineMathNode, inherited: TextStyle): Box {
        val style = styled(inherited, node.style)
        val em = with(density) { style.fontSize.toPx() }
        return when (node.kind) {
            "token" -> {
                val glyph = measure(node.text, style)
                val left = node.leftSpaceEm * em
                Box(glyph.width + left + node.rightSpaceEm * em, glyph.ascent, glyph.descent,
                    listOf(NativeMathGlyph(glyph.layout, Offset(left, -glyph.ascent))))
            }
            "row" -> row(node.children.map { visit(it, style) }, node.children)
            "frac" -> fraction(node, style, em)
            "sqrt", "root" -> radical(node, style, em)
            "sup", "sub", "subsup" -> scripts(node, style, em)
            else -> error("Validated MathML node required")
        }
    }

    private fun row(children: List<Box>, nodes: List<InlineMathNode> = emptyList()): Box {
        val naturalAscent = children.maxOfOrNull { it.ascent } ?: 0f
        val naturalDescent = children.maxOfOrNull { it.descent } ?: 0f
        var x = 0f
        val glyphs = ArrayList<NativeMathGlyph>()
        val rules = ArrayList<NativeMathRule>()
        for ((index, original) in children.withIndex()) {
            val child = if (nodes.getOrNull(index)?.stretchy == true &&
                original.glyphs.size == 1 && original.rules.isEmpty() &&
                naturalAscent + naturalDescent > original.ascent + original.descent + 1f) {
                val ratio = ((naturalAscent + naturalDescent) / (original.ascent + original.descent))
                    .coerceIn(1f, 8f)
                val center = (naturalDescent - naturalAscent) / 2f
                val shift = center - (original.descent - original.ascent) * ratio / 2f
                original.copy(ascent = naturalAscent, descent = naturalDescent,
                    glyphs = original.glyphs.map { glyph -> glyph.copy(
                        topLeft = Offset(glyph.topLeft.x, glyph.topLeft.y * ratio + shift),
                        scaleY = glyph.scaleY * ratio,
                    ) })
            } else original
            val shifted = child.shifted(x, 0f)
            glyphs.addAll(shifted.glyphs)
            rules.addAll(shifted.rules)
            x += child.width
        }
        return Box(x, naturalAscent, naturalDescent, glyphs, rules)
    }

    private fun fraction(node: InlineMathNode, style: TextStyle, em: Float): Box {
        val smaller = resized(style, 0.85f)
        val numerator = visit(node.children[0], smaller)
        val denominator = visit(node.children[1], smaller)
        val width = max(numerator.width, denominator.width) + em * 0.24f
        val axis = -em * 0.25f
        val gap = em * 0.13f
        val numeratorBaseline = axis - gap - numerator.descent
        val denominatorBaseline = axis + gap + denominator.ascent
        val upper = numerator.shifted((width - numerator.width) / 2f, numeratorBaseline)
        val lower = denominator.shifted((width - denominator.width) / 2f, denominatorBaseline)
        return Box(width, numerator.ascent - numeratorBaseline, denominator.descent + denominatorBaseline,
            upper.glyphs + lower.glyphs,
            upper.rules + lower.rules + NativeMathRule(Offset(0f, axis), Offset(width, axis), ruleWidth(em)))
    }

    private fun radical(node: InlineMathNode, style: TextStyle, em: Float): Box {
        val radicand = if (node.kind == "sqrt") row(node.children.map { visit(it, style) }, node.children)
            else visit(node.children[0], style)
        val index = if (node.kind == "root") visit(node.children[1], resized(style, 0.55f)) else null
        val inset = index?.width?.times(0.8f) ?: 0f
        val rootWidth = em * 0.62f
        val top = -radicand.ascent - em * 0.12f
        val content = radicand.shifted(inset + rootWidth, 0f)
        val width = inset + rootWidth + radicand.width + em * 0.08f
        val bottom = max(radicand.descent, em * 0.13f)
        val points = listOf(
            Offset(inset + em * 0.02f, -em * 0.12f),
            Offset(inset + em * 0.16f, -em * 0.18f),
            Offset(inset + em * 0.29f, bottom),
            Offset(inset + rootWidth, top),
            Offset(width, top),
        )
        val rootRules = points.zipWithNext { start, end -> NativeMathRule(start, end, ruleWidth(em)) }
        val placedIndex = index?.shifted(0f, top + index.ascent * 0.70f)
        return Box(width, max(-top + em * 0.04f,
            placedIndex?.let { index.ascent - (top + index.ascent * 0.70f) } ?: 0f), bottom,
            content.glyphs + placedIndex?.glyphs.orEmpty(), content.rules + placedIndex?.rules.orEmpty() + rootRules)
    }

    private fun scripts(node: InlineMathNode, style: TextStyle, em: Float): Box {
        val base = visit(node.children[0], style)
        val scriptStyle = resized(style, 0.70f)
        val sub = if (node.kind != "sup") visit(node.children[1], scriptStyle) else null
        val sup = if (node.kind == "sup") visit(node.children[1], scriptStyle)
            else if (node.kind == "subsup") visit(node.children[2], scriptStyle) else null
        var superBaseline = sup?.let { minOf(-em * 0.55f, -base.ascent + it.ascent * 0.25f) } ?: 0f
        var subBaseline = sub?.let { max(em * 0.25f, base.descent + it.ascent * 0.65f) } ?: 0f
        if (sup != null && sub != null) {
            val missingGap = em * 0.15f - (subBaseline - sub.ascent - superBaseline - sup.descent)
            if (missingGap > 0f) { superBaseline -= missingGap / 2f; subBaseline += missingGap / 2f }
        }
        val x = base.width + em * 0.04f
        val upper = sup?.shifted(x, superBaseline)
        val lower = sub?.shifted(x, subBaseline)
        return Box(x + max(sup?.width ?: 0f, sub?.width ?: 0f),
            max(base.ascent, sup?.let { it.ascent - superBaseline } ?: 0f),
            max(base.descent, sub?.let { it.descent + subBaseline } ?: 0f),
            base.glyphs + upper?.glyphs.orEmpty() + lower?.glyphs.orEmpty(),
            base.rules + upper?.rules.orEmpty() + lower?.rules.orEmpty())
    }

    private fun resized(style: TextStyle, factor: Float): TextStyle =
        style.copy(fontSize = boundedSize(style.fontSize.value * factor).sp)

    private fun styled(inherited: TextStyle, style: InlineMathStyle): TextStyle {
        val family = when (style.fontFamily) {
            "serif" -> FontFamily.Serif
            "sans-serif" -> FontFamily.SansSerif
            "monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> inherited.fontFamily
        }
        val decorations = listOfNotNull(TextDecoration.Underline.takeIf { style.underline },
            TextDecoration.LineThrough.takeIf { style.strike })
        return inherited.copy(
            fontSize = boundedSize(inherited.fontSize.value * style.fontSizeEm).sp,
            fontStyle = style.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: inherited.fontStyle,
            fontWeight = style.weight?.let(::FontWeight) ?: inherited.fontWeight,
            fontFamily = family,
            color = style.colorArgb?.let(::Color) ?: inherited.color,
            textDecoration = decorations.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine) ?: inherited.textDecoration,
            fontFeatureSettings = style.fontFeatures ?: inherited.fontFeatureSettings,
            localeList = style.language?.let(::LocaleList) ?: inherited.localeList,
        )
    }

    private fun ruleWidth(em: Float): Float = max(em * 0.045f, density.density * 0.6f)
    private fun boundedSize(sizeSp: Float): Float =
        sizeSp.coerceIn(baseStyle.fontSize.value * 0.05f, baseStyle.fontSize.value * 8f)
}

/** Drawing consumes the exact glyph positions used to produce the placeholder. */
internal fun DrawScope.drawInlineMath(
    layout: NativeMathLayout,
    topLeft: Offset = Offset.Zero,
    color: Color = Color.Unspecified,
) {
    withTransform({ translate(topLeft.x, topLeft.y); scale(layout.scale, layout.scale, Offset.Zero) }) {
        val ink = color.takeUnless { it == Color.Unspecified } ?: Color.Black
        for (glyph in layout.glyphs) glyph.layout?.let { measured ->
            withTransform({ translate(glyph.topLeft.x, glyph.topLeft.y); scale(1f, glyph.scaleY, Offset.Zero) }) {
                drawText(measured, color = ink, topLeft = Offset.Zero)
            }
        }
        for (rule in layout.rules) drawLine(ink, rule.start, rule.end, strokeWidth = rule.width)
    }
}

internal fun mathInlinePlacements(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    maxWidthPx: Int? = null,
): List<MathInlinePlacement> {
    val marks = completeMathInlineMarks(text)
    if (marks.isEmpty()) return emptyList()
    return marks.mapNotNull { mark ->
        val inherited = inheritedMathStyle(text, mark.start, mark.end, style, mark.math.wholeStyles)
        val key = MathLayoutKey(mark.payload, inherited, density.density, density.fontScale,
            WeakMeasurerKey(measurer))
        val measuredNative = synchronized(mathLayouts) {
            mathLayouts[key] ?: NativeMathTypesetter(density, inherited) { token, tokenStyle ->
                val typography = readerTypographyPlan(AnnotatedString(token), tokenStyle, density, true)
                val measured = measurer.measure(typography.text, typography.style, softWrap = false, maxLines = 1)
                MathGlyphMetrics(measured.size.width.toFloat(), measured.firstBaseline,
                    measured.size.height - measured.firstBaseline, measured)
            }.layout(mark.math.root)?.also { mathLayouts[key] = it }
        } ?: return@mapNotNull null
        val punctuationEnd = mathInlineTrailingPunctuationEnd(text.text, mark.end)
        val punctuationWidthPx = if (maxWidthPx != null && punctuationEnd > mark.end) {
            val punctuation = text.subSequence(mark.end, punctuationEnd)
            // Keep the surrounding glyph styles, but do not inherit a clipped
            // paragraph's indentation when measuring these closing marks.
            val typography = readerTypographyPlan(punctuation, style.copy(textIndent = TextIndent.None), density, true)
            measurer.measure(typography.text, typography.style, softWrap = false, maxLines = 1).size.width
        } else 0
        val native = measuredNative.fitted(mathInlineAvailableWidthPx(maxWidthPx,
            mark.start, style, density, punctuationWidthPx))
        MathInlinePlacement(mark.start, mark.end, mark.id,
            with(density) { Placeholder(native.widthPx.toSp(), native.heightPx.toSp(), PlaceholderVerticalAlign.TextCenter) },
            native, inherited.color, mark.math.description)
    }
}

/** Closing marks share the formula's line; words and whitespace keep their ordinary layout. */
internal fun mathInlineTrailingPunctuationEnd(text: CharSequence, sourceEnd: Int): Int {
    val start = sourceEnd.coerceIn(0, text.length)
    var end = start
    val limit = minOf(text.length, start + 8)
    while (end < limit && text[end] in ".,;:!?)]}»”’…\"") end++
    return end
}

/** A leading formula shares the first-line indent and leaves room for closing punctuation. */
internal fun mathInlineAvailableWidthPx(
    columnWidthPx: Int?,
    sourceStart: Int,
    style: TextStyle,
    density: Density,
    trailingPunctuationWidthPx: Int = 0,
): Int? {
    if (columnWidthPx == null) return null
    val first = if (sourceStart == 0) style.textIndent?.firstLine ?: TextUnit.Unspecified
        else TextUnit.Unspecified
    val indent = when (first.type) {
        TextUnitType.Sp -> with(density) { first.toPx() }
        TextUnitType.Em -> with(density) {
            (style.fontSize.takeUnless { it == TextUnit.Unspecified } ?: 16.sp).toPx() * first.value
        }
        else -> 0f
    }.takeIf { it.isFinite() && it > 0f } ?: 0f
    return (columnWidthPx - ceil(indent).toInt() - trailingPunctuationWidthPx.coerceAtLeast(0))
        .coerceAtLeast(1)
}

/** Pure style resolution keeps appended reader/theme spans while applying MathML spans once. */
internal fun inheritedMathStyle(
    text: AnnotatedString,
    start: Int,
    end: Int,
    style: TextStyle,
    ownedStyles: List<RubySpan>,
): TextStyle {
    val mathStyles = ownedStyles.toMutableList()
    var inherited = style.copy(fontSize = style.fontSize.takeUnless { it == TextUnit.Unspecified } ?: 16.sp)
    for (span in text.spanStyles) {
        if (span.start > start || span.end < end) continue
        if (span.start == start && span.end == end) {
            val owned = mathStyles.indexOf(RubySpan.from(span.item, 0, 0))
            if (owned >= 0) { mathStyles.removeAt(owned); continue }
        }
        val resolved = if (span.item.fontSize.type == TextUnitType.Em) {
            span.item.copy(fontSize = (inherited.fontSize.value * span.item.fontSize.value).sp)
        } else span.item
        inherited = inherited.merge(resolved)
    }
    return inherited.copy(textAlign = TextAlign.Left, textIndent = TextIndent.None,
        lineHeight = TextUnit.Unspecified, lineHeightStyle = null, baselineShift = BaselineShift.None)
}

internal fun mathInlineContent(
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    maxWidthPx: Int? = null,
): Map<String, InlineTextContent> = mathInlinePlacements(text, style, measurer, density, maxWidthPx)
    .associate { placement -> placement.id to InlineTextContent(placement.placeholder) { placement.Content() } }

private data class MathLayoutKey(val payload: String, val style: TextStyle,
    val density: Float, val fontScale: Float, val measurer: WeakMeasurerKey)

private class WeakMeasurerKey(measurer: TextMeasurer) {
    private val reference = WeakReference(measurer)
    private val identity = System.identityHashCode(measurer)
    override fun hashCode(): Int = identity
    override fun equals(other: Any?): Boolean = other is WeakMeasurerKey &&
        identity == other.identity && reference.get()?.let { it === other.reference.get() } == true
}

private val mathLayouts = object : LinkedHashMap<MathLayoutKey, NativeMathLayout>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MathLayoutKey, NativeMathLayout>?): Boolean = size > 128
}
