package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ParagraphStyle as ComposeParagraphStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.IndexedContentSession
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.BidiLayoutText
import com.example.frogreader.ui.reader.FastParagraphMeasurer
import com.example.frogreader.ui.reader.LAYOUT_ENGINE_VERSION
import com.example.frogreader.ui.reader.MAX_CHAPTER_LAYOUT_WORKERS
import com.example.frogreader.ui.reader.PaginationSpec
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderMetrics
import com.example.frogreader.ui.reader.ReaderTextStyleCache
import com.example.frogreader.ui.reader.createReaderTextMeasurer
import com.example.frogreader.ui.reader.defaultReaderPageWorkerCount
import com.example.frogreader.ui.reader.emitPaginationAnchors
import com.example.frogreader.ui.reader.fastParagraphMeasurerFor
import com.example.frogreader.ui.reader.paginateBook
import com.example.frogreader.ui.reader.paginateIndexedBookAnchors
import com.example.frogreader.ui.theme.TypographyFontFace
import com.example.frogreader.ui.theme.fontTypographyCapabilities
import com.example.frogreader.ui.theme.fontTypographyRequest
import com.example.frogreader.ui.theme.forgetFontTypographyFiles
import com.example.frogreader.ui.theme.registerFontTypography
import com.example.frogreader.ui.theme.resolveFontTypography
import com.example.frogreader.ui.theme.sourceFontTypography
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import kotlin.math.ceil
import kotlin.math.max

class PaginationFastPathTest {

    @Test
    fun `layout engine version remains unchanged and worker count scales up to six`() {
        assertEquals(24, LAYOUT_ENGINE_VERSION)
        assertEquals(6, MAX_CHAPTER_LAYOUT_WORKERS)
        assertEquals(2, defaultReaderPageWorkerCount(1))
        assertEquals(2, defaultReaderPageWorkerCount(2))
        assertEquals(2, defaultReaderPageWorkerCount(4))
        assertEquals(4, defaultReaderPageWorkerCount(6))
        assertEquals(6, defaultReaderPageWorkerCount(8))
        assertEquals(6, defaultReaderPageWorkerCount(16))
        assertTrue(defaultReaderPageWorkerCount() in 2..MAX_CHAPTER_LAYOUT_WORKERS)
    }

