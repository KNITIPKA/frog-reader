package com.example.frogreader.data.parser

import androidx.annotation.Keep
import com.example.frogreader.engine.RustBridge
import org.jsoup.nodes.Attributes
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.LeafNode
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/** Materializes native chapter nodes and precomputed index metadata for content mapping. */
internal object RustChapterMarkup {
    // Tag flags (keep 1:1 with rust/frogreader-core/src/chapter_markup.rs).
    const val TAG_ROOT = 1 shl 0
    const val TAG_PUBLISHER_BOX = 1 shl 1
    const val TAG_NON_BLOCK = 1 shl 2
    const val TAG_NON_CONTENT = 1 shl 3
    const val TAG_NON_TEXT_CONTENT = 1 shl 4
    const val TAG_NOTE_ITEM = 1 shl 5
    const val TAG_IMAGE = 1 shl 6
    const val TAG_HEADING = 1 shl 7
    const val TAG_BLOCK_PROBE = 1 shl 8
    const val TAG_HORIZONTAL_COMPLEX = 1 shl 9
    const val TAG_CSS_ROW_EXCLUDED = 1 shl 10
    const val TAG_SIMPLE_INLINE_TEXT_BOX = 1 shl 11
    const val TAG_INLINE_TEXT_BLOCK = 1 shl 12
    const val TAG_EXPLICIT_INITIAL = 1 shl 13
    const val TAG_SEMANTIC_MONOSPACE = 1 shl 14
    const val TAG_NESTED_LIST = 1 shl 15
    const val TAG_CELL_BLOCK = 1 shl 16
    const val TAG_MATH = 1 shl 17
    const val TAG_MATH_EXACT = 1 shl 18
    const val TAG_IMG = 1 shl 19
    const val TAG_SVG = 1 shl 20
    const val TAG_SUP = 1 shl 21
    const val TAG_A = 1 shl 22
    const val TAG_BR = 1 shl 23
    const val TAG_CAPTION = 1 shl 24
    const val TAG_NOTE_PRODNOTE = 1 shl 25
    const val TAG_STYLE = 1 shl 26
    const val TAG_LINK = 1 shl 27
    const val TAG_SCRIPT = 1 shl 28
    const val TAG_INLINE_SEMANTIC = 1 shl 29
    const val TAG_TABLE = 1 shl 30
    const val TAG_TR = 1 shl 31

    // Attribute & ancestor flags.
    const val ATTR_HAS_ID = 1 shl 0
    const val ATTR_HAS_SRC = 1 shl 1
    const val ATTR_HAS_HREF = 1 shl 2
    const val ATTR_HAS_XLINK_HREF = 1 shl 3
    const val ATTR_HAS_STYLE = 1 shl 4
    const val ATTR_HAS_DIR = 1 shl 5
    const val ATTR_HAS_LANG = 1 shl 6
    const val ATTR_TYPE_IS_NOTE = 1 shl 7
    const val ATTR_EPUB_NOTEREF = 1 shl 8
    const val ATTR_ROLE_DOC_NOTEREF = 1 shl 9
    const val ATTR_NOTE_CONTAINER_TOKEN = 1 shl 10
    const val ATTR_NOTE_COLLECTION_TOKEN = 1 shl 11
    const val ATTR_IS_SEMANTIC_NOTE_CONTAINER = 1 shl 12
    const val ATTR_HAS_NOTE_HINT = 1 shl 13
    const val ATTR_HAS_EMPTY_LINE_CLASS = 1 shl 14
    const val ATTR_IS_DISPLAY_MATH = 1 shl 15
    const val ATTR_IS_BLOCK_PROBE_MATH = 1 shl 16
    const val ATTR_ANC_HAS_SUP = 1 shl 17
    const val ATTR_ANC_HAS_LANG = 1 shl 18
    const val ATTR_ANC_HAS_DIR = 1 shl 19
    const val ATTR_ANC_HAS_FLOW_SCOPE = 1 shl 20
    const val TITLE_CLASS_SHIFT = 21
    const val ATTR_HAS_PRESENTATIONAL = 1 shl 24

