package com.example.frogreader.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.IndexedContentSession
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.TextBookParser
import com.example.frogreader.data.parser.Woff2Decoder
import com.example.frogreader.data.parser.WoffDecoder
import com.example.frogreader.data.parser.mobi.Exth
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.PdbFile
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.SEARCH_RESULT_LIMIT
import com.example.frogreader.ui.reader.SearchResults
import com.example.frogreader.ui.reader.adjustReaderElement
import com.example.frogreader.ui.reader.searchBook
import com.example.frogreader.ui.reader.searchIndexedBook
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Android parity & benchmark suite for Rust engine milestones:
 * - M3.1 Native FRB Search (`searchIndexedBook` -> zero-alloc `RustSearchNative.searchFrbChapter`)
 * - M3.2 Native WOFF 1.0 & WOFF 2.0 Font Unpackers
 * - M4.1 Native TXT & Markdown Parser / Direct FRB Writer
 * - M4.2 Native FB2 Streaming Metadata, Structural Scan, Base64 & Progressive Parse
 * - M5.2 Native MOBI6 PDB / PalmDoc Decompression & Parsing
 */
@RunWith(AndroidJUnit4::class)
class RustEngineDeviceBenchmarkTest {

    // -------------------------------------------------------------------------
    // M3.1 Search & M3.2 WOFF/WOFF2
    // -------------------------------------------------------------------------

