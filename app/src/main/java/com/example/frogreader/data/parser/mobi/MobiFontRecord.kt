package com.example.frogreader.data.parser.mobi

import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire

/**
 * Kindle embedded-font resource record ("FONT" magic): the payload is a
 * raw sfnt, optionally XOR-obfuscated over its first 1040 bytes and/or
 * zlib-compressed (KindleUnpack layout: u32 usize@+4, flags@+8,
 * dataStart@+12, xorLen@+16, xorStart@+20).
 */
internal object MobiFontRecord {

    private const val HEADER = 24
    private const val DEFAULT_MAX_FONT_BYTES = 32 * 1024 * 1024

    fun isFontRecord(data: ByteArray, off: Int, len: Int): Boolean =
        len >= HEADER && off >= 0 && off <= data.size - len && data.magic(off, "FONT")

    /** Decoded sfnt bytes, or null on structural damage. */
    fun decode(
        data: ByteArray,
        off: Int,
        len: Int,
        maxDecodedBytes: Int = DEFAULT_MAX_FONT_BYTES,
    ): ByteArray? {
        RustMobiWire.requireLibrary()
        if (maxDecodedBytes <= 0 || !isFontRecord(data, off, len)) {
            return null
        }
        return RustMobiNative.decodeFontRecord(data, off, len, maxDecodedBytes)
    }
}
