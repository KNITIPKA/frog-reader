package com.example.frogreader.data.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Generous, device-oriented ceilings for untrusted ebook containers.
 *
 * They are deliberately much larger than normal trade books. The goal is not
 * to reject unusually rich publications; it is to put a finite upper bound on
 * allocations, decompression work and cache extraction when a damaged or
 * hostile file reports implausible sizes.
 */
internal data class ReaderResourceLimits(
    val maxArchiveEntries: Int = 20_000,
    val maxArchiveDeclaredBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxArchiveReadBytes: Long = 768L * 1024 * 1024,
    val maxCompressionRatio: Long = 2_000,
    val compressionRatioCheckFromBytes: Long = 8L * 1024 * 1024,
    val maxPackageXmlBytes: Long = 16L * 1024 * 1024,
    val maxChapterBytes: Long = 32L * 1024 * 1024,
    val maxStylesheetBytes: Long = 8L * 1024 * 1024,
    val maxCoverBytes: Long = 32L * 1024 * 1024,
    val maxImageBytes: Long = 64L * 1024 * 1024,
    val maxFontBytes: Long = 32L * 1024 * 1024,
    val maxFb2Bytes: Long = 256L * 1024 * 1024,
    val maxFb2SanitizedBytes: Long = 64L * 1024 * 1024,
    val maxFb2BinaryBytes: Long = 64L * 1024 * 1024,
    val maxFb2BinaryAggregateBytes: Long = 256L * 1024 * 1024,
    val maxFb2BinaryCount: Int = 4_096,
    val maxFb2StructuralDepth: Int = 256,
    val maxFb2StylesheetCount: Int = 256,
    val maxFb2StylesheetAggregateBytes: Long = 32L * 1024 * 1024,
    val maxMobiRecords: Int = 20_000,
    val maxMobiRecordBytes: Long = 64L * 1024 * 1024,
    val maxMobiIndexRecordBytes: Long = 16L * 1024 * 1024,
    val maxMobiReadAggregateBytes: Long = 768L * 1024 * 1024,
    val maxMobiTextBytes: Long = 32L * 1024 * 1024,
    val maxMobiHuffDictionaryBytes: Long = 32L * 1024 * 1024,
    val maxMobiIndexEntries: Int = 100_000,
    val maxMobiIndexValues: Int = 250_000,
    val maxMobiTagxEntries: Int = 1_024,
    val maxMobiCncxEntries: Int = 100_000,
    val maxMobiCncxBytes: Long = 32L * 1024 * 1024,
    val maxKf8Parts: Int = 20_000,
    val maxKf8Fragments: Int = 250_000,
    val maxKf8PartBytes: Long = 32L * 1024 * 1024,
    val maxKf8AssembledBytes: Long = 64L * 1024 * 1024,
    val maxKf8FlowAggregateBytes: Long = 64L * 1024 * 1024,
    val maxKf8Markers: Int = 100_000,
    val maxKf8MarkerExpansionBytes: Long = 8L * 1024 * 1024,
    val maxKf8CssFlowBytes: Long = 8L * 1024 * 1024,
    val maxKf8CssAggregateBytes: Long = 64L * 1024 * 1024,
    val maxKf8CssExpandedBytes: Long = 128L * 1024 * 1024,
    val maxKf8CssExpandedSheets: Int = 4_096,
    val maxKf8CssExpansionOperations: Int = 16_384,
    val maxKindleCssMediaDepth: Int = 256,
    val maxKindleCssMediaOperations: Int = 16_384,
    val maxEpubCssExpandedSheets: Int = 4_096,
    val maxEpubCssExpansionOperations: Int = 16_384,
    val maxEpubCssExpandedBytes: Long = 128L * 1024 * 1024,
    val maxHtmlGeneratedRunChars: Int = HtmlExpansionBudget.DEFAULT_MAX_GENERATED_RUN_CHARS,
    val maxHtmlGeneratedTotalChars: Long = HtmlExpansionBudget.DEFAULT_MAX_GENERATED_TOTAL_CHARS,
) {
    init {
        require(maxArchiveEntries > 0)
        require(maxArchiveDeclaredBytes > 0)
        require(maxArchiveReadBytes > 0)
        require(maxCompressionRatio > 0)
        require(compressionRatioCheckFromBytes >= 0)
        require(maxPackageXmlBytes in 1..Int.MAX_VALUE.toLong())
        require(maxChapterBytes in 1..Int.MAX_VALUE.toLong())
        require(maxStylesheetBytes in 1..Int.MAX_VALUE.toLong())
        require(maxCoverBytes in 1..Int.MAX_VALUE.toLong())
        require(maxImageBytes > 0)
        require(maxFontBytes in 1..Int.MAX_VALUE.toLong())
        require(maxFb2Bytes > 0)
        require(maxFb2SanitizedBytes in 1..Int.MAX_VALUE.toLong())
        require(maxFb2BinaryBytes in 1..Int.MAX_VALUE.toLong())
        require(maxFb2BinaryAggregateBytes > 0)
        require(maxFb2BinaryCount > 0)
        require(maxFb2StructuralDepth > 0)
        require(maxFb2StylesheetCount > 0)
        require(maxFb2StylesheetAggregateBytes > 0)
        require(maxMobiRecords > 0)
        require(maxMobiRecordBytes in 1..Int.MAX_VALUE.toLong())
        require(maxMobiIndexRecordBytes in 1..Int.MAX_VALUE.toLong())
        require(maxMobiReadAggregateBytes > 0)
        require(maxMobiTextBytes in 1..Int.MAX_VALUE.toLong())
        require(maxMobiHuffDictionaryBytes in 1..Int.MAX_VALUE.toLong())
        require(maxMobiIndexEntries > 0)
        require(maxMobiIndexValues > 0)
        require(maxMobiTagxEntries > 0)
        require(maxMobiCncxEntries > 0)
        require(maxMobiCncxBytes > 0)
        require(maxKf8Parts > 0)
        require(maxKf8Fragments > 0)
        require(maxKf8PartBytes in 1..Int.MAX_VALUE.toLong())
        require(maxKf8AssembledBytes > 0)
        require(maxKf8FlowAggregateBytes > 0)
        require(maxKf8Markers > 0)
        require(maxKf8MarkerExpansionBytes > 0)
        require(maxKf8CssFlowBytes in 1..Int.MAX_VALUE.toLong())
        require(maxKf8CssAggregateBytes > 0)
        require(maxKf8CssExpandedBytes > 0)
        require(maxKf8CssExpandedSheets > 0)
        require(maxKf8CssExpansionOperations > 0)
        require(maxKindleCssMediaDepth > 0)
        require(maxKindleCssMediaOperations > 0)
        require(maxEpubCssExpandedSheets > 0)
        require(maxEpubCssExpansionOperations > 0)
        require(maxEpubCssExpandedBytes > 0)
        require(maxHtmlGeneratedRunChars > 0)
        require(maxHtmlGeneratedTotalChars > 0)
    }

    companion object {
        val DEFAULT = ReaderResourceLimits()
    }
}