    @Test
    fun benchmarkM3SearchAndFontsOnDevice() = runBlocking {
        withBenchmarkEnv("m3-search-fonts") { context, root ->
            // ---- M3.1: 20-chapter ~2 MB FRB archive with English + Russian ё/е + accented text ----
            val blockStyle = BlockStyle(align = BlockAlign.JUSTIFY, fontFamily = "serif")
            val filler = (
                "The quick brown fox jumps over the lazy dog across realistic literary prose sentences. " +
                    "В чащах юга жил бы цитрус? Да, но фальшивый экземпляр! Ёжик и ежик бежали по тропе. " +
                    "Café naïve résumé Straße Søren İstanbul Привіт світ. "
                ).repeat(4)

            val chapters = List(20) { ch ->
                Chapter(
                    title = "Chapter ${ch + 1}",
                    elements = List(95) { el ->
                        val text = buildAnnotatedString {
                            append("Ch$ch-P$el. ")
                            append(filler)
                            if (el % 12 == 0) {
                                append(" Target needle occurrence in chapter $ch.")
                            }
                            if (el % 18 == 0) {
                                append(" Старый ёжик и маленький ежик у Café.")
                            }
                            if (el % 25 == 0) {
                                pushStringAnnotation(FOOTNOTE_TAG, "fn-$ch-$el")
                                append("[footnote needle]")
                                pop()
                            }
                        }
                        ContentElement.Paragraph(text, ParagraphStyle.NORMAL, blockStyle)
                    },
                )
            }
            val content = BookContent(chapters)
            val source = File(root, "m3-search.epub").apply { writeText("M3 device benchmark source") }
            val cache = ChunkedContentCache(File(root, "m3-cache"))
            assertTrue("cache.save must succeed", cache.save("m3-book", source, content))

            val nativeSession = requireNotNull(cache.openIndexed("m3-book", source))
            val handleSearches = AtomicInteger()
            val blockReads = AtomicInteger()
            val chapterReads = AtomicInteger()
            val spySession = object : IndexedContentSession {
                override val index = nativeSession.index
                override val contentKey = nativeSession.contentKey
                override fun readChapter(index: Int): Chapter? {
                    chapterReads.incrementAndGet()
                    throw AssertionError("Zero-alloc search must not call readChapter($index)")
                }
                override fun readChapterBlockBytes(index: Int): ByteArray? {
                    blockReads.incrementAndGet()
                    return nativeSession.readChapterBlockBytes(index)
                }
                override fun <T> withNativeHandle(action: (Long) -> T): T? {
                    handleSearches.incrementAndGet()
                    return nativeSession.withNativeHandle(action)
                }
                override fun readChapters(first: Int, last: Int, checkActive: () -> Unit): List<Chapter>? {
                    chapterReads.incrementAndGet()
                    throw AssertionError("Zero-alloc search must not call readChapters($first..$last)")
                }
                override fun readGlobals(): BookContent? = nativeSession.readGlobals()
                override fun close() = nativeSession.close()
            }

            val session = requireNotNull(IndexedBookSession.open(Closeable {}) { spySession })
            var corpusBytes = 0L
            val searchTimesMs = DoubleArray(5)
            lateinit var lastResult: SearchResults

            session.use { s ->
                for (i in chapters.indices) {
                    corpusBytes += requireNotNull(nativeSession.readChapterBlockBytes(i)).size
                }
                assertTrue("Expected ~2 MB FRB corpus, got $corpusBytes", corpusBytes >= 1_500_000L)

                // Exact parity check on English, Russian ё/е, and accented queries against Kotlin searchBook.
                for (q in listOf("needle", "ёжик", "ежик", "café", "istanbul")) {
                    val expected = searchBook(
                        ReaderChapterItems(content.chapters, cover = null) { adjustReaderElement(it, hideFootnotes = true) },
                        q,
                        SEARCH_RESULT_LIMIT,
                    )
                    val actual = searchIndexedBook(s, q, coverCount = 0, hideFootnotes = true, limit = SEARCH_RESULT_LIMIT)
                    assertSearchResultsEqual("m3_search q=$q", expected, actual)
                    assertTrue("Expected matches > 0 for query '$q'", actual.totalCount > 0)
                }

                // Warmup + 5 warm timed runs
                repeat(2) {
                    searchIndexedBook(s, "needle", coverCount = 0, hideFootnotes = true, limit = SEARCH_RESULT_LIMIT)
                }
                for (i in searchTimesMs.indices) {
                    val startNs = System.nanoTime()
                    lastResult = searchIndexedBook(s, "needle", coverCount = 0, hideFootnotes = true, limit = SEARCH_RESULT_LIMIT)
                    searchTimesMs[i] = (System.nanoTime() - startNs) / 1_000_000.0
                }
            }
            assertTrue("Expected zero-copy native handle searches > 0", handleSearches.get() > 0)
            assertEquals("Zero-copy handle search must not copy chapter blocks", 0, blockReads.get())
            assertEquals("Zero-alloc native search must not decode Chapter objects", 0, chapterReads.get())

            val searchMedianMs = median(searchTimesMs)
            val throughputMbS = (corpusBytes / 1_000_000.0) / (searchMedianMs / 1000.0).coerceAtLeast(1e-9)
            emitMetric(
                context,
                String.format(
                    Locale.ROOT,
                    "ENGINE_BENCH subsystem=m3_search corpus_bytes=%d matches=%d median_ms=%.3f throughput_mb_s=%.2f",
                    corpusBytes,
                    lastResult.totalCount,
                    searchMedianMs,
                    throughputMbS,
                ),
            )

            // ---- M3.2: WOFF 1.0 & WOFF 2.0 ----
            val woffStored = ByteArray(1024) { (it * 31).toByte() }
            val woffCompressed = ByteArray(64 * 1024) { (it % 29).toByte() }
            val woffBytes = buildWoff(woffStored, woffCompressed)

            val woff2Bytes = Woff2Fixtures.buildWoff2(
                listOf(
                    Woff2Fixtures.Spec("glyf", Woff2Fixtures.transformedGlyf(Woff2Fixtures.TRIANGLE), origLength = 64, transformed = true),
                    Woff2Fixtures.Spec("loca", ByteArray(0), origLength = 6, transformed = true),
                    Woff2Fixtures.Spec("head", Woff2Fixtures.headTable(indexToLocFormat = 1)),
                    Woff2Fixtures.Spec("hhea", Woff2Fixtures.hheaTable(numHMetrics = 2)),
                    Woff2Fixtures.Spec("maxp", Woff2Fixtures.maxpTable(numGlyphs = 2)),
                    Woff2Fixtures.Spec("hmtx", Woff2Fixtures.transformedHmtx(intArrayOf(500, 600)), origLength = 8, transformed = true),
                    Woff2Fixtures.Spec("cmap", ByteArray(32 * 1024) { (it % 17).toByte() }),
                ),
            )

            // Parity assertions
            val woffNative = requireNotNull(RustWoffNative.decodeWoff(woffBytes, 32 * 1024 * 1024))
            val woffPublic = requireNotNull(WoffDecoder.decode(woffBytes))
            assertArrayEquals("WOFF native vs public API parity", woffNative, woffPublic)
            assertEquals(
                "Valid SFNT magic for WOFF",
                0x00010000,
                ByteBuffer.wrap(woffPublic).order(ByteOrder.BIG_ENDIAN).getInt(0),
            )

            val woff2Native = requireNotNull(RustWoffNative.decodeWoff2(woff2Bytes, 32 * 1024 * 1024))
            val woff2Actual = requireNotNull(Woff2Decoder.decode(woff2Bytes))
            assertArrayEquals("WOFF2 native vs public API parity", woff2Native, woff2Actual)
            assertEquals(
                "Valid SFNT magic for WOFF2",
                0x00010000,
                ByteBuffer.wrap(woff2Actual).order(ByteOrder.BIG_ENDIAN).getInt(0),
            )

            // Warmup + 20 warm iterations
            repeat(5) {
                requireNotNull(WoffDecoder.decode(woffBytes))
                requireNotNull(Woff2Decoder.decode(woff2Bytes))
            }
            val woffTimesMs = DoubleArray(20)
            val woff2TimesMs = DoubleArray(20)
            for (i in 0 until 20) {
                val t0 = System.nanoTime()
                assertNotNull(WoffDecoder.decode(woffBytes))
                woffTimesMs[i] = (System.nanoTime() - t0) / 1_000_000.0

                val t1 = System.nanoTime()
                assertNotNull(Woff2Decoder.decode(woff2Bytes))
                woff2TimesMs[i] = (System.nanoTime() - t1) / 1_000_000.0
            }

            emitMetric(
                context,
                String.format(
                    Locale.ROOT,
                    "ENGINE_BENCH subsystem=m3_woff woff_median_ms=%.4f woff2_median_ms=%.4f",
                    median(woffTimesMs),
                    median(woff2TimesMs),
                ),
            )
        }
    }

    // -------------------------------------------------------------------------
    // M4.1 TXT & Markdown + M4.2 FB2
    // -------------------------------------------------------------------------