    private const val MASK_CAPTURED_ATTRS =
        ATTR_HAS_ID or ATTR_HAS_SRC or ATTR_HAS_HREF or ATTR_HAS_XLINK_HREF

    private const val MASK_ANC_ATTR_FLAGS =
        ATTR_ANC_HAS_SUP or ATTR_ANC_HAS_LANG or ATTR_ANC_HAS_DIR or ATTR_ANC_HAS_FLOW_SCOPE

    // Subtree summary flags (self or descendant).
    const val SUBTREE_HAS_BLOCK_PROBE = 1 shl 0
    const val SUBTREE_HAS_STYLE = 1 shl 1
    const val SUBTREE_HAS_ID = 1 shl 2
    const val SUBTREE_HAS_IMG = 1 shl 3
    const val SUBTREE_HAS_SVG = 1 shl 4
    const val SUBTREE_HAS_HORIZONTAL_COMPLEX = 1 shl 5
    const val SUBTREE_HAS_SUP = 1 shl 6
    const val SUBTREE_HAS_CAPTION = 1 shl 7
    const val SUBTREE_HAS_NESTED_LIST = 1 shl 8
    const val SUBTREE_HAS_A_HREF = 1 shl 9
    const val SUBTREE_HAS_JAVA_NON_WS_TEXT = 1 shl 10
    const val SUBTREE_SIMPLE_LEAF_TEXT = 1 shl 11
    const val SUBTREE_HAS_INLINE_FLOW_CANDIDATE = 1 shl 12

    // Text whitespace compaction hints.
    const val TEXT_UNKNOWN = -1
    const val TEXT_HTML_BLANK = 1 shl 0
    const val TEXT_COMPACT = 1 shl 1
    const val TEXT_ALREADY_COLLAPSED_ASCII = TEXT_COMPACT
    const val TEXT_STARTS_HTML_WS = 1 shl 2
    const val TEXT_ENDS_HTML_WS = 1 shl 3
    const val TEXT_HAS_JAVA_NON_WS = 1 shl 4
    const val TEXT_HAS_CR = 1 shl 5
    const val TEXT_HAS_KOTLIN_NON_WS = 1 shl 7

    // Document summary flags.
    const val DOC_HAS_MATH = 1 shl 0
    const val DOC_HAS_SVG = 1 shl 1
    const val DOC_HAS_SCRIPT = 1 shl 2
    const val DOC_MAYBE_VERTICAL_WRITING = 1 shl 3
    const val DOC_HAS_ANY_STYLE = 1 shl 4
    const val DOC_HAS_ANY_LANG = 1 shl 5
    const val DOC_HAS_ANY_DIR = 1 shl 6
    const val DOC_HAS_ANY_ID = 1 shl 7
    const val DOC_HAS_ANY_A_HREF = 1 shl 8
    const val DOC_HAS_ANY_PRESENTATIONAL = 1 shl 9

    // Post-parse mutation tracking masks.
    const val MUTATED_ID = 1 shl 0
    const val MUTATED_SRC = 1 shl 1
    const val MUTATED_HREF = 1 shl 2
    const val MUTATED_STYLE = 1 shl 3
    const val MUTATED_TYPE = 1 shl 4
    const val MUTATED_OTHER = 1 shl 5
    const val MUTATED_PRESENTATIONAL = 1 shl 6

    fun parse(bytes: ByteArray, htmlOnly: Boolean = false): Document? {
        if (!RustBridge.shared.isLibraryReady) throw IOException("Rust chapter parser is unavailable")
        val wire = RustChapterMarkupNative.parse(bytes, htmlOnly) ?: return null
        return Fields(wire).document()
    }

