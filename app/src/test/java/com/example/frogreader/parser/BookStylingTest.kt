package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.Fb2Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Books express structure through CSS classes (LitRes/Calibre EPUBs) or FB2
 * semantics; these tests pin down how both turn into styled elements.
 */
class BookStylingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Mirrors the exact structure of LitRes FB2→EPUB conversions. */
    private fun buildLitresLikeEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            // A fake OTF: only the magic bytes matter for extraction.
            zip.putNextEntry(ZipEntry("OPS/fonts/Test-Regular.otf"))
            zip.write("OTTO".toByteArray() + ByteArray(16))
            zip.closeEntry()
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="id">
                  <metadata><dc:title>Styled</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>""",
            )
            entry(
                "OPS/style.css",
                """
                @font-face {
                    font-family: 'Test Serif';
                    font-style: normal;
                    font-weight: normal;
                    src: url(fonts/Test-Regular.otf);
                }
                .z { font-family: 'Test Serif', serif; margin-right: 8pt; line-height: 1.4; -webkit-hyphens: none; }
                .title1 { font-size: 1.8em; font-weight: bold; margin: 1em 0 0.5em 0; text-align: center; }
                .p { margin: 0 0 0.5em 0; text-align: inherit; text-indent: 0; }
                .p1 { margin: 0; text-align: justify; text-indent: 1.5em; }
                .epigraph { font-style: italic; margin: 1em 1em 2em 30%; text-align: left; }
                .text-author { margin: 0.2em 0 0 3em; text-indent: 0; }
                .subtitle { font-style: italic; font-weight: bold; margin: 0.5em 2em; text-align: center; text-indent: 0; }
                .empty-line { height: 1em; margin: 0; }
                .hidden-note { display: none; }
                """.trimIndent(),
            )
            entry(
                "OPS/ch1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title/><link rel="stylesheet" href="style.css" type="text/css"/></head>
                <body class="z">
                <span id="id2"><div class="title1">
                <p class="p">Глава 1. Дементоры – фантомы и кошмары</p>
                </div><div class="epigraph">
                <p class="p1">Мы перестали искать монстров у себя под кроватью,</p>
                <p class="p1">когда осознали, что они внутри нас.</p>
                <p class="text-author" id="id17">Чарльз Дарвин</p>
                </div><p class="p1">И вправду, сон рождает чудовищ. Первый обычный абзац.</p>
                <p class="subtitle">2</p>
                <div class="empty-line"></div>
                <p class="p1">Абзац после пустой строки.</p>
                <p class="hidden-note">Служебный скрытый текст.</p>
                <ol><li>первый пункт</li><li>второй пункт</li></ol>
                <ul><li>маркер</li></ul>
                </span>
                </body>
                </html>""",
            )
        }
    }

    @Test
    fun `litres-style epub - chapter title becomes a centered heading`() {
        val epub = tempFolder.newFile("styled.epub")
        buildLitresLikeEpub(epub)

        val elements = EpubParser.parseContent(epub, tempFolder.newFolder())
            .chapters.single().elements

        val heading = elements.filterIsInstance<ContentElement.Heading>()
            .single { it.text.startsWith("Глава 1") }
        assertEquals("Глава 1. Дементоры – фантомы и кошмары", heading.text)
        val block = heading.block
        assertNotNull("heading must carry the CSS style", block)
        assertEquals(BlockAlign.CENTER, block!!.align)
        assertEquals(true, block.bold)
        assertEquals(1.8f, block.fontScale, 0.01f)
    }

    @Test
    fun `litres-style epub - epigraph is italic and indented by a third`() {
        val epub = tempFolder.newFile("styled2.epub")
        buildLitresLikeEpub(epub)

        val elements = EpubParser.parseContent(epub, tempFolder.newFolder())
            .chapters.single().elements
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()

        val epigraph = paragraphs.single { it.text.text.startsWith("Мы перестали") }
        val block = epigraph.block
        assertNotNull(block)
        assertEquals(true, block!!.italic)
        assertEquals(0.30f, block.indentStartFrac, 0.01f)

        // The author's signature keeps the epigraph indent plus its own 3em.
        val author = paragraphs.single { it.text.text == "Чарльз Дарвин" }
        val authorBlock = author.block
        assertNotNull(authorBlock)
        assertEquals(true, authorBlock!!.italic)
        assertEquals(0.30f, authorBlock.indentStartFrac, 0.01f)
        assertTrue(authorBlock.indentStartEm >= 2.9f)
        assertEquals(false, authorBlock.firstLineIndent)

        // Body text: the full book style is recorded (align, font, spacing);
        // whether it wins over the user's settings is the renderer's call.
        val body = paragraphs.single { it.text.text.startsWith("И вправду") }
        val bodyBlock = body.block
        assertNotNull(bodyBlock)
        assertEquals(BlockAlign.JUSTIFY, bodyBlock!!.align)
        assertEquals(true, bodyBlock.firstLineIndent)
        assertEquals(1.5f, bodyBlock.firstLineIndentEm ?: 0f, 0.01f)
        assertEquals(1f, bodyBlock.fontScale, 0.01f)
        assertEquals("test serif", bodyBlock.fontFamily)
        assertEquals(1.4f, bodyBlock.lineHeightMult ?: 0f, 0.01f)
        assertEquals(false, bodyBlock.hyphens)

        // Subtitle: italic + bold + centered via CSS.
        val subtitle = paragraphs.single { it.text.text == "2" }
        assertEquals(BlockAlign.CENTER, subtitle.block?.align)
        assertEquals(true, subtitle.block?.italic)
        assertEquals(true, subtitle.block?.bold)
    }

    @Test
    fun `litres-style epub - embedded fonts are extracted`() {
        val epub = tempFolder.newFile("fonts.epub")
        buildLitresLikeEpub(epub)

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        val font = content.fonts.single()
        assertEquals("test serif", font.family)
        assertEquals(false, font.bold)
        assertEquals(false, font.italic)
        assertTrue(File(font.path).exists())
    }

    @Test
    fun `litres-style epub - spacers, hidden text and list numbering`() {
        val epub = tempFolder.newFile("styled3.epub")
        buildLitresLikeEpub(epub)

        val elements = EpubParser.parseContent(epub, tempFolder.newFolder())
            .chapters.single().elements

        // The empty-line div becomes a spacer.
        assertTrue(elements.any { it is ContentElement.Spacer })

        // display:none paragraphs must not leak into the book.
        assertTrue(
            elements.filterIsInstance<ContentElement.Paragraph>()
                .none { it.text.text.contains("Служебный") },
        )

        // Ordered lists are numbered, unordered keep bullets.
        val texts = elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text }
        assertTrue(texts.contains("1. первый пункт"))
        assertTrue(texts.contains("2. второй пункт"))
        assertTrue(texts.contains("• маркер"))
    }

    @Test
    fun `fb2 - epigraph and text-author carry classic styling`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Стили</book-title></title-info></description>
            <body>
              <section>
                <title><p>Глава 1</p></title>
                <epigraph>
                  <p>Мы перестали искать монстров у себя под кроватью,</p>
                  <text-author>Чарльз Дарвин</text-author>
                </epigraph>
                <p>Обычный текст.</p>
                <empty-line/>
                <subtitle>* * *</subtitle>
                <p>Текст после сцены.</p>
              </section>
            </body>
            </FictionBook>
        """.trimIndent()

        val elements = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters.single().elements

        val title = elements.filterIsInstance<ContentElement.Heading>().first()
        assertEquals(BlockAlign.CENTER, title.block?.align)

        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        val epigraph = paragraphs.single { it.text.text.startsWith("Мы перестали") }
        assertEquals(ParagraphStyle.QUOTE, epigraph.style)
        assertEquals(true, epigraph.block?.italic)
        assertTrue((epigraph.block?.indentStartFrac ?: 0f) > 0.2f)

        val author = paragraphs.single { it.text.text == "Чарльз Дарвин" }
        assertEquals(BlockAlign.END, author.block?.align)

        assertTrue(elements.any { it is ContentElement.Spacer })

        val subtitle = elements.filterIsInstance<ContentElement.Heading>()
            .single { it.text == "* * *" }
        assertEquals(BlockAlign.CENTER, subtitle.block?.align)
    }

    @Test
    fun `broken fb2 - html entities and bare ampersands are repaired`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Битая</book-title></title-info></description>
            <body>
              <section>
                <p>Тире&nbsp;&mdash; и много&hellip; ошибок & прочего.</p>
              </section>
            </body>
            </FictionBook>
        """.trimIndent()

        val chapters = Fb2Parser.parseContent({ xml.byteInputStream() }, tempFolder.newFolder())
            .chapters
        val text = chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single().text.text
        assertTrue("got: $text", text.contains("Тире — и много… ошибок & прочего."))
    }

    @Test
    fun `broken fb2 - file cut off mid-book keeps parsed chapters`() {
        val full = StringBuilder(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><book-title>Обрыв</book-title></title-info></description>
            <body>
            """.trimIndent(),
        )
        repeat(30) { index ->
            full.append("<section><title><p>Глава ${index + 1}</p></title>")
            repeat(10) { full.append("<p>Абзац $it главы ${index + 1}.</p>") }
            full.append("</section>")
        }
        full.append("</body></FictionBook>")
        // Cut the file at ~60%: mid-tag, no closing tags at all.
        val broken = full.substring(0, (full.length * 0.6).toInt())

        val chapters = Fb2Parser.parseContent({ broken.byteInputStream() }, tempFolder.newFolder())
            .chapters
        assertTrue("salvaged ${chapters.size} chapters", chapters.size >= 15)
        assertFalse(
            chapters.flatMap { it.elements }
                .filterIsInstance<ContentElement.Paragraph>()
                .isEmpty(),
        )
    }

    @Test
    fun `epub without container xml still opens via opf scan`() {
        val epub = tempFolder.newFile("noconTainer.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry(
                "content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
                  <metadata><dc:title>Headless</dc:title></metadata>
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>""",
            )
            entry(
                "ch1.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Текст без контейнера.</p></body></html>""",
            )
        }

        val chapters = EpubParser.parseContent(epub, tempFolder.newFolder()).chapters
        assertEquals(1, chapters.size)
        assertEquals(
            "Текст без контейнера.",
            (chapters[0].elements.single() as ContentElement.Paragraph).text.text,
        )
    }
}
