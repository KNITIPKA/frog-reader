package com.example.frogreader.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import kotlinx.coroutines.yield
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** A fragment of one element placed on a page. */
class PagePart(
    val itemIndex: Int,
    val element: ContentElement,
    /** For text elements: the exact (sub)text this part draws. */
    val text: AnnotatedString? = null,
    /** False for continuations of a paragraph split across pages. */
    val isParagraphStart: Boolean = true,
    /** For images: the display height in px chosen during measurement. */
    val imageHeightPx: Int? = null,
    /** Character range of [text] inside the element (for the disk cache). */
    val charStart: Int = -1,
    val charEnd: Int = -1,
    /** For tables: the row range this part draws and its measured layout. */
    val rowStart: Int = -1,
    val rowEnd: Int = -1,
    val tableLayout: TableLayout? = null,
    /** True when the header row is repeated above a table continuation. */
    val headerRepeated: Boolean = false,
    /** Drop cap / floated image composite at a paragraph's start. */
    val sideBox: SideBoxSpec? = null,
    /** A float rendered as a plain block image (publisher formatting off). */
    val floatImagePath: String? = null,
)

class BookPage(
    val parts: List<PagePart>,
    /** Flat item index the page starts at — used for progress mapping. */
    val firstItemIndex: Int,
    /**
     * Character offset inside [firstItemIndex]'s text where the page starts
     * (0 unless a paragraph is split across pages). Anchoring the reading
     * position to the character keeps the same text on screen when settings
     * change and the book is re-paginated with different page breaks.
     */
    val firstCharOffset: Int = 0,
)

/**
 * Bumped whenever pagination decisions or parser output that affects layout
 * change — stale disk caches then miss on the key and are recomputed.
 */
const val LAYOUT_ENGINE_VERSION = 8

class PaginationSpec(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val density: Density,
    val settings: ReaderSettings,
    val fontSize: Float,
    /** The book's embedded font families (publisher's formatting mode). */
    val bookFonts: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
    /** The book's language tag — it changes hyphenation, hence line breaks. */
    val language: String? = null,
) {
    /** Cache key: anything that changes layout must be part of it. */
    val key: String =
        "e$LAYOUT_ENGINE_VERSION | $contentWidthPx x $contentHeightPx | " +
            "$fontSize | ${settings.lineHeight} | " +
            "${settings.font} | ${settings.customFontPath} | " +
            "${settings.justify} | ${settings.hyphenation} | " +
            "${settings.startChaptersOnNewPage} | ${settings.hideFootnotes} | " +
            "${settings.bookStyles} | ${settings.dropCaps} | lang=$language"
}

/**
 * Splits the book into fixed pages. Runs off the main thread; text is
 * measured once per element and long paragraphs are cut at line boundaries
 * (never mid-word — the cut retreats to the previous space).
 *
 * [fromIndex]/[toIndex] limit the pass to a slice of the book (the quick
 * current-chapter pass after a settings change). Page splits are computed
 * sequentially from a fresh page, so a slice that starts where the full pass
 * also starts a page (a chapter start, with chapters-on-new-page enabled)
 * produces byte-identical pages — swapping the full result in later is
 * invisible.
 */
