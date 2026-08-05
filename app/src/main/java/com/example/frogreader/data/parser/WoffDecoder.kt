package com.example.frogreader.data.parser

import java.util.zip.Inflater

/**
 * WOFF 1.0 → sfnt (TTF/OTF) unpacker, so web-only fonts inside EPUBs still
 * load (Android's font stack reads raw sfnt only). WOFF is just an sfnt with
 * a different directory and per-table zlib compression — `java.util.zip`
 * covers it with no extra dependencies. WOFF2 (Brotli) stays unsupported.
 */
object WoffDecoder {

    private const val HEADER_SIZE = 44
    private const val DIR_ENTRY_SIZE = 20
    private const val MAX_TABLES = 64
    private const val MAX_TABLE_SIZE = 10_000_000L

    fun isWoff(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'w'.code.toByte() && bytes[1] == 'O'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /** Decoded sfnt bytes, or null on any structural problem. */
    fun decode(woff: ByteArray): ByteArray? = runCatching { decodeOrNull(woff) }.getOrNull()

    private fun decodeOrNull(woff: ByteArray): ByteArray? {
        if (!isWoff(woff) || woff.size < HEADER_SIZE) return null
        val flavor = readU32(woff, 4)
        val numTables = readU16(woff, 12)
        if (numTables == 0 || numTables > MAX_TABLES) return null
        if (woff.size < HEADER_SIZE + numTables * DIR_ENTRY_SIZE) return null

        class Table(val tag: Int, val checksum: Int, val data: ByteArray)

        val tables = ArrayList<Table>(numTables)
        for (i in 0 until numTables) {
            val at = HEADER_SIZE + i * DIR_ENTRY_SIZE
            val tag = readU32(woff, at)
            val offset = readU32(woff, at + 4).toLong() and 0xFFFFFFFFL
            val compLength = readU32(woff, at + 8).toLong() and 0xFFFFFFFFL
            val origLength = readU32(woff, at + 12).toLong() and 0xFFFFFFFFL
            val checksum = readU32(woff, at + 16)
            if (origLength > MAX_TABLE_SIZE || compLength > origLength) return null
            if (offset + compLength > woff.size) return null

            val data = if (compLength < origLength) {
                val inflater = Inflater()
                try {
                    inflater.setInput(woff, offset.toInt(), compLength.toInt())
                    val out = ByteArray(origLength.toInt())
                    val produced = inflater.inflate(out)
                    if (produced.toLong() != origLength || !inflater.finished()) return null
                    out
                } finally {
                    inflater.end()
                }
            } else {
                woff.copyOfRange(offset.toInt(), (offset + compLength).toInt())
            }
            tables += Table(tag, checksum, data)
        }

        // sfnt header: searchRange/entrySelector/rangeShift from numTables.
        var entrySelector = 0
        while ((1 shl (entrySelector + 1)) <= numTables) entrySelector++
        val searchRange = 16 * (1 shl entrySelector)
        val rangeShift = numTables * 16 - searchRange

        var total = 12 + numTables * 16
        for (table in tables) total += padded(table.data.size)

        val out = ByteArray(total)
        writeU32(out, 0, flavor)
        writeU16(out, 4, numTables)
        writeU16(out, 6, searchRange)
        writeU16(out, 8, entrySelector)
        writeU16(out, 10, rangeShift)

        var record = 12
        var dataOffset = 12 + numTables * 16
        for (table in tables) {
            writeU32(out, record, table.tag)
            writeU32(out, record + 4, table.checksum)
            writeU32(out, record + 8, dataOffset)
            writeU32(out, record + 12, table.data.size)
            System.arraycopy(table.data, 0, out, dataOffset, table.data.size)
            dataOffset += padded(table.data.size)
            record += 16
        }
        return out
    }

    private fun padded(size: Int): Int = (size + 3) / 4 * 4

    private fun readU16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readU32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
}
