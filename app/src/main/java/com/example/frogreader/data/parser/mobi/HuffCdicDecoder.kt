package com.example.frogreader.data.parser.mobi

import com.example.frogreader.engine.RustMobiNative
import com.example.frogreader.engine.RustMobiWire
import java.io.IOException
import java.lang.ref.PhantomReference
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * HUFF/CDIC decompression (MOBI compression type 17480) — canonical
 * Huffman codes over a phrase dictionary, delegated to the native Rust
 * implementation. Any structural violation throws IOException ("damaged
 * book"), deliberately distinct from the DRM rejection.
 */
internal class HuffCdicDecoder(
    huffRecord: ByteArray,
    cdicRecords: Iterable<ByteArray>,
    maxDecodedBytes: Int = 32 * 1024 * 1024,
    maxDictionaryBytes: Int = 32 * 1024 * 1024,
) {
    private val nativeSession: NativeHuffSession

    init {
        RustMobiWire.requireLibrary()
        val wire = RustMobiWire.unwrapOrThrow(
            RustMobiNative.huffOpen(huffRecord, maxDecodedBytes, maxDictionaryBytes),
            "Damaged MOBI: bad HUFF record",
        )!!
        if (wire.size != 9) throw IOException("Damaged MOBI: invalid HUFF session header")
        val handle = ByteBuffer.wrap(wire, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val session = NativeHuffSession(handle)
        try {
            for (cdic in cdicRecords) {
                RustMobiWire.unwrapOrThrow(
                    RustMobiNative.huffAddCdic(handle, cdic),
                    "Damaged MOBI: bad CDIC record",
                )
            }
        } catch (error: Throwable) {
            session.close()
            throw error
        }
        nativeSession = session
    }

    /** Decompresses one record; returns the number of bytes written. */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        if (off < 0 || len < 0 || off > src.size - len || dstOff < 0 || dstOff > dst.size) {
            throw IOException("Damaged MOBI: HUFF output overflow")
        }
        val maxOut = dst.size - dstOff
        val wire = nativeSession.useHandle { handle ->
            RustMobiWire.unwrapOrThrow(
                RustMobiNative.huffDecompress(handle, src, off, len, maxOut),
                "Damaged MOBI: HUFF output overflow",
            )!!
        }
        val count = wire.size - 1
        System.arraycopy(wire, 1, dst, dstOff, count)
        return count
    }

    /** Decompresses one record without allowing a hidden oversized expansion. */
    fun decompressBounded(
        src: ByteArray,
        off: Int,
        len: Int,
        maxOutputBytes: Int,
    ): ByteArray {
        if (off < 0 || len < 0 || off > src.size - len) {
            throw IndexOutOfBoundsException()
        }
        val wire = nativeSession.useHandle { handle ->
            RustMobiWire.unwrapOrThrow(
                RustMobiNative.huffDecompressBounded(handle, src, off, len, maxOutputBytes),
                "Damaged MOBI: HUFF decode failed",
            )!!
        }
        return wire.copyOfRange(1, wire.size)
    }

    private class NativeHuffSession(handle: Long) {
        private val reference = SessionReference(this, handle).also(Cleanup::track)

        init {
            if (handle == 0L) throw IOException("Damaged MOBI: HUFF context unavailable")
        }

        inline fun <T> useHandle(operation: (Long) -> T): T = try {
            operation(reference.handle.get())
        } finally {
            Reference.reachabilityFence(this)
        }

        fun close() = reference.release()

        private class SessionReference(
            owner: NativeHuffSession,
            handle: Long,
        ) : PhantomReference<NativeHuffSession>(owner, Cleanup.queue) {
            val handle = AtomicLong(handle)

            fun release() {
                val id = handle.getAndSet(0L)
                if (id == 0L) return
                Cleanup.untrack(this)
                clear()
                RustMobiNative.huffClose(id)
            }
        }

        private object Cleanup {
            val queue = ReferenceQueue<NativeHuffSession>()
            private val references = ConcurrentHashMap.newKeySet<SessionReference>()

            init {
                thread(isDaemon = true, name = "Rust MOBI HUFF cleanup") {
                    while (true) {
                        (queue.remove() as SessionReference).release()
                    }
                }
            }

            fun track(reference: SessionReference) {
                references.add(reference)
            }

            fun untrack(reference: SessionReference) {
                references.remove(reference)
            }
        }
    }
}