    @Test
    fun benchmarkM4TextMarkdownAndFb2OnDevice() {
        withBenchmarkEnv("m4-txt-md-fb2") { context, root ->
            // ---- M4.1: ~512 KB Russian+English TXT & ~128 KB rich Markdown ----
            val txtFile = File(root, "sample-512k.txt").apply {
                writeText(buildSampleTxt(targetBytes = 512 * 1024), Charsets.UTF_8)
            }
            assertTrue("TXT fixture must be >= 500 KB", txtFile.length() >= 500 * 1024L)

            val mdFile = File(root, "sample-128k.md").apply {
                writeText(buildSampleMarkdown(targetBytes = 128 * 1024), Charsets.UTF_8)
            }
            assertTrue("MD fixture must be >= 120 KB", mdFile.length() >= 120 * 1024L)

            val cache = ChunkedContentCache(File(root, "m4-cache"))

            // Reference TXT parse for parity
            val refTxt = TextBookParser.parseContent(txtFile, BookFormat.TXT)
            assertTrue(refTxt.chapters.single().elements.size > 100)

            // Warmup
            assertTrue(cache.saveTxtDirectly("txt-warm", txtFile))
            requireNotNull(cache.openIndexed("txt-warm", txtFile)).close()
            assertEquals("FrogReader Markdown Benchmark", TextBookParser.parseMetadata(mdFile, BookFormat.MD).title)
            val refMd = TextBookParser.parseContent(mdFile, BookFormat.MD)
            assertTrue(refMd.navigation.size >= 20)
            assertTrue(cache.saveMdDirectly("md-warm", mdFile))
            requireNotNull(cache.openIndexed("md-warm", mdFile)).close()

            val runs = 5
            val txtDirectMs = DoubleArray(runs)
            val mdMetaMs = DoubleArray(runs)
            val mdParseMs = DoubleArray(runs)
            val mdDirectMs = DoubleArray(runs)

            for (i in 0 until runs) {
                val txtId = "txt-bench-$i"
                val t0 = System.nanoTime()
                assertTrue("saveTxtDirectly failed", cache.saveTxtDirectly(txtId, txtFile))
                val txtSession = requireNotNull(cache.openIndexed(txtId, txtFile))
                txtDirectMs[i] = (System.nanoTime() - t0) / 1_000_000.0
                txtSession.use { s ->
                    assertEquals(refTxt.chapters.single().elements.size, s.index.totalElements)
                    assertEquals(refTxt.language, s.index.language)
                    val ch = requireNotNull(s.readChapter(0))
                    assertEquals(refTxt.chapters.single().elements.size, ch.elements.size)
                    val firstRef = (refTxt.chapters.single().elements.first() as ContentElement.Paragraph).text.text
                    val firstDirect = (ch.elements.first() as ContentElement.Paragraph).text.text
                    assertEquals(firstRef, firstDirect)
                }

                val t1 = System.nanoTime()
                val mdMeta = TextBookParser.parseMetadata(mdFile, BookFormat.MD)
                mdMetaMs[i] = (System.nanoTime() - t1) / 1_000_000.0
                assertEquals("FrogReader Markdown Benchmark", mdMeta.title)

                val t2 = System.nanoTime()
                val mdContent = TextBookParser.parseContent(mdFile, BookFormat.MD)
                mdParseMs[i] = (System.nanoTime() - t2) / 1_000_000.0
                assertEquals(refMd.navigation.size, mdContent.navigation.size)
                assertEquals(refMd.chapters.single().elements.size, mdContent.chapters.single().elements.size)
                assertTrue(
                    "Markdown must contain GFM tables",
                    mdContent.chapters.single().elements.any { it is ContentElement.Table },
                )

                val mdId = "md-bench-$i"
                val t3 = System.nanoTime()
                assertTrue("saveMdDirectly failed", cache.saveMdDirectly(mdId, mdFile))
                val mdSession = requireNotNull(cache.openIndexed(mdId, mdFile))
                mdDirectMs[i] = (System.nanoTime() - t3) / 1_000_000.0
                mdSession.use { s ->
                    assertEquals(refMd.chapters.single().elements.size, s.index.totalElements)
                    assertEquals(refMd.navigation.size, s.index.navigation.size)
                    val ch = requireNotNull(s.readChapter(0))
                    assertEquals(refMd.chapters.single().elements.size, ch.elements.size)
                }
            }

            emitMetric(
                context,
                String.format(
                    Locale.ROOT,
                    "ENGINE_BENCH subsystem=m4_txt_md txt_direct_frb_ms=%.3f md_meta_ms=%.3f md_parse_ms=%.3f md_direct_frb_ms=%.3f",
                    median(txtDirectMs),
                    median(mdMetaMs),
                    median(mdParseMs),
                    median(mdDirectMs),
                ),
            )

            // ---- M4.2: ~512 KB multi-section FB2 with cover, Base64 PNG, poem, epigraph, footnotes ----
            val coverPng = ByteArray(32 * 1024) { idx ->
                when (idx) {
                    0 -> 0x89.toByte()
                    1 -> 0x50
                    2 -> 0x4E
                    3 -> 0x47
                    4 -> 0x0D
                    5 -> 0x0A
                    6 -> 0x1A
                    7 -> 0x0A
                    else -> ((idx * 37 + 11) and 0xFF).toByte()
                }
            }
            val coverB64Bytes = Base64.getMimeEncoder(76, "\n".toByteArray(Charsets.US_ASCII)).encode(coverPng)
            val fb2File = File(root, "sample-512k.fb2").apply {
                writeText(buildSampleFb2(String(coverB64Bytes, Charsets.US_ASCII), targetBytes = 512 * 1024), Charsets.UTF_8)
            }
            assertTrue("FB2 fixture must be >= 500 KB", fb2File.length() >= 500 * 1024L)

            // Warmup
            val warmMeta = Fb2Parser.parseMetadata { fb2File.inputStream().buffered() }
            assertEquals("Хроники Лягушки", warmMeta.title)
            assertEquals("Иван Тестов", warmMeta.author)
            assertEquals("ru", warmMeta.language)
            assertArrayEquals(coverPng, warmMeta.coverBytes)

            val warmStruct = Fb2Parser.scanStructure({ fb2File.inputStream().buffered() })
            assertEquals(2, warmStruct.bodies.size)
            assertTrue(warmStruct.sections.size >= 16)

            val warmDecodedB64 = requireNotNull(
                RustFb2TxtNative.decodeMimeBase64(coverB64Bytes, coverPng.size.toLong() + 1024L),
            )
            assertArrayEquals(coverPng, warmDecodedB64)

            val warmImgs = File(root, "fb2-warm-imgs").apply { mkdirs() }
            val warmPreviews = mutableListOf<BookContent>()
            val warmFull = Fb2Parser.parseContent(
                open = { fb2File.inputStream().buffered() },
                imagesDir = warmImgs,
                onPreview = warmPreviews::add,
            )
            assertTrue("Expected progressive FB2 preview", warmPreviews.isNotEmpty())
            assertTrue(warmPreviews.first().totalElements >= 32)
            assertTrue(warmPreviews.first().totalElements < warmFull.totalElements)
            assertTrue("Expected FB2 notes", warmFull.notes.isNotEmpty())

            val warmDirectImgs = File(root, "fb2-warm-direct-imgs").apply { mkdirs() }
            assertTrue("saveFb2Directly warmup failed", cache.saveFb2Directly("fb2-warm", fb2File, warmDirectImgs))
            requireNotNull(cache.openIndexed("fb2-warm", fb2File)).close()

            val fb2MetaMs = DoubleArray(runs)
            val fb2ScanMs = DoubleArray(runs)
            val fb2Base64Ms = DoubleArray(runs)
            val fb2ParseMs = DoubleArray(runs)
            val fb2DirectMs = DoubleArray(runs)

            for (i in 0 until runs) {
                val t0 = System.nanoTime()
                val meta = Fb2Parser.parseMetadata { fb2File.inputStream().buffered() }
                fb2MetaMs[i] = (System.nanoTime() - t0) / 1_000_000.0
                assertEquals("Хроники Лягушки", meta.title)
                assertArrayEquals(coverPng, meta.coverBytes)

                val t1 = System.nanoTime()
                val structure = Fb2Parser.scanStructure({ fb2File.inputStream().buffered() })
                fb2ScanMs[i] = (System.nanoTime() - t1) / 1_000_000.0
                assertEquals(warmStruct.sections.size, structure.sections.size)

                val t2 = System.nanoTime()
                val decoded = requireNotNull(
                    RustFb2TxtNative.decodeMimeBase64(coverB64Bytes, coverPng.size.toLong() + 1024L),
                )
                fb2Base64Ms[i] = (System.nanoTime() - t2) / 1_000_000.0
                assertArrayEquals(coverPng, decoded)

                val imgsDir = File(root, "fb2-imgs-$i").apply { mkdirs() }
                val previews = mutableListOf<BookContent>()
                val t3 = System.nanoTime()
                val parsed = Fb2Parser.parseContent(
                    open = { fb2File.inputStream().buffered() },
                    imagesDir = imgsDir,
                    onPreview = previews::add,
                )
                fb2ParseMs[i] = (System.nanoTime() - t3) / 1_000_000.0
                assertEquals(warmFull.chapters.size, parsed.chapters.size)
                assertEquals(warmFull.totalElements, parsed.totalElements)
                assertTrue(previews.isNotEmpty())

                val fb2Id = "fb2-bench-$i"
                val directImgsDir = File(root, "fb2-direct-imgs-$i").apply { mkdirs() }
                val t4 = System.nanoTime()
                assertTrue("saveFb2Directly failed", cache.saveFb2Directly(fb2Id, fb2File, directImgsDir))
                val fb2Session = requireNotNull(cache.openIndexed(fb2Id, fb2File))
                fb2DirectMs[i] = (System.nanoTime() - t4) / 1_000_000.0
                fb2Session.use { s ->
                    assertEquals(warmFull.chapters.size, s.index.chapters.size)
                    assertEquals(warmFull.totalElements, s.index.totalElements)
                    assertEquals(warmFull.navigation.size, s.index.navigation.size)
                    assertEquals(warmFull.linkTargets.size, s.index.linkTargets.size)
                    val globals = requireNotNull(s.readGlobals())
                    assertEquals(warmFull.notes.keys, globals.notes.keys)
                    assertEquals(warmFull.notes.size, globals.notes.size)
                    val ch0 = requireNotNull(s.readChapter(0))
                    assertEquals(warmFull.chapters.first().elements.size, ch0.elements.size)
                    assertTrue(
                        "Direct FB2 FRB must extract referenced binary images",
                        directImgsDir.listFiles()?.isNotEmpty() == true,
                    )
                }
            }

            emitMetric(
                context,
                String.format(
                    Locale.ROOT,
                    "ENGINE_BENCH subsystem=m4_fb2 fb2_meta_ms=%.3f fb2_scan_ms=%.3f fb2_base64_ms=%.3f fb2_parse_ms=%.3f fb2_direct_frb_ms=%.3f",
                    median(fb2MetaMs),
                    median(fb2ScanMs),
                    median(fb2Base64Ms),
                    median(fb2ParseMs),
                    median(fb2DirectMs),
                ),
            )
        }
    }

