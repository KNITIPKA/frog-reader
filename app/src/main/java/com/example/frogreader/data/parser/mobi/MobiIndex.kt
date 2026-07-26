package com.example.frogreader.data.parser.mobi

/**
 * INDX/TAGX/CNCX index machinery — the structure MOBI uses for the NCX
 * table of contents and KF8's SKEL/FRAG tables. The layout is the classic
 * KindleUnpack interpretation. ANY structural violation makes [parse]
 * return null: callers degrade (chapters from pagebreaks, titles from
 * headings) instead of failing the book.
 */
internal class IndexEntry(val label: String, val tags: Map<Int, List<Long>>)

internal object MobiIndex {

    class Parsed(val entries: List<IndexEntry>, val cncx: Map<Int, String>)

    fun parse(section: MobiSection, firstRecord: Int): Parsed? =
        runCatching { parseOrNull(section, firstRecord) }.getOrNull()

    private class TagxEntry(
        val tag: Int,
        val valuesPerEntry: Int,
        val bitmask: Int,
        val endFlag: Boolean,
    )

    private fun parseOrNull(section: MobiSection, firstRecord: Int): Parsed? {
        if (firstRecord < 0 || !section.hasRecord(firstRecord)) return null
        val header = section.record(firstRecord)
        if (!header.magic(0, "INDX")) return null
        val headerLength = header.index32(4)
        if (headerLength < 56 || headerLength > header.size) return null
        val entryRecordCount = header.index32(24) // 'count'
        val cncxCount = header.index32(52) // 'nctoc'
        if (entryRecordCount !in 0..4096 || cncxCount !in 0..64) return null

        // TAGX table right after the INDX header.
        if (!header.magic(headerLength, "TAGX")) return null
        val tagxLength = header.index32(headerLength + 4)
        val controlByteCount = header.index32(headerLength + 8)
        if (tagxLength < 12 || headerLength + tagxLength > header.size) return null
        if (controlByteCount !in 1..8) return null
        val tagx = mutableListOf<TagxEntry>()
        var p = headerLength + 12
        while (p + 4 <= headerLength + tagxLength) {
            tagx += TagxEntry(
                tag = header[p].toInt() and 0xFF,
                valuesPerEntry = header[p + 1].toInt() and 0xFF,
                bitmask = header[p + 2].toInt() and 0xFF,
                endFlag = header[p + 3].toInt() and 0x01 != 0,
            )
            p += 4
        }
        if (tagx.isEmpty()) return null

        // Entry records follow the header record.
        val entries = mutableListOf<IndexEntry>()
        for (r in 1..entryRecordCount) {
            if (!section.hasRecord(firstRecord + r)) return null
            val record = section.record(firstRecord + r)
            if (!record.magic(0, "INDX")) return null
            val idxtOffset = record.index32(20) // 'start'
            val count = record.index32(24) // 'count'
            if (count !in 0..8192) return null
            if (!record.magic(idxtOffset, "IDXT")) return null
            for (e in 0 until count) {
                val offAt = idxtOffset + 4 + 2 * e
                if (offAt + 2 > record.size) return null
                val entryOffset = record.u16(offAt)
                entries += parseEntry(record, entryOffset, tagx, controlByteCount)
                    ?: return null
            }
        }

        // CNCX string pool after the entry records; keys are offsets into
        // the CONCATENATION of the CNCX records.
        val cncx = mutableMapOf<Int, String>()
        var cncxBase = 0
        for (c in 0 until cncxCount) {
            val index = firstRecord + 1 + entryRecordCount + c
            if (!section.hasRecord(index)) break
            val record = section.record(index)
            var q = 0
            while (q < record.size) {
                val start = q
                val (length, consumed) = forwardVarint(record, q) ?: break
                if (length <= 0L || q + consumed + length > record.size) break
                cncx[cncxBase + start] = runCatching {
                    String(record, q + consumed, length.toInt(), Charsets.UTF_8)
                }.getOrNull() ?: break
                q += consumed + length.toInt()
            }
            cncxBase += record.size
        }

        return Parsed(entries, cncx)
    }

