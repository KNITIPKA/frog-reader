package com.example.frogreader.data.parser

import com.example.frogreader.data.model.FirstLetterStyle
import org.jsoup.nodes.Element

/**
 * CSS engine for EPUB chapters.
 *
 * Real-world EPUBs (LitRes/Calibre FB2 conversions above all) express the
 * entire visual structure — chapter titles, epigraphs, poems, authors'
 * signatures — as `<div class="…">`/`<p class="…">` plus a stylesheet, not as
 * semantic HTML tags. Without reading that CSS a book renders as an
 * undifferentiated wall of plain paragraphs.
 *
 * Supported: tag/.class/#id/attribute selectors with descendant, child (`>`)
 * and sibling (`+`, `~`) combinators; the structural pseudo-classes
 * `:first-child`/`:last-child`/`:only-child`/`:first-of-type`; the
 * `::first-letter` pseudo-element (drop caps); standard (a,b,c) specificity
 * with `!important` and source-order cascade; `style=""` attributes;
 * inheritance of font and text properties; and the properties a paginated
 * book reader can honor (font-style/weight/size, text-align, text-indent,
 * margins, display, vertical-align, text-decoration, list-style-type,
 * page-break-before, float/width of images).
 *
 * Deliberately ignored: colors and backgrounds (themes own those),
 * positioning, `::before`/`::after` generated content. A selector using an
 * unsupported pseudo-class drops only itself, not its comma-group siblings —
 * stricter than the spec, kinder to books.
 */
class CssResolver(sheets: List<Sheet>) {

    /** One stylesheet text plus the directory its relative urls resolve from. */
    class Sheet(val text: String, val baseDir: String = "")

    /** One `@font-face` rule: a face of [family] at [src] (url as written). */
    class FontFace(
        val family: String,
        val src: String,
        val baseDir: String,
        val bold: Boolean,
        val italic: Boolean,
    )

    // ------------------------------------------------------------- model

    private class Declaration(val value: String, val important: Boolean)

    /** One parsed declaration block for one selector. */
    private class Rule(
        /** Compound chain; the last entry must match the element itself. */
        val chain: List<Compound>,
        val specificity: Int,
        val order: Int,
        val declarations: Map<String, Declaration>,
    )

    private enum class Combinator { DESCENDANT, CHILD, NEXT_SIBLING, SUBSEQUENT_SIBLING }

    private enum class AttrOp { EXISTS, EQUALS, INCLUDES, DASH, PREFIX, SUFFIX, SUBSTRING }

    private class AttrSelector(val name: String, val op: AttrOp, val value: String?) {
        fun matches(element: Element): Boolean {
            if (!element.hasAttr(name)) return false
            val actual = element.attr(name)
            val expected = value ?: return true
            return when (op) {
                AttrOp.EXISTS -> true
                AttrOp.EQUALS -> actual == expected
                AttrOp.INCLUDES ->
                    expected.isNotEmpty() && actual.split(whitespaceRegex).contains(expected)
                AttrOp.DASH -> actual == expected || actual.startsWith("$expected-")
                AttrOp.PREFIX -> expected.isNotEmpty() && actual.startsWith(expected)
                AttrOp.SUFFIX -> expected.isNotEmpty() && actual.endsWith(expected)
                AttrOp.SUBSTRING -> expected.isNotEmpty() && actual.contains(expected)
            }
        }
    }

    private sealed interface PseudoClass {
        fun matches(element: Element): Boolean

        object FirstChild : PseudoClass {
            override fun matches(element: Element) = element.previousElementSibling() == null
        }

        object LastChild : PseudoClass {
            override fun matches(element: Element) = element.nextElementSibling() == null
        }

        object OnlyChild : PseudoClass {
            override fun matches(element: Element) =
                element.previousElementSibling() == null && element.nextElementSibling() == null
        }

        object FirstOfType : PseudoClass {
            override fun matches(element: Element) =
                generateSequence(element.previousElementSibling()) { it.previousElementSibling() }
                    .none { it.normalName() == element.normalName() }
        }

        /** `:nth-child(an+b)` / `:nth-of-type(an+b)`, 1-based sibling index. */
        class Nth(private val a: Int, private val b: Int, private val ofType: Boolean) : PseudoClass {
            override fun matches(element: Element): Boolean {
                var index = 1
                var sibling = element.previousElementSibling()
                while (sibling != null) {
                    if (!ofType || sibling.normalName() == element.normalName()) index++
                    sibling = sibling.previousElementSibling()
                }
                val diff = index - b
                return if (a == 0) diff == 0 else diff % a == 0 && diff / a >= 0
            }
        }

        /** `:not(simple-compound)` — no combinators or nested functions. */
        class Not(private val inner: Simple) : PseudoClass {
            override fun matches(element: Element) = !inner.matches(element)
        }
    }

    private class Simple(
        val tag: String?,
        val id: String?,
        val classes: List<String>,
        val attrs: List<AttrSelector>,
        val pseudos: List<PseudoClass>,
    ) {
        fun matches(element: Element): Boolean {
            if (tag != null && tag != "*" && element.normalName() != tag) return false
            if (id != null && element.id() != id) return false
            if (classes.isNotEmpty()) {
                val names = element.classNames()
                if (classes.any { it !in names }) return false
            }
            if (attrs.any { !it.matches(element) }) return false
            if (pseudos.any { !it.matches(element) }) return false
            return true
        }
    }

    /** [combinator] relates this compound to the PREVIOUS one in the chain. */
    private class Compound(val combinator: Combinator, val simple: Simple)

