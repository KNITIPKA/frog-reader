package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustEpubZipNative
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One EPUB parse owns the checked ZIP directory, archive handle, and expansion budget. */
internal class RustEpubArchive private constructor(
    private var handle: Long,
    val entries: List<Entry>,
    private val limits: ReaderResourceLimits,
) : Closeable {
    class Entry(
        internal val index: Int,
        val name: String,
        val size: Long,
        internal val compressedSize: Long,
        val isDirectory: Boolean,
    )

    private val entriesByName = entries.associateBy(Entry::name)
    private var bytesRead = 0L

    internal val bytesReadSoFar: Long get() = bytesRead

    internal fun remainingReadBytes(): Long =
        (limits.maxArchiveReadBytes - bytesRead).coerceAtLeast(0L)

    internal fun resetBytesRead(value: Long = 0L) {
        bytesRead = value
    }

    internal fun chargeBytesRead(delta: Long): Boolean {
        if (delta <= 0L) return true
        if (delta > limits.maxArchiveReadBytes - bytesRead) return false
        bytesRead += delta
        return true
    }

    internal fun openWorker(file: File): RustEpubArchive? = runCatching {
        open(file, limits)
    }.getOrNull()

    fun getEntry(name: String): Entry? = entriesByName[name]

    fun readRequired(entry: Entry, maxBytes: Long, label: String): ByteArray =
        read(entry, maxBytes, label, optional = false)!!

    fun readOptional(entry: Entry, maxBytes: Long, label: String): ByteArray? =
        read(entry, maxBytes, label, optional = true)

    private fun read(entry: Entry, maxBytes: Long, label: String, optional: Boolean): ByteArray? =
        readChecked(entry, maxBytes, label, optional) {
            RustEpubZipNative.read(handle, entry.index) ?: throw DamagedEntry(label)
        }

    fun parseChapterDocument(
        entry: Entry,
        maxBytes: Long,
        label: String,
        optional: Boolean,
        retainTree: Boolean,
    ): org.jsoup.nodes.Document? =
        readChecked(entry, maxBytes, label, optional) {
            val document = if (retainTree) {
                RustChapterMarkup.parseRetainedFromZip(handle, entry.index)
            } else {
                RustChapterMarkup.parseFromZip(handle, entry.index, htmlOnly = false)
            }
            document ?: throw DamagedEntry(label)
        }

    fun copyRequired(entry: Entry, target: File, maxBytes: Long, label: String) {
        copy(entry, target, maxBytes, label, optional = false)
    }

    fun copyOptional(entry: Entry, target: File, maxBytes: Long, label: String): Boolean =
        copy(entry, target, maxBytes, label, optional = true)

    private fun copy(entry: Entry, target: File, maxBytes: Long, label: String, optional: Boolean): Boolean =
        readChecked(entry, maxBytes, label, optional) {
            writeArchiveResourceAtomically(target) { partial ->
                when (RustEpubZipNative.copy(handle, entry.index, partial.absolutePath)) {
                    0 -> Unit
                    1 -> throw DamagedEntry(label)
                    else -> throw IOException("Could not write $label")
                }
            }
        } != null

    private inline fun <T : Any> readChecked(
        entry: Entry,
        maxBytes: Long,
        label: String,
        optional: Boolean,
        read: () -> T,
    ): T? {
        check(handle != 0L && entries.getOrNull(entry.index) === entry)
        try {
            validateArchiveEntry(
                name = entry.name,
                declared = entry.size,
                compressed = entry.compressedSize,
                maxBytes = minOf(maxBytes, MAX_NATIVE_ENTRY_BYTES),
                label = label,
                limits = limits,
            )
        } catch (error: ResourceLimitException) {
            if (optional) return null
            throw error
        }
        if (entry.size > limits.maxArchiveReadBytes - bytesRead) {
            throw ResourceLimitException(
                ResourceLimitKind.ACTUAL_AGGREGATE,
                "Archive expands beyond ${limits.maxArchiveReadBytes} bytes during this open",
            )
        }
        // Rust bounds expansion to the checked directory size. Charge the full
        // reservation even when a damaged optional member fails CRC validation.
        bytesRead += entry.size
        return try {
            read()
        } catch (error: DamagedEntry) {
            if (optional) null else throw error
        }
    }

    override fun close() {
        if (handle == 0L) return
        val archive = handle
        handle = 0L
        RustEpubZipNative.close(archive)
    }

    private class DamagedEntry(label: String) : IOException("Could not read $label from EPUB archive")

    companion object {
        private const val MAX_NATIVE_ENTRY_BYTES = 64L * 1024 * 1024
        private const val ENTRY_METADATA_BYTES = 2 * Long.SIZE_BYTES + 1

        fun open(file: File, limits: ReaderResourceLimits): RustEpubArchive {
            if (!RustBridge.shared.isLibraryReady) throw IOException("Rust EPUB archive reader is unavailable")
            val handle = RustEpubZipNative.open(
                file.absolutePath,
                limits.maxArchiveEntries,
                limits.maxArchiveDeclaredBytes,
            )
            when (handle) {
                -1L -> throw ResourceLimitException(
                    ResourceLimitKind.ENTRY_COUNT,
                    "Archive has more than ${limits.maxArchiveEntries} entries",
                )
                -2L -> throw ResourceLimitException(
                    ResourceLimitKind.DECLARED_AGGREGATE,
                    "Archive declares more than ${limits.maxArchiveDeclaredBytes} uncompressed bytes",
                )
            }
            if (handle <= 0L) throw IOException("Could not open EPUB archive")
            var handedOff = false
            try {
                val directory = RustEpubZipNative.directory(handle)
                    ?: throw IOException("Could not read EPUB directory")
                return RustEpubArchive(handle, decodeDirectory(directory, limits.maxArchiveEntries), limits)
                    .also { handedOff = true }
            } finally {
                if (!handedOff) RustEpubZipNative.close(handle)
            }
        }

        private fun decodeDirectory(bytes: ByteArray, maxEntries: Int): List<Entry> {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Invalid EPUB directory")
            val count = input.int
            if (count !in 0..maxEntries || count > input.remaining() / (Int.SIZE_BYTES + ENTRY_METADATA_BYTES)) {
                throw IOException("Invalid EPUB directory entry count")
            }
            val entries = List(count) { index ->
                if (input.remaining() < Int.SIZE_BYTES + ENTRY_METADATA_BYTES) throw IOException("Truncated EPUB directory")
                val length = input.int
                if (length !in 1..(input.remaining() - ENTRY_METADATA_BYTES)) {
                    throw IOException("Invalid EPUB entry name size")
                }
                val end = input.position() + length
                val name = bytes.decodeToString(input.position(), end, throwOnInvalidSequence = true)
                input.position(end)
                val size = input.long
                val compressed = input.long
                val isDirectory = input.get().toInt()
                if (size < 0L || compressed < 0L || isDirectory !in 0..1) throw IOException("Invalid EPUB entry metadata")
                Entry(index, name, size, compressed, isDirectory == 1)
            }
            if (input.hasRemaining()) throw IOException("Trailing data in EPUB directory")
            return entries
        }
    }
}
