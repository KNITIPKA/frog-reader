package com.example.frogreader.data.parser.mobi

import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One reassembled KF8 part — a complete XHTML document (≈ a chapter). */
internal class Kf8Part(val index: Int, val bytes: ByteArray)

internal class Kf8Book(
    val parts: List<Kf8Part>,
    /** Flow 0 is the (consumed) skeleton flow; 1.. are CSS/SVG resources. */
    val flows: List<ByteArray>,
    /** Fragment id (FRAG table order) → (part index, offset inside part). */
    val fragLocations: List<Pair<Int, Int>>,
)

/**
 * KF8 (AZW3) text reassembly: FDST splits the raw text into flows, the
 * SKEL/FRAG indexes describe how flow 0's skeleton documents and content
 * fragments interleave into complete XHTML parts. Throws IOException on
 * structural damage — combo files then fall back to their MOBI6 half.
 */
internal object Kf8Assembler {

    private const val SKL_MAGIC = 0x314C4B53 // SKL1
    private const val FRG_MAGIC = 0x31475246 // FRG1
    private const val KF8_MAGIC = 0x3138464B // KF81

    fun assemble(section: MobiSection, rawText: ByteArray): Kf8Book {
        RustMobiWire.requireLibrary()
        val mobi = section.mobi ?: throw IOException("Damaged KF8: no MOBI header")
        val limits = section.pdb.limits
        val fdst = findFdstRecord(section)
        val flowWire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.kf8ParseFdst(
                fdst ?: ByteArray(0),
                rawText.size,
                limits.maxKf8FlowAggregateBytes,
            ),
            "Damaged KF8: FDST header failed",
        )!!
        val flowRanges = decodeFlowRanges(flowWire)

        val skel = MobiIndex.parse(section, mobi.skelIndex, required = true)
            ?: throw IOException("Damaged KF8: skeleton index")
        val frag = MobiIndex.parse(section, mobi.fragIndex, required = true)
            ?: throw IOException("Damaged KF8: fragment index")

        val skelWire = encodeSkelWire(skel.entries)
        val fragWire = encodeFragWire(frag.entries)
        val limitsArray = longArrayOf(
            limits.maxKf8Parts.toLong(),
            limits.maxKf8Fragments.toLong(),
            limits.maxKf8PartBytes,
            limits.maxKf8AssembledBytes,
        )
        val assembledWire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.kf8Assemble(rawText, flowRanges, skelWire, fragWire, limitsArray),
            "Damaged KF8: native assembly failed",
        )!!
        val (parts, fragLocations) = decodeAssembledWire(assembledWire)
        val flows = if (flowRanges.size == 2 && flowRanges[0] == 0 && flowRanges[1] == rawText.size) {
            listOf(rawText)
        } else {
            List(flowRanges.size / 2) { i ->
                rawText.copyOfRange(flowRanges[2 * i], flowRanges[2 * i + 1])
            }
        }
        return Kf8Book(parts, flows, fragLocations)
    }

    private fun decodeFlowRanges(wire: ByteArray): IntArray {
        val input = ByteBuffer.wrap(wire, 1, wire.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val count = RustMobiWire.readNonNegativeInt(
            input,
            "Truncated KF8 flow table",
            "Invalid KF8 flow count",
        )
        if (count == 0 || count > input.remaining() / (2 * Int.SIZE_BYTES)) {
            throw IOException("Invalid KF8 flow count")
        }
        val ranges = IntArray(count * 2) { input.int }
        if (input.hasRemaining()) throw IOException("Trailing KF8 flow table data")
        return ranges
    }

    private fun encodeSkelWire(entries: List<IndexEntry>): ByteArray {
        val out = ByteArrayOutputStream(8 + entries.size * 32)
        writeLeInt(out, SKL_MAGIC)
        writeLeInt(out, entries.size)
        for (entry in entries) {
            val tag1 = entry.tags[1]?.firstOrNull()
            if (tag1 == null) {
                out.write(0)
            } else {
                out.write(1)
                writeLeLong(out, tag1)
            }
            val tag6 = entry.tags[6]
            if (tag6 == null) {
                out.write(0)
            } else {
                out.write(1)
                writeLeInt(out, tag6.size)
                for (value in tag6) writeLeLong(out, value)
            }
        }
        return out.toByteArray()
    }

    private fun encodeFragWire(entries: List<IndexEntry>): ByteArray {
        val out = ByteArrayOutputStream(8 + entries.size * 24)
        writeLeInt(out, FRG_MAGIC)
        writeLeInt(out, entries.size)
        for (entry in entries) {
            val labelBytes = entry.label.toByteArray(Charsets.UTF_8)
            writeLeInt(out, labelBytes.size)
            out.write(labelBytes)
            val len = entry.tags[6]?.getOrNull(1)
            if (len == null) {
                out.write(0)
            } else {
                out.write(1)
                writeLeLong(out, len)
            }
        }
        return out.toByteArray()
    }

    private fun decodeAssembledWire(wire: ByteArray): Pair<List<Kf8Part>, List<Pair<Int, Int>>> {
        val input = RustMobiWire.openReader(wire, KF8_MAGIC, "Invalid KF8 assembly protocol")
        val partCount = RustMobiWire.readNonNegativeInt(
            input,
            "Truncated KF8 parts",
            "Invalid KF8 part count",
        )
        if (partCount > input.remaining() / Int.SIZE_BYTES) {
            throw IOException("Invalid KF8 part count")
        }
        val parts = ArrayList<Kf8Part>(partCount)
        for (index in 0 until partCount) {
            val size = RustMobiWire.readNonNegativeInt(
                input,
                "Truncated KF8 part size",
                "Truncated KF8 part bytes",
            )
            if (size > input.remaining()) throw IOException("Truncated KF8 part bytes")
            val start = input.position()
            input.position(start + size)
            parts += Kf8Part(index, wire.copyOfRange(start, start + size))
        }
        val fragCount = RustMobiWire.readNonNegativeInt(
            input,
            "Truncated KF8 frag locations",
            "Invalid KF8 frag location count",
        )
        if (fragCount > input.remaining() / (2 * Int.SIZE_BYTES)) {
            throw IOException("Invalid KF8 frag location count")
        }
        val fragLocations = List(fragCount) {
            input.int to input.int
        }
        if (input.hasRemaining()) throw IOException("Trailing KF8 assembly data")
        return parts to fragLocations
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeLeLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in 0 until 64 step 8) {
            out.write(((value ushr shift) and 0xFFL).toInt())
        }
    }

    private fun findFdstRecord(section: MobiSection): ByteArray? {
        val declared = section.mobi?.fdstRecord ?: -1
        if (declared >= 0 && section.hasRecord(declared)) {
            section.record(declared).takeIf { it.magic(0, "FDST") }?.let { return it }
        }
        val firstNonText = section.palmDoc.textRecordCount + 1
        for (i in firstNonText until section.recordCount) {
            val prefix = section.recordPrefix(i, 4) ?: continue
            if (prefix.magic(0, "FDST")) {
                return section.record(i)
            }
        }
        return null
    }

    /** Kindle base-32 ("0123456789ABCDEFGHIJKLMNOPQRSTUV", case-insensitive). */
    fun base32(text: String): Int? = text.toIntOrNull(32)?.takeIf { it >= 0 }
}