suspend fun paginateBook(
    items: List<ReaderItem>,
    measurer: TextMeasurer,
    spec: PaginationSpec,
    fromIndex: Int = 0,
    toIndex: Int = items.size,
): List<BookPage> {
    val pages = mutableListOf<BookPage>()
    var parts = mutableListOf<PagePart>()
    var usedPx = 0
    var pageFirstIndex = 0
    var pageFirstChar = 0

    fun closePage() {
        if (parts.isNotEmpty()) {
            pages += BookPage(parts.toList(), pageFirstIndex, pageFirstChar)
            parts = mutableListOf()
        }
        usedPx = 0
    }

    fun addPart(part: PagePart, heightPx: Int, index: Int, charStart: Int = 0) {
        if (parts.isEmpty()) {
            pageFirstIndex = index
            pageFirstChar = charStart
        }
        parts += part
        usedPx += heightPx
    }

    with(spec.density) {
        val maxImageHeightPx = ReaderMetrics.maxImageHeight.roundToPx()
        val contentWidthDp = spec.contentWidthPx.toDp()

        /**
         * Height of the content a heading must keep on its page (the first
         * lines of the following block), or 0 when nothing constrains it.
         * Reads only the shared [items] list — identical in both passes.
         */
        fun keepWithNextPx(headingIndex: Int): Int {
            var interPx = 0
            var j = headingIndex + 1
            val limit = min(headingIndex + 4, items.size)
            while (j < limit) {
                val next = items[j]
                if (spec.settings.startChaptersOnNewPage &&
                    next.chapterIndex != items[headingIndex].chapterIndex
                ) {
                    return 0 // a forced chapter break follows anyway
                }
                val (nTop, nBottom) = ReaderMetrics.verticalPaddings(next.element, spec.fontSize)
                when (val el = next.element) {
                    is ContentElement.Table -> {
                        // A table after a heading: keep two text lines' worth.
                        return interPx + nTop.roundToPx() +
                            (spec.fontSize * spec.settings.lineHeight * 2).dp.roundToPx()
                    }

                    is ContentElement.Spacer ->
                        interPx += ReaderMetrics.spacerHeight(el, spec.fontSize).roundToPx()

                    ContentElement.Divider ->
                        interPx += ReaderMetrics.dividerHeight.roundToPx() +
                            nTop.roundToPx() + nBottom.roundToPx()

                    is ContentElement.Image -> {
                        val aspect = imageAspectRatio(el.path) ?: 1.4f
                        val imageTotal = min(
                            (spec.contentWidthPx * aspect).roundToInt(),
                            maxImageHeightPx,
                        ) + nTop.roundToPx()
                        // A sliver of the image counts — a full-page picture
                        // must not permanently exile the heading.
                        val twoLines =
                            (spec.fontSize * spec.settings.lineHeight * 2).dp.roundToPx()
                        return interPx + min(imageTotal, twoLines)
                    }

                    is ContentElement.Paragraph, is ContentElement.Heading -> {
                        val text = when (el) {
                            is ContentElement.Paragraph -> el.text
                            is ContentElement.Heading -> el.styledText
                            else -> return 0
                        }
                        if (text.text.isBlank()) {
                            j++
                            continue
                        }
                        val (sInset, eInset) =
                            ReaderMetrics.horizontalInsets(el, contentWidthDp, spec.fontSize)
                        val w = (spec.contentWidthPx - sInset.roundToPx() - eInset.roundToPx())
                            .coerceAtLeast(1)
                        val nextLayout = measurer.measure(
                            text = text,
                            style = ReaderMetrics.textStyle(
                                el, spec.settings, spec.fontSize,
                                bookFonts = spec.bookFonts,
                                language = spec.language,
                            ),
                            constraints = Constraints(maxWidth = w),
                            placeholders = inlineImagePlaceholders(text),
                        )
                        // A following heading moves whole; a paragraph must
                        // contribute at least its first two lines.
                        val keepLines = if (el is ContentElement.Heading) {
                            nextLayout.lineCount - 1
                        } else {
                            min(BreakRules.MIN_ORPHAN_LINES - 1, nextLayout.lineCount - 1)
                        }
                        return interPx + nTop.roundToPx() +
                            ceil(nextLayout.getLineBottom(keepLines)).toInt()
                    }
                }
                j++
            }
            return 0
        }

        for (index in fromIndex.coerceAtLeast(0) until toIndex.coerceAtMost(items.size)) {
            val item = items[index]
            if (index % 32 == 0) yield()

            // Optionally start every chapter on a fresh page.
            if (spec.settings.startChaptersOnNewPage &&
                index > 0 &&
                item.chapterIndex != items[index - 1].chapterIndex &&
                parts.isNotEmpty()
            ) {
                closePage()
            }

            val element = item.element
            // The book's own forced break (CSS page-break-before: always).
            val elementBlock = when (element) {
                is ContentElement.Paragraph -> element.block
                is ContentElement.Heading -> element.block
                else -> null
            }
            if (elementBlock?.pageBreakBefore == true && parts.isNotEmpty()) closePage()

            val (vTop, vBottom) = ReaderMetrics.verticalPaddings(element, spec.fontSize)
            val vPaddingPx = vTop.roundToPx() + vBottom.roundToPx()

            when (element) {
                is ContentElement.Paragraph, is ContentElement.Heading -> {
                    val fullText = when (element) {
                        is ContentElement.Paragraph -> element.text
                        is ContentElement.Heading -> element.styledText
                    }
                    if (fullText.text.isBlank()) continue

                    val (startInset, endInset) =
                        ReaderMetrics.horizontalInsets(element, contentWidthDp, spec.fontSize)
                    val widthPx =
                        spec.contentWidthPx - startInset.roundToPx() - endInset.roundToPx()
                    val layout = measurer.measure(
                        text = fullText,
                        style = ReaderMetrics.textStyle(
                            element, spec.settings, spec.fontSize,
                            bookFonts = spec.bookFonts,
                            language = spec.language,
                        ),
                        constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
                        placeholders = inlineImagePlaceholders(fullText),
                    )

                    // Headings: never split across pages, and never strand at
                    // a page bottom without the first lines of what follows.
                    if (element is ContentElement.Heading && parts.isNotEmpty()) {
                        val ownHeight = ceil(layout.getLineBottom(layout.lineCount - 1)).toInt()
                        val fitsSomePage = ownHeight + vPaddingPx <= spec.contentHeightPx
                        val remainingHere = spec.contentHeightPx - usedPx - vPaddingPx
                        if (fitsSomePage &&
                            (
                                ownHeight > remainingHere ||
                                    !BreakRules.headingFits(
                                        remainingAfterHeadingPx = remainingHere - ownHeight,
                                        requiredNextPx = keepWithNextPx(index),
                                        pageHasOtherContent = true,
                                    )
                                )
                        ) {
                            closePage()
                        }
                    }

                    // Side-box composites (drop caps, floated images) consume
                    // the paragraph's first characters; the loop below then
                    // packs the remainder from a fresh full-width layout.
                    var packedText = fullText
                    var packLayout = layout
                    var packBase = 0
                    var firstPart = true

                    if (element is ContentElement.Paragraph) {
                        val floatImage = element.block?.floatImage
                        if (floatImage != null && !spec.settings.bookStyles) {
                            // Publisher formatting off: the float is a plain
                            // block image above its paragraph.
                            val aspect = imageAspectRatio(floatImage.path) ?: 1.4f
                            val imageH = min(
                                (spec.contentWidthPx * aspect).roundToInt(),
                                maxImageHeightPx,
                            )
                            val imagePadPx = 12.dp.roundToPx() * 2
                            val total = imageH + imagePadPx
                            if (total > spec.contentHeightPx - usedPx && parts.isNotEmpty()) {
                                closePage()
                            }
                            addPart(
                                PagePart(
                                    itemIndex = index,
                                    element = element,
                                    imageHeightPx = min(
                                        imageH,
                                        spec.contentHeightPx - imagePadPx,
                                    ),
                                    floatImagePath = floatImage.path,
                                ),
                                heightPx = total,
                                index = index,
                                charStart = 0,
                            )
                        }
                        val sideBox = planSideBox(
                            element, measurer, spec.density, spec.settings,
                            spec.fontSize, spec.bookFonts, spec.language, widthPx,
                        )
                        if (sideBox != null) {
                            val compositeTotal = sideBox.compositeHeightPx + vPaddingPx
                            if (compositeTotal > spec.contentHeightPx - usedPx &&
                                parts.isNotEmpty()
                            ) {
                                closePage()
                            }
                            val capLen = sideBox.capText?.length ?: 0
                            addPart(
                                PagePart(
                                    itemIndex = index,
                                    element = element,
                                    text = fullText.subSequence(
                                        capLen, sideBox.besideEndChar,
                                    ),
                                    isParagraphStart = true,
                                    charStart = capLen,
                                    charEnd = sideBox.besideEndChar,
                                    sideBox = sideBox,
                                ),
                                heightPx = compositeTotal,
                                index = index,
                                charStart = 0,
                            )
                            if (sideBox.besideEndChar >= fullText.length) continue
                            packBase = sideBox.besideEndChar
                            firstPart = false
                            packedText = fullText.subSequence(packBase, fullText.length)
                            packLayout = measurer.measure(
                                text = packedText,
                                style = ReaderMetrics.textStyle(
                                    element, spec.settings, spec.fontSize,
                                    isParagraphStart = false,
                                    bookFonts = spec.bookFonts,
                                    language = spec.language,
                                ),
                                constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
                                placeholders = inlineImagePlaceholders(packedText),
                            )
                        }
                    }

                    val raw = packedText.text
                    var cursor = 0
                    while (cursor < raw.length) {
                        // Skip whitespace a previous cut left behind.
                        while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
                        if (cursor >= raw.length) break

                        val startLine = packLayout.getLineForOffset(cursor)
                        val topOffset = packLayout.getLineTop(startLine)
                        val remaining = spec.contentHeightPx - usedPx - vPaddingPx

                        // How many lines fit into the remaining page height?
                        var endLine = startLine
                        while (
                            endLine + 1 < packLayout.lineCount &&
                            packLayout.getLineBottom(endLine + 1) - topOffset <= remaining
                        ) {
                            endLine++
                        }

                        // A fragment draws as its own Text, whose first/last
                        // lines carry font paddings the big layout's interior
                        // lines don't. Measure the exact fragment standalone —
                        // the height on screen — and drop trailing lines until
                        // it truly fits, or the last line gets clipped.
                        val fragmentStyle = ReaderMetrics.textStyle(
                            element, spec.settings, spec.fontSize,
                            isParagraphStart = firstPart,
                            bookFonts = spec.bookFonts,
                            language = spec.language,
                        )
                        var endChar: Int
                        var fragmentHeight: Int
                        while (true) {
                            val lastLine = endLine >= packLayout.lineCount - 1
                            endChar = if (lastLine) raw.length else packLayout.getLineEnd(endLine)
                            if (!lastLine) {
                                // Never cut mid-word (auto-hyphenation breaks words).
                                endChar = retreatToWordBoundary(raw, cursor, endChar)
                                if (endChar <= cursor) endChar = packLayout.getLineEnd(endLine)
                            }
                            fragmentHeight = if (cursor == 0 && lastLine) {
                                // Whole paragraph: the big layout IS standalone.
                                ceil(packLayout.getLineBottom(endLine)).toInt()
                            } else {
                                val fragment = packedText.subSequence(cursor, endChar)
                                measurer.measure(
                                    text = fragment,
                                    style = fragmentStyle,
                                    constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
                                    placeholders = inlineImagePlaceholders(fragment),
                                ).size.height
                            }
                            if (fragmentHeight <= remaining || endLine <= startLine) break
                            endLine--
                        }

                        if (fragmentHeight > remaining && parts.isNotEmpty()) {
                            // Doesn't fit on this page — retry on a fresh one.
                            closePage()
                            continue
                        }
                        if (endChar <= cursor) break // safety against stalls

                        // Widow/orphan control at a real cut: keep at least
                        // two lines on each side of the page break. Decisions
                        // read the big layout's line indices only, so both
                        // pagination passes agree by construction.
                        if (endLine < packLayout.lineCount - 1) {
                            val decision = BreakRules.splitDecision(
                                startLine = startLine,
                                endLine = endLine,
                                lineCount = packLayout.lineCount,
                                isFirstFragment = firstPart,
                                pageHasOtherContent = parts.isNotEmpty(),
                            )
                            when (decision) {
                                BreakRules.SplitDecision.MoveToNextPage -> {
                                    closePage()
                                    continue
                                }

                                is BreakRules.SplitDecision.PlaceFewer -> {
                                    endLine = decision.endLine
                                    endChar = packLayout.getLineEnd(endLine)
                                    retreatToWordBoundary(raw, cursor, endChar)
                                        .takeIf { it > cursor }
                                        ?.let { endChar = it }
                                    // Fewer trailing lines only shrink the
                                    // fragment — it still fits the page.
                                    val fragment = packedText.subSequence(cursor, endChar)
                                    fragmentHeight = measurer.measure(
                                        text = fragment,
                                        style = fragmentStyle,
                                        constraints = Constraints(
                                            maxWidth = widthPx.coerceAtLeast(1),
                                        ),
                                        placeholders = inlineImagePlaceholders(fragment),
                                    ).size.height
                                }

                                BreakRules.SplitDecision.Place -> Unit
                            }
                        }
                        if (endChar <= cursor) break // safety against stalls

                        addPart(
                            PagePart(
                                itemIndex = index,
                                element = element,
                                text = packedText.subSequence(cursor, endChar),
                                isParagraphStart = firstPart,
                                charStart = packBase + cursor,
                                charEnd = packBase + endChar,
                            ),
                            heightPx = fragmentHeight + vPaddingPx,
                            index = index,
                            charStart = packBase + cursor,
                        )
                        firstPart = false
                        cursor = endChar

                        if (cursor < raw.length) closePage() // page filled mid-element
                    }
                }

                is ContentElement.Image -> {
                    val heightPx = imageHeightPx(
                        element = element,
                        contentWidthPx = spec.contentWidthPx,
                        fontSizePx = spec.fontSize.dp.toPx(),
                        maxHeightPx = maxImageHeightPx,
                    )
                    val total = heightPx + vPaddingPx
                    if (total > spec.contentHeightPx - usedPx && parts.isNotEmpty()) closePage()
                    addPart(
                        PagePart(
                            itemIndex = index,
                            element = element,
                            imageHeightPx = min(heightPx, spec.contentHeightPx - vPaddingPx),
                        ),
                        heightPx = total,
                        index = index,
                    )
                }

                ContentElement.Divider -> {
                    val total = ReaderMetrics.dividerHeight.roundToPx() + vPaddingPx
                    if (total > spec.contentHeightPx - usedPx && parts.isNotEmpty()) closePage()
                    addPart(PagePart(itemIndex = index, element = element), total, index)
                }

                is ContentElement.Spacer -> {
                    // Blank lines vanish at page boundaries, like in print.
                    if (parts.isNotEmpty()) {
                        val total = ReaderMetrics.spacerHeight(element, spec.fontSize).roundToPx()
                        if (total <= spec.contentHeightPx - usedPx) {
                            addPart(PagePart(itemIndex = index, element = element), total, index)
                        } else {
                            closePage()
                        }
                    }
                }

                is ContentElement.Table -> {
                    if (element.rows.isEmpty()) continue
                    val layout = measureTableLayout(
                        element, measurer, spec.density, spec.settings,
                        spec.fontSize, spec.contentWidthPx, spec.language,
                    )
                    val repeatHeader = element.rows.first().isHeader && element.rows.size > 1
                    var row = 0
                    while (row < element.rows.size) {
                        val headerPx = if (row > 0 && repeatHeader) layout.rowHeightsPx[0] else 0
                        val remaining =
                            spec.contentHeightPx - usedPx - vPaddingPx - headerPx
                        var end = TableGrid.rowsThatFit(
                            layout.rowHeightsPx, layout.grid.noBreakAfterRow, row, remaining,
                        )
                        if (end == row) {
                            if (parts.isNotEmpty()) {
                                // Retry on a fresh page.
                                closePage()
                                continue
                            }
                            // Even an empty page can't hold a safe break:
                            // rowspan barriers or a giant row. Take what
                            // physically fits (at least one row) and clip.
                            end = row + 1
                            var used = layout.rowHeightsPx[row].toLong()
                            while (end < element.rows.size &&
                                used + layout.rowHeightsPx[end] <= remaining
                            ) {
                                used += layout.rowHeightsPx[end]
                                end++
                            }
                        }
                        var partHeight = headerPx + vPaddingPx
                        for (r in row until end) partHeight += layout.rowHeightsPx[r]
                        addPart(
                            PagePart(
                                itemIndex = index,
                                element = element,
                                rowStart = row,
                                rowEnd = end,
                                tableLayout = layout,
                                headerRepeated = row > 0 && repeatHeader,
                                charStart = row,
                                charEnd = end,
                            ),
                            heightPx = partHeight,
                            index = index,
                            charStart = row,
                        )
                        row = end
                        if (row < element.rows.size) closePage()
                    }
                }
            }
        }
    }

    closePage()
    return pages
}

