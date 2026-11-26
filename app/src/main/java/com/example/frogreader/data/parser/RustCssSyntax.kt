package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustCssSyntaxNative
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed author CSS. Values stay as CSS source for publisher-specific interpretation. */
internal object RustCssSyntax {
    data class Declaration(val property: String, val value: String, val important: Boolean)

    sealed interface Rule {
        data class Qualified(val selector: String, val declarations: List<Declaration>) : Rule
        data class FontFace(val declarations: List<Declaration>) : Rule
        data class Media(val condition: String, val rules: List<Rule>) : Rule
        data class Supports(val condition: String, val rules: List<Rule>) : Rule
        data class Namespace(val prelude: String) : Rule
    }

    fun sheet(text: String): List<Rule> =
        read(text, SHEET_SIGNATURE, RustCssSyntaxNative::sheet) { rules(0) }

    fun inline(text: String): List<Declaration> =
        read(text, INLINE_SIGNATURE, RustCssSyntaxNative::inline) { declarations() }

    private inline fun <T> read(
        text: String,
        signature: Int,
        parse: (ByteArray) -> ByteArray?,
        decode: Fields.() -> List<T>,
    ): List<T> {
        if (text.length > MAX_INPUT_BYTES || text.isBlank()) return emptyList()
        check(RustBridge.shared.isLibraryReady) { "Rust CSS syntax parser is unavailable" }
        val bytes = parse(text.encodeToByteArray()) ?: return emptyList()
        return Fields(bytes, signature).run { decode().also { finish() } }
    }

    private class Fields(private val bytes: ByteArray, signature: Int) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        init {
            check(number() == signature) { "Invalid CSS syntax format" }
        }

        fun rules(depth: Int): List<Rule> {
            check(depth <= MAX_GROUP_DEPTH) { "CSS syntax nesting exceeded its limit" }
            return List(count(minRowBytes = 5)) {
                when (flag()) {
                    1 -> Rule.Qualified(string(), declarations())
                    2 -> Rule.FontFace(declarations())
                    3 -> Rule.Media(string(), rules(depth + 1))
                    4 -> Rule.Supports(string(), rules(depth + 1))
                    5 -> Rule.Namespace(string())
                    else -> error("Unknown CSS syntax rule")
                }
            }
        }

        fun declarations(): List<Declaration> = List(count(minRowBytes = 9, limit = MAX_DECLARATIONS)) {
            val property = string()
            val value = string()
            val important = flag()
            check(important <= 1) { "Invalid CSS declaration flag" }
            Declaration(property, value, important == 1)
        }

        private fun count(minRowBytes: Int, limit: Int = Int.MAX_VALUE): Int {
            val count = number()
            check(count <= limit && count <= input.remaining() / minRowBytes) { "Invalid CSS syntax count" }
            return count
        }

        private fun number(): Int {
            check(input.remaining() >= Int.SIZE_BYTES) { "Truncated CSS syntax" }
            return input.int.also { check(it >= 0) { "Invalid CSS syntax number" } }
        }

        private fun string(): String {
            val length = number()
            val start = input.position()
            check(length <= input.remaining()) { "Truncated CSS syntax text" }
            input.position(start + length)
            return bytes.decodeToString(start, start + length, throwOnInvalidSequence = true)
        }

        private fun flag(): Int {
            check(input.hasRemaining()) { "Truncated CSS syntax flag" }
            return input.get().toInt() and 0xff
        }

        fun finish() {
            check(!input.hasRemaining()) { "Trailing CSS syntax data" }
        }
    }

    private const val SHEET_SIGNATURE = 0x31535343 // CSS1
    private const val INLINE_SIGNATURE = 0x44535343 // CSSD
    private const val MAX_GROUP_DEPTH = 64
    private const val MAX_DECLARATIONS = 512
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
}
