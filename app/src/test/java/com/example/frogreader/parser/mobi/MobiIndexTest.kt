package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.data.parser.mobi.MobiIndex
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.ncxEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Wraps INDX records into a minimal book so MobiSection can serve them. */
    private fun sectionWith(indxRecords: List<ByteArray>): Pair<MobiDoc, Int> {
        val file = tempFolder.newFile("indx.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = "<html><body><p>x</p></body></html>",
            compress = false,
            extraRecords = indxRecords,
        )
        return MobiDoc.open(file) to 2 // record 0 + 1 text record
    }

    @Test
    fun `single-bit tags with cncx labels parse`() {
        val (doc, first) = sectionWith(
            MobiBuilder.ncxIndx(
                listOf(
                    Triple(0, "Часть первая", 0),
                    Triple(500, "Глава вторая", 1),
                ),
            ),
        )
        val parsed = MobiIndex.parse(doc.mobi6, first)!!
        assertEquals(2, parsed.entries.size)

        val rows = ncxEntries(parsed)
        assertEquals(0, rows[0].filepos)
        assertEquals("Часть первая", rows[0].label)
        assertEquals(0, rows[0].depth)
        assertEquals(500, rows[1].filepos)
        assertEquals("Глава вторая", rows[1].label)
        assertEquals(1, rows[1].depth)
    }

    @Test
    fun `multi-bit mask carries a shifted value count`() {
        val (doc, first) = sectionWith(
            MobiBuilder.indx(
                tagx = listOf(MobiBuilder.TagxSpec(6, 2, 0x30)),
                entries = listOf(
                    // Two value-groups of two values each.
                    "e1" to mapOf(6 to listOf(11, 22, 3000, 44)),
                ),
            ),
        )
        val parsed = MobiIndex.parse(doc.mobi6, first)!!
        assertEquals(listOf(11L, 22L, 3000L, 44L), parsed.entries.single().tags[6])
    }

    @Test
    fun `garbage index returns null instead of failing`() {
        val (doc, first) = sectionWith(listOf(ByteArray(64) { 0x5A }))
        assertNull(MobiIndex.parse(doc.mobi6, first))
        assertNull(MobiIndex.parse(doc.mobi6, 999))
        assertNull(MobiIndex.parse(doc.mobi6, -1))
    }

    @Test
    fun `repeated IDXT offsets cannot amplify retained index entries`() {
        val records = MobiBuilder.indx(
            tagx = listOf(MobiBuilder.TagxSpec(1, 1, 0x01)),
            entries = (0 until 5).map { "e$it" to mapOf(1 to listOf(it)) },
        ).toMutableList()
        val entryRecord = records[1].copyOf()
        val idxt = entryRecord.index32ForTest(20)
        val firstOffsetHigh = entryRecord[idxt + 4]
        val firstOffsetLow = entryRecord[idxt + 5]
        for (entry in 1 until 5) {
            entryRecord[idxt + 4 + entry * 2] = firstOffsetHigh
            entryRecord[idxt + 5 + entry * 2] = firstOffsetLow
        }
        records[1] = entryRecord
        val file = tempFolder.newFile("indx-budget.mobi")
        MobiBuilder.buildMobi6(
            file,
            "<html><body><p>x</p></body></html>",
            compress = false,
            extraRecords = records,
        )
        MobiDoc.open(
            file,
            ReaderResourceLimits.DEFAULT.copy(maxMobiIndexEntries = 3),
        ).use { doc ->
            val error = assertThrows(ResourceLimitException::class.java) {
                MobiIndex.parse(doc.mobi6, 2, required = true)
            }
            assertEquals(ResourceLimitKind.ENTRY_COUNT, error.kind)
        }
    }

    @Test
    fun `TAGX value and CNCX object counts have independent budgets`() {
        fun open(name: String, records: List<ByteArray>, limits: ReaderResourceLimits): MobiDoc {
            val file = tempFolder.newFile(name)
            MobiBuilder.buildMobi6(
                file,
                "<html><body><p>x</p></body></html>",
                compress = false,
                extraRecords = records,
            )
            return MobiDoc.open(file, limits)
        }

        val tagx = MobiBuilder.indx(
            tagx = listOf(
                MobiBuilder.TagxSpec(1, 1, 0x01),
                MobiBuilder.TagxSpec(2, 1, 0x02),
                MobiBuilder.TagxSpec(3, 1, 0x04),
            ),
            entries = listOf("e" to mapOf(1 to listOf(1))),
        )
        open(
            "tagx-budget.mobi",
            tagx,
            ReaderResourceLimits.DEFAULT.copy(maxMobiTagxEntries = 2),
        ).use { doc ->
            assertThrows(ResourceLimitException::class.java) {
                MobiIndex.parse(doc.mobi6, 2, required = true)
            }
        }

        val values = MobiBuilder.indx(
            tagx = listOf(MobiBuilder.TagxSpec(6, 2, 0x30)),
            entries = listOf("e" to mapOf(6 to listOf(1, 2, 3, 4))),
        )
        open(
            "value-budget.mobi",
            values,
            ReaderResourceLimits.DEFAULT.copy(maxMobiIndexValues = 2),
        ).use { doc ->
            assertThrows(ResourceLimitException::class.java) {
                MobiIndex.parse(doc.mobi6, 2, required = true)
            }
        }

        val cncx = MobiBuilder.ncxIndx(
            listOf(
                Triple(0, "one", 0),
                Triple(10, "two", 0),
                Triple(20, "three", 0),
            ),
        )
        open(
            "cncx-budget.mobi",
            cncx,
            ReaderResourceLimits.DEFAULT.copy(maxMobiCncxEntries = 2),
        ).use { doc ->
            assertThrows(ResourceLimitException::class.java) {
                MobiIndex.parse(doc.mobi6, 2, required = true)
            }
        }
    }

    private fun ByteArray.index32ForTest(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    @Test
    fun `ncx supplies chapter boundaries titles and depths end-to-end`() {
        val placeholderA = "AAAAAAAAAA"
        val placeholderB = "BBBBBBBBBB"
        var html = """
            <html><body>
            <h1 id="$placeholderA">Первый раздел</h1>
            <p>Текст первого раздела достаточной длины для проверки.</p>
            <h1 id="$placeholderB">Второй раздел</h1>
            <p>Текст второго раздела, тоже вполне осмысленный.</p>
            </body></html>
        """.trimIndent()
        fun bytePosOf(marker: String): Int =
            html.substring(0, html.indexOf("<h1 id=\"$marker\""))
                .toByteArray(Charsets.UTF_8).size
        val posA = bytePosOf(placeholderA)
        val posB = bytePosOf(placeholderB)

        val file = tempFolder.newFile("ncx.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = html,
            compress = true,
            extraRecords = MobiBuilder.ncxIndx(
                listOf(
                    Triple(posA, "Часть I. Начало", 0),
                    Triple(posB, "Глава 2. Продолжение", 1),
                ),
            ),
            indxRecord = 2, // record0 + 1 text record → INDX header at 2
        )

        val content = MobiParser.parseContent(file, tempFolder.newFolder())
        val titled = content.chapters.filter { it.title != null }
        assertEquals(
            listOf("Часть I. Начало", "Глава 2. Продолжение"),
            titled.map { it.title },
        )
        assertEquals(listOf(0, 1), titled.map { it.depth })
        assertTrue(
            titled[1].elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text.startsWith("Текст второго") },
        )
    }
}
