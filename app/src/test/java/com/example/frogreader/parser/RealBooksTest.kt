package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.Fb2Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Checks against the user's actual books (skipped when the files are absent).
 * These are the books the formatting work was verified on:
 * - Панчин, «Защита от тёмных искусств» — the chapter-title/epigraph bug.
 * - Павлюк, «Я бачу, вас цікавить пітьма» — same LitRes converter, Ukrainian.
 * - Кинг, «Тёмная Башня 7» — FB2→EPUB conversion + the original FB2.
 * - Оруэлл, «1984» — plain FB2.
 */
class RealBooksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Fixture locations: the project's untracked `.testbooks` first (always
     * readable), the user's Downloads as a fallback (may be blocked by macOS
     * permissions — tests then skip instead of failing).
     */
    private val bookDirs = listOf(
        File("/Users/frog/AndroidStudioProjects/FrogReader/.testbooks"),
        File("/Users/frog/Downloads"),
    )

    private fun book(name: String): File {
        for (dir in bookDirs) {
            val file = File(dir, name)
            if (runCatching { file.inputStream().use { it.read() } }.isSuccess) return file
        }
        return File(bookDirs.first(), name)
    }

    private fun assumeReadable(file: File) {
        assumeTrue(
            "fixture ${file.name} not readable — skipping",
            runCatching { file.inputStream().use { it.read() } }.isSuccess,
        )
    }

    private fun charCount(chapters: List<com.example.frogreader.data.model.Chapter>): Long =
        chapters.sumOf { ch ->
            ch.elements.sumOf { el ->
                when (el) {
                    is ContentElement.Paragraph -> el.text.text.length.toLong()
                    is ContentElement.Heading -> el.text.length.toLong()
                    else -> 0L
                }
            }
        }

    @Test
    fun `panchin - chapter 1 title and epigraph are styled`() {
        val file = book("Panchin_Corpus_458_Zashchita_ot_temnyh_iskusstv_Putevoditel_po_miru.epub")
        assumeReadable(file)

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertTrue("chapters: ${content.chapters.size}", content.chapters.size >= 10)
        assertTrue("chars: ${charCount(content.chapters)}", charCount(content.chapters) > 400_000)
        assertTrue("notes: ${content.notes.size}", content.notes.size > 500)

        val chapter = content.chapters.first { ch ->
            ch.elements.any { it is ContentElement.Heading && it.text.contains("Дементоры") }
        }
        val heading = chapter.elements.filterIsInstance<ContentElement.Heading>()
            .first { it.text.contains("Дементоры") }
        assertEquals("Глава 1. Дементоры – фантомы и кошмары", heading.text)
        val headingBlock = heading.block
        assertNotNull("heading carries CSS block style", headingBlock)
        assertEquals(BlockAlign.CENTER, headingBlock?.align)
        assertEquals(true, headingBlock?.bold)
        assertEquals(1.8f, headingBlock?.fontScale ?: 0f, 0.05f)

        val headingIndex = chapter.elements.indexOf(heading)
        val epigraph = chapter.elements.drop(headingIndex).filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Мы перестали искать монстров") }
        assertEquals(true, epigraph.block?.italic)
        assertEquals(0.30f, epigraph.block?.indentStartFrac ?: 0f, 0.02f)

        val author = chapter.elements.drop(headingIndex).filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text == "Чарльз Дарвин" }
        assertTrue((author.block?.indentStartEm ?: 0f) >= 2.9f)
    }

    @Test
    fun `pavlyuk - ukrainian litres epub parses with styled titles`() {
        val file = book("Pavlyuk_Ya-bachu-vas-cikavit-pitma.801912.fb2.epub")
        assumeReadable(file)

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertTrue("chapters: ${content.chapters.size}", content.chapters.size >= 5)
        assertTrue("chars: ${charCount(content.chapters)}", charCount(content.chapters) > 200_000)
        assertTrue(
            "styled headings exist",
            content.chapters.any { ch ->
                ch.elements.any { it is ContentElement.Heading && it.block != null }
            },
        )
    }

    @Test
    fun `dark tower epub - left-aligned chapter titles honored`() {
        val file = book("King_-Tyomnaya-Bashnya-_7_Temnaya-Bashnya.328261.fb2.epub")
        assumeReadable(file)

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertTrue("chars: ${charCount(content.chapters)}", charCount(content.chapters) > 1_000_000)

        val heading = content.chapters.asSequence()
            .flatMap { it.elements.asSequence() }
            .filterIsInstance<ContentElement.Heading>()
            .first { it.text.startsWith("Глава 4. Дан-тете") }
        // .title2 { text-align: left; font-size: 1.5em } in this conversion.
        assertEquals(BlockAlign.START, heading.block?.align)
        assertEquals(1.5f, heading.block?.fontScale ?: 0f, 0.05f)
    }

    @Test
    fun `dark tower fb2 - parses with part-chapter structure`() {
        val file = book("King_-Tyomnaya-Bashnya-_7_Temnaya-Bashnya.KriKEA.328261.fb2")
        assumeReadable(file)

        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())
        assertTrue("chapters: ${content.chapters.size}", content.chapters.size >= 20)
        assertTrue("chars: ${charCount(content.chapters)}", charCount(content.chapters) > 1_000_000)
    }

    @Test
    fun `torture fb2 - metadata survives broken entities, all blocks parse`() {
        val file = book("FrogReader_Test.fb2")
        assumeReadable(file)

        // The file deliberately contains &nbsp;/&mdash;/bare & — metadata
        // (incl. the cover binary at the very end) must still come through.
        val metadata = Fb2Parser.parseMetadata { file.inputStream().buffered() }
        assertEquals("Пыточная камера вёрстки (FB2)", metadata.title)
        assertEquals("Тест Тестович Тестов", metadata.author)
        assertNotNull("cover survives the repair pass", metadata.coverBytes)

        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())
        // Гл.1, Гл.2, Часть I (титул), Гл.3, Гл.4, Гл.5 — the part is its own
        // small chapter now, its chapters nest one level deeper.
        assertEquals(6, content.chapters.size)
        assertEquals(listOf(0, 0, 0, 1, 1, 0), content.chapters.map { it.depth })
        assertEquals(2, content.notes.size)
        assertTrue(content.notes["#n1"]!!.text.contains("два абзаца"))

        val allElements = content.chapters.flatMap { it.elements }
        assertTrue(allElements.any { it is ContentElement.Spacer })
        assertTrue(allElements.any { it is ContentElement.Image })
        assertTrue(
            allElements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.block?.align == BlockAlign.END }, // text-author
        )
        // The broken-entities chapter came through the repair pass.
        assertTrue(
            allElements.filterIsInstance<ContentElement.Paragraph>()
                .any { it.text.text.contains("Тор & Локи") },
        )
    }

    @Test
    fun `torture epub - css, fonts, hidden text, merge and lists`() {
        val file = book("FrogReader_Test.epub")
        assumeReadable(file)

        val content = EpubParser.parseContent(file, tempFolder.newFolder())

        val font = content.fonts.single()
        assertEquals("book font", font.family)
        assertTrue(File(font.path).exists())

        assertEquals(2, content.notes.size)

        val paragraphs = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
        // display:none and linear="no" text must not leak.
        assertTrue(paragraphs.none { it.text.text.contains("ОШИБКА") })
        // @media print rules must not apply on screen.
        assertTrue(paragraphs.any { it.text.text.contains("ДОЛЖЕН быть виден") })
        // Ordered list numbering.
        assertTrue(paragraphs.any { it.text.text == "1. пункт номер один" })
        assertTrue(paragraphs.any { it.text.text == "3. пункт номер три" })
        // The out-of-TOC file merged into its chapter.
        assertTrue(paragraphs.any { it.text.text.contains("проверка склейки") })

        val headings = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Heading>()
        assertEquals(
            BlockAlign.CENTER,
            headings.first { it.text.startsWith("Глава 1") }.block?.align,
        )
        assertEquals(
            BlockAlign.START,
            headings.first { it.text.startsWith("Глава 3") }.block?.align,
        )
        // The book asks for monospace + line-height 1.9 (publisher mode).
        val body = paragraphs.first { it.text.text.contains("Инлайн-теги") }
        assertEquals("monospace", body.block?.fontFamily)
        assertEquals(1.9f, body.block?.lineHeightMult ?: 0f, 0.01f)
    }

    @Test
    fun `tolkien uk fb2 - three-level section tree becomes navigable chapters`() {
        val file = book("Tolkien_LOTR1_uk.fb2")
        assumeReadable(file)

        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())
        val chapters = content.chapters
        assertTrue("chapters: ${chapters.size}", chapters.size >= 30)

        val prologue = chapters.first { it.title?.startsWith("Пролог") == true }
        assertEquals(0, prologue.depth)

        val kniga = chapters.first { it.title?.contains("Книга перша") == true }
        assertEquals(1, kniga.depth)

        // Every «Розділ» of both books is its own chapter at depth 2.
        val rozdil1 = chapters.first { it.title?.contains("Довгоочікувана гостина") == true }
        assertEquals(2, rozdil1.depth)
        val rozdily = chapters.filter { it.title?.startsWith("Розділ") == true }
        assertTrue("розділи: ${rozdily.size}", rozdily.size >= 22)
        assertTrue(rozdily.all { it.depth == 2 })

        assertTrue("chars: ${charCount(chapters)}", charCount(chapters) > 700_000)
    }

    @Test
    fun `tolkien uk fb2 - picture initials stay in the text, plates stay blocks`() {
        val file = book("Tolkien_LOTR1_uk.fb2")
        assumeReadable(file)

        val imagesDir = tempFolder.newFolder()
        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, imagesDir)
        val paragraphs = content.chapters.flatMap { chapter ->
            chapter.elements.filterIsInstance<ContentElement.Paragraph>()
        }

        // «Коли пан Більбо…» opens with the "К" drawn as a picture.
        val opening = paragraphs.first { it.text.text.contains("оли пан Більбо Торбин") }
        val mark = opening.text
            .getStringAnnotations(INLINE_IMAGE_TAG, 0, opening.text.length)
            .single()
        assertEquals(0, mark.start)
        assertTrue("initial file missing: ${mark.item}", File(mark.item).exists())
        assertTrue(opening.text.text.startsWith(INLINE_IMAGE_CHAR + "оли пан"))

        // All 31 pictures the book sets inside <p>: 22 chapter initials,
        // 6 runes closing Gandalf's letters, and 3 standalone plates that
        // become block images below.
        val marks = paragraphs.flatMap {
            it.text.getStringAnnotations(INLINE_IMAGE_TAG, 0, it.text.length)
        }
        assertEquals(28, marks.size)
        assertEquals(22, marks.count { it.start == 0 })
        assertTrue(marks.all { File(it.item).exists() })

        // The three plates wrapped in <p> are full-width block images, and
        // no paragraph was reduced to a bare placeholder.
        val blockImages = content.chapters.flatMap { chapter ->
            chapter.elements.filterIsInstance<ContentElement.Image>()
        }
        assertTrue("block images: ${blockImages.size}", blockImages.size >= 3)
        assertTrue(blockImages.all { File(it.path).exists() })
        assertTrue(paragraphs.none { it.text.text.isBlank() })
    }

    @Test
    fun `engine torture fb2 - table grid, language, keep-with-next fixture`() {
        val file = book("FrogReader_Engine.fb2")
        assumeReadable(file)

        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())
        assertEquals("ru", content.language)

        val table = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(4, table.rows.size)
        assertTrue(table.rows[0].isHeader)
        assertEquals(2, table.rows[1].cells[2].rowSpan)
        assertEquals(3, table.rows[3].cells[0].colSpan)
        assertEquals(BlockAlign.CENTER, table.rows[3].cells[0].align)
    }

    @Test
    fun `engine torture epub - caps, floats, lists, ruby, obfuscated font`() {
        val file = book("FrogReader_Engine.epub")
        assumeReadable(file)

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        assertEquals("ru", content.language)

        // The IDPF-obfuscated font decrypts back into a real TTF.
        val font = content.fonts.single()
        assertEquals("tortureserif", font.family)
        val fontBytes = File(font.path).readBytes()
        assertTrue(
            "decrypted font magic",
            fontBytes.size > 4 && (
                fontBytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                    fontBytes.copyOfRange(0, 4).contentEquals("OTTO".toByteArray()) ||
                    fontBytes.copyOfRange(0, 4).contentEquals("true".toByteArray())
                ),
        )

        val paragraphs = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
        // Drop cap captured from ::first-letter.
        val opener = paragraphs.first { it.text.text.startsWith("Мысль о буквице") }
        assertEquals(true, opener.block?.firstLetter?.isDropCap)
        assertEquals(3f, opener.block?.firstLetter?.scale ?: 0f, 0.01f)
        // Float image attached to its paragraph, not duplicated as a block.
        val floated = paragraphs.first { it.text.text.startsWith("Эта картинка") }
        assertEquals(0.32f, floated.block?.floatImage?.widthFrac ?: 0f, 0.01f)
        assertTrue(floated.block?.floatImage?.left == true)
        // Attribute and sibling selectors.
        assertEquals(
            true,
            paragraphs.first { it.text.text.startsWith("Этот абзац отобран") }.block?.italic,
        )
        assertEquals(
            true,
            paragraphs.first { it.text.text.startsWith("Этот абзац стоит сразу") }.block?.bold,
        )
        assertTrue(
            paragraphs.first { it.text.text.startsWith("А этот — второй") }.block?.bold != true,
        )
        // Lists: roman with start, letters, no-marker.
        assertTrue(paragraphs.any { it.text.text.startsWith("III. Римская") })
        assertTrue(paragraphs.any { it.text.text.startsWith("a. буквенный") })
        assertTrue(paragraphs.any { it.text.text == "Пункт без маркера вообще" })
        // Ruby: reading present, fallback parens gone.
        val ruby = paragraphs.first { it.text.text.contains("漢字") }
        assertTrue(ruby.text.text.contains("кандзи"))
        assertTrue(!ruby.text.text.contains("("))
        // page-break-before survives onto the chapter's first block.
        assertTrue(
            content.chapters.flatMap { it.elements }.any {
                (it as? ContentElement.Heading)?.block?.pageBreakBefore == true ||
                    (it as? ContentElement.Paragraph)?.block?.pageBreakBefore == true
            },
        )
        // The long table keeps its header flag for repeats.
        val table = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Table>().single()
        assertEquals(31, table.rows.size)
        assertTrue(table.rows[0].isHeader)

        // CSS ::before/::after generated content.
        assertTrue(
            "div.stars::before produces a separator paragraph",
            paragraphs.any { it.text.text.trim() == "* * *" },
        )
        val quoted = paragraphs.first { it.text.text.contains("подставляют CSS-псевдоэлементы") }
        assertTrue("::before « prepended", quoted.text.text.startsWith("«"))
        assertTrue("::after » appended", quoted.text.text.endsWith("»"))

        // Inline vector <svg> extracted to a real .svg file.
        val svg = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Image>()
            .first { it.path.endsWith(".svg") }
        val svgText = File(svg.path).readText()
        assertTrue(svgText.contains("<svg"))
        assertTrue(svgText.contains("circle") || svgText.contains("polygon"))
    }

    @Test
    fun `engine torture mobi - ncx toc, footnote, image, table, language`() {
        val file = book("FrogReader_Engine.mobi")
        assumeReadable(file)

        val metadata = com.example.frogreader.data.parser.mobi.MobiParser.parseMetadata(file)
        assertEquals("Engine Torture MOBI", metadata.title)
        assertEquals("Движок Тест", metadata.author)
        assertNotNull(metadata.coverBytes)

        val content = com.example.frogreader.data.parser.mobi.MobiParser
            .parseContent(file, tempFolder.newFolder())
        assertEquals("ru", content.language)

        val titled = content.chapters.filter { it.title != null }
        assertEquals(
            listOf("Глава первая. Проверка MOBI", "Глава вторая, вложенная", "Примечания"),
            titled.map { it.title },
        )
        assertEquals(listOf(0, 1, 0), titled.map { it.depth })

        val note = content.notes.entries.single()
        assertTrue(note.value.text.startsWith("Текст примечания"))

        val first = titled[0].elements
        assertTrue(first.any { it is ContentElement.Image })
        val table = first.filterIsInstance<ContentElement.Table>().single()
        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0].isHeader)
    }

    @Test
    fun `orwell 1984 fb2 - parses fully`() {
        val file = book("Oruell_1984.508275.fb2")
        assumeReadable(file)

        val content = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())
        assertTrue("chapters: ${content.chapters.size}", content.chapters.isNotEmpty())
        assertTrue("chars: ${charCount(content.chapters)}", charCount(content.chapters) > 300_000)
    }
}