    /**
     * Absolute (inheritance-resolved) style of an element. Font size is
     * cumulative relative to the reader's base size (body = 1.0).
     */
    class Computed(
        val italic: Boolean?,
        val bold: Boolean?,
        val fontSizeEm: Float,
        /** "center" | "end" | "start" | "justify" or null. */
        val textAlign: String?,
        /** First-line indent in em, null when never specified. */
        val textIndentEm: Float?,
        val hidden: Boolean,
        /** Own (non-inherited) box properties in em / width fractions. */
        val marginStartEm: Float,
        val marginStartFrac: Float,
        val marginEndEm: Float,
        val marginTopEm: Float,
        val marginBottomEm: Float,
        /** margin-left/right: auto — a centered block. */
        val centeredBox: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val superScript: Boolean,
        val subScript: Boolean,
        val monospace: Boolean,
        /** First font-family name (normalized), inherited. */
        val fontFamilyName: String?,
        /** line-height as a multiplier of the font size, inherited. */
        val lineHeightMult: Float?,
        /** CSS hyphens: true = auto, false = none/manual, inherited. */
        val hyphensAuto: Boolean?,
        /** Inherited CSS base direction (`ltr`/`rtl`). */
        val direction: String?,
        /** CSS list-style-type keyword, inherited (lists pick it up). */
        val listStyleType: String?,
        /** page-break-before: always — start this block on a fresh page. */
        val pageBreakBefore: Boolean,
        /** float: "left"/"right" — text may wrap around this element. */
        val floatSide: String?,
        /** width as a fraction of the container, when given in %. */
        val widthFrac: Float?,
        /** width in em, when given as a length. */
        val widthEm: Float?,
        /** height in em, when given as a length (ornament images). */
        val heightEm: Float? = null,
        /** Custom properties (`--x`) in scope, inherited like text styles. */
        val customProps: Map<String, String> = emptyMap(),
    )

    private val mutableFontFaces = mutableListOf<FontFace>()
    private val rules = mutableListOf<Rule>()
    private val firstLetterRules = mutableListOf<Rule>()
    private val beforeRules = mutableListOf<Rule>()
    private val afterRules = mutableListOf<Rule>()
    private var orderCounter = 0
    private val cache = HashMap<Element, Computed>()

    init {
        for (sheet in sheets) parseSheetText(sheet.text, sheet.baseDir)
    }

    /** Every `@font-face` declared by the sheets (for embedded fonts). */
    val fontFaces: List<FontFace> get() = mutableFontFaces

    // ------------------------------------------------------------- API

    /** Computed style of [element]; walks and caches the ancestor chain. */
    fun computed(element: Element): Computed {
        cache[element]?.let { return it }
        val parent = element.parent()?.takeIf { it.normalName() != "#root" }
        val inherited = parent?.let { computed(it) }
        val result = resolve(element, inherited)
        cache[element] = result
        return result
    }

    /**
     * Drops per-element computed styles. Chapters are parsed one after
     * another with a shared resolver; clearing between chapters keeps the
     * cache from pinning every DOM node of the whole book in memory.
     */
    fun clearCache() = cache.clear()

    /**
     * The `::first-letter` style targeting [element], or null. Used for
     * drop caps: `isDropCap` is set for floated or noticeably enlarged caps.
     */
    fun firstLetter(element: Element): FirstLetterStyle? {
        if (firstLetterRules.isEmpty()) return null
        val merged = cascade(firstLetterRules, element, inline = null) ?: return null

        var scale = 1f
        var bold: Boolean? = null
        var italic: Boolean? = null
        var floated = false
        var family: String? = null
        for ((property, rawValue) in merged) {
            val value = rawValue.trim().lowercase()
            when (property) {
                "font-size" -> fontSizeFactor(value, 1f)?.let { scale = it }
                "font-weight" -> {
                    val number = value.toIntOrNull()
                    when {
                        value == "bold" || value == "bolder" -> bold = true
                        number != null -> bold = number >= 600
                        value == "normal" || value == "lighter" -> bold = false
                    }
                }

                "font-style" -> when {
                    value.startsWith("italic") || value.startsWith("oblique") -> italic = true
                    value == "normal" -> italic = false
                }

                "float" -> floated = value == "left" || value == "right"
                "font-family" -> firstFamilyName(value)?.let { family = it }
            }
        }
        val isDropCap = floated || scale >= 1.5f
        val plain = !isDropCap && scale in 0.99f..1.01f &&
            bold == null && italic == null && family == null
        if (plain) return null
        return FirstLetterStyle(
            scale = scale.coerceIn(1f, 4f),
            bold = bold,
            italic = italic,
            isDropCap = isDropCap,
            fontFamily = family,
        )
    }

    /** A `::before`/`::after` text run the book generates via CSS. */
    class GeneratedRun(
        val text: String,
        val italic: Boolean? = null,
        val bold: Boolean? = null,
        /** Font size relative to the element (1 = same). */
        val scale: Float = 1f,
    )

    /**
     * Static generated content for [element] (`::after` when [after]).
     * Only literal string `content` is honored — counters, `attr()`,
     * `url()` and quote keywords degrade to nothing, as before.
     */
    fun generated(element: Element, after: Boolean): GeneratedRun? {
        val pool = if (after) afterRules else beforeRules
        if (pool.isEmpty()) return null
        val merged = cascade(pool, element, inline = null) ?: return null
        val text = merged["content"]?.let { parseContentValue(it) } ?: return null
        if (text.isBlank()) return null

        var italic: Boolean? = null
        var bold: Boolean? = null
        var scale = 1f
        for ((property, rawValue) in merged) {
            val value = rawValue.trim().lowercase()
            when (property) {
                "font-style" -> when {
                    value.startsWith("italic") || value.startsWith("oblique") -> italic = true
                    value == "normal" -> italic = false
                }

                "font-weight" -> {
                    val number = value.toIntOrNull()
                    when {
                        value == "bold" || value == "bolder" -> bold = true
                        number != null -> bold = number >= 600
                        value == "normal" || value == "lighter" -> bold = false
                    }
                }

                "font-size" -> fontSizeFactor(value, 1f)?.let { scale = it }
            }
        }
        return GeneratedRun(text, italic, bold, scale.coerceIn(0.5f, 2.5f))
    }

