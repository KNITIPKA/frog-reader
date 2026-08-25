package com.example.frogreader.ui.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.reader.selection.ReaderHighlights
import com.example.frogreader.ui.reader.selection.readerHighlights
import com.example.frogreader.ui.reader.selection.rememberTextFragment
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * "Side box" paragraph shaping: a drop cap (pseudo or explicit float) or a
 * small floated image sits beside the paragraph's first lines; the rest of
 * the text continues at full width. The plan is computed once with the
 * pagination's measurer and stored in the page part, so measure and render
 * agree to the pixel. Gated behind the publisher's-formatting toggle.
 */
class SideBoxSpec(
    /** Cap composite when non-null (the box is this text drawn large). */
    val capText: String?,
    /** Image composite when non-null (absolute file path). */
    val imagePath: String?,
    val leftSide: Boolean,
    val boxWidthPx: Int,
    val boxHeightPx: Int,
    /** Exact width of the text laid beside the box. */
    val besideWidthPx: Int,
    /** Absolute element-text offset where full-width text resumes. */
    val besideEndChar: Int,
    val compositeHeightPx: Int,
    /** The cap glyph's exact font size, geometry-fitted to the box. */
    val capFontSizeSp: Float = 0f,
    /**
     * Y where the cap's Text is placed so its BASELINE lands exactly on
     * the Nth beside-line's baseline (may be negative: the text box's
     * inkless headroom then pokes above the composite).
     */
    val capTopPx: Int = 0,
)

/**
 * Plans the composite for [paragraph], or null when nothing applies —
 * no drop cap/float, publisher formatting off, or a degenerate geometry.
 */
fun planSideBox(
    paragraph: ContentElement.Paragraph,
    measurer: TextMeasurer,
    density: Density,
    settings: ReaderSettings,
    fontSize: Float,
    bookFonts: Map<String, FontFamily>,
    language: String?,
    widthPx: Int,
): SideBoxSpec? = with(density) {
    // Drop caps have their own switch; text wrapping around a floated
    // picture stays part of the publisher's full formatting.
    if (!settings.bookStyles && !settings.dropCaps) return null
    val block = paragraph.block ?: return null
    val gapPx = (fontSize * 0.4f).dp.roundToPx()

    if (settings.bookStyles) block.floatImage?.let { float ->
        val aspect = imageAspectRatio(float.path) ?: return null
        val boxW = (widthPx * float.widthFrac.coerceIn(0.1f, 0.45f)).roundToInt()
        val boxH = (boxW * aspect).roundToInt()
            .coerceAtMost((widthPx * 0.9f).roundToInt())
        return planBeside(
            paragraph, measurer, settings, fontSize, bookFonts, language,
            widthPx, capText = null, imagePath = float.path, leftSide = float.left,
            boxW = boxW, boxH = boxH, gapPx = gapPx,
        )
    }

    val cap = block.firstLetter?.takeIf { it.isDropCap } ?: return null
    val capText = SideBoxRules.capPrefix(
        paragraph.text.text,
        cap.sourceTextLength,
    ) ?: return null
    val besideStyle = ReaderMetrics.textStyle(
        paragraph, settings, fontSize,
        isParagraphStart = false, bookFonts = bookFonts, language = language,
    )
    val rest = paragraph.text.subSequence(capText.length, paragraph.text.length)
    if (rest.text.isBlank()) return null

    // Classic print geometry: the cap's VISIBLE top aligns with the first
    // line's capital tops, its BASELINE lands exactly on the Nth line's
    // baseline (N from the CSS scale). Baselines are measured precisely;
    // only the caps' visible height uses the ~0.72 cap-height ratio.
    val referenceSp = fontSize * cap.scale.coerceIn(1.8f, 4f)
    val reference = measurer.measure(
        text = AnnotatedString(capText),
        style = ReaderMetrics.dropCapStyle(settings, referenceSp, cap, bookFonts, language),
        constraints = Constraints(),
    )
    if (reference.size.height <= 0 || reference.size.width <= 0) return null

    val probeWidth = (widthPx - reference.size.width - gapPx)
        .coerceAtLeast((fontSize * 4).toInt())
    val bidiRest = BidiLayoutText.of(rest)
    val probe = measurer.measure(
        text = bidiRest.display,
        style = besideStyle,
        constraints = Constraints(maxWidth = probeWidth),
    )
    if (probe.lineCount == 0) return null
    val lines = Math.round(cap.scale).coerceIn(2, 4).coerceAtMost(probe.lineCount)
    // Line heights are style-fixed, so baselines don't depend on width.
    val baselineFirst = probe.getLineBaseline(0)
    val baselineLast = probe.getLineBaseline(lines - 1)
    val boxH = kotlin.math.floor(baselineLast).toInt()
    if (boxH <= 0) return null

    // From the first line's capital tops down to the Nth baseline.
    val bodyFontPx = fontSize.sp.toPx()
    val lineOneCapTop = baselineFirst - CAP_HEIGHT_RATIO * bodyFontPx
    val targetVisualPx = baselineLast - lineOneCapTop
    if (targetVisualPx <= 0f) return null
    val capSp = (fontSize * targetVisualPx / (CAP_HEIGHT_RATIO * bodyFontPx))
        .coerceIn(fontSize, fontSize * 8f)
    val capLayout = measurer.measure(
        text = AnnotatedString(capText),
        style = ReaderMetrics.dropCapStyle(settings, capSp, cap, bookFonts, language),
        constraints = Constraints(),
    )
    if (capLayout.size.width <= 0) return null
    // Place the cap so its baseline sits on the Nth line's baseline.
    val capTopPx = (baselineLast - capLayout.firstBaseline).roundToInt()
    return planBeside(
        paragraph, measurer, settings, fontSize, bookFonts, language,
        widthPx, capText = capText, imagePath = null, leftSide = cap.leftSide,
        boxW = capLayout.size.width, boxH = boxH, gapPx = gapPx,
        capFontSizeSp = capSp, capTopPx = capTopPx,
    )
}

