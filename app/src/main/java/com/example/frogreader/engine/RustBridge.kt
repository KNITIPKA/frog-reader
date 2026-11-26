package com.example.frogreader.engine

import android.util.Log
import java.util.concurrent.CancellationException

/** Optional JNI transport with a version handshake and session-scoped fallback. */
internal class RustBridge(
    private val loadLibrary: () -> Unit = { System.loadLibrary("frogreader_core") },
    private val native: NativeCalls = RustNative,
    private val diagnostic: (String, Throwable) -> Unit = { message, cause ->
        Log.w("RustBridge", message, cause)
    },
) {
    @Volatile
    private var failed = false

    private val loaded: Boolean by lazy {
        recover(false) {
            loadLibrary()
            check(native.protocolVersion() == PROTOCOL_VERSION) { "Rust JNI protocol mismatch" }
            true
        }
    }

    val isAvailable: Boolean
        get() = !failed && loaded

    // Required source parsers still work after an optional derived-cache failure.
    val isLibraryReady: Boolean
        get() = loaded

    /**
     * The Kotlin lambda is evaluated exactly once on a disabled/missing/failed path.
     * Cancellation and VM errors propagate: they are not recoverable engine errors.
     */
    fun <T> withFallback(enabled: Boolean, kotlin: () -> T, rust: () -> T): T {
        if (!enabled || !isAvailable) return kotlin()
        return try {
            rust()
        } catch (failure: LinkageError) {
            disable(failure)
            kotlin()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            disable(failure)
            kotlin()
        }
    }

    /** Transport smoke operation; real format implementations are added separately. */
    fun echo(bytes: ByteArray, enabled: Boolean = false): ByteArray = withFallback(
        enabled = enabled,
        kotlin = { bytes.copyOf() },
        rust = { native.echo(bytes) },
    )

    private fun <T> recover(fallback: T, action: () -> T): T = try {
        action()
    } catch (failure: LinkageError) {
        disable(failure)
        fallback
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        disable(failure)
        fallback
    }

    private fun disable(cause: Throwable) {
        failed = true
        diagnostic("Rust engine unavailable; using Kotlin for this session", cause)
    }

    internal interface NativeCalls {
        fun protocolVersion(): Int
        fun echo(bytes: ByteArray): ByteArray
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        val shared: RustBridge by lazy { RustBridge() }
    }
}

/** Names and signatures are part of the JNI ABI; retain them in R8 rules. */
internal object RustNative : RustBridge.NativeCalls {
    external override fun protocolVersion(): Int
    external override fun echo(bytes: ByteArray): ByteArray

    // Symbol exists only in cargoBuildHost's test-hooks feature, never in Android.
    external fun panicProbe(): Int
}
