package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.HeadingDefaults
import org.jsoup.nodes.Element
import java.util.IdentityHashMap

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
 * Author foreground/background colors are retained and later gated by the
 * publisher-formatting switch; positioning remains outside the native model.
 * A selector using an
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

    private enum class MarginSide { TOP, RIGHT, BOTTOM, LEFT, INLINE_START, INLINE_END }
    private enum class FontPart { STYLE, WEIGHT, SIZE, LINE_HEIGHT, FAMILY }

    private class Declaration(
        val value: String,
        val important: Boolean,
        /** Non-null when an inline/physical box shorthand awaits value expansion. */
        val marginSide: MarginSide? = null,
        /** Non-null for a `font: var(...)` pending computed-value expansion. */
        val fontPart: FontPart? = null,
    )

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
        fun matches(element: Element, context: SelectorMatchContext): Boolean

        object FirstChild : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext) =
                element.previousElementSibling() == null
        }

        object LastChild : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext) =
                element.nextElementSibling() == null
        }

        object OnlyChild : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext) =
                element.previousElementSibling() == null && element.nextElementSibling() == null
        }

        object FirstOfType : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext) =
                context.siblingIndex(element, ofType = true) == 1
        }

        /** `:nth-child(an+b)` / `:nth-of-type(an+b)`, 1-based sibling index. */
        class Nth(private val a: Int, private val b: Int, private val ofType: Boolean) : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext): Boolean {
                val index = context.siblingIndex(element, ofType) ?: return false
                val diff = index - b
                return if (a == 0) diff == 0 else diff % a == 0 && diff / a >= 0
            }
        }

        /** `:not(simple-compound)` — no combinators or nested functions. */
        class Not(private val inner: Simple) : PseudoClass {
            override fun matches(element: Element, context: SelectorMatchContext) =
                !inner.matches(element, context)
        }
    }

    private class Simple(
        val tag: String?,
        val id: String?,
        val classes: List<String>,
        val attrs: List<AttrSelector>,
        val pseudos: List<PseudoClass>,
    ) {
        fun matches(element: Element, context: SelectorMatchContext): Boolean {
            if (!context.consume(1 + classes.size + attrs.size + pseudos.size)) return false
            if (tag != null && tag != "*" && element.normalName() != tag) return false
            if (id != null && element.id() != id) return false
            if (classes.isNotEmpty()) {
                val names = element.classNames()
                if (classes.any { it !in names }) return false
            }
            if (attrs.any { !it.matches(element) }) return false
            if (pseudos.any { !it.matches(element, context) }) return false
            return true
        }
    }

    /** [combinator] relates this compound to the PREVIOUS one in the chain. */
    private class Compound(val combinator: Combinator, val simple: Simple)

    /**
     * Per-resolver work ceiling plus a linear-time cache for structural
     * pseudo-classes. Without the cache, resolving `:nth-child` for every
     * member of one large sibling list is quadratic even with a rule cap.
     */
    private class SelectorMatchContext(maxOperations: Int) {
        private data class SiblingPosition(val child: Int, val ofType: Int)

        private var remaining = maxOperations
        private val positionsByParent =
            IdentityHashMap<Element, IdentityHashMap<Element, SiblingPosition>>()

        val exhausted: Boolean get() = remaining <= 0

        fun consume(cost: Int = 1): Boolean {
            if (cost <= 0) return true
            if (remaining < cost) {
                remaining = 0
                return false
            }
            remaining -= cost
            return true
        }

        fun siblingIndex(element: Element, ofType: Boolean): Int? {
            val parent = element.parent() ?: return 1
            var positions = positionsByParent[parent]
            if (positions == null) {
                val children = parent.children()
                if (!consume(children.size)) return null
                positions = IdentityHashMap(children.size)
                val typeCounts = HashMap<String, Int>()
                children.forEachIndexed { index, child ->
                    val tag = child.normalName()
                    val typeIndex = (typeCounts[tag] ?: 0) + 1
                    typeCounts[tag] = typeIndex
                    positions[child] = SiblingPosition(index + 1, typeIndex)
                }
                positionsByParent[parent] = positions
            }
            val position = positions[element] ?: return null
            return if (ofType) position.ofType else position.child
        }
    }

    /**
     * Rules are bucketed by the most selective cheap part of their subject.
     * A chapter element therefore never scans an attacker-controlled global
     * rule list just to reject every selector at its rightmost compound.
     */
    private class RuleIndex {
        private val universal = mutableListOf<Rule>()
        private val byTag = mutableMapOf<String, MutableList<Rule>>()
        private val byClass = mutableMapOf<String, MutableList<Rule>>()
        private val byId = mutableMapOf<String, MutableList<Rule>>()
        var size: Int = 0
            private set

        fun add(rule: Rule) {
            val subject = rule.chain.last().simple
            val id = subject.id
            val tag = subject.tag
            val bucket = when {
                id != null -> byId.getOrPut(id) { mutableListOf() }
                subject.classes.isNotEmpty() ->
                    byClass.getOrPut(subject.classes.first()) { mutableListOf() }
                tag != null && tag != "*" ->
                    byTag.getOrPut(tag) { mutableListOf() }
                else -> universal
            }
            val cap = if (bucket === universal) MAX_UNIVERSAL_RULES else MAX_RULES_PER_INDEX_BUCKET
            if (bucket.size >= cap) return
            bucket.add(rule)
            size++
        }

        fun visitCandidates(element: Element, visitor: (Rule) -> Boolean) {
            fun visit(bucket: List<Rule>?) {
                if (bucket == null) return
                for (rule in bucket) if (!visitor(rule)) return
            }

            for (rule in universal) if (!visitor(rule)) return
            val tag = byTag[element.normalName()]
            if (tag != null) for (rule in tag) if (!visitor(rule)) return
            for (name in element.classNames()) {
                val classes = byClass[name] ?: continue
                for (rule in classes) if (!visitor(rule)) return
            }
            val id = element.id()
            if (id.isNotEmpty()) visit(byId[id])
        }
    }

    /**
     * Absolute (inheritance-resolved) style of an element. Font size is
     * cumulative relative to the reader's base size (body = 1.0).
     */
    class Computed(
        val italic: Boolean?,
        val bold: Boolean?,
        val fontSizeEm: Float,
        /** "center" | "left" | "right" | "end" | "start" | "justify" or null. */
        val textAlign: String?,
        /** First-line indent in em, null when never specified. */
        val textIndentEm: Float?,
        val hidden: Boolean,
        /** Own (non-inherited) box properties in em / width fractions. */
        /** Physical CSS left/right margins and padding (legacy names retained internally). */
        val marginStartEm: Float,
        val marginStartFrac: Float,
        val marginEndEm: Float,
        val marginEndFrac: Float,
        /** Logical CSS inline-axis margins and padding. */
        val marginInlineStartEm: Float,
        val marginInlineStartFrac: Float,
        val marginInlineEndEm: Float,
        val marginInlineEndFrac: Float,
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
        /** Effective line-height multiplier for this element's own font size. */
        val lineHeightMult: Float?,
        /**
         * Inheritance form of line-height. A unitless number is inherited as
         * a multiplier; percentages and lengths are inherited as their
         * already-computed absolute height relative to the reader root.
         */
        internal val lineHeightNumber: Float?,
        internal val lineHeightAbsoluteEm: Float?,
        /** CSS hyphens: true = auto, false = none/manual, inherited. */
        val hyphensAuto: Boolean?,
        /** Inherited HTML/CSS base direction (`ltr`/`rtl`/`auto`). */
        val direction: String?,
        /** Own CSS unicode-bidi value; this property does not inherit. */
        val unicodeBidi: String?,
        /** CSS list-style-type keyword, inherited (lists pick it up). */
        val listStyleType: String?,
        /** Resolved inherited CSS foreground, packed as `0xAARRGGBB`. */
        val foregroundColorArgb: Int?,
        /** This element's own computed (non-inherited) box background. */
        val backgroundColorArgb: Int?,
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
    private val fontFaceKeys = mutableSetOf<String>()
    private val rules = RuleIndex()
    private val firstLetterRules = RuleIndex()
    private val beforeRules = RuleIndex()
    private val afterRules = RuleIndex()
    private var orderCounter = 0
    private var ruleCount = 0
    private var stylesheetDeclarationCount = 0
    private val selectorMatchContext = SelectorMatchContext(MAX_SELECTOR_MATCH_OPERATIONS)
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
        val unresolved = mutableListOf<Element>()
        var cursor: Element? = element
        var inherited: Computed? = null
        while (true) {
            val current = cursor ?: break
            val cached = cache[current]
            if (cached != null) {
                inherited = cached
                break
            }
            unresolved.add(current)
            cursor = current.parent()?.takeIf { it.normalName() != "#root" }
        }
        for (index in unresolved.lastIndex downTo 0) {
            val current = unresolved[index]
            inherited = resolve(current, inherited).also { cache[current] = it }
        }
        return checkNotNull(cache[element])
    }

    /**
     * Drops per-element computed styles. Chapters are parsed one after
     * another with a shared resolver; clearing between chapters keeps the
     * cache from pinning every DOM node of the whole book in memory.
     */
    fun clearCache() = cache.clear()

    /**
     * Visible background contributed by [element] and its wrapper ancestors.
     * CSS backgrounds do not inherit, but an ancestor's painted box is still
     * visible behind every flattened native child.  [stopExclusive] lets a
     * table cell retain only tbody/tr/cell layers because the table layer is
     * painted separately by the renderer.
     */
    fun visualBackground(
        element: Element,
        stopExclusive: Element? = null,
    ): Int? {
        val layers = ArrayList<Int>(4)
        var cursor: Element? = element
        while (cursor != null && cursor !== stopExclusive && cursor.normalName() != "#root") {
            computed(cursor).backgroundColorArgb?.let { layers += it }
            cursor = cursor.parent()
        }
        var result = 0
        for (index in layers.lastIndex downTo 0) {
            result = compositeArgb(layers[index], result)
        }
        return result.takeIf { (it ushr 24) != 0 }
    }

    /**
     * The `::first-letter` style targeting [element], or null. Used for
     * drop caps: `isDropCap` is set for floated or noticeably enlarged caps.
     */
    fun firstLetter(element: Element): FirstLetterStyle? {
        if (firstLetterRules.size == 0) return null
        val merged = cascade(firstLetterRules, element, inline = null) ?: return null

        var scale = 1f
        var bold: Boolean? = null
        var italic: Boolean? = null
        var floatSide: String? = null
        var family: String? = null
        val inherited = computed(element)
        var direction = inherited.direction
        var foreground = merged["color"]?.value?.let {
            resolveForeground(it, inherited.foregroundColorArgb)
        } ?: inherited.foregroundColorArgb
        var background = merged["background-color"]?.value?.let {
            resolveBackground(it, inherited.backgroundColorArgb, foreground)
        }
        for ((property, declaration) in merged) {
            val value = declaration.value.trim().lowercase()
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

                "float" -> if (value == "left" || value == "right") floatSide = value
                "font-family" -> firstFamilyName(value)?.let { family = it }
                "direction" -> if (value == "ltr" || value == "rtl") direction = value
                "color", "background-color" -> Unit
            }
        }
        val isDropCap = floatSide != null || scale >= 1.5f
        val plain = !isDropCap && scale in 0.99f..1.01f &&
            bold == null && italic == null && family == null &&
            foreground == inherited.foregroundColorArgb && background == null
        if (plain) return null
        return FirstLetterStyle(
            scale = scale.coerceIn(1f, 4f),
            bold = bold,
            italic = italic,
            isDropCap = isDropCap,
            fontFamily = family,
            foregroundColorArgb = foreground.takeIf { it != inherited.foregroundColorArgb },
            backgroundColorArgb = background,
            leftSide = floatSide != "right",
            direction = when (direction) {
                "ltr" -> BookTextDirection.LTR
                "rtl" -> BookTextDirection.RTL
                "auto" -> BookTextDirection.AUTO
                else -> null
            },
        )
    }

    /**
     * Converts a real leading floated text element into the same native model
     * used by `::first-letter`. KF8 does not reliably support that pseudo, so
     * publishers commonly emit `<span style="float:left">K</span>` instead.
     *
     * [sourceTextLength] is retained so rendering consumes exactly the text
     * already present in the paragraph rather than guessing another grapheme.
     */
    fun floatedTextInitial(
        element: Element,
        paragraph: Element,
        sourceTextLength: Int,
    ): FirstLetterStyle? {
        val own = computed(element)
        val side = own.floatSide ?: return null
        val base = computed(paragraph)
        val baseSize = base.fontSizeEm.coerceAtLeast(0.1f)

        // Computed text sizes are deliberately bounded for hostile books. A
        // legitimate drop cap may ask for 3.4em, though, so recover its exact
        // cascaded size before applying the drop-cap-specific 4em ceiling.
        val declaration = declarationsFor(element)["font-size"]
        val declaredAbsolute = declaration?.let {
            val original = it.value
            val raw = if ("var(" in original) {
                substituteVars(original, own.customProps)
            } else {
                original
            } ?: return@let null
            val normalized = raw.trim().lowercase()
            val value = it.fontPart?.let { part ->
                fontShorthandValues(normalized)?.value(part)
            } ?: if (it.fontPart != null) {
                return@let null
            } else {
                normalized
            }
            when (value) {
                "inherit", "unset" -> baseSize
                "initial" -> 1f
                else -> fontSizeFactor(value, baseSize)
            }
        }
        val scale = ((declaredAbsolute ?: own.fontSizeEm) / baseSize).coerceIn(1f, 4f)
        return FirstLetterStyle(
            scale = scale,
            bold = own.bold,
            italic = own.italic,
            isDropCap = true,
            fontFamily = own.fontFamilyName,
            foregroundColorArgb = own.foregroundColorArgb
                .takeIf { it != base.foregroundColorArgb },
            backgroundColorArgb = own.backgroundColorArgb,
            leftSide = side != "right",
            direction = when (own.direction) {
                "ltr" -> BookTextDirection.LTR
                "rtl" -> BookTextDirection.RTL
                "auto" -> BookTextDirection.AUTO
                else -> null
            },
            sourceTextLength = sourceTextLength,
        )
    }

    /** A `::before`/`::after` text run the book generates via CSS. */
    class GeneratedRun(
        val text: String,
        val italic: Boolean? = null,
        val bold: Boolean? = null,
        /** Font size relative to the element (1 = same). */
        val scale: Float = 1f,
        val foregroundColorArgb: Int? = null,
        val backgroundColorArgb: Int? = null,
    )

    /**
     * Static generated content for [element] (`::after` when [after]).
     * Only literal string `content` is honored — counters, `attr()`,
     * `url()` and quote keywords degrade to nothing, as before.
     */
    fun generated(element: Element, after: Boolean): GeneratedRun? {
        val pool = if (after) afterRules else beforeRules
        if (pool.size == 0) return null
        val merged = cascade(pool, element, inline = null) ?: return null
        val text = merged["content"]?.value?.let { parseContentValue(it) } ?: return null
        if (text.isBlank()) return null

        var italic: Boolean? = null
        var bold: Boolean? = null
        var scale = 1f
        val inherited = computed(element)
        var foreground = merged["color"]?.value?.let {
            resolveForeground(it, inherited.foregroundColorArgb)
        } ?: inherited.foregroundColorArgb
        var background = merged["background-color"]?.value?.let {
            resolveBackground(it, inherited.backgroundColorArgb, foreground)
        }
        for ((property, declaration) in merged) {
            val value = declaration.value.trim().lowercase()
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
                "color", "background-color" -> Unit
            }
        }
        return GeneratedRun(
            text,
            italic,
            bold,
            scale.coerceIn(0.5f, 2.5f),
            foreground.takeIf { it != inherited.foregroundColorArgb },
            background,
        )
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
    private fun declarationsFor(element: Element): Map<String, Declaration> {
        val inline = element.attr("style")
            .takeIf { it.isNotEmpty() }
            ?.let { parseDeclarations(it, stylesheet = false) }
        return cascade(rules, element, inline) ?: emptyMap()
    }

    /**
     * CSS 2.1 author-level cascade over the matching rules: normal sheet
     * declarations → normal inline → important sheet → important inline,
     * each layer sorted by (specificity, source order).
     */
    private fun cascade(
        pool: RuleIndex,
        element: Element,
        inline: Map<String, Declaration>?,
    ): Map<String, Declaration>? {
        val matched = ArrayList<Rule>(4)
        pool.visitCandidates(element) { rule ->
            if (selectorMatchContext.exhausted) return@visitCandidates false
            if (matches(rule.chain, rule.chain.lastIndex, element)) matched.add(rule)
            !selectorMatchContext.exhausted
        }
        if (matched.isEmpty() && inline.isNullOrEmpty()) return null
        matched.sortWith(compareBy({ it.specificity }, { it.order }))
        val merged = LinkedHashMap<String, Declaration>()
        fun putInCascadeOrder(property: String, declaration: Declaration) {
            // LinkedHashMap replacement keeps the old insertion slot. Moving
            // the winner lets equivalent logical/physical properties be
            // resolved in their actual final cascade order later.
            merged.remove(property)
            merged[property] = declaration
        }
        for (rule in matched) {
            for ((property, decl) in rule.declarations) {
                if (!decl.important) putInCascadeOrder(property, decl)
            }
        }
        inline?.forEach { (property, decl) ->
            if (!decl.important) putInCascadeOrder(property, decl)
        }
        for (rule in matched) {
            for ((property, decl) in rule.declarations) {
                if (decl.important) putInCascadeOrder(property, decl)
            }
        }
        inline?.forEach { (property, decl) ->
            if (decl.important) putInCascadeOrder(property, decl)
        }
        return merged
    }

    /** Selector matching memoizes every (element, compound) state. */
    private fun matches(chain: List<Compound>, index: Int, element: Element): Boolean =
        matches(chain, index, element, IdentityHashMap())

    private fun matches(
        chain: List<Compound>,
        index: Int,
        element: Element,
        memo: IdentityHashMap<Element, ByteArray>,
    ): Boolean {
        if (!selectorMatchContext.consume()) return false
        val states = memo.getOrPut(element) { ByteArray(chain.size) }
        when (states[index].toInt()) {
            1 -> return false
            2 -> return true
        }
        if (!chain[index].simple.matches(element, selectorMatchContext)) {
            states[index] = 1
            return false
        }
        val matched = if (index == 0) {
            true
        } else when (chain[index].combinator) {
            Combinator.CHILD -> {
                val parent = element.parent()
                parent != null && parent.normalName() != "#root" &&
                    matches(chain, index - 1, parent, memo)
            }

            Combinator.DESCENDANT -> {
                var ancestor = element.parent()
                while (ancestor != null && ancestor.normalName() != "#root") {
                    if (matches(chain, index - 1, ancestor, memo)) {
                        states[index] = 2
                        return true
                    }
                    ancestor = ancestor.parent()
                }
                false
            }

            Combinator.NEXT_SIBLING -> {
                val sibling = element.previousElementSibling()
                sibling != null && matches(chain, index - 1, sibling, memo)
            }

            Combinator.SUBSEQUENT_SIBLING -> {
                var sibling = element.previousElementSibling()
                while (sibling != null) {
                    if (matches(chain, index - 1, sibling, memo)) {
                        states[index] = 2
                        return true
                    }
                    sibling = sibling.previousElementSibling()
                }
                false
            }
        }
        states[index] = if (matched) 2 else 1
        return matched
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
            for ((property, declaration) in decl) {
                if (property.startsWith("--")) {
                    val value = declaration.value
                    own[property] = substituteVars(value, parentProps) ?: value
                }
            }
            customProps = own
        }

        var italic = parent?.italic
        var bold = parent?.bold
        val inheritedFontSize = parent?.fontSizeEm ?: 1f
        val tagFontScale = when (element.normalName()) {
            "h1" -> HeadingDefaults.scale(1)
            "h2" -> HeadingDefaults.scale(2)
            "h3" -> HeadingDefaults.scale(3)
            "h4" -> HeadingDefaults.scale(4)
            "h5" -> HeadingDefaults.scale(5)
            "h6" -> HeadingDefaults.scale(6)
            "small" -> 0.85f
            "big" -> 1.2f
            else -> 1f
        }
        // Author CSS overrides the UA/tag font-size instead of multiplying
        // on top of it. Resolve it against the inherited size before other
        // properties, since CSS computed values do not depend on declaration
        // order (`line-height` must see the final font size either way).
        val declaredFontSize = decl["font-size"]?.let { declaration ->
            val original = declaration.value
            val raw = if ("var(" in original) {
                substituteVars(original, customProps) ?: return@let null
            } else {
                original
            }
            val normalized = raw.trim().lowercase()
            val value = declaration.fontPart?.let { part ->
                fontShorthandValues(normalized)?.value(part)
            } ?: if (declaration.fontPart != null) {
                return@let null
            } else {
                normalized
            }
            when (value) {
                "inherit", "unset" -> inheritedFontSize
                "initial" -> 1f
                else -> fontSizeFactor(value, inheritedFontSize)
            }
        }
        val fontSize = (declaredFontSize ?: inheritedFontSize * tagFontScale)
            .coerceIn(0.5f, 3f)
        var align = parent?.textAlign
        var indent = parent?.textIndentEm
        var hidden = parent?.hidden ?: false
        var underline = parent?.underline ?: false
        var strike = parent?.strike ?: false
        var monospace = parent?.monospace ?: false
        var fontFamilyName = parent?.fontFamilyName
        var lineHeightNumber = parent?.lineHeightNumber
        var lineHeightAbsoluteEm = parent?.lineHeightAbsoluteEm
        var lineHeightMult = safeLineHeightMultiplier(
            when {
                lineHeightNumber != null -> lineHeightNumber
                lineHeightAbsoluteEm != null -> lineHeightAbsoluteEm / fontSize
                else -> null
            },
        )
        var hyphensAuto = parent?.hyphensAuto
        var direction = parent?.direction
        // `dir` is a presentational hint: authored CSS still wins below, but
        // descendants must inherit it even when no CSS direction exists.
        when (element.attr("dir").trim().lowercase()) {
            "ltr", "rtl", "auto" -> direction = element.attr("dir").trim().lowercase()
        }
        when (declarationValue(decl["direction"], customProps)?.trim()?.lowercase()) {
            "ltr", "rtl" -> direction = declarationValue(decl["direction"], customProps)
                ?.trim()?.lowercase()
            "initial", "revert", "revert-layer" -> direction = "ltr"
            "inherit", "unset" -> direction = parent?.direction
        }
        val hasLogicalBoxDeclaration = decl.keys.any {
            it == "margin-inline-start" || it == "margin-inline-end" ||
                it == "padding-inline-start" || it == "padding-inline-end"
        }
        val rtlLogicalAxis = when (direction) {
            "rtl" -> true
            "auto" -> hasLogicalBoxDeclaration && (firstStrongRtl(element.text()) ?: false)
            else -> false
        }
        var unicodeBidi: String? = null
        var listStyleType = parent?.listStyleType
        val declaredForeground = declarationValue(decl["color"], customProps)
            ?: element.attr("color").takeIf { it.isNotBlank() }
            ?: element.attr("text").takeIf {
                element.normalName() == "body" && it.isNotBlank()
            }
        val foregroundColorArgb = declaredForeground?.let {
            resolveForeground(it, parent?.foregroundColorArgb)
        } ?: parent?.foregroundColorArgb
        val declaredBackground = declarationValue(decl["background-color"], customProps)
            ?: element.attr("bgcolor").takeIf { it.isNotBlank() }
        val backgroundColorArgb = declaredBackground?.let {
            resolveBackground(it, parent?.backgroundColorArgb, foregroundColorArgb)
        }
        var superScript = false
        var subScript = false
        var marginStartEm = 0f
        var marginStartFrac = 0f
        var marginEndEm = 0f
        var marginEndFrac = 0f
        var marginInlineStartEm = 0f
        var marginInlineStartFrac = 0f
        var marginInlineEndEm = 0f
        var marginInlineEndFrac = 0f
        var paddingStartEm = 0f
        var paddingStartFrac = 0f
        var paddingEndEm = 0f
        var paddingEndFrac = 0f
        var paddingInlineStartEm = 0f
        var paddingInlineStartFrac = 0f
        var paddingInlineEndEm = 0f
        var paddingInlineEndFrac = 0f
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
            "h1", "h2", "h3", "h4", "h5", "h6" -> bold = true
        }

        for ((property, declaration) in decl) {
            if (property.startsWith("--")) continue
            val declaredValue = declaration.value
            // var() substitution: unresolvable without a fallback → the
            // declaration is invalid at computed-value time and dropped.
            val rawValue = if ("var(" in declaredValue) {
                substituteVars(declaredValue, customProps) ?: continue
            } else {
                declaredValue
            }
            val normalizedValue = rawValue.trim().lowercase()
            val value = when {
                declaration.marginSide != null ->
                    marginShorthandValue(normalizedValue, declaration.marginSide)
                declaration.fontPart != null ->
                    fontShorthandValues(normalizedValue)?.value(declaration.fontPart)
                else -> normalizedValue
            }
            if (value == null) continue
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

                // Resolved above so declaration order cannot affect it.
                "font-size" -> Unit

                // Resolved above: currentColor/background-color must see the
                // final foreground regardless of declaration source order.
                "color", "background-color" -> Unit

                "font-family" -> when (value) {
                    "inherit", "unset" -> Unit
                    "initial", "revert", "revert-layer" -> {
                        monospace = false
                        fontFamilyName = null
                    }
                    else -> {
                        monospace = value.contains("monospace")
                        firstFamilyName(value)?.let { fontFamilyName = it }
                    }
                }

                "line-height" -> when (value) {
                    // line-height is inherited, so unset has inherit semantics.
                    "inherit", "unset" -> Unit
                    "normal", "initial" -> {
                        lineHeightNumber = null
                        lineHeightAbsoluteEm = null
                        lineHeightMult = null
                    }

                    else -> lineHeightValueOf(value, fontSize)?.let { resolved ->
                        lineHeightNumber = resolved.number
                        lineHeightAbsoluteEm = resolved.absoluteEm
                        lineHeightMult = safeLineHeightMultiplier(resolved.multiplier(fontSize))
                    }
                }

                "hyphens", "-webkit-hyphens", "-epub-hyphens", "-moz-hyphens" -> when (value) {
                    "auto" -> hyphensAuto = true
                    "none", "manual" -> hyphensAuto = false
                }

                "direction" -> when (value) {
                    "ltr", "rtl" -> direction = value
                }

                "unicode-bidi" -> when (value) {
                    "normal", "embed", "isolate", "bidi-override",
                    "isolate-override", "plaintext" -> unicodeBidi = value
                    "inherit" -> unicodeBidi = parent?.unicodeBidi
                    "initial", "unset", "revert", "revert-layer" -> unicodeBidi = null
                }

                "adobe-hyphenate" -> when (value) {
                    "explicit", "none" -> hyphensAuto = false
                    "auto" -> hyphensAuto = true
                }

                "text-align" -> when (value) {
                    "center" -> align = "center"
                    "right" -> align = "right"
                    "left" -> align = "left"
                    "end" -> align = "end"
                    "start" -> align = "start"
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

                "margin-left" -> {
                    // Physical left competes with logical start in LTR and
                    // logical end in RTL; the later cascaded winner clears
                    // the earlier representation instead of being added.
                    if (rtlLogicalAxis) {
                        marginInlineEndEm = 0f
                        marginInlineEndFrac = 0f
                    } else {
                        marginInlineStartEm = 0f
                        marginInlineStartFrac = 0f
                    }
                    if (value == "auto") autoStart = true else {
                        lengthEm(value)?.let { marginStartEm = it }
                        percentFrac(value)?.let { marginStartFrac = it }
                    }
                }

                "margin-right" -> {
                    if (rtlLogicalAxis) {
                        marginInlineStartEm = 0f
                        marginInlineStartFrac = 0f
                    } else {
                        marginInlineEndEm = 0f
                        marginInlineEndFrac = 0f
                    }
                    if (value == "auto") autoEnd = true else {
                        lengthEm(value)?.let { marginEndEm = it }
                        percentFrac(value)?.let { marginEndFrac = it }
                    }
                }

                "margin-inline-start" -> {
                    if (rtlLogicalAxis) {
                        marginEndEm = 0f
                        marginEndFrac = 0f
                    } else {
                        marginStartEm = 0f
                        marginStartFrac = 0f
                    }
                    if (value == "auto") autoStart = true else {
                        lengthEm(value)?.let { marginInlineStartEm = it }
                        percentFrac(value)?.let { marginInlineStartFrac = it }
                    }
                }

                "margin-inline-end" -> {
                    if (rtlLogicalAxis) {
                        marginStartEm = 0f
                        marginStartFrac = 0f
                    } else {
                        marginEndEm = 0f
                        marginEndFrac = 0f
                    }
                    if (value == "auto") autoEnd = true else {
                        lengthEm(value)?.let { marginInlineEndEm = it }
                        percentFrac(value)?.let { marginInlineEndFrac = it }
                    }
                }

                "margin-top" -> lengthEm(value)?.let { marginTopEm = it }
                "margin-bottom" -> lengthEm(value)?.let { marginBottomEm = it }

                "padding-left" -> {
                    if (rtlLogicalAxis) {
                        paddingInlineEndEm = 0f
                        paddingInlineEndFrac = 0f
                    } else {
                        paddingInlineStartEm = 0f
                        paddingInlineStartFrac = 0f
                    }
                    lengthEm(value)?.let { paddingStartEm = it }
                    percentFrac(value)?.let { paddingStartFrac = it }
                }
                "padding-right" -> {
                    if (rtlLogicalAxis) {
                        paddingInlineStartEm = 0f
                        paddingInlineStartFrac = 0f
                    } else {
                        paddingInlineEndEm = 0f
                        paddingInlineEndFrac = 0f
                    }
                    lengthEm(value)?.let { paddingEndEm = it }
                    percentFrac(value)?.let { paddingEndFrac = it }
                }
                "padding-inline-start" -> {
                    if (rtlLogicalAxis) {
                        paddingEndEm = 0f
                        paddingEndFrac = 0f
                    } else {
                        paddingStartEm = 0f
                        paddingStartFrac = 0f
                    }
                    lengthEm(value)?.let { paddingInlineStartEm = it }
                    percentFrac(value)?.let { paddingInlineStartFrac = it }
                }
                "padding-inline-end" -> {
                    if (rtlLogicalAxis) {
                        paddingStartEm = 0f
                        paddingStartFrac = 0f
                    } else {
                        paddingEndEm = 0f
                        paddingEndFrac = 0f
                    }
                    lengthEm(value)?.let { paddingInlineEndEm = it }
                    percentFrac(value)?.let { paddingInlineEndFrac = it }
                }
            }
        }

        return Computed(
            customProps = customProps,
            italic = italic,
            bold = bold,
            fontSizeEm = fontSize,
            textAlign = align,
            textIndentEm = indent,
            hidden = hidden,
            marginStartEm = (marginStartEm + paddingStartEm).coerceIn(0f, 8f),
            marginStartFrac = (marginStartFrac + paddingStartFrac).coerceIn(0f, 0.45f),
            marginEndEm = (marginEndEm + paddingEndEm).coerceIn(0f, 8f),
            marginEndFrac = (marginEndFrac + paddingEndFrac).coerceIn(0f, 0.45f),
            marginInlineStartEm = (marginInlineStartEm + paddingInlineStartEm)
                .coerceIn(0f, 8f),
            marginInlineStartFrac = (marginInlineStartFrac + paddingInlineStartFrac)
                .coerceIn(0f, 0.45f),
            marginInlineEndEm = (marginInlineEndEm + paddingInlineEndEm)
                .coerceIn(0f, 8f),
            marginInlineEndFrac = (marginInlineEndFrac + paddingInlineEndFrac)
                .coerceIn(0f, 0.45f),
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
            lineHeightNumber = lineHeightNumber,
            lineHeightAbsoluteEm = lineHeightAbsoluteEm,
            hyphensAuto = hyphensAuto,
            direction = direction,
            unicodeBidi = unicodeBidi,
            listStyleType = listStyleType,
            foregroundColorArgb = foregroundColorArgb,
            backgroundColorArgb = backgroundColorArgb,
            pageBreakBefore = pageBreakBefore,
            floatSide = floatSide,
            widthFrac = widthFrac,
            widthEm = widthEm,
            heightEm = heightEm,
        )
    }

    private fun declarationValue(
        declaration: Declaration?,
        customProps: Map<String, String>,
    ): String? {
        val value = declaration?.value ?: return null
        return (if ("var(" in value) substituteVars(value, customProps) else value)
            ?.trim()
            ?.lowercase()
    }

    /** First-strong direction used only to map logical CSS box sides for dir=auto. */
    private fun firstStrongRtl(text: String): Boolean? {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            }
            offset += Character.charCount(codePoint)
        }
        return null
    }

    private fun resolveForeground(value: String, inherited: Int?): Int? =
        when (value.trim().lowercase()) {
            "inherit", "unset", "currentcolor" -> inherited
            "initial", "revert", "revert-layer" -> BLACK_ARGB
            else -> CssColor.parse(value)
        }

    private fun resolveBackground(value: String, inherited: Int?, current: Int?): Int? =
        when (value.trim().lowercase()) {
            "inherit" -> inherited
            "currentcolor" -> current
            "initial", "unset", "revert", "revert-layer" -> null
            else -> CssColor.parse(value)
        }

    private fun compositeArgb(foreground: Int, background: Int): Int {
        val fa = (foreground ushr 24) and 0xFF
        if (fa == 0) return background
        if (fa == 0xFF) return foreground
        val ba = (background ushr 24) and 0xFF
        val outA = fa + (ba * (255 - fa) + 127) / 255
        if (outA == 0) return 0
        fun channel(shift: Int): Int {
            val fc = (foreground ushr shift) and 0xFF
            val bc = (background ushr shift) and 0xFF
            val premul = fc * fa + (bc * ba * (255 - fa) + 127) / 255
            return (premul + outA / 2) / outA
        }
        return (outA shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
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

    /** Absolute factor from the reader root for a font-size [value], or null. */
    private fun fontSizeFactor(value: String, inherited: Float): Float? = when (value) {
        "medium" -> 1f
        "small" -> 0.9f
        "x-small" -> 0.75f
        "xx-small" -> 0.6f
        "large" -> 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        "smaller" -> inherited * 0.85f
        "larger" -> inherited * 1.2f
        else -> {
            if (CssCalc.isMath(value)) {
                CssCalc.eval(value, CssCalc.Ctx(inherited, 1f, 1f / 16f, inherited / 100f))
                    ?.takeIf { it > 0f }
            } else {
                val number = leadingNumber(value) ?: return null
                when (unitOf(value)) {
                    "em" -> inherited * number
                    "rem" -> number
                    "%" -> inherited * number / 100f
                    "px" -> number / 16f
                    "pt" -> number / 12f
                    "pc" -> number
                    "in" -> number * 6f
                    "cm" -> number * 2.3622f
                    "mm" -> number * 0.23622f
                    "ch", "ex" -> inherited * number * 0.5f
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

    private class LineHeightValue(
        val number: Float? = null,
        val absoluteEm: Float? = null,
    ) {
        fun multiplier(fontSizeEm: Float): Float? =
            number ?: absoluteEm?.div(fontSizeEm.coerceAtLeast(0.1f))
    }

    /**
     * CSS computed line-height, preserving the distinction inheritance needs:
     * a unitless number remains a number, while %, em and every other length
     * become an absolute root-em height at the declaring element.
     */
    private fun lineHeightValueOf(value: String, fontSizeEm: Float): LineHeightValue? {
        if (CssCalc.isMath(value)) {
            val isLength = lineHeightLengthUnitRegex.containsMatchIn(value)
            val evaluated = if (isLength) {
                CssCalc.eval(
                    value,
                    CssCalc.Ctx(
                        emUnit = fontSizeEm,
                        remUnit = 1f,
                        pxUnit = 1f / 16f,
                        percentUnit = fontSizeEm / 100f,
                    ),
                )
            } else {
                CssCalc.eval(
                    value,
                    CssCalc.Ctx(1f, 1f, 1f / 16f, null),
                    acceptScalar = true,
                )
            }?.takeIf { it.isFinite() && it > 0f } ?: return null
            return if (isLength) {
                LineHeightValue(absoluteEm = evaluated)
            } else {
                LineHeightValue(number = evaluated)
            }
        }

        val number = leadingNumber(value) ?: return null
        if (!number.isFinite() || number <= 0f) return null
        val resolved = when (unitOf(value)) {
            "" -> LineHeightValue(number = number)
            "%" -> LineHeightValue(absoluteEm = fontSizeEm * number / 100f)
            "em" -> LineHeightValue(absoluteEm = fontSizeEm * number)
            "rem" -> LineHeightValue(absoluteEm = number)
            "px" -> LineHeightValue(absoluteEm = number / 16f)
            "pt" -> LineHeightValue(absoluteEm = number / 12f)
            "pc" -> LineHeightValue(absoluteEm = number)
            "in" -> LineHeightValue(absoluteEm = number * 6f)
            "cm" -> LineHeightValue(absoluteEm = number * 2.3622f)
            "mm" -> LineHeightValue(absoluteEm = number * 0.23622f)
            "q" -> LineHeightValue(absoluteEm = number * 0.059055f)
            "ch", "ex" -> LineHeightValue(absoluteEm = fontSizeEm * number * 0.5f)
            "vw", "vmin" -> LineHeightValue(absoluteEm = number * 0.30f)
            "vh", "vmax" -> LineHeightValue(absoluteEm = number * 0.50f)
            else -> null
        }
        return resolved?.takeIf {
            it.number?.isFinite() != false && it.absoluteEm?.isFinite() != false
        }
    }

    /**
     * The native paginator cannot safely consume an unbounded multiplier.
     * Keep a wider envelope than the old 1..2.4 clamp because valid computed
     * absolute line-heights can be below 1 or reach 3 on a resized child.
     */
    private fun safeLineHeightMultiplier(value: Float?): Float? =
        value?.takeIf { it.isFinite() }?.coerceIn(0.5f, 4f)

    private fun listTypeKeyword(value: String): String? = when (value) {
        "disc", "circle", "square", "none", "decimal",
        "lower-alpha", "lower-latin", "upper-alpha", "upper-latin",
        "lower-roman", "upper-roman",
        -> value

        else -> null
    }

    // ------------------------------------------------------------- parsing

    private fun parseSheetText(sheetText: String, baseDir: String, groupDepth: Int = 0) {
        if (groupDepth > MAX_GROUP_RULE_DEPTH || ruleCount >= MAX_RULES ||
            stylesheetDeclarationCount >= MAX_STYLESHEET_DECLARATIONS
        ) {
            return
        }
        val css = stripComments(sheetText)
        var index = 0
        while (index < css.length) {
            if (ruleCount >= MAX_RULES ||
                stylesheetDeclarationCount >= MAX_STYLESHEET_DECLARATIONS
            ) {
                return
            }
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
                        parseSheetText(
                            css.substring(braceOpen + 1, bodyEnd),
                            baseDir,
                            groupDepth + 1,
                        )
                    }
                    index = bodyEnd + 1
                } else if (selectorText.startsWith("@font-face", ignoreCase = true)) {
                    val braceClose = css.indexOf('}', braceOpen)
                    if (braceClose < 0) break
                    // @font-face is a CSS rule too: count it against the
                    // global rule ceiling even when the face output cap has
                    // already been reached.
                    ruleCount++
                    if (mutableFontFaces.size < MAX_FONT_FACES) {
                        parseFontFace(
                            parseDeclarations(
                                css.substring(braceOpen + 1, braceClose),
                                stylesheet = true,
                            ),
                            baseDir,
                        )
                    }
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
            val declarations = parseDeclarations(
                css.substring(braceOpen + 1, braceClose),
                stylesheet = true,
            )
            if (declarations.isNotEmpty()) {
                for (selector in selectorSlices(selectorText)) {
                    if (ruleCount >= MAX_RULES) return
                    val parsed = parseSelector(selector.trim()) ?: continue
                    val rule = Rule(parsed.chain, parsed.specificity, orderCounter++, declarations)
                    val index = when (parsed.pseudoElement) {
                        PseudoElement.FIRST_LETTER -> firstLetterRules
                        PseudoElement.BEFORE -> beforeRules
                        PseudoElement.AFTER -> afterRules
                        null -> rules
                    }
                    index.add(rule)
                    ruleCount++
                }
            }
            index = braceClose + 1
        }
    }

    /** Bounded comma-group scanner; commas inside strings/functions/attrs do not split. */
    private fun selectorSlices(text: String): List<String> {
        val result = ArrayList<String>(minOf(MAX_SELECTORS_PER_RULE, 8))
        var start = 0
        var quote: Char? = null
        var parentheses = 0
        var brackets = 0
        for (index in text.indices) {
            val char = text[index]
            when {
                quote != null -> if (char == quote && !isEscaped(text, index)) quote = null
                char == '\'' || char == '"' -> quote = char
                char == '(' -> parentheses++
                char == ')' && parentheses > 0 -> parentheses--
                char == '[' -> brackets++
                char == ']' && brackets > 0 -> brackets--
                char == ',' && parentheses == 0 && brackets == 0 -> {
                    result.add(text.substring(start, index))
                    if (result.size >= MAX_SELECTORS_PER_RULE) return result
                    start = index + 1
                }
            }
        }
        if (result.size < MAX_SELECTORS_PER_RULE) result.add(text.substring(start))
        return result
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
        val bold = weight == "bold" || (weight?.toIntOrNull() ?: 400) >= 600
        val italic = style?.startsWith("italic") == true || style?.startsWith("oblique") == true
        val key = listOf(family.lowercase(), url, baseDir, bold.toString(), italic.toString())
            .joinToString("\u0000")
        if (!fontFaceKeys.add(key) || mutableFontFaces.size >= MAX_FONT_FACES) return
        mutableFontFaces.add(FontFace(
            family = family,
            src = url,
            baseDir = baseDir,
            bold = bold,
            italic = italic,
        ))
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
        if (text.isEmpty() || text.length > MAX_SELECTOR_CHARS) return null
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
                    if (chain.size >= MAX_SELECTOR_COMPOUNDS ||
                        classes.size + attrs.size + pseudos.size > MAX_SIMPLE_COMPONENTS
                    ) {
                        return null
                    }
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
        if (text.isEmpty() || text.length > MAX_SELECTOR_CHARS || text.any { it.isWhitespace() }) {
            return null
        }
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
        if (classes.size + attrs.size > MAX_SIMPLE_COMPONENTS) return null
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

    private fun parseDeclarations(
        text: String,
        stylesheet: Boolean,
    ): Map<String, Declaration> {
        val out = LinkedHashMap<String, Declaration>()
        val sourceLimit = if (stylesheet) {
            minOf(
                MAX_DECLARATIONS_PER_BLOCK,
                MAX_STYLESHEET_DECLARATIONS - stylesheetDeclarationCount,
            )
        } else {
            MAX_DECLARATIONS_PER_BLOCK
        }
        if (sourceLimit <= 0) return out

        for (declaration in declarationSlices(text, sourceLimit)) {
            if (stylesheet) stylesheetDeclarationCount++
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
                val candidates = when (property) {
                    "margin" -> {
                        if (splitTopLevelWhitespace(value).size !in 1..4) continue
                        listOf(
                            "margin-top" to Declaration(value, important, MarginSide.TOP),
                            "margin-right" to Declaration(value, important, MarginSide.RIGHT),
                            "margin-bottom" to Declaration(value, important, MarginSide.BOTTOM),
                            "margin-left" to Declaration(value, important, MarginSide.LEFT),
                        )
                    }

                    "margin-inline" -> {
                        if (splitTopLevelWhitespace(value).size !in 1..2) continue
                        listOf(
                            "margin-inline-start" to Declaration(
                                value, important, MarginSide.INLINE_START,
                            ),
                            "margin-inline-end" to Declaration(
                                value, important, MarginSide.INLINE_END,
                            ),
                        )
                    }

                    "padding-inline" -> {
                        if (splitTopLevelWhitespace(value).size !in 1..2) continue
                        listOf(
                            "padding-inline-start" to Declaration(
                                value, important, MarginSide.INLINE_START,
                            ),
                            "padding-inline-end" to Declaration(
                                value, important, MarginSide.INLINE_END,
                            ),
                        )
                    }

                    "font" -> fontShorthandDeclarations(value, important) ?: continue
                    else -> listOf(property to Declaration(value, important))
                }
                // Shorthands are admitted atomically: never leave a partial
                // reset merely because the per-block output cap was reached.
                val newKeys = candidates.asSequence()
                    .map { it.first }
                    .distinct()
                    .count { it !in out }
                if (out.size + newKeys > MAX_DECLARATIONS_PER_BLOCK) break
                candidates.forEach { (name, candidate) ->
                    putDeclaration(out, name, candidate)
                }
            }
        }
        return out
    }

    /**
     * Bounded declaration scanner. Unlike `split(';')`, this neither allocates
     * attacker-controlled numbers of substrings nor cuts quoted/function
     * values at an embedded semicolon.
     */
    private fun declarationSlices(text: String, limit: Int): List<String> {
        val result = ArrayList<String>(minOf(limit, 16))
        var start = 0
        var quote: Char? = null
        var parentheses = 0
        var index = 0
        while (index <= text.length && result.size < limit) {
            val atEnd = index == text.length
            val char = if (atEnd) ';' else text[index]
            when {
                quote != null -> if (char == quote && !isEscaped(text, index)) quote = null
                char == '\'' || char == '"' -> quote = char
                char == '(' -> parentheses++
                char == ')' && parentheses > 0 -> parentheses--
                char == ';' && parentheses == 0 -> {
                    val declaration = text.substring(start, index).trim()
                    if (declaration.isNotEmpty()) result.add(declaration)
                    start = index + 1
                }
            }
            index++
        }
        return result
    }

    /** Same-block declarations still run the importance/source-order cascade. */
    private fun putDeclaration(
        out: MutableMap<String, Declaration>,
        property: String,
        candidate: Declaration,
    ) {
        val previous = out[property]
        if (previous == null || candidate.important || !previous.important) {
            out.remove(property)
            out[property] = candidate
        }
    }

    private data class FontShorthandValues(
        val style: String,
        val weight: String,
        val size: String,
        val lineHeight: String,
        val family: String,
    ) {
        fun value(part: FontPart): String = when (part) {
            FontPart.STYLE -> style
            FontPart.WEIGHT -> weight
            FontPart.SIZE -> size
            FontPart.LINE_HEIGHT -> lineHeight
            FontPart.FAMILY -> family
        }
    }

    private fun fontShorthandDeclarations(
        value: String,
        important: Boolean,
    ): List<Pair<String, Declaration>>? {
        val parsed = fontShorthandValues(value)
        if (parsed != null) {
            return listOf(
                "font-style" to Declaration(parsed.style, important),
                "font-weight" to Declaration(parsed.weight, important),
                "font-size" to Declaration(parsed.size, important),
                "line-height" to Declaration(parsed.lineHeight, important),
                "font-family" to Declaration(parsed.family, important),
            )
        }
        if (!value.contains("var(", ignoreCase = true)) return null

        // A custom property may supply the entire shorthand. Keep the raw
        // value until computed-value substitution, but expose every longhand
        // key now so it participates in the ordinary cascade.
        return listOf(
            "font-style" to FontPart.STYLE,
            "font-weight" to FontPart.WEIGHT,
            "font-size" to FontPart.SIZE,
            "line-height" to FontPart.LINE_HEIGHT,
            "font-family" to FontPart.FAMILY,
        ).map { (property, part) ->
            property to Declaration(value, important, fontPart = part)
        }
    }

    private fun fontShorthandValues(raw: String): FontShorthandValues? {
        val value = raw.trim()
        when (value.lowercase()) {
            "inherit", "unset" -> return FontShorthandValues(
                "inherit", "inherit", "inherit", "inherit", "inherit",
            )
            "initial", "revert", "revert-layer" -> return FontShorthandValues(
                "normal", "normal", "medium", "normal", "initial",
            )
            "caption", "icon", "menu", "message-box", "small-caption", "status-bar" ->
                return FontShorthandValues("normal", "normal", "medium", "normal", "sans-serif")
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
            if (fontSizeFactor(lower, 1f) != null) {
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
        return leadingNumber(value) != null &&
            (value.endsWith("deg") || value.endsWith("grad") ||
                value.endsWith("rad") || value.endsWith("turn"))
    }

    /** Whitespace tokenizer that exposes only a top-level font `/`. */
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

    private fun marginShorthandValue(value: String, side: MarginSide): String? {
        val parts = splitTopLevelWhitespace(value)
        if (parts.size !in 1..4) return null
        val top = parts[0]
        val right = parts.getOrElse(1) { top }
        val bottom = parts.getOrElse(2) { top }
        val left = parts.getOrElse(3) { right }
        return when (side) {
            MarginSide.TOP -> top
            MarginSide.RIGHT -> right
            MarginSide.BOTTOM -> bottom
            MarginSide.LEFT -> left
            MarginSide.INLINE_START -> parts[0]
            MarginSide.INLINE_END -> parts.getOrElse(1) { parts[0] }
        }
    }

    /** Splits CSS values on whitespace, preserving strings and functions. */
    private fun splitTopLevelWhitespace(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = -1
        var quote: Char? = null
        var parentheses = 0
        var index = 0
        while (index < value.length) {
            val char = value[index]
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
                char.isWhitespace() && parentheses == 0 -> {
                    if (start >= 0) {
                        result += value.substring(start, index)
                        start = -1
                    }
                }
                else -> if (start < 0) start = index
            }
            index++
        }
        if (start >= 0) result += value.substring(start)
        return result
    }

    private fun isEscaped(value: String, index: Int): Boolean {
        var cursor = index - 1
        var backslashes = 0
        while (cursor >= 0 && value[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
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
        const val BLACK_ARGB: Int = -0x1000000
        const val MAX_GROUP_RULE_DEPTH = 64
        const val MAX_RULES = 16_384
        const val MAX_FONT_FACES = 512
        const val MAX_STYLESHEET_DECLARATIONS = 131_072
        const val MAX_DECLARATIONS_PER_BLOCK = 512
        const val MAX_SELECTORS_PER_RULE = 256
        const val MAX_SELECTOR_CHARS = 16_384
        const val MAX_SELECTOR_COMPOUNDS = 64
        const val MAX_SIMPLE_COMPONENTS = 64
        const val MAX_UNIVERSAL_RULES = 1_024
        const val MAX_RULES_PER_INDEX_BUCKET = 4_096
        const val MAX_SELECTOR_MATCH_OPERATIONS = 5_000_000
        val commentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val whitespaceRegex = Regex("""\s+""")
        // ".5em" is as common as "0.5em" in publisher CSS — without the
        // leading-dot branch the match starts at "5" and the value comes
        // out ten times too large.
        val numberRegex = Regex("""-?(?:\d+(?:\.\d+)?|\.\d+)""")
        val lineHeightLengthUnitRegex = Regex(
            """(?i)(?:%|(?<=[0-9.])(?:rem|em|px|pt|pc|in|cm|mm|q|ch|ex|vw|vh|vmin|vmax)(?=[\s),*/+\-]|$))""",
        )
        val FONT_STRETCH_KEYWORDS = setOf(
            "ultra-condensed", "extra-condensed", "condensed", "semi-condensed",
            "semi-expanded", "expanded", "extra-expanded", "ultra-expanded",
        )
        val fontUrlRegex = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")
        val caseFlagRegex = Regex("""^(['"].*['"])\s+[iIsS]$""")
        val nthRegex = Regex("""^([+-]?\d*)n([+-]\d+)?$""")
    }
}