internal enum class ResourceLimitKind {
    ENTRY_COUNT,
    DECLARED_AGGREGATE,
    ACTUAL_AGGREGATE,
    ENTRY_SIZE,
    COMPRESSION_RATIO,
    UNSAFE_PATH,
    STRUCTURAL_DEPTH,
}

internal class ResourceLimitException(
    val kind: ResourceLimitKind,
    message: String,
) : IOException(message)

/** Finds the original limit failure even when an XML parser wraps it. */
private fun Throwable.resourceLimitFailure(): ResourceLimitException? {
    var current: Throwable? = this
    repeat(8) {
        if (current is ResourceLimitException) return current
        val next = current?.cause
        if (next == null || next === current) return null
        current = next
    }
    return null
}

internal fun Throwable.isResourceLimitFailure(): Boolean = resourceLimitFailure() != null

internal fun Throwable.rethrowIfResourceLimit() {
    resourceLimitFailure()?.let { throw it }
}

/**
 * A per-open EPUB/ZIP budget. Every decompressed byte consumed through this
 * object counts toward one aggregate, including entries whose central
 * directory did not declare a size.
 */
internal class ArchiveResourceBudget(
    private val zip: ZipFile,
    private val limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
) {
    private var bytesRead = 0L

    init {
        validateDirectory()
    }

    private fun validateDirectory() {
        if (zip.size() > limits.maxArchiveEntries) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_COUNT,
                "Archive has ${zip.size()} entries; limit is ${limits.maxArchiveEntries}",
            )
        }
        var declared = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val size = entries.nextElement().size
            if (size <= 0L) continue
            if (size > limits.maxArchiveDeclaredBytes - declared) {
                throw ResourceLimitException(
                    ResourceLimitKind.DECLARED_AGGREGATE,
                    "Archive declares more than ${limits.maxArchiveDeclaredBytes} uncompressed bytes",
                )
            }
            declared += size
        }
    }

    fun readRequired(entry: ZipEntry, maxBytes: Long, label: String): ByteArray =
        read(entry, maxBytes, label, optional = false)!!

    fun readOptional(entry: ZipEntry, maxBytes: Long, label: String): ByteArray? =
        read(entry, maxBytes, label, optional = true)

    private fun read(
        entry: ZipEntry,
        maxBytes: Long,
        label: String,
        optional: Boolean,
    ): ByteArray? {
        val allowed = runCatching { validateEntry(entry, maxBytes, label) }
        if (allowed.isFailure) {
            val error = allowed.exceptionOrNull()!!
            if (optional && error is ResourceLimitException &&
                error.kind != ResourceLimitKind.ACTUAL_AGGREGATE
            ) {
                return null
            }
            throw error
        }
        return try {
            zip.getInputStream(entry).use { input -> readBounded(input, maxBytes, label) }
        } catch (error: ResourceLimitException) {
            if (optional && error.kind == ResourceLimitKind.ENTRY_SIZE) null else throw error
        } catch (error: IOException) {
            // CRC/truncation failures in decorative resources should not make
            // an otherwise readable book fail. Required package/spine data
            // still propagates the original IOException.
            if (optional) null else throw error
        }
    }

    fun copyRequired(entry: ZipEntry, target: File, maxBytes: Long, label: String) {
        copy(entry, target, maxBytes, label, optional = false)
    }

    fun copyOptional(entry: ZipEntry, target: File, maxBytes: Long, label: String): Boolean =
        copy(entry, target, maxBytes, label, optional = true)

    private fun copy(
        entry: ZipEntry,
        target: File,
        maxBytes: Long,
        label: String,
        optional: Boolean,
    ): Boolean {
        val allowed = runCatching { validateEntry(entry, maxBytes, label) }
        if (allowed.isFailure) {
            val error = allowed.exceptionOrNull()!!
            if (optional && error is ResourceLimitException &&
                error.kind != ResourceLimitKind.ACTUAL_AGGREGATE
            ) {
                return false
            }
            throw error
        }

        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".tmp")
        partial.delete()
        val archiveInput = try {
            zip.getInputStream(entry)
        } catch (error: IOException) {
            partial.delete()
            if (optional) return false
            throw error
        }
        try {
            val input = if (optional) OptionalArchiveInputStream(archiveInput) else archiveInput
            input.use {
                partial.outputStream().buffered().use { output ->
                    copyBounded(input, output, maxBytes, label)
                }
            }
            if (!partial.renameTo(target)) {
                throw IOException("Could not publish extracted $label")
            }
            return true
        } catch (error: ResourceLimitException) {
            partial.delete()
            if (optional && error.kind == ResourceLimitKind.ENTRY_SIZE) return false
            throw error
        } catch (_: OptionalArchiveReadException) {
            partial.delete()
            return false
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun validateEntry(entry: ZipEntry, maxBytes: Long, label: String) {
        if (!isSafeArchivePath(entry.name)) {
            throw ResourceLimitException(
                ResourceLimitKind.UNSAFE_PATH,
                "Unsafe archive path for $label: ${entry.name}",
            )
        }
        val declared = entry.size
        if (declared > maxBytes) {
            throw ResourceLimitException(
                ResourceLimitKind.ENTRY_SIZE,
                "$label declares $declared bytes; limit is $maxBytes",
            )
        }
        val compressed = entry.compressedSize
        if (declared >= limits.compressionRatioCheckFromBytes && compressed >= 0L &&
            declared > saturatedMultiply(maxOf(1L, compressed), limits.maxCompressionRatio)
        ) {
            throw ResourceLimitException(
                ResourceLimitKind.COMPRESSION_RATIO,
                "$label has an implausible compression ratio",
            )
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long, label: String): ByteArray {
        val initial = minOf(maxBytes, 64L * 1024, Int.MAX_VALUE.toLong()).toInt()
        val output = ByteArrayOutputStream(initial)
        copyBounded(input, output, maxBytes, label)
        return output.toByteArray()
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        label: String,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            if (entryBytes > maxBytes - count) {
                throw ResourceLimitException(
                    ResourceLimitKind.ENTRY_SIZE,
                    "$label expands beyond $maxBytes bytes",
                )
            }
            if (bytesRead > limits.maxArchiveReadBytes - count) {
                throw ResourceLimitException(
                    ResourceLimitKind.ACTUAL_AGGREGATE,
                    "Archive expands beyond ${limits.maxArchiveReadBytes} bytes during this open",
                )
            }
            output.write(buffer, 0, count)
            entryBytes += count
            bytesRead += count
        }
    }
}

