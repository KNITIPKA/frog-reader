package com.example.frogreader.ui

import com.example.frogreader.ui.library.coversDiffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Whether replacing a book would change the picture on the shelf.
 *
 * This decides whether the dialog shows one cover or two. Getting it wrong in
 * the quiet direction is the bad one: the user presses Replace, and the art
 * they picked the book out by silently becomes something else.
 */
class CoverComparisonTest {

    private val testDir = File("build/tmp/cover_compare_test")

    @Before
    fun setUp() {
        testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun cover(name: String, bytes: ByteArray) =
        File(testDir, name).apply { writeBytes(bytes) }

    @Test
    fun `the same image is not a difference`() {
        val art = ByteArray(4096) { (it % 251).toByte() }
        assertFalse(coversDiffer(cover("a.img", art), art.copyOf()))
    }

    @Test
    fun `different art of the same length is still caught`() {
        // The length shortcut must not be mistaken for the answer.
        val original = ByteArray(4096) { (it % 251).toByte() }
        val altered = original.copyOf().also { it[4095] = (it[4095] + 1).toByte() }
        assertTrue(coversDiffer(cover("a.img", original), altered))
    }

    @Test
    fun `different lengths differ`() {
        assertTrue(coversDiffer(cover("a.img", ByteArray(100)), ByteArray(200)))
    }

    @Test
    fun `gaining or losing a cover is a difference`() {
        assertTrue("the new file brings art the old one never had", coversDiffer(null, ByteArray(10)))
        assertTrue("replacing would lose the cover", coversDiffer(cover("a.img", ByteArray(10)), null))
    }

    @Test
    fun `two books with no cover at all do not differ`() {
        assertFalse(coversDiffer(null, null))
    }

    @Test
    fun `an unreadable cover file counts as different`() {
        // Better to show both and let the user look than to claim a match we
        // could not actually verify.
        assertTrue(coversDiffer(File(testDir, "missing.img"), ByteArray(0)))
    }
}
