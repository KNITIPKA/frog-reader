package com.example.frogreader.engine

/** Checked ZIP transport used by EPUB parsing. Close every positive archive handle. */
internal object RustEpubZipNative {
    external fun open(path: String, maxEntries: Int, maxDeclaredBytes: Long): Long
    external fun directory(handle: Long): ByteArray?
    external fun read(handle: Long, index: Int): ByteArray?
    external fun copy(handle: Long, index: Int, target: String): Int
    external fun close(handle: Long)
}
