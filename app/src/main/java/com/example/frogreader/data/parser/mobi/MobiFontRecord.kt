package com.example.frogreader.data.parser.mobi

import java.util.zip.Inflater

/**
 * Kindle embedded-font resource record ("FONT" magic): the payload is a
 * raw sfnt, optionally XOR-obfuscated over its first 1040 bytes and/or
 * zlib-compressed (KindleUnpack layout: u32 usize@+4, flags@+8,
 * dataStart@+12, xorLen@+16, xorStart@+20).
 */
internal object MobiFontRecord {

    private const val HEADER = 24
    private const val XOR_SPAN = 1040
    private const val FLAG_ZLIB = 0x1
    private const val FLAG_OBFUSCATED = 0x2
    private const val MAX_FONT_BYTES = 32 * 1024 * 1024

    fun isFontRecord(data: ByteArray, off: Int, len: Int): Boolean =
        len >= HEADER && data.magic(off, "FONT")

    /** Decoded sfnt bytes, or null on structural damage. */
    fun decode(data: ByteArray, off: Int, len: Int): ByteArray? =
        runCatching { decodeOrNull(data, off, len) }.getOrNull()

    private fun decodeOrNull(data: ByteArray, off: Int, len: Int): ByteArray? {
        if (!isFontRecord(data, off, len)) return null
        val usize = data.u32(off + 4)
        val flags = data.u32(off + 8).toInt()
        val dataStart = data.index32(off + 12)
        val xorLen = data.index32(off + 16)
        val xorStart = data.index32(off + 20)
        if (dataStart < HEADER || dataStart > len) return null
        if (usize > MAX_FONT_BYTES.toLong()) return null

        var font = data.copyOfRange(off + dataStart, off + len)
        if (flags and FLAG_OBFUSCATED != 0) {
            if (xorLen <= 0 || xorStart < 0 || xorStart + xorLen > len) return null
            for (i in 0 until minOf(XOR_SPAN, font.size)) {
                font[i] = (font[i].toInt() xor data[off + xorStart + i % xorLen].toInt()).toByte()
            }
        }
        if (flags and FLAG_ZLIB != 0) {
            val inflater = Inflater()
            try {
                inflater.setInput(font)
                var out = ByteArray(usize.toInt().coerceIn(1024, MAX_FONT_BYTES))
                var pos = 0
                while (!inflater.finished()) {
                    if (pos == out.size) {
                        if (out.size >= MAX_FONT_BYTES) return null
                        out = out.copyOf((out.size * 2).coerceAtMost(MAX_FONT_BYTES))
                    }
                    val n = inflater.inflate(out, pos, out.size - pos)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    pos += n
                }
                font = out.copyOf(pos)
            } finally {
                inflater.end()
            }
        }
        return font
    }
}