    // -------------------------------------------------------------------------
    // M5.2 MOBI
    // -------------------------------------------------------------------------

    @Test
    fun benchmarkM5MobiOnDevice() {
        withBenchmarkEnv("m5-mobi") { context, root ->
            val chapterCount = 20
            val html = buildMobiHtml(chapterCount = chapterCount, targetBytes = 256 * 1024)
            assertTrue("MOBI HTML must be >= 250 KB", html.toByteArray(Charsets.UTF_8).size >= 250 * 1024)

            val mobiFile = File(root, "bench-20ch.mobi")
            MobiFixtures.buildMobi6(
                target = mobiFile,
                html = html,
                compress = true,
                fullName = "FrogReader MOBI Benchmark",
                exth = listOf(
                    Exth.AUTHOR to "Ada Lovelace".toByteArray(Charsets.UTF_8),
                    Exth.UPDATED_TITLE to "FrogReader MOBI Benchmark".toByteArray(Charsets.UTF_8),
                    Exth.PUBLISHER to "FrogPress".toByteArray(Charsets.UTF_8),
                    Exth.LANGUAGE to "en".toByteArray(Charsets.UTF_8),
                ),
            )

            // Warmup & parity verification
            val warmMeta = MobiParser.parseMetadata(mobiFile)
            assertEquals("FrogReader MOBI Benchmark", warmMeta.title)
            assertEquals("Ada Lovelace", warmMeta.author)
            assertEquals("FrogPress", warmMeta.publisher)
            assertEquals("en", warmMeta.language)

            val warmImgs = File(root, "mobi-warm-imgs").apply { mkdirs() }
            val warmContent = MobiParser.parseContent(mobiFile, warmImgs)
            assertEquals("MOBI6 chapter count parity", chapterCount, warmContent.chapters.size)
            assertEquals("Chapter 1: Opening", warmContent.chapters.first().title)
            assertEquals("Chapter 10: Opening", warmContent.chapters[9].title)
            assertEquals("Chapter 20: Opening", warmContent.chapters.last().title)
            val firstChapterText = warmContent.chapters.first().elements
                .filterIsInstance<ContentElement.Paragraph>()
                .joinToString("\n") { it.text.text }
            assertTrue("Chapter 1 prose parity", firstChapterText.contains("Chapter 1 paragraph 1"))
            val lastChapterText = warmContent.chapters.last().elements
                .filterIsInstance<ContentElement.Paragraph>()
                .joinToString("\n") { it.text.text }
            assertTrue("Chapter 20 prose parity", lastChapterText.contains("Chapter 20 paragraph 1"))

            val runs = 5
            val mobiMetaMs = DoubleArray(runs)
            val mobiParseMs = DoubleArray(runs)

            for (i in 0 until runs) {
                val t0 = System.nanoTime()
                val meta = MobiParser.parseMetadata(mobiFile)
                mobiMetaMs[i] = (System.nanoTime() - t0) / 1_000_000.0
                assertEquals("FrogReader MOBI Benchmark", meta.title)

                val imgsDir = File(root, "mobi-imgs-$i").apply { mkdirs() }
                val t1 = System.nanoTime()
                val parsed = MobiParser.parseContent(mobiFile, imgsDir)
                mobiParseMs[i] = (System.nanoTime() - t1) / 1_000_000.0
                assertEquals(chapterCount, parsed.chapters.size)
                assertEquals(warmContent.totalElements, parsed.totalElements)
            }

            emitMetric(
                context,
                String.format(
                    Locale.ROOT,
                    "ENGINE_BENCH subsystem=m5_mobi mobi_meta_ms=%.3f mobi_parse_ms=%.3f",
                    median(mobiMetaMs),
                    median(mobiParseMs),
                ),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Harness & Metric Logging
    // -------------------------------------------------------------------------

    private inline fun withBenchmarkEnv(prefix: String, block: (Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Device benchmarks require the isolated Rust app",
            context.packageName == "knitipka.frogreader",
        )
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)

        val root = File.createTempFile("$prefix-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:$prefix")
        wakeLock.acquire(10 * 60 * 1000L)
        try {
            block(context, root)
            assertTrue("Rust engine must remain available after $prefix", RustBridge.shared.isAvailable)
        } finally {
            root.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun emitMetric(context: Context, line: String) {
        Log.i(PERF_TAG, line)
        synchronized(logLock) {
            val logFile = File(context.filesDir, LOG_RELATIVE_PATH)
            logFile.parentFile!!.mkdirs()
            if (logInitialized.compareAndSet(false, true)) {
                logFile.writeText("$line\n")
            } else {
                logFile.appendText("$line\n")
            }
        }
    }

    private fun median(samples: DoubleArray): Double {
        val sorted = samples.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun assertSearchResultsEqual(label: String, expected: SearchResults, actual: SearchResults) {
        assertEquals("$label query", expected.query, actual.query)
        assertEquals("$label totalCount", expected.totalCount, actual.totalCount)
        assertEquals("$label truncated", expected.truncated, actual.truncated)
        fun rows(r: SearchResults) = r.matches.map {
            listOf(it.itemIndex, it.chapterIndex, it.charStart, it.before, it.match, it.after)
        }
        assertEquals("$label matches", rows(expected), rows(actual))
    }

    // -------------------------------------------------------------------------
    // Fixture Generators
    // -------------------------------------------------------------------------

    private fun buildSampleTxt(targetBytes: Int): String {
        val sb = StringBuilder(targetBytes + 4096)
        var byteCount = 0
        var idx = 0
        while (byteCount < targetBytes) {
            idx++
            val chunk = "Абзац №$idx. Старый ёжик шёл по осеннему лесу, слушая шелест листьев и далёкий шум ручья.\r\n" +
                "Paragraph $idx continues with English literary text to benchmark UTF-8 line normalization and FRB encoding.\n\n"
            sb.append(chunk)
            byteCount += chunk.toByteArray(Charsets.UTF_8).size
        }
        return sb.toString()
    }

    private fun buildSampleMarkdown(targetBytes: Int): String {
        val sb = StringBuilder(targetBytes + 4096)
        sb.append("# FrogReader Markdown Benchmark\n\n")
        var section = 0
        while (sb.length < targetBytes) {
            section++
            sb.append("## Section $section — *Overview* & **Details**\n\n")
            sb.append("Paragraph $section with ~~strikethrough~~, `inline_code()`, and a [local jump](#section-$section--overview--details) plus [external](https://example.invalid/s/$section).\n\n")
            sb.append("| Metric | Value | Notes |\n")
            sb.append("| :--- | :---: | ---: |\n")
            sb.append("| Section | $section | *italic* |\n")
            sb.append("| Speed | `fast` | **bold** |\n\n")
            sb.append("```kotlin\nfun section$section(x: Int): Int = x * 2 + $section\n```\n\n")
            sb.append("> Quoted insight for section $section with Russian text: Привет, ёжик!\n\n")
        }
        return sb.toString()
    }

    private fun buildSampleFb2(coverBase64: String, targetBytes: Int): String {
        val sb = StringBuilder(targetBytes + coverBase64.length + 8192)
        sb.append(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <genre>prose_classic</genre>
                  <author><first-name>Иван</first-name><last-name>Тестов</last-name></author>
                  <book-title>Хроники Лягушки</book-title>
                  <annotation><p>Тестовая аннотация &amp; описание для устройства Pixel 9a.</p></annotation>
                  <coverpage><image l:href="#cover.png"/></coverpage>
                  <lang>ru</lang>
                </title-info>
              </description>
              <body>
                <title><p>Хроники Лягушки</p></title>
                <epigraph><p>В начале было слово &mdash; и оно было быстрым.</p><text-author>Летописец</text-author></epigraph>
            """.trimIndent(),
        )
        val sections = 20
        val parasPerSection = 65
        for (s in 1..sections) {
            sb.append("<section id=\"sec-$s\"><title><p>Глава $s</p></title>")
            if (s == 1) {
                sb.append("<image l:href=\"#cover.png\"/>")
            }
            sb.append(
                "<poem><stanza><v>Строка первая главы $s,</v><v>Строка вторая у пруда.</v></stanza></poem>",
            )
            for (p in 1..parasPerSection) {
                sb.append(
                    "<p>Глава $s, абзац $p. Подробный текст на русском и английском языках для измерения скорости потокового разбора FB2 " +
                        "с примечанием<a l:href=\"#n$s\" type=\"note\">[$s]</a> и сущностями &laquo;кавычки&raquo; &mdash; тире. " +
                        "Additional English sentence for realistic UTF-8 byte density in section $s paragraph $p.</p>",
                )
            }
            sb.append("</section>\n")
        }
        sb.append("</body>\n<body name=\"notes\">\n")
        for (s in 1..sections) {
            sb.append("<section id=\"n$s\"><title><p>$s</p></title><p>Примечание к главе $s.</p></section>\n")
        }
        sb.append("</body>\n")
        sb.append("<binary id=\"cover.png\" content-type=\"image/png\">\n")
        sb.append(coverBase64)
        sb.append("\n</binary>\n</FictionBook>\n")
        return sb.toString()
    }

    private fun buildMobiHtml(chapterCount: Int, targetBytes: Int): String {
        val perChapterTarget = targetBytes / chapterCount
        val sb = StringBuilder(targetBytes + 8192)
        sb.append("<html><head><title>FrogReader MOBI Benchmark</title></head><body>")
        for (ch in 1..chapterCount) {
            if (ch > 1) sb.append("<mbp:pagebreak/>")
            val chapterStart = sb.length
            sb.append("<h2>Chapter $ch: Opening</h2>")
            var p = 0
            while (sb.length - chapterStart < perChapterTarget) {
                p++
                sb.append(
                    "<p>Chapter $ch paragraph $p. The quick brown fox jumps over the lazy dog across realistic MOBI6 HTML prose " +
                        "with <em>emphasis</em>, <strong>strong text</strong>, and Cyrillic: Привет, ёжик из главы $ch!</p>",
                )
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Minimal WOFF 1.0 Builder (from RustSearchWoffTest)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Minimal WOFF 2.0 Builder (from Woff2TestFonts)
    // -------------------------------------------------------------------------

    private object Woff2Fixtures {
        private class BitWriter {
            private val out = ByteArrayOutputStream()
            private var current = 0
            private var bitCount = 0

            fun writeBits(value: Int, bits: Int) {
                for (i in 0 until bits) {
                    current = current or (((value ushr i) and 1) shl bitCount)
                    bitCount++
                    if (bitCount == 8) {
                        out.write(current)
                        current = 0
                        bitCount = 0
                    }
                }
            }

            fun alignByte() {
                if (bitCount > 0) {
                    out.write(current)
                    current = 0
                    bitCount = 0
                }
            }

            fun writeBytes(bytes: ByteArray, off: Int, len: Int) {
                check(bitCount == 0) { "not byte-aligned" }
                out.write(bytes, off, len)
            }

            fun finish(): ByteArray {
                alignByte()
                return out.toByteArray()
            }
        }

        private fun brotliUncompressed(data: ByteArray): ByteArray {
            val writer = BitWriter()
            writer.writeBits(0, 1)
            var off = 0
            while (off < data.size) {
                val chunk = minOf(65536, data.size - off)
                writer.writeBits(0, 1)
                writer.writeBits(0, 2)
                writer.writeBits(chunk - 1, 16)
                writer.writeBits(1, 1)
                writer.alignByte()
                writer.writeBytes(data, off, chunk)
                off += chunk
            }
            writer.writeBits(1, 1)
            writer.writeBits(1, 1)
            return writer.finish()
        }

        private val KNOWN_TAGS = arrayOf(
            "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
            "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
            "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea",
            "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
            "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar",
            "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
            "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
            "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
        )

        class Spec(
            val tag: String,
            val data: ByteArray,
            val origLength: Int = data.size,
            val transformed: Boolean = false,
            val transformVersion: Int = -1,
        )

        val TRIANGLE = listOf(
            Triple(50, 0, true),
            Triple(550, 900, true),
            Triple(1050, 0, false),
        )

        fun buildWoff2(tables: List<Spec>, flavor: Long = 0x00010000L): ByteArray {
            val payload = ByteArrayOutputStream()
            for (table in tables) payload.write(table.data)
            val compressed = brotliUncompressed(payload.toByteArray())

            val directory = ByteArrayOutputStream()
            for (table in tables) {
                val index = KNOWN_TAGS.indexOf(table.tag)
                val version = when {
                    table.transformVersion >= 0 -> table.transformVersion
                    table.transformed -> if (table.tag == "hmtx") 1 else 0
                    table.tag == "glyf" || table.tag == "loca" -> 3
                    else -> 0
                }
                directory.write((if (index >= 0) index else 63) or (version shl 6))
                if (index < 0) directory.write(table.tag.toByteArray(Charsets.ISO_8859_1))
                directory.write(uintBase128(table.origLength))
                if (table.transformed) directory.write(uintBase128(table.data.size))
            }
            val directoryBytes = directory.toByteArray()

            val out = ByteArrayOutputStream()
            out.write("wOF2".toByteArray())
            out.u32be(flavor)
            out.u32be((48 + directoryBytes.size + compressed.size).toLong())
            out.u16be(tables.size)
            out.u16be(0)
            out.u32be(0L)
            out.u32be(compressed.size.toLong())
            out.u16be(1)
            out.u16be(0)
            repeat(5) { out.u32be(0L) }
            out.write(directoryBytes)
            out.write(compressed)
            return out.toByteArray()
        }

        private fun uintBase128(value: Int): ByteArray {
            var v = value
            val groups = ArrayList<Int>()
            do {
                groups += v and 0x7F
                v = v ushr 7
            } while (v != 0)
            groups.reverse()
            val bytes = ByteArray(groups.size)
            for (i in groups.indices) {
                bytes[i] = (groups[i] or (if (i < groups.size - 1) 0x80 else 0)).toByte()
            }
            return bytes
        }

        fun headTable(indexToLocFormat: Int): ByteArray {
            val head = ByteArrayOutputStream()
            head.u32be(0x00010000L)
            head.u32be(0x00010000L)
            head.u32be(0L)
            head.u32be(0x5F0F3CF5L)
            head.u16be(0x0003)
            head.u16be(1000)
            repeat(4) { head.u32be(0L) }
            head.i16be(50); head.i16be(0); head.i16be(1050); head.i16be(900)
            head.u16be(0)
            head.u16be(8)
            head.i16be(2)
            head.i16be(indexToLocFormat)
            head.i16be(0)
            return head.toByteArray()
        }

        fun hheaTable(numHMetrics: Int): ByteArray {
            val hhea = ByteArrayOutputStream()
            hhea.u32be(0x00010000L)
            hhea.i16be(800); hhea.i16be(-200); hhea.i16be(0)
            hhea.u16be(600)
            hhea.i16be(0); hhea.i16be(0); hhea.i16be(1050)
            hhea.i16be(1); hhea.i16be(0); hhea.i16be(0)
            repeat(4) { hhea.i16be(0) }
            hhea.i16be(0)
            hhea.u16be(numHMetrics)
            return hhea.toByteArray()
        }

        fun maxpTable(numGlyphs: Int): ByteArray {
            val maxp = ByteArrayOutputStream()
            maxp.u32be(0x00010000L)
            maxp.u16be(numGlyphs)
            repeat(13) { maxp.u16be(0) }
            return maxp.toByteArray()
        }

        fun transformedGlyf(points: List<Triple<Int, Int, Boolean>>): ByteArray {
            val nContour = ByteArrayOutputStream()
            nContour.i16be(0)
            nContour.i16be(1)

            val nPoints = ByteArrayOutputStream()
            nPoints.write(points.size)

            val flags = ByteArrayOutputStream()
            val glyphs = ByteArrayOutputStream()
            var px = 0
            var py = 0
            for ((x, y, onCurve) in points) {
                val dx = x - px
                val dy = y - py
                px = x
                py = y
                var flag = 124
                if (dx >= 0) flag = flag or 1
                if (dy >= 0) flag = flag or 2
                flags.write(flag or (if (onCurve) 0 else 0x80))
                val ax = if (dx >= 0) dx else -dx
                val ay = if (dy >= 0) dy else -dy
                glyphs.write(ax shr 8); glyphs.write(ax and 0xFF)
                glyphs.write(ay shr 8); glyphs.write(ay and 0xFF)
            }
            glyphs.write(0)

            val bbox = ByteArray(4)
            val out = ByteArrayOutputStream()
            out.u32be(0L)
            out.u16be(2)
            out.u16be(0)
            out.u32be(nContour.size().toLong())
            out.u32be(nPoints.size().toLong())
            out.u32be(flags.size().toLong())
            out.u32be(glyphs.size().toLong())
            out.u32be(0L)
            out.u32be(bbox.size.toLong())
            out.u32be(0L)
            out.write(nContour.toByteArray())
            out.write(nPoints.toByteArray())
            out.write(flags.toByteArray())
            out.write(glyphs.toByteArray())
            out.write(bbox)
            return out.toByteArray()
        }

        fun transformedHmtx(advances: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x03)
            for (advance in advances) out.u16be(advance)
            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.u16be(value: Int) {
            write((value ushr 8) and 0xFF)
            write(value and 0xFF)
        }

        private fun ByteArrayOutputStream.i16be(value: Int) = u16be(value and 0xFFFF)

        private fun ByteArrayOutputStream.u32be(value: Long) {
            u16be(((value ushr 16) and 0xFFFF).toInt())
            u16be((value and 0xFFFF).toInt())
        }
    }

    // -------------------------------------------------------------------------
    // Minimal MOBI6 PDB + PalmDoc Builder (from MobiBuilder)
    // -------------------------------------------------------------------------

    private object MobiFixtures {
        private fun ByteArrayOutputStream.u16(value: Int) {
            write((value ushr 8) and 0xFF)
            write(value and 0xFF)
        }

        private fun ByteArrayOutputStream.u32(value: Long) {
            write(((value ushr 24) and 0xFF).toInt())
            write(((value ushr 16) and 0xFF).toInt())
            write(((value ushr 8) and 0xFF).toInt())
            write((value and 0xFF).toInt())
        }

        private fun ByteArrayOutputStream.u32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

        private fun ByteArray.putU16(off: Int, value: Int) {
            this[off] = (value ushr 8).toByte()
            this[off + 1] = value.toByte()
        }

        private fun ByteArray.putU32(off: Int, value: Long) {
            this[off] = ((value ushr 24) and 0xFF).toByte()
            this[off + 1] = ((value ushr 16) and 0xFF).toByte()
            this[off + 2] = ((value ushr 8) and 0xFF).toByte()
            this[off + 3] = (value and 0xFF).toByte()
        }

        private fun ByteArray.putU32(off: Int, value: Int) = putU32(off, value.toLong() and 0xFFFFFFFFL)

        private fun buildPdb(type: String, name: String, records: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            val nameBytes = name.toByteArray(Charsets.ISO_8859_1).copyOf(32)
            out.write(nameBytes)
            repeat(28) { out.write(0) }
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            repeat(8) { out.write(0) }
            out.u16(records.size)
            var offset = 78 + 8 * records.size
            for ((i, record) in records.withIndex()) {
                out.u32(offset)
                out.write(0)
                out.write((i shr 16) and 0xFF)
                out.write((i shr 8) and 0xFF)
                out.write(i and 0xFF)
                offset += record.size
            }
            for (record in records) out.write(record)
            return out.toByteArray()
        }

        private fun record0(
            compression: Int,
            textLength: Int,
            textRecords: Int,
            encoding: Int = 65001,
            exth: List<Pair<Int, ByteArray>> = emptyList(),
            fullName: String = "Test Book",
        ): ByteArray {
            val headerLength = 0x108 - 16
            val mobiEnd = 16 + headerLength

            val exthBytes = if (exth.isEmpty()) {
                ByteArray(0)
            } else {
                val body = ByteArrayOutputStream()
                for ((type, data) in exth) {
                    body.u32(type)
                    body.u32(data.size + 8)
                    body.write(data)
                }
                val out = ByteArrayOutputStream()
                out.write("EXTH".toByteArray())
                out.u32(12 + body.size())
                out.u32(exth.size)
                out.write(body.toByteArray())
                while (out.size() % 4 != 0) out.write(0)
                out.toByteArray()
            }

            val nameBytes = fullName.toByteArray(Charsets.UTF_8)
            val nameOffset = mobiEnd + exthBytes.size
            val record = ByteArray(nameOffset + nameBytes.size + 2)

            for (i in 16 + 0x28 until mobiEnd step 4) record.putU32(i, 0xFFFFFFFFL)

            record.putU16(0, compression)
            record.putU32(4, textLength)
            record.putU16(8, textRecords)
            record.putU16(10, 4096)
            record.putU16(12, 0)

            System.arraycopy("MOBI".toByteArray(), 0, record, 16, 4)
            record.putU32(20, headerLength)
            record.putU32(24, 2)
            record.putU32(28, encoding)
            record.putU32(32, 0x12345678)
            record.putU32(36, 6)
            record.putU32(16 + 0x44, nameOffset)
            record.putU32(16 + 0x48, nameBytes.size)
            record.putU32(16 + 0x4C, 0)
            record.putU32(16 + 0x5C, -1)
            record.putU32(16 + 0x60, -1)
            record.putU32(16 + 0x64, 0)
            record.putU32(16 + 0x70, if (exth.isEmpty()) 0 else 0x40)
            record.putU32(16 + 0xE0, 0)
            record.putU32(16 + 0xE4, -1)

            System.arraycopy(exthBytes, 0, record, mobiEnd, exthBytes.size)
            System.arraycopy(nameBytes, 0, record, nameOffset, nameBytes.size)
            return record
        }

        private fun palmdocCompress(text: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < text.size) {
                var bestLen = 0
                var bestDist = 0
                val maxDist = minOf(i, 2047)
                var dist = 1
                while (dist <= maxDist) {
                    var l = 0
                    while (l < 10 && i + l < text.size && text[i + l] == text[i - dist + l]) l++
                    if (l > bestLen) {
                        bestLen = l
                        bestDist = dist
                        if (bestLen == 10) break
                    }
                    dist++
                }
                if (bestLen >= 3) {
                    val pair = 0x8000 or (bestDist shl 3) or (bestLen - 3)
                    out.write((pair ushr 8) and 0xFF)
                    out.write(pair and 0xFF)
                    i += bestLen
                    continue
                }
                val b = text[i].toInt() and 0xFF
                if (b == ' '.code && i + 1 < text.size) {
                    val next = text[i + 1].toInt() and 0xFF
                    if (next in 0x40..0x7F) {
                        out.write(next xor 0x80)
                        i += 2
                        continue
                    }
                }
                if (b == 0x00 || b in 0x09..0x7F) {
                    out.write(b)
                    i++
                    continue
                }
                val runStart = i
                var runLength = 0
                while (i < text.size && runLength < 8) {
                    val c = text[i].toInt() and 0xFF
                    if (c in 0x09..0x7F || c == 0x00) break
                    runLength++
                    i++
                }
                out.write(runLength)
                out.write(text, runStart, runLength)
            }
            return out.toByteArray()
        }

        fun buildMobi6(
            target: File,
            html: String,
            compress: Boolean = true,
            exth: List<Pair<Int, ByteArray>> = emptyList(),
            fullName: String = "Engine Mobi",
        ): File {
            val text = html.toByteArray(Charsets.UTF_8)
            val textRecords = ArrayList<ByteArray>((text.size + 4095) / 4096)
            var off = 0
            while (off < text.size) {
                val end = minOf(off + 4096, text.size)
                val chunk = text.copyOfRange(off, end)
                textRecords += if (compress) palmdocCompress(chunk) else chunk
                off = end
            }
            val rec0 = record0(
                compression = if (compress) 2 else 1,
                textLength = text.size,
                textRecords = textRecords.size,
                encoding = 65001,
                exth = exth,
                fullName = fullName,
            )
            target.writeBytes(buildPdb(PdbFile.TYPE_MOBI, "EngineMobi", listOf(rec0) + textRecords))
            return target
        }
    }

    private companion object {
        const val PERF_TAG = "FrogReaderPerf"
        const val LOG_RELATIVE_PATH = "engine-benchmark/m3-m4-m5-latest.log"
        val logLock = Any()
        val logInitialized = AtomicBoolean(false)
    }
}
