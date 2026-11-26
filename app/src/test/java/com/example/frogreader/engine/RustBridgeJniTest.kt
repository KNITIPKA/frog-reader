package com.example.frogreader.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the real host cdylib; Kotlin-only test runs may explicitly skip it. */
class RustBridgeJniTest {
    @Test
    fun hostLibraryHandshakeAndBinaryTransportMatchKotlin() {
        val bridge = requiredBridge()
        val fixtures = listOf(
            byteArrayOf(),
            ByteArray(256) { it.toByte() },
            "FrogReader 🐸 Українська العربية עברית\u0000".toByteArray(Charsets.UTF_8),
            ByteArray(256 * 1024) { ((it * 31) xor (it ushr 7)).toByte() },
        )
        for (fixture in fixtures) {
            assertArrayEquals(fixture.copyOf(), bridge.echo(fixture, enabled = true))
        }
        assertEquals(RustBridge.PROTOCOL_VERSION, RustNative.protocolVersion())
    }

    @Test
    fun actualRustPanicReturnsThroughJniAndFallsBackWithoutTerminatingJvm() {
        val errors = mutableListOf<Throwable>()
        val bridge = requiredBridge(errors)
        assertEquals(73, bridge.withFallback(true, { 73 }, { RustNative.panicProbe() }))
        assertFalse(bridge.isAvailable)
        assertTrue(errors.single() is IllegalStateException)
        assertTrue(errors.single().message!!.contains("panicked"))
        // Another independent session can still use the same loaded library.
        assertEquals(RustBridge.PROTOCOL_VERSION, RustNative.protocolVersion())
    }

    private fun requiredBridge(errors: MutableList<Throwable> = mutableListOf()): RustBridge {
        assumeTrue("host Rust tests require -Dfrogreader.requireRust=true",
            java.lang.Boolean.getBoolean("frogreader.requireRust"))
        return RustBridge(diagnostic = { _, cause -> errors += cause }).also {
            assertTrue("Required host JNI library failed to load: $errors", it.isAvailable)
        }
    }
}
