package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.encode
import com.example.frogreader.data.model.normalizeFontUnicodeRange
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.parser.RustCssValues.Declaration
import com.example.frogreader.data.parser.RustCssValues.lengthEm
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
 * forward/reverse `:nth-*`, child/type edges and bounded relative `:has()`; the
 * `::first-letter` pseudo-element (drop caps); standard (a,b,c) specificity
 * with `!important` and source-order cascade; `style=""` attributes;
 * inheritance of font and text properties; and the properties a paginated
 * book reader can honor (font-style/weight/size, text-align, text-indent,
 * font variants, text-transform, visibility, margins, display, vertical-align,
 * text-decoration, list-style-type, page boundaries and image dimension caps).
 *
 * Author foreground/background colors are retained and later gated by the
 * publisher-formatting switch; general positioning remains outside the native
 * model while far-offscreen accessibility copies stay visually hidden.
 * A selector using an
 * unsupported pseudo-class drops only itself, not its comma-group siblings —
 * stricter than the spec, kinder to books.
 */
class CssResolver(sheets: List<Sheet>, private val viewport: Viewport? = null) : AutoCloseable {

    /** Optional known CSS viewport. No parse-time guess is made for device size. */
    class Viewport(val widthPx: Float, val heightPx: Float? = null, val dark: Boolean? = null)

    /** One stylesheet text plus the directory its relative urls resolve from. */
    class Sheet(val text: String, val baseDir: String = "") {
        internal val syntax by lazy { RustCssSyntax.sheet(text) }
    }

    /** One `@font-face` rule: a face of [family] at [src] (url as written). */
    class FontFace(
        val family: String,
        val src: String,
        val baseDir: String,
        val bold: Boolean,
        val italic: Boolean,
        val weight: Int = if (bold) 700 else 400,
        val weightMin: Int = weight,
        val weightMax: Int = weight,
        val stretchMin: Float = 100f,
        val stretchMax: Float = stretchMin,
        val unicodeRange: String? = null,
    )

    // ------------------------------------------------------------- model

    private class Rule(
        val selector: RustCssSelectors.Compiled,
        val order: Int,
        val declarations: List<Pair<String, Declaration>>,
    )

    /** Applies the stylesheet limits before selectors are sent to Rust. */
    private class RulePool(val id: Int) {
        val entries = mutableListOf<Rule>()
        val size: Int get() = entries.size
        private val bucketSizes = mutableMapOf<String, Int>()

        fun add(rule: Rule) {
            val key = rule.selector.bucket.orEmpty()
            val cap = if (key.isEmpty()) MAX_UNIVERSAL_RULES else MAX_RULES_PER_INDEX_BUCKET
            val count = bucketSizes[key] ?: 0
            if (count < cap) {
                bucketSizes[key] = count + 1
                entries += rule
            }
        }
    }

    private class SelectorProgram(
        val rules: ByteArray,
        val absoluteKeys: Set<String>,
        val propertyIds: Map<String, Int>,
        val declarations: List<Pair<String, Declaration>>,
    )

    // Serialize the rule set once, sharing each declaration block across its selectors.
    // Rust returns indices into the original declarations to preserve their identity.
    private class SelectorProgramWriter {
        private val writer = CssMatchWireWriter(128 * 1024 * 1024)
        private val absoluteKeys = linkedSetOf<String>()

        fun encode(pools: List<RulePool>): SelectorProgram {
            val blockIds = IdentityHashMap<List<Pair<String, Declaration>>, Int>()
            val blocks = mutableListOf<List<Pair<String, Declaration>>>()
            for (pool in pools) {
                for (rule in pool.entries) {
                    if (!blockIds.containsKey(rule.declarations)) {
                        blockIds[rule.declarations] = blocks.size
                        blocks += rule.declarations
                    }
                }
            }
            val declarations = ArrayList<Pair<String, Declaration>>()
            val propertyIds = mutableMapOf<String, Int>()
            writer.int(blocks.size)
            for (block in blocks) {
                writer.int(block.size)
                for ((property, declaration) in block) {
                    writer.int(propertyIds.getOrPut(property) { propertyIds.size })
                    writer.int(if (declaration.important) 1 else 0)
                }
                declarations.addAll(block)
            }
            for (pool in pools) {
                writer.int(pool.size)
                for (rule in pool.entries) {
                    writer.int(blockIds.getValue(rule.declarations))
                    writer.int(rule.selector.specificity)
                    writer.int(rule.order)
                    writer.raw(rule.selector.chain)
                    absoluteKeys.addAll(rule.selector.absoluteKeys)
                }
            }
            return SelectorProgram(writer.toByteArray(), absoluteKeys, propertyIds, declarations)
        }
    }

    private class InlineStyle(
        val declarations: List<Pair<String, Declaration>>,
        propertyIds: Map<String, Int>,
    ) {
        // IDs below zero are unique to a block and cannot collide with sheet property IDs.
        val wire = declarationFields(declarations) { index, property ->
            propertyIds[property] ?: (-1 - index)
        }

        val winners by lazy(LazyThreadSafetyMode.NONE) {
            if (declarations.isEmpty()) null
            else cascadeDeclarations(RustCssMatch.cascadeInline(wire), emptyList(), declarations)
        }
    }

