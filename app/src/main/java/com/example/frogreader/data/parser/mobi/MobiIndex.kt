package com.example.frogreader.data.parser.mobi

import com.example.frogreader.data.parser.rethrowIfResourceLimit
import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * INDX/TAGX/CNCX index machinery — the structure MOBI uses for the NCX
 * table of contents and KF8's SKEL/FRAG tables. ANY structural violation makes
 * [parse] return null: callers degrade (chapters from pagebreaks, titles
 * from headings) instead of failing the book.
 */
internal class IndexEntry(val label: String, val tags: Map<Int, List<Long>>)

internal object MobiIndex {

    private const val IDX_MAGIC = 0x31584449 // IDX1

    class Parsed(val entries: List<IndexEntry>, val cncx: Map<Int, String>)

    fun parse(section: MobiSection, firstRecord: Int, required: Boolean = false): Parsed? {
        RustMobiWire.requireLibrary()
        return try {
            parseNative(section, firstRecord)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (required) error.rethrowIfResourceLimit()
            null
        }
    }

    /** Forward varint: 7 bits per byte, the byte with 0x80 set terminates. */
    fun forwardVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        RustMobiWire.requireLibrary()
        if (offset < 0 || offset >= data.size) return null
        val pair = RustMobiNative.forwardVarint(data, offset) ?: return null
        return pair[0] to pair[1].toInt()
    }

    private fun parseNative(section: MobiSection, firstRecord: Int): Parsed? {
        if (firstRecord < 0 || !section.hasRecord(firstRecord)) return null
        val header = section.record(firstRecord)
        val limits = section.pdb.limits
        val limitsArray = longArrayOf(
            limits.maxMobiTagxEntries.toLong(),
            limits.maxMobiIndexEntries.toLong(),
            limits.maxMobiIndexValues.toLong(),
            limits.maxMobiCncxEntries.toLong(),
            limits.maxMobiCncxBytes,
        )
        val beginWire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.indexBegin(header, limitsArray),
            "Damaged MOBI: index header failed",
        ) ?: return null
        if (beginWire.size != 17) throw IOException("Invalid MOBI index session header")
        val beginBuf = ByteBuffer.wrap(beginWire, 1, 16).order(ByteOrder.LITTLE_ENDIAN)
        val handle = beginBuf.long
        val entryRecordCount = beginBuf.int
        val cncxCount = beginBuf.int

        var finished = false
        try {
            for (r in 1..entryRecordCount) {
                if (!section.hasRecord(firstRecord + r)) return null
                val record = section.record(firstRecord + r)
                RustMobiWire.unwrapOrThrow(
                    RustMobiNative.indexAddEntryRecord(handle, record),
                    "Damaged MOBI: index entry record failed",
                ) ?: return null
            }
            for (c in 0 until cncxCount) {
                val index = firstRecord + 1 + entryRecordCount + c
                if (!section.hasRecord(index)) break
                val record = section.record(index)
                RustMobiWire.unwrapOrThrow(
                    RustMobiNative.indexAddCncxRecord(handle, record),
                    "Damaged MOBI: index CNCX record failed",
                )
            }
            val resultWire = RustMobiWire.unwrapOrThrow(
                RustMobiNative.indexFinish(handle),
                "Damaged MOBI: index finish failed",
            ) ?: return null
            finished = true
            return decodeParsedIndex(resultWire)
        } finally {
            if (!finished) RustMobiNative.indexClose(handle)
        }
    }

    private fun decodeParsedIndex(wire: ByteArray): Parsed {
        val input = RustMobiWire.openReader(wire, IDX_MAGIC, "Invalid MOBI index protocol")
        val entryCount = RustMobiWire.readNonNegativeInt(
            input,
            "Truncated MOBI index data",
            "Invalid MOBI index count",
        )
        val entries = ArrayList<IndexEntry>(entryCount)
        repeat(entryCount) {
            val label = RustMobiWire.readUtf8String(
                wire,
                input,
                "Truncated MOBI index string",
                "Invalid MOBI index count",
            )
            val tagCount = RustMobiWire.readNonNegativeInt(
                input,
                "Truncated MOBI index data",
                "Invalid MOBI index count",
            )
            val tags = LinkedHashMap<Int, List<Long>>(tagCount)
            repeat(tagCount) {
                val tagId = RustMobiWire.readNonNegativeInt(
                    input,
                    "Truncated MOBI index data",
                    "Invalid MOBI index count",
                )
                val valCount = RustMobiWire.readNonNegativeInt(
                    input,
                    "Truncated MOBI index data",
                    "Invalid MOBI index count",
                )
                if (valCount > input.remaining() / Long.SIZE_BYTES) {
                    throw IOException("Truncated MOBI index tag values")
                }
                val values = List(valCount) { input.long }
                tags[tagId] = values
            }
            entries += IndexEntry(label, tags)
        }
        val cncxCount = RustMobiWire.readNonNegativeInt(
            input,
            "Truncated MOBI index data",
            "Invalid MOBI index count",
        )
        val cncx = LinkedHashMap<Int, String>(cncxCount)
        repeat(cncxCount) {
            val key = RustMobiWire.readInt(input, "Truncated MOBI CNCX key")
            val text = RustMobiWire.readUtf8String(
                wire,
                input,
                "Truncated MOBI index string",
                "Invalid MOBI index count",
            )
            cncx[key] = text
        }
        if (input.hasRemaining()) throw IOException("Trailing MOBI index data")
        return Parsed(entries, cncx)
    }
}

/** One NCX row: MOBI6 uses [filepos], KF8 usually uses [posFid]. */
internal class NcxEntry(
    val filepos: Int,
    val label: String?,
    val depth: Int,
    val posFid: Pair<Int, Int>? = null,
)

/** NCX view: tag 1=filepos, 3=CNCX label, 4=depth, 6=KF8 fid/off. */
internal fun ncxEntries(parsed: MobiIndex.Parsed): List<NcxEntry> =
    parsed.entries.mapNotNull { entry ->
        val posFid = entry.tags[6]
            ?.takeIf { it.size >= 2 }
            ?.let { it[0].toInt() to it[1].toInt() }
        val filepos = entry.tags[1]?.firstOrNull()?.toInt() ?: -1
        if (filepos < 0 && posFid == null) return@mapNotNull null
        NcxEntry(
            filepos = filepos,
            label = entry.tags[3]?.firstOrNull()?.toInt()?.let { parsed.cncx[it] },
            depth = (entry.tags[4]?.firstOrNull()?.toInt() ?: 0).coerceIn(0, 5),
            posFid = posFid,
        )
    }.sortedBy { it.filepos }
