package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Turns a parsed XHTML body (EPUB chapter) into reader content elements.
 *
 * Structure comes from two places: semantic tags (h1…h6, blockquote, ul/ol)
 * and — far more often in real books — CSS classes resolved through [css].
 * FB2→EPUB conversions mark chapter titles as `<div class="title1">`,
 * epigraphs as `<div class="epigraph">` and so on; the resolver turns those
 * into [BlockStyle]s so the reader renders them the way the book intends.
 *
 * @param resolveImage maps an image `src` (as written in the document) to an
 * absolute path of the extracted file, or null when unavailable.
 * @param resolveLink maps an `<a href>` to a canonical footnote key
 * ("path#fragment"), or null for external/unresolvable links.
 * @param css stylesheet resolver for this chapter, or null when the book has
 * no usable CSS.
 */
class HtmlMapper(
    private val resolveImage: (String) -> String?,
    private val resolveLink: (String) -> String? = { null },
    private val css: CssResolver? = null,
    /** Writes inline vector `<svg>` markup to a file, returning its path. */
    private val resolveInlineSvg: (String) -> String? = { null },
) {

    private val out = mutableListOf<ContentElement>()
    private var pendingInline: MutableList<Node> = mutableListOf()

    /** Non-null while walking the children of a title-classed block. */
    private var headingLevel: Int? = null

    /** One open `<ul>`/`<ol>`: its marker style and the next item number. */
    private class ListContext(
        val ordered: Boolean,
        var nextIndex: Int,
        val styleType: String,
    )

    private val listStack = mutableListOf<ListContext>()

    /** id → index (in the produced element list) where that anchor lives. */
    val anchors = mutableMapOf<String, Int>()

    /** Canonical keys of every internal link referenced by this document. */
    val linkTargets = mutableSetOf<String>()

    fun map(body: Element): List<ContentElement> {
        walk(body, quote = false)
        flushPending(quote = false)
        return out.toList()
    }

    private fun registerAnchor(id: String) {
        if (id.isNotEmpty()) anchors.putIfAbsent(id, out.size)
    }

    private fun walk(container: Element, quote: Boolean) {
        for (node in container.childNodes()) {
            when (node) {
                is TextNode -> if (!node.isBlank) pendingInline.add(node)

                is Element -> {
                    registerAnchor(node.attr("id"))
                    walkElement(node, quote)
                }
            }
        }
    }

    private fun walkElement(node: Element, quote: Boolean) {
        // display:none — skip the element entirely, but keep its anchors
        // (already registered) pointing at the next visible element.
        if (css?.computed(node)?.hidden == true) return

        when (node.normalName()) {
            "p", "li", "dt", "dd", "pre" -> {
                flushPending(quote)
                emitParagraph(node, quote)
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flushPending(quote)
                emitHeading(node, node.normalName()[1].digitToInt())
            }

            "blockquote", "cite" -> {
                flushPending(quote)
                walk(node, quote = true)
                flushPending(quote = true)
            }

            "ul", "ol" -> {
                flushPending(quote)
                val ordered = node.normalName() == "ol"
                // The element's own type attribute beats an INHERITED CSS
                // value (inheritance is the weakest source in the cascade).
                val styleType = ListMarkers.cssTypeFor(node.attr("type").takeIf { it.isNotEmpty() })
                    ?: ListMarkers.applicableCssType(css?.computed(node)?.listStyleType, ordered)
                    ?: ListMarkers.defaultType(ordered, listStack.size + 1)
                val start = if (ordered) node.attr("start").toIntOrNull() ?: 1 else 1
                listStack.add(ListContext(ordered, start, styleType))
                walk(node, quote)
                flushPending(quote)
                listStack.removeAt(listStack.lastIndex)
            }

            "figcaption" -> {
                flushPending(quote)
                if (node.selectFirst(BLOCK_PROBE) != null) {
                    walk(node, quote)
                    flushPending(quote)
                } else {
                    emitCaptionLike(node, quote)
                }
            }

            "summary" -> {
                flushPending(quote)
                if (node.selectFirst(BLOCK_PROBE) != null) {
                    walk(node, quote)
                    flushPending(quote)
                } else {
                    emitSummary(node, quote)
                }
            }

            "audio", "video" -> {
                flushPending(quote)
                emitMediaFallback(node, quote)
            }

            "div", "section", "article", "aside", "main", "header",
            "footer", "dl", "figure", "center",
            "nav", "details",
            -> {
                flushPending(quote)
                if (isEmptyLineBlock(node)) {
                    out += ContentElement.Spacer(1f)
                    return
                }
                // CSS-generated decorations (div.separator::before "***").
                emitGeneratedParagraph(node, after = false, quote)
                val titleLevel = titleClassLevel(node)
                if (titleLevel != null && headingLevel == null) {
                    headingLevel = titleLevel
                    walk(node, quote)
                    flushPending(quote)
                    headingLevel = null
                } else {
                    walk(node, quote)
                    flushPending(quote)
                }
                emitGeneratedParagraph(node, after = true, quote)
            }

            "img" -> {
                flushPending(quote)
                emitImage(node.attr("src"), node)
            }

            "image" -> {
                // SVG-style <image xlink:href=…/> used by some EPUB covers.
                flushPending(quote)
                emitImage(node.attr("xlink:href").ifEmpty { node.attr("href") }, node)
            }

            "svg" -> {
                flushPending(quote)
                val image = node.selectFirst("image")
                if (image != null) {
                    emitImage(image.attr("xlink:href").ifEmpty { image.attr("href") }, image)
                } else {
                    // A real vector SVG: serialize the markup to a file and
                    // show it like any image (Coil's SVG decoder draws it).
                    if (node.attr("xmlns").isEmpty()) {
                        node.attr("xmlns", "http://www.w3.org/2000/svg")
                    }
                    resolveInlineSvg(node.outerHtml())?.let {
                        out += ContentElement.Image(it)
                    }
                }
            }

            "hr" -> {
                flushPending(quote)
                out += ContentElement.Divider
            }

            "table" -> {
                flushPending(quote)
                emitTable(node)
            }

            "br" -> pendingInline.add(node)

            "style", "script", "head", "title", "link", "meta", "source", "track" -> Unit

            else -> {
                // Unknown element: recurse if it holds block content,
                // otherwise treat it as inline (span, a, em, …).
                if (node.selectFirst(BLOCK_PROBE) != null) {
                    flushPending(quote)
                    walk(node, quote)
                    flushPending(quote)
                } else {
                    pendingInline.add(node)
                }
            }
        }
    }

    // -------------------------------------------------------------- blocks

    /** Emits loose inline nodes collected between block elements. */
    private fun flushPending(quote: Boolean) {
        if (pendingInline.isEmpty()) return
        val nodes = pendingInline
        pendingInline = mutableListOf()
        val builder = InlineTextBuilder()
        nodes.forEach { appendInline(it, builder) }
        if (!builder.isBlank) {
            out += ContentElement.Paragraph(
                builder.build(),
                if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
            )
        }
    }

    private fun emitParagraph(element: Element, quote: Boolean) {
        // Anchors inside the paragraph (e.g. <a id="n53"/>) resolve to it.
        element.select("[id]").forEach { registerAnchor(it.attr("id")) }

        val ownTitleLevel = titleClassLevel(element)
        val level = headingLevel ?: ownTitleLevel
        if (level != null) {
            emitHeading(element, level)
            return
        }
        if (isEmptyLineBlock(element)) {
            out += ContentElement.Spacer(1f)
            return
        }

        val builder = InlineTextBuilder()
        var isListItem = false
        if (element.normalName() == "li") {
            val context = listStack.lastOrNull()
            if (context != null) {
                isListItem = true
                if (context.ordered) {
                    element.attr("value").toIntOrNull()?.let { context.nextIndex = it }
                }
                builder.text(ListMarkers.marker(context.styleType, context.nextIndex))
                if (context.ordered) context.nextIndex++
            } else {
                builder.text("• ") // stray <li> outside any list
            }
        }
        // CSS-generated text (::before markers, decorations) opens the run.
        appendGeneratedRun(builder, css?.generated(element, after = false))
        // A list item's own text is inline, but a nested <ul>/<ol> inside it
        // is a block — walked separately below, not flattened into the text.
        for (child in element.childNodes()) {
            if (child is Element && child.normalName() in NESTED_LIST_TAGS) continue
            appendInline(child, builder)
        }
        appendGeneratedRun(builder, css?.generated(element, after = true))
        // A small CSS-floated image travels with the paragraph so the
        // renderer can wrap text around it (publisher's formatting mode).
        var floatImage: FloatImage? = null
        var floatElement: Element? = null
        if (css != null) {
            for (img in element.select("img")) {
                val computed = css.computed(img)
                val side = computed.floatSide ?: continue
                val frac = computed.widthFrac
                    ?: computed.widthEm?.let { it / 30f }
                    ?: 0.33f
                if (frac > 0.45f) continue
                val path = resolveImage(img.attr("src")) ?: continue
                floatImage = FloatImage(path, frac.coerceIn(0.1f, 0.45f), side == "left")
                floatElement = img
                break
            }
        }
        if (!builder.isBlank) {
            var block = blockStyleFor(element, withFirstLetter = true)
            if (isListItem) {
                // Nested lists indent one step per level; markers hang at
                // the item's own edge (no first-line indent).
                val base = block ?: BlockStyle()
                block = base.copy(
                    indentStartEm = base.indentStartEm + 1.2f * (listStack.size - 1),
                    firstLineIndent = false,
                )
            }
            if (floatImage != null) {
                block = (block ?: BlockStyle()).copy(floatImage = floatImage)
            }
            out += ContentElement.Paragraph(
                builder.build(),
                if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
                block,
            )
        } else {
            floatElement = null // no paragraph to carry it — keep the block image
        }
        // Images nested inside paragraphs still need to be shown (the
        // consumed float is drawn with its paragraph instead).
        element.select("img").forEach {
            if (it !== floatElement) emitImage(it.attr("src"), it)
        }
        element.children()
            .filter { it.normalName() in NESTED_LIST_TAGS }
            .forEach { walkElement(it, quote) }
    }

    private fun emitHeading(element: Element, level: Int) {
        element.select("[id]").forEach { registerAnchor(it.attr("id")) }
        val before = css?.generated(element, after = false)?.text.orEmpty()
        val afterText = css?.generated(element, after = true)?.text.orEmpty()
        val text = (before + element.text().trim() + afterText).trim()
        if (text.isEmpty()) return
        out += ContentElement.Heading(
            text = text,
            level = level.coerceIn(1, 6),
            block = blockStyleFor(element),
        )
        element.select("img").forEach { emitImage(it.attr("src"), it) }
    }

    /** `<figcaption>`: centered italic small print, like a table caption. */
    private fun emitCaptionLike(node: Element, quote: Boolean) {
        node.select("[id]").forEach { registerAnchor(it.attr("id")) }
        val builder = InlineTextBuilder()
        appendChildrenInline(node, builder)
        if (builder.isBlank) return
        val base = blockStyleFor(node) ?: BlockStyle()
        out += ContentElement.Paragraph(
            builder.build(),
            if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
            base.copy(
                align = base.align ?: BlockAlign.CENTER,
                italic = base.italic ?: true,
                fontScale = if (base.fontScale in 0.99f..1.01f) 0.9f else base.fontScale,
                firstLineIndent = false,
            ),
        )
        node.select("img").forEach { emitImage(it.attr("src"), it) }
    }

    /** `<summary>`: the disclosure caption reads as a bold lead-in line. */
    private fun emitSummary(node: Element, quote: Boolean) {
        node.select("[id]").forEach { registerAnchor(it.attr("id")) }
        val builder = InlineTextBuilder()
        appendChildrenInline(node, builder)
        if (builder.isBlank) return
        val base = blockStyleFor(node) ?: BlockStyle()
        out += ContentElement.Paragraph(
            builder.build(),
            if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
            base.copy(bold = base.bold ?: true, firstLineIndent = false),
        )
    }

    /**
     * `<audio>`/`<video>`: playback is out of scope — show the poster frame
     * when there is one, else the author's fallback markup, else a quiet
     * placeholder so the reader knows something was here.
     */
    private fun emitMediaFallback(node: Element, quote: Boolean) {
        val poster = node.attr("poster")
        if (poster.isNotEmpty() && resolveImage(poster) != null) {
            emitImage(poster)
            return
        }
        if (node.selectFirst(BLOCK_PROBE) != null || node.text().isNotBlank()) {
            walk(node, quote)
            flushPending(quote)
            return
        }
        val label = if (node.normalName() == "audio") AUDIO_PLACEHOLDER else VIDEO_PLACEHOLDER
        out += ContentElement.Paragraph(
            AnnotatedString(label),
            ParagraphStyle.NORMAL,
            BlockStyle(
                align = BlockAlign.CENTER,
                italic = true,
                fontScale = 0.85f,
                firstLineIndent = false,
            ),
        )
    }

    /**
     * Tables become real grids ([ContentElement.Table]) with colspan/rowspan
     * intact. Degenerate cases keep the old flattened behavior: no rows →
     * one paragraph, a single column (layout tables) → plain paragraphs,
     * absurd sizes → per-row text.
     */
    private fun emitTable(table: Element) {
        // The caption reads like a small centered title above the grid.
        table.selectFirst("caption")?.let { caption ->
            val builder = InlineTextBuilder()
            appendChildrenInline(caption, builder)
            if (!builder.isBlank) {
                out += ContentElement.Paragraph(
                    builder.build(),
                    ParagraphStyle.NORMAL,
                    BlockStyle(align = BlockAlign.CENTER, italic = true, firstLineIndent = false),
                )
            }
        }

        // Only this table's own rows; a nested table flattens into its cell.
        val ownRows = table.select("tr").filter { row ->
            row.parents().firstOrNull { it.normalName() == "table" } === table
        }
        if (ownRows.isEmpty()) {
            val text = table.text().trim()
            if (text.isNotEmpty()) out += ContentElement.Paragraph(AnnotatedString(text))
            return
        }

        val rows = mutableListOf<TableRow>()
        for (rowElement in ownRows) {
            val cellElements = rowElement.children()
                .filter { it.normalName() == "td" || it.normalName() == "th" }
            if (cellElements.isEmpty()) continue
            val inHead = rowElement.parents().any { it.normalName() == "thead" }
            val cells = cellElements.map { cellElement ->
                val builder = InlineTextBuilder()
                appendCellContent(cellElement, builder)
                val header = cellElement.normalName() == "th"
                TableCell(
                    text = builder.build(),
                    colSpan = cellElement.attr("colspan").toIntOrNull()?.coerceIn(1, 10) ?: 1,
                    rowSpan = cellElement.attr("rowspan").toIntOrNull()?.coerceIn(1, 20) ?: 1,
                    align = cellAlign(cellElement, header),
                    header = header,
                )
            }
            rows += TableRow(cells, isHeader = inHead || cells.all { it.header })
        }

        val columnCount = rows.maxOfOrNull { row -> row.cells.sumOf { it.colSpan } } ?: 0
        when {
            rows.isEmpty() || columnCount == 0 -> Unit

            columnCount == 1 -> {
                // A layout "table": plain paragraphs read better than a grid.
                for (row in rows) {
                    for (cell in row.cells) {
                        if (cell.text.text.isBlank()) continue
                        out += ContentElement.Paragraph(
                            cell.text,
                            ParagraphStyle.NORMAL,
                            BlockStyle(firstLineIndent = false),
                        )
                    }
                }
            }

            columnCount > 12 || rows.size > 400 -> {
                for (row in rows) {
                    val text = row.cells.joinToString("    ") { it.text.text.trim() }
                        .trim()
                    if (text.isEmpty()) continue
                    out += ContentElement.Paragraph(
                        AnnotatedString(text),
                        ParagraphStyle.NORMAL,
                        BlockStyle(firstLineIndent = false),
                    )
                }
            }

            else -> out += ContentElement.Table(rows, blockStyleFor(table))
        }
    }

    /** Cell content: inline runs, block children separated by line breaks. */
    private fun appendCellContent(cell: Element, builder: InlineTextBuilder) {
        for (child in cell.childNodes()) {
            if (child is Element && child.normalName() in CELL_BLOCK_TAGS) {
                if (!builder.isBlank) builder.lineBreak()
                appendChildrenInline(child, builder)
            } else {
                appendInline(child, builder)
            }
        }
    }

    private fun cellAlign(cell: Element, header: Boolean): BlockAlign? {
        val cssAlign = css?.let {
            // Only the cell's own/row/table alignment matters — an inherited
            // body-wide justify would look broken in narrow cells.
            when (it.computed(cell).textAlign) {
                "center" -> BlockAlign.CENTER
                "end" -> BlockAlign.END
                "start" -> BlockAlign.START
                else -> null
            }
        }
        if (cssAlign != null) return cssAlign
        return when (cell.attr("align").lowercase()) {
            "center" -> BlockAlign.CENTER
            "right" -> BlockAlign.END
            "left" -> BlockAlign.START
            else -> if (header) BlockAlign.CENTER else null
        }
    }

    private fun emitImage(src: String, node: Element? = null) {
        if (src.isEmpty()) return
        val path = resolveImage(src) ?: return
        // An ornament set to `height: 1em` must stay that size instead of
        // being stretched across the column.
        val computed = node?.let { css?.computed(it) }
        out += ContentElement.Image(
            path = path,
            widthFrac = computed?.widthFrac
                ?: computed?.widthEm?.let { (it / 30f).coerceIn(0.02f, 1f) },
            heightEm = computed?.heightEm,
        )
    }

    // -------------------------------------------------------------- styles

    /**
     * Block style of an element: its computed CSS plus the box indents and
     * spacing of the block ancestors it opens/closes (an epigraph div's
     * margin-left must indent every paragraph inside it).
     */
    private fun blockStyleFor(element: Element, withFirstLetter: Boolean = false): BlockStyle? {
        val resolver = css ?: return null
        val computed = resolver.computed(element)

        var startEm = computed.marginStartEm
        var startFrac = computed.marginStartFrac
        var endEm = computed.marginEndEm
        var beforeEm = computed.marginTopEm
        var afterEm = computed.marginBottomEm
        var centered = computed.centeredBox
        var pageBreak = computed.pageBreakBefore

        // Walk up through wrapper blocks, adding their horizontal indents;
        // vertical margins only apply at the wrapper's first/last block.
        var child: Element = element
        var ancestor = element.parent()
        while (ancestor != null && ancestor.normalName() !in ROOT_TAGS) {
            val box = resolver.computed(ancestor)
            startEm += box.marginStartEm
            startFrac += box.marginStartFrac
            endEm += box.marginEndEm
            centered = centered || box.centeredBox
            if (firstBlockChild(ancestor) === child) {
                beforeEm += box.marginTopEm
                // A chapter <div> with page-break-before must break before
                // its first block.
                pageBreak = pageBreak || box.pageBreakBefore
            }
            if (lastBlockChild(ancestor) === child) afterEm += box.marginBottomEm
            child = ancestor
            ancestor = ancestor.parent()
        }

        // The full alignment is stored; whether START/JUSTIFY of body text
        // actually wins over the user's setting is decided at render time
        // (the "publisher's formatting" toggle).
        val align = when {
            computed.textAlign == "center" || centered -> BlockAlign.CENTER
            computed.textAlign == "end" -> BlockAlign.END
            computed.textAlign == "start" -> BlockAlign.START
            computed.textAlign == "justify" -> BlockAlign.JUSTIFY
            else -> null
        }

        val style = BlockStyle(
            align = align,
            italic = computed.italic,
            bold = computed.bold,
            fontScale = computed.fontSizeEm.coerceIn(0.6f, 2.6f),
            indentStartFrac = startFrac.coerceIn(0f, 0.45f),
            indentStartEm = startEm.coerceIn(0f, 6f),
            indentEndEm = endEm.coerceIn(0f, 4f),
            firstLineIndent = computed.textIndentEm?.let { it > 0.05f },
            firstLineIndentEm = computed.textIndentEm,
            spaceBeforeEm = beforeEm.coerceIn(0f, 3f),
            spaceAfterEm = afterEm.coerceIn(0f, 3f),
            fontFamily = computed.fontFamilyName,
            lineHeightMult = computed.lineHeightMult,
            hyphens = computed.hyphensAuto,
            pageBreakBefore = pageBreak,
            firstLetter = if (withFirstLetter) resolver.firstLetter(element) else null,
        )
        return if (style.isDefault) null else style
    }

    /** A generated run appended as styled inline text. */
    private fun appendGeneratedRun(builder: InlineTextBuilder, run: CssResolver.GeneratedRun?) {
        if (run == null) return
        val style = generatedSpanStyle(run)
        if (style != null) builder.pushStyle(style)
        builder.text(run.text)
        if (style != null) builder.pop()
    }

    private fun generatedSpanStyle(run: CssResolver.GeneratedRun): SpanStyle? {
        var style = SpanStyle()
        var any = false
        if (run.italic == true) {
            style = style.copy(fontStyle = FontStyle.Italic)
            any = true
        }
        if (run.bold == true) {
            style = style.copy(fontWeight = FontWeight.Bold)
            any = true
        }
        if (run.scale !in 0.99f..1.01f) {
            style = style.copy(fontSize = TextUnit(run.scale, TextUnitType.Em))
            any = true
        }
        return if (any) style else null
    }

    /** `div::before`/`::after` decorations become their own paragraphs. */
    private fun emitGeneratedParagraph(element: Element, after: Boolean, quote: Boolean) {
        val run = css?.generated(element, after) ?: return
        val builder = InlineTextBuilder()
        appendGeneratedRun(builder, run)
        if (builder.isBlank) return
        val block = blockStyleFor(element)?.copy(firstLineIndent = false)
            ?: BlockStyle(firstLineIndent = false)
        out += ContentElement.Paragraph(
            builder.build(),
            if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
            block,
        )
    }

    private fun firstBlockChild(container: Element): Element? =
        container.children().firstOrNull { it.normalName() !in NON_BLOCK_TAGS }

    private fun lastBlockChild(container: Element): Element? =
        container.children().lastOrNull { it.normalName() !in NON_BLOCK_TAGS }

    /** Heading level for `title`/`titleN` CSS classes, or null. */
    private fun titleClassLevel(element: Element): Int? {
        for (name in element.classNames()) {
            val match = TITLE_CLASS.matchEntire(name) ?: continue
            val digit = match.groupValues[1]
            return if (digit.isEmpty()) 1 else (digit.toInt() + 1).coerceAtMost(6)
        }
        return null
    }

    /** Blocks that exist only to add vertical space (`<div class="empty-line"/>`). */
    private fun isEmptyLineBlock(element: Element): Boolean =
        element.classNames().any { it.equals("empty-line", ignoreCase = true) } &&
            element.text().isBlank()

    // -------------------------------------------------------------- inline

    private fun appendChildrenInline(element: Element, out: InlineTextBuilder) {
        for (child in element.childNodes()) appendInline(child, out)
    }

    private fun appendInline(node: Node, out: InlineTextBuilder) {
        when (node) {
            is TextNode -> out.text(node.wholeText)

            is Element -> {
                if (css?.computed(node)?.hidden == true) return
                when (node.normalName()) {
                    "br" -> out.lineBreak()
                    "img", "image", "svg" -> Unit // handled at block level

                    // EPUB 3 ruby: base text renders normally, the reading
                    // (<rt>) becomes a small superscript right after its
                    // base, fallback parentheses (<rp>) are dropped.
                    "ruby" -> appendChildrenInline(node, out)
                    "rp" -> Unit
                    "rt" -> {
                        out.pushStyle(
                            SpanStyle(
                                fontSize = TextUnit(0.6f, TextUnitType.Em),
                                baselineShift = BaselineShift(0.35f),
                            ),
                        )
                        appendChildrenInline(node, out)
                        out.pop()
                    }
                    "a" -> {
                        val key = node.attr("href")
                            .takeIf { it.isNotEmpty() }
                            ?.let(resolveLink)
                        if (key != null) {
                            linkTargets += key
                            // A link to an anchor is a footnote (it opens a
                            // popup); a link to a whole file is a Contents
                            // entry or cross-reference and moves the reader.
                            // Footnote references get a standard superscript
                            // look later; the book's own CSS is skipped so the
                            // note marker cannot be styled twice.
                            out.pushAnnotation(
                                if ('#' in key) FOOTNOTE_TAG else LINK_TAG,
                                key,
                            )
                            appendChildrenInline(node, out)
                            out.pop()
                        } else {
                            appendChildrenInline(node, out)
                        }
                    }

                    else -> {
                        val style = inlineStyleFor(node)
                        if (style != null) out.pushStyle(style)
                        appendGeneratedRun(out, css?.generated(node, after = false))
                        appendChildrenInline(node, out)
                        appendGeneratedRun(out, css?.generated(node, after = true))
                        if (style != null) out.pop()
                    }
                }
            }
        }
    }

    /**
     * Span style of an inline element: with CSS, the difference between the
     * element's computed style and its parent's (so inherited properties are
     * not re-applied); without CSS, classic tag semantics.
     */
    private fun inlineStyleFor(element: Element): SpanStyle? {
        val resolver = css ?: return tagStyleFor(element.normalName())
        val parent = element.parent() ?: return tagStyleFor(element.normalName())
        val own = resolver.computed(element)
        val base = resolver.computed(parent)

        var style = SpanStyle()
        var any = false
        if (own.italic != null && own.italic != base.italic) {
            style = style.copy(fontStyle = if (own.italic) FontStyle.Italic else FontStyle.Normal)
            any = true
        }
        if (own.bold != null && own.bold != base.bold) {
            style = style.copy(fontWeight = if (own.bold) FontWeight.Bold else FontWeight.Normal)
            any = true
        }
        val sizeRatio = own.fontSizeEm / base.fontSizeEm
        if (sizeRatio !in 0.99f..1.01f) {
            style = style.copy(
                fontSize = TextUnit(sizeRatio.coerceIn(0.5f, 2f), TextUnitType.Em),
            )
            any = true
        }
        if (own.superScript) {
            style = style.copy(baselineShift = BaselineShift.Superscript)
            if (style.fontSize == TextUnit.Unspecified) {
                style = style.copy(fontSize = TextUnit(0.75f, TextUnitType.Em))
            }
            any = true
        } else if (own.subScript) {
            style = style.copy(baselineShift = BaselineShift.Subscript)
            if (style.fontSize == TextUnit.Unspecified) {
                style = style.copy(fontSize = TextUnit(0.75f, TextUnitType.Em))
            }
            any = true
        }
        val decorations = mutableListOf<TextDecoration>()
        if (own.underline && !base.underline) decorations += TextDecoration.Underline
        if (own.strike && !base.strike) decorations += TextDecoration.LineThrough
        if (decorations.isNotEmpty()) {
            style = style.copy(textDecoration = TextDecoration.combine(decorations))
            any = true
        }
        if (own.monospace && !base.monospace) {
            style = style.copy(fontFamily = FontFamily.Monospace)
            any = true
        }
        return if (any) style else null
    }

    private fun tagStyleFor(tag: String): SpanStyle? = when (tag) {
        "i", "em", "dfn", "var" -> SpanStyle(fontStyle = FontStyle.Italic)
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "code", "kbd", "samp", "tt" -> SpanStyle(fontFamily = FontFamily.Monospace)
        "sup" -> SpanStyle(
            baselineShift = BaselineShift.Superscript,
            fontSize = TextUnit(0.75f, TextUnitType.Em),
        )

        "sub" -> SpanStyle(
            baselineShift = BaselineShift.Subscript,
            fontSize = TextUnit(0.75f, TextUnitType.Em),
        )

        "small" -> SpanStyle(fontSize = TextUnit(0.85f, TextUnitType.Em))
        else -> null
    }

    private companion object {
        const val BLOCK_PROBE =
            "p, div, h1, h2, h3, h4, h5, h6, blockquote, img, ul, ol, table"
        val ROOT_TAGS = setOf("body", "html", "#root")
        val NON_BLOCK_TAGS = setOf(
            "span", "a", "em", "i", "b", "strong", "u", "s", "sup", "sub",
            "small", "big", "code", "br", "img", "ruby", "rt", "rp", "rb",
        )
        val TITLE_CLASS = Regex("""(?i)title(\d*)""")
        val NESTED_LIST_TAGS = setOf("ul", "ol")
        val CELL_BLOCK_TAGS = setOf("p", "div")

        // English compile-time constants: the parser layer has no Android
        // resources. Localizing them means plumbing a strings provider
        // through HtmlMapper — accepted debt for the uk pass.
        const val AUDIO_PLACEHOLDER = "♪ Audio is not supported"
        const val VIDEO_PLACEHOLDER = "▶ Video is not supported"
    }
}
