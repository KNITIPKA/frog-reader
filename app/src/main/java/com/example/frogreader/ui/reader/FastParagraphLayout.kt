package com.example.frogreader.ui.reader

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreakConfig
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.model.INLINE_FONT_TYPOGRAPHY_TAG
import com.example.frogreader.ui.theme.resolveRegisteredFontTypography
import com.example.frogreader.ui.theme.sourceFontTypography
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil

private val fastMeasurerRegistryLock = Any()
private val fastMeasurers = WeakHashMap<TextMeasurer, FastParagraphMeasurer>()

/**
 * Creates a [TextMeasurer] and registers a paired [FastParagraphMeasurer] for
 * direct Android [StaticLayout] pagination measurements.
 */
internal fun createReaderTextMeasurer(
    fontFamilyResolver: FontFamily.Resolver,
    density: Density,
    layoutDirection: LayoutDirection,
    cacheSize: Int = 0,
): TextMeasurer {
    val measurer = TextMeasurer(
        defaultFontFamilyResolver = fontFamilyResolver,
        defaultDensity = density,
        defaultLayoutDirection = layoutDirection,
        cacheSize = cacheSize,
    )
    val fast = FastParagraphMeasurer(
        measurer = measurer,
        fontFamilyResolver = fontFamilyResolver,
        layoutDirection = layoutDirection,
    )
    synchronized(fastMeasurerRegistryLock) {
        fastMeasurers[measurer] = fast
    }
    return measurer
}

/** Looks up the [FastParagraphMeasurer] paired with [measurer], if any. */
internal fun fastParagraphMeasurerFor(measurer: TextMeasurer): FastParagraphMeasurer? =
    synchronized(fastMeasurerRegistryLock) {
        fastMeasurers[measurer]
    }

/** Minimal paragraph layout geometry consumed by [paginateLayout]. */
internal interface MeasuredParagraph {
    val lineCount: Int
    val height: Int
    fun getLineForOffset(offset: Int): Int
    fun getLineTop(lineIndex: Int): Float
    fun getLineBottom(lineIndex: Int): Float
    fun getLineEnd(lineIndex: Int): Int
}

internal fun TextLayoutResult.toMeasuredParagraph(): MeasuredParagraph =
    ComposeMeasuredParagraph(this)

private class ComposeMeasuredParagraph(
    private val result: TextLayoutResult,
) : MeasuredParagraph {
    override val lineCount: Int get() = result.lineCount
    override val height: Int get() = result.size.height
    override fun getLineForOffset(offset: Int): Int = result.getLineForOffset(offset)
    override fun getLineTop(lineIndex: Int): Float = result.getLineTop(lineIndex)
    override fun getLineBottom(lineIndex: Int): Float = result.getLineBottom(lineIndex)
    override fun getLineEnd(lineIndex: Int): Int = result.getLineEnd(lineIndex)
}

/**
 * Direct Android [StaticLayout] fast path for reader pagination with exact
 * parity against Compose [TextMeasurer.measureReader].
 *
 * Bypasses Compose's per-paragraph wrapper allocations and `BoringLayout.isBoring()`
 * double-shaping for eligible paragraphs while falling back cleanly to
 * [TextMeasurer.measureReader] on JVM tests or unsupported styles/spans/inlines.
 */
