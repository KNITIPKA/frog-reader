package com.example.frogreader.data.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.InlineMathCodec
import com.example.frogreader.data.model.InlineMathNode
import com.example.frogreader.data.model.InlineMathStyle
import com.example.frogreader.data.model.RubySpan
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Admit only trees the shared native typesetter can represent without flattening operators. */
internal class MathMlNativeParser(
    private val resolveAuthorStyle: (Element) -> SpanStyle?,
) {
    fun parse(math: Element, applyRootAuthorStyle: Boolean): InlineMathNode? {
        var count = 0
        var chars = 0
        fun visit(element: Element, depth: Int): InlineMathNode? {
            if (++count > InlineMathCodec.MAX_NODES || depth >= InlineMathCodec.MAX_DEPTH ||
                element.hasAttr("href") || element.hasAttr("xlink:href")) return null
            val name = element.normalName().substringAfterLast(':').lowercase()
            val children = element.children().filterNot {
                it.normalName().substringAfterLast(':') in setOf("annotation", "annotation-xml")
            }
            val direct = element.childNodes().filterIsInstance<TextNode>().joinToString("") { it.wholeText }.trim()
            val style = nativeStyle(element, if (depth > 0 || applyRootAuthorStyle) resolveAuthorStyle(element) else null)
                ?: return null
            // Per-token colors need the reader's background-aware correction.
            // Keep the rich fallback until that mapping can be represented exactly.
            if (style.colorArgb != null) return null
            if (name in setOf("mi", "mn", "mo", "mtext")) {
                if (children.isNotEmpty() || direct.isEmpty() || '\n' in direct) return null
                chars += direct.length
                if (chars > InlineMathCodec.MAX_TEXT) return null
                val styled = if (name == "mi" && direct.codePointCount(0, direct.length) == 1 &&
                    style.italic == null) style.copy(italic = true) else style
                val fence = element.attr("fence") == "true" || direct in FENCES
                val padded = name == "mo" && !fence && direct in OPERATORS
                return InlineMathNode(
                    "token", direct, style = styled,
                    leftSpaceEm = if (padded) 0.18f else 0f,
                    rightSpaceEm = when { padded -> 0.18f; name == "mo" && direct in setOf(",", ";") -> 0.15f; else -> 0f },
                    stretchy = name == "mo" && fence && !element.attr("stretchy").equals("false", true),
                )
            }
            if (direct.isNotEmpty()) return null
            val kind = when (name) {
                "math", "mrow", "mstyle" -> "row"
                "semantics" -> return children.firstOrNull()?.let { visit(it, depth + 1) }
                "mfrac" -> "frac"
                "msqrt" -> "sqrt"
                "mroot" -> "root"
                "msup" -> "sup"
                "msub" -> "sub"
                "msubsup" -> "subsup"
                else -> return null
            }
            if (children.isEmpty() || kind in setOf("frac", "root", "sup", "sub") && children.size != 2 ||
                kind == "subsup" && children.size != 3) return null
            if (name == "mfrac" && element.attr("bevelled").equals("true", true)) return null
            if (name == "mfrac" && element.hasAttr("linethickness") &&
                element.attr("linethickness") !in setOf("medium", "1", "1px")) return null
            val parsed = ArrayList<InlineMathNode>(children.size)
            for (child in children) parsed.add(visit(child, depth + 1) ?: return null)
            return InlineMathNode(kind, children = parsed, style = style)
        }
        return visit(math, 0)
    }

    private fun nativeStyle(element: Element, css: SpanStyle?): InlineMathStyle? {
        var italic: Boolean? = null
        var weight: Int? = null
        var family: String? = null
        when (element.attr("mathvariant").lowercase()) {
            "", "normal" -> if (element.attr("mathvariant") == "normal") { italic = false; weight = 400 }
            "italic" -> italic = true
            "bold" -> weight = 700
            "bold-italic" -> { weight = 700; italic = true }
            "monospace" -> family = "monospace"
            "sans-serif" -> family = "sans-serif"
            else -> return null
        }
        var size = relativeSize(element.attr("mathsize")) ?: if (element.hasAttr("mathsize")) return null else 1f
        var color = parseColor(element.attr("mathcolor").ifBlank { element.attr("color") })
        if ((element.hasAttr("mathcolor") || element.hasAttr("color")) && color == null) return null
        if (css != null) {
            css.fontStyle?.let { italic = it == FontStyle.Italic }
            css.fontWeight?.let { weight = it.weight }
            if (css.fontFamily != null) family = when (css.fontFamily) {
                FontFamily.Serif -> "serif"
                FontFamily.SansSerif -> "sans-serif"
                FontFamily.Monospace -> "monospace"
                FontFamily.Cursive -> "cursive"
                else -> return null
            }
            if (css.fontSize.type == TextUnitType.Em) size = css.fontSize.value
            else if (css.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) return null
            if (css.color != Color.Unspecified) color = css.color.toArgb()
            if (css.baselineShift != null || css.letterSpacing != androidx.compose.ui.unit.TextUnit.Unspecified ||
                css.background != Color.Unspecified) return null
        }
        return InlineMathStyle(
            italic, weight, family, size.coerceIn(0.25f, 4f), color,
            underline = css?.textDecoration?.contains(TextDecoration.Underline) == true,
            strike = css?.textDecoration?.contains(TextDecoration.LineThrough) == true,
            fontFeatures = css?.fontFeatureSettings,
            language = css?.let { RubySpan.from(it, 0, 0).language },
        )
    }

    private fun relativeSize(raw: String): Float? {
        val value = raw.trim().lowercase()
        return when {
            value == "normal" -> 1f
            value == "small" -> 0.8f
            value == "big" -> 1.25f
            value.endsWith("em") -> value.dropLast(2).toFloatOrNull()
            value.endsWith('%') -> value.dropLast(1).toFloatOrNull()?.div(100f)
            else -> null
        }?.takeIf { it.isFinite() && it in 0.25f..4f }
    }

    private fun parseColor(raw: String): Int? {
        val value = raw.trim().lowercase()
        if (!value.startsWith('#')) return when (value) {
            "black" -> 0xFF000000.toInt()
            "white" -> 0xFFFFFFFF.toInt()
            "red" -> 0xFFFF0000.toInt()
            "blue" -> 0xFF0000FF.toInt()
            else -> null
        }
        return runCatching {
            when (value.length) {
                4 -> value.drop(1).flatMap { listOf(it, it) }.joinToString("").toInt(16) or 0xFF000000.toInt()
                7 -> value.drop(1).toLong(16).toInt() or 0xFF000000.toInt()
                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        val FENCES = setOf("(", ")", "[", "]", "{", "}", "|", "‖")
        val OPERATORS = setOf("=", "+", "−", "-", "×", "÷", "<", ">", "≤", "≥", "≠", "≈", "∝", "±")
    }
}
