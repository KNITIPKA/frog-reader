package com.example.frogreader.data.parser.mobi

import java.io.IOException
import java.nio.charset.Charset

/**
 * Thrown for Mobipocket/Kindle DRM (encryptionType != 0) — the import UI
 * shows a dedicated "copy-protected" message instead of "damaged file".
 */
class MobiDrmException(message: String) : IOException(message)

/** The 16-byte PalmDOC header at the start of record 0. */
internal class PalmDocHeader(record0: ByteArray) {
    val compression: Int
    val textLength: Int
    val textRecordCount: Int
    val recordSize: Int
    val encryptionType: Int

    init {
        if (record0.size < 16) throw IOException("Damaged MOBI: record 0 too small")
        compression = record0.u16(0)
        textLength = record0.index32(4).coerceAtLeast(0)
        textRecordCount = record0.u16(8)
        recordSize = record0.u16(10)
        encryptionType = record0.u16(12)
    }

    companion object {
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_PALMDOC = 2
        const val COMPRESSION_HUFF = 17480
    }
}

/** The MOBI header following the PalmDOC header inside record 0. */
internal class MobiHeader private constructor(
    val headerLength: Int,
    val mobiType: Int,
    val textEncoding: Int,
    val fileVersion: Int,
    val fullName: String?,
    val locale: Int,
    val firstNonBookIndex: Int,
    val firstImageIndex: Int,
    val huffmanRecordOffset: Int,
    val huffmanRecordCount: Int,
    val exthFlags: Int,
    val extraRecordDataFlags: Int,
    val indxRecordOffset: Int,
    /** KF8-only fields (fileVersion >= 8); -1 when absent. */
    val fdstRecord: Int,
    val fdstCount: Int,
    val skelIndex: Int,
    val fragIndex: Int,
) {
    val charset: Charset = charsetFor(textEncoding)

    companion object {
        /** null when record 0 has no "MOBI" magic (plain TEXtREAd PalmDOC). */
        fun parse(record0: ByteArray): MobiHeader? {
            if (!record0.magic(16, "MOBI")) return null
            val headerLength = record0.index32(20)
            if (headerLength < 24 || 16 + headerLength > record0.size + 4096) {
                throw IOException("Damaged MOBI: bad header length $headerLength")
            }

            // Reads relative to the "MOBI" magic at offset 16; fields beyond
            // headerLength (or the record) simply don't exist in old files.
            fun field(rel: Int): Int =
                if (rel + 4 <= headerLength && 16 + rel + 4 <= record0.size) {
                    record0.index32(16 + rel)
                } else {
                    -1
                }

            val textEncoding = field(0x0C)
            val fileVersion = field(0x14)
            val fullNameOffset = field(0x44)
            val fullNameLength = field(0x48)
            val charset = charsetFor(textEncoding)
            val fullName = if (
                fullNameOffset > 0 && fullNameLength in 1..2048 &&
                fullNameOffset + fullNameLength <= record0.size
            ) {
                runCatching {
                    String(record0, fullNameOffset, fullNameLength, charset)
                        .substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
                }.getOrNull()
            } else {
                null
            }

            val extraFlags = if (
                headerLength >= 0xE4 && 16 + 0xE2 + 2 <= record0.size
            ) {
                record0.u16(16 + 0xE2)
            } else {
                0
            }

            return MobiHeader(
                headerLength = headerLength,
                mobiType = field(0x08),
                textEncoding = textEncoding,
                fileVersion = fileVersion,
                fullName = fullName,
                locale = field(0x4C),
                firstNonBookIndex = field(0x40),
                firstImageIndex = field(0x5C),
                huffmanRecordOffset = field(0x60),
                huffmanRecordCount = field(0x64),
                exthFlags = field(0x70),
                extraRecordDataFlags = extraFlags,
                indxRecordOffset = field(0xE4),
                fdstRecord = if (fileVersion >= 8) field(0xB0) else -1,
                fdstCount = if (fileVersion >= 8) field(0xB4) else -1,
                skelIndex = if (fileVersion >= 8) field(0xEC) else -1,
                fragIndex = if (fileVersion >= 8) field(0xF0) else -1,
            )
        }

        /** Windows codepage number → Java charset name. */
        private val CODEPAGES: Map<Int, String> = buildMap {
            for (cp in 1250..1258) put(cp, "windows-$cp") // 1251 = Cyrillic
            put(932, "windows-31j") // Shift-JIS
            put(936, "GBK")
            put(949, "x-windows-949") // Korean UHC
            put(950, "Big5")
            put(10000, "x-MacRoman")
            for (n in 1..16) {
                if (n == 12) continue // ISO-8859-12 was never assigned
                put(28590 + n, "ISO-8859-$n")
            }
        }

        private fun charsetFor(encoding: Int): Charset = when (encoding) {
            65001 -> Charsets.UTF_8
            else -> CODEPAGES[encoding]
                ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
                // Unknown codepage: decodeText's strict-UTF8 → cp1252
                // ladder still catches mislabelled files.
                ?: Charsets.UTF_8
        }
    }
}

/** EXTH metadata block: typed records after the MOBI header. */
internal class Exth(val entries: List<Entry>) {

    class Entry(val type: Int, val data: ByteArray)

    fun string(type: Int, charset: Charset): String? =
        entries.firstOrNull { it.type == type }
            ?.let { runCatching { String(it.data, charset).trim() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    fun int(type: Int): Int? =
        entries.firstOrNull { it.type == type && it.data.size >= 4 }
            ?.let { it.data.index32(0) }
            ?.takeIf { it >= 0 }

    /** Every value of a repeatable record type (authors, subjects). */
    fun strings(type: Int, charset: Charset): List<String> =
        entries.filter { it.type == type }
            .mapNotNull { runCatching { String(it.data, charset).trim() }.getOrNull() }
            .filter { it.isNotEmpty() }

    companion object {
        const val AUTHOR = 100
        const val PUBLISHER = 101
        const val DESCRIPTION = 103
        const val ISBN = 104
        const val SUBJECT = 105
        const val PUBLISH_DATE = 106
        const val CONTRIBUTOR = 108
        const val ASIN = 113
        const val COVER_OFFSET = 201
        const val THUMB_OFFSET = 202
        const val HAS_FAKE_COVER = 203
        const val UPDATED_TITLE = 503
        const val LANGUAGE = 524
        const val KF8_BOUNDARY = 121

        val EMPTY = Exth(emptyList())

        /**
         * Parses the EXTH block at 16 + [mobiHeaderLength], tolerating a few
         * padding bytes. Corruption mid-list keeps the entries read so far.
         */
        fun parse(record0: ByteArray, mobiHeaderLength: Int): Exth {
            var start = -1
            val base = 16 + mobiHeaderLength
            for (probe in base..base + 3) {
                if (record0.magic(probe, "EXTH")) {
                    start = probe
                    break
                }
            }
            if (start < 0 || start + 12 > record0.size) return EMPTY

            val count = record0.index32(start + 8)
            if (count !in 0..4096) return EMPTY
            val entries = mutableListOf<Entry>()
            var p = start + 12
            for (i in 0 until count) {
                if (p + 8 > record0.size) break
                val type = record0.index32(p)
                val recordLength = record0.index32(p + 4)
                if (type < 0 || recordLength < 8 || p + recordLength > record0.size) break
                entries += Entry(type, record0.copyOfRange(p + 8, p + recordLength))
                p += recordLength
            }
            return Exth(entries)
        }
    }
}
