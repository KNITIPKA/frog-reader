package com.example.frogreader.data.parser.mobi

import java.io.IOException

/** PalmDOC LZ77 decompression (MOBI compression type 2). */
internal object PalmDocDecoder {

    /**
     * Decompresses [len] bytes at [src]+[off] into [dst] at [dstOff].
     * Returns the number of bytes written.
     */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        var i = off
        val end = off + len
        var o = dstOff
        try {
            while (i < end) {
                val c = src[i++].toInt() and 0xFF
                when {
                    c == 0x00 -> dst[o++] = 0

                    c in 0x01..0x08 -> {
                        // Literal run of c bytes.
                        if (i + c > end) throw IOException("PalmDoc: truncated literal run")
                        repeat(c) { dst[o++] = src[i++] }
                    }

                    c <= 0x7F -> dst[o++] = c.toByte()

                    c <= 0xBF -> {
                        if (i >= end) throw IOException("PalmDoc: truncated back-reference")
                        val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                        val distance = (pair shr 3) and 0x7FF
                        val length = (pair and 7) + 3
                        // Records decompress independently: a back-reference
                        // may not reach into the previous record's output.
                        if (distance == 0 || o - distance < dstOff) {
                            throw IOException("PalmDoc: bad back-reference")
                        }
                        // Byte-by-byte: overlapping copies are intended.
                        repeat(length) {
                            dst[o] = dst[o - distance]
                            o++
                        }
                    }

                    else -> {
                        // 0xC0..0xFF: a space plus the character XOR 0x80.
                        dst[o++] = ' '.code.toByte()
                        dst[o++] = (c xor 0x80).toByte()
                    }
                }
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw IOException("PalmDoc: output overflow", e)
        }
        return o - dstOff
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
        var n = len
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) {
                val size = backwardVarint(data, off, n)
                if (size <= 0 || size > n) break // corrupt: stop trimming
                n -= size
            }
            f = f shr 1
        }
        if (flags and 1 != 0 && n > 0) {
            n -= (data[off + n - 1].toInt() and 0x3) + 1
        }
        return n.coerceAtLeast(0)
    }

    /**
     * The backward-encoded size at the end of the first [endLen] bytes:
     * scan the last ≤4 bytes forward, 7 bits each, resetting whenever the
     * high bit (the terminator of the backward encoding) is seen.
     */
    fun backwardVarint(data: ByteArray, off: Int, endLen: Int): Int {
        var num = 0
        val start = off + endLen - minOf(4, endLen)
        for (i in start until off + endLen) {
            val v = data[i].toInt() and 0xFF
            if (v and 0x80 != 0) num = 0
            num = (num shl 7) or (v and 0x7F)
        }
        return num
    }
}
