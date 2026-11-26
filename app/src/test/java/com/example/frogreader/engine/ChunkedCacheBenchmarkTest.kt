@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.engine

import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderChapterItems
import java.io.File
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** FRB model-restore benchmark; it does not measure Android page layout or UI frames. */
class ChunkedCacheBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val canonicalJson = Json { encodeDefaults = false; classDiscriminator = "kind" }

    @Test fun recordCompleteChunkedCacheBaseline() {
        assertTrue("Benchmark requires the real Rust host library", RustBridge.shared.isAvailable)
        val provided = System.getProperty("frogreader.profileEpub")?.takeIf { it.isNotBlank() }
        val source = provided?.let(::File) ?: generatedEpub(File(temporary.root, "benchmark.epub"))
        require(source.isFile) { "Benchmark source is missing: $source" }
        val cacheRoot = temporary.newFolder("chunked-cache")
        val cache = ChunkedContentCache(cacheRoot)
        val rows = mutableListOf<Row>()
        // The parser model and its fully prepared signature list leave scope
        // before restore, so separate restores need not retain duplicate books.
        val prepared = parseAndSave(source, cache, rows)
        for (percent in listOf(0, 85)) restoreAndCheck(source, cache, prepared, percent, rows)
        assertFalse(File(cache.entryFile("benchmark").parentFile, "full-rejected.bin").exists())

        println("CHUNKED_BENCHMARK corpus=${if (provided == null) "generated" else "local"} " +
            "source_bytes=${source.length()} chapters=${prepared.chapters} elements=${prepared.elements} " +
            "model_json_bytes=${prepared.fingerprint.bytes} frb_bytes=${cache.entryFile("benchmark").length()}")
        rows.forEach { println(it.log()) }
        val report = report(source, provided == null, prepared, rows, treeBytes(cacheRoot))
        System.getProperty("frogreader.engineBenchmarkReport")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).apply { parentFile?.mkdirs(); writeText(report) }
        }
        println(report)
    }

    private data class PreparedBook(
        val fingerprint: Fingerprint,
        val signature: String,
        val chapters: Int,
        val elements: Int,
    )

    private fun parseAndSave(source: File, cache: ChunkedContentCache, rows: MutableList<Row>): PreparedBook {
        val parse = measure { EpubParser.parseContent(source, temporary.newFolder("resources")) }
        rows += parse.row("parse", null)
        val original = parse.value
        val fingerprint = fingerprint(original)
        val signature = PaginationCache.contentSignature(ReaderChapterItems(original.chapters))
        val save = measure { cache.save("benchmark", source, original) }
        rows += save.row("frb_save", null, save.value, cache.entryFile("benchmark").length())
        assertTrue("A complete provided book must save in the FRB cache", save.value)
        assertTrue(cache.entryFile("benchmark").isFile)
        return PreparedBook(fingerprint, signature, original.chapters.size, original.totalElements)
    }

    private fun restoreAndCheck(source: File, cache: ChunkedContentCache, expected: PreparedBook,
        percent: Int, rows: MutableList<Row>) {
        val restored = measure { cache.load("benchmark", source) }
        rows += restored.row("frb_full_restore", percent, restored.value != null,
            cache.entryFile("benchmark").length())
        val content = requireNotNull(restored.value) { "A successfully written FRB must restore completely" }
        assertEquals(expected.chapters, content.chapters.size)
        assertEquals(expected.elements, content.totalElements)
        val actual = fingerprint(content)
        assertEquals(expected.fingerprint.bytes, actual.bytes)
        assertArrayEquals(expected.fingerprint.digest, actual.digest)
        assertEquals(expected.signature, PaginationCache.contentSignature(ReaderChapterItems(content.chapters)))
        val target = ((expected.elements.toLong() * percent) / 100).toInt()
        val prepare = measure {
            val items = ReaderChapterItems(content.chapters)
            items.prepareAt(target)
            if (items.isEmpty()) null else items[target.coerceAtMost(items.lastIndex)].element
        }
        rows += prepare.row("prepare_saved_chapter", percent)
    }

    private data class Fingerprint(val digest: ByteArray, val bytes: Long)

    private fun fingerprint(content: BookContent): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingOutputStream()
        DigestOutputStream(counted, digest).use { canonicalJson.encodeToStream(BookContent.serializer(), content, it) }
        return Fingerprint(digest.digest(), counted.count)
    }

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set
        override fun write(value: Int) { count++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { count += length }
    }

    private data class Measurement<T>(val value: T, val nanos: Long, val heapBefore: Long, val heapPeak: Long) {
        fun row(phase: String, position: Int?, success: Boolean? = null, bytes: Long? = null) =
            Row(phase, position, nanos, heapBefore, heapPeak, success, bytes)
    }

    private data class Row(val phase: String, val position: Int?, val nanos: Long,
        val heapBefore: Long, val heapPeak: Long, val success: Boolean?, val bytes: Long?) {
        fun log() = "CHUNKED_BENCHMARK phase=$phase position=${position ?: "all"} " +
            "elapsed_ms=${millis(nanos)} jvm_before_bytes=$heapBefore sampled_jvm_peak_bytes=$heapPeak " +
            "success=${success ?: "n/a"} disk_bytes=${bytes ?: "n/a"}"
    }

    private fun <T> measure(block: () -> T): Measurement<T> {
        val bean = ManagementFactory.getMemoryMXBean()
        val initial = bean.heapMemoryUsage.used
        val peak = AtomicLong(initial)
        val running = AtomicBoolean(true)
        val sampler = Thread({
            while (running.get()) {
                peak.accumulateAndGet(bean.heapMemoryUsage.used, ::maxOf)
                Thread.sleep(2)
            }
        }, "chunked-cache-benchmark-heap").apply { isDaemon = true; start() }
        val start = System.nanoTime()
        try {
            val value = block()
            val nanos = System.nanoTime() - start
            peak.accumulateAndGet(bean.heapMemoryUsage.used, ::maxOf)
            return Measurement(value, nanos, initial, peak.get())
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private fun treeBytes(root: File): Long =
        if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0

    private fun report(source: File, generated: Boolean, prepared: PreparedBook, rows: List<Row>, bytes: Long) = buildString {
        appendLine("# M1 complete chunked-cache host measurements")
        appendLine()
        appendLine("Measured on ${java.time.Instant.now()} with ${System.getProperty("java.vm.name")} " +
            "${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
        appendLine()
        appendLine("Corpus: ${if (generated) "deterministic generated EPUB (16 spine documents, 96 paragraphs per document)" else "optional local EPUB"}; " +
            "${source.length()} source bytes; ${prepared.chapters} parsed chapters; ${prepared.elements} elements. " +
            "Canonical complete-model JSON: ${prepared.fingerprint.bytes} bytes. Final cache tree: $bytes bytes.")
        appendLine()
        appendLine("| Phase | Saved position | Time (ms) | JVM heap before (bytes) | Sampled JVM heap peak (bytes) | Success | FRB bytes |")
        appendLine("| --- | ---: | ---: | ---: | ---: | --- | ---: |")
        rows.forEach { row ->
            appendLine("| ${row.phase} | ${row.position?.let { "$it%" } ?: "all"} | ${millis(row.nanos)} | " +
                "${row.heapBefore} | ${row.heapPeak} | ${row.success ?: "n/a"} | ${row.bytes ?: "n/a"} |")
        }
        appendLine()
        appendLine("Every complete restore must match the original parser model by a streaming SHA-256 over " +
            "all serialized BookContent fields and by PaginationCache.contentSignature over ReaderChapterItems. " +
            "Those checks run outside the timed phases. The original parser model leaves scope before the restores.")
        appendLine()
        appendLine("M1 restores the complete model for either saved position. These two rows are independent " +
            "native-reader sessions in the same warmed host JVM, not selective chapter loading. Preparing the saved " +
            "chapter is recorded separately. No Android text measurement, pagination or displayed UI frame is timed.")
        appendLine()
        appendLine("The source parse has empty derived resources and no parsed cache. JVM/JIT, OS caches, " +
            "font caches and GC are not reset. Heap is whole-process used JVM heap sampled every 2 ms; it is not " +
            "allocated bytes, native memory or a guaranteed absolute peak. No timing threshold is asserted.")
        appendLine()
        appendLine("Compare with the separately captured before reports for the same source. The old monolithic " +
            "codec is not retained for compatibility. Model correctness and successful persistence do not by " +
            "themselves prove an Android opening-time improvement; device measurements remain a separate gate.")
    }

    private fun generatedEpub(target: File): File {
        val chapters = 16
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            entry("OPS/book.opf", buildString {
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">")
                append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">frog-benchmark-v1</dc:identifier>")
                append("<dc:title>Engine benchmark</dc:title><dc:language>en</dc:language></metadata><manifest>")
                repeat(chapters) { append("<item id=\"c$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>") }
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest><spine>")
                repeat(chapters) { append("<itemref idref=\"c$it\"/>") }
                append("</spine></package>")
            })
            entry("OPS/nav.xhtml", buildString {
                append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol>")
                repeat(chapters) { append("<li><a href=\"chapter$it.xhtml\">Chapter $it</a></li>") }
                append("</ol></nav></body></html>")
            })
            repeat(chapters) { chapter ->
                entry("OPS/chapter$chapter.xhtml", buildString {
                    append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter $chapter</title>")
                    append("<style>p { margin-top: .25em; text-indent: 1em; } em { font-style: italic; } strong { font-weight: bold; }</style>")
                    append("</head><body><h1>Chapter $chapter</h1>")
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
        fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
    }
}