    /**
     * The literal text of a CSS `content` value: quoted strings (possibly
     * several, concatenated) with `\HHHH` escapes. Anything dynamic —
     * counters, `attr()`, `url()`, quote keywords — returns null.
     */
    private fun parseContentValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        if (lower == "none" || lower == "normal") return null
        if ("counter(" in lower || "attr(" in lower || "url(" in lower ||
            "quote" in lower
        ) {
            return null
        }
        val out = StringBuilder()
        var i = 0
        var sawString = false
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' || c == '\'' -> {
                    sawString = true
                    i++
                    while (i < value.length && value[i] != c) {
                        if (value[i] == '\\' && i + 1 < value.length) {
                            i++
                            val hexStart = i
                            var hexEnd = i
                            while (hexEnd < value.length && hexEnd - hexStart < 6 &&
                                value[hexEnd].isHexDigit()
                            ) {
                                hexEnd++
                            }
                            if (hexEnd > hexStart) {
                                value.substring(hexStart, hexEnd).toIntOrNull(16)
                                    ?.takeIf { it in 1..0x10FFFF }
                                    ?.let { out.appendCodePoint(it) }
                                i = hexEnd
                                // A single whitespace terminates the escape.
                                if (i < value.length && value[i] == ' ') i++
                            } else {
                                out.append(value[i])
                                i++
                            }
                        } else {
                            out.append(value[i])
                            i++
                        }
                    }
                    i++ // closing quote
                }

                c.isWhitespace() -> i++

