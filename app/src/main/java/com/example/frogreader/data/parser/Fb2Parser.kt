package com.example.frogreader.data.parser

import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustFb2TxtNative
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Native Rust FB2 parser backed by `fb2_stream.rs` + `fb2_body.rs` (`quick-xml`
 * over `Fb2Sanitizer`) and `frb_model.rs`.
 *
 * Handles XML prolog/BOM encoding detection (including windows-1251, KOI8-R,
 * ISO-8859-5, UTF-16/UTF-32), HTML entity expansion, bare `&` repair,
 * tolerant mid-file truncation salvage, bounded FB2 CSS compatibility rules,
 * selective `<binary>` image extraction, and direct zero-JVM-allocation `.frb`
 * container emission.
 */
object Fb2Parser {

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(open: () -> InputStream): BookMetadata =
        parseMetadata(open, ReaderResourceLimits.DEFAULT)

    internal fun parseMetadata(
        open: () -> InputStream,
        limits: ReaderResourceLimits,
    ): BookMetadata {
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Rust FB2 metadata parser is unavailable")
        }
        val attempt = runCatching {
            val handle = RustFb2TxtNative.openMetadataParse(limits.maxCoverBytes)
            if (handle == 0L) throw IOException("Could not open native FB2 metadata parser")
            val wire = try {
                open().use { raw ->
                    val input = ThrowingBoundedInputStream(raw, limits.maxFb2Bytes, "FB2 document")
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            if (RustFb2TxtNative.feedMetadataParse(handle, EMPTY_BYTES, 0, true) == 0) {
                                throw IOException("Native FB2 metadata parse failed at EOF")
                            }
                            break
                        }
                        if (read == 0) continue
                        val status = RustFb2TxtNative.feedMetadataParse(handle, buffer, read, false)
                        if (status == 0) throw IOException("Native FB2 metadata parse failed")
                        if (status == 2) break
                    }
                }
                RustFb2TxtNative.finishMetadataParse(handle)
                    ?: throw IOException("Native FB2 metadata parse produced no result")
            } finally {
                RustFb2TxtNative.closeMetadataParse(handle)
            }
            decodeMetadataWire(wire)
        }
        attempt.exceptionOrNull()?.let { err ->
            if (err is CancellationException) throw err
            err.rethrowIfResourceLimit()
        }
        return attempt.getOrNull() ?: BookMetadata(null, null, null)
    }

    // ---------------------------------------------------------------- content

    fun parseContent(file: File, imagesDir: File): BookContent =
        parseContent(file, imagesDir, ReaderResourceLimits.DEFAULT)

    internal fun parseContent(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
        onPreview: ((BookContent) -> Unit)? = null,
        previewElementIndex: Int = 0,
    ): BookContent {
        if (onPreview != null) {
            return parseContent(
                open = { file.inputStream().buffered() },
                imagesDir = imagesDir,
                limits = limits,
                onPreview = onPreview,
                previewElementIndex = previewElementIndex,
            )
        }
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Rust FB2 parser is unavailable")
        }
        val wire = RustFb2TxtNative.parseFb2ContentFile(
            file.absolutePath,
            imagesDir.absolutePath,
            limits.toNativeFb2Array(),
        ) ?: throw IOException("Native FB2 parser returned no result")
        return decodeFullContentWire(wire)
    }

    fun parseContent(open: () -> InputStream, imagesDir: File): BookContent =
        parseContent(open, imagesDir, ReaderResourceLimits.DEFAULT)

    fun parseContent(
        open: () -> InputStream,
        imagesDir: File,
        onPreview: ((BookContent) -> Unit)?,
        previewElementIndex: Int = 0,
    ): BookContent = parseContent(
        open = open,
        imagesDir = imagesDir,
        limits = ReaderResourceLimits.DEFAULT,
        onPreview = onPreview,
        previewElementIndex = previewElementIndex,
    )

    internal fun parseContent(
        open: () -> InputStream,
        imagesDir: File,
        limits: ReaderResourceLimits,
        onPreview: ((BookContent) -> Unit)? = null,
        previewElementIndex: Int = 0,
    ): BookContent {
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Rust FB2 parser is unavailable")
        }
        val bytes = open().use { it.readBytesBounded(limits.maxFb2Bytes, "FB2 document") }
        val nativeLimits = limits.toNativeFb2Array()

        if (onPreview == null) {
            val wire = RustFb2TxtNative.parseFb2Content(bytes, imagesDir.absolutePath, nativeLimits)
                ?: throw IOException("Native FB2 parser returned no result")
            return decodeFullContentWire(wire)
        }

        val startWire = RustFb2TxtNative.startFb2ContentWithPreview(
            bytes,
            imagesDir.absolutePath,
            nativeLimits,
            previewElementIndex.coerceAtLeast(0),
        ) ?: throw IOException("Native FB2 preview parser returned no result")

        return when (val outcome = decodeContentEnvelope(startWire)) {
            is ContentEnvelope.Full -> decodeBookContent(outcome.payload)
            is ContentEnvelope.Preview -> {
                var handle = outcome.handle
                try {
                    onPreview(decodeBookContent(outcome.payload))
                    val activeHandle = handle
                    handle = 0L
                    val finishWire = RustFb2TxtNative.finishFb2ContentParse(activeHandle)
                        ?: throw IOException("Native FB2 parser failed to finish after preview")
                    decodeFullContentWire(finishWire)
                } finally {
                    if (handle != 0L) {
                        RustFb2TxtNative.closeFb2ContentParse(handle)
                    }
                }
            }
        }
    }

    /**
     * Parses [sourceFile] natively in Rust, extracts referenced binaries into [imagesDir],
     * and writes a complete CRC-checked `.frb` v2 container directly to [destFrbFile]
     * with zero JVM `BookContent` / `Chapter` allocation.
     */
    internal fun writeFb2Frb(
        sourceFile: File,
        imagesDir: File,
        destFrbFile: File,
        sourceMetadata: ByteArray,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): Boolean {
        if (!RustBridge.shared.isLibraryReady) return false
        val wire = RustFb2TxtNative.writeFb2FrbFile(
            sourceFile.absolutePath,
            imagesDir.absolutePath,
            destFrbFile.absolutePath,
            sourceMetadata,
            limits.toNativeFb2Array(),
        ) ?: return false
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < 5 || input.int != FB2_CONTENT_MAGIC) return false
        return when (input.get().toInt() and 0xff) {
            FB2_STATUS_OK_FULL -> true
            FB2_STATUS_ERR_LIMIT -> throw decodeLimitException(input, wire)
            else -> false
        }
    }

    // --------------------------------------------------------- native structural scan & targeted opening

    internal data class Fb2ByteRange(val startOffset: Long, val endOffset: Long)

    internal data class Fb2BodyStructure(
        val index: Int,
        val name: String?,
        val lang: String?,
        val titlePreview: String?,
        val startOffset: Long,
        val endOffset: Long,
    )

    internal data class Fb2SectionStructure(
        val index: Int,
        val bodyIndex: Int,
        val parentIndex: Int,
        val depth: Int,
        val id: String?,
        val lang: String?,
        val titlePreview: String?,
        val startOffset: Long,
        val endOffset: Long,
        val estimatedEndElements: Int = 0,
    )

    internal data class Fb2BinaryStructure(
        val id: String,
        val contentType: String?,
        val startOffset: Long,
        val payloadStartOffset: Long,
        val payloadEndOffset: Long,
        val endOffset: Long,
    )

    internal data class Fb2Structure(
        val detectedEncoding: String,
        val sanitizedByteLength: Long,
        val bookLanguage: String?,
        val coverId: String?,
        val rootTagRange: Fb2ByteRange?,
        val descriptionRange: Fb2ByteRange?,
        val stylesheets: List<Fb2ByteRange>,
        val bodies: List<Fb2BodyStructure>,
        val sections: List<Fb2SectionStructure>,
        val binaries: List<Fb2BinaryStructure>,
        val maxSectionDepth: Int,
    )

    /**
     * Performs a fast native structural scan of an FB2 stream via `quick-xml`,
     * recording `<body>`, nested `<section>`, `<title>` previews, and `<binary>`
     * byte ranges without Base64-decoding binary payloads.
     */
    internal fun scanStructure(
        open: () -> InputStream,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): Fb2Structure {
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Rust FB2 structural scanner is unavailable")
        }
        val handle = RustFb2TxtNative.openStructureScan()
        if (handle == 0L) throw IOException("Could not open native FB2 structure scanner")
        val wire = try {
            open().use { raw ->
                val input = ThrowingBoundedInputStream(raw, limits.maxFb2Bytes, "FB2 document")
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0 && !RustFb2TxtNative.feedStructureScan(handle, buffer, read, false)) {
                        throw IOException("Native FB2 structural scan failed")
                    }
                }
                if (!RustFb2TxtNative.feedStructureScan(handle, EMPTY_BYTES, 0, true)) {
                    throw IOException("Native FB2 structural scan failed at EOF")
                }
            }
            RustFb2TxtNative.finishStructureScan(handle)
                ?: throw IOException("Native FB2 structural scan produced no result")
        } finally {
            RustFb2TxtNative.closeStructureScan(handle)
        }
        val structure = decodeStructureWire(wire)
        if (structure.maxSectionDepth > limits.maxFb2StructuralDepth) {
            throw ResourceLimitException(
                ResourceLimitKind.STRUCTURAL_DEPTH,
                "FB2 section nesting exceeds ${limits.maxFb2StructuralDepth} levels",
            )
        }
        return structure
    }

    /**
     * Parses only the selected [sectionIndices] natively in Rust using structural
     * offsets, skipping all other sections and unreferenced `<binary>` payloads.
     */
    internal fun parseTargetedSections(
        open: () -> InputStream,
        structure: Fb2Structure,
        sectionIndices: List<Int>,
        imagesDir: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): BookContent {
        val validIndices = sectionIndices.asSequence()
            .distinct()
            .filter { it in structure.sections.indices }
            .toList()
        if (validIndices.isEmpty()) {
            return BookContent(
                chapters = emptyList(),
                language = LanguageTag.normalize(structure.bookLanguage),
            )
        }
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Rust FB2 parser is unavailable")
        }
        val bytes = open().use { it.readBytesBounded(limits.maxFb2Bytes, "FB2 document") }
        val wire = RustFb2TxtNative.parseFb2TargetedSections(
            bytes,
            validIndices.toIntArray(),
            imagesDir.absolutePath,
            limits.toNativeFb2Array(),
        ) ?: throw IOException("Native FB2 targeted section parser returned no result")
        return decodeFullContentWire(wire)
    }

    // ---------------------------------------------------------------- wire decoders

    private sealed interface ContentEnvelope {
        class Full(val payload: ByteArray) : ContentEnvelope
        class Preview(val handle: Long, val payload: ByteArray) : ContentEnvelope
    }

    private fun decodeFullContentWire(wire: ByteArray): BookContent =
        when (val envelope = decodeContentEnvelope(wire)) {
            is ContentEnvelope.Full -> decodeBookContent(envelope.payload)
            is ContentEnvelope.Preview -> {
                RustFb2TxtNative.closeFb2ContentParse(envelope.handle)
                throw IOException("Unexpected preview response from full FB2 parse")
            }
        }

    private fun decodeBookContent(payload: ByteArray): BookContent =
        FrbModelCodec.decode(payload, BookContent.serializer())

    private fun decodeContentEnvelope(wire: ByteArray): ContentEnvelope {
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < 5 || input.int != FB2_CONTENT_MAGIC) {
            throw IOException("Invalid native FB2 content magic")
        }
        return when (input.get().toInt() and 0xff) {
            FB2_STATUS_OK_FULL -> ContentEnvelope.Full(readLengthPrefixedBytes(input, wire))
            FB2_STATUS_OK_PREVIEW -> {
                if (input.remaining() < Long.SIZE_BYTES) {
                    throw IOException("Truncated native FB2 preview handle")
                }
                val handle = input.long
                val payload = readLengthPrefixedBytes(input, wire)
                ContentEnvelope.Preview(handle, payload)
            }
            FB2_STATUS_ERR_LIMIT -> throw decodeLimitException(input, wire)
            FB2_STATUS_ERR_IO -> {
                val msg = readLengthPrefixedString(input, wire)
                throw IOException(msg)
            }
            else -> throw IOException("Unknown native FB2 content status")
        }
    }

    private fun decodeLimitException(input: ByteBuffer, wire: ByteArray): ResourceLimitException {
        if (input.remaining() < 1) {
            throw IOException("Truncated native FB2 limit kind")
        }
        val kindCode = input.get().toInt() and 0xff
        val kind = ResourceLimitKind.entries.getOrNull(kindCode) ?: ResourceLimitKind.ENTRY_SIZE
        val message = readLengthPrefixedString(input, wire)
        return ResourceLimitException(kind, message)
    }

    private fun readLengthPrefixedBytes(input: ByteBuffer, wire: ByteArray): ByteArray {
        if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated native FB2 payload length")
        val len = input.int
        if (len < 0 || len > input.remaining()) throw IOException("Invalid native FB2 payload length")
        val start = input.position()
        input.position(start + len)
        if (input.hasRemaining()) throw IOException("Trailing native FB2 content bytes")
        return wire.copyOfRange(start, start + len)
    }

    private fun readLengthPrefixedString(input: ByteBuffer, wire: ByteArray): String {
        if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated native FB2 message length")
        val len = input.int
        if (len < 0 || len > input.remaining()) throw IOException("Invalid native FB2 message length")
        val start = input.position()
        input.position(start + len)
        return wire.decodeToString(start, start + len, throwOnInvalidSequence = false)
    }

    private fun ReaderResourceLimits.toNativeFb2Array(): LongArray = longArrayOf(
        maxFb2Bytes,
        maxFb2StructuralDepth.toLong(),
        maxFb2StylesheetCount.toLong(),
        maxStylesheetBytes,
        maxFb2StylesheetAggregateBytes,
        maxFb2BinaryCount.toLong(),
        maxFb2BinaryBytes,
        maxFb2BinaryAggregateBytes,
    )

    private fun decodeStructureWire(wire: ByteArray): Fb2Structure {
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        fun int(): Int {
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated FB2 structure")
            return input.int
        }
        fun uInt(): Int {
            val v = int()
            if (v < 0) throw IOException("Invalid FB2 structure count")
            return v
        }
        fun long(): Long {
            if (input.remaining() < Long.SIZE_BYTES) throw IOException("Truncated FB2 structure offset")
            val v = input.long
            if (v < 0L) throw IOException("Invalid FB2 structure offset")
            return v
        }
        fun str(): String {
            val size = uInt()
            if (size > input.remaining()) throw IOException("Truncated FB2 structure string")
            if (size == 0) return ""
            val start = input.position()
            input.position(start + size)
            return wire.decodeToString(start, start + size, throwOnInvalidSequence = true)
        }
        fun optRange(): Fb2ByteRange? {
            if (input.remaining() < 1) throw IOException("Truncated FB2 structure flag")
            return when (input.get().toInt() and 0xff) {
                0 -> null
                1 -> Fb2ByteRange(long(), long())
                else -> throw IOException("Invalid FB2 structure range flag")
            }
        }

        if (int() != FB2_SCAN_MAGIC) throw IOException("Invalid FB2 structure magic")
        val detectedEncoding = str()
        val sanitizedByteLen = long()
        val bookLanguage = str().takeIf { it.isNotEmpty() }
        val coverId = str().takeIf { it.isNotEmpty() }
        val maxSectionDepth = uInt()
        val rootTagRange = optRange()
        val descriptionRange = optRange()

        val stylesheetCount = uInt()
        if (stylesheetCount > input.remaining() / (2 * Long.SIZE_BYTES)) {
            throw IOException("Invalid FB2 stylesheet count")
        }
        val stylesheets = List(stylesheetCount) { Fb2ByteRange(long(), long()) }

        val bodyCount = uInt()
        val bodies = List(bodyCount) {
            Fb2BodyStructure(
                index = uInt(),
                name = str().takeIf { it.isNotEmpty() },
                lang = str().takeIf { it.isNotEmpty() },
                titlePreview = str().takeIf { it.isNotEmpty() },
                startOffset = long(),
                endOffset = long(),
            )
        }

        val sectionCount = uInt()
        val sections = List(sectionCount) {
            Fb2SectionStructure(
                index = uInt(),
                bodyIndex = uInt(),
                parentIndex = int(),
                depth = uInt(),
                id = str().takeIf { it.isNotEmpty() },
                lang = str().takeIf { it.isNotEmpty() },
                titlePreview = str().takeIf { it.isNotEmpty() },
                startOffset = long(),
                endOffset = long(),
                estimatedEndElements = uInt(),
            )
        }

        val binaryCount = uInt()
        val binaries = List(binaryCount) {
            Fb2BinaryStructure(
                id = str(),
                contentType = str().takeIf { it.isNotEmpty() },
                startOffset = long(),
                payloadStartOffset = long(),
                payloadEndOffset = long(),
                endOffset = long(),
            )
        }

        if (input.hasRemaining()) throw IOException("Trailing FB2 structure bytes")
        return Fb2Structure(
            detectedEncoding = detectedEncoding,
            sanitizedByteLength = sanitizedByteLen,
            bookLanguage = bookLanguage,
            coverId = coverId,
            rootTagRange = rootTagRange,
            descriptionRange = descriptionRange,
            stylesheets = stylesheets,
            bodies = bodies,
            sections = sections,
            binaries = binaries,
            maxSectionDepth = maxSectionDepth,
        )
    }

    internal fun decodeMetadataWire(wire: ByteArray): BookMetadata {
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        fun byte(): Int {
            if (input.remaining() < 1) throw IOException("Truncated FB2 metadata flag")
            return input.get().toInt() and 0xff
        }
        fun int(): Int {
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated FB2 metadata int")
            return input.int
        }
        fun uInt(): Int {
            val v = int()
            if (v < 0) throw IOException("Invalid FB2 metadata count")
            return v
        }
        fun str(): String {
            val size = uInt()
            if (size > input.remaining()) throw IOException("Truncated FB2 metadata string")
            if (size == 0) return ""
            val start = input.position()
            input.position(start + size)
            return wire.decodeToString(start, start + size, throwOnInvalidSequence = true)
        }
        fun optStr(): String? = when (byte()) {
            0 -> null
            1 -> str()
            else -> throw IOException("Invalid FB2 metadata optional string flag")
        }
        fun strList(): List<String> {
            val count = uInt()
            if (count > input.remaining() / Int.SIZE_BYTES) {
                throw IOException("Invalid FB2 metadata string list count")
            }
            return List(count) { str() }
        }

        if (int() != FB2_META_MAGIC) throw IOException("Invalid FB2 metadata magic")
        val title = optStr()
        val authors = strList()
        val genres = strList()
        val series = optStr()
        val seriesNumber = when (byte()) {
            0 -> null
            1 -> {
                if (input.remaining() < Float.SIZE_BYTES) throw IOException("Truncated FB2 series number")
                input.float
            }
            else -> throw IOException("Invalid FB2 series number flag")
        }
        val translators = strList()
        val publisher = optStr()
        val year = optStr()
        val isbn = optStr()
        val description = optStr()
        val language = optStr()
        val coverId = optStr()
        val coverBytes = when (byte()) {
            0 -> null
            1 -> {
                val len = uInt()
                if (len > input.remaining()) throw IOException("Truncated FB2 cover bytes")
                val bytes = ByteArray(len)
                input.get(bytes)
                bytes
            }
            else -> throw IOException("Invalid FB2 cover bytes flag")
        }
        val customCount = uInt()
        if (customCount > input.remaining() / (2 * Int.SIZE_BYTES)) {
            throw IOException("Invalid FB2 custom-info count")
        }
        val customInfo = LinkedHashMap<String, String>()
        repeat(customCount) {
            val key = str()
            val value = str()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                customInfo.putIfAbsent(key, value)
            }
        }
        if (input.hasRemaining()) throw IOException("Trailing FB2 metadata bytes")
        @Suppress("UNUSED_VARIABLE")
        val ignoredCoverId = coverId
        return BookMetadata(
            title = title,
            author = authors.firstOrNull(),
            coverBytes = coverBytes,
            authors = authors,
            genres = genres,
            series = series,
            seriesNumber = seriesNumber,
            publisher = publisher,
            year = year,
            isbn = isbn,
            translators = translators,
            description = description,
            language = language,
            additionalMetadata = if (customInfo.isEmpty()) emptyMap() else customInfo.toMap(),
        )
    }

    private const val FB2_SCAN_MAGIC = 0x31324246 // FB21
    private const val FB2_META_MAGIC = 0x4D324246 // FB2M
    private const val FB2_CONTENT_MAGIC = 0x43324246 // FB2C
    private const val FB2_STATUS_OK_FULL = 0
    private const val FB2_STATUS_OK_PREVIEW = 1
    private const val FB2_STATUS_ERR_LIMIT = 2
    private const val FB2_STATUS_ERR_IO = 3
    private const val STREAM_CHUNK_BYTES = 64 * 1024
    private val EMPTY_BYTES = ByteArray(0)
}