    /**
     * Absolute (inheritance-resolved) style of an element. Font size is
     * cumulative relative to the reader's base size (body = 1.0).
     */
    class Computed(
        val italic: Boolean?,
        val bold: Boolean?,
        val fontWeight: Int?,
        val fontSizeEm: Float,
        /** "center" | "left" | "right" | "end" | "start" | "justify" or null. */
        val textAlign: String?,
        /** First-line indent normalized to reader-root em, null when unspecified. */
        val textIndentEm: Float?,
        val hidden: Boolean,
        /** Own computed display value; null means the tag's initial display. */
        val display: String?,
        /** Own (non-inherited) box properties in root-em / width fractions. */
        /** Physical CSS left/right margins (legacy start/end names retained internally). */
        val marginStartEm: Float,
        val marginStartFrac: Float,
        val marginEndEm: Float,
        val marginEndFrac: Float,
        /** Logical CSS inline-axis margins. */
        val marginInlineStartEm: Float,
        val marginInlineStartFrac: Float,
        val marginInlineEndEm: Float,
        val marginInlineEndFrac: Float,
        val marginTopEm: Float,
        val marginBottomEm: Float,
        /** Own vertical margin declaration exists, including an explicit zero. */
        val marginTopSpecified: Boolean,
        val marginBottomSpecified: Boolean,
        /** Padding stays distinct from margins for container box painting. */
        val paddingStartEm: Float,
        val paddingStartFrac: Float,
        val paddingEndEm: Float,
        val paddingEndFrac: Float,
        val paddingInlineStartEm: Float,
        val paddingInlineStartFrac: Float,
        val paddingInlineEndEm: Float,
        val paddingInlineEndFrac: Float,
        val paddingTopEm: Float,
        val paddingBottomEm: Float,
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
        /** page-break-inside/break-inside: avoid. */
        val breakInsideAvoid: Boolean,
        /** float: "left"/"right" — text may wrap around this element. */
        val floatSide: String?,
        /** clear: "left"/"right"/"both" after logical-side resolution. */
        val clear: String?,
        /** width as a fraction of the container, when given in %. */
        val widthFrac: Float?,
        /** Width normalized to reader-root em, when given as a length. */
        val widthEm: Float?,
        /** Height normalized to reader-root em (ornament images). */
        val heightEm: Float? = null,
        /** Physical publisher border edges; null means no painted edge. */
        val borderTop: PublisherBorderSide? = null,
        val borderRight: PublisherBorderSide? = null,
        val borderBottom: PublisherBorderSide? = null,
        val borderLeft: PublisherBorderSide? = null,
        /** Own padding declaration exists, including an explicit zero. */
        val paddingSpecified: Boolean = false,
        /** Own border declaration exists, including `none`/zero width. */
        val borderSpecified: Boolean = false,
        /** Custom properties (`--x`) in scope, inherited like text styles. */
        val customProps: Map<String, String> = emptyMap(),
        /** Inherited OpenType features assembled from CSS font variants. */
        val fontFeatureSettings: String? = null,
        internal val fontVariantCaps: String? = null,
        internal val fontVariantNumeric: String? = null,
        internal val explicitFontFeatures: String? = null,
        internal val fontKerning: String? = null,
        internal val fontVariantLigatures: String? = null,
        val fontTypography: FontTypography = FontTypography(),
        /** Authored case conversion; source text is retained until mapping. */
        val textTransform: String? = null,
        /** Visibility retains layout; unlike display:none it can be overridden. */
        val visibilityHidden: Boolean = false,
        val maxWidthFrac: Float? = null,
        val maxWidthEm: Float? = null,
        val maxHeightEm: Float? = null,
        val maxHeightViewportFrac: Float? = null,
        val minHeightEm: Float? = null,
        val pageBreakAfter: Boolean = false,
        /** Own flex layout axes; none of these properties inherit. */
        val flexDirection: String = "row",
        val flexWrap: String = "nowrap",
        val justifyContent: String? = null,
        val alignItems: String? = null,
        /** Exact publisher dark-mode image filter supported by the native reader. */
        val imageInvert: Boolean = false,
        /** Unresolved currentColor uses the reader foreground at rendering time. */
        val backgroundUsesCurrentColor: Boolean = false,
        /** Exact viewport-relative minimum; offset is normalized reader-root em. */
        val minHeightViewportFrac: Float? = null,
        val minHeightOffsetEm: Float = 0f,
        val centerContentVertically: Boolean = false,
        val borderBox: Boolean = false,
        internal val nativeStyleId: Int = -1,
    ) {
        internal val encodedFontTypography: String = fontTypography.encode()

        internal val standardBoxStyle: PublisherBoxStyle? by lazy(LazyThreadSafetyMode.NONE) {
            buildPublisherBoxStyle(
                rtl = direction == "rtl",
                horizontalAlign = when {
                    floatSide != null -> PublisherBoxAlign.START
                    centeredBox -> PublisherBoxAlign.CENTER
                    else -> PublisherBoxAlign.START
                },
            )
        }

        internal fun buildPublisherBoxStyle(
            rtl: Boolean,
            horizontalAlign: PublisherBoxAlign,
        ): PublisherBoxStyle? = PublisherBoxStyle(
            marginTopEm = marginTopEm,
            marginRightEm = (marginEndEm + if (rtl) {
                marginInlineStartEm
            } else {
                marginInlineEndEm
            }).coerceIn(0f, MAX_BOX_INLINE_EM),
            marginRightFrac = (marginEndFrac + if (rtl) {
                marginInlineStartFrac
            } else {
                marginInlineEndFrac
            }).coerceIn(0f, MAX_BOX_INLINE_FRAC),
            marginBottomEm = marginBottomEm,
            marginLeftEm = (marginStartEm + if (rtl) {
                marginInlineEndEm
            } else {
                marginInlineStartEm
            }).coerceIn(0f, MAX_BOX_INLINE_EM),
            marginLeftFrac = (marginStartFrac + if (rtl) {
                marginInlineEndFrac
            } else {
                marginInlineStartFrac
            }).coerceIn(0f, MAX_BOX_INLINE_FRAC),
            paddingTopEm = paddingTopEm,
            paddingRightEm = (paddingEndEm + if (rtl) {
                paddingInlineStartEm
            } else {
                paddingInlineEndEm
            }).coerceIn(0f, MAX_BOX_INLINE_EM),
            paddingRightFrac = (paddingEndFrac + if (rtl) {
                paddingInlineStartFrac
            } else {
                paddingInlineEndFrac
            }).coerceIn(0f, MAX_BOX_INLINE_FRAC),
            paddingBottomEm = paddingBottomEm,
            paddingLeftEm = (paddingStartEm + if (rtl) {
                paddingInlineEndEm
            } else {
                paddingInlineStartEm
            }).coerceIn(0f, MAX_BOX_INLINE_EM),
            paddingLeftFrac = (paddingStartFrac + if (rtl) {
                paddingInlineEndFrac
            } else {
                paddingInlineStartFrac
            }).coerceIn(0f, MAX_BOX_INLINE_FRAC),
            backgroundColorArgb = backgroundColorArgb
                ?.takeIf { (it ushr 24) != 0 },
            borderTop = borderTop,
            borderRight = borderRight,
            borderBottom = borderBottom,
            borderLeft = borderLeft,
            widthFrac = widthFrac,
            widthEm = widthEm,
            shrinkToFit = display == "inline-block" &&
                widthEm == null && widthFrac == null,
            hangingIndentEm = textIndentEm?.takeIf { it < 0f }?.let { -it } ?: 0f,
            horizontalAlign = horizontalAlign,
            breakInsideAvoid = breakInsideAvoid,
            pageBreakBefore = pageBreakBefore,
            pageBreakAfter = pageBreakAfter,
            minHeightEm = minHeightEm,
            maxWidthEm = maxWidthEm,
            maxWidthFrac = maxWidthFrac,
            minHeightViewportFrac = minHeightViewportFrac,
            minHeightOffsetEm = minHeightOffsetEm,
            centerContentVertically = centerContentVertically,
            borderBox = borderBox,
        ).takeUnless(PublisherBoxStyle::isDefault)
    }

