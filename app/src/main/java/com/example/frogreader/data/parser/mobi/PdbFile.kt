package com.example.frogreader.data.parser.mobi

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// ------------------------------------------------------------ big-endian

internal fun ByteArray.u16(off: Int): Int =
    ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

/** Unsigned 32-bit as Long (0xFFFFFFFF markers stay distinguishable). */
internal fun ByteArray.u32(off: Int): Long =
    ((this[off].toInt() and 0xFF).toLong() shl 24) or
        ((this[off + 1].toInt() and 0xFF).toLong() shl 16) or
        ((this[off + 2].toInt() and 0xFF).toLong() shl 8) or
        (this[off + 3].toInt() and 0xFF).toLong()

/** u32 as an Int index; 0xFFFFFFFF (and anything unrepresentable) → -1. */
internal fun ByteArray.index32(off: Int): Int {
    val value = u32(off)
    return if (value in 0 until Int.MAX_VALUE.toLong()) value.toInt() else -1
}

internal fun ByteArray.magic(off: Int, ascii: String): Boolean {
    if (off < 0 || off + ascii.length > size) return false
    for (i in ascii.indices) {
        if (this[off + i].toInt() and 0xFF != ascii[i].code) return false
    }
    return true
}

// ------------------------------------------------------------ sources

/**
 * Random-access byte source for a PDB container. Not thread-safe: one
 * parse owns one source on one thread.
 */
internal interface PdbSource : Closeable {
    val size: Long

    /** A fresh copy of [length] bytes at [offset]. */
    fun readAt(offset: Long, length: Int): ByteArray

    /** A window over [length] bytes at [offset], handed as (data, off, len). */
    fun <T> withWindow(offset: Long, length: Int, block: (data: ByteArray, off: Int, len: Int) -> T): T

    override fun close() {}
}

/** In-memory source — tests and small inputs; windows are true zero-copy. */
internal class ByteArrayPdbSource(private val bytes: ByteArray) : PdbSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray =
        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)

    override fun <T> withWindow(offset: Long, length: Int, block: (ByteArray, Int, Int) -> T): T =
        block(bytes, offset.toInt(), length)
}

/**
 * On-disk source: records are read on demand, so a 100 MB AZW3 never
 * lands in the heap whole. Records are ≤4 KB text or single resources,
 * so a fresh array per window is cheap.
 */
internal class FilePdbSource(file: File) : PdbSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = raf.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun <T> withWindow(offset: Long, length: Int, block: (ByteArray, Int, Int) -> T): T {
        val data = readAt(offset, length)
        return block(data, 0, data.size)
    }

    override fun close() = raf.close()
}

// ------------------------------------------------------------ container

/**
 * Palm Database container — the outer shell of every MOBI/AZW/AZW3/PRC
 * file: a 78-byte header, a record offset table, then the records
 * back-to-back. All integers are big-endian.
 */
internal class PdbFile(private val source: PdbSource) : Closeable {

    constructor(bytes: ByteArray) : this(ByteArrayPdbSource(bytes))

    val name: String
    val typeCreator: String
    val recordCount: Int
    private val offsets: IntArray

    init {
        if (source.size < HEADER_SIZE + 8) throw IOException("Damaged MOBI: file too small")
        if (source.size > Int.MAX_VALUE) throw IOException("Damaged MOBI: file too large")
        val head = source.readAt(0, HEADER_SIZE)
        name = String(head, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        typeCreator = String(head, 60, 8, Charsets.ISO_8859_1)
        recordCount = head.u16(76)
        if (recordCount <= 0 || source.size < HEADER_SIZE + 8L * recordCount) {
            throw IOException("Damaged MOBI: bad record count $recordCount")
        }
        val table = source.readAt(HEADER_SIZE.toLong(), 8 * recordCount)
        offsets = IntArray(recordCount + 1)
        for (i in 0 until recordCount) {
            offsets[i] = table.index32(8 * i)
        }
        offsets[recordCount] = source.size.toInt()
        for (i in 0 until recordCount) {
            if (offsets[i] < HEADER_SIZE + 8 * recordCount ||
                offsets[i] > offsets[i + 1]
            ) {
                throw IOException("Damaged MOBI: bad record table at $i")
            }
        }
    }

    /** True when the type/creator marks a Mobipocket or PalmDOC book. */
    val isBook: Boolean
        get() = typeCreator == TYPE_MOBI || typeCreator == TYPE_PALMDOC

    fun recordOffset(i: Int): Int = offsets[i]

    fun recordLength(i: Int): Int = offsets[i + 1] - offsets[i]

    /** A copy of record [i] — for small records (headers, INDX, HUFF). */
    fun record(i: Int): ByteArray {
        if (i !in 0 until recordCount) throw IOException("Damaged MOBI: record $i out of range")
        return source.readAt(offsets[i].toLong(), recordLength(i))
    }

    /** A window over record [i] for the large text/resource records. */
    fun <T> withRecord(i: Int, block: (data: ByteArray, off: Int, len: Int) -> T): T {
        if (i !in 0 until recordCount) throw IOException("Damaged MOBI: record $i out of range")
        return source.withWindow(offsets[i].toLong(), recordLength(i), block)
    }

    override fun close() = source.close()

    companion object {
        const val HEADER_SIZE = 78
        const val TYPE_MOBI = "BOOKMOBI"
        const val TYPE_PALMDOC = "TEXtREAd"

        /** Sniff: is this the header of a Mobipocket/PalmDOC book? */
        fun isPdbBook(header: ByteArray): Boolean =
            header.size >= 68 &&
                (header.magic(60, TYPE_MOBI) || header.magic(60, TYPE_PALMDOC))
    }
}
