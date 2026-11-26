package com.example.frogreader.data.parser

import androidx.annotation.Keep
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustEpubPackageNative
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Typed view of the fields read by the native EPUB package parser. */
@Keep
internal object RustEpubPackage {
    data class Package(
        val version: String,
        val uniqueIdentifier: String,
        val spineToc: String,
        val pageProgressionDirection: String,
        val metadata: List<Metadata>,
        val manifest: List<Manifest>,
        val itemRefs: List<ItemRef>,
    )

    data class Metadata(
        val tag: String,
        val text: String,
        val id: String,
        val role: String,
        val scheme: String,
        val name: String,
        val property: String,
        val refines: String,
        val content: String,
    )

    data class Manifest(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val fallback: String,
    )

    data class ItemRef(val idref: String, val linear: String, val id: String, val properties: String)
    data class NavigationPoint(
        val depth: Int,
        val source: String,
        val title: String,
        val parentTitle: String = "",
    )

    fun rootfile(xml: ByteArray): String? {
        requireLibrary()
        return RustEpubPackageNative.rootfile(xml)
    }

    fun parse(xml: ByteArray): Package {
        requireLibrary()
        val bytes = RustEpubPackageNative.packageData(xml) ?: throw IOException("Invalid EPUB package XML")
        val input = PackageFields(bytes, OPF_SIGNATURE)
        return Package(
            version = input.string(),
            uniqueIdentifier = input.string(),
            spineToc = input.string(),
            pageProgressionDirection = input.string(),
            metadata = input.rows(fields = 9) {
                Metadata(string(), string(), string(), string(), string(), string(), string(), string(), string())
            },
            manifest = input.rows(fields = 5) { Manifest(string(), string(), string(), string(), string()) },
            itemRefs = input.rows(fields = 4) { ItemRef(string(), string(), string(), string()) },
        ).also { input.finish() }
    }

    fun ncx(xml: ByteArray): List<NavigationPoint> {
        requireLibrary()
        val bytes = RustEpubPackageNative.ncxData(xml) ?: return emptyList()
        val input = PackageFields(bytes, NCX_SIGNATURE)
        return input.rows(fields = 3) { NavigationPoint(unsignedInt(), string(), string()) }
            .also { input.finish() }
    }

    fun nav(xml: ByteArray): List<NavigationPoint> {
        requireLibrary()
        val bytes = RustEpubPackageNative.navData(xml) ?: return emptyList()
        val input = PackageFields(bytes, NAV_SIGNATURE)
        return input.rows(fields = 4) { NavigationPoint(unsignedInt(), string(), string(), string()) }
            .also { input.finish() }
    }

    fun parseEncryption(bytes: ByteArray): List<Pair<String, String>>? {
        requireLibrary()
        val wire = RustEpubPackageNative.parseEncryption(bytes) ?: return null
        val input = PackageFields(wire, ENC_SIGNATURE)
        return input.rows(fields = 2) { string() to string() }
            .also { input.finish() }
    }

    sealed class NativeContentResult {
        class Ready(val payload: ByteArray) : NativeContentResult()
        class Fallback(val reason: String) : NativeContentResult()
    }

    sealed class NativeWriteResult {
        object Written : NativeWriteResult()
        class Fallback(val reason: String) : NativeWriteResult()
    }

    @Keep
    external fun parseEpubContentNative(
        epubPath: String,
        imagesDir: String,
        limits: LongArray,
    ): ByteArray?

    @Keep
    external fun writeEpubFrbNative(
        epubPath: String,
        imagesDir: String,
        destFrbPath: String,
        sourceMetadata: ByteArray,
        limits: LongArray,
    ): ByteArray?