    /** The EPUB mapper keeps the original tree available to the CSS matcher. */
    fun parseRetained(bytes: ByteArray): RetainedDocument? {
        if (!RustBridge.shared.isLibraryReady) throw IOException("Rust chapter parser is unavailable")
        val handle = RustChapterMarkupNative.open(bytes, false)
        if (handle == 0L) return null
        return try {
            val wire = RustChapterMarkupNative.snapshot(handle)
            if (wire == null) {
                RustChapterMarkupNative.close(handle)
                null
            } else {
                Fields(wire).document(handle) as RetainedDocument
            }
        } catch (error: Throwable) {
            RustChapterMarkupNative.close(handle)
            throw error
        }
    }

    fun parseFromZip(zipHandle: Long, entryIndex: Int, htmlOnly: Boolean = false): Document? {
        if (!RustBridge.shared.isLibraryReady) throw IOException("Rust chapter parser is unavailable")
        val wire = RustChapterMarkupNative.parseFromZip(zipHandle, entryIndex, htmlOnly) ?: return null
        return Fields(wire).document()
    }

    fun decodeSnapshot(wire: ByteArray): Document? = Fields(wire).document()

    fun parseRetainedFromZip(zipHandle: Long, entryIndex: Int): RetainedDocument? {
        if (!RustBridge.shared.isLibraryReady) throw IOException("Rust chapter parser is unavailable")
        val handle = RustChapterMarkupNative.openFromZip(zipHandle, entryIndex, false)
        if (handle == 0L) return null
        return try {
            val wire = RustChapterMarkupNative.snapshot(handle)
            if (wire == null) {
                RustChapterMarkupNative.close(handle)
                null
            } else {
                Fields(wire).document(handle) as RetainedDocument
            }
        } catch (error: Throwable) {
            RustChapterMarkupNative.close(handle)
            throw error
        }
    }

    open class MarkupDocument(namespace: String) : Document(namespace, "") {
        internal var docFlags: Int = 0
        internal var bodyElement: Element? = null
        internal var bookElement: Element? = null
        internal var headTitleElement: Element? = null
        internal var viewportMetaElement: Element? = null
        internal var stylesheetElements: List<Element> = emptyList()
        private var buildComplete: Boolean = false
        internal var treeMutated: Boolean = false
            private set
        internal var mutatedAttrs: Int = 0
            private set

        internal fun finishBuild() {
            buildComplete = true
        }

        internal fun hasDocFlag(flag: Int): Boolean = (docFlags and flag) != 0

        internal fun markTreeMutated() {
            if (buildComplete) treeMutated = true
        }

        internal fun markAttrMutated(key: String) {
            if (!buildComplete) return
            mutatedAttrs = mutatedAttrs or when {
                key.equals("id", ignoreCase = true) -> MUTATED_ID
                key.equals("src", ignoreCase = true) || key.equals("xlink:href", ignoreCase = true) -> MUTATED_SRC
                key.equals("href", ignoreCase = true) -> MUTATED_HREF
                key.equals("style", ignoreCase = true) -> MUTATED_STYLE
                key.equals("type", ignoreCase = true) -> MUTATED_TYPE
                key.equals("align", ignoreCase = true) ||
                    key.equals("color", ignoreCase = true) ||
                    key.equals("text", ignoreCase = true) ||
                    key.equals("bgcolor", ignoreCase = true) -> MUTATED_PRESENTATIONAL
                else -> MUTATED_OTHER
            }
        }

        internal fun markAllAttrsMutated() {
            if (buildComplete) mutatedAttrs = -1
        }

        override fun attr(attributeKey: String, attributeValue: String): Element {
            markAttrMutated(attributeKey)
            return super.attr(attributeKey, attributeValue)
        }

        override fun removeAttr(attributeKey: String): Element {
            markAttrMutated(attributeKey)
            return super.removeAttr(attributeKey)
        }

        override fun clearAttributes(): Element {
            markAllAttrsMutated()
            return super.clearAttributes()
        }

