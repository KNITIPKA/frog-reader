package com.example.frogreader.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The hash decides whether two files are "the same book". Getting it subtly
 * wrong — a truncated read, a sign-extended byte in the hex conversion — would
 * not crash anything; it would just quietly stop recognising duplicates, or
 * start claiming two different books are one. So these check it against known
 * SHA-256 vectors rather than against itself.
 */
class ContentHashTest {

    private val testDir = File("build/tmp/content_hash_test")

    @Before
    fun setUp() {
        testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun fileOf(name: String, bytes: ByteArray): File =
        File(testDir, name).apply { writeBytes(bytes) }

    @Test
    fun `empty file hashes to the known empty digest`() {
        val file = fileOf("empty.epub", ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.of(file),
        )
    }

    @Test
    fun `known vector`() {
        val file = fileOf("abc.fb2", "abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.of(file),
        )
    }

    @Test
    fun `bytes above 0x7F are not sign-extended`() {
        // 0x80..0xFF is where a naive Byte-to-hex conversion produces "ffffff80".
        val file = fileOf("high.mobi", ByteArray(128) { (it + 128).toByte() })
        val hash = ContentHash.of(file)
        assertEquals(64, hash.length)
        assertEquals(hash, hash.lowercase())
        assertEquals(hash.filter { it in "0123456789abcdef" }, hash)
    }

    @Test
    fun `reads past a single buffer`() {
        // Larger than the 64 KiB read buffer, so the digest has to be fed in
        // several passes — the loop this is really testing.
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val whole = fileOf("big.epub", big)
        val copy = fileOf("big-copy.epub", big)
        assertEquals(ContentHash.of(whole), ContentHash.of(copy))

        val altered = big.copyOf().also { it[199_999] = (it[199_999] + 1).toByte() }
        assertNotEquals(ContentHash.of(whole), ContentHash.of(fileOf("big-alt.epub", altered)))
    }

    @Test
    fun `same bytes under different names hash the same`() {
        val bytes = "the same book".toByteArray()
        assertEquals(
            ContentHash.of(fileOf("a.fb2", bytes)),
            ContentHash.of(fileOf("b.epub", bytes)),
        )
    }
}