                else -> return null // bare identifiers/functions: unsupported
            }
        }
        return if (sawString) out.toString() else null
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Declarations that directly apply to [element], cascade-ordered. */
    private fun declarationsFor(element: Element): Map<String, String> {
        val inline = element.attr("style")
            .takeIf { it.isNotEmpty() }
            ?.let { parseDeclarations(it) }
        return cascade(rules, element, inline) ?: emptyMap()
    }

    /**
     * CSS 2.1 author-level cascade over the matching rules: normal sheet
     * declarations → normal inline → important sheet → important inline,
     * each layer sorted by (specificity, source order).
     */
    private fun cascade(
        pool: List<Rule>,
        element: Element,
        inline: Map<String, Declaration>?,
    ): Map<String, String>? {
        val matched = ArrayList<Rule>(4)
        for (rule in pool) {
            if (matches(rule.chain, rule.chain.lastIndex, element)) matched += rule
        }
        if (matched.isEmpty() && inline.isNullOrEmpty()) return null
        matched.sortWith(compareBy({ it.specificity }, { it.order }))
        val merged = LinkedHashMap<String, String>()
        for (rule in matched) {
            for ((property, decl) in rule.declarations) {
                if (!decl.important) merged[property] = decl.value
            }
        }
        inline?.forEach { (property, decl) ->
            if (!decl.important) merged[property] = decl.value
        }
        for (rule in matched) {
            for ((property, decl) in rule.declarations) {
                if (decl.important) merged[property] = decl.value
            }
        }
        inline?.forEach { (property, decl) ->
            if (decl.important) merged[property] = decl.value
        }
        return merged
    }

    /** Recursive chain matching with backtracking (the [index]-th compound). */
    private fun matches(chain: List<Compound>, index: Int, element: Element): Boolean {
        if (!chain[index].simple.matches(element)) return false
        if (index == 0) return true
        return when (chain[index].combinator) {
            Combinator.CHILD -> {
                val parent = element.parent()
                parent != null && parent.normalName() != "#root" &&
                    matches(chain, index - 1, parent)
            }

            Combinator.DESCENDANT -> {
                var ancestor = element.parent()
                while (ancestor != null && ancestor.normalName() != "#root") {
                    if (matches(chain, index - 1, ancestor)) return true
                    ancestor = ancestor.parent()
                }
                false
            }

            Combinator.NEXT_SIBLING -> {
                val sibling = element.previousElementSibling()
                sibling != null && matches(chain, index - 1, sibling)
            }

            Combinator.SUBSEQUENT_SIBLING -> {
                var sibling = element.previousElementSibling()
                while (sibling != null) {
                    if (matches(chain, index - 1, sibling)) return true
                    sibling = sibling.previousElementSibling()
                }
                false
            }
        }
    }

    // ------------------------------------------------------------- resolve

    private fun resolve(element: Element, parent: Computed?): Computed {
        val decl = declarationsFor(element)

        // Custom properties cascade like inherited values; own declarations
        // shadow the parent's. Their values may reference other vars.
        val parentProps = parent?.customProps ?: emptyMap()
        var customProps = parentProps
        if (decl.keys.any { it.startsWith("--") }) {
            val own = LinkedHashMap(parentProps)
            for ((property, value) in decl) {
                if (property.startsWith("--")) {
                    own[property] = substituteVars(value, parentProps) ?: value
                }
            }
            customProps = own
        }

        var italic = parent?.italic
        var bold = parent?.bold
        var fontSize = parent?.fontSizeEm ?: 1f
        var align = parent?.textAlign
        var indent = parent?.textIndentEm
        var hidden = parent?.hidden ?: false
        var underline = parent?.underline ?: false
        var strike = parent?.strike ?: false
        var monospace = parent?.monospace ?: false
        var fontFamilyName = parent?.fontFamilyName
        var lineHeightMult = parent?.lineHeightMult
        var hyphensAuto = parent?.hyphensAuto
        var direction = parent?.direction
        var listStyleType = parent?.listStyleType
        var superScript = false
        var subScript = false
        var marginStartEm = 0f
        var marginStartFrac = 0f
        var marginEndEm = 0f
        var marginTopEm = 0f
        var marginBottomEm = 0f
        var autoStart = false
        var autoEnd = false
        var pageBreakBefore = false
        var floatSide: String? = null
        var widthFrac: Float? = null
        var widthEm: Float? = null
        var heightEm: Float? = null

        // Tag defaults, so CSS-less inline tags keep working through here.
        when (element.normalName()) {
            "i", "em", "dfn", "var", "cite" -> italic = true
            "b", "strong" -> bold = true
            "u", "ins" -> underline = true
            "s", "strike", "del" -> strike = true
            "sup" -> superScript = true
            "sub" -> subScript = true
            "code", "kbd", "samp", "tt", "pre" -> monospace = true
            "h1" -> { bold = true; fontSize *= 1.5f }
            "h2" -> { bold = true; fontSize *= 1.32f }
            "h3" -> { bold = true; fontSize *= 1.18f }
            "h4", "h5", "h6" -> bold = true
            "small" -> fontSize *= 0.85f
            "big" -> fontSize *= 1.2f
        }

        for ((property, declaredValue) in decl) {
            if (property.startsWith("--")) continue
            // var() substitution: unresolvable without a fallback → the
            // declaration is invalid at computed-value time and dropped.
            val rawValue = if ("var(" in declaredValue) {
                substituteVars(declaredValue, customProps) ?: continue
            } else {
                declaredValue
            }
            val value = rawValue.trim().lowercase()
            when (property) {
                "font-style" -> when {
                    value.startsWith("italic") || value.startsWith("oblique") -> italic = true
                    value == "normal" -> italic = false
                }

                "font-weight" -> {
                    val number = value.toIntOrNull()
                    when {
                        value == "bold" || value == "bolder" -> bold = true
                        number != null -> bold = number >= 600
                        value == "normal" || value == "lighter" -> bold = false
                    }
                }

                "font-size" -> fontSizeFactor(value, fontSize)?.let { fontSize = it }

                "font-family" -> {
                    monospace = value.contains("monospace")
                    firstFamilyName(value)?.let { fontFamilyName = it }
                }

                "line-height" -> lineHeightMultOf(value, fontSize)?.let {
                    lineHeightMult = it.coerceIn(1f, 2.4f)
                }

                "hyphens", "-webkit-hyphens", "-epub-hyphens", "-moz-hyphens" -> when (value) {
                    "auto" -> hyphensAuto = true
                    "none", "manual" -> hyphensAuto = false
                }

                "direction" -> when (value) {
                    "ltr", "rtl" -> direction = value
                }

                "adobe-hyphenate" -> when (value) {
                    "explicit", "none" -> hyphensAuto = false
                    "auto" -> hyphensAuto = true
                }

                "font" -> { // shorthand: only pick out italic/bold flags
                    if (value.contains("italic") || value.contains("oblique")) italic = true
                    if (value.contains("bold")) bold = true
                }

                "text-align" -> when (value) {
                    "center" -> align = "center"
                    "right", "end" -> align = "end"
                    "left", "start" -> align = "start"
                    "justify" -> align = "justify"
                    // "inherit": keep the parent's value already in `align`.
                }

                "text-indent" -> lengthEm(value, percentBase = 0.30f)?.let {
                    indent = it.coerceIn(0f, 4f)
                }

                "display" -> hidden = hidden || value == "none"

                "text-decoration", "text-decoration-line" -> {
                    if (value.contains("underline")) underline = true
                    if (value.contains("line-through")) strike = true
                    if (value == "none") { underline = false; strike = false }
                }

                "vertical-align" -> when {
                    value.contains("super") -> superScript = true
                    value.contains("sub") -> subScript = true
                }

                "list-style-type" -> listTypeKeyword(value)?.let { listStyleType = it }

                "list-style" -> value.split(whitespaceRegex)
                    .firstNotNullOfOrNull { listTypeKeyword(it) }
                    ?.let { listStyleType = it }

                "page-break-before", "break-before" -> when (value) {
                    "always", "left", "right", "page" -> pageBreakBefore = true
                    "auto", "avoid" -> pageBreakBefore = false
                }

                "float" -> when (value) {
                    "left", "right" -> floatSide = value
                    "none" -> floatSide = null
                }

                "width" -> if (value.endsWith("%")) {
                    leadingNumber(value)?.let { widthFrac = (it / 100f).coerceIn(0.05f, 1f) }
                } else {
                    lengthEm(value)?.let { widthEm = it }
                }

                "height" -> if (!value.endsWith("%") && value != "auto") {
                    lengthEm(value)?.takeIf { it > 0f }?.let { heightEm = it }
                }

                "margin" -> {
                    val parts = value.split(whitespaceRegex).filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) {
                        val top = parts[0]
                        val end = parts.getOrElse(1) { top }
                        val bottom = parts.getOrElse(2) { top }
                        val start = parts.getOrElse(3) { end }
                        lengthEm(top)?.let { marginTopEm = it }
                        lengthEm(bottom)?.let { marginBottomEm = it }
                        if (start == "auto") autoStart = true else {
                            lengthEm(start)?.let { marginStartEm = it }
                            percentFrac(start)?.let { marginStartFrac = it }
                        }
                        if (end == "auto") autoEnd = true else {
                            lengthEm(end)?.let { marginEndEm = it }
                        }
                    }
                }

                "margin-left" -> if (value == "auto") autoStart = true else {
                    lengthEm(value)?.let { marginStartEm = it }
                    percentFrac(value)?.let { marginStartFrac = it }
                }

                "margin-right" -> if (value == "auto") autoEnd = true else {
                    lengthEm(value)?.let { marginEndEm = it }
                }

                "margin-top" -> lengthEm(value)?.let { marginTopEm = it }
                "margin-bottom" -> lengthEm(value)?.let { marginBottomEm = it }

                "padding-left" -> lengthEm(value)?.let { marginStartEm += it }
                "padding-right" -> lengthEm(value)?.let { marginEndEm += it }
            }
        }

        return Computed(
            customProps = customProps,
            italic = italic,
            bold = bold,
            fontSizeEm = fontSize.coerceIn(0.5f, 3f),
            textAlign = align,
            textIndentEm = indent,
            hidden = hidden,
            marginStartEm = marginStartEm.coerceIn(0f, 8f),
            marginStartFrac = marginStartFrac.coerceIn(0f, 0.45f),
            marginEndEm = marginEndEm.coerceIn(0f, 8f),
            marginTopEm = marginTopEm.coerceIn(0f, 4f),
            marginBottomEm = marginBottomEm.coerceIn(0f, 4f),
            centeredBox = autoStart && autoEnd,
            underline = underline,
            strike = strike,
            superScript = superScript,
            subScript = subScript,
            monospace = monospace,
            fontFamilyName = fontFamilyName,
            lineHeightMult = lineHeightMult,
            hyphensAuto = hyphensAuto,
            direction = direction,
            listStyleType = listStyleType,
            pageBreakBefore = pageBreakBefore,
            floatSide = floatSide,
            widthFrac = widthFrac,
            widthEm = widthEm,
            heightEm = heightEm,
        )
    }

    /**
     * Replaces every `var(--name, fallback?)` in [value] with the property's
     * value from [props] (or the fallback). Null when a reference cannot be
     * resolved; the recursion cap kills self-referential cycles.
     */
    private fun substituteVars(value: String, props: Map<String, String>, depth: Int = 0): String? {
        val start = value.indexOf("var(")
        if (start < 0) return value
        if (depth > 8) return null
        val close = matchingParen(value, start + 3)
        if (close < 0) return null
        val inner = value.substring(start + 4, close)
        var comma = -1
        var parens = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '(' -> parens++
                ')' -> parens--
                ',' -> if (parens == 0) {
                    comma = i
                    break
                }
            }
        }
        val name = (if (comma < 0) inner else inner.substring(0, comma)).trim().lowercase()
        val fallback = if (comma < 0) null else inner.substring(comma + 1).trim()
        val replacement = props[name] ?: fallback ?: return null
        val substituted = value.substring(0, start) + replacement + value.substring(close + 1)
        return substituteVars(substituted, props, depth + 1)
    }

    // ------------------------------------------------------------- values

    /** New cumulative font factor for a font-size [value], or null. */
    private fun fontSizeFactor(value: String, current: Float): Float? = when (value) {
        "medium" -> 1f
        "small" -> 0.9f
        "x-small" -> 0.75f
        "xx-small" -> 0.6f
        "large" -> 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        "smaller" -> current * 0.85f
        "larger" -> current * 1.2f
        else -> {
            if (CssCalc.isMath(value)) {
                CssCalc.eval(value, CssCalc.Ctx(current, 1f, current / 16f, current / 100f))
                    ?.takeIf { it > 0f }
            } else {
                val number = leadingNumber(value) ?: return null
                when (unitOf(value)) {
                    "em" -> current * number
                    "rem" -> number
                    "%" -> current * number / 100f
                    "px" -> current * number / 16f
                    "pt" -> current * number / 12f
                    "pc" -> current * number
                    "in" -> current * number * 6f
                    "cm" -> current * number * 2.3622f
                    "mm" -> current * number * 0.23622f
                    "ch", "ex" -> current * number * 0.5f
                    // Viewport units are root-relative, not cumulative.
                    "vw", "vmin" -> number * 0.30f
                    "vh", "vmax" -> number * 0.50f
                    else -> null
                }
            }
        }
    }

    /** Length in em (relative to the element's font), or null. */
    private fun lengthEm(value: String, percentBase: Float = 0f): Float? {
        if (value == "0") return 0f
        if (CssCalc.isMath(value)) {
            val percentUnit = if (percentBase > 0f) percentBase * 30f / 100f else null
            return CssCalc.eval(value, CssCalc.Ctx(1f, 1f, 1f / 16f, percentUnit))
                ?.coerceAtLeast(0f)
        }
        val number = leadingNumber(value) ?: return null
        if (number < 0) return 0f
        return when (unitOf(value)) {
            "em", "rem" -> number
            "px" -> number / 16f
            "pt" -> number / 12f
            "pc" -> number // 16px
            "in" -> number * 6f // 96px
            "cm" -> number * 2.3622f
            "mm" -> number * 0.23622f
            "q" -> number * 0.059055f
            "ch", "ex" -> number * 0.5f
            // Viewport units against the 30-em content-width convention.
            "vw", "vmin" -> number * 0.30f
            "vh", "vmax" -> number * 0.50f
            "%" -> if (percentBase > 0f) number / 100f * percentBase * 30f else null
            else -> null
        }
    }

    /** The unit suffix of a numeric value ("1.5em" → "em", "30%" → "%"). */
    private fun unitOf(value: String): String {
        var i = value.length
        while (i > 0 && (value[i - 1].isLetter() || value[i - 1] == '%')) i--
        return value.substring(i)
    }

    /** A percentage as a fraction of the container width, or null. */
    private fun percentFrac(value: String): Float? {
        if (!value.endsWith("%")) return null
        val number = leadingNumber(value) ?: return null
        return (number / 100f).coerceIn(0f, 0.6f)
    }

    private fun leadingNumber(value: String): Float? =
        numberRegex.find(value)?.value?.toFloatOrNull()

    /** First family of a font-family list, unquoted and lowercased. */
    private fun firstFamilyName(value: String): String? =
        value.split(',').firstOrNull()
            ?.trim()?.trim('"', '\'')?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "inherit" }

    /** line-height as a multiplier of the element's font size, or null. */
    private fun lineHeightMultOf(value: String, fontSizeEm: Float): Float? {
        if (value == "normal") return null
        if (CssCalc.isMath(value)) {
            val pxUnit = 1f / 16f / fontSizeEm.coerceAtLeast(0.1f)
            return CssCalc.eval(value, CssCalc.Ctx(1f, 1f, pxUnit, 0.01f), acceptScalar = true)
                ?.takeIf { it > 0f }
        }
        val number = leadingNumber(value) ?: return null
        if (number <= 0) return null
        return when {
            value.endsWith("%") -> number / 100f
            value.endsWith("em") -> number
            value.endsWith("px") -> number / 16f / fontSizeEm.coerceAtLeast(0.1f)
            value.endsWith("pt") -> number / 12f / fontSizeEm.coerceAtLeast(0.1f)
            // Unitless: already a multiplier.
            value == number.toString() || value.toFloatOrNull() != null -> number
            else -> null
        }
    }

    private fun listTypeKeyword(value: String): String? = when (value) {
        "disc", "circle", "square", "none", "decimal",
        "lower-alpha", "lower-latin", "upper-alpha", "upper-latin",
        "lower-roman", "upper-roman",
        -> value

        else -> null
    }

    // ------------------------------------------------------------- parsing

    private fun parseSheetText(sheetText: String, baseDir: String) {
        val css = stripComments(sheetText)
        var index = 0
        while (index < css.length) {
            while (index < css.length && css[index].isWhitespace()) index++
            if (index >= css.length) break

            // Brace-less @-statements (@charset/@import/@namespace) end at the
            // semicolon. Without this, their text becomes a prefix of the next
            // rule's selector, which then reads as an @-rule and the whole
            // next rule is silently skipped.
            if (css[index] == '@') {
                val semi = css.indexOf(';', index)
                val brace = css.indexOf('{', index)
                if (semi >= 0 && (brace < 0 || semi < brace)) {
                    index = semi + 1
                    continue
                }
            }

            val braceOpen = css.indexOf('{', index)
            if (braceOpen < 0) break
            val selectorText = css.substring(index, braceOpen).trim()

            // @-rules: unwrap screen/all @media, collect @font-face,
            // skip the rest wholesale.
            if (selectorText.startsWith("@")) {
                if (selectorText.startsWith("@media", ignoreCase = true)) {
                    val bodyEnd = matchingBrace(css, braceOpen)
                    if (bodyEnd < 0) break
                    if (screenMediaApplies(selectorText.substring(6))) {
                        parseSheetText(css.substring(braceOpen + 1, bodyEnd), baseDir)
                    }
                    index = bodyEnd + 1
                } else if (selectorText.startsWith("@font-face", ignoreCase = true)) {
                    val braceClose = css.indexOf('}', braceOpen)
                    if (braceClose < 0) break
                    parseFontFace(
                        parseDeclarations(css.substring(braceOpen + 1, braceClose)),
                        baseDir,
                    )
                    index = braceClose + 1
                } else {
                    val bodyEnd = matchingBrace(css, braceOpen)
                    if (bodyEnd < 0) break
                    index = bodyEnd + 1
                }
                continue
            }

            val braceClose = css.indexOf('}', braceOpen)
            if (braceClose < 0) break
            val declarations = parseDeclarations(css.substring(braceOpen + 1, braceClose))
            if (declarations.isNotEmpty()) {
                for (selector in selectorText.split(',')) {
                    val parsed = parseSelector(selector.trim()) ?: continue
                    val rule = Rule(parsed.chain, parsed.specificity, orderCounter++, declarations)
                    when (parsed.pseudoElement) {
                        PseudoElement.FIRST_LETTER -> firstLetterRules += rule
                        PseudoElement.BEFORE -> beforeRules += rule
                        PseudoElement.AFTER -> afterRules += rule
                        null -> rules += rule
                    }
                }
            }
            index = braceClose + 1
        }
    }

    /**
     * Evaluates the media *type* against the reader's screen environment.
     * Feature expressions are deliberately left responsive: the custom
     * renderer has no fixed CSS viewport at parse time, so `(min-width: …)`
     * is treated as potentially applicable. Explicit print/speech rules and
     * negated screen rules, however, must never leak into normal reading.
     */
    private fun screenMediaApplies(raw: String): Boolean = raw.split(',').any { branch ->
        var query = branch.trim().lowercase()
        if (query.isEmpty()) return@any true
        var negated = false
        if (query.startsWith("only ")) query = query.removePrefix("only ").trimStart()
        if (query.startsWith("not ")) {
            negated = true
            query = query.removePrefix("not ").trimStart()
        }
        val type = query.split(Regex("""\s+and\b"""), limit = 2).first().trim()
            .takeUnless { it.startsWith("(") }
            ?.substringBefore(' ')
            .orEmpty()
        val baseApplies = when (type) {
            "", "all", "screen" -> true
            "print", "speech", "aural", "braille", "embossed", "handheld",
            "projection", "tty", "tv", "amzn-mobi", "amzn-kf8" -> false
            else -> false
        }
        if (negated) !baseApplies else baseApplies
    }

    private fun parseFontFace(declarations: Map<String, Declaration>, baseDir: String) {
        val family = declarations["font-family"]?.let { firstFamilyName(it.value) } ?: return
        val src = declarations["src"]?.value ?: return
        val url = fontUrlRegex.find(src)?.groupValues?.get(1) ?: return
        val weight = declarations["font-weight"]?.value?.trim()?.lowercase()
        val style = declarations["font-style"]?.value?.trim()?.lowercase()
        mutableFontFaces += FontFace(
            family = family,
            src = url,
            baseDir = baseDir,
            bold = weight == "bold" || (weight?.toIntOrNull() ?: 400) >= 600,
            italic = style?.startsWith("italic") == true || style?.startsWith("oblique") == true,
        )
    }

    private enum class PseudoElement { FIRST_LETTER, BEFORE, AFTER }

    private class ParsedSelector(
        val chain: List<Compound>,
        val specificity: Int,
        /** Non-null when the selector targets a supported pseudo-element. */
        val pseudoElement: PseudoElement?,
    )

    /**
     * Tokenizing selector parser. Returns null for selectors that cannot be
     * honored (unknown pseudo-classes, functional selectors, syntax errors) —
     * only that comma-group member is dropped.
     */
    private fun parseSelector(selector: String): ParsedSelector? {
        val text = selector.trim()
        if (text.isEmpty()) return null
        val chain = mutableListOf<Compound>()
        var ids = 0
        var classLike = 0
        var tags = 0
        var pseudoElement: PseudoElement? = null
        var pseudoElementAt = -1
        var pending: Combinator? = null
        var i = 0

        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                ' ', '\t', '\n', '\r' -> i++

                '>', '+', '~' -> {
                    if (chain.isEmpty() || pending != null) return null
                    pending = when (ch) {
                        '>' -> Combinator.CHILD
                        '+' -> Combinator.NEXT_SIBLING
                        else -> Combinator.SUBSEQUENT_SIBLING
                    }
                    i++
                }

                else -> {
                    // One compound: tag / * / .class / #id / [attr] / :pseudo.
                    var tag: String? = null
                    var id: String? = null
                    val classes = mutableListOf<String>()
                    val attrs = mutableListOf<AttrSelector>()
                    val pseudos = mutableListOf<PseudoClass>()
                    var sawPseudoElement: PseudoElement? = null

                    compound@ while (i < text.length) {
                        when (val c = text[i]) {
                            ' ', '\t', '\n', '\r', '>', '+', '~' -> break@compound

                            '*' -> {
                                tag = "*"
                                i++
                            }

                            '.' -> {
                                val end = readIdent(text, i + 1)
                                if (end == i + 1) return null
                                classes += text.substring(i + 1, end)
                                classLike++
                                i = end
                            }

                            '#' -> {
                                val end = readIdent(text, i + 1)
                                if (end == i + 1) return null
                                id = text.substring(i + 1, end)
                                ids++
                                i = end
                            }

                            '[' -> {
                                val close = text.indexOf(']', i)
                                if (close < 0) return null
                                attrs += parseAttrSelector(text.substring(i + 1, close))
                                    ?: return null
                                classLike++
                                i = close + 1
                            }

                            ':' -> {
                                val isElement = i + 1 < text.length && text[i + 1] == ':'
                                val start = i + if (isElement) 2 else 1
                                val end = readIdent(text, start)
                                if (end == start) return null
                                val name = text.substring(start, end).lowercase()
                                if (end < text.length && text[end] == '(') {
                                    // Functional pseudo-classes. Unsupported
                                    // ones (:has, :is, :nth-last-*) still drop
                                    // only this comma-group member.
                                    if (isElement) return null
                                    val close = matchingParen(text, end)
                                    if (close < 0) return null
                                    val arg = text.substring(end + 1, close).trim()
                                    when (name) {
                                        "not" -> {
                                            val inner = parseInnerCompound(arg) ?: return null
                                            pseudos += PseudoClass.Not(inner.simple)
                                            ids += inner.ids
                                            classLike += inner.classLike
                                            tags += inner.tags
                                        }

                                        "nth-child", "nth-of-type" -> {
                                            val nth = parseNth(arg) ?: return null
                                            pseudos += PseudoClass.Nth(
                                                nth.first,
                                                nth.second,
                                                ofType = name == "nth-of-type",
                                            )
                                            classLike++
                                        }

                                        else -> return null
                                    }
                                    i = close + 1
                                } else {
                                    when (name) {
                                        "first-letter" -> {
                                            sawPseudoElement = PseudoElement.FIRST_LETTER
                                            tags++ // pseudo-elements count like tags
                                        }

                                        "before" -> {
                                            sawPseudoElement = PseudoElement.BEFORE
                                            tags++
                                        }

                                        "after" -> {
                                            sawPseudoElement = PseudoElement.AFTER
                                            tags++
                                        }

                                        "first-child" -> {
                                            if (isElement) return null
                                            pseudos += PseudoClass.FirstChild
                                            classLike++
                                        }

                                        "last-child" -> {
                                            if (isElement) return null
                                            pseudos += PseudoClass.LastChild
                                            classLike++
                                        }

                                        "only-child" -> {
                                            if (isElement) return null
                                            pseudos += PseudoClass.OnlyChild
                                            classLike++
                                        }

                                        "first-of-type" -> {
                                            if (isElement) return null
                                            pseudos += PseudoClass.FirstOfType
                                            classLike++
                                        }

                                        else -> return null
                                    }
                                    i = end
                                }
                            }

                            else -> {
                                if (tag != null || id != null || classes.isNotEmpty() ||
                                    attrs.isNotEmpty() || pseudos.isNotEmpty()
                                ) {
                                    return null // tag name not at compound start
                                }
                                val end = readIdent(text, i)
                                if (end == i) return null
                                tag = text.substring(i, end).lowercase()
                                tags++
                                i = end
                            }
                        }
                    }

                    val empty = tag == null && id == null && classes.isEmpty() &&
                        attrs.isEmpty() && pseudos.isEmpty() && sawPseudoElement == null
                    if (empty) return null
                    chain += Compound(
                        pending ?: Combinator.DESCENDANT,
                        Simple(tag, id, classes, attrs, pseudos),
                    )
                    if (sawPseudoElement != null) {
                        pseudoElement = sawPseudoElement
                        pseudoElementAt = chain.lastIndex
                    }
                    pending = null
                }
            }
        }

        if (chain.isEmpty() || pending != null) return null
        // A pseudo-element is only meaningful on the selector's subject.
        if (pseudoElementAt >= 0 && pseudoElementAt != chain.lastIndex) return null
        return ParsedSelector(
            chain = chain,
            specificity = ids * 1_000_000 + classLike * 1_000 + tags,
            pseudoElement = pseudoElement,
        )
    }

    private class InnerCompound(
        val simple: Simple,
        val ids: Int,
        val classLike: Int,
        val tags: Int,
    )

    /** The argument of `:not()`: one compound, no combinators/pseudos. */
    private fun parseInnerCompound(argument: String): InnerCompound? {
        val text = argument.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        var tag: String? = null
        var id: String? = null
        val classes = mutableListOf<String>()
        val attrs = mutableListOf<AttrSelector>()
        var ids = 0
        var classLike = 0
        var tags = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '*' -> {
                    tag = "*"
                    i++
                }

                '.' -> {
                    val end = readIdent(text, i + 1)
                    if (end == i + 1) return null
                    classes += text.substring(i + 1, end)
                    classLike++
                    i = end
                }

                '#' -> {
                    val end = readIdent(text, i + 1)
                    if (end == i + 1) return null
                    id = text.substring(i + 1, end)
                    ids++
                    i = end
                }

                '[' -> {
                    val close = text.indexOf(']', i)
                    if (close < 0) return null
                    attrs += parseAttrSelector(text.substring(i + 1, close)) ?: return null
                    classLike++
                    i = close + 1
                }

                ':' -> return null // nested pseudos inside :not(): drop

                else -> {
                    if (tag != null || id != null || classes.isNotEmpty() || attrs.isNotEmpty()) {
                        return null
                    }
                    val end = readIdent(text, i)
                    if (end == i) return null
                    tag = text.substring(i, end).lowercase()
                    tags++
                    i = end
                }
            }
        }
        if (tag == null && id == null && classes.isEmpty() && attrs.isEmpty()) return null
        return InnerCompound(Simple(tag, id, classes, attrs, emptyList()), ids, classLike, tags)
    }

    /** `an+b`, `even`, `odd` or a bare integer → (a, b). */
    private fun parseNth(argument: String): Pair<Int, Int>? {
        val arg = argument.lowercase().replace(" ", "")
        return when {
            arg == "even" -> 2 to 0
            arg == "odd" -> 2 to 1
            !arg.contains('n') -> arg.toIntOrNull()?.let { 0 to it }
            else -> {
                val match = nthRegex.matchEntire(arg) ?: return null
                val a = when (val aText = match.groupValues[1]) {
                    "", "+" -> 1
                    "-" -> -1
                    else -> aText.toIntOrNull() ?: return null
                }
                val b = match.groupValues[2]
                    .takeIf { it.isNotEmpty() }
                    ?.removePrefix("+")
                    ?.let { it.toIntOrNull() ?: return null }
                    ?: 0
                a to b
            }
        }
    }

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** Identifier scan: letters, digits, `-`, `_` and non-ASCII. */
    private fun readIdent(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c.code > 127) i++ else break
        }
        return i
    }

    /** Parses the inside of `[…]`: `attr`, `attr=v`, `attr~=v` … */
    private fun parseAttrSelector(inner: String): AttrSelector? {
        val text = inner.trim()
        if (text.isEmpty()) return null
        val opIndex = text.indexOfFirst { it in "=~|^$*" }
        if (opIndex < 0) {
            val name = text.lowercase()
            return if (name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' }) {
                AttrSelector(name, AttrOp.EXISTS, null)
            } else {
                null
            }
        }
        val opChar = text[opIndex]
        val op: AttrOp
        val valueStart: Int
        if (opChar == '=') {
            op = AttrOp.EQUALS
            valueStart = opIndex + 1
        } else {
            if (opIndex + 1 >= text.length || text[opIndex + 1] != '=') return null
            op = when (opChar) {
                '~' -> AttrOp.INCLUDES
                '|' -> AttrOp.DASH
                '^' -> AttrOp.PREFIX
                '$' -> AttrOp.SUFFIX
                else -> AttrOp.SUBSTRING
            }
            valueStart = opIndex + 2
        }
        val name = text.substring(0, opIndex).trim().lowercase()
        if (name.isEmpty()) return null
        var value = text.substring(valueStart).trim()
        // Drop a trailing case-sensitivity flag: [attr="v" i].
        caseFlagRegex.find(value)?.let { value = it.groupValues[1].trim() }
        value = value.trim('"', '\'')
        return AttrSelector(name, op, value)
    }

    private fun parseDeclarations(text: String): Map<String, Declaration> {
        val out = LinkedHashMap<String, Declaration>()
        for (declaration in text.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon <= 0) continue
            val property = declaration.substring(0, colon).trim().lowercase()
            var value = declaration.substring(colon + 1).trim()
            var important = false
            val bang = value.lastIndexOf('!')
            if (bang >= 0 && value.substring(bang + 1).trim().equals("important", true)) {
                important = true
                value = value.substring(0, bang).trim()
            }
            if (property.isNotEmpty() && value.isNotEmpty()) {
                out[property] = Declaration(value, important)
            }
        }
        return out
    }

    private fun stripComments(css: String): String =
        commentRegex.replace(css, " ")

    private fun matchingBrace(css: String, open: Int): Int {
        var depth = 0
        for (i in open until css.length) {
            when (css[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private companion object {
        val commentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val whitespaceRegex = Regex("""\s+""")
        // ".5em" is as common as "0.5em" in publisher CSS — without the
        // leading-dot branch the match starts at "5" and the value comes
        // out ten times too large.
        val numberRegex = Regex("""-?(?:\d+(?:\.\d+)?|\.\d+)""")
        val fontUrlRegex = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")
        val caseFlagRegex = Regex("""^(['"].*['"])\s+[iIsS]$""")
        val nthRegex = Regex("""^([+-]?\d*)n([+-]\d+)?$""")
    }
}
