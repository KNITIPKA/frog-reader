package com.example.frogreader.data.parser

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * WOFF 2.0 → sfnt (TTF/OTF) unpacker (W3C WOFF2 spec): table directory with
 * known-tag indexes, one Brotli stream for all table data, and reconstruction
 * of the transformed `glyf`/`loca` (triplet-encoded points) and `hmtx`
 * (omitted lsb) tables. Collections (`ttcf` flavor) are not supported and
 * decode to null, like any structural damage — a broken face is skipped, it
 * can never crash text layout.
 */
object Woff2Decoder {

    private const val MAX_TABLES = 512
    private const val MAX_TABLE_SIZE = 32 * 1024 * 1024
    private const val DEFAULT_MAX_DECODED_BYTES = 32 * 1024 * 1024
    private const val TTCF = 0x74746366L

    /** The spec's known-table-tags array; directory entries index into it. */
    private val KNOWN_TAGS = arrayOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
        "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
        "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea",
        "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
        "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar",
        "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
        "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
        "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    )

    fun isWoff2(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'w'.code.toByte() && bytes[1] == 'O'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '2'.code.toByte()

    /** Decoded sfnt bytes, or null on anything unsupported or damaged. */
    fun decode(
        woff2: ByteArray,
        maxDecodedBytes: Int = DEFAULT_MAX_DECODED_BYTES,
    ): ByteArray? = runCatching {
        if (maxDecodedBytes <= 0) null else decodeOrNull(woff2, maxDecodedBytes)
    }.getOrNull()

    // ------------------------------------------------------------ pipeline

    private class Entry(
        val tag: String,
        val origLength: Int,
        val transformed: Boolean,
        /** Bytes taken from the decompressed stream (transformLength when transformed). */
        val dataLength: Int,
    ) {
        var data: ByteArray = ByteArray(0)
    }

    private fun decodeOrNull(woff2: ByteArray, maxDecodedBytes: Int): ByteArray? {
        if (!isWoff2(woff2) || woff2.size < 48) return null
        val header = Reader(woff2, 4)
        val flavor = header.u32()
        if (flavor == TTCF) return null // collections: out of scope, skip the face
        header.u32() // file length
        val numTables = header.u16()
        header.u16() // reserved
        header.u32() // totalSfntSize (hint only)
        val totalCompressedSizeLong = header.u32()
        if (totalCompressedSizeLong > Int.MAX_VALUE) return null
        val totalCompressedSize = totalCompressedSizeLong.toInt()
        if (numTables == 0 || numTables > MAX_TABLES) return null

        // ---- table directory (starts at 48)
        val reader = Reader(woff2, 48)
        val entries = ArrayList<Entry>(numTables)
        var totalUncompressed = 0L
        var estimatedSfnt = (12 + numTables * 16).toLong()
        repeat(numTables) {
            val flags = reader.u8()
            val tagIndex = flags and 0x3F
            val tag = if (tagIndex == 63) reader.tag4() else KNOWN_TAGS[tagIndex]
            val version = (flags ushr 6) and 0x3
            val glyfLoca = tag == "glyf" || tag == "loca"
            val transformed = if (glyfLoca) version == 0 else version != 0
            if (transformed && !glyfLoca && !(tag == "hmtx" && version == 1)) return null
            if (glyfLoca && !transformed && version != 3) return null
            val origLength = reader.uintBase128()
            val dataLength = if (transformed) reader.uintBase128() else origLength
            if (origLength > MAX_TABLE_SIZE || dataLength > MAX_TABLE_SIZE) return null
            totalUncompressed += dataLength
            val paddedOrig = (origLength.toLong() + 3L) / 4L * 4L
            if (paddedOrig > maxDecodedBytes - estimatedSfnt) return null
            estimatedSfnt += paddedOrig
            entries += Entry(tag, origLength, transformed, dataLength)
        }
        if (totalUncompressed > maxDecodedBytes) return null

        // ---- one Brotli stream covers every table's (transformed) data
        if (reader.pos + totalCompressedSize > woff2.size) return null
        val uncompressed = ByteArray(totalUncompressed.toInt())
        BrotliInputStream(
            ByteArrayInputStream(woff2, reader.pos, totalCompressedSize),
        ).use { stream ->
            var off = 0
            while (off < uncompressed.size) {
                val n = stream.read(uncompressed, off, uncompressed.size - off)
                if (n <= 0) return null
                off += n
            }
            // The directory's transformed lengths must describe the complete
            // Brotli stream, not merely a prefix of it.
            if (stream.read() != -1) return null
        }
        var slice = 0
        for (entry in entries) {
            entry.data = uncompressed.copyOfRange(slice, slice + entry.dataLength)
            slice += entry.dataLength
        }

        // ---- reconstruct transformed tables
        val glyfEntry = entries.firstOrNull { it.tag == "glyf" }
        val locaEntry = entries.firstOrNull { it.tag == "loca" }
        var xMins: IntArray? = null
        var numGlyphs = 0
        if (glyfEntry?.transformed == true || locaEntry?.transformed == true) {
            if (glyfEntry?.transformed != true || locaEntry?.transformed != true) return null
            if (locaEntry.dataLength != 0) return null
            val result = reconstructGlyf(glyfEntry.data, maxDecodedBytes) ?: return null
            glyfEntry.data = result.glyf
            locaEntry.data = result.loca
            xMins = result.xMins
            numGlyphs = result.numGlyphs
            entries.firstOrNull { it.tag == "head" }?.let { head ->
                if (head.data.size >= 52) {
                    head.data[50] = 0
                    head.data[51] = result.indexFormat.toByte()
                }
            }
        }
        entries.firstOrNull { it.tag == "hmtx" }?.let { hmtx ->
            if (!hmtx.transformed) return@let
            val mins = xMins ?: return null // hmtx transform needs glyf's xMins
            val hhea = entries.firstOrNull { it.tag == "hhea" && !it.transformed } ?: return null
            if (hhea.data.size < 36) return null
            val numHMetrics = ((hhea.data[34].toInt() and 0xFF) shl 8) or
                (hhea.data[35].toInt() and 0xFF)
            hmtx.data = reconstructHmtx(hmtx.data, numGlyphs, numHMetrics, mins) ?: return null
        }

        return assembleSfnt(flavor, entries, maxDecodedBytes)
    }

    // ------------------------------------------------------------ glyf/loca

    private class GlyfResult(
        val glyf: ByteArray,
        val loca: ByteArray,
        val xMins: IntArray,
        val numGlyphs: Int,
        val indexFormat: Int,
    )

    private fun reconstructGlyf(data: ByteArray, maxDecodedBytes: Int): GlyfResult? {
        val header = Reader(data)
        if (data.size < 36) return null
        header.u32() // version (reserved)
        val numGlyphs = header.u16()
        val indexFormat = header.u16()
        val sizes = IntArray(7) {
            val size = header.u32()
            if (size > data.size) return null
            size.toInt()
        }
        var off = header.pos
        fun substream(size: Int): Reader {
            if (off + size > data.size) throw IndexOutOfBoundsException("substream")
            val sub = Reader(data, off, off + size)
            off += size
            return sub
        }
        val nContourStream = substream(sizes[0])
        val nPointsStream = substream(sizes[1])
        val flagStream = substream(sizes[2])
        val glyphStream = substream(sizes[3])
        val compositeStream = substream(sizes[4])
        val bboxStream = substream(sizes[5])
        val instructionStream = substream(sizes[6])

        // The bbox stream opens with a bitmap: one bit per glyph, MSB-first.
        val bitmapSize = (numGlyphs + 31) / 32 * 4
        if (bboxStream.remaining < bitmapSize) return null
        val bboxBitmap = bboxStream.bytes(bitmapSize)
        fun hasBbox(i: Int) = bboxBitmap[i / 8].toInt() and (0x80 ushr (i % 8)) != 0

        val out = LimitedByteArrayOutputStream(maxDecodedBytes)
        val locaOffsets = IntArray(numGlyphs + 1)
        val xMins = IntArray(numGlyphs)
        for (glyph in 0 until numGlyphs) {
            locaOffsets[glyph] = out.size()
            when (val nContours = nContourStream.i16()) {
                0 -> {
                    // Empty glyph: no data; an explicit bbox would be invalid.
                    if (hasBbox(glyph)) return null
                }

                -1 -> { // composite
                    val components = ByteArrayOutputStream()
                    var haveInstructions = false
                    while (true) {
                        val flags = compositeStream.u16()
                        components.u16be(flags)
                        components.u16be(compositeStream.u16()) // glyph index
                        var extra = if (flags and 0x0001 != 0) 4 else 2 // args
                        extra += when {
                            flags and 0x0008 != 0 -> 2 // WE_HAVE_A_SCALE
                            flags and 0x0040 != 0 -> 4 // X_AND_Y_SCALE
                            flags and 0x0080 != 0 -> 8 // TWO_BY_TWO
                            else -> 0
                        }
                        components.write(compositeStream.bytes(extra))
                        if (flags and 0x0100 != 0) haveInstructions = true
                        if (flags and 0x0020 == 0) break // MORE_COMPONENTS
                    }
                    if (!hasBbox(glyph)) return null // composite bbox is always explicit
                    val bbox = IntArray(4) { bboxStream.i16() }
                    xMins[glyph] = bbox[0]
                    out.i16be(-1)
                    for (v in bbox) out.i16be(v)
                    out.write(components.toByteArray())
                    if (haveInstructions) {
                        val instructionLength = glyphStream.read255UShort()
                        out.u16be(instructionLength)
                        out.write(instructionStream.bytes(instructionLength))
                    }
                }

                else -> { // simple glyph
                    if (nContours < 0 || nContours > 0xFFFF) return null
                    val pointsPerContour = IntArray(nContours) { nPointsStream.read255UShort() }
                    val totalPoints = pointsPerContour.sum()
                    if (totalPoints == 0 || totalPoints > 0xFFFF) return null
                    val xs = IntArray(totalPoints)
                    val ys = IntArray(totalPoints)
                    val onCurve = BooleanArray(totalPoints)
                    var x = 0
                    var y = 0
                    for (p in 0 until totalPoints) {
                        val flag = flagStream.u8()
                        onCurve[p] = flag and 0x80 == 0
                        val delta = tripletDecode(flag and 0x7F, glyphStream)
                        x += delta.first
                        y += delta.second
                        xs[p] = x
                        ys[p] = y
                    }
                    val instructionLength = glyphStream.read255UShort()
                    val instructions = instructionStream.bytes(instructionLength)
                    val bbox = if (hasBbox(glyph)) {
                        IntArray(4) { bboxStream.i16() }
                    } else {
                        intArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                    }
                    xMins[glyph] = bbox[0]
                    writeSimpleGlyph(out, nContours, pointsPerContour, xs, ys, onCurve, bbox, instructions)
                }
            }
            if (out.size() % 2 != 0) out.write(0) // glyphs stay short-aligned
        }
        locaOffsets[numGlyphs] = out.size()

        val loca = ByteArrayOutputStream()
        if (indexFormat == 0) {
            for (offset in locaOffsets) {
                if (offset / 2 > 0xFFFF) return null
                loca.u16be(offset / 2)
            }
        } else {
            for (offset in locaOffsets) loca.u32be(offset)
        }
        return GlyfResult(out.toByteArray(), loca.toByteArray(), xMins, numGlyphs, indexFormat)
    }

    /** dx/dy for one point: the spec's 128-row triplet table, algorithmically. */
    private fun tripletDecode(flag: Int, data: Reader): Pair<Int, Int> {
        fun withSign(signFlag: Int, value: Int): Int =
            if (signFlag and 1 != 0) value else -value
        return when {
            flag < 10 -> {
                val b0 = data.u8()
                0 to withSign(flag, ((flag and 14) shl 7) + b0)
            }

            flag < 20 -> {
                val b0 = data.u8()
                withSign(flag, (((flag - 10) and 14) shl 7) + b0) to 0
            }

            flag < 84 -> {
                val base = flag - 20
                val b1 = data.u8()
                withSign(flag, 1 + (base and 0x30) + (b1 shr 4)) to
                    withSign(flag shr 1, 1 + ((base and 0x0C) shl 2) + (b1 and 0x0F))
            }

            flag < 120 -> {
                val base = flag - 84
                val b1 = data.u8()
                val b2 = data.u8()
                withSign(flag, 1 + (base / 12 shl 8) + b1) to
                    withSign(flag shr 1, 1 + (base % 12 shr 2 shl 8) + b2)
            }

            flag < 124 -> {
                val b1 = data.u8()
                val b2 = data.u8()
                val b3 = data.u8()
                withSign(flag, (b1 shl 4) + (b2 shr 4)) to
                    withSign(flag shr 1, ((b2 and 0x0F) shl 8) + b3)
            }

            else -> {
                val b1 = data.u8()
                val b2 = data.u8()
                val b3 = data.u8()
                val b4 = data.u8()
                withSign(flag, (b1 shl 8) + b2) to withSign(flag shr 1, (b3 shl 8) + b4)
            }
        }
    }

    private fun writeSimpleGlyph(
        out: ByteArrayOutputStream,
        nContours: Int,
        pointsPerContour: IntArray,
        xs: IntArray,
        ys: IntArray,
        onCurve: BooleanArray,
        bbox: IntArray,
        instructions: ByteArray,
    ) {
        out.i16be(nContours)
        for (v in bbox) out.i16be(v)
        var end = -1
        for (count in pointsPerContour) {
            end += count
            out.u16be(end)
        }
        out.u16be(instructions.size)
        out.write(instructions)

        // Flags without repeat-compression (valid, just a few bytes larger),
        // coordinates in SHORT form whenever the delta fits a byte.
        val flags = ByteArrayOutputStream()
        val xData = ByteArrayOutputStream()
        val yData = ByteArrayOutputStream()
        var px = 0
        var py = 0
        for (p in xs.indices) {
            val dx = xs[p] - px
            val dy = ys[p] - py
            px = xs[p]
            py = ys[p]
            var flag = if (onCurve[p]) 0x01 else 0x00
            when {
                dx == 0 -> flag = flag or 0x10 // X_IS_SAME
                dx in -255..255 -> {
                    flag = flag or 0x02 or (if (dx > 0) 0x10 else 0) // X_SHORT
                    xData.write(if (dx > 0) dx else -dx)
                }

                else -> xData.i16be(dx)
            }
            when {
                dy == 0 -> flag = flag or 0x20 // Y_IS_SAME
                dy in -255..255 -> {
                    flag = flag or 0x04 or (if (dy > 0) 0x20 else 0) // Y_SHORT
                    yData.write(if (dy > 0) dy else -dy)
                }

                else -> yData.i16be(dy)
            }
            flags.write(flag)
        }
        out.write(flags.toByteArray())
        out.write(xData.toByteArray())
        out.write(yData.toByteArray())
    }

    // ------------------------------------------------------------ hmtx

    private fun reconstructHmtx(
        data: ByteArray,
        numGlyphs: Int,
        numHMetrics: Int,
        xMins: IntArray,
    ): ByteArray? {
        if (numHMetrics < 1 || numHMetrics > numGlyphs || numGlyphs > xMins.size) return null
        val reader = Reader(data)
        val flags = reader.u8()
        val proportionalOmitted = flags and 0x1 != 0
        val monospaceOmitted = flags and 0x2 != 0
        val advances = IntArray(numHMetrics) { reader.u16() }
        val lsbs = IntArray(numHMetrics) { i -> if (proportionalOmitted) xMins[i] else reader.i16() }
        val extra = IntArray(numGlyphs - numHMetrics) { i ->
            if (monospaceOmitted) xMins[numHMetrics + i] else reader.i16()
        }
        val out = ByteArrayOutputStream()
        for (i in 0 until numHMetrics) {
            out.u16be(advances[i])
            out.i16be(lsbs[i])
        }
        for (v in extra) out.i16be(v)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ sfnt

    private fun assembleSfnt(
        flavor: Long,
        entries: List<Entry>,
        maxDecodedBytes: Int,
    ): ByteArray? {
        val sorted = entries.sortedBy { it.tag } // sfnt directories are tag-sorted
        val numTables = sorted.size

        // head checksums are computed with checkSumAdjustment zeroed; Android
        // never verifies the whole-font adjustment, so it stays zero.
        sorted.firstOrNull { it.tag == "head" }?.let { head ->
            if (head.data.size >= 12) {
                for (i in 8..11) head.data[i] = 0
            }
        }

        var entrySelector = 0
        while ((1 shl (entrySelector + 1)) <= numTables) entrySelector++
        val searchRange = 16 * (1 shl entrySelector)
        val rangeShift = numTables * 16 - searchRange

        var total = (12 + numTables * 16).toLong()
        for (entry in sorted) {
            val padded = padded(entry.data.size).toLong()
            if (padded > maxDecodedBytes - total) return null
            total += padded
        }

        val out = ByteArray(total.toInt())
        writeU32(out, 0, flavor)
        writeU16(out, 4, numTables)
        writeU16(out, 6, searchRange)
        writeU16(out, 8, entrySelector)
        writeU16(out, 10, rangeShift)

        var record = 12
        var dataOffset = 12 + numTables * 16
        for (entry in sorted) {
            System.arraycopy(entry.data, 0, out, dataOffset, entry.data.size)
            writeTag(out, record, entry.tag)
            writeU32(out, record + 4, checksum(out, dataOffset, padded(entry.data.size)))
            writeU32(out, record + 8, dataOffset.toLong())
            writeU32(out, record + 12, entry.data.size.toLong())
            dataOffset += padded(entry.data.size)
            record += 16
        }
        return out
    }

    /** ByteArrayOutputStream that fails before its growth allocation. */
    private class LimitedByteArrayOutputStream(
        private val maxBytes: Int,
    ) : ByteArrayOutputStream(minOf(maxBytes, 32)) {
        override fun write(value: Int) {
            ensureBoundedCapacity(count + 1)
            buf[count++] = value.toByte()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length < 0 || offset < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            ensureBoundedCapacity(count + length)
            System.arraycopy(bytes, offset, buf, count, length)
            count += length
        }

        private fun ensureBoundedCapacity(required: Int) {
            if (required < 0 || required > maxBytes) {
                throw IllegalStateException("WOFF2 output limit")
            }
            if (required <= buf.size) return
            val grown = maxOf(required, buf.size + buf.size / 2 + 1).coerceAtMost(maxBytes)
            buf = buf.copyOf(grown)
        }
    }

    private fun checksum(bytes: ByteArray, offset: Int, paddedLength: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + paddedLength
        while (i < end) {
            sum = (sum + (bytes.u32be(i))) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun padded(size: Int): Int = (size + 3) / 4 * 4

    // ------------------------------------------------------------ io helpers

    private class Reader(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        val remaining: Int get() = end - pos

        fun u8(): Int {
            if (pos >= end) throw IndexOutOfBoundsException("u8")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun i16(): Int = u16().toShort().toInt()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > end) throw IndexOutOfBoundsException("bytes($n)")
            val result = bytes.copyOfRange(pos, pos + n)
            pos += n
            return result
        }

        fun tag4(): String = String(bytes(4), Charsets.ISO_8859_1)

        /** Spec §5.2: variable-length u32, 7 bits per byte, no leading zeros. */
        fun uintBase128(): Int {
            var value = 0L
            for (i in 0 until 5) {
                val byte = u8()
                if (i == 0 && byte == 0x80) throw NumberFormatException("leading zero")
                if (value and 0xFE000000L != 0L) throw NumberFormatException("overflow")
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return value.toInt()
            }
            throw NumberFormatException("too long")
        }

        /** Spec §5.1: 253 = 2-byte word, 254 = byte+506, 255 = byte+253. */
        fun read255UShort(): Int = when (val code = u8()) {
            253 -> u16()
            254 -> u8() + 506
            255 -> u8() + 253
            else -> code
        }
    }

    private fun ByteArrayOutputStream.u16be(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.i16be(value: Int) = u16be(value and 0xFFFF)

    private fun ByteArrayOutputStream.u32be(value: Int) {
        u16be(value ushr 16)
        u16be(value and 0xFFFF)
    }

    private fun ByteArray.u32be(at: Int): Long =
        ((this[at].toInt() and 0xFF).toLong() shl 24) or
            ((this[at + 1].toInt() and 0xFF).toLong() shl 16) or
            ((this[at + 2].toInt() and 0xFF).toLong() shl 8) or
            (this[at + 3].toInt() and 0xFF).toLong()

    private fun writeU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = ((value ushr 24) and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 3] = (value and 0xFF).toByte()
    }

    private fun writeTag(bytes: ByteArray, at: Int, tag: String) {
        for (i in 0 until 4) {
            bytes[at + i] = tag[i].code.toByte()
        }
    }
}
