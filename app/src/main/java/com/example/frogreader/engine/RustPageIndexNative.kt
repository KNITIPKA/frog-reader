package com.example.frogreader.engine

/** Checked source anchors for one exact pagination key. IO callers run off Main. */
internal object RustPageIndexNative {
    external fun read(path: String, key: ByteArray, itemCount: Int): IntArray?
    /** Decode the RPI payload carried by a layout block with the same validation as a standalone file. */
    external fun readBytes(bytes: ByteArray, key: ByteArray, itemCount: Int): IntArray?
    external fun write(path: String, key: ByteArray, itemCount: Int, anchors: IntArray): Boolean
}
