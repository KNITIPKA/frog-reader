package com.example.frogreader.engine

import androidx.annotation.Keep

/**
 * Native WOFF 1.0 and WOFF 2.0 font unpackers.
 *
 * All header/directory bounds and decoded output budgets are validated in Rust
 * before buffer allocation; damaged or unsupported fonts return `null`.
 * Names and signatures are part of the JNI ABI.
 */
@Keep
internal object RustWoffNative {
    @Keep
    external fun decodeWoff(woffBytes: ByteArray, maxDecodedBytes: Int): ByteArray?

    @Keep
    external fun decodeWoff2(woff2Bytes: ByteArray, maxDecodedBytes: Int): ByteArray?
}