    private val mutableFontFaces = mutableListOf<FontFace>()
    private val fontFaceKeys = mutableSetOf<String>()
    private val rules = RulePool(0)
    private val firstLetterRules = RulePool(1)
    private val beforeRules = RulePool(2)
    private val afterRules = RulePool(3)
    private val rulePools = listOf(rules, firstLetterRules, beforeRules, afterRules)
    private val selectorProgram by lazy(LazyThreadSafetyMode.NONE) {
        SelectorProgramWriter().encode(rulePools)
    }
    private var selectorSession: RustCssMatch? = null
    private var computedSession: RustCssComputed? = null
    private var computedSessionResolutions = 0
    private var selectorBudget = MAX_SELECTOR_MATCH_OPERATIONS
    private var orderCounter = 0
    private var ruleCount = 0
    private var stylesheetDeclarationCount = 0
    private val cache = HashMap<Element, Computed>()
    /**
     * Resolution depends on the inherited style, tag defaults, presentational
     * attributes and the final ordered cascade. Selectors are still matched
     * for every element before consulting this cache: identical-looking
     * siblings can have different structural or contextual CSS rules.
     *
     * Computed and Declaration use reference identity. The declaration order
     * also matters when physical and logical properties address one side.
     * No key holds a DOM element, and the cache is bounded even for chapters
     * whose every paragraph has a unique style.
     */
    private data class SharedComputedKey(
        val parent: Computed?,
        val tag: String,
        val align: String,
        val direction: String,
        val foreground: String,
        val bodyForeground: String,
        val background: String,
        val declarations: List<Pair<String, Declaration>>,
    )

