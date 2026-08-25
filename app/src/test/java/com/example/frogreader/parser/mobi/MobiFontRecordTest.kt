package com.example.frogreader.parser.mobi

import com.example.frogreader.data.parser.mobi.MobiFontRecord
import com.example.frogreader.data.parser.mobi.MobiSection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiFontRecordTest {

    /** A fake sfnt: TrueType magic + deterministic filler past the XOR span. */
    private val sfnt =
        byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(2000) { (it * 7 and 0xFF).toByte() }

    @Test
    fun `plain payload round-trips`() {
        val record = MobiBuilder.fontRecord(sfnt)
        assertTrue(MobiFontRecord.isFontRecord(record, 0, record.size))
        assertArrayEquals(sfnt, MobiFontRecord.decode(record, 0, record.size))
    }

    @Test
    fun `obfuscated and compressed payload round-trips`() {
        val key = byteArrayOf(0x5A, 0x33, 0x77, 0x0F, 0xEE.toByte())
        val record = MobiBuilder.fontRecord(sfnt, xorKey = key, compress = true)
        assertArrayEquals(sfnt, MobiFontRecord.decode(record, 0, record.size))
    }

    @Test
    fun `compressed-only payload round-trips`() {
        val record = MobiBuilder.fontRecord(sfnt, compress = true)
        assertArrayEquals(sfnt, MobiFontRecord.decode(record, 0, record.size))
    }

    @Test
    fun `record inside a larger buffer decodes at its offset`() {
        val record = MobiBuilder.fontRecord(sfnt, compress = true)
        val padded = ByteArray(16) { 0x21 } + record
        assertArrayEquals(sfnt, MobiFontRecord.decode(padded, 16, record.size))
    }

    @Test
    fun `corrupt header degrades to null`() {
        val record = MobiBuilder.fontRecord(sfnt)
        // dataStart beyond the record.
        val broken = record.copyOf()
        broken[12] = 0x7F
        assertNull(MobiFontRecord.decode(broken, 0, broken.size))
        // Not a FONT record at all.
        assertNull(MobiFontRecord.decode(ByteArray(64) { 3 }, 0, 64))
        // Truncated below the header.
        assertNull(MobiFontRecord.decode(record, 0, 12))
    }

    @Test
    fun `compressed FONT expansion respects caller limit before allocation`() {
        val record = MobiBuilder.fontRecord(sfnt, compress = true)
        assertNull(MobiFontRecord.decode(record, 0, record.size, maxDecodedBytes = 512))
        assertArrayEquals(sfnt, MobiFontRecord.decode(record, 0, record.size, sfnt.size))
    }
}

class ResourceSniffTest {

    @Test
    fun `extensions require full magic`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0, 0, 0, 0)
        val notJpeg = byteArrayOf(0xFF.toByte(), 0x00, 0, 0, 0, 0, 0, 0)
        val bmp = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0, 0, 0, 0, 0, 0)
        assertEquals("jpg", MobiSection.resourceExtension(jpeg))
        assertEquals("img", MobiSection.resourceExtension(notJpeg))
        assertEquals("bmp", MobiSection.resourceExtension(bmp))
        assertEquals("png", MobiSection.resourceExtension(MobiBuilder.fakePng(0)))
    }

    @Test
    fun `font records are resources but not images`() {
        val font = MobiBuilder.fontRecord(ByteArray(64) { 1 })
        assertTrue(MobiSection.looksLikeResource(font, 0, font.size))
        assertFalse(MobiSection.looksLikeImage(font, 0, font.size))
        val png = MobiBuilder.fakePng(3)
        assertTrue(MobiSection.looksLikeImage(png, 0, png.size))
    }
}