/** height / width of the image file, or null when it cannot be decoded. */
/**
 * On-screen height of a book image. A size the book's CSS asks for wins:
 * ornaments marked `height: 1em` stay letter-sized instead of being blown
 * up to the full column. Measurement and rendering both go through here.
 */
internal fun imageHeightPx(
    element: ContentElement.Image,
    contentWidthPx: Int,
    fontSizePx: Float,
    maxHeightPx: Int,
): Int {
    val aspect = imageAspectRatio(element.path) ?: 1.4f
    element.heightEm?.let { heightEm ->
        val wanted = (heightEm * fontSizePx).roundToInt()
        // Still never wider than the column.
        val widthAtWanted = wanted / aspect
        val fitted = if (widthAtWanted > contentWidthPx) {
            (contentWidthPx * aspect).roundToInt()
        } else {
            wanted
        }
        return fitted.coerceIn(1, maxHeightPx)
    }
    val width = (element.widthFrac ?: 1f) * contentWidthPx
    return min((width * aspect).roundToInt(), maxHeightPx).coerceAtLeast(1)
}

internal fun imageAspectRatio(path: String): Float? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth > 0 && options.outHeight > 0) {
        options.outHeight.toFloat() / options.outWidth
    } else {
        // BitmapFactory cannot read SVG — parse its declared geometry.
        svgFileAspectRatio(path)
    }
}.getOrNull()

