package com.example.frogreader.data

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontGlyphCoverageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `format four checks actual glyph array entries before applying delta`() {
        val font = font(formatFour())
        assertTrue(customFontSupportsText(font.path, "AC"))
        assertFalse(customFontSupportsText(font.path, "ABC")) // B maps to .notdef, not delta=1.
        assertFalse(customFontSupportsText(font.path, "Subset-Times New Roman"))
        assertEquals("AC", customFontPreviewText(font.path))
    }

    @Test fun `format twelve supports supplementary code points and zero start glyph`() {
        val font = font(grouped(12, listOf(Triple(65, 67, 0), Triple(0x1f600, 0x1f600, 8))))
        assertFalse(customFontSupportsText(font.path, "A"))
        assertTrue(customFontSupportsText(font.path, "BC😀"))
        assertFalse(customFontSupportsText(font.path, "😁"))
        assertFalse(customFontSupportsText(font.path, "\ud83d"))
    }

    @Test fun `format thirteen maps whole ranges to one genuine glyph`() {
        val font = font(grouped(13, listOf(Triple(65, 67, 5), Triple(0x1f600, 0x1f601, 0))))
        assertTrue(customFontSupportsText(font.path, "ABC"))
        assertFalse(customFontSupportsText(font.path, "😀"))
    }

    @Test fun `format zero and six retain holes in their glyph arrays`() {
        val zero = ByteBuffer.allocate(262).putShort(0).putShort(262).putShort(0).array()
        zero[6 + 65] = 3
        val zeroFont = font(zero)
        assertTrue(customFontSupportsText(zeroFont.path, "A"))
        assertFalse(customFontSupportsText(zeroFont.path, "B"))
        val six = ByteBuffer.allocate(16).putShort(6).putShort(16).putShort(0)
            .putShort(65).putShort(3).putShort(3).putShort(0).putShort(4).array()
        val sixFont = font(six)
        assertTrue(customFontSupportsText(sixFont.path, "AC"))
        assertFalse(customFontSupportsText(sixFont.path, "B"))
    }

    @Test fun `unicode subtables combine coverage but symbol encodings do not`() {
        val cmap = cmap(listOf(
            Record(3, 1, grouped(12, listOf(Triple(65, 65, 1)))),
            Record(0, 4, grouped(12, listOf(Triple(66, 66, 2)))),
            Record(3, 0, grouped(12, listOf(Triple(67, 67, 3)))),
        ))
        val font = file(sfnt(cmap))
        assertTrue(customFontSupportsText(font.path, "AB"))
        assertFalse(customFontSupportsText(font.path, "ABC"))
    }

    @Test fun `space and default ignorable controls do not require visible glyphs`() {
        val font = font(grouped(12, listOf(Triple(65, 65, 1))))
        assertTrue(customFontSupportsText(font.path, "A \t\n\u00a0\u200d\ufe0f\udb40\udd00"))
        assertFalse(customFontSupportsText(font.path, "A\u0301")) // Visible combining accent is absent.
    }

    @Test fun `subset preview chooses a completely supported Ukrainian phrase`() {
        val phrase = "Український текст"
        val map = grouped(12, phrase.filterNot(Char::isWhitespace).map { it.code }.distinct().sorted()
            .map { Triple(it, it, 1) })
        val font = font(map)
        assertFalse(customFontSupportsText(font.path, "Subset-Times New Roman"))
        assertEquals(phrase, customFontPreviewText(font.path))
        assertTrue(customFontSupportsText(font.path, customFontPreviewText(font.path)!!))
    }

    @Test fun `uppercase subset does not preview unsupported lowercase letters`() {
        val phrase = "УКРАЇНСЬКИЙ ТЕКСТ"
        val map = grouped(13, phrase.filterNot(Char::isWhitespace).map { it.code }.distinct().sorted()
            .map { Triple(it, it, 1) })
        val font = font(map)
        assertEquals(phrase, customFontPreviewText(font.path))
        assertFalse(customFontSupportsText(font.path, "Український текст"))
    }

    @Test fun `out of range actual glyph identifiers are not advertised`() {
        val font = file(sfnt(cmap(listOf(Record(3, 10,
            grouped(12, listOf(Triple(65, 65, 10)))))), glyphCount = 4))
        assertFalse(customFontSupportsText(font.path, "A"))
        assertNull(customFontPreviewText(font.path))
    }

    @Test fun `malformed tables and unknown formats fail closed without throwing`() {
        val invalidOffset = sfnt(cmap(listOf(Record(3, 1, formatFour()))))
        ByteBuffer.wrap(invalidOffset).putInt(20, Int.MAX_VALUE)
        val missing = file(invalidOffset)
        assertFalse(customFontSupportsText(missing.path, "A"))
        assertNull(customFontPreviewText(missing.path))
        val badRange = formatFour()
        ByteBuffer.wrap(badRange).putShort(28, 2) // Points inside idRangeOffset, not glyph array.
        val bad = font(badRange)
        assertFalse(customFontSupportsText(bad.path, "A"))
        val truncated = font(formatFour().copyOf(12))
        assertFalse(customFontSupportsText(truncated.path, "A"))
        val unknown = font(ByteBuffer.allocate(8).putShort(14).array())
        assertFalse(customFontSupportsText(unknown.path, "A"))
        assertNull(customFontPreviewText(unknown.path))
    }

    @Test fun `TTC uses its first face with file relative table offsets`() {
        val single = sfnt(cmap(listOf(Record(3, 1, formatFour()))))
        val tables = ByteBuffer.wrap(single)
        tables.putInt(20, tables.getInt(20) + 16)
        tables.putInt(36, tables.getInt(36) + 16)
        val header = ByteBuffer.allocate(16).putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(16).array()
        val font = file(header + single)
        assertTrue(customFontSupportsText(font.path, "AC"))
        assertFalse(customFontSupportsText(font.path, "B"))
    }

    @Test fun `replacing a font file invalidates cached coverage by file identity`() {
        val font = font(grouped(12, listOf(Triple(65, 65, 1))))
        assertTrue(customFontSupportsText(font.path, "A"))
        val modified = font.lastModified()
        font.writeBytes(sfnt(cmap(listOf(Record(3, 10, grouped(12, listOf(Triple(66, 66, 1))))))))
        assertTrue(font.setLastModified(modified + 2000))
        assertFalse(customFontSupportsText(font.path, "A"))
        assertTrue(customFontSupportsText(font.path, "B"))
    }

    private fun font(subtable: ByteArray): File = file(sfnt(cmap(listOf(Record(3, 10, subtable)))))
    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private data class Record(val platform: Int, val encoding: Int, val bytes: ByteArray)

    private fun cmap(records: List<Record>): ByteArray {
        val directorySize = 4 + records.size * 8
        val result = ByteBuffer.allocate(directorySize + records.sumOf { it.bytes.size })
            .putShort(0).putShort(records.size.toShort())
        var offset = directorySize
        records.forEach {
            result.putShort(it.platform.toShort()).putShort(it.encoding.toShort()).putInt(offset)
            offset += it.bytes.size
        }
        records.forEach { result.put(it.bytes) }
        return result.array()
    }

    private fun sfnt(cmap: ByteArray, glyphCount: Int = 1000): ByteArray =
        ByteBuffer.allocate(44 + cmap.size + 6).apply {
            putInt(0x00010000).putShort(2).putShort(0).putShort(0).putShort(0)
            putInt(0x636d6170).putInt(0).putInt(44).putInt(cmap.size)
            putInt(0x6d617870).putInt(0).putInt(44 + cmap.size).putInt(6)
            put(cmap).putInt(0x00010000).putShort(glyphCount.toShort())
        }.array()

    /** A..C uses glyphIdArray with a hole; final segment is the mandatory sentinel. */
    private fun formatFour(): ByteArray = ByteBuffer.allocate(38).apply {
        putShort(4).putShort(38).putShort(0).putShort(4).putShort(4).putShort(1).putShort(0)
        putShort(67).putShort((-1).toShort()).putShort(0)
        putShort(65).putShort((-1).toShort())
        putShort(1).putShort(1)
        putShort(4).putShort(0)
        putShort(5).putShort(0).putShort(7)
    }.array()

    private fun grouped(format: Int, groups: List<Triple<Int, Int, Int>>): ByteArray =
        ByteBuffer.allocate(16 + groups.size * 12).apply {
            putShort(format.toShort()).putShort(0).putInt(capacity()).putInt(0).putInt(groups.size)
            groups.forEach { (start, end, glyph) -> putInt(start).putInt(end).putInt(glyph) }
        }.array()
}
