package com.example.frogreader.reader

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.PaginationSpec
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.ReaderPageCheckpoint
import com.example.frogreader.ui.reader.ReaderPageIndex
import com.example.frogreader.ui.reader.ReaderPageIndexCache
import com.example.frogreader.ui.reader.ReaderPaginationRange
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.ReaderTextStyleCache
import com.example.frogreader.ui.reader.SideBoxSpec
import com.example.frogreader.ui.reader.TableLayout
import com.example.frogreader.ui.reader.adjustReaderElement
import com.example.frogreader.ui.reader.createReaderTextMeasurer
import com.example.frogreader.ui.reader.fastParagraphMeasurerFor
import com.example.frogreader.ui.reader.measureReader
import com.example.frogreader.ui.reader.paginateBook
import com.example.frogreader.ui.reader.paginateIndexedBookAnchors
import com.example.frogreader.ui.reader.paginateIndexedBookPages
import com.example.frogreader.ui.reader.resolveExactQuickPaginationStart
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexedPaginationPassTest {
    @Test fun indexedAnchorsMatchRealWholeBookLayout() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File.createTempFile("indexed-pagination-", ".test", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            val density = Density(1f)
            lateinit var measurers: List<TextMeasurer>
            instrumentation.runOnMainSync {
                measurers = List(4) {
                    createReaderTextMeasurer(
                        fontFamilyResolver = createFontFamilyResolver(context),
                        density = density,
                        layoutDirection = LayoutDirection.Ltr,
                        cacheSize = 0,
                    )
                }
            }
            val measurer = measurers.first()
            val image = File(root, "illustration.png")
            val bitmap = Bitmap.createBitmap(96, 24, Bitmap.Config.ARGB_8888)
            try {
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val cover = ReaderItem(0, ContentElement.Image(image.path))
            val front = Chapter("Front matter", listOf(paragraph("Preface.")))
            val chapters = listOf(
                Chapter("Long paragraph", listOf(
                    paragraph("An extended paragraph with ordinary words. ".repeat(240)),
                    ContentElement.Heading("A heading before the next chapter", 2),
                )),
                Chapter("Empty section", emptyList()),
                Chapter("Float and table", listOf(
                    ContentElement.Image(image.path, widthFrac = 0.3f),
                    paragraph("Caption"),
                    ContentElement.Spacer(heightEm = 0.25f),
                    ContentElement.Spacer(heightEm = 0.25f),
                    paragraph("Text beside the illustration. ".repeat(14)),
                    ContentElement.Table(List(45) { row ->
                        TableRow(listOf(TableCell(AnnotatedString("Row $row"))), isHeader = row == 0)
                    }),
                ), publisherBoxes = listOf(
                    PublisherBoxSpan("figure", startElement = 0, endElementExclusive = 4,
                        floatSide = PublisherFloatSide.LEFT),
                )),
                Chapter("Short section", listOf(ContentElement.Heading("Another heading", 2))),
                Chapter("Short paragraph", listOf(paragraph("First short paragraph."))),
                Chapter("Page break after", listOf(paragraph("Second short paragraph.")), publisherBoxes = listOf(
                    PublisherBoxSpan("page-break", startElement = 0, endElementExclusive = 1,
                        style = PublisherBoxStyle(pageBreakAfter = true)),
                )),
                Chapter("Last paragraph", listOf(paragraph("Starts a new page."))),
                Chapter("Trailing empty section", emptyList()),
            )
            val settings = ReaderSettings(font = ReaderFont.SERIF, startChaptersOnNewPage = true, bookStyles = true)
            val spec = PaginationSpec(300, 290, density, settings, 18f)
            val source = File(root, "source.epub").apply { writeText("Synthetic FRB source identity") }
            val cache = ChunkedContentCache(File(root, "reader-content"))
            for (opening in listOf(front, Chapter("Empty front matter", emptyList()))) {
                val publication = listOf(opening) + chapters
                assertTrue(cache.save("pagination", source, BookContent(publication)))
                val original = ReaderChapterItems(publication, cover) {
                    adjustReaderElement(it, settings.hideFootnotes)
                }
                val expected = paginateBook(original, measurer, spec)
                assertTrue(expected.any { page -> page.parts.any { it.charStart > 0 && it.text != null } })
                assertTrue(expected.any { page -> page.parts.any { it.rowStart > 0 } })
                val session = requireNotNull(IndexedBookSession.open(Closeable {}) {
                    cache.openIndexed("pagination", source)
                })
                session.use {
                    val expectedAnchors = expected.flatMap { listOf(it.firstItemIndex, it.firstCharOffset) }.toIntArray()
                    val emitted = mutableListOf<Pair<Int, IntArray>>()
                    val preferredChapter = publication.lastIndex
                    val actual = paginateIndexedBookAnchors(
                        session, cover, measurers.take(1), spec, preferredChapter = { preferredChapter },
                    ) { chapter, anchors -> emitted += chapter to anchors }
                    assertArrayEquals(expectedAnchors, actual)
                    assertEquals(preferredChapter, emitted.first().first)
                    assertEquals(publication.indices.toList(), emitted.map { it.first }.sorted())
                    val byChapter = emitted.toMap()
                    assertArrayEquals(actual, publication.indices.flatMap { byChapter.getValue(it).asList() }.toIntArray())
                    assertTrue(byChapter.getValue(2).isEmpty())
                    assertTrue(byChapter.getValue(publication.lastIndex).isEmpty())
                    if (opening.elements.isEmpty()) assertArrayEquals(intArrayOf(0, 0), byChapter.getValue(0))
                    for (workers in listOf(2, 4)) {
                        val parallel = paginateIndexedBookAnchors(
                            session, cover, measurers.take(workers), spec, preferredChapter = { preferredChapter },
                        )
                        assertArrayEquals("$workers workers", expectedAnchors, parallel)
                    }
                    val checkpointDir = File(root, "checkpoint")
                    val checkpointKey = ByteArray(32) { it.toByte() }
                    val stoppedChapter = publication.lastIndex - 1
                    val interrupted = ReaderPageCheckpoint.open(
                        checkpointDir, checkpointKey, original.chapterStarts, original.size,
                    ).use { checkpoint ->
                        coroutineScope {
                            val pass = async {
                                val passJob = currentCoroutineContext().job
                                paginateIndexedBookAnchors(
                                    session, cover, measurers.take(1), spec, preferredChapter = { stoppedChapter },
                                ) { chapter, anchors ->
                                    assertEquals(stoppedChapter, chapter)
                                    checkpoint.record(chapter, anchors)
                                    passJob.cancel(CancellationException("Stop after the first persisted chapter"))
                                    passJob.ensureActive()
                                }
                            }
                            runCatching { pass.await() }.exceptionOrNull()
                        }
                    }
                    assertTrue("Expected cancellation of the pagination pass, got $interrupted",
                        interrupted is CancellationException)
                    val completed = ReaderPageCheckpoint.open(
                        checkpointDir, checkpointKey, original.chapterStarts, original.size,
                    ).use { checkpoint ->
                        val durable = checkpoint.completed
                        assertEquals(setOf(stoppedChapter), durable.keys)
                        assertArrayEquals(byChapter.getValue(stoppedChapter), durable.getValue(stoppedChapter))
                        checkpoint.remove()
                        durable + byChapter.filterKeys { it in setOf(0, 2, preferredChapter) }
                    }
                    val resumed = mutableListOf<Int>()
                    val resumedAnchors = paginateIndexedBookAnchors(
                        session, cover, measurers.take(2), spec, preferredChapter = { preferredChapter }, completed = completed,
                    ) { chapter, anchors ->
                        resumed += chapter
                        assertArrayEquals(byChapter.getValue(chapter), anchors)
                    }
                    assertEquals(publication.indices.filterNot(completed::containsKey), resumed.sorted())
                    assertArrayEquals("resumed chapter calculation", expectedAnchors, resumedAnchors)
                    val continuous = PaginationSpec(300, 290, density, settings.copy(startChaptersOnNewPage = false), 18f)
                    assertTrue(runCatching {
                        paginateIndexedBookAnchors(session, cover, measurers.take(1), continuous)
                    }.exceptionOrNull() is IllegalArgumentException)
                    val continuousExpected = paginateBook(original, measurer, continuous)
                    assertTrue(continuousExpected.any { page ->
                        page.parts.map { original[it.itemIndex].chapterIndex }.distinct().size > 1
                    })
                    assertTrue(continuousExpected.any { page ->
                        page.parts.any { it.sideBox?.publisherFloat?.contents?.size == 4 }
                    })
                    assertTrue(continuousExpected.any { page ->
                        page.firstItemIndex == original.chapterStarts[publication.lastIndex - 1]
                    })
                    for ((mode, wholeBookPages) in listOf(spec to expected, continuous to continuousExpected)) {
                        var emittedPages = 0
                        paginateIndexedBookPages(session, cover, measurer, mode) { page ->
                            assertTrue(emittedPages < wholeBookPages.size)
                            assertPageEquals(emittedPages, wholeBookPages[emittedPages], page)
                            emittedPages++
                        }
                        assertEquals(wholeBookPages.size, emittedPages)
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun processDeathCheckpointRecoveryAndThemeVsTypographyKeyIsolation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File.createTempFile("indexed-checkpoint-kill-", ".test", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            val density = Density(1f)
            lateinit var measurers: List<TextMeasurer>
            instrumentation.runOnMainSync {
                measurers = List(2) {
                    createReaderTextMeasurer(
                        fontFamilyResolver = createFontFamilyResolver(context),
                        density = density,
                        layoutDirection = LayoutDirection.Ltr,
                        cacheSize = 0,
                    )
                }
            }
            val image = File(root, "illustration.png")
            val bitmap = Bitmap.createBitmap(96, 24, Bitmap.Config.ARGB_8888)
            try {
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val cover = ReaderItem(0, ContentElement.Image(image.path))
            val publication = listOf(
                Chapter("Front matter", listOf(paragraph("Preface."))),
                Chapter("Long paragraph", listOf(
                    paragraph("An extended paragraph with ordinary words. ".repeat(180)),
                    ContentElement.Heading("A heading before the next chapter", 2),
                )),
                Chapter("Empty section", emptyList()),
                Chapter("Float and table", listOf(
                    ContentElement.Image(image.path, widthFrac = 0.3f),
                    paragraph("Caption"),
                    ContentElement.Spacer(heightEm = 0.25f),
                    ContentElement.Spacer(heightEm = 0.25f),
                    paragraph("Text beside the illustration. ".repeat(12)),
                    ContentElement.Table(List(30) { row ->
                        TableRow(listOf(TableCell(AnnotatedString("Row $row"))), isHeader = row == 0)
                    }),
                ), publisherBoxes = listOf(
                    PublisherBoxSpan("figure", startElement = 0, endElementExclusive = 4,
                        floatSide = PublisherFloatSide.LEFT),
                )),
                Chapter("Short section", listOf(ContentElement.Heading("Another heading", 2))),
                Chapter("Preferred start", listOf(paragraph("Reading position paragraph. ".repeat(45)))),
                Chapter("Page break after", listOf(paragraph("Second short paragraph.")), publisherBoxes = listOf(
                    PublisherBoxSpan("page-break", startElement = 0, endElementExclusive = 1,
                        style = PublisherBoxStyle(pageBreakAfter = true)),
                )),
                Chapter("Last paragraph", listOf(paragraph("Starts a new page."))),
            )
            val baseSettings = ReaderSettings(
                font = ReaderFont.SERIF,
                fontSizeSp = 18f,
                lineHeight = 1.5f,
                pageMargins = PageMargins.NORMAL,
                pageMarginDp = 20,
                startChaptersOnNewPage = true,
                bookStyles = true,
            )
            fun specFor(settings: ReaderSettings): PaginationSpec {
                val contentWidthPx = 340 - (settings.sideMarginDp * 2 * density.density).toInt()
                return PaginationSpec(contentWidthPx, 290, density, settings, settings.fontSizeSp, language = "en")
            }
            val environment = "rtl=false|locale=en-US|device-test"
            fun layoutKeyFor(contentKey: String, settings: ReaderSettings): ByteArray =
                ReaderPageIndexCache.key(contentKey, specFor(settings), coverPath = image.path, environment = environment)

            val bookId = "checkpoint-kill"
            val source = File(root, "source.epub").apply { writeText("Synthetic FRB source identity for kill recovery") }
            val cache = ChunkedContentCache(File(root, "reader-content"))
            assertTrue(cache.save(bookId, source, BookContent(publication)))
            val original = ReaderChapterItems(publication, cover) {
                adjustReaderElement(it, baseSettings.hideFootnotes)
            }
            val preferredChapter = 5
            val baseSpec = specFor(baseSettings)
            val uninterruptedRpi = File(root, "uninterrupted.rpi")

            // 1. Baseline uninterrupted pass persisted to .rpi.
            val (baseKey, expectedAnchors) = requireNotNull(IndexedBookSession.open(Closeable {}) {
                cache.openIndexed(bookId, source)
            }).use { session ->
                val key = layoutKeyFor(session.contentKey, baseSettings)
                val anchors = paginateIndexedBookAnchors(
                    session, cover, measurers.take(2), baseSpec, preferredChapter = { preferredChapter },
                )
                assertTrue(ReaderPageIndexCache.save(uninterruptedRpi, key, original.size, anchors))
                key to anchors
            }

            // 2. Partial pass from preferredChapter = 5 outward, killed after 3 chapters (5, 4, 6),
            // closing both ReaderPageCheckpoint and IndexedBookSession to simulate process death.
            val checkpointDir = File(root, "checkpoint")
            val persistedBeforeKill = mutableListOf<Int>()
            val preKillAnchors = mutableMapOf<Int, IntArray>()
            requireNotNull(IndexedBookSession.open(Closeable {}) {
                cache.openIndexed(bookId, source)
            }).use { session1 ->
                val key1 = layoutKeyFor(session1.contentKey, baseSettings)
                assertArrayEquals(baseKey, key1)
                ReaderPageCheckpoint.open(
                    checkpointDir, key1, original.chapterStarts, original.size,
                ).use { checkpoint1 ->
                    val interrupted = coroutineScope {
                        val pass = async {
                            val passJob = currentCoroutineContext().job
                            paginateIndexedBookAnchors(
                                session1, cover, measurers.take(1), baseSpec, preferredChapter = { preferredChapter },
                            ) { chapter, anchors ->
                                persistedBeforeKill += chapter
                                preKillAnchors[chapter] = anchors.copyOf()
                                checkpoint1.record(chapter, anchors)
                                if (persistedBeforeKill.size == 3) {
                                    checkpoint1.flush()
                                    passJob.cancel(CancellationException("Simulated process death after 3 chapters"))
                                    passJob.ensureActive()
                                }
                            }
                        }
                        runCatching { pass.await() }.exceptionOrNull()
                    }
                    assertTrue(interrupted is CancellationException)
                }
            }
            assertEquals(listOf(5, 4, 6), persistedBeforeKill)

            // 3. Reopen a brand-new IndexedBookSession from disk and verify layout-key isolation vs theme invariance.
            requireNotNull(IndexedBookSession.open(Closeable {}) {
                cache.openIndexed(bookId, source)
            }).use { session2 ->
                for (invalidating in listOf(
                    baseSettings.copy(fontSizeSp = 21f),
                    baseSettings.copy(lineHeight = 1.75f),
                    baseSettings.copy(font = ReaderFont.SANS),
                    baseSettings.copy(justify = true),
                    baseSettings.copy(pageMarginDp = 10),
                    baseSettings.copy(pageMarginDp = null, pageMargins = PageMargins.WIDE),
                )) {
                    val changedKey = layoutKeyFor(session2.contentKey, invalidating)
                    assertFalse(baseKey.contentEquals(changedKey))
                    ReaderPageCheckpoint.open(
                        checkpointDir, changedKey, original.chapterStarts, original.size,
                    ).use { ignored ->
                        assertTrue(ignored.completed.isEmpty())
                    }
                }

                val themeChangedSettings = baseSettings.copy(
                    invertImages = true,
                    pdfTheme = AppTheme.SEPIA,
                    pageTurnAnimation = PageTurnAnimation.SLIDE,
                    showTotalPageCount = true,
                )
                val themeKey = layoutKeyFor(session2.contentKey, themeChangedSettings)
                assertArrayEquals(baseKey, themeKey)

                val resumedRpi = File(root, "resumed.rpi")
                ReaderPageCheckpoint.open(
                    checkpointDir, themeKey, original.chapterStarts, original.size,
                ).use { checkpoint2 ->
                    val recovered = checkpoint2.completed
                    assertEquals(setOf(4, 5, 6), recovered.keys)
                    for ((chapter, anchors) in preKillAnchors) {
                        assertArrayEquals(anchors, recovered.getValue(chapter))
                    }
                    val resumedOrder = mutableListOf<Int>()
                    val resumedAnchors = paginateIndexedBookAnchors(
                        session2,
                        cover,
                        measurers.take(1),
                        specFor(themeChangedSettings),
                        preferredChapter = { preferredChapter },
                        completed = recovered,
                    ) { chapter, anchors ->
                        resumedOrder += chapter
                        checkpoint2.record(chapter, anchors)
                    }
                    assertEquals(listOf(3, 7, 2, 1, 0), resumedOrder)
                    assertArrayEquals(expectedAnchors, resumedAnchors)
                    assertTrue(ReaderPageIndexCache.save(resumedRpi, themeKey, original.size, resumedAnchors))
                    checkpoint2.remove()
                }
                assertArrayEquals(uninterruptedRpi.readBytes(), resumedRpi.readBytes())
                val loaded = requireNotNull(ReaderPageIndexCache.load(resumedRpi, themeKey, original.size))
                assertArrayEquals(expectedAnchors, loaded.anchors)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun fastParagraphLayoutMatchesComposeTextMeasurerExactly() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = Density(context)
        val maxWidthPx = (360f * density.density).toInt().coerceAtLeast(360)
        val pageHeightPx = (640f * density.density).toInt().coerceAtLeast(640)

        lateinit var composeMeasurer: TextMeasurer
        lateinit var fastMeasurer: TextMeasurer
        instrumentation.runOnMainSync {
            composeMeasurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr,
                cacheSize = 0,
            )
            fastMeasurer = createReaderTextMeasurer(
                fontFamilyResolver = createFontFamilyResolver(context),
                density = density,
                layoutDirection = LayoutDirection.Ltr,
                cacheSize = 0,
            )
        }
        val fastParagraphMeasurer = requireNotNull(fastParagraphMeasurerFor(fastMeasurer))

        data class SampleText(val name: String, val language: String, val text: AnnotatedString)

        val shortText = AnnotatedString("Short one-line paragraph.")
        val twoLineText = AnnotatedString(
            "A compact two-line sentence that wraps neatly across the reading column width once.",
        )
        val sixLineText = AnnotatedString(
            "Typographic line breaking balances inter-word spacing across multiple lines so every paragraph reads cleanly on a handheld screen without jagged rags or awkward orphans. "
                .repeat(3).trimEnd(),
        )
        val eighteenLineText = AnnotatedString(
            "In long-form reading engines, measuring thousands of prose paragraphs quickly while keeping byte-identical line breaks and vertical metrics matters for instant chapter navigation and background pagination. "
                .repeat(9).trimEnd(),
        )
        val russianText = AnnotatedString(
            "Синхронизация типографических переносных алгоритмов воспроизводится в реальной конфигурации устройства совершенно точно и без единой ошибки межстрочного интервала и длинных многосложных выражений. "
                .repeat(4).trimEnd(),
        )
        val hebrewText = AnnotatedString(
            "הספרייה הדיגיטלית מציגה פסקאות בעברית בצורה מדויקת עם שבירת שורות תקינה וסימני פיסוק בסוף משפט, כולל שילוב מספרים כמו 2026 בתוך הטקסט הרציף. "
                .repeat(3).trimEnd(),
        )
        val styledSpansText = buildAnnotatedString {
            append("\"Wait—really?!\" she asked, checking ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append("italicized phrasing")
            }
            append(" beside ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("strong emphasis")
            }
            append(" and ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                append("bold-italic words")
            }
            append(", plus a ")
            withStyle(SpanStyle(color = Color(0xFF1B5E20), textDecoration = TextDecoration.Underline)) {
                append("styled reference link")
            }
            append(" before an explicit line break.\nSecond line after break with trailing spaces   ")
        }

        val samples = listOf(
            SampleText("en-1line", "en", shortText),
            SampleText("en-2line", "en", twoLineText),
            SampleText("en-6line", "en", sixLineText),
            SampleText("en-18line", "en", eighteenLineText),
            SampleText("ru-hyphen", "ru", russianText),
            SampleText("he-rtl", "he", hebrewText),
            SampleText("styled-spans", "en", styledSpansText),
        )

        val fonts = listOf(ReaderFont.LITERATA, ReaderFont.SERIF, ReaderFont.SANS)
        val fontSizeSp = 18f
        val constraints = Constraints(maxWidth = maxWidthPx)

        var checkedCases = 0
        for (font in fonts) {
            for (bookStyles in listOf(true, false)) {
                for (justify in listOf(false, true)) {
                    for (hyphenation in listOf(false, true)) {
                        val settings = ReaderSettings(
                            font = font,
                            fontSizeSp = fontSizeSp,
                            lineHeight = 1.5f,
                            justify = justify,
                            hyphenation = hyphenation,
                            bookStyles = bookStyles,
                        )
                        for (sample in samples) {
                            val styleCache = ReaderTextStyleCache(
                                settings = settings,
                                defaultLanguage = sample.language,
                            )
                            for (indentEm in listOf(0f, 1.5f)) {
                                val block = BlockStyle(
                                    firstLineIndent = indentEm > 0f,
                                    firstLineIndentEm = indentEm.takeIf { it > 0f },
                                )
                                val baseStyle = styleCache.textStyle(
                                    element = ContentElement.Paragraph(sample.text, block = block),
                                    fontSize = fontSizeSp,
                                    isParagraphStart = indentEm > 0f,
                                )
                                val style = baseStyle.copy(
                                    textIndent = if (indentEm > 0f) {
                                        TextIndent(firstLine = (fontSizeSp * indentEm).sp)
                                    } else {
                                        TextIndent.None
                                    },
                                )
                                val caseLabel =
                                    "font=$font bookStyles=$bookStyles justify=$justify hyphen=$hyphenation indent=$indentEm sample=${sample.name}"
                                val expected = composeMeasurer.measureReader(
                                    text = sample.text,
                                    style = style,
                                    constraints = constraints,
                                    density = density,
                                    publisherStyles = bookStyles,
                                )
                                val fast = fastParagraphMeasurer.measureDirectOrNull(
                                    text = sample.text,
                                    style = style,
                                    maxWidthPx = maxWidthPx,
                                    density = density,
                                    publisherStyles = bookStyles,
                                )
                                assertNotNull("Expected direct StaticLayout fast path for $caseLabel", fast)
                                fast!!
                                assertEquals("lineCount mismatch for $caseLabel", expected.lineCount, fast.lineCount)
                                assertEquals("height mismatch for $caseLabel", expected.size.height, fast.height)
                                for (l in 0 until expected.lineCount) {
                                    assertEquals(
                                        "lineTop($l) mismatch for $caseLabel",
                                        expected.getLineTop(l),
                                        fast.getLineTop(l),
                                        0.01f,
                                    )
                                    assertEquals(
                                        "lineBottom($l) mismatch for $caseLabel",
                                        expected.getLineBottom(l),
                                        fast.getLineBottom(l),
                                        0.01f,
                                    )
                                    assertEquals(
                                        "lineEnd($l) mismatch for $caseLabel",
                                        expected.getLineEnd(l),
                                        fast.getLineEnd(l),
                                    )
                                }
                                val offsets = buildSet {
                                    add(0)
                                    add(sample.text.length)
                                    val step = (sample.text.length / 8).coerceAtLeast(1)
                                    var off = 0
                                    while (off < sample.text.length) {
                                        add(off)
                                        off += step
                                    }
                                    add((sample.text.length - 1).coerceAtLeast(0))
                                }
                                for (off in offsets) {
                                    assertEquals(
                                        "getLineForOffset($off) mismatch for $caseLabel",
                                        expected.getLineForOffset(off),
                                        fast.getLineForOffset(off),
                                    )
                                }
                                checkedCases++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Expected full parity matrix coverage, checked=$checkedCases", checkedCases == 336)

        // Whole-book paginateBook parity with Literata + bookStyles=true/false + italic/bold spans.
        val syntheticChapters = listOf(
            Chapter("Chapter 1", listOf(
                ContentElement.Heading("Chapter One: Typography", 1),
                ContentElement.Paragraph(styledSpansText),
                ContentElement.Paragraph(eighteenLineText),
                ContentElement.Paragraph(sixLineText),
            )),
            Chapter("Chapter 2", listOf(
                ContentElement.Heading("Chapter Two: Russian", 2),
                ContentElement.Paragraph(russianText),
                ContentElement.Paragraph(twoLineText),
                ContentElement.Paragraph(shortText),
            )),
            Chapter("Chapter 3", listOf(
                ContentElement.Heading("Chapter Three: Mixed", 2),
                ContentElement.Paragraph(styledSpansText),
                ContentElement.Paragraph(eighteenLineText),
            )),
        )
        for (bookStyles in listOf(true, false)) {
            val bookSettings = ReaderSettings(
                font = ReaderFont.LITERATA,
                fontSizeSp = fontSizeSp,
                lineHeight = 1.5f,
                justify = true,
                hyphenation = true,
                startChaptersOnNewPage = true,
                bookStyles = bookStyles,
            )
            val bookItems = ReaderChapterItems(syntheticChapters) {
                adjustReaderElement(it, bookSettings.hideFootnotes)
            }
            val spec = PaginationSpec(
                contentWidthPx = maxWidthPx,
                contentHeightPx = pageHeightPx,
                density = density,
                settings = bookSettings,
                fontSize = fontSizeSp,
                language = "en",
            )
            val expectedPages = paginateBook(bookItems, composeMeasurer, spec)
            val fastPages = paginateBook(bookItems, fastMeasurer, spec)
            assertTrue(expectedPages.size > 3)
            assertEquals("paginateBook page count mismatch for bookStyles=$bookStyles", expectedPages.size, fastPages.size)
            for (i in expectedPages.indices) {
                assertEquals("page[$i].firstItemIndex mismatch", expectedPages[i].firstItemIndex, fastPages[i].firstItemIndex)
                assertEquals("page[$i].firstCharOffset mismatch", expectedPages[i].firstCharOffset, fastPages[i].firstCharOffset)
                assertPageEquals(i, expectedPages[i], fastPages[i])
            }
        }

        // Throughput benchmark: 500 paragraphs Compose vs FastParagraphMeasurer.
        val benchSettings = ReaderSettings(
            font = ReaderFont.LITERATA,
            fontSizeSp = fontSizeSp,
            lineHeight = 1.5f,
            justify = true,
            hyphenation = true,
            bookStyles = true,
        )
        val benchStyle = ReaderTextStyleCache(benchSettings, defaultLanguage = "en").textStyle(
            element = ContentElement.Paragraph(sixLineText),
            fontSize = fontSizeSp,
            isParagraphStart = true,
        )
        val benchParagraphs = List(500) { idx ->
            if (idx % 4 == 0) styledSpansText else sixLineText
        }
        for (i in 0 until 20) {
            composeMeasurer.measureReader(
                text = benchParagraphs[i],
                style = benchStyle,
                constraints = constraints,
                density = density,
                publisherStyles = true,
            )
            fastParagraphMeasurer.measure(
                text = benchParagraphs[i],
                style = benchStyle,
                maxWidthPx = maxWidthPx,
                density = density,
                publisherStyles = true,
            )
        }
        val composeStartNs = System.nanoTime()
        var composeChecksum = 0
        for (p in benchParagraphs) {
            val r = composeMeasurer.measureReader(
                text = p,
                style = benchStyle,
                constraints = constraints,
                density = density,
                publisherStyles = true,
            )
            composeChecksum += r.lineCount + r.size.height
        }
        val composeMs = (System.nanoTime() - composeStartNs) / 1_000_000.0

        val fastStartNs = System.nanoTime()
        var fastChecksum = 0
        for (p in benchParagraphs) {
            val r = fastParagraphMeasurer.measure(
                text = p,
                style = benchStyle,
                maxWidthPx = maxWidthPx,
                density = density,
                publisherStyles = true,
            )
            fastChecksum += r.lineCount + r.height
        }
        val fastMs = (System.nanoTime() - fastStartNs) / 1_000_000.0
        assertEquals(composeChecksum, fastChecksum)
        val speedup = if (fastMs > 0.0) composeMs / fastMs else 0.0
        Log.i(
            "IndexedPaginationPassTest",
            String.format(
                Locale.US,
                "FAST_PARAGRAPH_BENCH paragraphs=%d composeMs=%.2f fastMs=%.2f speedup=%.2fx",
                benchParagraphs.size,
                composeMs,
                fastMs,
                speedup,
            ),
        )
    }

    @Test fun anchorSlicedQuickPaginationMatchesFullChapterLayout() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File.createTempFile("anchor-sliced-quick-", ".test", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        try {
            val density = Density(1f)
            lateinit var measurer: TextMeasurer
            instrumentation.runOnMainSync {
                measurer = createReaderTextMeasurer(
                    fontFamilyResolver = createFontFamilyResolver(context),
                    density = density,
                    layoutDirection = LayoutDirection.Ltr,
                    cacheSize = 0,
                )
            }
            val image = File(root, "figure.png")
            val bitmap = Bitmap.createBitmap(96, 24, Bitmap.Config.ARGB_8888)
            try {
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }

            val elements = mutableListOf<ContentElement>()
            val boxes = mutableListOf<PublisherBoxSpan>()
            repeat(6) { round ->
                elements += ContentElement.Heading("Section ${round + 1}: Large Chapter Slice", 2)
                elements += paragraph("Long prose paragraph in section ${round + 1} with ordinary sentences that span multiple pages. ".repeat(48))
                val floatStart = elements.size
                elements += ContentElement.Image(image.path, widthFrac = 0.3f)
                elements += paragraph("Figure ${round + 1} caption")
                elements += ContentElement.Spacer(heightEm = 0.25f)
                val floatEnd = elements.size
                boxes += PublisherBoxSpan(
                    id = "float-$round",
                    startElement = floatStart,
                    endElementExclusive = floatEnd,
                    floatSide = PublisherFloatSide.LEFT,
                )
                elements += paragraph("Text beside the floated figure in section ${round + 1}. ".repeat(14))
                val containerStart = elements.size
                elements += paragraph("First boxed paragraph in section ${round + 1}. ".repeat(12))
                elements += paragraph("Second boxed paragraph in section ${round + 1}. ".repeat(18))
                elements += paragraph("Third boxed paragraph in section ${round + 1}. ".repeat(12))
                boxes += PublisherBoxSpan(
                    id = "box-$round",
                    startElement = containerStart,
                    endElementExclusive = elements.size,
                    style = PublisherBoxStyle(paddingLeftEm = 0.6f, paddingRightEm = 0.6f),
                )
                if (round % 2 == 1) {
                    elements += ContentElement.Table(
                        List(30) { row ->
                            TableRow(
                                cells = listOf(TableCell(AnnotatedString("Section ${round + 1} Row $row"))),
                                isHeader = row == 0,
                            )
                        },
                    )
                }
                elements += paragraph("Closing prose paragraph after section ${round + 1}. ".repeat(20))
            }

            val chapters = listOf(
                Chapter("Preface", listOf(paragraph("Opening chapter before the large chapter."))),
                Chapter("Large Chapter", elements, publisherBoxes = boxes),
            )
            val settings = ReaderSettings(
                font = ReaderFont.SERIF,
                startChaptersOnNewPage = true,
                bookStyles = true,
            )
            val spec = PaginationSpec(300, 290, density, settings, 18f)
            val items = ReaderChapterItems(chapters) { adjustReaderElement(it, settings.hideFootnotes) }
            val chapterStart = items.chapterStarts[1]
            val chapterEnd = items.size

            val fullPages = paginateBook(items, measurer, spec)
            val exactIndex = ReaderPageIndex(
                fullPages.flatMap { listOf(it.firstItemIndex, it.firstCharOffset) }.toIntArray(),
            )
            val chapterFirstPage = exactIndex.firstPageAtOrAfter(chapterStart)
            val chapterPageCount = fullPages.size - chapterFirstPage
            assertTrue("Expected large chapter with >= 30 pages, got $chapterPageCount", chapterPageCount >= 30)
            assertTrue(fullPages.any { page -> page.parts.any { it.charStart > 0 && it.text != null } })
            assertTrue(fullPages.any { page -> page.parts.any { it.rowStart > 0 } })

            val quickRange = ReaderPaginationRange(chapterStart, chapterEnd)
            for (pct in listOf(50, 80, 95)) {
                val targetPageIndex = chapterFirstPage + (chapterPageCount * pct / 100).coerceAtMost(chapterPageCount - 1)
                val targetPage = fullPages[targetPageIndex]
                val target = ReaderReturnLocation.Main(targetPage.firstItemIndex, targetPage.firstCharOffset)

                val quickStart = resolveExactQuickPaginationStart(
                    exactIndex = exactIndex,
                    items = items,
                    quickRange = quickRange,
                    target = target,
                )
                assertTrue("Expected quickStart ($quickStart) > chapterStart ($chapterStart) at $pct%", quickStart > chapterStart)

                val quickPages = paginateBook(
                    items = items,
                    measurer = measurer,
                    spec = spec,
                    fromIndex = quickStart,
                    toIndex = chapterEnd,
                    stopAfterLocation = target,
                )
                assertTrue("Expected non-empty quickPages at $pct%", quickPages.isNotEmpty())
                assertTrue(
                    "Expected sliced quickPages (${quickPages.size}) < fullPages/2 (${fullPages.size / 2}) at $pct%",
                    quickPages.size < fullPages.size / 2,
                )

                val offset = exactIndex.offsetOf(quickPages, quickStart)
                assertNotNull("Expected exactIndex.offsetOf to match quickPages at $pct%", offset)
                val baseOffset = offset!!
                for (i in quickPages.indices) {
                    val expected = fullPages[baseOffset + i]
                    val actual = quickPages[i]
                    assertEquals("pct=$pct page=$i firstItemIndex", expected.firstItemIndex, actual.firstItemIndex)
                    assertEquals("pct=$pct page=$i firstCharOffset", expected.firstCharOffset, actual.firstCharOffset)
                    assertEquals("pct=$pct page=$i parts.size", expected.parts.size, actual.parts.size)
                    assertPageEquals(baseOffset + i, expected, actual)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertPageEquals(index: Int, expected: BookPage, actual: BookPage) {
        val label = "Page $index"
        assertEquals(label, expected.firstItemIndex, actual.firstItemIndex)
        assertEquals(label, expected.firstCharOffset, actual.firstCharOffset)
        assertEquals(label, expected.parts.size, actual.parts.size)
        expected.parts.forEachIndexed { partIndex, part ->
            val actualPart = actual.parts[partIndex]
            val partLabel = "$label part $partIndex"
            assertEquals(partLabel, partFields(part), partFields(actualPart))
            assertEquals(partLabel, sideBoxFields(part.sideBox), sideBoxFields(actualPart.sideBox))
            assertEquals(partLabel, tableFields(part.tableLayout), tableFields(actualPart.tableLayout))
        }
    }

    private fun partFields(part: PagePart): List<Any?> = with(part) {
        val content = element
        val source = if (content is ContentElement.Table) {
            content.rows.map { row -> row.isHeader to row.cells.map { it.text } }
        } else content
        listOf(
            itemIndex, source, text, isParagraphStart, imageHeightPx, charStart, charEnd, rowStart, rowEnd,
            headerRepeated, floatImagePath, publisherBoxes, publisherStartsElement, publisherEndsElement,
            allowsVerticalScroll,
        )
    }

    private fun sideBoxFields(box: SideBoxSpec?): List<Any?>? = box?.run {
        listOf(
            capText, imagePath, leftSide, boxWidthPx, boxHeightPx, besideWidthPx, besideEndChar,
            compositeHeightPx, capFontSizeSp, capTopPx, publisherFloat, publisherFloatItemHeightsPx,
            publisherStacked,
        )
    }

    private fun tableFields(layout: TableLayout?): List<Any?>? = layout?.run {
        listOf(
            grid.columnCount, grid.cellColumns.map { it.asList() }, grid.noBreakAfterRow.asList(),
            colWidthsPx.asList(), rowHeightsPx.asList(), fontScale, columnOffsetsPx.asList(), tableWidthPx,
            stackedCellHeightsPx?.map { it.asList() },
        )
    }

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
}