        override fun appendChild(child: Node): Element {
            markTreeMutated()
            return super.appendChild(child)
        }

        override fun removeChild(out: Node) {
            markTreeMutated()
            super.removeChild(out)
        }

        override fun addChildren(vararg children: Node) {
            markTreeMutated()
            super.addChildren(*children)
        }

        override fun addChildren(index: Int, vararg children: Node) {
            markTreeMutated()
            super.addChildren(index, *children)
        }

        override fun replaceChild(out: Node, `in`: Node) {
            markTreeMutated()
            super.replaceChild(out, `in`)
        }

        override fun empty(): Element {
            markTreeMutated()
            return super.empty()
        }
    }

    class RetainedDocument(
        namespace: String,
        handle: Long,
        expectedNodes: Int = 32,
    ) : MarkupDocument(namespace), AutoCloseable {
        private var chapterHandle = handle
        internal val cssNodeIds = IdentityHashMap<Element, Int>(expectedNodes + 1).apply {
            put(this@RetainedDocument, 0)
        }

        internal fun openMatcher(rules: ByteArray): Long {
            check(chapterHandle != 0L) { "Rust chapter is closed" }
            return RustCssMatchNative.openChapter(chapterHandle, rules)
        }

        override fun close() {
            val handle = chapterHandle
            if (handle == 0L) return
            chapterHandle = 0
            cssNodeIds.clear()
            RustChapterMarkupNative.close(handle)
        }
    }

    internal class PreindexedElement(
        tag: Tag,
        attributes: Attributes?,
        internal var owner: MarkupDocument?,
        internal val tagFlags: Int,
        internal val attrFlags: Int,
        internal val subtreeFlags: Int,
        internal val anchorId: String?,
        internal val srcAttr: String?,
        internal val hrefAttr: String?,
        internal val xlinkHrefAttr: String?,
    ) : Element(tag, null, attributes) {
        internal var promotedNote: Boolean = false
            private set

        internal fun hasTag(flag: Int): Boolean = (tagFlags and flag) != 0

        internal fun hasAttr(flag: Int): Boolean = hasAttrFlag(flag)

        internal fun hasAttrFlag(flag: Int): Boolean {
            if ((flag and MASK_ANC_ATTR_FLAGS) != 0) {
                val doc = owner ?: return false
                if (doc.treeMutated) return false
            }
            return (attrFlags and flag) != 0
        }

        internal fun hasSubtree(flag: Int): Boolean = hasSubtreeFlag(flag)

        internal fun hasSubtreeFlag(flag: Int): Boolean {
            val doc = owner ?: return false
            if (doc.treeMutated) return false
            if ((subtreeFlags and flag) == 0) return false
            if ((flag and SUBTREE_SIMPLE_LEAF_TEXT) != 0) {
                if (childNodeSize() != 1) return false
                val textNode = childNode(0) as? PreindexedTextNode ?: return false
                if (!textNode.hasTextFlag(TEXT_HAS_KOTLIN_NON_WS)) return false
            }
            return true
        }

        internal val titleClassLevel: Int
            get() = (attrFlags ushr TITLE_CLASS_SHIFT) and 0x7

        internal fun setPromotedNoteType() {
            promotedNote = true
            super.attr("type", "note")
        }

        override fun attr(attributeKey: String, attributeValue: String): Element {
            owner?.markAttrMutated(attributeKey)
            return super.attr(attributeKey, attributeValue)
        }

        override fun removeAttr(attributeKey: String): Element {
            owner?.markAttrMutated(attributeKey)
            return super.removeAttr(attributeKey)
        }

        override fun clearAttributes(): Element {
            owner?.markAllAttrsMutated()
            return super.clearAttributes()
        }

        override fun appendChild(child: Node): Element {
            owner?.markTreeMutated()
            return super.appendChild(child)
        }

        override fun removeChild(out: Node) {
            owner?.markTreeMutated()
            super.removeChild(out)
        }

        override fun addChildren(vararg children: Node) {
            owner?.markTreeMutated()
            super.addChildren(*children)
        }

        override fun addChildren(index: Int, vararg children: Node) {
            owner?.markTreeMutated()
            super.addChildren(index, *children)
        }

        override fun replaceChild(out: Node, `in`: Node) {
            owner?.markTreeMutated()
            super.replaceChild(out, `in`)
        }

        override fun empty(): Element {
            owner?.markTreeMutated()
            return super.empty()
        }

        override fun doClone(parent: Node?): Element {
            val cloned = super.doClone(parent) as PreindexedElement
            cloned.owner = null
            return cloned
        }
    }

