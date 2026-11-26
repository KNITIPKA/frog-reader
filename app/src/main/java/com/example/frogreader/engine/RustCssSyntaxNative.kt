package com.example.frogreader.engine

internal object RustCssSyntaxNative {
    external fun sheet(css: ByteArray): ByteArray?
    external fun inline(css: ByteArray): ByteArray?
    external fun color(css: ByteArray): Long
}
