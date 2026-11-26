package com.example.frogreader.engine

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RustBridgeTest {
    @Test
    fun disabledPathNeverLoadsOrCallsNativeAndFallsBackOnce() {
        var fallbackCalls = 0
        val bridge = bridge(load = { error("disabled path loaded JNI") })
        val value = bridge.withFallback(false, { fallbackCalls++; "Kotlin" }, { error("called Rust") })
        assertEquals("Kotlin", value)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun missingLibraryFallsBackOnceAndIsNotRetried() {
        var loads = 0
        val errors = mutableListOf<Throwable>()
        val bridge = bridge(load = { loads++; throw UnsatisfiedLinkError("missing") }, errors = errors)
        repeat(2) {
            assertEquals("Kotlin", bridge.withFallback(true, { "Kotlin" }, { error("called Rust") }))
        }
        assertEquals(1, loads)
        assertEquals(1, errors.size)
        assertFalse(bridge.isAvailable)
        assertFalse(bridge.isLibraryReady)
    }

    @Test
    fun incompatibleProtocolNeverCallsEngine() {
        val errors = mutableListOf<Throwable>()
        val bridge = bridge(protocol = 2, errors = errors)
        assertEquals(23, bridge.withFallback(true, { 23 }, { error("called incompatible engine") }))
        assertFalse(bridge.isAvailable)
        assertTrue(errors.single().message!!.contains("protocol mismatch"))
    }

    @Test
    fun successfulNativeOperationDoesNotRunFallback() {
        var loads = 0
        val bridge = bridge(load = { loads++ })
        repeat(2) {
            assertEquals("Rust", bridge.withFallback(true, { error("called fallback") }, { "Rust" }))
        }
        assertEquals(1, loads)
    }

    @Test
    fun recoverableNativeFailureDisablesEngineBeforeFallingBack() {
        val errors = mutableListOf<Throwable>()
        val bridge = bridge(errors = errors)
        var nativeCalls = 0
        var fallbackCalls = 0
        repeat(2) {
            assertEquals(7, bridge.withFallback(true, { fallbackCalls++; 7 }, {
                nativeCalls++
                throw IllegalStateException("Rust panic translated at JNI boundary")
            }))
        }
        assertEquals(1, nativeCalls)
        assertEquals(2, fallbackCalls)
        assertEquals(1, errors.size)
        assertTrue(bridge.isLibraryReady)
    }

    @Test
    fun missingNativeSymbolFallsBack() {
        val bridge = bridge()
        assertEquals(9, bridge.withFallback(true, { 9 }, { throw UnsatisfiedLinkError("wrong ABI") }))
        assertFalse(bridge.isAvailable)
    }

    @Test
    fun cancellationPropagatesWithoutRunningFallbackOrDisablingEngine() {
        val bridge = bridge()
        val cancellation = CancellationException("cancel reader")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            bridge.withFallback(true, { error("called fallback") }, { throw cancellation })
        })
        assertTrue(bridge.isAvailable)
    }

    @Test
    fun fatalVmErrorsPropagateWithoutRunningFallback() {
        val bridge = bridge()
        val failure = OutOfMemoryError("test only")
        assertSame(failure, assertThrows(OutOfMemoryError::class.java) {
            bridge.withFallback(true, { error("called fallback") }, { throw failure })
        })
        assertTrue(bridge.isAvailable)
    }

    @Test
    fun fallbackFailurePropagatesWithoutRetry() {
        var fallbackCalls = 0
        val failure = IllegalArgumentException("Kotlin parser rejected input")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            bridge(load = { throw UnsatisfiedLinkError("missing") }).withFallback(true, {
                fallbackCalls++
                throw failure
            }, { error("called Rust") })
        })
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun echoPreservesEveryByteWithoutSharingMutableArray() {
        val bytes = ByteArray(256) { it.toByte() }
        val copy = bridge().echo(bytes, enabled = false)
        assertArrayEquals(bytes, copy)
        copy[0] = 42
        assertEquals(0, bytes[0].toInt())
    }

    private fun bridge(
        load: () -> Unit = {},
        protocol: Int = RustBridge.PROTOCOL_VERSION,
        errors: MutableList<Throwable> = mutableListOf(),
    ) = RustBridge(
        loadLibrary = load,
        native = object : RustBridge.NativeCalls {
            override fun protocolVersion() = protocol
            override fun echo(bytes: ByteArray) = bytes.copyOf()
        },
        diagnostic = { _, cause -> errors += cause },
    )
}
