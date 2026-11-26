package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustWoffNative

/**
 * WOFF 1.0 → sfnt (TTF/OTF) unpacker, so web-only fonts inside EPUBs still
 * load (Android's font stack reads raw sfnt only). Backed by the native Rust
 * unpacker (`RustWoffNative.decodeWoff`).
 */
object WoffDecoder {

    private const val DEFAULT_MAX_DECODED_BYTES = 32 * 1024 * 1024

    fun isWoff(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'w'.code.toByte() && bytes[1] == 'O'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /** Decoded sfnt bytes, or null on any structural problem. */
    fun decode(
        woff: ByteArray,
        maxDecodedBytes: Int = DEFAULT_MAX_DECODED_BYTES,
    ): ByteArray? = runCatching {
        if (maxDecodedBytes <= 0 || !isWoff(woff) || !RustBridge.shared.isLibraryReady) return null
        RustWoffNative.decodeWoff(woff, maxDecodedBytes)
    }.getOrNull()
}
