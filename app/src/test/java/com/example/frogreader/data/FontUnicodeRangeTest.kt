package com.example.frogreader.data

import com.example.frogreader.data.model.normalizeFontUnicodeRange
import com.example.frogreader.data.model.parseFontUnicodeRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontUnicodeRangeTest {
    @Test
    fun `single interval and wildcard forms normalize their union`() {
        assertEquals(listOf(0xa5..0xa5, 0x400..0x4ff, 0x1f600..0x1f64f),
            parseFontUnicodeRanges("u+a5, U+4??, U+1f600-1f64f"))
        assertEquals("U+A5,U+400-4FF,U+1F600-1F64F",
            normalizeFontUnicodeRange("u+a5, U+0400-04ff, U+1f600-1f64f"))
        assertEquals("U+0-FFF", normalizeFontUnicodeRange("U+???"))
    }

    @Test
    fun `overlapping adjacent and repeated ranges have one canonical representation`() {
        assertEquals("U+400-4FF", normalizeFontUnicodeRange("U+410-450,U+400-40F,U+450-4FF,U+416"))
        assertEquals("U+10FFFF", normalizeFontUnicodeRange("U+10ffff"))
        assertNull(normalizeFontUnicodeRange("U+0-10FFFF"))
        assertNull(normalizeFontUnicodeRange(null))
    }

    @Test
    fun `one invalid token invalidates the entire descriptor rather than restricting a face`() {
        listOf("", "U+500-400", "U+110000", "U+0-110000", "U+??????", "U+2?????",
            "U+400,broken", "U+400,", "U+4?F", "U+1234567", "400", "U+400-4??").forEach { value ->
            assertNull(value, parseFontUnicodeRanges(value))
            assertNull(value, normalizeFontUnicodeRange(value))
        }
        assertEquals(listOf(0x100000..0x10ffff), parseFontUnicodeRanges("U+10????"))
    }
}
