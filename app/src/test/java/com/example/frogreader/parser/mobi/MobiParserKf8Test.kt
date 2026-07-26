package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.parser.mobi.MobiParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MobiParserKf8Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun spec(): MobiBuilder.Kf8Spec {
        val shell = "<html><head>" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"kindle:flow:0001?mime=text/css\"/>" +
            "</head><body></body></html>"
        return MobiBuilder.Kf8Spec(
            skeletons = listOf(shell, shell),
            fragments = listOf(
                listOf(
                    "<p class=\"centered\">Центрированный абзац из CSS-потока.</p>" +
                        "<p>Абзац со сноской<a href=\"kindle:pos:fid:0001:off:0000000000\">[1]</a> внутри книги.</p>" +
                        "<p><img src=\"kindle:embed:0001?mime=image/png\"/></p>",
                    "<p>Текст примечания КФ8, извлекаемый по kindle:pos.</p>",
                ),
                listOf(
                    "<h1>Вторая часть</h1><p>Текст второй части книги, вполне обычный.</p>",
                ),
            ),
            css = "p.centered { text-align: center; }",
        )
    }

    @Test
    fun `pure azw3 parses parts with css notes and images`() {
        val file = tempFolder.newFile("book.azw3")
        MobiBuilder.buildKf8(file, spec())
        val content = MobiParser.parseContent(file, tempFolder.newFolder())

        assertEquals(2, content.chapters.size)

        val paragraphs = content.chapters[0].elements
            .filterIsInstance<ContentElement.Paragraph>()
        // CSS from the kindle:flow stylesheet went through CssResolver.
        val centered = paragraphs.first { it.text.text.startsWith("Центрированный") }
        assertEquals(BlockAlign.CENTER, centered.block?.align)

        // kindle:pos link → anchor → footnote text.
        val note = content.notes.entries.single()
        assertEquals("#kpos_1_0", note.key)
        assertTrue(note.value.text.startsWith("Текст примечания КФ8"))
        val linked = paragraphs.first { it.text.text.contains("[1]") }
        val annotation = linked.text
            .getStringAnnotations(FOOTNOTE_TAG, 0, linked.text.length)
            .single()
        assertEquals("#kpos_1_0", annotation.item)

        // kindle:embed image extracted from the resource record.
        val image = content.chapters[0].elements
            .filterIsInstance<ContentElement.Image>().single()
        assertArrayEquals(MobiBuilder.fakePng(42), File(image.path).readBytes())

        assertEquals("Вторая часть", content.chapters[1].title)
        assertEquals("ru", content.language) // Cyrillic heuristic
    }

    @Test
    fun `combo file prefers the kf8 half`() {
        val file = tempFolder.newFile("combo.mobi")
        MobiBuilder.buildKf8(file, spec(), combo = true)
        val content = MobiParser.parseContent(file, tempFolder.newFolder())

        val texts = content.chapters.flatMap { ch ->
            ch.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        }
        assertTrue(texts.any { it.startsWith("Центрированный") })
        assertTrue(texts.none { it.contains("Старая версия") })
    }

    @Test
    fun `combo with a damaged skeleton falls back to mobi6`() {
        val file = tempFolder.newFile("broken-combo.mobi")
        MobiBuilder.buildKf8(file, spec(), combo = true, breakSkel = true)
        val content = MobiParser.parseContent(file, tempFolder.newFolder())

        val texts = content.chapters.flatMap { ch ->
            ch.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        }
        assertTrue(texts.any { it.contains("Старая версия") })
    }

    @Test
    fun `pure azw3 with a damaged skeleton throws`() {
        val file = tempFolder.newFile("broken.azw3")
        MobiBuilder.buildKf8(file, spec(), breakSkel = true)
        assertThrows(IOException::class.java) {
            MobiParser.parseContent(file, tempFolder.newFolder())
        }
    }

    @Test
    fun `embedded fonts extract from FONT records`() {
        val sfnt = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(400) { (it % 100).toByte() }
        val base = spec()
        val withFontCss = MobiBuilder.Kf8Spec(
            skeletons = base.skeletons,
            fragments = base.fragments,
            css = "@font-face { font-family: BookSerif; " +
                "src: url(kindle:embed:0002?mime=font/ttf); font-weight: bold; } " +
                base.css,
        )
        val file = tempFolder.newFile("fonts.azw3")
        MobiBuilder.buildKf8(
            file,
            withFontCss,
            extraResources = listOf(
                MobiBuilder.fontRecord(sfnt, xorKey = byteArrayOf(0x11, 0x22), compress = true),
            ),
        )
        val content = MobiParser.parseContent(file, tempFolder.newFolder())

        val font = content.fonts.single()
        assertEquals("bookserif", font.family)
        assertTrue(font.bold)
        assertArrayEquals(sfnt, File(font.path).readBytes())
        // The image pipeline still works and never serves the font record.
        val image = content.chapters[0].elements
            .filterIsInstance<ContentElement.Image>().single()
        assertArrayEquals(MobiBuilder.fakePng(42), File(image.path).readBytes())
    }

    @Test
    fun `metadata comes from the kf8 header`() {
        val file = tempFolder.newFile("meta.azw3")
        MobiBuilder.buildKf8(file, spec())
        val metadata = MobiParser.parseMetadata(file)
        assertEquals("KF8 Book", metadata.title)
    }
}
