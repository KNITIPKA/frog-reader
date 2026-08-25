package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ReaderResourceBudgetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `archive directory rejects excessive entry count and declared aggregate`() {
        val countZip = zip(
            "count.zip",
            (1..4).associate { "entry-$it" to byteArrayOf(it.toByte()) },
        )
        ZipFile(countZip).use { archive ->
            val error = assertThrows(ResourceLimitException::class.java) {
                ArchiveResourceBudget(
                    archive,
                    limits(maxArchiveEntries = 3),
                )
            }
            assertEquals(ResourceLimitKind.ENTRY_COUNT, error.kind)
        }

        val aggregateZip = zip(
            "declared.zip",
            mapOf("one" to ByteArray(8), "two" to ByteArray(8)),
        )
        ZipFile(aggregateZip).use { archive ->
            val error = assertThrows(ResourceLimitException::class.java) {
                ArchiveResourceBudget(
                    archive,
                    limits(maxArchiveDeclaredBytes = 12),
                )
            }
            assertEquals(ResourceLimitKind.DECLARED_AGGREGATE, error.kind)
        }
    }

    @Test
    fun `actual aggregate includes repeated bounded reads and unknown sizes cannot bypass it`() {
        val file = zip(
            "actual.zip",
            mapOf("one" to ByteArray(8) { 1 }, "two" to ByteArray(8) { 2 }),
        )
        ZipFile(file).use { archive ->
            val budget = ArchiveResourceBudget(
                archive,
                limits(maxArchiveReadBytes = 12),
            )
            assertEquals(8, budget.readRequired(archive.getEntry("one"), 16, "one").size)
            val error = assertThrows(ResourceLimitException::class.java) {
                budget.readRequired(archive.getEntry("two"), 16, "two")
            }
            assertEquals(ResourceLimitKind.ACTUAL_AGGREGATE, error.kind)
        }
    }

    @Test
    fun `unsafe and oversized decorative entries skip while required entries fail`() {
        val file = zip(
            "entries.zip",
            mapOf("../escape.png" to ByteArray(4), "large.png" to ByteArray(64)),
        )
        ZipFile(file).use { archive ->
            val budget = ArchiveResourceBudget(archive, limits())
            assertNull(
                budget.readOptional(archive.getEntry("../escape.png"), 128, "unsafe image"),
            )
            assertNull(
                budget.readOptional(archive.getEntry("large.png"), 16, "large image"),
            )
            val error = assertThrows(ResourceLimitException::class.java) {
                budget.readRequired(archive.getEntry("large.png"), 16, "spine image")
            }
            assertEquals(ResourceLimitKind.ENTRY_SIZE, error.kind)
        }
        assertFalse(isSafeArchivePath("OPS/../../escape"))
        assertFalse(isSafeArchivePath("C:/escape"))
        assertFalse(isSafeArchivePath("OPS\\escape"))
        assertTrue(isSafeArchivePath("OPS/images/cover.jpg"))
    }

    @Test
    fun `corrupt optional ZIP payload skips without hiding required IO failure`() {
        val file = zip(
            "corrupt-entry.zip",
            linkedMapOf(
                "broken.css" to ByteArray(4_096) { (it * 37).toByte() },
                "good.txt" to "readable".toByteArray(),
            ),
        )
        corruptFirstPayload(file)

        ZipFile(file).use { archive ->
            val budget = ArchiveResourceBudget(archive, limits())
            assertNull(budget.readOptional(archive.getEntry("broken.css"), 8_192, "optional CSS"))
            val target = File(tempFolder.root, "broken-extract.css")
            assertFalse(
                budget.copyOptional(
                    archive.getEntry("broken.css"),
                    target,
                    8_192,
                    "optional CSS",
                ),
            )
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, target.name + ".tmp").exists())
            assertEquals(
                "readable",
                budget.readRequired(archive.getEntry("good.txt"), 128, "required text")
                    .decodeToString(),
            )
        }
        ZipFile(file).use { archive ->
            val budget = ArchiveResourceBudget(archive, limits())
            assertThrows(IOException::class.java) {
                budget.readRequired(archive.getEntry("broken.css"), 8_192, "required CSS")
            }
        }
    }

    @Test
    fun `high ratio mandatory EPUB metadata is rejected before parse allocation`() {
        val padding = "A".repeat(20_000)
        val file = epub(
            "ratio.epub",
            container = """
                <container><rootfiles><rootfile full-path="book.opf"/></rootfiles><!--$padding--></container>
            """.trimIndent(),
        )
        val error = assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseMetadata(
                file,
                limits(
                    maxCompressionRatio = 5,
                    compressionRatioCheckFromBytes = 100,
                    maxPackageXmlBytes = 64 * 1024,
                ),
            )
        }
        assertEquals(ResourceLimitKind.COMPRESSION_RATIO, error.kind)
    }

    @Test
    fun `EPUB oversized OPF and chapter are mandatory failures`() {
        val opfBomb = epub(
            "opf.epub",
            opfPadding = "x".repeat(2_000),
        )
        val opfError = assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseMetadata(
                opfBomb,
                limits(maxPackageXmlBytes = 512),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, opfError.kind)

        val chapterBomb = epub(
            "chapter.epub",
            chapterBody = "Readable " + "y".repeat(2_000),
        )
        val chapterError = assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseContent(
                chapterBomb,
                tempFolder.newFolder("chapter-images"),
                limits(maxChapterBytes = 512),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, chapterError.kind)
    }

    @Test
    fun `EPUB oversized decorative image is skipped but readable chapter survives`() {
        val file = epub(
            "decorative.epub",
            chapterBody = "Before<img src=\"large.png\" alt=\"ornament\"/>After",
            image = ByteArray(128) { it.toByte() },
        )
        val content = EpubParser.parseContent(
            file,
            tempFolder.newFolder("decorative-images"),
            limits(maxImageBytes = 32),
        )

        assertTrue(content.chapters.isNotEmpty())
        assertTrue(
            content.chapters.flatMap { it.elements }
                .filterIsInstance<ContentElement.Paragraph>()
                .any { "Before" in it.text.text && "After" in it.text.text },
        )
        assertTrue(
            content.chapters.flatMap { it.elements }
                .none { it is ContentElement.Image },
        )
    }

    @Test
    fun `zipped FB2 extraction is bounded atomic and keeps source on failure`() {
        val xml = fb2("z".repeat(2_000))
        val source = zip("oversized-fb2.zip", mapOf("book.fb2" to xml.toByteArray()))
        val books = tempFolder.newFolder("zipped-books")

        val error = assertThrows(ResourceLimitException::class.java) {
            BookParsers.detectAndStore(
                source,
                books,
                "too-large",
                limits(maxFb2Bytes = 512),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, error.kind)
        assertTrue(source.exists())
        assertFalse(File(books, "too-large.fb2").exists())
        assertFalse(File(books, "too-large.fb2.tmp").exists())

        val ordinaryXml = fb2("normal ".repeat(12_000)).toByteArray()
        val ordinary = zip("ordinary-fb2.zip", mapOf("nested/book.fb2" to ordinaryXml))
        val (format, stored) = BookParsers.detectAndStore(
            ordinary,
            books,
            "ordinary",
            limits(maxFb2Bytes = 256 * 1024),
        )
        assertEquals(BookFormat.FB2, format)
        assertArrayEquals(ordinaryXml, stored.readBytes())
        assertFalse(ordinary.exists())
    }

    @Test
    fun `FB2 whole document ceiling throws instead of returning truncated content`() {
        val xml = fb2("large paragraph ".repeat(200))
        val error = runCatching {
            Fb2Parser.parseContent(
                { xml.byteInputStream() },
                tempFolder.newFolder("fb2-limit"),
                limits(maxFb2Bytes = 512),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.isResourceLimitFailure())
    }

    @Test
    fun `FB2 oversized binary is consumed and skipped without losing text`() {
        val bytes = ByteArray(128) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
              xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>Binary budget</book-title></title-info></description>
              <body><section><p>Readable text.</p><image l:href="#huge"/></section></body>
              <binary id="huge" content-type="image/png">$encoded</binary>
            </FictionBook>
        """.trimIndent()

        val content = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder("fb2-binary"),
            limits(maxFb2BinaryBytes = 32),
        )
        val elements = content.chapters.flatMap { it.elements }
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any {
            it.text.text == "Readable text."
        })
        assertTrue(elements.none { it is ContentElement.Image })
    }

    @Test
    fun `FB2 binary count and aggregate independently bound extracted resources`() {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(8) { it.toByte() })
        val xml = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
              xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>Binary totals</book-title></title-info></description>
              <body><section><image l:href="#one"/><image l:href="#two"/></section></body>
              <binary id="one" content-type="image/png">$encoded</binary>
              <binary id="two" content-type="image/png">$encoded</binary>
            </FictionBook>
        """.trimIndent()

        val countLimited = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder("fb2-binary-count"),
            limits(maxFb2BinaryBytes = 16, maxFb2BinaryCount = 1),
        )
        assertEquals(
            1,
            countLimited.chapters.flatMap { it.elements }
                .filterIsInstance<ContentElement.Image>().size,
        )

        val aggregateLimited = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder("fb2-binary-aggregate"),
            limits(
                maxFb2BinaryBytes = 16,
                maxFb2BinaryAggregateBytes = 8,
                maxFb2BinaryCount = 16,
            ),
        )
        assertEquals(
            1,
            aggregateLimited.chapters.flatMap { it.elements }
                .filterIsInstance<ContentElement.Image>().size,
        )
    }

    @Test
    fun `ordinary large FB2 text remains inside the generous streaming budget`() {
        val xml = fb2("A substantial paragraph. ".repeat(5_000))
        val content = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder("fb2-large"),
            limits(maxFb2Bytes = 256 * 1024),
        )
        assertTrue(content.chapters.flatMap { it.elements }.isNotEmpty())
    }

    @Test
    fun `FB2 main and notes section nesting share one structural ceiling`() {
        fun nested(depth: Int, leaf: String): String =
            "<section>".repeat(depth) + leaf + "</section>".repeat(depth)

        val main = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Depth</book-title></title-info></description>
              <body>${nested(8, "<p>Main.</p>")}</body>
            </FictionBook>
        """.trimIndent()
        val mainError = assertThrows(ResourceLimitException::class.java) {
            Fb2Parser.parseContent(
                { main.byteInputStream() },
                tempFolder.newFolder("fb2-main-depth"),
                limits(maxFb2StructuralDepth = 4),
            )
        }
        assertEquals(ResourceLimitKind.STRUCTURAL_DEPTH, mainError.kind)

        val notes = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Note depth</book-title></title-info></description>
              <body><section><p>Main survives only without a limit violation.</p></section></body>
              <body name="notes">${nested(8, "<p>Nested note.</p>")}</body>
            </FictionBook>
        """.trimIndent()
        val noteError = assertThrows(ResourceLimitException::class.java) {
            Fb2Parser.parseContent(
                { notes.byteInputStream() },
                tempFolder.newFolder("fb2-note-depth"),
                limits(maxFb2StructuralDepth = 4),
            )
        }
        assertEquals(ResourceLimitKind.STRUCTURAL_DEPTH, noteError.kind)
    }

    @Test
    fun `FB2 stylesheet count and aggregate bound retained CSS before one parse`() {
        val center = "p { text-align: center; }"
        val end = "p { text-align: right; }"
        val xml = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <stylesheet type="text/css"><![CDATA[$center]]></stylesheet>
              <stylesheet type="text/css"><![CDATA[$end]]></stylesheet>
              ${"<stylesheet type=\"text/css\">p { font-style: italic; }</stylesheet>".repeat(100)}
              <description><title-info><book-title>CSS budgets</book-title></title-info></description>
              <body><section><p>Bounded CSS.</p></section></body>
            </FictionBook>
        """.trimIndent()

        fun alignment(folder: String, configured: ReaderResourceLimits): BlockAlign? =
            Fb2Parser.parseContent(
                { xml.byteInputStream() },
                tempFolder.newFolder(folder),
                configured,
            ).chapters.single().elements.filterIsInstance<ContentElement.Paragraph>()
                .single().block?.align

        assertEquals(
            BlockAlign.CENTER,
            alignment("fb2-css-count", limits(maxFb2StylesheetCount = 1)),
        )
        assertEquals(
            BlockAlign.CENTER,
            alignment(
                "fb2-css-aggregate",
                limits(
                    maxFb2StylesheetCount = 128,
                    maxFb2StylesheetAggregateBytes = center.length.toLong() * 2L,
                ),
            ),
        )
    }

    @Test
    fun `FB2 lossy binary ids extract to distinct cache files`() {
        val one = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 1)
        val two = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 2)
        val xml = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
              xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>Aliases</book-title></title-info></description>
              <body><section><image l:href="#a/b"/><image l:href="#a_b"/></section></body>
              <binary id="a/b" content-type="image/png">${Base64.getEncoder().encodeToString(one)}</binary>
              <binary id="a_b" content-type="image/png">${Base64.getEncoder().encodeToString(two)}</binary>
            </FictionBook>
        """.trimIndent()
        val images = Fb2Parser.parseContent(
            { xml.byteInputStream() },
            tempFolder.newFolder("fb2-id-alias"),
            limits(),
        ).chapters.single().elements.filterIsInstance<ContentElement.Image>()
        assertEquals(2, images.size)
        assertEquals(2, images.map { it.path }.toSet().size)
        assertTrue(images.any { File(it.path).readBytes().contentEquals(one) })
        assertTrue(images.any { File(it.path).readBytes().contentEquals(two) })
    }

    private fun limits(
        maxArchiveEntries: Int = 100,
        maxArchiveDeclaredBytes: Long = 2L * 1024 * 1024,
        maxArchiveReadBytes: Long = 2L * 1024 * 1024,
        maxCompressionRatio: Long = 10_000,
        compressionRatioCheckFromBytes: Long = 1L * 1024 * 1024,
        maxPackageXmlBytes: Long = 16 * 1024,
        maxChapterBytes: Long = 16 * 1024,
        maxImageBytes: Long = 16 * 1024,
        maxFb2Bytes: Long = 512 * 1024,
        maxFb2BinaryBytes: Long = 16 * 1024,
        maxFb2BinaryAggregateBytes: Long = 64 * 1024,
        maxFb2BinaryCount: Int = 16,
        maxFb2StructuralDepth: Int = 32,
        maxFb2StylesheetCount: Int = 32,
        maxFb2StylesheetAggregateBytes: Long = 32 * 1024,
    ) = ReaderResourceLimits(
        maxArchiveEntries = maxArchiveEntries,
        maxArchiveDeclaredBytes = maxArchiveDeclaredBytes,
        maxArchiveReadBytes = maxArchiveReadBytes,
        maxCompressionRatio = maxCompressionRatio,
        compressionRatioCheckFromBytes = compressionRatioCheckFromBytes,
        maxPackageXmlBytes = maxPackageXmlBytes,
        maxChapterBytes = maxChapterBytes,
        maxStylesheetBytes = 8 * 1024,
        maxImageBytes = maxImageBytes,
        maxFontBytes = 8 * 1024,
        maxFb2Bytes = maxFb2Bytes,
        maxFb2SanitizedBytes = minOf(maxFb2Bytes, 128 * 1024L),
        maxFb2BinaryBytes = maxFb2BinaryBytes,
        maxFb2BinaryAggregateBytes = maxFb2BinaryAggregateBytes,
        maxFb2BinaryCount = maxFb2BinaryCount,
        maxFb2StructuralDepth = maxFb2StructuralDepth,
        maxFb2StylesheetCount = maxFb2StylesheetCount,
        maxFb2StylesheetAggregateBytes = maxFb2StylesheetAggregateBytes,
    )

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = tempFolder.newFile(name)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            for ((path, bytes) in entries) {
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private fun corruptFirstPayload(file: File) {
        val compressedSize = ZipFile(file).use { archive ->
            archive.entries().nextElement().compressedSize
        }
        RandomAccessFile(file, "rw").use { random ->
            fun littleU16(offset: Long): Int {
                random.seek(offset)
                return random.readUnsignedByte() or (random.readUnsignedByte() shl 8)
            }
            val nameLength = littleU16(26)
            val extraLength = littleU16(28)
            val dataOffset = 30L + nameLength + extraLength
            random.seek(dataOffset)
            random.write(ByteArray(compressedSize.toInt()))
        }
    }

    private fun epub(
        name: String,
        container: String = """
            <container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>
        """.trimIndent(),
        opfPadding: String = "",
        chapterBody: String = "Readable chapter.",
        image: ByteArray? = null,
    ): File {
        val imageManifest = if (image == null) "" else
            "<item id=\"image\" href=\"large.png\" media-type=\"image/png\"/>"
        val opf = """
            <package xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
              <metadata><dc:title>Budget fixture</dc:title><dc:language>en</dc:language></metadata>
              <manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                $imageManifest
              </manifest>
              <spine><itemref idref="chapter"/></spine>
              <!--$opfPadding-->
            </package>
        """.trimIndent()
        val chapter = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body><p>$chapterBody</p></body></html>
        """.trimIndent()
        val entries = linkedMapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "book.opf" to opf.toByteArray(),
            "chapter.xhtml" to chapter.toByteArray(),
        )
        if (image != null) entries["large.png"] = image
        return zip(name, entries)
    }

    private fun fb2(text: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <description><title-info><book-title>Budget fixture</book-title></title-info></description>
          <body><section><p>$text</p></section></body>
        </FictionBook>
    """.trimIndent()
}
