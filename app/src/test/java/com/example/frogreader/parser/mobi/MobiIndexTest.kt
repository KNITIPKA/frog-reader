package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.data.parser.mobi.MobiIndex
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.ncxEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
