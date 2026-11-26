package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compiles each supported selector into the matcher program's native format. */
internal object RustCssSelectors {
    class Compiled(
        val pool: Int,
        val specificity: Int,
        val bucket: String?,
        val absoluteKeys: List<String>,
        val chain: ByteArray,
    )

    fun compile(selector: String, namespaces: Map<String, String>, limit: Int): List<Compiled> {
        require(limit >= 0)
        if (limit == 0 || selector.isEmpty()) return emptyList()
        check(RustBridge.shared.isLibraryReady) { "Rust CSS selector compiler is unavailable" }
        val countLimit = minOf(limit, MAX_SELECTOR_COUNT)
        val result = RustCssSelectorsNative.compile(selector.encodeToByteArray(), namespaceBytes(namespaces), countLimit)
        return Fields(result).selectors(countLimit)
    }

    private fun namespaceBytes(namespaces: Map<String, String>): ByteArray {
        if (namespaces.isEmpty()) return EMPTY_NAMESPACES
        val writer = CssMatchWireWriter(MAX_INPUT_BYTES)
        writer.int(namespaces.size)
        namespaces.forEach { (prefix, uri) ->
            writer.string(prefix)
            writer.string(uri)
        }
        return writer.toByteArray()
    }

    private class Fields(private val bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun selectors(limit: Int): List<Compiled> {
            val count = int()
            check(count in 0..limit && count <= input.remaining() / MIN_SELECTOR_BYTES) {
                "Invalid compiled CSS selector count"
            }
            val selectors = List(count) {
                val pool = int()
                check(pool in 0 until SELECTOR_POOLS) { "Invalid compiled CSS selector pool" }
                val specificity = int()
                val bucketLength = int()
                val bucket = if (bucketLength == -1) null else text(bucketLength)
                val keyCount = int()
                check(keyCount >= 0 && keyCount <= input.remaining() / Int.SIZE_BYTES) {
                    "Invalid absolute CSS attribute count"
                }
                val keys = List(keyCount) { text(int()) }
                val chainLength = int()
                check(chainLength in MIN_CHAIN_BYTES..input.remaining()) { "Invalid compiled CSS selector chain" }
                val start = input.position()
                val end = start + chainLength
                input.position(end)
                Compiled(pool, specificity, bucket, keys, bytes.copyOfRange(start, end))
            }
            check(!input.hasRemaining()) { "Trailing compiled CSS selector data" }
            return selectors
        }

        private fun int(): Int {
            check(input.remaining() >= Int.SIZE_BYTES) { "Truncated compiled CSS selector data" }
            return input.int
        }

        private fun text(length: Int): String {
            check(length in 0..input.remaining()) { "Invalid compiled CSS selector text" }
            val start = input.position()
            val end = start + length
            input.position(end)
            return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        }
    }

    private val EMPTY_NAMESPACES = ByteArray(Int.SIZE_BYTES)
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_SELECTOR_COUNT = 256
    private const val SELECTOR_POOLS = 4
    private const val MIN_CHAIN_BYTES = 7 * Int.SIZE_BYTES
    private const val MIN_SELECTOR_BYTES = 5 * Int.SIZE_BYTES + MIN_CHAIN_BYTES
}

/** Names and signatures are part of the JNI ABI. */
internal object RustCssSelectorsNative {
    external fun compile(selector: ByteArray, namespaces: ByteArray, limit: Int): ByteArray
}
