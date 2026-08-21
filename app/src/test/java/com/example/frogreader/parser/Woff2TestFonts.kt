package com.example.frogreader.parser

import java.io.ByteArrayOutputStream

/**
 * Test-side WOFF2 builder. `org.brotli:dec` cannot compress, so the "brotli"
 * stream is hand-written from RFC 7932 *uncompressed* meta-blocks — a real
 * decoder consumes it like any other stream (verified by its own test).
 */
object Woff2TestFonts {

    // ------------------------------------------------------------ brotli

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var bitCount = 0

        /** LSB-first, per RFC 7932 §2. */
        fun writeBits(value: Int, bits: Int) {
            for (i in 0 until bits) {
                current = current or (((value ushr i) and 1) shl bitCount)
                bitCount++
                if (bitCount == 8) {
                    out.write(current)
                    current = 0
                    bitCount = 0
                }
            }
        }

        fun alignByte() {
            if (bitCount > 0) {
                out.write(current)
                current = 0
                bitCount = 0
            }
        }

        fun writeBytes(bytes: ByteArray, off: Int, len: Int) {
            check(bitCount == 0) { "not byte-aligned" }
            out.write(bytes, off, len)
        }

        fun finish(): ByteArray {
            alignByte()
            return out.toByteArray()
        }
    }

    /** A valid brotli stream of uncompressed meta-blocks around [data]. */
    fun brotliUncompressed(data: ByteArray): ByteArray {
        val writer = BitWriter()
        writer.writeBits(0, 1) // WBITS = '0' → window 16
        var off = 0
        while (off < data.size) {
            val chunk = minOf(65536, data.size - off)
            writer.writeBits(0, 1) // ISLAST = 0
            writer.writeBits(0, 2) // MNIBBLES code '00' → 4 nibbles
            writer.writeBits(chunk - 1, 16) // MLEN - 1
            writer.writeBits(1, 1) // ISUNCOMPRESSED
            writer.alignByte()
            writer.writeBytes(data, off, chunk)
            off += chunk
        }
        writer.writeBits(1, 1) // ISLAST
        writer.writeBits(1, 1) // ISLASTEMPTY
        return writer.finish()
    }

    // ------------------------------------------------------------ WOFF2

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

    class Spec(
        val tag: String,
        /** Bytes that enter the brotli stream (transformed or plain). */
        val data: ByteArray,
        val origLength: Int = data.size,
        /** true → directory carries transformLength = data.size. */
        val transformed: Boolean = false,
        /** -1 = auto: 0 for plain tables, 3 for plain glyf/loca, 0/1 for transformed. */
        val transformVersion: Int = -1,
    )

    fun buildWoff2(tables: List<Spec>, flavor: Long = 0x00010000L): ByteArray {
        val payload = ByteArrayOutputStream()
        for (table in tables) payload.write(table.data)
        val compressed = brotliUncompressed(payload.toByteArray())

        val directory = ByteArrayOutputStream()
        for (table in tables) {
            val index = KNOWN_TAGS.indexOf(table.tag)
            val version = when {
                table.transformVersion >= 0 -> table.transformVersion
                table.transformed -> if (table.tag == "hmtx") 1 else 0
                table.tag == "glyf" || table.tag == "loca" -> 3
                else -> 0
            }
            directory.write((if (index >= 0) index else 63) or (version shl 6))
            if (index < 0) directory.write(table.tag.toByteArray(Charsets.ISO_8859_1))
            directory.write(uintBase128(table.origLength))
            if (table.transformed) directory.write(uintBase128(table.data.size))
        }
        val directoryBytes = directory.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("wOF2".toByteArray())
        out.u32be(flavor)
        out.u32be((48 + directoryBytes.size + compressed.size).toLong())
        out.u16be(tables.size)
        out.u16be(0) // reserved
        out.u32be(0L) // totalSfntSize (hint, unused by the decoder)
        out.u32be(compressed.size.toLong())
        out.u16be(1) // majorVersion
        out.u16be(0) // minorVersion
        repeat(5) { out.u32be(0L) } // meta/priv offsets and lengths
        out.write(directoryBytes)
        out.write(compressed)
        return out.toByteArray()
    }

    private fun uintBase128(value: Int): ByteArray {
        var v = value
        val groups = ArrayList<Int>()
        do {
            groups += v and 0x7F
            v = v ushr 7
        } while (v != 0)
        groups.reverse()
        val bytes = ByteArray(groups.size)
        for (i in groups.indices) {
            bytes[i] = (groups[i] or (if (i < groups.size - 1) 0x80 else 0)).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------ minimal TTF

    /** Triangle used by every fixture: (50,0) on, (550,900) on, (1050,0) off. */
    val TRIANGLE = listOf(
        Triple(50, 0, true),
        Triple(550, 900, true),
        Triple(1050, 0, false),
    )

    /**
     * Structurally valid TTF tables: 2 glyphs (empty + raw triangle),
     * loca format 0, 2 hmetrics. Order is deliberately not tag-sorted.
     */
    fun minimalTtfTables(): List<Pair<String, ByteArray>> {
        val glyf = ByteArrayOutputStream()
        glyf.i16be(1) // numberOfContours
        glyf.i16be(50); glyf.i16be(0); glyf.i16be(1050); glyf.i16be(900) // bbox
        glyf.u16be(2) // endPtsOfContours
        glyf.u16be(0) // instructionLength
        glyf.write(0x01); glyf.write(0x01); glyf.write(0x00) // flags (2-byte coords)
        glyf.i16be(50); glyf.i16be(500); glyf.i16be(500) // x deltas
        glyf.i16be(0); glyf.i16be(900); glyf.i16be(-900) // y deltas
        if (glyf.size() % 2 != 0) glyf.write(0)
        val glyfBytes = glyf.toByteArray()

        val loca = ByteArrayOutputStream()
        loca.u16be(0); loca.u16be(0); loca.u16be(glyfBytes.size / 2)

        return listOf(
            "glyf" to glyfBytes,
            "head" to headTable(indexToLocFormat = 0),
            "hhea" to hheaTable(numHMetrics = 2),
            "maxp" to maxpTable(numGlyphs = 2),
            "hmtx" to hmtxTable(),
            "loca" to loca.toByteArray(),
            "name" to byteArrayOf(0, 0, 0, 0, 0, 6),
            "cmap" to byteArrayOf(0, 0, 0, 1, 0, 3, 0, 1),
        )
    }

    fun headTable(indexToLocFormat: Int): ByteArray {
        val head = ByteArrayOutputStream()
        head.u32be(0x00010000L) // version
        head.u32be(0x00010000L) // fontRevision
        head.u32be(0L) // checkSumAdjustment
        head.u32be(0x5F0F3CF5L) // magic
        head.u16be(0x0003) // flags
        head.u16be(1000) // unitsPerEm
        repeat(4) { head.u32be(0L) } // created + modified
        head.i16be(50); head.i16be(0); head.i16be(1050); head.i16be(900) // bbox
        head.u16be(0) // macStyle
        head.u16be(8) // lowestRecPPEM
        head.i16be(2) // fontDirectionHint
        head.i16be(indexToLocFormat)
        head.i16be(0) // glyphDataFormat
        return head.toByteArray()
    }

    fun hheaTable(numHMetrics: Int): ByteArray {
        val hhea = ByteArrayOutputStream()
        hhea.u32be(0x00010000L)
        hhea.i16be(800); hhea.i16be(-200); hhea.i16be(0) // ascent/descent/lineGap
        hhea.u16be(600) // advanceWidthMax
        hhea.i16be(0); hhea.i16be(0); hhea.i16be(1050) // minLSB/minRSB/xMaxExtent
        hhea.i16be(1); hhea.i16be(0); hhea.i16be(0) // caret
        repeat(4) { hhea.i16be(0) } // reserved
        hhea.i16be(0) // metricDataFormat
        hhea.u16be(numHMetrics)
        return hhea.toByteArray()
    }

    fun maxpTable(numGlyphs: Int): ByteArray {
        val maxp = ByteArrayOutputStream()
        maxp.u32be(0x00010000L)
        maxp.u16be(numGlyphs)
        repeat(13) { maxp.u16be(0) }
        return maxp.toByteArray()
    }

    fun hmtxTable(): ByteArray {
        val hmtx = ByteArrayOutputStream()
        hmtx.u16be(500); hmtx.i16be(0) // glyph 0
        hmtx.u16be(600); hmtx.i16be(50) // glyph 1
        return hmtx.toByteArray()
    }

    // ------------------------------------------------------------ transformed glyf

    /**
     * The transformed-glyf table for [minimalTtfTables]'s glyph set: glyph 0
     * empty, glyph 1 = [points] triplet-encoded with the 4-byte rows
     * (flags 124..127), computed bbox, no instructions, indexFormat 0.
     */
    fun transformedGlyf(points: List<Triple<Int, Int, Boolean>>): ByteArray {
        val nContour = ByteArrayOutputStream()
        nContour.i16be(0) // glyph 0: empty
        nContour.i16be(1) // glyph 1: one contour

        val nPoints = ByteArrayOutputStream()
        nPoints.write(points.size) // 255UShort, small value = plain byte

        val flags = ByteArrayOutputStream()
        val glyphs = ByteArrayOutputStream()
        var px = 0
        var py = 0
        for ((x, y, onCurve) in points) {
            val dx = x - px
            val dy = y - py
            px = x
            py = y
            var flag = 124
            if (dx >= 0) flag = flag or 1
            if (dy >= 0) flag = flag or 2
            flags.write(flag or (if (onCurve) 0 else 0x80))
            val ax = if (dx >= 0) dx else -dx
            val ay = if (dy >= 0) dy else -dy
            glyphs.write(ax shr 8); glyphs.write(ax and 0xFF)
            glyphs.write(ay shr 8); glyphs.write(ay and 0xFF)
        }
        glyphs.write(0) // instructionLength (255UShort)

        val bbox = ByteArray(4) // bitmap for 2 glyphs: no explicit bboxes

        val out = ByteArrayOutputStream()
        out.u32be(0L) // version
        out.u16be(2) // numGlyphs
        out.u16be(0) // indexFormat
        out.u32be(nContour.size().toLong())
        out.u32be(nPoints.size().toLong())
        out.u32be(flags.size().toLong())
        out.u32be(glyphs.size().toLong())
        out.u32be(0L) // compositeStreamSize
        out.u32be(bbox.size.toLong())
        out.u32be(0L) // instructionStreamSize
        out.write(nContour.toByteArray())
        out.write(nPoints.toByteArray())
        out.write(flags.toByteArray())
        out.write(glyphs.toByteArray())
        out.write(bbox)
        return out.toByteArray()
    }

    /** Transformed hmtx: flags 0b11 → every lsb omitted; advances only. */
    fun transformedHmtx(advances: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        for (advance in advances) out.u16be(advance)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ sfnt reader

    /** Output-side directory parse: tag → table bytes. */
    fun parseSfnt(sfnt: ByteArray): Map<String, ByteArray> {
        val numTables = ((sfnt[4].toInt() and 0xFF) shl 8) or (sfnt[5].toInt() and 0xFF)
        val tables = LinkedHashMap<String, ByteArray>()
        for (i in 0 until numTables) {
            val at = 12 + i * 16
            val tag = String(sfnt, at, 4, Charsets.ISO_8859_1)
            val offset = readU32(sfnt, at + 8).toInt()
            val length = readU32(sfnt, at + 12).toInt()
            tables[tag] = sfnt.copyOfRange(offset, offset + length)
        }
        return tables
    }

    private fun readU32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toInt() and 0xFF).toLong() shl 24) or
            ((bytes[at + 1].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[at + 2].toInt() and 0xFF).toLong() shl 8) or
            (bytes[at + 3].toInt() and 0xFF).toLong()

    // ------------------------------------------------------------ io

    private fun ByteArrayOutputStream.u16be(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.i16be(value: Int) = u16be(value and 0xFFFF)

    private fun ByteArrayOutputStream.u32be(value: Long) {
        u16be(((value ushr 16) and 0xFFFF).toInt())
        u16be((value and 0xFFFF).toInt())
    }
}
