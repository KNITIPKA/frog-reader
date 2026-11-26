package com.example.frogreader.data.parser.mobi

import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

    /** Streams exactly [length] bytes without allocating a record-sized array. */
    fun copyAt(offset: Long, length: Int, output: OutputStream)

    override fun close() {}
}

/** In-memory source — tests and small inputs; windows are true zero-copy. */
internal class ByteArrayPdbSource(private val bytes: ByteArray) : PdbSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray =
        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)

    override fun <T> withWindow(offset: Long, length: Int, block: (ByteArray, Int, Int) -> T): T =
        block(bytes, offset.toInt(), length)

    override fun copyAt(offset: Long, length: Int, output: OutputStream) {
        output.write(bytes, offset.toInt(), length)
    }
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

    override fun copyAt(offset: Long, length: Int, output: OutputStream) {
        raf.seek(offset)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val count = raf.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) throw IOException("Damaged MOBI: truncated record")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    override fun close() = raf.close()
}

// ------------------------------------------------------------ container

/**
 * Palm Database container — the outer shell of every MOBI/AZW/AZW3/PRC
 * file: a 78-byte header, a record offset table, then the records
 * back-to-back. All integers are big-endian.
 */
internal class PdbFile(
    private val source: PdbSource,
    internal val limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
) : Closeable {

    constructor(bytes: ByteArray) : this(ByteArrayPdbSource(bytes), ReaderResourceLimits.DEFAULT)

    constructor(bytes: ByteArray, limits: ReaderResourceLimits) :
        this(ByteArrayPdbSource(bytes), limits)

    val name: String
    val typeCreator: String
    val recordCount: Int
    private val offsets: IntArray
    private var bytesRead = 0L

    init {
        RustMobiWire.requireLibrary()
        if (source.size < HEADER_SIZE + 8) throw IOException("Damaged MOBI: file too small")
        if (source.size > Int.MAX_VALUE) throw IOException("Damaged MOBI: file too large")
        val head = readAtRequired(0, HEADER_SIZE, "PDB header")
        val headerWire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.parsePdbHeader(head, source.size, limits.maxMobiRecords),
            "Damaged MOBI: invalid PDB header",
        )!!
        val headerInput = RustMobiWire.openReader(
            headerWire,
            PDB_MAGIC,
            "Invalid PDB header protocol",
        )
        name = RustMobiWire.readUtf8String(
            headerWire,
            headerInput,
            "Truncated PDB header",
            "Invalid PDB header",
        )
        typeCreator = RustMobiWire.readUtf8String(
            headerWire,
            headerInput,
            "Truncated PDB header",
            "Invalid PDB header",
        )
        recordCount = RustMobiWire.readNonNegativeInt(
            headerInput,
            "Truncated PDB header",
            "Invalid PDB record count",
        )
        if (headerInput.hasRemaining()) throw IOException("Trailing PDB header data")

        val table = readAtRequired(
            HEADER_SIZE.toLong(),
            8 * recordCount,
            "PDB record table",
        )
        val offsetsWire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.parsePdbOffsets(table, recordCount, source.size),
            "Damaged MOBI: bad record table",
        )!!
        val offsetsInput = RustMobiWire.openReader(
            offsetsWire,
            OFS_MAGIC,
            "Invalid PDB offset protocol",
        )
        val offsetCount = RustMobiWire.readNonNegativeInt(
            offsetsInput,
            "Truncated PDB offsets",
            "Invalid PDB offset count",
        )
        if (offsetCount != recordCount + 1 ||
            offsetsInput.remaining() != offsetCount * Int.SIZE_BYTES
        ) {
            throw IOException("Invalid PDB offset protocol")
        }
        offsets = IntArray(offsetCount) { offsetsInput.int }
    }

    /** True when the type/creator marks a Mobipocket or PalmDOC book. */
    val isBook: Boolean
        get() = typeCreator == TYPE_MOBI || typeCreator == TYPE_PALMDOC

    fun recordOffset(i: Int): Int = offsets[i]

    fun recordLength(i: Int): Int = offsets[i + 1] - offsets[i]

    /** A required copy of record [i] (headers, text dictionaries and indexes). */
    fun record(
        i: Int,
        maxBytes: Long = limits.maxMobiRecordBytes,
        label: String = "MOBI record $i",
    ): ByteArray {
        val length = checkedRecordLength(i, maxBytes, label, optional = false)!!
        reserve(length, label, optional = false)
        return source.readAt(offsets[i].toLong(), length)
    }

    /** An optional resource record; oversize or aggregate exhaustion skips it. */
    fun recordOptional(i: Int, maxBytes: Long, label: String): ByteArray? {
        val length = checkedRecordLength(i, maxBytes, label, optional = true) ?: return null
        if (!reserve(length, label, optional = true)) return null
        return source.readAt(offsets[i].toLong(), length)
    }

    fun recordPrefix(
        i: Int,
        maxPrefixBytes: Int,
        label: String = "MOBI record $i prefix",
        optional: Boolean = false,
    ): ByteArray? {
        if (i !in 0 until recordCount) {
            if (optional) return null
            throw IOException("Damaged MOBI: record $i out of range")
        }
        val length = minOf(recordLength(i), maxPrefixBytes)
        if (!reserve(length, label, optional)) return null
        return source.readAt(offsets[i].toLong(), length)
    }

    /** A window over record [i] for the large text/resource records. */
    fun <T> withRecord(
        i: Int,
        maxBytes: Long = limits.maxMobiRecordBytes,
        label: String = "MOBI record $i",
        block: (data: ByteArray, off: Int, len: Int) -> T,
    ): T {
        val length = checkedRecordLength(i, maxBytes, label, optional = false)!!
        reserve(length, label, optional = false)
        return source.withWindow(offsets[i].toLong(), length, block)
    }

    /** Streams an optional resource record through a sibling temporary file. */
    fun copyRecordOptional(
        i: Int,
        target: File,
        maxBytes: Long,
        label: String,
    ): Boolean {
        val length = checkedRecordLength(i, maxBytes, label, optional = true) ?: return false
        if (!reserve(length, label, optional = true)) return false
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".tmp")
        partial.delete()
        try {
            partial.outputStream().buffered().use { output ->
                source.copyAt(offsets[i].toLong(), length, output)
            }
            if (!partial.renameTo(target)) throw IOException("Could not publish extracted $label")
            return true
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun readAtRequired(offset: Long, length: Int, label: String): ByteArray {
        reserve(length, label, optional = false)
        return source.readAt(offset, length)
    }

    private fun checkedRecordLength(
        i: Int,
        maxBytes: Long,
        label: String,
        optional: Boolean,
    ): Int? {
        if (i !in 0 until recordCount) {
            if (optional) return null
            throw IOException("Damaged MOBI: record $i out of range")
        }
        val length = recordLength(i)
        if (length.toLong() > maxBytes) {
            if (optional) return null
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_SIZE,
                "$label is $length bytes; limit is $maxBytes",
            )
        }
        return length
    }

    private fun reserve(length: Int, label: String, optional: Boolean): Boolean {
        if (bytesRead > limits.maxMobiReadAggregateBytes - length) {
            if (optional) return false
            throw ResourceLimitException(
                ResourceLimitKind.ACTUAL_AGGREGATE,
                "MOBI reads exceed ${limits.maxMobiReadAggregateBytes} bytes at $label",
            )
        }
        bytesRead += length
        return true
    }

    override fun close() = source.close()

    companion object {
        private const val PDB_MAGIC = 0x31424450 // PDB1
        private const val OFS_MAGIC = 0x3153464F // OFS1
        const val HEADER_SIZE = 78
        const val TYPE_MOBI = "BOOKMOBI"
        const val TYPE_PALMDOC = "TEXtREAd"

        /** Sniff: is this the header of a Mobipocket/PalmDOC book? */
        fun isPdbBook(header: ByteArray): Boolean {
            RustMobiWire.requireLibrary()
            return RustMobiNative.isPdbBook(header)
        }
    }
}
