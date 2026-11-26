package com.example.frogreader.reader

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.R
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.EpubSourceDestination
import com.example.frogreader.debug.ReaderOpeningTestActivity
import com.example.frogreader.ui.reader.ReaderPageIndexCache
import com.example.frogreader.ui.reader.ReaderPageLayoutCache
import com.example.frogreader.ui.reader.ReaderNavigationTarget
import com.example.frogreader.ui.reader.ReaderProgression
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.ReaderViewModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexedReaderUiTest {
    @Test fun scrollNavigatesOutsideTheOpeningWindow() = verifyNavigation(ReadingMode.SCROLL)
    @Test fun pagesNavigateOutsideTheOpeningWindow() = verifyNavigation(ReadingMode.PAGES)

    @Test fun globalPageCounterReopensFromDiskWithAnUnreadableDistantChapter() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("The fixture uses the isolated Rust app", context.packageName == "knitipka.frogreader")
        val repository = (context.applicationContext as FrogReaderApp).bookRepository
        val id = "indexed-page-counter-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub").apply {
            parentFile!!.mkdirs()
            writeText("Stable source identity for the global page counter fixture")
        }
        val start = 16
        val chapters = List(20) { chapter ->
            Chapter("Chapter ${chapter + 1}", listOf(ContentElement.Paragraph(AnnotatedString(marker(chapter)))))
        }
        val book = Book(id, "Global page counter fixture", format = BookFormat.EPUB,
            addedAtMillis = System.currentTimeMillis(), fileName = source.name,
            progress = ReadingProgress(elementIndex = start, fraction = start.toFloat() / chapters.lastIndex))
        val cache = ChunkedContentCache(File(context.filesDir, "reader-content"))
        val indexFile = ReaderPageIndexCache.fileFor(File(context.filesDir, "pagination"), id)
        val parser = repository.contentParserForTests
        try {
            repository.addBookForRestore(book)
            repository.saveReaderSettings(id, ReaderSettings(
                readingMode = ReadingMode.PAGES, startChaptersOnNewPage = true,
                showPageCounter = true, showTotalPageCount = true, showChapterPagesLeft = false,
            ))
            assertTrue(cache.save(id, source, BookContent(chapters)))
            repository.contentParserForTests = { file, format, resources, position, publish ->
                check(file.name != source.name) { "The indexed reader unexpectedly opened the full model" }
                parser?.invoke(file, format, resources, position, publish) ?: BookParsers.parseContent(file, format, resources)
            }
            val expected = context.getString(R.string.reader_page_number_total, start + 1, chapters.size)
            fun openReader() = ActivityScenario.launch<ReaderOpeningTestActivity>(
                Intent(context, ReaderOpeningTestActivity::class.java)
                    .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id),
            )
            openReader().use {
                await("global counter $expected and page text") {
                    findText(marker(start)) != null && findNode { node -> node.text?.toString() == expected } != null
                }
                assertTrue("The page index should be persisted before publication", indexFile.length() > 0)
            }
            corruptChapter(cache.entryFile(id), chapter = 0)
            requireNotNull(cache.openIndexed(id, source)).use { session ->
                assertNull("The damaged chapter must prevent an exact full-book scan", session.readChapter(0))
                assertEquals("Chapter ${start + 1}", session.readChapter(start)?.title)
            }
            openReader().use {
                await("global counter $expected from disk with the first chapter unreadable") {
                    findText(marker(start)) != null && findNode { node -> node.text?.toString() == expected } != null
                }
            }
        } finally {
            repository.contentParserForTests = parser
            repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
            repository.deleteBook(id)
            indexFile.delete()
            source.delete()
        }
    }

    @Test fun continuousExactPagesCrossTheSourceWindowAndReopenFromDisk() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("The fixture uses the isolated Rust app", context.packageName == "knitipka.frogreader")
        val repository = (context.applicationContext as FrogReaderApp).bookRepository
        val id = "indexed-continuous-pages-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub").apply {
            parentFile!!.mkdirs()
            writeText("Stable source identity for the continuous pagination fixture")
        }
        val shortChapterCount = 8
        val start = 6
        val middleChapterCount = 16
        val paragraphsPerChapter = 56
        val last = shortChapterCount + middleChapterCount * paragraphsPerChapter
        val lastTitle = "Distant chapter destination"
        val chapters = buildList {
            repeat(shortChapterCount) { chapter ->
                add(Chapter("Short chapter ${chapter + 1}", listOf(ContentElement.Paragraph(AnnotatedString(marker(chapter))))))
            }
            repeat(middleChapterCount) { chapter ->
                add(Chapter("Middle chapter ${chapter + 1}", List(paragraphsPerChapter) { paragraph ->
                    val item = shortChapterCount + chapter * paragraphsPerChapter + paragraph
                    ContentElement.Paragraph(AnnotatedString("${marker(item)} A fixture paragraph in the middle of the book."))
                }))
            }
            add(Chapter(lastTitle, listOf(ContentElement.Paragraph(AnnotatedString(marker(last))))))
        }
        val content = BookContent(chapters, navigation = listOf(
            BookNavigationEntry("First chapter destination", 0, BookNavigationTarget.ReadingOrder(0, 0)),
            BookNavigationEntry(lastTitle, 0, BookNavigationTarget.ReadingOrder(chapters.lastIndex, 0)),
        ))
        val savedPosition = ReadingProgress(chapterIndex = start, elementIndex = start, fraction = start.toFloat() / last)
        val book = Book(id, "Continuous pagination fixture", format = BookFormat.EPUB,
            addedAtMillis = System.currentTimeMillis(), fileName = source.name, progress = savedPosition)
        val cache = ChunkedContentCache(File(context.filesDir, "reader-content"))
        val geometryFile = ReaderPageLayoutCache.fileFor(File(context.filesDir, "pagination"), id)
        val parser = repository.contentParserForTests

        fun openReader() = ActivityScenario.launch<ReaderOpeningTestActivity>(
            Intent(context, ReaderOpeningTestActivity::class.java)
                .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id),
        )

        fun canonicalFirstPage(totalPages: Int): Boolean {
            val counter = context.getString(R.string.reader_page_number_total, 1, totalPages)
            return findText(marker(0)) != null && findText(marker(start)) != null &&
                findNode { it.text?.toString() == counter } != null
        }

        try {
            repository.addBookForRestore(book)
            repository.saveReaderSettings(id, ReaderSettings(
                readingMode = ReadingMode.PAGES, startChaptersOnNewPage = false,
                fontSizeSp = 16f, lineHeight = 1.15f, pageMarginDp = 10,
                showPageCounter = true, showTotalPageCount = true, showChapterPagesLeft = false,
            ))
            assertTrue(cache.save(id, source, content))
            repository.contentParserForTests = { file, format, resources, position, publish ->
                check(file.name != source.name) { "Continuous indexed pagination opened the full model" }
                parser?.invoke(file, format, resources, position, publish) ?: BookParsers.parseContent(file, format, resources)
            }
            val totalPages = openReader().use { scenario ->
                await("visible text in the saved chapter") { findText(marker(start)) != null }
                val originalBounds = Rect().also(requireNotNull(findText(marker(start)))::getBoundsInScreen)
                await("exact counter while the originally visible page stays in place") {
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    progress.totalPages > 12 && progress.elementIndex == start &&
                        findNode { it.text?.toString() == context.getString(R.string.reader_page_number_total, 1, progress.totalPages) } != null
                }
                val count = requireNotNull(repository.bookById(id)).progress.totalPages
                assertTrue("Exact continuous page geometry was persisted", geometryFile.length() > 0)
                assertNull("Publishing the exact layout must not replace the local page", findText(marker(0)))
                assertEquals(originalBounds, Rect().also(requireNotNull(findText(marker(start)))::getBoundsInScreen))

                showChrome(context, scenario)
                val contents = context.getString(R.string.reader_contents)
                await("enabled table of contents") { findDescription(contents)?.isEnabled == true }
                click(requireNotNull(findDescription(contents)))
                await("distant table of contents destination") { findText(lastTitle) != null }
                click(requireNotNull(findText(lastTitle)))
                await("distant chapter text and saved progress") {
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    progress.fraction > 0.85f && progress.totalPages == count &&
                        findText(marker(last)) != null
                }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await("contents closed") { findText(lastTitle) == null }
                hideChrome(context, scenario)
                await("exact counter on the last page") {
                    findNode { it.text?.toString() == context.getString(R.string.reader_page_number_total, count, count) } != null
                }

                showChrome(context, scenario)
                assertTrue(setBookProgress(context, 0f))
                await("slider returned to the same canonical first page") {
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    progress.elementIndex == 0 && progress.totalPages == count && findText(marker(start)) != null
                }
                hideChrome(context, scenario)
                await("canonical first page spans the earlier source chapters") { canonicalFirstPage(count) }
                count
            }

            val geometryModified = geometryFile.lastModified()
            val damagedChapter = shortChapterCount + middleChapterCount / 2
            corruptChapter(cache.entryFile(id), damagedChapter)
            requireNotNull(cache.openIndexed(id, source)).use { session ->
                assertNull("An exact full-book rescan cannot succeed", session.readChapter(damagedChapter))
                assertEquals(chapters[start].title, session.readChapter(start)?.title)
            }
            repository.saveProgress(id, savedPosition)
            openReader().use {
                await("canonical cross-chapter page and count from disk") {
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    progress.elementIndex == 0 && progress.totalPages == totalPages && canonicalFirstPage(totalPages)
                }
                assertEquals("The reader should reuse the existing geometry file", geometryModified, geometryFile.lastModified())
            }
        } finally {
            repository.contentParserForTests = parser
            repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
            repository.deleteBook(id)
            geometryFile.delete()
            source.delete()
        }
    }

    private fun corruptChapter(file: File, chapter: Int) {
        RandomAccessFile(file, "rw").use { bytes ->
            val header = ByteArray(64).also(bytes::readFully)
            val metadataLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            // The manifest and globals occupy the first two FRB index entries.
            bytes.seek(64L + metadataLength + (2L + chapter) * 48)
            val entry = ByteArray(48).also(bytes::readFully)
            val payload = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            bytes.seek(payload)
            val first = bytes.readUnsignedByte()
            bytes.seek(payload)
            bytes.writeByte(first xor 0xff)
        }
    }

    @Test fun coldEpubSourceAnchorSurvivesProcessRecreationBeforeAndAfterExactPromotion() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("The fixture uses the isolated Rust app", context.packageName == "knitipka.frogreader")
        val app = context.applicationContext as FrogReaderApp
        val repository = app.bookRepository
        val id = "cold-epub-kill-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub")
        val cache = ChunkedContentCache(File(context.filesDir, "reader-content"))
        val contentFile = cache.entryFile(id)
        val paginationDir = File(context.filesDir, "pagination")
        val firstCount = 20
        val secondCount = 20
        val thirdPrefaceCount = 10
        val thirdTrailingCount = 40
        val targetGlobalIndex = firstCount + secondCount + thirdPrefaceCount
        val targetMarker = marker(targetGlobalIndex)

        fun fillerBody(prefix: String, startIndex: Int, count: Int): String = buildString {
            repeat(count) { offset ->
                append("<p>")
                append(marker(startIndex + offset))
                append(' ')
                append(prefix)
                append(" paragraph ")
                append(offset)
                append(" with enough prose to give the scroll viewport stable height across promotion on tall screens.")
                append("</p>")
            }
        }

        fun writeColdEpub(file: File) {
            file.parentFile!!.mkdirs()
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.encodeToByteArray())
                    zip.closeEntry()
                }
                entry(
                    "META-INF/container.xml",
                    """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
                )
                entry(
                    "OPS/book.opf",
                    """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest>
                        <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                        <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                        <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
                        <item id="third" href="third.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="first"/>
                        <itemref idref="second"/>
                        <itemref idref="third"/>
                      </spine>
                    </package>
                    """.trimIndent(),
                )
                entry(
                    "OPS/nav.xhtml",
                    """
                    <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <nav epub:type="toc"><ol>
                        <li><a href="first.xhtml">Chapter One</a></li>
                        <li><a href="second.xhtml">Chapter Two</a></li>
                        <li><a href="third.xhtml#target">Chapter Three Target</a></li>
                      </ol></nav>
                    </body></html>
                    """.trimIndent(),
                )
                entry(
                    "OPS/first.xhtml",
                    "<html><body>${fillerBody("Opening chapter", 0, firstCount)}</body></html>",
                )
                entry(
                    "OPS/second.xhtml",
                    "<html><body>${fillerBody("Middle chapter", firstCount, secondCount)}</body></html>",
                )
                entry(
                    "OPS/third.xhtml",
                    "<html><body>" +
                        fillerBody("Unlisted third preface", firstCount + secondCount, thirdPrefaceCount) +
                        """<p id="target">$targetMarker Distant cold EPUB target paragraph with enough text to anchor the viewport cleanly.</p>""" +
                        fillerBody("Third trailing", targetGlobalIndex + 1, thirdTrailingCount) +
                        "</body></html>",
                )
            }
        }

        fun openReader() = ActivityScenario.launch<ReaderOpeningTestActivity>(
            Intent(context, ReaderOpeningTestActivity::class.java)
                .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id),
        )

        try {
            writeColdEpub(source)
            repository.addBookForRestore(Book(
                id = id,
                title = "Cold EPUB process death fixture",
                format = BookFormat.EPUB,
                addedAtMillis = System.currentTimeMillis(),
                fileName = source.name,
            ))
            repository.saveReaderSettings(id, ReaderSettings(readingMode = ReadingMode.SCROLL))
            assertFalse("Cold open must begin without a content FRB", contentFile.exists())
            assertNull("Cold open must begin without a retained BookIndex", cache.loadEpubSourceIndex(id, source))

            // 1. Open cold before the exact BookIndex / .frb is built, seek to the third spine document,
            // persist the provisional epubSource anchor (elementIndex == 0), and simulate process death.
            val initialVm = withContext(Dispatchers.Main) {
                ReaderViewModel(repository, app.settingsRepository, app.statsRepository, id, paginationDir)
            }
            try {
                val first = initialVm.state.first { it is ReaderState.Error || it is ReaderState.Ready } as ReaderState.Ready
                assertFalse(first.globalCoordinatesReady)
                val target = (first.navigation[2].target as ReaderNavigationTarget.EpubSource).destination
                withContext(Dispatchers.Main) { initialVm.openEpubDestination(target) }
                val second = initialVm.state.first {
                    it is ReaderState.Ready && it.epubDestination == target
                } as ReaderState.Ready
                assertEquals(thirdPrefaceCount, initialVm.currentFlatIndex)
                withContext(Dispatchers.Main) {
                    initialVm.saveProgress(thirdPrefaceCount, 0, 0, sourceGeneration = second.sourceGeneration)
                }
                val provisional = repository.books.first { books ->
                    val location = books.firstOrNull { it.id == id }?.progress?.documentLocation as? DocumentLocation.Reflowable
                    location?.epubSource?.spineOrdinal == 2
                }.first { it.id == id }.progress
                val provisionalLocation = provisional.documentLocation as DocumentLocation.Reflowable
                val anchor = requireNotNull(provisionalLocation.epubSource)
                assertEquals(0, provisional.elementIndex)
                assertEquals(0, provisionalLocation.elementIndex)
                assertEquals("OPS/third.xhtml", anchor.path)
                assertEquals(thirdPrefaceCount, anchor.documentElement)
            } finally {
                initialVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                repository.releaseContentCache()
            }
            assertFalse("Process death occurred before content.frb was built", contentFile.exists())
            assertNull("Process death occurred before index.frb was built", cache.loadEpubSourceIndex(id, source))
            assertEquals(0, requireNotNull(repository.bookById(id)).progress.elementIndex)

            // 2. Recreate the reader activity from disk BEFORE the exact BookIndex is published:
            // verify it reopens at the saved epubSource passage in OPS/third.xhtml (despite elementIndex == 0)
            // and promotes cleanly to exact global coordinates (elementIndex == targetGlobalIndex) without moving the text.
            openReader().use { scenario ->
                await("saved epubSource target passage on cold recreation") { findText(targetMarker) != null }
                assertNull("Must not open the first spine document at element 0", findText(marker(0)))
                val boundsBeforePromotion = Rect().also(requireNotNull(findText(targetMarker))::getBoundsInScreen)
                lateinit var reader: ReaderViewModel
                scenario.onActivity { activity ->
                    reader = ViewModelProvider(activity)["reader-$id", ReaderViewModel::class.java]
                }
                await("exact BookIndex and FRB promotion at the same visible passage") {
                    val ready = reader.state.value as? ReaderState.Ready ?: return@await false
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    val location = progress.documentLocation as? DocumentLocation.Reflowable ?: return@await false
                    ready.globalCoordinatesReady && ready.epubOpening == null &&
                        contentFile.isFile && cache.loadEpubSourceIndex(id, source) != null &&
                        progress.chapterIndex == 2 && progress.elementIndex == targetGlobalIndex &&
                        location.epubSource?.spineOrdinal == 2 && location.elementIndex == targetGlobalIndex &&
                        findText(targetMarker) != null
                }
                assertEquals(
                    "Publishing the exact index must keep the visible passage at the same screen Y",
                    boundsBeforePromotion.top,
                    Rect().also(requireNotNull(findText(targetMarker))::getBoundsInScreen).top,
                )
            }

            // 3. Recreate the reader activity from disk AFTER the exact BookIndex is published.
            repository.releaseContentCache()
            openReader().use { scenario ->
                await("reopened target passage from published exact index") { findText(targetMarker) != null }
                lateinit var reader: ReaderViewModel
                scenario.onActivity { activity ->
                    reader = ViewModelProvider(activity)["reader-$id", ReaderViewModel::class.java]
                }
                val ready = requireNotNull(reader.state.value as? ReaderState.Ready)
                assertTrue(ready.globalCoordinatesReady)
                assertNull(ready.epubOpening)
                assertEquals(targetGlobalIndex, reader.currentFlatIndex)
            }
        } finally {
            repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
            repository.deleteBook(id)
            source.delete()
        }
    }

    @Test fun privateCorpusKeepsColdEpubSeekVisibleThroughExactPromotion() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val relativePath = arguments.getString("engineProfileEpub")
        val mode = arguments.getString("engineReadingMode")?.let(ReadingMode::valueOf) ?: ReadingMode.SCROLL
        val chapterBreaks = arguments.getString("engineContinuousPages") != "true"
        assumeTrue("Provide a private EPUB in the isolated Rust app", relativePath != null &&
            context.packageName == "knitipka.frogreader")
        val original = File(context.filesDir, requireNotNull(relativePath)).canonicalFile
        require(original.isFile && original.path.startsWith(context.filesDir.canonicalPath + File.separator))
        val expected = requireNotNull(EpubParser.readOpeningStructure(original))
        val sliderDestination = expected.locate(0.85f).first
        require(sliderDestination.spineOrdinal != expected.first.spineOrdinal)

        val app = context.applicationContext as FrogReaderApp
        val repository = app.bookRepository
        val id = "epub-package-cold-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub")
        val cache = ChunkedContentCache(File(context.filesDir, "reader-content"))
        val contentFile = cache.entryFile(id)
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ColdEpubUi")
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            source.parentFile!!.mkdirs()
            original.copyTo(source)
            repository.addBookForRestore(Book(id, "Cold EPUB fixture", format = BookFormat.EPUB,
                addedAtMillis = System.currentTimeMillis(), fileName = source.name))
            repository.saveReaderSettings(id, ReaderSettings(readingMode = mode, startChaptersOnNewPage = chapterBreaks))
            assertTrue("Fresh open must start without an FRB", !contentFile.exists())
            assertNull("Fresh open must start without a retained exact source map", cache.loadEpubSourceIndex(id, source))

            val started = SystemClock.elapsedRealtime()
            ActivityScenario.launch<ReaderOpeningTestActivity>(Intent(context, ReaderOpeningTestActivity::class.java)
                .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id)).use { scenario ->
                await("reader's first source window", includeLabels = false) {
                    app.readerDiagnostics.snapshotFor(id)?.itemCount?.let { it > 0 } == true
                }
                lateinit var reader: ReaderViewModel
                scenario.onActivity { activity ->
                    reader = ViewModelProvider(activity)["reader-$id", ReaderViewModel::class.java]
                }
                fun ready(): ReaderState.Ready? = reader.state.value as? ReaderState.Ready
                val initial = checkNotNull(ready())
                assertTrue("First text must precede the exact index", !initial.globalCoordinatesReady && !contentFile.exists())
                assertEquals("All addressable TOC entries must exist in the first window",
                    expected.navigation.map { Triple(it.title, it.depth, it.destination) },
                    initial.navigation.map {
                        Triple(it.title, it.depth, (it.target as ReaderNavigationTarget.EpubSource).destination)
                    })
                Log.i("FrogReaderPerf", "epub_package_cold_window source_bytes=${source.length()} mode=$mode " +
                    "toc_rows=${initial.navigation.size} ready_ms=${SystemClock.elapsedRealtime() - started}")

                // The initial model is ready before the entrance overlay
                // releases input and exposes the content's semantics.
                await("opening transition to accessible content", includeLabels = false) {
                    visibleNodes().any { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
                }
                showChrome(context, scenario)
                val contents = context.getString(R.string.reader_contents)
                val bookmarks = context.getString(R.string.reader_bookmarks)
                val tabs = setOf(contents, bookmarks, context.getString(R.string.reader_quotes))
                await("enabled Contents before promotion") { findDescription(contents)?.isEnabled == true }
                click(requireNotNull(findDescription(contents)))
                val tocTargets = expected.navigation.filter {
                    it.destination.spineOrdinal != initial.epubDestination?.spineOrdinal &&
                        it.destination.spineOrdinal != sliderDestination.spineOrdinal && it.title !in tabs
                }.groupBy { it.title }
                var tocRow: Pair<AccessibilityNodeInfo, EpubSourceDestination>? = null
                await("distant source destination in Contents") {
                    tocRow = visibleNodes().firstNotNullOfOrNull { node ->
                        tocTargets[node.text?.toString()]?.singleOrNull()?.let { node to it.destination }
                    }
                    tocRow != null
                }
                val (row, tocDestination) = requireNotNull(tocRow)
                click(row)
                await("TOC jump to an unopened source document", includeLabels = false) {
                    ready()?.epubDestination == tocDestination && !reader.epubOpeningSeekPending.value
                }
                assertTrue("TOC jump must finish before the exact index", !contentFile.exists())
                fun contentsPanelVisible(): Boolean = findNode { it.text?.toString() == bookmarks } != null
                if (contentsPanelVisible()) {
                    click(requireNotNull(findDescription(contents)))
                    await("Contents panel closed") { !contentsPanelVisible() }
                }

                showChrome(context, scenario)
                assertTrue(setBookProgress(context, 0.85f))
                await("slider jump to the source document near 85%", includeLabels = false) {
                    ready()?.epubDestination == sliderDestination && !reader.epubOpeningSeekPending.value
                }
                if (mode == ReadingMode.SCROLL) hideChrome(context, scenario)
                var anchor: Pair<String, Rect>? = null
                await("visible slider destination text", includeLabels = false) {
                    val sourceReady = ready() ?: return@await false
                    if (mode == ReadingMode.PAGES && reader.pagination.value?.let {
                            it.rangeComplete && it.key.endsWith("|source=${sourceReady.sourceGeneration}")
                        } != true
                    ) return@await false
                    val texts = mutableListOf<String>()
                    scenario.onActivity {
                        val items = ready()?.items.orEmpty()
                        for (index in reader.currentFlatIndex until minOf(items.size, reader.currentFlatIndex + 9)) {
                            val text = when (val element = items[index].element) {
                                is ContentElement.Paragraph -> element.text.text
                                is ContentElement.Heading -> element.styledText.text
                                else -> continue
                            }
                            val from = if (index == reader.currentFlatIndex) reader.currentCharOffset.coerceIn(0, text.length) else 0
                            text.substring(from).trim().take(40).takeIf { it.length >= 15 }?.let(texts::add)
                        }
                    }
                    anchor = texts.firstNotNullOfOrNull { text ->
                        findText(text)?.let { text to Rect().also(it::getBoundsInScreen) }
                    }
                    anchor != null
                }
                val (text, bounds) = requireNotNull(anchor)
                await("source-local slider position persisted", includeLabels = false) {
                    val location = repository.bookById(id)?.progress?.documentLocation as? DocumentLocation.Reflowable
                    location?.epubSource?.spineOrdinal == sliderDestination.spineOrdinal
                }
                val local = repository.bookById(id)?.progress?.documentLocation as? DocumentLocation.Reflowable
                assertEquals(sliderDestination.spineOrdinal, local?.epubSource?.spineOrdinal)
                assertEquals("Local progress must not be stored as a global offset", 0, local?.elementIndex)
                assertTrue("The slider must display text before the exact index", !contentFile.exists())
                Log.i("FrogReaderPerf", "epub_package_cold_seek source_bytes=${source.length()} " +
                    "ready_ms=${SystemClock.elapsedRealtime() - started}")

                await("exact promotion at the same visible reading position", includeLabels = false, timeoutMillis = 180_000) {
                    val progress = repository.bookById(id)?.progress ?: return@await false
                    val location = progress.documentLocation as? DocumentLocation.Reflowable ?: return@await false
                    val exact = ready()?.takeIf { it.globalCoordinatesReady } ?: return@await false
                    val pagesPresented = mode != ReadingMode.PAGES ||
                        reader.pagination.value?.let { holder ->
                            holder.rangeComplete && holder.key.endsWith("|source=${exact.sourceGeneration}") &&
                                app.readerDiagnostics.snapshotFor(id)?.calculatedPages == holder.pages.size
                        } == true
                    contentFile.isFile && pagesPresented &&
                        location.epubSource?.spineOrdinal == sliderDestination.spineOrdinal &&
                        location.elementIndex > 0 && findText(text) != null
                }
                assertEquals("Publishing the exact index must keep the text at the same screen Y", bounds.top,
                    Rect().also(requireNotNull(findText(text))::getBoundsInScreen).top)
                Log.i("FrogReaderPerf", "epub_package_cold_exact source_bytes=${source.length()} " +
                    "ready_ms=${SystemClock.elapsedRealtime() - started} position_stable=true")
            }
        } finally {
            try {
                repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
                repository.deleteBook(id)
                source.delete()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    @Test fun privateCorpusRestoresDistantTextFromIndexedCache() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val relativePath = arguments.getString("engineProfileEpub")
        assumeTrue("Provide a private EPUB in the isolated Rust app", relativePath != null &&
            context.packageName == "knitipka.frogreader")
        val original = File(context.filesDir, requireNotNull(relativePath)).canonicalFile
        require(original.isFile && original.path.startsWith(context.filesDir.canonicalPath + File.separator))
        val mode = arguments.getString("engineReadingMode")?.let(ReadingMode::valueOf) ?: ReadingMode.PAGES
        val verifyPageIndex = arguments.getString("enginePageIndex") == "true"
        val seekMatrix = arguments.getString("engineSeekMatrix") == "true"
        val profileSeek = seekMatrix || arguments.getString("engineProfileSeek") == "true"
        val pageWorkers = arguments.getString("enginePageWorkers")?.toInt()
        val profileFrames = arguments.getString("engineProfilePaginationFrames") == "true"
        val targetedCold = arguments.getString("engineTargetedColdOpen") == "true"
        val chapterBreaks = arguments.getString("engineContinuousPages") != "true"
        require(!verifyPageIndex || mode == ReadingMode.PAGES) { "The page-index trial requires paged mode" }
        require(!verifyPageIndex || !targetedCold) {
            "Run the targeted cold seek separately from the page-index reopen trial"
        }
        require(pageWorkers == null || pageWorkers in listOf(1, 2, 4)) { "Use 1, 2 or 4 pagination workers" }
        require(pageWorkers == null || verifyPageIndex && chapterBreaks) {
            "The worker setting requires a chapter-break page-index build"
        }
        require(!profileFrames || verifyPageIndex && chapterBreaks) {
            "Frame sampling requires a chapter-break page-index build from a prepared content cache"
        }
        val app = context.applicationContext as FrogReaderApp
        val repository = app.bookRepository
        val id = "indexed-corpus-ui-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub")
        val paginationDir = File(context.filesDir, "pagination")
        val contentCache = ChunkedContentCache(File(context.filesDir, "reader-content"))
        val indexFile = if (chapterBreaks) ReaderPageIndexCache.fileFor(paginationDir, id)
        else ReaderPageLayoutCache.fileFor(paginationDir, id)
        val parser = repository.contentParserForTests
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:IndexedReaderUi")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            source.parentFile!!.mkdirs()
            original.copyTo(source)
            repository.addBookForRestore(Book(id, "Indexed EPUB fixture", format = BookFormat.EPUB,
                addedAtMillis = System.currentTimeMillis(), fileName = source.name))
            repository.retainReaderResources(id).use {
                val expected = prepareCorpus(context.filesDir, id, source)
                repository.saveProgress(id, ReadingProgress(elementIndex = expected.item,
                    fraction = expected.item.toFloat() / (expected.itemCount - 1)))
                repository.saveReaderSettings(id, ReaderSettings(
                    readingMode = mode, startChaptersOnNewPage = chapterBreaks, showTotalPageCount = verifyPageIndex,
                    pageTurnAnimation = if (profileFrames) PageTurnAnimation.SLIDE else PageTurnAnimation.CASCADE,
                ))
                val contentFile = contentCache.entryFile(id)
                if (targetedCold) {
                    assertTrue("An exact source index must survive removal of the content FRB",
                        contentCache.loadEpubSourceIndex(id, source) != null)
                    assertTrue(contentFile.delete())
                    File(context.filesDir, "images/$id").deleteRecursively()
                } else {
                    repository.contentParserForTests = { file, format, resources, position, publish ->
                        check(file.name != source.name) { "The indexed warm open unexpectedly restored a full model" }
                        parser?.invoke(file, format, resources, position, publish) ?: BookParsers.parseContent(file, format, resources)
                    }
                }
                Runtime.getRuntime().gc()
                fun openReader() = ActivityScenario.launch<ReaderOpeningTestActivity>(
                    Intent(context, ReaderOpeningTestActivity::class.java)
                        .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id)
                        .apply { pageWorkers?.let { putExtra(ReaderOpeningTestActivity.EXTRA_PAGE_WORKERS, it) } },
                )
                var persistedCounter: String? = null
                var pageCount = 0
                var indexModified = 0L
                val started = SystemClock.elapsedRealtime()
                openReader().use { scenario ->
                    await("rendered source text near 85%", includeLabels = false) { findText(expected.text) != null }
                    assertEquals(expected.itemCount, app.readerDiagnostics.snapshotFor(id)?.itemCount)
                    Log.i("FrogReaderPerf", "indexed_corpus_ui source_bytes=${source.length()} mode=$mode " +
                        "item=${expected.item} item_count=${expected.itemCount} rendered_ms=${SystemClock.elapsedRealtime() - started}")
                    if (targetedCold) {
                        assertTrue("First text should appear before the content FRB is rebuilt", !contentFile.exists())
                        showChrome(context, scenario)
                        assertTrue(setBookProgress(context, 0f))
                        await("working whole-book slider before full preparation", includeLabels = false) {
                            repository.bookById(id)?.progress?.elementIndex == 0
                        }
                        assertTrue("The seek should complete before the content FRB is rebuilt", !contentFile.exists())
                        Log.i("FrogReaderPerf", "targeted_cold_seek source_bytes=${source.length()} mode=$mode " +
                            "ready_ms=${SystemClock.elapsedRealtime() - started}")
                        await("complete FRB published in the background", includeLabels = false, timeoutMillis = 180_000) {
                            contentFile.isFile
                        }
                        assertEquals("Publishing the FRB should keep the current position",
                            0, repository.bookById(id)?.progress?.elementIndex)
                        Log.i("FrogReaderPerf", "targeted_cold_full_data source_bytes=${source.length()} mode=$mode " +
                            "ready_ms=${SystemClock.elapsedRealtime() - started}")
                    }
                    if (verifyPageIndex) {
                        if (profileFrames) {
                            profilePaginationFrames(scenario, id, expected.pageProgression, indexFile, source.length(), pageWorkers) {
                                repository.bookById(id)?.progress?.documentLocation as? DocumentLocation.Reflowable
                            }
                        }
                        await("persisted global page index and visible counter", includeLabels = false, timeoutMillis = 180_000) {
                            pageCount = repository.bookById(id)?.progress?.totalPages ?: 0
                            if (pageCount <= 0 || indexFile.length() <= 0) return@await false
                            persistedCounter = findPageCounter(context, pageCount)
                            persistedCounter != null
                        }
                        indexModified = indexFile.lastModified()
                        Log.i("FrogReaderPerf", "indexed_corpus_page_index stage=build source_bytes=${source.length()} " +
                            "chapter_breaks=$chapterBreaks workers=${pageWorkers ?: "default"} " +
                            "frame_profile=$profileFrames page_count=$pageCount index_bytes=${indexFile.length()} " +
                            "ready_ms=${SystemClock.elapsedRealtime() - started}")
                    }
                    if (profileSeek) profileCorpusSeeks(context, scenario, expected, mode, source.length()) {
                        repository.bookById(id)?.progress
                    }
                }
                if (verifyPageIndex) {
                    val expectedCounter = checkNotNull(persistedCounter)
                    val reopened = SystemClock.elapsedRealtime()
                    openReader().use {
                        await("saved text and the same exact cached counter", includeLabels = false) {
                            findText(expected.text) != null && findNode { node -> node.text?.toString() == expectedCounter } != null
                        }
                        assertEquals("The cached trial must reuse the persisted page index", indexModified, indexFile.lastModified())
                        Log.i("FrogReaderPerf", "indexed_corpus_page_index stage=cache source_bytes=${source.length()} " +
                            "chapter_breaks=$chapterBreaks workers=${pageWorkers ?: "default"} " +
                            "page_count=$pageCount index_bytes=${indexFile.length()} " +
                            "ready_ms=${SystemClock.elapsedRealtime() - reopened}")
                    }
                }
            }
        } finally {
            repository.contentParserForTests = parser
            try {
                repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
                repository.deleteBook(id)
                if (verifyPageIndex) indexFile.delete()
                source.delete()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private data class CorpusTarget(
        val item: Int,
        val itemCount: Int,
        val text: String,
        val seekTexts: List<String>,
        val pageProgression: PageProgression,
    )

    /** Sample the reader window only while the test performs real animated page turns. */
    private fun profilePaginationFrames(
        scenario: ActivityScenario<ReaderOpeningTestActivity>,
        bookId: String,
        progression: PageProgression,
        indexFile: File,
        sourceBytes: Long,
        workers: Int?,
        location: () -> DocumentLocation.Reflowable?,
    ) {
        lateinit var app: FrogReaderApp
        lateinit var reader: ReaderViewModel
        scenario.onActivity { activity ->
            app = activity.application as FrogReaderApp
            // ReaderScreen already created this model under the activity's book key.
            reader = ViewModelProvider(activity)["reader-$bookId", ReaderViewModel::class.java]
        }
        // The opening preview is replaced by a new pager when the local window finishes.
        // Measure turns in that pager while the separate whole-book index is still building.
        await("rendered local page window and initial position", includeLabels = false) {
            val holder = reader.pagination.value ?: return@await false
            holder.rangeComplete && app.readerDiagnostics.snapshotFor(bookId)?.calculatedPages == holder.pages.size &&
                location() != null
        }
        Log.i("FrogReaderPerf", "indexed_pagination_frame_window source_bytes=$sourceBytes " +
            "workers=${workers ?: "default"} local_pages=${reader.pagination.value?.pages?.size} " +
            "index_ready=${indexFile.isFile}")
        val thread = HandlerThread("reader-frame-benchmark").apply { start() }
        val frames = mutableListOf<Long>()
        var deadlineFrames = 0
        var deadlineMisses = 0
        var droppedReports = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            droppedReports += dropped
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0L) {
                    frames += duration
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                        if (deadline > 0L) {
                            deadlineFrames++
                            if (duration > deadline) deadlineMisses++
                        }
                    }
                }
            }
        }
        var width = 0f
        var height = 0f
        var measuredTurns = 0
        var turnsStartedBeforeIndex = 0
        fun turnPage(forward: Boolean) {
            val before = requireNotNull(location())
            val holderBefore = reader.pagination.value
            val rightEdge = forward != (progression == PageProgression.RTL)
            if (!indexFile.isFile) turnsStartedBeforeIndex++
            tap(width * if (rightEdge) 0.9f else 0.1f, height * 0.5f)
            try {
                await("page turn ${measuredTurns + 1}", includeLabels = false) {
                    val after = location() ?: return@await false
                    val direction = if (after.elementIndex == before.elementIndex) {
                        after.charOffset.compareTo(before.charOffset)
                    } else after.elementIndex.compareTo(before.elementIndex)
                    if (forward) direction > 0 else direction < 0
                }
            } catch (failure: AssertionError) {
                val holderAfter = reader.pagination.value
                Log.e("FrogReaderPerf", "indexed_pagination_turn_timeout source_bytes=$sourceBytes " +
                    "workers=${workers ?: "default"} turn=${measuredTurns + 1} forward=$forward " +
                    "before=$before after=${location()} local_pages_before=${holderBefore?.pages?.size} " +
                    "local_pages_after=${holderAfter?.pages?.size} same_window=${holderBefore === holderAfter} " +
                    "index_ready=${indexFile.isFile}")
                throw failure
            }
            measuredTurns++
        }
        val handler = Handler(thread.looper)
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                width = decor.width.toFloat()
                height = decor.height.toFloat()
                activity.window.addOnFrameMetricsAvailableListener(listener, handler)
            }
            while (measuredTurns < 6 && !indexFile.isFile) {
                turnPage(forward = true)
                // Restore the opening position even if the index finishes during the forward turn.
                turnPage(forward = false)
            }
        } finally {
            try {
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            } finally {
                thread.quitSafely()
                thread.join(5_000)
            }
        }
        assertTrue("The frame callback thread must finish", !thread.isAlive)
        val sorted = frames.sorted()
        val p95 = sorted.getOrNull((sorted.size * 95 + 99) / 100 - 1)
        Log.i("FrogReaderPerf", "indexed_pagination_turn_frames source_bytes=$sourceBytes " +
            "workers=${workers ?: "default"} turns_started_before_index=$turnsStartedBeforeIndex " +
            "measured_turns=$measuredTurns frames=${sorted.size} " +
            "p50_ns=${sorted.getOrNull((sorted.size - 1) / 2)} p95_ns=$p95 max_ns=${sorted.lastOrNull()} " +
            "deadline_frames=$deadlineFrames deadline_misses=$deadlineMisses dropped_reports=$droppedReports")
    }

    private fun profileCorpusSeeks(
        context: Context,
        scenario: ActivityScenario<ReaderOpeningTestActivity>,
        target: CorpusTarget,
        mode: ReadingMode,
        sourceBytes: Long,
        progress: () -> ReadingProgress?,
    ) {
        val trackLabel = context.getString(R.string.reader_track_book)
        val fraction = target.item.toFloat() / (target.itemCount - 1)
        fun destinationVisible(): Boolean = findNode { node ->
            val text = node.text?.toString() ?: return@findNode false
            target.seekTexts.any(text::contains)
        } != null
        repeat(5) { trial ->
            showChrome(context, scenario)
            assertTrue(setBookProgress(context, 0f))
            await("reading position reset to the start", includeLabels = false) {
                progress()?.elementIndex == 0 && !destinationVisible()
            }
            val track = requireNotNull(findDescription(trackLabel))
            val started = SystemClock.elapsedRealtime()
            assertTrue(setBookProgress(track, fraction))
            await("slider destination text near 85%", includeLabels = false) { destinationVisible() }
            val observed = SystemClock.elapsedRealtime() - started
            await("saved global progress near slider destination", includeLabels = false) {
                progress()?.fraction?.let { it in (fraction - 0.01f)..(fraction + 0.01f) } == true
            }
            Log.i("FrogReaderPerf", "indexed_corpus_seek source_bytes=$sourceBytes mode=$mode trial=$trial " +
                "target_item=${target.item} observed_ms=$observed saved_progress_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun prepareCorpus(files: File, id: String, source: File): CorpusTarget {
        val content = EpubParser.parseContent(source, File(files, "images/$id/${source.nameWithoutExtension}"))
        val cache = ChunkedContentCache(File(files, "reader-content"))
        assertTrue(cache.save(id, source, content))
        val desired = (content.totalElements.toLong() * 85 / 100).toInt()
        var index = 0
        fun paragraphPrefix(element: ContentElement): String? = (element as? ContentElement.Paragraph)
            ?.text?.text?.take(18)?.takeIf { it.length == 18 && it.all { char -> !char.isISOControl() } }
        for (chapter in content.chapters) for ((local, element) in chapter.elements.withIndex()) {
            val text = if (index >= desired) paragraphPrefix(element) else null
            if (text != null) {
                // Open chrome can occlude the first line of the destination.
                val seekTexts = chapter.elements.subList(local, minOf(local + 6, chapter.elements.size))
                    .mapNotNull(::paragraphPrefix).distinct()
                return CorpusTarget(index, content.totalElements, text, seekTexts,
                    ReaderProgression.resolve(content.pageProgression, content.language))
            }
            index++
        }
        error("No text near the corpus target")
    }

    private fun verifyNavigation(mode: ReadingMode) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("The fixture uses the isolated Rust app", context.packageName == "knitipka.frogreader")
        val app = context.applicationContext as FrogReaderApp
        val repository = app.bookRepository
        val id = "indexed-ui-${UUID.randomUUID()}"
        val source = File(context.filesDir, "books/$id.epub").apply {
            parentFile!!.mkdirs()
            writeText("Stable source identity for an indexed navigation fixture")
        }
        val book = Book(id, "Indexed navigation fixture", format = BookFormat.EPUB,
            addedAtMillis = System.currentTimeMillis(), fileName = source.name,
            progress = ReadingProgress(elementIndex = 85, fraction = 85f / 99))
        val content = BookContent(List(20) { chapter ->
            Chapter("Chapter ${chapter + 1}", List(5) { local ->
                ContentElement.Paragraph(AnnotatedString(marker(chapter * 5 + local) + " A short fixture paragraph."))
            })
        }, navigation = listOf(
            BookNavigationEntry("First chapter", 0, BookNavigationTarget.ReadingOrder(0, 0)),
            BookNavigationEntry("Last chapter destination", 0, BookNavigationTarget.ReadingOrder(19, 0)),
        ))
        val parser = repository.contentParserForTests
        try {
            repository.addBookForRestore(book)
            repository.saveReaderSettings(id, ReaderSettings(readingMode = mode, startChaptersOnNewPage = true))
            assertTrue(ChunkedContentCache(File(context.filesDir, "reader-content")).save(id, source, content))
            repository.contentParserForTests = { file, format, resources, position, publish ->
                check(file.name != source.name) { "A warm indexed book must not take the full-model path" }
                parser?.invoke(file, format, resources, position, publish) ?: BookParsers.parseContent(file, format, resources)
            }
            val started = SystemClock.elapsedRealtime()
            ActivityScenario.launch<ReaderOpeningTestActivity>(Intent(context, ReaderOpeningTestActivity::class.java)
                .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, id)).use { scenario ->
                await("saved text near 85%") { findText(marker(85)) != null }
                assertEquals(100, app.readerDiagnostics.snapshotFor(id)?.itemCount)
                Log.i("FrogReaderPerf", "indexed_ui mode=$mode stage=saved_position ms=${SystemClock.elapsedRealtime() - started}")
                val contents = context.getString(R.string.reader_contents)
                showChrome(context, scenario)
                await("enabled table of contents button") { findDescription(contents)?.isEnabled == true }
                click(requireNotNull(findDescription(contents)))
                await("last chapter in the table of contents") { findText("Last chapter destination") != null }
                val jumpStarted = SystemClock.elapsedRealtime()
                click(requireNotNull(findText("Last chapter destination")))
                await("last chapter text") { findText(marker(95)) != null }
                Log.i("FrogReaderPerf", "indexed_ui mode=$mode stage=last_chapter ms=${SystemClock.elapsedRealtime() - jumpStarted}")
                showChrome(context, scenario)
                val seekStarted = SystemClock.elapsedRealtime()
                assertTrue(setBookProgress(context, 0.10f))
                await("text at ten percent") { findText(marker(10)) != null }
                await("saved global progress") {
                    repository.bookById(id)?.progress?.fraction?.let { it in 0.08f..0.15f } == true
                }
                Log.i("FrogReaderPerf", "indexed_ui mode=$mode stage=slider_10 ms=${SystemClock.elapsedRealtime() - seekStarted}")
            }
        } finally {
            repository.contentParserForTests = parser
            repository.bookById(id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
            repository.deleteBook(id)
            source.delete()
        }
    }

    private fun showChrome(context: Context, scenario: ActivityScenario<ReaderOpeningTestActivity>) {
        val track = context.getString(R.string.reader_track_book)
        if (findDescription(track)?.isEnabled == true) return
        tapCenter(scenario)
        await("book progress control") { findDescription(track)?.isEnabled == true }
    }

    private fun hideChrome(context: Context, scenario: ActivityScenario<ReaderOpeningTestActivity>) {
        val track = context.getString(R.string.reader_track_book)
        if (findDescription(track)?.isEnabled != true) return
        tapCenter(scenario)
        await("reader chrome hidden") { findDescription(track)?.isEnabled != true }
    }

    private fun tapCenter(scenario: ActivityScenario<ReaderOpeningTestActivity>) {
        var x = 0f
        var y = 0f
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            x = decor.width / 2f
            y = decor.height / 2f
        }
        tap(x, y)
    }

    private fun setBookProgress(context: Context, fraction: Float): Boolean =
        setBookProgress(requireNotNull(findDescription(context.getString(R.string.reader_track_book))), fraction)

    private fun setBookProgress(track: AccessibilityNodeInfo, fraction: Float): Boolean {
        val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, fraction) }
        return track.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments)
    }

    private fun marker(index: Int) = "Source-${index.toString().padStart(3, '0')}"

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).also {
                try { InstrumentationRegistry.getInstrumentation().sendPointerSync(it) } finally { it.recycle() }
            }
        }
    }

    private fun findText(text: String): AccessibilityNodeInfo? = findNode { it.text?.contains(text) == true }
    private fun findDescription(label: String): AccessibilityNodeInfo? = findNode { it.contentDescription?.toString() == label }

    private fun findPageCounter(context: Context, totalPages: Int): String? =
        visibleNodes().mapNotNull { it.text?.toString() }.firstOrNull { text ->
            val page = text.substringBefore('/').toIntOrNull() ?: return@firstOrNull false
            page in 1..totalPages && text == context.getString(R.string.reader_page_number_total, page, totalPages)
        }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        visibleNodes().firstOrNull(predicate)

    private fun visibleNodes(): Sequence<AccessibilityNodeInfo> = sequence {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return@sequence
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) yield(node)
            repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
        }
    }

    private fun await(label: String, includeLabels: Boolean = true, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        val details = if (includeLabels) visibleNodes()
            .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .take(32).joinToString(" | ", prefix = ". Visible fixture labels: ") else ""
        assertTrue("Timed out waiting for $label$details", condition())
    }
}