    /** One IDXT entry: len-prefixed label, control bytes, varint tag values. */
    private fun parseEntry(
        record: ByteArray,
        entryOffset: Int,
        tagx: List<TagxEntry>,
        controlByteCount: Int,
    ): IndexEntry? {
        if (entryOffset < 0 || entryOffset + 1 > record.size) return null
        val labelLength = record[entryOffset].toInt() and 0xFF
        if (entryOffset + 1 + labelLength + controlByteCount > record.size) return null
        val label = String(record, entryOffset + 1, labelLength, Charsets.ISO_8859_1)
        val controlStart = entryOffset + 1 + labelLength
        var dataStart = controlStart + controlByteCount

        class PendingTag(
            val tag: Int,
            val valueCount: Int?,
            val valueBytes: Long?,
            val valuesPerEntry: Int,
        )

        val pending = mutableListOf<PendingTag>()
        var controlIndex = 0
        for (entry in tagx) {
            if (entry.endFlag) {
                controlIndex++
                continue
            }
            if (controlIndex >= controlByteCount) return null
            val value = (record[controlStart + controlIndex].toInt() and 0xFF) and entry.bitmask
            if (value == 0) continue
            if (value == entry.bitmask) {
                if (Integer.bitCount(entry.bitmask) > 1) {
                    val (totalBytes, consumed) = forwardVarint(record, dataStart) ?: return null
                    dataStart += consumed
                    pending += PendingTag(entry.tag, null, totalBytes, entry.valuesPerEntry)
                } else {
                    pending += PendingTag(entry.tag, 1, null, entry.valuesPerEntry)
                }
            } else {
                var mask = entry.bitmask
                var shifted = value
                while (mask and 0x01 == 0) {
                    mask = mask shr 1
                    shifted = shifted shr 1
                }
                pending += PendingTag(entry.tag, shifted, null, entry.valuesPerEntry)
            }
        }

        val tags = mutableMapOf<Int, List<Long>>()
        for (tag in pending) {
            val values = mutableListOf<Long>()
            if (tag.valueCount != null) {
                repeat(tag.valueCount * tag.valuesPerEntry) {
                    val (value, consumed) = forwardVarint(record, dataStart) ?: return null
                    dataStart += consumed
                    values += value
                }
            } else {
                var consumedTotal = 0L
                while (consumedTotal < (tag.valueBytes ?: 0L)) {
                    val (value, consumed) = forwardVarint(record, dataStart) ?: return null
                    dataStart += consumed
                    consumedTotal += consumed
                    values += value
                }
            }
            tags[tag.tag] = values
        }
        return IndexEntry(label, tags)
    }

    /** Forward varint: 7 bits per byte, the byte with 0x80 set terminates. */
    fun forwardVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var value = 0L
        var consumed = 0
        while (true) {
            if (offset + consumed >= data.size || consumed >= 8) return null
            val byte = data[offset + consumed].toInt() and 0xFF
            consumed++
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 != 0) return value to consumed
        }
    }
}

/** One NCX (table-of-contents) row extracted from a parsed index. */
internal class NcxEntry(val filepos: Int, val label: String?, val depth: Int)

/** NCX view: tag 1 = filepos, 3 = CNCX label offset, 4 = depth. */
internal fun ncxEntries(parsed: MobiIndex.Parsed): List<NcxEntry> =
    parsed.entries.mapNotNull { entry ->
        val filepos = entry.tags[1]?.firstOrNull()?.toInt() ?: return@mapNotNull null
        NcxEntry(
            filepos = filepos,
            label = entry.tags[3]?.firstOrNull()?.toInt()?.let { parsed.cncx[it] },
            depth = (entry.tags[4]?.firstOrNull()?.toInt() ?: 0).coerceIn(0, 5),
        )
    }.sortedBy { it.filepos }
