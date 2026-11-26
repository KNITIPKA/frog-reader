package com.example.frogreader.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle as ComposeParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.IndexedContentSession
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableCellStyle
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.parser.Woff2Decoder
import com.example.frogreader.data.parser.WoffDecoder
import com.example.frogreader.data.search.SearchTextNormalizer
import com.example.frogreader.parser.Woff2TestFonts
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.SEARCH_RESULT_LIMIT
import com.example.frogreader.ui.reader.SearchResults
import com.example.frogreader.ui.reader.adjustReaderElement
import com.example.frogreader.ui.reader.searchBook
import com.example.frogreader.ui.reader.searchFrbFileNative
import com.example.frogreader.ui.reader.searchIndexedBook
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RustSearchWoffTest {
    @get:Rule
    val temporary = TemporaryFolder()

    // ------------------------------------------------------------ M3.1 Native Search Parity

    @Test
    fun `native FRB search matches Kotlin searchBook across rich models, snippets, footnotes, and Unicode`() = runBlocking {
        assumeRust()

        val image = temporary.newFile("img.png").absolutePath
        val boxStyle = PublisherBoxStyle(
            marginTopEm = 0.2f,
            paddingLeftEm = 0.5f,
            backgroundColorArgb = 0xFF123456.toInt(),
            borderTop = PublisherBorderSide(0.1f, 0xFF112233.toInt(), PublisherBorderStyle.SOLID, true),
            horizontalAlign = PublisherBoxAlign.CENTER,
            breakInsideAvoid = true,
        )
        val typography = FontTypography(mapOf("wght" to 650f), 95f, false)
        val blockStyle = BlockStyle(
            align = BlockAlign.JUSTIFY,
            italic = true,
            bold = false,
            fontScale = 1.15f,
            fontFamily = "serif",
            firstLetter = FirstLetterStyle(2.0f, true, false, true, fontTypography = typography),
            floatImage = FloatImage(image, 0.25f, true, "Side"),
            language = "uk",
            direction = BookTextDirection.AUTO,
            fontTypography = typography,
        )

        val richAnnotated = buildAnnotatedString {
            append("Start ")
            pushStringAnnotation(HIDDEN_TEXT_TAG, "true")
            append("hidden needle ")
            pop()
            pushStringAnnotation(FOOTNOTE_TAG, "note#1")
            append("[fn needle]")
            pop()
            append(" 📖 middle\nline needle and Привіт ПРИВІТ\n")
            addStyle(
                SpanStyle(
                    color = Color(0xFF654321),
                    fontSize = 1.2.em,
                    fontWeight = FontWeight(650),
                    fontStyle = FontStyle.Italic,
                    fontSynthesis = FontSynthesis.All,
                    fontFamily = FontFamily.Serif,
                    textDecoration = TextDecoration.Underline,
                    shadow = Shadow(Color.Black, Offset(1f, 1f), 2f),
                    localeList = LocaleList("uk"),
                ),
                0,
                5,
            )
            addStyle(ComposeParagraphStyle(textAlign = TextAlign.Center, textDirection = TextDirection.Content, textIndent = TextIndent(1.em, 0.em)), 0, length)
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_LTR.name, 0, 5)
            addStringAnnotation(LINK_TAG, "end", 0, 5)
            addTtsAnnotation(VerbatimTtsAnnotation("Start"), 0, 5)
            addLink(LinkAnnotation.Url("https://example.invalid", TextLinkStyles(SpanStyle(fontWeight = FontWeight.Bold))), 0, 5)
        }

        val longContext = "alpha beta gamma delta epsilon zeta eta theta " +
            "NEEDLE" +
            " iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega alpha beta gamma delta epsilon zeta"

        val chapter0 = Chapter("Empty", emptyList())
        val chapter1 = Chapter(
            title = "Rich",
            elements = listOf(
                ContentElement.Paragraph(richAnnotated, ParagraphStyle.POEM, blockStyle),
                ContentElement.Paragraph(AnnotatedString(longContext), ParagraphStyle.NORMAL, blockStyle),
                ContentElement.Image(image, widthFrac = 0.5f, altText = "needle alt ignored"),
                ContentElement.Divider,
                ContentElement.Spacer(1.2f),
                ContentElement.Heading(richAnnotated, level = 2, block = blockStyle),
                ContentElement.Table(
                    rows = listOf(
                        TableRow(
                            listOf(
                                TableCell(AnnotatedString("CellA"), block = blockStyle, publisherBox = boxStyle, narrowStyle = TableCellStyle(blockStyle, boxStyle, BlockAlign.END, true, true)),
                                TableCell(buildAnnotatedString {
                                    append("CellB ")
                                    pushStringAnnotation(FOOTNOTE_TAG, "n2")
                                    append("needle")
                                    pop()
                                    append(" needle")
                                }),
                            ),
                            isHeader = true,
                        ),
                        TableRow(listOf(TableCell(AnnotatedString("Row2 needle"))), isHeader = false),
                    ),
                    block = blockStyle,
                    publisherRowLayout = PublisherRowLayout.NATURAL,
                    publisherBox = boxStyle,
                ),
            ),
            depth = 1,
            publisherBoxes = listOf(
                PublisherBoxSpan("b1", null, 0, 2, boxStyle, PublisherFloatSide.LEFT, PublisherClear.BOTH, true, false),
            ),
        )
        val chapter2 = Chapter(
            title = "Special folding",
            elements = listOf(
                ContentElement.Paragraph(AnnotatedString("İstanbul ſample Kelvin Straße ß ᾈ ᾀ")),
            ),
        )
        val content = BookContent(
            chapters = listOf(chapter0, chapter1, chapter2),
            notes = mapOf("note#1" to NoteDocument(listOf(ContentElement.Paragraph(AnnotatedString("needle in excluded note"))))),
            linkedDocuments = mapOf("app" to LinkedDocument("app", "Appendix", listOf(ContentElement.Paragraph(AnnotatedString("needle in excluded doc"))))),
        )

        val root = temporary.newFolder("frb-search")
        val source = File(root, "book.epub").apply { writeText("Source payload") }
        val cache = ChunkedContentCache(File(root, "cache"))
        assertTrue(cache.save("book", source, content))
        val frbFile = cache.entryFile("book")
        val metadata = readFrbMetadata(frbFile)

        // Corrupt Block 1 (heavy globals) to verify search never touches globals.
        corruptBlock(frbFile, 1)

        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { cache.openIndexed("book", source) })
        session.use { s ->
            for (hide in listOf(false, true)) {
                for (coverCount in listOf(0, 1)) {
                    val cover = if (coverCount == 1) ReaderItem(0, ContentElement.Image("/cover.png")) else null
                    for (query in listOf("needle", "привіт", "istanbul", "sample", "kelvin", "ß", "ᾀ", "")) {
                        val expected = if (query.isEmpty()) {
                            SearchResults(query, emptyList(), 0)
                        } else {
                            searchBook(
                                ReaderChapterItems(content.chapters, cover) { adjustReaderElement(it, hide) },
                                query,
                                SEARCH_RESULT_LIMIT,
                            )
                        }
                        val viaSession = searchIndexedBook(s, query, coverCount, hide, SEARCH_RESULT_LIMIT)
                        val viaFrbFile = searchFrbFileNative(frbFile, metadata, query, coverCount, hide, SEARCH_RESULT_LIMIT)!!
                        assertResultsEqual("session hide=$hide cover=$coverCount q=$query", expected, viaSession)
                        assertResultsEqual("frbFile hide=$hide cover=$coverCount q=$query", expected, viaFrbFile)
                    }
                }
            }
        }

        // Direct chapter binary search via RustSearchNative.searchFrbChapter
        val encodedChapter = ByteArrayOutputStream().also {
            FrbModelCodec.encode(it, Chapter.serializer(), chapter1)
        }.toByteArray()
        val chBytes = RustSearchNative.searchFrbChapter(encodedChapter, 1, 10, "needle", true, 2)!!
        val chRes = RustSearchNative.decodeSearchResponse(chBytes, "needle")
        assertEquals(2, chRes.matches.size)
        assertEquals(7, chRes.totalCount)
        assertEquals(10, chRes.matches[0].itemIndex)
        assertEquals(1, chRes.matches[0].chapterIndex)

        // Corrupt chapter 1 triggers IOException mentioning "chapter 1"
        corruptBlock(frbFile, 3)
        val failure = runCatching {
            searchFrbFileNative(frbFile, metadata, "needle", 0, false, SEARCH_RESULT_LIMIT)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("chapter 1"))
    }

    @Test
    fun `native SearchTextNormalizer matches Kotlin SearchTextNormalizer across all scripts`() {
        assumeRust()
        val samples = listOf(
            "",
            "   ",
            "  Café   Naïve\nRésumé ",
            "Straße Søren Łódź",
            "Encyclopædie Œuvre",
            "Украї\u0301на й Ки\u0301їв",
            "І\u0308жак и\u0306орж Киі\u0308в",
            "солов'їний пам’ять соловʼїний пам′ять",
            "أَحْمَدُ إِبْرَاهِيم آفاق مَكْتَبَة مُوسَى كِتَابٌ كـتـاب کتاب",
            "שָׁלוֹם בְּרֵאשִׁית הַזְּמַן תנ\"ך תנ״ך רמב״ם",
            "\uE000Time\u00A0Word",
            "time \"AND\" OR* (test)",
            "   ... — ?!  ",
        )
        for (sample in samples) {
            assertEquals("normalize($sample)", SearchTextNormalizer.normalize(sample), RustSearchNative.normalizeText(sample))
            assertEquals("tokenize($sample)", SearchTextNormalizer.tokenize(sample), RustSearchNative.tokenizeQuery(sample).toList())
            assertEquals("fts5($sample)", SearchTextNormalizer.toFts5PrefixQuery(sample), RustSearchNative.toFts5PrefixQuery(sample))
        }
    }

    @Test
    fun `searchIndexedBook scans real FRB blocks natively without calling readChapter`() = runBlocking {
        assumeRust()

        val rich = buildAnnotatedString {
            append("Alpha ")
            pushStringAnnotation(FOOTNOTE_TAG, "fn1")
            append("[fn needle]")
            pop()
            append(" 📖 beta needle and Привіт")
        }
        val chapters = listOf(
            Chapter("Empty", emptyList()),
            Chapter(
                title = "Body",
                elements = listOf(
                    ContentElement.Paragraph(rich, ParagraphStyle.NORMAL),
                    ContentElement.Divider,
                    ContentElement.Heading(AnnotatedString("Heading needle"), 2),
                    ContentElement.Table(
                        listOf(
                            TableRow(
                                listOf(
                                    TableCell(AnnotatedString("Left")),
                                    TableCell(buildAnnotatedString {
                                        append("Cell ")
                                        pushStringAnnotation(FOOTNOTE_TAG, "fn2")
                                        append("needle")
                                        pop()
                                        append(" needle")
                                    }),
                                ),
                                isHeader = false,
                            ),
                        ),
                    ),
                ),
            ),
            Chapter(
                title = "Unicode",
                elements = listOf(
                    ContentElement.Paragraph(AnnotatedString("İstanbul ſample Kelvin ß ᾈ")),
                ),
            ),
        )
        val content = BookContent(chapters)
        val root = temporary.newFolder("frb-zero-chapter")
        val source = File(root, "book.epub").apply { writeText("Immutable source") }
        val cache = ChunkedContentCache(File(root, "cache"))
        assertTrue(cache.save("book", source, content))

        val nativeSession = requireNotNull(cache.openIndexed("book", source))
        val blockReads = AtomicInteger()
        val chapterReads = AtomicInteger()
        val spySession = object : IndexedContentSession {
            override val index = nativeSession.index
            override val contentKey = nativeSession.contentKey
            override fun readChapter(index: Int): Chapter? {
                chapterReads.incrementAndGet()
                throw AssertionError("searchIndexedBook must not decode Chapter $index via readChapter")
            }
            override fun readChapterBlockBytes(index: Int): ByteArray? {
                blockReads.incrementAndGet()
                return nativeSession.readChapterBlockBytes(index)
            }
            override fun readChapters(first: Int, last: Int, checkActive: () -> Unit): List<Chapter>? {
                chapterReads.incrementAndGet()
                throw AssertionError("searchIndexedBook must not decode Chapter range $first..$last")
            }
            override fun readGlobals(): BookContent? {
                throw AssertionError("searchIndexedBook must not read globals")
            }
            override fun close() = nativeSession.close()
        }

        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { spySession })
        session.use { s ->
            // Verify direct block-byte access bounds and payload presence.
            assertNotNull(s.readChapterBlockBytes(0))
            assertNotNull(s.readChapterBlockBytes(1))
            assertNull(s.readChapterBlockBytes(-1))
            assertNull(s.readChapterBlockBytes(chapters.size))
            blockReads.set(0)

            val queries = listOf("needle", "привіт", "istanbul", "sample", "kelvin", "ß", "ᾀ")
            var expectedBlockReads = 0
            for (hide in listOf(false, true)) {
                for (coverCount in listOf(0, 1)) {
                    val cover = if (coverCount == 1) ReaderItem(0, ContentElement.Image("/cover.png")) else null
                    for (query in queries) {
                        val expected = searchBook(
                            ReaderChapterItems(content.chapters, cover) { adjustReaderElement(it, hide) },
                            query,
                            SEARCH_RESULT_LIMIT,
                        )
                        val actual = searchIndexedBook(s, query, coverCount, hide, SEARCH_RESULT_LIMIT)
                        assertResultsEqual("zero-alloc hide=$hide cover=$coverCount q=$query", expected, actual)
                        expectedBlockReads += chapters.size
                    }
                }
            }
            assertEquals(expectedBlockReads, blockReads.get())
            assertEquals(0, chapterReads.get())
        }

        // Verify fallback to readChapter when readChapterBlockBytes returns null (e.g., source-backed session)
        // or when the query contains an unpaired surrogate.
        val fallbackIndex = cache.openIndexed("book", source)!!.use { it.index }
        val fallbackReads = AtomicInteger()
        val fallbackSession = requireNotNull(IndexedBookSession.open(Closeable {}) {
            object : IndexedContentSession {
                override val index = fallbackIndex
                override val contentKey = "source-fallback"
                override fun readChapter(index: Int): Chapter? {
                    fallbackReads.incrementAndGet()
                    return chapters.getOrNull(index)
                }
                override fun readGlobals(): BookContent? = null
                override fun close() = Unit
            }
        })
        fallbackSession.use { s ->
            val res = searchIndexedBook(s, "needle", coverCount = 0, hideFootnotes = true)
            assertEquals(3, res.totalCount)
            assertEquals(chapters.size, fallbackReads.get())
        }

        requireNotNull(IndexedBookSession.open(Closeable {}) { cache.openIndexed("book", source) }).use { real ->
            val surrogateRes = searchIndexedBook(real, "\uD800", coverCount = 0, hideFootnotes = false)
            assertEquals(0, surrogateRes.totalCount)
        }
    }

    @Test
    fun `searchFrbHandle and batched searchIndexedBook match searchBook with zero chapter buffer copies`() = runBlocking {
        assumeRust()

        val chapters = List(70) { ch ->
            if (ch % 11 == 0) {
                Chapter("Empty $ch", emptyList())
            } else {
                val para = buildAnnotatedString {
                    append("Ch$ch alpha ")
                    pushStringAnnotation(FOOTNOTE_TAG, "fn-$ch")
                    append("[fn needle]")
                    pop()
                    append(" 📖 beta NEEDLE and Привіт İstanbul ſample Kelvin")
                }
                Chapter(
                    title = "Chapter $ch",
                    elements = listOf(
                        ContentElement.Paragraph(para, ParagraphStyle.NORMAL),
                        ContentElement.Divider,
                        ContentElement.Heading(AnnotatedString("Heading $ch needle"), 2),
                        ContentElement.Table(
                            listOf(
                                TableRow(
                                    listOf(
                                        TableCell(AnnotatedString("CellA $ch")),
                                        TableCell(AnnotatedString("CellB needle $ch")),
                                    ),
                                    isHeader = false,
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val content = BookContent(chapters)
        val root = temporary.newFolder("frb-handle-search")
        val source = File(root, "book.epub").apply { writeText("Handle search source") }
        val cache = ChunkedContentCache(File(root, "cache"))
        assertTrue(cache.save("book", source, content))
        val frbFile = cache.entryFile("book")
        // Corrupt Block 1 globals to confirm handle search never touches globals.
        corruptBlock(frbFile, 1)

        // Direct RustSearchNative.searchFrbHandle edge cases: invalid handles, negative bounds, ranges.
        assertNull(RustSearchNative.searchFrbHandle(0L, 0, 32, 0, "needle", false, 10))
        assertNull(RustSearchNative.searchFrbHandle(-1L, 0, 32, 0, "needle", false, 10))

        val nativeSession = requireNotNull(cache.openIndexed("book", source))
        val handleCalls = AtomicInteger()
        val blockReads = AtomicInteger()
        val chapterReads = AtomicInteger()
        val spySession = object : IndexedContentSession {
            override val index = nativeSession.index
            override val contentKey = nativeSession.contentKey
            override fun readChapter(index: Int): Chapter? {
                chapterReads.incrementAndGet()
                throw AssertionError("Handle search must not decode Chapter $index via readChapter")
            }
            override fun readChapterBlockBytes(index: Int): ByteArray? {
                blockReads.incrementAndGet()
                throw AssertionError("Handle search must not copy chapter $index bytes via readChapterBlockBytes")
            }
            override fun <T> withNativeHandle(action: (Long) -> T): T? {
                handleCalls.incrementAndGet()
                return nativeSession.withNativeHandle(action)
            }
            override fun readChapters(first: Int, last: Int, checkActive: () -> Unit): List<Chapter>? {
                chapterReads.incrementAndGet()
                throw AssertionError("Handle search must not decode Chapter range $first..$last")
            }
            override fun readGlobals(): BookContent? {
                throw AssertionError("Handle search must not read globals")
            }
            override fun close() = nativeSession.close()
        }

        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { spySession })
        session.use { s ->
            // Direct handle range edge checks.
            val directEmpty = requireNotNull(
                nativeSession.withNativeHandle { handle ->
                    assertNull(RustSearchNative.searchFrbHandle(handle, -1, 5, 0, "needle", false, 10))
                    assertNull(RustSearchNative.searchFrbHandle(handle, 0, -1, 0, "needle", false, 10))
                    assertNull(RustSearchNative.searchFrbHandle(handle, 0, 5, 2, "needle", false, 10))
                    assertNull(RustSearchNative.searchFrbHandle(handle, 0, 5, 0, "needle", false, -1))
                    RustSearchNative.searchFrbHandle(handle, 100, 32, 0, "needle", false, 10)
                },
            )
            val decodedOutOfRange = RustSearchNative.decodeSearchResponse(directEmpty, "needle")
            assertEquals(0, decodedOutOfRange.totalCount)
            assertTrue(decodedOutOfRange.matches.isEmpty())

            handleCalls.set(0)
            // 70 chapters -> ceil(70 / 32) = 3 batches per searchIndexedBook call.
            val expectedBatchesPerSearch = 3
            var totalSearches = 0
            for (hide in listOf(false, true)) {
                for (coverCount in listOf(0, 1)) {
                    val cover = if (coverCount == 1) ReaderItem(0, ContentElement.Image("/cover.png")) else null
                    val items = ReaderChapterItems(content.chapters, cover) { adjustReaderElement(it, hide) }
                    for (query in listOf("needle", "привіт", "istanbul", "sample", "kelvin")) {
                        for (limit in listOf(0, 5, SEARCH_RESULT_LIMIT)) {
                            val expected = searchBook(items, query, limit)
                            val actual = searchIndexedBook(s, query, coverCount, hide, limit)
                            assertResultsEqual("handle hide=$hide cover=$coverCount q=$query limit=$limit", expected, actual)
                            totalSearches++
                        }
                    }
                }
            }
            assertEquals(totalSearches * expectedBatchesPerSearch, handleCalls.get())
            assertEquals(0, blockReads.get())
            assertEquals(0, chapterReads.get())
        }

        // Corrupt chapter 35 (in batch 2) triggers IOException mentioning "chapter 35".
        corruptBlock(frbFile, 35 + 2)
        requireNotNull(IndexedBookSession.open(Closeable {}) { cache.openIndexed("book", source) }).use { corruptSession ->
            val failure = runCatching {
                searchIndexedBook(corruptSession, "needle", coverCount = 0, hideFootnotes = false)
            }.exceptionOrNull()
            assertTrue("Expected IOException, got $failure", failure is IOException)
            assertTrue("Expected message to mention chapter 35, got ${failure!!.message}", failure.message!!.contains("chapter 35"))
        }
    }

    // ------------------------------------------------------------ M3.2 WOFF & WOFF2 Parity

    @Test
    fun `WoffDecoder delegates to RustWoffNative and produces exact sfnt output`() {
        assumeRust()
        val stored = "ABCDEFG".toByteArray() // non-4-aligned stored table
        val compressed = ByteArray(1024) { (it % 7).toByte() }
        val woff = buildWoff(stored, compressed)

        val native = RustWoffNative.decodeWoff(woff, 32 * 1024 * 1024)!!
        val publicApi = WoffDecoder.decode(woff)!!
        assertArrayEquals(native, publicApi)

        // Exact sfnt header & table layout checks
        val buf = ByteBuffer.wrap(publicApi).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00010000, buf.getInt(0))
        assertEquals(2, buf.getShort(4).toInt() and 0xFFFF)
        assertEquals(32, buf.getShort(6).toInt() and 0xFFFF)
        assertEquals(1, buf.getShort(8).toInt() and 0xFFFF)
        assertEquals(0, buf.getShort(10).toInt() and 0xFFFF)

        assertEquals(12 + 32 + 8 + 1024, publicApi.size) // 4-byte padded stored(7 -> 8) + compressed(1024)
        assertEquals(44, buf.getInt(12 + 8)) // name offset
        assertEquals(stored.size, buf.getInt(12 + 12))
        assertEquals(52, buf.getInt(28 + 8)) // glyf offset
        assertEquals(compressed.size, buf.getInt(28 + 12))
        assertArrayEquals(stored, publicApi.copyOfRange(44, 44 + stored.size))
        assertArrayEquals(compressed, publicApi.copyOfRange(52, 52 + compressed.size))

        // Rejection parity
        val truncated = woff.copyOf(woff.size - 3)
        assertNull(RustWoffNative.decodeWoff(truncated, 32 * 1024 * 1024))
        assertNull(WoffDecoder.decode(truncated))

        assertNull(WoffDecoder.decode(woff, maxDecodedBytes = 256))
        assertNull(RustWoffNative.decodeWoff(woff, 256))
        assertNull(WoffDecoder.decode("notW".toByteArray()))
    }

    @Test
    fun `native Woff2Decoder delegates to RustWoffNative and decodes glyf and hmtx transforms`() {
        assumeRust()
        val minimal = Woff2TestFonts.buildWoff2(
            Woff2TestFonts.minimalTtfTables().map { (tag, data) -> Woff2TestFonts.Spec(tag, data) },
        )
        val transformed = Woff2TestFonts.buildWoff2(
            listOf(
                Woff2TestFonts.Spec("glyf", Woff2TestFonts.transformedGlyf(Woff2TestFonts.TRIANGLE), origLength = 64, transformed = true),
                Woff2TestFonts.Spec("loca", ByteArray(0), origLength = 6, transformed = true),
                Woff2TestFonts.Spec("head", Woff2TestFonts.headTable(indexToLocFormat = 1)),
                Woff2TestFonts.Spec("hhea", Woff2TestFonts.hheaTable(numHMetrics = 2)),
                Woff2TestFonts.Spec("maxp", Woff2TestFonts.maxpTable(numGlyphs = 2)),
                Woff2TestFonts.Spec("hmtx", Woff2TestFonts.transformedHmtx(intArrayOf(500, 600)), origLength = 8, transformed = true),
                Woff2TestFonts.Spec("FROG", "custom-tag".toByteArray()),
            ),
        )

        for (woff2 in listOf(minimal, transformed)) {
            val native = RustWoffNative.decodeWoff2(woff2, 32 * 1024 * 1024)!!
            val actual = Woff2Decoder.decode(woff2)!!
            assertArrayEquals(native, actual)
            assertEquals(0x00010000, ByteBuffer.wrap(actual).order(ByteOrder.BIG_ENDIAN).getInt(0))
        }

        val parsedTransformed = Woff2TestFonts.parseSfnt(Woff2Decoder.decode(transformed)!!)
        assertEquals(listOf("FROG", "glyf", "head", "hhea", "hmtx", "loca", "maxp"), parsedTransformed.keys.toList())
        assertArrayEquals("custom-tag".toByteArray(), parsedTransformed.getValue("FROG"))

        // TTC collection & understated Brotli stream length rejection parity
        val ttc = Woff2TestFonts.buildWoff2(
            Woff2TestFonts.minimalTtfTables().map { (tag, data) -> Woff2TestFonts.Spec(tag, data) },
            flavor = 0x74746366L,
        )
        assertNull(Woff2Decoder.decode(ttc))
        assertNull(RustWoffNative.decodeWoff2(ttc, 32 * 1024 * 1024))

        val understated = Woff2TestFonts.buildWoff2(
            listOf(Woff2TestFonts.Spec("name", ByteArray(64) { it.toByte() })),
        ).also { it[49] = 63 }
        assertNull(Woff2Decoder.decode(understated))
        assertNull(RustWoffNative.decodeWoff2(understated, 32 * 1024 * 1024))

        assertNull(Woff2Decoder.decode(minimal, maxDecodedBytes = 100))
        assertNull(RustWoffNative.decodeWoff2(minimal, 100))
    }

    // ------------------------------------------------------------ M3 Benchmarks

    @Test
    fun `benchmark native FRB search throughput and WOFF unpack times`() = runBlocking {
        assumeRust()

        val blockStyle = BlockStyle(align = BlockAlign.JUSTIFY, fontFamily = "serif")
        val chapters = List(24) { ch ->
            Chapter(
                title = "Benchmark Chapter $ch",
                elements = List(80) { el ->
                    val text = buildAnnotatedString {
                        append("Chapter $ch paragraph $el. ")
                        append("The quick brown fox jumps over the lazy dog across realistic prose sentences. ".repeat(10))
                        if (el % 10 == 0) {
                            append(" Target needle occurrence.")
                        }
                        if (el % 15 == 0) {
                            pushStringAnnotation(FOOTNOTE_TAG, "note-$ch-$el")
                            append("[footnote needle]")
                            pop()
                        }
                    }
                    ContentElement.Paragraph(text, ParagraphStyle.NORMAL, blockStyle)
                },
            )
        }
        val content = BookContent(chapters)
        val root = temporary.newFolder("frb-bench")
        val source = File(root, "bench.epub").apply { writeText("Benchmark EPUB") }
        val cache = ChunkedContentCache(File(root, "cache"))
        assertTrue(cache.save("bench", source, content))
        val frbFile = cache.entryFile("bench")
        val metadata = readFrbMetadata(frbFile)

        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { cache.openIndexed("bench", source) })
        var rawPayloadBytes = 0L
        var frbNanos = 0L
        var indexedNanos = 0L
        var decodeNanos = 0L
        val searchIterations = 10
        val decodeIterations = 3
        var frbRes: SearchResults? = null
        var indexedRes: SearchResults? = null
        var decodedRes: SearchResults? = null

        session.use { s ->
            for (i in chapters.indices) {
                rawPayloadBytes += requireNotNull(s.readChapterBlockBytes(i)).size
            }

            val decodedSession = requireNotNull(IndexedBookSession.open(Closeable {}) {
                val inner = requireNotNull(cache.openIndexed("bench", source))
                object : IndexedContentSession {
                    override val index = inner.index
                    override val contentKey = inner.contentKey
                    override fun readChapter(index: Int) = inner.readChapter(index)
                    override fun readGlobals() = inner.readGlobals()
                    override fun close() = inner.close()
                }
            })

            decodedSession.use { ds ->
                // Warmup
                repeat(2) {
                    searchFrbFileNative(frbFile, metadata, "needle", 0, true, SEARCH_RESULT_LIMIT)!!
                    searchIndexedBook(s, "needle", 0, true, SEARCH_RESULT_LIMIT)
                }
                searchIndexedBook(ds, "needle", 0, true, SEARCH_RESULT_LIMIT)

                val frbStart = System.nanoTime()
                repeat(searchIterations) {
                    frbRes = searchFrbFileNative(frbFile, metadata, "needle", 0, true, SEARCH_RESULT_LIMIT)!!
                }
                frbNanos = System.nanoTime() - frbStart

                val indexedStart = System.nanoTime()
                repeat(searchIterations) {
                    indexedRes = searchIndexedBook(s, "needle", 0, true, SEARCH_RESULT_LIMIT)
                }
                indexedNanos = System.nanoTime() - indexedStart

                // Legacy decoded-chapter baseline (FrbModelCodec.decode + searchDecodedChapter)
                val decodeStart = System.nanoTime()
                repeat(decodeIterations) {
                    decodedRes = searchIndexedBook(ds, "needle", 0, true, SEARCH_RESULT_LIMIT)
                }
                decodeNanos = System.nanoTime() - decodeStart
            }
        }

        val frb = requireNotNull(frbRes)
        val indexed = requireNotNull(indexedRes)
        val decoded = requireNotNull(decodedRes)
        assertResultsEqual("bench frb vs indexed", frb, indexed)
        assertResultsEqual("bench frb vs decoded", frb, decoded)
        assertTrue("Expected hits > 0", frb.totalCount > 0)

        val frbTotalMb = (rawPayloadBytes * searchIterations) / 1_000_000.0
        val frbThroughputMbS = frbTotalMb / (frbNanos / 1_000_000_000.0)
        val indexedThroughputMbS = frbTotalMb / (indexedNanos / 1_000_000_000.0)
        val decodeThroughputMbS = ((rawPayloadBytes * decodeIterations) / 1_000_000.0) / (decodeNanos / 1_000_000_000.0)

        // WOFF 1.0 & WOFF 2.0 unpack timing
        val woffStored = ByteArray(1024) { (it * 31).toByte() }
        val woffCompressed = ByteArray(64 * 1024) { (it % 29).toByte() }
        val woffBytes = buildWoff(woffStored, woffCompressed)

        val woff2Bytes = Woff2TestFonts.buildWoff2(
            listOf(
                Woff2TestFonts.Spec("glyf", Woff2TestFonts.transformedGlyf(Woff2TestFonts.TRIANGLE), origLength = 64, transformed = true),
                Woff2TestFonts.Spec("loca", ByteArray(0), origLength = 6, transformed = true),
                Woff2TestFonts.Spec("head", Woff2TestFonts.headTable(indexToLocFormat = 1)),
                Woff2TestFonts.Spec("hhea", Woff2TestFonts.hheaTable(numHMetrics = 2)),
                Woff2TestFonts.Spec("maxp", Woff2TestFonts.maxpTable(numGlyphs = 2)),
                Woff2TestFonts.Spec("hmtx", Woff2TestFonts.transformedHmtx(intArrayOf(500, 600)), origLength = 8, transformed = true),
                Woff2TestFonts.Spec("cmap", ByteArray(32 * 1024) { (it % 17).toByte() }),
            ),
        )
        repeat(10) {
            WoffDecoder.decode(woffBytes)!!
            Woff2Decoder.decode(woff2Bytes)!!
        }

        val fontIterations = 50
        val woffStart = System.nanoTime()
        var woffOut: ByteArray? = null
        repeat(fontIterations) {
            woffOut = WoffDecoder.decode(woffBytes)
        }
        val woffNanos = System.nanoTime() - woffStart

        val woff2NativeStart = System.nanoTime()
        var woff2NativeOut: ByteArray? = null
        repeat(fontIterations) {
            woff2NativeOut = Woff2Decoder.decode(woff2Bytes)
        }
        val woff2NativeNanos = System.nanoTime() - woff2NativeStart

        assertNotNull(woffOut)
        assertNotNull(woff2NativeOut)

        println(
            String.format(
                Locale.ROOT,
                "M3_BENCHMARK search raw_payload_bytes=%d frb_file_bytes=%d chapters=%d matches=%d " +
                    "frb_file_ms=%.2f frb_file_mb_s=%.1f " +
                    "indexed_zero_alloc_ms=%.2f indexed_zero_alloc_mb_s=%.1f " +
                    "decode_baseline_ms=%.2f decode_baseline_mb_s=%.1f",
                rawPayloadBytes,
                frbFile.length(),
                chapters.size,
                frb.totalCount,
                (frbNanos / searchIterations) / 1_000_000.0,
                frbThroughputMbS,
                (indexedNanos / searchIterations) / 1_000_000.0,
                indexedThroughputMbS,
                (decodeNanos / decodeIterations) / 1_000_000.0,
                decodeThroughputMbS,
            ),
        )
        println(
            String.format(
                Locale.ROOT,
                "M3_BENCHMARK woff woff_in_bytes=%d woff_out_bytes=%d woff_rust_ms=%.4f " +
                    "woff2_in_bytes=%d woff2_out_bytes=%d woff2_rust_ms=%.4f",
                woffBytes.size,
                woffOut!!.size,
                (woffNanos / fontIterations) / 1_000_000.0,
                woff2Bytes.size,
                woff2NativeOut!!.size,
                (woff2NativeNanos / fontIterations) / 1_000_000.0,
            ),
        )
    }

    // ------------------------------------------------------------ helpers

    private fun assumeRust() {
        assumeTrue(
            "host Rust tests require -Dfrogreader.requireRust=true",
            java.lang.Boolean.getBoolean("frogreader.requireRust"),
        )
        assertTrue("RustBridge must be available", RustBridge.shared.isAvailable)
    }

    private fun assertResultsEqual(label: String, expected: SearchResults, actual: SearchResults) {
        assertEquals("$label query", expected.query, actual.query)
        assertEquals("$label totalCount", expected.totalCount, actual.totalCount)
        assertEquals("$label truncated", expected.truncated, actual.truncated)
        fun rows(r: SearchResults) = r.matches.map {
            listOf(it.itemIndex, it.chapterIndex, it.charStart, it.before, it.match, it.after)
        }
        assertEquals("$label matches", rows(expected), rows(actual))
    }

    private fun readFrbMetadata(frbFile: File): ByteArray {
        RandomAccessFile(frbFile, "r").use { raf ->
            val header = ByteArray(64)
            raf.readFully(header)
            val metaLen = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            return ByteArray(metaLen).also { raf.readFully(it) }
        }
    }

    private fun corruptBlock(frbFile: File, block: Int) {
        RandomAccessFile(frbFile, "rw").use { bytes ->
            val header = ByteArray(64).also(bytes::readFully)
            val metadataLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            bytes.seek(64L + metadataLength + block * 48L)
            val entry = ByteArray(48).also(bytes::readFully)
            val offset = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            bytes.seek(offset)
            val original = bytes.readUnsignedByte()
            bytes.seek(offset)
            bytes.writeByte(original xor 0xff)
        }
    }

    private fun buildWoff(storedData: ByteArray, compressedData: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(compressedData)
        deflater.finish()
        val buf = ByteArray(compressedData.size * 2 + 64)
        val n = deflater.deflate(buf)
        deflater.end()
        val deflated = buf.copyOf(n)

        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun tag(s: String) = u32(s.fold(0) { acc, c -> (acc shl 8) or c.code })

        out.write("wOFF".toByteArray())
        u32(0x00010000)
        val dataStart = 44 + 2 * 20
        u32(dataStart + storedData.size + deflated.size)
        u16(2)
        u16(0)
        u32(12 + 2 * 16 + storedData.size + compressedData.size)
        u16(1); u16(0)
        repeat(5) { u32(0) }

        tag("name"); u32(dataStart); u32(storedData.size); u32(storedData.size); u32(0x11223344)
        tag("glyf"); u32(dataStart + storedData.size); u32(deflated.size); u32(compressedData.size); u32(0x55667788)
        out.write(storedData)
        out.write(deflated)
        return out.toByteArray()
    }
}