/** Marks only ZIP/input failures, so optional extraction never hides disk IO errors. */
private class OptionalArchiveReadException(cause: IOException) : IOException(cause)

private class OptionalArchiveInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = archiveRead { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        archiveRead { super.read(buffer, offset, length) }

    override fun close() = archiveRead { super.close() }

    private inline fun <T> archiveRead(block: () -> T): T = try {
        block()
    } catch (error: ResourceLimitException) {
        throw error
    } catch (error: IOException) {
        throw OptionalArchiveReadException(error)
    }
}

/** Reject absolute, drive-letter, NUL, backslash and parent-traversal names. */
internal fun isSafeArchivePath(path: String): Boolean {
    if (path.isEmpty() || '\u0000' in path || '\\' in path || path.startsWith('/')) return false
    if (DRIVE_PATH.matches(path.substringBefore('/'))) return false
    return path.split('/').none { it == ".." }
}

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

private val DRIVE_PATH = Regex("[A-Za-z]:.*")

/**
 * A stream that throws on the first byte beyond [maxBytes]. It never turns a
 * hostile oversized document into an apparently valid truncated one.
 */
internal class ThrowingBoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val label: String,
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        if (count >= maxBytes) return overflowOrEof()
        val value = delegate.read()
        if (value >= 0) count++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (count >= maxBytes) return overflowOrEof()
        val allowed = minOf(length.toLong(), maxBytes - count).toInt()
        val read = delegate.read(buffer, offset, allowed)
        if (read > 0) count += read
        return read
    }

    private fun overflowOrEof(): Int {
        val extra = delegate.read()
        if (extra < 0) return -1
        throw ResourceLimitException(
            ResourceLimitKind.ENTRY_SIZE,
            "$label exceeds $maxBytes bytes",
        )
    }

    override fun close() = delegate.close()
}

internal fun InputStream.readBytesBounded(maxBytes: Long, label: String): ByteArray =
    ThrowingBoundedInputStream(this, maxBytes, label).use { bounded -> bounded.readBytes() }