    internal class PreindexedTextNode(
        text: String,
        internal var textFlags: Int,
    ) : TextNode(text) {
        internal fun hasTextFlag(flag: Int): Boolean =
            textFlags != TEXT_UNKNOWN && (textFlags and flag) != 0

        override fun text(text: String): TextNode {
            textFlags = TEXT_UNKNOWN
            return super.text(text)
        }

        override fun doClone(parent: Node?): LeafNode =
            super.doClone(parent)
    }

    private class Fields(private val bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val htmlTagCache = HashMap<String, Tag>(64)

        fun document(handle: Long? = null): Document {
            if (int() != SIGNATURE) throw IOException("Invalid chapter markup protocol")
            val html = when (int()) {
                0 -> false
                1 -> true
                else -> throw IOException("Invalid chapter markup mode")
            }
            val docFlags = int()
            val bodyIndex = int()
            val bookIndex = int()
            val headTitleIndex = int()
            val viewportMetaIndex = int()
            val count = count(MIN_NODE_BYTES)
            val parser = if (html) Parser.htmlParser() else Parser.xmlParser()
            val retained = handle?.let { RetainedDocument(parser.defaultNamespace(), it, count) }
            val document = (retained ?: MarkupDocument(parser.defaultNamespace())).also {
                it.parser(parser)
                it.docFlags = docFlags
            }
            if (!html) {
                document.outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(false)
            }

            val elements = arrayOfNulls<Element>(count)
            var hasBaseUri = false
            for (index in elements.indices) {
                val parentIndex = int()
                val parent = when {
                    parentIndex == -1 -> document
                    parentIndex in 0 until index -> elements[parentIndex]
                    else -> null
                } ?: throw IOException("Invalid chapter markup parent")
                val child = when (int()) {
                    ELEMENT -> element(parser, document).also {
                        elements[index] = it
                        retained?.cssNodeIds?.let { ids -> ids[it] = ids.size }
                    }
                    TEXT -> PreindexedTextNode(textFlags = int(), text = string())
                    DATA -> DataNode(string())
                    CDATA -> CDataNode(string())
                    COMMENT -> Comment(string())
                    DOCTYPE -> DocumentType(string(), string(), string())
                    INSTRUCTION -> instruction(string(), string())
                    else -> throw IOException("Invalid chapter markup node")
                }
                parent.appendChild(child)
                if (html && !hasBaseUri && child is PreindexedElement &&
                    child.normalName() == "base" && child.tag().namespace() == Parser.NamespaceHtml
                ) {
                    val href = child.absUrl("href")
                    if (href.isNotEmpty()) {
                        document.setBaseUri(href)
                        hasBaseUri = true
                    }
                }
            }

            document.bodyElement = summaryElement(elements, bodyIndex)
            document.bookElement = summaryElement(elements, bookIndex)
            document.headTitleElement = summaryElement(elements, headTitleIndex)
            document.viewportMetaElement = summaryElement(elements, viewportMetaIndex)
            val stylesheetCount = count(Int.SIZE_BYTES)
            if (stylesheetCount > 0) {
                val stylesheets = ArrayList<Element>(stylesheetCount)
                repeat(stylesheetCount) {
                    val idx = int()
                    stylesheets.add(
                        summaryElement(elements, idx)
                            ?: throw IOException("Invalid chapter markup stylesheet index"),
                    )
                }
                document.stylesheetElements = stylesheets
            }

            if (input.hasRemaining()) throw IOException("Trailing chapter markup data")
            if (html) applyHtmlQuirks(document)
            document.finishBuild()
            return document
        }

        private fun summaryElement(elements: Array<Element?>, index: Int): Element? {
            if (index == -1) return null
            if (index !in elements.indices) throw IOException("Invalid chapter markup summary index")
            return elements[index] ?: throw IOException("Invalid chapter markup summary target")
        }

        private fun element(parser: Parser, document: MarkupDocument): Element {
            val name = string()
            val namespace = namespaceString()
            if (name.isBlank()) throw IOException("Invalid chapter markup tag")
            val selfClosing = when (int()) {
                0 -> false
                1 -> true
                else -> throw IOException("Invalid chapter markup self-closing flag")
            }
            val tagFlags = int()
            val attrFlags = int()
            val subtreeFlags = int()
            val attributeCount = count(2 * Int.SIZE_BYTES)
            var anchorId: String? = null
            var srcAttr: String? = null
            var hrefAttr: String? = null
            var xlinkHrefAttr: String? = null
            val captureInteresting = (attrFlags and MASK_CAPTURED_ATTRS) != 0
            val attributes = if (attributeCount == 0) null else Attributes().apply {
                repeat(attributeCount) {
                    val key = string()
                    val value = string()
                    add(key, value)
                    if (captureInteresting) {
                        if ((attrFlags and ATTR_HAS_ID) != 0 && anchorId == null && key.equals("id", ignoreCase = true)) {
                            anchorId = value
                        } else if ((attrFlags and ATTR_HAS_SRC) != 0 && srcAttr == null && key.equals("src", ignoreCase = true)) {
                            srcAttr = value
                        } else if ((attrFlags and ATTR_HAS_HREF) != 0 && hrefAttr == null && key.equals("href", ignoreCase = true)) {
                            hrefAttr = value
                        } else if ((attrFlags and ATTR_HAS_XLINK_HREF) != 0 && xlinkHrefAttr == null && key.equals("xlink:href", ignoreCase = true)) {
                            xlinkHrefAttr = value
                        }
                    }
                }
            }
            // The native parser supplies canonical spelling, including SVG names like linearGradient.
            val tag = if (namespace === HTML_NS) {
                htmlTagCache.getOrPut(name) {
                    parser.tagSet().valueOf(name, namespace, ParseSettings.preserveCase)
                }
            } else {
                parser.tagSet().valueOf(name, namespace, ParseSettings.preserveCase)
            }
            if (selfClosing && !tag.`is`(Tag.SeenSelfClose)) {
                // Jsoup shares the seen-self-close flag by name and namespace.
                val known = tag.isKnownTag
                tag.set(Tag.SeenSelfClose)
                if (!known) tag.clear(Tag.Known)
            }
            return PreindexedElement(
                tag = tag,
                attributes = attributes,
                owner = document,
                tagFlags = tagFlags,
                attrFlags = attrFlags,
                subtreeFlags = subtreeFlags,
                anchorId = anchorId,
                srcAttr = srcAttr,
                hrefAttr = hrefAttr,
                xlinkHrefAttr = xlinkHrefAttr,
            )
        }

        private fun namespaceString(): String {
            val length = int()
            if (length !in 0..input.remaining()) throw IOException("Invalid chapter markup string size")
            if (length == 0) return ""
            val start = input.position()
            val end = start + length
            input.position(end)
            return when {
                length == HTML_NS_BYTES.size && matchesBytes(start, HTML_NS_BYTES) -> HTML_NS
                length == XML_NS_BYTES.size && matchesBytes(start, XML_NS_BYTES) -> XML_NS
                length == SVG_NS_BYTES.size && matchesBytes(start, SVG_NS_BYTES) -> SVG_NS
                else -> bytes.decodeToString(start, end, throwOnInvalidSequence = true)
            }
        }

        private fun matchesBytes(offset: Int, expected: ByteArray): Boolean {
            for (i in expected.indices) {
                if (bytes[offset + i] != expected[i]) return false
            }
            return true
        }

        private fun count(minBytes: Int): Int {
            val value = int()
            if (value !in 0..(input.remaining() / minBytes)) throw IOException("Invalid chapter markup count")
            return value
        }

        private fun int(): Int {
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated chapter markup")
            return input.int
        }

        private fun string(): String {
            val length = int()
            if (length !in 0..input.remaining()) throw IOException("Invalid chapter markup string size")
            if (length == 0) return ""
            val start = input.position()
            val end = start + length
            input.position(end)
            return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        }
    }

