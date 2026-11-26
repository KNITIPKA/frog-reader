@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.engine

import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.contentResourcePaths
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.RustEpubPackage
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-only FRB2 index/chapter measurements on Android. The original full
 * parser model leaves scope before five indexed reopens. These measurements do
 * not exercise ReaderScreen, text measurement, page cuts or displayed frames.
 */
@RunWith(AndroidJUnit4::class)
class IndexedCacheDeviceTest {
    private val json = Json { encodeDefaults = false; classDiscriminator = "kind" }
    private val records = mutableListOf<String>()

    @Test fun fullIndexAndDistantChaptersPreserveSourceModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        val root = File.createTempFile("indexed-benchmark-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:IndexedCacheBenchmark")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            val (source, isGenerated) = resolveSource(context, root)
            record("INDEXED_DEVICE corpus=${if (isGenerated) "generated" else "local"} " +
                "source_bytes=${source.length()} native_bridge=true format=2 payload_codec=binary repetitions=5 heap_sampling_ms=2 " +
                "ui_frames=false real_reader_screen=false page_cuts=false full_model_restore=false")
            val cache = ChunkedContentCache(File(root, "chunked-content"))
            // Only descriptors and digests escape this function. No Chapter,
            // BookContent or ReaderItems from the parser are retained here.
            val expected = parseSaveAndFingerprint(source, root, cache)
            repeat(5) { iteration ->
                record("INDEXED_DEVICE phase=gc_request iteration=$iteration outside_timing=true " +
                    "reason=release_prior_model not_guaranteed=true")
                Runtime.getRuntime().gc()
                System.runFinalization()
                verifyIndexedOpen(source, cache, expected, iteration)
            }
            if (InstrumentationRegistry.getArguments().getString("engineProfileFrbPhases") == "true") {
                profileChapterReadPhases(cache.entryFile(BOOK_ID), expected)
            }
            assertTrue("Rust engine must remain available after real indexed IO", RustBridge.shared.isAvailable)
            record("INDEXED_DEVICE result=passed full_index_digest=true chapter_metadata=true " +
                "full_navigation_digest=true target_chapter_digests=true globals_digest=true " +
                "real_reader_screen=false exact_page_total=false")
        } finally {
            try {
                File(context.filesDir, "engine-benchmark/m2-indexed-latest.log").apply {
                    parentFile!!.mkdirs()
                    writeText(records.joinToString("\n", postfix = "\n"))
                }
            } finally {
                root.deleteRecursively()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    @Test fun directRustEpubFrbColdOpenBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assumeTrue(
            "Opt in with -e engineProfileDirectEpubFrb true",
            InstrumentationRegistry.getArguments().getString("engineProfileDirectEpubFrb") == "true",
        )
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        records.clear()
        val root = File.createTempFile("direct-frb-benchmark-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:DirectEpubFrbBenchmark")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            val (source, isGenerated) = resolveSource(context, root)
            record("INDEXED_DEVICE corpus=${if (isGenerated) "generated" else "local"} " +
                "source_bytes=${source.length()} native_bridge=true format=2 payload_codec=binary " +
                "direct_rust_epub_frb=true heap_sampling_ms=2 ui_frames=false real_reader_screen=false " +
                "page_cuts=false full_model_restore=false")
            val cache = ChunkedContentCache(File(root, "chunked-content"))
            val directResourcesDir = File(root, "direct-resources")
            val saved = measure {
                cache.saveEpubDirectlyWithReason(BOOK_ID, source, directResourcesDir)
            }
            val entryFile = cache.entryFile(BOOK_ID)
            val savedOk = saved.value is RustEpubPackage.NativeWriteResult.Written
            val fallbackReason = (saved.value as? RustEpubPackage.NativeWriteResult.Fallback)?.reason ?: "none"
            log(
                "direct_save",
                null,
                "all",
                saved,
                "saved=$savedOk fallback_reason=$fallbackReason entry_bytes=${entryFile.length()}",
            )
            assertTrue(
                "Direct Rust EPUB FRB save must succeed for this corpus (fallback_reason=$fallbackReason)",
                savedOk,
            )
            assertTrue("Direct Rust EPUB FRB entry must exist", entryFile.isFile)

            record("INDEXED_DEVICE phase=gc_request iteration=0 outside_timing=true " +
                "reason=release_direct_save_scratch not_guaranteed=true")
            Runtime.getRuntime().gc()
            System.runFinalization()

            val opened = measure {
                requireNotNull(cache.openIndexed(BOOK_ID, source)) { "Direct EPUB FRB cache miss" }
            }
            opened.value.use { session ->
                val index = session.index
                assertTrue("Benchmark requires sequential content", index.totalElements > 0 && index.chapters.isNotEmpty())
                log("direct_index_open", 0, "all", opened,
                    "cache_hit=true includes_source_hash=true includes_manifest_decode=true " +
                        "chapters=${index.chapters.size} elements=${index.totalElements} " +
                        "navigation_entries=${index.navigation.size} full_model_restore=false")
                val indexDigest = digest(BookIndex.serializer(), index)
                val navigationDigest = digest(ListSerializer(BookNavigationEntry.serializer()), index.navigation)
                val at85 = ((index.totalElements.toLong() * 85) / 100).toInt()
                    .coerceIn(0, index.totalElements - 1)
                val chapter85 = index.chapters.indexOfLast { it.elementCount > 0 && it.startElement <= at85 }
                check(chapter85 >= 0)
                val targetSpecs = listOf("0" to 0, "85" to chapter85, "last" to index.chapters.lastIndex)
                for ((label, chapterIndex) in targetSpecs) {
                    val loaded = measure {
                        requireNotNull(session.readChapter(chapterIndex)) { "Missing chapter $chapterIndex" }
                    }
                    val chapterDigest = digest(Chapter.serializer(), loaded.value)
                    log("direct_target_chapter", 0, label, loaded,
                        "chapter=$chapterIndex decoded_elements=${loaded.value.elements.size} " +
                            "source_start=${index.chapters[chapterIndex].startElement} " +
                            "preceding_chapters_decoded=0 chapter_digest=$chapterDigest")
                }
                val globals = measure { requireNotNull(session.readGlobals()) { "Missing global tables" } }
                log("direct_heavy_globals", 0, "all", globals, "separate_from_index_open=true")
                val globalsDigest = digest(BookContent.serializer(), globals.value)
                record("INDEXED_DEVICE phase=reference_check outside_timing=true " +
                    "index_digest=$indexDigest navigation_digest=$navigationDigest " +
                    "globals_digest=$globalsDigest targets=${targetSpecs.joinToString(",") { "${it.first}:${it.second}" }}")
            }
            assertTrue("Rust engine must remain available after direct EPUB FRB IO", RustBridge.shared.isAvailable)
            record("INDEXED_DEVICE result=passed direct_rust_epub_frb=true full_index_digest=true " +
                "chapter_metadata=true full_navigation_digest=true target_chapter_digests=true " +
                "globals_digest=true real_reader_screen=false exact_page_total=false")
        } finally {
            try {
                File(context.filesDir, "engine-benchmark/m62b-direct-latest.log").apply {
                    parentFile!!.mkdirs()
                    writeText(records.joinToString("\n", postfix = "\n"))
                }
            } finally {
                root.deleteRecursively()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    @Test fun compareDirectRustVsKotlinEpubParity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assumeTrue(
            "Opt in with -e engineProfileDirectEpubParity true",
            InstrumentationRegistry.getArguments().getString("engineProfileDirectEpubParity") == "true",
        )
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        records.clear()
        val root = File.createTempFile("direct-parity-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:DirectEpubParity")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            val (source, isGenerated) = resolveSource(context, root)
            record("PARITY_DIFF phase=start corpus=${if (isGenerated) "generated" else "local"} source_bytes=${source.length()}")

            // Use the exact same directory path for both runs so extracted resource
            // paths are identical, while wiping between runs so neither reads the other's files.
            val resourcesDir = File(root, "resources")
            val kotlinContent = EpubParser.parseContent(
                file = source,
                imagesDir = resourcesDir,
                limits = ReaderResourceLimits.DEFAULT,
                maxParallelism = 1,
            )
            var start = 0
            val kotlinChapterIndex = kotlinContent.chapters.map { chapter ->
                BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also {
                    start += chapter.elements.size
                }
            }
            val expectedIndex = BookIndex(
                chapters = kotlinChapterIndex,
                navigation = kotlinContent.navigation,
                linkTargets = kotlinContent.linkTargets,
                linkedDocumentTargets = kotlinContent.linkedDocumentTargets,
                fonts = kotlinContent.fonts,
                language = kotlinContent.language,
                pageProgression = kotlinContent.pageProgression,
                totalElements = kotlinContent.totalElements,
                resourcePaths = contentResourcePaths(kotlinContent).sorted(),
                publisherStyle = publisherStyleOf(kotlinContent),
                epubSourceRanges = kotlinContent.epubSourceRanges,
            )
            val expectedGlobals = BookContent(
                chapters = emptyList(),
                notes = kotlinContent.notes,
                linkTargets = kotlinContent.linkTargets,
                fonts = kotlinContent.fonts,
                language = kotlinContent.language,
                linkedDocuments = kotlinContent.linkedDocuments,
                linkedDocumentTargets = kotlinContent.linkedDocumentTargets,
                navigation = kotlinContent.navigation,
                publisherPublication = kotlinContent.publisherPublication,
                pageProgression = kotlinContent.pageProgression,
            )
            val expectedIndexDigest = digest(BookIndex.serializer(), expectedIndex.canonicalized())
            val expectedNavigationDigest = digest(ListSerializer(BookNavigationEntry.serializer()), expectedIndex.navigation)
            val expectedGlobalsDigest = digest(BookContent.serializer(), expectedGlobals.canonicalized())
            val kotlinChapterDigests = kotlinContent.chapters.map { digest(Chapter.serializer(), it) }

            resourcesDir.deleteRecursively()

            val cache = ChunkedContentCache(File(root, "chunked-content"))
            val directResult = cache.saveEpubDirectlyWithReason(BOOK_ID, source, resourcesDir)
            val fallbackReason = (directResult as? RustEpubPackage.NativeWriteResult.Fallback)?.reason ?: "none"
            assertTrue(
                "Direct Rust EPUB FRB save must succeed (fallback_reason=$fallbackReason)",
                directResult is RustEpubPackage.NativeWriteResult.Written,
            )

            var rustIndex: BookIndex? = null
            var rustGlobals: BookContent? = null
            var rustIndexDigest = ""
            var rustNavigationDigest = ""
            var rustGlobalsDigest = ""
            var differingChapters = 0

            val opened = requireNotNull(cache.openIndexed(BOOK_ID, source)) { "Direct EPUB FRB cache miss" }
            opened.valueOrSelf().use { session ->
                val sessionIndex = session.index
                rustIndex = sessionIndex
                rustIndexDigest = digest(BookIndex.serializer(), sessionIndex.canonicalized())
                rustNavigationDigest = digest(ListSerializer(BookNavigationEntry.serializer()), sessionIndex.navigation)
                record("PARITY_DIFF section=index match=${expectedIndexDigest == rustIndexDigest} " +
                    "kotlin_digest=$expectedIndexDigest rust_digest=$rustIndexDigest")
                logBookIndexDiff(expectedIndex, sessionIndex, expectedNavigationDigest, rustNavigationDigest)

                val sessionGlobals = requireNotNull(session.readGlobals()) { "Missing global tables" }
                rustGlobals = sessionGlobals
                rustGlobalsDigest = digest(BookContent.serializer(), sessionGlobals.canonicalized())
                record("PARITY_DIFF section=globals match=${expectedGlobalsDigest == rustGlobalsDigest} " +
                    "kotlin_digest=$expectedGlobalsDigest rust_digest=$rustGlobalsDigest")
                logGlobalsDiff(expectedGlobals, sessionGlobals)

                val sharedChapterCount = minOf(kotlinContent.chapters.size, sessionIndex.chapters.size)
                var loggedChapterDiffs = 0
                for (chapterIndex in 0 until sharedChapterCount) {
                    val rustChapter = requireNotNull(session.readChapter(chapterIndex)) { "Missing chapter $chapterIndex" }
                    val rustChapterDigest = digest(Chapter.serializer(), rustChapter)
                    if (rustChapterDigest != kotlinChapterDigests[chapterIndex]) {
                        differingChapters++
                        if (loggedChapterDiffs < 3) {
                            loggedChapterDiffs++
                            logChapterDiff(chapterIndex, kotlinContent.chapters[chapterIndex], rustChapter)
                        }
                    }
                }
                if (kotlinContent.chapters.size != sessionIndex.chapters.size) {
                    differingChapters += kotlin.math.abs(kotlinContent.chapters.size - sessionIndex.chapters.size)
                }
                record("PARITY_DIFF section=chapters shared_count=$sharedChapterCount " +
                    "kotlin_count=${kotlinContent.chapters.size} rust_count=${sessionIndex.chapters.size} " +
                    "differing_chapters=$differingChapters")
            }

            assertBookIndexEquals(expectedIndex.canonicalized(), rustIndex?.canonicalized())
            assertEquals("BookIndex digest mismatch", expectedIndexDigest, rustIndexDigest)
            assertEquals("Navigation digest mismatch", expectedNavigationDigest, rustNavigationDigest)
            assertEquals("Globals digest mismatch", expectedGlobalsDigest, rustGlobalsDigest)
            assertEquals("Chapter parity mismatch (differingChapters=$differingChapters)", 0, differingChapters)
        } finally {
            try {
                File(context.filesDir, "engine-benchmark/m62b-parity-diff.log").apply {
                    parentFile!!.mkdirs()
                    writeText(records.joinToString("\n", postfix = "\n"))
                }
            } finally {
                root.deleteRecursively()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun <T : java.io.Closeable> T.valueOrSelf(): T = this

    private fun BookIndex.canonicalized(): BookIndex = copy(
        linkTargets = linkTargets.toSortedMap(),
        linkedDocumentTargets = linkedDocumentTargets.toSortedMap(),
    )

    /**
     * Compares every field of two canonicalized [BookIndex] instances.
     *
     * [BookNavigationEntry], [PublisherStyle], and [EmbeddedFont] are plain classes without
     * structural `equals`, so `BookIndex.equals` fails by reference identity even when their
     * contents and canonicalized digests match bit-for-bit.
     */
    private fun assertBookIndexEquals(expected: BookIndex, actual: BookIndex?) {
        val actualIndex = requireNotNull(actual) { "Missing Rust BookIndex" }
        assertEquals("BookIndex.chapters mismatch", expected.chapters, actualIndex.chapters)
        assertEquals(
            "BookIndex.navigation size mismatch",
            expected.navigation.size,
            actualIndex.navigation.size,
        )
        for (i in expected.navigation.indices) {
            val k = expected.navigation[i]
            val r = actualIndex.navigation[i]
            assertEquals("BookIndex.navigation[$i].title mismatch", k.title, r.title)
            assertEquals("BookIndex.navigation[$i].depth mismatch", k.depth, r.depth)
            assertEquals("BookIndex.navigation[$i].target mismatch", k.target, r.target)
        }
        assertEquals("BookIndex.linkTargets mismatch", expected.linkTargets, actualIndex.linkTargets)
        assertEquals(
            "BookIndex.linkedDocumentTargets mismatch",
            expected.linkedDocumentTargets,
            actualIndex.linkedDocumentTargets,
        )
        assertEquals("BookIndex.fonts mismatch", expected.fonts, actualIndex.fonts)
        assertEquals("BookIndex.language mismatch", expected.language, actualIndex.language)
        assertEquals(
            "BookIndex.pageProgression mismatch",
            expected.pageProgression,
            actualIndex.pageProgression,
        )
        assertEquals("BookIndex.totalElements mismatch", expected.totalElements, actualIndex.totalElements)
        assertEquals("BookIndex.resourcePaths mismatch", expected.resourcePaths, actualIndex.resourcePaths)
        assertPublisherStyleEquals(expected.publisherStyle, actualIndex.publisherStyle)
        assertEquals(
            "BookIndex.epubSourceRanges mismatch",
            expected.epubSourceRanges,
            actualIndex.epubSourceRanges,
        )
    }

    private fun assertPublisherStyleEquals(expected: PublisherStyle?, actual: PublisherStyle?) {
        if (expected == null || actual == null) {
            assertEquals("BookIndex.publisherStyle nullability mismatch", expected == null, actual == null)
            return
        }
        assertEquals("PublisherStyle.justify mismatch", expected.justify, actual.justify)
        assertEquals("PublisherStyle.lineHeight mismatch", expected.lineHeight, actual.lineHeight)
        assertEquals("PublisherStyle.hyphenation mismatch", expected.hyphenation, actual.hyphenation)
        assertEquals("PublisherStyle.dropCaps mismatch", expected.dropCaps, actual.dropCaps)
        assertEquals("PublisherStyle.fontName mismatch", expected.fontName, actual.fontName)
        assertEquals("PublisherStyle.fontPath mismatch", expected.fontPath, actual.fontPath)
        assertEquals("PublisherStyle.fontCss mismatch", expected.fontCss, actual.fontCss)
        assertEquals(
            "PublisherStyle.embeddedFonts mismatch",
            expected.embeddedFonts.map { it.name to it.path },
            actual.embeddedFonts.map { it.name to it.path },
        )
        assertEquals("PublisherStyle.fontFaces mismatch", expected.fontFaces, actual.fontFaces)
        assertEquals("PublisherStyle.fontTypography mismatch", expected.fontTypography, actual.fontTypography)
    }

    private fun BookContent.canonicalized(): BookContent = BookContent(
        chapters = chapters,
        notes = notes.toSortedMap(),
        linkTargets = linkTargets.toSortedMap(),
        fonts = fonts,
        language = language,
        linkedDocuments = linkedDocuments.toSortedMap(),
        linkedDocumentTargets = linkedDocumentTargets.toSortedMap(),
        navigation = navigation,
        publisherPublication = publisherPublication,
        pageProgression = pageProgression,
        epubSourceRanges = epubSourceRanges,
    )

    private fun logBookIndexDiff(
        k: BookIndex,
        r: BookIndex,
        kNavDigest: String,
        rNavDigest: String,
    ) {
        val firstChapterMismatch = (0 until minOf(k.chapters.size, r.chapters.size))
            .firstOrNull { k.chapters[it] != r.chapters[it] }
        val chapterDetail = firstChapterMismatch?.let { idx ->
            val kc = k.chapters[idx]
            val rc = r.chapters[idx]
            "firstMismatchIdx=$idx k=(depth=${kc.depth},elems=${kc.elementCount},start=${kc.startElement},titleLen=${kc.title?.length}) " +
                "r=(depth=${rc.depth},elems=${rc.elementCount},start=${rc.startElement},titleLen=${rc.title?.length}) " +
                "titleMatch=${kc.title == rc.title}"
        } ?: "none"
        record("PARITY_DIFF index_field=chapters kotlin=${k.chapters.size} rust=${r.chapters.size} $chapterDetail")

        val navMatch = kNavDigest == rNavDigest
        val firstNavMismatch = if (navMatch) null else {
            (0 until minOf(k.navigation.size, r.navigation.size)).firstOrNull { i ->
                digest(BookNavigationEntry.serializer(), k.navigation[i]) !=
                    digest(BookNavigationEntry.serializer(), r.navigation[i])
            }
        }
        val navDetail = firstNavMismatch?.let { idx ->
            val kn = k.navigation[idx]
            val rn = r.navigation[idx]
            "firstMismatchIdx=$idx titleMatch=${kn.title == rn.title} kDepth=${kn.depth} rDepth=${rn.depth} " +
                "kTarget=${kn.target} rTarget=${rn.target}"
        } ?: if (navMatch) "match" else "size_only"
        record("PARITY_DIFF index_field=navigation match=$navMatch kotlin=${k.navigation.size} rust=${r.navigation.size} $navDetail")

        logMapDiff("linkTargets", k.linkTargets, r.linkTargets)
        logMapDiff("linkedDocumentTargets", k.linkedDocumentTargets, r.linkedDocumentTargets)

        val firstFontDiff = (0 until minOf(k.fonts.size, r.fonts.size)).firstOrNull { k.fonts[it] != r.fonts[it] }
        val fontDetail = firstFontDiff?.let { idx ->
            val kf = k.fonts[idx]
            val rf = r.fonts[idx]
            "firstMismatchIdx=$idx k=(family=${kf.family},base=${File(kf.path).name},bold=${kf.bold},italic=${kf.italic},w=${kf.weight},range=${kf.weightMin}..${kf.weightMax},stretch=${kf.stretchMin}..${kf.stretchMax},uni=${kf.unicodeRange}) " +
                "r=(family=${rf.family},base=${File(rf.path).name},bold=${rf.bold},italic=${rf.italic},w=${rf.weight},range=${rf.weightMin}..${rf.weightMax},stretch=${rf.stretchMin}..${rf.stretchMax},uni=${rf.unicodeRange})"
        } ?: "none"
        record("PARITY_DIFF index_field=fonts kotlin=${k.fonts.size} rust=${r.fonts.size} $fontDetail")

        record("PARITY_DIFF index_field=language kotlin=${k.language} rust=${r.language} match=${k.language == r.language}")
        record("PARITY_DIFF index_field=pageProgression kotlin=${k.pageProgression} rust=${r.pageProgression} match=${k.pageProgression == r.pageProgression}")
        record("PARITY_DIFF index_field=totalElements kotlin=${k.totalElements} rust=${r.totalElements} match=${k.totalElements == r.totalElements}")

        val kResSet = k.resourcePaths.map { File(it).name }.toSet()
        val rResSet = r.resourcePaths.map { File(it).name }.toSet()
        val onlyKRes = (kResSet - rResSet).sorted()
        val onlyRRes = (rResSet - kResSet).sorted()
        record("PARITY_DIFF index_field=resourcePaths kotlin=${k.resourcePaths.size} rust=${r.resourcePaths.size} " +
            "onlyInKotlinCount=${onlyKRes.size} onlyInRustCount=${onlyRRes.size} " +
            "onlyInKotlinSample=${onlyKRes.take(5)} onlyInRustSample=${onlyRRes.take(5)}")

        val kPubDigest = k.publisherStyle?.let { digest(PublisherStyle.serializer(), it) }
        val rPubDigest = r.publisherStyle?.let { digest(PublisherStyle.serializer(), it) }
        val pubDiff = describePublisherStyleDiff(k.publisherStyle, r.publisherStyle)
        record("PARITY_DIFF index_field=publisherStyle match=${kPubDigest == rPubDigest} $pubDiff")

        val firstRangeDiff = (0 until minOf(k.epubSourceRanges.size, r.epubSourceRanges.size))
            .firstOrNull { k.epubSourceRanges[it] != r.epubSourceRanges[it] }
        val rangeDetail = firstRangeDiff?.let { idx ->
            "firstMismatchIdx=$idx k=${k.epubSourceRanges[idx]} r=${r.epubSourceRanges[idx]}"
        } ?: "none"
        record("PARITY_DIFF index_field=epubSourceRanges kotlin=${k.epubSourceRanges.size} rust=${r.epubSourceRanges.size} $rangeDetail")
    }

    private fun <V> logMapDiff(name: String, k: Map<String, V>, r: Map<String, V>) {
        val onlyK = (k.keys - r.keys).sorted()
        val onlyR = (r.keys - k.keys).sorted()
        val firstMismatchKey = k.keys.intersect(r.keys).sorted().firstOrNull { k[it] != r[it] }
        val valDetail = firstMismatchKey?.let { key -> "firstValMismatchKey=$key k=${k[key]} r=${r[key]}" } ?: "none"
        record("PARITY_DIFF index_field=$name kotlin=${k.size} rust=${r.size} " +
            "onlyInKotlinCount=${onlyK.size} onlyInRustCount=${onlyR.size} " +
            "onlyInKotlinSample=${onlyK.take(5)} onlyInRustSample=${onlyR.take(5)} $valDetail")
    }

    private fun describePublisherStyleDiff(k: PublisherStyle?, r: PublisherStyle?): String {
        if (k == null && r == null) return "bothNull=true"
        if (k == null || r == null) return "kNull=${k == null} rNull=${r == null}"
        val diffs = mutableListOf<String>()
        if (k.justify != r.justify) diffs += "justify(${k.justify}/${r.justify})"
        if (k.lineHeight != r.lineHeight) diffs += "lineHeight(${k.lineHeight}/${r.lineHeight})"
        if (k.hyphenation != r.hyphenation) diffs += "hyphenation(${k.hyphenation}/${r.hyphenation})"
        if (k.dropCaps != r.dropCaps) diffs += "dropCaps(${k.dropCaps}/${r.dropCaps})"
        if (k.fontName != r.fontName) diffs += "fontName(${k.fontName}/${r.fontName})"
        if (k.fontPath?.let { File(it).name } != r.fontPath?.let { File(it).name }) {
            diffs += "fontPath(${k.fontPath?.let { File(it).name }}/${r.fontPath?.let { File(it).name }})"
        }
        if (k.fontCss != r.fontCss) diffs += "fontCss(${k.fontCss}/${r.fontCss})"
        if (k.embeddedFonts.size != r.embeddedFonts.size) diffs += "embeddedFontsSize(${k.embeddedFonts.size}/${r.embeddedFonts.size})"
        if (k.fontFaces != r.fontFaces) diffs += "fontFaces(${k.fontFaces.size}/${r.fontFaces.size})"
        if (k.fontTypography != r.fontTypography) diffs += "fontTypography(${k.fontTypography}/${r.fontTypography})"
        return if (diffs.isEmpty()) "fieldsMatch=true" else "diffs=${diffs.joinToString(",")}"
    }

    private fun logGlobalsDiff(k: BookContent, r: BookContent) {
        val kNoteKeys = k.notes.keys
        val rNoteKeys = r.notes.keys
        val onlyKNotes = (kNoteKeys - rNoteKeys).sorted()
        val onlyRNotes = (rNoteKeys - kNoteKeys).sorted()
        val sharedNoteKeys = kNoteKeys.intersect(rNoteKeys).sorted()
        var diffNotes = 0
        var firstDiffNoteDetail = "none"
        for (key in sharedNoteKeys) {
            val kn = k.notes.getValue(key)
            val rn = r.notes.getValue(key)
            if (digest(NoteDocument.serializer(), kn) != digest(NoteDocument.serializer(), rn)) {
                diffNotes++
                if (firstDiffNoteDetail == "none") {
                    val elDiff = findFirstDifferingElement(kn.elements, rn.elements)
                    firstDiffNoteDetail = "key=$key kElems=${kn.elements.size} rElems=${rn.elements.size} " +
                        "kBoxes=${kn.publisherBoxes.size} rBoxes=${rn.publisherBoxes.size} $elDiff"
                }
            }
        }
        record("PARITY_DIFF globals_field=notes kotlin=${k.notes.size} rust=${r.notes.size} " +
            "onlyInKotlinCount=${onlyKNotes.size} onlyInRustCount=${onlyRNotes.size} " +
            "onlyInKotlinSample=${onlyKNotes.take(5)} onlyInRustSample=${onlyRNotes.take(5)} " +
            "differingNotes=$diffNotes firstDiff=($firstDiffNoteDetail)")

        val kDocKeys = k.linkedDocuments.keys
        val rDocKeys = r.linkedDocuments.keys
        val onlyKDocs = (kDocKeys - rDocKeys).sorted()
        val onlyRDocs = (rDocKeys - kDocKeys).sorted()
        val sharedDocKeys = kDocKeys.intersect(rDocKeys).sorted()
        var diffDocs = 0
        var firstDiffDocDetail = "none"
        for (key in sharedDocKeys) {
            val kd = k.linkedDocuments.getValue(key)
            val rd = r.linkedDocuments.getValue(key)
            if (digest(LinkedDocument.serializer(), kd) != digest(LinkedDocument.serializer(), rd)) {
                diffDocs++
                if (firstDiffDocDetail == "none") {
                    val elDiff = findFirstDifferingElement(kd.elements, rd.elements)
                    firstDiffDocDetail = "key=$key kElems=${kd.elements.size} rElems=${rd.elements.size} " +
                        "kBoxes=${kd.publisherBoxes.size} rBoxes=${rd.publisherBoxes.size} $elDiff"
                }
            }
        }
        record("PARITY_DIFF globals_field=linkedDocuments kotlin=${k.linkedDocuments.size} rust=${r.linkedDocuments.size} " +
            "onlyInKotlinCount=${onlyKDocs.size} onlyInRustCount=${onlyRDocs.size} " +
            "onlyInKotlinSample=${onlyKDocs.take(5)} onlyInRustSample=${onlyRDocs.take(5)} " +
            "differingDocs=$diffDocs firstDiff=($firstDiffDocDetail)")
    }

    private fun logChapterDiff(chapterIndex: Int, k: Chapter, r: Chapter) {
        val elDiff = findFirstDifferingElement(k.elements, r.elements)
        val boxesMatch = k.publisherBoxes == r.publisherBoxes
        record("PARITY_DIFF chapter=$chapterIndex kElems=${k.elements.size} rElems=${r.elements.size} " +
            "kBoxes=${k.publisherBoxes.size} rBoxes=${r.publisherBoxes.size} boxesMatch=$boxesMatch " +
            "titleMatch=${k.title == r.title} depthMatch=${k.depth == r.depth} $elDiff")
    }

    private fun findFirstDifferingElement(kList: List<ContentElement>, rList: List<ContentElement>): String {
        val serializer = ContentElement.serializer()
        val shared = minOf(kList.size, rList.size)
        for (i in 0 until shared) {
            if (digest(serializer, kList[i]) != digest(serializer, rList[i])) {
                return "firstElemDiff=(elementIndex=$i ${describeElementDiff(kList[i], rList[i])})"
            }
        }
        return if (kList.size != rList.size) {
            "firstElemDiff=(sizeMismatch k=${kList.size} r=${rList.size})"
        } else {
            "firstElemDiff=none"
        }
    }

    private fun describeElementDiff(k: ContentElement, r: ContentElement): String {
        val kType = k::class.simpleName
        val rType = r::class.simpleName
        if (kType != rType) return "kType=$kType rType=$rType"
        return when {
            k is ContentElement.Paragraph && r is ContentElement.Paragraph -> {
                val blockDiff = compareBlockStyles(k.block, r.block)
                "type=Paragraph styleMatch=${k.style == r.style}(${k.style}/${r.style}) " +
                    "textLen=${k.text.length}/${r.text.length} textEqual=${k.text.text == r.text.text} " +
                    "spans=${k.text.spanStyles.size}/${r.text.spanStyles.size} " +
                    "annotations=${annotationTagCounts(k.text)}/${annotationTagCounts(r.text)} " +
                    "blockDiffs=$blockDiff"
            }
            k is ContentElement.Heading && r is ContentElement.Heading -> {
                val blockDiff = compareBlockStyles(k.block, r.block)
                "type=Heading level=${k.level}/${r.level} " +
                    "textLen=${k.styledText.length}/${r.styledText.length} textEqual=${k.text == r.text} " +
                    "spans=${k.styledText.spanStyles.size}/${r.styledText.spanStyles.size} " +
                    "annotations=${annotationTagCounts(k.styledText)}/${annotationTagCounts(r.styledText)} " +
                    "blockDiffs=$blockDiff"
            }
            k is ContentElement.Table && r is ContentElement.Table -> {
                val blockDiff = compareBlockStyles(k.block, r.block)
                "type=Table rows=${k.rows.size}/${r.rows.size} " +
                    "flatLen=${k.flatText().length}/${r.flatText().length} " +
                    "rowLayout=${k.publisherRowLayout}/${r.publisherRowLayout} " +
                    "centerV=${k.centerCellsVertically}/${r.centerCellsVertically} " +
                    "stackBelow=${k.stackCellsBelowCssPx}/${r.stackCellsBelowCssPx} " +
                    "boxMatch=${k.publisherBox == r.publisherBox} blockDiffs=$blockDiff"
            }
            k is ContentElement.Image && r is ContentElement.Image -> {
                "type=Image basePath=${File(k.path).name}/${File(r.path).name} " +
                    "wFrac=${k.widthFrac}/${r.widthFrac} wEm=${k.widthEm}/${r.widthEm} hEm=${k.heightEm}/${r.heightEm} " +
                    "altMatch=${k.altText == r.altText}"
            }
            else -> "type=$kType"
        }
    }

    private fun annotationTagCounts(s: AnnotatedString): Map<String, Int> =
        s.getStringAnnotations(0, s.length).groupingBy { it.tag }.eachCount()

    private fun compareBlockStyles(k: BlockStyle?, r: BlockStyle?): List<String> {
        if (k == r) return emptyList()
        if (k == null || r == null) return listOf("nullity(${k == null}/${r == null})")
        val diffs = mutableListOf<String>()
        if (k.align != r.align) diffs += "align"
        if (k.italic != r.italic) diffs += "italic"
        if (k.bold != r.bold) diffs += "bold"
        if (k.fontScale != r.fontScale) diffs += "fontScale"
        if (k.indentStartFrac != r.indentStartFrac) diffs += "indentStartFrac"
        if (k.indentStartEm != r.indentStartEm) diffs += "indentStartEm"
        if (k.indentEndFrac != r.indentEndFrac) diffs += "indentEndFrac"
        if (k.indentEndEm != r.indentEndEm) diffs += "indentEndEm"
        if (k.indentLeftFrac != r.indentLeftFrac) diffs += "indentLeftFrac"
        if (k.indentLeftEm != r.indentLeftEm) diffs += "indentLeftEm"
        if (k.indentRightFrac != r.indentRightFrac) diffs += "indentRightFrac"
        if (k.indentRightEm != r.indentRightEm) diffs += "indentRightEm"
        if (k.firstLineIndent != r.firstLineIndent) diffs += "firstLineIndent"
        if (k.firstLineIndentEm != r.firstLineIndentEm) diffs += "firstLineIndentEm"
        if (k.isCssTextLine != r.isCssTextLine) diffs += "isCssTextLine"
        if (k.spaceBeforeEm != r.spaceBeforeEm) diffs += "spaceBeforeEm"
        if (k.spaceAfterEm != r.spaceAfterEm) diffs += "spaceAfterEm"
        if (k.spaceBeforeSpecified != r.spaceBeforeSpecified) diffs += "spaceBeforeSpecified"
        if (k.spaceAfterSpecified != r.spaceAfterSpecified) diffs += "spaceAfterSpecified"
        if (k.fontFamily != r.fontFamily) diffs += "fontFamily"
        if (k.lineHeightMult != r.lineHeightMult) diffs += "lineHeightMult"
        if (k.hyphens != r.hyphens) diffs += "hyphens"
        if (k.pageBreakBefore != r.pageBreakBefore) diffs += "pageBreakBefore"
        if (k.firstLetter != r.firstLetter) diffs += "firstLetter"
        if (k.floatImage != r.floatImage) diffs += "floatImage"
        if (k.language != r.language) diffs += "language"
        if (k.direction != r.direction) diffs += "direction"
        if (k.foregroundColorArgb != r.foregroundColorArgb) diffs += "foregroundColorArgb"
        if (k.backgroundColorArgb != r.backgroundColorArgb) diffs += "backgroundColorArgb"
        if (k.fontFeatureSettings != r.fontFeatureSettings) diffs += "fontFeatureSettings"
        if (k.fontWeight != r.fontWeight) diffs += "fontWeight"
        if (k.fontTypography != r.fontTypography) diffs += "fontTypography"
        return diffs
    }

    private fun resolveSource(context: android.content.Context, root: File): Pair<File, Boolean> {
        val provided = InstrumentationRegistry.getArguments().getString("engineProfileEpub")
        return if (provided.isNullOrBlank()) {
            generatedEpub(File(root, "generated.epub")) to true
        } else {
            File(context.filesDir, provided).also {
                require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                    "engineProfileEpub must stay inside the isolated app's files directory"
                }
                require(it.isFile) { "Missing benchmark EPUB: $provided" }
            } to false
        }
    }

    private data class Target(val label: String, val chapter: Int, val digest: String)
    private data class Expected(
        val index: BookIndex,
        val indexDigest: String,
        val navigationDigest: String,
        val globalsDigest: String,
        val targets: List<Target>,
    )

    private fun parseSaveAndFingerprint(source: File, root: File, cache: ChunkedContentCache): Expected {
        val parsed = measure {
            EpubParser.parseContent(source, File(root, "resources"), ReaderResourceLimits.DEFAULT)
        }
        val content = parsed.value
        assertTrue("Benchmark requires sequential content", content.totalElements > 0 && content.chapters.isNotEmpty())
        log("cold_parse", null, "all", parsed,
            "chapters=${content.chapters.size} elements=${content.totalElements} full_model_parse=true")
        var start = 0
        val chapterIndex = content.chapters.map { chapter ->
            BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also {
                start += chapter.elements.size
            }
        }
        assertEquals(content.totalElements, start)
        val index = BookIndex(chapterIndex, content.navigation, content.linkTargets,
            content.linkedDocumentTargets, content.fonts, content.language, content.pageProgression,
            content.totalElements, contentResourcePaths(content).sorted(), publisherStyleOf(content),
            epubSourceRanges = content.epubSourceRanges)
        val at85 = ((content.totalElements.toLong() * 85) / 100).toInt()
            .coerceIn(0, content.totalElements - 1)
        val chapter85 = chapterIndex.indexOfLast { it.elementCount > 0 && it.startElement <= at85 }
        check(chapter85 >= 0)
        val targets = listOf("0" to 0, "85" to chapter85, "last" to content.chapters.lastIndex)
            .map { (label, chapter) -> Target(label, chapter, digest(Chapter.serializer(), content.chapters[chapter])) }
        // Exact EPUB coordinates belong to the light index, outside the global block.
        val globals = BookContent(emptyList(), content.notes, content.linkTargets, content.fonts,
            content.language, content.linkedDocuments, content.linkedDocumentTargets, content.navigation,
            content.publisherPublication, content.pageProgression)
        // Streaming hashes run outside every index/chapter timing; no JSON
        // String or byte array holds the publication in memory.
        val expected = Expected(index, digest(BookIndex.serializer(), index),
            digest(ListSerializer(BookNavigationEntry.serializer()), content.navigation),
            digest(BookContent.serializer(), globals), targets)
        record("INDEXED_DEVICE phase=reference_check outside_timing=true " +
            "index_digest=${expected.indexDigest} navigation_digest=${expected.navigationDigest} " +
            "globals_digest=${expected.globalsDigest} targets=${targets.joinToString(",") { "${it.label}:${it.chapter}" }}")
        val saved = measure { cache.save(BOOK_ID, source, content) }
        log("frb_save", null, "all", saved, "saved=${saved.value} entry_bytes=${cache.entryFile(BOOK_ID).length()}")
        assertTrue("Complete indexed FRB must save this corpus", saved.value)
        assertTrue("Indexed FRB entry must exist", cache.entryFile(BOOK_ID).isFile)
        return expected
    }

    private fun verifyIndexedOpen(source: File, cache: ChunkedContentCache, expected: Expected, iteration: Int) {
        val opened = measure {
            requireNotNull(cache.openIndexed(BOOK_ID, source)) { "Indexed FRB cache miss" }
        }
        opened.value.use { session ->
            val index = session.index
            log("index_open", iteration, "all", opened,
                "cache_hit=true includes_source_hash=true includes_manifest_decode=true " +
                    "chapters=${index.chapters.size} elements=${index.totalElements} " +
                    "navigation_entries=${index.navigation.size} full_model_restore=false")
            assertEquals("Full chapter titles, depths, element counts and starts", expected.index.chapters, index.chapters)
            assertEquals("Whole-book total elements", expected.index.totalElements, index.totalElements)
            assertEquals("Whole index including links, fonts, resources, publisher summary and EPUB source ranges",
                expected.indexDigest, digest(BookIndex.serializer(), index))
            assertEquals("Full author navigation, including every target",
                expected.navigationDigest, digest(ListSerializer(BookNavigationEntry.serializer()), index.navigation))
            for (target in expected.targets) {
                val loaded = measure {
                    requireNotNull(session.readChapter(target.chapter)) { "Missing chapter ${target.chapter}" }
                }
                log("target_chapter", iteration, target.label, loaded,
                    "chapter=${target.chapter} decoded_elements=${loaded.value.elements.size} " +
                        "source_start=${index.chapters[target.chapter].startElement} preceding_chapters_decoded=0")
                assertEquals("Complete source chapter at ${target.label}%/last",
                    target.digest, digest(Chapter.serializer(), loaded.value))
            }
            if (iteration == 0) {
                val globals = measure { requireNotNull(session.readGlobals()) { "Missing global tables" } }
                log("heavy_globals", iteration, "all", globals, "separate_from_index_open=true")
                assertEquals("Exact notes, linked documents and global metadata", expected.globalsDigest,
                    digest(BookContent.serializer(), globals.value))
            }
        }
    }

    /** Run after the comparable reads, with an already validated, test-owned FRB. */
    private fun profileChapterReadPhases(file: File, expected: Expected) {
        val target = expected.targets.single { it.label == "85" }
        val descriptor = expected.index.chapters[target.chapter]
        val metadata = readFixtureMetadata(file, expected.index.chapters.size)
        val handle = RustChunkedCacheNative.openRead(file.path, metadata)
        check(handle != 0L) { "Could not reopen the benchmark FRB" }
        try {
            // FRB2 places the manifest and globals before the chapter blocks.
            val blockIndex = target.chapter + 2
            val nativeIndex = requireNotNull(RustChunkedCacheNative.readIndex(handle))
            check(nativeIndex.size == expected.index.chapters.size * 5)
            val entry = target.chapter * 5
            val compressedBytes = nativeIndex[entry + 1]
            val decodedBytes = nativeIndex[entry + 2]
            check(nativeIndex[entry + 3] == descriptor.elementCount.toLong())
            check(nativeIndex[entry + 4] == descriptor.startElement.toLong())
            val details = "chapter=${target.chapter} elements=${descriptor.elementCount} " +
                "compressed_bytes=$compressedBytes decoded_bytes=$decodedBytes diagnostic_only=true"
            val serializer = Chapter.serializer()
            repeat(5) { iteration ->
                val payload = measure {
                    requireNotNull(RustChunkedCacheNative.readBlockBytes(handle, blockIndex))
                }
                assertEquals(decodedBytes, payload.value.size.toLong())
                log("chapter_jni_read", iteration, target.label, payload, details)
                val decoded = measure { FrbModelCodec.decode(payload.value, serializer) }
                log("chapter_binary_decode", iteration, target.label, decoded, details)
                assertEquals(descriptor.elementCount, decoded.value.elements.size)
                assertEquals("Split phase must decode the same chapter", target.digest, digest(serializer, decoded.value))
            }
        } finally {
            RustChunkedCacheNative.close(handle)
        }
    }

    private fun readFixtureMetadata(file: File, chapters: Int): ByteArray = RandomAccessFile(file, "r").use { input ->
        // The native reopen checks CRCs, offsets and sizes. These bounds protect
        // this test's read of its own fixture before asking Rust to open it.
        val header = ByteArray(64).also(input::readFully)
        check(header.copyOfRange(0, 8).contentEquals("FRBCACHE".toByteArray(Charsets.US_ASCII)))
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val metadataLength = fields.getInt(24)
        check(fields.getInt(8) == 2 && fields.getInt(12) == header.size && fields.getInt(16) == 1)
        check(fields.getInt(20) == chapters && fields.getInt(28) == 48 && fields.getLong(32) == input.length())
        check(metadataLength in 1..(1024 * 1024) &&
            header.size + metadataLength + (chapters + 2L) * 48 <= input.length())
        ByteArray(metadataLength).also(input::readFully)
    }

    private fun <T> digest(serializer: SerializationStrategy<T>, value: T): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val discard = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        DigestOutputStream(discard, digest).use { json.encodeToStream(serializer, value, it) }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    private data class Measurement<T>(val value: T, val nanos: Long, val jvmBefore: Long,
        val jvmPeak: Long, val nativeBefore: Long, val nativePeak: Long)

    private fun <T> measure(block: () -> T): Measurement<T> {
        val runtime = Runtime.getRuntime()
        fun usedJvm() = runtime.totalMemory() - runtime.freeMemory()
        val jvmBefore = usedJvm()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val jvmPeak = AtomicLong(jvmBefore)
        val nativePeak = AtomicLong(nativeBefore)
        val running = AtomicBoolean(true)
        fun sample() {
            jvmPeak.accumulateAndGet(usedJvm(), ::maxOf)
            nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
        }
        val sampler = Thread({ while (running.get()) { sample(); Thread.sleep(2) } },
            "indexed-device-heap").apply { isDaemon = true; start() }
        val began = System.nanoTime()
        try {
            val value = block()
            val elapsed = System.nanoTime() - began
            sample()
            return Measurement(value, elapsed, jvmBefore, jvmPeak.get(), nativeBefore, nativePeak.get())
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private fun log(phase: String, iteration: Int?, target: String, measured: Measurement<*>, extra: String) {
        record("INDEXED_DEVICE phase=$phase iteration=${iteration ?: "setup"} target=$target " +
            "elapsed_ms=${millis(measured.nanos)} process_jvm_before_bytes=${measured.jvmBefore} " +
            "sampled_process_jvm_peak_bytes=${measured.jvmPeak} process_native_before_bytes=${measured.nativeBefore} " +
            "sampled_process_native_peak_bytes=${measured.nativePeak} $extra")
    }

    private fun record(message: String) { records += message; Log.i("FrogReaderPerf", message) }
    private fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private fun generatedEpub(target: File): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OPS/book.opf", buildString {
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">frog-indexed-benchmark-v2</dc:identifier><dc:title>Indexed benchmark</dc:title><dc:language>en</dc:language></metadata><manifest>")
                repeat(16) { append("<item id=\"c$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>") }
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest><spine>")
                repeat(16) { append("<itemref idref=\"c$it\"/>") }
                append("</spine></package>")
            })
            entry("OPS/nav.xhtml", buildString {
                append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol>")
                repeat(16) { append("<li><a href=\"chapter$it.xhtml\">Chapter $it</a></li>") }
                append("</ol></nav></body></html>")
            })
            repeat(16) { chapter ->
                entry("OPS/chapter$chapter.xhtml", buildString {
                    append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter $chapter</title><style>p { margin-top: .25em; text-indent: 1em; } em { font-style: italic; } strong { font-weight: bold; }</style></head><body><h1>Chapter $chapter</h1>")
                    repeat(96) { paragraph ->
                        append("<p id=\"p$paragraph\">Chapter $chapter paragraph $paragraph. ")
                        repeat(8) { append("A deterministic sentence with <em>styled words</em> and <strong>bold text</strong>. ") }
                        append("</p>")
                    }
                    append("</body></html>")
                })
            }
        }
        return target
    }

    private companion object { const val BOOK_ID = "complete-indexed" }
}
