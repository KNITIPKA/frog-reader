package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import org.jsoup.nodes.Element
import java.util.IdentityHashMap
import java.util.TreeMap

/** A chapter DOM snapshot and native matching state. Element IDs are in preorder. */
internal class RustCssMatch private constructor(
    private val ids: IdentityHashMap<Element, Int>,
    handle: Long,
) : AutoCloseable {
    private val session = RustCssSession(handle, RustCssMatchNative::close)

    fun nodeId(element: Element): Int? = ids[element]

    fun cascade(pool: Int, node: Int, budget: Int, inline: IntArray?): IntArray =
        session.useHandle { RustCssMatchNative.cascade(it, pool, node, budget, inline) }

    override fun close() = session.close()

    companion object {
        fun cascadeInline(declarations: IntArray): IntArray {
            check(RustBridge.shared.isLibraryReady) { "Rust CSS cascade is unavailable" }
            return RustCssMatchNative.cascade(0L, 0, -1, 0, declarations)
        }

        fun open(element: Element, rules: ByteArray, absoluteKeys: Set<String>): RustCssMatch {
            check(RustBridge.shared.isLibraryReady) { "Rust CSS matcher is unavailable" }
            val root = element.root()
            if (root is RustChapterMarkup.RetainedDocument && absoluteKeys.isEmpty()) {
                val handle = root.openMatcher(rules)
                check(handle != 0L) { "Rust CSS matcher could not open the chapter" }
                return RustCssMatch(root.cssNodeIds, handle)
            }
            val nodes = ArrayList<Element>()
            val ids = IdentityHashMap<Element, Int>()
            root.traverse { node, _ ->
                if (node is Element) {
                    ids[node] = nodes.size
                    nodes.add(node)
                }
            }
            val absolute = AbsoluteAttributes(absoluteKeys)
            val dom = CssMatchWireWriter()
            dom.int(nodes.size)
            for (node in nodes) {
                dom.string(node.normalName())
                dom.string(node.id())
                dom.strings(node.classNames())
                val count = node.attributesSize()
                dom.int(count)
                val keys = if (count > 0) absolute.keysFor(node) else emptySet()
                if (count > 0) {
                    for (attribute in node.attributes()) {
                        dom.string(attribute.key)
                        dom.string(attribute.value)
                    }
                }
                dom.int(keys.size)
                for (key in keys) {
                    dom.string(key)
                    dom.string(node.attr(key))
                }
                dom.int(ids[node.parent()] ?: -1)
                dom.int(ids[node.previousElementSibling()] ?: -1)
                dom.int(ids[node.nextElementSibling()] ?: -1)
                dom.int(ids[node.firstElementChild()] ?: -1)
            }
            val handle = RustCssMatchNative.open(dom.toByteArray(), rules)
            check(handle != 0L) { "Rust CSS matcher could not open the chapter" }
            return RustCssMatch(ids, handle)
        }
    }

    private class AbsoluteAttributes(keys: Set<String>) {
        // Node.attr("abs:href") can resolve a relative `href` against the
        // base URI or read a literal `abs:href` attribute. Check only keys
        // for which either source attribute exists on this node.
        private val keysByAttribute = TreeMap<String, MutableSet<String>>(String.CASE_INSENSITIVE_ORDER)

        init {
            for (key in keys) {
                keysByAttribute.getOrPut(key) { linkedSetOf() }.add(key)
                keysByAttribute.getOrPut(key.removePrefix("abs:")) { linkedSetOf() }.add(key)
            }
        }

        fun keysFor(node: Element): Set<String> {
            if (keysByAttribute.isEmpty()) return emptySet()
            return buildSet {
                for (attribute in node.attributes()) {
                    val keys = keysByAttribute[attribute.key] ?: continue
                    for (key in keys) {
                        if (node.hasAttr(key)) add(key)
                    }
                }
            }
        }
    }
}

/** Names and signatures are part of the JNI ABI. */
internal object RustCssMatchNative {
    external fun open(dom: ByteArray, rules: ByteArray): Long
    external fun openChapter(chapterHandle: Long, rules: ByteArray): Long
    external fun cascade(handle: Long, pool: Int, node: Int, budget: Int, inline: IntArray?): IntArray
    external fun close(handle: Long)
}

internal class CssMatchWireWriter(private val byteLimit: Int = 256 * 1024 * 1024) {
    private var bytes = ByteArray(minOf(4 * 1024, byteLimit))
    private var size = 0

    fun int(value: Int) {
        reserve(Int.SIZE_BYTES)
        bytes[size++] = value.toByte()
        bytes[size++] = (value ushr 8).toByte()
        bytes[size++] = (value ushr 16).toByte()
        bytes[size++] = (value ushr 24).toByte()
    }

    fun string(value: String?) {
        if (value == null) {
            int(-1)
            return
        }
        if (value.isEmpty()) {
            int(0)
            return
        }
        val utf8 = value.encodeToByteArray()
        int(utf8.size)
        raw(utf8)
    }

    fun strings(values: Collection<String>) {
        int(values.size)
        for (value in values) string(value)
    }

    fun raw(value: ByteArray) {
        reserve(value.size)
        value.copyInto(bytes, destinationOffset = size)
        size += value.size
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun reserve(length: Int) {
        require(length <= byteLimit - size) { "CSS match data exceeds its size limit" }
        val needed = size + length
        if (needed > bytes.size) {
            bytes = bytes.copyOf(maxOf(needed, minOf(bytes.size * 2, byteLimit)))
        }
    }
}