    private fun instruction(target: String, data: String): XmlDeclaration {
        val declaration = XmlDeclaration(target, false)
        var cursor = 0
        fun skipWhitespace() {
            while (cursor < data.length && data[cursor].isWhitespace()) cursor++
        }
        // Jsoup represents processing instruction data as ordered pseudo-attributes.
        while (cursor < data.length) {
            skipWhitespace()
            val start = cursor
            while (cursor < data.length && data[cursor] != '=' && !data[cursor].isWhitespace()) cursor++
            if (start == cursor) {
                cursor++
                continue
            }
            val name = data.substring(start, cursor)
            skipWhitespace()
            if (cursor == data.length || data[cursor] != '=') {
                declaration.attributes().add(name, null)
                continue
            }
            cursor++
            skipWhitespace()
            val quote = data.getOrNull(cursor)?.takeIf { it == '\'' || it == '"' }
            if (quote != null) cursor++
            val valueStart = cursor
            while (cursor < data.length && (quote?.let { data[cursor] != it } ?: !data[cursor].isWhitespace())) {
                cursor++
            }
            declaration.attributes().add(name, Parser.unescapeEntities(data.substring(valueStart, cursor), true))
            if (quote != null && cursor < data.length) cursor++
        }
        return declaration
    }

    private fun applyHtmlQuirks(document: Document) {
        val doctype = document.documentType()
        if (doctype == null || doctype.name() != "html" || doctype.publicId().equals("HTML", ignoreCase = true)) {
            document.quirksMode(Document.QuirksMode.quirks)
        }
    }

