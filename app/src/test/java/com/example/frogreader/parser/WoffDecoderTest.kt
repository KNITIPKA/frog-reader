package com.example.frogreader.parser

import com.example.frogreader.data.parser.WoffDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class WoffDecoderTest {

    private fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 8)
        out.write(value and 0xFF)
    }

    private fun u32(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 24)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun tag(text: String): Int =
        text.fold(0) { acc, c -> (acc shl 8) or c.code }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(data.size * 2 + 64)
        val n = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(n)
    }

    private fun buildWoff(storedData: ByteArray, compressedData: ByteArray): ByteArray {
        val deflated = deflate(compressedData)
        val out = ByteArrayOutputStream()
        // Header (44 bytes)
        out.write("wOFF".toByteArray())
        u32(out, 0x00010000) // flavor: TrueType
        val dataStart = 44 + 2 * 20
        u32(out, dataStart + storedData.size + deflated.size) // length (unchecked)
        u16(out, 2) // numTables
        u16(out, 0) // reserved
        u32(out, 12 + 2 * 16 + storedData.size + compressedData.size) // totalSfntSize
        u16(out, 1); u16(out, 0) // version
        u32(out, 0); u32(out, 0); u32(out, 0) // meta
        u32(out, 0); u32(out, 0) // priv
        // Directory: stored table, then compressed table.
        u32(out, tag("name"))
        u32(out, dataStart)
        u32(out, storedData.size) // compLength == origLength → stored
        u32(out, storedData.size)
        u32(out, 0x11223344)
        u32(out, tag("glyf"))
        u32(out, dataStart + storedData.size)
        u32(out, deflated.size)
        u32(out, compressedData.size)
        u32(out, 0x55667788)
        // Data
        out.write(storedData)
        out.write(deflated)
        return out.toByteArray()
    }

    @Test
    fun `decodes a minimal woff to sfnt`() {
        val stored = "ABCDEF".toByteArray()
        // Repetitive data so the deflated form is genuinely smaller.
        val compressed = ByteArray(400) { (it % 4).toByte() }
        val sfnt = WoffDecoder.decode(buildWoff(stored, compressed))!!

        // sfnt header.
        assertEquals(0x00010000, readU32(sfnt, 0))
        assertEquals(2, readU16(sfnt, 4)) // numTables
        assertEquals(32, readU16(sfnt, 6)) // searchRange
        assertEquals(1, readU16(sfnt, 8)) // entrySelector
        assertEquals(0, readU16(sfnt, 10)) // rangeShift

        // Table records: tag, checksum, offset, length.
        assertEquals(tag("name"), readU32(sfnt, 12))
        assertEquals(0x11223344, readU32(sfnt, 16))
        val nameOffset = readU32(sfnt, 20)
        assertEquals(44, nameOffset) // 12 header + 2*16 records
        assertEquals(stored.size, readU32(sfnt, 24))

        assertEquals(tag("glyf"), readU32(sfnt, 28))
        val glyfOffset = readU32(sfnt, 36)
        assertEquals(52, glyfOffset) // 44 + pad4(6)
        assertEquals(compressed.size, readU32(sfnt, 40))

        assertArrayEquals(stored, sfnt.copyOfRange(nameOffset, nameOffset + stored.size))
        assertArrayEquals(compressed, sfnt.copyOfRange(glyfOffset, glyfOffset + compressed.size))
        // Total size is 4-byte padded.
        assertEquals(52 + 400, sfnt.size)
    }

    @Test
    fun `rejects garbage and truncated input`() {
        assertNull(WoffDecoder.decode(ByteArray(10)))
        assertNull(WoffDecoder.decode("wOFF".toByteArray()))
        val valid = buildWoff("AB".toByteArray(), ByteArray(120) { 1 })
        assertNull(WoffDecoder.decode(valid.copyOf(valid.size - 5)))
        assertTrue(WoffDecoder.isWoff(valid))
    }

    private fun readU16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readU32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
