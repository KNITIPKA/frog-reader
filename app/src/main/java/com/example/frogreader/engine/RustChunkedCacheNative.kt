package com.example.frogreader.engine

import androidx.annotation.Keep

/**
 * FRB transport. Expected IO/invalid-cache failures return 0/null/false;
 * unexpected Rust panics become Java exceptions at the guarded JNI boundary.
 *
 * Write paths must be owned temporary paths in the app sandbox. beginWrite
 * creates a new file and refuses existing paths. Close every positive session ID
 * in finally. The reader owns an open FD, so replacement cannot mix generations.
 */
@Keep
internal object RustChunkedCacheNative {
    const val MAX_BLOCK_BYTES = 64L * 1024 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    @Keep external fun beginWrite(path: String, metadata: ByteArray, elementCounts: IntArray, maxFileBytes: Long): Long
    @Keep external fun writeBlock(handle: Long, blockIndex: Int, jsonPath: String): Boolean
    @Keep external fun writeBlockBytes(handle: Long, blockIndex: Int, bytes: ByteArray): Boolean
    @Keep external fun finishWrite(handle: Long): Boolean
    @Keep external fun openRead(path: String, metadata: ByteArray): Long
    /** Five values per chapter: offset, compressed bytes, decoded bytes, elements, start flat index. */
    @Keep external fun readIndex(handle: Long): LongArray?
    /** Validated header and index CRCs as two little-endian u32 values from this reader. */
    @Keep external fun readGeneration(handle: Long): ByteArray?
    /** Verified decoded block, including empty blocks. Invalid reads return null. */
    @Keep external fun readBlockBytes(handle: Long, blockIndex: Int): ByteArray?
    @Keep external fun close(handle: Long)
}
