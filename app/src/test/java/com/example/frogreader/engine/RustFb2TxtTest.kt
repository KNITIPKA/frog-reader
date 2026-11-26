package com.example.frogreader.engine

import android.content.Context
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.LanguageTag
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.TextBookParser
import com.example.frogreader.testfixtures.LocalBookFixtures
import kotlinx.coroutines.runBlocking
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64

class RustFb2TxtTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun requireRust() {
        assumeTrue(
            "host Rust tests require -Dfrogreader.requireRust=true",
            java.lang.Boolean.getBoolean("frogreader.requireRust"),
        )
        assertTrue("Rust native library must be ready", RustBridge.shared.isLibraryReady)
    }

    // -------------------------------------------------------------------------
    // M4.1: Native TXT / MD decoding, validation, paragraphs, and slugs
    // -------------------------------------------------------------------------

    @Test
    fun `native text decoder handles BOMs, encodings, and line endings directly`() {
        requireRust()
        val samples = listOf(
            (
                "Українська книжка — їжак і ґанок\r\nДругий рядок\rТретій\u000cЧетвертий" to
                    "Українська книжка — їжак і ґанок\nДругий рядок\nТретій\nЧетвертий"
                ),
            (
                "  Leading spaces\n\n\tTabbed line\r\n\r\nTrailing\n" to
                    "  Leading spaces\n\n\tTabbed line\n\nTrailing\n"
                ),
            (
                "Mixed emoji 🐸 and rtl שלום العربية" to
                    "Mixed emoji 🐸 and rtl שלום العربية"
                ),
        )
        val charsets = listOf(
            Charsets.UTF_8 to byteArrayOf(),
            Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()),
        )

        for ((raw, expected) in samples) {
            for ((charset, bom) in charsets) {
                val bytes = bom + raw.toByteArray(charset)
                val file = tempFolder.newFile().apply { writeBytes(bytes) }
                assertEquals(
                    "${charset.name()} bom=${bom.size}",
                    expected,
                    TextBookParser.readText(file),
                )
            }
        }

        val cp1251Text = "Привет, мир!\r\nСтарая кириллица — тире и «кавычки»\rСтрока\u000cКонец"
        val cp1251Bytes = cp1251Text.toByteArray(Charset.forName("windows-1251"))
        val cp1251File = tempFolder.newFile("cp1251.txt").apply { writeBytes(cp1251Bytes) }
        assertEquals(
            "Привет, мир!\nСтарая кириллица — тире и «кавычки»\nСтрока\nКонец",
            TextBookParser.readText(cp1251File),
        )
    }

    @Test
    fun `native text decoder preserves exact error contracts for UTF-32, binary, blank, and malformed inputs`() {
        requireRust()
        val utf32Le = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0, 'A'.code.toByte(), 0, 0, 0)
        val utf32Be = byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte(), 0, 0, 0, 'A'.code.toByte())
        for (bytes in listOf(utf32Le, utf32Be)) {
            val file = tempFolder.newFile().apply { writeBytes(bytes) }
            val err = assertThrows(IOException::class.java) { TextBookParser.readText(file) }
            assertEquals("UTF-32 text is not supported. Save the file as UTF-8.", err.message)
        }

        for (bytes in listOf(
            "hello\u0000world".toByteArray(),
            "hello\u0007world".toByteArray(),
            "hello\u007fworld".toByteArray(),
            "hello\u0085world".toByteArray(Charsets.UTF_8),
        )) {
            val file = tempFolder.newFile().apply { writeBytes(bytes) }
            val err = assertThrows(IOException::class.java) { TextBookParser.readText(file) }
            assertEquals("The file contains binary data, not readable text.", err.message)
        }

        for (blank in listOf("", "   \n\t\r\u000c\u00a0 ")) {
            val file = tempFolder.newFile().apply { writeText(blank) }
            val err = assertThrows(IOException::class.java) { TextBookParser.readText(file) }
            assertEquals("The text document is empty.", err.message)
        }

        val oddUtf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 65)
        assertThrows(IOException::class.java) {
            TextBookParser.readText(tempFolder.newFile().apply { writeBytes(oddUtf16) })
        }
        val unpairedSurrogate = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0xd8.toByte())
        assertThrows(IOException::class.java) {
            TextBookParser.readText(tempFolder.newFile().apply { writeBytes(unpairedSurrogate) })
        }
        val badUtf8Bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte())
        assertThrows(IOException::class.java) {
            TextBookParser.readText(tempFolder.newFile().apply { writeBytes(badUtf8Bom) })
        }
        val unmappedCp1251 = byteArrayOf(0xcf.toByte(), 0xff.toByte(), 0x98.toByte())
        assertThrows(IOException::class.java) {
            TextBookParser.readText(tempFolder.newFile().apply { writeBytes(unmappedCp1251) })
        }
    }

    @Test
    fun `native TXT paragraph splitting and MD heading slugs handle Unicode and deduplication`() {
        requireRust()
        val rawTxt = "  <b>Literal & text</b>\r\n# Not a heading\r\n   \t  \r\n**Not bold**\rLast line\u000cPage 2\n\n"
        val txtFile = tempFolder.newFile("sample.txt").apply { writeBytes(rawTxt.toByteArray()) }
        val nativeContent = TextBookParser.parseContent(txtFile, BookFormat.TXT)
        assertEquals(
            listOf(
                "  <b>Literal & text</b>\n# Not a heading",
                "**Not bold**\nLast line\nPage 2",
            ),
            nativeContent.chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text },
        )

        val headings = listOf(
            "Книжка!",
            "MD-40. Финиш",
            "Title with   spaces\tand\u00a0nbsp",
            "Repeat",
            "Repeat-1",
            "Repeat",
            "Repeat",
            "Їжак\u0301 & Ґанок №1",
            "ΣΟΦΟΣ",
            "İstanbul",
            "!!!",
            "???",
        )
        assertEquals(
            listOf(
                "книжка",
                "md-40-финиш",
                "title-with---spaces-andnbsp",
                "repeat",
                "repeat-1",
                "repeat-2",
                "repeat-3",
                "їжак\u0301--ґанок-1",
                "σοφος",
                "i\u0307stanbul",
                "",
                "-1",
            ),
            TextBookParser.headingSlugsNative(headings),
        )
    }

    @Test
    fun `local TXT and MD formatting test fixtures parse cleanly via Rust`() {
        requireRust()
        val txt = LocalBookFixtures.find("text-formats/FrogReader-TXT-Formatting-Test.txt")
        if (txt.isFile) {
            val paragraphs = TextBookParser.parseContent(txt, BookFormat.TXT)
                .chapters.single().elements
                .filterIsInstance<ContentElement.Paragraph>()
                .map { it.text.text }
            assertEquals("FrogReader — TXT: полный тест обычного текста", paragraphs.first())
            assertTrue(paragraphs.contains("LF-СТРОКА-1\nLF-СТРОКА-2\nLF-СТРОКА-3"))
            assertTrue(paragraphs.contains("CRLF-СТРОКА-1\nCRLF-СТРОКА-2\nCRLF-СТРОКА-3"))
            assertTrue(paragraphs.contains("CR-СТРОКА-1\nCR-СТРОКА-2\nCR-СТРОКА-3"))
        }

        val md = LocalBookFixtures.find("text-formats/FrogReader-Markdown-Formatting-Test.md")
        if (md.isFile) {
            val parsed = BookParsers.parseContent(md, BookFormat.MD, tempFolder.newFolder())
            assertTrue(parsed.navigation.size > 10)
            assertTrue(parsed.linkTargets.containsKey("#md-40-финиш"))
        }
    }

    @Test
    fun `native Markdown parser and renderer matches CommonMark reference across all constructs and depth bounds`() {
        requireRust()
        val corpus = mutableListOf(
            "headings-and-inlines" to """
                # Книжка! #
                ## Repeat
                Repeat
                ------

                Setext H1
                =========

                Paragraph with *em*, **strong**, _under_, __strong2__, ~~strike~~, and `code <b>`.
                Line with hard break  
                and backslash\
                next line.
            """.trimIndent(),
            "blocks-lists-and-code" to """
                # Code & Lists

                ```kotlin info
                val x = 1 < 2 && 3 > 1
                ```

                    indented <code & block>
                    line 2

                > Quoted *line*
                > lazy continuation

                ---

                - tight 1
                - tight 2

                3. loose 1

                4. loose 2
            """.trimIndent(),
            "tables-links-images-html-entities" to """
                # Tables & Links

                | Left | Center | Right | Default |
                | :--- | :---: | ---: | --- |
                | a \| b | *c* | `d` | e |

                [inline](https://example.com "Title & <tag>") and [bad](javascript:alert(1)) and [frag](#repeat-1) and [mail](mailto:a@b.c)

                [ref][id1], [Collapsed][], [shortcut]

                [id1]: https://frog.example/path (Ref Title)
                [collapsed]: /local/path
                [shortcut]: #tables--links

                Autolinks: <https://example.org/test?a=1&b=2> and <reader@example.org>

                Image: ![Alt *text* & a < b](https://example.com/pic.png "ignored")

                Entities: &mdash; &laquo;Frog&raquo; &#160; &#x2014;

                <script>alert('xss')</script>

                Inline <b>raw</b> html.
            """.trimIndent(),
        )
        val fixtureMd = LocalBookFixtures.find("text-formats/FrogReader-Markdown-Formatting-Test.md")
        if (fixtureMd.isFile) {
            corpus += "fixture-md" to TextBookParser.readText(fixtureMd)
        }

        for ((name, markdownText) in corpus) {
            val mdFile = tempFolder.newFile("$name.md").apply {
                writeBytes(markdownText.toByteArray(Charsets.UTF_8))
            }
            val actualMeta = TextBookParser.parseMetadata(mdFile, BookFormat.MD)
            val expectedMeta = referenceParseMarkdownMetadata(markdownText)
            assertEquals("Metadata title parity for $name", expectedMeta.title, actualMeta.title)
            assertEquals("Metadata author parity for $name", expectedMeta.author, actualMeta.author)
            assertArrayEquals("Metadata cover parity for $name", expectedMeta.coverBytes, actualMeta.coverBytes)

            val actualContent = TextBookParser.parseContent(mdFile, BookFormat.MD)
            val expectedContent = referenceParseMarkdownContent(markdownText)
            assertEquals("Language parity for $name", expectedContent.language, actualContent.language)
            assertEquals("LinkTargets parity for $name", expectedContent.linkTargets, actualContent.linkTargets)
            assertEquals(
                "Navigation parity for $name",
                expectedContent.navigation.map { entry ->
                    val ro = entry.target as? BookNavigationTarget.ReadingOrder
                    Triple(entry.title, entry.depth, ro?.let { it.chapterIndex to it.elementIndex })
                },
                actualContent.navigation.map { entry ->
                    val ro = entry.target as? BookNavigationTarget.ReadingOrder
                    Triple(entry.title, entry.depth, ro?.let { it.chapterIndex to it.elementIndex })
                },
            )
            val expectedElements = expectedContent.chapters.single().elements
            val actualElements = actualContent.chapters.single().elements
            assertEquals("Element count for $name", expectedElements.size, actualElements.size)
            for (idx in expectedElements.indices) {
                assertEquals(
                    "Element $idx parity for $name (expected=${expectedElements[idx]}, actual=${actualElements[idx]})",
                    encodeElements(listOf(expectedElements[idx])),
                    encodeElements(listOf(actualElements[idx])),
                )
            }
            assertEquals(
                "Chapters FRB parity for $name",
                encodeBookChapters(expectedContent.chapters),
                encodeBookChapters(actualContent.chapters),
            )
        }

        // Depth bounds: 110 levels of blockquote passes; 140 levels and 280 nested emphasis fail.
        val okQuotes = "> ".repeat(110) + "ok"
        assertTrue(TextBookParser.renderMarkdownHtmlNative(okQuotes).contains("<p>ok</p>"))

        for (deep in listOf("> ".repeat(140) + "deep", "*".repeat(280) + "x" + "*".repeat(280))) {
            val err1 = assertThrows(IOException::class.java) {
                TextBookParser.renderMarkdownHtmlNative(deep)
            }
            assertEquals("Markdown nesting is too deep.", err1.message)

            val deepFile = tempFolder.newFile().apply { writeText(deep) }
            val err2 = assertThrows(IOException::class.java) {
                TextBookParser.parseContent(deepFile, BookFormat.MD)
            }
            assertEquals("Markdown nesting is too deep.", err2.message)

            val err3 = assertThrows(IOException::class.java) {
                TextBookParser.parseMetadata(deepFile, BookFormat.MD)
            }
            assertEquals("Markdown nesting is too deep.", err3.message)
        }
    }

    @Test
    fun `single-JNI parseMarkdownTitle and parseMarkdownMarkup handle encodings, formatting, entities, blank, and guardrails`() {
        requireRust()

        val mdText = """
            Intro line before heading.

            ## *Tom* &amp; **Jerry** `code` &#x2605; <span>raw</span>

            Body paragraph with [link](#tom--jerry-code--spanrawspan) and ~~strike~~.
        """.trimIndent()

        // UTF-8, UTF-8 BOM, UTF-16LE BOM, UTF-16BE BOM
        val encodings = listOf(
            "utf8" to mdText.toByteArray(Charsets.UTF_8),
            "utf8-bom" to (byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + mdText.toByteArray(Charsets.UTF_8)),
            "utf16le" to (byteArrayOf(0xff.toByte(), 0xfe.toByte()) + mdText.toByteArray(Charsets.UTF_16LE)),
            "utf16be" to (byteArrayOf(0xfe.toByte(), 0xff.toByte()) + mdText.toByteArray(Charsets.UTF_16BE)),
        )
        val expectedMeta = referenceParseMarkdownMetadata(mdText)
        val expectedContent = referenceParseMarkdownContent(mdText)
        assertEquals("Tom & Jerry code ★ <span>raw</span>", expectedMeta.title)

        for ((label, bytes) in encodings) {
            val file = tempFolder.newFile("single-jni-$label.md").apply { writeBytes(bytes) }
            val actualMeta = TextBookParser.parseMetadata(file, BookFormat.MD)
            assertEquals("Metadata title parity for $label", expectedMeta.title, actualMeta.title)
            assertEquals("Metadata author parity for $label", expectedMeta.author, actualMeta.author)
            assertNull("Metadata cover parity for $label", actualMeta.coverBytes)
            val actualContent = TextBookParser.parseContent(file, BookFormat.MD)
            assertEquals("Chapters FRB parity for $label", encodeBookChapters(expectedContent.chapters), encodeBookChapters(actualContent.chapters))
            assertEquals("LinkTargets parity for $label", expectedContent.linkTargets, actualContent.linkTargets)
        }

        // Heading with inline image renders alt text consistently in both parseMetadata and parseContent
        val imgHeadingFile = tempFolder.newFile("img-heading.md").apply {
            writeText("## *Tom* ![alt](img.png) `code`\n\nParagraph.")
        }
        assertEquals("Tom alt code", TextBookParser.parseMetadata(imgHeadingFile, BookFormat.MD).title)
        assertEquals("Tom alt code", TextBookParser.parseContent(imgHeadingFile, BookFormat.MD).chapters.single().title)

        // Windows-1251 encoded Markdown
        val cp1251Md = "# Заголовок — *тест* &amp; `код`\r\n\r\nПривет, **мир**!\r\n"
        val cp1251File = tempFolder.newFile("single-jni-cp1251.md").apply {
            writeBytes(cp1251Md.toByteArray(Charset.forName("windows-1251")))
        }
        assertEquals("Заголовок — тест & код", TextBookParser.parseMetadata(cp1251File, BookFormat.MD).title)
        val cp1251Content = TextBookParser.parseContent(cp1251File, BookFormat.MD)
        val cp1251Expected = referenceParseMarkdownContent(cp1251Md.replace("\r\n", "\n"))
        assertEquals(encodeBookChapters(cp1251Expected.chapters), encodeBookChapters(cp1251Content.chapters))

        // Empty first heading -> null title (matches selectFirst("h1..h6")?.text()?.takeIf { isNotBlank() })
        val emptyFirstFile = tempFolder.newFile("empty-first.md").apply { writeText("# \n\n## Second\n") }
        assertNull(TextBookParser.parseMetadata(emptyFirstFile, BookFormat.MD).title)

        // Document without headings -> null title
        val noHeadingFile = tempFolder.newFile("no-heading.md").apply { writeText("Plain paragraph only.\n") }
        assertNull(TextBookParser.parseMetadata(noHeadingFile, BookFormat.MD).title)

        // Link-definition-only Markdown -> blank rendered HTML -> no readable content
        val linkDefOnlyFile = tempFolder.newFile("link-def-only.md").apply { writeText("[ref]: https://example.com\n") }
        assertNull(TextBookParser.parseMetadata(linkDefOnlyFile, BookFormat.MD).title)
        val noContentErr = assertThrows(IOException::class.java) {
            TextBookParser.parseContent(linkDefOnlyFile, BookFormat.MD)
        }
        assertEquals("The Markdown document has no readable content.", noContentErr.message)

        // Blank Markdown file -> empty document error on both metadata and content
        val blankFile = tempFolder.newFile("blank.md").apply { writeText("   \r\n\t  ") }
        val blankMetaErr = assertThrows(IOException::class.java) {
            TextBookParser.parseMetadata(blankFile, BookFormat.MD)
        }
        assertEquals("The text document is empty.", blankMetaErr.message)
        val blankContentErr = assertThrows(IOException::class.java) {
            TextBookParser.parseContent(blankFile, BookFormat.MD)
        }
        assertEquals("The text document is empty.", blankContentErr.message)

        // Direct JNI wire sanity: parseMarkdownMarkup returns valid TXT1+OK wrapping MRK2
        val rawWire = requireNotNull(RustFb2TxtNative.parseMarkdownMarkup("# Hello\n\nWorld".toByteArray()))
        assertEquals('T'.code.toByte(), rawWire[0])
        assertEquals('X'.code.toByte(), rawWire[1])
        assertEquals('T'.code.toByte(), rawWire[2])
        assertEquals('1'.code.toByte(), rawWire[3])
        assertEquals(0.toByte(), rawWire[4])
        assertEquals('M'.code.toByte(), rawWire[9])
        assertEquals('R'.code.toByte(), rawWire[10])
        assertEquals('K'.code.toByte(), rawWire[11])
        assertEquals('2'.code.toByte(), rawWire[12])
    }

    @Test
    fun `native parseMarkdownContent and writeMdFrb achieve bit-for-bit parity with HtmlMapper including GFM tables`() {
        requireRust()

        val richUnicodeMd = """
            # 🐸 Книжка — **Заголовок** з *курсивом* та `кодом`

            Перший абзац українською мовою про їжака, ґанок та єнота у лісі для перевірки автоматичного визначення мови.
            Додатковий текст українською: і, ї, є, ґ, ще трохи слів про книжки, сторінки та читання у дорозі.

            Emoji UTF-16 surrogate pairs: 🐸 **bold** and *שלום* and ~~strike~~ and `code <b>` and [back](#%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0) and [web](https://example.com) and [bad](javascript:alert(1)).

            > Цитований *абзац*
            > з продовженням

            - Перший
              - Вкладений *елемент*
                - Третій рівень
            - Другий

            3. Нумерований третій
            4. Нумерований четвертий

            ```kotlin
              val emoji = "🐸"
                println("<tag>&amp;")
            ```

            ---

            ## Книга

            Посилання назад на [початок](#-книжка--заголовок-з-курсивом-та-кодом) і на [розділ](#%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0) та [сирий пробіл](#bad%20frag).
        """.trimIndent()

        val hebrewMd = """
            # שלום עולם

            זוהי פסקה בעברית לבדיקת זיהוי שפה אוטומטי וסידור טקסט מימין לשמאל עם **הדגשה** ו*נטוי* וגם `קוד`.
            עוד שורה בעברית כדי לעבור את סף האותיות הנדרש לזיהוי מדויק של השפה העברית במסמך.

            [חזרה](#שלום-עולם)
        """.trimIndent()

        for ((name, md) in listOf("rich-unicode-uk" to richUnicodeMd, "hebrew-rtl" to hebrewMd)) {
            val bytes = md.toByteArray(Charsets.UTF_8)
            val sourceFile = tempFolder.newFile("native-md-$name.md").apply { writeBytes(bytes) }

            // 1. Verify native JNI parseMarkdownContent hits STATUS_OK (0), NOT fallback!
            val wire = requireNotNull(RustFb2TxtNative.parseMarkdownContent(bytes))
            assertEquals("TXT1 magic for $name", 'T'.code.toByte(), wire[0])
            assertEquals("Native parseMarkdownContent must return STATUS_OK for $name", 0.toByte(), wire[4])

            // 2. Compare native TextBookParser.parseContent bit-for-bit against Kotlin HtmlMapper fallback!
            val actual = TextBookParser.parseContent(sourceFile, BookFormat.MD)
            val fallback = TextBookParser.parseMarkdownMarkupFallback(bytes)
            assertEquals("Language parity for $name", fallback.language, actual.language)
            assertEquals("LinkTargets parity for $name", fallback.linkTargets, actual.linkTargets)
            assertEquals("Title parity for $name", fallback.chapters.single().title, actual.chapters.single().title)
            assertEquals(
                "Navigation parity for $name",
                fallback.navigation.map { Triple(it.title, it.depth, it.target) },
                actual.navigation.map { Triple(it.title, it.depth, it.target) },
            )
            // Chapter block (elements, UTF-16 ranges, annotations, styles, publisherBoxes) must be 100% byte-identical!
            assertEquals(
                "Chapter FRB wire parity for $name",
                encodeBookChapters(fallback.chapters),
                encodeBookChapters(actual.chapters),
            )
            // Full BookContent wire parity (sorted linkTargets matching Rust BTreeMap order).
            val canonicalFallback = BookContent(
                chapters = fallback.chapters,
                notes = fallback.notes.toSortedMap(),
                linkTargets = fallback.linkTargets.toSortedMap(),
                fonts = fallback.fonts,
                language = fallback.language,
                linkedDocuments = fallback.linkedDocuments.toSortedMap(),
                linkedDocumentTargets = fallback.linkedDocumentTargets.toSortedMap(),
                navigation = fallback.navigation,
                publisherPublication = fallback.publisherPublication,
                pageProgression = fallback.pageProgression,
                epubSourceRanges = fallback.epubSourceRanges,
            )
            assertArrayEquals(
                "BookContent FRB wire parity for $name",
                FrbModelCodec.encodeToBytes(BookContent.serializer(), canonicalFallback),
                FrbModelCodec.encodeToBytes(BookContent.serializer(), actual),
            )

            // 3. Verify direct writeMdFrb writes a byte-for-byte identical FRB v2 container!
            val refCache = ChunkedContentCache(tempFolder.newFolder("md-ref-cache-$name"))
            assertTrue(refCache.save(name, sourceFile, canonicalFallback))
            val refEntry = refCache.entryFile(name, complete = true)

            val directCache = ChunkedContentCache(tempFolder.newFolder("md-direct-cache-$name"))
            val directEntry = directCache.entryFile(name, complete = true)
            val sourceMetadata = chunkedCacheSourceMetadata(sourceFile)
            assertTrue("TextBookParser.writeMdFrb succeeded for $name", TextBookParser.writeMdFrb(sourceFile, directEntry, sourceMetadata))
            assertTrue(directEntry.isFile)
            // Must refuse overwriting existing file
            assertFalse(TextBookParser.writeMdFrb(sourceFile, directEntry, sourceMetadata))

            val refHandle = RustChunkedCacheNative.openRead(refEntry.path, sourceMetadata)
            val directHandle = RustChunkedCacheNative.openRead(directEntry.path, sourceMetadata)
            assertTrue(refHandle > 0L && directHandle > 0L)
            try {
                assertArrayEquals(
                    "Block 0 (BookIndex) byte parity for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 0)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 0)),
                )
                assertArrayEquals(
                    "Block 1 (BookGlobals) byte parity for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 1)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 1)),
                )
                assertArrayEquals(
                    "Block 2 (Chapter) byte parity for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 2)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 2)),
                )
            } finally {
                RustChunkedCacheNative.close(refHandle)
                RustChunkedCacheNative.close(directHandle)
            }

            val loaded = requireNotNull(directCache.load(name, sourceFile))
            assertArrayEquals(
                "Loaded direct MD FRB BookContent parity for $name",
                FrbModelCodec.encodeToBytes(BookContent.serializer(), canonicalFallback),
                FrbModelCodec.encodeToBytes(BookContent.serializer(), loaded),
            )
        }

        // 4. Markdown containing GFM tables now parses 100% natively in Rust (STATUS_OK / MD_FRB_STATUS_OK)
        // with bit-for-bit BookContent & FRB container parity against the Kotlin HtmlMapper fallback!
        val tableMd = """
            # Table Doc

            | Left | Center | Right | Default |
            | :--- | :---: | ---: | --- |
            | **A** 🐸 | *italic* | 42 | `code` |
            | [link](#table-doc) | ~~strike~~ | 100 | plain \| pipe |

            Single-column table:

            | Only |
            | --- |
            | Row 1 |
            | Row 2 |
        """.trimIndent()
        val tableBytes = tableMd.toByteArray(Charsets.UTF_8)
        val tableWire = requireNotNull(RustFb2TxtNative.parseMarkdownContent(tableBytes))
        assertEquals("TXT1 magic for GFM table", 'T'.code.toByte(), tableWire[0])
        assertEquals("GFM table must return STATUS_OK (0)", 0.toByte(), tableWire[4])

        val tableFile = tempFolder.newFile("gfm-table.md").apply { writeBytes(tableBytes) }
        val actualTableContent = TextBookParser.parseContent(tableFile, BookFormat.MD)
        val fallbackTableContent = TextBookParser.parseMarkdownMarkupFallback(tableBytes)
        assertEquals(1, actualTableContent.chapters.single().elements.filterIsInstance<ContentElement.Table>().size)
        val canonicalTableFallback = BookContent(
            chapters = fallbackTableContent.chapters,
            notes = fallbackTableContent.notes.toSortedMap(),
            linkTargets = fallbackTableContent.linkTargets.toSortedMap(),
            fonts = fallbackTableContent.fonts,
            language = fallbackTableContent.language,
            linkedDocuments = fallbackTableContent.linkedDocuments.toSortedMap(),
            linkedDocumentTargets = fallbackTableContent.linkedDocumentTargets.toSortedMap(),
            navigation = fallbackTableContent.navigation,
            publisherPublication = fallbackTableContent.publisherPublication,
            pageProgression = fallbackTableContent.pageProgression,
            epubSourceRanges = fallbackTableContent.epubSourceRanges,
        )
        assertEquals(
            "GFM table Chapter FRB parity",
            encodeBookChapters(canonicalTableFallback.chapters),
            encodeBookChapters(actualTableContent.chapters),
        )
        assertArrayEquals(
            "GFM table BookContent FRB wire parity",
            FrbModelCodec.encodeToBytes(BookContent.serializer(), canonicalTableFallback),
            FrbModelCodec.encodeToBytes(BookContent.serializer(), actualTableContent),
        )

        val tableRefCache = ChunkedContentCache(tempFolder.newFolder("md-table-ref-cache"))
        assertTrue(tableRefCache.save("gfm-table", tableFile, canonicalTableFallback))
        val tableRefEntry = tableRefCache.entryFile("gfm-table", complete = true)

        val tableDirectCache = ChunkedContentCache(tempFolder.newFolder("md-table-direct-cache"))
        val tableDirectEntry = tableDirectCache.entryFile("gfm-table", complete = true)
        val tableMeta = chunkedCacheSourceMetadata(tableFile)
        assertTrue("TextBookParser.writeMdFrb succeeded for GFM table", TextBookParser.writeMdFrb(tableFile, tableDirectEntry, tableMeta))
        assertTrue(tableDirectEntry.isFile)
        assertFalse(TextBookParser.writeMdFrb(tableFile, tableDirectEntry, tableMeta))

        val refTblHandle = RustChunkedCacheNative.openRead(tableRefEntry.path, tableMeta)
        val directTblHandle = RustChunkedCacheNative.openRead(tableDirectEntry.path, tableMeta)
        assertTrue(refTblHandle > 0L && directTblHandle > 0L)
        try {
            for (blockIndex in 0..2) {
                assertArrayEquals(
                    "GFM table FRB block $blockIndex byte parity",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refTblHandle, blockIndex)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directTblHandle, blockIndex)),
                )
            }
        } finally {
            RustChunkedCacheNative.close(refTblHandle)
            RustChunkedCacheNative.close(directTblHandle)
        }

        val loadedTable = requireNotNull(tableDirectCache.load("gfm-table", tableFile))
        assertArrayEquals(
            "Loaded direct GFM table MD FRB parity",
            FrbModelCodec.encodeToBytes(BookContent.serializer(), canonicalTableFallback),
            FrbModelCodec.encodeToBytes(BookContent.serializer(), loadedTable),
        )
    }

    @Test
    fun `writeTxtFrb writes direct FRB v2 container matching ChunkedContentCache and TextBookParser`() {
        requireRust()
        val ukSample = buildString {
            append("Перший абзац українською мовою про їжака, ґанок та єнота в лісі.\r\n")
            append("Другий рядок першого абзацу з додатковим українським текстом про їжаків.\r\n\r\n")
            append("  Повторюваний короткий абзац.\n\n")
            append("  Повторюваний короткий абзац.\n\n")
            append("Довгий абзац українською мовою понад сто двадцять вісім байтів для перевірки прямого запису рядка без словника: ")
            append("0123456789".repeat(8))
        }
        val cp1251Sample = (
            "Привет, мир! Это проверочный текст на русском языке для определения языка.\r\n" +
                "Вторая строка первого абзаца.\r\n\r\n" +
                "Второй абзац со старой кириллицей — тире и «кавычки».\rТретья строка\u000cКонец."
            ).toByteArray(Charset.forName("windows-1251"))
        val asciiSample = "  <b>Literal & text</b>\r\n# Not a heading\r\n   \r\nSecond paragraph.\n\nSecond paragraph."

        val cases = mutableListOf(
            "uk-utf8" to ukSample.toByteArray(Charsets.UTF_8),
            "ru-cp1251" to cp1251Sample,
            "en-ascii" to asciiSample.toByteArray(Charsets.UTF_8),
        )
        val fixtureTxt = LocalBookFixtures.find("text-formats/FrogReader-TXT-Formatting-Test.txt")
        if (fixtureTxt.isFile) {
            cases += "fixture-txt" to fixtureTxt.readBytes()
        }

        for ((name, rawBytes) in cases) {
            val sourceFile = tempFolder.newFile("$name.txt").apply { writeBytes(rawBytes) }
            val expectedContent = TextBookParser.parseContent(sourceFile, BookFormat.TXT)

            val refCache = ChunkedContentCache(tempFolder.newFolder("ref-cache-$name"))
            assertTrue("refCache.save for $name", refCache.save(name, sourceFile, expectedContent))
            val refEntry = refCache.entryFile(name, complete = true)

            val directCache = ChunkedContentCache(tempFolder.newFolder("direct-cache-$name"))
            val directEntry = directCache.entryFile(name, complete = true)
            val sourceMetadata = chunkedCacheSourceMetadata(sourceFile)

            val wrote = RustFb2TxtNative.writeTxtFrb(rawBytes, directEntry.path, sourceMetadata, null)
            assertTrue("writeTxtFrb succeeded for $name", wrote)
            assertTrue("direct FRB exists for $name", directEntry.isFile)
            // Must refuse existing destination without truncating it.
            val beforeBytes = directEntry.readBytes()
            assertFalse(RustFb2TxtNative.writeTxtFrb(rawBytes, directEntry.path, sourceMetadata, null))
            assertArrayEquals(beforeBytes, directEntry.readBytes())

            // Verify raw block-by-block FRB v2 revision-5 parity (Block 0, Block 1, Block 2).
            val refHandle = RustChunkedCacheNative.openRead(refEntry.path, sourceMetadata)
            val directHandle = RustChunkedCacheNative.openRead(directEntry.path, sourceMetadata)
            assertTrue(refHandle > 0L && directHandle > 0L)
            try {
                assertArrayEquals(
                    "Block 0 (BookIndex) bytes match for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 0)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 0)),
                )
                assertArrayEquals(
                    "Block 1 (global BookContent) bytes match for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 1)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 1)),
                )
                val directChapterBlock = requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 2))
                assertArrayEquals(
                    "Block 2 (Chapter) bytes match for $name",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 2)),
                    directChapterBlock,
                )
                val decodedIndex = FrbModelCodec.decode(
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 0)),
                    BookIndex.serializer(),
                )
                val decodedGlobals = FrbModelCodec.decode(
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 1)),
                    BookContent.serializer(),
                )
                val decodedChapter = FrbModelCodec.decode(directChapterBlock, Chapter.serializer())
                assertEquals(expectedContent.language, decodedIndex.language)
                assertEquals(expectedContent.language, decodedGlobals.language)
                assertEquals(expectedContent.chapters.single().elements.size, decodedIndex.totalElements)
                assertEquals(encodeChapter(expectedContent.chapters.single()), encodeChapter(decodedChapter))
            } finally {
                RustChunkedCacheNative.close(refHandle)
                RustChunkedCacheNative.close(directHandle)
            }

            // Verify opening via ChunkedContentCache.openIndexed and ChunkedContentCache.load.
            val refSession = requireNotNull(refCache.openIndexed(name, sourceFile))
            val directSession = requireNotNull(directCache.openIndexed(name, sourceFile))
            refSession.use { ref ->
                directSession.use { direct ->
                    assertEquals("BookIndex parity for $name", ref.index, direct.index)
                    val directGlobals = requireNotNull(direct.readGlobals())
                    assertEquals(expectedContent.language, directGlobals.language)
                    val directChapter = requireNotNull(direct.readChapter(0))
                    assertEquals(encodeChapter(expectedContent.chapters.single()), encodeChapter(directChapter))
                }
            }
            val loaded = requireNotNull(directCache.load(name, sourceFile))
            assertEquals(expectedContent.language, loaded.language)
            assertEquals(encodeBookChapters(expectedContent.chapters), encodeBookChapters(loaded.chapters))
        }

        // Verify TextBookParser.writeTxtFrb helper and explicit language override.
        val overrideSource = tempFolder.newFile("explicit-lang.txt").apply { writeText("Hello world\n\nSecond paragraph") }
        val overrideMeta = chunkedCacheSourceMetadata(overrideSource)
        val overrideCache = ChunkedContentCache(tempFolder.newFolder("override-cache"))
        val overrideDest = overrideCache.entryFile("explicit-lang", complete = true)
        assertTrue(TextBookParser.writeTxtFrb(overrideSource, overrideDest, overrideMeta, language = "en"))
        val overrideLoaded = requireNotNull(overrideCache.load("explicit-lang", overrideSource))
        assertEquals("en", overrideLoaded.language)
        assertEquals(
            listOf("Hello world", "Second paragraph"),
            overrideLoaded.chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().map { it.text.text },
        )

        // Blank/invalid TXT must return false without creating the file.
        val badDest = File(tempFolder.root, "should-not-exist.frb")
        assertFalse(RustFb2TxtNative.writeTxtFrb("   \n\t ".toByteArray(), badDest.path, overrideMeta, null))
        assertFalse(badDest.exists())
    }

    // -------------------------------------------------------------------------
    // M4.2: Native FB2 encoding detection, streaming sanitizer, structural scan
    // -------------------------------------------------------------------------

    @Test
    fun `native streaming FB2 metadata and cover parser extracts all fields and skips unrelated binaries`() {
        requireRust()
        val coverPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val coverB64 = Base64.getEncoder().encodeToString(coverPng)
        val brokenBody = "<p>Тор & Локи &mdash; " + "Длинный сломанный текст. ".repeat(200) + "</p>"
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <genre>sf_fantasy</genre>
                  <genre>prose_classic</genre>
                  <author><first-name>Стивен</first-name><middle-name>Эдвин</middle-name><last-name>Кинг</last-name></author>
                  <author><nickname>Соавтор &amp; Друг</nickname></author>
                  <book-title>Тёмная&nbsp;башня &mdash; &lt;Том 7&gt;</book-title>
                  <annotation><p>Первый   абзац
                    аннотации &amp; &lt;B&gt;.</p><empty-line/><p>Второй &laquo;абзац&raquo;.</p></annotation>
                  <date value="2004">2004</date>
                  <coverpage><image l:href="#cover.png"/></coverpage>
                  <lang>ru</lang>
                  <src-lang>en</src-lang>
                  <translator><first-name>Виктор</first-name><last-name>Вебер</last-name></translator>
                  <sequence name="Тёмная башня" number="7.5"/>
                </title-info>
                <src-title-info>
                  <genre>foreign_sf</genre>
                  <author><first-name>Stephen</first-name><last-name>King</last-name></author>
                  <book-title>The Dark Tower</book-title>
                  <annotation><p>Must not override.</p></annotation>
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
                <custom-info info-type="archive-source">Lib.ru &amp; Flibusta</custom-info>
              </description>
              <body><section>$brokenBody</section></body>
              <binary id="skip-before.png" content-type="image/png">NOT_VALID_BASE64_!@#</binary>
              <binary id="cover.png" content-type="image/png">${coverB64.take(8)}
            ${coverB64.drop(8)}</binary>
              <binary id="skip-after.png" content-type="image/png">ALSO_INVALID_!@#</binary>
            </FictionBook>
        """.trimIndent()
        val bytes = xml.toByteArray(Charset.forName("windows-1251"))

        val streamed = Fb2Parser.parseMetadata { bytes.inputStream() }
        val oneShotWire = requireNotNull(
            RustFb2TxtNative.parseFb2Metadata(bytes, ReaderResourceLimits.DEFAULT.maxCoverBytes),
        )
        val oneShot = Fb2Parser.decodeMetadataWire(oneShotWire)

        for (meta in listOf(streamed, oneShot)) {
            assertEquals("Тёмная\u00a0башня — <Том 7>", meta.title)
            assertEquals("Стивен Эдвин Кинг", meta.author)
            assertEquals(listOf("Стивен Эдвин Кинг", "Соавтор & Друг"), meta.authors)
            assertEquals(listOf("sf_fantasy", "prose_classic"), meta.genres)
            assertEquals("Тёмная башня", meta.series)
            assertEquals(7.5f, requireNotNull(meta.seriesNumber), 0.0001f)
            assertEquals(listOf("Виктор Вебер"), meta.translators)
            assertEquals("АСТ", meta.publisher)
            assertEquals("2005", meta.year)
            assertEquals("5-17-030411-1", meta.isbn)
            assertEquals("Первый абзац аннотации & <B>.\nВторой «абзац».", meta.description)
            assertEquals("ru", meta.language)
            assertArrayEquals(coverPng, meta.coverBytes)
            assertEquals(mapOf("archive-source" to "Lib.ru & Flibusta"), meta.additionalMetadata)
        }

        // Verify chunked streaming stops early (status == 2) right after </binary> of cover.png.
        val handle = RustFb2TxtNative.openMetadataParse(ReaderResourceLimits.DEFAULT.maxCoverBytes)
        assertTrue(handle > 0L)
        try {
            var offset = 0
            var stoppedEarly = false
            while (offset < bytes.size) {
                val len = minOf(19, bytes.size - offset)
                val slice = bytes.copyOfRange(offset, offset + len)
                val status = RustFb2TxtNative.feedMetadataParse(handle, slice, len, false)
                assertTrue("status must be 1 or 2", status == 1 || status == 2)
                offset += len
                if (status == 2) {
                    stoppedEarly = true
                    break
                }
            }
            assertTrue("Expected early stop before reading trailing skipped binaries", stoppedEarly)
            assertTrue("Expected unread trailing bytes after cover", offset < bytes.size)
            val finished = Fb2Parser.decodeMetadataWire(requireNotNull(RustFb2TxtNative.finishMetadataParse(handle)))
            assertArrayEquals(coverPng, finished.coverBytes)
        } finally {
            RustFb2TxtNative.closeMetadataParse(handle)
        }

        // Verify maxCoverBytes drops oversized covers while preserving all other metadata.
        val capped = Fb2Parser.parseMetadata(
            open = { bytes.inputStream() },
            limits = ReaderResourceLimits(maxCoverBytes = 4L),
        )
        assertEquals("Тёмная\u00a0башня — <Том 7>", capped.title)
        assertNull(capped.coverBytes)
    }

    @Test
    fun `native decodeMimeBase64 handles single-line, multi-line, padding variations, limits, and malformed inputs`() {
        requireRust()

        // Valid single-line and multi-line MIME Base64 payloads across all remainder lengths.
        val payloads = listOf(
            ByteArray(0),
            byteArrayOf(0x00),
            byteArrayOf(0x89.toByte(), 0x50),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E),
            ByteArray(257) { it.toByte() },
        )
        for (expected in payloads) {
            val singleLine = Base64.getEncoder().encodeToString(expected).toByteArray(Charsets.US_ASCII)
            val multiLine = Base64.getMimeEncoder(16, "\r\n".toByteArray(Charsets.US_ASCII))
                .encode(expected)
            val spaced = ("  " + multiLine.toString(Charsets.US_ASCII).replace("\r\n", " \t\n ") + "\r\n")
                .toByteArray(Charsets.US_ASCII)

            assertArrayEquals(expected, RustFb2TxtNative.decodeMimeBase64(singleLine, expected.size.toLong()))
            assertArrayEquals(expected, RustFb2TxtNative.decodeMimeBase64(multiLine, expected.size.toLong()))
            assertArrayEquals(expected, RustFb2TxtNative.decodeMimeBase64(spaced, expected.size.toLong()))

            if (expected.isNotEmpty()) {
                assertNull(
                    "Oversized payload (${expected.size} bytes) must return null at limit ${expected.size - 1}",
                    RustFb2TxtNative.decodeMimeBase64(singleLine, (expected.size - 1).toLong()),
                )
            }
        }

        // Padding variations (standard, split across lines, unpadded 2-char and 3-char tails).
        assertArrayEquals(byteArrayOf('f'.code.toByte()), RustFb2TxtNative.decodeMimeBase64("Zg==".toByteArray(), 16))
        assertArrayEquals(byteArrayOf('f'.code.toByte()), RustFb2TxtNative.decodeMimeBase64("Zg=\n=".toByteArray(), 16))
        assertArrayEquals(byteArrayOf('f'.code.toByte()), RustFb2TxtNative.decodeMimeBase64("Zg".toByteArray(), 16))
        assertArrayEquals("fo".toByteArray(), RustFb2TxtNative.decodeMimeBase64("Zm8=".toByteArray(), 16))
        assertArrayEquals("fo".toByteArray(), RustFb2TxtNative.decodeMimeBase64("Zm8".toByteArray(), 16))
        assertArrayEquals(ByteArray(0), RustFb2TxtNative.decodeMimeBase64(" \t\r\n ".toByteArray(), 0))

        // Negative maxDecodedBytes and oversized encoded input must return null.
        assertNull(RustFb2TxtNative.decodeMimeBase64("AA==".toByteArray(), -1L))
        assertNull(RustFb2TxtNative.decodeMimeBase64("AA==".toByteArray(), 0L))
        assertNull(RustFb2TxtNative.decodeMimeBase64(" ".repeat(4096).toByteArray(), 1L))

        // Malformed inputs: non-Base64 characters, trailing single quantum, invalid padding.
        for (bad in listOf(
            "!",
            "Zm9v!YmFy",
            "NOT_VALID_BASE64_!@#",
            "A",
            "Zm9vA",
            "=",
            "A=",
            "Zg=",
            "Zg===",
            "Zm8==",
            "Zg==Zg==",
            "Z=g=",
        )) {
            assertNull(
                "Malformed Base64 '$bad' must return null",
                RustFb2TxtNative.decodeMimeBase64(bad.toByteArray(Charsets.UTF_8), 64L),
            )
        }
    }

    @Test
    fun `native XML encoding detector and streaming sanitizer handle legacy Cyrillic encodings and broken XML`() {
        requireRust()
        val encodings = listOf(
            "windows-1251" to Charset.forName("windows-1251"),
            "koi8-r" to Charset.forName("KOI8-R"),
            "iso-8859-5" to Charset.forName("ISO-8859-5"),
            "utf-16" to Charsets.UTF_16,
            "utf-8" to Charsets.UTF_8,
        )

        for ((declared, charset) in encodings) {
            val xml = """
                <?xml version="1.0" encoding="$declared"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                  <stylesheet type="text/css"><![CDATA[p { color: #112233; } /* a & b */]]></stylesheet>
                  <description><title-info>
                    <author><first-name>Иван</first-name><last-name>Тестов</last-name></author>
                    <book-title>Книга&nbsp;&mdash; тест</book-title>
                    <coverpage><image l:href="#cover.png"/></coverpage>
                    <lang>ru</lang>
                  </title-info></description>
                  <body>
                    <section>
                      <title><p>Глава &laquo;Первая&raquo;&hellip;</p></title>
                      <p>Тор & Локи &ndash; проверка&#160;сущностей.</p>
                    </section>
                  </body>
                  <binary id="cover.png" content-type="image/png">AAECAw==</binary>
                </FictionBook>
            """.trimIndent()
            val bytes = xml.toByteArray(charset)

            val detected = RustFb2TxtNative.detectXmlEncoding(bytes.copyOf(minOf(bytes.size, 512)))
            assertNotNull(detected)

            // Verify chunked streaming sanitization via RustFb2TxtNative directly (5-byte chunks).
            val sanitized = sanitizeInChunks(bytes, chunkSize = 5)
            val sanitizedText = sanitized.toString(Charsets.UTF_8)
            assertTrue("prolog rewritten for $declared", sanitizedText.contains("encoding=\"utf-8\""))
            assertTrue("CDATA ampersand preserved", sanitizedText.contains("<![CDATA[p { color: #112233; } /* a & b */]]>"))
            assertTrue("broken body repaired", sanitizedText.contains("Тор &amp; Локи – проверка&#160;сущностей."))

            // Verify end-to-end Fb2Parser metadata + content recovery.
            val meta = Fb2Parser.parseMetadata { bytes.inputStream() }
            assertEquals("Книга\u00a0— тест", meta.title)
            assertEquals("Иван Тестов", meta.author)
            assertArrayEquals(byteArrayOf(0, 1, 2, 3), meta.coverBytes)

            val content = Fb2Parser.parseContent({ bytes.inputStream() }, tempFolder.newFolder())
            assertEquals("ru", content.language)
            val chapter = content.chapters.single()
            assertEquals("Глава «Первая»…", chapter.title)
            val paragraph = chapter.elements.filterIsInstance<ContentElement.Paragraph>().single()
            assertEquals("Тор & Локи – проверка\u00a0сущностей.", paragraph.text.text)
        }
    }

    @Test
    fun `fast structural scan skips Base64 binary decoding and supports parity-exact targeted section opening`() {
        requireRust()
        val imgOne = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2)
        val imgTwo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 3, 4)
        val encOne = Base64.getEncoder().encodeToString(imgOne)
        val encTwo = Base64.getEncoder().encodeToString(imgTwo)

        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <stylesheet type="text/css">p { font-style: italic; }</stylesheet>
              <description><title-info><book-title>Targeted</book-title><lang>ru</lang></title-info></description>
              <body xml:lang="ru">
                <section id="part-1">
                  <title><p>Часть I</p></title>
                  <section id="ch-1">
                    <title><p>Глава 1</p></title>
                    <p>Первый текст с картинкой <image l:href="#one.png"/> и текстом.</p>
                  </section>
                  <section id="ch-2" xml:lang="uk">
                    <title><p>Глава 2</p><p>Цільова</p></title>
                    <p>Український &laquo;текст&raquo; & Приклад з картинкою.</p>
                    <image l:href="#two.png"/>
                  </section>
                </section>
              </body>
              <body name="notes">
                <section id="n1"><p>Примітка.</p></section>
              </body>
              <binary id="one.png" content-type="image/png">$encOne</binary>
              <binary id="two.png" content-type="image/png">$encTwo</binary>
              <binary id="corrupted.bin" content-type="image/png">NOT_VALID_BASE64_!@#</binary>
            </FictionBook>
        """.trimIndent()
        val bytes = xml.toByteArray(Charsets.UTF_8)

        // Structural scan must succeed even with an invalid Base64 payload in an unreferenced binary.
        val structure = Fb2Parser.scanStructure({ bytes.inputStream() })
        assertEquals("UTF-8", structure.detectedEncoding)
        assertEquals("ru", structure.bookLanguage)
        assertEquals(2, structure.bodies.size)
        assertEquals("notes", structure.bodies[1].name)
        assertEquals(4, structure.sections.size)
        assertEquals(2, structure.maxSectionDepth)

        val targetSection = structure.sections.single { it.id == "ch-2" }
        assertEquals(1, targetSection.depth)
        assertEquals("Глава 2\nЦільова", targetSection.titlePreview)
        assertEquals("uk", targetSection.lang)

        // Targeted opening of ch-2 must produce the exact same Chapter model as full parseContent,
        // while extracting ONLY #two.png and skipping #one.png and #corrupted.bin!
        val fullImagesDir = tempFolder.newFolder("full-img")
        val fullContent = Fb2Parser.parseContent({ bytes.inputStream() }, fullImagesDir)
        val fullChapter2 = fullContent.chapters.single { it.title == "Глава 2\nЦільова" }

        val targetedImagesDir = tempFolder.newFolder("targeted-img")
        val targetedContent = Fb2Parser.parseTargetedSections(
            open = { bytes.inputStream() },
            structure = structure,
            sectionIndices = listOf(targetSection.index),
            imagesDir = targetedImagesDir,
        )
        val targetedChapter2 = targetedContent.chapters.single()

        assertEquals(fullChapter2.title, targetedChapter2.title)
        assertEquals(fullChapter2.depth, targetedChapter2.depth)
        assertEquals(fullChapter2.elements.size, targetedChapter2.elements.size)

        // Verify only 1 image file (#two.png) was extracted during targeted opening.
        assertEquals(1, targetedImagesDir.listFiles()?.size ?: 0)
        assertEquals(2, fullImagesDir.listFiles()?.size ?: 0)

        val fullParagraph = fullChapter2.elements.filterIsInstance<ContentElement.Paragraph>().single()
        val targetedParagraph = targetedChapter2.elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(fullParagraph.text.text, targetedParagraph.text.text)
        assertEquals(fullParagraph.block, targetedParagraph.block)

        val targetedImage = targetedChapter2.elements.filterIsInstance<ContentElement.Image>().single()
        assertArrayEquals(imgTwo, File(targetedImage.path).readBytes())
    }

    @Test
    fun `real local FB2 books scan and target-open identically to full parse when present`() {
        requireRust()
        for (name in listOf("FrogReader_Test.fb2", "Tolkien_LOTR1_uk.fb2")) {
            val file = LocalBookFixtures.find(name)
            if (!file.isFile) continue
            val structure = Fb2Parser.scanStructure({ file.inputStream().buffered() })
            assertTrue("$name has sections", structure.sections.isNotEmpty())
            val full = Fb2Parser.parseContent({ file.inputStream().buffered() }, tempFolder.newFolder())

            // Pick a late section (~80% into main body sections).
            val mainSections = structure.sections.filter { it.bodyIndex == 0 && !it.titlePreview.isNullOrBlank() }
            val lateSection = mainSections[(mainSections.size * 4) / 5]
            val targeted = Fb2Parser.parseTargetedSections(
                open = { file.inputStream().buffered() },
                structure = structure,
                sectionIndices = listOf(lateSection.index),
                imagesDir = tempFolder.newFolder(),
            )
            val expectedChapter = full.chapters.first { it.title == lateSection.titlePreview }
            val actualChapter = targeted.chapters.first()
            assertEquals(expectedChapter.title, actualChapter.title)
            assertEquals(expectedChapter.depth, actualChapter.depth)
            assertEquals(expectedChapter.elements.size, actualChapter.elements.size)
        }
    }

    @Test
    fun `progressive FB2 parseContent emits parity-exact prefix before full parse across preambles, nested sections, and broken legacy XML`() {
        requireRust()
        val imgOne = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val imgTwo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 4, 5, 6)
        val encOne = Base64.getEncoder().encodeToString(imgOne)
        val encTwo = Base64.getEncoder().encodeToString(imgTwo)

        val ch1Body = (1..20).joinToString("") { "<p id=\"c1p$it\">Первый раздел &mdash; абзац $it.</p>" }
        val ch2Body = (1..22).joinToString("") { "<p id=\"c2p$it\">Второй раздел &laquo;текст&raquo; $it.</p>" }
        val ch3Body = (1..20).joinToString("") { "<p id=\"c3p$it\">Третий & проблемный $it.</p>" }
        val ch4Body = (1..20).joinToString("") { "<p id=\"c4p$it\">Четвёртый финальный $it.</p>" }
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <stylesheet type="text/css">p { text-indent: 1.2em; }</stylesheet>
              <description><title-info>
                <book-title>Прогрессивный FB2</book-title>
                <lang>ru</lang>
              </title-info></description>
              <body>
                <title><p>Книга тест</p></title>
                <epigraph><p>Общий эпиграф.</p></epigraph>
                <section id="part-1">
                  <title><p>Часть I</p></title>
                  <section id="ch-1">
                    <title><p>Глава 1</p></title>
                    <image l:href="#one.png"/>
                    $ch1Body
                  </section>
                  <section id="ch-2">
                    <title><p>Глава 2</p></title>
                    $ch2Body
                  </section>
                  <section id="ch-3">
                    <title><p>Глава 3</p></title>
                    $ch3Body
                  </section>
                  <section id="ch-4">
                    <title><p>Глава 4</p></title>
                    <image l:href="#two.png"/>
                    $ch4Body
                  </section>
                </section>
              </body>
              <body name="notes">
                <section id="n1"><p>Сноска.</p></section>
              </body>
              <binary id="one.png" content-type="image/png">$encOne</binary>
              <binary id="two.png" content-type="image/png">$encTwo</binary>
            </FictionBook>
        """.trimIndent()
        val bytes = xml.toByteArray(Charset.forName("windows-1251"))

        val baselineDir = tempFolder.newFolder("fb2-baseline")
        val baseline = Fb2Parser.parseContent({ bytes.inputStream() }, baselineDir)

        val previews = mutableListOf<BookContent>()
        val progressiveDir = tempFolder.newFolder("fb2-progressive")
        val full = Fb2Parser.parseContent(
            open = { bytes.inputStream() },
            imagesDir = progressiveDir,
            onPreview = { preview ->
                assertTrue(preview.totalElements >= 32)
                assertTrue(preview.totalElements < baseline.totalElements)
                // Only #one.png is referenced in the opening preview; #two.png must not be decoded yet.
                assertEquals(1, progressiveDir.listFiles()?.size ?: 0)
                previews += preview
            },
        )
        val preview = previews.single()
        assertPrefixParity(full, preview)
        assertEquals(
            encodeBookChapters(normalizeImageDir(baseline.chapters, baselineDir)),
            encodeBookChapters(normalizeImageDir(full.chapters, progressiveDir)),
        )
        val fullImages = full.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Image>()
        assertEquals(2, fullImages.size)
        assertArrayEquals(imgOne, File(fullImages[0].path).readBytes())
        assertArrayEquals(imgTwo, File(fullImages[1].path).readBytes())
        assertEquals(2, progressiveDir.listFiles()?.size ?: 0)

        // Saved position near element 28 must wait until >= 28 + 32 = 60 elements are mapped.
        val latePreviews = mutableListOf<BookContent>()
        val lateFull = Fb2Parser.parseContent(
            open = { bytes.inputStream() },
            imagesDir = tempFolder.newFolder("fb2-late"),
            onPreview = latePreviews::add,
            previewElementIndex = 28,
        )
        val latePreview = latePreviews.single()
        assertTrue(latePreview.totalElements >= 60)
        assertTrue(latePreview.totalElements < lateFull.totalElements)
        assertPrefixParity(lateFull, latePreview)

        // Unreachable previewElementIndex must never overflow or emit a premature preview.
        val unreachablePreviews = mutableListOf<BookContent>()
        val unreachableFull = Fb2Parser.parseContent(
            open = { bytes.inputStream() },
            imagesDir = tempFolder.newFolder("fb2-unreachable"),
            onPreview = unreachablePreviews::add,
            previewElementIndex = Int.MAX_VALUE,
        )
        assertTrue(unreachablePreviews.isEmpty())
        assertEquals(full.totalElements, unreachableFull.totalElements)
    }

    @Test
    fun `progressive FB2 degrades cleanly to full parse on sectionless or malformed files`() {
        requireRust()
        val sectionlessXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>No Sections</book-title><lang>en</lang></title-info></description>
              <body>
                ${(1..40).joinToString("") { "<p>Plain paragraph $it without sections.</p>" }}
              </body>
            </FictionBook>
        """.trimIndent()
        val previews = mutableListOf<BookContent>()
        val full = Fb2Parser.parseContent(
            open = { sectionlessXml.byteInputStream() },
            imagesDir = tempFolder.newFolder("fb2-sectionless"),
            onPreview = previews::add,
        )
        assertEquals(40, full.totalElements)
        assertTrue(previews.isEmpty())
    }

    @Test
    fun `real local FB2 books emit parity-exact progressive previews when present`() {
        requireRust()
        for (name in listOf(
            "FrogCompare.fb2",
            "Tolkien_LOTR1_uk.fb2",
            "King_-Tyomnaya-Bashnya-_7_Temnaya-Bashnya.KriKEA.328261.fb2",
        )) {
            val file = LocalBookFixtures.find(name)
            if (!file.isFile) continue
            val previews = mutableListOf<BookContent>()
            val full = Fb2Parser.parseContent(
                open = { file.inputStream().buffered() },
                imagesDir = tempFolder.newFolder("prog-$name"),
                onPreview = previews::add,
            )
            assertEquals("$name should emit one preview", 1, previews.size)
            val preview = previews.single()
            assertTrue("$name preview elements >= 32", preview.totalElements >= 32)
            assertTrue("$name preview smaller than full", preview.totalElements < full.totalElements)
            assertPrefixParity(full, preview)

            val deepPreviews = mutableListOf<BookContent>()
            val deepTarget = (full.totalElements * 3) / 4
            val deepFull = Fb2Parser.parseContent(
                open = { file.inputStream().buffered() },
                imagesDir = tempFolder.newFolder("deep-$name"),
                onPreview = deepPreviews::add,
                previewElementIndex = deepTarget,
            )
            assertEquals("$name deep preview emitted once", 1, deepPreviews.size)
            val deepPreview = deepPreviews.single()
            assertTrue(deepPreview.totalElements >= deepTarget + 32)
            assertPrefixParity(deepFull, deepPreview)
        }
    }

    @Test
    fun `BookRepository loadContentProgressively supports FB2 and delivers preview before completion`() = runBlocking {
        requireRust()
        val root = tempFolder.newFolder("repo-fb2")
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(root)
        given(context.cacheDir).willReturn(root)

        val paragraphs1 = (1..36).joinToString("") { "<p>Глава первая, абзац $it.</p>" }
        val paragraphs2 = (1..40).joinToString("") { "<p>Глава вторая, абзац $it.</p>" }
        val fb2Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Repo FB2</book-title><lang>ru</lang></title-info></description>
              <body>
                <section><title><p>Глава 1</p></title>$paragraphs1</section>
                <section><title><p>Глава 2</p></title>$paragraphs2</section>
              </body>
            </FictionBook>
        """.trimIndent()
        val book = Book(
            id = "fb2-progressive-book",
            title = "Repo FB2",
            author = "Author",
            format = BookFormat.FB2,
            fileName = "fb2-progressive-book.fb2",
            addedAtMillis = 1L,
            progress = ReadingProgress(elementIndex = 0),
        )
        StoreFixture.seed(root, listOf(book))
        File(root, "books").mkdirs()
        val bookFile = File(root, "books/${book.fileName}").apply { writeText(fb2Xml) }

        val repository = BookRepository(context = context, writePdfAnnotations = null)
        try {
            val previews = mutableListOf<BookContent>()
            val full = repository.loadContentProgressively(book) { previews += it }
            repository.awaitContentCacheWrites(book.id)

            assertTrue("Expected at least one progressive preview for FB2", previews.isNotEmpty())
            val preview = previews.first()
            assertTrue(preview.totalElements >= 32)
            assertTrue(preview.totalElements < full.totalElements)
            assertPrefixParity(full, preview)

            val expected = Fb2Parser.parseContent({ bookFile.inputStream().buffered() }, tempFolder.newFolder("fb2-repo-ref"))
            assertEquals(encodeBookChapters(expected.chapters), encodeBookChapters(full.chapters))
        } finally {
            DatabaseManager.closeAndDeleteForFilesRoot(root)
        }
    }

    @Test
    fun `native writeFb2Frb and parseContent(file) match ChunkedContentCache block-for-block`() {
        requireRust()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val svgBytes = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"/>""".toByteArray(Charsets.UTF_8)
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <stylesheet type="text/css"><![CDATA[
                body { color: #123456; line-height: 1.5; }
                p { text-indent: 1.25em; }
                .accent { font-style: italic; text-decoration: underline; }
                table { border: 1px solid blue; }
                td { border-bottom: 1px dashed red; }
              ]]></stylesheet>
              <description><title-info>
                <book-title>Прямой FRB FB2</book-title>
                <lang>ru</lang>
              </title-info></description>
              <body>
                <title><p>Книга тест</p></title>
                <epigraph><p>Эпиграф &mdash; проверка &amp; ремонт & сущностей.</p><text-author>Автор</text-author></epigraph>
                <section id="ch1">
                  <title><p>Глава <strong>1</strong></p></title>
                  <p><image l:href="#inline.svg" alt="Initial"/>Текст со <style name="accent">стилем</style> и <a l:href="#n1" type="note">[1]</a> и <a l:href="#ch2">ссылкой</a>.</p>
                  <image l:href="#plate.png" alt="Plate"/>
                  <table>
                    <tr><th>Колонка 1</th><th>Колонка 2</th></tr>
                    <tr><td>A</td><td>B</td></tr>
                  </table>
                </section>
                <section id="ch2">
                  <title><p>Глава 2</p></title>
                  <poem><stanza><v>Первая строка</v><v><emphasis>Вторая строка</emphasis></v></stanza></poem>
                </section>
              </body>
              <body name="notes">
                <section id="n1"><title><p>1</p></title><p>Текст примечания.</p></section>
              </body>
              <binary id="plate.png" content-type="image/png">${Base64.getEncoder().encodeToString(pngBytes)}</binary>
              <binary id="inline.svg" content-type="image/svg+xml">${Base64.getEncoder().encodeToString(svgBytes)}</binary>
              <binary id="unused.bin" content-type="image/png">NOT_VALID_BASE64_!@#</binary>
            </FictionBook>
        """.trimIndent()
        val sourceFile = tempFolder.newFile("direct-fb2.fb2").apply {
            writeBytes(xml.toByteArray(Charset.forName("windows-1251")))
        }

        // Verify File-based parseContent matches stream-based parseContent byte-for-byte.
        val sharedImagesDir = tempFolder.newFolder("fb2-shared-imgs")
        val fromStream = Fb2Parser.parseContent({ sourceFile.inputStream().buffered() }, sharedImagesDir)
        val fromFile = Fb2Parser.parseContent(sourceFile, sharedImagesDir)
        assertEquals(encodeBookContent(fromStream), encodeBookContent(fromFile))
        assertEquals(2, sharedImagesDir.listFiles()?.size ?: 0)

        // Write reference .frb via ChunkedContentCache.save and direct .frb via Fb2Parser.writeFb2Frb
        // using the SAME imagesDir so extracted resource paths are byte-for-byte identical.
        val refCache = ChunkedContentCache(tempFolder.newFolder("fb2-ref-cache"))
        assertTrue(refCache.save("fb2-direct", sourceFile, fromFile))
        val refEntry = refCache.entryFile("fb2-direct", complete = true)

        val directCache = ChunkedContentCache(tempFolder.newFolder("fb2-direct-cache"))
        val directEntry = directCache.entryFile("fb2-direct", complete = true)
        val sourceMetadata = chunkedCacheSourceMetadata(sourceFile)
        val directImagesDir = tempFolder.newFolder("fb2-direct-imgs")

        // Also test fresh extraction into empty directImagesDir.
        val freshDirectEntry = File(tempFolder.newFolder("fb2-fresh-frb"), "fresh.frb")
        assertTrue(Fb2Parser.writeFb2Frb(sourceFile, directImagesDir, freshDirectEntry, sourceMetadata))
        assertEquals(2, directImagesDir.listFiles()?.size ?: 0)

        assertTrue(Fb2Parser.writeFb2Frb(sourceFile, sharedImagesDir, directEntry, sourceMetadata))
        assertTrue(directEntry.isFile)

        // Must refuse overwriting an existing .frb without truncating it.
        val existingBytes = directEntry.readBytes()
        assertFalse(Fb2Parser.writeFb2Frb(sourceFile, sharedImagesDir, directEntry, sourceMetadata))
        assertArrayEquals(existingBytes, directEntry.readBytes())

        // Verify raw block-by-block FRB v2 parity (Block 0 BookIndex, Block 1 Globals, Blocks 2..N Chapters).
        val refHandle = RustChunkedCacheNative.openRead(refEntry.path, sourceMetadata)
        val directHandle = RustChunkedCacheNative.openRead(directEntry.path, sourceMetadata)
        assertTrue(refHandle > 0L && directHandle > 0L)
        try {
            assertArrayEquals(
                "Block 0 (BookIndex) bytes match",
                requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 0)),
                requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 0)),
            )
            assertArrayEquals(
                "Block 1 (BookGlobals) bytes match",
                requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, 1)),
                requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, 1)),
            )
            for (chIdx in fromFile.chapters.indices) {
                assertArrayEquals(
                    "Block ${chIdx + 2} (Chapter $chIdx) bytes match",
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(refHandle, chIdx + 2)),
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(directHandle, chIdx + 2)),
                )
            }
        } finally {
            RustChunkedCacheNative.close(refHandle)
            RustChunkedCacheNative.close(directHandle)
        }

        // Verify opening via ChunkedContentCache.openIndexed and load.
        val loaded = requireNotNull(directCache.load("fb2-direct", sourceFile))
        assertEquals(encodeBookContent(fromFile), encodeBookContent(loaded))

        // Verify ReaderResourceLimits enforcement throws ResourceLimitException without creating destination.
        val limitDest = File(tempFolder.root, "fb2-limit-should-not-exist.frb")
        val limitErr = assertThrows(com.example.frogreader.data.parser.ResourceLimitException::class.java) {
            Fb2Parser.writeFb2Frb(
                sourceFile = sourceFile,
                imagesDir = tempFolder.newFolder("fb2-limit-imgs"),
                destFrbFile = limitDest,
                sourceMetadata = sourceMetadata,
                limits = ReaderResourceLimits(maxFb2Bytes = 64L),
            )
        }
        assertEquals(com.example.frogreader.data.parser.ResourceLimitKind.ENTRY_SIZE, limitErr.kind)
        assertFalse(limitDest.exists())
    }

    private fun assertPrefixParity(full: BookContent, preview: BookContent) {
        assertTrue(preview.chapters.isNotEmpty())
        assertTrue(preview.chapters.size <= full.chapters.size)
        for (idx in 0 until preview.chapters.lastIndex) {
            assertEquals(
                "Chapter $idx mismatch between preview and full",
                encodeChapter(full.chapters[idx]),
                encodeChapter(preview.chapters[idx]),
            )
        }
        val lastIdx = preview.chapters.lastIndex
        assertEquals(full.chapters[lastIdx].title, preview.chapters[lastIdx].title)
        assertEquals(full.chapters[lastIdx].depth, preview.chapters[lastIdx].depth)
        assertTrue(preview.chapters[lastIdx].elements.size <= full.chapters[lastIdx].elements.size)

        val previewElements = preview.chapters.flatMap { it.elements }
        val fullPrefixElements = full.chapters.flatMap { it.elements }.take(previewElements.size)
        assertEquals(
            encodeElements(fullPrefixElements),
            encodeElements(previewElements),
        )
        assertEquals(full.language, preview.language)
        for ((key, target) in preview.linkTargets) {
            assertEquals("Link target $key mismatch", target, full.linkTargets[key])
        }
    }

    private fun encodeBookContent(content: BookContent): List<Byte> {
        val out = ByteArrayOutputStream()
        FrbModelCodec.encode(out, BookContent.serializer(), content)
        return out.toByteArray().toList()
    }

    private fun encodeChapter(chapter: Chapter): List<Byte> {
        val out = ByteArrayOutputStream()
        FrbModelCodec.encode(out, Chapter.serializer(), chapter)
        return out.toByteArray().toList()
    }

    private fun normalizeImageDir(chapters: List<Chapter>, imagesDir: File): List<Chapter> {
        val prefix = imagesDir.absolutePath
        return chapters.map { chapter ->
            val normalizedElements = chapter.elements.map { element ->
                if (element is ContentElement.Image && element.path.startsWith(prefix)) {
                    element.copy(path = "<images>" + element.path.removePrefix(prefix))
                } else {
                    element
                }
            }
            Chapter(chapter.title, normalizedElements, chapter.depth, chapter.publisherBoxes)
        }
    }

    private fun encodeBookChapters(chapters: List<Chapter>): List<List<Byte>> = chapters.map(::encodeChapter)

    private fun encodeElements(elements: List<ContentElement>): List<List<Byte>> = elements.map { element ->
        val out = ByteArrayOutputStream()
        FrbModelCodec.encode(out, ContentElement.serializer(), element)
        out.toByteArray().toList()
    }

    private fun sanitizeInChunks(bytes: ByteArray, chunkSize: Int): ByteArray {
        val handle = RustFb2TxtNative.openSanitizer(true)
        assertTrue(handle > 0L)
        val out = ByteArrayOutputStream()
        try {
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                val slice = bytes.copyOfRange(offset, offset + len)
                val produced = requireNotNull(RustFb2TxtNative.feedSanitizer(handle, slice, len, false))
                out.write(produced)
                offset += len
            }
            val tail = requireNotNull(RustFb2TxtNative.feedSanitizer(handle, ByteArray(0), 0, true))
            out.write(tail)
        } finally {
            RustFb2TxtNative.closeSanitizer(handle)
        }
        return out.toByteArray()
    }

    private fun chunkedCacheSourceMetadata(source: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(8) // ChunkedContentCache.MODEL_VERSION
                it.writeByte(1) // EntryKind.COMPLETE
                it.writeUTF(source.canonicalPath)
                it.writeLong(source.length())
                it.writeLong(source.lastModified())
                it.write(digest)
            }
        }.toByteArray()
    }

    private fun referenceMarkdownBody(text: String): org.jsoup.nodes.Element {
        val maxDepth = 128
        val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
        val parser = Parser.builder().extensions(extensions)
            .maxOpenBlockParsers(maxDepth + 1)
            .maxInlineNesting(maxDepth + 1)
            .build()
        val renderer = HtmlRenderer.builder().extensions(extensions)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build()
        val document = parser.parse(text)
        val pending = java.util.ArrayDeque<Pair<Node, Int>>()
        pending.add(document to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > maxDepth) throw IOException("Markdown nesting is too deep.")
            var child = node.firstChild
            while (child != null) {
                pending.add(child to depth + 1)
                child = child.next
            }
        }
        return Jsoup.parseBodyFragment(renderer.render(document)).body()
    }

    private fun referenceParseMarkdownMetadata(text: String): BookMetadata {
        val title = referenceMarkdownBody(text).selectFirst("h1, h2, h3, h4, h5, h6")
            ?.text()?.takeIf { it.isNotBlank() }
        return BookMetadata(title = title, author = null, coverBytes = null)
    }

    private fun referenceParseMarkdownContent(text: String): BookContent {
        val body = referenceMarkdownBody(text)
        val headings = body.select("h1, h2, h3, h4, h5, h6")
        if (headings.isNotEmpty()) {
            val slugs = requireNotNull(TextBookParser.headingSlugsNative(headings.map { it.text() }))
            headings.forEachIndexed { index, heading ->
                heading.attr("id", slugs[index])
            }
        }
        body.select("img").forEach { it.replaceWith(TextNode(it.attr("alt"))) }
        val mapper = HtmlMapper(resolveImage = { null }, resolveLink = { href ->
            if (href.startsWith('#')) {
                runCatching { "#" + java.net.URI(href).fragment }.getOrDefault(href)
            } else {
                null
            }
        })
        val elements = mapper.map(body)
        if (elements.isEmpty()) throw IOException("The Markdown document has no readable content.")
        val title = elements.filterIsInstance<ContentElement.Heading>().firstOrNull()?.text
        val chapters = listOf(Chapter(title, elements, publisherBoxes = mapper.publisherBoxes.toList()))
        val navigation = elements.mapIndexedNotNull { index, element ->
            (element as? ContentElement.Heading)?.let {
                BookNavigationEntry(it.text, it.level - 1, BookNavigationTarget.ReadingOrder(0, index))
            }
        }
        return BookContent(
            chapters = chapters,
            navigation = navigation,
            linkTargets = mapper.anchors.filterValues { it < elements.size }.mapKeys { "#${it.key}" }
                .mapValues { 0 to it.value },
            language = LanguageTag.detectFromChapters(chapters),
        )
    }
}
