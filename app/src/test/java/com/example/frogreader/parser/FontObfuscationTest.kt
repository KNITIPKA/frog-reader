package com.example.frogreader.parser

import com.example.frogreader.data.parser.FontObfuscation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontObfuscationTest {

    @Test
    fun `xor round-trips a fake font`() {
        val original = "OTTO".toByteArray() + ByteArray(2000) { (it % 251).toByte() }
        val key = FontObfuscation.idpfKey("urn:uuid:12345678-90ab-cdef-1234-567890abcdef")
        assertEquals(20, key.size) // SHA-1

        val obfuscated = FontObfuscation.deobfuscate(original, key, FontObfuscation.IDPF_PREFIX)
        // The first bytes are scrambled (magic gone), the tail untouched.
        assertTrue(!obfuscated.copyOfRange(0, 4).contentEquals("OTTO".toByteArray()))
        assertArrayEquals(
            original.copyOfRange(1040, original.size),
            obfuscated.copyOfRange(1040, obfuscated.size),
        )

        val restored = FontObfuscation.deobfuscate(obfuscated, key, FontObfuscation.IDPF_PREFIX)
        assertArrayEquals(original, restored)
    }

    @Test
    fun `idpf key removes every whitespace, not just the edges`() {
        val spaced = FontObfuscation.idpfKey("urn:uuid: 1234\t5678\r\n90")
        val compact = FontObfuscation.idpfKey("urn:uuid:1234567890")
        assertArrayEquals(compact, spaced)
    }

    @Test
    fun `adobe key parses uuid identifiers`() {
        val key = FontObfuscation.adobeKey("urn:uuid:01020304-0506-0708-090a-0b0c0d0e0f10")
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            key,
        )
        // Prefix variants and case.
        assertArrayEquals(key, FontObfuscation.adobeKey("URN:UUID:01020304-0506-0708-090a-0b0c0d0e0f10"))
        assertArrayEquals(key, FontObfuscation.adobeKey("01020304-0506-0708-090a-0b0c0d0e0f10"))
    }

    @Test
    fun `adobe key rejects non-uuid identifiers`() {
        assertNull(FontObfuscation.adobeKey("isbn:978-5-17-118366-9"))
        assertNull(FontObfuscation.adobeKey("litres-123456"))
        assertNull(FontObfuscation.adobeKey(""))
    }

    @Test
    fun `short files obfuscate without overflow`() {
        val tiny = byteArrayOf(1, 2, 3)
        val key = byteArrayOf(0x0F, 0x0F)
        val out = FontObfuscation.deobfuscate(tiny, key, 1040)
        assertArrayEquals(byteArrayOf(0x0E, 0x0D, 0x0C), out)
    }
}
