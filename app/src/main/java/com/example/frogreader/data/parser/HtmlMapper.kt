package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.Base64

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

    /** Subset of [linkTargets] that are semantically note references. */
    val noteTargets = mutableSetOf<String>()

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
                emitSvg(node)
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
        val hasText = !builder.isBlank
        if (hasText) {
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
        val preformatted = element.normalName() == "pre"
        if (preformatted) builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
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
            appendInline(child, builder, preserveWhitespace = preformatted)
        }
        appendGeneratedRun(builder, css?.generated(element, after = true))
        if (preformatted) builder.pop()
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
                floatImage = FloatImage(
                    path = path,
                    widthFrac = frac.coerceIn(0.1f, 0.45f),
                    left = side == "left",
                    altText = imageAlt(img),
                )
                floatElement = img
                break
            }
        }
        val hasText = !builder.isBlank
        if (hasText) {
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
            if (preformatted) {
                block = (block ?: BlockStyle()).copy(
                    fontFamily = "monospace",
                    firstLineIndent = false,
                )
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
        val inlineRefs = builder.imageRefs.toSet()
        element.select("img").forEach { image ->
            val path = resolveImage(image.attr("src"))
            if (image !== floatElement &&
                (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs)
            ) {
                emitImage(image.attr("src"), image)
            }
        }
        element.select("svg").forEach { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
        element.children()
            .filter { it.normalName() in NESTED_LIST_TAGS }
            .forEach { walkElement(it, quote) }
    }

    private fun emitHeading(element: Element, level: Int) {
        element.select("[id]").forEach { registerAnchor(it.attr("id")) }
        val builder = InlineTextBuilder()
        appendGeneratedRun(builder, css?.generated(element, after = false))
        appendChildrenInline(element, builder)
        appendGeneratedRun(builder, css?.generated(element, after = true))
        val hasText = !builder.isBlank
        if (hasText) {
            out += ContentElement.Heading(
                styledText = builder.build().trimmed(),
                level = level.coerceIn(1, 6),
                block = blockStyleFor(element),
            )
        }
        val inlineRefs = builder.imageRefs.toSet()
        element.select("img").forEach { image ->
            val path = resolveImage(image.attr("src"))
            if (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs) {
                emitImage(image.attr("src"), image)
            }
        }
        element.select("svg").forEach { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
    }

    /** `<figcaption>`: centered italic small print, like a table caption. */
    private fun emitCaptionLike(node: Element, quote: Boolean) {
        node.select("[id]").forEach { registerAnchor(it.attr("id")) }
        val builder = InlineTextBuilder()
        appendChildrenInline(node, builder)
        val hasText = !builder.isBlank
        if (hasText) {
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
        }
        val inlineRefs = builder.imageRefs.toSet()
        node.select("img").forEach { image ->
            val path = resolveImage(image.attr("src"))
            if (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs) {
                emitImage(image.attr("src"), image)
            }
        }
        node.select("svg").forEach { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
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

    /**
     * Legacy HTML uses `align=` instead of CSS, especially in MOBI6. Search
     * wrapper blocks too: `<div align="center"><p>…</p></div>` is common.
     * A computed CSS alignment still wins in [blockStyleFor].
     */
    private fun attributeAlign(element: Element): BlockAlign? {
        var node: Element? = element
        while (node != null && node.normalName() !in ROOT_TAGS) {
            if (node.normalName() == "center") return BlockAlign.CENTER
            when (node.attr("align").lowercase()) {
                "center" -> return BlockAlign.CENTER
                "right" -> return BlockAlign.END
                "left" -> return BlockAlign.START
                "justify" -> return BlockAlign.JUSTIFY
            }
            node = node.parent()
        }
        return null
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
        val alt = node?.let(::imageAlt)
        if (src.isEmpty()) {
            emitMissingImageAlt(alt)
            return
        }
        val path = resolveImage(src)
        if (path == null) {
            emitMissingImageAlt(alt)
            return
        }
        // An ornament set to `height: 1em` must stay that size instead of
        // being stretched across the column.
        val computed = node?.let { css?.computed(it) }
        out += ContentElement.Image(
            path = path,
            widthFrac = computed?.widthFrac
                ?: computed?.widthEm?.let { (it / 30f).coerceIn(0.02f, 1f) },
            heightEm = computed?.heightEm,
            altText = alt,
        )
    }

    private fun imageAlt(node: Element): String? =
        node.attr("alt").ifBlank { node.attr("aria-label") }
            .ifBlank { node.attr("title") }
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun isBlockLikeMedia(node: Element): Boolean {
        val computed = css?.computed(node) ?: return false
        return computed.floatSide != null ||
            (computed.widthFrac ?: 0f) > 0.45f ||
            (computed.widthEm ?: 0f) > 12f ||
            (computed.heightEm ?: 0f) > 3f
    }

    private fun emitMissingImageAlt(alt: String?) {
        if (alt == null) return
        out += ContentElement.Paragraph(
            AnnotatedString(alt),
            ParagraphStyle.NORMAL,
            BlockStyle(italic = true, firstLineIndent = false),
        )
    }

    /**
     * Keeps an inline SVG intact, including mixed vector shapes/text and
     * raster `<image>` children. Referenced local images are embedded because
     * the serialized SVG is written to a cache file whose directory is no
     * longer the publication resource directory.
     */
    private fun emitSvg(source: Element) {
        resolveSvg(source)?.let {
            out += ContentElement.Image(
                path = it,
                altText = source.attr("aria-label").ifBlank {
                    source.selectFirst("title")?.text().orEmpty()
                }.trim().takeIf(String::isNotEmpty),
            )
        }
    }

    private fun resolveSvg(source: Element): String? {
        val svg = source.clone()
        if (svg.attr("xmlns").isEmpty()) {
            svg.attr("xmlns", "http://www.w3.org/2000/svg")
        }
        for (image in svg.select("image")) {
            val attr = if (image.hasAttr("xlink:href")) "xlink:href" else "href"
            val ref = image.attr(attr)
            if (ref.isEmpty() || ref.startsWith("data:", ignoreCase = true)) continue
            val path = resolveImage(ref) ?: continue
            val file = File(path)
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            image.attr(attr, "data:${imageMime(file.name, bytes)};base64,${Base64.getEncoder().encodeToString(bytes)}")
        }
        return resolveInlineSvg(svg.outerHtml())
    }

    private fun imageMime(name: String, bytes: ByteArray): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".svg") || bytes.take(256).toByteArray()
                .decodeToString().contains("<svg", ignoreCase = true) -> "image/svg+xml"
            lower.endsWith(".gif") || bytes.size >= 3 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/png"
        }
    }

    // -------------------------------------------------------------- styles

    /**
     * Block style of an element: its computed CSS plus the box indents and
     * spacing of the block ancestors it opens/closes (an epigraph div's
     * margin-left must indent every paragraph inside it).
     */
    private fun blockStyleFor(element: Element, withFirstLetter: Boolean = false): BlockStyle? {
        val resolver = css
        if (resolver == null) {
            val style = BlockStyle(
                align = attributeAlign(element),
                language = languageFor(element),
                direction = attributeDirection(element),
            )
            return if (style.isDefault) null else style
        }
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
            else -> attributeAlign(element)
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
            language = languageFor(element),
            direction = when (computed.direction) {
                "ltr" -> BookTextDirection.LTR
                "rtl" -> BookTextDirection.RTL
                else -> attributeDirection(element)
            },
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

    private fun appendChildrenInline(
        element: Element,
        out: InlineTextBuilder,
        preserveWhitespace: Boolean = false,
    ) {
        for (child in element.childNodes()) appendInline(child, out, preserveWhitespace)
    }

    /** Trims formatting-source whitespace without discarding span/link ranges. */
    private fun AnnotatedString.trimmed(): AnnotatedString {
        var start = 0
        var end = length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }

    private fun appendInline(
        node: Node,
        out: InlineTextBuilder,
        preserveWhitespace: Boolean = false,
    ) {
        when (node) {
            is TextNode -> if (preserveWhitespace) {
                out.preformattedText(node.wholeText)
            } else {
                out.text(node.wholeText)
            }

            is Element -> {
                if (css?.computed(node)?.hidden == true) return
                when (node.normalName()) {
                    "br" -> out.lineBreak()
                    "wbr" -> out.wordBreakOpportunity()
                    "img", "image" -> {
                        if (!isBlockLikeMedia(node)) {
                            val src = if (node.normalName() == "img") {
                                node.attr("src")
                            } else {
                                node.attr("xlink:href").ifEmpty { node.attr("href") }
                            }
                            val path = resolveImage(src)
                            if (path != null) {
                                out.inlineImage(path, imageAlt(node))
                            } else {
                                imageAlt(node)?.let(out::text)
                            }
                        }
                    }

                    "svg" -> {
                        val computed = css?.computed(node)
                        val blockLike = (computed?.widthFrac ?: 0f) > 0.45f ||
                            (computed?.widthEm ?: 0f) > 12f ||
                            (computed?.heightEm ?: 0f) > 3f
                        if (!blockLike) {
                            resolveSvg(node)?.let { path ->
                                val alt = node.attr("aria-label").ifBlank {
                                    node.selectFirst("title")?.text().orEmpty()
                                }
                                out.inlineImage(path, alt)
                            }
                        }
                    }

                    // EPUB 3 ruby: base text renders normally, the reading
                    // (<rt>) becomes a small superscript right after its
                    // base, fallback parentheses (<rp>) are dropped.
                    "ruby" -> appendChildrenInline(node, out, preserveWhitespace)
                    "rp" -> Unit
                    "rt" -> {
                        out.pushStyle(
                            SpanStyle(
                                fontSize = TextUnit(0.6f, TextUnitType.Em),
                                baselineShift = BaselineShift(0.35f),
                            ),
                        )
                        appendChildrenInline(node, out, preserveWhitespace)
                        out.pop()
                    }
                    "q" -> {
                        val generatedBefore = css?.generated(node, after = false)
                        val generatedAfter = css?.generated(node, after = true)
                        val (open, close) = quoteMarksFor(node)
                        if (generatedBefore != null) {
                            appendGeneratedRun(out, generatedBefore)
                        } else {
                            out.text(open)
                        }
                        appendChildrenInline(node, out, preserveWhitespace)
                        if (generatedAfter != null) {
                            appendGeneratedRun(out, generatedAfter)
                        } else {
                            out.text(close)
                        }
                    }
                    "mark" -> {
                        out.pushStyle(SpanStyle(background = Color(0x66FFD54F)))
                        val style = inlineStyleFor(node)
                        if (style != null) out.pushStyle(style)
                        appendChildrenInline(node, out, preserveWhitespace)
                        if (style != null) out.pop()
                        out.pop()
                    }
                    "a" -> {
                        val href = node.attr("href").trim()
                        val key = href.takeIf { it.isNotEmpty() }?.let(resolveLink)
                        if (key != null) {
                            linkTargets += key
                            val note = isNoteReference(node, key)
                            if (note) noteTargets += key
                            out.pushAnnotation(
                                if (note) FOOTNOTE_TAG else LINK_TAG,
                                key,
                            )
                            appendChildrenInline(node, out, preserveWhitespace)
                            out.pop()
                        } else if (safeExternalHref(href)) {
                            out.pushAnnotation(EXTERNAL_LINK_TAG, href)
                            appendChildrenInline(node, out, preserveWhitespace)
                            out.pop()
                        } else {
                            appendChildrenInline(node, out, preserveWhitespace)
                        }
                    }

                    else -> {
                        val style = inlineStyleFor(node)
                        if (style != null) out.pushStyle(style)
                        appendGeneratedRun(out, css?.generated(node, after = false))
                        appendChildrenInline(node, out, preserveWhitespace)
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
        val languageStyle = inlineLanguageStyle(element)
        val resolver = css
            ?: return mergeSpanStyles(tagStyleFor(element.normalName()), languageStyle)
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
        if (own.fontFamilyName != base.fontFamilyName) {
            val family = when (own.fontFamilyName) {
                "serif" -> FontFamily.Serif
                "sans-serif", "sans" -> FontFamily.SansSerif
                "monospace" -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else -> null // named embedded faces are selected at block level
            }
            if (family != null) {
                style = style.copy(fontFamily = family)
                any = true
            }
        }
        return mergeSpanStyles(if (any) style else null, languageStyle)
    }

    private fun mergeSpanStyles(first: SpanStyle?, second: SpanStyle?): SpanStyle? = when {
        first == null -> second
        second == null -> first
        else -> first.merge(second)
    }

    private fun inlineLanguageStyle(element: Element): SpanStyle? {
        val own = languageFor(element)
        val inherited = element.parent()?.let(::languageFor)
        return own.takeIf { it != null && it != inherited }
            ?.let { SpanStyle(localeList = LocaleList(it)) }
    }

    private fun languageFor(element: Element): String? {
        var node: Element? = element
        while (node != null) {
            val raw = node.attr("lang").ifBlank { node.attr("xml:lang") }.trim()
            if (raw.isNotEmpty()) return LanguageTag.normalize(raw) ?: raw.lowercase()
            node = node.parent()
        }
        return null
    }

    private fun attributeDirection(element: Element): BookTextDirection? {
        var node: Element? = element
        while (node != null) {
            when (node.attr("dir").lowercase()) {
                "ltr" -> return BookTextDirection.LTR
                "rtl" -> return BookTextDirection.RTL
            }
            node = node.parent()
        }
        return null
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

    /** EPUB 3 semantics plus conservative EPUB 2/Kindle legacy patterns. */
    private fun isNoteReference(link: Element, key: String): Boolean {
        if ('#' !in key) return false
        val epubTypes = link.attr("epub:type").split(Regex("\\s+"))
        if (epubTypes.any { it.equals("noteref", ignoreCase = true) }) return true
        if (link.attr("role").split(Regex("\\s+")).any {
                it.equals("doc-noteref", ignoreCase = true)
            }
        ) {
            return true
        }
        if (link.attr("type").equals("note", ignoreCase = true)) return true

        val hint = (link.className() + " " + link.id() + " " +
            link.attr("rel") + " " + key.substringBefore('#').substringAfterLast('/'))
            .lowercase()
        if (NOTE_HINT.containsMatchIn(hint)) return true

        // EPUB 2 generators commonly used only a superscript numeric marker.
        val marker = link.text().trim().trim('[', ']', '(', ')')
        return (link.normalName() == "sup" || link.parents().any { it.normalName() == "sup" } ||
            link.selectFirst("sup") != null) &&
            marker.isNotEmpty() && marker.all { it.isDigit() || it in "*†‡" }
    }

    private fun safeExternalHref(href: String): Boolean {
        val scheme = href.substringBefore(':', "").lowercase()
        return scheme in SAFE_EXTERNAL_SCHEMES
    }

    private fun quoteMarksFor(element: Element): Pair<String, String> {
        val nested = element.parents().count { it.normalName() == "q" } % 2 == 1
        val language = generateSequence(element as Element?) { it.parent() }
            .mapNotNull { node ->
                node.attr("lang").ifBlank { node.attr("xml:lang") }
                    .substringBefore('-').lowercase().takeIf(String::isNotEmpty)
            }
            .firstOrNull()
        return when (language) {
            "ru", "uk", "be", "fr" -> if (nested) "„" to "“" else "«" to "»"
            "de" -> if (nested) "‚" to "‘" else "„" to "“"
            else -> if (nested) "‘" to "’" else "“" to "”"
        }
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
        val NOTE_HINT = Regex("(?:^|[-_\\s])(footnote|endnote|noteref|note)(?:$|[-_\\s])")
        val SAFE_EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")

        // English compile-time constants: the parser layer has no Android
        // resources. Localizing them means plumbing a strings provider
        // through HtmlMapper — accepted debt for the uk pass.
        const val AUDIO_PLACEHOLDER = "♪ Audio is not supported"
        const val VIDEO_PLACEHOLDER = "▶ Video is not supported"
    }
}
