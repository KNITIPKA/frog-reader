package com.example.frogreader.parser.mobi

import com.example.frogreader.data.parser.mobi.Exth
import com.example.frogreader.data.parser.mobi.HuffCdicDecoder
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.data.parser.mobi.MobiDrmException
import com.example.frogreader.data.parser.mobi.PalmDocDecoder
import com.example.frogreader.data.parser.mobi.PdbFile
import com.example.frogreader.data.parser.mobi.TrailingEntries
import com.example.frogreader.parser.mobi.MobiBuilder.u16
import com.example.frogreader.parser.mobi.MobiBuilder.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class MobiPdbTest {

    @Test
    fun `parses header and slices records`() {
        val records = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5),
            byteArrayOf(6),
        )
        val pdb = PdbFile(MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "Тест", records))

        assertEquals(3, pdb.recordCount)
        assertEquals(PdbFile.TYPE_MOBI, pdb.typeCreator)
        assertArrayEquals(byteArrayOf(1, 2, 3), pdb.record(0))
        assertArrayEquals(byteArrayOf(4, 5), pdb.record(1))
        // The last record runs to EOF.
        assertArrayEquals(byteArrayOf(6), pdb.record(2))
        assertEquals(3, pdb.recordLength(0))
    }

    @Test
    fun `sniffs book type from 68-byte header`() {
        val mobi = MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "x", listOf(ByteArray(4)))
        val palm = MobiBuilder.buildPdb(PdbFile.TYPE_PALMDOC, "x", listOf(ByteArray(4)))
        assertTrue(PdbFile.isPdbBook(mobi.copyOf(68)))
        assertTrue(PdbFile.isPdbBook(palm.copyOf(68)))
        assertTrue(!PdbFile.isPdbBook("PK".toByteArray().copyOf(68)))
        assertTrue(!PdbFile.isPdbBook(ByteArray(10)))
    }

    @Test
    fun `corrupt record table throws`() {
        val bytes = MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "x", listOf(ByteArray(8), ByteArray(8)))
        // Break the second record's offset (points before the table).
        bytes[78 + 8] = 0
        bytes[78 + 8 + 1] = 0
        bytes[78 + 8 + 2] = 0
        bytes[78 + 8 + 3] = 1
        assertThrows(IOException::class.java) { PdbFile(bytes) }
    }
}

class MobiHeadersTest {