/** Visible capital height as a fraction of the font size (serif ≈ 0.72). */
private const val CAP_HEIGHT_RATIO = 0.72f

private fun planBeside(
    paragraph: ContentElement.Paragraph,
    measurer: TextMeasurer,
    settings: ReaderSettings,
    fontSize: Float,
    bookFonts: Map<String, FontFamily>,
    language: String?,
    widthPx: Int,
    capText: String?,
    imagePath: String?,
    leftSide: Boolean,
    boxW: Int,
    boxH: Int,
    gapPx: Int,
    capFontSizeSp: Float = 0f,
    capTopPx: Int = 0,
): SideBoxSpec? {
    if (boxW <= 0 || boxH <= 0) return null
    val besideW = widthPx - boxW - gapPx
    // Fewer than ~4 characters per line beside the box reads terribly.
    if (besideW < (fontSize * 4).toInt()) return null

    val capLen = capText?.length ?: 0
    val rest = paragraph.text.subSequence(capLen, paragraph.text.length)
    if (rest.text.isBlank()) return null
    val besideStyle = ReaderMetrics.textStyle(
        paragraph, settings, fontSize,
        isParagraphStart = false, bookFonts = bookFonts, language = language,
    )
    val bidiRest = BidiLayoutText.of(rest)
    val layout = measurer.measure(
        text = bidiRest.display,
        style = besideStyle,
        constraints = Constraints(maxWidth = besideW.coerceAtLeast(1)),
    )
    if (layout.lineCount == 0) return null
    val bottoms = FloatArray(layout.lineCount) { layout.getLineBottom(it) }
    val lines = SideBoxRules.besideLineCount(bottoms, boxH)
    if (lines <= 0) return null

    val besideEndRelative: Int
    val compositeH: Int
    if (lines >= layout.lineCount) {
        // The whole paragraph fits beside the box.
        besideEndRelative = rest.length
        compositeH = maxOf(boxH, ceil(layout.getLineBottom(layout.lineCount - 1)).toInt())
    } else {
        var end = bidiRest.sourceOffset(layout.getLineEnd(lines - 1))
        retreatToWordBoundary(rest.text, 0, end)
            .takeIf { it > 0 }
            ?.let { end = it }
        if (end <= 0) return null
        // Standalone re-measure: a fragment's own first/last-line font
        // paddings differ from the big layout's interior lines.
        val fragment = rest.subSequence(0, end)
        val bidiFragment = BidiLayoutText.of(fragment)
        val fragmentHeight = measurer.measure(
            text = bidiFragment.display,
            style = besideStyle,
            constraints = Constraints(maxWidth = besideW.coerceAtLeast(1)),
        ).size.height
        besideEndRelative = end
        compositeH = maxOf(boxH, fragmentHeight)
    }
    return SideBoxSpec(
        capText = capText,
        imagePath = imagePath,
        leftSide = leftSide,
        boxWidthPx = boxW,
        boxHeightPx = boxH,
        besideWidthPx = besideW,
        besideEndChar = capLen + besideEndRelative,
        compositeHeightPx = compositeH,
        capFontSizeSp = capFontSizeSp,
        capTopPx = capTopPx,
    )
}

