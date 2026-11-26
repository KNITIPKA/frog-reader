package com.example.frogreader.data.parser.mobi

import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
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
        RustMobiWire.requireLibrary()
        val wire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.parsePalmDocHeader(record0),
            "Damaged MOBI: record 0 too small",
        )!!
        val input = RustMobiWire.openReader(wire, PLM_MAGIC, "Invalid PalmDOC header protocol")
        compression = RustMobiWire.readInt(input, "Truncated PalmDOC header")
        textLength = RustMobiWire.readInt(input, "Truncated PalmDOC header")
        textRecordCount = RustMobiWire.readInt(input, "Truncated PalmDOC header")
        recordSize = RustMobiWire.readInt(input, "Truncated PalmDOC header")
        encryptionType = RustMobiWire.readInt(input, "Truncated PalmDOC header")
        if (input.hasRemaining()) throw IOException("Trailing PalmDOC header data")
    }

    companion object {
        private const val PLM_MAGIC = 0x314D4C50 // PLM1
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
    val charset: Charset,
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
    companion object {
        private const val MOB_MAGIC = 0x31424F4D // MOB1

        /** null when record 0 has no "MOBI" magic (plain TEXtREAd PalmDOC). */
        fun parse(record0: ByteArray): MobiHeader? {
            RustMobiWire.requireLibrary()
            val wire = RustMobiWire.unwrapOrThrow(
                RustMobiNative.parseMobiHeader(record0),
                "Damaged MOBI: invalid MOBI header",
            ) ?: return null
            val input = RustMobiWire.openReader(wire, MOB_MAGIC, "Invalid MOBI header protocol")
            val headerLength = RustMobiWire.readInt(input, "Truncated MOBI header")
            val mobiType = RustMobiWire.readInt(input, "Truncated MOBI header")
            val textEncoding = RustMobiWire.readInt(input, "Truncated MOBI header")
            val fileVersion = RustMobiWire.readInt(input, "Truncated MOBI header")
            val fullName = RustMobiWire.readOptionalUtf8String(
                wire,
                input,
                "Truncated MOBI header",
                "Invalid MOBI header name",
            )
            val charsetName = RustMobiWire.readUtf8String(
                wire,
                input,
                "Truncated MOBI header",
                "Invalid MOBI charset",
            )
            val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
            val locale = RustMobiWire.readInt(input, "Truncated MOBI header")
            val firstNonBookIndex = RustMobiWire.readInt(input, "Truncated MOBI header")
            val firstImageIndex = RustMobiWire.readInt(input, "Truncated MOBI header")
            val huffmanRecordOffset = RustMobiWire.readInt(input, "Truncated MOBI header")
            val huffmanRecordCount = RustMobiWire.readInt(input, "Truncated MOBI header")
            val exthFlags = RustMobiWire.readInt(input, "Truncated MOBI header")
            val extraRecordDataFlags = RustMobiWire.readInt(input, "Truncated MOBI header")
            val indxRecordOffset = RustMobiWire.readInt(input, "Truncated MOBI header")
            val fdstRecord = RustMobiWire.readInt(input, "Truncated MOBI header")
            val fdstCount = RustMobiWire.readInt(input, "Truncated MOBI header")
            val skelIndex = RustMobiWire.readInt(input, "Truncated MOBI header")
            val fragIndex = RustMobiWire.readInt(input, "Truncated MOBI header")
            if (input.hasRemaining()) throw IOException("Trailing MOBI header data")

            return MobiHeader(
                headerLength = headerLength,
                mobiType = mobiType,
                textEncoding = textEncoding,
                fileVersion = fileVersion,
                fullName = fullName,
                charset = charset,
                locale = locale,
                firstNonBookIndex = firstNonBookIndex,
                firstImageIndex = firstImageIndex,
                huffmanRecordOffset = huffmanRecordOffset,
                huffmanRecordCount = huffmanRecordCount,
                exthFlags = exthFlags,
                extraRecordDataFlags = extraRecordDataFlags,
                indxRecordOffset = indxRecordOffset,
                fdstRecord = fdstRecord,
                fdstCount = fdstCount,
                skelIndex = skelIndex,
                fragIndex = fragIndex,
            )
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
        private const val EXT_MAGIC = 0x31545845 // EXT1

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
            RustMobiWire.requireLibrary()
            val wire = RustMobiWire.unwrapOrThrow(
                RustMobiNative.parseExth(record0, mobiHeaderLength),
                "Damaged MOBI: invalid EXTH block",
            ) ?: return EMPTY
            val input = RustMobiWire.openReader(wire, EXT_MAGIC, "Invalid EXTH protocol")
            val count = RustMobiWire.readNonNegativeInt(
                input,
                "Truncated EXTH data",
                "Invalid EXTH count",
            )
            if (count == 0) {
                if (input.hasRemaining()) throw IOException("Trailing EXTH data")
                return EMPTY
            }
            if (count > input.remaining() / (2 * Int.SIZE_BYTES)) {
                throw IOException("Invalid EXTH entry count")
            }
            val entries = ArrayList<Entry>(count)
            repeat(count) {
                val type = RustMobiWire.readInt(input, "Truncated EXTH entry type")
                val data = RustMobiWire.readBytes(
                    input,
                    "Truncated EXTH entry data",
                    "Invalid EXTH entry size",
                )
                entries += Entry(type, data)
            }
            if (input.hasRemaining()) throw IOException("Trailing EXTH data")
            return Exth(entries)
        }
    }
}