    fun parseEpubContent(
        epubFile: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
    ): NativeContentResult {
        if (!RustBridge.shared.isLibraryReady) {
            return NativeContentResult.Fallback("Rust EPUB engine unavailable")
        }
        val wire = parseEpubContentNative(
            epubPath = epubFile.absolutePath,
            imagesDir = imagesDir.absolutePath,
            limits = limits.toNativeEpubArray(),
        ) ?: return NativeContentResult.Fallback("Native EPUB parse returned null")
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < 5 || input.int != EPBC_SIGNATURE) {
            return NativeContentResult.Fallback("Invalid EPBC wire header")
        }
        return when (input.get().toInt() and 0xff) {
            EPBC_STATUS_OK -> {
                val payload = readWireBytes(input, wire)
                    ?: return NativeContentResult.Fallback("Truncated EPBC payload")
                NativeContentResult.Ready(payload)
            }
            EPBC_STATUS_FALLBACK -> {
                val reason = readWireString(input, wire) ?: "fallback"
                NativeContentResult.Fallback(reason)
            }
            EPBC_STATUS_LIMIT -> throw decodeLimitException(input, wire)
            else -> {
                val msg = readWireString(input, wire) ?: "native error"
                NativeContentResult.Fallback(msg)
            }
        }
    }

    fun writeEpubFrbWithReason(
        sourceFile: File,
        imagesDir: File,
        destFrbFile: File,
        sourceMetadata: ByteArray,
        limits: ReaderResourceLimits,
    ): NativeWriteResult {
        if (!RustBridge.shared.isLibraryReady) {
            return NativeWriteResult.Fallback("Rust EPUB engine unavailable")
        }
        val wire = writeEpubFrbNative(
            epubPath = sourceFile.absolutePath,
            imagesDir = imagesDir.absolutePath,
            destFrbPath = destFrbFile.absolutePath,
            sourceMetadata = sourceMetadata,
            limits = limits.toNativeEpubArray(),
        ) ?: return NativeWriteResult.Fallback("Native EPUB FRB write returned null")
        val input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < 5 || input.int != EPBC_SIGNATURE) {
            return NativeWriteResult.Fallback("Invalid EPBC wire header")
        }
        return when (input.get().toInt() and 0xff) {
            EPBC_STATUS_OK -> NativeWriteResult.Written
            EPBC_STATUS_FALLBACK -> {
                val reason = readWireString(input, wire) ?: "fallback"
                NativeWriteResult.Fallback(reason)
            }
            EPBC_STATUS_LIMIT -> throw decodeLimitException(input, wire)
            else -> {
                val msg = readWireString(input, wire) ?: "native error"
                NativeWriteResult.Fallback(msg)
            }
        }
    }

    fun writeEpubFrb(
        sourceFile: File,
        imagesDir: File,
        destFrbFile: File,
        sourceMetadata: ByteArray,
        limits: ReaderResourceLimits,
    ): Boolean = writeEpubFrbWithReason(
        sourceFile = sourceFile,
        imagesDir = imagesDir,
        destFrbFile = destFrbFile,
        sourceMetadata = sourceMetadata,
        limits = limits,
    ) is NativeWriteResult.Written

    private fun ReaderResourceLimits.toNativeEpubArray(): LongArray = longArrayOf(
        maxArchiveEntries.toLong(),
        maxArchiveDeclaredBytes,
        maxArchiveReadBytes,
        maxCompressionRatio,
        compressionRatioCheckFromBytes,
        maxPackageXmlBytes,
        maxChapterBytes,
        maxStylesheetBytes,
        maxImageBytes,
        maxFontBytes,
        maxEpubCssExpandedSheets.toLong(),
        maxEpubCssExpansionOperations.toLong(),
        maxEpubCssExpandedBytes,
        maxHtmlGeneratedRunChars.toLong(),
        maxHtmlGeneratedTotalChars,
    )

    private fun readWireBytes(input: ByteBuffer, wire: ByteArray): ByteArray? {
        if (input.remaining() < Int.SIZE_BYTES) return null
        val size = input.int
        if (size < 0 || size > input.remaining()) return null
        val start = input.position()
        input.position(start + size)
        return wire.copyOfRange(start, start + size)
    }

    private fun readWireString(input: ByteBuffer, wire: ByteArray): String? {
        if (input.remaining() < Int.SIZE_BYTES) return null
        val size = input.int
        if (size < 0 || size > input.remaining()) return null
        val start = input.position()
        input.position(start + size)
        return wire.decodeToString(start, start + size, throwOnInvalidSequence = false)
    }

    private fun decodeLimitException(input: ByteBuffer, wire: ByteArray): ResourceLimitException {
        val kindCode = if (input.remaining() >= 1) input.get().toInt() and 0xff else -1
        val message = readWireString(input, wire) ?: "EPUB resource limit exceeded"
        val kind = when (kindCode) {
            0 -> ResourceLimitKind.ENTRY_COUNT
            1 -> ResourceLimitKind.DECLARED_AGGREGATE
            2 -> ResourceLimitKind.ACTUAL_AGGREGATE
            3 -> ResourceLimitKind.ENTRY_SIZE
            4 -> ResourceLimitKind.COMPRESSION_RATIO
            5 -> ResourceLimitKind.UNSAFE_PATH
            6 -> ResourceLimitKind.STRUCTURAL_DEPTH
            else -> ResourceLimitKind.ENTRY_SIZE
        }
        return ResourceLimitException(kind, message)
    }

    private fun requireLibrary() {
        if (!RustBridge.shared.isLibraryReady) throw IOException("Rust EPUB package reader is unavailable")
    }

    private class PackageFields(private val bytes: ByteArray, signature: Int) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        init {
            if (unsignedInt() != signature) throw IOException("Invalid EPUB package protocol")
        }

        fun unsignedInt(): Int {
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated EPUB package data")
            val value = input.int
            if (value < 0) throw IOException("Invalid EPUB package number")
            return value
        }

        fun string(): String {
            val size = unsignedInt()
            if (size > input.remaining()) throw IOException("Truncated EPUB package field")
            val start = input.position()
            input.position(start + size)
            return bytes.decodeToString(start, start + size, throwOnInvalidSequence = true)
        }

        fun <T> rows(fields: Int, row: PackageFields.() -> T): List<T> {
            val count = unsignedInt()
            if (count > input.remaining() / (fields * Int.SIZE_BYTES)) {
                throw IOException("Invalid EPUB package row count")
            }
            return List(count) { row() }
        }

        fun finish() {
            if (input.hasRemaining()) throw IOException("Trailing EPUB package data")
        }
    }

    private const val OPF_SIGNATURE = 0x3146504F // OPF1
    private const val NCX_SIGNATURE = 0x3158434E // NCX1
    private const val NAV_SIGNATURE = 0x3156414E // NAV1
    private const val ENC_SIGNATURE = 0x31434E45 // ENC1
    private const val EPBC_SIGNATURE = 0x43425045 // EPBC
    private const val EPBC_STATUS_OK = 0
    private const val EPBC_STATUS_FALLBACK = 1
    private const val EPBC_STATUS_LIMIT = 2
}
