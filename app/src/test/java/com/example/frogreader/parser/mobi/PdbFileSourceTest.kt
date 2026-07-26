package com.example.frogreader.parser.mobi

import com.example.frogreader.data.parser.mobi.MobiDoc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** File-backed lazy reads must behave exactly like the in-memory source. */
class PdbFileSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun buildBook(): java.io.File {
        val html = buildString {
            append("<html><body>")
            repeat(80) { append("<p>Абзац номер $it — немного текста для сжатия.</p>") }
            append("</body></html>")
        }
        return MobiBuilder.buildMobi6(
            target = temp.newFile("lazy.mobi"),
            html = html,
            compress = true,
            images = listOf(MobiBuilder.fakePng(1), MobiBuilder.fakePng(2)),
        )
    }

    @Test
    fun `file source matches byte source record for record`() {
        val file = buildBook()
        val bytes = file.readBytes()

        MobiDoc.open(file).use { fromFile ->
            MobiDoc.open(bytes).use { fromBytes ->
                assertEquals(fromBytes.pdb.recordCount, fromFile.pdb.recordCount)
                assertEquals(fromBytes.pdb.name, fromFile.pdb.name)
                assertEquals(fromBytes.pdb.typeCreator, fromFile.pdb.typeCreator)
                for (i in 0 until fromFile.pdb.recordCount) {
                    assertArrayEquals("record $i", fromBytes.pdb.record(i), fromFile.pdb.record(i))
                }
                assertArrayEquals(
                    fromBytes.mobi6.assembleText(),
                    fromFile.mobi6.assembleText(),
                )
                assertEquals(
                    fromBytes.mobi6.mobi?.fullName,
                    fromFile.mobi6.mobi?.fullName,
                )
            }
        }
    }

    @Test
    fun `windows over the file source hand back full records`() {
        val file = buildBook()
        MobiDoc.open(file).use { doc ->
            val direct = doc.pdb.record(1)
            val windowed = doc.pdb.withRecord(1) { data, off, len ->
                data.copyOfRange(off, off + len)
            }
            assertArrayEquals(direct, windowed)
        }
    }

    @Test
    fun `closing the doc releases the file handle`() {
        val file = buildBook()
        val doc = MobiDoc.open(file)
        doc.pdb.record(0) // warm read succeeds
        doc.close()
        assertThrows(IOException::class.java) { doc.pdb.record(0) }
    }

    @Test
    fun `truncated file is rejected`() {
        val file = buildBook()
        val whole = file.readBytes()
        val cut = temp.newFile("cut.mobi")
        cut.writeBytes(whole.copyOf(80)) // header survives, record table doesn't
        assertThrows(IOException::class.java) { MobiDoc.open(cut) }
    }

    @Test
    fun `non-book file is rejected and closed`() {
        val junk = temp.newFile("junk.bin")
        junk.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        assertThrows(IOException::class.java) { MobiDoc.open(junk) }
    }
}
