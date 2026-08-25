package com.example.frogreader.data.parser.mobi

import java.io.IOException
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind

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

    fun assemble(section: MobiSection, rawText: ByteArray): Kf8Book {
        val mobi = section.mobi ?: throw IOException("Damaged KF8: no MOBI header")
        val flows = readFlows(section, rawText)
        val flow0 = flows[0]

        val skel = MobiIndex.parse(section, mobi.skelIndex, required = true)
            ?: throw IOException("Damaged KF8: skeleton index")
        val frag = MobiIndex.parse(section, mobi.fragIndex, required = true)
            ?: throw IOException("Damaged KF8: fragment index")
        val limits = section.pdb.limits
        if (skel.entries.size > limits.maxKf8Parts) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_COUNT,
                "KF8 has more than ${limits.maxKf8Parts} reassembled parts",
            )
        }

        val parts = mutableListOf<Kf8Part>()
        val fragLocations = mutableListOf<Pair<Int, Int>>()
        var fragPtr = 0
        var basePtr = 0L
        var assembledBytes = 0L
        var fragmentCount = 0

        for ((partIndex, entry) in skel.entries.withIndex()) {
            val numFragsLong = entry.tags[1]?.firstOrNull() ?: 0L
            if (numFragsLong !in 0..limits.maxKf8Fragments.toLong() ||
                numFragsLong > limits.maxKf8Fragments - fragmentCount
            ) {
                throw ResourceLimitException(
                    ResourceLimitKind.ENTRY_COUNT,
                    "KF8 has more than ${limits.maxKf8Fragments} fragments",
                )
            }
            val numFrags = numFragsLong.toInt()
            fragmentCount += numFrags
            val geometry = entry.tags[6]
                ?: throw IOException("Damaged KF8: skeleton geometry")
            val skelPosLong = geometry.getOrNull(0) ?: -1L
            val skelLenLong = geometry.getOrNull(1) ?: -1L
            if (skelPosLong < 0 || skelLenLong < 0 ||
                skelPosLong > flow0.size.toLong() - skelLenLong
            ) {
                throw IOException("Damaged KF8: skeleton range")
            }
            val skelPos = skelPosLong.toInt()
            val skelLen = skelLenLong.toInt()
            basePtr = skelPosLong + skelLenLong

            // Collect this part's fragments (sequential in flow 0 after the
            // skeleton; insert positions grow with the assembled document).
            class Frag(val insertPos: Int, val start: Int, val length: Int)

            val frags = ArrayList<Frag>(numFrags)
            var totalFragLen = 0L
            repeat(numFrags) {
                val fragEntry = frag.entries.getOrNull(fragPtr++)
                    ?: throw IOException("Damaged KF8: fragment table short")
                val insertPos = fragEntry.label.trim().toLongOrNull()
                    ?: throw IOException("Damaged KF8: fragment insert position")
                val lengthLong = fragEntry.tags[6]?.getOrNull(1)
                    ?: throw IOException("Damaged KF8: fragment length")
                if (insertPos !in 0..Int.MAX_VALUE.toLong() || lengthLong < 0 ||
                    basePtr > flow0.size.toLong() - lengthLong
                ) {
                    throw IOException("Damaged KF8: fragment range")
                }
                if (totalFragLen > limits.maxKf8PartBytes - lengthLong) {
                    throw partLimit(limits.maxKf8PartBytes)
                }
                val length = lengthLong.toInt()
                frags += Frag(insertPos.toInt(), basePtr.toInt(), length)
                basePtr += lengthLong
                totalFragLen += lengthLong
            }

            // Linear rebuild: split the skeleton at each fragment's
            // skeleton-coordinate position (assembled position minus the
            // fragments already inserted); disorder clamps forward.
            val partBytes = skelLenLong + totalFragLen
            if (partBytes > limits.maxKf8PartBytes ||
                assembledBytes > limits.maxKf8AssembledBytes - partBytes
            ) {
                throw partLimit(
                    minOf(limits.maxKf8PartBytes, limits.maxKf8AssembledBytes),
                )
            }
            assembledBytes += partBytes
            val out = ByteArray(partBytes.toInt())
            var outPos = 0
            var skelCursor = 0
            var consumed = 0
            for (piece in frags) {
                val assembledAt = piece.insertPos - skelPos
                var splitAt = assembledAt - consumed
                if (splitAt < skelCursor) splitAt = skelCursor
                if (splitAt > skelLen) splitAt = skelLen
                val skelChunk = splitAt - skelCursor
                System.arraycopy(flow0, skelPos + skelCursor, out, outPos, skelChunk)
                outPos += skelChunk
                skelCursor = splitAt
                fragLocations += partIndex to outPos
                System.arraycopy(flow0, piece.start, out, outPos, piece.length)
                outPos += piece.length
                consumed += piece.length
            }
            System.arraycopy(flow0, skelPos + skelCursor, out, outPos, skelLen - skelCursor)
            parts += Kf8Part(partIndex, out)
        }
        if (parts.isEmpty()) throw IOException("Damaged KF8: no parts")
        return Kf8Book(parts, flows, fragLocations)
    }

    private fun partLimit(maxBytes: Long) = ResourceLimitException(
        ResourceLimitKind.ENTRY_SIZE,
        "KF8 reassembled content exceeds $maxBytes bytes",
    )

    /** FDST flow table; a lying header offset falls back to a record scan. */
    private fun readFlows(section: MobiSection, rawText: ByteArray): List<ByteArray> {
        var record: ByteArray? = null
        val declared = section.mobi?.fdstRecord ?: -1
        if (declared >= 0 && section.hasRecord(declared)) {
            section.record(declared).takeIf { it.magic(0, "FDST") }?.let { record = it }
        }
        if (record == null) {
            val firstNonText = (section.palmDoc.textRecordCount + 1)
            for (i in firstNonText until section.recordCount) {
                val prefix = section.recordPrefix(i, 4) ?: continue
                if (prefix.magic(0, "FDST")) {
                    record = section.record(i)
                    break
                }
            }
        }
        val fdst = record ?: run {
            if (rawText.size.toLong() > section.pdb.limits.maxKf8FlowAggregateBytes) {
                throw ResourceLimitException(
                    ResourceLimitKind.ACTUAL_AGGREGATE,
                    "KF8 flow exceeds ${section.pdb.limits.maxKf8FlowAggregateBytes} bytes",
                )
            }
            return listOf(rawText)
        }
        val count = fdst.index32(8)
        if (count !in 1..4096 || 12 + count * 8 > fdst.size) return listOf(rawText)
        val flows = ArrayList<ByteArray>(count)
        var copiedBytes = 0L
        for (i in 0 until count) {
            val start = fdst.index32(12 + i * 8).coerceIn(0, rawText.size)
            val end = fdst.index32(16 + i * 8).coerceIn(start, rawText.size)
            val length = (end - start).toLong()
            if (copiedBytes > section.pdb.limits.maxKf8FlowAggregateBytes - length) {
                throw ResourceLimitException(
                    ResourceLimitKind.ACTUAL_AGGREGATE,
                    "KF8 flows exceed ${section.pdb.limits.maxKf8FlowAggregateBytes} bytes",
                )
            }
            copiedBytes += length
            flows += rawText.copyOfRange(start, end)
        }
        return if (flows.isEmpty()) listOf(rawText) else flows
    }

    /** Kindle base-32 ("0123456789ABCDEFGHIJKLMNOPQRSTUV", case-insensitive). */
    fun base32(text: String): Int? = text.toIntOrNull(32)?.takeIf { it >= 0 }
}
