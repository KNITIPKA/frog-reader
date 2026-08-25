package com.example.frogreader.data.parser

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import kotlin.math.abs

/**
 * Deliberately small CSS compatibility layer for FictionBook 2.x.
 *
 * FB2's schema permits arbitrary root `<stylesheet>` text, `style` attributes
 * on pType/table/cell elements and named inline `<style name="…">` markup,
 * but it does not define a browser DOM or require a CSS implementation.  This
 * adapter therefore accepts only selectors that map unambiguously to FB2 XML
 * and only declarations representable by FrogReader's native text model.
 * Unknown/malformed CSS is ignored independently, preserving the tolerant
 * streaming parser's existing behaviour.
 */
internal class Fb2Stylesheet private constructor(
    private val rules: List<Rule>,
) {
    /** Candidate indexes keep every XML element/name lookup proportional to
     * the relevant rightmost selector, not to the whole author stylesheet. */
    private val bodyRules = rules.filter { it.selector.matchesBody() }
    private val elementRules = ELEMENT_CLASS_ALIASES.associateWith { element ->
        rules.filter { it.selector.matchesElement(element) }
    }
    private val universalNamedRules = rules.filter { rule ->
        rule.selector === Selector.Universal ||
            rule.selector is Selector.Type && rule.selector.name == "style"
    }
    private val namedRules = buildMap<String, MutableList<Rule>> {
        rules.forEach { rule ->
            val name = when (val selector = rule.selector) {
                is Selector.Class -> selector.name
                is Selector.Named -> selector.name
                else -> null
            }
            if (name != null) getOrPut(name) { mutableListOf() }.add(rule)
        }
    }

    /**
     * The FB2 `body` is not passed to [computed] as an XML node, so resolve
     * its inheritable declarations once and use that computed style as the
     * root of every visible element. Keeping this separate from the child's
     * cascade is important: `body { font-size:150% } p { font-size:120% }`
     * computes to 180%, rather than letting the raw `120%` replace `150%`.
     */
    private val bodyStyle: Computed by lazy {
        val winners = mutableMapOf<String, Winner>()
        bodyRules.forEach { rule ->
            // Foreground inherits normally. A body background is not a CSS
            // inherited value, but it is visibly behind every flattened FB2
            // block, so Computed keeps a separate visual background chain.
            applyRule(winners, rule)
        }
        Computed.from(winners.mapValues { it.value.value })
    }

    /** CSS that applies to one FB2 XML element, plus its literal @style. */
    fun computed(
        element: String,
        inlineStyle: String? = null,
        inherited: Computed? = null,
    ): Computed {
        val winners = mutableMapOf<String, Winner>()
        elementRules[element].orEmpty().forEach { rule ->
            applyRule(winners, rule)
        }
        declarations(inlineStyle.orEmpty(), MAX_DECLARATIONS_PER_BLOCK).values
            .forEach { declaration ->
            putWinner(
                winners,
                declaration,
                specificity = INLINE_SPECIFICITY,
                cascadeOrder = rules.size,
            )
        }
        return Computed.from(
            winners.mapValues { it.value.value },
            inherited ?: bodyStyle,
        )
    }

    /** CSS for `<style name="…">`; `.name` is its conventional selector. */
    fun computedNamed(name: String?): Computed {
        val normalized = name?.trim().orEmpty()
        if (normalized.isEmpty()) return Computed.EMPTY
        val winners = mutableMapOf<String, Winner>()
        universalNamedRules.forEach { rule -> applyRule(winners, rule) }
        namedRules[normalized].orEmpty().forEach { rule ->
            applyRule(winners, rule)
        }
        return Computed.from(winners.mapValues { it.value.value })
    }

    private fun applyRule(
        winners: MutableMap<String, Winner>,
        rule: Rule,
        inheritedOnly: Boolean = false,
    ) {
        rule.declarations.forEach { declaration ->
            if (!inheritedOnly || declaration.property in INHERITED_PROPERTIES) {
                putWinner(
                    winners,
                    declaration,
                    rule.selector.specificity,
                    cascadeOrder = rule.order,
                )
            }
        }
    }

    private fun putWinner(
        winners: MutableMap<String, Winner>,
        declaration: Declaration,
        specificity: Int,
        cascadeOrder: Int,
    ) {
        if (declaration.property !in SUPPORTED_PROPERTIES) return
        val old = winners[declaration.property]
        val replaces = old == null ||
            declaration.important && !old.important ||
            declaration.important == old.important && (
                specificity > old.specificity ||
                    specificity == old.specificity && (
                        cascadeOrder > old.cascadeOrder ||
                            cascadeOrder == old.cascadeOrder &&
                            declaration.sourceOrder >= old.declarationOrder
                        )
                )
        if (replaces) {
            winners[declaration.property] = Winner(
                declaration.value,
                declaration.important,
                specificity,
                cascadeOrder,
                declaration.sourceOrder,
            )
        }
    }

    internal data class Computed(
        val italic: Boolean? = null,
        val bold: Boolean? = null,
        val fontScale: Float? = null,
        val fontFamily: String? = null,
        val align: BlockAlign? = null,
        val firstLineIndentEm: Float? = null,
        val indentStartEm: Float? = null,
        val indentStartFrac: Float? = null,
        val indentEndEm: Float? = null,
        val indentEndFrac: Float? = null,
        val indentLeftEm: Float? = null,
        val indentLeftFrac: Float? = null,
        val indentRightEm: Float? = null,
        val indentRightFrac: Float? = null,
        val spaceBeforeEm: Float? = null,
        val spaceAfterEm: Float? = null,
        val lineHeightMult: Float? = null,
        val decoration: TextDecoration? = null,
        val pageBreakBefore: Boolean? = null,
        /** Resolved inherited foreground and this element's own background. */
        val foregroundColorArgb: Int? = null,
        val backgroundColorArgb: Int? = null,
        /** Ancestor/self layers flattened for native block painting. */
        internal val visualBackgroundColorArgb: Int? = null,
        /** Absolute used line-height for inherited percentage/length forms. */
        internal val absoluteLineHeightEm: Float? = null,
        /** Unitless line-height inherits as the number, not an absolute size. */
        internal val unitlessLineHeight: Float? = null,
    ) {
        fun applyTo(base: BlockStyle? = null): BlockStyle? {
            val original = base ?: BlockStyle.DEFAULT
            val result = original.copy(
                align = align ?: original.align,
                italic = italic ?: original.italic,
                bold = bold ?: original.bold,
                fontScale = fontScale ?: original.fontScale,
                indentStartFrac = when {
                    indentStartFrac != null -> indentStartFrac
                    indentStartEm != null -> 0f
                    else -> original.indentStartFrac
                },
                indentStartEm = when {
                    indentStartEm != null -> indentStartEm
                    indentStartFrac != null -> 0f
                    else -> original.indentStartEm
                },
                indentEndEm = when {
                    indentEndEm != null -> indentEndEm
                    indentEndFrac != null -> 0f
                    else -> original.indentEndEm
                },
                indentEndFrac = when {
                    indentEndFrac != null -> indentEndFrac
                    indentEndEm != null -> 0f
                    else -> original.indentEndFrac
                },
                indentLeftEm = when {
                    indentLeftEm != null -> indentLeftEm
                    indentLeftFrac != null -> 0f
                    else -> original.indentLeftEm
                },
                indentLeftFrac = when {
                    indentLeftFrac != null -> indentLeftFrac
                    indentLeftEm != null -> 0f
                    else -> original.indentLeftFrac
                },
                indentRightEm = when {
                    indentRightEm != null -> indentRightEm
                    indentRightFrac != null -> 0f
                    else -> original.indentRightEm
                },
                indentRightFrac = when {
                    indentRightFrac != null -> indentRightFrac
                    indentRightEm != null -> 0f
                    else -> original.indentRightFrac
                },
                firstLineIndent = when {
                    firstLineIndentEm == null -> original.firstLineIndent
                    abs(firstLineIndentEm) < 0.001f -> false
                    firstLineIndentEm > 0f -> true
                    else -> original.firstLineIndent
                },
                firstLineIndentEm = firstLineIndentEm
                    ?.takeIf { it > 0f }
                    ?: original.firstLineIndentEm,
                spaceBeforeEm = spaceBeforeEm ?: original.spaceBeforeEm,
                spaceAfterEm = spaceAfterEm ?: original.spaceAfterEm,
                fontFamily = fontFamily ?: original.fontFamily,
                lineHeightMult = lineHeightMult ?: original.lineHeightMult,
                pageBreakBefore = pageBreakBefore ?: original.pageBreakBefore,
                foregroundColorArgb = foregroundColorArgb
                    ?: original.foregroundColorArgb,
                backgroundColorArgb = visualBackgroundColorArgb
                    ?: original.backgroundColorArgb,
            )
            return result.takeUnless { base == null && it.isDefault }
        }

        fun spanStyle(relativeTo: Computed? = null): SpanStyle? {
            val generic = when (fontFamily) {
                "serif" -> FontFamily.Serif
                "sans-serif" -> FontFamily.SansSerif
                "monospace" -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else -> null
            }
            val relativeGeneric = when (relativeTo?.fontFamily) {
                "serif" -> FontFamily.Serif
                "sans-serif" -> FontFamily.SansSerif
                "monospace" -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else -> null
            }
            val relativeScale = relativeTo?.fontScale ?: 1f
            val sizeRatio = fontScale?.div(relativeScale)
                ?.takeIf { it.isFinite() && it !in 0.999f..1.001f }
            val ownItalic = italic?.takeIf { it != relativeTo?.italic }
            val ownBold = bold?.takeIf { it != relativeTo?.bold }
            val ownFamily = generic?.takeIf { it != relativeGeneric }
            val ownDecoration = decoration?.takeIf { it != relativeTo?.decoration }
            val ownForeground = foregroundColorArgb
                ?.takeIf { it != relativeTo?.foregroundColorArgb }
            val ownBackground = backgroundColorArgb?.takeIf { (it ushr 24) != 0 }
            if (
                ownItalic == null && ownBold == null && sizeRatio == null && ownFamily == null &&
                ownDecoration == null && ownForeground == null && ownBackground == null
            ) {
                return null
            }
            var style = SpanStyle()
            ownItalic?.let {
                style = style.copy(fontStyle = if (it) FontStyle.Italic else FontStyle.Normal)
            }
            ownBold?.let {
                style = style.copy(fontWeight = if (it) FontWeight.Bold else FontWeight.Normal)
            }
            sizeRatio?.let { style = style.copy(fontSize = TextUnit(it, TextUnitType.Em)) }
            ownFamily?.let { style = style.copy(fontFamily = it) }
            ownDecoration?.let { style = style.copy(textDecoration = it) }
            ownForeground?.let { style = style.copy(color = Color(it)) }
            ownBackground?.let { style = style.copy(background = Color(it)) }
            return style
        }

        /** Cell-level publisher colors relative to the surrounding table. */
        fun colorBlockStyle(
            relativeTo: Computed? = null,
            inheritedBackgroundArgb: Int? = null,
        ): BlockStyle? {
            val style = BlockStyle(
                foregroundColorArgb = foregroundColorArgb
                    ?.takeIf { it != relativeTo?.foregroundColorArgb },
                backgroundColorArgb = backgroundColorArgb ?: inheritedBackgroundArgb,
            )
            return style.takeUnless { it.isDefault }
        }

        /**
         * Block typography already lives in BlockStyle.  Repeating it as a
         * full-range SpanStyle would square font scaling and bypass the
         * publisher-formatting toggle; decoration has no block equivalent.
         */
        fun decorationSpanStyle(): SpanStyle? =
            decoration?.let { SpanStyle(textDecoration = it) }

        companion object {
            val EMPTY = Computed()

            fun from(
                values: Map<String, String>,
                inherited: Computed? = null,
            ): Computed {
                val parentScale = inherited?.fontScale ?: 1f
                val fontScale = fontScale(values["font-size"], parentScale)
                    ?: inherited?.fontScale
                val effectiveScale = fontScale ?: parentScale
                val resolvedLineHeight = resolvedLineHeight(
                    values["line-height"],
                    effectiveScale,
                    inherited,
                )
                val margins = marginValues(values)
                val foreground = resolveForegroundColor(
                    values["color"],
                    inherited?.foregroundColorArgb,
                )
                val ownBackground = resolveBackgroundColor(
                    values["background-color"],
                    inherited?.backgroundColorArgb,
                    foreground,
                )
                return Computed(
                    italic = when (values["font-style"]?.trim()?.lowercase()) {
                        "italic", "oblique" -> true
                        "normal" -> false
                        else -> inherited?.italic
                    },
                    bold = fontWeight(values["font-weight"]) ?: inherited?.bold,
                    fontScale = fontScale,
                    fontFamily = family(values["font-family"]) ?: inherited?.fontFamily,
                    align = alignment(values["text-align"]) ?: inherited?.align,
                    firstLineIndentEm = lengthEm(values["text-indent"]),
                    indentStartEm = margins.start?.em,
                    indentStartFrac = margins.start?.fraction,
                    indentEndEm = margins.end?.em,
                    indentEndFrac = margins.end?.fraction,
                    indentLeftEm = margins.left?.em,
                    indentLeftFrac = margins.left?.fraction,
                    indentRightEm = margins.right?.em,
                    indentRightFrac = margins.right?.fraction,
                    spaceBeforeEm = margins.before?.em,
                    spaceAfterEm = margins.after?.em,
                    lineHeightMult = resolvedLineHeight.multiplier,
                    decoration = decoration(values["text-decoration"])
                        ?: inherited?.decoration,
                    pageBreakBefore = pageBreak(
                        values["break-before"] ?: values["page-break-before"],
                    ),
                    foregroundColorArgb = foreground,
                    backgroundColorArgb = ownBackground,
                    visualBackgroundColorArgb = compositeNullable(
                        ownBackground,
                        inherited?.visualBackgroundColorArgb,
                    ),
                    absoluteLineHeightEm = resolvedLineHeight.absoluteEm,
                    unitlessLineHeight = resolvedLineHeight.unitless,
                )
            }
        }
    }

    private data class Rule(
        val selector: Selector,
        val declarations: List<Declaration>,
        val order: Int,
    )

    private sealed interface Selector {
        val specificity: Int
        fun matchesBody(): Boolean = false
        fun matchesElement(element: String): Boolean = false
        fun matchesNamed(name: String): Boolean = false

        data class Type(val name: String) : Selector {
            override val specificity = 1
            override fun matchesBody(): Boolean = name == "body"
            override fun matchesElement(element: String): Boolean = name == element
            override fun matchesNamed(name: String): Boolean = this.name == "style"
        }

        data class Class(val name: String) : Selector {
            override val specificity = 10
            override fun matchesBody(): Boolean = name == "body"
            override fun matchesElement(element: String): Boolean =
                name == element && element in ELEMENT_CLASS_ALIASES

            override fun matchesNamed(name: String): Boolean = this.name == name
        }

        data class Named(val name: String, val withType: Boolean) : Selector {
            override val specificity = if (withType) 11 else 10
            override fun matchesNamed(name: String): Boolean = this.name == name
        }

        data object Universal : Selector {
            override val specificity = 0
            override fun matchesElement(element: String): Boolean =
                element in ELEMENT_CLASS_ALIASES

            override fun matchesNamed(name: String): Boolean = true
        }
    }

    private data class Declaration(
        val property: String,
        val value: String,
        val important: Boolean,
        val sourceOrder: Int,
    )

    private data class Winner(
        val value: String,
        val important: Boolean,
        val specificity: Int,
        val cascadeOrder: Int,
        val declarationOrder: Int,
    )

    companion object {
        // Lazy avoids constructing the indexed empty adapter before the
        // selector alias sets below have finished companion initialization.
        val EMPTY: Fb2Stylesheet by lazy { Fb2Stylesheet(emptyList()) }

        fun parse(sheets: List<String>): Fb2Stylesheet {
            var order = 0
            var blockCount = 0
            var ruleCount = 0
            var declarationCount = 0
            val rules = mutableListOf<Rule>()
            sheetLoop@ for (css in sheets.take(MAX_STYLESHEETS)) {
                val remainingBlocks = MAX_RULE_BLOCKS - blockCount
                if (remainingBlocks <= 0 || ruleCount >= MAX_RULES ||
                    declarationCount >= MAX_STYLESHEET_DECLARATIONS
                ) {
                    break
                }
                val parsedRules = cssRules(css, remainingBlocks)
                blockCount += parsedRules.scannedBlocks
                for ((prelude, body) in parsedRules.values) {
                    val declarationLimit = minOf(
                        MAX_DECLARATIONS_PER_BLOCK,
                        MAX_STYLESHEET_DECLARATIONS - declarationCount,
                    )
                    if (declarationLimit <= 0) break@sheetLoop
                    val parsedDeclarations = declarations(body, declarationLimit)
                    declarationCount += parsedDeclarations.sourceCount
                    if (parsedDeclarations.values.isEmpty()) continue
                    for (rawSelector in splitTopLevel(
                        prelude,
                        ',',
                        MAX_SELECTORS_PER_RULE,
                    )) {
                        if (ruleCount >= MAX_RULES) break@sheetLoop
                        val selector = selector(rawSelector) ?: continue
                        rules.add(Rule(selector, parsedDeclarations.values, order++))
                        ruleCount++
                    }
                }
            }
            return if (rules.isEmpty()) EMPTY else Fb2Stylesheet(rules)
        }

        private const val INLINE_SPECIFICITY = 1_000
        private const val MAX_STYLESHEETS = 64
        private const val MAX_RULE_BLOCKS = 8_192
        private const val MAX_RULES = 8_192
        private const val MAX_STYLESHEET_DECLARATIONS = 65_536
        private const val MAX_DECLARATIONS_PER_BLOCK = 256
        private const val MAX_EXPANDED_DECLARATIONS_PER_BLOCK = 512
        private const val MAX_SELECTORS_PER_RULE = 128
        private const val MAX_SELECTOR_CHARS = 4_096

        private val FONT_STRETCH_KEYWORDS = setOf(
            "ultra-condensed", "extra-condensed", "condensed", "semi-condensed",
            "semi-expanded", "expanded", "extra-expanded", "ultra-expanded",
        )

        private val ELEMENT_CLASS_ALIASES = setOf(
            "p", "subtitle", "v", "text-author", "table", "tr", "td", "th",
        )

        private val SUPPORTED_PROPERTIES = setOf(
            "font-style", "font-weight", "font-size", "font-family",
            "text-align", "text-indent", "line-height", "text-decoration",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "margin-inline-start", "margin-inline-end",
            "page-break-before", "break-before",
            "color", "background-color",
        )

        private val INHERITED_PROPERTIES = setOf(
            "font-style", "font-weight", "font-size", "font-family",
            "text-align", "line-height", "text-decoration",
            "color",
        )

        private fun resolveForegroundColor(value: String?, inherited: Int?): Int? =
            when (value?.trim()?.lowercase()) {
                null, "inherit", "unset", "currentcolor" -> inherited
                "initial", "revert", "revert-layer" -> -0x1000000
                else -> CssColor.parse(value) ?: inherited
            }

        private fun resolveBackgroundColor(
            value: String?,
            inherited: Int?,
            current: Int?,
        ): Int? = when (value?.trim()?.lowercase()) {
            null -> null
            "inherit" -> inherited
            "currentcolor" -> current
            "initial", "unset", "revert", "revert-layer" -> null
            else -> CssColor.parse(value)
        }

        private fun compositeNullable(foreground: Int?, background: Int?): Int? {
            if (foreground == null || (foreground ushr 24) == 0) return background
            if (background == null || (background ushr 24) == 0) return foreground
            val fa = (foreground ushr 24) and 0xFF
            if (fa == 0xFF) return foreground
            val ba = (background ushr 24) and 0xFF
            val outA = fa + (ba * (255 - fa) + 127) / 255
            fun channel(shift: Int): Int {
                val fc = (foreground ushr shift) and 0xFF
                val bc = (background ushr shift) and 0xFF
                val premul = fc * fa + (bc * ba * (255 - fa) + 127) / 255
                return (premul + outA / 2) / outA
            }
            return (outA shl 24) or (channel(16) shl 16) or
                (channel(8) shl 8) or channel(0)
        }

        private fun selector(raw: String): Selector? {
            val value = raw.trim()
            if (value.isEmpty() || value.length > MAX_SELECTOR_CHARS) return null
            if (value == "*") return Selector.Universal
            Regex("""(?i)^style\s*\[\s*name\s*=\s*(['\"])([^'\"]+)\1\s*]$""")
                .matchEntire(value)
                ?.let { return Selector.Named(it.groupValues[2], withType = true) }
            Regex("""(?i)^style\.([_a-zA-Z][_a-zA-Z0-9-]*)$""")
                .matchEntire(value)
                ?.let { return Selector.Named(it.groupValues[1], withType = true) }
            Regex("""^\.([_a-zA-Z][_a-zA-Z0-9-]*)$""")
                .matchEntire(value)
                ?.let { return Selector.Class(it.groupValues[1]) }
            Regex("""^([a-zA-Z][a-zA-Z0-9-]*)$""")
                .matchEntire(value)
                ?.groupValues?.get(1)?.lowercase()
                ?.let {
                    if (it == "body" || it == "style" || it in ELEMENT_CLASS_ALIASES) {
                        return Selector.Type(it)
                    }
                }
            return null
        }

        /**
         * Top-level CSS rules. Semicolon at-rules (notably `@charset` and
         * `@namespace`) and block at-rules are consumed as their own tokens,
         * so neither can swallow the next qualified rule. Comments are
         * stripped without touching comment-like text inside strings.
         */
        private data class ParsedRules(
            val values: List<Pair<String, String>>,
            val scannedBlocks: Int,
        )

        private fun cssRules(rawCss: String, limit: Int): ParsedRules {
            val css = stripComments(rawCss)
            val result = mutableListOf<Pair<String, String>>()
            var position = 0
            var scannedBlocks = 0
            while (position < css.length && scannedBlocks < limit) {
                while (position < css.length && (css[position].isWhitespace() || css[position] == ';')) {
                    position++
                }
                if (position >= css.length) break

                if (css[position] == '@') {
                    val boundary = findTopLevel(css, position + 1, ';', '{') ?: break
                    position = if (css[boundary] == ';') {
                        boundary + 1
                    } else {
                        scannedBlocks++
                        val close = matchingBrace(css, boundary) ?: break
                        close + 1
                    }
                    continue
                }

                val open = findTopLevel(css, position, '{') ?: break
                val prelude = css.substring(position, open).trim()
                val close = matchingBrace(css, open) ?: break
                scannedBlocks++
                if (prelude.isNotEmpty()) {
                    result += prelude to css.substring(open + 1, close)
                }
                position = close + 1
            }
            return ParsedRules(result, scannedBlocks)
        }

        private data class ParsedDeclarations(
            val values: List<Declaration>,
            val sourceCount: Int,
        )

        private fun declarations(raw: String, sourceLimit: Int): ParsedDeclarations {
            val pieces = splitTopLevel(stripComments(raw), ';', sourceLimit)
            val values = mutableListOf<Declaration>()
            pieces.forEachIndexed { sourceOrder, piece ->
                val colon = indexOutside(piece, ':') ?: return@forEachIndexed
                val property = piece.substring(0, colon).trim().lowercase()
                var value = piece.substring(colon + 1).trim()
                if (property.isEmpty() || value.isEmpty()) return@forEachIndexed
                val important = Regex("""(?i)\s*!important\s*$""").containsMatchIn(value)
                if (important) value = value.replace(Regex("""(?i)\s*!important\s*$"""), "").trim()
                val normalized = normalizeDeclaration(property, value, important, sourceOrder)
                if (values.size + normalized.size > MAX_EXPANDED_DECLARATIONS_PER_BLOCK) {
                    return@forEachIndexed
                }
                values.addAll(normalized)
            }
            return ParsedDeclarations(values, pieces.size)
        }

        private fun normalizeDeclaration(
            property: String,
            value: String,
            important: Boolean,
            sourceOrder: Int,
        ): List<Declaration> {
            if (property == "font") {
                val shorthand = fontShorthandValues(value) ?: return emptyList()
                return listOf(
                    Declaration("font-style", shorthand.style, important, sourceOrder),
                    Declaration("font-weight", shorthand.weight, important, sourceOrder),
                    Declaration("font-size", shorthand.size, important, sourceOrder),
                    Declaration("line-height", shorthand.lineHeight, important, sourceOrder),
                    Declaration("font-family", shorthand.family, important, sourceOrder),
                )
            }
            if (property == "margin") {
                val tokens = value.trim().split(Regex("""\s+"""))
                    .takeIf {
                        it.isNotEmpty() && it.size <= 4 &&
                            it.all { token -> length(token) != null }
                    }
                    ?: return emptyList()
                val top = tokens[0]
                val right = tokens.getOrElse(1) { top }
                val bottom = tokens.getOrElse(2) { top }
                val left = tokens.getOrElse(3) { right }
                return listOf(
                    Declaration("margin-top", top, important, sourceOrder),
                    Declaration("margin-right", right, important, sourceOrder),
                    Declaration("margin-bottom", bottom, important, sourceOrder),
                    Declaration("margin-left", left, important, sourceOrder),
                )
            }

            if (property == "margin-inline") {
                val tokens = value.trim().split(Regex("""\s+"""))
                    .takeIf {
                        it.isNotEmpty() && it.size <= 2 &&
                            it.all { token -> length(token) != null }
                    }
                    ?: return emptyList()
                return listOf(
                    Declaration("margin-left", tokens[0], important, sourceOrder),
                    Declaration(
                        "margin-right",
                        tokens.getOrElse(1) { tokens[0] },
                        important,
                        sourceOrder,
                    ),
                )
            }

            // FB2 2.x has no standardized direction or logical-properties
            // model. Its optional compatibility CSS therefore resolves these
            // aliases on the conventional LTR axes; xml:lang still supplies
            // text direction later, but cannot retroactively change cascade.
            val normalizedProperty = when (property) {
                "margin-block-start" -> "margin-top"
                "margin-block-end" -> "margin-bottom"
                "margin-inline-start" -> "margin-left"
                "margin-inline-end" -> "margin-right"
                else -> property
            }
            if (normalizedProperty.startsWith("margin-") && length(value) == null) {
                return emptyList()
            }
            return listOf(Declaration(normalizedProperty, value, important, sourceOrder))
        }

        private data class FontShorthandValues(
            val style: String,
            val weight: String,
            val size: String,
            val lineHeight: String,
            val family: String,
        )

        private fun fontShorthandValues(raw: String): FontShorthandValues? {
            val value = raw.trim()
            when (value.lowercase()) {
                "inherit", "unset" -> return FontShorthandValues(
                    "inherit", "inherit", "inherit", "inherit", "inherit",
                )
                "initial", "revert", "revert-layer" -> return FontShorthandValues(
                    "normal", "normal", "medium", "normal", "serif",
                )
                "caption", "icon", "menu", "message-box", "small-caption", "status-bar" ->
                    return FontShorthandValues(
                        "normal", "normal", "medium", "normal", "sans-serif",
                    )
            }

            val tokens = splitFontTokens(value)
            if (tokens.isEmpty()) return null
            var style = "normal"
            var weight = "normal"
            var size: String? = null
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                val lower = token.lowercase()
                if (fontScale(lower, 1f) != null) {
                    size = token
                    index++
                    break
                }
                when {
                    lower == "italic" || lower == "oblique" -> {
                        style = lower
                        if (lower == "oblique" && tokens.getOrNull(index + 1).isFontAngle()) index++
                    }
                    lower == "normal" || lower == "small-caps" -> Unit
                    lower == "bold" || lower == "bolder" || lower == "lighter" ||
                        lower.toIntOrNull()?.let { it in 1..1_000 } == true -> weight = lower
                    lower in FONT_STRETCH_KEYWORDS -> Unit
                    else -> return null
                }
                index++
            }
            val resolvedSize = size ?: return null
            var lineHeight = "normal"
            if (tokens.getOrNull(index) == "/") {
                lineHeight = tokens.getOrNull(index + 1)?.takeIf { it != "/" } ?: return null
                index += 2
            }
            val family = tokens.drop(index).joinToString(" ").trim()
            if (family.isEmpty() || family == "/") return null
            return FontShorthandValues(style, weight, resolvedSize, lineHeight, family)
        }

        private fun String?.isFontAngle(): Boolean {
            val value = this?.lowercase() ?: return false
            return value.dropLastWhile { it.isLetter() }.toFloatOrNull() != null &&
                (value.endsWith("deg") || value.endsWith("grad") ||
                    value.endsWith("rad") || value.endsWith("turn"))
        }

        private fun splitFontTokens(value: String): List<String> {
            val result = mutableListOf<String>()
            var start = -1
            var quote: Char? = null
            var parentheses = 0
            fun flush(end: Int) {
                if (start >= 0) {
                    result += value.substring(start, end)
                    start = -1
                }
            }
            value.forEachIndexed { index, char ->
                when {
                    quote != null -> {
                        if (char == quote && !isEscaped(value, index)) quote = null
                        if (start < 0) start = index
                    }
                    char == '\'' || char == '"' -> {
                        quote = char
                        if (start < 0) start = index
                    }
                    char == '(' -> {
                        parentheses++
                        if (start < 0) start = index
                    }
                    char == ')' -> {
                        if (parentheses > 0) parentheses--
                        if (start < 0) start = index
                    }
                    parentheses == 0 && char == '/' -> {
                        flush(index)
                        result += "/"
                    }
                    parentheses == 0 && char.isWhitespace() -> flush(index)
                    else -> if (start < 0) start = index
                }
            }
            flush(value.length)
            return result
        }

        private fun splitTopLevel(
            raw: String,
            separator: Char,
            limit: Int = Int.MAX_VALUE,
        ): List<String> {
            if (raw.isEmpty() || limit <= 0) return emptyList()
            val result = mutableListOf<String>()
            var start = 0
            var quote: Char? = null
            var parentheses = 0
            for (index in raw.indices) {
                val char = raw[index]
                when {
                    quote != null && char == quote && !isEscaped(raw, index) -> quote = null
                    quote != null -> Unit
                    char == '\'' || char == '"' -> quote = char
                    char == '(' -> parentheses++
                    char == ')' && parentheses > 0 -> parentheses--
                    char == separator && parentheses == 0 -> {
                        result += raw.substring(start, index)
                        if (result.size >= limit) return result
                        start = index + 1
                    }
                }
            }
            if (result.size < limit) result += raw.substring(start)
            return result
        }

        private fun findTopLevel(raw: String, start: Int, vararg targets: Char): Int? {
            var quote: Char? = null
            var parentheses = 0
            var brackets = 0
            for (index in start until raw.length) {
                val char = raw[index]
                when {
                    quote != null && char == quote && !isEscaped(raw, index) -> quote = null
                    quote != null -> Unit
                    char == '\'' || char == '"' -> quote = char
                    char == '(' -> parentheses++
                    char == ')' && parentheses > 0 -> parentheses--
                    char == '[' -> brackets++
                    char == ']' && brackets > 0 -> brackets--
                    parentheses == 0 && brackets == 0 && char in targets -> return index
                }
            }
            return null
        }

        private fun indexOutside(raw: String, target: Char): Int? =
            findTopLevel(raw, 0, target)

        private fun matchingBrace(raw: String, open: Int): Int? {
            var depth = 1
            var quote: Char? = null
            for (index in open + 1 until raw.length) {
                val char = raw[index]
                when {
                    quote != null && char == quote && !isEscaped(raw, index) -> quote = null
                    quote != null -> Unit
                    char == '\'' || char == '"' -> quote = char
                    char == '{' -> depth++
                    char == '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            return null
        }

        private fun stripComments(raw: String): String {
            val result = StringBuilder(raw.length)
            var index = 0
            var quote: Char? = null
            while (index < raw.length) {
                val char = raw[index]
                when {
                    quote != null -> {
                        result.append(char)
                        if (char == quote && !isEscaped(raw, index)) quote = null
                        index++
                    }
                    char == '\'' || char == '"' -> {
                        quote = char
                        result.append(char)
                        index++
                    }
                    char == '/' && raw.getOrNull(index + 1) == '*' -> {
                        result.append(' ')
                        val close = raw.indexOf("*/", startIndex = index + 2)
                        index = if (close < 0) raw.length else close + 2
                    }
                    else -> {
                        result.append(char)
                        index++
                    }
                }
            }
            return result.toString()
        }

        private fun isEscaped(raw: String, index: Int): Boolean {
            var backslashes = 0
            var cursor = index - 1
            while (cursor >= 0 && raw[cursor] == '\\') {
                backslashes++
                cursor--
            }
            return backslashes % 2 == 1
        }

        private fun fontWeight(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "bold", "bolder", "600", "700", "800", "900" -> true
            "normal", "lighter", "100", "200", "300", "400", "500" -> false
            else -> null
        }

        private fun fontScale(value: String?, parentScale: Float): Float? {
            val raw = value?.trim()?.lowercase() ?: return null
            val scale = when (raw) {
                "xx-small" -> 0.6f
                "x-small" -> 0.75f
                "small" -> 0.875f
                "medium" -> 1f
                "large" -> 1.125f
                "x-large" -> 1.5f
                "xx-large" -> 2f
                "smaller" -> parentScale * 0.875f
                "larger" -> parentScale * 1.125f
                "inherit", "unset" -> parentScale
                else -> when {
                    raw.endsWith("%") -> raw.dropLast(1).toFloatOrNull()
                        ?.div(100f)?.times(parentScale)
                    raw.endsWith("rem") -> raw.dropLast(3).toFloatOrNull()
                    raw.endsWith("em") -> raw.dropLast(2).toFloatOrNull()?.times(parentScale)
                    raw.endsWith("px") -> raw.dropLast(2).toFloatOrNull()?.div(16f)
                    raw.endsWith("pt") -> raw.dropLast(2).toFloatOrNull()?.div(12f)
                    else -> null
                }
            }
            return scale?.takeIf { it.isFinite() }?.coerceIn(0.6f, 2.6f)
        }

        private fun family(value: String?): String? {
            val raw = splitTopLevel(value.orEmpty(), ',').firstOrNull()
                ?.trim()?.trim('\'', '"')?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return null
            return when (raw) {
                "inherit", "unset" -> null
                "initial", "revert", "revert-layer" -> "serif"
                "serif", "times", "times new roman", "georgia" -> "serif"
                "sans-serif", "arial", "helvetica", "verdana", "tahoma" -> "sans-serif"
                "monospace", "courier", "courier new", "consolas" -> "monospace"
                "cursive", "comic sans ms" -> "cursive"
                else -> raw
            }
        }

        private fun alignment(value: String?): BlockAlign? = when (value?.trim()?.lowercase()) {
            "left" -> BlockAlign.LEFT
            "right" -> BlockAlign.RIGHT
            "start" -> BlockAlign.START
            "end" -> BlockAlign.END
            "center" -> BlockAlign.CENTER
            "justify" -> BlockAlign.JUSTIFY
            else -> null
        }

        private data class Length(val em: Float? = null, val fraction: Float? = null)

        private data class Margins(
            val before: Length? = null,
            val end: Length? = null,
            val after: Length? = null,
            val start: Length? = null,
            val left: Length? = null,
            val right: Length? = null,
        )

        private fun marginValues(values: Map<String, String>): Margins {
            val top = length(values["margin-top"])
            val right = length(values["margin-right"])
            val bottom = length(values["margin-bottom"])
            val left = length(values["margin-left"])
            // CSS percentage top/bottom margins are width-relative.  The
            // native model has only em vertical spacing, so guessing would be
            // visibly worse than leaving them to reader defaults.
            return Margins(
                before = top?.takeIf { it.em != null },
                end = null,
                after = bottom?.takeIf { it.em != null },
                start = null,
                left = left,
                right = right,
            )
        }

        private fun length(value: String?): Length? {
            val raw = value?.trim()?.lowercase() ?: return null
            if (raw == "0" || raw == "+0" || raw == "-0") return Length(em = 0f)
            val parsed = when {
                raw.endsWith("%") -> raw.dropLast(1).toFloatOrNull()
                    ?.let { Length(fraction = it.div(100f).coerceIn(0f, 0.45f)) }
                raw.endsWith("rem") -> raw.dropLast(3).toFloatOrNull()?.let { Length(em = it) }
                raw.endsWith("em") -> raw.dropLast(2).toFloatOrNull()?.let { Length(em = it) }
                raw.endsWith("px") -> raw.dropLast(2).toFloatOrNull()?.let { Length(em = it / 16f) }
                raw.endsWith("pt") -> raw.dropLast(2).toFloatOrNull()?.let { Length(em = it / 12f) }
                else -> null
            }
            return parsed?.let {
                it.copy(em = it.em?.takeIf(Float::isFinite)?.coerceIn(0f, 6f))
            }
        }

        private fun lengthEm(value: String?): Float? = length(value)?.em

        private data class ResolvedLineHeight(
            val multiplier: Float?,
            val absoluteEm: Float?,
            val unitless: Float?,
        )

        /**
         * CSS inherits a unitless line-height as a number, but percentages
         * and lengths as an already-computed absolute size. The distinction
         * matters when a child changes its font size.
         */
        private fun resolvedLineHeight(
            value: String?,
            fontScale: Float,
            inherited: Computed?,
        ): ResolvedLineHeight {
            val raw = value?.trim()?.lowercase()
            if (raw == null || raw == "inherit" || raw == "unset") {
                inherited?.unitlessLineHeight?.let {
                    return ResolvedLineHeight(it, null, it)
                }
                inherited?.absoluteLineHeightEm?.let { absolute ->
                    return ResolvedLineHeight(
                        (absolute / fontScale.coerceAtLeast(0.1f)).coerceIn(1f, 2.4f),
                        absolute,
                        null,
                    )
                }
                return ResolvedLineHeight(inherited?.lineHeightMult, null, null)
            }
            if (raw == "normal" || raw == "initial") {
                return ResolvedLineHeight(null, null, null)
            }

            raw.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { number ->
                val safe = number.coerceIn(1f, 2.4f)
                return ResolvedLineHeight(safe, null, safe)
            }

            val absolute = when {
                raw.endsWith("%") -> raw.dropLast(1).toFloatOrNull()
                    ?.div(100f)?.times(fontScale)
                raw.endsWith("rem") -> raw.dropLast(3).toFloatOrNull()
                raw.endsWith("em") -> raw.dropLast(2).toFloatOrNull()?.times(fontScale)
                raw.endsWith("px") -> raw.dropLast(2).toFloatOrNull()?.div(16f)
                raw.endsWith("pt") -> raw.dropLast(2).toFloatOrNull()?.div(12f)
                else -> null
            }?.takeIf { it.isFinite() && it > 0f }

            if (absolute != null) {
                return ResolvedLineHeight(
                    (absolute / fontScale.coerceAtLeast(0.1f)).coerceIn(1f, 2.4f),
                    absolute,
                    null,
                )
            }

            // Invalid declarations are ignored at computed-value time, so
            // the inherited value remains in force.
            return resolvedLineHeight(null, fontScale, inherited)
        }

        private fun decoration(value: String?): TextDecoration? {
            val tokens = value?.trim()?.lowercase()?.split(Regex("""\s+""")) ?: return null
            if ("none" in tokens) return TextDecoration.None
            val values = buildList {
                if ("underline" in tokens) add(TextDecoration.Underline)
                if ("line-through" in tokens) add(TextDecoration.LineThrough)
            }
            return when (values.size) {
                0 -> null
                1 -> values[0]
                else -> TextDecoration.combine(values)
            }
        }

        private fun pageBreak(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "always", "left", "right", "page" -> true
            "auto", "avoid" -> false
            else -> null
        }
    }
}
