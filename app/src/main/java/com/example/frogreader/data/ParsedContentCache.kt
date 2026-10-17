@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.data

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * Rebuildable, lossless parsed text. Image and font bytes stay in their original
 * derived-resource directory. Call on an IO dispatcher; no Android dependency.
 *
 * Both compressed and expanded streams have hard limits. In particular, saving
 * a very large publication never first allocates a publication-sized JSON string.
 * A failed/unsupported/oversized full save preserves the usable preview on disk.
 * The owner applies a global LRU budget across book directories and serializes
 * writes for each book. Atomic publication lets other books load concurrently.
 */
internal class ParsedContentCache(
    private val directory: File,
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    private val maxExpandedBytes: Long = MAX_EXPANDED_BYTES,
) {
    data class Snapshot(val content: BookContent, val complete: Boolean)

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
    }

    /** A small prefix can be presented while the complete snapshot is decoded. */
    fun loadPreview(bookId: String, sourceFile: File): Snapshot? =
        readSnapshot(entryFile(bookId, complete = false), sourceFile, complete = false)

    /** Returns a full snapshot when available, otherwise the saved prefix. */
    fun load(bookId: String, sourceFile: File): Snapshot? =
        readSnapshot(entryFile(bookId), sourceFile, complete = true) ?: loadPreview(bookId, sourceFile)

    /** Returns null for stale, damaged, unsupported or evicted files. */
    private fun readSnapshot(file: File, sourceFile: File, complete: Boolean): Snapshot? {
        if (!file.isFile || file.length() !in 1..maxEntryBytes || !sourceFile.isFile) return null
        return try {
            val metadata = sourceMetadata(sourceFile)
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER_BYTES)).use { input ->
                val header = readHeader(input)
                if (header.complete != complete || header.path != metadata.path || header.length != metadata.length ||
                    header.modified != metadata.modified || !header.digest.contentEquals(sha256(sourceFile))) {
                    return null
                }
                val content = GZIPInputStream(input, BUFFER_BYTES).use { compressed ->
                    LimitedInputStream(compressed, maxExpandedBytes).use { expanded ->
                        json.decodeFromStream(BookContent.serializer(), expanded).also {
                            // Verify the gzip trailer/CRC even if a decoder stops at the JSON end.
                            if (expanded.read() != -1) throw IOException("Trailing parsed content")
                        }
                    }
                }
                if (sourceMetadata(sourceFile) != metadata || !resourcesExist(content)) return null
                file.setLastModified(System.currentTimeMillis())
                Snapshot(content, header.complete)
            }
        } catch (_: Exception) {
            // Cache files contain no user data; a miss simply uses the parser.
            null
        }
    }

    /**
     * Atomically publishes a complete book or an immutable parser preview in
     * separate files. Their combined compressed size is bounded per book.
     */
    fun save(bookId: String, sourceFile: File, content: BookContent, complete: Boolean = true): Boolean {
        if (!sourceFile.isFile) return false
        val destination = entryFile(bookId, complete)
        var temporary: File? = null
        var attemptedHeader: Header? = null
        var allowedCompressedBytes = 0L
        return try {
            val metadata = sourceMetadata(sourceFile)
            val digest = sha256(sourceFile)
            val header = Header(metadata.path, metadata.length, metadata.modified, digest, complete)
            attemptedHeader = header
            val rejection = readRejection(bookId, header)
            val otherSize = otherEntrySize(entryFile(bookId, !complete), header)
            var markerSize = rejection?.let { rejectionFile(bookId).length() } ?: 0L
            if (complete && rejection != null) {
                // A smaller replacement prefix can make a compressed full
                // entry fit. Reclaim the hint itself before that retry.
                if (rejection.reason != RejectionReason.COMPRESSED ||
                    maxEntryBytes - otherSize <= rejection.allowedCompressedBytes) return false
                if (!rejectionFile(bookId).delete()) return false
                markerSize = 0L
            }
            val availableBytes = maxEntryBytes - otherSize - markerSize
            allowedCompressedBytes = availableBytes
            if (availableBytes <= 0) return false
            if (!resourcesExist(content)) return false
            require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            // Per-book writes are serialized by the owner. A leftover temporary
            // file therefore belongs to an interrupted process, never a reader.
            destination.parentFile!!.listFiles()?.forEach { stale ->
                if ((stale.name.startsWith("content-") || stale.name.startsWith("rejection-")) &&
                    stale.name.endsWith(".tmp")) stale.delete()
            }
            temporary = File.createTempFile("content-", ".tmp", destination.parentFile)
            FileOutputStream(temporary).use { raw ->
                val counted = LimitedOutputStream(raw, availableBytes, RejectionReason.COMPRESSED)
                val output = DataOutputStream(counted)
                writeHeader(output, header)
                FastGzipOutputStream(counted).use { compressed ->
                    val expanded = LimitedOutputStream(compressed, maxExpandedBytes, RejectionReason.EXPANDED)
                    json.encodeToStream(BookContent.serializer(), content, expanded)
                    expanded.flush()
                    compressed.finish()
                    compressed.flush()
                    raw.fd.sync()
                }
            }
            if (sourceMetadata(sourceFile) != metadata) return false
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Same-directory rename remains a whole-file publication on Android.
                if (!temporary.renameTo(destination)) return false
            }
            destination.setLastModified(System.currentTimeMillis())
            if (complete) rejectionFile(bookId).delete()
            true
        } catch (error: Exception) {
            temporary?.delete()
            temporary = null
            val reason = when (error) {
                is CacheLimitException -> error.reason
                is SerializationException -> RejectionReason.UNSUPPORTED
                else -> null
            }
            // A deterministic failure should not repeat seconds of doomed
            // serialization on every cold open. IO and missing-resource failures
            // remain retryable, and a prefix remains available independently.
            if (complete && attemptedHeader != null && reason != null) {
                writeRejection(bookId, sourceFile, attemptedHeader, reason, allowedCompressedBytes)
            }
            false
        } finally {
            temporary?.delete()
        }
    }

    fun entryFile(bookId: String, complete: Boolean = true): File {
        require(bookId.isNotEmpty() && bookId != "." && bookId != ".." &&
            '/' !in bookId && '\\' !in bookId && '\u0000' !in bookId)
        return File(File(directory, bookId), if (complete) "content.bin.gz" else "prefix.bin.gz")
    }

    private fun rejectionFile(bookId: String) = File(entryFile(bookId).parentFile, "full-rejected.bin")

    private enum class RejectionReason { COMPRESSED, EXPANDED, UNSUPPORTED }
    private data class Rejection(val reason: RejectionReason, val allowedCompressedBytes: Long)

    /** A stale marker is disposable; changed limits/source/schema permit retry. */
    private fun readRejection(bookId: String, expected: Header): Rejection? {
        val file = rejectionFile(bookId)
        if (!file.isFile) return null
        val rejection = try {
            if (file.length() !in 1..MAX_REJECTION_BYTES) null
            else DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val header = readHeader(input, REJECTION_MAGIC)
                val entryLimit = input.readLong()
                val expandedLimit = input.readLong()
                val allowedCompressed = input.readLong()
                val reason = RejectionReason.entries.getOrNull(input.readUnsignedByte())
                if (!header.complete || !sameSource(header, expected) || entryLimit != maxEntryBytes ||
                    expandedLimit != maxExpandedBytes || allowedCompressed <= 0 || reason == null || input.read() != -1) null
                else Rejection(reason, allowedCompressed)
            }
        } catch (_: Exception) { null }
        if (rejection == null) file.delete()
        return rejection
    }

    private fun writeRejection(bookId: String, source: File, header: Header, reason: RejectionReason, allowedCompressed: Long) {
        if (allowedCompressed <= 0) return
        var temporary: File? = null
        try {
            if (sourceMetadata(source) != SourceMetadata(header.path, header.length, header.modified)) return
            val destination = rejectionFile(bookId)
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    writeHeader(output, header, REJECTION_MAGIC)
                    output.writeLong(maxEntryBytes)
                    output.writeLong(maxExpandedBytes)
                    output.writeLong(allowedCompressed)
                    output.writeByte(reason.ordinal)
                }
            }.toByteArray()
            // The marker shares the same per-book quota, including any older
            // valid snapshot. A rejection can never grow cache storage itself.
            val snapshotBytes = entryFile(bookId).length() + entryFile(bookId, complete = false).length()
            if (bytes.size > MAX_REJECTION_BYTES || bytes.size.toLong() > maxEntryBytes - snapshotBytes) return
            require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            temporary = File.createTempFile("rejection-", ".tmp", destination.parentFile)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            if (sourceMetadata(source) != SourceMetadata(header.path, header.length, header.modified)) return
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                temporary.renameTo(destination)
            }
        } catch (_: Exception) {
            // A marker is only a CPU-saving hint; an IO failure must not affect reading.
        } finally {
            temporary?.delete()
        }
    }

    private fun sameSource(actual: Header, expected: Header): Boolean = actual.path == expected.path &&
        actual.length == expected.length && actual.modified == expected.modified && actual.digest.contentEquals(expected.digest)

    private fun otherEntrySize(file: File, expected: Header): Long = try {
        if (!file.isFile) 0L
        else if (file.length() !in 1..maxEntryBytes) { file.delete(); 0L }
        else DataInputStream(BufferedInputStream(file.inputStream(), BUFFER_BYTES)).use { input ->
            val actual = readHeader(input)
            if (actual.complete != expected.complete && actual.path == expected.path && actual.length == expected.length &&
                actual.modified == expected.modified && actual.digest.contentEquals(expected.digest)) file.length()
            else { file.delete(); 0L }
        }
    } catch (_: Exception) { file.delete(); 0L }

    private data class SourceMetadata(val path: String, val length: Long, val modified: Long)
    private data class Header(val path: String, val length: Long, val modified: Long, val digest: ByteArray, val complete: Boolean)

    private fun sourceMetadata(file: File) = SourceMetadata(file.canonicalPath, file.length(), file.lastModified())

    private fun writeHeader(output: DataOutputStream, header: Header, magic: Int = MAGIC) {
        output.writeInt(magic)
        output.writeInt(FORMAT_VERSION)
        output.writeUTF(header.path)
        output.writeLong(header.length)
        output.writeLong(header.modified)
        output.write(header.digest)
        output.writeBoolean(header.complete)
    }

    private fun readHeader(input: DataInputStream, magic: Int = MAGIC): Header {
        if (input.readInt() != magic || input.readInt() != FORMAT_VERSION) throw IOException("Stale parsed content")
        val path = input.readUTF()
        val length = input.readLong()
        val modified = input.readLong()
        val digest = ByteArray(32).also(input::readFully)
        return Header(path, length, modified, digest, input.readBoolean())
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun resourcesExist(content: BookContent): Boolean {
        val paths = HashSet<String>()
        content.fonts.forEach { paths += it.path }
        fun text(value: androidx.compose.ui.text.AnnotatedString) {
            value.getStringAnnotations(INLINE_IMAGE_TAG, 0, value.length).forEach { paths += it.item }
        }
        fun elements(values: List<ContentElement>) {
            for (element in values) when (element) {
                is ContentElement.Image -> paths += element.path
                is ContentElement.Paragraph -> { text(element.text); element.block?.floatImage?.let { paths += it.path } }
                is ContentElement.Heading -> { text(element.styledText); element.block?.floatImage?.let { paths += it.path } }
                is ContentElement.Table -> {
                    element.block?.floatImage?.let { paths += it.path }
                    element.rows.forEach { row -> row.cells.forEach { cell ->
                        text(cell.text)
                        cell.block?.floatImage?.let { paths += it.path }
                        cell.narrowStyle?.block?.floatImage?.let { paths += it.path }
                    } }
                }
                ContentElement.Divider, is ContentElement.Spacer -> Unit
            }
        }
        content.chapters.forEach { elements(it.elements) }
        content.notes.values.forEach { elements(it.elements) }
        content.linkedDocuments.values.forEach { elements(it.elements) }
        return paths.all { File(it).isFile }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it != -1) consume(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt())
                .also { if (it > 0) consume(it) }
        private fun consume(amount: Int) {
            count += amount
            if (count > limit) throw IOException("Expanded parsed content limit")
        }
    }

    /** Background serialization should consume as little CPU as possible. */
    private class FastGzipOutputStream(output: OutputStream) : GZIPOutputStream(output, BUFFER_BYTES) {
        init { def.setLevel(Deflater.BEST_SPEED) }
    }

    private class CacheLimitException(val reason: RejectionReason) : IOException("Parsed content cache limit")

    private class LimitedOutputStream(output: OutputStream, private val limit: Long, private val reason: RejectionReason) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { consume(1); out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { consume(length); out.write(buffer, offset, length) }
        private fun consume(amount: Int) {
            if (amount.toLong() > limit - count) throw CacheLimitException(reason)
            count += amount
        }
    }

    companion object {
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
        // Bump for any parser/model behavior change that invalidates rendered content.
        private const val FORMAT_VERSION = 1
        private const val MAGIC = 0x46524350 // FRCP
        private const val REJECTION_MAGIC = 0x46524352 // FRCR
        private const val MAX_REJECTION_BYTES = 4096L
        private const val BUFFER_BYTES = 32 * 1024
    }
}
