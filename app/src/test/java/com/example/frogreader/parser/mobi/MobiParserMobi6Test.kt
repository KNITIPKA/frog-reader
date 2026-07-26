package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.parser.mobi.Exth
import com.example.frogreader.data.parser.mobi.MobiParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobiParserMobi6Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Builds the standard fixture: two pagebreak-separated chapters, a
     * footnote reached via filepos, and a recindex image. The filepos
     * placeholder is patched with the real byte offset of the note tag.
     */
    private fun fixtureHtml(): String {
        val placeholder = "0000000000"
        var html = """
            <html><head><guide></guide></head><body>
            <h1>Глава первая</h1>
            <p>Первый абзац главы с примечанием<a filepos=$placeholder>[1]</a> в тексте.</p>
            <p><img recindex="00001"></p>
            <p>Ещё один абзац для объёма, чтобы главе было что показать.</p>
            <mbp:pagebreak/>
            <h1>Глава вторая</h1>
            <p>Текст второй главы, ничем не примечательный.</p>
            <mbp:pagebreak/>
            <p id="note1">Текст примечания, на который указывает ссылка из первой главы.</p>
            </body></html>
        """.trimIndent()
        val notePos = html.toByteArray(Charsets.UTF_8).size -
            html.substringAfter("<p id=\"note1\"").let {
                ("<p id=\"note1\"$it").toByteArray(Charsets.UTF_8).size
            }
        html = html.replace(placeholder, notePos.toString().padStart(placeholder.length, '0'))
        return html
    }

    private fun parse(compress: Boolean): Pair<com.example.frogreader.data.model.BookContent, File> {
        val file = tempFolder.newFile("book.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = fixtureHtml(),
            compress = compress,
            exth = listOf(
                Exth.AUTHOR to "Тест Автор".toByteArray(),
                Exth.UPDATED_TITLE to "Книга-испытание".toByteArray(),
                Exth.LANGUAGE to "ru".toByteArray(),
                Exth.COVER_OFFSET to byteArrayOf(0, 0, 0, 0),
            ),
            images = listOf(MobiBuilder.fakePng(7)),
        )
        val imagesDir = tempFolder.newFolder()
        return MobiParser.parseContent(file, imagesDir) to file
    }

    @Test
    fun `chapters split on pagebreaks with heading titles`() {
        val (content, _) = parse(compress = true)
        assertEquals(3, content.chapters.size)
        assertEquals("Глава первая", content.chapters[0].title)
        assertEquals("Глава вторая", content.chapters[1].title)
        assertTrue(
            content.chapters[2].elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text.startsWith("Текст примечания") },
        )
    }

    @Test
    fun `filepos link becomes a footnote with extracted text`() {
        val (content, _) = parse(compress = true)
        val note = content.notes.entries.single()
        assertTrue(note.key.startsWith("#filepos"))
        assertTrue(note.value.text.startsWith("Текст примечания"))

        val paragraph = content.chapters[0].elements
            .filterIsInstance<ContentElement.Paragraph>().first()
        val annotation = paragraph.text
            .getStringAnnotations(FOOTNOTE_TAG, 0, paragraph.text.length)
            .single()
        assertEquals(note.key, annotation.item)
        assertEquals("[1]", paragraph.text.text.substring(annotation.start, annotation.end))
    }

    @Test
    fun `recindex image is extracted to disk`() {
        val (content, _) = parse(compress = true)
        val image = content.chapters[0].elements
            .filterIsInstance<ContentElement.Image>().single()
        val bytes = File(image.path).readBytes()
        assertArrayEquals(MobiBuilder.fakePng(7), bytes)
    }

    @Test
    fun `language comes from exth`() {
        val (content, _) = parse(compress = true)
        assertEquals("ru", content.language)
    }

    @Test
    fun `uncompressed and compressed variants agree`() {
        val (compressed, _) = parse(compress = true)
        val fileU = tempFolder.newFile("plain.mobi")
        MobiBuilder.buildMobi6(fileU, fixtureHtml(), compress = false)
        val uncompressed = MobiParser.parseContent(fileU, tempFolder.newFolder())

        fun texts(content: com.example.frogreader.data.model.BookContent) =
            content.chapters.flatMap { ch ->
                ch.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
            }
        assertEquals(texts(uncompressed), texts(compressed))
    }

    @Test
    fun `trailing entries do not leak into the text`() {
        val file = tempFolder.newFile("trailed.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = fixtureHtml(),
            compress = true,
            trailingPayloads = listOf(byteArrayOf(9, 9, 9), byteArrayOf(1)),
        )
        val content = MobiParser.parseContent(file, tempFolder.newFolder())
        assertEquals(3, content.chapters.size)
        assertTrue(
            content.chapters[1].elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text.startsWith("Текст второй главы") },
        )
    }

    @Test
    fun `cp1252 encoded book decodes correctly`() {
        val file = tempFolder.newFile("cp1252.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = "<html><body><h1>Café</h1><p>Déjà vu — naïve résumé.</p></body></html>",
            compress = true,
            encoding = 1252,
            fullName = "Cafe",
        )
        val content = MobiParser.parseContent(file, tempFolder.newFolder())
        val paragraph = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("Déjà vu — naïve résumé.", paragraph.text.text)
    }

    @Test
    fun `metadata title author cover from exth`() {
        val (_, file) = parse(compress = true)
        val metadata = MobiParser.parseMetadata(file)
        assertEquals("Книга-испытание", metadata.title)
        assertEquals("Тест Автор", metadata.author)
        assertNotNull(metadata.coverBytes)
        assertArrayEquals(MobiBuilder.fakePng(7), metadata.coverBytes)
    }

    @Test
    fun `extended exth metadata flows into book metadata`() {
        val file = tempFolder.newFile("richmeta.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = "<html><body><p>Текст книги.</p></body></html>",
            exth = listOf(
                Exth.UPDATED_TITLE to "Богатая книга".toByteArray(),
                Exth.AUTHOR to "Первый Автор".toByteArray(),
                Exth.AUTHOR to "Второй Автор".toByteArray(),
                Exth.PUBLISHER to "Издатель АСТ".toByteArray(),
                Exth.DESCRIPTION to "<p>Аннотация <b>книги</b>.</p>".toByteArray(),
                Exth.ISBN to "9785170304111".toByteArray(),
                Exth.SUBJECT to "Фантастика; Приключения".toByteArray(),
                Exth.SUBJECT to "Классика".toByteArray(),
                Exth.PUBLISH_DATE to "2005-11-02".toByteArray(),
                Exth.LANGUAGE to "ru".toByteArray(),
            ),
        )
        val metadata = MobiParser.parseMetadata(file)

        assertEquals("Богатая книга", metadata.title)
        assertEquals(listOf("Первый Автор", "Второй Автор"), metadata.authors)
        assertEquals("Первый Автор", metadata.author)
        assertEquals("Издатель АСТ", metadata.publisher)
        assertEquals("Аннотация книги.", metadata.description)
        assertEquals("9785170304111", metadata.isbn)
        assertEquals(listOf("Фантастика", "Приключения", "Классика"), metadata.genres)
        assertEquals("2005", metadata.year)
        assertEquals("ru", metadata.language)
    }

    @Test
    fun `multi-record book with long text survives record boundaries`() {
        val paragraphs = (1..80).joinToString("") {
            "<p>Абзац номер $it, в котором достаточно текста, чтобы книга заняла несколько записей по четыре килобайта каждая.</p>"
        }
        val file = tempFolder.newFile("long.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = "<html><body><h1>Одна глава</h1>$paragraphs</body></html>",
            compress = true,
        )
        val content = MobiParser.parseContent(file, tempFolder.newFolder())
        val texts = content.chapters.flatMap { ch ->
            ch.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        }
        assertEquals(80, texts.size)
        assertTrue(texts[40].startsWith("Абзац номер 41"))
        assertEquals("ru", content.language) // Cyrillic heuristic fallback
    }
}
