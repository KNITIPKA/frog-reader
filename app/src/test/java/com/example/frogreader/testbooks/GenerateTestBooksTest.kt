package com.example.frogreader.testbooks

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.mobi.Kf8Assembler
import com.example.frogreader.data.parser.mobi.MobiSection
import com.example.frogreader.data.parser.mobi.PdbFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Builds the four comparison books and reads them back through the real
 * parsers.
 *
 * Ordinarily it works in a temporary folder and asserts — so `./gradlew test`
 * keeps proving that all four files still parse and still carry the same
 * numbered checks. Run it with `-PgenerateTestBooks=true` and it also writes
 * the books into `.testbooks/` for copying onto a phone.
 */
class GenerateTestBooksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val doc = TestBookContent.doc

    @Test
    fun `all four formats carry the same checks and parse`() {
        val built = tempFolder.newFolder("books")
        val files = buildAll(built)

        for ((format, file) in files) {
            assertTrue("$format file is empty", file.length() > 0)

            val bookFormat = bookFormatOf(format)
            val metadata = BookParsers.parseMetadata(file, bookFormat)
            assertEquals("$format title", doc.titleSuffix(format), metadata.title)
            assertEquals("$format author", doc.authors.first(), metadata.author)
            assertEquals("$format authors", doc.authors, metadata.authors)
            assertNotNull("$format cover", metadata.coverBytes)
            assertEquals("$format language", doc.language, metadata.language)
            assertEquals("$format genres", doc.genres, metadata.genres)
            assertEquals("$format publisher", doc.publisher, metadata.publisher)
            assertEquals("$format year", doc.year, metadata.year)
            assertEquals("$format ISBN", doc.isbn, metadata.isbn)
            for (paragraph in doc.annotation) {
                assertTrue(
                    "$format description lost «$paragraph»",
                    paragraph in metadata.description.orEmpty(),
                )
            }
            if (format == Fmt.FB2 || format == Fmt.EPUB) {
                assertEquals("$format translators", doc.translators, metadata.translators)
                assertEquals("$format series", doc.series, metadata.series)
                assertEquals("$format series number", doc.seriesIndex.toFloat(), metadata.seriesNumber)
            } else {
                assertTrue("$format invented a translator role", metadata.translators.isEmpty())
                assertEquals("$format invented a portable series", null, metadata.series)
            }

            val imagesDir = tempFolder.newFolder("images-$format")
            val content = BookParsers.parseContent(file, bookFormat, imagesDir)
            assertTrue("$format has no chapters", content.chapters.isNotEmpty())
            assertTrue("$format has no notes", content.notes.isNotEmpty())

            // The whole point of the exercise: same numbering everywhere.
            val text = allText(content)
            for (number in doc.testNumbers()) {
                assertTrue("$format is missing «Тест $number.»", "Тест $number." in text)
            }

            // Text that the parsers have a real chance of eating.
            for (fragile in SURVIVES_EVERYWHERE) {
                assertTrue("$format ate «$fragile»", fragile in text)
            }
        }
    }

    @Test
    fun `check numbers are contiguous and unique`() {
        val numbers = doc.testNumbers()
        assertEquals("numbers repeat", numbers.size, numbers.toSet().size)
        assertEquals("numbering does not start at 1", 1, numbers.first())
        assertEquals("numbering has holes", numbers.size, numbers.last())
        assertEquals("numbers are out of order", numbers.sorted(), numbers)
    }

    @Test
    fun `format specific resources and shared navigation remain honest`() {
        val files = buildAll(tempFolder.newFolder("books"))
        val parsed = files.mapValues { (format, file) ->
            BookParsers.parseContent(
                file,
                bookFormatOf(format),
                tempFolder.newFolder("diff-$format"),
            )
        }

        // All four writers use different native addresses, but an ordinary
        // cross-reference must remain navigation rather than a popup note.
        for ((format, content) in parsed) {
            assertTrue("$format lost normal navigation", content.linkTargets.isNotEmpty())
            val expectedDepths = mapOf(
                "Часть II. Вложенность" to 0,
                "Глава II.1 Двухстрочное название" to 1,
                "Сцена II.1.1" to 2,
            )
            for ((title, depth) in expectedDepths) {
                val chapter = content.chapters.firstOrNull {
                    it.title?.replace('\n', ' ') == title
                }
                assertNotNull("$format TOC lost «$title»", chapter)
                assertEquals("$format TOC depth for «$title»", depth, chapter!!.depth)
            }
        }

        // Embedded font resources travel only in EPUB/KF8; MOBI6 still reads CSS.
        assertTrue("EPUB font missing", parsed.getValue(Fmt.EPUB).fonts.isNotEmpty())
        assertTrue("KF8 font missing", parsed.getValue(Fmt.KF8).fonts.isNotEmpty())
        assertTrue("MOBI6 grew fonts", parsed.getValue(Fmt.MOBI6).fonts.isEmpty())
        assertTrue("FB2 grew fonts", parsed.getValue(Fmt.FB2).fonts.isEmpty())

        // KF8 does not promise ::first-letter. Case 98 deliberately uses the
        // supported explicit-span recipe, which every HTML parser must map to
        // the same native side-box model without duplicating the source glyph.
        val dropCap = doc.tests().single { it.number == 98 }
        assertTrue("KF8 was wrongly excluded from explicit drop caps", Fmt.KF8 in dropCap.formats)
        val dropCapMarkup = dropCap.body.filterIsInstance<Block.P>()
            .flatMap { it.runs }
            .filterIsInstance<Run.Raw>()
            .joinToString { it.markup }
        assertTrue("explicit drop-cap span vanished", "dropcap-letter" in dropCapMarkup)
        assertTrue("fixture revived unsupported KF8 ::first-letter", "::first-letter" !in testStylesheet())
        for (format in listOf(Fmt.EPUB, Fmt.MOBI6, Fmt.KF8)) {
            val paragraph = parsed.getValue(format).chapters
                .flatMap { it.elements }
                .filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text.startsWith("Когда-то давно") }
            val cap = paragraph.block?.firstLetter
            assertNotNull("$format failed to synthesize explicit drop cap", cap)
            assertEquals("$format consumed the wrong initial", 1, cap!!.sourceTextLength)
            assertTrue("$format duplicated/styled the source initial", paragraph.text.spanStyles.none {
                it.start == 0 && it.end > 0
            })
        }

        // The same semantic note contains genuine blocks in every engine.
        for ((format, content) in parsed) {
            val rich = content.notes.values.firstOrNull { "Сложная сноска" in it.text }
            assertNotNull("$format lost the rich note", rich)
            val elements = rich!!.elements
            assertTrue("$format flattened the rich note", elements.size > 4)
            assertTrue("$format lost rich-note heading", elements.any { it is ContentElement.Heading })
            assertTrue("$format lost rich-note table", elements.any { it is ContentElement.Table })
            assertTrue("$format lost rich-note image", elements.any { it is ContentElement.Image })
            assertTrue("$format lost note-to-note text", "обычную сноску" in rich.text)
            val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
            assertTrue(
                "$format reclassified note-to-note as plain text",
                paragraphs.any { paragraph ->
                    paragraph.text.getStringAnnotations(
                        FOOTNOTE_TAG,
                        0,
                        paragraph.text.length,
                    ).isNotEmpty()
                },
            )
            assertTrue(
                "$format reclassified the rich-note chapter link",
                paragraphs.any { paragraph ->
                    paragraph.text.getStringAnnotations(
                        LINK_TAG,
                        0,
                        paragraph.text.length,
                    ).isNotEmpty()
                },
            )
        }

        // The deliberately hidden paragraph must never reach the reader.
        for ((format, content) in parsed) {
            assertTrue(
                "$format shows the display:none paragraph",
                "ОШИБКА" !in allText(content),
            )
        }
    }

    @Test
    fun `structural H1 through H6 survive on every parser path`() {
        val files = buildAll(tempFolder.newFolder("headings"))
        for ((format, file) in files) {
            val content = BookParsers.parseContent(
                file,
                bookFormatOf(format),
                tempFolder.newFolder("heading-images-$format"),
            )
            val headings = content.chapters
                .flatMap { it.elements }
                .filterIsInstance<ContentElement.Heading>()
            for (level in 1..6) {
                val expectedText = "H$level — СТРУКТУРНЫЙ УРОВЕНЬ $level"
                val heading = headings.firstOrNull { it.text == expectedText }
                assertNotNull("$format lost $expectedText", heading)
                assertEquals("$format changed $expectedText level", level, heading!!.level)
            }
        }
    }

    @Test
    fun `bidi corpus keeps native markup honest and text in logical order`() {
        val files = buildAll(tempFolder.newFolder("bidi"))

        val fb2Source = files.getValue(Fmt.FB2).readText(Charsets.UTF_8)
        val epubSource = ZipFile(files.getValue(Fmt.EPUB)).use { zip ->
            zip.getInputStream(zip.getEntry("OPS/bidi-parity.xhtml"))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }
        val mobi6Source = mobiMarkup(files.getValue(Fmt.MOBI6), kf8 = false)
        val kf8Source = mobiMarkup(files.getValue(Fmt.KF8), kf8 = true)

        for ((format, source) in mapOf(Fmt.EPUB to epubSource, Fmt.KF8 to kf8Source)) {
            assertTrue("$format lost dir=auto", "dir=\"auto\"" in source)
            assertTrue("$format lost bdi isolation markup", "<bdi>FrogReader-2026</bdi>" in source)
            assertTrue("$format lost bdo override markup", "<bdo dir=\"rtl\">ABC-123</bdo>" in source)
            assertTrue("$format lost RTL heading direction", "<h3 lang=\"ar\" dir=\"rtl\">" in source)
            assertTrue("$format lost logical text alignment", "text-align:start" in source && "text-align:end" in source)
        }
        assertTrue("EPUB lost logical margins", "margin-inline-start:3em" in epubSource)
        assertTrue(
            "KF8 fixture invented unsupported logical margins",
            "margin-inline-start:3em" !in kf8Source,
        )

        for ((format, source) in mapOf(Fmt.FB2 to fb2Source, Fmt.MOBI6 to mobi6Source)) {
            assertTrue("$format lost first-strong isolate", '\u2068' in source && '\u2069' in source)
            assertTrue("$format lost RTL isolate", '\u2067' in source && '\u2069' in source)
            assertTrue("$format lost directional override", '\u202e' in source && '\u202c' in source)
            assertTrue("$format invented HTML5 bdi", "<bdi>" !in source)
            assertTrue("$format invented HTML5 bdo", "<bdo" !in source)
        }

        for ((format, file) in files) {
            val content = BookParsers.parseContent(
                file,
                bookFormatOf(format),
                tempFolder.newFolder("bidi-images-$format"),
            )
            val text = allText(content)
            assertTrue("$format lost Arabic joining sample", ARABIC_BIDI_SAMPLE in text)
            assertTrue("$format lost Hebrew niqqud sample", HEBREW_BIDI_SAMPLE in text)
            assertTrue("$format lost mixed bidi sample", MIXED_BIDI_SAMPLE in text)

            val linkParagraph = content.chapters
                .flatMap { it.elements }
                .filterIsInstance<ContentElement.Paragraph>()
                .firstOrNull { "إلى الفصل 12" in it.text.text }
            assertNotNull("$format lost RTL link/noteref paragraph", linkParagraph)
            assertTrue(
                "$format lost RTL internal-link annotation",
                linkParagraph!!.text.getStringAnnotations(
                    LINK_TAG,
                    0,
                    linkParagraph.text.length,
                ).isNotEmpty(),
            )
            assertTrue(
                "$format lost RTL noteref annotation",
                linkParagraph.text.getStringAnnotations(
                    FOOTNOTE_TAG,
                    0,
                    linkParagraph.text.length,
                ).isNotEmpty(),
            )
        }

        for (format in listOf(Fmt.EPUB, Fmt.KF8)) {
            val content = BookParsers.parseContent(
                files.getValue(format),
                bookFormatOf(format),
                tempFolder.newFolder("bidi-style-$format"),
            )
            val elements = content.chapters.flatMap { it.elements }
            val heading = elements.filterIsInstance<ContentElement.Heading>()
                .single { it.text == "عنوان RTL: FrogReader 2026" }
            assertEquals("$format lost RTL heading base", BookTextDirection.RTL, heading.block?.direction)

            val start = elements.filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text == "START — بداية السطر" }
            val end = elements.filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text == "END — نهاية السطر" }
            assertEquals("$format logical start", BlockAlign.START, start.block?.align)
            assertEquals("$format logical end", BlockAlign.END, end.block?.align)
            assertEquals("$format start lost RTL base", BookTextDirection.RTL, start.block?.direction)
            assertEquals("$format end lost RTL base", BookTextDirection.RTL, end.block?.direction)

            val auto = elements.filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text == "2026 — مرحبًا FrogReader" }
            assertEquals("$format lost dir=auto", BookTextDirection.AUTO, auto.block?.direction)

            val isolated = elements.filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text == "حساب المستخدم: FrogReader-2026؛ جاهز." }
            val isolate = isolated.text.getStringAnnotations(
                BIDI_TAG,
                0,
                isolated.text.length,
            ).single { annotation ->
                isolated.text.text.substring(annotation.start, annotation.end) == "FrogReader-2026"
            }
            assertEquals(
                "$format lost bdi isolation",
                InlineBidiMode.ISOLATE_AUTO.name,
                isolate.item,
            )

            val overridden = elements.filterIsInstance<ContentElement.Paragraph>()
                .single { it.text.text == "Override: ABC-123." }
            val override = overridden.text.getStringAnnotations(
                BIDI_TAG,
                0,
                overridden.text.length,
            ).single { annotation ->
                overridden.text.text.substring(annotation.start, annotation.end) == "ABC-123"
            }
            assertEquals(
                "$format lost bdo override",
                InlineBidiMode.OVERRIDE_RTL.name,
                override.item,
            )
        }

        val epubContent = BookParsers.parseContent(
            files.getValue(Fmt.EPUB),
            bookFormatOf(Fmt.EPUB),
            tempFolder.newFolder("bidi-logical-margins-epub"),
        )
        val logicalMargins = epubContent.chapters
            .flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "LOGICAL MARGINS — هامش البداية أكبر" }
        assertEquals(BookTextDirection.RTL, logicalMargins.block?.direction)
        assertEquals(3f, logicalMargins.block?.indentStartEm ?: 0f, 0.001f)
        assertEquals(0.5f, logicalMargins.block?.indentEndEm ?: 0f, 0.001f)
    }

    @Test
    fun `EPUB alone carries MathML while other books carry linear equivalents`() {
        val files = buildAll(tempFolder.newFolder("math"))
        ZipFile(files.getValue(Fmt.EPUB)).use { zip ->
            val xhtml = zip.getInputStream(zip.getEntry("OPS/advanced-parity.xhtml"))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            assertTrue("EPUB fixture lost MathML namespace", "1998/Math/MathML" in xhtml)
            assertTrue("EPUB fixture lost fraction", "<mfrac>" in xhtml)
        }
        for (format in listOf(Fmt.FB2, Fmt.MOBI6, Fmt.KF8)) {
            val content = BookParsers.parseContent(
                files.getValue(format),
                bookFormatOf(format),
                tempFolder.newFolder("math-images-$format"),
            )
            assertTrue("$format lost linear math fallback", EXPECTED_LINEAR_MATH in allText(content))
        }
    }

    @Test
    fun `EPUB2 DTBook fixture parses native vocabulary`() {
        val file = File(tempFolder.newFolder("dtbook"), "FrogCompare_DTBook.epub")
        Epub2DtbookWriter.write(file)
        val metadata = BookParsers.parseMetadata(file, BookFormat.EPUB)
        assertEquals("FrogCompare — EPUB2 DTBook", metadata.title)
        val content = BookParsers.parseContent(
            file,
            BookFormat.EPUB,
            tempFolder.newFolder("dtbook-images"),
        )
        val text = allText(content)
        for (id in listOf("DTB-01", "DTB-02", "DTB-03")) {
            assertTrue("DTBook lost $id", id in text)
        }
        assertTrue("DTBook list vanished", "Третий пункт" in text)
        assertTrue("DTBook table vanished", "DTBook A" in text && "B1" in text)
        assertTrue("DTBook image was not extracted", content.chapters
            .flatMap { it.elements }
            .any { it is ContentElement.Image })
    }

    @Test
    fun `writers are byte deterministic and containers have distinct signatures`() {
        val first = buildAll(tempFolder.newFolder("deterministic-a"))
        val second = buildAll(tempFolder.newFolder("deterministic-b"))
        for (format in Fmt.entries) {
            assertTrue(
                "$format writer is not deterministic",
                first.getValue(format).readBytes().contentEquals(second.getValue(format).readBytes()),
            )
        }

        val dtbookA = File(tempFolder.newFolder("dtbook-a"), "book.epub")
        val dtbookB = File(tempFolder.newFolder("dtbook-b"), "book.epub")
        Epub2DtbookWriter.write(dtbookA)
        Epub2DtbookWriter.write(dtbookB)
        assertTrue("DTBook writer is not deterministic", dtbookA.readBytes().contentEquals(dtbookB.readBytes()))

        assertTrue("FB2 XML signature missing", first.getValue(Fmt.FB2).readText().startsWith("<?xml"))
        assertEpubSignature(first.getValue(Fmt.EPUB))
        assertMobiSignature(first.getValue(Fmt.MOBI6), expectedVersion = 6)
        assertMobiSignature(first.getValue(Fmt.KF8), expectedVersion = 8)
        assertEpubSignature(dtbookA)
    }

    @Test
    fun `README checklist classifies limits gaps and fixture mistakes`() {
        for (test in doc.tests()) {
            if (test.formats == ALL_FORMATS) continue
            val stub = test.stub.lowercase()
            assertTrue(
                "Test ${test.number} excludes a format without an explicit spec/profile limit: ${test.stub}",
                "spec" in stub || "формат" in stub || "не име" in stub || "нет " in stub ||
                    "огранич" in stub || "profile" in stub,
            )
        }

        val files = buildAll(tempFolder.newFolder("readme-books"))
        val dtbook = File(tempFolder.newFolder("readme-dtbook"), "book.epub")
            .also(Epub2DtbookWriter::write)
        val readme = buildReadme(files, dtbook)
        assertTrue("README lost first case", "| 1 |" in readme)
        assertTrue("README lost last case", "| 132 |" in readme)
        assertTrue("README lost spec classification", "Ø формат так не умеет" in readme)
        assertTrue("README lost reader-gap classification", "⚠ читалка — нет" in readme)
        assertTrue("README lost fixture-mistake rule", "fixture mistake" in readme)
        assertTrue("README revived stale css=null claim", "css = null" !in readme)
        assertTrue("README revived stale links-as-notes claim", "все ссылки считаются сносками" !in readme)
        for (number in 124..126) {
            val row = readme.lineSequence().single { it.startsWith("| $number |") }
            assertTrue("README still labels implemented bidi case $number as a gap", "⚠" !in row)
        }
        val logicalMarginsRow = readme.lineSequence().single { it.startsWith("| 132 |") }
        assertTrue("README still labels EPUB logical margins as a gap", "⚠" !in logicalMarginsRow)
    }

    /** `-PgenerateTestBooks=true` turns the check into the generator. */
    @Test
    fun `writes the books into testbooks when asked`() {
        if (System.getProperty("frogreader.generateTestBooks") != "true") return

        val repoRoot = TestAssets.repoRoot()
        val outputDir = File(repoRoot, ".testbooks")
        outputDir.mkdirs()
        val written = buildAll(outputDir)
        val dtbook = File(outputDir, "FrogCompare_DTBook_EPUB2.epub")
            .also(Epub2DtbookWriter::write)
        writeReadme(File(outputDir, "FrogCompare.README.md"), written, dtbook)

        // A summary beats opening four books to discover that one path lost a
        // chapter, rich note, link target or embedded font.
        for ((format, file) in written) {
            val content = BookParsers.parseContent(
                file,
                bookFormatOf(format),
                tempFolder.newFolder("out-$format"),
            )
            println(
                "$format → ${file.name}, ${file.length() / 1024} KB, " +
                    "глав ${content.chapters.size}, " +
                    "уровней ${content.chapters.maxOf { it.depth } + 1}, " +
                    "сносок ${content.notes.size}, " +
                    "переходов ${content.linkTargets.size}, " +
                    "шрифтов ${content.fonts.size}",
            )
        }
        val dtbookContent = BookParsers.parseContent(
            dtbook,
            BookFormat.EPUB,
            tempFolder.newFolder("out-dtbook"),
        )
        println(
            "EPUB2/DTBook → ${dtbook.name}, ${dtbook.length() / 1024} KB, " +
                "глав ${dtbookContent.chapters.size}",
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Both MOBI files go through the same parser; it picks the path itself. */
    private fun bookFormatOf(format: Fmt): BookFormat = when (format) {
        Fmt.FB2 -> BookFormat.FB2
        Fmt.EPUB -> BookFormat.EPUB
        Fmt.MOBI6, Fmt.KF8 -> BookFormat.MOBI
    }

    private fun buildAll(dir: File): Map<Fmt, File> {
        val font = TestAssets.bookFont(TestAssets.repoRoot())
        val fb2 = File(dir, "FrogCompare.fb2").also { Fb2Writer.write(it, doc) }
        val epub = File(dir, "FrogCompare.epub").also { EpubWriter.write(it, doc, font) }
        val mobi = File(dir, "FrogCompare.mobi").also { MobiWriter.writeMobi6(it, doc) }
        val azw3 = File(dir, "FrogCompare.azw3").also { MobiWriter.writeKf8(it, doc, font) }
        return mapOf(Fmt.FB2 to fb2, Fmt.EPUB to epub, Fmt.MOBI6 to mobi, Fmt.KF8 to azw3)
    }

    private fun allText(content: BookContent): String = buildString {
        for (chapter in content.chapters) {
            chapter.title?.let { appendLine(it) }
            for (element in chapter.elements) {
                when (element) {
                    is ContentElement.Paragraph -> appendLine(element.text.text)
                    is ContentElement.Heading -> appendLine(element.text)
                    is ContentElement.Table -> appendLine(element.flatText())
                    else -> Unit
                }
            }
        }
    }

    /** Reopens the generated PDB and returns its real authored HTML/XHTML. */
    private fun mobiMarkup(file: File, kf8: Boolean): String = PdbFile(file.readBytes()).use { pdb ->
        val section = MobiSection(pdb, base = 0, lastRecordExclusive = pdb.recordCount)
        val raw = section.assembleText()
        if (!kf8) {
            raw.toString(Charsets.UTF_8)
        } else {
            Kf8Assembler.assemble(section, raw).parts.joinToString("\n") {
                it.bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private fun assertEpubSignature(file: File) {
        val bytes = file.readBytes()
        assertTrue("${file.name} has no ZIP local-header signature", bytes.size > 4)
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            assertTrue("${file.name} has no entries", entries.hasMoreElements())
            val mimetype = entries.nextElement()
            assertEquals("mimetype must be first", "mimetype", mimetype.name)
            assertEquals("mimetype must be stored", ZipEntry.STORED, mimetype.method)
            val value = zip.getInputStream(mimetype).use { it.readBytes() }
            assertEquals("application/epub+zip", value.toString(Charsets.US_ASCII))
        }
    }

    private fun assertMobiSignature(file: File, expectedVersion: Int) {
        val bytes = file.readBytes()
        assertTrue("${file.name} is shorter than a PDB header", bytes.size > 98)
        assertEquals("BOOKMOBI", bytes.copyOfRange(60, 68).toString(Charsets.ISO_8859_1))
        val record0 = u32(bytes, 78)
        assertTrue("${file.name} record 0 offset is invalid", record0 >= 86 && record0 + 40 < bytes.size)
        assertEquals("MOBI", bytes.copyOfRange(record0 + 16, record0 + 20).toString(Charsets.US_ASCII))
        assertEquals("${file.name} has wrong MOBI generation", expectedVersion, u32(bytes, record0 + 36))
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeReadme(
        target: File,
        files: Map<Fmt, File>,
        dtbook: File,
    ) {
        target.writeText(buildReadme(files, dtbook), Charsets.UTF_8)
    }

    private fun buildReadme(files: Map<Fmt, File>, dtbook: File): String = buildString {
        append(README_HEADER)
        append("\n\n## Файлы и контрольные суммы\n\n")
        append("| Engine | Файл | SHA-256 |\n|---|---|---|\n")
        for (format in Fmt.entries) {
            val file = files.getValue(format)
            append("| $format | `${file.name}` | `${sha256(file)}` |\n")
        }
        append("| EPUB2/DTBook | `${dtbook.name}` | `${sha256(dtbook)}` |\n")

        append("\n## Единый numbered checklist\n\n")
        append("| № | Проверка | Базовое ручное ожидание | FB2 | EPUB | MOBI6 | KF8 |\n")
        append("|---:|---|---|---|---|---|---|\n")
        for (test in doc.tests()) {
            append("| ${test.number} | ${md(test.title)} | ${md(test.expected)} |")
            for (format in Fmt.entries) append(" ${status(test, format)} |")
            append('\n')
        }

        val overrides = doc.tests().filter { it.expectedPerFormat.isNotEmpty() }
        append("\n### Format-specific expectations\n\n")
        for (test in overrides) {
            for ((format, expected) in test.expectedPerFormat) {
                append("- **${test.number} / $format:** ${expected.trim()}\n")
            }
        }

        append(README_FOOTER)
    }

    private fun status(test: Block.Test, format: Fmt): String {
        if (format !in test.formats) return "Ø формат так не умеет"
        val expectation = test.expectedPerFormat[format] ?: test.expected
        val normalized = expectation.trim().lowercase()
        return if (normalized.startsWith("reader gap") ||
            normalized.startsWith("известный пробел") ||
            normalized.startsWith("известный край")
        ) {
            "⚠ читалка — нет"
        } else {
            "✓ проверить"
        }
    }

    private fun md(value: String): String = value
        .trim()
        .replace("|", "\\|")
        .replace("\n", "<br/>")

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}

/**
 * Strings from the "broken files" and typography chapters that every format
 * has to deliver intact — a bare ampersand, entities, an unknown tag's text,
 * deep nesting, non-Latin scripts and surrogate pairs.
 */
private val SURVIVES_EVERYWHERE = listOf(
    "Тор & Локи",
    "текст внутри неизвестного тега",
    "двадцать уровней вложенности",
    "«ёлочки»",
    "українськ",
    // From the typography chapter, which every format carries; the ruby
    // check is HTML-only, so its kanji would not do.
    "这是一个中文段落",
    "🐸",
    "السَّلَامُ عَلَيْكُمْ",
    "שָׁלוֹם עֲלֵיכֶם",
)

private const val EXPECTED_LINEAR_MATH = "x = (−b ± √(b² − 4ac)) / 2a"
private const val ARABIC_BIDI_SAMPLE = "السَّلَامُ عَلَيْكُمْ"
private const val HEBREW_BIDI_SAMPLE = "שָׁלוֹם עֲלֵיכֶם"
private const val MIXED_BIDI_SAMPLE = "مرحبا FrogReader 2026 — (الإصدار 3.5) [EPUB/KF8]"

private val README_HEADER = """
# FrogCompare — одна книга в четырёх форматах

`FrogCompare.fb2`, `.epub`, `.mobi` (классический MOBI6) и `.azw3` (KF8) содержат
**одинаковый список 1–132**. `.mobi` и `.azw3` не являются одним переименованным
файлом: первый строится как PalmDOC/MOBI6 с `filepos`, второй — как pure KF8 с
FDST/SKEL/FRAG, `kindle:pos`, INDX navigation, CSS flow и font resource.

Файлы собирает `GenerateTestBooksTest`; содержание живёт в `TestBookContent.kt`.
Пересобрать:

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest --tests "*GenerateTestBooksTest*" \
  -PgenerateTestBooks=true
```

Без `-PgenerateTestBooks=true` тесты работают только во временной папке и не
меняют `.testbooks/`. ZIP timestamps фиксированы, PDB headers детерминированы;
повторная сборка должна дать те же SHA-256.

## Как классифицировать отличие

- **Ø формат так не умеет** — в книге виден stub; писатель не подсовывает
  неподдерживаемый markup и использует честный форматный эквивалент, если он есть.
- **⚠ формат умеет, читалка — нет** — markup действительно находится в файле,
  но README/строка ожидания явно фиксирует reader gap.
- **fixture mistake** — нужного markup/resource/ID нет в самом generated file,
  четыре книги получили разные исходные тексты или подпись/container неверны.
  Автотесты одинаковой нумерации, signatures, parse round-trip и determinism
  должны ловить эту категорию до телефона.

Обычный fragment/filepos/kindle:pos link и настоящий noteref проверяются
раздельно. MOBI6 читает собственный legacy stylesheet, но это не превращает его
в HTML5/KF8 и не добавляет embedded fonts, SVG, ruby или MathML.
""".trimIndent()

private val README_FOOTER = """

## Критический ручной маршрут на Pixel 9a

1. Импортировать четыре main files одновременно и проверить metadata/cover 1–11.
2. В каждом файле открыть 14–16: одинаковая nested navigation и обычные links.
3. Сравнить 20–30 и 31–41 с Publisher's formatting off/on.
4. Сравнить tables 62–69, inline/block/SVG/GIF images 70–78.
5. Проверить cross-reference vs noteref 79–85.
6. На 111–116 убедиться глазами, что H1, H2, H3, H4, H5, H6 имеют шесть
   последовательно разных размеров при минимальном, среднем и максимальном base font.
7. На 117 открыть rich note: прокрутить H3, rich paragraph с inline image,
   quote, table, block image; [1] должен заменить popup, ссылка в главу 12 —
   закрыть popup и выполнить обычную навигацию.
8. На 118 EPUB показывает readable structured MathML. FB2/MOBI6/KF8 показывают
   ровно линейный эквивалент `x = (−b ± √(b² − 4ac)) / 2a` — это spec parity,
   не reader failure.
9. Case 98 использует explicit leading span во всех HTML paths. EPUB/MOBI6/KF8
   должны синтезировать из него один SideBox drop cap; FB2 честно показывает
   format-limit stub, потому что нормативной float-модели текста у него нет.
10. На 119–120 проверить typography и publisher colors при выключенном/включённом
   Publisher's formatting во всех четырёх книгах.
11. GIF 75 должен реально менять orange/blue frame; static frame отметить как
    «формат умеет, читалка — нет» только после device check, не по JVM parse test.
12. На 121–132 сравнить Arabic joining/harakat, Hebrew niqqud, mixed punctuation,
    `dir=auto`, `bdi`, `bdo`, RTL heading/link/noteref/list/table и logical
    start/end. В 124–126 EPUB/KF8 сохраняют native HTML bidi semantics, а
    FB2/MOBI6 используют честные Unicode equivalents; все четыре результата
    должны совпасть по смыслу. На 132 только EPUB несёт `margin-inline-*` и
    должен дать больший логический начальный отступ справа; остальные получают
    `Ø` stub.

Классификация bidi опирается на [W3C HTML bidi guidance](https://www.w3.org/TR/i18n-html-tech-bidi/)
и официальную [Amazon KF8 support table](https://kdp.amazon.com/en_US/help/topic/GG5R7N649LECKP7U):
KF8 явно поддерживает `bdi`, `bdo`, `direction` и `unicode-bidi`, но таблица не
обещает `margin-inline-*`. Поэтому 124–126 являются обязательными reader checks,
а отсутствие 132 в KF8 fixture — честный format/profile limit.

`FrogCompare_DTBook_EPUB2.epub` — отдельная пятая compatibility fixture. Она
намеренно не входит в four-way 1–132: это валидный EPUB 2 с
`application/x-dtbook+xml`, native level1–level6, nested list, poem/linegroup,
table, PNG+SVG, CSS, anchors, prodnote/rearmatter и NCX fragments. Подмешивать
DTBook в EPUB 3 main book было бы fixture mistake.
""".trimIndent()
