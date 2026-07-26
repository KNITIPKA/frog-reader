package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.parser.Fb2Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Decorative initials drawn as pictures: `<p><image/>оли пан…` must keep the
 * letter in the text flow, while `<p><image/></p>` is a plain illustration.
 */
class InlineImageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bookXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description><title-info><book-title>Буквиці</book-title></title-info></description>
        <body>
          <section>
            <title><p>Розділ 1</p></title>
            <p><image l:href="#cap.jpg"/>оли пан Більбо Торбин оголосив про гостину.</p>
            <p><image l:href="#plate.jpg"/></p>
            <p>Звичайний абзац без картинок.</p>
            <p><image l:href="#missing.jpg"/>Текст із загубленою буквицею.</p>
          </section>
        </body>
        <binary id="cap.jpg" content-type="image/jpeg">AAEC</binary>
        <binary id="plate.jpg" content-type="image/jpeg">AwQF</binary>
        </FictionBook>
    """.trimIndent()

    @Test
    fun `image inside a paragraph stays in the text as an inline image`() {
        val imagesDir = tempFolder.newFolder("images")
        val chapters = Fb2Parser.parseContent({ bookXml.byteInputStream() }, imagesDir).chapters
        val elements = chapters.single().elements

        val dropCap = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.contains("Більбо") }
        // The placeholder opens the paragraph, the text follows immediately.
        assertEquals(
            INLINE_IMAGE_CHAR + "оли пан Більбо Торбин оголосив про гостину.",
            dropCap.text.text,
        )
        val mark = dropCap.text
            .getStringAnnotations(INLINE_IMAGE_TAG, 0, dropCap.text.length)
            .single()
        assertEquals(0, mark.start)
        assertEquals(1, mark.end)
        // Resolved to a real extracted file, like any block image.
        val file = File(mark.item)
        assertTrue("expected an extracted file, got ${mark.item}", file.exists())
        assertEquals(listOf<Byte>(0, 1, 2), file.readBytes().toList())
    }

    @Test
    fun `paragraph holding only an image becomes a block illustration`() {
        val imagesDir = tempFolder.newFolder("images")
        val elements = Fb2Parser.parseContent({ bookXml.byteInputStream() }, imagesDir)
            .chapters.single().elements

        val images = elements.filterIsInstance<ContentElement.Image>()
        assertEquals(1, images.size)
        assertEquals(listOf<Byte>(3, 4, 5), File(images.single().path).readBytes().toList())
    }

    @Test
    fun `missing binary drops the placeholder instead of leaving a stray char`() {
        val imagesDir = tempFolder.newFolder("images")
        val elements = Fb2Parser.parseContent({ bookXml.byteInputStream() }, imagesDir)
            .chapters.single().elements

        val orphan = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.contains("загубленою") }
        assertEquals("Текст із загубленою буквицею.", orphan.text.text)
        assertTrue(
            orphan.text.getStringAnnotations(INLINE_IMAGE_TAG, 0, orphan.text.length).isEmpty(),
        )
    }

    @Test
    fun `footnote annotations survive next to a dropped placeholder`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Т</book-title></title-info></description>
            <body><section>
              <p><image l:href="#gone.jpg"/>Текст зі <a l:href="#n1" type="note">[1]</a> виноскою.</p>
            </section></body>
            <body name="notes"><section id="n1"><p>Текст виноски.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        val paragraph = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().first()

        assertEquals("Текст зі [1] виноскою.", paragraph.text.text)
        val note = paragraph.text
            .getStringAnnotations("footnote", 0, paragraph.text.length)
            .single()
        // The link must still cover exactly "[1]" after the shift.
        assertEquals("[1]", paragraph.text.text.substring(note.start, note.end))
        assertTrue(content.notes.containsKey("#n1"))
    }
}
