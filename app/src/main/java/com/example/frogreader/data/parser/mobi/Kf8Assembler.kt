package com.example.frogreader.data.parser.mobi

import java.io.IOException

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

        val skel = MobiIndex.parse(section, mobi.skelIndex)
            ?: throw IOException("Damaged KF8: skeleton index")
        val frag = MobiIndex.parse(section, mobi.fragIndex)
            ?: throw IOException("Damaged KF8: fragment index")

        val parts = mutableListOf<Kf8Part>()
        val fragLocations = mutableListOf<Pair<Int, Int>>()
        var fragPtr = 0
        var basePtr = 0

        for ((partIndex, entry) in skel.entries.withIndex()) {
            val numFrags = entry.tags[1]?.firstOrNull()?.toInt() ?: 0
            val geometry = entry.tags[6]
                ?: throw IOException("Damaged KF8: skeleton geometry")
            val skelPos = geometry.getOrNull(0)?.toInt() ?: -1
            val skelLen = geometry.getOrNull(1)?.toInt() ?: -1
            if (skelPos < 0 || skelLen < 0 || skelPos + skelLen > flow0.size) {
                throw IOException("Damaged KF8: skeleton range")
            }
            basePtr = skelPos + skelLen

            // Collect this part's fragments (sequential in flow 0 after the
            // skeleton; insert positions grow with the assembled document).
            class Frag(val insertPos: Int, val start: Int, val length: Int)

            val frags = ArrayList<Frag>(numFrags)
            var totalFragLen = 0
            repeat(numFrags) {
                val fragEntry = frag.entries.getOrNull(fragPtr++)
                    ?: throw IOException("Damaged KF8: fragment table short")
                val insertPos = fragEntry.label.trim().toIntOrNull()
                    ?: throw IOException("Damaged KF8: fragment insert position")
                val length = fragEntry.tags[6]?.getOrNull(1)?.toInt()
                    ?: throw IOException("Damaged KF8: fragment length")
                if (length < 0 || basePtr + length > flow0.size) {
                    throw IOException("Damaged KF8: fragment range")
                }
                frags += Frag(insertPos, basePtr, length)
                basePtr += length
                totalFragLen += length
            }

            // Linear rebuild: split the skeleton at each fragment's
            // skeleton-coordinate position (assembled position minus the
            // fragments already inserted); disorder clamps forward.
            val out = ByteArray(skelLen + totalFragLen)
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
                val candidate = section.record(i)
                if (candidate.magic(0, "FDST")) {
                    record = candidate
                    break
                }
            }
        }
        val fdst = record ?: return listOf(rawText)
        val count = fdst.index32(8)
        if (count !in 1..4096 || 12 + count * 8 > fdst.size) return listOf(rawText)
        val flows = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val start = fdst.index32(12 + i * 8).coerceIn(0, rawText.size)
            val end = fdst.index32(16 + i * 8).coerceIn(start, rawText.size)
            flows += rawText.copyOfRange(start, end)
        }
        return if (flows.isEmpty()) listOf(rawText) else flows
    }

    /** Kindle base-32 ("0123456789ABCDEFGHIJKLMNOPQRSTUV", case-insensitive). */
    fun base32(text: String): Int? = text.toIntOrNull(32)?.takeIf { it >= 0 }
}
