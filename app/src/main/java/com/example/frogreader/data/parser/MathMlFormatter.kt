package com.example.frogreader.data.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.LINK_TAG
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * A bounded, linear presentation-MathML renderer.
 *
 * Compose does not ship a two-dimensional MathML layout engine. Silently
 * flattening the DOM is worse, though: `<msup><mi>x</mi><mn>2</mn></msup>`
 * used to become the ambiguous `x2`. This formatter keeps the expression
 * selectable and paginatable while retaining the most important visual and
 * semantic relationships with Unicode, relative size and baseline shifts.
 * It is deliberately a readable fallback, not a claim of pixel-identical
 * browser MathML layout.
 */
internal class MathMlFormatter(
    private val maxDepth: Int,
    private val resolveInternalLink: (String) -> String?,
    private val acceptExternalLink: (String) -> Boolean,
    private val onInternalLink: (String) -> Unit,
    private val resolveAuthorStyle: (Element) -> SpanStyle?,
) {

    init {
        require(maxDepth > 0)
    }

    /** Formats a standalone expression, mainly useful to block callers/tests. */
    fun format(math: Element): AnnotatedString = InlineTextBuilder().also {
        append(math, it)
    }.build()

    /** Appends an expression without losing styles already open in [out]. */
    fun append(
        math: Element,
        out: InlineTextBuilder,
        applyRootAuthorStyle: Boolean = true,
    ) {
        appendElement(math, out, depth = 0, applyAuthorStyle = applyRootAuthorStyle)
    }

    private fun appendElement(
        element: Element,
        out: InlineTextBuilder,
        depth: Int,
        applyAuthorStyle: Boolean = true,
    ) {
        if (depth >= maxDepth) {
            appendDirectText(element, out)
            return
        }

        val href = element.attr("href").ifBlank { element.attr("xlink:href") }.trim()
        val internal = href.takeIf(String::isNotEmpty)?.let(resolveInternalLink)
        val external = href.takeIf { it.isNotEmpty() && internal == null && acceptExternalLink(it) }
        when {
            internal != null -> {
                onInternalLink(internal)
                out.pushAnnotation(LINK_TAG, internal)
            }

            external != null -> out.pushAnnotation(EXTERNAL_LINK_TAG, external)
        }

        val defaultStyle = if (element.mathName() == "mi") defaultMiStyle(element) else null
        val mathStyle = elementMathStyle(element)
        val authorStyle = if (applyAuthorStyle) resolveAuthorStyle(element) else null
        val style = mergeStyles(defaultStyle, mathStyle, authorStyle)
        if (style != null) out.pushStyle(style)
        appendElementContent(element, out, depth)
        if (style != null) out.pop()
        if (internal != null || external != null) out.pop()
    }

    private fun appendElementContent(element: Element, out: InlineTextBuilder, depth: Int) {
        when (element.mathName()) {
            "math" -> appendMathRoot(element, out, depth)
            "semantics" -> appendSemantics(element, out, depth)
            "annotation", "annotation-xml" -> Unit

            "mi" -> appendToken(element, out)
            "mn" -> appendToken(element, out)
            "mo" -> appendOperator(element, out)
            "mtext" -> appendTextContent(element, out, depth)
            "ms" -> appendStringLiteral(element, out)
            "mglyph" -> element.attr("alt").ifBlank { element.attr("title") }
                .takeIf(String::isNotBlank)?.let(out::text)

            "mrow", "mstyle", "mpadded", "mtd", "mtr", "mlabeledtr" ->
                appendChildren(element, out, depth)

            "msup" -> appendScripts(element, out, depth, hasSub = false, hasSup = true)
            "msub" -> appendScripts(element, out, depth, hasSub = true, hasSup = false)
            "msubsup" -> appendScripts(element, out, depth, hasSub = true, hasSup = true)
            "mmultiscripts" -> appendMultiScripts(element, out, depth)
            "mprescripts", "none" -> Unit

            "mfrac" -> appendFraction(element, out, depth)
            "msqrt" -> appendSquareRoot(element, out, depth)
            "mroot" -> appendIndexedRoot(element, out, depth)
            "mfenced" -> appendFenced(element, out, depth)
            "menclose" -> appendEnclosure(element, out, depth)

            "mover" -> appendLimits(element, out, depth, hasUnder = false, hasOver = true)
            "munder" -> appendLimits(element, out, depth, hasUnder = true, hasOver = false)
            "munderover" -> appendLimits(element, out, depth, hasUnder = true, hasOver = true)

            "mtable" -> appendTable(element, out, depth)
            "mspace" -> appendSpace(element, out)
            "merror" -> appendError(element, out, depth)
            "mphantom", "maligngroup", "malignmark" -> Unit
            "maction" -> appendAction(element, out, depth)

            else -> appendChildren(element, out, depth)
        }
    }

    private fun appendMathRoot(element: Element, out: InlineTextBuilder, depth: Int) {
        val children = element.children().filterNot { it.mathName() in ANNOTATION_TAGS }
        if (children.any { hasRenderablePresentation(it, depth + 1) }) {
            children.forEach { appendElement(it, out, depth + 1) }
            return
        }
        accessibilityFallback(element)?.let(out::text)
    }

    /** MathML semantics: presentation first; annotations are fallback only. */
    private fun appendSemantics(element: Element, out: InlineTextBuilder, depth: Int) {
        val directPresentation = element.children().firstOrNull {
            it.mathName() !in ANNOTATION_TAGS && hasRenderablePresentation(it, depth + 1)
        }
        if (directPresentation != null) {
            appendElement(directPresentation, out, depth + 1)
            return
        }

        val embeddedPresentation = element.children()
            .firstOrNull {
                it.mathName() == "annotation-xml" &&
                    it.attr("encoding").lowercase() in PRESENTATION_ENCODINGS
            }
            ?.children()
            ?.firstOrNull { hasRenderablePresentation(it, depth + 1) }
        if (embeddedPresentation != null) {
            appendElement(embeddedPresentation, out, depth + 1)
            return
        }

        accessibilityFallback(element)?.let(out::text)
    }

    private fun appendToken(element: Element, out: InlineTextBuilder) {
        val text = directText(element).ifBlank {
            element.attr("alttext").ifBlank { element.attr("aria-label") }
        }
        if (text.isBlank()) return
        out.text(text)
    }

    private fun defaultMiStyle(element: Element): SpanStyle? {
        val text = directText(element).trim()
        if (text.codePointCount(0, text.length) != 1) return null
        return SpanStyle(fontStyle = FontStyle.Italic)
    }

    private fun appendOperator(element: Element, out: InlineTextBuilder) {
        val token = directText(element).trim()
        if (token.isEmpty()) return
        val isSeparator = element.attr("separator").equals("true", ignoreCase = true) ||
            token in TRAILING_SPACE_OPERATORS
        val isFence = element.attr("fence").equals("true", ignoreCase = true) ||
            token in FENCE_OPERATORS
        when {
            isSeparator -> out.text("$token ")
            isFence -> out.text(token)
            token in PADDED_OPERATORS || token.any { it in PADDED_OPERATOR_CHARS } ->
                out.text(" $token ")
            else -> out.text(token)
        }
    }

    private fun appendTextContent(element: Element, out: InlineTextBuilder, depth: Int) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> out.text(child.wholeText)
                is Element -> appendElement(child, out, depth + 1)
            }
        }
    }

    private fun appendStringLiteral(element: Element, out: InlineTextBuilder) {
        val open = element.attr("lquote").ifBlank { "\"" }
        val close = element.attr("rquote").ifBlank { "\"" }
        out.text(open)
        out.text(directText(element))
        out.text(close)
    }

    private fun appendScripts(
        element: Element,
        out: InlineTextBuilder,
        depth: Int,
        hasSub: Boolean,
        hasSup: Boolean,
    ) {
        val children = element.children()
        children.getOrNull(0)?.let { appendElement(it, out, depth + 1) }
            ?: out.text(MISSING_OPERAND)
        var index = 1
        if (hasSub) {
            children.getOrNull(index)?.let { appendScript(it, out, depth, superscript = false) }
            index++
        }
        if (hasSup) {
            children.getOrNull(index)?.let { appendScript(it, out, depth, superscript = true) }
        }
    }

    /** Both post- and pre-scripts stay distinguishable in a linear layout. */
    private fun appendMultiScripts(element: Element, out: InlineTextBuilder, depth: Int) {
        val children = element.children()
        val base = children.firstOrNull()
        val marker = children.indexOfFirst { it.mathName() == "mprescripts" }
        val post = if (marker < 0) children.drop(1) else children.subList(1, marker)
        val pre = if (marker < 0) emptyList() else children.drop(marker + 1)

        appendScriptPairs(pre, out, depth)
        base?.let { appendElement(it, out, depth + 1) } ?: out.text(MISSING_OPERAND)
        appendScriptPairs(post, out, depth)
    }

    private fun appendScriptPairs(children: List<Element>, out: InlineTextBuilder, depth: Int) {
        var i = 0
        while (i < children.size) {
            children.getOrNull(i)?.takeUnless { it.mathName() == "none" }
                ?.let { appendScript(it, out, depth, superscript = false) }
            children.getOrNull(i + 1)?.takeUnless { it.mathName() == "none" }
                ?.let { appendScript(it, out, depth, superscript = true) }
            i += 2
        }
    }

    private fun appendScript(
        element: Element,
        out: InlineTextBuilder,
        depth: Int,
        superscript: Boolean,
    ) {
        out.pushStyle(if (superscript) SUPERSCRIPT_STYLE else SUBSCRIPT_STYLE)
        appendGrouped(element, out, depth + 1)
        out.pop()
    }

    private fun appendFraction(element: Element, out: InlineTextBuilder, depth: Int) {
        val children = element.children()
        val numerator = children.getOrNull(0)
        val denominator = children.getOrNull(1)

        out.pushStyle(NUMERATOR_STYLE)
        if (numerator != null) appendGrouped(numerator, out, depth + 1) else out.text(MISSING_OPERAND)
        out.pop()
        out.text(FRACTION_SLASH)
        out.pushStyle(DENOMINATOR_STYLE)
        if (denominator != null) appendGrouped(denominator, out, depth + 1) else out.text(MISSING_OPERAND)
        out.pop()
    }

    private fun appendSquareRoot(element: Element, out: InlineTextBuilder, depth: Int) {
        out.text(SQUARE_ROOT)
        appendGroupedChildren(element, out, depth, forceForMany = true)
    }

    private fun appendIndexedRoot(element: Element, out: InlineTextBuilder, depth: Int) {
        val children = element.children()
        children.getOrNull(1)?.let { index ->
            out.pushStyle(ROOT_INDEX_STYLE)
            appendGrouped(index, out, depth + 1)
            out.pop()
        }
        out.text(SQUARE_ROOT)
        children.getOrNull(0)?.let { appendGrouped(it, out, depth + 1) }
            ?: out.text(MISSING_OPERAND)
    }

    private fun appendFenced(element: Element, out: InlineTextBuilder, depth: Int) {
        val open = element.attr("open").ifEmpty { "(" }
        val close = element.attr("close").ifEmpty { ")" }
        val separators = element.attr("separators")
            .ifBlank { "," }
            .filterNot(Char::isWhitespace)
            .ifEmpty { "," }
        out.text(open)
        element.children().forEachIndexed { index, child ->
            if (index > 0) {
                val separator = separators[(index - 1).coerceAtMost(separators.lastIndex)]
                out.text("$separator ")
            }
            appendElement(child, out, depth + 1)
        }
        out.text(close)
    }

    private fun appendEnclosure(element: Element, out: InlineTextBuilder, depth: Int) {
        val notation = element.attr("notation").lowercase().split(Regex("\\s+")).toSet()
        when {
            "radical" in notation -> {
                out.text(SQUARE_ROOT)
                appendGroupedChildren(element, out, depth, forceForMany = true)
            }

            notation.any { it in BOX_NOTATIONS } -> {
                out.text("[")
                appendChildren(element, out, depth)
                out.text("]")
            }

            else -> appendChildren(element, out, depth)
        }
    }

    private fun appendLimits(
        element: Element,
        out: InlineTextBuilder,
        depth: Int,
        hasUnder: Boolean,
        hasOver: Boolean,
    ) {
        val children = element.children()
        children.getOrNull(0)?.let { appendElement(it, out, depth + 1) }
            ?: out.text(MISSING_OPERAND)
        var index = 1
        if (hasUnder) {
            children.getOrNull(index)?.let { appendScript(it, out, depth, superscript = false) }
            index++
        }
        if (hasOver) {
            children.getOrNull(index)?.let { appendScript(it, out, depth, superscript = true) }
        }
    }

    private fun appendTable(element: Element, out: InlineTextBuilder, depth: Int) {
        val rows = element.children().filter { it.mathName() in TABLE_ROW_TAGS }
        out.text("[")
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) out.text("; ")
            val cells = row.children().filter { it.mathName() == "mtd" }
            cells.forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) out.text(", ")
                appendChildren(cell, out, depth + 2)
            }
        }
        out.text("]")
    }

    private fun appendSpace(element: Element, out: InlineTextBuilder) {
        if (element.attr("linebreak").equals("newline", ignoreCase = true)) {
            out.lineBreak()
            return
        }
        val width = element.attr("width").trim().lowercase()
        if (width.startsWith("0") || width.startsWith("-")) return
        out.text(" ")
    }

    private fun appendError(element: Element, out: InlineTextBuilder, depth: Int) {
        out.pushStyle(ERROR_STYLE)
        out.text("⟦")
        if (hasRenderablePresentation(element, depth + 1)) {
            appendChildren(element, out, depth)
        } else {
            out.text(accessibilityFallback(element) ?: "Math error")
        }
        out.text("⟧")
        out.pop()
    }

    private fun appendAction(element: Element, out: InlineTextBuilder, depth: Int) {
        val children = element.children().filterNot { it.mathName() in ANNOTATION_TAGS }
        val selected = element.attr("selection").toIntOrNull()?.minus(1) ?: 0
        children.getOrNull(selected.coerceIn(0, (children.size - 1).coerceAtLeast(0)))
            ?.let { appendElement(it, out, depth + 1) }
    }

    private fun appendGroupedChildren(
        element: Element,
        out: InlineTextBuilder,
        depth: Int,
        forceForMany: Boolean,
    ) {
        val children = element.children().filterNot { it.mathName() in ANNOTATION_TAGS }
        val grouped = forceForMany && (children.size > 1 || children.any(::needsGrouping))
        if (grouped) out.text("(")
        children.forEach { appendElement(it, out, depth + 1) }
        if (grouped) out.text(")")
    }

    private fun appendGrouped(element: Element, out: InlineTextBuilder, depth: Int) {
        val grouped = needsGrouping(element)
        if (grouped) out.text("(")
        appendElement(element, out, depth)
        if (grouped) out.text(")")
    }

    private fun needsGrouping(element: Element): Boolean {
        val name = element.mathName()
        if (name in ALWAYS_GROUP_TAGS) return true
        val meaningfulChildren = element.children().filterNot { it.mathName() in ANNOTATION_TAGS }
        if (meaningfulChildren.size <= 1) return false
        return meaningfulChildren.size > 2 || meaningfulChildren.any {
            it.mathName() == "mo" && directText(it).trim().let { op ->
                op in PADDED_OPERATORS || op.any { char -> char in PADDED_OPERATOR_CHARS }
            }
        }
    }

    private fun appendChildren(element: Element, out: InlineTextBuilder, depth: Int) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> if (!child.isBlank) out.text(child.wholeText)
                is Element -> appendElement(child, out, depth + 1)
            }
        }
    }

    private fun appendDirectText(element: Element, out: InlineTextBuilder) {
        element.childNodes().filterIsInstance<TextNode>().forEach { child ->
            if (!child.isBlank) out.text(child.wholeText)
        }
    }

    private fun directText(element: Element): String = element.childNodes()
        .filterIsInstance<TextNode>()
        .joinToString(separator = "") { it.wholeText }

    private fun hasRenderablePresentation(element: Element, depth: Int): Boolean {
        if (depth >= maxDepth) return directText(element).isNotBlank()
        return when (element.mathName()) {
            "annotation", "annotation-xml", "mphantom", "maligngroup", "malignmark",
            "mprescripts", "none",
            -> false

            "mi", "mn", "mo", "mtext", "ms" -> directText(element).isNotBlank()
            "mglyph" -> element.attr("alt").isNotBlank() || element.attr("title").isNotBlank()
            "mspace" -> false
            "semantics" -> element.children().any {
                it.mathName() !in ANNOTATION_TAGS && hasRenderablePresentation(it, depth + 1)
            } || accessibilityFallback(element) != null

            else -> directText(element).isNotBlank() || element.children().any {
                hasRenderablePresentation(it, depth + 1)
            } || accessibilityLabel(element) != null
        }
    }

    private fun accessibilityFallback(element: Element): String? {
        accessibilityLabel(element)?.let { return it }

        val annotations = element.select("annotation, annotation-xml")
        val preferred = annotations.firstOrNull {
            it.attr("encoding").trim().lowercase() in ACCESSIBLE_ENCODINGS && it.text().isNotBlank()
        } ?: annotations.firstOrNull { it.text().isNotBlank() }
        return preferred?.text()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun accessibilityLabel(element: Element): String? =
        element.attr("aria-label").ifBlank { element.attr("alttext") }
            .trim().takeIf(String::isNotEmpty)

    private fun elementMathStyle(element: Element): SpanStyle? {
        var style = SpanStyle()
        var any = false
        when (element.attr("mathvariant").trim().lowercase().replace('_', '-')) {
            "normal" -> {
                style = style.copy(fontStyle = FontStyle.Normal, fontWeight = FontWeight.Normal)
                any = true
            }

            "bold" -> {
                style = style.copy(fontWeight = FontWeight.Bold)
                any = true
            }

            "italic" -> {
                style = style.copy(fontStyle = FontStyle.Italic)
                any = true
            }

            "bold-italic" -> {
                style = style.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                any = true
            }

            "monospace" -> {
                style = style.copy(fontFamily = FontFamily.Monospace)
                any = true
            }

            "sans-serif" -> {
                style = style.copy(fontFamily = FontFamily.SansSerif)
                any = true
            }

            "bold-sans-serif", "sans-serif-bold" -> {
                style = style.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
                any = true
            }

            "sans-serif-italic" -> {
                style = style.copy(fontFamily = FontFamily.SansSerif, fontStyle = FontStyle.Italic)
                any = true
            }

            "sans-serif-bold-italic" -> {
                style = style.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                )
                any = true
            }
        }

        parseRelativeSize(element.attr("mathsize"))?.let { scale ->
            style = style.copy(fontSize = TextUnit(scale, TextUnitType.Em))
            any = true
        }
        parseColor(element.attr("mathcolor").ifBlank { element.attr("color") })?.let { color ->
            style = style.copy(color = color)
            any = true
        }
        return style.takeIf { any }
    }

    /** Later layers have higher precedence: MathML attrs, then author CSS. */
    private fun mergeStyles(vararg styles: SpanStyle?): SpanStyle? {
        var merged: SpanStyle? = null
        for (style in styles) {
            if (style != null) merged = merged?.merge(style) ?: style
        }
        return merged
    }

    private fun parseRelativeSize(raw: String): Float? {
        val value = raw.trim().lowercase()
        if (value.isEmpty() || value == "normal") return null
        val scale = when {
            value == "small" -> 0.8f
            value == "big" -> 1.25f
            value.endsWith("%") -> value.dropLast(1).toFloatOrNull()?.div(100f)
            value.endsWith("em") -> value.dropLast(2).toFloatOrNull()
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()?.div(16f)
            value.endsWith("pt") -> value.dropLast(2).toFloatOrNull()?.div(12f)
            else -> null
        }
        return scale?.takeIf(Float::isFinite)?.coerceIn(0.5f, 2.5f)
    }

    private fun parseColor(raw: String): Color? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        NAMED_COLORS[value]?.let { return it }
        if (!value.startsWith('#')) return null
        return runCatching {
            when (value.length) {
                4 -> {
                    val r = "${value[1]}${value[1]}".toInt(16)
                    val g = "${value[2]}${value[2]}".toInt(16)
                    val b = "${value[3]}${value[3]}".toInt(16)
                    Color(r, g, b)
                }

                7 -> Color(value.drop(1).toLong(16).toInt() or 0xFF000000.toInt())
                else -> null
            }
        }.getOrNull()
    }

    private fun Element.mathName(): String = normalName().substringAfterLast(':').lowercase()

    private companion object {
        const val MISSING_OPERAND = "?"
        const val FRACTION_SLASH = "⁄"
        const val SQUARE_ROOT = "√"

        val SUPERSCRIPT_STYLE = SpanStyle(
            fontSize = TextUnit(0.72f, TextUnitType.Em),
            baselineShift = BaselineShift.Superscript,
        )
        val SUBSCRIPT_STYLE = SpanStyle(
            fontSize = TextUnit(0.72f, TextUnitType.Em),
            baselineShift = BaselineShift.Subscript,
        )
        val ROOT_INDEX_STYLE = SpanStyle(
            fontSize = TextUnit(0.62f, TextUnitType.Em),
            baselineShift = BaselineShift(0.55f),
        )
        val NUMERATOR_STYLE = SpanStyle(
            fontSize = TextUnit(0.88f, TextUnitType.Em),
            baselineShift = BaselineShift(0.16f),
        )
        val DENOMINATOR_STYLE = SpanStyle(
            fontSize = TextUnit(0.88f, TextUnitType.Em),
            baselineShift = BaselineShift(-0.12f),
        )
        val ERROR_STYLE = SpanStyle(color = Color(0xFFB3261E))

        val ANNOTATION_TAGS = setOf("annotation", "annotation-xml")
        val TABLE_ROW_TAGS = setOf("mtr", "mlabeledtr")
        val ALWAYS_GROUP_TAGS = setOf("mfrac", "mtable")
        val BOX_NOTATIONS = setOf("box", "roundedbox", "circle", "longdiv", "actuarial")
        val PRESENTATION_ENCODINGS = setOf(
            "application/mathml-presentation+xml",
            "mathml-presentation",
        )
        val ACCESSIBLE_ENCODINGS = setOf(
            "text/plain",
            "application/x-tex",
            "application/x-latex",
            "application/tex",
        )

        val TRAILING_SPACE_OPERATORS = setOf(",", ";")
        val FENCE_OPERATORS = setOf(
            "(", ")", "[", "]", "{", "}", "⟨", "⟩", "⌈", "⌉", "⌊", "⌋", "|", "‖",
        )
        val PADDED_OPERATORS = setOf(
            "=", "≠", "<", ">", "≤", "≥", "≈", "≃", "≅", "≡", "∼", "∝",
            "+", "-", "−", "±", "∓", "×", "÷", "·", "∗", "/",
            "∈", "∉", "∋", "⊂", "⊃", "⊆", "⊇", "∩", "∪",
            "∧", "∨", "⊕", "⊗", "→", "←", "↔", "⇒", "⇐", "⇔", ":=",
        )
        val PADDED_OPERATOR_CHARS = setOf('=', '<', '>', '+', '−', '±', '×', '÷')

        val NAMED_COLORS = mapOf(
            "black" to Color.Black,
            "white" to Color.White,
            "red" to Color.Red,
            "blue" to Color.Blue,
            "green" to Color.Green,
            "gray" to Color.Gray,
            "grey" to Color.Gray,
            "yellow" to Color.Yellow,
            "cyan" to Color.Cyan,
            "magenta" to Color.Magenta,
        )
    }
}