    private fun docWith(record0: ByteArray): MobiDoc =
        MobiDoc.open(MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "PdbName", listOf(record0, ByteArray(8))))

    @Test
    fun `parses palmdoc and mobi headers`() {
        val doc = docWith(
            MobiBuilder.record0(
                compression = 2, textLength = 12345, textRecords = 4,
                encoding = 65001, fullName = "Полное имя книги", extraFlags = 3,
                firstImage = 7, indxRecord = 9,
            ),
        )
        val section = doc.mobi6
        assertEquals(2, section.palmDoc.compression)
        assertEquals(12345, section.palmDoc.textLength)
        assertEquals(4, section.palmDoc.textRecordCount)
        assertEquals(0, section.palmDoc.encryptionType)
        val mobi = section.mobi!!
        assertEquals(65001, mobi.textEncoding)
        assertEquals("Полное имя книги", mobi.fullName)
        assertEquals(3, mobi.extraRecordDataFlags)
        assertEquals(7, mobi.firstImageIndex)
        assertEquals(9, mobi.indxRecordOffset)
        assertNull(doc.kf8)
    }

    @Test
    fun `exth entries parse with unknown types skipped gracefully`() {
        val doc = docWith(
            MobiBuilder.record0(
                compression = 1, textLength = 10, textRecords = 1,
                exth = listOf(
                    Exth.AUTHOR to "Джордж Оруэлл".toByteArray(),
                    999 to byteArrayOf(1, 2, 3),
                    Exth.UPDATED_TITLE to "1984".toByteArray(),
                    Exth.LANGUAGE to "ru".toByteArray(),
                    Exth.COVER_OFFSET to byteArrayOf(0, 0, 0, 5),
                ),
            ),
        )
        val exth = doc.mobi6.exth
        assertEquals("Джордж Оруэлл", exth.string(Exth.AUTHOR, Charsets.UTF_8))
        assertEquals("1984", exth.string(Exth.UPDATED_TITLE, Charsets.UTF_8))
        assertEquals("ru", exth.string(Exth.LANGUAGE, Charsets.UTF_8))
        assertEquals(5, exth.int(Exth.COVER_OFFSET))
        assertNull(exth.string(Exth.THUMB_OFFSET, Charsets.UTF_8))
    }

    @Test
    fun `drm-protected book is rejected with the dedicated exception`() {
        assertThrows(MobiDrmException::class.java) {
            docWith(
                MobiBuilder.record0(
                    compression = 2, textLength = 10, textRecords = 1, encryption = 2,
                ),
            )
        }
    }

    @Test
    fun `textread record0 has no mobi header`() {
        val record0 = MobiBuilder.record0(compression = 2, textLength = 5, textRecords = 1)
            .copyOf(16) // just the PalmDOC header
        val doc = MobiDoc.open(
            MobiBuilder.buildPdb(PdbFile.TYPE_PALMDOC, "Plain", listOf(record0, ByteArray(4))),
        )
        assertNull(doc.mobi6.mobi)
        assertEquals(2, doc.mobi6.palmDoc.compression)
    }
}

class MobiPalmDocTest {

    private fun roundTrip(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val compressed = MobiBuilder.palmdocCompress(bytes)
        val out = ByteArray(bytes.size + 16)
        val written = PalmDocDecoder.decompress(compressed, 0, compressed.size, out, 0)
        return String(out, 0, written, Charsets.UTF_8)
    }

    @Test
    fun `round-trips every token class`() {
        val text = "Simple ASCII with spaces and words. " +
            "aaaaaaaaaaaaaaaaaaaaaa overlapping backref " + // dist=1 runs
            "Русский текст в UTF-8 идёт литеральными сериями. " +
            "repeat repeat repeat repeat done."
        assertEquals(text, roundTrip(text))
    }

    @Test
    fun `long text with distant repeats round-trips`() {
        val builder = StringBuilder()
        repeat(60) { builder.append("Фраза номер $it, потом ещё немного текста. ") }
        builder.append(builder.substring(0, 500)) // far back-references
        val text = builder.toString()
        assertEquals(text, roundTrip(text))
    }

    @Test
    fun `null byte and control tokens survive`() {
        val bytes = byteArrayOf(0x41, 0x00, 0x42, 0x07, 0x43) // A NUL B BEL C
        val compressed = MobiBuilder.palmdocCompress(bytes)
        val out = ByteArray(32)
        val written = PalmDocDecoder.decompress(compressed, 0, compressed.size, out, 0)
        assertArrayEquals(bytes, out.copyOf(written))
    }

    @Test
    fun `trailing entries are trimmed per flags`() {
        val record = "content!".toByteArray()

        // No flags: untouched.
        assertEquals(8, TrailingEntries.contentLength(record, 0, record.size, 0))

        // One size-entry (flag bit 1).
        val one = MobiBuilder.withTrailing(record, listOf(byteArrayOf(9, 9, 9)))
        assertEquals(8, TrailingEntries.contentLength(one, 0, one.size, 0b10))

        // Two entries (bits 1 and 2) trimmed in reverse order.
        val two = MobiBuilder.withTrailing(record, listOf(byteArrayOf(1), byteArrayOf(2, 2)))
        assertEquals(8, TrailingEntries.contentLength(two, 0, two.size, 0b110))

        // A large entry whose size needs a 2-byte varint.
        val big = MobiBuilder.withTrailing(record, listOf(ByteArray(200)))
        assertEquals(8, TrailingEntries.contentLength(big, 0, big.size, 0b10))

        // Multibyte-overlap tail (bit 0): 2 chars + the count byte.
        val multi = MobiBuilder.withTrailing(record, emptyList(), multibyteTail = 2)
        assertEquals(8, TrailingEntries.contentLength(multi, 0, multi.size, 0b1))

        // Both kinds together.
        val both = MobiBuilder.withTrailing(record, listOf(byteArrayOf(7)), multibyteTail = 1)
        assertEquals(8, TrailingEntries.contentLength(both, 0, both.size, 0b11))
    }

