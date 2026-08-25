package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.Fb2Stylesheet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

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
    fun `fb2 stylesheet keeps inherited block inline and table colors`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              body { color: #123456; background-color: linen; }
              p { background-color: rgb(240 248 255); }
              .accent { color: rebeccapurple; background-color: #ff08; }
              table { color: white; background-color: navy; }
              tr { background-color: teal; }
              td { color: hsl(60 100% 50%); }
            ]]></stylesheet>
            <description><title-info><book-title>Color FB2</book-title></title-info></description>
            <body><section>
              <p>Plain <style name="accent">accent</style>.</p>
              <table><tr><td>A</td><td>B</td></tr></table>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent(
            open = { xml.byteInputStream() },
            imagesDir = tempFolder.newFolder(),
        )
        val paragraph = content.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(0xff123456.toInt(), paragraph.block?.foregroundColorArgb)
        assertEquals(0xfff0f8ff.toInt(), paragraph.block?.backgroundColorArgb)
        val accentOffset = paragraph.text.text.indexOf("accent")
        val accent = paragraph.text.spanStyles.last {
            it.start <= accentOffset && it.end >= accentOffset + 6 &&
                it.item.color != Color.Unspecified
        }.item
        assertEquals(0xff663399.toInt(), accent.color.toArgb())
        assertEquals(0x88ffff00.toInt(), accent.background.toArgb())

        val table = content.chapters.single().elements
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(0xffffffff.toInt(), table.block?.foregroundColorArgb)
        assertEquals(0xff000080.toInt(), table.block?.backgroundColorArgb)
        table.rows.single().cells.forEach { cell ->
            assertEquals(0xffffff00.toInt(), cell.block?.foregroundColorArgb)
            assertEquals(0xff008080.toInt(), cell.block?.backgroundColorArgb)
        }
    }

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
    fun `fb2 css applies safe block subset and named inline styles`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              body { font-family: Georgia; line-height: 1.6; }
              p { font-size: 110%; text-align: justify; text-indent: 1.25em;
                  margin: 1em 2em .5em 10%; page-break-before: always; }
              .p { font-weight: bold; }
              .subtitle { font-style: italic; text-align: left; margin-bottom: 1.2em; }
              .marked { font-style: italic; text-decoration: underline; }
              style[name="marked"] { font-weight: bold; }
              @media print { p { font-size: 260%; } }
            ]]></stylesheet>
            <description><title-info><book-title>CSS</book-title></title-info></description>
            <body><section>
              <subtitle style="font-size: 90%; font-weight: normal">Styled subtitle</subtitle>
              <p style="font-weight: normal; font-size: 150%; text-align: center; margin-left: 2em">
                Base <style name="marked">marked</style> end.
              </p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val paragraph = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        val block = requireNotNull(paragraph.block)

        assertEquals(BlockAlign.CENTER, block.align)
        assertEquals(false, block.bold)
        assertEquals(1.5f, requireNotNull(block.fontScale), 0.0001f)
        assertEquals("serif", block.fontFamily)
        assertEquals(1.6f, block.lineHeightMult!!, 0.0001f)
        assertEquals(2f, block.indentLeftEm, 0.0001f)
        assertEquals(2f, block.indentRightEm, 0.0001f)
        assertEquals(1f, block.spaceBeforeEm, 0.0001f)
        assertEquals(0.5f, block.spaceAfterEm, 0.0001f)
        assertEquals(1.25f, block.firstLineIndentEm!!, 0.0001f)
        assertEquals(true, block.firstLineIndent)
        assertTrue(block.pageBreakBefore)

        val base = paragraph.text.text.indexOf("Base")
        val baseStyles = paragraph.text.spanStyles
            .filter { it.start <= base && it.end > base }
            .map { it.item }
        assertTrue(baseStyles.none { !it.fontSize.value.isNaN() })
        assertTrue(baseStyles.none { it.fontFamily != null })
        assertTrue(baseStyles.none { it.fontWeight != null || it.fontStyle != null })

        val marked = paragraph.text.text.indexOf("marked")
        val markedStyles = paragraph.text.spanStyles
            .filter { it.start <= marked && it.end > marked }
            .map { it.item }
        assertTrue(markedStyles.any { it.fontStyle == FontStyle.Italic })
        assertTrue(markedStyles.any { it.fontWeight == FontWeight.Bold })
        assertTrue(markedStyles.any { it.textDecoration == TextDecoration.Underline })
        // The nested @media rule is intentionally outside the compatibility
        // profile and must not leak its print-only font size into the book.
        assertTrue(markedStyles.none { it.fontSize.value == 2.6f })

        val subtitle = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder(),
        ).chapters.single().elements.filterIsInstance<ContentElement.Heading>().single()
        val subtitleBlock = requireNotNull(subtitle.block)
        assertEquals("Styled subtitle", subtitle.text)
        assertEquals(BlockAlign.LEFT, subtitleBlock.align)
        assertEquals(false, subtitleBlock.bold)
        assertEquals(0.9f, requireNotNull(subtitleBlock.fontScale), 0.0001f)
        assertEquals(1.2f, subtitleBlock.spaceAfterEm, 0.0001f)
        assertEquals(true, subtitleBlock.italic)
    }

    @Test
    fun `leading semicolon at rules do not consume the first fb2 css rule`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              @charset "UTF-8";
              @namespace fb "urn:fb2;{string-boundary}";
              /* A comment containing fake delimiters: p { broken: yes; } */
              p { font-style: italic; font-family: "/* string, not comment */";
                  margin-left: 1em; }
              @media print { p { font-weight: bold; margin-left: 6em; } }
              subtitle { text-align: center; }
            ]]></stylesheet>
            <description><title-info><book-title>At-rules</book-title></title-info></description>
            <body><section>
              <subtitle>Still scanned after a block at-rule.</subtitle>
              <p>The first qualified rule survives.</p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        val paragraphBlock = requireNotNull(paragraph.block)
        assertEquals(true, paragraphBlock.italic)
        assertEquals(null, paragraphBlock.bold)
        assertEquals("/* string, not comment */", paragraphBlock.fontFamily)
        assertEquals(1f, paragraphBlock.indentLeftEm, 0.0001f)

        val subtitle = elements.filterIsInstance<ContentElement.Heading>().single()
        assertEquals(BlockAlign.CENTER, requireNotNull(subtitle.block).align)
    }

    @Test
    fun `fb2 margin shorthand and longhands share one css cascade`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              .p { margin: 3em; }
              p { margin-left: 1em; margin-right: 4em !important; }
              .p { margin-inline-end: 5em; }
            ]]></stylesheet>
            <description><title-info><book-title>Margin cascade</book-title></title-info></description>
            <body><section>
              <subtitle style="margin-left: 1em; margin: 2em">Longhand then shorthand.</subtitle>
              <subtitle style="margin: 2em; margin-left: 1em">Shorthand then longhand.</subtitle>
              <subtitle style="margin: 2em !important; margin-left: 1em">Important shorthand.</subtitle>
              <subtitle style="margin: 2em; margin-left: 1em !important">Important longhand.</subtitle>
              <p>Stylesheet specificity and importance.</p>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements
        val headings = elements.filterIsInstance<ContentElement.Heading>()
        assertEquals(4, headings.size)

        requireNotNull(headings[0].block).let { block ->
            assertEquals(2f, block.indentLeftEm, 0.0001f)
            assertEquals(2f, block.indentRightEm, 0.0001f)
            assertEquals(2f, block.spaceBeforeEm, 0.0001f)
            assertEquals(2f, block.spaceAfterEm, 0.0001f)
        }
        requireNotNull(headings[1].block).let { block ->
            assertEquals(1f, block.indentLeftEm, 0.0001f)
            assertEquals(2f, block.indentRightEm, 0.0001f)
            assertEquals(2f, block.spaceBeforeEm, 0.0001f)
            assertEquals(2f, block.spaceAfterEm, 0.0001f)
        }
        assertEquals(2f, requireNotNull(headings[2].block).indentLeftEm, 0.0001f)
        assertEquals(1f, requireNotNull(headings[3].block).indentLeftEm, 0.0001f)

        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        requireNotNull(paragraph.block).let { block ->
            // Higher-specificity shorthand beats a later type longhand.
            assertEquals(3f, block.indentLeftEm, 0.0001f)
            // An important physical longhand beats a later logical alias.
            assertEquals(4f, block.indentRightEm, 0.0001f)
            assertEquals(3f, block.spaceBeforeEm, 0.0001f)
            assertEquals(3f, block.spaceAfterEm, 0.0001f)
        }
    }

    @Test
    fun `fb2 font shorthand resets inheritance and shares the longhand cascade`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              body { font-style: italic; font-weight: bold; font-size: 24px;
                     line-height: 2; font-family: monospace; }
              .p { font: normal 12px/1.2 serif; }
              p { font-style: italic; font-weight: bold; font-size: 20px;
                  line-height: 1.5; font-family: cursive; }

              subtitle { font: normal 12px/1.1 serif; }
              .subtitle { font-style: italic; font-weight: bold; font-size: 20px;
                          line-height: 1.5; font-family: monospace; }
            ]]></stylesheet>
            <description><title-info><book-title>Font shorthand</book-title></title-info></description>
            <body><section>
              <p>Higher-specificity shorthand.</p>
              <subtitle>Higher-specificity longhands.</subtitle>
              <subtitle style="font-style: italic; font: normal 12px/1.2 serif">Later shorthand.</subtitle>
              <subtitle style="font: normal 12px/1.2 serif; font-style: italic">Later longhand.</subtitle>
              <subtitle style="font: italic bold 20px/1.5 serif !important; font-style: normal">Important shorthand.</subtitle>
              <subtitle style="font: normal 12px/1.2 serif; font-style: italic !important">Important longhand.</subtitle>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements
        val paragraph = requireNotNull(
            elements.filterIsInstance<ContentElement.Paragraph>().single().block,
        )
        assertEquals(false, paragraph.italic)
        assertEquals(false, paragraph.bold)
        assertEquals(0.75f, requireNotNull(paragraph.fontScale), 0.0001f)
        assertEquals(1.2f, paragraph.lineHeightMult!!, 0.0001f)
        assertEquals("serif", paragraph.fontFamily)

        val headings = elements.filterIsInstance<ContentElement.Heading>()
        requireNotNull(headings[0].block).let { block ->
            assertEquals(true, block.italic)
            assertEquals(true, block.bold)
            assertEquals(1.25f, requireNotNull(block.fontScale), 0.0001f)
            assertEquals(1.5f, block.lineHeightMult!!, 0.0001f)
            assertEquals("monospace", block.fontFamily)
        }
        assertEquals(false, requireNotNull(headings[1].block).italic)
        assertEquals(true, requireNotNull(headings[2].block).italic)
        requireNotNull(headings[3].block).let { block ->
            assertEquals(true, block.italic)
            assertEquals(true, block.bold)
        }
        assertEquals(true, requireNotNull(headings[4].block).italic)
    }

    @Test
    fun `relative inherited font size and line height use the computed body style`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              body { font-size: 150%; line-height: 150%; }
              p { font-size: 120%; }
            ]]></stylesheet>
            <description><title-info><book-title>Computed inheritance</book-title></title-info></description>
            <body><section><p>Body 150%, paragraph 120%.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val paragraph = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        val block = requireNotNull(paragraph.block)

        // Relative font-size multiplies the computed parent: 1.5 * 1.2.
        assertEquals(1.8f, requireNotNull(block.fontScale), 0.0001f)
        // Percentage line-height computes on body (2.25 root em) and is then
        // inherited as that absolute value: 2.25 / the paragraph's 1.8.
        assertEquals(1.25f, block.lineHeightMult!!, 0.0001f)
        // Block typography must not be duplicated over the entire text run.
        assertTrue(paragraph.text.spanStyles.none { !it.item.fontSize.value.isNaN() })
    }

    @Test
    fun `table and cell style attributes preserve layout and text styling`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css"><![CDATA[
              td { font-size: 80%; }
              .accent { text-decoration: underline; }
            ]]></stylesheet>
            <description><title-info><book-title>Table CSS</book-title></title-info></description>
            <body><section>
              <table style="margin-top: 2em; page-break-before: always; font-family: monospace; font-size: 150%; text-align: right">
                <tr align="center">
                  <th style="font-weight: normal">Head</th>
                  <td style="text-align: left; font-style: italic; text-decoration: line-through">
                    Cell <style name="accent">accent</style>
                  </td>
                </tr>
              </table>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val table = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Table>().single()
        val block = requireNotNull(table.block)
        assertEquals(2f, block.spaceBeforeEm, 0.0001f)
        assertTrue(block.pageBreakBefore)
        assertEquals("monospace", block.fontFamily)
        assertEquals(1.5f, requireNotNull(block.fontScale), 0.0001f)

        val header = table.rows.single().cells[0]
        assertEquals(BlockAlign.RIGHT, header.align)
        assertTrue(header.text.spanStyles.any { it.item.fontWeight == FontWeight.Normal })
        // Inherited table typography lives in Table.block and is not repeated
        // as a full-cell span (which would bypass the publisher-style toggle).
        assertTrue(header.text.spanStyles.none { it.item.fontFamily != null })

        val cell = table.rows.single().cells[1]
        assertEquals(BlockAlign.LEFT, cell.align)
        assertTrue(cell.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(cell.text.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        val cellScale = cell.text.spanStyles.single {
            !it.item.fontSize.value.isNaN()
        }.item.fontSize.value
        assertEquals(0.8f, cellScale, 0.0001f)
        // Effective table → cell scale is 150% * 80%, never 150% * 120%.
        assertEquals(1.2f, requireNotNull(block.fontScale) * cellScale, 0.0001f)
        val accent = cell.text.text.indexOf("accent")
        assertTrue(
            cell.text.spanStyles.any {
                it.start <= accent && it.end > accent &&
                    it.item.textDecoration == TextDecoration.Underline
            },
        )
    }

    @Test
    fun `single column table fallback applies table and cell scale exactly once`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="text/css">td { font-size: 80%; }</stylesheet>
            <description><title-info><book-title>Layout table CSS</book-title></title-info></description>
            <body><section>
              <table style="font-size: 150%; font-family: monospace">
                <tr><td>One-column layout text.</td></tr>
              </table>
            </section></body>
            </FictionBook>
        """.trimIndent()

        val paragraph = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        val block = requireNotNull(paragraph.block)
        val cellScale = paragraph.text.spanStyles.single {
            !it.item.fontSize.value.isNaN()
        }.item.fontSize.value

        assertEquals(1.5f, requireNotNull(block.fontScale), 0.0001f)
        assertEquals("monospace", block.fontFamily)
        assertEquals(0.8f, cellScale, 0.0001f)
        assertEquals(1.2f, requireNotNull(block.fontScale) * cellScale, 0.0001f)
        assertTrue(paragraph.text.spanStyles.none { it.item.fontFamily != null })
    }

    @Test
    fun `malformed and non css stylesheets do not break tolerant fb2 parsing`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <stylesheet type="application/xslt">p { font-weight: bold; }</stylesheet>
            <stylesheet type="text/css">p { unknown: value; broken</stylesheet>
            <description><title-info><book-title>Tolerant CSS</book-title></title-info></description>
            <body><section><p style="font-style: italic; position: fixed">Readable.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val paragraph = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("Readable.", paragraph.text.text)
        assertEquals(true, paragraph.block?.italic)
        assertEquals(null, paragraph.block?.bold)
    }

    @Test
    fun `xml lang inherits through body sections and is overridden by inline runs`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Languages</book-title></title-info></description>
            <body xml:lang="fr">
              <section>
                <p>Bonjour <strong xml:lang="de-DE">Wort</strong>.</p>
                <section xml:lang="it">
                  <subtitle>Sottotitolo</subtitle>
                  <p>Italiano <style name="foreign" xml:lang="UA">слово</style>.</p>
                  <cite><text-author xml:lang="en">Author</text-author></cite>
                  <poem>
                    <stanza><v xml:lang="pl">Pierwszy wers</v><v>Drugi wers</v></stanza>
                    <stanza><v xml:lang="es">Uno</v><v xml:lang="es">Dos</v></stanza>
                  </poem>
                </section>
              </section>
            </body>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.flatMap { it.elements }
        val french = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Bonjour") }
        assertEquals("fr", french.block?.language)
        assertEquals(LocaleList("fr"), localeAt(french.text, french.text.text.indexOf("Bonjour")))
        assertEquals(LocaleList("de-de"), localeAt(french.text, french.text.text.indexOf("Wort")))

        val subtitle = elements.filterIsInstance<ContentElement.Heading>()
            .single { it.text == "Sottotitolo" }
        assertEquals("it", subtitle.block?.language)

        val italian = elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Italiano") }
        assertEquals("it", italian.block?.language)
        assertEquals(LocaleList("uk"), localeAt(italian.text, italian.text.text.indexOf("слово")))

        val author = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "Author" }
        assertEquals("en", author.block?.language)

        val stanza = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text.startsWith("Pierwszy wers") }
        // One verse overrides the section while the next inherits it; the
        // aggregate block is mixed, so each line carries its own locale span.
        assertEquals(LocaleList("pl"), localeAt(stanza.text, 0))
        assertEquals(
            LocaleList("it"),
            localeAt(stanza.text, stanza.text.text.indexOf("Drugi")),
        )
        val spanishStanza = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text.startsWith("Uno") }
        assertEquals("es", spanishStanza.block?.language)
    }

    private fun localeAt(text: androidx.compose.ui.text.AnnotatedString, index: Int): LocaleList? =
        text.spanStyles.lastOrNull {
            it.start <= index && it.end > index && it.item.localeList != null
        }
            ?.item?.localeList

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

        val note = content.notes.getValue("#n1")
        assertTrue(note.elements.first() is ContentElement.Heading)
        assertEquals("Это текст примечания.", note.elements
            .filterIsInstance<ContentElement.Paragraph>()
            .single().text.text)

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
        assertTrue(note.elements.any { it is ContentElement.Table })
        assertTrue(note.text.contains("A  B"))
        assertTrue(note.text.contains("Diagram description"))
        assertTrue(note.elements.any { element ->
            element is ContentElement.Paragraph &&
                element.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic }
        })
        assertTrue(note.elements.first() is ContentElement.Heading)
    }

    @Test
    fun `rich FB2 note keeps long blocks images and separates nested note`() {
        val longText = "Long authored note text ".repeat(45)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>Complete note</book-title></title-info></description>
              <body><section><p>Main<a l:href="#n1" type="note">1</a>.</p></section></body>
              <body name="notes"><section id="n1">
                <title><p>Full note heading</p></title>
                <p style="text-align: center; font-size: 120%">$longText</p><p>Second block.</p><p>Third block.</p>
                <p>Fourth block.</p><p>Fifth block.</p>
                <cite><p>Quoted block.</p></cite>
                <poem><stanza><v>Verse one</v><v>Verse two</v></stanza></poem>
                <table><tr><th>Key</th><th>Value</th></tr><tr><td>A</td><td>B</td></tr></table>
                <image l:href="#diagram" alt="Diagram"/>
                <p>Inline <image l:href="#diagram" alt="Inline diagram"/> tail.</p>
                <p>See <a l:href="#n2" type="note">nested note</a>.</p>
                <section id="n2"><p>Nested note body.</p></section>
                <p>Parent tail.</p>
              </section></body>
              <binary id="diagram" content-type="image/png">AQIDBA==</binary>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
        val note = content.notes.getValue("#n1")

        assertTrue(note.text.length > 700)
        assertTrue(note.elements.first() is ContentElement.Heading)
        assertTrue(note.elements.count { it is ContentElement.Paragraph } >= 9)
        assertTrue(note.elements.any { it is ContentElement.Table })
        val styled = note.elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Long authored") }
        assertEquals(BlockAlign.CENTER, styled.block?.align)
        assertEquals(1.2f, styled.block?.fontScale ?: 0f, 0.001f)
        val image = note.elements.filterIsInstance<ContentElement.Image>().single()
        assertTrue(File(image.path).isFile)
        assertEquals("Diagram", image.altText)
        val inline = note.elements.filterIsInstance<ContentElement.Paragraph>()
            .flatMap { it.text.getStringAnnotations(INLINE_IMAGE_TAG, 0, it.text.length) }
            .single()
        assertTrue(File(inline.item).isFile)
        assertTrue(note.text.contains("Parent tail."))
        assertTrue(!note.text.contains("Nested note body."))
        assertEquals("Nested note body.", content.notes.getValue("#n2").text)
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

    @Test
    fun `fb2 stylesheet caps declarations and selector groups independently`() {
        val css = buildString {
            append(".p {")
            repeat(256) { append("unknown-$it: value;") }
            append("font-weight: bold; }")
            repeat(128) { append(".unused$it,") }
            append(".p { text-decoration: underline; }")
            append(".p { font-style: italic; }")
        }

        val computed = Fb2Stylesheet.parse(listOf(css)).computed("p")
        assertEquals(null, computed.bold)
        assertEquals(null, computed.decoration)
        assertEquals(true, computed.italic)
    }

    @Test
    fun `fb2 stylesheet skips deeply nested block at rules without recursion`() {
        val css = buildString {
            repeat(2_000) { append("@media screen {") }
            append(".p { font-weight: bold; }")
            repeat(2_000) { append('}') }
            append(".p { font-style: italic; }")
        }

        val computed = Fb2Stylesheet.parse(listOf(css)).computed("p")
        assertEquals(null, computed.bold)
        assertEquals(true, computed.italic)
    }

    @Test
    fun `fb2 stylesheet rule cap prevents unbounded selector indexes`() {
        val css = buildString {
            repeat(8_192) { append(".unused$it { font-weight: bold; }") }
            append(".p { font-style: italic; }")
        }

        val computed = Fb2Stylesheet.parse(listOf(css)).computed("p")
        assertEquals(null, computed.bold)
        assertEquals(null, computed.italic)
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

    @Test
    fun `deep section headings preserve distinct levels four five and six`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Deep sections</book-title></title-info></description>
            <body>
              <section><title><p>Level 2</p></title>
                <section><title><p>Level 3</p></title>
                  <section><title><p>Level 4</p></title>
                    <section><title><p>Level 5</p></title>
                      <section><title><p>Level 6</p></title>
                        <section><title><p>Deeper still</p></title><p>Text.</p></section>
                      </section>
                    </section>
                  </section>
                </section>
              </section>
            </body>
            </FictionBook>
        """.trimIndent()

        val headings = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Heading>()

        assertEquals(
            listOf(2, 3, 4, 5, 6, 6),
            headings.map { it.level },
        )
        assertEquals(
            listOf("Level 2", "Level 3", "Level 4", "Level 5", "Level 6", "Deeper still"),
            headings.map { it.text },
        )
    }
}