internal class FastParagraphMeasurer(
    val measurer: TextMeasurer,
    private val fontFamilyResolver: FontFamily.Resolver,
    private val layoutDirection: LayoutDirection,
) {
    private val threadScratch = ThreadLocal<WorkerScratch>()

    private fun scratch(): WorkerScratch {
        var existing = threadScratch.get()
        if (existing == null) {
            existing = WorkerScratch()
            threadScratch.set(existing)
        }
        return existing
    }

    fun measure(
        text: AnnotatedString,
        style: TextStyle,
        maxWidthPx: Int,
        density: Density,
        bookFonts: Map<String, FontFamily> = emptyMap(),
        publisherStyles: Boolean = true,
    ): MeasuredParagraph = measureDirectOrNull(
        text = text,
        style = style,
        maxWidthPx = maxWidthPx,
        density = density,
        bookFonts = bookFonts,
        publisherStyles = publisherStyles,
    ) ?: measurer.measureReader(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
        density = density,
        bookFonts = bookFonts,
        publisherStyles = publisherStyles,
    ).toMeasuredParagraph()

    fun measureDirectOrNull(
        text: AnnotatedString,
        style: TextStyle,
        maxWidthPx: Int,
        density: Density,
        bookFonts: Map<String, FontFamily> = emptyMap(),
        publisherStyles: Boolean = true,
    ): MeasuredParagraph? {
        if (Build.VERSION.SDK_INT < 28) return null
        if (maxWidthPx <= 0 || text.isEmpty()) return null
        if (text.paragraphStyles.isNotEmpty()) return null

        val scratch = scratch()
        val resolvedPlan = resolveTypographyFast(
            scratch = scratch,
            text = text,
            style = style,
            density = density,
            bookFonts = bookFonts,
            publisherStyles = publisherStyles,
        ) ?: return null

        val layoutText = resolvedPlan.text
        val layoutStyle = resolvedPlan.style
        val rawText = layoutText.text
        val length = rawText.length
        if (length == 0) return null
        if (layoutText.paragraphStyles.isNotEmpty()) return null
        if (hasAnyReaderInlineTag(layoutText, publisherStyles)) return null
        if (layoutText.hasLinkAnnotations(0, length)) return null
        // Trailing newline creates an empty last line that triggers Compose's
        // getLastLineMetrics("\u200B") path; fall back for exact parity.
        if (rawText[length - 1] == '\n') return null
        if (containsPotentialEmoji(rawText)) return null

        // Validate style attributes against supported exact-parity subset.
        if (layoutStyle.fontSize.type != TextUnitType.Sp ||
            !layoutStyle.fontSize.value.isFinite() ||
            layoutStyle.fontSize.value <= 0f
        ) return null

        val lineHeight = layoutStyle.lineHeight
        if (lineHeight != TextUnit.Unspecified) {
            if ((lineHeight.type != TextUnitType.Sp && lineHeight.type != TextUnitType.Em) ||
                !lineHeight.value.isFinite() ||
                lineHeight.value < 0f
            ) return null
        }

        if (layoutStyle.letterSpacing != TextUnit.Unspecified) return null
        if (layoutStyle.fontFeatureSettings != null) return null
        if (layoutStyle.textGeometricTransform != null) return null
        if (layoutStyle.baselineShift != null) return null
        if (layoutStyle.shadow != null) return null
        val textMotion = layoutStyle.textMotion
        if (textMotion != null && textMotion != TextMotion.Static) return null

        val includeFontPadding = layoutStyle.platformStyle?.paragraphStyle?.includeFontPadding ?: false
        if (includeFontPadding) return null

        val resolvedAlign = if (layoutStyle.textAlign == TextAlign.Unspecified) {
            TextAlign.Start
        } else {
            layoutStyle.textAlign
        }
        val alignment = when (resolvedAlign) {
            TextAlign.Start, TextAlign.Justify -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
            else -> return null
        }
        val justificationMode = if (resolvedAlign == TextAlign.Justify) {
            Layout.JUSTIFICATION_MODE_INTER_WORD
        } else {
            Layout.JUSTIFICATION_MODE_NONE
        }

        val resolvedLineBreak = if (layoutStyle.lineBreak == LineBreak.Unspecified) {
            LineBreak.Simple
        } else {
            layoutStyle.lineBreak
        }
        val useParagraphBreakConfig = when (resolvedLineBreak) {
            LineBreak.Paragraph -> true
            LineBreak.Simple -> false
            else -> return null
        }

        val resolvedHyphens = if (layoutStyle.hyphens == Hyphens.Unspecified) {
            Hyphens.None
        } else {
            layoutStyle.hyphens
        }
        val hyphenationFrequency = when (resolvedHyphens) {
            Hyphens.None -> Layout.HYPHENATION_FREQUENCY_NONE
            Hyphens.Auto -> if (Build.VERSION.SDK_INT >= 33) {
                Layout.HYPHENATION_FREQUENCY_FULL_FAST
            } else {
                Layout.HYPHENATION_FREQUENCY_FULL
            }
            else -> return null
        }

        val textDirectionHeuristic = resolveTextDirectionHeuristic(
            textDirection = layoutStyle.textDirection,
            layoutDirection = layoutDirection,
        ) ?: return null

        // Validate and collect font-changing spans (layout-neutral spans are ignored).
        val spans = layoutText.spanStyles
        var fontSpanCount = 0
        var singleFontSpan: AnnotatedString.Range<SpanStyle>? = null
        var fontSpans: ArrayList<AnnotatedString.Range<SpanStyle>>? = null
        for (i in spans.indices) {
            val range = spans[i]
            val start = range.start
            val end = range.end
            if (start < 0 || start >= length || end <= start || end > length) continue
            val item = range.item
            if (item.fontSize != TextUnit.Unspecified ||
                item.fontFamily != null ||
                item.letterSpacing != TextUnit.Unspecified ||
                item.baselineShift != null ||
                item.textGeometricTransform != null ||
                item.fontFeatureSettings != null ||
                item.localeList != null ||
                item.shadow != null ||
                item.fontSynthesis != null
            ) {
                return null
            }
            if (item.fontWeight != null || item.fontStyle != null) {
                fontSpanCount++
                if (fontSpanCount == 1) {
                    singleFontSpan = range
                } else {
                    if (fontSpans == null) {
                        fontSpans = ArrayList(4)
                        fontSpans.add(singleFontSpan!!)
                    }
                    fontSpans.add(range)
                }
            }
        }

        val baseFamily = layoutStyle.fontFamily
        val baseWeight = layoutStyle.fontWeight ?: FontWeight.Normal
        val baseStyle = layoutStyle.fontStyle ?: FontStyle.Normal
        val baseSynthesis = layoutStyle.fontSynthesis ?: FontSynthesis.All
        val baseTypeface = resolveTypefaceCached(
            scratch = scratch,
            family = baseFamily,
            weight = baseWeight,
            style = baseStyle,
            synthesis = baseSynthesis,
        ) ?: return null

        val fontSizePx = with(density) { layoutStyle.fontSize.toPx() }
        if (!fontSizePx.isFinite() || fontSizePx <= 0f) return null

        val lineHeightPx = resolveLineHeightInPx(lineHeight, fontSizePx, density)
        val hasLineHeight = !lineHeightPx.isNaN()
        val lineHeightSpan = if (hasLineHeight) {
            val lhs = layoutStyle.lineHeightStyle ?: LineHeightStyle.Default
            val topRatio = when (lhs.alignment) {
                LineHeightStyle.Alignment.Top -> 0f
                LineHeightStyle.Alignment.Center -> 0.5f
                LineHeightStyle.Alignment.Proportional -> -1f
                LineHeightStyle.Alignment.Bottom -> 1f
                else -> return null
            }
            val trimFirst: Boolean
            val trimLast: Boolean
            when (lhs.trim) {
                LineHeightStyle.Trim.Both -> {
                    trimFirst = true
                    trimLast = true
                }
                LineHeightStyle.Trim.FirstLineTop -> {
                    trimFirst = true
                    trimLast = false
                }
                LineHeightStyle.Trim.LastLineBottom -> {
                    trimFirst = false
                    trimLast = true
                }
                LineHeightStyle.Trim.None -> {
                    trimFirst = false
                    trimLast = false
                }
                else -> return null
            }
            ReaderLineHeightStyleSpan(
                lineHeight = lineHeightPx,
                startIndex = 0,
                endIndex = length,
                trimFirstLineTop = trimFirst,
                trimLastLineBottom = trimLast,
                topRatio = topRatio,
                mode = lhs.mode,
            )
        } else {
            null
        }

        var firstLineIndentPx = 0
        var restLineIndentPx = 0
        var hasIndent = false
        val indent = layoutStyle.textIndent
        if (indent != null &&
            !(indent.firstLine == 0.sp && indent.restLine == 0.sp) &&
            !indent.firstLine.isUnspecified &&
            !indent.restLine.isUnspecified
        ) {
            val firstPx = when (indent.firstLine.type) {
                TextUnitType.Sp -> with(density) { indent.firstLine.toPx() }
                TextUnitType.Em -> indent.firstLine.value * fontSizePx
                else -> 0f
            }
            val restPx = when (indent.restLine.type) {
                TextUnitType.Sp -> with(density) { indent.restLine.toPx() }
                TextUnitType.Em -> indent.restLine.value * fontSizePx
                else -> 0f
            }
            if (!firstPx.isFinite() || !restPx.isFinite()) return null
            firstLineIndentPx = ceil(firstPx).toInt()
            restLineIndentPx = ceil(restPx).toInt()
            if (firstLineIndentPx < 0 || restLineIndentPx < 0) return null
            hasIndent = firstLineIndentPx != 0 || restLineIndentPx != 0
        }

        val charSequence: CharSequence = if (lineHeightSpan == null && !hasIndent && fontSpanCount == 0) {
            rawText
        } else {
            val spannable = SpannableString(rawText)
            if (lineHeightSpan != null) {
                spannable.setSpan(lineHeightSpan, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (hasIndent) {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(firstLineIndentPx, restLineIndentPx),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (fontSpanCount == 1) {
                val range = singleFontSpan!!
                val tf = resolveTypefaceCached(
                    scratch = scratch,
                    family = baseFamily,
                    weight = range.item.fontWeight ?: baseWeight,
                    style = range.item.fontStyle ?: baseStyle,
                    synthesis = baseSynthesis,
                ) ?: return null
                spannable.setSpan(
                    ReaderTypefaceSpan(tf),
                    range.start,
                    range.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else if (fontSpanCount > 1) {
                val ok = applyFlattenedFontSpans(
                    spannable = spannable,
                    scratch = scratch,
                    fontSpans = fontSpans!!,
                    baseFamily = baseFamily,
                    baseWeight = baseWeight,
                    baseStyle = baseStyle,
                    baseSynthesis = baseSynthesis,
                )
                if (!ok) return null
            }
            spannable
        }

        val paint = scratch.paint
        paint.density = density.density
        paint.textSize = fontSizePx
        paint.typeface = baseTypeface
        val localeList = layoutStyle.localeList
        if (localeList != null) {
            paint.textLocales = scratch.resolveAndroidLocales(localeList)
        } else if (paint.textLocales !== scratch.defaultTextLocales) {
            paint.textLocales = scratch.defaultTextLocales
        }

        val staticLayout = try {
            val builder = StaticLayout.Builder.obtain(charSequence, 0, length, paint, maxWidthPx)
                .setTextDirection(textDirectionHeuristic)
                .setAlignment(alignment)
                .setMaxLines(Int.MAX_VALUE)
                .setEllipsizedWidth(maxWidthPx)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .applyBreakStrategy(useParagraphBreakConfig)
                .setHyphenationFrequency(hyphenationFrequency)
                .setJustificationMode(justificationMode)
                .setUseLineSpacingFromFallbacks(true)
            if (Build.VERSION.SDK_INT >= 33) {
                FastStaticLayoutApi33.setLineBreakConfig(builder, useParagraphBreakConfig)
            }
            if (Build.VERSION.SDK_INT >= 35) {
                FastStaticLayoutApi35.disableUseBoundsForWidth(builder)
            }
            builder.build()
        } catch (_: Throwable) {
            return null
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            !FastStaticLayoutApi33.isFallbackLineSpacingEnabled(staticLayout)
        ) {
            return null
        }

        val lineCount = staticLayout.lineCount
        if (lineCount <= 0) return null
        val lastLine = lineCount - 1
        if (lineHeightSpan != null &&
            staticLayout.getLineStart(lastLine) == staticLayout.getLineEnd(lastLine)
        ) {
            return null
        }

        var topPadding = 0
        var bottomPadding = 0
        if (lineHeightSpan != null) {
            if (lineHeightSpan.firstAscentDiff < 0) {
                topPadding = abs(lineHeightSpan.firstAscentDiff)
            }
            if (lineHeightSpan.lastDescentDiff < 0) {
                // Exact match to Compose TextLayout's Array<LineHeightStyleSpan>.getLineHeightPaddings().
                bottomPadding = maxOf(topPadding, abs(lineHeightSpan.lastDescentDiff))
            }
        }

        return StaticLayoutMeasuredParagraph(
            layout = staticLayout,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
    }

    private fun resolveTypographyFast(
        scratch: WorkerScratch,
        text: AnnotatedString,
        style: TextStyle,
        density: Density,
        bookFonts: Map<String, FontFamily>,
        publisherStyles: Boolean,
    ): ReaderTypographyPlan? {
        val length = text.length
        val hasInlineFamily = publisherStyles &&
            text.hasStringAnnotations(INLINE_FONT_FAMILY_TAG, 0, length)
        val hasInlineTypography = publisherStyles &&
            text.hasStringAnnotations(INLINE_FONT_TYPOGRAPHY_TAG, 0, length)
        if (hasInlineFamily || hasInlineTypography) {
            return readerTypographyPlan(text, style, density, publisherStyles, bookFonts)
        }

        val baseFamily = style.fontFamily
        if (baseFamily == null) {
            // Check if any span declares a fontFamily (which will either need sweep or fail direct path).
            val spans = text.spanStyles
            for (i in spans.indices) {
                val span = spans[i]
                if (span.start < span.end && span.item.fontFamily != null) {
                    return null
                }
            }
            return scratch.reusePlan(text, style)
        }

        val baseSizeSp = style.fontSize.takeIf { it.type == TextUnitType.Sp }?.value ?: 14f
        val scaledSizeSp = baseSizeSp * density.fontScale
        val scaledSizeBits = scaledSizeSp.toBits()

        // Fast 1-slot + map cache for non-file (bundled/system) font families.
        val cached = scratch.lookupBaseTypography(baseFamily, scaledSizeBits)
        if (cached != null) {
            val spans = text.spanStyles
            for (i in spans.indices) {
                val span = spans[i]
                if (span.start < span.end) {
                    val item = span.item
                    if (item.fontFamily != null || item.fontSize.isSpecified) {
                        return null
                    }
                }
            }
            val resolvedStyle = scratch.resolveStyleWithFamily(style, cached.resolvedFamily)
            return scratch.reusePlan(text, resolvedStyle)
        }

        val baseSource = sourceFontTypography(baseFamily)
        if (baseSource == null) {
            scratch.storeBaseTypography(
                baseFamily = baseFamily,
                scaledSizeBits = scaledSizeBits,
                entry = CachedBaseTypography(resolvedFamily = baseFamily),
            )
            val spans = text.spanStyles
            for (i in spans.indices) {
                val span = spans[i]
                if (span.start < span.end && span.item.fontFamily != null) {
                    return null
                }
            }
            return scratch.reusePlan(text, style)
        }

        if (baseSource.plan.hasFiles) {
            // Custom file fonts may inject fallback runs via fileFontTextRuns and can be
            // invalidated by forgetFontTypographyFiles; always resolve via readerTypographyPlan.
            return readerTypographyPlan(text, style, density, publisherStyles, bookFonts)
        }

        val resolvedBase = resolveRegisteredFontTypography(baseFamily, baseSource, scaledSizeSp)
        scratch.storeBaseTypography(
            baseFamily = baseFamily,
            scaledSizeBits = scaledSizeBits,
            entry = CachedBaseTypography(resolvedFamily = resolvedBase),
        )
        val spans = text.spanStyles
        for (i in spans.indices) {
            val span = spans[i]
            if (span.start < span.end) {
                val item = span.item
                if (item.fontFamily != null || item.fontSize.isSpecified) {
                    return null
                }
            }
        }
        val resolvedStyle = scratch.resolveStyleWithFamily(style, resolvedBase)
        return scratch.reusePlan(text, resolvedStyle)
    }

    private fun resolveTypefaceCached(
        scratch: WorkerScratch,
        family: FontFamily?,
        weight: FontWeight,
        style: FontStyle,
        synthesis: FontSynthesis,
    ): Typeface? {
        scratch.lookupTypeface(family, weight.weight, style, synthesis)?.let { return it }
        val resolved = try {
            fontFamilyResolver.resolve(
                fontFamily = family,
                fontWeight = weight,
                fontStyle = style,
                fontSynthesis = synthesis,
            ).value as? Typeface
        } catch (_: Throwable) {
            null
        } ?: return null
        scratch.storeTypeface(family, weight.weight, style, synthesis, resolved)
        return resolved
    }

    private fun applyFlattenedFontSpans(
        spannable: SpannableString,
        scratch: WorkerScratch,
        fontSpans: ArrayList<AnnotatedString.Range<SpanStyle>>,
        baseFamily: FontFamily?,
        baseWeight: FontWeight,
        baseStyle: FontStyle,
        baseSynthesis: FontSynthesis,
    ): Boolean {
        val count = fontSpans.size
        val transitions = IntArray(count * 2)
        for (i in 0 until count) {
            val range = fontSpans[i]
            transitions[i] = range.start
            transitions[i + count] = range.end
        }
        transitions.sort()

        var prev = transitions[0]
        for (t in 1 until transitions.size) {
            val curr = transitions[t]
            if (curr == prev) continue

            var weight: FontWeight? = null
            var style: FontStyle? = null
            var covered = false
            for (i in 0 until count) {
                val range = fontSpans[i]
                if (range.start < curr && range.end > prev) {
                    covered = true
                    range.item.fontWeight?.let { weight = it }
                    range.item.fontStyle?.let { style = it }
                }
            }
            if (covered) {
                val tf = resolveTypefaceCached(
                    scratch = scratch,
                    family = baseFamily,
                    weight = weight ?: baseWeight,
                    style = style ?: baseStyle,
                    synthesis = baseSynthesis,
                ) ?: return false
                spannable.setSpan(
                    ReaderTypefaceSpan(tf),
                    prev,
                    curr,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            prev = curr
        }
        return true
    }

    private class StaticLayoutMeasuredParagraph(
        private val layout: StaticLayout,
        private val topPadding: Int,
        private val bottomPadding: Int,
    ) : MeasuredParagraph {
        override val lineCount: Int get() = layout.lineCount

        override val height: Int
            get() = layout.height + topPadding + bottomPadding

        override fun getLineForOffset(offset: Int): Int {
            val count = layout.lineCount
            if (count <= 0) return 0
            if (offset >= layout.text.length) return count - 1
            if (offset <= 0) return 0
            return layout.getLineForOffset(offset).coerceAtMost(count - 1)
        }

        override fun getLineTop(lineIndex: Int): Float =
            layout.getLineTop(lineIndex).toFloat() + if (lineIndex == 0) 0 else topPadding

        override fun getLineBottom(lineIndex: Int): Float =
            topPadding +
                layout.getLineBottom(lineIndex).toFloat() +
                if (lineIndex == layout.lineCount - 1) bottomPadding else 0

        override fun getLineEnd(lineIndex: Int): Int = layout.getLineEnd(lineIndex)
    }

    private class ReaderTypefaceSpan(
        private val typeface: Typeface,
    ) : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.typeface = typeface
        }

        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
        }
    }

    private class ReaderLineHeightStyleSpan(
        private val lineHeight: Float,
        private val startIndex: Int,
        private val endIndex: Int,
        private val trimFirstLineTop: Boolean,
        private val trimLastLineBottom: Boolean,
        private val topRatio: Float,
        private val mode: LineHeightStyle.Mode,
    ) : LineHeightSpan {
        private var firstAscent: Int = Int.MIN_VALUE
        private var ascent: Int = Int.MIN_VALUE
        private var descent: Int = Int.MIN_VALUE
        private var lastDescent: Int = Int.MIN_VALUE

        var firstAscentDiff: Int = 0
            private set
        var lastDescentDiff: Int = 0
            private set

        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanStartVertical: Int,
            lineHeight: Int,
            fontMetricsInt: Paint.FontMetricsInt,
        ) {
            val currentHeight = fontMetricsInt.descent - fontMetricsInt.ascent
            if (currentHeight <= 0) return

            val isFirstLine = (start == startIndex)
            val isLastLine = (end == endIndex)

            if (isFirstLine &&
                isLastLine &&
                trimFirstLineTop &&
                trimLastLineBottom &&
                mode != LineHeightStyle.Mode.Tight
            ) {
                return
            }

            if (firstAscent == Int.MIN_VALUE) {
                calculateTargetMetrics(fontMetricsInt, currentHeight)
            }

            fontMetricsInt.ascent = if (isFirstLine) firstAscent else ascent
            fontMetricsInt.descent = if (isLastLine) lastDescent else descent
        }

        private fun calculateTargetMetrics(
            fontMetricsInt: Paint.FontMetricsInt,
            currentHeight: Int,
        ) {
            val ceiledLineHeight = ceil(lineHeight).toInt()
            val diff = ceiledLineHeight - currentHeight
            if (mode == LineHeightStyle.Mode.Minimum && diff <= 0) {
                ascent = fontMetricsInt.ascent
                descent = fontMetricsInt.descent
                firstAscent = ascent
                lastDescent = descent
                firstAscentDiff = 0
                lastDescentDiff = 0
                return
            }

            val ascentRatio = if (topRatio == -1f) {
                abs(fontMetricsInt.ascent.toFloat()) / currentHeight
            } else {
                topRatio
            }

            val descentDiff = if (diff <= 0) {
                ceil(diff * ascentRatio).toInt()
            } else {
                ceil(diff * (1f - ascentRatio)).toInt()
            }

            descent = fontMetricsInt.descent + descentDiff
            ascent = descent - ceiledLineHeight

            if (mode == LineHeightStyle.Mode.Fixed || diff >= 0) {
                firstAscent = if (trimFirstLineTop) fontMetricsInt.ascent else ascent
                lastDescent = if (trimLastLineBottom) fontMetricsInt.descent else descent
                firstAscentDiff = fontMetricsInt.ascent - firstAscent
                lastDescentDiff = lastDescent - fontMetricsInt.descent
            } else if (mode == LineHeightStyle.Mode.Tight) {
                firstAscent = if (trimFirstLineTop) {
                    maxOf(fontMetricsInt.ascent, ascent)
                } else {
                    minOf(fontMetricsInt.ascent, ascent)
                }
                lastDescent = if (trimLastLineBottom) {
                    minOf(fontMetricsInt.descent, descent)
                } else {
                    maxOf(fontMetricsInt.descent, descent)
                }
                firstAscentDiff = 0
                lastDescentDiff = 0
            }
        }
    }

    private data class TypefaceKey(
        val family: FontFamily?,
        val weight: Int,
        val style: FontStyle,
        val synthesis: FontSynthesis,
    )

    private data class BaseTypographyKey(
        val family: FontFamily,
        val scaledSizeBits: Int,
    )

    private class CachedBaseTypography(
        val resolvedFamily: FontFamily,
    )

    /** Per-thread lock-free scratch state; holds no reference to [FastParagraphMeasurer]. */
    private class WorkerScratch {
        val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            flags = flags and Paint.SUBPIXEL_TEXT_FLAG.inv() and Paint.LINEAR_TEXT_FLAG.inv()
            hinting = Paint.HINTING_ON
        }
        val defaultTextLocales: android.os.LocaleList = paint.textLocales

        private val typefaceCache = HashMap<TypefaceKey, Typeface>(16)
        private var lastTfFamily: FontFamily? = null
        private var lastTfWeight: Int = -1
        private var lastTfStyle: FontStyle = FontStyle.Normal
        private var lastTfSynthesis: FontSynthesis = FontSynthesis.All
        private var lastTfValue: Typeface? = null

        private val localeCache = HashMap<LocaleList, android.os.LocaleList>(8)
        private var lastLocaleKey: LocaleList? = null
        private var lastLocaleValue: android.os.LocaleList? = null

        private val baseTypographyCache = HashMap<BaseTypographyKey, CachedBaseTypography>(8)
        private var lastBaseFamily: FontFamily? = null
        private var lastBaseSizeBits: Int = 0
        private var lastBaseEntry: CachedBaseTypography? = null

        private var lastInputStyle: TextStyle? = null
        private var lastResolvedFamily: FontFamily? = null
        private var lastResolvedStyle: TextStyle? = null

        fun lookupTypeface(
            family: FontFamily?,
            weight: Int,
            style: FontStyle,
            synthesis: FontSynthesis,
        ): Typeface? {
            val cached = lastTfValue
            if (cached != null &&
                lastTfFamily === family &&
                lastTfWeight == weight &&
                lastTfStyle == style &&
                lastTfSynthesis == synthesis
            ) {
                return cached
            }
            val found = typefaceCache[TypefaceKey(family, weight, style, synthesis)] ?: return null
            lastTfFamily = family
            lastTfWeight = weight
            lastTfStyle = style
            lastTfSynthesis = synthesis
            lastTfValue = found
            return found
        }

        fun storeTypeface(
            family: FontFamily?,
            weight: Int,
            style: FontStyle,
            synthesis: FontSynthesis,
            typeface: Typeface,
        ) {
            if (typefaceCache.size >= 64) typefaceCache.clear()
            typefaceCache[TypefaceKey(family, weight, style, synthesis)] = typeface
            lastTfFamily = family
            lastTfWeight = weight
            lastTfStyle = style
            lastTfSynthesis = synthesis
            lastTfValue = typeface
        }

        fun resolveAndroidLocales(localeList: LocaleList): android.os.LocaleList {
            if (lastLocaleKey === localeList) {
                lastLocaleValue?.let { return it }
            }
            val resolved = localeCache[localeList] ?: run {
                if (localeCache.size >= 32) localeCache.clear()
                val platformLocales = Array(localeList.size) { index ->
                    localeList[index].platformLocale
                }
                android.os.LocaleList(*platformLocales).also { localeCache[localeList] = it }
            }
            lastLocaleKey = localeList
            lastLocaleValue = resolved
            return resolved
        }

        fun lookupBaseTypography(
            baseFamily: FontFamily,
            scaledSizeBits: Int,
        ): CachedBaseTypography? {
            val last = lastBaseEntry
            if (last != null && lastBaseFamily === baseFamily && lastBaseSizeBits == scaledSizeBits) {
                return last
            }
            val found = baseTypographyCache[BaseTypographyKey(baseFamily, scaledSizeBits)] ?: return null
            lastBaseFamily = baseFamily
            lastBaseSizeBits = scaledSizeBits
            lastBaseEntry = found
            return found
        }

        fun storeBaseTypography(
            baseFamily: FontFamily,
            scaledSizeBits: Int,
            entry: CachedBaseTypography,
        ) {
            if (baseTypographyCache.size >= 32) baseTypographyCache.clear()
            baseTypographyCache[BaseTypographyKey(baseFamily, scaledSizeBits)] = entry
            lastBaseFamily = baseFamily
            lastBaseSizeBits = scaledSizeBits
            lastBaseEntry = entry
        }

        fun resolveStyleWithFamily(style: TextStyle, resolvedFamily: FontFamily): TextStyle {
            if (style.fontFamily === resolvedFamily || style.fontFamily == resolvedFamily) {
                return style
            }
            val cached = lastResolvedStyle
            if (cached != null && lastInputStyle === style && lastResolvedFamily === resolvedFamily) {
                return cached
            }
            val updated = style.copy(fontFamily = resolvedFamily)
            lastInputStyle = style
            lastResolvedFamily = resolvedFamily
            lastResolvedStyle = updated
            return updated
        }

        fun reusePlan(text: AnnotatedString, style: TextStyle): ReaderTypographyPlan =
            ReaderTypographyPlan(text, style)
    }
}

private fun resolveTextDirectionHeuristic(
    textDirection: TextDirection,
    layoutDirection: LayoutDirection,
): TextDirectionHeuristic? {
    val resolved = when (textDirection) {
        TextDirection.Content -> if (layoutDirection == LayoutDirection.Ltr) {
            TextDirection.ContentOrLtr
        } else {
            TextDirection.ContentOrRtl
        }
        TextDirection.Unspecified -> if (layoutDirection == LayoutDirection.Ltr) {
            TextDirection.Ltr
        } else {
            TextDirection.Rtl
        }
        else -> textDirection
    }
    return when (resolved) {
        TextDirection.Ltr -> TextDirectionHeuristics.LTR
        TextDirection.Rtl -> TextDirectionHeuristics.RTL
        TextDirection.ContentOrLtr -> TextDirectionHeuristics.FIRSTSTRONG_LTR
        TextDirection.ContentOrRtl -> TextDirectionHeuristics.FIRSTSTRONG_RTL
        else -> null
    }
}

private fun resolveLineHeightInPx(
    lineHeight: TextUnit,
    contextFontSizePx: Float,
    density: Density,
): Float = when (lineHeight.type) {
    TextUnitType.Sp -> {
        if (density.fontScale <= 1.05) {
            with(density) { lineHeight.toPx() }
        } else {
            val fontSizeSp = with(density) { contextFontSizePx.toSp() }
            val lineHeightMultiplier = lineHeight.value / fontSizeSp.value
            lineHeightMultiplier * contextFontSizePx
        }
    }
    TextUnitType.Em -> lineHeight.value * contextFontSizePx
    else -> Float.NaN
}

/**
 * Falls back to Compose when text contains characters that EmojiCompat might
 * replace with EmojiSpan (surrogate pairs, emoji symbols, variation selector 16,
 * or keycap combiner).
 */
private fun containsPotentialEmoji(text: String): Boolean {
    for (i in 0 until text.length) {
        val c = text[i]
        if (c >= '\u2600') {
            if (c <= '\u27BF' || Character.isSurrogate(c) || c == '\uFE0F') return true
        } else if (c == '\u20E3') {
            return true
        }
    }
    return false
}

@RequiresApi(33)
private object FastStaticLayoutApi33 {
    private val paragraphConfig: LineBreakConfig = LineBreakConfig.Builder()
        .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
        .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
        .build()

    private val simpleConfig: LineBreakConfig = LineBreakConfig.Builder()
        .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NORMAL)
        .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
        .build()

    @JvmStatic
    fun setLineBreakConfig(builder: StaticLayout.Builder, isParagraph: Boolean) {
        builder.setLineBreakConfig(if (isParagraph) paragraphConfig else simpleConfig)
    }

    @JvmStatic
    fun isFallbackLineSpacingEnabled(layout: StaticLayout): Boolean =
        layout.isFallbackLineSpacingEnabled
}

@RequiresApi(35)
private object FastStaticLayoutApi35 {
    @JvmStatic
    fun disableUseBoundsForWidth(builder: StaticLayout.Builder) {
        builder.setUseBoundsForWidth(false)
    }
}

private fun StaticLayout.Builder.applyBreakStrategy(
    highQuality: Boolean,
): StaticLayout.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    setBreakStrategy(
        if (highQuality) {
            LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        } else {
            LineBreaker.BREAK_STRATEGY_SIMPLE
        },
    )
} else {
    applyLegacyBreakStrategy(highQuality)
}

@SuppressLint("WrongConstant")
private fun StaticLayout.Builder.applyLegacyBreakStrategy(
    highQuality: Boolean,
): StaticLayout.Builder = setBreakStrategy(
    if (highQuality) {
        Layout.BREAK_STRATEGY_HIGH_QUALITY
    } else {
        Layout.BREAK_STRATEGY_SIMPLE
    },
)
