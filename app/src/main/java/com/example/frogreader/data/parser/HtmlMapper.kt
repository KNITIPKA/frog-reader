package com.example.frogreader.data.parser

import androidx.compose.foundation.text.appendInlineContent
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
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.model.INLINE_FONT_TYPOGRAPHY_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.InlineTextBoxSpec
import com.example.frogreader.data.model.encode
import com.example.frogreader.data.model.INLINE_RUBY_TAG
import com.example.frogreader.data.model.InlineRuby
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableCellStyle
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.model.slicePublisherBoxSpans
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

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
    css: CssResolver? = null,
    /** Writes inline vector `<svg>` markup to a file, returning its path. */
    private val resolveInlineSvg: (String) -> String? = { null },
    /** Finite stack depth for untrusted HTML trees. */
    private val maxStructureDepth: Int = DEFAULT_HTML_STRUCTURE_DEPTH,
    /** Shared expansion budget for CSS-generated model text. */
    private val expansionBudget: HtmlExpansionBudget = HtmlExpansionBudget(),
    /** Stable per-source namespace; EPUB can merge ranges from several files. */
    private val publisherBoxIdPrefix: String = "document",
) {

    init {
        require(maxStructureDepth > 0)
    }

    /**
     * A document does not need a linked `<style>` sheet to use CSS: a lone
     * `style="…"` attribute is author CSS too. Parsers used to pass null in
     * that case, making alignment, display, dimensions and typography vanish.
     * The empty resolver still evaluates inline declarations and tag defaults.
     */
    private var css: CssResolver? = css

    private val out = mutableListOf<ContentElement>()
    private var pendingInline: MutableList<Node> = mutableListOf()
    private var pendingInlineOwner: Element? = null
    private var preindexDoc: RustChapterMarkup.MarkupDocument? = null

    /** Containers whose inline descendants actually create CSS block flow. */
    private val cssBlockContainers = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
    private val cssLineGroups = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())

    /** Publisher box ranges use the same flat leaf coordinates as [out]. */
    val publisherBoxes = mutableListOf<PublisherBoxSpan>()

    private data class OpenPublisherBox(
        val id: String,
        val parentId: String?,
        val startElement: Int,
        val style: PublisherBoxStyle,
        val floatSide: PublisherFloatSide?,
        val clear: PublisherClear,
    )

    private val publisherBoxStack = mutableListOf<String>()
    private var publisherBoxSequence = 0

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

    /** Same-position aliases, indexed while registering each first-seen id. */
    private val anchorIdsByStart = mutableMapOf<Int, MutableList<String>>()

    /** Canonical keys of every internal link referenced by this document. */
    val linkTargets = mutableSetOf<String>()

    /** Subset of [linkTargets] that are semantically note references. */
    val noteTargets = mutableSetOf<String>()

    private val mathFormatter by lazy {
        MathMlFormatter(
            maxDepth = maxStructureDepth,
            resolveInternalLink = resolveLink,
            acceptExternalLink = ::safeExternalHref,
            onInternalLink = linkTargets::add,
            resolveAuthorStyle = ::inlineStyleFor,
        )
    }

    /**
     * Raw anchor id → exact rich block stream when the DOM exposes a
     * semantic footnote/endnote container. Parsers canonicalize the id with
     * the document path before passing these to [buildNotes].
     */
    val noteDocuments = mutableMapOf<String, NoteDocument>()

    fun map(body: Element): List<ContentElement> {
        val doc = body.ownerDocument() as? RustChapterMarkup.MarkupDocument
        preindexDoc = doc
        if (css == null && bodyHasStyleAttr(body)) {
            css = CssResolver(emptyList())
        }
        try {
            indexCssBlockContainers(body)
            val bodyBox = beginPublisherBox(body, allowRoot = true)
            try {
                walk(body, quote = false)
                flushPending(quote = false)
            } finally {
                endPublisherBox(bodyBox)
            }
            return out.toList()
        } finally {
            preindexDoc = null
            css?.clearCache()
        }
    }

    private fun preindexOf(element: Element): RustChapterMarkup.PreindexedElement? {
        val doc = preindexDoc ?: return null
        return (element as? RustChapterMarkup.PreindexedElement)?.takeIf { it.owner === doc }
    }

    private fun treePreindexValid(): Boolean = preindexDoc?.treeMutated == false

    private fun attrValid(mask: Int): Boolean {
        val doc = preindexDoc ?: return false
        return (doc.mutatedAttrs and mask) == 0
    }

    private fun bodyHasStyleAttr(body: Element): Boolean {
        val pre = preindexOf(body)
        if (pre != null && treePreindexValid() && attrValid(RustChapterMarkup.MUTATED_STYLE)) {
            return pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_STYLE)
        }
        return body.hasAttr("style") || body.selectFirst("[style]") != null
    }

    private fun isTextBlank(node: TextNode): Boolean {
        if (node is RustChapterMarkup.PreindexedTextNode &&
            node.textFlags != RustChapterMarkup.TEXT_UNKNOWN
        ) {
            return (node.textFlags and RustChapterMarkup.TEXT_HTML_BLANK) != 0
        }
        return node.isBlank
    }

    private fun isRootTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_ROOT) ?: (element.normalName() in ROOT_TAGS)

    private fun isPublisherBoxTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_PUBLISHER_BOX)
            ?: (element.normalName() in PUBLISHER_BOX_TAGS)

    private fun isNonBlockTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_NON_BLOCK)
            ?: (element.normalName() in NON_BLOCK_TAGS)

    private fun isNonContentTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_NON_CONTENT)
            ?: (element.normalName() in NON_CONTENT_TAGS)

    private fun isNonTextContentTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_NON_TEXT_CONTENT)
            ?: (element.normalName() in NON_TEXT_CONTENT_TAGS)

    private fun isImageTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_IMAGE)
            ?: (element.normalName() in IMAGE_TAGS)

    private fun isNestedListTag(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_NESTED_LIST)
            ?: (element.normalName() in NESTED_LIST_TAGS)

    private fun anchorIdOf(element: Element): String {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_ID)) {
            return pre.anchorId.orEmpty()
        }
        return element.attr("id")
    }

    private fun srcOf(element: Element): String {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_SRC)) {
            return pre.srcAttr.orEmpty()
        }
        return element.attr("src")
    }

    private fun hrefOf(element: Element): String {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_HREF)) {
            return pre.hrefAttr.orEmpty()
        }
        return element.attr("href")
    }

    private fun xlinkHrefOf(element: Element): String {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_SRC)) {
            return pre.xlinkHrefAttr.orEmpty()
        }
        return element.attr("xlink:href")
    }

    private fun hasBlockProbe(element: Element): Boolean {
        val pre = preindexOf(element)
        if (pre != null && treePreindexValid() && attrValid(RustChapterMarkup.MUTATED_OTHER)) {
            return pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_BLOCK_PROBE)
        }
        return element.selectFirst(BLOCK_PROBE) != null
    }

    private inline fun forEachMatchingPreindexed(
        root: Element,
        subtreeFlag: Int,
        action: (RustChapterMarkup.PreindexedElement) -> Unit,
    ): Boolean {
        val rootPre = preindexOf(root) ?: return false
        if (!treePreindexValid()) return false
        if (!rootPre.hasSubtree(subtreeFlag)) return true
        val stack = ArrayList<RustChapterMarkup.PreindexedElement>()
        stack.add(rootPre)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.lastIndex)
            action(current)
            val startSize = stack.size
            var child = current.firstElementChild()
            while (child != null) {
                val childPre = preindexOf(child) ?: return false
                if (childPre.hasSubtree(subtreeFlag)) {
                    stack.add(childPre)
                }
                child = child.nextElementSibling()
            }
            var left = startSize
            var right = stack.lastIndex
            while (left < right) {
                val tmp = stack[left]
                stack[left] = stack[right]
                stack[right] = tmp
                left++
                right--
            }
        }
        return true
    }

    private fun registerAllIds(element: Element) {
        if (attrValid(RustChapterMarkup.MUTATED_ID)) {
            val handled = forEachMatchingPreindexed(element, RustChapterMarkup.SUBTREE_HAS_ID) { pre ->
                pre.anchorId?.takeIf(String::isNotEmpty)?.let(::registerAnchor)
            }
            if (handled) return
        }
        element.select("[id]").forEach { registerAnchor(it.attr("id")) }
    }

    private fun collectImages(element: Element): List<Element> {
        val rootPre = preindexOf(element)
        if (rootPre != null && treePreindexValid()) {
            if (!rootPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_IMG)) return emptyList()
            val images = mutableListOf<Element>()
            val handled = forEachMatchingPreindexed(element, RustChapterMarkup.SUBTREE_HAS_IMG) { pre ->
                if (pre.hasTag(RustChapterMarkup.TAG_IMG)) images.add(pre)
            }
            if (handled) return images
        }
        return element.select("img")
    }

    private inline fun forEachSvg(element: Element, action: (Element) -> Unit) {
        val rootPre = preindexOf(element)
        if (rootPre != null && treePreindexValid()) {
            if (!rootPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SVG)) return
            val svgs = mutableListOf<Element>()
            val handled = forEachMatchingPreindexed(element, RustChapterMarkup.SUBTREE_HAS_SVG) { pre ->
                if (pre.hasTag(RustChapterMarkup.TAG_SVG)) svgs.add(pre)
            }
            if (handled) {
                svgs.forEach(action)
                return
            }
        }
        element.select("svg").forEach(action)
    }

    private fun isCssFlowBlock(element: Element): Boolean =
        !isPublisherBoxTag(element) &&
            !isNonContentTag(element) &&
            css?.computed(element)?.display in CSS_BLOCK_DISPLAYS

    /** One bounded traversal avoids rescanning each enclosing inline wrapper. */
    private fun indexCssBlockContainers(body: Element) {
        val resolver = css ?: return
        val treeValid = treePreindexValid()
        val requireStyleAttr = !resolver.hasDisplayRules && attrValid(RustChapterMarkup.MUTATED_STYLE)
        val bodyPre = if (treeValid) preindexOf(body) else null
        if (bodyPre != null) {
            if (!bodyPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE)) return
            if (requireStyleAttr && !bodyPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_STYLE)) return
        }
        val visited = ArrayList<Element>()
        val stackElements = ArrayList<Element>()
        var stackDepths = IntArray(64)
        var stackSize = 0
        fun push(element: Element, depth: Int) {
            if (stackSize == stackDepths.size) {
                stackDepths = stackDepths.copyOf(stackDepths.size * 2)
            }
            stackElements.add(element)
            stackDepths[stackSize++] = depth
        }
        push(body, 0)
        while (stackSize > 0) {
            val depth = stackDepths[--stackSize]
            val element = stackElements.removeAt(stackSize)
            visited.add(element)
            if (depth >= maxStructureDepth || resolver.computed(element).hidden) continue
            var child = element.firstElementChild()
            while (child != null) {
                val childPre = if (treeValid) preindexOf(child) else null
                if (childPre == null ||
                    (childPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE) &&
                        (!requireStyleAttr || childPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_STYLE)))
                ) {
                    push(child, depth + 1)
                }
                child = child.nextElementSibling()
            }
        }
        for (i in visited.lastIndex downTo 0) {
            val element = visited[i]
            if (isCssFlowBlock(element) || element in cssBlockContainers) {
                element.parent()?.let(cssBlockContainers::add)
            }
            if (element in cssBlockContainers) {
                var allLineCompatible = true
                val count = element.childNodeSize()
                for (j in 0 until count) {
                    val child = element.childNode(j)
                    val ok = when (child) {
                        is TextNode -> isTextBlank(child)
                        is Element -> css?.computed(child)?.hidden == true ||
                            isCssFlowBlock(child) || child in cssLineGroups ||
                            (preindexOf(child)?.hasTag(RustChapterMarkup.TAG_BR) ?: (child.normalName() == "br"))
                        else -> true
                    }
                    if (!ok) {
                        allLineCompatible = false
                        break
                    }
                }
                if (allLineCompatible) cssLineGroups.add(element)
            }
        }
    }

    private fun registerAnchor(id: String) {
        if (id.isNotEmpty() && anchors.putIfAbsent(id, out.size) == null) {
            anchorIdsByStart.getOrPut(out.size) { mutableListOf() }.add(id)
        }
    }

    private fun walk(container: Element, quote: Boolean, depth: Int = 0) {
        val childCount = container.childNodeSize()
        if (depth >= maxStructureDepth) {
            // Preserve text directly owned by the boundary node without
            // descending into a hostile thousands-deep subtree.
            for (i in 0 until childCount) {
                val child = container.childNode(i)
                if (child is TextNode && !isTextBlank(child)) {
                    queuePending(child, container)
                }
            }
            return
        }
        for (i in 0 until childCount) {
            when (val node = container.childNode(i)) {
                is TextNode -> if (!isTextBlank(node) || pendingInline.isNotEmpty()) {
                    // Mixed block/inline paragraphs still collapse a source
                    // space between adjacent ruby, links, or abbreviations.
                    // Ignore formatting whitespace only outside an inline run.
                    queuePending(node, container)
                }

                is Element -> {
                    // EPUB 3/Daisy and many Kindle books wrap a complete note
                    // in aside/note/li with an explicit semantic token. Capture
                    // that DOM boundary while it still exists; the flattened
                    // chapter stream cannot reconstruct it later.
                    val richNoteContainer = isSemanticNoteContainer(node)
                    if (richNoteContainer || isCssFlowBlock(node) || node in cssBlockContainers) {
                        flushPending(quote)
                    }
                    val nodeId = anchorIdOf(node)
                    registerAnchor(nodeId)
                    val noteStart = out.size
                    walkElement(node, quote, depth + 1)
                    if (richNoteContainer) {
                        flushPending(quote)
                        if (out.size > noteStart) {
                            val document = NoteDocument(
                                elements = out.subList(noteStart, out.size).toList(),
                                publisherBoxes = slicePublisherBoxSpans(
                                    spans = publisherBoxes,
                                    startElement = noteStart,
                                    endElementExclusive = out.size,
                                ),
                            )
                            if (nodeId.isNotEmpty()) noteDocuments.putIfAbsent(nodeId, document)
                            val handled = if (attrValid(RustChapterMarkup.MUTATED_ID)) {
                                forEachMatchingPreindexed(node, RustChapterMarkup.SUBTREE_HAS_ID) { pre ->
                                    pre.anchorId?.takeIf(String::isNotEmpty)?.let { id ->
                                        noteDocuments.putIfAbsent(id, document)
                                    }
                                }
                            } else {
                                false
                            }
                            if (!handled) {
                                node.select("[id]").forEach { descendant ->
                                    descendant.attr("id").takeIf(String::isNotEmpty)?.let { id ->
                                        noteDocuments.putIfAbsent(id, document)
                                    }
                                }
                            }
                            // Kindle frequently puts a zero-width filepos
                            // anchor immediately before the semantic aside.
                            // It points at the same output position and is
                            // therefore an exact alias of this note body.
                            anchorIdsByStart[noteStart]?.forEach { id ->
                                noteDocuments.putIfAbsent(id, document)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Whether this element is the source-level boundary of one rich note. */
    private fun isSemanticNoteContainer(node: Element): Boolean {
        val pre = preindexOf(node)
        if (pre != null && treePreindexValid() &&
            attrValid(RustChapterMarkup.MUTATED_ID or RustChapterMarkup.MUTATED_TYPE or RustChapterMarkup.MUTATED_OTHER)
        ) {
            return pre.hasAttrFlag(RustChapterMarkup.ATTR_IS_SEMANTIC_NOTE_CONTAINER)
        }
        val ownTokens = buildString {
            append(node.attr("epub:type"))
            append(' ')
            append(node.attr("role"))
            append(' ')
            append(node.attr("type"))
            append(' ')
            append(node.className())
        }.lowercase().split(NOTE_TOKEN_SEPARATOR)
        if (node.normalName() in NOTE_ITEM_TAGS && ownTokens.any(::isNoteContainerToken)) {
            return true
        }
        if (node.normalName() == "note" || node.normalName() == "prodnote") return true

        // EPUB 2 commonly uses <ol class="notes"><li id="n1">...</li>.
        // The list is the collection, each direct-ish anchored li/p is the
        // individual note boundary.
        if (node.attr("id").isEmpty() || node.normalName() !in NOTE_ITEM_TAGS) return false
        return node.parents().take(3).any { ancestor ->
            val tokens = "${ancestor.attr("epub:type")} ${ancestor.attr("role")} " +
                "${ancestor.attr("type")} ${ancestor.className()}"
            tokens.lowercase().split(NOTE_TOKEN_SEPARATOR).any(::isNoteCollectionToken)
        }
    }

    private fun isNoteContainerToken(token: String): Boolean = token in NOTE_CONTAINER_TOKENS

    private fun isNoteCollectionToken(token: String): Boolean =
        token in NOTE_CONTAINER_TOKENS || token in NOTE_COLLECTION_TOKENS

    private fun walkElement(node: Element, quote: Boolean, depth: Int) {
        // Raster image boxes are opened by emitImage as images discovered
        // inside p/h wrappers do not pass through this dispatcher.
        val opensFlowBox = !isImageTag(node) &&
            isPublisherBoxElement(node, allowRoot = false)
        // Pending loose inline text belongs to the preceding flow context,
        // never to the next sibling's retained CSS box.
        if (opensFlowBox) flushPending(quote)
        val openBox = if (opensFlowBox) beginPublisherBox(node, allowRoot = false, knownBoxElement = true) else null
        try {
            walkElementContent(node, quote, depth)
        } finally {
            endPublisherBox(openBox)
        }
    }

    private fun walkElementContent(node: Element, quote: Boolean, depth: Int) {
        // display:none — skip the element entirely, but keep its anchors
        // (already registered) pointing at the next visible element.
        if (css?.computed(node)?.hidden == true) return

        // Presentation MathML is neither ordinary flattened XML text nor an
        // image: keep scripts/fractions/roots readable and selectable. A
        // display expression is its own centered paragraph; inline math stays
        // in the surrounding sentence.
        if (isMathElement(node)) {
            if (isDisplayMath(node)) {
                flushPending(quote)
                emitMathBlock(node, quote)
            } else {
                queuePending(node, node.parent())
            }
            return
        }

        if (css != null &&
            !(preindexOf(node)?.hasTag(RustChapterMarkup.TAG_CSS_ROW_EXCLUDED) ?: false) &&
            emitCssHorizontalRow(node, quote)
        ) {
            return
        }

        // CSS can turn <span>, <em> or <a> into real blocks. Keeping them in
        // AnnotatedString inline runs loses both their line breaks and boxes.
        if (isCssFlowBlock(node)) {
            flushPending(quote)
            if (node in cssBlockContainers) {
                walk(node, quote = false, depth = depth)
                flushPending(quote = false)
            } else {
                emitParagraph(node, quote = false, depth = depth, anonymous = true)
            }
            return
        }

        when (node.normalName()) {
            "p", "li", "dt", "dd", "pre" -> {
                flushPending(quote)
                emitParagraph(node, quote, depth)
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flushPending(quote)
                emitHeading(node, node.normalName()[1].digitToInt())
            }

            // EPUB 2 may use DAISY DTBook content documents. Their section
            // headings take their level from the enclosing level element.
            "levelhd", "hd", "bridgehead" -> {
                flushPending(quote)
                emitHeading(node, dtbookHeadingLevel(node))
            }

            "doctitle", "covertitle" -> {
                flushPending(quote)
                emitHeading(node, 1)
            }

            "blockquote", "cite", "poem", "epigraph" -> {
                flushPending(quote)
                emitGeneratedParagraph(node, after = false, quote = true)
                walk(node, quote = true, depth = depth)
                flushPending(quote = true)
                emitGeneratedParagraph(node, after = true, quote = true)
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
                walk(node, quote, depth)
                flushPending(quote)
                listStack.removeAt(listStack.lastIndex)
            }

            "list" -> {
                flushPending(quote)
                val kind = node.attr("type").lowercase()
                val ordered = kind == "ol" || node.attr("enum").isNotEmpty()
                val styleType = when (kind) {
                    "pl" -> "none"
                    else -> ListMarkers.cssTypeFor(node.attr("enum").takeIf(String::isNotEmpty))
                        ?: ListMarkers.applicableCssType(css?.computed(node)?.listStyleType, ordered)
                        ?: ListMarkers.defaultType(ordered, listStack.size + 1)
                }
                val start = if (ordered) node.attr("start").toIntOrNull() ?: 1 else 1
                listStack.add(ListContext(ordered, start, styleType))
                walk(node, quote, depth)
                flushPending(quote)
                listStack.removeAt(listStack.lastIndex)
            }

            "figcaption", "caption" -> {
                flushPending(quote)
                if (node in cssBlockContainers || hasBlockProbe(node)) {
                    walk(node, quote, depth)
                    flushPending(quote)
                } else {
                    emitCaptionLike(node, quote)
                }
            }

            "summary" -> {
                flushPending(quote)
                if (hasBlockProbe(node)) {
                    walk(node, quote, depth)
                    flushPending(quote)
                } else {
                    emitSummary(node, quote)
                }
            }

            "audio", "video" -> {
                flushPending(quote)
                emitMediaFallback(node, quote, depth)
            }

            "div", "section", "article", "aside", "main", "header",
            "footer", "dl", "figure", "center",
            "nav", "details",
            // DTBook structural containers. Keeping their original names
            // lets publisher CSS selectors continue to match.
            "dtbook", "book", "frontmatter", "bodymatter", "rearmatter",
            "level", "level1", "level2", "level3", "level4", "level5", "level6",
            "sidebar", "note", "prodnote", "annotation", "linegroup", "imggroup",
            -> {
                flushPending(quote)
                if (isEmptyLineBlock(node)) {
                    out += ContentElement.Spacer(1f)
                    return
                }
                // CSS-generated decorations (div.separator::before "***").
                val emptyBoxStart = out.size
                emitGeneratedParagraph(node, after = false, quote)
                val titleLevel = titleClassLevel(node)
                if (titleLevel != null && headingLevel == null) {
                    headingLevel = titleLevel
                    walk(node, quote, depth)
                    flushPending(quote)
                    headingLevel = null
                } else {
                    walk(node, quote, depth)
                    flushPending(quote)
                }
                emitGeneratedParagraph(node, after = true, quote)
                // An intentionally blank publisher page (for example the
                // reader's drawing page in Tristram Shandy) still owns a
                // dimensioned box and its forced page boundaries.
                if (out.size == emptyBoxStart) {
                    val minimumHeight = css?.publisherBoxStyle(node)?.minHeightEm ?: 0f
                    if (minimumHeight > 0f) {
                        out += ContentElement.Spacer(minimumHeight)
                    }
                }
            }

            "img" -> {
                flushPending(quote)
                emitImage(srcOf(node), node)
            }

            "image" -> {
                // SVG-style <image xlink:href=…/> used by some EPUB covers.
                flushPending(quote)
                emitImage(xlinkHrefOf(node).ifEmpty { hrefOf(node) }, node)
            }

            "svg" -> {
                flushPending(quote)
                emitSvg(node)
            }

            "hr" -> {
                flushPending(quote)
                val computed = css?.computed(node)
                if (computed?.borderSpecified == true) {
                    // Authored rules use the retained box border, width and
                    // margins. A native fixed-width divider would draw a
                    // second rule with unrelated geometry over that box.
                    out += ContentElement.Spacer(computed.heightEm ?: 0f)
                } else {
                    out += ContentElement.Divider
                }
            }

            "table" -> {
                flushPending(quote)
                emitTable(node)
            }

            // DTBook poem lines and attribution/date blocks are blocks even
            // when they contain only inline markup.
            "line", "byline", "dateline", "docauthor", "address" -> {
                flushPending(quote)
                emitParagraph(node, quote, depth)
            }

            "br" -> queuePending(node, node.parent())

            "style", "script", "head", "title", "link", "meta", "source", "track" -> Unit

            else -> {
                // Unknown element: recurse if it holds block content,
                // otherwise treat it as inline (span, a, em, …).
                if (node in cssBlockContainers || hasBlockProbe(node)) {
                    flushPending(quote)
                    walk(node, quote, depth)
                    flushPending(quote)
                } else {
                    queuePending(node, node.parent())
                }
            }
        }
    }

    // -------------------------------------------------------------- blocks

    /**
     * A small authored horizontal text layout can use the native table
     * measurement path without losing the relationship between its columns.
     * This covers paired alternatives with a brace, distributed ornaments,
     * and CSS table-cell rows. General flex wrapping/nesting keeps its
     * readable block fallback rather than pretending to be a full flexbox.
     */
    private fun emitCssHorizontalRow(element: Element, quote: Boolean): Boolean {
        val pre = preindexOf(element)
        val excluded = pre?.hasTag(RustChapterMarkup.TAG_CSS_ROW_EXCLUDED)
            ?: (element.normalName() in CSS_ROW_EXCLUDED_TAGS)
        if (excluded) return false
        val resolver = css ?: return false
        if (!resolver.hasDisplayRules && pre != null && treePreindexValid() &&
            attrValid(RustChapterMarkup.MUTATED_STYLE) && !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_STYLE)
        ) {
            return false
        }
        val computed = resolver.computed(element)
        val display = computed.display
        if (display !in CSS_HORIZONTAL_DISPLAYS) return false
        val flex = display == "flex" || display == "inline-flex"
        if (flex && (computed.flexDirection != "row" || computed.flexWrap != "nowrap")
        ) return false

        val sourceCells = mutableListOf<List<Node>>()
        val anonymous = mutableListOf<Node>()
        fun finishAnonymous() {
            if (anonymous.any { it is TextNode && !isTextBlank(it) }) {
                sourceCells += anonymous.toList()
            }
            anonymous.clear()
        }
        val childCount = element.childNodeSize()
        for (i in 0 until childCount) {
            when (val child = element.childNode(i)) {
                is TextNode -> anonymous += child
                is Element -> {
                    if (resolver.computed(child).hidden || isNonTextContentTag(child)) continue
                    finishAnonymous()
                    // A nested table/list/media box needs its own block
                    // renderer, so leave complex flex containers in flow.
                    val childPre = preindexOf(child)
                    val complex = if (childPre != null && treePreindexValid()) {
                        childPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_HORIZONTAL_COMPLEX)
                    } else {
                        child.normalName() in COMPLEX_HORIZONTAL_CELL_TAGS ||
                            child.selectFirst("table, ul, ol, audio, video, img, svg") != null
                    }
                    if (complex) return false
                    if (!flex && resolver.computed(child).display != "table-cell") return false
                    sourceCells += listOf(child)
                }
            }
        }
        finishAnonymous()
        val rawBefore = resolver.generated(element, after = false)
        val rawAfter = resolver.generated(element, after = true)
        fun hasCell(run: CssResolver.GeneratedRun?) = run != null &&
            (run.text.isNotBlank() || run.publisherBox != null)
        val count = sourceCells.size + (if (hasCell(rawBefore)) 1 else 0) +
            (if (hasCell(rawAfter)) 1 else 0)
        if (count !in 2..MAX_CSS_ROW_CELLS) return false

        flushPending(quote)
        registerAllIds(element)
        val cells = mutableListOf<TableCell>()
        fun addGenerated(raw: CssResolver.GeneratedRun?) {
            if (!hasCell(raw)) return
            val run = raw?.takeIf { it.text.isEmpty() || expansionBudget.acceptGenerated(it.text.length) } ?: return
            val builder = HtmlInlineBuilder()
            appendGeneratedRun(builder, run)
            cells += TableCell(
                text = builder.build(),
                publisherBox = run.publisherBox,
                publisherPaddingSpecified = true,
                publisherBorderSpecified = true,
            )
        }
        addGenerated(rawBefore)
        for (nodes in sourceCells) {
            val cellElement = nodes.singleOrNull() as? Element
            val builder = HtmlInlineBuilder()
            if (cellElement != null) appendCellContent(cellElement, builder)
            else nodes.forEach { appendInline(it, builder) }
            cells += TableCell(
                text = flowAnnotations(builder.build().trimmed(), cellElement ?: element),
                align = cellElement?.let { cellAlign(it, header = false) },
                block = cellElement?.let { tableCellBlockStyle(it, element) },
                publisherBox = cellElement?.let(resolver::publisherBoxStyle),
                publisherPaddingSpecified = true,
                publisherBorderSpecified = true,
            )
        }
        addGenerated(rawAfter)
        if (cells.size < 2) return false
        out += ContentElement.Table(
            rows = listOf(TableRow(cells, isHeader = false)),
            block = blockStyleFor(element)?.copy(firstLineIndent = false),
            publisherRowLayout = when (computed.justifyContent) {
                "space-between" -> PublisherRowLayout.SPACE_BETWEEN
                "space-around", "space-evenly" -> PublisherRowLayout.SPACE_AROUND
                else -> PublisherRowLayout.NATURAL
            },
            centerCellsVertically = computed.alignItems == "center",
        )
        return true
    }

    private fun queuePending(node: Node, owner: Element?) {
        if (pendingInline.isEmpty()) pendingInlineOwner = owner
        pendingInline += node
    }

    /**
     * Opens a retained CSS box without adding a semantic leaf. Only elements
     * that the mapper itself treats as block/structural participate; ordinary
     * inline spans continue to live solely inside AnnotatedString ranges.
     */
    private fun beginPublisherBox(
        element: Element,
        allowRoot: Boolean = false,
        knownBoxElement: Boolean = false,
    ): OpenPublisherBox? {
        if (!knownBoxElement && !isPublisherBoxElement(element, allowRoot)) return null
        val resolver = css ?: return null
        val computed = resolver.computed(element)
        if (computed.hidden) return null
        var style = resolver.publisherBoxStyle(element) ?: PublisherBoxStyle.DEFAULT
        if (element in cssLineGroups && !isRootTag(element)) {
            style = style.copy(fitTextFlow = true)
        }
        val floatSide = when (computed.floatSide) {
            "left" -> PublisherFloatSide.LEFT
            "right" -> PublisherFloatSide.RIGHT
            else -> null
        }
        val clear = when (computed.clear) {
            "left" -> PublisherClear.LEFT
            "right" -> PublisherClear.RIGHT
            "both" -> PublisherClear.BOTH
            else -> PublisherClear.NONE
        }
        if (style.isDefault && floatSide == null && clear == PublisherClear.NONE) return null

        val prefix = publisherBoxIdPrefix.ifBlank { "document" }
        val open = OpenPublisherBox(
            id = "$prefix:publisher-box-${publisherBoxSequence++}",
            parentId = publisherBoxStack.lastOrNull(),
            startElement = out.size,
            style = style,
            floatSide = floatSide,
            clear = clear,
        )
        publisherBoxStack += open.id
        return open
    }

    private fun endPublisherBox(open: OpenPublisherBox?) {
        if (open == null) return
        check(publisherBoxStack.removeAt(publisherBoxStack.lastIndex) == open.id) {
            "Publisher box stack is not properly nested"
        }
        val end = out.size
        if (end == open.startElement) {
            // A decoration/float has no surface without a leaf. Clear is the
            // sole meaningful zero-length flow event and has a dedicated
            // model invariant.
            if (open.clear != PublisherClear.NONE) {
                publisherBoxes += PublisherBoxSpan(
                    id = open.id,
                    parentId = open.parentId,
                    startElement = end,
                    endElementExclusive = end,
                    clear = open.clear,
                )
            } else {
                // Descendant clear markers may survive even when a decorated
                // wrapper has no semantic leaf of its own. Do not leave their
                // parentId pointing at a span that was deliberately omitted.
                for (index in publisherBoxes.indices) {
                    val span = publisherBoxes[index]
                    if (span.parentId == open.id) {
                        publisherBoxes[index] = span.copy(parentId = open.parentId)
                    }
                }
            }
            return
        }
        publisherBoxes += PublisherBoxSpan(
            id = open.id,
            parentId = open.parentId,
            startElement = open.startElement,
            endElementExclusive = end,
            style = open.style,
            floatSide = open.floatSide,
            clear = open.clear,
        )
    }

    private fun isPublisherBoxElement(element: Element, allowRoot: Boolean): Boolean {
        if (isCssFlowBlock(element)) return true
        val pre = preindexOf(element)
        if (pre != null) {
            val isRoot = pre.hasTag(RustChapterMarkup.TAG_ROOT)
            if ((!allowRoot && isRoot) ||
                (pre.hasTag(RustChapterMarkup.TAG_NON_BLOCK) && !pre.hasTag(RustChapterMarkup.TAG_IMAGE))
            ) {
                return false
            }
            return (allowRoot && isRoot) ||
                pre.hasTag(RustChapterMarkup.TAG_PUBLISHER_BOX) ||
                (pre.hasTag(RustChapterMarkup.TAG_MATH) && isDisplayMath(element)) ||
                hasBlockProbe(element)
        }
        val tag = element.normalName()
        if ((!allowRoot && tag in ROOT_TAGS) ||
            (tag in NON_BLOCK_TAGS && tag !in IMAGE_TAGS)
        ) {
            return false
        }
        return (allowRoot && tag in ROOT_TAGS) ||
            tag in PUBLISHER_BOX_TAGS ||
            (isMathElement(element) && isDisplayMath(element)) ||
            element.selectFirst(BLOCK_PROBE) != null
    }

    /** Emits loose inline nodes collected between block elements. */
    private fun flushPending(quote: Boolean) {
        if (pendingInline.isEmpty()) return
        val nodes = pendingInline
        val owner = pendingInlineOwner
        pendingInline = mutableListOf()
        pendingInlineOwner = null
        val builder = HtmlInlineBuilder()
        nodes.forEach { appendInline(it, builder) }
        val hasText = !builder.isBlank
        if (hasText) {
            val level = headingLevel ?: owner?.let(::titleClassLevel)
            if (level != null) {
                out += ContentElement.Heading(
                    styledText = flowAnnotations(builder.build().trimmed(), owner),
                    level = level.coerceIn(1, 6),
                    block = looseBlockTextStyle(owner),
                )
            } else {
                out += ContentElement.Paragraph(
                    flowAnnotations(builder.build(), owner),
                    if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
                    looseBlockTextStyle(owner, withFirstLetter = true),
                )
            }
        }
    }

    /**
     * Direct text inside a structural element forms an anonymous CSS block,
     * not an HTML paragraph. It therefore has neither a browser-default first
     * line indent nor paragraph margins. Preserve an explicit/inherited
     * `text-indent`, but do not inject the reader's prose defaults into labels,
     * rules, generated callouts, or other direct container text.
     */
    private fun looseBlockTextStyle(
        owner: Element?,
        withFirstLetter: Boolean = false,
    ): BlockStyle {
        val authored = owner?.let { blockStyleFor(it, withFirstLetter) }
        val base = authored ?: BlockStyle()
        return base.copy(
            firstLineIndent = base.firstLineIndent ?: false,
            spaceBeforeSpecified = true,
            spaceAfterSpecified = true,
        )
    }

    /** Inline scopes still apply when their child blocks become separate leaves. */
    private fun flowAnnotations(text: AnnotatedString, owner: Element?): AnnotatedString {
        if (text.isEmpty() || owner == null) return text
        val pre = preindexOf(owner)
        if (pre != null && treePreindexValid() && !pre.hasAttr(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE)) {
            return text
        }
        var builder: AnnotatedString.Builder? = null
        var scope = owner
        var depth = 0
        while (scope != null && !isRootTag(scope) && depth++ < maxStructureDepth) {
            if (!isPublisherBoxTag(scope)) {
                inlineBidiMode(scope)?.let { mode ->
                    val b = builder ?: AnnotatedString.Builder(text).also { builder = it }
                    b.addStringAnnotation(BIDI_TAG, mode.name, 0, text.length)
                }
            }
            val isLink = preindexOf(scope)?.hasTag(RustChapterMarkup.TAG_A) ?: (scope.normalName() == "a")
            if (isLink) {
                val href = hrefOf(scope).trim()
                val key = href.takeIf(String::isNotEmpty)?.let(resolveLink)
                if (key != null) {
                    linkTargets += key
                    val note = isNoteReference(scope, key)
                    if (note) noteTargets += key
                    val b = builder ?: AnnotatedString.Builder(text).also { builder = it }
                    b.addStringAnnotation(if (note) FOOTNOTE_TAG else LINK_TAG, key, 0, text.length)
                } else if (safeExternalHref(href)) {
                    val b = builder ?: AnnotatedString.Builder(text).also { builder = it }
                    b.addStringAnnotation(EXTERNAL_LINK_TAG, href, 0, text.length)
                }
            }
            scope = scope.parent()
        }
        return builder?.toAnnotatedString() ?: text
    }

    private fun buildSimpleLeafAnnotatedString(
        element: Element,
        textNode: RustChapterMarkup.PreindexedTextNode,
        trimTrailing: Boolean,
    ): AnnotatedString {
        val computed = css?.computed(element)
        val raw = textNode.wholeText
        val textFlags = textNode.textFlags
        val transform = computed?.textTransform
        val collapsed = if (transform == null || transform == "none") {
            collapsePreindexedLeafText(raw, textFlags, trimTrailing)
        } else {
            val transformed = transformText(raw, transform, element, previousCharacter = null)
            if (transformed === raw) {
                collapsePreindexedLeafText(raw, textFlags, trimTrailing)
            } else {
                collapseLeafText(transformed, trimTrailing)
            }
        }
        if (computed == null || collapsed.isEmpty()) {
            return AnnotatedString(collapsed)
        }
        val family = if (computed.monospace) "monospace" else computed.fontFamilyName
        val hidden = computed.visibilityHidden
        val builder = AnnotatedString.Builder(collapsed.length)
        if (family != null) builder.pushStringAnnotation(INLINE_FONT_FAMILY_TAG, family)
        builder.pushStringAnnotation(INLINE_FONT_TYPOGRAPHY_TAG, computed.encodedFontTypography)
        if (hidden) builder.pushStringAnnotation(HIDDEN_TEXT_TAG, "true")
        builder.append(collapsed)
        if (hidden) builder.pop()
        builder.pop()
        if (family != null) builder.pop()
        return builder.toAnnotatedString()
    }

    private fun collapsePreindexedLeafText(
        raw: String,
        textFlags: Int,
        trimTrailing: Boolean,
    ): String {
        if ((textFlags and RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII) != 0) {
            val start = if ((textFlags and RustChapterMarkup.TEXT_STARTS_HTML_WS) != 0) 1 else 0
            val end = if (trimTrailing && (textFlags and RustChapterMarkup.TEXT_ENDS_HTML_WS) != 0) {
                raw.length - 1
            } else {
                raw.length
            }
            if (start >= end) return ""
            return if (start == 0 && end == raw.length) raw else raw.substring(start, end)
        }
        return collapseLeafText(raw, trimTrailing)
    }

    private fun collapseLeafText(raw: String, trimTrailing: Boolean): String {
        val len = raw.length
        var i = 0
        while (i < len && isHtmlWhitespace(raw[i])) i++
        if (i >= len) return ""
        var end = len
        if (trimTrailing) {
            while (end > i && isHtmlWhitespace(raw[end - 1])) end--
        }
        val firstRunStart = i
        while (i < end && !isHtmlWhitespace(raw[i])) i++
        if (i >= end) {
            return if (firstRunStart == 0 && end == len) raw else raw.substring(firstRunStart, end)
        }
        val sb = StringBuilder(end - firstRunStart)
        sb.append(raw, firstRunStart, i)
        var inSpace = false
        while (i < end) {
            if (isHtmlWhitespace(raw[i])) {
                while (i < end && isHtmlWhitespace(raw[i])) i++
                if (!inSpace) {
                    sb.append(' ')
                    inSpace = true
                }
            } else {
                val runStart = i
                i++
                while (i < end && !isHtmlWhitespace(raw[i])) i++
                sb.append(raw, runStart, i)
                inSpace = false
            }
        }
        return sb.toString()
    }

    private fun emitParagraph(
        element: Element,
        quote: Boolean,
        depth: Int,
        anonymous: Boolean = false,
    ) {
        // A stanza <p> can itself contain CSS block lines. It remains a box
        // around the lines, while their own typography and geometry become
        // independent leaves. Hidden fallback <br>s contribute no extra gaps.
        if (element in cssBlockContainers && element.normalName() !in setOf("li", "pre")) {
            emitGeneratedParagraph(element, after = false, quote)
            walk(element, quote = false, depth = depth)
            flushPending(quote = false)
            emitGeneratedParagraph(element, after = true, quote)
            return
        }
        val pre = preindexOf(element)
        val isSimpleLeafText = pre != null && pre.hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT)
        // Anchors inside the paragraph (e.g. <a id="n53"/>) resolve to it.
        if (isSimpleLeafText) {
            registerAnchor(anchorIdOf(element))
        } else {
            registerAllIds(element)
        }

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

        if (isSimpleLeafText &&
            ((!anonymous && element.normalName() == "p") ||
                (anonymous && element.normalName() !in setOf("li", "pre"))) &&
            css?.generated(element, after = false) == null &&
            css?.generated(element, after = true) == null &&
            css?.firstLetter(element) == null
        ) {
            val textNode = element.childNode(0) as? RustChapterMarkup.PreindexedTextNode
            if (textNode != null && textNode.textFlags != RustChapterMarkup.TEXT_UNKNOWN) {
                val styledText = buildSimpleLeafAnnotatedString(
                    element = element,
                    textNode = textNode,
                    trimTrailing = false,
                )
                if (styledText.isNotEmpty()) {
                    val block = if (anonymous) {
                        looseBlockTextStyle(element, withFirstLetter = false).copy(isCssTextLine = true)
                    } else {
                        blockStyleFor(element, withFirstLetter = false)
                    }
                    out += ContentElement.Paragraph(
                        flowAnnotations(styledText, element),
                        if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
                        block,
                    )
                }
                return
            }
        }

        val builder = HtmlInlineBuilder()
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
        val explicitInitial = if (!preformatted && !isListItem) {
            explicitFloatedInitial(element)
        } else {
            null
        }
        // CSS-generated text (::before markers, decorations) opens the run.
        appendGeneratedRun(builder, generated(element, after = false))
        // A list item's own text is inline, but a nested <ul>/<ol> inside it
        // is a block — walked separately below, not flattened into the text.
        val childCount = element.childNodeSize()
        for (i in 0 until childCount) {
            val child = element.childNode(i)
            if (child is Element && isNestedListTag(child)) continue
            appendInline(
                child,
                builder,
                preserveWhitespace = preformatted,
                suppressedStyleElement = explicitInitial?.element,
            )
        }
        appendGeneratedRun(builder, generated(element, after = true))
        if (preformatted) builder.pop()
        // A small CSS-floated image travels with the paragraph so the
        // renderer can wrap text around it (publisher's formatting mode).
        val images = collectImages(element)
        var floatImage: FloatImage? = null
        var floatElement: Element? = null
        val resolver = css
        if (resolver != null && images.isNotEmpty()) {
            for (img in images) {
                val computed = resolver.computed(img)
                if (computed.hidden) continue
                val side = computed.floatSide ?: continue
                val frac = computed.widthFrac
                    ?: computed.widthEm?.let { it / 30f }
                    ?: 0.33f
                if (frac > 0.45f) continue
                val path = resolveImage(srcOf(img)) ?: continue
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
            var block = if (anonymous) {
                looseBlockTextStyle(element, withFirstLetter = true).copy(isCssTextLine = true)
            } else {
                blockStyleFor(element, withFirstLetter = true)
            }
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
            if (explicitInitial != null) {
                block = (block ?: BlockStyle()).copy(firstLetter = explicitInitial.style)
            }
            if (preformatted) {
                block = (block ?: BlockStyle()).copy(
                    fontFamily = "monospace",
                    firstLineIndent = false,
                )
            }
            out += ContentElement.Paragraph(
                flowAnnotations(builder.build(), element),
                if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
                block,
            )
        } else {
            floatElement = null // no paragraph to carry it — keep the block image
        }
        // Images nested inside paragraphs still need to be shown (the
        // consumed float is drawn with its paragraph instead).
        val inlineRefs = if (images.isNotEmpty() ||
            (preindexOf(element)?.let { treePreindexValid() && it.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SVG) } != false)
        ) {
            builder.imageRefs.toSet()
        } else {
            emptySet()
        }
        for (image in images) {
            val src = srcOf(image)
            val path = resolveImage(src)
            if (image !== floatElement &&
                (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs)
            ) {
                emitImage(src, image)
            }
        }
        forEachSvg(element) { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
        val hasNestedList = preindexOf(element)?.let { pre ->
            if (treePreindexValid()) pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_NESTED_LIST) else true
        } ?: true
        if (hasNestedList) {
            var nested = element.firstElementChild()
            while (nested != null) {
                if (isNestedListTag(nested) && depth < maxStructureDepth) {
                    walkElement(nested, quote, depth + 1)
                }
                nested = nested.nextElementSibling()
            }
        }
    }

    private fun emitHeading(element: Element, level: Int) {
        val pre = preindexOf(element)
        val isSimpleLeafText = pre != null && pre.hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT)
        if (isSimpleLeafText) {
            registerAnchor(anchorIdOf(element))
            if (css?.generated(element, after = false) == null &&
                css?.generated(element, after = true) == null &&
                css?.firstLetter(element) == null
            ) {
                val textNode = element.childNode(0) as? RustChapterMarkup.PreindexedTextNode
                if (textNode != null && textNode.textFlags != RustChapterMarkup.TEXT_UNKNOWN) {
                    val styledText = buildSimpleLeafAnnotatedString(
                        element = element,
                        textNode = textNode,
                        trimTrailing = true,
                    )
                    if (styledText.isNotEmpty()) {
                        out += ContentElement.Heading(
                            styledText = styledText,
                            level = level.coerceIn(1, 6),
                            block = blockStyleFor(element),
                        )
                    }
                    return
                }
            }
        } else {
            registerAllIds(element)
        }
        val builder = HtmlInlineBuilder()
        appendGeneratedRun(builder, generated(element, after = false))
        appendChildrenInline(element, builder)
        appendGeneratedRun(builder, generated(element, after = true))
        val hasText = !builder.isBlank
        if (hasText) {
            out += ContentElement.Heading(
                styledText = builder.build().trimmed(),
                level = level.coerceIn(1, 6),
                block = blockStyleFor(element),
            )
        }
        val images = collectImages(element)
        val hasSvg = preindexOf(element)?.let { treePreindexValid() && it.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SVG) } != false
        val inlineRefs = if (images.isNotEmpty() || hasSvg) builder.imageRefs.toSet() else emptySet()
        for (image in images) {
            val src = srcOf(image)
            val path = resolveImage(src)
            if (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs) {
                emitImage(src, image)
            }
        }
        forEachSvg(element) { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
    }

    private fun emitMathBlock(element: Element, quote: Boolean) {
        registerMathAnchors(element)
        val builder = InlineTextBuilder()
        // Root block typography is already represented by BlockStyle below;
        // descendants can still add relative CSS/MathML spans.
        mathFormatter.append(element, builder, applyRootAuthorStyle = false)
        if (builder.isBlank) return
        val base = blockStyleFor(element) ?: BlockStyle()
        out += ContentElement.Paragraph(
            builder.build(),
            if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
            base.copy(
                align = base.align ?: BlockAlign.CENTER,
                firstLineIndent = false,
            ),
        )
    }

    /** `<figcaption>`: centered italic small print, like a table caption. */
    private fun emitCaptionLike(node: Element, quote: Boolean) {
        registerAllIds(node)
        val builder = HtmlInlineBuilder()
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
                    fontScale = (base.fontScale ?: 1f).let {
                        if (it in 0.99f..1.01f) 0.9f else it
                    },
                    firstLineIndent = false,
                ),
            )
        }
        val images = collectImages(node)
        val hasSvg = preindexOf(node)?.let { treePreindexValid() && it.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SVG) } != false
        val inlineRefs = if (images.isNotEmpty() || hasSvg) builder.imageRefs.toSet() else emptySet()
        for (image in images) {
            val src = srcOf(image)
            val path = resolveImage(src)
            if (!hasText || isBlockLikeMedia(image) || path != null && path !in inlineRefs) {
                emitImage(src, image)
            }
        }
        forEachSvg(node) { svg ->
            val path = resolveSvg(svg)
            if (!hasText || path !in inlineRefs) path?.let { out += ContentElement.Image(it) }
        }
    }

    /** `<summary>`: the disclosure caption reads as a bold lead-in line. */
    private fun emitSummary(node: Element, quote: Boolean) {
        registerAllIds(node)
        val builder = HtmlInlineBuilder()
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
    private fun emitMediaFallback(node: Element, quote: Boolean, depth: Int) {
        val poster = node.attr("poster")
        if (poster.isNotEmpty() && resolveImage(poster) != null) {
            emitImage(poster)
            return
        }
        val hasFallbackText = preindexOf(node)?.let { pre ->
            if (treePreindexValid()) pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_JAVA_NON_WS_TEXT) else null
        } ?: node.text().isNotBlank()
        if (hasBlockProbe(node) || hasFallbackText) {
            // Keep the caller's structural depth. Resetting it here lets a
            // hostile chain of nested media fallback elements bypass the
            // shared HTML depth guard and overflow the stack.
            walk(node, quote, depth)
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

    private fun findFirstCaption(table: Element): Element? {
        val pre = preindexOf(table)
        if (pre != null && treePreindexValid()) {
            if (!pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_CAPTION)) return null
            var found: Element? = null
            val stack = ArrayList<RustChapterMarkup.PreindexedElement>()
            stack.add(pre)
            while (stack.isNotEmpty()) {
                val current = stack.removeAt(stack.lastIndex)
                if (current.hasTag(RustChapterMarkup.TAG_CAPTION)) {
                    found = current
                    break
                }
                val startSize = stack.size
                var child = current.firstElementChild()
                while (child != null) {
                    val childPre = preindexOf(child) ?: return table.selectFirst("caption")
                    if (childPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_CAPTION)) {
                        stack.add(childPre)
                    }
                    child = child.nextElementSibling()
                }
                var left = startSize
                var right = stack.lastIndex
                while (left < right) {
                    val tmp = stack[left]
                    stack[left] = stack[right]
                    stack[right] = tmp
                    left++
                    right--
                }
            }
            return found
        }
        return table.selectFirst("caption")
    }

    private fun collectOwnTableRows(table: Element): List<Element> {
        val rows = ArrayList<Element>()
        val stack = ArrayList<Element>()
        var child = table.firstElementChild()
        while (child != null) {
            stack.add(child)
            child = child.nextElementSibling()
        }
        stack.reverse()
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.lastIndex)
            val isTable = preindexOf(current)?.hasTag(RustChapterMarkup.TAG_TABLE)
                ?: (current.normalName() == "table")
            if (isTable) continue
            val isTr = preindexOf(current)?.hasTag(RustChapterMarkup.TAG_TR)
                ?: (current.normalName() == "tr")
            if (isTr) {
                if (css?.computed(current)?.hidden != true) rows.add(current)
            }
            val startSize = stack.size
            var grandChild = current.firstElementChild()
            while (grandChild != null) {
                stack.add(grandChild)
                grandChild = grandChild.nextElementSibling()
            }
            var left = startSize
            var right = stack.lastIndex
            while (left < right) {
                val tmp = stack[left]
                stack[left] = stack[right]
                stack[right] = tmp
                left++
                right--
            }
        }
        return rows
    }

    /**
     * Tables become real grids ([ContentElement.Table]) with colspan/rowspan
     * intact. Degenerate cases keep the old flattened behavior: no rows →
     * one paragraph, a single column (layout tables) → plain paragraphs,
     * excessively wide grids → rich per-cell paragraphs. A long table is
     * still a table: plays routinely contain hundreds of two-column rows,
     * and the reader already paginates those rows without flattening them.
     */
    private fun emitTable(table: Element) {
        val tableBlock = blockStyleFor(table)
        // The caption reads like a small centered title above the grid.
        findFirstCaption(table)?.let(::emitTableCaption)

        // Only this table's own rows; a nested table flattens into its cell.
        val ownRows = collectOwnTableRows(table)
        if (ownRows.isEmpty()) {
            val text = table.text().trim()
            if (text.isNotEmpty()) out += ContentElement.Paragraph(AnnotatedString(text))
            return
        }

        val sourceRows = ownRows.mapNotNull { rowElement ->
            val cells = ArrayList<Element>()
            var cell = rowElement.firstElementChild()
            while (cell != null) {
                val name = cell.normalName()
                if ((name == "td" || name == "th") && css?.computed(cell)?.hidden != true) {
                    cells.add(cell)
                }
                cell = cell.nextElementSibling()
            }
            cells.takeIf { it.isNotEmpty() }?.let { rowElement to it }
        }
        val responsive = responsiveTableStyle(table, sourceRows.map { it.second })
        val rows = mutableListOf<TableRow>()
        for ((rowElement, cellElements) in sourceRows) {
            var inHead = false
            var parent = rowElement.parent()
            while (parent != null) {
                if (parent.normalName() == "thead") {
                    inHead = true
                    break
                }
                parent = parent.parent()
            }
            val cells = cellElements.map { cellElement ->
                val builder = HtmlInlineBuilder()
                appendCellContent(cellElement, builder)
                val header = cellElement.normalName() == "th"
                val cellComputed = css?.computed(cellElement)
                val cellPublisherBox = css?.publisherBoxStyle(cellElement)
                    ?: PublisherBoxStyle.DEFAULT.takeIf {
                        cellComputed?.paddingSpecified == true ||
                            cellComputed?.borderSpecified == true
                    }
                TableCell(
                    text = builder.build(),
                    colSpan = cellElement.attr("colspan").toIntOrNull()?.coerceIn(1, 10) ?: 1,
                    rowSpan = cellElement.attr("rowspan").toIntOrNull()?.coerceIn(1, 20) ?: 1,
                    align = cellAlign(cellElement, header),
                    header = header,
                    block = tableCellBlockStyle(cellElement, table),
                    publisherBox = cellPublisherBox,
                    publisherPaddingSpecified = cellComputed?.paddingSpecified == true,
                    publisherBorderSpecified = tableBorderSpecified(cellElement, table, css),
                    narrowStyle = responsive?.cells?.get(cellElement),
                )
            }
            rows += TableRow(cells, isHeader = inHead || cells.all { it.header })
        }

        val columnCount = rows.maxOfOrNull { row -> row.cells.sumOf { it.colSpan } } ?: 0
        val authoredGeometry = css?.publisherBoxStyle(table) != null || rows.any { row ->
            row.cells.any { cell ->
                cell.publisherBorderSpecified || cell.publisherPaddingSpecified ||
                    cell.publisherBox?.isDefault == false
            }
        }
        when {
            rows.isEmpty() || columnCount == 0 -> Unit

            columnCount == 1 && !authoredGeometry -> {
                // A layout "table": plain paragraphs read better than a grid.
                registerAllIds(table)
                for (row in rows) {
                    for (cell in row.cells) {
                        if (cell.text.text.isBlank()) continue
                        out += ContentElement.Paragraph(
                            cell.text,
                            ParagraphStyle.NORMAL,
                            mergedTableCellBlock(tableBlock, cell),
                        )
                    }
                }
            }

            columnCount > 12 -> {
                registerAllIds(table)
                for (row in rows) {
                    for (cell in row.cells) {
                        if (cell.text.text.isBlank()) continue
                        out += ContentElement.Paragraph(
                            cell.text,
                            ParagraphStyle.NORMAL,
                            mergedTableCellBlock(tableBlock, cell),
                        )
                    }
                }
            }

            else -> {
                // Row/cell anchors resolve to the retained grid, after its
                // caption rather than to that unrelated caption paragraph.
                registerAllIds(table)
                out += ContentElement.Table(
                    rows, tableBlock,
                    stackCellsBelowCssPx = responsive?.widthCssPx,
                )
            }
        }
    }

    private class ResponsiveTableStyle(
        val widthCssPx: Float,
        val cells: IdentityHashMap<Element, TableCellStyle>,
    )

    /** Preserve the authored narrow geometry without changing source row/cell indices. */
    private fun responsiveTableStyle(table: Element, sourceRows: List<List<Element>>): ResponsiveTableStyle? {
        val base = css ?: return null
        val thresholds = base.maxWidthBreakpointsCssPx
        if (thresholds.isEmpty()) return null
        if (sourceRows.isEmpty() || sourceRows.any { it.size !in 2..4 } ||
            sourceRows.sumOf { it.size } > MAX_RESPONSIVE_TABLE_CELLS
        ) return null
        val cells = sourceRows.flatten()
        if (cells.any { cell ->
                (cell.attr("colspan").toIntOrNull()?.coerceIn(1, 10) ?: 1) != 1 ||
                    (cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 20) ?: 1) != 1 ||
                    base.computed(cell).display !in setOf(null, "table-cell")
            }
        ) return null
        val descendants = ArrayList<Element>()
        fun collectChildren(element: Element): Boolean {
            for (child in element.childNodes()) {
                if (child !is Element) continue
                if (descendants.size >= MAX_RESPONSIVE_TABLE_DESCENDANTS) return false
                descendants.add(child)
            }
            return true
        }
        for (cell in cells) if (!collectChildren(cell)) return null
        var descendantIndex = 0
        while (descendantIndex < descendants.size) {
            if (!collectChildren(descendants[descendantIndex++])) return null
        }
        var admitted: ResponsiveTableStyle? = null
        var admittedCells: List<TableCellStyle>? = null
        for (threshold in thresholds) {
            val resolver = base.resolverAtMaxWidth(threshold) ?: continue
            if (resolver.computed(table).hidden ||
                resolver.publisherBoxStyle(table) != base.publisherBoxStyle(table)
            ) return null
            val matches = cells.all { cell ->
                val own = resolver.computed(cell)
                val original = base.computed(cell)
                own.display == "block" && !own.hidden &&
                    own.visibilityHidden == original.visibilityHidden &&
                    own.textTransform == original.textTransform &&
                    // Named family annotations and inline effects already
                    // belong to the shared source text, not the cell block.
                    own.fontFamilyName == original.fontFamilyName &&
                    own.fontTypography == original.fontTypography &&
                    own.underline == original.underline && own.strike == original.strike &&
                    own.superScript == original.superScript && own.subScript == original.subScript &&
                    own.unicodeBidi == original.unicodeBidi &&
                    sameResponsiveGeneratedContent(cell, base, resolver)
            }
            if (!matches) {
                // A second, narrower rule that changes the admitted block
                // arrangement cannot be represented by a single variant.
                if (admitted != null) return null
                continue
            }
            // The responsive contract changes cell geometry and block
            // typography; its AnnotatedString remains shared with the wide
            // layout. Reject media changes that would require remapping it.
            if (descendants.any { element ->
                    !sameResponsiveInlinePresentation(base.computed(element), resolver.computed(element)) ||
                        base.publisherBoxStyle(element) != resolver.publisherBoxStyle(element) ||
                        !sameResponsiveGeneratedContent(element, base, resolver)
                }
            ) return null
            val variants = cells.map { cell ->
                responsiveCellStyle(cell, table, cell.normalName() == "th", resolver)
            }
            if (admitted == null) {
                val styles = IdentityHashMap<Element, TableCellStyle>(cells.size)
                cells.forEachIndexed { index, cell -> styles[cell] = variants[index] }
                admitted = ResponsiveTableStyle(threshold, styles)
                admittedCells = variants
            } else if (variants != admittedCells) {
                return null
            }
        }
        return admitted
    }

    private fun sameResponsiveInlinePresentation(a: CssResolver.Computed, b: CssResolver.Computed): Boolean =
        a.hidden == b.hidden && a.display == b.display &&
            a.italic == b.italic && a.fontWeight == b.fontWeight && a.fontSizeEm == b.fontSizeEm &&
            a.fontFamilyName == b.fontFamilyName && a.fontFeatureSettings == b.fontFeatureSettings &&
            a.fontTypography == b.fontTypography &&
            a.monospace == b.monospace && a.underline == b.underline && a.strike == b.strike &&
            a.superScript == b.superScript && a.subScript == b.subScript &&
            a.foregroundColorArgb == b.foregroundColorArgb && a.backgroundColorArgb == b.backgroundColorArgb &&
            a.direction == b.direction && a.unicodeBidi == b.unicodeBidi &&
            a.visibilityHidden == b.visibilityHidden && a.textTransform == b.textTransform &&
            a.widthFrac == b.widthFrac && a.widthEm == b.widthEm && a.heightEm == b.heightEm &&
            a.maxWidthFrac == b.maxWidthFrac && a.maxWidthEm == b.maxWidthEm &&
            a.maxHeightEm == b.maxHeightEm && a.maxHeightViewportFrac == b.maxHeightViewportFrac &&
            a.imageInvert == b.imageInvert

    private fun sameResponsiveGeneratedContent(element: Element, base: CssResolver, narrow: CssResolver): Boolean {
        fun same(a: CssResolver.GeneratedRun?, b: CssResolver.GeneratedRun?): Boolean {
            if (a == null || b == null) return a == null && b == null
            return a.text == b.text && a.italic == b.italic && a.fontWeight == b.fontWeight && a.scale == b.scale &&
                a.foregroundColorArgb == b.foregroundColorArgb && a.backgroundColorArgb == b.backgroundColorArgb &&
                a.fontFeatureSettings == b.fontFeatureSettings && a.fontFamilyName == b.fontFamilyName &&
                a.fontTypography == b.fontTypography &&
                a.textTransform == b.textTransform && a.visibilityHidden == b.visibilityHidden &&
                a.publisherBox == b.publisherBox
        }
        return same(base.generated(element, after = false), narrow.generated(element, after = false)) &&
            same(base.generated(element, after = true), narrow.generated(element, after = true))
    }

    private fun responsiveCellStyle(
        cell: Element,
        table: Element,
        header: Boolean,
        resolver: CssResolver,
    ): TableCellStyle {
        val computed = resolver.computed(cell)
        val box = resolver.publisherBoxStyle(cell)
            ?: PublisherBoxStyle.DEFAULT.takeIf { computed.paddingSpecified || computed.borderSpecified }
        return TableCellStyle(
            block = tableCellBlockStyle(cell, table, resolver, css),
            publisherBox = box,
            align = cellAlign(cell, header, resolver),
            publisherPaddingSpecified = computed.paddingSpecified,
            publisherBorderSpecified = tableBorderSpecified(cell, table, resolver),
        )
    }

    /**
     * Table/row border declarations opt out of the reader's fallback grid,
     * including explicit `none`/zero values that have no painted box. Borders
     * themselves still belong to their source element; CSS does not inherit
     * the table's outer frame into every cell.
     */
    private fun tableBorderSpecified(cell: Element, table: Element, resolver: CssResolver?): Boolean {
        var element: Element? = cell
        while (element != null) {
            if (resolver?.computed(element)?.borderSpecified == true) {
                if (element === cell || element === table) return true
                val rowBox = resolver.publisherBoxStyle(element)
                val hasPaintedRowBorder = listOf(
                    rowBox?.borderTop, rowBox?.borderRight, rowBox?.borderBottom, rowBox?.borderLeft,
                ).any { it != null && (it.usesCurrentColor || (it.colorArgb ushr 24) != 0) }
                // Row/group frames are not retained by the grid renderer.
                // Their explicit borderless policy is still representable;
                // positive row borders must not erase the fallback grid.
                if (!hasPaintedRowBorder) return true
            }
            if (element === table) {
                // Legacy EPUB/MOBI markup can express a borderless table
                // without a stylesheet. Positive borders use the default grid.
                return element.hasAttr("border") && element.attr("border").trim().toIntOrNull() == 0
            }
            element = element.parent()
        }
        return false
    }

    private fun emitTableCaption(caption: Element) {
        val openBox = beginPublisherBox(caption)
        try {
            val builder = HtmlInlineBuilder()
            appendChildrenInline(caption, builder)
            if (!builder.isBlank) {
                out += ContentElement.Paragraph(
                    builder.build(),
                    ParagraphStyle.NORMAL,
                    (blockStyleFor(caption) ?: BlockStyle.DEFAULT).copy(
                        align = BlockAlign.CENTER,
                        italic = true,
                        firstLineIndent = false,
                    ),
                )
            }
        } finally {
            endPublisherBox(openBox)
        }
    }

    /** Cell content: inline runs, block children separated by line breaks. */
    private fun appendCellContent(cell: Element, builder: HtmlInlineBuilder) {
        val wholeCellStyle = tableCellInlineStyle(cell)
        if (wholeCellStyle != null) builder.pushStyle(wholeCellStyle)
        appendGeneratedRun(builder, generated(cell, after = false))
        val childCount = cell.childNodeSize()
        for (i in 0 until childCount) {
            val child = cell.childNode(i)
            val isCellBlock = child is Element && (
                (preindexOf(child)?.hasTag(RustChapterMarkup.TAG_CELL_BLOCK)
                    ?: (child.normalName() in CELL_BLOCK_TAGS)) ||
                    isCssFlowBlock(child)
                )
            if (isCellBlock) {
                if (!builder.isBlank && builder.lastCharacter != '\n') builder.lineBreak()
                // Treat the block as an inline formatting scope inside the
                // cell. Calling appendChildrenInline directly used to drop a
                // p/div's own CSS while keeping only styles on its children.
                appendInline(child, builder)
                builder.lineBreak()
            } else {
                appendInline(child, builder)
            }
        }
        appendGeneratedRun(builder, generated(cell, after = true))
        if (wholeCellStyle != null) builder.pop()
    }

    /**
     * Typography on `tr`/`td`/`th`, expressed as an absolute override of the
     * table style.  It cannot live only in an AnnotatedString span: named
     * embedded font families are resolved later by the renderer and the
     * publisher-formatting toggle must continue to control them.
     */
    private fun tableCellBlockStyle(
        cell: Element,
        table: Element,
        resolver: CssResolver? = css,
        baseResolver: CssResolver? = resolver,
    ): BlockStyle? {
        val language = languageFor(cell)
        val tableLanguage = languageFor(table)
        if (resolver == null) {
            val direction = attributeDirection(cell)
            val style = BlockStyle(
                language = language.takeIf { it != tableLanguage },
                direction = direction.takeIf { it != attributeDirection(table) },
                foregroundColorArgb = legacyForeground(cell)
                    ?.takeIf { it != legacyForeground(table) },
                backgroundColorArgb = legacyVisualBackground(cell, table),
            )
            return style.takeUnless { it.isDefault }
        }

        val own = resolver.computed(cell)
        val base = (baseResolver ?: resolver).computed(table)
        val ownDirection = when (own.direction) {
            "ltr" -> BookTextDirection.LTR
            "rtl" -> BookTextDirection.RTL
            "auto" -> BookTextDirection.AUTO
            else -> attributeDirection(cell)
        }
        val baseDirection = when (base.direction) {
            "ltr" -> BookTextDirection.LTR
            "rtl" -> BookTextDirection.RTL
            "auto" -> BookTextDirection.AUTO
            else -> attributeDirection(table)
        }
        val style = BlockStyle(
            italic = own.italic.takeIf { it != base.italic },
            bold = own.bold.takeIf { it != base.bold },
            fontWeight = own.fontWeight.takeIf { it != base.fontWeight },
            // Absolute root-em scale: ReaderMetrics replaces, rather than
            // multiplies, the surrounding table scale when this is present.
            fontScale = own.fontSizeEm.coerceIn(0.5f, 3f).takeIf {
                kotlin.math.abs(own.fontSizeEm - base.fontSizeEm) > 0.001f
            },
            fontFamily = own.fontFamilyName.takeIf { it != base.fontFamilyName },
            fontFeatureSettings = own.fontFeatureSettings.orEmpty().takeIf { own.fontFeatureSettings != base.fontFeatureSettings },
            fontTypography = own.fontTypography.takeIf { it != base.fontTypography },
            lineHeightMult = when {
                own.lineHeightMult == null && base.lineHeightMult != null -> 1.3f
                own.lineHeightMult != null && (
                    base.lineHeightMult == null ||
                        kotlin.math.abs(own.lineHeightMult - base.lineHeightMult) > 0.001f
                    ) -> own.lineHeightMult
                else -> null
            },
            language = language.takeIf { it != tableLanguage },
            direction = ownDirection.takeIf { it != baseDirection },
            foregroundColorArgb = own.foregroundColorArgb
                .takeIf { it != base.foregroundColorArgb },
            backgroundColorArgb = resolver.visualBackground(cell, table),
        )
        return style.takeUnless { it.isDefault }
    }

    /** Decorations have no BlockStyle fields, so retain them over the cell. */
    private fun tableCellInlineStyle(cell: Element): SpanStyle? {
        val computed = css?.computed(cell) ?: return null
        var style = SpanStyle()
        var any = false
        val decorations = buildList {
            if (computed.underline) add(TextDecoration.Underline)
            if (computed.strike) add(TextDecoration.LineThrough)
        }
        if (decorations.isNotEmpty()) {
            style = style.copy(textDecoration = TextDecoration.combine(decorations))
            any = true
        }
        if (computed.superScript || computed.subScript) {
            style = style.copy(
                baselineShift = if (computed.superScript) {
                    BaselineShift.Superscript
                } else {
                    BaselineShift.Subscript
                },
                fontSize = TextUnit(0.75f, TextUnitType.Em),
            )
            any = true
        }
        return style.takeIf { any }
    }

    /** Table geometry plus a cell's absolute typography for 1-column fallback. */
    private fun mergedTableCellBlock(
        table: BlockStyle?,
        cell: TableCell,
    ): BlockStyle {
        val base = table ?: BlockStyle.DEFAULT
        val own = cell.block
        return base.copy(
            align = cell.align ?: base.align,
            italic = own?.italic ?: base.italic,
            bold = own?.bold ?: base.bold,
            fontWeight = own?.fontWeight ?: base.fontWeight,
            fontScale = own?.fontScale ?: base.fontScale,
            fontFamily = own?.fontFamily ?: base.fontFamily,
            fontFeatureSettings = own?.fontFeatureSettings ?: base.fontFeatureSettings,
            fontTypography = own?.fontTypography ?: base.fontTypography,
            lineHeightMult = own?.lineHeightMult ?: base.lineHeightMult,
            language = own?.language ?: base.language,
            direction = own?.direction ?: base.direction,
            foregroundColorArgb = own?.foregroundColorArgb ?: base.foregroundColorArgb,
            backgroundColorArgb = own?.backgroundColorArgb ?: base.backgroundColorArgb,
            firstLineIndent = false,
        )
    }

    /**
     * Legacy HTML uses `align=` instead of CSS, especially in MOBI6. Search
     * wrapper blocks too: `<div align="center"><p>…</p></div>` is common.
     * Used when no CSS resolver is available. With a resolver, presentational
     * hints participate at their owning element before CSS and inheritance.
     */
    private fun attributeAlign(element: Element): BlockAlign? {
        val doc = preindexDoc
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_PRESENTATIONAL)
        if (doc != null && treePreindexValid() && canCheckAttr &&
            !doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL)
        ) {
            return null
        }
        var node: Element? = element
        while (node != null && !isRootTag(node)) {
            if (node.normalName() == "center") return BlockAlign.CENTER
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr && !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL)) {
                node = node.parent()
                continue
            }
            when (node.attr("align").lowercase()) {
                "center" -> return BlockAlign.CENTER
                "right" -> return BlockAlign.RIGHT
                "left" -> return BlockAlign.LEFT
                "justify" -> return BlockAlign.JUSTIFY
            }
            node = node.parent()
        }
        return null
    }

    private fun cellAlign(cell: Element, header: Boolean, resolver: CssResolver? = css): BlockAlign? {
        val cssAlign = resolver?.let {
            // Only the cell's own/row/table alignment matters — an inherited
            // body-wide justify would look broken in narrow cells.
            when (it.computed(cell).textAlign) {
                "center" -> BlockAlign.CENTER
                "end" -> BlockAlign.END
                "start" -> BlockAlign.START
                "right" -> BlockAlign.RIGHT
                "left" -> BlockAlign.LEFT
                else -> null
            }
        }
        if (cssAlign != null) return cssAlign
        return when (cell.attr("align").lowercase()) {
            "center" -> BlockAlign.CENTER
            "right" -> BlockAlign.RIGHT
            "left" -> BlockAlign.LEFT
            else -> if (header) BlockAlign.CENTER else null
        }
    }

    private fun emitImage(src: String, node: Element? = null) {
        val openBox = node?.let { beginPublisherBox(it) }
        try {
            emitImageContent(src, node)
        } finally {
            endPublisherBox(openBox)
        }
    }

    private fun emitImageContent(src: String, node: Element?) {
        if (node?.let { css?.computed(it)?.hidden } == true) return
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
        val darkStyle = node?.let { css?.darkImageStyle(it) }
        out += ContentElement.Image(
            path = path,
            widthFrac = computed?.widthFrac,
            widthEm = computed?.widthEm,
            heightEm = computed?.heightEm,
            maxHeightEm = computed?.maxHeightEm,
            maxHeightViewportFrac = computed?.maxHeightViewportFrac,
            maxWidthEm = computed?.maxWidthEm,
            maxWidthFrac = computed?.maxWidthFrac,
            darkModeInvert = darkStyle?.imageInvert == true,
            darkModeBackgroundArgb = darkStyle?.backgroundColorArgb,
            darkModeBackgroundCurrentColor = darkStyle?.backgroundUsesCurrentColor == true,
            altText = alt,
            spaceBeforeSpecified = computed?.marginTopSpecified == true,
            spaceAfterSpecified = computed?.marginBottomSpecified == true,
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

    private class ExplicitFloatedInitial(
        val element: Element,
        val style: FirstLetterStyle,
    )

    /**
     * Finds the Kindle-compatible drop-cap pattern where the first visible
     * node is a short floated text span. The span text remains at paragraph
     * offset zero; only its inline presentation is suppressed and moved into
     * [FirstLetterStyle], allowing the renderer's drop-cap toggles to work.
     */
    private fun explicitFloatedInitial(paragraph: Element): ExplicitFloatedInitial? {
        val resolver = css ?: return null
        val pre = preindexOf(paragraph)
        if (pre != null && treePreindexValid() &&
            !pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE)
        ) {
            return null
        }
        // Generated content would precede the span in the flattened string,
        // so the floated glyph would no longer start at offset zero.
        if (resolver.generated(paragraph, after = false) != null) return null

        var candidate: Element? = null
        val childCount = paragraph.childNodeSize()
        for (i in 0 until childCount) {
            when (val node = paragraph.childNode(i)) {
                is TextNode -> if (!isTextBlank(node)) return null
                is Element -> {
                    if (!resolver.computed(node).hidden) {
                        candidate = node
                        break
                    }
                }
            }
        }
        if (candidate == null) return null
        val isInitialTag = preindexOf(candidate)?.hasTag(RustChapterMarkup.TAG_EXPLICIT_INITIAL)
            ?: (candidate.normalName() in EXPLICIT_INITIAL_TAGS)
        if (!isInitialTag) return null
        val candCount = candidate.childNodeSize()
        for (i in 0 until candCount) {
            if (candidate.childNode(i) !is TextNode) return null
        }
        if (resolver.generated(candidate, after = false) != null ||
            resolver.generated(candidate, after = true) != null
        ) {
            return null
        }

        val raw = when (candCount) {
            0 -> ""
            1 -> (candidate.childNode(0) as TextNode).wholeText
            else -> buildString {
                for (i in 0 until candCount) {
                    append((candidate.childNode(i) as TextNode).wholeText)
                }
            }
        }
        val visible = raw.replace(INLINE_WHITESPACE, " ").trim()
        if (visible.isEmpty() || ' ' in visible) return null
        val codePoints = visible.codePointCount(0, visible.length)
        if (codePoints !in 1..3 || visible.codePoints().noneMatch(Character::isLetter)) return null

        val style = resolver.floatedTextInitial(candidate, paragraph, visible.length)
            ?.let { resolved ->
                resolved.copy(
                    language = languageFor(candidate),
                    direction = resolved.direction ?: attributeDirection(candidate),
                )
            }
            ?: return null
        return ExplicitFloatedInitial(candidate, style)
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
        if (css?.computed(source)?.hidden == true) return
        resolveSvg(source)?.let {
            val computed = css?.computed(source)
            val darkStyle = css?.darkImageStyle(source)
            out += ContentElement.Image(
                path = it,
                widthFrac = computed?.widthFrac,
                widthEm = computed?.widthEm,
                heightEm = computed?.heightEm,
                maxWidthFrac = computed?.maxWidthFrac,
                maxWidthEm = computed?.maxWidthEm,
                maxHeightEm = computed?.maxHeightEm,
                maxHeightViewportFrac = computed?.maxHeightViewportFrac,
                darkModeInvert = darkStyle?.imageInvert == true,
                darkModeBackgroundArgb = darkStyle?.backgroundColorArgb,
                darkModeBackgroundCurrentColor = darkStyle?.backgroundUsesCurrentColor == true,
                altText = source.attr("aria-label").ifBlank {
                    source.selectFirst("title")?.text().orEmpty()
                }.trim().takeIf(String::isNotEmpty),
                spaceBeforeSpecified = computed?.marginTopSpecified == true,
                spaceAfterSpecified = computed?.marginBottomSpecified == true,
            )
        }
    }

    private fun resolveSvg(source: Element): String? {
        if (css?.computed(source)?.hidden == true) return null
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
     * Text/paragraph presentation of one semantic leaf. CSS box geometry is
     * retained separately in [publisherBoxes], otherwise wrapper margins,
     * padding and backgrounds would be repeated on every flattened child.
     */
    private fun blockStyleFor(element: Element, withFirstLetter: Boolean = false): BlockStyle? {
        val resolver = css
        if (resolver == null) {
            val style = BlockStyle(
                align = attributeAlign(element),
                language = languageFor(element),
                direction = attributeDirection(element),
                foregroundColorArgb = legacyForeground(element),
                // Without a resolver there are no PublisherBoxSpans, so keep
                // legacy bgcolor wrapper propagation as the compatibility
                // fallback for old HTML/MOBI sources.
                backgroundColorArgb = legacyVisualBackground(element),
            )
            return if (style.isDefault) null else style
        }
        val computed = resolver.computed(element)

        var pageBreak = computed.pageBreakBefore

        // A wrapper's forced break still belongs to its first semantic leaf;
        // unlike box geometry, it cannot be painted from a leaf range alone.
        if (!pageBreak) {
            val doc = preindexDoc
            val skipAncestorPageBreakWalk = !resolver.hasPageBreakBeforeRules &&
                treePreindexValid() &&
                attrValid(RustChapterMarkup.MUTATED_STYLE) &&
                doc != null &&
                !doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_STYLE)
            if (!skipAncestorPageBreakWalk) {
                var child: Element = element
                var ancestor = element.parent()
                while (ancestor != null && !isRootTag(ancestor)) {
                    val box = resolver.computed(ancestor)
                    if (box.pageBreakBefore && firstBlockChild(ancestor) === child) {
                        pageBreak = true
                        break
                    }
                    child = ancestor
                    ancestor = ancestor.parent()
                }
            }
        }

        // The full alignment is stored; whether START/JUSTIFY of body text
        // actually wins over the user's setting is decided at render time
        // (the "publisher's formatting" toggle).
        val align = when {
            computed.textAlign == "center" -> BlockAlign.CENTER
            computed.textAlign == "end" -> BlockAlign.END
            computed.textAlign == "start" -> BlockAlign.START
            computed.textAlign == "right" -> BlockAlign.RIGHT
            computed.textAlign == "left" -> BlockAlign.LEFT
            computed.textAlign == "justify" -> BlockAlign.JUSTIFY
            else -> attributeAlign(element)
        }

        val computedFontScale = computed.fontSizeEm.coerceIn(0.6f, 2.6f)
        val style = BlockStyle(
            align = align,
            italic = computed.italic,
            bold = computed.bold,
            fontWeight = computed.fontWeight,
            // A 1em paragraph is visually identical to the reader base and
            // need not allocate a non-default BlockStyle. A heading is
            // different: explicit 1em must override its semantic H1-H6 size.
            fontScale = computedFontScale.takeIf {
                it !in 0.999f..1.001f ||
                    (preindexOf(element)?.hasTag(RustChapterMarkup.TAG_HEADING)
                        ?: (element.normalName() in HEADING_TAGS))
            },
            firstLineIndent = computed.textIndentEm?.let { it > 0.05f },
            firstLineIndentEm = computed.textIndentEm,
            fontFamily = computed.fontFamilyName,
            fontFeatureSettings = computed.fontFeatureSettings,
            fontTypography = computed.fontTypography.takeUnless { it.isEmpty },
            lineHeightMult = computed.lineHeightMult,
            hyphens = computed.hyphensAuto,
            // The values themselves stay on PublisherBoxSpan. These bits let
            // ReaderMetrics replace, rather than add to, native block gaps.
            spaceBeforeSpecified = computed.marginTopSpecified,
            spaceAfterSpecified = computed.marginBottomSpecified,
            pageBreakBefore = pageBreak,
            firstLetter = if (withFirstLetter) resolver.firstLetter(element) else null,
            language = languageFor(element),
            direction = when (computed.direction) {
                "ltr" -> BookTextDirection.LTR
                "rtl" -> BookTextDirection.RTL
                "auto" -> BookTextDirection.AUTO
                else -> attributeDirection(element)
            },
            foregroundColorArgb = computed.foregroundColorArgb,
            // Ancestor backgrounds are painted once by PublisherBoxSpan.
            // Copying them into every flattened child produced the striped
            // green/grey boxes seen in complex textbooks.
            backgroundColorArgb = computed.backgroundColorArgb,
        )
        return if (style.isDefault) null else style
    }

    /** A generated run appended as styled inline text. */
    private fun appendGeneratedRun(builder: HtmlInlineBuilder, run: CssResolver.GeneratedRun?) {
        if (run == null) return
        val family = run.fontFamilyName
        if (family != null) builder.pushAnnotation(INLINE_FONT_FAMILY_TAG, family)
        builder.pushAnnotation(INLINE_FONT_TYPOGRAPHY_TAG, run.fontTypography.encode())
        if (run.visibilityHidden) builder.pushAnnotation(HIDDEN_TEXT_TAG, "true")
        val style = generatedSpanStyle(run)
        if (style != null) builder.pushStyle(style)
        builder.text(transformText(run.text, run.textTransform))
        if (style != null) builder.pop()
        if (run.visibilityHidden) builder.pop()
        builder.pop()
        if (family != null) builder.pop()
    }

    private fun generatedSpanStyle(run: CssResolver.GeneratedRun): SpanStyle? {
        var style = SpanStyle()
        var any = false
        run.fontFeatureSettings?.let {
            style = style.copy(fontFeatureSettings = it)
            any = true
        }
        if (run.italic != null) {
            style = style.copy(fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal)
            any = true
        }
        if (run.fontWeight != null || run.bold != null) {
            style = style.copy(fontWeight = run.fontWeight?.let(::FontWeight)
                ?: if (run.bold == true) FontWeight.Bold else FontWeight.Normal)
            any = true
        }
        if (run.scale !in 0.99f..1.01f) {
            style = style.copy(fontSize = TextUnit(run.scale, TextUnitType.Em))
            any = true
        }
        run.foregroundColorArgb?.let {
            style = style.copy(color = Color(it))
            any = true
        }
        run.backgroundColorArgb?.takeIf { (it ushr 24) != 0 }?.let {
            style = style.copy(background = Color(it))
            any = true
        }
        return if (any) style else null
    }

    /** `div::before`/`::after` decorations become their own paragraphs. */
    private fun emitGeneratedParagraph(element: Element, after: Boolean, quote: Boolean) {
        val run = generated(element, after) ?: return
        val builder = HtmlInlineBuilder()
        appendGeneratedRun(builder, run)
        val generatedBox = run.publisherBox
        if (builder.isBlank && generatedBox == null) return
        val start = out.size
        if (builder.isBlank) {
            // Empty content can still draw a publisher separator; the zero
            // height leaf gives its border/margins a retained range without
            // inventing visible text or a full default paragraph gap.
            out += ContentElement.Spacer(generatedBox?.minHeightEm ?: 0f)
        } else {
            out += ContentElement.Paragraph(
                builder.build(),
                if (quote) ParagraphStyle.QUOTE else ParagraphStyle.NORMAL,
                looseBlockTextStyle(element).copy(firstLineIndent = false),
            )
        }
        if (generatedBox != null) {
            publisherBoxes += PublisherBoxSpan(
                id = "$publisherBoxIdPrefix:generated-box-${publisherBoxSequence++}",
                parentId = publisherBoxStack.lastOrNull(),
                startElement = start,
                endElementExclusive = out.size,
                style = generatedBox,
            )
        }
    }

    /** Returns a generated run only when the shared book budget accepts it. */
    private fun generated(element: Element, after: Boolean): CssResolver.GeneratedRun? {
        val run = css?.generated(element, after) ?: return null
        // An explicit empty string still suppresses default q punctuation
        // and can paint a border. It consumes no generated-text budget.
        return run.takeIf { it.text.isEmpty() || expansionBudget.acceptGenerated(it.text.length) }
    }

    private fun firstBlockChild(container: Element): Element? {
        var child = container.firstElementChild()
        while (child != null) {
            if (!isNonBlockTag(child)) return child
            child = child.nextElementSibling()
        }
        return null
    }

    /** Heading level for `title`/`titleN` CSS classes, or null. */
    private fun titleClassLevel(element: Element): Int? {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_OTHER)) {
            val level = pre.titleClassLevel
            if (level == 0) return null
            if (level in 1..6) return level
        }
        for (name in element.classNames()) {
            val match = TITLE_CLASS.matchEntire(name) ?: continue
            val digit = match.groupValues[1]
            return if (digit.isEmpty()) 1 else (digit.toInt() + 1).coerceAtMost(6)
        }
        return null
    }

    /** Hierarchical level of a DTBook `levelhd`/`hd`/`bridgehead`. */
    private fun dtbookHeadingLevel(element: Element): Int {
        var nestedGenericLevels = 0
        var parent = element.parent()
        while (parent != null) {
            val name = parent.normalName()
            if (name == "level") {
                nestedGenericLevels++
            } else if (name.length == 6 && name.startsWith("level")) {
                val explicit = name.last().digitToIntOrNull()
                if (explicit != null) {
                    return (explicit + nestedGenericLevels).coerceIn(1, 6)
                }
            }
            parent = parent.parent()
        }
        return nestedGenericLevels.coerceIn(1, 6)
    }

    /** Blocks that exist only to add vertical space (`<div class="empty-line"/>`). */
    private fun isEmptyLineBlock(element: Element): Boolean {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_OTHER)) {
            if (!pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_EMPTY_LINE_CLASS)) return false
            if (treePreindexValid()) {
                return !pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_JAVA_NON_WS_TEXT)
            }
            return element.text().isBlank()
        }
        return element.classNames().any { it.equals("empty-line", ignoreCase = true) } &&
            element.text().isBlank()
    }

    // -------------------------------------------------------------- inline

    /** Fixed-width author initials/ornaments remain inside their source line. */
    private fun appendInlineTextBox(
        element: Element,
        own: CssResolver.Computed,
        builder: HtmlInlineBuilder,
        preserveWhitespace: Boolean,
        depth: Int,
        parentComputed: CssResolver.Computed? = null,
    ): Boolean {
        val resolver = css ?: return false
        val width = own.widthEm ?: return false
        if (own.display != "inline-block" || !width.isFinite() || width <= 0f ||
            own.superScript || own.subScript || own.floatSide != null || own.paddingSpecified ||
            own.borderSpecified || own.heightEm != null || own.maxWidthEm != null || own.maxWidthFrac != null ||
            own.backgroundColorArgb?.let { it ushr 24 != 0 } == true ||
            own.marginTopEm != 0f || own.marginBottomEm != 0f ||
            own.marginStartEm != 0f || own.marginEndEm != 0f ||
            own.marginStartFrac != 0f || own.marginEndFrac != 0f ||
            own.marginInlineStartEm != 0f || own.marginInlineEndEm != 0f ||
            own.marginInlineStartFrac != 0f || own.marginInlineEndFrac != 0f
        ) return false
        val isSimpleBoxTag = preindexOf(element)?.hasTag(RustChapterMarkup.TAG_SIMPLE_INLINE_TEXT_BOX)
            ?: (element.normalName() in SIMPLE_INLINE_TEXT_BOX_TAGS)
        if (!isSimpleBoxTag) return false
        val childCount = element.childNodeSize()
        for (i in 0 until childCount) {
            if (element.childNode(i) !is TextNode) return false
        }
        if (resolver.generated(element, after = false) != null ||
            resolver.generated(element, after = true) != null
        ) return false
        val raw = element.wholeText()
        if ('\n' in raw || raw.codePointCount(0, raw.length) !in 1..2) return false
        var ancestor = element.parent()
        var container: Element? = null
        while (ancestor != null) {
            val isBlockTag = preindexOf(ancestor)?.hasTag(RustChapterMarkup.TAG_INLINE_TEXT_BLOCK)
                ?: (ancestor.normalName() in INLINE_TEXT_BLOCK_TAGS)
            if (isBlockTag || isCssFlowBlock(ancestor)) {
                container = ancestor
                break
            }
            ancestor = ancestor.parent()
        }
        container = container ?: element.parent() ?: return false
        val widthEm = width / resolver.computed(container).fontSizeEm
        if (!widthEm.isFinite() || widthEm !in 0.05f..16f) return false
        val content = HtmlInlineBuilder()
        val style = inlineStyleFor(element, own, parentComputed)
        if (style != null) content.pushStyle(style)
        appendChildrenInline(element, content, preserveWhitespace, depth, parentComputed = own)
        if (style != null) content.pop()
        val text = content.build()
        if (text.isEmpty() || text.text.codePointCount(0, text.length) > 2) return false
        val spec = InlineTextBoxSpec(
            id = "inline-box:$publisherBoxIdPrefix:${builder.length}",
            widthEm = widthEm,
            align = own.textAlign?.takeIf { it in setOf("start", "end", "left", "right", "center") } ?: "start",
            sourceLength = text.length,
        )
        builder.pushAnnotation(INLINE_TEXT_BOX_TAG, spec.encode())
        builder.inlineAnnotated(spec.id, text)
        builder.pop()
        return true
    }

    /** Author-requested case conversion follows the text's declared language. */
    private fun transformText(
        text: String,
        transform: String?,
        element: Element? = null,
        previousCharacter: Char? = null,
    ): String {
        if (transform == null || transform == "none") return text
        val locale = element?.let(::languageFor)?.let(Locale::forLanguageTag) ?: Locale.ROOT
        return when (transform) {
            "uppercase" -> text.uppercase(locale)
            "lowercase" -> text.lowercase(locale)
            "capitalize" -> CAPITALIZABLE_LETTER.replace(text) {
                if (it.range.first == 0 && previousCharacter != null &&
                    (previousCharacter.isLetterOrDigit() || previousCharacter in "’'"))
                {
                    it.value
                } else {
                    it.value.uppercase(locale)
                }
            }
            else -> text
        }
    }

    private fun appendChildrenInline(
        element: Element,
        out: HtmlInlineBuilder,
        preserveWhitespace: Boolean = false,
        depth: Int = 0,
        suppressedStyleElement: Element? = null,
        parentComputed: CssResolver.Computed? = null,
    ) {
        val childCount = element.childNodeSize()
        if (childCount == 0) return
        val computed = parentComputed ?: css?.computed(element)
        for (i in 0 until childCount) {
            appendInline(
                element.childNode(i),
                out,
                preserveWhitespace,
                depth + 1,
                suppressedStyleElement,
                parentComputed = computed,
            )
        }
    }

    /** Native ruby keeps the base in source coordinates and retains rich rt. */
    private fun appendRuby(
        ruby: Element,
        out: HtmlInlineBuilder,
        preserveWhitespace: Boolean,
        depth: Int,
        suppressedStyleElement: Element?,
        rubyComputed: CssResolver.Computed? = null,
    ) {
        val parentStyle = rubyComputed ?: css?.computed(ruby)
        var base = HtmlInlineBuilder()
        val childCount = ruby.childNodeSize()
        for (i in 0 until childCount) {
            val child = ruby.childNode(i)
            if (child is Element && child.normalName() == "rp") continue
            if (child !is Element || child.normalName() != "rt") {
                appendInline(
                    child,
                    base,
                    preserveWhitespace,
                    depth + 1,
                    suppressedStyleElement,
                    parentComputed = parentStyle,
                )
                continue
            }
            val childComputed = css?.computed(child)
            if (childComputed?.hidden == true) {
                out.appendAnnotated(base.build())
                base = HtmlInlineBuilder()
                continue
            }
            val baseText = base.build().trimmed()
            val reading = HtmlInlineBuilder()
            val style = inlineStyleFor(child, childComputed, parentStyle) ?: if (css == null) {
                RUBY_NO_CSS_RT_SPAN_STYLE
            } else null
            if (style != null) reading.pushStyle(style)
            appendChildrenInline(
                child,
                reading,
                preserveWhitespace,
                depth + 1,
                suppressedStyleElement,
                parentComputed = childComputed,
            )
            if (style != null) reading.pop()
            val readingText = reading.build().trimmed()
            val mark = if ('\n' !in baseText.text &&
                baseText.getStringAnnotations(INLINE_IMAGE_TAG, 0, baseText.length).isEmpty()) {
                InlineRuby.from("ruby:${out.length}", baseText.length, readingText)
            } else null
            if (mark == null) {
                // Malformed or unsupported complex ruby remains readable.
                out.appendAnnotated(baseText)
                if (readingText.isNotEmpty()) {
                    out.pushStyle(RT_SPAN_STYLE)
                    out.appendAnnotated(readingText)
                    out.pop()
                }
            } else {
                val fallback = AnnotatedString.Builder().apply {
                    append(baseText)
                    pushStyle(RT_SPAN_STYLE)
                    append(readingText)
                    pop()
                }.toAnnotatedString()
                out.pushAnnotation(INLINE_RUBY_TAG, mark.encode())
                out.inlineAnnotated(mark.id, fallback)
                out.pop()
            }
            base = HtmlInlineBuilder()
        }
        out.appendAnnotated(base.build())
    }

    /** Trims formatting-source whitespace without discarding span/link ranges. */
    private fun AnnotatedString.trimmed(): AnnotatedString {
        var start = 0
        var end = length
        while (start < end && isHtmlWhitespace(text[start])) start++
        while (end > start && isHtmlWhitespace(text[end - 1])) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }

    private fun appendTextContent(
        node: TextNode,
        out: HtmlInlineBuilder,
        preserveWhitespace: Boolean,
        textTransform: String? = null,
        parent: Element? = null,
    ) {
        val raw = node.wholeText
        val textFlags = (node as? RustChapterMarkup.PreindexedTextNode)?.textFlags
            ?: RustChapterMarkup.TEXT_UNKNOWN
        val noTransform = textTransform == null || textTransform == "none"
        if (textFlags != RustChapterMarkup.TEXT_UNKNOWN) {
            if (preserveWhitespace) {
                val text = if (noTransform || (textFlags and RustChapterMarkup.TEXT_HTML_BLANK) != 0) {
                    raw
                } else {
                    transformText(raw, textTransform, parent, out.lastCharacter)
                }
                if (text === raw) {
                    out.preindexedPreformattedText(raw, textFlags)
                } else {
                    out.preformattedText(text)
                }
            } else if (noTransform || (textFlags and RustChapterMarkup.TEXT_HTML_BLANK) != 0) {
                out.preindexedText(raw, textFlags)
            } else {
                val text = transformText(raw, textTransform, parent, out.lastCharacter)
                if (text === raw) {
                    out.preindexedText(raw, textFlags)
                } else {
                    out.text(text)
                }
            }
        } else {
            val text = if (noTransform) {
                raw
            } else {
                transformText(raw, textTransform, parent, out.lastCharacter)
            }
            if (preserveWhitespace) out.preformattedText(text) else out.text(text)
        }
    }

    private fun appendInlineMath(node: Element, out: HtmlInlineBuilder) {
        val mathBuilder = InlineTextBuilder()
        val seedChar = out.lastCharacter
        val seeded = when {
            seedChar == null -> false
            out.lastWasSpace && (seedChar == ' ' || seedChar == '\n') -> false
            out.lastWasSpace -> {
                mathBuilder.preformattedText(seedChar.toString())
                true
            }
            else -> {
                mathBuilder.text("\uFFFF")
                true
            }
        }
        mathFormatter.append(node, mathBuilder)
        val built = mathBuilder.build()
        if (seeded && built.length == 1) {
            for (annotation in built.getStringAnnotations(1, 1)) {
                out.pushAnnotation(annotation.tag, annotation.item)
                out.pop()
            }
            return
        }
        val result = if (seeded) built.subSequence(1, built.length) else built
        out.appendAnnotated(result, keepEmptyAnnotations = true)
    }

    private fun appendInline(
        node: Node,
        out: HtmlInlineBuilder,
        preserveWhitespace: Boolean = false,
        depth: Int = 0,
        suppressedStyleElement: Element? = null,
        parentComputed: CssResolver.Computed? = null,
    ) {
        when (node) {
            is TextNode -> {
                val parent = node.parent()
                val computed = parentComputed ?: parent?.let { css?.computed(it) }
                val hidden = computed?.visibilityHidden == true
                // Code/pre defaults set monospace independently of an
                // inherited body's family name. An explicit CSS family on
                // the code resets computed.monospace and still takes effect.
                val family = if (computed?.monospace == true) "monospace" else computed?.fontFamilyName
                if (family != null) out.pushAnnotation(INLINE_FONT_FAMILY_TAG, family)
                if (computed != null) out.pushAnnotation(INLINE_FONT_TYPOGRAPHY_TAG, computed.encodedFontTypography)
                if (hidden) out.pushAnnotation(HIDDEN_TEXT_TAG, "true")
                appendTextContent(node, out, preserveWhitespace, computed?.textTransform, parent)
                if (hidden) out.pop()
                if (computed != null) out.pop()
                if (family != null) out.pop()
            }

            is Element -> {
                val computed = css?.computed(node)
                val bidiMode = inlineBidiMode(node, computed)
                if (bidiMode != null) out.pushAnnotation(BIDI_TAG, bidiMode.name)
                try {
                    if (isNonTextContentTag(node)) return
                    if (depth >= maxStructureDepth) {
                        val childCount = node.childNodeSize()
                        for (i in 0 until childCount) {
                            val child = node.childNode(i)
                            if (child is TextNode) {
                                appendTextContent(child, out, preserveWhitespace)
                            }
                        }
                        return
                    }
                    if (computed?.hidden == true) return
                    if (computed?.widthEm != null &&
                        appendInlineTextBox(node, computed, out, preserveWhitespace, depth, parentComputed)
                    ) {
                        return
                    }
                    if (isMathElement(node)) {
                        registerMathAnchors(node)
                        appendInlineMath(node, out)
                        return
                    }
                    val scopedStyle = if (node === suppressedStyleElement) {
                        null
                    } else {
                        inlineStyleFor(node, computed, parentComputed)
                    }
                    if (scopedStyle != null) out.pushStyle(scopedStyle)
                    try {
                    when (val tagName = node.normalName()) {
                    "br" -> out.lineBreak()
                    "wbr" -> out.wordBreakOpportunity()
                    "img", "image" -> {
                        if (!isBlockLikeMedia(node)) {
                            val src = if (tagName == "img") {
                                srcOf(node)
                            } else {
                                xlinkHrefOf(node).ifEmpty { hrefOf(node) }
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

                    "ruby" -> appendRuby(
                        node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                    )
                    "rp" -> Unit
                    "rt" -> {
                        out.pushStyle(RT_SPAN_STYLE)
                        appendChildrenInline(
                            node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                        )
                        out.pop()
                    }
                    "q" -> {
                        val generatedBefore = generated(node, after = false)
                        val generatedAfter = generated(node, after = true)
                        val (open, close) = quoteMarksFor(node)
                        if (generatedBefore != null) {
                            appendGeneratedRun(out, generatedBefore)
                        } else {
                            out.text(open)
                        }
                        appendChildrenInline(
                            node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                        )
                        if (generatedAfter != null) {
                            appendGeneratedRun(out, generatedAfter)
                        } else {
                            out.text(close)
                        }
                    }
                    "mark" -> {
                        out.pushStyle(MARK_SPAN_STYLE)
                        appendGeneratedRun(out, generated(node, after = false))
                        appendChildrenInline(
                            node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                        )
                        appendGeneratedRun(out, generated(node, after = true))
                        out.pop()
                    }
                    "a" -> {
                        fun appendLinkContent() {
                            appendGeneratedRun(out, generated(node, after = false))
                            appendChildrenInline(
                                node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                            )
                            appendGeneratedRun(out, generated(node, after = true))
                        }
                        val href = hrefOf(node).trim()
                        val key = href.takeIf { it.isNotEmpty() }?.let(resolveLink)
                        if (key != null) {
                            linkTargets += key
                            val note = isNoteReference(node, key)
                            if (note) noteTargets += key
                            out.pushAnnotation(
                                if (note) FOOTNOTE_TAG else LINK_TAG,
                                key,
                            )
                            appendLinkContent()
                            out.pop()
                        } else if (safeExternalHref(href)) {
                            out.pushAnnotation(EXTERNAL_LINK_TAG, href)
                            appendLinkContent()
                            out.pop()
                        } else {
                            appendLinkContent()
                        }
                    }

                    else -> {
                        appendGeneratedRun(out, generated(node, after = false))
                        appendChildrenInline(
                            node, out, preserveWhitespace, depth, suppressedStyleElement, computed,
                        )
                        appendGeneratedRun(out, generated(node, after = true))
                    }
                    }
                    } finally {
                        if (scopedStyle != null) out.pop()
                    }
                } finally {
                    if (bidiMode != null) out.pop()
                }
            }
        }
    }

    /**
     * Span style of an inline element: with CSS, the difference between the
     * element's computed style and its parent's (so inherited properties are
     * not re-applied); without CSS, classic tag semantics.
     */
    private fun inlineStyleFor(element: Element): SpanStyle? =
        inlineStyleFor(element, ownComputed = null, parentComputed = null)

    private fun inlineStyleFor(
        element: Element,
        ownComputed: CssResolver.Computed?,
        parentComputed: CssResolver.Computed?,
    ): SpanStyle? {
        val resolver = css
            ?: return mergeSpanStyles(
                mergeSpanStyles(tagStyleFor(element.normalName()), legacyInlineStyle(element)),
                inlineLanguageStyle(element),
            )
        val parent = element.parent() ?: return tagStyleFor(element.normalName())
        val own = ownComputed ?: resolver.computed(element)
        val base = parentComputed ?: resolver.computed(parent)

        // Structural code keeps its semantic face even when publisher CSS is
        // disabled. CSS family choices stay as annotations so the reader can
        // apply or ignore them with the current setting.
        val isMonospace = preindexOf(element)?.hasTag(RustChapterMarkup.TAG_SEMANTIC_MONOSPACE)
            ?: (element.normalName() in SEMANTIC_MONOSPACE_TAGS)
        val bgArgb = own.backgroundColorArgb?.takeIf { (it ushr 24) != 0 }

        if (own === base) {
            if (!own.superScript && !own.subScript && !isMonospace && bgArgb == null) {
                val localeList = inlineLanguageLocaleList(element) ?: return null
                return SpanStyle(localeList = localeList)
            }
            val baselineShift = when {
                own.superScript -> BaselineShift.Superscript
                own.subScript -> BaselineShift.Subscript
                else -> null
            }
            val fontSize = if (baselineShift != null) SUB_SUPER_FONT_SIZE else TextUnit.Unspecified
            return SpanStyle(
                fontSize = fontSize,
                fontFamily = if (isMonospace) FontFamily.Monospace else null,
                baselineShift = baselineShift,
                localeList = inlineLanguageLocaleList(element),
                background = if (bgArgb != null) Color(bgArgb) else Color.Unspecified,
            )
        }

        var fontFeatureSettings: String? = null
        var fontStyle: FontStyle? = null
        var fontWeight: FontWeight? = null
        var fontSize: TextUnit = TextUnit.Unspecified
        var baselineShift: BaselineShift? = null
        var needUnderline = false
        var needStrike = false
        var fontFamily: FontFamily? = null
        var color: Color = Color.Unspecified
        var background: Color = Color.Unspecified
        var any = false

        if (own.fontFeatureSettings != base.fontFeatureSettings) {
            fontFeatureSettings = own.fontFeatureSettings ?: ""
            any = true
        }
        if (own.italic != null && own.italic != base.italic) {
            fontStyle = if (own.italic) FontStyle.Italic else FontStyle.Normal
            any = true
        }
        if (own.fontWeight != null && own.fontWeight != base.fontWeight) {
            fontWeight = FontWeight(own.fontWeight)
            any = true
        }
        val sizeRatio = own.fontSizeEm / base.fontSizeEm
        if (sizeRatio !in 0.99f..1.01f) {
            fontSize = TextUnit(sizeRatio.coerceIn(0.5f, 2f), TextUnitType.Em)
            any = true
        }
        if (own.superScript) {
            baselineShift = BaselineShift.Superscript
            if (fontSize == TextUnit.Unspecified) {
                fontSize = SUB_SUPER_FONT_SIZE
            }
            any = true
        } else if (own.subScript) {
            baselineShift = BaselineShift.Subscript
            if (fontSize == TextUnit.Unspecified) {
                fontSize = SUB_SUPER_FONT_SIZE
            }
            any = true
        }
        if (own.underline && !base.underline) {
            needUnderline = true
            any = true
        }
        if (own.strike && !base.strike) {
            needStrike = true
            any = true
        }
        if (isMonospace) {
            fontFamily = FontFamily.Monospace
            any = true
        }
        if (own.foregroundColorArgb != base.foregroundColorArgb) {
            own.foregroundColorArgb?.let {
                color = Color(it)
                any = true
            }
        }
        if (bgArgb != null) {
            background = Color(bgArgb)
            any = true
        }
        val localeList = inlineLanguageLocaleList(element)
        if (!any && localeList == null) return null
        val textDecoration = when {
            needUnderline && needStrike -> COMBINED_UNDERLINE_LINE_THROUGH
            needUnderline -> TextDecoration.Underline
            needStrike -> TextDecoration.LineThrough
            else -> null
        }
        return SpanStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontFamily = fontFamily,
            fontFeatureSettings = fontFeatureSettings,
            baselineShift = baselineShift,
            localeList = localeList,
            background = background,
            textDecoration = textDecoration,
        )
    }


    /** Presentational HTML attributes used heavily by MOBI6. */
    private fun legacyInlineStyle(element: Element): SpanStyle? {
        val doc = preindexDoc
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_PRESENTATIONAL)
        if (doc != null && treePreindexValid() && canCheckAttr &&
            !doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL)
        ) {
            return null
        }
        val pre = preindexOf(element)
        if (pre != null && canCheckAttr && !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL)) {
            return null
        }
        val colorArgb = element.attr("color").takeIf { it.isNotBlank() }?.let(CssColor::parse)
        val bgArgb = element.attr("bgcolor").takeIf { it.isNotBlank() }?.let(CssColor::parse)
            ?.takeIf { (it ushr 24) != 0 }
        if (colorArgb == null && bgArgb == null) return null
        return SpanStyle(
            color = if (colorArgb != null) Color(colorArgb) else Color.Unspecified,
            background = if (bgArgb != null) Color(bgArgb) else Color.Unspecified,
        )
    }

    private fun legacyForeground(element: Element): Int? {
        val doc = preindexDoc
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_PRESENTATIONAL)
        if (doc != null && treePreindexValid() && canCheckAttr &&
            !doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL)
        ) {
            return null
        }
        var node: Element? = element
        while (node != null && node.normalName() != "#root") {
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr && !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL)) {
                node = node.parent()
                continue
            }
            val raw = node.attr("color").ifBlank {
                node.attr("text").takeIf { node.normalName() == "body" }.orEmpty()
            }
            if (raw.isNotBlank()) CssColor.parse(raw)?.let { return it }
            node = node.parent()
        }
        return null
    }

    private fun legacyVisualBackground(
        element: Element,
        stopExclusive: Element? = null,
    ): Int? {
        val doc = preindexDoc
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_PRESENTATIONAL)
        if (doc != null && treePreindexValid() && canCheckAttr &&
            !doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL)
        ) {
            return null
        }
        val layers = ArrayList<Int>(3)
        var node: Element? = element
        while (node != null && node !== stopExclusive && node.normalName() != "#root") {
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr && !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL)) {
                node = node.parent()
                continue
            }
            node.attr("bgcolor").takeIf { it.isNotBlank() }?.let(CssColor::parse)?.let {
                layers += it
            }
            node = node.parent()
        }
        var result = 0
        for (index in layers.lastIndex downTo 0) {
            result = compositeArgb(layers[index], result)
        }
        return result.takeIf { (it ushr 24) != 0 }
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

    private fun mergeSpanStyles(first: SpanStyle?, second: SpanStyle?): SpanStyle? = when {
        first == null -> second
        second == null -> first
        else -> first.merge(second)
    }

    private fun inlineLanguageLocaleList(element: Element): LocaleList? {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_OTHER) &&
            !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_LANG)
        ) {
            return null
        }
        val own = languageFor(element) ?: return null
        val inherited = element.parent()?.let(::languageFor)
        return own.takeIf { it != inherited }?.let(::LocaleList)
    }

    private fun inlineLanguageStyle(element: Element): SpanStyle? =
        inlineLanguageLocaleList(element)?.let { SpanStyle(localeList = it) }

    private fun languageFor(element: Element): String? {
        val canCheckTree = treePreindexValid()
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_OTHER)
        var node: Element? = element
        while (node != null) {
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr) {
                if (canCheckTree && !pre.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_LANG)) {
                    return null
                }
                if (!pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_LANG)) {
                    node = node.parent()
                    continue
                }
            }
            val raw = node.attr("lang").ifBlank { node.attr("xml:lang") }.trim()
            if (raw.isNotEmpty()) return LanguageTag.normalize(raw) ?: raw.lowercase()
            node = node.parent()
        }
        return null
    }

    private fun attributeDirection(element: Element): BookTextDirection? {
        val canCheckTree = treePreindexValid()
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_OTHER)
        var node: Element? = element
        while (node != null) {
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr) {
                if (canCheckTree && !pre.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_DIR)) {
                    return null
                }
                if (!pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_DIR)) {
                    node = node.parent()
                    continue
                }
            }
            when (node.attr("dir").lowercase()) {
                "ltr" -> return BookTextDirection.LTR
                "rtl" -> return BookTextDirection.RTL
                "auto" -> return BookTextDirection.AUTO
            }
            node = node.parent()
        }
        return null
    }

    /**
     * Inline bidi semantics cannot be represented by a Compose SpanStyle.
     * Retain a clean source-text range; [BidiLayoutText] later introduces the
     * corresponding UBA scope only in its temporary measured/rendered copy.
     */
    private fun inlineBidiMode(
        element: Element,
        computed: CssResolver.Computed? = css?.computed(element),
    ): InlineBidiMode? {
        val computedBidi = computed?.unicodeBidi
        if (computedBidi == "normal") return null
        val pre = preindexOf(element)
        val canSkipDir = pre != null && attrValid(RustChapterMarkup.MUTATED_OTHER) &&
            !pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_DIR)
        val tagName = element.normalName()
        if (computedBidi == null && canSkipDir && tagName != "bdi" && tagName != "bdo") {
            return null
        }
        val rawDir = if (canSkipDir) {
            ""
        } else {
            val attr = element.attr("dir")
            if (attr.isEmpty()) "" else attr.trim().lowercase()
        }
        val unicodeBidi = when (tagName) {
            "bdi" -> computedBidi ?: "isolate"
            "bdo" -> computedBidi ?: "bidi-override"
            else -> computedBidi ?: if (rawDir.isNotEmpty()) "isolate" else "normal"
        }
        if (unicodeBidi == "normal") return null
        if (unicodeBidi == "plaintext") return InlineBidiMode.PLAINTEXT
        if (unicodeBidi != "isolate" &&
            unicodeBidi != "embed" &&
            unicodeBidi != "bidi-override" &&
            unicodeBidi != "isolate-override"
        ) {
            return null
        }

        val direction = when {
            // `dir` is a presentational hint, so an authored CSS direction
            // has precedence. CssResolver seeds the attribute before applying
            // the cascade and [computed.direction] is therefore the final
            // value in both the CSS and CSS-less cases.
            rawDir == "ltr" || rawDir == "rtl" || rawDir == "auto" -> computed?.direction ?: rawDir
            // A bare bdi has HTML's auto direction. A direction that differs
            // from its parent must have been declared on the bdi itself.
            tagName == "bdi" -> {
                val parentDirection = element.parent()?.let { css?.computed(it)?.direction }
                computed?.direction?.takeIf { it != parentDirection } ?: "auto"
            }
            else -> computed?.direction ?: when (attributeDirection(element)) {
                BookTextDirection.LTR -> "ltr"
                BookTextDirection.RTL -> "rtl"
                BookTextDirection.AUTO -> "auto"
                null -> "auto"
            }
        }
        return when (unicodeBidi) {
            "isolate" -> when (direction) {
                "ltr" -> InlineBidiMode.ISOLATE_LTR
                "rtl" -> InlineBidiMode.ISOLATE_RTL
                else -> InlineBidiMode.ISOLATE_AUTO
            }
            "embed" -> when (direction) {
                "rtl" -> InlineBidiMode.EMBED_RTL
                "ltr" -> InlineBidiMode.EMBED_LTR
                else -> InlineBidiMode.ISOLATE_AUTO
            }
            "bidi-override" -> when (direction) {
                "rtl" -> InlineBidiMode.OVERRIDE_RTL
                else -> InlineBidiMode.OVERRIDE_LTR
            }
            "isolate-override" -> when (direction) {
                "rtl" -> InlineBidiMode.ISOLATE_OVERRIDE_RTL
                else -> InlineBidiMode.ISOLATE_OVERRIDE_LTR
            }
            else -> null
        }
    }

    private fun tagStyleFor(tag: String): SpanStyle? = when (tag) {
        "i", "em", "dfn", "var" -> TAG_STYLE_ITALIC
        "b", "strong" -> TAG_STYLE_BOLD
        "u", "ins" -> TAG_STYLE_UNDERLINE
        "s", "strike", "del" -> TAG_STYLE_LINE_THROUGH
        "code", "kbd", "samp", "tt" -> TAG_STYLE_MONOSPACE
        "sup" -> TAG_STYLE_SUP
        "sub" -> TAG_STYLE_SUB
        "small" -> TAG_STYLE_SMALL
        else -> null
    }

    /** EPUB 3 semantics plus conservative EPUB 2/Kindle legacy patterns. */
    private fun isNoteReference(link: Element, key: String): Boolean {
        if ('#' !in key) return false
        val pre = preindexOf(link)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_OTHER)) {
            if (pre.hasAttrFlag(RustChapterMarkup.ATTR_EPUB_NOTEREF)) return true
            if (pre.hasAttrFlag(RustChapterMarkup.ATTR_ROLE_DOC_NOTEREF)) return true
        } else {
            val epubTypes = link.attr("epub:type").split(NOTE_WHITESPACE_SEPARATOR)
            if (epubTypes.any { it.equals("noteref", ignoreCase = true) }) return true
            if (link.attr("role").split(NOTE_WHITESPACE_SEPARATOR).any {
                    it.equals("doc-noteref", ignoreCase = true)
                }
            ) {
                return true
            }
        }
        val isTypeNote = if (pre != null && attrValid(RustChapterMarkup.MUTATED_TYPE)) {
            pre.hasAttrFlag(RustChapterMarkup.ATTR_TYPE_IS_NOTE) || pre.promotedNote
        } else {
            link.attr("type").equals("note", ignoreCase = true)
        }
        if (isTypeNote) return true

        val hasAttrHint = if (pre != null &&
            attrValid(RustChapterMarkup.MUTATED_ID or RustChapterMarkup.MUTATED_OTHER)
        ) {
            pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_NOTE_HINT)
        } else {
            val attrHint = (link.className() + " " + link.id() + " " + link.attr("rel")).lowercase()
            NOTE_HINT.containsMatchIn(attrHint)
        }
        if (hasAttrHint) return true
        val fileHint = key.substringBefore('#').substringAfterLast('/').lowercase()
        if (NOTE_HINT.containsMatchIn(fileHint)) return true

        // EPUB 2 generators commonly used only a superscript numeric marker.
        val hasSup = if (pre != null && treePreindexValid()) {
            pre.hasTag(RustChapterMarkup.TAG_SUP) ||
                pre.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_SUP) ||
                pre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SUP)
        } else {
            link.normalName() == "sup" || link.parents().any { it.normalName() == "sup" } ||
                link.selectFirst("sup") != null
        }
        if (!hasSup) return false
        val marker = link.text().trim().trim('[', ']', '(', ')')
        return marker.isNotEmpty() && marker.all { it.isDigit() || it in "*†‡" }
    }

    private fun safeExternalHref(href: String): Boolean {
        val scheme = href.substringBefore(':', "").lowercase()
        return scheme in SAFE_EXTERNAL_SCHEMES
    }

    private fun isMathElement(element: Element): Boolean =
        preindexOf(element)?.hasTag(RustChapterMarkup.TAG_MATH)
            ?: element.normalName().substringAfterLast(':').equals("math", ignoreCase = true)

    private fun isDisplayMath(element: Element): Boolean {
        val pre = preindexOf(element)
        if (pre != null && attrValid(RustChapterMarkup.MUTATED_STYLE or RustChapterMarkup.MUTATED_OTHER)) {
            return pre.hasAttrFlag(RustChapterMarkup.ATTR_IS_DISPLAY_MATH)
        }
        if (element.attr("display").equals("block", ignoreCase = true)) return true
        return element.attr("style").split(';').any { declaration ->
            val (property, value) = declaration.split(':', limit = 2)
                .map(String::trim)
                .let { parts ->
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }
            property.equals("display", ignoreCase = true) &&
                value.substringBefore('!').trim().equals("block", ignoreCase = true)
        }
    }

    private fun registerMathAnchors(element: Element) {
        if (attrValid(RustChapterMarkup.MUTATED_ID)) {
            val handled = forEachMatchingPreindexed(element, RustChapterMarkup.SUBTREE_HAS_ID) { pre ->
                pre.anchorId?.takeIf(String::isNotEmpty)?.let(::registerAnchor)
            }
            if (handled) return
        }
        registerAnchor(anchorIdOf(element))
        element.select("[id]").forEach { registerAnchor(it.attr("id")) }
    }

    private fun quoteMarksFor(element: Element): Pair<String, String> {
        val nested = element.parents().count { it.normalName() == "q" } % 2 == 1
        val canCheckTree = treePreindexValid()
        val canCheckAttr = attrValid(RustChapterMarkup.MUTATED_OTHER)
        var language: String? = null
        var node: Element? = element
        while (node != null) {
            val pre = preindexOf(node)
            if (pre != null && canCheckAttr) {
                if (canCheckTree && !pre.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_LANG)) {
                    break
                }
                if (!pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_LANG)) {
                    node = node.parent()
                    continue
                }
            }
            val raw = node.attr("lang").ifBlank { node.attr("xml:lang") }
                .substringBefore('-').lowercase()
            if (raw.isNotEmpty()) {
                language = raw
                break
            }
            node = node.parent()
        }
        return when (language) {
            "ru", "uk", "be", "fr" -> if (nested) "„" to "“" else "«" to "»"
            "de" -> if (nested) "‚" to "‘" else "„" to "“"
            else -> if (nested) "‘" to "’" else "“" to "”"
        }
    }

    /**
     * Inline [AnnotatedString] collector matching [InlineTextBuilder] while
     * using precomputed [RustChapterMarkup.PreindexedTextNode.textFlags] to
     * skip redundant whitespace scanning on pre-collapsed nodes.
     */
    private class HtmlInlineBuilder {
        private val builder = AnnotatedString.Builder()
        var lastWasSpace = true
            private set
        private var hasVisibleContent = false
        var lastCharacter: Char? = null
            private set
        private val mutableImageRefs = mutableListOf<String>()

        val length: Int get() = builder.length
        val imageRefs: List<String> get() = mutableImageRefs
        val isBlank: Boolean get() = !hasVisibleContent

        fun text(raw: String) {
            if (raw.isEmpty()) return
            collapsedAppend(raw, RustChapterMarkup.TEXT_UNKNOWN)
        }

        fun preindexedText(raw: String, textFlags: Int) {
            if (raw.isEmpty()) return
            if ((textFlags and RustChapterMarkup.TEXT_HTML_BLANK) != 0) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastCharacter = ' '
                    lastWasSpace = true
                }
                return
            }
            if ((textFlags and RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII) != 0) {
                val start = if (
                    lastWasSpace && (textFlags and RustChapterMarkup.TEXT_STARTS_HTML_WS) != 0
                ) 1 else 0
                if (start >= raw.length) return
                if (start == 0) {
                    builder.append(raw)
                } else {
                    builder.append(raw, start, raw.length)
                }
                lastCharacter = raw[raw.length - 1]
                lastWasSpace = (textFlags and RustChapterMarkup.TEXT_ENDS_HTML_WS) != 0
                if (!hasVisibleContent &&
                    (textFlags and RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS) != 0
                ) {
                    hasVisibleContent = true
                }
                return
            }
            collapsedAppend(raw, textFlags)
        }

        private fun collapsedAppend(raw: String, textFlags: Int) {
            val len = raw.length
            var i = 0
            var inSpace = lastWasSpace
            var lastChar = '\u0000'
            var appendedAny = false
            while (i < len) {
                if (isHtmlWhitespace(raw[i])) {
                    while (i < len && isHtmlWhitespace(raw[i])) i++
                    if (!inSpace) {
                        builder.append(' ')
                        lastChar = ' '
                        inSpace = true
                        appendedAny = true
                    }
                } else {
                    val runStart = i
                    i++
                    while (i < len && !isHtmlWhitespace(raw[i])) i++
                    builder.append(raw, runStart, i)
                    lastChar = raw[i - 1]
                    inSpace = false
                    appendedAny = true
                }
            }
            if (!appendedAny) return
            lastCharacter = lastChar
            lastWasSpace = inSpace
            if (!hasVisibleContent) {
                val visible = if (textFlags != RustChapterMarkup.TEXT_UNKNOWN) {
                    (textFlags and RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS) != 0
                } else {
                    raw.isNotBlank()
                }
                if (visible) hasVisibleContent = true
            }
        }

        fun preformattedText(raw: String) {
            if (raw.isEmpty()) return
            val chunk = if ('\r' in raw) raw.replace("\r\n", "\n").replace('\r', '\n') else raw
            builder.append(chunk)
            val tail = chunk[chunk.length - 1]
            lastCharacter = tail
            lastWasSpace = tail.isWhitespace()
            if (!hasVisibleContent && chunk.any { !it.isWhitespace() }) {
                hasVisibleContent = true
            }
        }

        fun preindexedPreformattedText(raw: String, textFlags: Int) {
            if (raw.isEmpty()) return
            val chunk = if ((textFlags and RustChapterMarkup.TEXT_HAS_CR) == 0) {
                raw
            } else {
                raw.replace("\r\n", "\n").replace('\r', '\n')
            }
            builder.append(chunk)
            val tail = chunk[chunk.length - 1]
            lastCharacter = tail
            lastWasSpace = (textFlags and RustChapterMarkup.TEXT_ENDS_HTML_WS) != 0 || tail.isWhitespace()
            if (!hasVisibleContent && (textFlags and RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS) != 0) {
                hasVisibleContent = true
            }
        }

        fun appendAnnotated(text: AnnotatedString, keepEmptyAnnotations: Boolean = false) {
            if (text.isEmpty()) {
                if (keepEmptyAnnotations &&
                    (text.spanStyles.isNotEmpty() ||
                        text.paragraphStyles.isNotEmpty() ||
                        text.getStringAnnotations(0, 0).isNotEmpty())
                ) {
                    builder.append(text)
                }
                return
            }
            val images = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
            builder.append(text)
            lastCharacter = text.last()
            lastWasSpace = text.last() == ' ' || text.last() == '\n'
            mutableImageRefs += images.map { it.item }
            if (!hasVisibleContent && text.text.indices.any { offset ->
                    val char = text[offset]
                    !char.isWhitespace() && !(char == INLINE_IMAGE_CHAR.single() &&
                        images.any { offset in it.start until it.end })
                }) hasVisibleContent = true
        }

        fun inlineAnnotated(id: String, text: AnnotatedString) {
            if (text.isEmpty()) return
            val contentAnnotation = AnnotatedString.Builder().apply {
                appendInlineContent(id, text.text)
            }.toAnnotatedString().getStringAnnotations(0, text.length).single()
            appendAnnotated(AnnotatedString.Builder(text).apply {
                addStringAnnotation(contentAnnotation.tag, id, 0, text.length)
            }.toAnnotatedString())
        }

        fun lineBreak() {
            builder.append("\n")
            lastCharacter = '\n'
            lastWasSpace = true
        }

        fun wordBreakOpportunity() {
            builder.append('\u200B')
            lastCharacter = '\u200B'
            lastWasSpace = false
        }

        fun inlineImage(ref: String, altText: String? = null) {
            val alt = altText?.trim()?.takeIf { it.isNotEmpty() }
            if (alt != null) builder.pushStringAnnotation(INLINE_IMAGE_ALT_TAG, alt)
            builder.pushStringAnnotation(INLINE_IMAGE_TAG, ref)
            builder.appendInlineContent(ref, INLINE_IMAGE_CHAR)
            lastCharacter = INLINE_IMAGE_CHAR.last()
            builder.pop()
            if (alt != null) builder.pop()
            mutableImageRefs += ref
            lastWasSpace = false
        }

        fun pushStyle(style: SpanStyle) {
            builder.pushStyle(style)
        }

        fun pushAnnotation(tag: String, value: String) {
            builder.pushStringAnnotation(tag, value)
        }

        fun pop() {
            builder.pop()
        }

        fun build(): AnnotatedString = builder.toAnnotatedString()
    }

    private companion object {
        fun isHtmlWhitespace(ch: Char): Boolean =
            ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C'

        val EXPLICIT_INITIAL_TAGS = setOf("span", "font", "b", "strong", "i", "em")
        val INLINE_WHITESPACE = Regex("[\\s\\u00A0]+")
        const val DEFAULT_HTML_STRUCTURE_DEPTH = 256
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        const val BLOCK_PROBE =
            "p, div, h1, h2, h3, h4, h5, h6, blockquote, img, ul, ol, table, math[display=block]"
        val ROOT_TAGS = setOf("body", "html", "#root")
        val NON_BLOCK_TAGS = setOf(
            "span", "a", "em", "i", "b", "strong", "u", "s", "sup", "sub",
            "small", "big", "code", "br", "img", "ruby", "rt", "rp", "rb",
        )
        val NON_CONTENT_TAGS = setOf("br", "wbr", "style", "script", "head", "title", "link", "meta")
        val NON_TEXT_CONTENT_TAGS = setOf("style", "script", "head", "title", "link", "meta", "source", "track")
        val CAPITALIZABLE_LETTER = Regex("(?<![\\p{L}\\p{N}’'])[\\p{L}]")
        val CSS_BLOCK_DISPLAYS = setOf("block", "flow-root", "list-item", "flex", "grid", "table")
        val CSS_HORIZONTAL_DISPLAYS = setOf("flex", "inline-flex", "table", "inline-table")
        val CSS_ROW_EXCLUDED_TAGS = setOf("table", "tr", "td", "th")
        val SEMANTIC_MONOSPACE_TAGS = setOf("code", "kbd", "samp", "tt")
        val COMPLEX_HORIZONTAL_CELL_TAGS = setOf("table", "ul", "ol", "audio", "video", "img", "svg")
        const val MAX_CSS_ROW_CELLS = 24
        const val MAX_RESPONSIVE_TABLE_CELLS = 4_096
        const val MAX_RESPONSIVE_TABLE_DESCENDANTS = 8_192
        val SIMPLE_INLINE_TEXT_BOX_TAGS = setOf("span", "i", "em", "b", "strong", "small", "abbr", "u", "s")
        val INLINE_TEXT_BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th", "pre", "li", "dt", "dd", "line")
        val IMAGE_TAGS = setOf("img", "image")
        val PUBLISHER_BOX_TAGS = setOf(
            "p", "li", "dt", "dd", "pre",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "levelhd", "hd", "bridgehead", "doctitle", "covertitle",
            "blockquote", "cite", "poem", "epigraph",
            "ul", "ol", "list", "figcaption", "caption", "summary",
            "audio", "video",
            "div", "section", "article", "aside", "main", "header", "footer",
            "dl", "figure", "center", "nav", "details",
            "dtbook", "book", "frontmatter", "bodymatter", "rearmatter",
            "level", "level1", "level2", "level3", "level4", "level5", "level6",
            "sidebar", "note", "prodnote", "annotation", "linegroup", "imggroup",
            "svg", "hr", "table", "line", "byline", "dateline", "docauthor",
            "address", "img", "image",
        )
        val TITLE_CLASS = Regex("""(?i)title(\d*)""")
        val NESTED_LIST_TAGS = setOf("ul", "ol", "list")
        val CELL_BLOCK_TAGS = setOf("p", "div")
        val NOTE_HINT = Regex("(?:^|[-_\\s])(footnote|endnote|noteref|note)(?:$|[-_\\s])")
        val NOTE_TOKEN_SEPARATOR = Regex("[^a-z0-9_-]+")
        val NOTE_WHITESPACE_SEPARATOR = Regex("\\s+")
        val NOTE_ITEM_TAGS = setOf("li", "p", "div", "section", "aside", "note", "prodnote")
        val NOTE_CONTAINER_TOKENS = setOf(
            "footnote", "endnote", "rearnote", "doc-footnote", "doc-endnote",
        )
        val NOTE_COLLECTION_TOKENS = setOf(
            "footnotes", "endnotes", "rearnotes", "notes", "doc-endnotes",
        )
        val SAFE_EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")

        val SUB_SUPER_FONT_SIZE = TextUnit(0.75f, TextUnitType.Em)
        val COMBINED_UNDERLINE_LINE_THROUGH = TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
        )
        val MARK_SPAN_STYLE = SpanStyle(background = Color(0x66FFD54F))
        val RT_SPAN_STYLE = SpanStyle(
            fontSize = TextUnit(0.6f, TextUnitType.Em),
            baselineShift = BaselineShift(0.35f),
        )
        val RUBY_NO_CSS_RT_SPAN_STYLE = SpanStyle(fontSize = TextUnit(0.5f, TextUnitType.Em))
        val TAG_STYLE_ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
        val TAG_STYLE_BOLD = SpanStyle(fontWeight = FontWeight.Bold)
        val TAG_STYLE_UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)
        val TAG_STYLE_LINE_THROUGH = SpanStyle(textDecoration = TextDecoration.LineThrough)
        val TAG_STYLE_MONOSPACE = SpanStyle(fontFamily = FontFamily.Monospace)
        val TAG_STYLE_SUP = SpanStyle(
            baselineShift = BaselineShift.Superscript,
            fontSize = SUB_SUPER_FONT_SIZE,
        )
        val TAG_STYLE_SUB = SpanStyle(
            baselineShift = BaselineShift.Subscript,
            fontSize = SUB_SUPER_FONT_SIZE,
        )
        val TAG_STYLE_SMALL = SpanStyle(fontSize = TextUnit(0.85f, TextUnitType.Em))

        // English compile-time constants: the parser layer has no Android
        // resources. Localizing them means plumbing a strings provider
        // through HtmlMapper — accepted debt for the uk pass.
        const val AUDIO_PLACEHOLDER = "♪ Audio is not supported"
        const val VIDEO_PLACEHOLDER = "▶ Video is not supported"
    }
}
