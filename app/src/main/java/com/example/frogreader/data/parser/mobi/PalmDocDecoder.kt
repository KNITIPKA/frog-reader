package com.example.frogreader.data.parser.mobi

import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
import java.io.IOException

/** PalmDOC LZ77 decompression (MOBI compression type 2). */
internal object PalmDocDecoder {

    /**
     * Decompresses [len] bytes at [src]+[off] into [dst] at [dstOff].
     * Returns the number of bytes written.
     */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        RustMobiWire.requireLibrary()
        if (len <= 0) return 0
        if (off < 0 || off > src.size - len || dstOff < 0 || dstOff > dst.size) {
            throw IOException("PalmDoc: output overflow")
        }
        val maxOut = dst.size - dstOff
        val wire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.palmdocDecompress(src, off, len, maxOut),
            "PalmDoc: native decompression failed",
        )!!
        val count = wire.size - 1
        System.arraycopy(wire, 1, dst, dstOff, count)
        return count
    }
}

/**
 * Every text record may carry trailing data (multibyte overlaps, TBS
 * indexing) that is NOT part of the text — sizes are encoded backwards at
 * the record's end, one entry per set flag bit.
 */
internal object TrailingEntries {

    /** The record's actual compressed payload length after trimming. */
    fun contentLength(data: ByteArray, off: Int, len: Int, flags: Int): Int {
        RustMobiWire.requireLibrary()
        if (len <= 0) return 0
        if (flags == 0) return len
        if (off < 0 || off > data.size - len) {
            throw IndexOutOfBoundsException("TrailingEntries range out of bounds")
        }
        val nativeLen = RustMobiNative.trailingContentLength(data, off, len, flags)
        if (nativeLen < 0) throw IOException("TrailingEntries: native calculation failed")
        return nativeLen
    }

    /**
     * The backward-encoded size at the end of the first [endLen] bytes:
     * scan the last ≤4 bytes forward, 7 bits each, resetting whenever the
     * high bit (the terminator of the backward encoding) is seen.
     */
    fun backwardVarint(data: ByteArray, off: Int, endLen: Int): Int {
        RustMobiWire.requireLibrary()
        if (endLen <= 0) return 0
        if (off < 0 || off > data.size - endLen) {
            throw IndexOutOfBoundsException("TrailingEntries range out of bounds")
        }
        val nativeVal = RustMobiNative.backwardVarint(data, off, endLen)
        if (nativeVal < 0) throw IOException("TrailingEntries: native varint failed")
        return nativeVal
    }
}
