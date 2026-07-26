package com.example.frogreader.data.parser.mobi

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
    val record0: ByteArray = pdb.record(base)
    val palmDoc = PalmDocHeader(record0)
    val mobi: MobiHeader? = MobiHeader.parse(record0)
    val exth: Exth = mobi?.let { Exth.parse(record0, it.headerLength) } ?: Exth.EMPTY

    val recordCount: Int get() = lastRecordExclusive - base

    fun record(i: Int): ByteArray {
        if (i !in 0 until recordCount) throw IOException("Damaged MOBI: record $i out of section")
        return pdb.record(base + i)
    }

    fun hasRecord(i: Int): Boolean = i in 0 until recordCount

    /**
     * Decompresses text records 1..textRecordCount into one exact-size
     * array in a single pass. The buffer grows only when the header lies
     * about textLength.
     */
    fun assembleText(): ByteArray {
        val flags = mobi?.extraRecordDataFlags ?: 0
        val decoder: (ByteArray, Int, Int, ByteArray, Int) -> Int = when (palmDoc.compression) {
            PalmDocHeader.COMPRESSION_NONE -> { src, off, len, dst, dstOff ->
                System.arraycopy(src, off, dst, dstOff, len)
                len
            }

            PalmDocHeader.COMPRESSION_PALMDOC -> PalmDocDecoder::decompress

            PalmDocHeader.COMPRESSION_HUFF -> {
                val huffBase = mobi?.huffmanRecordOffset ?: -1
                val huffCount = mobi?.huffmanRecordCount ?: 0
                if (huffBase < 0 || huffCount < 1 || !hasRecord(huffBase)) {
                    throw IOException("Damaged MOBI: missing HUFF records")
                }
                val huff = HuffCdicDecoder(
                    record(huffBase),
                    (1 until huffCount).map { record(huffBase + it) },
                )
                huff::decompress
            }

            else -> throw IOException("Unknown MOBI compression ${palmDoc.compression}")
        }

        var out = ByteArray(palmDoc.textLength.coerceAtMost(MAX_TEXT_BYTES))
        var outPos = 0
        for (r in 1..palmDoc.textRecordCount) {
            if (!hasRecord(r)) break
            pdb.withRecord(base + r) { data, off, len ->
                val contentLen = TrailingEntries.contentLength(data, off, len, flags)
                // Worst case a record expands to recordSize (4096) + slack.
                val needed = outPos + palmDoc.recordSize.coerceAtLeast(4096) * 2
                if (needed > out.size) {
                    out = out.copyOf(maxOf(needed, out.size * 2).coerceAtMost(MAX_TEXT_BYTES))
                }
                outPos += decoder(data, off, contentLen, out, outPos)
            }
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

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
            val looks = pdb.withRecord(candidate) { data, off, len ->
                looksLikeResource(data, off, len)
            }
            if (looks) return candidate
        }
        return null
    }

    companion object {
        /** Hard cap so a lying header cannot allocate gigabytes. */
        const val MAX_TEXT_BYTES = 256 * 1024 * 1024

        /** JPEG/PNG/GIF/BMP magic bytes — a plausible image record. */
        fun looksLikeImage(data: ByteArray, off: Int, len: Int): Boolean {
            if (len < 8) return false
            val b0 = data[off].toInt() and 0xFF
            val b1 = data[off + 1].toInt() and 0xFF
            return (b0 == 0xFF && b1 == 0xD8) || // JPEG
                (b0 == 0x89 && b1 == 'P'.code) || // PNG
                (b0 == 'G'.code && b1 == 'I'.code) || // GIF
                (b0 == 'B'.code && b1 == 'M'.code) // BMP
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
            else -> "img"
        }
    }
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
        fun open(file: File): MobiDoc {
            val source = FilePdbSource(file)
            val pdb = try {
                PdbFile(source)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
            return open(pdb)
        }

        fun open(bytes: ByteArray): MobiDoc = open(PdbFile(bytes))

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
                    if (!pdb.record(candidate).magic(16, "MOBI")) continue
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
