package com.example.frogreader.data.parser.mobi

import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import java.io.IOException

/**
 * HUFF/CDIC decompression (MOBI compression type 17480) — canonical
 * Huffman codes over a phrase dictionary, a careful port of the well-known
 * KindleUnpack algorithm. Any structural violation throws IOException
 * ("damaged book"), deliberately distinct from the DRM rejection.
 */
internal class HuffCdicDecoder(
    huffRecord: ByteArray,
    cdicRecords: Iterable<ByteArray>,
    private val maxDecodedBytes: Int = 32 * 1024 * 1024,
    private val maxDictionaryBytes: Int = 32 * 1024 * 1024,
) {

    /** Per-first-byte: code length (1..32). */
    private val codeLengths = IntArray(256)

    /** Per-first-byte: code terminates within 8 bits. */
    private val terminates = BooleanArray(256)

    /** Per-first-byte: shifted max code when [terminates]. */
    private val maxCode1 = LongArray(256)

    /** Per code length 0..32: shifted min/max codes. */
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)

    private val dictionary: Array<ByteArray?>
    private val terminal: BooleanArray
    private var decodedDictionaryBytes = 0

    init {
        if (!huffRecord.magic(0, "HUFF") || huffRecord.size < 24) {
            throw IOException("Damaged MOBI: bad HUFF record")
        }
        val cacheOffset = huffRecord.index32(8)
        val baseOffset = huffRecord.index32(12)
        if (cacheOffset < 0 || baseOffset < 0 ||
            cacheOffset + 256 * 4 > huffRecord.size ||
            baseOffset + 64 * 4 > huffRecord.size
        ) {
            throw IOException("Damaged MOBI: HUFF tables out of range")
        }
        for (b in 0 until 256) {
            val v = huffRecord.u32(cacheOffset + 4 * b)
            val len = (v and 0x1F).toInt()
            if (len !in 1..32) throw IOException("Damaged MOBI: HUFF code length $len")
            codeLengths[b] = len
            terminates[b] = v and 0x80 != 0L
            maxCode1[b] = (((v ushr 8) + 1) shl (32 - len)) - 1
        }
        for (len in 1..32) {
            minCode[len] = huffRecord.u32(baseOffset + 8 * (len - 1)) shl (32 - len)
            maxCode[len] = ((huffRecord.u32(baseOffset + 8 * (len - 1) + 4) + 1) shl
                (32 - len)) - 1
        }

        // CDIC phrase dictionary, possibly spread over several records.
        val phrases: MutableList<ByteArray?> = mutableListOf()
        val flags = mutableListOf<Boolean>()
        var sourceBytes = 0L
        var phraseBytes = 0L
        for (cdic in cdicRecords) {
            if (cdic.size > maxDictionaryBytes - sourceBytes) throw dictionaryLimit()
            sourceBytes += cdic.size
            if (!cdic.magic(0, "CDIC") || cdic.size < 16) {
                throw IOException("Damaged MOBI: bad CDIC record")
            }
            val total = cdic.index32(8)
            val bits = cdic.index32(12)
            if (total < 0 || bits !in 0..16) throw IOException("Damaged MOBI: CDIC header")
            val here = minOf(1 shl bits, total - phrases.size)
            for (i in 0 until here) {
                if (16 + 2 * i + 2 > cdic.size) throw IOException("Damaged MOBI: CDIC offsets")
                val offset = cdic.u16(16 + 2 * i)
                val at = 16 + offset
                if (at + 2 > cdic.size) throw IOException("Damaged MOBI: CDIC entry")
                val lengthWord = cdic.u16(at)
                val length = lengthWord and 0x7FFF
                if (at + 2 + length > cdic.size) throw IOException("Damaged MOBI: CDIC phrase")
                if (length > maxDictionaryBytes - phraseBytes) throw dictionaryLimit()
                phrases += cdic.copyOfRange(at + 2, at + 2 + length)
                phraseBytes += length
                flags += (lengthWord and 0x8000) != 0
            }
        }
        dictionary = phrases.toTypedArray()
        terminal = flags.toBooleanArray()
    }

    /** Decompresses one record; returns the number of bytes written. */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        var o = dstOff
        try {
            decode(src, off, len, MAX_DEPTH) { slice ->
                System.arraycopy(slice, 0, dst, o, slice.size)
                o += slice.size
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw IOException("Damaged MOBI: HUFF output overflow", e)
        }
        return o - dstOff
    }

    /** Decompresses one record without allowing a hidden oversized expansion. */
    fun decompressBounded(
        src: ByteArray,
        off: Int,
        len: Int,
        maxOutputBytes: Int,
    ): ByteArray {
        val out = BoundedHuffOutput(maxOutputBytes)
        decode(src, off, len, MAX_DEPTH) { out.write(it) }
        return out.toByteArray()
    }

    /** Bit-stream decode with the classic 64-bit accumulator. */
    private inline fun decode(
        src: ByteArray,
        off: Int,
        len: Int,
        depth: Int,
        emit: (ByteArray) -> Unit,
    ) {
        // Zero tail so 8-byte reads never overrun.
        val buf = ByteArray(len + 8)
        System.arraycopy(src, off, buf, 0, len)
        var bitsLeft = len * 8
        var pos = 0
        var x = u64(buf, pos)
        var n = 32
        while (true) {
            if (n <= 0) {
                pos += 4
                x = u64(buf, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val b = (code ushr 24).toInt()
            var codeLength = codeLengths[b]
            var max = maxCode1[b]
            if (!terminates[b]) {
                while (code < minCode[codeLength]) {
                    codeLength++
                    if (codeLength > 32) throw IOException("Damaged MOBI: HUFF code")
                }
                max = maxCode[codeLength]
            }
            n -= codeLength
            bitsLeft -= codeLength
            if (bitsLeft < 0) break

            val index = ((max - code) ushr (32 - codeLength)).toInt()
            if (index !in dictionary.indices) {
                throw IOException("Damaged MOBI: HUFF dictionary index $index")
            }
            emit(resolved(index, depth))
        }
    }

    /** Non-terminal phrases decode recursively; memoized, cycle-guarded. */
    private fun resolved(index: Int, depth: Int): ByteArray {
        val phrase = dictionary[index]
            ?: throw IOException("Damaged MOBI: cyclic CDIC phrase")
        if (terminal[index]) return phrase
        if (depth <= 0) throw IOException("Damaged MOBI: CDIC recursion too deep")
        dictionary[index] = null // cycle guard while decoding this phrase
        val remaining = maxDecodedBytes - decodedDictionaryBytes
        if (remaining <= 0) throw decodedLimit()
        val out = BoundedHuffOutput(remaining)
        decode(phrase, 0, phrase.size, depth - 1) { out.write(it) }
        val decoded = out.toByteArray()
        decodedDictionaryBytes += decoded.size
        dictionary[index] = decoded
        terminal[index] = true
        return decoded
    }

    private fun decodedLimit() = ResourceLimitException(
        ResourceLimitKind.ENTRY_SIZE,
        "MOBI HUFF text expands beyond $maxDecodedBytes bytes",
    )

    private fun dictionaryLimit() = ResourceLimitException(
        ResourceLimitKind.ENTRY_SIZE,
        "MOBI HUFF dictionary exceeds $maxDictionaryBytes bytes",
    )

    private fun u64(bytes: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[off + i].toLong() and 0xFF)
        }
        return v
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

private class BoundedHuffOutput(private val maxBytes: Int) {
    private var bytes = ByteArray(minOf(maxBytes, 8 * 1024).coerceAtLeast(0))
    private var size = 0

    fun write(value: ByteArray) {
        if (value.size > maxBytes - size) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_SIZE,
                "MOBI HUFF output exceeds $maxBytes bytes",
            )
        }
        val needed = size + value.size
        if (needed > bytes.size) {
            var capacity = minOf(maxBytes, maxOf(8 * 1024, bytes.size))
            while (capacity < needed) {
                capacity = minOf(maxBytes, maxOf(needed, capacity + capacity / 2))
            }
            bytes = bytes.copyOf(capacity)
        }
        System.arraycopy(value, 0, bytes, size, value.size)
        size = needed
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