    @Test
    fun `text style cache matches ReaderMetrics textStyle across all element kinds and settings`() {
        val bookFonts = mapOf(
            "book-serif" to FontFamily.Serif,
            "book-mono" to FontFamily.Monospace,
        )
        val blocks = listOf(
            null,
            BlockStyle.DEFAULT,
            BlockStyle(align = BlockAlign.CENTER, italic = true, bold = true, fontScale = 0.9f),
            BlockStyle(align = BlockAlign.JUSTIFY, firstLineIndent = false, lineHeightMult = 1.6f),
            BlockStyle(align = BlockAlign.END, firstLineIndent = true, firstLineIndentEm = 2.2f),
            BlockStyle(isCssTextLine = true, firstLineIndentEm = -1.5f),
            BlockStyle(fontFamily = "book-serif", hyphens = false, language = "ru"),
            BlockStyle(fontFamily = "book-mono", hyphens = true, language = "he", direction = BookTextDirection.RTL),
            BlockStyle(fontFamily = "sans-serif", fontWeight = 600, fontFeatureSettings = "\"smcp\" 1"),
            BlockStyle(
                fontFamily = "serif",
                direction = BookTextDirection.LTR,
                fontTypography = FontTypography(
                    variationAxes = mapOf("wght" to 550f),
                    stretchPercent = 95f,
                    opticalSizing = true,
                ),
            ),
            BlockStyle(language = "ar", direction = BookTextDirection.AUTO, bold = false, italic = false),
        )
        val elements = buildList<ContentElement> {
            for (block in blocks) {
                for (style in ParagraphStyle.entries) {
                    add(ContentElement.Paragraph(AnnotatedString("Sample paragraph text"), style = style, block = block))
                }
                for (level in 1..6) {
                    add(ContentElement.Heading("Chapter $level", level = level, block = block))
                    add(ContentElement.Heading("* * *", level = level, block = block))
                }
                add(
                    ContentElement.Table(
                        rows = listOf(TableRow(listOf(TableCell(AnnotatedString("Cell"))), isHeader = true)),
                        block = block,
                    ),
                )
            }
            add(ContentElement.Image("/img.png"))
            add(ContentElement.Divider)
            add(ContentElement.Spacer(1.2f))
        }

        val settingsVariants = listOf(
            ReaderSettings(font = ReaderFont.SERIF, bookStyles = false, justify = false, hyphenation = false),
            ReaderSettings(font = ReaderFont.SANS, bookStyles = false, justify = true, hyphenation = true),
            ReaderSettings(font = ReaderFont.SERIF, bookStyles = true, justify = true, hyphenation = true, lineHeight = 1.45f),
            ReaderSettings(font = ReaderFont.LITERATA, bookStyles = true, justify = false, hyphenation = false, lineHeight = 1.7f),
        )
        val languages = listOf(null, "en", "he", "ru")
        val fontSizes = listOf(14f, 18f, 22.5f)

        for (settings in settingsVariants) {
            for (defaultLanguage in languages) {
                val cache = ReaderTextStyleCache(settings, bookFonts, defaultLanguage)
                for (fontSize in fontSizes) {
                    for (element in elements) {
                        for (isParagraphStart in listOf(true, false)) {
                            val expected = ReaderMetrics.textStyle(
                                element = element,
                                settings = settings,
                                fontSize = fontSize,
                                isParagraphStart = isParagraphStart,
                                bookFonts = bookFonts,
                                language = defaultLanguage,
                            )
                            val cachedFirst = cache.textStyle(element, fontSize, isParagraphStart)
                            val cachedSecond = cache.textStyle(element, fontSize, isParagraphStart)
                            assertEquals(
                                "Mismatch for $element (bookStyles=${settings.bookStyles}, lang=$defaultLanguage, size=$fontSize, start=$isParagraphStart)",
                                expected,
                                cachedFirst,
                            )
                            assertSame(cachedFirst, cachedSecond)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `emitPaginationAnchors produces identical anchors to paginateBook across mixed elements`() = runTest {
        val longParagraph = List(55) { i -> "word$i" }.joinToString(" ")
        val mediumParagraph = List(18) { i -> "token$i" }.joinToString(" ")
        val chapter0 = Chapter(
            title = "Chapter 1",
            elements = listOf(
                ContentElement.Heading("Chapter One", level = 1),
                ContentElement.Paragraph(AnnotatedString(mediumParagraph)),
                ContentElement.Paragraph(AnnotatedString(longParagraph)),
                ContentElement.Spacer(1.0f),
                ContentElement.Divider,
                ContentElement.Heading("* * *", level = 4),
                ContentElement.Paragraph(
                    AnnotatedString(longParagraph),
                    style = ParagraphStyle.QUOTE,
                    block = BlockStyle(italic = true),
                ),
                ContentElement.Image("/nonexistent-aspect-fallback.png", heightEm = 5f),
                ContentElement.Heading("Keep With Next Heading", level = 2),
                ContentElement.Paragraph(AnnotatedString(mediumParagraph)),
                ContentElement.Spacer(1.5f),
                ContentElement.Paragraph(
                    AnnotatedString(mediumParagraph),
                    block = BlockStyle(pageBreakBefore = true),
                ),
                ContentElement.Table(
                    rows = listOf(
                        TableRow(listOf(TableCell(AnnotatedString("Header A")), TableCell(AnnotatedString("Header B"))), isHeader = true),
                        TableRow(listOf(TableCell(AnnotatedString("Row 1 A")), TableCell(AnnotatedString("Row 1 B"))), isHeader = false),
                        TableRow(listOf(TableCell(AnnotatedString("Row 2 A")), TableCell(AnnotatedString("Row 2 B"))), isHeader = false),
                        TableRow(listOf(TableCell(AnnotatedString("Row 3 A")), TableCell(AnnotatedString("Row 3 B"))), isHeader = false),
                        TableRow(listOf(TableCell(AnnotatedString("Row 4 A")), TableCell(AnnotatedString("Row 4 B"))), isHeader = false),
                        TableRow(listOf(TableCell(AnnotatedString("Row 5 A")), TableCell(AnnotatedString("Row 5 B"))), isHeader = false),
                    ),
                ),
            ),
            publisherBoxes = listOf(
                PublisherBoxSpan(
                    id = "avoid-box",
                    startElement = 9,
                    endElementExclusive = 10,
                    style = PublisherBoxStyle(
                        breakInsideAvoid = true,
                        paddingTopEm = 0.5f,
                        paddingBottomEm = 0.5f,
                    ),
                ),
                PublisherBoxSpan(
                    id = "decorated-spacer",
                    startElement = 10,
                    endElementExclusive = 11,
                    style = PublisherBoxStyle(
                        backgroundColorArgb = 0xFFEFEFEFL.toInt(),
                        minHeightEm = 1.5f,
                    ),
                ),
            ),
        )
        val chapter1 = Chapter(
            title = "Chapter 2",
            elements = listOf(
                ContentElement.Heading("Chapter Two", level = 1),
                ContentElement.Paragraph(AnnotatedString(longParagraph)),
                ContentElement.Paragraph(AnnotatedString(mediumParagraph), style = ParagraphStyle.POEM),
                ContentElement.Divider,
                ContentElement.Paragraph(AnnotatedString(longParagraph)),
            ),
        )

        val items = ReaderChapterItems(listOf(chapter0, chapter1))
        val measurer = fakeTextMeasurer()

        for (bookStyles in listOf(false, true)) {
            for (startChaptersOnNewPage in listOf(true, false)) {
                val settings = ReaderSettings(
                    font = ReaderFont.SERIF,
                    fontSizeSp = 18f,
                    lineHeight = 1.4f,
                    bookStyles = bookStyles,
                    startChaptersOnNewPage = startChaptersOnNewPage,
                )
                val spec = PaginationSpec(
                    contentWidthPx = 300,
                    contentHeightPx = 220,
                    density = Density(1f),
                    settings = settings,
                    fontSize = settings.fontSizeSp,
                    language = "en",
                )

                val fullPages = paginateBook(items, measurer, spec)
                val expectedAnchors = IntArray(fullPages.size * 2)
                fullPages.forEachIndexed { idx, page ->
                    expectedAnchors[idx * 2] = page.firstItemIndex
                    expectedAnchors[idx * 2 + 1] = page.firstCharOffset
                }

                val emitted = ArrayList<Int>()
                emitPaginationAnchors(items, measurer, spec) { itemIndex, charOffset ->
                    emitted += itemIndex
                    emitted += charOffset
                }
                val actualAnchors = emitted.toIntArray()

                assertTrue("Expected multi-page pagination", fullPages.size >= 6)
                assertTrue("Expected at least one split paragraph anchor", fullPages.any { it.firstCharOffset > 0 })
                assertArrayEquals(
                    "Anchors diverged for bookStyles=$bookStyles startChaptersOnNewPage=$startChaptersOnNewPage",
                    expectedAnchors,
                    actualAnchors,
                )
            }
        }
    }

    @Test
    fun `paginateIndexedBookAnchors supports six workers and matches paginateBook`() = runTest {
        val chapters = List(12) { chapterIdx ->
            val longText = List(40) { i -> "ch${chapterIdx}w$i" }.joinToString(" ")
            val shortText = List(12) { i -> "ch${chapterIdx}s$i" }.joinToString(" ")
            Chapter(
                title = "Chapter $chapterIdx",
                elements = listOf(
                    ContentElement.Heading("Chapter $chapterIdx", level = 1),
                    ContentElement.Paragraph(AnnotatedString(longText)),
                    ContentElement.Divider,
                    ContentElement.Paragraph(AnnotatedString(shortText)),
                    ContentElement.Paragraph(AnnotatedString(longText)),
                ),
            )
        }
        val session = IndexedBookSession.open(Closeable {}) {
            InMemoryIndexedContentSession(chapters)
        }!!
        try {
            val settings = ReaderSettings(
                font = ReaderFont.SERIF,
                fontSizeSp = 18f,
                lineHeight = 1.4f,
                startChaptersOnNewPage = true,
            )
            val spec = PaginationSpec(
                contentWidthPx = 280,
                contentHeightPx = 200,
                density = Density(1f),
                settings = settings,
                fontSize = settings.fontSizeSp,
                language = "en",
            )

            val fullItems = ReaderChapterItems(chapters)
            val expectedPages = paginateBook(fullItems, fakeTextMeasurer(), spec)
            val expectedAnchors = IntArray(expectedPages.size * 2)
            expectedPages.forEachIndexed { idx, page ->
                expectedAnchors[idx * 2] = page.firstItemIndex
                expectedAnchors[idx * 2 + 1] = page.firstCharOffset
            }

            val measurers6 = List(MAX_CHAPTER_LAYOUT_WORKERS) { fakeTextMeasurer() }
            val completedChapters = Collections.synchronizedSet(HashSet<Int>())
            val actualAnchors = paginateIndexedBookAnchors(
                session = session,
                cover = null,
                measurers = measurers6,
                spec = spec,
                preferredChapter = { 5 },
                onChapterReady = { chapterIdx, _ -> completedChapters += chapterIdx },
            )

            assertEquals((0 until chapters.size).toSet(), completedChapters)
            assertArrayEquals(expectedAnchors, actualAnchors)

            // Exceeding MAX_CHAPTER_LAYOUT_WORKERS must still fail fast.
            val tooManyMeasurers = List(MAX_CHAPTER_LAYOUT_WORKERS + 1) { fakeTextMeasurer() }
            val error = runCatching {
                paginateIndexedBookAnchors(session, null, tooManyMeasurers, spec)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        } finally {
            session.close()
        }
    }

    @Test
    fun `BidiLayoutText returns identity for plain text and still transforms bidi annotations`() {
        val plain = AnnotatedString("Plain paragraph text with no bidi tags.")
        val plainBidi = BidiLayoutText.of(plain)
        assertFalse(plainBidi.hasLayoutChanges)
        assertSame(plain, plainBidi.source)
        assertSame(plain, plainBidi.display)
        assertEquals(5, plainBidi.layoutStart(5))
        assertEquals(12, plainBidi.layoutEnd(12))
        assertEquals(7, plainBidi.sourceOffset(7))

        val withBidi = buildAnnotatedString {
            append("Hello ")
            val start = length
            append("שלום")
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, start, length)
            append(" world")
        }
        val transformed = BidiLayoutText.of(withBidi)
        assertTrue(transformed.hasLayoutChanges)
        assertTrue(transformed.display.text.contains('\u2067'))
        assertTrue(transformed.display.text.contains('\u2069'))
        assertEquals(6, transformed.sourceOffset(transformed.layoutStart(6)))
    }

    @Test
    fun `FontFamilyTypography concurrent reads and forgetFontTypographyFiles are thread safe`() {
        val path = "/tmp/frogreader-concurrent-test-font.ttf"
        val baseFamily = FontFamily.Serif
        val faces = listOf(
            TypographyFontFace(
                path = path,
                weight = 400,
                italic = false,
            ),
        )
        val registered = registerFontTypography(
            family = baseFamily,
            faces = faces,
            request = FontTypography(opticalSizing = true),
        )
        assertNotNull(sourceFontTypography(registered))
        assertEquals(FontTypography(opticalSizing = true), fontTypographyRequest(registered))

        val workerCount = 6
        val pool = Executors.newFixedThreadPool(workerCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(workerCount) { workerIdx ->
                pool.submit {
                    try {
                        startLatch.await(5, TimeUnit.SECONDS)
                        for (iter in 0 until 400) {
                            if (workerIdx == 0 && iter % 50 == 0) {
                                forgetFontTypographyFiles(listOf(path))
                                registerFontTypography(
                                    family = baseFamily,
                                    faces = faces,
                                    request = FontTypography(opticalSizing = true),
                                )
                            } else {
                                sourceFontTypography(baseFamily)
                                fontTypographyRequest(baseFamily)
                                fontTypographyCapabilities(baseFamily)
                                resolveFontTypography(baseFamily, 18f)
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue("Concurrent typography workers timed out", doneLatch.await(15, TimeUnit.SECONDS))
            assertNull("Concurrent typography threw: ${failure.get()}", failure.get())
        } finally {
            pool.shutdownNow()
            forgetFontTypographyFiles(listOf(path))
        }
    }

    @Test
    fun `FastParagraphMeasurer registers cleanly and falls back safely on JVM and unsupported inputs`() {
        val resolver = mock(FontFamily.Resolver::class.java)
        val density = Density(2f)
        val registeredMeasurer = createReaderTextMeasurer(
            fontFamilyResolver = resolver,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            cacheSize = 0,
        )
        assertNotNull(fastParagraphMeasurerFor(registeredMeasurer))

        val fake = fakeTextMeasurer()
        assertNull(fastParagraphMeasurerFor(fake))
        val fast = FastParagraphMeasurer(
            measurer = fake,
            fontFamilyResolver = resolver,
            layoutDirection = LayoutDirection.Ltr,
        )

        val baseStyle = TextStyle(fontSize = 18.sp, lineHeight = 25.sp)
        val plain = AnnotatedString("First sentence of a chapter paragraph for fallback testing.")
        // On JVM (Build.VERSION.SDK_INT == 0), measureDirectOrNull returns null and measure() falls back cleanly.
        assertNull(fast.measureDirectOrNull(plain, baseStyle, 300, density))
        val fallbackMeasured = fast.measure(plain, baseStyle, 300, density)
        assertTrue(fallbackMeasured.lineCount >= 1)
        assertTrue(fallbackMeasured.height > 0)
        assertEquals(0, fallbackMeasured.getLineForOffset(0))
        assertEquals(0f, fallbackMeasured.getLineTop(0))
        assertTrue(fallbackMeasured.getLineBottom(0) > 0f)
        assertTrue(fallbackMeasured.getLineEnd(0) > 0)

        // Unsupported style / span / inline inputs must return null from measureDirectOrNull and fall back cleanly.
        val letterSpacingStyle = baseStyle.copy(letterSpacing = 1.sp)
        assertNull(fast.measureDirectOrNull(plain, letterSpacingStyle, 300, density))
        assertTrue(fast.measure(plain, letterSpacingStyle, 300, density).height > 0)

        val featureStyle = baseStyle.copy(fontFeatureSettings = "\"smcp\" 1")
        assertNull(fast.measureDirectOrNull(plain, featureStyle, 300, density))
        assertTrue(fast.measure(plain, featureStyle, 300, density).height > 0)

        val fontSizeSpanText = buildAnnotatedString {
            append("Normal ")
            pushStyle(SpanStyle(fontSize = 24.sp))
            append("bigger")
            pop()
        }
        assertNull(fast.measureDirectOrNull(fontSizeSpanText, baseStyle, 300, density))
        assertTrue(fast.measure(fontSizeSpanText, baseStyle, 300, density).height > 0)

        val multiParagraphText = buildAnnotatedString {
            pushStyle(ComposeParagraphStyle())
            append("Para 1\n")
            pop()
            pushStyle(ComposeParagraphStyle())
            append("Para 2")
            pop()
        }
        assertNull(fast.measureDirectOrNull(multiParagraphText, baseStyle, 300, density))
        assertTrue(fast.measure(multiParagraphText, baseStyle, 300, density).height > 0)

        val inlineTagText = buildAnnotatedString {
            append("Before ")
            val start = length
            append("\uFFFC")
            addStringAnnotation(INLINE_IMAGE_TAG, "img-1", start, length)
            append(" after")
        }
        assertNull(fast.measureDirectOrNull(inlineTagText, baseStyle, 300, density))
        assertTrue(fast.measure(inlineTagText, baseStyle, 300, density).height > 0)
    }

    /**
     * Deterministic JVM [TextMeasurer] double that wraps text by character count
     * and returns real [TextLayoutResult] geometry without requiring Android's
     * native StaticLayout implementation.
     */
    private fun fakeTextMeasurer(): TextMeasurer = mock(
        TextMeasurer::class.java,
        Answer { invocation ->
            val args = invocation.arguments
            val text = when (val first = args.firstOrNull()) {
                is AnnotatedString -> first.text
                is String -> first
                else -> ""
            }
            val style = args.getOrNull(1) as? TextStyle ?: TextStyle.Default
            val maxWidthPx = extractMaxWidthPx(args)
            buildFakeTextLayoutResult(text, style, maxWidthPx)
        },
    )

    private fun extractMaxWidthPx(args: Array<Any?>): Int {
        for (arg in args) {
            if (arg is Constraints) {
                return if (arg.hasBoundedWidth) arg.maxWidth else 400
            }
        }
        // Fallback when Mockito receives the unboxed Long backing Constraints.
        val raw = args.getOrNull(6) as? Long
        if (raw != null) {
            // Decode Compose Constraints maxWidth conservatively or fall back.
            return 300
        }
        return 300
    }

    private fun buildFakeTextLayoutResult(
        text: String,
        style: TextStyle,
        maxWidthPx: Int,
    ): TextLayoutResult {
        val fontSizePx = if (style.fontSize.value.isFinite() && style.fontSize.value > 0f) {
            style.fontSize.value
        } else 18f
        val lineHeightPx = if (style.lineHeight.value.isFinite() && style.lineHeight.value > 0f) {
            style.lineHeight.value
        } else fontSizePx * 1.4f
        val charWidth = max(6f, fontSizePx * 0.55f)
        val charsPerLine = max(4, (maxWidthPx / charWidth).toInt())

        val lineStarts = ArrayList<Int>()
        val lineEnds = ArrayList<Int>()
        if (text.isEmpty()) {
            lineStarts += 0
            lineEnds += 0
        } else {
            var pos = 0
            while (pos < text.length) {
                val tentativeEnd = minOf(text.length, pos + charsPerLine)
                val end = if (tentativeEnd < text.length) {
                    val space = text.lastIndexOf(' ', tentativeEnd - 1)
                    if (space > pos) space + 1 else tentativeEnd
                } else {
                    tentativeEnd
                }
                lineStarts += pos
                lineEnds += end
                pos = end
            }
        }
        val lineCount = lineStarts.size
        val totalHeight = ceil(lineCount * lineHeightPx).toInt().coerceAtLeast(1)

        val multiParagraph = mock(
            MultiParagraph::class.java,
            Answer { inv ->
                when (inv.method.name) {
                    "getLineCount" -> lineCount
                    "getHeight" -> totalHeight.toFloat()
                    "getWidth" -> maxWidthPx.toFloat()
                    "getLineTop" -> {
                        val line = (inv.arguments[0] as Int).coerceIn(0, lineCount - 1)
                        line * lineHeightPx
                    }
                    "getLineBottom" -> {
                        val line = (inv.arguments[0] as Int).coerceIn(0, lineCount - 1)
                        (line + 1) * lineHeightPx
                    }
                    "getLineEnd" -> {
                        val line = (inv.arguments[0] as Int).coerceIn(0, lineCount - 1)
                        lineEnds[line]
                    }
                    "getLineStart" -> {
                        val line = (inv.arguments[0] as Int).coerceIn(0, lineCount - 1)
                        lineStarts[line]
                    }
                    "getLineForOffset" -> {
                        val offset = inv.arguments[0] as Int
                        var found = lineCount - 1
                        for (i in 0 until lineCount) {
                            if (offset < lineEnds[i]) {
                                found = i
                                break
                            }
                        }
                        found
                    }
                    else -> null
                }
            },
        )
        return TextLayoutResult(
            layoutInput = mock(TextLayoutInput::class.java),
            multiParagraph = multiParagraph,
            size = IntSize(maxWidthPx, totalHeight),
        )
    }

    private class InMemoryIndexedContentSession(
        private val chapters: List<Chapter>,
    ) : IndexedContentSession {
        override val index: BookIndex = run {
            var start = 0
            val descriptors = chapters.map { chapter ->
                BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also {
                    start += chapter.elements.size
                }
            }
            BookIndex(
                chapters = descriptors,
                navigation = emptyList(),
                linkTargets = emptyMap(),
                linkedDocumentTargets = emptyMap(),
                fonts = emptyList(),
                language = "en",
                pageProgression = PageProgression.LTR,
                totalElements = start,
                resourcePaths = emptyList(),
            )
        }
        override val contentKey: String = "in-memory-test-key"
        override fun readChapter(index: Int): Chapter? = chapters.getOrNull(index)
        override fun readGlobals(): BookContent = BookContent(emptyList())
        override fun close() = Unit
    }
}
