package com.example.frogreader.engine

import androidx.annotation.Keep
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI transport for native MOBI / AZW3 (KF8) container decoders and assemblers.
 * Names and signatures are part of the JNI ABI; retain them in R8 rules.
 */
@Keep
internal object RustMobiNative {
    @Keep
    external fun isPdbBook(header: ByteArray): Boolean

    @Keep
    external fun parsePdbHeader(
        head: ByteArray,
        fileSize: Long,
        maxMobiRecords: Int,
    ): ByteArray?

    @Keep
    external fun parsePdbOffsets(
        table: ByteArray,
        recordCount: Int,
        fileSize: Long,
    ): ByteArray?

    @Keep
    external fun parsePalmDocHeader(record0: ByteArray): ByteArray?

    @Keep
    external fun parseMobiHeader(record0: ByteArray): ByteArray?

    @Keep
    external fun parseExth(record0: ByteArray, mobiHeaderLength: Int): ByteArray?

    @Keep
    external fun palmdocDecompress(src: ByteArray, off: Int, len: Int, maxOut: Int): ByteArray?

    @Keep
    external fun trailingContentLength(data: ByteArray, off: Int, len: Int, flags: Int): Int

    @Keep
    external fun backwardVarint(data: ByteArray, off: Int, endLen: Int): Int

    @Keep
    external fun huffOpen(
        huffRecord: ByteArray,
        maxDecodedBytes: Int,
        maxDictionaryBytes: Int,
    ): ByteArray?

    @Keep
    external fun huffAddCdic(handle: Long, cdicRecord: ByteArray): ByteArray?

    @Keep
    external fun huffDecompress(
        handle: Long,
        src: ByteArray,
        off: Int,
        len: Int,
        maxOut: Int,
    ): ByteArray?

    @Keep
    external fun huffDecompressBounded(
        handle: Long,
        src: ByteArray,
        off: Int,
        len: Int,
        maxOutputBytes: Int,
    ): ByteArray?

    @Keep
    external fun huffClose(handle: Long)

    @Keep
    external fun decodeFontRecord(
        data: ByteArray,
        off: Int,
        len: Int,
        maxDecodedBytes: Int,
    ): ByteArray?

    @Keep
    external fun indexBegin(header: ByteArray, limits: LongArray): ByteArray?

    @Keep
    external fun indexAddEntryRecord(handle: Long, record: ByteArray): ByteArray?

    @Keep
    external fun indexAddCncxRecord(handle: Long, record: ByteArray): ByteArray?

    @Keep
    external fun indexFinish(handle: Long): ByteArray?

    @Keep
    external fun indexClose(handle: Long)

    @Keep
    external fun forwardVarint(data: ByteArray, offset: Int): LongArray?

    @Keep
    external fun kf8ParseFdst(
        fdst: ByteArray,
        rawTextLen: Int,
        maxFlowAggregateBytes: Long,
    ): ByteArray?

    @Keep
    external fun kf8Assemble(
        rawText: ByteArray,
        flowRanges: IntArray,
        skelWire: ByteArray,
        fragWire: ByteArray,
        limits: LongArray,
    ): ByteArray?
}

/** Decodes status envelopes returned by [RustMobiNative] into typed JVM results/exceptions. */
internal object RustMobiWire {
    const val STATUS_OK = 0
    const val STATUS_IO_ERROR = 1
    const val STATUS_RESOURCE_LIMIT = 2
    const val STATUS_DAMAGED_NULL = 3

    fun requireLibrary() {
        check(RustBridge.shared.isLibraryReady) { "Rust MOBI engine is unavailable" }
    }

    fun unwrapOrThrow(bytes: ByteArray?, fallbackError: String): ByteArray? {
        if (bytes == null || bytes.isEmpty()) throw IOException(fallbackError)
        return when (bytes[0].toInt() and 0xFF) {
            STATUS_OK -> bytes
            STATUS_IO_ERROR -> throw IOException(bytes.decodeToString(1, bytes.size))
            STATUS_RESOURCE_LIMIT -> {
                if (bytes.size < 2) throw IOException(fallbackError)
                val kind = limitKind(bytes[1].toInt() and 0xFF)
                throw ResourceLimitException(kind, bytes.decodeToString(2, bytes.size))
            }
            STATUS_DAMAGED_NULL -> null
            else -> throw IOException(fallbackError)
        }
    }

    fun openReader(wire: ByteArray, expectedMagic: Int, protocolError: String): ByteBuffer {
        val input = ByteBuffer.wrap(wire, 1, wire.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < Int.SIZE_BYTES || input.int != expectedMagic) {
            throw IOException(protocolError)
        }
        return input
    }

    fun readInt(input: ByteBuffer, truncatedError: String): Int {
        if (input.remaining() < Int.SIZE_BYTES) throw IOException(truncatedError)
        return input.int
    }

    fun readNonNegativeInt(
        input: ByteBuffer,
        truncatedError: String,
        invalidError: String,
    ): Int {
        val value = readInt(input, truncatedError)
        if (value < 0) throw IOException(invalidError)
        return value
    }

    fun readBytes(
        input: ByteBuffer,
        truncatedError: String,
        invalidError: String,
    ): ByteArray {
        val length = readNonNegativeInt(input, truncatedError, invalidError)
        if (length > input.remaining()) throw IOException(truncatedError)
        return ByteArray(length).also(input::get)
    }

    fun readUtf8String(
        wire: ByteArray,
        input: ByteBuffer,
        truncatedError: String,
        invalidError: String,
    ): String {
        val length = readNonNegativeInt(input, truncatedError, invalidError)
        if (length > input.remaining()) throw IOException(truncatedError)
        val start = input.position()
        input.position(start + length)
        return wire.decodeToString(start, start + length, throwOnInvalidSequence = true)
    }

    fun readOptionalUtf8String(
        wire: ByteArray,
        input: ByteBuffer,
        truncatedError: String,
        invalidError: String,
    ): String? {
        if (!input.hasRemaining()) throw IOException(truncatedError)
        return when (input.get().toInt() and 0xFF) {
            0 -> null
            1 -> readUtf8String(wire, input, truncatedError, invalidError)
            else -> throw IOException(invalidError)
        }
    }

    private fun limitKind(code: Int): ResourceLimitKind = when (code) {
        0 -> ResourceLimitKind.ENTRY_COUNT
        1 -> ResourceLimitKind.DECLARED_AGGREGATE
        2 -> ResourceLimitKind.ACTUAL_AGGREGATE
        3 -> ResourceLimitKind.ENTRY_SIZE
        4 -> ResourceLimitKind.COMPRESSION_RATIO
        5 -> ResourceLimitKind.UNSAFE_PATH
        6 -> ResourceLimitKind.STRUCTURAL_DEPTH
        else -> ResourceLimitKind.ENTRY_SIZE
    }
}
