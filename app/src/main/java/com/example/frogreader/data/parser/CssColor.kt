package com.example.frogreader.data.parser

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustCssSyntaxNative
import java.util.concurrent.ConcurrentHashMap

/** Literal CSS colors in Android ARGB order. The cascade handles context-dependent values. */
internal object CssColor {
    private const val MAX_INPUT_BYTES = 4096
    private const val MAX_CACHE_ENTRIES = 512
    private const val MAX_CACHE_KEY_LENGTH = 128
    private val cached = ConcurrentHashMap<String, Long>()

    fun parse(raw: String): Int? {
        if (raw.length > MAX_INPUT_BYTES || raw.isBlank()) return null
        cached[raw]?.let { return it.takeIf { value -> value >= 0 }?.toInt() }
        check(RustBridge.shared.isLibraryReady) { "Rust CSS color parser is unavailable" }
        val value = RustCssSyntaxNative.color(raw.encodeToByteArray())
        if (raw.length <= MAX_CACHE_KEY_LENGTH && cached.size < MAX_CACHE_ENTRIES) {
            synchronized(cached) {
                if (cached.size < MAX_CACHE_ENTRIES) cached.putIfAbsent(raw, value)
            }
        }
        return value.takeIf { it >= 0 }?.toInt()
    }
}