    private const val HTML_NS = Parser.NamespaceHtml
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private const val SVG_NS = "http://www.w3.org/2000/svg"
    private val HTML_NS_BYTES = HTML_NS.encodeToByteArray()
    private val XML_NS_BYTES = XML_NS.encodeToByteArray()
    private val SVG_NS_BYTES = SVG_NS.encodeToByteArray()

    private const val SIGNATURE = 0x324b524d // MRK2
    private const val MIN_NODE_BYTES = 3 * Int.SIZE_BYTES
    private const val ELEMENT = 1
    private const val TEXT = 2
    private const val DATA = 3
    private const val CDATA = 4
    private const val COMMENT = 5
    private const val DOCTYPE = 6
    private const val INSTRUCTION = 7
}

/** Names and signatures are part of the JNI ABI. */
@Keep
internal object RustChapterMarkupNative {
    @Keep external fun parse(bytes: ByteArray, htmlOnly: Boolean): ByteArray?
    @Keep external fun open(bytes: ByteArray, htmlOnly: Boolean): Long
    @Keep external fun parseFromZip(zipHandle: Long, entryIndex: Int, htmlOnly: Boolean): ByteArray?
    @Keep external fun openFromZip(zipHandle: Long, entryIndex: Int, htmlOnly: Boolean): Long
    @Keep external fun snapshot(handle: Long): ByteArray?
    @Keep external fun close(handle: Long)
}
