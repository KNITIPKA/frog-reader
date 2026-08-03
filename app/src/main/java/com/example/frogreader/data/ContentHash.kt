package com.example.frogreader.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 over a book file, as lowercase hex.
 *
 * Cryptographic strength is not the point — nobody is attacking a library — but
 * a collision here would silently offer to replace one book with a completely
 * different one, and SHA-256 makes that impossible rather than unlikely. It is
 * also fast enough not to matter: the file is being read from disk anyway, and
 * the digest keeps up with the read.
 */
internal object ContentHash {

    /** Streams [file] through the digest; never holds more than [BUFFER_BYTES]. */
    fun of(file: File): String = file.inputStream().use { of(it) }

    /** Consumes [stream] to the end. The caller still owns closing it. */
    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val BUFFER_BYTES = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()
}
