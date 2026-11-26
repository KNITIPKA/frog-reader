package com.example.frogreader.engine

import androidx.annotation.Keep

/** Package and navigation readers return only fields consumed by the reader. */
@Keep
internal object RustEpubPackageNative {
    @Keep external fun rootfile(xml: ByteArray): String?
    @Keep external fun packageData(xml: ByteArray): ByteArray?
    @Keep external fun ncxData(xml: ByteArray): ByteArray?
    @Keep external fun navData(xml: ByteArray): ByteArray?
    @Keep external fun parseEncryption(bytes: ByteArray): ByteArray?
}
