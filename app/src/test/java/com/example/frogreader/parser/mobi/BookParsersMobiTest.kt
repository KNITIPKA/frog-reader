package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.parser.BookParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookParsersMobiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `detects and stores a mobi book`() {
        val source = tempFolder.newFile("Тёмная башня.mobi")
        MobiBuilder.buildMobi6(
            target = source,
            html = "<html><body><p>Проверка детекции формата по магии PDB.</p></body></html>",
        )
        val booksDir = tempFolder.newFolder("books")

        val (format, stored) = BookParsers.detectAndStore(source, booksDir, "id-mobi")
        assertEquals(BookFormat.MOBI, format)
        assertEquals("id-mobi.mobi", stored.name)
        assertTrue(stored.exists())

        val metadata = BookParsers.parseMetadata(stored, format)
        assertEquals("Engine Mobi", metadata.title)

        val content = BookParsers.parseContent(stored, format, tempFolder.newFolder())
        assertTrue(
            content.chapters.single().elements.isNotEmpty(),
        )
    }
}
