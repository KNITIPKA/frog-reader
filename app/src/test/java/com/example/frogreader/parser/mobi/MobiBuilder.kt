package com.example.frogreader.parser.mobi

import com.example.frogreader.data.parser.mobi.PdbFile
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Test-side builder of synthetic MOBI files: PDB container, record 0 with
 * MOBI/EXTH headers, a PalmDOC compressor (so the decoder is exercised by
 * real compressed data), trailing-entry encoding, INDX/FDST builders.
 */
object MobiBuilder {

    // ------------------------------------------------------------ primitives

    fun ByteArrayOutputStream.u16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    fun ByteArrayOutputStream.u32(value: Long) {
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    fun ByteArrayOutputStream.u32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

    private fun ByteArray.putU16(off: Int, value: Int) {
        this[off] = (value ushr 8).toByte()
        this[off + 1] = value.toByte()
    }

    private fun ByteArray.putU32(off: Int, value: Long) {
        this[off] = ((value ushr 24) and 0xFF).toByte()
        this[off + 1] = ((value ushr 16) and 0xFF).toByte()
        this[off + 2] = ((value ushr 8) and 0xFF).toByte()
        this[off + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArray.putU32(off: Int, value: Int) = putU32(off, value.toLong() and 0xFFFFFFFFL)

    // ------------------------------------------------------------ PDB

    fun buildPdb(type: String, name: String, records: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1).copyOf(32)
        out.write(nameBytes)
        repeat(28) { out.write(0) } // attributes..modnum etc. (32..59)
        out.write(type.toByteArray(Charsets.ISO_8859_1)) // 60..67 type+creator
        repeat(8) { out.write(0) } // 68..75
        out.u16(records.size) // 76
        var offset = 78 + 8 * records.size
        for ((i, record) in records.withIndex()) {
            out.u32(offset)
            out.write(0)
            out.write((i shr 16) and 0xFF)
            out.write((i shr 8) and 0xFF)
            out.write(i and 0xFF)
            offset += record.size
        }
        for (record in records) out.write(record)
        return out.toByteArray()
    }

    fun writePdb(target: File, type: String, name: String, records: List<ByteArray>): File {
        target.writeBytes(buildPdb(type, name, records))
        return target
    }

    // ------------------------------------------------------------ record 0

    class Kf8Fields(
        val fdstRecord: Int,
        val fdstCount: Int,
        val skelIndex: Int,
        val fragIndex: Int,
    )

    fun record0(
        compression: Int,
        textLength: Int,
        textRecords: Int,
        encoding: Int = 65001,
        encryption: Int = 0,
        exth: List<Pair<Int, ByteArray>> = emptyList(),
        fullName: String = "Test Book",
        extraFlags: Int = 0,
        firstImage: Int = -1,
        indxRecord: Int = -1,
        huffmanRecord: Int = -1,
        huffmanCount: Int = 0,
        kf8: Kf8Fields? = null,
        version: Int = 6,
    ): ByteArray {
        val headerLength = 0x108 - 16 // MOBI header length (from the magic)
        val mobiEnd = 16 + headerLength

        val exthBytes = if (exth.isEmpty()) {
            ByteArray(0)
        } else {
            val body = ByteArrayOutputStream()
            for ((type, data) in exth) {
                body.u32(type)
                body.u32(data.size + 8)
                body.write(data)
            }
            val out = ByteArrayOutputStream()
            out.write("EXTH".toByteArray())
            out.u32(12 + body.size())
            out.u32(exth.size)
            out.write(body.toByteArray())
            while (out.size() % 4 != 0) out.write(0)
            out.toByteArray()
        }

        val nameBytes = fullName.toByteArray(Charsets.UTF_8)
        val nameOffset = mobiEnd + exthBytes.size
        val record = ByteArray(nameOffset + nameBytes.size + 2)

        // Index-type MOBI fields default to "absent" (0xFFFFFFFF).
        for (i in 16 + 0x28 until mobiEnd step 4) record.putU32(i, 0xFFFFFFFFL)

        // PalmDOC header.
        record.putU16(0, compression)
        record.putU32(4, textLength)
        record.putU16(8, textRecords)
        record.putU16(10, 4096)
        record.putU16(12, encryption)

        // MOBI header.
        System.arraycopy("MOBI".toByteArray(), 0, record, 16, 4)
        record.putU32(20, headerLength)
        record.putU32(24, 2) // mobiType = book
        record.putU32(28, encoding)
        record.putU32(32, 0x12345678) // uniqueID
        record.putU32(36, version)
        record.putU32(16 + 0x44, nameOffset)
        record.putU32(16 + 0x48, nameBytes.size)
        record.putU32(16 + 0x4C, 0) // locale
        record.putU32(16 + 0x5C, firstImage)
        record.putU32(16 + 0x60, huffmanRecord)
        record.putU32(16 + 0x64, huffmanCount)
        record.putU32(16 + 0x70, if (exth.isEmpty()) 0 else 0x40)
        record.putU32(16 + 0xE0, extraFlags) // u16 at 0xF2 = low half
        record.putU32(16 + 0xE4, indxRecord)
        if (kf8 != null) {
            record.putU32(16 + 0xB0, kf8.fdstRecord)
            record.putU32(16 + 0xB4, kf8.fdstCount)
            record.putU32(16 + 0xEC, kf8.skelIndex)
            record.putU32(16 + 0xF0, kf8.fragIndex)
        }

        System.arraycopy(exthBytes, 0, record, mobiEnd, exthBytes.size)
        System.arraycopy(nameBytes, 0, record, nameOffset, nameBytes.size)
        return record
    }

    // ------------------------------------------------------------ PalmDOC

    /** A real (greedy) PalmDOC compressor covering every token class. */
    fun palmdocCompress(text: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.size) {
            // Back-reference: window ≤ 2047, length 3..10.
            var bestLen = 0
            var bestDist = 0
            val maxDist = minOf(i, 2047)
            var dist = 1
            while (dist <= maxDist) {
                var l = 0
                while (l < 10 && i + l < text.size && text[i + l] == text[i - dist + l]) l++
                if (l > bestLen) {
                    bestLen = l
                    bestDist = dist
                }
                dist++
            }
            if (bestLen >= 3) {
                val pair = 0x8000 or (bestDist shl 3) or (bestLen - 3)
                out.write((pair ushr 8) and 0xFF)
                out.write(pair and 0xFF)
                i += bestLen
                continue
            }
            val b = text[i].toInt() and 0xFF
            if (b == ' '.code && i + 1 < text.size) {
                val next = text[i + 1].toInt() and 0xFF
                if (next in 0x40..0x7F) {
                    out.write(next xor 0x80)
                    i += 2
                    continue
                }
            }
            if (b == 0x00 || b in 0x09..0x7F) {
                out.write(b)
                i++
                continue
            }
            // Bytes 0x01..0x08 / ≥ 0x80 (multibyte UTF-8): literal run.
            val runStart = i
            var runLength = 0
            while (i < text.size && runLength < 8) {
                val c = text[i].toInt() and 0xFF
                if (c in 0x09..0x7F || c == 0x00) break
                runLength++
                i++
            }
            out.write(runLength)
            out.write(text, runStart, runLength)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ trailing

    // ------------------------------------------------------------ INDX

    /** Forward varint: 7-bit groups, high first, final byte flagged 0x80. */
    fun forwardVarintBytes(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf((value or 0x80).toByte())
        value < 0x4000 -> byteArrayOf(
            ((value ushr 7) and 0x7F).toByte(),
            ((value and 0x7F) or 0x80).toByte(),
        )
        else -> byteArrayOf(
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            ((value and 0x7F) or 0x80).toByte(),
        )
    }

    class TagxSpec(val tag: Int, val valuesPerEntry: Int, val bitmask: Int)

    /**
     * A generic INDX cluster: header record with the TAGX, one entry
     * record, one CNCX record. Entries: label → (tag → control value to
     * varint values). Control values equal the FULL mask (single value)
     * unless [controlOverrides] shifts them.
     */
    fun indx(
        tagx: List<TagxSpec>,
        entries: List<Pair<String, Map<Int, List<Int>>>>,
        cncxStrings: List<String> = emptyList(),
    ): List<ByteArray> {
        val cncx = ByteArrayOutputStream()
        for (s in cncxStrings) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            cncx.write(forwardVarintBytes(bytes.size))
            cncx.write(bytes)
        }

        val entryData = ByteArrayOutputStream()
        val entryOffsets = mutableListOf<Int>()
        for ((label, tags) in entries) {
            entryOffsets += 192 + entryData.size()
            val ident = label.toByteArray(Charsets.ISO_8859_1)
            entryData.write(ident.size)
            entryData.write(ident)
            var control = 0
            for (spec in tagx) {
                val values = tags[spec.tag] ?: continue
                val singleBit = Integer.bitCount(spec.bitmask) == 1
                control = control or if (singleBit) {
                    spec.bitmask
                } else {
                    // value = count of value-groups, shifted into the mask.
                    val shift = Integer.numberOfTrailingZeros(spec.bitmask)
                    val count = values.size / spec.valuesPerEntry
                    (count shl shift) and spec.bitmask
                }
            }
            entryData.write(control)
            for (spec in tagx) {
                val values = tags[spec.tag] ?: continue
                for (v in values) entryData.write(forwardVarintBytes(v))
            }
        }
        val idxtOffset = 192 + entryData.size()
        val entryRecord = ByteArrayOutputStream()
        val entryHeader = ByteArray(192)
        System.arraycopy("INDX".toByteArray(), 0, entryHeader, 0, 4)
        putU32At(entryHeader, 4, 192)
        putU32At(entryHeader, 20, idxtOffset)
        putU32At(entryHeader, 24, entries.size)
        entryRecord.write(entryHeader)
        entryRecord.write(entryData.toByteArray())
        entryRecord.write("IDXT".toByteArray())
        for (off in entryOffsets) entryRecord.u16(off)

        val tagxBytes = ByteArrayOutputStream()
        tagxBytes.write("TAGX".toByteArray())
        tagxBytes.u32(12 + (tagx.size + 1) * 4)
        tagxBytes.u32(1) // control byte count
        for (spec in tagx) {
            tagxBytes.write(spec.tag)
            tagxBytes.write(spec.valuesPerEntry)
            tagxBytes.write(spec.bitmask)
            tagxBytes.write(0)
        }
        tagxBytes.write(0)
        tagxBytes.write(0)
        tagxBytes.write(0)
        tagxBytes.write(1) // end flag

        val headerRecord = ByteArrayOutputStream()
        val header = ByteArray(192)
        System.arraycopy("INDX".toByteArray(), 0, header, 0, 4)
        putU32At(header, 4, 192)
        putU32At(header, 24, 1) // one entry record
        putU32At(header, 36, entries.size) // total entries
        putU32At(header, 52, if (cncxStrings.isEmpty()) 0 else 1)
        headerRecord.write(header)
        headerRecord.write(tagxBytes.toByteArray())

        return if (cncxStrings.isEmpty()) {
            listOf(headerRecord.toByteArray(), entryRecord.toByteArray())
        } else {
            listOf(headerRecord.toByteArray(), entryRecord.toByteArray(), cncx.toByteArray())
        }
    }

    /** NCX cluster for (filepos, label, depth) rows. */
    fun ncxIndx(rows: List<Triple<Int, String, Int>>): List<ByteArray> {
        // CNCX offsets must be known up front — compute them like indx() will.
        val labelOffsets = mutableListOf<Int>()
        var acc = 0
        for ((_, label, _) in rows) {
            labelOffsets += acc
            val bytes = label.toByteArray(Charsets.UTF_8)
            acc += forwardVarintBytes(bytes.size).size + bytes.size
        }
        return indx(
            tagx = listOf(
                TagxSpec(1, 1, 0x01), // filepos
                TagxSpec(2, 1, 0x02), // length
                TagxSpec(3, 1, 0x04), // label (CNCX offset)
                TagxSpec(4, 1, 0x08), // depth
            ),
            entries = rows.mapIndexed { i, (filepos, _, depth) ->
                "%03d".format(i) to mapOf(
                    1 to listOf(filepos),
                    2 to listOf(10),
                    3 to listOf(labelOffsets[i]),
                    4 to listOf(depth),
                )
            },
            cncxStrings = rows.map { it.second },
        )
    }

    class Kf8NcxRow(val fid: Int, val off: Int, val label: String, val depth: Int)

    /** KF8 NCX: tag 6 carries `(fid, off)` and tag 1 may be absent. */
    fun kf8NcxIndx(rows: List<Kf8NcxRow>): List<ByteArray> {
        val labelOffsets = mutableListOf<Int>()
        var offset = 0
        for (row in rows) {
            labelOffsets += offset
            val bytes = row.label.toByteArray(Charsets.UTF_8)
            offset += forwardVarintBytes(bytes.size).size + bytes.size
        }
        return indx(
            tagx = listOf(
                TagxSpec(3, 1, 0x01),
                TagxSpec(4, 1, 0x02),
                TagxSpec(6, 2, 0x04),
            ),
            entries = rows.mapIndexed { index, row ->
                "%03d".format(index) to mapOf(
                    3 to listOf(labelOffsets[index]),
                    4 to listOf(row.depth),
                    6 to listOf(row.fid, row.off),
                )
            },
            cncxStrings = rows.map { it.label },
        )
    }

    private fun putU32At(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = (value ushr 24).toByte()
        bytes[off + 1] = (value ushr 16).toByte()
        bytes[off + 2] = (value ushr 8).toByte()
        bytes[off + 3] = value.toByte()
    }

    // ------------------------------------------------------------ whole book

    /**
     * A complete MOBI6 file: record 0, text records (optionally PalmDOC
     * compressed, optionally with trailing entries), then image records.
     */
    fun buildMobi6(
        target: File,
        html: String,
        compress: Boolean = true,
        encoding: Int = 65001,
        exth: List<Pair<Int, ByteArray>> = emptyList(),
        fullName: String = "Engine Mobi",
        images: List<ByteArray> = emptyList(),
        trailingPayloads: List<ByteArray> = emptyList(),
        indxRecord: Int = -1,
        extraRecords: List<ByteArray> = emptyList(),
    ): File {
        val charset = if (encoding == 65001) {
            Charsets.UTF_8
        } else {
            runCatching { charset("windows-$encoding") }.getOrDefault(Charsets.UTF_8)
        }
        val text = html.toByteArray(charset)
        val chunks = text.toList().chunked(4096).map { it.toByteArray() }
        val textRecords = chunks.map { chunk ->
            val payload = if (compress) palmdocCompress(chunk) else chunk
            if (trailingPayloads.isEmpty()) payload else withTrailing(payload, trailingPayloads)
        }
        val extraFlags = if (trailingPayloads.isEmpty()) {
            0
        } else {
            ((1 shl trailingPayloads.size) - 1) shl 1
        }
        val firstImage = if (images.isEmpty() && extraRecords.isEmpty()) {
            -1
        } else {
            1 + textRecords.size + extraRecords.size
        }
        val record0 = record0(
            compression = if (compress) 2 else 1,
            textLength = text.size,
            textRecords = textRecords.size,
            encoding = encoding,
            exth = exth,
            fullName = fullName,
            extraFlags = extraFlags,
            firstImage = if (images.isEmpty()) -1 else firstImage,
            indxRecord = indxRecord,
        )
        val records = listOf(record0) + textRecords + extraRecords + images
        return writePdb(target, PdbFile.TYPE_MOBI, "EngineMobi", records)
    }

    /** A tiny fake PNG (magic bytes + filler) for image records. */
    fun fakePng(seed: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            ByteArray(32) { ((it + seed) % 100).toByte() }

    /**
     * A "FONT" resource record around [payload]: deflated first when
     * [compress], then XOR-obfuscated over the first 1040 bytes when
     * [xorKey] is given (the decoder reverses in the opposite order).
     */
    fun fontRecord(
        payload: ByteArray,
        xorKey: ByteArray? = null,
        compress: Boolean = false,
    ): ByteArray {
        var data = payload.copyOf()
        if (compress) {
            val deflater = java.util.zip.Deflater()
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(data.size * 2 + 64)
            val n = deflater.deflate(buffer)
            deflater.end()
            data = buffer.copyOf(n)
        }
        if (xorKey != null) {
            for (i in 0 until minOf(1040, data.size)) {
                data[i] = (data[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
            }
        }
        val keyLen = xorKey?.size ?: 0
        val out = ByteArrayOutputStream()
        out.write("FONT".toByteArray())
        out.u32(payload.size) // usize
        out.u32((if (compress) 1 else 0) or (if (xorKey != null) 2 else 0)) // flags
        out.u32(24 + keyLen) // dataStart
        out.u32(keyLen) // xorLen
        out.u32(if (xorKey != null) 24 else 0) // xorStart
        xorKey?.let { out.write(it) }
        out.write(data)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ KF8

    class Kf8Spec(
        /** One skeleton XHTML shell per part; fragments insert at [insertMarker]. */
        val skeletons: List<String>,
        val fragments: List<List<String>>,
        val css: String,
        val insertMarker: String = "</body>",
        /** Extra compiled CSS flows, addressable as kindle:flow:0002, 0003, ... */
        val additionalCssFlows: List<String> = emptyList(),
    )

    /**
     * A KF8 book: flow 0 = skeletons+fragments, flow 1 = CSS, FDST +
     * SKEL/FRAG indexes, then [images] and [extraResources]. [combo] prepends
     * a MOBI6 half with EXTH 121 pointing at the KF8 boundary. The metadata
     * parameters are optional so compact parser fixtures retain their old
     * byte layout, while full comparison books can exercise EXTH and covers.
     */
    fun buildKf8(
        target: File,
        spec: Kf8Spec,
        combo: Boolean = false,
        breakSkel: Boolean = false,
        extraResources: List<ByteArray> = emptyList(),
        ncxRows: List<Kf8NcxRow> = emptyList(),
        indxAimedAtSkeleton: Boolean = false,
        images: List<ByteArray> = listOf(fakePng(42)),
        exth: List<Pair<Int, ByteArray>> = emptyList(),
        fullName: String = "KF8 Book",
    ): File {
        val flow0 = ByteArrayOutputStream()
        val skelEntries = mutableListOf<Pair<String, Map<Int, List<Int>>>>()
        val fragEntries = mutableListOf<Pair<String, Map<Int, List<Int>>>>()

        for ((p, skelText) in spec.skeletons.withIndex()) {
            val skelBytes = skelText.toByteArray(Charsets.UTF_8)
            val skelPos = flow0.size()
            val insertRelative = skelText
                .substring(0, skelText.indexOf(spec.insertMarker))
                .toByteArray(Charsets.UTF_8).size
            skelEntries += "SKEL%03d".format(p) to mapOf(
                1 to listOf(spec.fragments[p].size),
                6 to listOf(skelPos, skelBytes.size),
            )
            flow0.write(skelBytes)
            var assembled = insertRelative
            for (fragText in spec.fragments[p]) {
                val fragBytes = fragText.toByteArray(Charsets.UTF_8)
                fragEntries += (skelPos + assembled).toString() to mapOf(
                    6 to listOf(flow0.size(), fragBytes.size),
                )
                flow0.write(fragBytes)
                assembled += fragBytes.size
            }
        }
        val flow0Bytes = flow0.toByteArray()
        val flows = listOf(flow0Bytes, spec.css.toByteArray(Charsets.UTF_8)) +
            spec.additionalCssFlows.map { it.toByteArray(Charsets.UTF_8) }
        val text = ByteArrayOutputStream().also { out ->
            flows.forEach(out::write)
        }.toByteArray()
        val textRecords = text.toList().chunked(4096).map { it.toByteArray() }
        val recordCountT = textRecords.size

        val fdst = ByteArrayOutputStream()
        fdst.write("FDST".toByteArray())
        fdst.u32(12)
        fdst.u32(flows.size)
        var flowStart = 0
        for (flow in flows) {
            fdst.u32(flowStart)
            flowStart += flow.size
            fdst.u32(flowStart)
        }

        var skelRecords = indx(
            tagx = listOf(TagxSpec(1, 1, 0x01), TagxSpec(6, 2, 0x02)),
            entries = skelEntries,
        )
        if (breakSkel) skelRecords = listOf(ByteArray(48) { 0x11 }) + skelRecords.drop(1)
        val fragRecords = indx(
            tagx = listOf(TagxSpec(6, 2, 0x01)),
            entries = fragEntries,
        )

        val ncxRecords = if (ncxRows.isEmpty()) emptyList() else kf8NcxIndx(ncxRows)
        val ncxStart = recordCountT + 2 + skelRecords.size + fragRecords.size

        val kf8Record0 = record0(
            compression = 1,
            textLength = text.size,
            textRecords = recordCountT,
            exth = exth,
            fullName = fullName,
            firstImage = if (images.isEmpty()) -1 else ncxStart + ncxRecords.size,
            indxRecord = when {
                indxAimedAtSkeleton -> recordCountT + 2
                ncxRecords.isEmpty() -> -1
                else -> ncxStart
            },
            kf8 = Kf8Fields(
                fdstRecord = recordCountT + 1,
                fdstCount = flows.size,
                skelIndex = recordCountT + 2,
                fragIndex = recordCountT + 2 + skelRecords.size,
            ),
            version = 8,
        )
        val kf8Records = listOf(kf8Record0) + textRecords +
            listOf(fdst.toByteArray()) + skelRecords + fragRecords + ncxRecords +
            images + extraResources

        if (!combo) return writePdb(target, PdbFile.TYPE_MOBI, "Kf8", kf8Records)

        val mobi6Html = "<html><body><p>Старая версия для запасного пути.</p></body></html>"
        val mobi6Text = mobi6Html.toByteArray(Charsets.UTF_8)
        val mobi6Record0 = record0(
            compression = 1,
            textLength = mobi6Text.size,
            textRecords = 1,
            fullName = "Combo Book",
            exth = listOf(121 to byteArrayOf(0, 0, 0, 2)), // KF8 boundary = record 2
        )
        return writePdb(
            target, PdbFile.TYPE_MOBI, "Combo",
            listOf(mobi6Record0, mobi6Text) + kf8Records,
        )
    }

    /** Backward varint: value encoded with the FIRST byte flagged 0x80. */
    fun backwardVarint(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf((0x80 or value).toByte())
        else -> byteArrayOf(
            (0x80 or (value ushr 7)).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    /**
     * Appends trailing data to a record. Physical layout (matching the
     * reader's trim order): text, then the multibyte-overlap tail (bit 0,
     * innermost), then the size entries (bits 1.., outermost — trimmed
     * first from the record's end).
     */
    fun withTrailing(
        record: ByteArray,
        payloads: List<ByteArray>,
        multibyteTail: Int = -1,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(record)
        if (multibyteTail >= 0) {
            repeat(multibyteTail) { out.write('x'.code) }
            out.write(multibyteTail and 0x3)
        }
        for (payload in payloads) {
            // Entry size includes the varint's own bytes.
            var varint = backwardVarint(payload.size + 1)
            if (varint.size != 1) varint = backwardVarint(payload.size + 2)
            out.write(payload)
            out.write(varint)
        }
        return out.toByteArray()
    }
}
