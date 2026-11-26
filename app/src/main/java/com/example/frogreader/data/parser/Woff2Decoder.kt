package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustWoffNative

/**
 * WOFF 2.0 → sfnt (TTF/OTF) unpacker (W3C WOFF2 spec): table directory with
 * known-tag indexes, pure-Rust Brotli decompression, and reconstruction of the
 * transformed `glyf`/`loca` (triplet-encoded points) and `hmtx` (omitted lsb)
 * tables. Backed by the native Rust unpacker (`RustWoffNative.decodeWoff2`).
 * Collections (`ttcf` flavor) and structurally damaged faces return null
 * cleanly so a broken face can never crash text layout.
 */
object Woff2Decoder {

    private const val DEFAULT_MAX_DECODED_BYTES = 32 * 1024 * 1024

    fun isWoff2(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'w'.code.toByte() && bytes[1] == 'O'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '2'.code.toByte()

    /** Decoded sfnt bytes, or null on anything unsupported or damaged. */
    fun decode(
        woff2: ByteArray,
        maxDecodedBytes: Int = DEFAULT_MAX_DECODED_BYTES,
    ): ByteArray? = runCatching {
        if (maxDecodedBytes <= 0 || !isWoff2(woff2) || !RustBridge.shared.isLibraryReady) return null
        RustWoffNative.decodeWoff2(woff2, maxDecodedBytes)
    }.getOrNull()
}
