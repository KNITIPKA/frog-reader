package com.example.frogreader.data

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.TextBookParser
import com.example.frogreader.engine.RustBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

/** Exercises the native container, not a mock of chapter access. */
class IndexedContentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = false; classDiscriminator = "kind" }
    private val source: File get() = File(temporary.root, "source.epub").apply {
        if (!exists()) writeText("Immutable complete publication")
    }
    private fun cache() = ChunkedContentCache(File(temporary.root, "cache"))

    @Test fun `manifest retains complete navigation and exact sparse chapter coordinates`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val cache = cache()
        val original = richBook()
        assertTrue(cache.save("book", source, original))
        requireNotNull(cache.openIndexed("book", source)).use { session ->
            val index = session.index
            assertEquals(listOf("Empty", "Part one", "Empty middle", "Last chapter"), index.chapters.map { it.title })
            assertEquals(listOf(0, 1, 1, 2), index.chapters.map { it.depth })
            assertEquals(listOf(0, 0, 2, 2), index.chapters.map { it.startElement })
            assertEquals(listOf(0, 2, 0, 1), index.chapters.map { it.elementCount })
            assertEquals(3, index.totalElements)
            assertEquals(original.linkTargets, index.linkTargets)
            assertEquals(original.linkedDocumentTargets, index.linkedDocumentTargets)
            assertEquals(original.fonts, index.fonts)
            assertEquals(original.language, index.language)
            assertEquals(original.pageProgression, index.pageProgression)
            assertEquals(navigationJson(original.navigation), navigationJson(index.navigation))
            assertEquals(
                json.encodeToString(PublisherStyle.serializer(), requireNotNull(publisherStyleOf(original))),
                json.encodeToString(PublisherStyle.serializer(), requireNotNull(index.publisherStyle)),
            )
            // Destination chapter 3 remains addressable without reading 0..2.
            assertChapterEquals(original.chapters[3], requireNotNull(session.readChapter(3)))
            assertChapterEquals(original.chapters[0], requireNotNull(session.readChapter(0)))
            assertNull(session.readChapter(-1))
            assertNull(session.readChapter(original.chapters.size))
            val globals = requireNotNull(session.readGlobals())
            assertTrue(globals.chapters.isEmpty())
            assertEquals(globalsJson(original), json.encodeToString(BookContent.serializer(), globals))
        }
    }

    @Test fun `empty publication has a valid manifest and globals without sentinel chapters`() {
        val cache = cache()
        val original = BookContent(emptyList(), language = "uk")
        assertTrue(cache.save("empty", source, original))
        requireNotNull(cache.openIndexed("empty", source)).use { session ->
            assertTrue(session.index.chapters.isEmpty())
            assertEquals(0, session.index.totalElements)
            assertNull(session.readChapter(0))
            assertEquals(globalsJson(original), json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())))
        }
    }

    @Test fun `distant chapter and full TOC load with an unread corrupt preceding chapter`() {
        val cache = cache()
        val original = richBook()
        assertTrue(cache.save("book", source, original))
        corruptPayload(cache.entryFile("book"), block = 3) // chapter 1; prefixes occupy blocks 0 and 1
        requireNotNull(cache.openIndexed("book", source)).use { session ->
            assertEquals(navigationJson(original.navigation), navigationJson(session.index.navigation))
            assertEquals(original.totalElements, session.index.totalElements)
            assertChapterEquals(original.chapters.last(), requireNotNull(session.readChapter(3)))
            assertNull("The damaged block must fail independently", session.readChapter(1))
            assertChapterEquals(original.chapters.last(), requireNotNull(session.readChapter(3)))
        }
        assertNull("A complete load must still reject the incomplete model", cache.load("book", source))
        assertTrue("A cache miss must not disable healthy native operations", RustBridge.shared.isAvailable)
    }

    @Test fun `lightweight manifest and target chapter do not decode heavy global tables`() {
        val cache = cache()
        val original = richBook()
        assertTrue(cache.save("book", source, original))
        corruptPayload(cache.entryFile("book"), block = 1)
        requireNotNull(cache.openIndexed("book", source)).use { session ->
            assertEquals(navigationJson(original.navigation), navigationJson(session.index.navigation))
            assertChapterEquals(original.chapters.last(), requireNotNull(session.readChapter(3)))
            assertNull("A corrupt notes/linked-documents block is detected when requested", session.readGlobals())
        }
        assertNull(cache.load("book", source))
    }

    @Test fun `corrupt manifest cannot expose untrusted chapter coordinates`() {
        val cache = cache()
        assertTrue(cache.save("book", source, richBook()))
        corruptPayload(cache.entryFile("book"), block = 0)
        assertNull(cache.openIndexed("book", source))
    }

    @Test fun `old container version is invalidated rather than retained as another decoder`() {
        val cache = cache()
        assertTrue(cache.save("book", source, richBook()))
        val file = cache.entryFile("book")
        val bytes = file.readBytes()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, view.getInt(8))
        view.putInt(8, 1)
        view.putInt(56, 0)
        val headerCrc = CRC32().apply { update(bytes, 0, 64) }.value.toInt()
        view.putInt(56, headerCrc)
        file.writeBytes(bytes)
        assertNull(cache.openIndexed("book", source))
        assertNull(cache.load("book", source))
    }

    @Test fun `changed source bytes with identical size and timestamp invalidate the index`() {
        val cache = cache()
        val file = source
        assertTrue(cache.save("book", file, richBook()))
        val stamp = file.lastModified()
        file.writeText("X".repeat(file.length().toInt()))
        assertTrue(file.setLastModified(stamp))
        assertNull(cache.openIndexed("book", file))
    }

    @Test fun `preview cannot masquerade as a complete publication index`() {
        val cache = cache()
        assertTrue(cache.save("book", source, richBook(), complete = false))
        cache.entryFile("book", complete = false).copyTo(cache.entryFile("book"))
        assertNull(cache.openIndexed("book", source))
        assertNotNull(cache.loadPreview("book", source))
    }

    @Test fun `manifest validates resources belonging to distant notes without decoding notes`() {
        val cache = cache()
        val original = richBook()
        assertTrue(cache.save("book", source, original))
        val image = (original.notes.getValue("note#1").elements.last() as ContentElement.Image).path
        assertTrue(File(image).delete())
        assertNull(cache.openIndexed("book", source))
    }

    @Test fun `reader retains one native generation across atomic replacement`() {
        val cache = cache()
        val original = richBook()
        val replacement = richBook(lastText = "Other distant destination שלום")
        assertTrue(cache.save("book", source, original))
        requireNotNull(cache.openIndexed("book", source)).use { previous ->
            val previousKey = previous.contentKey
            assertTrue(cache.save("book", source, replacement))
            assertEquals(original.chapters.size, previous.index.chapters.size)
            assertChapterEquals(original.chapters.last(), requireNotNull(previous.readChapter(3)))
            val replacementKey = requireNotNull(cache.openIndexed("book", source)).use { current ->
                assertEquals(previous.index.chapters, current.index.chapters)
                assertChapterEquals(replacement.chapters.last(), requireNotNull(current.readChapter(3)))
                assertNotEquals(previousKey, current.contentKey)
                current.contentKey
            }
            assertEquals(previousKey, previous.contentKey)
            assertTrue(cache.save("book", source, replacement))
            requireNotNull(cache.openIndexed("book", source)).use { current ->
                assertEquals(replacementKey, current.contentKey)
            }
        }
    }

    @Test fun `concurrent distant chapter reads share no temporary output and close is idempotent`() {
        val cache = cache()
        val original = BookContent(List(12) { index ->
            Chapter("Chapter $index", List(8) { paragraph("$index:$it " + "content ".repeat(64)) })
        })
        assertTrue(cache.save("book", source, original))
        val entry = cache.entryFile("book")
        val session = requireNotNull(cache.openIndexed("book", source))
        val executor = Executors.newFixedThreadPool(4)
        try {
            assertEquals(listOf(entry.name), requireNotNull(entry.parentFile!!.list()).toList())
            val calls = (0 until 48).map { index -> Callable {
                val chapterIndex = index % original.chapters.size
                assertChapterEquals(original.chapters[chapterIndex], requireNotNull(session.readChapter(chapterIndex)))
            } }
            executor.invokeAll(calls).forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(entry.name), requireNotNull(entry.parentFile!!.list()).toList())
        } finally {
            executor.shutdownNow()
            session.close()
        }
        session.close()
        assertNull(session.readChapter(0))
        assertNull(session.readGlobals())
    }

    @Test fun `saveTxtDirectly writes valid frb matching TextBookParser output`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val cache = cache()
        val txtFile = File(temporary.root, "sample.txt").apply {
            writeText(
                listOf(
                    "First paragraph with\na soft line break.",
                    "Second paragraph — with Unicode (Привет) and spaces.",
                    "Third paragraph at the end.",
                ).joinToString("\n\n"),
            )
        }
        val parsed = TextBookParser.parseContent(txtFile, BookFormat.TXT)
        val expected = BookContent(
            chapters = parsed.chapters,
            notes = parsed.notes,
            linkTargets = parsed.linkTargets,
            fonts = parsed.fonts,
            language = "en",
            linkedDocuments = parsed.linkedDocuments,
            linkedDocumentTargets = parsed.linkedDocumentTargets,
            navigation = parsed.navigation,
            publisherPublication = parsed.publisherPublication,
            pageProgression = parsed.pageProgression,
            epubSourceRanges = parsed.epubSourceRanges,
        )
        assertTrue(cache.save("txt-book", txtFile, BookContent(emptyList(), language = "en"), complete = false))
        assertTrue(cache.entryFile("txt-book", complete = false).isFile)

        assertTrue(cache.saveTxtDirectly("txt-book", txtFile, language = "en"))
        assertFalse("Preview entry should be removed on direct complete save", cache.entryFile("txt-book", complete = false).exists())
        assertTrue(cache.entryFile("txt-book", complete = true).isFile)

        val loaded = requireNotNull(cache.load("txt-book", txtFile))
        assertEquals(expected.language, loaded.language)
        assertEquals(navigationJson(expected.navigation), navigationJson(loaded.navigation))
        assertEquals(expected.chapters.size, loaded.chapters.size)
        expected.chapters.forEachIndexed { idx, chapter ->
            assertChapterEquals(chapter, loaded.chapters[idx])
        }

        requireNotNull(cache.openIndexed("txt-book", txtFile)).use { session ->
            assertEquals(expected.language, session.index.language)
            assertEquals(navigationJson(expected.navigation), navigationJson(session.index.navigation))
            assertEquals(expected.chapters.size, session.index.chapters.size)
            assertEquals(expected.totalElements, session.index.totalElements)
            expected.chapters.forEachIndexed { idx, chapter ->
                assertChapterEquals(chapter, requireNotNull(session.readChapter(idx)))
            }
            assertEquals(globalsJson(expected), json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())))
        }
    }

    @Test fun `openIndexedContent builds frb directly on cold open for TXT books`() = runBlocking {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val txtBook = Book(
            id = "cold-txt",
            title = "Cold TXT",
            format = BookFormat.TXT,
            fileName = "cold.txt",
            addedAtMillis = 1L,
        )
        StoreFixture.seed(temporary.root, listOf(txtBook))
        File(temporary.root, "books/cold.txt").apply {
            parentFile!!.mkdirs()
            writeText(
                listOf(
                    "First English paragraph with enough words for language detection.",
                    "Second paragraph — with Unicode (Привет) and spaces.",
                    "Third paragraph at the end of the cold open test.",
                ).joinToString("\n\n"),
            )
        }
        val cache = ChunkedContentCache(File(temporary.root, "reader-content"))
        assertFalse(cache.entryFile(txtBook.id).exists())

        val repository = BookRepository(context)
        val savedBook = requireNotNull(repository.bookById(txtBook.id))
        val session = requireNotNull(repository.openIndexedContent(savedBook))
        try {
            assertTrue("Cold open should persist complete frb directly", cache.entryFile(txtBook.id, complete = true).isFile)
            val loaded = repository.loadContent(savedBook)
            assertEquals(loaded.language, session.index.language)
            assertEquals(loaded.totalElements, session.index.totalElements)
            assertEquals(loaded.chapters.size, session.index.chapters.size)
            var start = 0
            loaded.chapters.forEachIndexed { idx, chapter ->
                val indexedChapter = session.index.chapters[idx]
                assertEquals(chapter.title, indexedChapter.title)
                assertEquals(start, indexedChapter.startElement)
                assertEquals(chapter.elements.size, indexedChapter.elementCount)
                start += chapter.elements.size
                assertChapterEquals(chapter, requireNotNull(session.readChapter(idx)))
            }
        } finally {
            session.close()
        }
    }

    @Test fun `saveMdDirectly writes valid frb matching TextBookParser output`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val cache = cache()
        val mdFile = File(temporary.root, "sample.md").apply {
            writeText(
                """
                # Chapter One

                Intro paragraph with **bold**, *italic*, and [a jump](#section-a).

                ## Section A

                > A quoted line in Markdown.

                - First list item
                - Second list item

                ```kotlin
                val answer = 42
                ```
                """.trimIndent(),
            )
        }
        val expected = TextBookParser.parseContent(mdFile, BookFormat.MD)
        assertTrue(expected.navigation.isNotEmpty())
        assertTrue(expected.linkTargets.isNotEmpty())

        assertTrue(cache.save("md-book", mdFile, BookContent(emptyList(), language = "en"), complete = false))
        assertTrue(cache.entryFile("md-book", complete = false).isFile)

        assertTrue(cache.saveMdDirectly("md-book", mdFile))
        assertFalse("Preview entry should be removed on direct complete save", cache.entryFile("md-book", complete = false).exists())
        assertTrue(cache.entryFile("md-book", complete = true).isFile)

        val loaded = requireNotNull(cache.load("md-book", mdFile))
        assertEquals(expected.language, loaded.language)
        assertEquals(navigationJson(expected.navigation), navigationJson(loaded.navigation))
        assertEquals(expected.linkTargets, loaded.linkTargets)
        assertEquals(expected.chapters.size, loaded.chapters.size)
        expected.chapters.forEachIndexed { idx, chapter ->
            assertChapterEquals(chapter, loaded.chapters[idx])
        }

        requireNotNull(cache.openIndexed("md-book", mdFile)).use { session ->
            assertEquals(expected.language, session.index.language)
            assertEquals(navigationJson(expected.navigation), navigationJson(session.index.navigation))
            assertEquals(expected.linkTargets, session.index.linkTargets)
            assertEquals(expected.chapters.size, session.index.chapters.size)
            assertEquals(expected.totalElements, session.index.totalElements)
            expected.chapters.forEachIndexed { idx, chapter ->
                assertChapterEquals(chapter, requireNotNull(session.readChapter(idx)))
            }
            assertEquals(globalsJson(expected), json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())))
        }
    }

    @Test fun `openIndexedContent builds frb directly on cold open for MD books`() = runBlocking {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val mdBook = Book(
            id = "cold-md",
            title = "Cold MD",
            format = BookFormat.MD,
            fileName = "cold.md",
            addedAtMillis = 1L,
        )
        StoreFixture.seed(temporary.root, listOf(mdBook))
        File(temporary.root, "books/cold.md").apply {
            parentFile!!.mkdirs()
            writeText(
                """
                # First Heading

                First English paragraph with **formatted** text and [section link](#second-heading).

                ## Second Heading

                > A blockquote block for Markdown testing.

                - Bullet one
                - Bullet two

                ```
                code block line
                ```
                """.trimIndent(),
            )
        }
        val cache = ChunkedContentCache(File(temporary.root, "reader-content"))
        assertFalse(cache.entryFile(mdBook.id).exists())

        val repository = BookRepository(context)
        val savedBook = requireNotNull(repository.bookById(mdBook.id))
        val session = requireNotNull(repository.openIndexedContent(savedBook))
        try {
            assertTrue("Cold open should persist complete frb directly", cache.entryFile(mdBook.id, complete = true).isFile)
            val loaded = repository.loadContent(savedBook)
            assertEquals(loaded.language, session.index.language)
            assertEquals(navigationJson(loaded.navigation), navigationJson(session.index.navigation))
            assertEquals(loaded.linkTargets, session.index.linkTargets)
            assertEquals(loaded.totalElements, session.index.totalElements)
            assertEquals(loaded.chapters.size, session.index.chapters.size)
            var start = 0
            loaded.chapters.forEachIndexed { idx, chapter ->
                val indexedChapter = session.index.chapters[idx]
                assertEquals(chapter.title, indexedChapter.title)
                assertEquals(start, indexedChapter.startElement)
                assertEquals(chapter.elements.size, indexedChapter.elementCount)
                start += chapter.elements.size
                assertChapterEquals(chapter, requireNotNull(session.readChapter(idx)))
            }
            assertEquals(globalsJson(loaded), json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())))
        } finally {
            session.close()
        }
    }

    @Test fun `saveFb2Directly writes valid frb matching Fb2Parser output`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val cache = cache()
        val fb2File = File(temporary.root, "sample.fb2").apply {
            writeText(sampleFb2Xml())
        }
        val expectedImagesDir = File(temporary.root, "fb2-expected-images")
        val directImagesDir = File(temporary.root, "fb2-direct-images")
        val expected = Fb2Parser.parseContent(fb2File, expectedImagesDir)
        assertTrue(expected.chapters.size >= 2)
        assertTrue(expected.chapters.any { it.depth > 0 })
        assertTrue(expected.notes.containsKey("#note1"))
        assertTrue(expected.linkTargets.isNotEmpty())

        assertTrue(cache.save("fb2-book", fb2File, BookContent(emptyList(), language = "en"), complete = false))
        assertTrue(cache.entryFile("fb2-book", complete = false).isFile)

        assertTrue(cache.saveFb2Directly("fb2-book", fb2File, directImagesDir))
        assertFalse("Preview entry should be removed on direct complete save", cache.entryFile("fb2-book", complete = false).exists())
        assertTrue(cache.entryFile("fb2-book", complete = true).isFile)

        val expectedImages = expectedImagesDir.listFiles().orEmpty().sortedBy { it.name }
        val directImages = directImagesDir.listFiles().orEmpty().sortedBy { it.name }
        assertTrue("Embedded FB2 binary images should be extracted", directImages.isNotEmpty())
        assertEquals(expectedImages.map { it.name }, directImages.map { it.name })
        expectedImages.zip(directImages).forEach { (expImg, actImg) ->
            assertArrayEquals(expImg.readBytes(), actImg.readBytes())
        }

        fun normalizePaths(encoded: String): String =
            encoded.replace(expectedImagesDir.absolutePath, directImagesDir.absolutePath)

        val loaded = requireNotNull(cache.load("fb2-book", fb2File))
        assertEquals(expected.language, loaded.language)
        assertEquals(navigationJson(expected.navigation), navigationJson(loaded.navigation))
        assertEquals(expected.linkTargets, loaded.linkTargets)
        assertEquals(expected.chapters.size, loaded.chapters.size)
        expected.chapters.forEachIndexed { idx, chapter ->
            assertEquals(
                normalizePaths(json.encodeToString(Chapter.serializer(), chapter)),
                json.encodeToString(Chapter.serializer(), loaded.chapters[idx]),
            )
        }

        requireNotNull(cache.openIndexed("fb2-book", fb2File)).use { session ->
            assertEquals(expected.language, session.index.language)
            assertEquals(navigationJson(expected.navigation), navigationJson(session.index.navigation))
            assertEquals(expected.linkTargets, session.index.linkTargets)
            assertEquals(expected.chapters.size, session.index.chapters.size)
            assertEquals(expected.totalElements, session.index.totalElements)
            expected.chapters.forEachIndexed { idx, chapter ->
                assertEquals(
                    normalizePaths(json.encodeToString(Chapter.serializer(), chapter)),
                    json.encodeToString(Chapter.serializer(), requireNotNull(session.readChapter(idx))),
                )
            }
            assertEquals(
                normalizePaths(globalsJson(expected)),
                json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())),
            )
        }
    }

    @Test fun `openIndexedContent builds frb directly on cold open for FB2 books`() = runBlocking {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val fb2Book = Book(
            id = "cold-fb2",
            title = "Cold FB2",
            format = BookFormat.FB2,
            fileName = "cold.fb2",
            addedAtMillis = 1L,
        )
        StoreFixture.seed(temporary.root, listOf(fb2Book))
        val sourceFile = File(temporary.root, "books/cold.fb2").apply {
            parentFile!!.mkdirs()
            writeText(sampleFb2Xml())
        }
        val cache = ChunkedContentCache(File(temporary.root, "reader-content"))
        assertFalse(cache.entryFile(fb2Book.id).exists())

        val repository = BookRepository(context)
        val savedBook = requireNotNull(repository.bookById(fb2Book.id))
        val session = requireNotNull(repository.openIndexedContent(savedBook))
        try {
            assertTrue("Cold open should persist complete frb directly", cache.entryFile(fb2Book.id, complete = true).isFile)
            val extractedDir = File(File(temporary.root, "images/${fb2Book.id}"), "cold")
            val extractedImages = extractedDir.listFiles().orEmpty()
            assertTrue("Cold open should extract embedded FB2 binaries into imagesDir", extractedImages.isNotEmpty())

            val parsedDirect = Fb2Parser.parseContent(sourceFile, extractedDir)
            val loaded = repository.loadContent(savedBook)
            listOf(parsedDirect, loaded).forEach { expected ->
                assertEquals(expected.language, session.index.language)
                assertEquals(navigationJson(expected.navigation), navigationJson(session.index.navigation))
                assertEquals(expected.linkTargets, session.index.linkTargets)
                assertEquals(expected.totalElements, session.index.totalElements)
                assertEquals(expected.chapters.size, session.index.chapters.size)
                var start = 0
                expected.chapters.forEachIndexed { idx, chapter ->
                    val indexedChapter = session.index.chapters[idx]
                    assertEquals(chapter.title, indexedChapter.title)
                    assertEquals(start, indexedChapter.startElement)
                    assertEquals(chapter.elements.size, indexedChapter.elementCount)
                    start += chapter.elements.size
                    assertChapterEquals(chapter, requireNotNull(session.readChapter(idx)))
                }
                assertEquals(globalsJson(expected), json.encodeToString(BookContent.serializer(), requireNotNull(session.readGlobals())))
            }
        } finally {
            session.close()
        }
    }

    private fun sampleFb2Xml(): String {
        // 1x1 transparent PNG
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <genre>prose</genre>
                  <author><first-name>Ada</first-name><last-name>Lovelace</last-name></author>
                  <book-title>Sample FB2 Book</book-title>
                  <lang>en</lang>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>Chapter One</p></title>
                  <p>Opening paragraph with <emphasis>italic</emphasis>, <strong>bold</strong>, <strikethrough>strike</strikethrough>, <code>inline</code>, and a <a l:href="#note1" type="note">[1]</a>.</p>
                  <image l:href="#img1.png"/>
                  <section id="sub-a">
                    <title><p>Section A</p></title>
                    <p>Jump back to <a l:href="#sub-a">here</a> or read the note.</p>
                  </section>
                </section>
              </body>
              <body name="notes">
                <section id="note1">
                  <title><p>1</p></title>
                  <p>Footnote with <strong>formatted</strong> text.</p>
                  <image l:href="#note.png"/>
                </section>
              </body>
              <binary id="img1.png" content-type="image/png">$pngBase64</binary>
              <binary id="note.png" content-type="image/png">$pngBase64</binary>
            </FictionBook>
        """.trimIndent()
    }

    private fun richBook(lastText: String = "Exact distant destination שלום"): BookContent {
        val font = File(temporary.root, "font.ttf").apply { if (!exists()) writeText("font fixture") }
        val image = File(temporary.root, "image.png").apply { if (!exists()) writeText("image fixture") }
        return BookContent(
            chapters = listOf(
                Chapter("Empty", emptyList()),
                Chapter("Part one", listOf(paragraph("First source element"), paragraph("Second source element")), depth = 1),
                Chapter("Empty middle", emptyList(), depth = 1),
                Chapter("Last chapter", listOf(paragraph(lastText)), depth = 2),
            ),
            notes = mapOf("note#1" to NoteDocument(listOf(paragraph("Rich note"), ContentElement.Image(image.absolutePath)))),
            linkTargets = mapOf("OPS/end.xhtml#target" to (3 to 0)),
            fonts = listOf(BookFont("publisher", font.absolutePath, bold = false, italic = true)),
            language = "he",
            linkedDocuments = mapOf("OPS/appendix.xhtml" to LinkedDocument("OPS/appendix.xhtml", "Appendix", listOf(paragraph("Non-linear appendix")))),
            linkedDocumentTargets = mapOf("appendix" to LinkedDocumentTarget("OPS/appendix.xhtml", 0)),
            navigation = listOf(
                BookNavigationEntry("Part one", 0, BookNavigationTarget.ReadingOrder(1, 0)),
                BookNavigationEntry("Destination", 1, BookNavigationTarget.ReadingOrder(3, 0)),
                BookNavigationEntry("Appendix", 0, BookNavigationTarget.Linked("OPS/appendix.xhtml", 0)),
            ),
            pageProgression = PageProgression.RTL,
        )
    }

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text), block = BlockStyle(
        align = BlockAlign.JUSTIFY, fontFamily = "publisher", lineHeightMult = 1.6f, hyphens = false,
    ))
    private fun assertChapterEquals(expected: Chapter, actual: Chapter) {
        assertEquals(json.encodeToString(Chapter.serializer(), expected), json.encodeToString(Chapter.serializer(), actual))
    }
    private fun navigationJson(entries: List<BookNavigationEntry>) = json.encodeToString(ListSerializer(BookNavigationEntry.serializer()), entries)
    private fun globalsJson(content: BookContent): String = json.encodeToString(BookContent.serializer(), BookContent(
        emptyList(), content.notes, content.linkTargets, content.fonts, content.language,
        content.linkedDocuments, content.linkedDocumentTargets, content.navigation,
        content.publisherPublication, content.pageProgression,
    ))

    private fun corruptPayload(file: File, block: Int) {
        val bytes = file.readBytes()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val indexOffset = 64 + view.getInt(24)
        val entryOffset = indexOffset + block * 48
        val payload = view.getLong(entryOffset)
        val length = view.getLong(entryOffset + 8)
        assertTrue(length > 0)
        val position = Math.toIntExact(payload + length / 2)
        bytes[position] = (bytes[position].toInt() xor 0x40).toByte()
        file.writeBytes(bytes)
    }
}
