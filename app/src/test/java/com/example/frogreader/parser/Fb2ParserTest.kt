package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.Fb2Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class Fb2ParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleFb2 = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description>
          <title-info>
            <author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
            <book-title>Война и мир</book-title>
            <coverpage><image l:href="#cover.jpg"/></coverpage>
          </title-info>
        </description>
        <body>
          <title><p>Том первый</p></title>
          <section>
            <title><p>Глава 1</p></title>
            <p>Первый <emphasis>абзац</emphasis> текста.</p>
            <empty-line/>
            <p>Второй абзац.</p>
            <image l:href="#pic1.png"/>
          </section>
          <section>
            <title><p>Глава 2</p></title>
            <p>Текст второй главы.</p>
          </section>
        </body>
        <body name="notes">
          <section id="n1"><p>Сноска, которой не место в тексте.</p></section>
        </body>
        <binary id="cover.jpg" content-type="image/jpeg">AAEC</binary>
        <binary id="pic1.png" content-type="image/png">AwQF</binary>
        </FictionBook>
    """.trimIndent()

    @Test
    fun `parses metadata with cover`() {
        val metadata = Fb2Parser.parseMetadata { sampleFb2.byteInputStream() }

        assertEquals("Война и мир", metadata.title)
        assertEquals("Лев Толстой", metadata.author)
        assertArrayEquals(byteArrayOf(0, 1, 2), metadata.coverBytes)
    }

    @Test
    fun `parses extended metadata from title-info and publish-info`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description>
              <title-info>
                <genre>sf_fantasy</genre>
                <genre>prose_classic</genre>
                <author><first-name>Стивен</first-name><last-name>Кинг</last-name></author>
                <author><nickname>Соавтор</nickname></author>
                <book-title>Тёмная башня</book-title>
                <annotation><p>Первый абзац аннотации.</p><p>Второй абзац.</p></annotation>
                <date value="2004">2004</date>
                <lang>ru</lang>
                <src-lang>en</src-lang>
                <translator><first-name>Виктор</first-name><last-name>Вебер</last-name></translator>
                <sequence name="Тёмная башня" number="7"/>
              </title-info>
              <src-title-info>
                <genre>foreign_sf</genre>
                <author><first-name>Stephen</first-name><last-name>King</last-name></author>
                <book-title>The Dark Tower</book-title>
                <annotation><p>Original blurb that must not win.</p></annotation>
              </src-title-info>
              <document-info>
                <author><nickname>scan-group</nickname></author>
              </document-info>
              <publish-info>
                <publisher>АСТ</publisher>
                <year>2005</year>
                <isbn>5-17-030411-1</isbn>
                <sequence name="Издательская серия" number="3"/>
              </publish-info>
            </description>
            <body><section><p>Текст.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val metadata = Fb2Parser.parseMetadata { xml.byteInputStream() }

        assertEquals("Тёмная башня", metadata.title)
        assertEquals(listOf("Стивен Кинг", "Соавтор"), metadata.authors)
        assertEquals("Стивен Кинг", metadata.author)
        assertEquals(listOf("sf_fantasy", "prose_classic"), metadata.genres)
        assertEquals("Тёмная башня", metadata.series)
        assertEquals(7f, metadata.seriesNumber!!, 0.0001f)
        assertEquals(listOf("Виктор Вебер"), metadata.translators)
        assertEquals("АСТ", metadata.publisher)
        assertEquals("2005", metadata.year)
        assertEquals("5-17-030411-1", metadata.isbn)
        assertEquals("ru", metadata.language)
        assertEquals("Первый абзац аннотации.\nВторой абзац.", metadata.description)
    }

    @Test
    fun `named style elements keep their text`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Стили</book-title></title-info></description>
            <body><section>
              <style name="epigraph-alt">Блочный стиль не должен пропадать.</style>
              <p>Обычный абзац со <style name="term">встроенным стилем</style> внутри.</p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val chapters = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters
        val texts = chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text.text }
        assertEquals(
            listOf(
                "Блочный стиль не должен пропадать.",
                "Обычный абзац со встроенным стилем внутри.",
            ),
            texts,
        )
    }

    @Test
    fun `publish-info year is used when title-info has no date`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description>
              <title-info><book-title>Книга</book-title><date>написано в 1984 году</date></title-info>
            </description>
            <body><section><p>Текст.</p></section></body>
            </FictionBook>
        """.trimIndent()
        val metadata = Fb2Parser.parseMetadata { xml.byteInputStream() }
        assertEquals("1984", metadata.year)
    }

    @Test
    fun `parses chapters with formatting and images`() {
        val imagesDir = tempFolder.newFolder("images")
        val chapters = Fb2Parser.parseContent({ sampleFb2.byteInputStream() }, imagesDir).chapters

        assertEquals(2, chapters.size)
        assertEquals("Глава 1", chapters[0].title)
        assertEquals("Глава 2", chapters[1].title)

        val first = chapters[0].elements
        // Body title carried into the first chapter, then the section heading.
        val bodyTitle = first[0] as ContentElement.Heading
        assertEquals("Том первый", bodyTitle.text)
        assertEquals(1, bodyTitle.level)
        val sectionTitle = first[1] as ContentElement.Heading
        assertEquals("Глава 1", sectionTitle.text)
        assertEquals(2, sectionTitle.level)

        val paragraph = first[2] as ContentElement.Paragraph
        assertEquals("Первый абзац текста.", paragraph.text.text)
        val italics = paragraph.text.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italics.size)
        assertEquals("абзац", paragraph.text.text.substring(italics[0].start, italics[0].end))

        val image = first.filterIsInstance<ContentElement.Image>().single()
        val imageFile = java.io.File(image.path)
        assertTrue(imageFile.exists())
        assertArrayEquals(byteArrayOf(3, 4, 5), imageFile.readBytes())

        // Footnote body must not leak into the text.
        assertTrue(chapters.none { ch ->
            ch.elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text.contains("Сноска") }
        })
    }

    @Test
    fun `honors windows-1251 encoding`() {
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info>
              <book-title>Кобзар</book-title>
            </title-info></description>
            <body><section><p>Реве та стогне Дніпр широкий.</p></section></body>
            </FictionBook>
        """.trimIndent()
        val bytes = xml.toByteArray(charset("windows-1251"))

        val metadata = Fb2Parser.parseMetadata { ByteArrayInputStream(bytes) }
        assertEquals("Кобзар", metadata.title)

        val chapters = Fb2Parser.parseContent({ ByteArrayInputStream(bytes) }, tempFolder.newFolder())
            .chapters
        val paragraph = chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("Реве та стогне Дніпр широкий.", paragraph.text.text)
    }

    @Test
    fun `reads the book language from title-info, not src-lang`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description>
              <title-info>
                <book-title>Переклад</book-title>
                <lang>ua</lang>
                <src-lang>en</src-lang>
              </title-info>
            </description>
            <body><section><p>Text.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        // "ua" is a frequent FB2 mistake for Ukrainian's real tag "uk".
        assertEquals("uk", content.language)
    }

    @Test
    fun `guesses the language from text when metadata has none`() {
        val content = Fb2Parser.parseContent({ sampleFb2.byteInputStream() }, tempFolder.newFolder())
        assertEquals("ru", content.language)
    }

    @Test
    fun `parses footnotes from the notes body`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Notes</book-title></title-info></description>
            <body>
              <section>
                <p>Обычный текст<a l:href="#n1" type="note">[1]</a> с примечанием.</p>
              </section>
            </body>
            <body name="notes">
              <section id="n1"><title><p>1</p></title><p>Это текст примечания.</p></section>
            </body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())

        assertEquals("Это текст примечания.", content.notes["#n1"]?.text)

        val paragraph = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single()
        val annotation = paragraph.text
            .getStringAnnotations(com.example.frogreader.data.model.FOOTNOTE_TAG, 0, paragraph.text.text.length)
            .single()
        assertEquals("#n1", annotation.item)
        assertEquals("[1]", paragraph.text.text.substring(annotation.start, annotation.end))
    }
}

class Fb2NestedSectionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val nestedFb2 = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description><title-info><book-title>Темная Башня</book-title></title-info></description>
        <body>
          <section>
            <title><p>Часть 1. МАЛЕНЬКИЙ КРАСНЫЙ КОРОЛЬ. ДАН-ТЕТЕ</p></title>
            <epigraph><p>Эпиграф части.</p></epigraph>
            <section>
              <title><p>Глава 1. Каллагэн и вампиры</p></title>
              <p>Текст первой главы.</p>
              <section><p>Сцена без названия.</p></section>
            </section>
            <section>
              <title><p>Глава 2. Лифт</p></title>
              <p>Текст второй главы.</p>
            </section>
          </section>
          <section>
            <title><p>Часть 2. СИНИЕ НЕБЕСА. ДЕВАР-ТОИ</p></title>
            <section>
              <title><p>Глава 3</p><p>Сверкающий наблюдатель</p></title>
              <p>Текст третьей главы.</p>
            </section>
          </section>
          <section>
            <title><p>Эпилог</p></title>
            <p>Плоская секция без подглав.</p>
          </section>
        </body>
        </FictionBook>
    """.trimIndent()

    @Test
    fun `titled nested sections become a chapter tree with depths`() {
        val imagesDir = tempFolder.newFolder("img")
        val chapters = Fb2Parser.parseContent({ nestedFb2.byteInputStream() }, imagesDir).chapters

        assertEquals(
            listOf(
                "Часть 1. МАЛЕНЬКИЙ КРАСНЫЙ КОРОЛЬ. ДАН-ТЕТЕ",
                "Глава 1. Каллагэн и вампиры",
                "Глава 2. Лифт",
                "Часть 2. СИНИЕ НЕБЕСА. ДЕВАР-ТОИ",
                "Глава 3\nСверкающий наблюдатель",
                "Эпилог",
            ),
            chapters.map { it.title },
        )
        assertEquals(listOf(0, 1, 1, 0, 1, 0), chapters.map { it.depth })

        // The part's own chapter holds its title page and epigraph.
        val partTitle = chapters[0].elements[0] as ContentElement.Heading
        assertEquals("Часть 1. МАЛЕНЬКИЙ КРАСНЫЙ КОРОЛЬ. ДАН-ТЕТЕ", partTitle.text)
        assertEquals(2, partTitle.level)
        assertTrue(
            chapters[0].elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text == "Эпиграф части." },
        )
        // Nested chapter headings sit one level deeper.
        assertTrue(
            chapters[1].elements.filterIsInstance<ContentElement.Heading>()
                .any { it.text == "Глава 1. Каллагэн и вампиры" && it.level == 3 },
        )
        // Untitled scene sections still flatten into their chapter.
        assertTrue(
            chapters[1].elements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text == "Сцена без названия." },
        )
    }
}
