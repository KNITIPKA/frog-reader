package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RustCssValues {
    // Reference identity matters to CssResolver's shared computed-style keys.
    class Declaration(val value: String, val important: Boolean, val expansion: Int)

    class Block(val consumed: Int, val declarations: List<Pair<String, Declaration>>)

    class Variables(val scope: Map<String, String>, private val resolved: Map<String, String?>) {
        fun value(raw: String): String? = if ("var(" in raw) resolved[raw] else raw
    }

    class LineHeight(val number: Float? = null, val absoluteEm: Float? = null) {
        fun multiplier(fontSizeEm: Float): Float? =
            number ?: absoluteEm?.div(fontSizeEm.coerceAtLeast(0.1f))
    }

    fun compile(source: List<RustCssSyntax.Declaration>, limit: Int): Block {
        val count = minOf(source.size, limit)
        if (count == 0) return Block(0, emptyList())
        checkLibrary()
        val wire = CssMatchWireWriter(MAX_BLOCK_BYTES)
        wire.int(count)
        for (index in 0 until count) {
            val declaration = source[index]
            wire.string(declaration.property)
            wire.string(declaration.value)
            wire.int(if (declaration.important) 1 else 0)
        }

        val result = Fields(RustCssValuesNative.compile(wire.toByteArray()))
        val consumed = result.int()
        check(consumed in 0..count) { "Invalid CSS declaration attempt count" }
        val values = List(result.count(MAX_DECLARATIONS, 4)) { result.text() }
        val declarations = List(result.count(MAX_DECLARATIONS, 16)) {
            val property = result.text()
            val value = values[result.int()]
            val important = result.int()
            check(important in 0..1) { "Invalid CSS importance flag" }
            val expansion = result.int()
            check(expansion in 0..MAX_EXPANSION) { "Invalid CSS shorthand expansion" }
            property to Declaration(value, important == 1, expansion)
        }
        result.finish()
        return Block(consumed, declarations)
    }

    fun variables(parent: Map<String, String>, declarations: Map<String, Declaration>): Variables {
        var own: MutableMap<String, String>? = null
        var references: MutableSet<String>? = null
        for ((property, declaration) in declarations) {
            if (property.startsWith("--")) {
                if (own == null) own = linkedMapOf()
                own[property] = declaration.value
            } else if ("var(" in declaration.value) {
                if (references == null) references = linkedSetOf()
                references.add(declaration.value)
            }
        }
        if (own == null && references == null) return Variables(parent, emptyMap())

        checkLibrary()
        val wire = CssMatchWireWriter(MAX_SCOPE_BYTES)
        wire.scope(parent)
        wire.scope(own.orEmpty())
        val values = references.orEmpty()
        wire.strings(values)
        val result = Fields(RustCssValuesNative.variables(wire.toByteArray()))
        val scopeCount = result.int()
        val scope = if (scopeCount == -1) parent else {
            result.checkCount(scopeCount, MAX_SCOPE_BYTES / 8, 8)
            buildMap(scopeCount) {
                repeat(scopeCount) {
                    put(result.text(), result.text())
                }
            }
        }
        check(result.count(values.size, 4) == values.size) { "Invalid CSS variable result count" }
        val resolved = buildMap(values.size) {
            for (value in values) put(value, result.optionalText())
        }
        result.finish()
        return Variables(scope, resolved)
    }

    fun expand(expansion: Int, value: String): String? {
        checkLibrary()
        return RustCssValuesNative.expand(value.encodeToByteArray(), expansion)
            ?.decodeToString(throwOnInvalidSequence = true)
    }

    fun fontSize(value: String, inherited: Float): Float? {
        checkLibrary()
        return RustCssValuesNative.fontSize(value.encodeToByteArray(), inherited).takeUnless(Float::isNaN)
    }

    fun lengthEm(
        value: String,
        percentBase: Float = 0f,
        emScale: Float = 1f,
        allowNegative: Boolean = false,
    ): Float? {
        checkLibrary()
        return RustCssValuesNative.lengthEm(value.encodeToByteArray(), percentBase, emScale, allowNegative)
            .takeUnless(Float::isNaN)
    }

    fun lineHeight(value: String, fontSizeEm: Float): LineHeight? {
        checkLibrary()
        val result = RustCssValuesNative.lineHeight(value.encodeToByteArray(), fontSizeEm)
        val amount = Float.fromBits(result.toInt())
        return when ((result ushr 32).toInt()) {
            0 -> null
            1 -> LineHeight(number = amount)
            2 -> LineHeight(absoluteEm = amount)
            else -> error("Invalid computed CSS line height")
        }
    }

    private fun CssMatchWireWriter.scope(scope: Map<String, String>) {
        int(scope.size)
        for ((name, value) in scope) {
            string(name)
            string(value)
        }
    }

    private fun checkLibrary() {
        check(RustBridge.shared.isLibraryReady) { "Rust CSS values are unavailable" }
    }

    private class Fields(private val bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun int(): Int {
            check(input.remaining() >= Int.SIZE_BYTES) { "Truncated CSS value data" }
            return input.int
        }

        fun checkCount(count: Int, limit: Int, minRowBytes: Int) {
            check(count in 0..limit && count <= input.remaining() / minRowBytes) { "Invalid CSS value count" }
        }

        fun count(limit: Int, minRowBytes: Int): Int =
            int().also { checkCount(it, limit, minRowBytes) }

        fun text(): String = text(int())

        fun optionalText(): String? {
            val length = int()
            return if (length == -1) null else text(length)
        }

        private fun text(length: Int): String {
            check(length in 0..input.remaining()) { "Invalid CSS value text" }
            val start = input.position()
            val end = start + length
            input.position(end)
            return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        }

        fun finish() {
            check(!input.hasRemaining()) { "Trailing CSS value data" }
        }
    }

    private const val MAX_BLOCK_BYTES = 32 * 1024 * 1024
    private const val MAX_SCOPE_BYTES = 128 * 1024 * 1024
    private const val MAX_DECLARATIONS = 512
    private const val MAX_EXPANSION = 22
}

/** Names and signatures are part of the JNI ABI. */
internal object RustCssValuesNative {
    external fun compile(declarations: ByteArray): ByteArray
    external fun variables(input: ByteArray): ByteArray
    external fun expand(value: ByteArray, expansion: Int): ByteArray?
    external fun fontSize(value: ByteArray, inherited: Float): Float
    external fun lengthEm(value: ByteArray, percentBase: Float, emScale: Float, allowNegative: Boolean): Float
    external fun lineHeight(value: ByteArray, fontSizeEm: Float): Long
}