    private class WinnerKey(
        var ids: IntArray,
        var offset: Int,
        var hash: Int,
    ) {
        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WinnerKey) return false
            val a = ids
            val aOff = offset
            val aLen = a.size - aOff
            val b = other.ids
            val bOff = other.offset
            if (aLen != b.size - bOff) return false
            for (i in 0 until aLen) {
                if (a[aOff + i] != b[bOff + i]) return false
            }
            return true
        }
    }

    private class CascadedDeclarations(
        val map: Map<String, Declaration>,
        val pairs: List<Pair<String, Declaration>>,
    ) {
        companion object {
            val EMPTY = CascadedDeclarations(emptyMap(), emptyList())
        }
    }

    private val sharedComputed = HashMap<SharedComputedKey, Computed>()
    private val cascadeWinnerCache = HashMap<WinnerKey, CascadedDeclarations>()
    private val winnerProbe = WinnerKey(EMPTY_INT_ARRAY, 0, 0)
    private val inlineDeclarations = HashMap<String, InlineStyle>()
    private var inlineCacheKeyBytes = 0
    private val parsingNamespaces = mutableMapOf<String, String>()
    private val sourceSheets = sheets.toList()
    private var darkImageResolver: CssResolver? = null
    private val simpleMaxWidthBreakpoints = linkedSetOf<Float>()
    private val responsiveResolvers = mutableMapOf<Float, CssResolver>()

    internal val hasDisplayRules: Boolean
    internal val hasPageBreakBeforeRules: Boolean
    private val hasFlexCenteringRules: Boolean

    init {
        for (sheet in sheets) {
            if (!canAcceptStylesheetRules()) break
            parsingNamespaces.clear()
            addSheetRules(sheet.syntax, sheet.baseDir)
        }
        var displayRules = false
        var pageBreakBeforeRules = false
        var flexCenteringRules = false
        for (rule in rules.entries) {
            for ((property, _) in rule.declarations) {
                if (!displayRules && (property == "display" || property.startsWith("--"))) {
                    displayRules = true
                }
                if (!pageBreakBeforeRules &&
                    (property == "page-break-before" || property == "break-before" || property.startsWith("--"))
                ) {
                    pageBreakBeforeRules = true
                }
                if (!flexCenteringRules && (property == "align-items" || property.startsWith("--"))) {
                    flexCenteringRules = true
                }
                if (displayRules && pageBreakBeforeRules && flexCenteringRules) break
            }
            if (displayRules && pageBreakBeforeRules && flexCenteringRules) break
        }
        hasDisplayRules = displayRules
        hasPageBreakBeforeRules = pageBreakBeforeRules
        hasFlexCenteringRules = flexCenteringRules
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
            inherited = resolveShared(current, inherited).also { cache[current] = it }
        }
        return checkNotNull(cache[element])
    }

    /**
     * Drops per-element computed styles. Chapters are parsed one after
     * another with a shared resolver; clearing between chapters keeps the
     * cache from pinning every DOM node of the whole book in memory while
     * retaining DOM-free shared computed styles across chapters.
     */
    fun clearCache() {
        cache.clear()
        // Release the chapter snapshot and its structural selector memo.
        // The resolver keeps the remaining budget and bounded shared styles across chapters.
        selectorSession?.close()
        selectorSession = null
        if (sharedComputed.size >= MAX_SHARED_COMPUTED_STYLES ||
            computedSessionResolutions >= MAX_SHARED_COMPUTED_STYLES
        ) {
            sharedComputed.clear()
            cascadeWinnerCache.clear()
            computedSession?.close()
            computedSession = null
            computedSessionResolutions = 0
        }
        darkImageResolver?.clearCache()
        responsiveResolvers.values.forEach(CssResolver::clearCache)
    }

    override fun close() {
        clearCache()
        sharedComputed.clear()
        cascadeWinnerCache.clear()
        computedSession?.close()
        computedSession = null
        computedSessionResolutions = 0
        darkImageResolver?.close()
        responsiveResolvers.values.forEach(CssResolver::close)
    }

    internal val sharedComputedStyleCount: Int get() = sharedComputed.size
    internal val cascadeWinnerCacheSize: Int get() = cascadeWinnerCache.size
    internal val hasActiveComputedSession: Boolean get() = computedSession != null
    internal val hasActiveSelectorSession: Boolean get() = selectorSession != null
    internal val cachedElementCount: Int get() = cache.size

    private fun preindexedElement(element: Element): RustChapterMarkup.PreindexedElement? {
        val pre = element as? RustChapterMarkup.PreindexedElement ?: return null
        val doc = pre.owner ?: return null
        return if (!doc.treeMutated) pre else null
    }

    private fun styleAttrOf(element: Element): String {
        val pre = preindexedElement(element)
        if (pre != null) {
            val doc = pre.owner!!
            if ((doc.mutatedAttrs and RustChapterMarkup.MUTATED_STYLE) == 0 &&
                !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_STYLE)
            ) {
                return ""
            }
        }
        return element.attr("style")
    }

    private fun dirAttrOf(element: Element): String {
        val pre = preindexedElement(element)
        if (pre != null) {
            val doc = pre.owner!!
            if ((doc.mutatedAttrs and RustChapterMarkup.MUTATED_OTHER) == 0 &&
                !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_DIR)
            ) {
                return ""
            }
        }
        return element.attr("dir")
    }

    private fun hasPresentationalAttrs(element: Element): Boolean {
        val pre = preindexedElement(element) ?: return true
        val doc = pre.owner!!
        return (doc.mutatedAttrs and RustChapterMarkup.MUTATED_PRESENTATIONAL) != 0 ||
            pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL)
    }

    private fun resolveShared(element: Element, parent: Computed?): Computed {
        val cascaded = cascadedFor(element)
        val declared = cascaded.map
        val direction = dirAttrOf(element)
        // An auto direction can inspect this element's actual text while
        // resolving logical margins/padding. Text is deliberately absent
        // from the shared key, so keep that path element-specific.
        if (parent?.direction == "auto" || direction.trim().equals("auto", ignoreCase = true)) {
            return resolve(element, parent, declared)
        }
        val hasPresentational = hasPresentationalAttrs(element)
        val key = SharedComputedKey(
            parent = parent,
            tag = element.normalName(),
            align = if (hasPresentational) element.attr("align") else "",
            direction = direction,
            foreground = if (hasPresentational) element.attr("color") else "",
            bodyForeground = if (hasPresentational) element.attr("text") else "",
            background = if (hasPresentational) element.attr("bgcolor") else "",
            declarations = cascaded.pairs,
        )
        sharedComputed[key]?.let { return it }
        return resolve(element, parent, declared).also { resolved ->
            if (sharedComputed.size < MAX_SHARED_COMPUTED_STYLES) sharedComputed[key] = resolved
        }
    }

    /** Bounded simple max-width queries retained for native responsive tables. */
    val maxWidthBreakpointsCssPx: List<Float> get() = simpleMaxWidthBreakpoints.sortedDescending()

    /** Reuse one lazily parsed variant per authored breakpoint across chapter tables. */
    fun resolverAtMaxWidth(widthCssPx: Float): CssResolver? {
        if (widthCssPx !in simpleMaxWidthBreakpoints) return null
        return responsiveResolvers.getOrPut(widthCssPx) {
            CssResolver(sourceSheets, Viewport(widthPx = widthCssPx))
        }
    }

    /** Dark appearance is resolved lazily; the reading theme can change after parsing. */
    fun darkImageStyle(element: Element): Computed {
        if (viewport?.dark == true) return computed(element)
        val resolver = darkImageResolver ?: CssResolver(
            sourceSheets,
            Viewport(widthPx = Float.NaN, dark = true),
        ).also { darkImageResolver = it }
        return resolver.computed(element)
    }

    /**
     * Own CSS box decoration of [element] in renderer-ready physical axes.
     *
     * Text inheritance is intentionally absent. Logical inline margin and
     * padding declarations are mapped exactly once using the computed bidi
     * direction, then combined with the surviving physical declarations.
     * Null means the element contributes no retained native box styling.
     */
    fun publisherBoxStyle(element: Element): PublisherBoxStyle? {
        val value = computed(element)
        return publisherBoxStyle(element, value)
    }

    private fun publisherBoxStyle(element: Element, value: Computed): PublisherBoxStyle? {
        val horizontalAlign = publisherHorizontalAlign(element, value)
        val defaultAlign = when {
            value.floatSide != null -> PublisherBoxAlign.START
            value.centeredBox -> PublisherBoxAlign.CENTER
            else -> PublisherBoxAlign.START
        }
        if (value.direction != "auto" && horizontalAlign == defaultAlign) {
            return value.standardBoxStyle
        }
        val rtl = when (value.direction) {
            "rtl" -> true
            "auto" -> firstStrongRtl(element.text()) ?: false
            else -> false
        }
        return value.buildPublisherBoxStyle(rtl = rtl, horizontalAlign = horizontalAlign)
    }

    /**
     * Preserve the inline formatting position when a replaced element becomes
     * a standalone native reader leaf.
     *
     * In HTML, `text-align` belongs to the containing block, not to `<img>`
     * itself. The native mapper has to lift a large inline image out of its
     * line box, so without this bridge an authored `text-align:center` keeps
     * the 80% width but incorrectly places that narrower box at the left edge.
     * Explicit block display and floats keep their own CSS positioning rules;
     * auto inline margins continue to center any block via [Computed.centeredBox].
     */
    private fun publisherHorizontalAlign(
        element: Element,
        value: Computed,
    ): PublisherBoxAlign {
        if (value.floatSide != null) return PublisherBoxAlign.START
        if (value.centeredBox) return PublisherBoxAlign.CENTER
        val parent = element.parent()
            ?.takeIf { it.normalName() != "#root" }
            ?: return PublisherBoxAlign.START
        val isBlockAligned = (element.normalName() !in INLINE_REPLACED_TAGS && value.display != "inline-block") ||
            value.display in BLOCK_LEVEL_DISPLAY_VALUES
        if (isBlockAligned && !hasFlexCenteringRules && styleAttrOf(parent).isEmpty()) {
            return PublisherBoxAlign.START
        }
        val container = computed(parent)
        if (container.centerContentVertically && container.alignItems == "center") {
            return PublisherBoxAlign.CENTER
        }
        if (isBlockAligned) {
            return PublisherBoxAlign.START
        }

        val containerRtl = when (container.direction) {
            "rtl" -> true
            "auto" -> firstStrongRtl(parent.text()) ?: false
            else -> false
        }
        return when (container.textAlign) {
            "center" -> PublisherBoxAlign.CENTER
            "right" -> PublisherBoxAlign.END
            "left" -> PublisherBoxAlign.START
            "start" -> if (containerRtl) PublisherBoxAlign.END else PublisherBoxAlign.START
            "end" -> if (containerRtl) PublisherBoxAlign.START else PublisherBoxAlign.END
            else -> PublisherBoxAlign.START
        }
    }

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
        var fontWeight: Int? = null
        var italic: Boolean? = null
        var floatSide: String? = null
        var family: String? = null
        val inherited = computed(element)
        val pseudo = resolve(element, inherited, merged, pseudoElement = true)
        val typography = pseudo.fontTypography
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
                "font-size" -> RustCssValues.fontSize(value, 1f)?.let { scale = it }
                "font-weight" -> cssFontWeight(value, inherited.fontWeight)?.let { fontWeight = it }

                "font-style" -> when {
                    value.startsWith("italic") || value.startsWith("oblique") -> italic = true
                    value == "normal" || value == "initial" -> italic = false
                    value in setOf("inherit", "unset", "revert", "revert-layer") -> italic = inherited.italic ?: false
                }

                "float" -> if (value == "left" || value == "right") floatSide = value
                "font-family" -> firstFamilyName(value)?.let { family = it }
                "direction" -> if (value == "ltr" || value == "rtl") direction = value
                "color", "background-color" -> Unit
            }
        }
        val isDropCap = floatSide != null || scale >= 1.5f
        val plain = !isDropCap && scale in 0.99f..1.01f &&
            fontWeight == null && italic == null && family == null &&
            foreground == inherited.foregroundColorArgb && background == null && typography == inherited.fontTypography &&
            pseudo.fontFeatureSettings == inherited.fontFeatureSettings
        if (plain) return null
        return FirstLetterStyle(
            scale = scale.coerceIn(1f, 4f),
            bold = (fontWeight ?: inherited.fontWeight)?.let { it >= 600 },
            fontWeight = fontWeight ?: inherited.fontWeight,
            italic = italic ?: inherited.italic,
            isDropCap = isDropCap,
            fontFamily = family,
            fontTypography = typography,
            fontFeatureSettings = pseudo.fontFeatureSettings
                ?: "".takeIf { inherited.fontFeatureSettings != null },
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
            val variables = RustCssValues.variables(own.customProps, mapOf("font-size" to it))
            val value = resolvedDeclarationValue(it, variables) ?: return@let null
            when (value) {
                "inherit", "unset" -> baseSize
                "initial" -> 1f
                else -> RustCssValues.fontSize(value, baseSize)
            }
        }
        val scale = ((declaredAbsolute ?: own.fontSizeEm) / baseSize).coerceIn(1f, 4f)
        return FirstLetterStyle(
            scale = scale,
            bold = own.bold,
            fontWeight = own.fontWeight,
            italic = own.italic,
            isDropCap = true,
            fontFamily = own.fontFamilyName,
            fontTypography = own.fontTypography,
            fontFeatureSettings = own.fontFeatureSettings
                ?: "".takeIf { base.fontFeatureSettings != null },
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
        val fontWeight: Int? = null,
        /** Font size relative to the element (1 = same). */
        val scale: Float = 1f,
        val foregroundColorArgb: Int? = null,
        val backgroundColorArgb: Int? = null,
        val fontFeatureSettings: String? = null,
        val textTransform: String? = null,
        val visibilityHidden: Boolean = false,
        /** A generated block decoration, including empty border separators. */
        val publisherBox: PublisherBoxStyle? = null,
        val fontFamilyName: String? = null,
        val fontTypography: FontTypography = FontTypography(),
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
        val inherited = computed(element)
        val value = resolve(element, inherited, merged, pseudoElement = true)
        return GeneratedRun(
            text = text,
            italic = value.italic.takeIf { it != inherited.italic },
            bold = value.bold.takeIf { it != inherited.bold },
            fontWeight = value.fontWeight.takeIf { it != inherited.fontWeight },
            scale = (value.fontSizeEm / inherited.fontSizeEm).coerceIn(0.5f, 2.5f),
            foregroundColorArgb = value.foregroundColorArgb
                .takeIf { it != inherited.foregroundColorArgb },
            backgroundColorArgb = value.backgroundColorArgb,
            fontFeatureSettings = value.fontFeatureSettings
                ?: "".takeIf { inherited.fontFeatureSettings != null },
            textTransform = value.textTransform,
            visibilityHidden = value.visibilityHidden,
            publisherBox = publisherBoxStyle(element, value),
            // Generated code/pre glyphs inherit their semantic monospace;
            // an explicit pseudo-element font-family clears this flag.
            fontFamilyName = if (value.monospace) "monospace" else value.fontFamilyName,
            fontTypography = value.fontTypography,
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
                                if (value[i] == '\r' && value.getOrNull(i + 1) == '\n') i++
                                if (value[i] != '\n' && value[i] != '\r' && value[i] != '\u000c') {
                                    out.append(value[i])
                                }
                                i++
                            }
                        } else {
                            if (value[i] == '\n' || value[i] == '\r' || value[i] == '\u000c') return null
                            out.append(value[i])
                            i++
                        }
                    }
                    if (i >= value.length) return null
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
    private fun declarationsFor(element: Element): Map<String, Declaration> =
        cascadedFor(element).map

    private fun cascadedFor(element: Element): CascadedDeclarations {
        val inline = styleAttrOf(element)
            .takeIf { it.isNotEmpty() }
            ?.let(::declarationsForInlineStyle)
        if (inline == null) {
            if (rules.size == 0 || selectorBudget <= 0) return CascadedDeclarations.EMPTY
            val winners = matchWinners(rules, element, null)
            if (winners.size == 1) return CascadedDeclarations.EMPTY
            var hash = 1
            for (index in 1 until winners.size) {
                hash = 31 * hash + winners[index]
            }
            winnerProbe.ids = winners
            winnerProbe.offset = 1
            winnerProbe.hash = hash
            val cached = cascadeWinnerCache[winnerProbe]
            winnerProbe.ids = EMPTY_INT_ARRAY
            if (cached != null) return cached
            val map = cascadeDeclarations(winners, selectorProgram.declarations, emptyList())
                ?: return CascadedDeclarations.EMPTY
            val pairs = map.map { (property, declaration) -> property to declaration }
            val result = CascadedDeclarations(map, pairs)
            if (cascadeWinnerCache.size < MAX_SHARED_COMPUTED_STYLES) {
                cascadeWinnerCache[WinnerKey(winners, 1, hash)] = result
            }
            return result
        }
        val map = cascade(rules, element, inline) ?: emptyMap()
        val pairs = if (map.isEmpty()) emptyList() else map.map { (property, declaration) -> property to declaration }
        return CascadedDeclarations(map, pairs)
    }

    private fun declarationsForInlineStyle(style: String): InlineStyle {
        inlineDeclarations[style]?.let { return it }
        val declarations = compileDeclarations(RustCssSyntax.inline(style), stylesheet = false)
        val propertyIds = if (rules.size == 0) emptyMap() else selectorProgram.propertyIds
        val inline = InlineStyle(declarations, propertyIds)
        // Use the maximum UTF-16 storage of a Java String without allocating
        // an encoded copy just to check cache admission.
        val availableBytes = minOf(MAX_INLINE_CACHE_KEY_BYTES, MAX_INLINE_CACHE_BYTES - inlineCacheKeyBytes)
        if (inlineDeclarations.size < MAX_INLINE_CACHE_ENTRIES && style.length <= availableBytes / Char.SIZE_BYTES) {
            inlineDeclarations[style] = inline
            inlineCacheKeyBytes += style.length * Char.SIZE_BYTES
        }
        return inline
    }

    private fun matchWinners(
        pool: RulePool,
        element: Element,
        inlineWire: IntArray?,
    ): IntArray {
        val program = selectorProgram
        var session = selectorSession
        var node = session?.nodeId(element)
        if (session == null || node == null) {
            selectorSession = null
            session?.close()
            session = RustCssMatch.open(element, program.rules, program.absoluteKeys)
            selectorSession = session
            node = checkNotNull(session.nodeId(element))
        }
        val winners = session.cascade(pool.id, node, selectorBudget, inlineWire)
        selectorBudget = winners[0]
        return winners
    }

    /** Matches rules and returns their winning declarations in application order. */
    private fun cascade(
        pool: RulePool,
        element: Element,
        inline: InlineStyle?,
    ): Map<String, Declaration>? {
        if (pool.size == 0 || selectorBudget <= 0) return inline?.winners
        val winners = matchWinners(pool, element, inline?.wire)
        return cascadeDeclarations(winners, selectorProgram.declarations, inline?.declarations.orEmpty())
    }

    // ------------------------------------------------------------- resolve

    private fun resolve(
        element: Element,
        parent: Computed?,
        declared: Map<String, Declaration>? = null,
        pseudoElement: Boolean = false,
    ): Computed {
        val declarations = declared ?: declarationsFor(element)
        val session = computedSession ?: RustCssComputed().also {
            computedSession = it
            computedSessionResolutions = 0
        }
        val mayUseAutomaticDirection = parent?.direction == "auto" || dirAttrOf(element).trim().equals("auto", true)
        val hasLogicalInsets = declarations.keys.any {
            it == "margin-inline-start" || it == "margin-inline-end" ||
                it == "padding-inline-start" || it == "padding-inline-end"
        }
        val firstStrongIsRtl = mayUseAutomaticDirection && hasLogicalInsets &&
            firstStrongRtl(element.text()) == true
        computedSessionResolutions++
        return session.resolve(element, parent, declarations, pseudoElement, firstStrongIsRtl)
    }

    private fun parseFontVariationSettings(value: String): Map<String, Float>? {
        val axes = LinkedHashMap<String, Float>()
        val items = commaSlices(value)
        if (items.size > 64) return null
        for (item in items) {
            val match = fontVariationRegex.matchEntire(item.trim()) ?: return null
            val amount = match.groupValues[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            axes[match.groupValues[2]] = amount
        }
        return axes.takeIf { it.isNotEmpty() }
    }

    private fun parseFontStretch(value: String): Float? = FONT_STRETCH_PERCENTAGES[value] ?: value
        .takeIf { it.endsWith('%') }?.dropLast(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

    private fun validLigatureSettings(value: String): Boolean {
        if (value == "none") return true
        val tokens = value.split(whitespaceRegex)
        if (tokens.any { it !in FONT_VARIANT_LIGATURE_FEATURES }) return false
        val groups = tokens.map { it.removePrefix("no-") }
        return groups.size == groups.distinct().size
    }

    private fun parseFontFeatureSettings(value: String): String? {
        val features = LinkedHashMap<String, Int>()
        for (item in commaSlices(value)) {
            val match = fontFeatureRegex.matchEntire(item.trim()) ?: return null
            val amount = when (val argument = match.groupValues[2].lowercase()) {
                "", "on" -> 1
                "off" -> 0
                else -> argument.toIntOrNull()?.takeIf { it in 0..65_535 } ?: return null
            }
            features[match.groupValues[1]] = amount
        }
        return features.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString(", ") { "'${it.key}' ${it.value}" }
    }

    private fun resolvedDeclarationValue(
        declaration: Declaration,
        variables: RustCssValues.Variables,
        preserveCase: Boolean = false,
    ): String? {
        val raw = variables.value(declaration.value) ?: return null
        val value = raw.trim().let { if (preserveCase) it else it.lowercase() }
        return if (declaration.expansion == 0) value else RustCssValues.expand(declaration.expansion, value)
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

    /** The unit suffix of a numeric value ("1.5em" → "em", "30%" → "%"). */
    private fun unitOf(value: String): String {
        var i = value.length
        while (i > 0 && (value[i - 1].isLetter() || value[i - 1] == '%')) i--
        return value.substring(i)
    }

    /** First family of a font-family list, unquoted and lowercased. */
    private fun firstFamilyName(value: String): String? =
        value.split(',').firstOrNull()
            ?.trim()?.trim('"', '\'')?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "inherit" }

    // ------------------------------------------------------------- parsing

    private fun canAcceptStylesheetRules(): Boolean =
        ruleCount < MAX_RULES && stylesheetDeclarationCount < MAX_STYLESHEET_DECLARATIONS

    private fun addSheetRules(sourceRules: List<RustCssSyntax.Rule>, baseDir: String, groupDepth: Int = 0) {
        if (groupDepth > MAX_GROUP_RULE_DEPTH) return
        for (source in sourceRules) {
            if (!canAcceptStylesheetRules()) return
            when (source) {
                is RustCssSyntax.Rule.Namespace -> namespaceRuleRegex.matchEntire(source.prelude.trim())?.let {
                    parsingNamespaces[it.groupValues[1]] = it.groupValues[2].ifEmpty { it.groupValues[3] }
                }
                is RustCssSyntax.Rule.Media -> {
                    if (simpleMaxWidthBreakpoints.size < MAX_RESPONSIVE_BREAKPOINTS) {
                        simpleMaxWidthBreakpoint(source.condition)?.let { simpleMaxWidthBreakpoints += it }
                    }
                    if (screenMediaApplies(source.condition)) {
                        addSheetRules(source.rules, baseDir, groupDepth + 1)
                    }
                }
                is RustCssSyntax.Rule.Supports -> {
                    if (supportsConditionApplies(source.condition.trim().lowercase())) {
                        addSheetRules(source.rules, baseDir, groupDepth + 1)
                    }
                }
                is RustCssSyntax.Rule.FontFace -> {
                    // Every @font-face counts even if the face cap was reached.
                    ruleCount++
                    if (mutableFontFaces.size < MAX_FONT_FACES) {
                        val declarations = compileDeclarations(source.declarations, stylesheet = true)
                        parseFontFace(declarations.toMap(), baseDir)
                    }
                }
                is RustCssSyntax.Rule.Qualified -> addQualifiedRule(source)
            }
        }
    }

    private fun addQualifiedRule(source: RustCssSyntax.Rule.Qualified) {
        val declarations = compileDeclarations(source.declarations, stylesheet = true)
        if (declarations.isEmpty()) return
        val selectors = RustCssSelectors.compile(source.selector, parsingNamespaces, MAX_RULES - ruleCount)
        for (selector in selectors) {
            rulePools[selector.pool].add(Rule(selector, orderCounter++, declarations))
            ruleCount++
        }
    }

    /** Commas inside strings and functions are part of the same value. */
    private fun commaSlices(text: String): List<String> {
        val result = ArrayList<String>(minOf(MAX_COMMA_LIST_ITEMS, 8))
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
                    if (result.size >= MAX_COMMA_LIST_ITEMS) return result
                    start = index + 1
                }
            }
        }
        if (result.size < MAX_COMMA_LIST_ITEMS) result.add(text.substring(start))
        return result
    }

    /**
     * Evaluate screen media against actual supplied values. Parsing normally
     * precedes layout, so constrained branches without a viewport are skipped
     * and keep the publisher's unconstrained fallback. Applying every width
     * branch at once destroys drama tables and can hide wide-screen content.
     */
    private fun screenMediaApplies(raw: String): Boolean = commaSlices(raw).any { branch ->
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
        var baseApplies = when (type) {
            "", "all", "screen" -> true
            "print", "speech", "aural", "braille", "embossed", "handheld",
            "projection", "tty", "tv", "amzn-mobi", "amzn-kf8" -> false
            else -> false
        }
        val features = mediaFeatureRegex.findAll(query).toList()
        // Unknown expressions are not evidence that a query is applicable,
        // even when negated. Preserve the stable base stylesheet instead.
        val remainder = mediaFeatureRegex.replace(query, "").replace(Regex("""\band\b"""), "")
            .trim().removePrefix(type).trim()
        if (remainder.isNotEmpty()) return@any false
        for (feature in features) {
            val applies = mediaFeatureApplies(feature.groupValues[1]) ?: return@any false
            baseApplies = baseApplies && applies
        }
        if (negated) !baseApplies else baseApplies
    }

    private fun mediaFeatureApplies(raw: String): Boolean? {
        val property = raw.substringBefore(':').trim()
        val value = raw.substringAfter(':', "").trim()
        if (property == "prefers-color-scheme") {
            val dark = viewport?.dark ?: return null
            return when (value) { "dark" -> dark; "light" -> !dark; else -> null }
        }
        if (property == "orientation") {
            val width = viewport?.widthPx?.takeIf { it.isFinite() && it > 0f } ?: return null
            val height = viewport.heightPx?.takeIf { it.isFinite() && it > 0f } ?: return null
            return when (value) { "portrait" -> height >= width; "landscape" -> width > height; else -> null }
        }
        val dimension = when (property.removePrefix("min-").removePrefix("max-")) {
            "width" -> viewport?.widthPx
            "height" -> viewport?.heightPx
            else -> return null
        }?.takeIf { it.isFinite() && it > 0f } ?: return null
        if (value.endsWith("%") || value.endsWith("vh") || value.endsWith("vw")) return null
        val threshold = lengthEm(value)?.times(16f) ?: return null
        return when {
            property.startsWith("min-") -> dimension >= threshold
            property.startsWith("max-") -> dimension <= threshold
            else -> dimension == threshold
        }
    }

    private fun simpleMaxWidthBreakpoint(raw: String): Float? {
        val match = simpleMaxWidthMediaRegex.matchEntire(raw.trim()) ?: return null
        val value = match.groupValues[1].trim().lowercase()
        // CSS media em/rem use the initial 16px size, independently of the
        // user's reading font or scale. Complex/negated queries keep fallback.
        if (unitOf(value) !in setOf("px", "em", "rem")) return null
        return lengthEm(value)?.times(16f)?.takeIf { it.isFinite() && it in 1f..10_000f }
    }

    /** Single-declaration capability queries, optionally negated; unknown syntax skips. */
    private fun supportsConditionApplies(raw: String): Boolean {
        val negated = raw.startsWith("not ")
        val query = if (negated) raw.removePrefix("not ").trimStart() else raw
        val match = supportsFeatureRegex.matchEntire(query) ?: return false
        val property = match.groupValues[1]
        val value = match.groupValues[2].trim()
        val supported = when (property) {
            "display" -> value in setOf("none", "block", "inline", "inline-block", "table", "table-row", "table-cell", "flex")
            "color", "background-color" -> value == "currentcolor" || CssColor.parse(value) != null
            "font-variation-settings" -> value == "normal" || parseFontVariationSettings(value) != null
            "font-width", "font-stretch" -> parseFontStretch(value) != null
            "font-optical-sizing" -> value == "auto" || value == "none"
            "font-kerning" -> value in setOf("auto", "normal", "none")
            "font-variant-ligatures" -> value == "normal" || validLigatureSettings(value)
            "font-feature-settings" -> value == "normal" || parseFontFeatureSettings(value) != null
            else -> false
        }
        return if (negated) !supported else supported
    }

    /** CSS Fonts 4 relative-weight table; Compose accepts integral weights from 1 to 1000. */
    private fun cssFontWeight(value: String, inherited: Int?, reverted: Int = inherited ?: 400): Int? {
        val base = inherited ?: 400
        return when (value) {
            "normal", "initial" -> 400
            "revert", "revert-layer" -> reverted
            "bold" -> 700
            "inherit", "unset" -> base
            "bolder" -> when {
                base < 350 -> 400
                base < 550 -> 700
                base < 900 -> 900
                else -> base
            }
            "lighter" -> when {
                base < 100 -> base
                base < 550 -> 100
                base < 750 -> 400
                else -> 700
            }
            else -> value.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..1000f }
                ?.let { kotlin.math.round(it).toInt() }
        }
    }

    private fun parseFontFace(declarations: Map<String, Declaration>, baseDir: String) {
        val family = declarations["font-family"]?.let { firstFamilyName(it.value) } ?: return
        val src = declarations["src"]?.value ?: return
        val url = fontUrlRegex.find(src)?.groupValues?.get(1) ?: return
        val weights = declarations["font-weight"]?.value?.trim()?.lowercase()
            ?.split(whitespaceRegex)?.mapNotNull { cssFontWeight(it, 400) } ?: listOf(400)
        val weightMin = weights.minOrNull() ?: 400
        val weightMax = weights.maxOrNull() ?: weightMin
        val weight = 400.coerceIn(weightMin, weightMax)
        val style = declarations["font-style"]?.value?.trim()?.lowercase()
        val bold = weight >= 600
        val italic = style?.startsWith("italic") == true || style?.startsWith("oblique") == true
        val widths = declarations["font-stretch"]?.value?.trim()?.lowercase()?.split(whitespaceRegex)
            ?.takeIf { it.size in 1..2 }?.map(::parseFontStretch)
            ?.takeIf { values -> values.all { it != null } }?.filterNotNull().orEmpty()
        val stretchMin = widths.minOrNull() ?: 100f
        val stretchMax = widths.maxOrNull() ?: stretchMin
        val unicodeRange = normalizeFontUnicodeRange(declarations["unicode-range"]?.value)
        val key = listOf(family.lowercase(), url, baseDir, weightMin.toString(), weightMax.toString(), italic.toString(), stretchMin.toString(), stretchMax.toString(), unicodeRange.orEmpty())
            .joinToString("\u0000")
        if (!fontFaceKeys.add(key) || mutableFontFaces.size >= MAX_FONT_FACES) return
        mutableFontFaces.add(FontFace(
            family = family,
            src = url,
            baseDir = baseDir,
            bold = bold,
            italic = italic,
            weight = weight,
            weightMin = weightMin,
            weightMax = weightMax,
            stretchMin = stretchMin,
            stretchMax = stretchMax,
            unicodeRange = unicodeRange,
        ))
    }

    private fun compileDeclarations(
        source: List<RustCssSyntax.Declaration>,
        stylesheet: Boolean,
    ): List<Pair<String, Declaration>> {
        val limit = if (stylesheet) {
            minOf(MAX_DECLARATIONS_PER_BLOCK, MAX_STYLESHEET_DECLARATIONS - stylesheetDeclarationCount)
        } else {
            MAX_DECLARATIONS_PER_BLOCK
        }
        val block = RustCssValues.compile(source, limit)
        if (stylesheet) stylesheetDeclarationCount += block.consumed
        return block.declarations
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

    private companion object {
        fun declarationFields(
            declarations: List<Pair<String, Declaration>>,
            propertyId: (Int, String) -> Int,
        ): IntArray = IntArray(declarations.size * 2).also { fields ->
            declarations.forEachIndexed { index, (property, declaration) ->
                fields[2 * index] = propertyId(index, property)
                fields[2 * index + 1] = if (declaration.important) 1 else 0
            }
        }

        fun cascadeDeclarations(
            winners: IntArray,
            sheets: List<Pair<String, Declaration>>,
            inline: List<Pair<String, Declaration>>,
        ): Map<String, Declaration>? {
            if (winners.size == 1) return null
            val declarations = LinkedHashMap<String, Declaration>()
            // The leading field is the remaining selector budget.
            for (index in 1 until winners.size) {
                val id = winners[index]
                val (property, declaration) = if (id < 0) inline[-1 - id] else sheets[id]
                declarations[property] = declaration
            }
            return declarations
        }

        val EMPTY_INT_ARRAY = IntArray(0)
        const val BLACK_ARGB: Int = -0x1000000
        const val MAX_BOX_INLINE_EM = 8f
        const val MAX_BOX_INLINE_FRAC = 0.45f
        const val MAX_GROUP_RULE_DEPTH = 64
        const val MAX_RESPONSIVE_BREAKPOINTS = 8
        const val MAX_RULES = 16_384
        const val MAX_FONT_FACES = 512
        const val MAX_STYLESHEET_DECLARATIONS = 131_072
        const val MAX_DECLARATIONS_PER_BLOCK = 512
        const val MAX_COMMA_LIST_ITEMS = 256
        const val MAX_UNIVERSAL_RULES = 1_024
        const val MAX_RULES_PER_INDEX_BUCKET = 4_096
        const val MAX_SELECTOR_MATCH_OPERATIONS = 5_000_000
        const val MAX_SHARED_COMPUTED_STYLES = 1_024
        const val MAX_INLINE_CACHE_ENTRIES = 256
        const val MAX_INLINE_CACHE_BYTES = 128 * 1024
        const val MAX_INLINE_CACHE_KEY_BYTES = 2 * 1024
        val whitespaceRegex = Regex("""\s+""")
        val mediaFeatureRegex = Regex("""\(([^()]*)\)""")
        val supportsFeatureRegex = Regex("""\(\s*([\w-]+)\s*:\s*([^()]+)\)""")
        val simpleMaxWidthMediaRegex = Regex(
            """(?i)(?:(?:only\s+)?(?:screen|all)\s+and\s+)?\(\s*max-width\s*:\s*([^()]+)\s*\)""",
        )
        val fontVariationRegex = Regex("""(["'])([ -~]{4})\1\s+([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)""")
        val FONT_STRETCH_PERCENTAGES = mapOf(
            "ultra-condensed" to 50f, "extra-condensed" to 62.5f, "condensed" to 75f,
            "semi-condensed" to 87.5f, "normal" to 100f, "semi-expanded" to 112.5f,
            "expanded" to 125f, "extra-expanded" to 150f, "ultra-expanded" to 200f,
        )
        val FONT_VARIANT_LIGATURE_FEATURES = mapOf(
            "common-ligatures" to (listOf("liga", "clig") to 1),
            "no-common-ligatures" to (listOf("liga", "clig") to 0),
            "discretionary-ligatures" to (listOf("dlig") to 1),
            "no-discretionary-ligatures" to (listOf("dlig") to 0),
            "historical-ligatures" to (listOf("hlig") to 1),
            "no-historical-ligatures" to (listOf("hlig") to 0),
            "contextual" to (listOf("calt") to 1),
            "no-contextual" to (listOf("calt") to 0),
        )
        val fontFeatureRegex = Regex("""["']([ -~]{4})["'](?:\s+((?i:on|off)|\d+))?""")
        val INLINE_REPLACED_TAGS = setOf("img", "image", "svg")
        val BLOCK_LEVEL_DISPLAY_VALUES = setOf(
            "block", "flow-root", "flex", "grid", "list-item", "table",
        )
        val fontUrlRegex = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")
        val namespaceRuleRegex = Regex(
            """(?i)^@namespace\s+([\w-]+)\s+(?:url\(\s*["']?([^\s"')]+)["']?\s*\)|["']([^"']+)["'])$""",
        )
    }
}
