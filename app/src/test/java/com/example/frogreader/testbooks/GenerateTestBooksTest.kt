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
        val logicalChapter = epubContent.chapters.single { chapter ->
            chapter.elements.any { element ->
                element is ContentElement.Paragraph &&
                    element.text.text == "LOGICAL MARGINS — هامش البداية أكبر"
            }
        }
        val logicalMargins = logicalChapter.elements
            .filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "LOGICAL MARGINS — هامش البداية أكبر" }
        assertEquals(BookTextDirection.RTL, logicalMargins.block?.direction)
        val logicalIndex = logicalChapter.elements.indexOf(logicalMargins)
        val logicalBox = logicalChapter.publisherBoxes.single { box ->
            box.startElement == logicalIndex && box.endElementExclusive == logicalIndex + 1
        }
        // Logical inline sides are resolved to physical axes before rendering.
        assertEquals(0.5f, logicalBox.style.marginLeftEm, 0.001f)
        assertEquals(3f, logicalBox.style.marginRightEm, 0.001f)
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
        assertTrue("README lost spec classification", "Ø unsupported by format" in readme)
        assertTrue("README lost reader-gap classification", "⚠ reader gap" in readme)
        assertTrue("README lost fixture-mistake rule", "fixture mistake" in readme)
        assertTrue("README revived stale css=null claim", "css = null" !in readme)
        assertTrue("README revived stale links-as-notes claim", "all links are treated as notes" !in readme && "все ссылки считаются сносками" !in readme)
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
                    "chapters ${content.chapters.size}, " +
                    "levels ${content.chapters.maxOf { it.depth } + 1}, " +
                    "notes ${content.notes.size}, " +
                    "links ${content.linkTargets.size}, " +
                    "fonts ${content.fonts.size}",
            )
        }
        val dtbookContent = BookParsers.parseContent(
            dtbook,
            BookFormat.EPUB,
            tempFolder.newFolder("out-dtbook"),
        )
        println(
            "EPUB2/DTBook → ${dtbook.name}, ${dtbook.length() / 1024} KB, " +
                "chapters ${dtbookContent.chapters.size}",
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
        append("\n\n## Files and Checksums\n\n")
        append("| Engine | File | SHA-256 |\n|---|---|---|\n")
        for (format in Fmt.entries) {
            val file = files.getValue(format)
            append("| $format | `${file.name}` | `${sha256(file)}` |\n")
        }
        append("| EPUB2/DTBook | `${dtbook.name}` | `${sha256(dtbook)}` |\n")

        append("\n## Unified Numbered Checklist\n\n")
        append("| № | Check | Baseline Manual Expectation | FB2 | EPUB | MOBI6 | KF8 |\n")
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
        if (format !in test.formats) return "Ø unsupported by format"
        val expectation = test.expectedPerFormat[format] ?: test.expected
        val normalized = expectation.trim().lowercase()
        return if (normalized.startsWith("reader gap") ||
            normalized.startsWith("известный пробел") ||
            normalized.startsWith("известный край")
        ) {
            "⚠ reader gap"
        } else {
            "✓ verify"
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
# FrogCompare — One Book in Four Formats

`FrogCompare.fb2`, `.epub`, `.mobi` (classic MOBI6), and `.azw3` (KF8) contain
the **identical checklist 1–132**. `.mobi` and `.azw3` are not a single renamed
file: the first is built as PalmDOC/MOBI6 with `filepos`, the second as pure KF8 with
FDST/SKEL/FRAG, `kindle:pos`, INDX navigation, CSS flow, and font resources.

Files are built by `GenerateTestBooksTest`; content lives in `TestBookContent.kt`.
Rebuild:

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest --tests "*GenerateTestBooksTest*" \
  -PgenerateTestBooks=true
```

Without `-PgenerateTestBooks=true`, tests run only in a temporary directory and
do not modify `.testbooks/`. ZIP timestamps are fixed, PDB headers are deterministic;
rebuilding must produce the same SHA-256 hashes.

## How to Classify Differences

- **Ø unsupported by format** — a stub is visible in the book; the writer does not inject
  unsupported markup and uses an honest format equivalent if one exists.
- **⚠ reader gap** — markup is indeed present in the file, but the README/expectation
  line explicitly records a reader gap.
- **fixture mistake** — the required markup/resource/ID is missing from the generated file itself,
  the four books received differing source texts, or the signature/container is invalid.
  Automated tests for identical numbering, signatures, parse round-trip, and determinism
  must catch this category before testing on a device.

Standard fragment/filepos/kindle:pos links and true noterefs are verified
separately. MOBI6 reads its own legacy stylesheet, but this does not transform it
into HTML5/KF8, nor does it add embedded fonts, SVG, ruby, or MathML.
""".trimIndent()

private val README_FOOTER = """

## Critical Manual Device Route on Pixel 9a

1. Import four main files simultaneously and verify metadata/cover 1–11.
2. In each file open 14–16: identical nested navigation and regular links.
3. Compare 20–30 and 31–41 with Publisher's formatting off/on.
4. Compare tables 62–69, inline/block/SVG/GIF images 70–78.
5. Verify cross-reference vs noteref 79–85.
6. At 111–116 verify by eye that H1, H2, H3, H4, H5, H6 have six
   consistently distinct sizes at small, medium, and maximum base font.
7. At 117 open rich note: scroll H3, rich paragraph with inline image,
   quote, table, block image; [1] must replace popup, link to chapter 12 must
   close popup and navigate normally.
8. At 118 EPUB displays readable structured MathML. FB2/MOBI6/KF8 show
   the exact linear equivalent `x = (−b ± √(b² − 4ac)) / 2a` — this is spec parity,
   not reader failure.
9. Case 98 uses explicit leading span across all HTML paths. EPUB/MOBI6/KF8
   must synthesize a single SideBox drop cap from it; FB2 honestly displays
   the format-limit stub because it has no normative float text model.
10. At 119–120 verify typography and publisher colors with Publisher's formatting
    turned off/on across all four books.
11. GIF 75 must visibly alternate orange/blue frames; mark static frame as
    "reader gap" only after device check, not based on JVM parse test.
12. At 121–132 compare Arabic joining/harakat, Hebrew niqqud, mixed punctuation,
    `dir=auto`, `bdi`, `bdo`, RTL heading/link/noteref/list/table, and logical
    start/end. In 124–126 EPUB/KF8 retain native HTML bidi semantics, while
    FB2/MOBI6 use honest Unicode equivalents; all four results must match semantically.
    At 132 only EPUB carries `margin-inline-*` and must produce a larger logical start
    indent on the right; others receive the `Ø` stub.

Bidi classification is based on [W3C HTML bidi guidance](https://www.w3.org/TR/i18n-html-tech-bidi/)
and official [Amazon KF8 support table](https://kdp.amazon.com/en_US/help/topic/GG5R7N649LECKP7U):
KF8 explicitly supports `bdi`, `bdo`, `direction`, and `unicode-bidi`, but the table does not
promise `margin-inline-*`. Therefore 124–126 are mandatory reader checks,
and the absence of 132 in KF8 fixture is an honest format/profile limit.

`FrogCompare_DTBook_EPUB2.epub` is a separate fifth compatibility fixture. It is
intentionally excluded from the four-way 1–132: it is a valid EPUB 2 with
`application/x-dtbook+xml`, native level1–level6, nested list, poem/linegroup,
table, PNG+SVG, CSS, anchors, prodnote/rearmatter, and NCX fragments. Mixing
DTBook into the EPUB 3 main book would be a fixture mistake.
""".trimIndent()
