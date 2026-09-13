package com.example.frogreader.data.backup

import java.io.FilterInputStream
import java.io.InputStream

/**
 * ZipInputStream also accepts EOF between entries, even when the archive was
 * cut short. Keep only the bounded ZIP trailer window to detect that case
 * without loading a full book backup into memory or seeking its input URI.
 */
internal class BackupZipInputStream(input: InputStream) : FilterInputStream(input) {
    // EOCD (22 bytes) plus the largest legal ZIP comment (65535 bytes).
    private val tail = ByteArray(22 + 65_535)
    private var next = 0
    private var count = 0

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) record(value.toByte())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { read ->
            if (read > 0) {
                // Only the last window matters, even for a large caller buffer.
                for (index in offset + (read - tail.size).coerceAtLeast(0) until offset + read) {
                    record(buffer[index])
                }
            }
        }

    private fun record(value: Byte) {
        tail[next] = value
        next = (next + 1) % tail.size
        count = (count + 1).coerceAtMost(tail.size)
    }

    fun requireCompleteArchive() {
        // ZipInputStream stops at the central directory. Drain the underlying
        // stream too; bytes prefetched by its inflater are already in the tail.
        val buffer = ByteArray(8192)
        while (read(buffer, 0, buffer.size) != -1) { /* retain only the tail */ }
        val start = if (count < tail.size) 0 else next
        fun byteAt(index: Int) = tail[(start + index) % tail.size].toInt() and 0xff
        for (index in (count - 22) downTo 0) {
            if (byteAt(index) != 0x50 || byteAt(index + 1) != 0x4b ||
                byteAt(index + 2) != 0x05 || byteAt(index + 3) != 0x06
            ) continue
            val commentLength = byteAt(index + 20) or (byteAt(index + 21) shl 8)
            if (index + 22 + commentLength == count) return
        }
        throw BackupFormatException("This backup is truncated: the ZIP end record is missing.")
    }
}