/**
 * Draws the composite: the box (cap glyph or image) on its side, the
 * beside-text at the exact stored pixel width. Children are composed in
 * reading order (cap first) so text selection reads naturally.
 *
 * Both texts register as selection fragments. The cap is a `Text` of its own
 * holding the paragraph's first characters, so without it a selection running
 * through a drop-capped paragraph would have a hole exactly where the
 * paragraph begins.
 */
@Composable
fun SideBoxComposite(
    element: ContentElement.Paragraph,
    sideBox: SideBoxSpec,
    besideText: AnnotatedString,
    settings: ReaderSettings,
    fontSize: Float,
    bookFonts: Map<String, FontFamily>,
    language: String?,
    colors: ReaderColors,
    totalWidthPx: Int,
    invertImages: Boolean = false,
    highlights: ReaderHighlights? = null,
    itemIndex: Int = -1,
    modifier: Modifier = Modifier,
) {
    val blockColors = publisherColorPair(
        element.block,
        settings.bookStyles,
        colors.text,
        colors.background,
    )
    val besideStyle = ReaderMetrics.textStyle(
        element, settings, fontSize,
        isParagraphStart = false, bookFonts = bookFonts, language = language,
    ).copy(color = blockColors.foreground)

    // The cap eats the paragraph's first characters; the beside-text picks up
    // exactly where it ends — in both reading modes (paged pagination stores
    // the same offset in PagePart.charStart).
    val capLength = sideBox.capText?.length ?: 0
    val capFragment = if (sideBox.capText != null) {
        rememberTextFragment(highlights, itemIndex, 0, capLength)
    } else {
        null // a floated image occupies no characters
    }
    val bidiBeside = remember(besideText) { BidiLayoutText.of(besideText) }
    val besideFragment = rememberTextFragment(
        highlights, itemIndex, capLength, besideText.length, bidiBeside,
    )

    Layout(
        modifier = modifier,
        content = {
            if (sideBox.capText != null) {
                val cap = element.block?.firstLetter
                val capColors = publisherColorPair(
                    foregroundArgb = cap?.foregroundColorArgb
                        ?: element.block?.foregroundColorArgb,
                    backgroundArgb = cap?.backgroundColorArgb,
                    enabled = settings.bookStyles,
                    defaultForeground = blockColors.foreground,
                    surroundingBackground = blockColors.effectiveBackground,
                )
                val capSize = sideBox.capFontSizeSp.takeIf { it > 0f }
                    ?: (fontSize * (cap?.scale ?: 2.6f).coerceIn(1.8f, 4f))
                Text(
                    text = sideBox.capText,
                    style = ReaderMetrics
                        .dropCapStyle(settings, capSize, cap, bookFonts, language)
                        .copy(
                            color = capColors.foreground,
                            background = capColors.background ?: androidx.compose.ui.graphics.Color.Unspecified,
                        ),
                    softWrap = false,
                    onTextLayout = { capFragment?.layout = it },
                    modifier = Modifier.readerHighlights(capFragment, highlights),
                )
            } else {
                AsyncImage(
                    model = sideBox.imagePath?.let { File(it) },
                    contentDescription = element.block?.floatImage?.altText,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter(invertImages),
                )
            }
            Text(
                text = bidiBeside.display,
                style = besideStyle,
                onTextLayout = { besideFragment?.layout = it },
                modifier = Modifier.readerHighlights(besideFragment, highlights),
            )
        },
    ) { measurables, _ ->
        val boxPlaceable = measurables[0].measure(
            if (sideBox.capText != null) {
                Constraints() // the glyph's natural size (matches the plan)
            } else {
                Constraints.fixed(
                    sideBox.boxWidthPx.coerceAtLeast(1),
                    sideBox.boxHeightPx.coerceAtLeast(1),
                )
            },
        )
        val besideWidth = sideBox.besideWidthPx.coerceAtLeast(1)
        val besidePlaceable = measurables[1].measure(
            Constraints(
                minWidth = besideWidth,
                maxWidth = besideWidth,
                maxHeight = sideBox.compositeHeightPx.coerceAtLeast(1),
            ),
        )
        layout(totalWidthPx, sideBox.compositeHeightPx) {
            // A cap is placed by its stored baseline offset (its inkless
            // headroom may poke above y=0); images sit at the top.
            val boxTop = if (sideBox.capText != null) sideBox.capTopPx else 0
            if (sideBox.leftSide) {
                boxPlaceable.place(0, boxTop)
                besidePlaceable.place(totalWidthPx - besideWidth, 0)
            } else {
                boxPlaceable.place(totalWidthPx - sideBox.boxWidthPx, boxTop)
                besidePlaceable.place(0, 0)
            }
        }
    }
}