private fun svgFileAspectRatio(path: String): Float? {
    if (!path.endsWith(".svg", ignoreCase = true)) return null
    val head = runCatching { java.io.File(path).readText().take(4096) }.getOrNull()
        ?: return null
    return svgAspectRatio(head)
}

/** height/width from SVG width/height attributes or the viewBox (pure). */
internal fun svgAspectRatio(markup: String): Float? {
    fun length(name: String): Float? {
        val raw = Regex("""(?i)\b$name\s*=\s*["']([^"']+)["']""")
            .find(markup)?.groupValues?.get(1)?.trim() ?: return null
        if ('%' in raw) return null
        return Regex("""\d+(\.\d+)?""").find(raw)?.value?.toFloatOrNull()
            ?.takeIf { it > 0f }
    }

    val width = length("width")
    val height = length("height")
    if (width != null && height != null) return height / width

    val viewBox = Regex("""(?i)viewBox\s*=\s*["']([^"']+)["']""")
        .find(markup)?.groupValues?.get(1)
        ?.trim()?.split(Regex("""[\s,]+"""))
        ?.mapNotNull { it.toFloatOrNull() }
    if (viewBox != null && viewBox.size == 4 && viewBox[2] > 0f && viewBox[3] > 0f) {
        return viewBox[3] / viewBox[2]
    }
    return null
}

/** Moves [end] back to the last whitespace so continuations start at a word. */
internal fun retreatToWordBoundary(text: String, start: Int, end: Int): Int {
    if (end >= text.length) return end
    if (text[end - 1].isWhitespace() || text[end].isWhitespace()) return end
    var i = end - 1
    while (i > start && !text[i].isWhitespace()) i--
    return if (i <= start) end else i + 1
}
