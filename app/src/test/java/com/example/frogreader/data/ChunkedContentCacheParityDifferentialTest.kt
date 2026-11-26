@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.data

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.example.frogreader.data.model.*
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustChunkedCacheNative
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderChapterItems
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real JNI/container and Kotlin codec parity, including all non-chapter tables. */
class ChunkedContentCacheParityDifferentialTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source: File get() = File(temporary.root, "source.epub").apply {
        if (!exists()) writeText("Immutable complete source")
    }
    private fun cache(maxEntryBytes: Long = 64L * 1024 * 1024) =
        ChunkedContentCache(File(temporary.root, "chunked"), maxEntryBytes = maxEntryBytes)

    @Test fun `lossless chapter container preserves spans tables notes links fonts and publisher layout`() {
        val expected = richBook()
        assertTrue("The real Rust bridge is required", RustBridge.shared.isAvailable)
        val cache = cache()
        assertTrue(cache.save("rich", source, expected))
        val restored = requireNotNull(cache.load("rich", source))
        assertContentParity(expected, restored)
        assertEquals(expected.fonts, restored.fonts)
        assertEquals(expected.publisherPublication, restored.publisherPublication)
        assertEquals(expected.pageProgression, restored.pageProgression)
        assertEquals(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BookNavigationEntry.serializer()), expected.navigation),
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BookNavigationEntry.serializer()), restored.navigation),
        )
        assertEquals(expected.linkedDocumentTargets, restored.linkedDocumentTargets)
        assertEquals(expected.linkTargets, restored.linkTargets)
        assertEquals(expected.language, restored.language)
        assertFalse(File(cache.entryFile("rich").parentFile, "full-rejected.bin").exists())
    }

    @Test fun `empty books and empty chapters preserve exact chapter and flat item coordinates`() {
        val cache = cache()
        val empty = BookContent(emptyList(), language = "uk", pageProgression = PageProgression.LTR)
        assertTrue(cache.save("empty", source, empty))
        assertContentParity(empty, requireNotNull(cache.load("empty", source)))
        val sparse = BookContent(listOf(Chapter("Empty first", emptyList()),
            Chapter("Middle", listOf(ContentElement.Paragraph(AnnotatedString("Kept source position")))),
            Chapter("Empty last", emptyList()), Chapter(null, emptyList())))
        assertTrue(cache.save("sparse", source, sparse))
        val restored = requireNotNull(cache.load("sparse", source))
        assertContentParity(sparse, restored)
        assertEquals(listOf(0, 0, 1, 1), ReaderChapterItems(restored.chapters).chapterStarts)
        assertEquals(listOf(0, 1, 0, 0), restored.chapters.map { it.elements.size })
    }

    @Test fun `total expanded model above sixty four MiB is saved in bounded independent chapters`() {
        // Reuse the source object so the fixture itself does not retain 65 huge strings.
        val paragraph = ContentElement.Paragraph(AnnotatedString("A".repeat(1024 * 1024)))
        val expected = BookContent(List(65) { Chapter("Chapter $it", listOf(paragraph)) })
        assertTrue(expected.chapters.sumOf { it.elements.sumOf { element ->
            (element as ContentElement.Paragraph).text.length.toLong()
        } } > 64L * 1024 * 1024)
        val cache = cache()
        assertTrue("Total size must not be limited by the monolithic JSON bound", cache.save("large", source, expected))
        val restored = requireNotNull(cache.load("large", source))
        assertContentParity(expected, restored)
        assertFalse(File(cache.entryFile("large").parentFile, "full-rejected.bin").exists())
    }

    @Test fun `same source length and timestamp cannot hide changed content`() {
        val cache = cache()
        val file = source
        assertTrue(cache.save("book", file, plainBook("Original model")))
        val stamp = file.lastModified()
        val length = file.length()
        file.writeText("X".repeat(length.toInt()))
        assertTrue(file.setLastModified(stamp))
        assertEquals(length, file.length())
        assertNull(cache.load("book", file))
    }

    @Test fun `cache cannot be reused for an identical source at another canonical path`() {
        val cache = cache()
        val original = source
        assertTrue(cache.save("book", original, plainBook("Original model")))
        val other = temporary.newFile("other.epub").apply { writeBytes(original.readBytes()); setLastModified(original.lastModified()) }
        assertNull(cache.load("book", other))
    }

    @Test fun `missing fonts invalidate the complete cache`() {
        val cache = cache()
        val expected = richBook()
        assertTrue(cache.save("book", source, expected))
        assertTrue(File(expected.fonts.single().path).delete())
        assertNull(cache.load("book", source))
    }

    @Test fun `missing images in global note tables invalidate the complete cache`() {
        val image = temporary.newFile("note-image.png")
        val expected = BookContent(plainBook("Reading order").chapters,
            notes = mapOf("note" to NoteDocument(listOf(ContentElement.Image(image.absolutePath)))))
        val cache = cache()
        assertTrue(cache.save("book", source, expected))
        assertTrue(image.delete())
        assertNull(cache.load("book", source))
    }

    @Test fun `missing images in linked documents invalidate the complete cache`() {
        val image = temporary.newFile("linked-image.png")
        val expected = BookContent(plainBook("Reading order").chapters,
            linkedDocuments = mapOf("appendix" to LinkedDocument("appendix", "Appendix",
                listOf(ContentElement.Image(image.absolutePath)))))
        val cache = cache()
        assertTrue(cache.save("book", source, expected))
        assertTrue(image.delete())
        assertNull(cache.load("book", source))
    }

    @Test fun `truncation and damaged header index or payload bytes are safe cache misses`() {
        val cache = cache()
        assertTrue(cache.save("book", source, BookContent(List(3) { Chapter("Chapter $it",
            listOf(ContentElement.Paragraph(AnnotatedString("Text $it ".repeat(400))))) })))
        val file = cache.entryFile("book")
        val valid = file.readBytes()
        for (cut in listOf(0, 1, 8, valid.size / 2, valid.lastIndex)) {
            file.writeBytes(valid.copyOf(cut))
            assertNull("Truncation at $cut must not decode", cache.load("book", source))
        }
        for (index in listOf(0, 8, valid.size / 2, valid.lastIndex).distinct()) {
            file.writeBytes(valid.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
            assertNull("Damage at $index must not decode", cache.load("book", source))
        }
        file.writeBytes(valid)
        assertNotNull("Expected data errors must not disable subsequent healthy cache reads", cache.load("book", source))
    }

    @Test fun `failed replacement keeps the last valid container and removes temporary blocks`() {
        val original = plainBook("Still readable")
        val cache = cache()
        assertTrue(cache.save("book", source, original))
        val valid = cache.entryFile("book").readBytes()
        val random = java.util.Random(42)
        val noisy = plainBook(buildString { repeat(24_000) { append((32 + random.nextInt(95)).toChar()) } })
        assertFalse(cache(maxEntryBytes = 1200).save("book", source, noisy))
        assertArrayEquals(valid, cache.entryFile("book").readBytes())
        assertContentParity(original, requireNotNull(cache.load("book", source)))
        assertTrue(cache.entryFile("book").parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun `unsupported compose values never replace a lossless container`() {
        val original = plainBook("Still readable")
        val cache = cache()
        assertTrue(cache.save("book", source, original))
        val valid = cache.entryFile("book").readBytes()
        val text = AnnotatedString("Colored", listOf(AnnotatedString.Range(
            SpanStyle(brush = Brush.linearGradient(listOf(Color.Red, Color.Blue))), 0, 7)))
        assertFalse(cache.save("book", source, BookContent(listOf(Chapter(null,
            listOf(ContentElement.Paragraph(text)))))))
        assertArrayEquals(valid, cache.entryFile("book").readBytes())
        assertContentParity(original, requireNotNull(cache.load("book", source)))
    }

    @Test fun `invalid book identifiers cannot escape the cache directory`() {
        for (id in listOf("", ".", "..", "../other", "a/b", "a\\b", "bad\u0000id")) {
            assertThrows("Unsafe id: $id", IllegalArgumentException::class.java) { cache().entryFile(id) }
        }
    }

    @Test fun `in-memory writeBlockBytes matches file-based writeBlock byte-for-byte and rejects invalid block writes`() {
        assertTrue("The real Rust bridge is required", RustBridge.shared.isAvailable)
        val metadata = "frb-parity-identity".toByteArray(Charsets.UTF_8)
        val counts = intArrayOf(3, 0, 5)
        val payloads = listOf(
            "{\"index\":true}".toByteArray(Charsets.UTF_8),
            "{\"globals\":true}".toByteArray(Charsets.UTF_8),
            byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte()),
            ByteArray(0),
            "Chapter payload".repeat(32).toByteArray(Charsets.UTF_8),
        )
        val fileBased = File(temporary.root, "file-based.frb")
        val inMemory = File(temporary.root, "in-memory.frb")

        val fileHandle = RustChunkedCacheNative.beginWrite(
            fileBased.path,
            metadata,
            counts,
            RustChunkedCacheNative.MAX_FILE_BYTES,
        )
        assertTrue(fileHandle > 0L)
        try {
            payloads.forEachIndexed { index, bytes ->
                val part = File(temporary.root, "part-$index.bin").apply { writeBytes(bytes) }
                assertTrue(RustChunkedCacheNative.writeBlock(fileHandle, index, part.path))
                assertTrue(part.delete())
            }
            assertTrue(RustChunkedCacheNative.finishWrite(fileHandle))
        } finally {
            RustChunkedCacheNative.close(fileHandle)
        }

        val memHandle = RustChunkedCacheNative.beginWrite(
            inMemory.path,
            metadata,
            counts,
            RustChunkedCacheNative.MAX_FILE_BYTES,
        )
        assertTrue(memHandle > 0L)
        try {
            assertFalse(RustChunkedCacheNative.writeBlockBytes(0L, 0, payloads[0]))
            assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, -1, payloads[0]))
            assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, 1, payloads[1]))
            assertFalse(
                RustChunkedCacheNative.writeBlockBytes(
                    memHandle,
                    0,
                    ByteArray(ChunkedContentCache.MAX_INDEX_BYTES.toInt() + 1),
                ),
            )
            payloads.forEachIndexed { index, bytes ->
                assertTrue(RustChunkedCacheNative.writeBlockBytes(memHandle, index, bytes))
                assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, index, bytes))
            }
            assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, payloads.size, byteArrayOf(1)))
            assertTrue(RustChunkedCacheNative.finishWrite(memHandle))
            assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, 0, payloads[0]))
        } finally {
            RustChunkedCacheNative.close(memHandle)
        }
        assertFalse(RustChunkedCacheNative.writeBlockBytes(memHandle, 0, payloads[0]))

        assertArrayEquals(fileBased.readBytes(), inMemory.readBytes())

        val reader = RustChunkedCacheNative.openRead(inMemory.path, metadata)
        assertTrue(reader > 0L)
        try {
            payloads.forEachIndexed { index, expected ->
                assertArrayEquals(expected, RustChunkedCacheNative.readBlockBytes(reader, index))
            }
            assertNull(RustChunkedCacheNative.readBlockBytes(reader, payloads.size))
        } finally {
            RustChunkedCacheNative.close(reader)
        }
    }

    @Test fun `in-memory save and epub source index round-trip without temporary block files`() {
        val cache = cache()
        val book = BookContent(
            chapters = listOf(
                Chapter("One", listOf(ContentElement.Paragraph(AnnotatedString("Alpha")), ContentElement.Divider)),
                Chapter("Empty", emptyList()),
                Chapter("Three", listOf(ContentElement.Paragraph(AnnotatedString("Omega")))),
            ),
            epubSourceRanges = listOf(
                EpubSourceRange(chapterIndex = 0, chapterStart = 0, elementCount = 2, spineOrdinal = 0, path = "OPS/ch1.xhtml", documentStart = 0),
                EpubSourceRange(chapterIndex = 2, chapterStart = 0, elementCount = 1, spineOrdinal = 1, path = "OPS/ch3.xhtml", documentStart = 0),
            ),
        )
        assertTrue(cache.save("indexed-book", source, book))
        val entryDir = cache.entryFile("indexed-book").parentFile!!
        assertEquals(listOf("content.frb"), entryDir.list()!!.sorted())
        val sourceIndexDir = File(File(temporary.root, "reader-source-index"), "indexed-book")
        assertEquals(listOf("index.frb"), sourceIndexDir.list()!!.sorted())

        val sourceIndex = requireNotNull(cache.loadEpubSourceIndex("indexed-book", source))
        assertEquals(book.epubSourceRanges, sourceIndex.epubSourceRanges)

        cache.openIndexed("indexed-book", source).use { session ->
            requireNotNull(session)
            assertEquals(3, session.index.chapters.size)
            val restoredChapters = (0..2).map { requireNotNull(session.readChapter(it)) }
            assertContentParity(BookContent(book.chapters), BookContent(restoredChapters))
            val rawChapter0 = requireNotNull(session.readChapterBlockBytes(0))
            assertContentParity(
                BookContent(listOf(book.chapters[0])),
                BookContent(listOf(FrbModelCodec.decode(rawChapter0, Chapter.serializer()))),
            )
        }
    }

    private fun plainBook(text: String) = BookContent(listOf(Chapter("Chapter",
        listOf(ContentElement.Paragraph(AnnotatedString(text))))))

    private fun assertContentParity(expected: BookContent, actual: BookContent) {
        assertEquals(expected.totalElements, actual.totalElements)
        assertEquals(expected.chapters.map { it.title }, actual.chapters.map { it.title })
        assertArrayEquals(contentDigest(expected), contentDigest(actual))
        val originalItems = ReaderChapterItems(expected.chapters)
        val restoredItems = ReaderChapterItems(actual.chapters)
        assertEquals(originalItems.chapterStarts, restoredItems.chapterStarts)
        assertEquals(PaginationCache.contentSignature(originalItems), PaginationCache.contentSignature(restoredItems))
    }

    private fun contentDigest(content: BookContent): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use {
            Json.encodeToStream(BookContent.serializer(), content, it)
        }
        return digest.digest()
    }

    private fun richBook(): BookContent {
        val image = temporary.newFile("image.png").absolutePath
        val font = temporary.newFile("font.ttf").absolutePath
        val inline = AnnotatedString.Builder("שלום math ruby \uFFFC note").apply {
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 0, 4)
            addStyle(SpanStyle(color = Color(0xFF654321), fontSize = 1.2.em,
                fontWeight = FontWeight(650), fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.All,
                fontFamily = FontFamily.Monospace, fontFeatureSettings = "smcp", letterSpacing = .1.em,
                baselineShift = BaselineShift(.3f), textGeometricTransform = TextGeometricTransform(.9f, .1f),
                localeList = LocaleList("he,en"), background = Color(0xFFABCDE0),
                textDecoration = TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline)),
                shadow = Shadow(Color.Black, Offset(1f, 2f), 3f)), 0, 4)
            addStringAnnotation(INLINE_FONT_TYPOGRAPHY_TAG, "{\"axes\":{\"wght\":650}}", 0, 4)
            addStringAnnotation(INLINE_IMAGE_TAG, image, 15, 16)
            addStringAnnotation(INLINE_IMAGE_ALT_TAG, "Ornament", 15, 16)
            addStringAnnotation(FOOTNOTE_TAG, "note#1", 17, 21)
            addStringAnnotation(LINK_TAG, "end", 0, 4)
            addTtsAnnotation(VerbatimTtsAnnotation("Shalom"), 0, 4)
            addStyle(androidx.compose.ui.text.ParagraphStyle(
                textDirection = TextDirection.Rtl, textIndent = TextIndent(1.em, .5.em)), 0, length)
            addStringAnnotation("zero-length", "retained", length, length)
        }.toAnnotatedString()
        val box = PublisherBoxStyle(marginTopEm = .1f, marginRightEm = .2f, marginRightFrac = .03f,
            marginBottomEm = .4f, marginLeftEm = .5f, marginLeftFrac = .06f,
            paddingTopEm = .7f, paddingRightEm = .8f, paddingRightFrac = .09f,
            paddingBottomEm = 1f, paddingLeftEm = 1.1f, paddingLeftFrac = .12f,
            backgroundColorArgb = 0xFF123456.toInt(),
            borderTop = PublisherBorderSide(.1f, 0xFF987654.toInt(), PublisherBorderStyle.DOUBLE, true),
            borderRight = PublisherBorderSide(.2f, 0, PublisherBorderStyle.NONE),
            borderBottom = PublisherBorderSide(.3f, 1, PublisherBorderStyle.DOTTED),
            borderLeft = PublisherBorderSide(.4f, 2, PublisherBorderStyle.DASHED),
            widthFrac = .7f, widthEm = 20f, shrinkToFit = true, hangingIndentEm = -.1f, fitTextFlow = true,
            horizontalAlign = PublisherBoxAlign.END, breakInsideAvoid = true, pageBreakBefore = true,
            pageBreakAfter = true, minHeightEm = 3f, minHeightViewportFrac = .2f, minHeightOffsetEm = .4f,
            centerContentVertically = true, borderBox = true, maxWidthEm = 30f, maxWidthFrac = .8f)
        val typography = FontTypography(mapOf("wght" to 650f, "wdth" to 95f), 95f, false)
        val block = BlockStyle(align = BlockAlign.RIGHT, italic = true, bold = false, fontScale = 1.3f,
            indentStartFrac = .1f, indentStartEm = .2f, indentEndFrac = .3f, indentEndEm = .4f,
            indentLeftFrac = .5f, indentLeftEm = .6f, indentRightFrac = .7f, indentRightEm = .8f,
            firstLineIndent = true, firstLineIndentEm = 1.1f, isCssTextLine = true,
            spaceBeforeEm = .9f, spaceAfterEm = 1f, spaceBeforeSpecified = true, spaceAfterSpecified = true,
            fontFamily = "serif", lineHeightMult = 1.5f, hyphens = false, pageBreakBefore = true,
            firstLetter = FirstLetterStyle(2f, true, false, true, "drop", 1, 2, false,
                BookTextDirection.RTL, "he", 2, 650, typography, "smcp"),
            floatImage = FloatImage(image, .2f, false, "Side image"), language = "he",
            direction = BookTextDirection.RTL, foregroundColorArgb = 3, backgroundColorArgb = 4,
            fontFeatureSettings = "liga", fontWeight = 650, fontTypography = typography)
        val elements = listOf(
            ContentElement.Paragraph(inline, ParagraphStyle.POEM, block),
            ContentElement.Heading(inline, 3, block),
            ContentElement.Image(image, .2f, 3f, 4f, "Image", true, true, 5f, .6f, 7f, .8f, true, 12, true),
            ContentElement.Divider, ContentElement.Spacer(1.3f),
            ContentElement.Table(listOf(TableRow(listOf(TableCell(inline, 2, 3, BlockAlign.CENTER, true,
                block, box, true, true, TableCellStyle(block, box, BlockAlign.END, true, true))), true)),
                block, PublisherRowLayout.SPACE_AROUND, true, 350f, box),
        )
        val boxes = listOf(PublisherBoxSpan("outer", null, 0, elements.size, box, PublisherFloatSide.LEFT, PublisherClear.BOTH, false, false))
        val rendition = PublisherRendition(RenditionLayout.PRE_PAGINATED, RenditionOrientation.LANDSCAPE,
            RenditionSpread.BOTH, PagePlacement.LEFT, PublisherViewport(PublisherViewportDimension.Device,
                PublisherViewportDimension.CssPixels(800f), ViewportSource.XHTML_META, true), PrimaryWritingMode.VERTICAL_RL, true)
        val publication = PublisherPublication(PublisherFormat.EPUB_3, PublisherProfile.EPUB_STANDARD,
            PublisherSourceDescriptor.EpubArchive("OPS/book.opf"), rendition, PageProgression.RTL,
            listOf(PublisherSpineItem("occurrence", "idref", "id", "OPS/chapter.xhtml", false, "Document", rendition,
                setOf(PublisherCapability.MATHML, PublisherCapability.VERTICAL_WRITING), listOf(KindleRegion("source", "target", 2, KindleRegionKind.IMAGE)))),
            mapOf("OPS/font.ttf" to PublisherResource("OPS/font.ttf", "font/ttf", setOf("font"), PublisherResourceTransform.IDPF_FONT_OBFUSCATION)),
            PublisherTextIndex(mapOf("OPS/chapter.xhtml" to PublisherTextDocument("OPS/chapter.xhtml", "Text", listOf(PublisherTextAnchor("anchor", 2))))))
        return BookContent(listOf(Chapter("Chapter", elements, 2, boxes)),
            notes = mapOf("note#1" to NoteDocument(elements, boxes)), linkTargets = mapOf("end" to (0 to 5)),
            fonts = listOf(BookFont("serif", font, true, true, 650, 100, 900, 70f, 120f, "U+0000-00FF")),
            language = "he", linkedDocuments = mapOf("linked" to LinkedDocument("linked", "Appendix", elements, boxes)),
            linkedDocumentTargets = mapOf("appendix" to LinkedDocumentTarget("linked", 2)),
            navigation = listOf(BookNavigationEntry("Chapter", 0, BookNavigationTarget.ReadingOrder(0, 1)),
                BookNavigationEntry("Appendix", 1, BookNavigationTarget.Linked("linked", 2))),
            publisherPublication = publication, pageProgression = PageProgression.RTL)
    }
}
