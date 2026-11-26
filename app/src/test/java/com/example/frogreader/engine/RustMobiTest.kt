package com.example.frogreader.engine

import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import com.example.frogreader.data.parser.mobi.Exth
import com.example.frogreader.data.parser.mobi.FilePdbSource
import com.example.frogreader.data.parser.mobi.HuffCdicDecoder
import com.example.frogreader.data.parser.mobi.Kf8Assembler
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.data.parser.mobi.MobiDrmException
import com.example.frogreader.data.parser.mobi.MobiFontRecord
import com.example.frogreader.data.parser.mobi.MobiHeader
import com.example.frogreader.data.parser.mobi.MobiIndex
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.PalmDocDecoder
import com.example.frogreader.data.parser.mobi.PalmDocHeader
import com.example.frogreader.data.parser.mobi.PdbFile
import com.example.frogreader.data.parser.mobi.TrailingEntries
import com.example.frogreader.parser.mobi.MobiBuilder
import com.example.frogreader.parser.mobi.MobiBuilder.u16
import com.example.frogreader.parser.mobi.MobiBuilder.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

class RustMobiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun requireNativeLibrary() {
        assumeTrue(
            "host Rust tests require -Dfrogreader.requireRust=true",
            java.lang.Boolean.getBoolean("frogreader.requireRust"),
        )
        assertTrue("Rust library must be ready", RustBridge.shared.isLibraryReady)
    }

    @Test
    fun `pdb file native header sniffing offsets limits and file source`() {
        val mobiBytes = MobiBuilder.buildPdb(
            PdbFile.TYPE_MOBI,
            "  Café\u0000junk",
            listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6)),
        )
        val palmBytes = MobiBuilder.buildPdb(PdbFile.TYPE_PALMDOC, "Plain", listOf(ByteArray(4)))

        assertTrue(PdbFile.isPdbBook(mobiBytes.copyOf(68)))
        assertTrue(PdbFile.isPdbBook(mobiBytes + ByteArray(8192)))
        assertTrue(PdbFile.isPdbBook(palmBytes.copyOf(68)))
        assertFalse(PdbFile.isPdbBook("PK\u0003\u0004".toByteArray().copyOf(68)))
        assertFalse(PdbFile.isPdbBook(ByteArray(10)))

        PdbFile(mobiBytes).use { pdb ->
            assertEquals("Café", pdb.name)
            assertEquals(PdbFile.TYPE_MOBI, pdb.typeCreator)
            assertTrue(pdb.isBook)
            assertEquals(3, pdb.recordCount)
            assertArrayEquals(byteArrayOf(1, 2, 3), pdb.record(0))
            assertArrayEquals(byteArrayOf(4, 5), pdb.record(1))
            assertArrayEquals(byteArrayOf(6), pdb.record(2))
            assertEquals(3, pdb.recordLength(0))
        }

        val file = tempFolder.newFile("native-pdb.mobi").apply { writeBytes(mobiBytes) }
        PdbFile(FilePdbSource(file)).use { pdb ->
            assertEquals(3, pdb.recordCount)
            assertArrayEquals(byteArrayOf(4, 5), pdb.record(1))
        }

        assertThrows(IOException::class.java) { PdbFile(ByteArray(80)) }

        val recordLimit = assertThrows(ResourceLimitException::class.java) {
            PdbFile(mobiBytes, ReaderResourceLimits.DEFAULT.copy(maxMobiRecords = 2))
        }
        assertEquals(ResourceLimitKind.ENTRY_COUNT, recordLimit.kind)

        val aggLimit = assertThrows(ResourceLimitException::class.java) {
            PdbFile(mobiBytes, ReaderResourceLimits.DEFAULT.copy(maxMobiReadAggregateBytes = 80))
        }
        assertEquals(ResourceLimitKind.ACTUAL_AGGREGATE, aggLimit.kind)

        val corruptOffsets = mobiBytes.copyOf().apply {
            this[78 + 8] = 0
            this[78 + 8 + 1] = 0
            this[78 + 8 + 2] = 0
            this[78 + 8 + 3] = 1
        }
        assertThrows(IOException::class.java) { PdbFile(corruptOffsets) }
    }

    @Test
    fun `palmdoc mobi and exth native headers handle codepages padding truncation and drm`() {
        assertThrows(IOException::class.java) { PalmDocHeader(ByteArray(15)) }

        val plainPalm = ByteArray(16).apply {
            this[0] = 0; this[1] = 2 // compression = 2
            this[4] = 0xFF.toByte(); this[5] = 0xFF.toByte()
            this[6] = 0xFF.toByte(); this[7] = 0xFF.toByte() // u32 max -> 0
            this[8] = 0; this[9] = 3 // textRecordCount = 3
            this[10] = 0x10; this[11] = 0x00 // recordSize = 4096
            this[12] = 0; this[13] = 0 // encryption = 0
        }
        val palmHeader = PalmDocHeader(plainPalm)
        assertEquals(PalmDocHeader.COMPRESSION_PALMDOC, palmHeader.compression)
        assertEquals(0, palmHeader.textLength)
        assertEquals(3, palmHeader.textRecordCount)
        assertEquals(4096, palmHeader.recordSize)
        assertEquals(0, palmHeader.encryptionType)
        assertNull(MobiHeader.parse(plainPalm))

        val cp1251 = Charset.forName("windows-1251")
        val record0Utf8 = MobiBuilder.record0(
            compression = 2,
            textLength = 12345,
            textRecords = 4,
            encoding = 65001,
            fullName = "Полное имя книги",
            extraFlags = 3,
            firstImage = 7,
            indxRecord = 9,
            version = 8,
            kf8 = MobiBuilder.Kf8Fields(fdstRecord = 11, fdstCount = 2, skelIndex = 12, fragIndex = 13),
            exth = listOf(
                Exth.AUTHOR to "Первый Автор".toByteArray(),
                Exth.AUTHOR to "Второй Автор".toByteArray(),
                999 to byteArrayOf(1, 2, 3),
                Exth.UPDATED_TITLE to "1984".toByteArray(),
                Exth.COVER_OFFSET to byteArrayOf(0, 0, 0, 5),
            ),
        )
        val kf8Header = MobiHeader.parse(record0Utf8)!!
        assertEquals(Charsets.UTF_8, kf8Header.charset)
        assertEquals("Полное имя книги", kf8Header.fullName)
        assertEquals(3, kf8Header.extraRecordDataFlags)
        assertEquals(7, kf8Header.firstImageIndex)
        assertEquals(9, kf8Header.indxRecordOffset)
        assertEquals(11, kf8Header.fdstRecord)
        assertEquals(2, kf8Header.fdstCount)
        assertEquals(12, kf8Header.skelIndex)
        assertEquals(13, kf8Header.fragIndex)

        val exth = Exth.parse(record0Utf8, kf8Header.headerLength)
        assertEquals(listOf("Первый Автор", "Второй Автор"), exth.strings(Exth.AUTHOR, Charsets.UTF_8))
        assertEquals("1984", exth.string(Exth.UPDATED_TITLE, Charsets.UTF_8))
        assertEquals(5, exth.int(Exth.COVER_OFFSET))
        assertEquals(Exth.EMPTY.entries, Exth.parse(plainPalm, 232).entries)

        // CP1251 fullName decoding + EXTH padding tolerance + mid-list truncation
        val name1251 = "Книга 1251".toByteArray(cp1251)
        val paddedExth = ByteArrayOutputStream().apply {
            write("EXTH".toByteArray())
            write(byteArrayOf(0, 0, 0, 32))
            write(byteArrayOf(0, 0, 0, 2)) // declares 2 entries
            write(byteArrayOf(0, 0, 0, 100, 0, 0, 0, 12)) // AUTHOR: "auth"
            write("auth".toByteArray())
            write(byteArrayOf(0, 0, 0, 101, 0, 0, 0, 60)) // truncated 2nd entry beyond record0
            write(byteArrayOf(1, 2))
        }.toByteArray()
        val mobiEnd = 0x108
        val paddedOffset = mobiEnd + 3 // +3 padding bytes before EXTH
        val nameOffset = paddedOffset + paddedExth.size
        val rec1251 = MobiBuilder.record0(
            compression = 1,
            textLength = 10,
            textRecords = 1,
            encoding = 1251,
            version = 6,
        ).copyOf(nameOffset + name1251.size)
        putBe32(rec1251, 16 + 0x44, nameOffset)
        putBe32(rec1251, 16 + 0x48, name1251.size)
        System.arraycopy(paddedExth, 0, rec1251, paddedOffset, paddedExth.size)
        System.arraycopy(name1251, 0, rec1251, nameOffset, name1251.size)

        val mobi1251 = MobiHeader.parse(rec1251)!!
        assertEquals(cp1251, mobi1251.charset)
        assertEquals("Книга 1251", mobi1251.fullName)
        assertEquals(-1, mobi1251.fdstRecord)
        assertEquals(-1, mobi1251.skelIndex)
        val partialExth = Exth.parse(rec1251, mobi1251.headerLength)
        assertEquals(1, partialExth.entries.size)
        assertEquals("auth", partialExth.string(Exth.AUTHOR, Charsets.UTF_8))

        // Bad MOBI header length -> IOException
        val badHeader = record0Utf8.copyOf()
        putBe32(badHeader, 20, 12)
        assertThrows(IOException::class.java) { MobiHeader.parse(badHeader) }

        // DRM throws MobiDrmException via MobiDoc.open
        val drmRecord0 = MobiBuilder.record0(compression = 2, textLength = 10, textRecords = 1, encryption = 2)
        val drmPdb = MobiBuilder.buildPdb(PdbFile.TYPE_MOBI, "DRM", listOf(drmRecord0, ByteArray(8)))
        assertThrows(MobiDrmException::class.java) { MobiDoc.open(drmPdb) }
    }

    @Test
    fun `palmdoc native decompresses tokens offsets and reports exact errors`() {
        val sample = (
            "Hello world! aaaaaaaaaaaaaaaaaa " +
                "Привет мир, кириллица и повторы: repeat repeat repeat. " +
                "\u0000\u0007control"
            ).toByteArray(Charsets.UTF_8)
        val compressed = MobiBuilder.palmdocCompress(sample)
        val paddedSrc = ByteArray(11) { 0x55 } + compressed + ByteArray(7) { 0x44 }

        val nativeDst = ByteArray(sample.size + 32) { 0x11 }
        val nativeLen = PalmDocDecoder.decompress(paddedSrc, 11, compressed.size, nativeDst, 5)
        assertEquals(sample.size, nativeLen)
        assertArrayEquals(sample, nativeDst.copyOfRange(5, 5 + nativeLen))

        val errorFixtures = listOf(
            Triple(byteArrayOf(0x03, 0x41), 16, "PalmDoc: truncated literal run"),
            Triple(byteArrayOf(0x80.toByte()), 16, "PalmDoc: truncated back-reference"),
            Triple(byteArrayOf(0x80.toByte(), 0x00), 16, "PalmDoc: bad back-reference"),
            Triple(byteArrayOf(0x41, 0x42, 0x43), 2, "PalmDoc: output overflow"),
            Triple(byteArrayOf(0xC1.toByte()), 1, "PalmDoc: output overflow"),
        )
        for ((payload, cap, expectedMessage) in errorFixtures) {
            val err = assertThrows(IOException::class.java) {
                PalmDocDecoder.decompress(payload, 0, payload.size, ByteArray(cap), 0)
            }
            assertEquals(expectedMessage, err.message)
        }
    }

    @Test
    fun `trailing entries native trims multibyte and size varints`() {
        val raw = "payload-data".toByteArray()
        val withMultiAndSizes = MobiBuilder.withTrailing(
            raw,
            listOf(byteArrayOf(1), ByteArray(200)),
            multibyteTail = 2,
        )
        val padded = ByteArray(9) { 0x22 } + withMultiAndSizes + ByteArray(3) { 0x33 }
        assertEquals(raw.size, TrailingEntries.contentLength(padded, 9, withMultiAndSizes.size, 0b111))

        val varintTail = MobiBuilder.withTrailing("abc".toByteArray(), listOf(ByteArray(198)))
        assertEquals(200, TrailingEntries.backwardVarint(varintTail, 0, varintTail.size))

        val corrupt = "abc".toByteArray() + byteArrayOf(0xFF.toByte())
        assertEquals(corrupt.size, TrailingEntries.contentLength(corrupt, 0, corrupt.size, 0b10))
    }

    @Test
    fun `huff cdic native decodes phrases and enforces overflow vs resource limits`() {
        val huff = buildTestHuff()
        val cdic = buildTestCdic(
            listOf(
                "ab".toByteArray() to true,
                "cd".toByteArray() to true,
                byteArrayOf(0x55) to false,
            ),
        )
        val nativeDec = HuffCdicDecoder(huff, listOf(cdic))

        val encoded = byteArrayOf(0x00, 0x00, 0x84.toByte(), 0x55, 0x55, 0x00)
        val nOut = ByteArray(128)
        val nCount = nativeDec.decompress(encoded, 2, 3, nOut, 4)
        assertEquals(36, nCount)
        assertEquals("abcdcdcdcdcdcdcdcdcd" + "cd".repeat(8), String(nOut, 4, nCount, Charsets.UTF_8))

        assertArrayEquals(
            nOut.copyOfRange(4, 4 + nCount),
            nativeDec.decompressBounded(encoded, 2, 3, 128),
        )

        // Overflow in decompress -> IOException, whereas decompressBounded -> ResourceLimitException
        assertThrows(IOException::class.java) {
            nativeDec.decompress(encoded, 2, 1, ByteArray(4), 0)
        }
        val limitErr = assertThrows(ResourceLimitException::class.java) {
            nativeDec.decompressBounded(encoded, 2, 1, 4)
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, limitErr.kind)

        // Dictionary byte budget
        val dictLimit = assertThrows(ResourceLimitException::class.java) {
            HuffCdicDecoder(huff, listOf(cdic, cdic), maxDecodedBytes = 1024, maxDictionaryBytes = cdic.size + 1)
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, dictLimit.kind)

        // Decoded dictionary expansion budget
        val expandLimit = assertThrows(ResourceLimitException::class.java) {
            val dec = HuffCdicDecoder(huff, listOf(cdic), maxDecodedBytes = 4, maxDictionaryBytes = 1024)
            dec.decompressBounded(byteArrayOf(0x00), 0, 1, 1024)
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, expandLimit.kind)

        // Cyclic CDIC phrase -> IOException (damaged MOBI, not ResourceLimitException)
        val cyclicCdic = buildTestCdic(
            listOf(
                "ab".toByteArray() to true,
                "cd".toByteArray() to true,
                byteArrayOf(0x00) to false,
            ),
        )
        val cyclicErr = assertThrows(IOException::class.java) {
            HuffCdicDecoder(huff, listOf(cyclicCdic)).decompressBounded(byteArrayOf(0x00), 0, 1, 64)
        }
        assertTrue(cyclicErr !is ResourceLimitException)
    }

    @Test
    fun `font record native decodes plain xor zlib and enforces limits`() {
        val sfnt = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(2400) { ((it * 13) and 0xFF).toByte() }
        val key = byteArrayOf(0x1F, 0xA2.toByte(), 0x77, 0x39)
        val variants = listOf(
            MobiBuilder.fontRecord(sfnt),
            MobiBuilder.fontRecord(sfnt, compress = true),
            MobiBuilder.fontRecord(sfnt, xorKey = key, compress = false),
            MobiBuilder.fontRecord(sfnt, xorKey = key, compress = true),
        )
        for (record in variants) {
            val padded = ByteArray(7) { 0x7A } + record + ByteArray(5)
            assertTrue(MobiFontRecord.isFontRecord(padded, 7, record.size))
            assertArrayEquals(sfnt, MobiFontRecord.decode(padded, 7, record.size))
            assertArrayEquals(sfnt, MobiFontRecord.decode(padded, 7, record.size, sfnt.size))
            assertNull(MobiFontRecord.decode(padded, 7, record.size, sfnt.size - 1))
        }
        val compressed = MobiBuilder.fontRecord(sfnt, xorKey = key, compress = true)
        assertNull(MobiFontRecord.decode(compressed, 0, compressed.size - 3))
    }

    @Test
    fun `mobi index native parses tags cncx varints and limits`() {
        val records = MobiBuilder.indx(
            tagx = listOf(
                MobiBuilder.TagxSpec(1, 1, 0x01),
                MobiBuilder.TagxSpec(3, 1, 0x02),
                MobiBuilder.TagxSpec(6, 2, 0x3C),
            ),
            entries = listOf(
                "000" to mapOf(1 to listOf(123), 3 to listOf(0), 6 to listOf(1, 2, 300, 400)),
                "001" to mapOf(1 to listOf(456), 3 to listOf(11)),
            ),
            cncxStrings = listOf("Первая", "Вторая"),
        )
        val mobi = tempFolder.newFile("indx-native.mobi")
        MobiBuilder.buildMobi6(mobi, "<html><body><p>Body</p></body></html>", compress = false, extraRecords = records)
        MobiDoc.open(mobi).use { doc ->
            val parsed = MobiIndex.parse(doc.mobi6, 2)
            assertNotNull(parsed)
            parsed!!
            assertEquals(mapOf(0 to "Первая", 13 to "Вторая"), parsed.cncx)
            assertEquals(2, parsed.entries.size)
            assertEquals("000", parsed.entries[0].label)
            assertEquals(
                mapOf(1 to listOf(123L), 3 to listOf(0L), 6 to listOf(1L, 2L, 300L, 400L)),
                parsed.entries[0].tags,
            )
            assertEquals("001", parsed.entries[1].label)
            assertEquals(mapOf(1 to listOf(456L), 3 to listOf(11L)), parsed.entries[1].tags)
        }

        val varintBytes = MobiBuilder.forwardVarintBytes(25_000) + byteArrayOf(0x01)
        assertEquals(25_000L to 3, MobiIndex.forwardVarint(varintBytes, 0))
        assertNull(MobiIndex.forwardVarint(byteArrayOf(0x01, 0x02), 0))

        // CNCX byte aggregate limit
        MobiDoc.open(
            mobi,
            ReaderResourceLimits.DEFAULT.copy(maxMobiCncxBytes = 8),
        ).use { doc ->
            val err = assertThrows(ResourceLimitException::class.java) {
                MobiIndex.parse(doc.mobi6, 2, required = true)
            }
            assertEquals(ResourceLimitKind.ACTUAL_AGGREGATE, err.kind)
        }
    }

    @Test
    fun `kf8 assembler native assembles parts flows fragLocations and enforces limits`() {
        val shell = "<html><head><link rel=\"stylesheet\" href=\"kindle:flow:0001\"/></head><body></body></html>"
        val spec = MobiBuilder.Kf8Spec(
            skeletons = listOf(shell, shell),
            fragments = listOf(
                listOf("<p>First fragment.</p>", "<p>Second fragment.</p>"),
                listOf("<p>Part two.</p>"),
            ),
            css = "p { text-align: justify; }",
            additionalCssFlows = listOf(".extra { font-style: italic; }"),
        )
        val file = tempFolder.newFile("kf8-native.azw3")
        MobiBuilder.buildKf8(file, spec)
        MobiDoc.open(file).use { doc ->
            val kf8 = doc.kf8!!
            val raw = kf8.assembleText()
            val book = Kf8Assembler.assemble(kf8, raw)
            assertEquals(2, book.parts.size)
            assertEquals(3, book.flows.size)
            assertEquals(3, book.fragLocations.size)

            val part0 = String(book.parts[0].bytes, Charsets.UTF_8)
            val part1 = String(book.parts[1].bytes, Charsets.UTF_8)
            assertTrue(part0.contains("<p>First fragment.</p><p>Second fragment.</p>"))
            assertTrue(part1.contains("<p>Part two.</p>"))
            assertEquals("p { text-align: justify; }", String(book.flows[1], Charsets.UTF_8))
            assertEquals(".extra { font-style: italic; }", String(book.flows[2], Charsets.UTF_8))
            assertEquals(0, book.fragLocations[0].first)
            assertEquals(0, book.fragLocations[1].first)
            assertEquals(1, book.fragLocations[2].first)
        }

        // Fragment count limit
        val fragLimit = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                tempFolder.newFolder("frag-limit-imgs"),
                ReaderResourceLimits.DEFAULT.copy(maxKf8Fragments = 2),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_COUNT, fragLimit.kind)

        // Flow aggregate limit
        val flowLimit = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                tempFolder.newFolder("flow-limit-imgs"),
                ReaderResourceLimits.DEFAULT.copy(maxKf8FlowAggregateBytes = 32),
            )
        }
        assertEquals(ResourceLimitKind.ACTUAL_AGGREGATE, flowLimit.kind)
    }

    private fun putBe32(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = (value ushr 24).toByte()
        bytes[off + 1] = (value ushr 16).toByte()
        bytes[off + 2] = (value ushr 8).toByte()
        bytes[off + 3] = value.toByte()
    }

    private fun buildTestHuff(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HUFF".toByteArray())
        out.u32(24)
        out.u32(24)
        out.u32(24 + 256 * 4)
        out.u32(0)
        out.u32(0)
        repeat(256) { out.u32(0x282) }
        for (len in 1..32) {
            if (len == 2) {
                out.u32(0)
                out.u32(2)
            } else {
                out.u32(0)
                out.u32(0)
            }
        }
        return out.toByteArray()
    }

    private fun buildTestCdic(entries: List<Pair<ByteArray, Boolean>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("CDIC".toByteArray())
        out.u32(16)
        out.u32(entries.size)
        out.u32(2)
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
}
