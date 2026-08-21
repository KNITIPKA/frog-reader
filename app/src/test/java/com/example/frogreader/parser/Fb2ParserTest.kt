package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle
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

    @Test
    fun `the first body remains the reading flow even when it has a name`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Named body</book-title></title-info></description>
            <body name="main-text">
              <section><title><p>Main chapter</p></title><p>Main text.</p></section>
            </body>
            <body name="notes">
              <section id="n1"><p>Supplemental note.</p></section>
            </body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())

        assertEquals(listOf("Main chapter"), content.chapters.map { it.title })
        assertEquals(
            "Main text.",
            content.chapters.single().elements
                .filterIsInstance<ContentElement.Paragraph>()
                .single().text.text,
        )
        assertEquals("Supplemental note.", content.notes["#n1"]?.text)
    }

    @Test
    fun `preserves rich titles and every poem text position including inline svg`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 12 8"/>"""
        val encodedSvg = java.util.Base64.getEncoder().encodeToString(svg.toByteArray())
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Poetry</book-title></title-info></description>
            <body><section>
              <title><p>A <strong>rich</strong> <emphasis>chapter</em></p></title>
              <subtitle><image l:href="#ornament"/></subtitle>
              <poem>
                <title><p>Poem <emphasis>title</emphasis></p></title>
                <subtitle>Poem subtitle</subtitle>
                <stanza>
                  <title><p>Stanza title</p></title>
                  <subtitle>Stanza subtitle</subtitle>
                  <v>Line <strong>one</strong></v>
                  <v><image l:href="#ornament"/></v>
                </stanza>
                <text-author>Poet Name</text-author>
                <date value="1923-01-01">1923</date>
              </poem>
            </section></body>
            <binary id="ornament" content-type="image/svg+xml">$encodedSvg</binary>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        val elements = content.chapters.single().elements

        val chapterTitle = elements.filterIsInstance<ContentElement.Heading>()
            .first { it.text == "A rich chapter" }
        assertEquals(
            "rich",
            chapterTitle.text.substring(
                chapterTitle.styledText.spanStyles.single {
                    it.item.fontWeight == FontWeight.Bold
                }.start,
                chapterTitle.styledText.spanStyles.single {
                    it.item.fontWeight == FontWeight.Bold
                }.end,
            ),
        )
        assertEquals(
            "chapter",
            chapterTitle.text.substring(
                chapterTitle.styledText.spanStyles.single {
                    it.item.fontStyle == FontStyle.Italic
                }.start,
                chapterTitle.styledText.spanStyles.single {
                    it.item.fontStyle == FontStyle.Italic
                }.end,
            ),
        )

        assertTrue(elements.filterIsInstance<ContentElement.Heading>().any {
            it.text == "Poem title"
        })
        assertTrue(elements.filterIsInstance<ContentElement.Heading>().any {
            it.text == "Poem subtitle"
        })
        assertTrue(elements.filterIsInstance<ContentElement.Heading>().any {
            it.text == "Stanza title"
        })
        assertTrue(elements.filterIsInstance<ContentElement.Heading>().any {
            it.text == "Stanza subtitle"
        })

        val ornamentHeading = elements.filterIsInstance<ContentElement.Heading>()
            .single { it.styledText.getStringAnnotations(INLINE_IMAGE_TAG, 0, it.text.length).isNotEmpty() }
        val ornamentPath = ornamentHeading.styledText
            .getStringAnnotations(INLINE_IMAGE_TAG, 0, ornamentHeading.text.length)
            .single().item
        assertTrue(ornamentPath.endsWith(".svg"))
        assertEquals(svg, java.io.File(ornamentPath).readText())

        val stanza = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.style == ParagraphStyle.POEM && it.text.text.startsWith("Line one") }
        assertTrue(stanza.text.text.contains('\n'))
        assertEquals(1, stanza.text.getStringAnnotations(INLINE_IMAGE_TAG, 0, stanza.text.length).size)
        val bold = stanza.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("one", stanza.text.text.substring(bold.start, bold.end))

        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any {
            it.text.text == "Poet Name"
        })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any {
            it.text.text == "1923"
        })
    }

    @Test
    fun `resolves inline images inside table cells using binary mime type`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4"/>"""
        val encodedSvg = java.util.Base64.getEncoder().encodeToString(svg.toByteArray())
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Table image</book-title></title-info></description>
            <body><section><table>
              <tr><th>Mark</th><th>Value</th></tr>
              <tr><td><image l:href="#mark"/></td><td><strong>Four</strong></td></tr>
            </table></section></body>
            <binary id="mark" content-type="image/svg+xml">$encodedSvg</binary>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        val table = content.chapters.single().elements.single() as ContentElement.Table
        val imageCell = table.rows[1].cells[0].text
        val image = imageCell.getStringAnnotations(INLINE_IMAGE_TAG, 0, imageCell.length).single()

        assertTrue(image.item.endsWith(".svg"))
        assertEquals(svg, java.io.File(image.item).readText())
        val bold = table.rows[1].cells[1].text.spanStyles
            .single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Four", table.rows[1].cells[1].text.text.substring(bold.start, bold.end))
    }

    @Test
    fun `ordinary internal links navigate while note links keep popup semantics`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Links</book-title></title-info></description>
            <body><section id="chapter">
              <p>See <a l:href="#target">the target</a>.</p>
              <p id="target">Target paragraph.</p>
              <p>Read <a l:href="#n1" type="note">the note</a>.</p>
            </section></body>
            <body name="notes"><section id="n1"><p>Note text.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        val paragraphs = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>()
        val crossReference = paragraphs[0].text
            .getStringAnnotations(LINK_TAG, 0, paragraphs[0].text.length)
            .single()

        assertEquals("#target", crossReference.item)
        assertEquals(0 to 1, content.linkTargets["#target"])
        assertEquals(0 to 0, content.linkTargets["#chapter"])
        assertEquals("Note text.", content.notes["#n1"]?.text)
        assertEquals(
            1,
            paragraphs[2].text.getStringAnnotations(
                com.example.frogreader.data.model.FOOTNOTE_TAG,
                0,
                paragraphs[2].text.length,
            ).size,
        )
    }

    @Test
    fun `supplemental notes retain poem table date and image alternatives`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Rich note</book-title></title-info></description>
            <body><section><p>Text<a l:href="#n1" type="note">[1]</a>.</p></section></body>
            <body name="notes"><section id="n1">
              <title><p>1</p></title>
              <poem>
                <title><p>Quoted poem</p></title>
                <stanza><v>First verse</v><v><emphasis>Second verse</emphasis></v></stanza>
                <text-author>Poet</text-author><date>1901</date>
              </poem>
              <table><tr><td>A</td><td>B</td></tr></table>
              <image l:href="#missing" alt="Diagram description"/>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val note = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .notes.getValue("#n1")

        assertTrue(note.text.contains("Quoted poem"))
        assertTrue(note.text.contains("First verse\nSecond verse"))
        assertTrue(note.text.contains("Poet"))
        assertTrue(note.text.contains("1901"))
        assertTrue(note.text.contains("A    B"))
        assertTrue(note.text.contains("Diagram description"))
        assertTrue(note.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(!note.text.startsWith("1\n"))
    }

    @Test
    fun `block image alternatives survive extraction and replace missing binaries`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>Alternatives</book-title></title-info></description>
            <body><section>
              <image l:href="#present" alt="A present diagram"/>
              <image l:href="#missing" alt="Readable missing diagram"/>
              <image l:href="#missing-title" title="Title fallback"/>
              <image l:href="#silent-missing"/>
            </section></body>
            <binary id="present" content-type="image/png">AAEC</binary>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements
        val image = elements.filterIsInstance<ContentElement.Image>().single()
        val fallbacks = elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }

        assertEquals("A present diagram", image.altText)
        assertArrayEquals(byteArrayOf(0, 1, 2), java.io.File(image.path).readBytes())
        assertEquals(listOf("Readable missing diagram", "Title fallback"), fallbacks)
    }

    @Test
    fun `section annotation is prose while a cite keeps quotation semantics`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Blocks</book-title></title-info></description>
            <body><section>
              <annotation><p>Section summary.</p><cite><p>Quoted inside.</p></cite></annotation>
              <p>Body.</p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val paragraphs = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>()

        assertEquals(ParagraphStyle.NORMAL, paragraphs.first { it.text.text == "Section summary." }.style)
        assertEquals(ParagraphStyle.QUOTE, paragraphs.first { it.text.text == "Quoted inside." }.style)
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