    @Test
    fun `corrupt trailing size stops trimming instead of crashing`() {
        val record = "abc".toByteArray() + byteArrayOf(0xFF.toByte())
        // The bogus entry claims a size larger than the record.
        val length = TrailingEntries.contentLength(record, 0, record.size, 0b10)
        assertEquals(record.size, length)
    }
}

class HuffCdicTest {

    /** All codes are 2 bits: 00→dict[2], 01→dict[1], 10→dict[0]. */
    private fun buildHuff(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HUFF".toByteArray())
        out.u32(24) // header length
        out.u32(24) // cache table offset
        out.u32(24 + 256 * 4) // base table offset
        out.u32(0) // (big-endian table offsets duplicated in real files)
        out.u32(0)
        repeat(256) { out.u32(0x282) } // len=2, terminal, max value 2
        for (len in 1..32) {
            if (len == 2) {
                out.u32(0) // mincode
                out.u32(2) // maxcode
            } else {
                out.u32(0)
                out.u32(0)
            }
        }
        return out.toByteArray()
    }

    private fun buildCdic(): ByteArray {
        val entries = listOf(
            "ab".toByteArray() to true, // dict[0], code 10
            "cd".toByteArray() to true, // dict[1], code 01
            byteArrayOf(0x55) to false, // dict[2], code 00 → "cd"×4 recursively
        )
        val out = ByteArrayOutputStream()
        out.write("CDIC".toByteArray())
        out.u32(16)
        out.u32(3) // total phrases
        out.u32(2) // code bits
        // Offset table (relative to byte 16), then the entries.
        var dataOffset = 2 * entries.size
        for ((bytes, _) in entries) {
            out.u16(dataOffset)
            dataOffset += 2 + bytes.size
        }
        for ((bytes, terminal) in entries) {
            out.u16(bytes.size or (if (terminal) 0x8000 else 0))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    @Test
    fun `decodes symbols and recursive phrases`() {
        val decoder = HuffCdicDecoder(buildHuff(), listOf(buildCdic()))
        // Bits: 10 00 01 00 → "ab" + "cdcdcdcd" + "cd" + "cdcdcdcd".
        val src = byteArrayOf(0x84.toByte())
        val out = ByteArray(64)
        val written = decoder.decompress(src, 0, src.size, out, 0)
        assertEquals("abcdcdcdcdcdcdcdcdcd", String(out, 0, written, Charsets.UTF_8))
    }

    @Test
    fun `decodes across the 32-bit accumulator reload`() {
        val decoder = HuffCdicDecoder(buildHuff(), listOf(buildCdic()))
        // 6 bytes of 0x55 = 24 codes "01" → "cd" × 24 (48 bytes).
        val src = ByteArray(6) { 0x55 }
        val out = ByteArray(64)
        val written = decoder.decompress(src, 0, src.size, out, 0)
        assertEquals("cd".repeat(24), String(out, 0, written, Charsets.UTF_8))
    }

    @Test
    fun `truncated huff record throws`() {
        assertThrows(IOException::class.java) {
            HuffCdicDecoder(buildHuff().copyOf(30), listOf(buildCdic()))
        }
        assertThrows(IOException::class.java) {
            HuffCdicDecoder(ByteArray(10), emptyList())
        }
    }
}
