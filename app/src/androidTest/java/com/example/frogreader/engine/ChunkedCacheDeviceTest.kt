@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.engine

import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.IndexedContentSession
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.openingContentSnapshot
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ParsedAnnotatedStringSerializer
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.PaginationSpec
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.ReaderPublisherBox
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.embeddedFontSignature
import com.example.frogreader.ui.reader.openingPaginationRange
import com.example.frogreader.ui.reader.paginateBook
import com.example.frogreader.ui.reader.paginateIndexedBookPages
import com.example.frogreader.ui.reader.paginationContainsLocation
import com.example.frogreader.ui.theme.publisherFontFamily
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android cache fidelity and diagnostic timing. M1 restores a complete
 * BookContent; this test makes no random-access or displayed-UI-frame claim.
 */
@RunWith(AndroidJUnit4::class)
class ChunkedCacheDeviceTest {
    private val density = Density(1f)
    private val records = mutableListOf<String>()
    private val json = Json { encodeDefaults = false; classDiscriminator = "kind" }

    /**
     * Run the same test APK source against both engine builds, then compare
     * the exported bytes. This is a whole-book pass, independent of FRB IO.
     */
    @Test fun exportCrossBuildPaginationGeometry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        lateinit var measurer: TextMeasurer
        instrumentation.runOnMainSync {
            measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        }
        // A stable extraction path also makes the reader's unmodified content
        // signature comparable across builds installed in this same app.
        val root = File(context.filesDir, "engine-benchmark/cross-build-pagination").apply { mkdirs() }
        val resources = File(root, "resources")
        check(!resources.exists() || resources.deleteRecursively())
        val arguments = InstrumentationRegistry.getArguments()
        fun insideFiles(path: String): File = File(context.filesDir, path).also {
            require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                "Pagination paths must stay inside the isolated app's files directory"
            }
        }
        val provided = arguments.getString("engineProfileEpub")
        val signaturesOnly = arguments.getString("enginePaginationSignaturesOnly").equals("true", ignoreCase = true)
        val source = if (provided.isNullOrBlank()) generatedEpub(File(root, "generated.epub"), geometry = true) else {
            insideFiles(provided).also { require(it.isFile) { "Missing benchmark EPUB: $provided" } }
        }
        val output = insideFiles(arguments.getString("enginePaginationExport")
            ?: "engine-benchmark/cross-build-pagination/pages.bin")
        require(output.canonicalFile != source.canonicalFile) { "Export must not overwrite the EPUB" }
        require(!output.canonicalPath.startsWith(resources.canonicalPath + File.separator)) {
            "Export must stay outside extracted resources"
        }
        output.parentFile!!.mkdirs()
        check(!output.exists() || output.delete())
        val partial = File(output.parentFile, output.name + ".partial")
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:PaginationExport")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            val sourceDigest = source.sha256()
            val content = EpubParser.parseContent(source, resources, ReaderResourceLimits.DEFAULT)
            // Production cache keys deliberately include image modification
            // times. Normalize only this fixture's newly extracted metadata so
            // two installs can compare the original, unmodified key contract.
            resources.walkTopDown().filter(File::isFile).forEach { file ->
                check(file.setLastModified(PAGINATION_RESOURCE_TIME) && file.lastModified() == PAGINATION_RESOURCE_TIME) {
                    "Cannot normalize pagination fixture timestamp: ${file.name}"
                }
            }
            val fonts = content.fonts.filter { File(it.path).isFile }.groupBy { it.family }
                .mapNotNull { (family, faces) -> publisherFontFamily(faces)?.let { family to it } }.toMap()
            val base = spec()
            val spec = PaginationSpec(base.contentWidthPx, base.contentHeightPx, density, base.settings,
                base.fontSize, fonts, embeddedFontSignature(content.fonts), content.language)
            val signature = contentSignature(content)
            var offset = 0
            val bookIndex = BookIndex(
                chapters = content.chapters.map { chapter ->
                    BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, offset)
                        .also { offset += chapter.elements.size }
                },
                navigation = content.navigation,
                linkTargets = content.linkTargets,
                linkedDocumentTargets = content.linkedDocumentTargets,
                fonts = content.fonts,
                language = content.language,
                pageProgression = content.pageProgression,
                totalElements = content.totalElements,
                resourcePaths = emptyList(),
                epubSourceRanges = content.epubSourceRanges,
            )
            val session = requireNotNull(IndexedBookSession.open(Closeable {}) {
                object : IndexedContentSession {
                    override val index = bookIndex
                    override val contentKey = sourceDigest
                    override fun readChapter(index: Int) = content.chapters.getOrNull(index)
                    override fun readGlobals() = content
                    override fun close() = Unit
                }
            })
            var pageCount = 0
            var tableParts = 0
            var continuedTables = 0
            var sideBoxes = 0
            session.use {
                DataOutputStream(partial.outputStream().buffered()).use { stream ->
                    val writer = PaginationGeometryWriter(stream, json, resources)
                    writer.text("FrogReader pagination geometry 1")
                    writer.text(sourceDigest)
                    writer.text(spec.key)
                    writer.text(signature)
                    writer.text(contentDigest(content))
                    stream.writeInt(content.chapters.size)
                    stream.writeInt(content.totalElements)
                    // This calls the same paginateLayout as paginateBook. Its
                    // callback releases each closed page instead of retaining
                    // the full private corpus's render fragments in memory.
                    if (!signaturesOnly) {
                        paginateIndexedBookPages(session, null, measurer, spec) { page ->
                            stream.writeInt(1)
                            writer.page(page)
                            pageCount++
                            tableParts += page.parts.count { it.tableLayout != null }
                            continuedTables += page.parts.count { it.tableLayout != null && it.rowStart > 0 }
                            sideBoxes += page.parts.count { it.sideBox != null }
                        }
                    }
                    stream.writeInt(0)
                    stream.writeInt(pageCount)
                }
            }
            if (!signaturesOnly) {
                assertTrue("The exported publication must contain pages", pageCount > 0)
                if (provided.isNullOrBlank()) {
                    assertTrue("Generated export must measure a table", tableParts > 0)
                    assertTrue("Generated export must split a table across pages", continuedTables > 0)
                    assertTrue("Generated export must measure a decorated initial", sideBoxes > 0)
                }
            }
            check(partial.renameTo(output)) { "Cannot publish pagination export" }
            val message = "PAGINATION_EXPORT result=passed source_sha256=$sourceDigest " +
                "content_signature=$signature pages=$pageCount bytes=${output.length()} " +
                "sha256=${output.sha256()} tables=$tableParts continued_tables=$continuedTables " +
                "side_boxes=$sideBoxes signatures_only=$signaturesOnly path=${output.relativeTo(context.filesDir).path}"
            record(message)
            File(root, "latest.log").writeText(message + "\n")
        } finally {
            partial.delete()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** Fixed field order, UTF-8 lengths and raw float bits form the binary schema. */
    private class PaginationGeometryWriter(
        private val out: DataOutputStream,
        private val json: Json,
        resources: File,
    ) {
        private val roots = listOf(resources.absolutePath, resources.canonicalPath).distinct()

        fun text(value: String?) {
            if (value == null) {
                out.writeInt(-1)
            } else {
                val normalized = roots.fold(value) { text, root -> text.replace(root, "@resources") }
                val bytes = normalized.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }

        private fun ints(values: IntArray) {
            out.writeInt(values.size)
            values.forEach(out::writeInt)
        }

        private fun optionalInt(value: Int?) {
            out.writeBoolean(value != null)
            value?.let(out::writeInt)
        }

        private fun box(box: ReaderPublisherBox) {
            text(box.id)
            text(box.parentId)
            text(json.encodeToString(PublisherBoxStyle.serializer(), box.style))
            text(box.floatSide?.name)
            out.writeBoolean(box.startsAtElement)
            out.writeBoolean(box.endsAtElement)
            // Inline group contents are already covered by the whole model
            // digest. Width/height memos are transient measurement caches.
        }

        fun page(page: BookPage) {
            out.writeInt(page.firstItemIndex)
            out.writeInt(page.firstCharOffset)
            out.writeInt(page.parts.size)
            for (part in page.parts) {
                out.writeInt(part.itemIndex)
                out.writeInt(part.charStart)
                out.writeInt(part.charEnd)
                out.writeInt(part.rowStart)
                out.writeInt(part.rowEnd)
                text(part.text?.let { json.encodeToString(ParsedAnnotatedStringSerializer, it) })
                out.writeBoolean(part.isParagraphStart)
                optionalInt(part.imageHeightPx)
                out.writeBoolean(part.headerRepeated)
                text(part.floatImagePath)
                out.writeBoolean(part.publisherStartsElement)
                out.writeBoolean(part.publisherEndsElement)
                out.writeBoolean(part.allowsVerticalScroll)
                out.writeBoolean(part.tableLayout != null)
                part.tableLayout?.let { table ->
                    ints(table.colWidthsPx)
                    ints(table.rowHeightsPx)
                    out.writeInt(table.fontScale.toRawBits())
                    ints(table.columnOffsetsPx)
                    out.writeInt(table.tableWidthPx)
                    out.writeInt(table.stackedCellHeightsPx?.size ?: -1)
                    table.stackedCellHeightsPx?.forEach(::ints)
                    out.writeInt(table.grid.columnCount)
                    out.writeInt(table.grid.cellColumns.size)
                    table.grid.cellColumns.forEach(::ints)
                    out.writeInt(table.grid.noBreakAfterRow.size)
                    table.grid.noBreakAfterRow.forEach(out::writeBoolean)
                }
                out.writeBoolean(part.sideBox != null)
                part.sideBox?.let { side ->
                    text(side.capText)
                    text(side.imagePath)
                    out.writeBoolean(side.leftSide)
                    out.writeInt(side.boxWidthPx)
                    out.writeInt(side.boxHeightPx)
                    out.writeInt(side.besideWidthPx)
                    out.writeInt(side.besideEndChar)
                    out.writeInt(side.compositeHeightPx)
                    out.writeInt(side.capFontSizeSp.toRawBits())
                    out.writeInt(side.capTopPx)
                    ints(side.publisherFloatItemHeightsPx.toIntArray())
                    out.writeBoolean(side.publisherStacked)
                    out.writeBoolean(side.publisherFloat != null)
                    side.publisherFloat?.let { float ->
                        text(float.side.name)
                        text(json.encodeToString(PublisherBoxStyle.serializer(), float.style))
                        out.writeInt(float.contents.size)
                        float.contents.forEach { leaf ->
                            out.writeInt(leaf.sourceItemIndex)
                            text(json.encodeToString(ContentElement.serializer(), leaf.element))
                            out.writeInt(leaf.publisherBoxes.size)
                            leaf.publisherBoxes.forEach(::box)
                        }
                    }
                }
                out.writeInt(part.publisherBoxes.size)
                part.publisherBoxes.forEach(::box)
            }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    @Test fun completeChunkedCachePreservesModelSignatureAndNativePageCuts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        lateinit var measurer: TextMeasurer
        instrumentation.runOnMainSync {
            measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        }
        val root = File.createTempFile("chunked-benchmark-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ChunkedCacheBenchmark")
        wakeLock.acquire(15 * 60 * 1000L)
        try {
            val provided = InstrumentationRegistry.getArguments().getString("engineProfileEpub")
            val source = if (provided.isNullOrBlank()) generatedEpub(File(root, "generated.epub")) else {
                File(context.filesDir, provided).also {
                    require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                        "engineProfileEpub must stay inside the isolated app's files directory"
                    }
                    require(it.isFile) { "Missing benchmark EPUB: $provided" }
                }
            }
            val cache = ChunkedContentCache(File(root, "chunked-content"))
            record("CHUNKED_DEVICE corpus=${if (provided.isNullOrBlank()) "generated" else "local"} " +
                "source_bytes=${source.length()} native_bridge=true heap_sampling_ms=2 " +
                "ui_frames=false full_model_restore=true")
            // This function returns no BookContent/ReaderItem references. The
            // original model can be collected before a complete cached restore.
            val expected = parseSaveAndFingerprint(source, root, cache, measurer, provided.isNullOrBlank())
            for (percent in listOf(0, 85)) {
                record("CHUNKED_DEVICE phase=gc_request position=$percent outside_timing=true " +
                    "reason=release_prior_model not_guaranteed=true")
                Runtime.getRuntime().gc()
                System.runFinalization()
                restoreAndVerify(source, cache, expected, percent, measurer)
            }
            assertTrue("Rust engine must remain available after real cache IO", RustBridge.shared.isAvailable)
            record("CHUNKED_DEVICE result=passed model_digest=true content_signature=true native_page_cuts=true")
        } finally {
            try {
                File(context.filesDir, "engine-benchmark/m1-latest.log").apply {
                    parentFile!!.mkdirs()
                    writeText(records.joinToString("\n", postfix = "\n"))
                }
            } finally {
                root.deleteRecursively()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /** Opening-only FRB timing: stop the parser at its first immutable prefix. */
    @Test fun openingSnapshotRestoresNativePageCuts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Device benchmarks require the isolated Rust app", context.packageName == "knitipka.frogreader")
        assertTrue("Required Rust JNI library did not load", RustBridge.shared.isAvailable)
        lateinit var measurer: TextMeasurer
        instrumentation.runOnMainSync {
            measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        }
        val root = File.createTempFile("chunked-opening-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ChunkedOpeningBenchmark")
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            val provided = InstrumentationRegistry.getArguments().getString("engineProfileEpub")
            val source = if (provided.isNullOrBlank()) generatedEpub(File(root, "generated.epub")) else {
                File(context.filesDir, provided).also {
                    require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                        "engineProfileEpub must stay inside the isolated app's files directory"
                    }
                    require(it.isFile) { "Missing benchmark EPUB: $provided" }
                }
            }
            record("CHUNKED_DEVICE corpus=${if (provided.isNullOrBlank()) "generated" else "local"} " +
                "source_bytes=${source.length()} native_bridge=true heap_sampling_ms=2 " +
                "ui_frames=false opening_snapshot_only=true full_model_restore=false")
            class PrefixCaptured : RuntimeException()
            var prefixEmitted = false
            val parsed = measure {
                var captured: BookContent? = null
                try {
                    val completed = EpubParser.parseContent(source, File(root, "resources"),
                        ReaderResourceLimits.DEFAULT, onPreview = { prefix ->
                            captured = prefix
                            prefixEmitted = true
                            throw PrefixCaptured()
                        })
                    // Very small books may complete without an intermediate prefix.
                    captured = completed
                } catch (_: PrefixCaptured) {
                    // Deliberately skip every remaining spine document.
                }
                checkNotNull(captured)
            }
            val prefix = parsed.value
            val snapshot = openingContentSnapshot(prefix, 0)
            val spec = spec()
            val prefixPages = pages(prefix, 0, measurer, spec)
            assertTrue(paginationContainsLocation(prefixPages, ReaderReturnLocation.Main(0)))
            assertTrue("Opening needs at least three closed pages", prefixPages.size >= 3)
            val expectedCuts = cuts(prefixPages)
            assertEquals("Bounded opening retains the parser prefix's native cuts",
                expectedCuts, cuts(pages(snapshot, 0, measurer, spec)))
            val digest = contentDigest(snapshot)
            val signature = contentSignature(snapshot)
            log("parse_to_first_prefix", 0, parsed, "parser_aborted_after_prefix=$prefixEmitted " +
                "source_prefix_elements=${prefix.totalElements} snapshot_elements=${snapshot.totalElements}")

            val cache = ChunkedContentCache(File(root, "chunked-content"))
            val bookId = "opening-only"
            val saved = measure { cache.save(bookId, source, snapshot, complete = false) }
            val openingFile = cache.entryFile(bookId, complete = false)
            log("opening_save", 0, saved, "saved=${saved.value} entry_bytes=${openingFile.length()} " +
                "cache_bytes=${treeBytes(openingFile.parentFile!!)}")
            assertTrue("Bounded opening FRB must save", saved.value)
            assertTrue("Opening entry is an independent FRB", openingFile.isFile && openingFile.name == "opening.frb")
            assertFalse("Opening-only cache must work without a complete entry", cache.entryFile(bookId).exists())

            var restoredModel: BookContent? = null
            var restoreNanos = 0L
            val opened = measure {
                val began = System.nanoTime()
                val content = requireNotNull(cache.loadPreview(bookId, source)) { "Opening FRB cache miss" }
                restoreNanos = System.nanoTime() - began
                restoredModel = content
                pages(content, 0, measurer, spec)
            }
            val restored = checkNotNull(restoredModel)
            log("opening_warm_open", 0, opened, "cache_hit=true full_model_restore=false " +
                "restore_ms=${millis(restoreNanos)} first_pages_ms=${millis(opened.nanos)} " +
                "pages=${opened.value.size} restored_elements=${restored.totalElements} " +
                "entry_bytes=${openingFile.length()}")
            assertEquals("Opening cache native cuts match the larger parser prefix", expectedCuts, cuts(opened.value))
            assertEquals("Whole bounded opening model", digest, contentDigest(restored))
            assertEquals("Bounded opening PaginationCache.contentSignature", signature, contentSignature(restored))
            assertTrue("Rust engine must remain available after opening cache IO", RustBridge.shared.isAvailable)
            record("CHUNKED_DEVICE result=passed opening_snapshot=true model_digest=true " +
                "content_signature=true native_page_cuts=true complete_entry_present=false")
        } finally {
            try {
                File(context.filesDir, "engine-benchmark/m1-opening-latest.log").apply {
                    parentFile!!.mkdirs()
                    writeText(records.joinToString("\n", postfix = "\n"))
                }
            } finally {
                root.deleteRecursively()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private data class Expected(
        val digest: String,
        val signature: String,
        val elements: Int,
        val chapters: Int,
        val targets: Map<Int, Int>,
        val cuts: Map<Int, List<List<Any?>>>,
    )

    private fun parseSaveAndFingerprint(source: File, root: File, cache: ChunkedContentCache,
        measurer: TextMeasurer, generated: Boolean): Expected {
        val spec = spec()
        var firstPagesNanos: Long? = null
        var prefixCuts: List<List<Any?>>? = null
        val parsed = measure {
            val began = System.nanoTime()
            EpubParser.parseContent(source, File(root, "resources"), ReaderResourceLimits.DEFAULT,
                onPreview = { prefix ->
                    prefixCuts = cuts(pages(prefix, 0, measurer, spec))
                    firstPagesNanos = System.nanoTime() - began
                })
        }
        val content = parsed.value
        val targets = mapOf(0 to 0, 85 to ((content.totalElements.toLong() * 85) / 100).toInt())
        val pageCuts = targets.mapValues { (_, target) ->
            val pages = pages(content, target, measurer, spec)
            assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(target)))
            assertTrue("Opening needs at least three closed pages", pages.size >= 3)
            cuts(pages)
        }
        prefixCuts?.let { assertEquals("Cold prefix page cuts", pageCuts.getValue(0), it) }
        log("cold_parse", 0, parsed, "first_pages_ms=${firstPagesNanos?.let(::millis) ?: "unavailable"} " +
            "chapters=${content.chapters.size} elements=${content.totalElements} " +
            "preview_callback_paginated=true")
        val referenceDigest = contentDigest(content)
        val referenceSignature = contentSignature(content)
        if (generated) {
            assertEquals("Bounded signature traversal must match the ordinary reader list",
                PaginationCache.contentSignature(ReaderChapterItems(content.chapters)), referenceSignature)
        }
        record("CHUNKED_DEVICE phase=reference_check digest=$referenceDigest " +
            "content_signature=$referenceSignature outside_timing=true")
        val saved = measure { cache.save(BOOK_ID, source, content) }
        log("chunked_full_save", null, saved, "saved=${saved.value} " +
            "entry_bytes=${cache.entryFile(BOOK_ID).length()} " +
            "cache_bytes=${treeBytes(cache.entryFile(BOOK_ID).parentFile!!)}")
        assertTrue("Complete chunked cache must save this corpus", saved.value)
        assertTrue("FRB entry must exist", cache.entryFile(BOOK_ID).isFile)
        assertFalse("Chunked cache must not write a legacy full-rejection marker",
            File(cache.entryFile(BOOK_ID).parentFile, "full-rejected.bin").exists())
        return Expected(referenceDigest, referenceSignature, content.totalElements,
            content.chapters.size, targets, pageCuts)
    }

    private fun restoreAndVerify(source: File, cache: ChunkedContentCache, expected: Expected,
        percent: Int, measurer: TextMeasurer) {
        var restoreNanos = 0L
        var restoredModel: BookContent? = null
        val opened = measure {
            val began = System.nanoTime()
            val content = requireNotNull(cache.load(BOOK_ID, source)) { "Complete FRB cache miss" }
            restoreNanos = System.nanoTime() - began
            restoredModel = content
            pages(content, expected.targets.getValue(percent), measurer, spec())
        }
        val content = checkNotNull(restoredModel)
        assertEquals(expected.elements, content.totalElements)
        assertEquals(expected.chapters, content.chapters.size)
        assertEquals("Exact native page geometry at $percent%", expected.cuts.getValue(percent), cuts(opened.value))
        log("chunked_warm_open", percent, opened, "cache_hit=true full_model_restore=true " +
            "restore_ms=${millis(restoreNanos)} first_pages_ms=${millis(opened.nanos)} " +
            "pages=${opened.value.size} entry_bytes=${cache.entryFile(BOOK_ID).length()}")
        // Both exhaustive checks run outside the opening measurement. They do
        // not retain the source model or prepare all reader chapters at once.
        assertEquals("Whole serialized BookContent", expected.digest, contentDigest(content))
        assertEquals("PaginationCache.contentSignature", expected.signature, contentSignature(content))
    }

    private fun spec() = PaginationSpec(360, 600, density,
        ReaderSettings(font = ReaderFont.SERIF, startChaptersOnNewPage = true), 18f)

    private fun pages(content: BookContent, target: Int, measurer: TextMeasurer,
        spec: PaginationSpec): List<BookPage> = runBlocking {
        val items = ReaderChapterItems(content.chapters)
        items.prepareAt(target)
        val range = openingPaginationRange(items.chapterStarts, items.size, target, true)
        paginateBook(items, measurer, spec, fromIndex = range?.fromIndex ?: 0,
            toIndex = range?.toIndex ?: items.size, stopAfterLocation = ReaderReturnLocation.Main(target))
    }

    private fun cuts(pages: List<BookPage>): List<List<Any?>> = pages.map { page ->
        listOf(page.firstItemIndex, page.firstCharOffset, page.parts.map { part ->
            listOf(part.itemIndex, part.charStart, part.charEnd, part.rowStart, part.rowEnd,
                part.text, part.isParagraphStart, part.imageHeightPx, part.headerRepeated,
                part.floatImagePath, part.publisherStartsElement, part.publisherEndsElement,
                part.allowsVerticalScroll,
                part.tableLayout?.let { table -> listOf(table.colWidthsPx.toList(), table.rowHeightsPx.toList(),
                    table.fontScale.toBits(), table.columnOffsetsPx.toList(), table.tableWidthPx,
                    table.stackedCellHeightsPx?.map { it.toList() }, table.grid.columnCount,
                    table.grid.cellColumns.map { it.toList() }, table.grid.noBreakAfterRow.toList()) },
                part.sideBox?.let { box -> listOf(box.capText, box.imagePath, box.leftSide,
                    box.boxWidthPx, box.boxHeightPx, box.besideWidthPx, box.besideEndChar,
                    box.compositeHeightPx, box.capFontSizeSp.toBits(), box.capTopPx,
                    box.publisherFloatItemHeightsPx, box.publisherStacked,
                    box.publisherFloat?.side, box.publisherFloat?.style,
                    box.publisherFloat?.contents?.map { it.sourceItemIndex }) },
                part.publisherBoxes.map { box -> listOf(box.id, box.parentId, box.style,
                    box.floatSide, box.startsAtElement, box.endsAtElement) })
        })
    }

    private fun contentDigest(content: BookContent): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val discard = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        DigestOutputStream(discard, digest).use { json.encodeToStream(BookContent.serializer(), content, it) }
        return digest.digest().hex()
    }

    private fun contentSignature(content: BookContent): String =
        PaginationCache.contentSignature(ChapterSignatureItems(content.chapters))

    /** Existing reader preparation and global coordinates, one live chapter at a time. */
    private class ChapterSignatureItems(private val chapters: List<Chapter>) : AbstractList<ReaderItem>() {
        private val starts = IntArray(chapters.size)
        override val size: Int
        private var chapter = -1
        private var current: ReaderChapterItems? = null
        init {
            var offset = 0
            chapters.forEachIndexed { index, value -> starts[index] = offset; offset += value.elements.size }
            size = offset
        }
        override fun get(index: Int): ReaderItem {
            if (index !in indices) throw IndexOutOfBoundsException("Item $index, size $size")
            var low = 0
            var high = starts.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (starts[middle] <= index) low = middle + 1 else high = middle
            }
            val wanted = low - 1
            if (chapter != wanted) {
                current = ReaderChapterItems(chapters)
                chapter = wanted
            }
            return checkNotNull(current)[index]
        }
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
            "chunked-device-heap").apply { isDaemon = true; start() }
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

    private fun log(phase: String, percent: Int?, measured: Measurement<*>, extra: String) {
        record("CHUNKED_DEVICE phase=$phase position=${percent ?: "all"} elapsed_ms=${millis(measured.nanos)} " +
            "process_jvm_before_bytes=${measured.jvmBefore} sampled_process_jvm_peak_bytes=${measured.jvmPeak} " +
            "process_native_before_bytes=${measured.nativeBefore} sampled_process_native_peak_bytes=${measured.nativePeak} $extra")
    }

    private fun record(message: String) { records += message; Log.i(TAG, message) }
    private fun treeBytes(root: File): Long =
        if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0
    private fun ByteArray.hex() = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun generatedEpub(target: File, geometry: Boolean = false): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OPS/book.opf", buildString {
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">frog-benchmark-v1</dc:identifier><dc:title>Engine benchmark</dc:title><dc:language>en</dc:language></metadata><manifest>")
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
                    append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter $chapter</title><style>p { margin-top: .25em; text-indent: 1em; } em { font-style: italic; } strong { font-weight: bold; }")
                    if (geometry) append("p.cap::first-letter { float: left; font-size: 2.5em; line-height: 1; } td, th { border: 1px solid currentColor; padding: .2em; } .panel { border: 1px solid currentColor; padding: .3em; }")
                    append("</style></head><body><h1>Chapter $chapter</h1>")
                    if (geometry && chapter == 0) {
                        append("<div class=\"panel\"><p class=\"cap\">")
                        repeat(32) { append("Decorated initials keep exact text and the same measured side box. ") }
                        append("</p></div><table><thead><tr><th>Row</th><th>Measured column</th></tr></thead><tbody>")
                        repeat(46) { row ->
                            append("<tr>")
                            if (row != 1) append(if (row == 0) "<td rowspan=\"2\">Rows 0 and 1</td>" else "<td>Row $row</td>")
                            append("<td>Table row $row keeps wrapping, padding and repeated headers across pages.</td></tr>")
                        }
                        append("</tbody></table>")
                    }
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

    private companion object {
        const val TAG = "FrogReaderPerf"
        const val BOOK_ID = "complete"
        const val PAGINATION_RESOURCE_TIME = 1_600_000_000_000L
        fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
    }
}
