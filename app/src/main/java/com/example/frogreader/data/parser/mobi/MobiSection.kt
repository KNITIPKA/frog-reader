package com.example.frogreader.data.parser.mobi

import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * One logical Mobipocket book inside a PDB. Combo .mobi files carry two:
 * the MOBI6 book from record 0 and a KF8 book starting at the boundary
 * record — each with its own record 0, headers and text records, all
 * indexed relative to [base].
 */
internal class MobiSection(
    val pdb: PdbFile,
    val base: Int,
    private val lastRecordExclusive: Int,
) {
    val record0: ByteArray = pdb.record(
        base,
        pdb.limits.maxMobiIndexRecordBytes,
        "MOBI section header",
    )
    val palmDoc = PalmDocHeader(record0)
    val mobi: MobiHeader? = MobiHeader.parse(record0)
    val exth: Exth = mobi?.let { Exth.parse(record0, it.headerLength) } ?: Exth.EMPTY

    val recordCount: Int get() = lastRecordExclusive - base

    fun record(
        i: Int,
        maxBytes: Long = pdb.limits.maxMobiIndexRecordBytes,
        label: String = "MOBI section record $i",
    ): ByteArray {
        if (i !in 0 until recordCount) throw IOException("Damaged MOBI: record $i out of section")
        return pdb.record(base + i, maxBytes, label)
    }

    fun recordPrefix(i: Int, bytes: Int): ByteArray? {
        if (i !in 0 until recordCount) return null
        return pdb.recordPrefix(base + i, bytes, optional = true)
    }

    fun hasRecord(i: Int): Boolean = i in 0 until recordCount

    /**
     * Decompresses text records 1..textRecordCount into one exact-size
     * array in a single pass. The buffer grows only when the header lies
     * about textLength.
     */
    fun assembleText(): ByteArray {
        val flags = mobi?.extraRecordDataFlags ?: 0
        val maxTextBytes = pdb.limits.maxMobiTextBytes.toInt()
        if (palmDoc.textLength > maxTextBytes) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_SIZE,
                "MOBI text declares ${palmDoc.textLength} bytes; limit is $maxTextBytes",
            )
        }
        val huff = when (palmDoc.compression) {
            PalmDocHeader.COMPRESSION_NONE, PalmDocHeader.COMPRESSION_PALMDOC -> null
            PalmDocHeader.COMPRESSION_HUFF -> {
                val huffBase = mobi?.huffmanRecordOffset ?: -1
                val huffCount = mobi?.huffmanRecordCount ?: 0
                if (huffBase < 0 || huffCount < 1 || !hasRecord(huffBase)) {
                    throw IOException("Damaged MOBI: missing HUFF records")
                }
                HuffCdicDecoder(
                    record(huffBase),
                    Iterable {
                        object : Iterator<ByteArray> {
                            private var index = 1
                            override fun hasNext(): Boolean = index < huffCount
                            override fun next(): ByteArray = record(huffBase + index++)
                        }
                    },
                    maxTextBytes,
                    pdb.limits.maxMobiHuffDictionaryBytes.toInt(),
                )
            }
            else -> throw IOException("Unknown MOBI compression ${palmDoc.compression}")
        }

        val out = BoundedByteArrayBuilder(
            maxTextBytes,
            palmDoc.textLength.coerceAtMost(INITIAL_TEXT_ALLOCATION),
        )
        for (r in 1..palmDoc.textRecordCount) {
            if (!hasRecord(r)) break
            pdb.withRecord(base + r) { data, off, len ->
                val contentLen = TrailingEntries.contentLength(data, off, len, flags)
                val remaining = maxTextBytes - out.size
                when (palmDoc.compression) {
                    PalmDocHeader.COMPRESSION_NONE -> out.write(data, off, contentLen)
                    PalmDocHeader.COMPRESSION_PALMDOC -> {
                        // PalmDOC's worst token is a 2-byte back-reference
                        // producing 10 bytes. A per-record buffer therefore
                        // needs at most 5x compressed input and never the
                        // whole-book ceiling up front.
                        val worst = minOf(
                            remaining.toLong(),
                            contentLen.toLong() * PALMDOC_MAX_EXPANSION,
                        ).toInt()
                        val decoded = ByteArray(worst)
                        val count = try {
                            PalmDocDecoder.decompress(data, off, contentLen, decoded, 0)
                        } catch (error: IOException) {
                            if (error.message?.contains("output overflow") == true) {
                                throw textLimit(maxTextBytes)
                            }
                            throw error
                        }
                        out.write(decoded, 0, count)
                    }
                    PalmDocHeader.COMPRESSION_HUFF -> {
                        val decoded = huff!!.decompressBounded(
                            data,
                            off,
                            contentLen,
                            remaining,
                        )
                        out.write(decoded)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private fun textLimit(maxBytes: Int) = ResourceLimitException(
        ResourceLimitKind.ENTRY_SIZE,
        "MOBI text expands beyond $maxBytes bytes",
    )

    /**
     * Resolves a 1-based resource number (recindex / kindle:embed /
     * coverOffset+1) to an absolute PDB record index whose bytes look like
     * a real resource. Combo files disagree about whether firstImageIndex
     * is section-relative or absolute — the magic-byte sniff arbitrates.
     */
    fun resourceRecord(n: Int): Int? {
        if (n < 1) return null
        val first = mobi?.firstImageIndex ?: -1
        if (first < 0) return null
        val candidates = intArrayOf(
            base + first + n - 1, // section-relative
            first + n - 1, // absolute in the PDB
        )
        for (candidate in candidates) {
            if (candidate !in 0 until pdb.recordCount) continue
            val prefix = pdb.recordPrefix(
                candidate,
                RESOURCE_SNIFF_BYTES,
                "MOBI resource signature",
                optional = true,
            ) ?: continue
            val looks = looksLikeResource(prefix, 0, prefix.size)
            if (looks) return candidate
        }
        return null
    }

    companion object {
        private const val INITIAL_TEXT_ALLOCATION = 1024 * 1024
        private const val PALMDOC_MAX_EXPANSION = 5L
        private const val RESOURCE_SNIFF_BYTES = 2_048

        /** JPEG/PNG/GIF/BMP/SVG signatures — a plausible image record. */
        fun looksLikeImage(data: ByteArray, off: Int, len: Int): Boolean {
            if (len < 5) return false
            val b0 = data[off].toInt() and 0xFF
            val b1 = data[off + 1].toInt() and 0xFF
            return (b0 == 0xFF && b1 == 0xD8) || // JPEG
                (b0 == 0x89 && b1 == 'P'.code) || // PNG
                (b0 == 'G'.code && b1 == 'I'.code) || // GIF
                (b0 == 'B'.code && b1 == 'M'.code) || // BMP
                looksLikeSvg(data, off, len)
        }

        private fun looksLikeSvg(data: ByteArray, off: Int, len: Int): Boolean {
            val end = minOf(data.size, off + len, off + 2048)
            var first = off
            if (first + 2 < end && data[first] == 0xEF.toByte() &&
                data[first + 1] == 0xBB.toByte() && data[first + 2] == 0xBF.toByte()
            ) {
                first += 3
            }
            while (first < end && (data[first].toInt() and 0xFF).toChar().isWhitespace()) first++
            if (first >= end || data[first] != '<'.code.toByte()) return false
            for (i in first until end - 3) {
                if (data[i] != '<'.code.toByte()) continue
                if (data[i + 1].toInt().toChar().equals('s', ignoreCase = true) &&
                    data[i + 2].toInt().toChar().equals('v', ignoreCase = true) &&
                    data[i + 3].toInt().toChar().equals('g', ignoreCase = true)
                ) {
                    val next = data.getOrNull(i + 4)?.toInt()?.and(0xFF)?.toChar()
                    if (next == null || next.isWhitespace() || next == '>' || next == '/') return true
                }
            }
            return false
        }

        /** An image or an embedded-font record — a plausible resource. */
        fun looksLikeResource(data: ByteArray, off: Int, len: Int): Boolean =
            looksLikeImage(data, off, len) || MobiFontRecord.isFontRecord(data, off, len)

        /** File extension for an extracted resource, from its magic. */
        fun resourceExtension(bytes: ByteArray): String = when {
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 -> "jpg"
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x89 -> "png"
            bytes.size >= 2 && bytes[0] == 'G'.code.toByte() -> "gif"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() &&
                bytes[1] == 'M'.code.toByte() -> "bmp"
            looksLikeSvg(bytes, 0, bytes.size) -> "svg"
            else -> "img"
        }
    }
}

/** A growable byte accumulator whose backing array never exceeds [maxBytes]. */
private class BoundedByteArrayBuilder(
    private val maxBytes: Int,
    initialCapacity: Int,
) {
    private var buffer = ByteArray(initialCapacity.coerceIn(0, maxBytes))
    var size: Int = 0
        private set

    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length < 0 || offset < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length > maxBytes - size) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_SIZE,
                "MOBI text expands beyond $maxBytes bytes",
            )
        }
        val needed = size + length
        if (needed > buffer.size) {
            var capacity = minOf(maxBytes, maxOf(8 * 1024, buffer.size))
            while (capacity < needed) {
                capacity = minOf(maxBytes, maxOf(needed, capacity + capacity / 2))
            }
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(bytes, offset, buffer, size, length)
        size = needed
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** The whole file: the MOBI6 section plus the KF8 section when present. */
internal class MobiDoc(
    val pdb: PdbFile,
    val mobi6: MobiSection,
    val kf8: MobiSection?,
) : Closeable {
    /** True when the file is KF8-only (.azw3) — no MOBI6 to fall back to. */
    val kf8Only: Boolean get() = kf8 === mobi6

    override fun close() = pdb.close()

    companion object {
        fun open(file: File): MobiDoc = open(file, ReaderResourceLimits.DEFAULT)

        fun open(file: File, limits: ReaderResourceLimits): MobiDoc {
            val source = FilePdbSource(file)
            val pdb = try {
                PdbFile(source, limits)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
            return open(pdb)
        }

        fun open(bytes: ByteArray): MobiDoc = open(PdbFile(bytes))

        fun open(bytes: ByteArray, limits: ReaderResourceLimits): MobiDoc =
            open(PdbFile(bytes, limits))

        /** Takes ownership of [pdb]: closes it when opening fails. */
        private fun open(pdb: PdbFile): MobiDoc = try {
            openOrThrow(pdb)
        } catch (e: Throwable) {
            pdb.close()
            throw e
        }

        private fun openOrThrow(pdb: PdbFile): MobiDoc {
            if (!pdb.isBook) throw IOException("Not a MOBI file")
            val first = MobiSection(pdb, 0, pdb.recordCount)
            if (first.palmDoc.encryptionType != 0) {
                throw MobiDrmException(
                    "This book is DRM-protected (encryption ${first.palmDoc.encryptionType})",
                )
            }
            // Pure KF8 (.azw3): the very first header is already version 8.
            if ((first.mobi?.fileVersion ?: 0) >= 8) {
                return MobiDoc(pdb, first, first)
            }
            // Combo: EXTH 121 points at (or one before) the KF8 record 0.
            val boundary = first.exth.int(Exth.KF8_BOUNDARY)
            var kf8: MobiSection? = null
            if (boundary != null) {
                for (candidate in intArrayOf(boundary, boundary + 1)) {
                    if (candidate !in 1 until pdb.recordCount) continue
                    val prefix = pdb.recordPrefix(
                        candidate,
                        20,
                        "KF8 boundary header",
                        optional = true,
                    ) ?: continue
                    if (!prefix.magic(16, "MOBI")) continue
                    kf8 = runCatching { MobiSection(pdb, candidate, pdb.recordCount) }
                        .getOrNull()
                        ?.takeIf {
                            (it.mobi?.fileVersion ?: 0) >= 8 &&
                                it.palmDoc.encryptionType == 0
                        }
                    if (kf8 != null) break
                }
            }
            return MobiDoc(pdb, first, kf8)
        }
    }
}
