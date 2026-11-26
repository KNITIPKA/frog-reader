@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.engine

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EpubSourceRange
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.ReaderResourceLimits
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Selective FRB access timings; Android rendering and ReaderScreen readiness are separate gates. */
class IndexedCacheBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = false; classDiscriminator = "kind" }

    @Test fun recordSelectiveChapterAccess() {
        assertTrue("The benchmark requires the real Rust host JNI library", RustBridge.shared.isAvailable)
        val provided = System.getProperty("frogreader.profileEpub")?.takeIf { it.isNotBlank() }
        val source = provided?.let(::File) ?: temporary.newFile("generated.epub").apply { writeText("deterministic model source") }
        require(source.isFile) { "Missing benchmark source" }
        val cache = ChunkedContentCache(temporary.newFolder("indexed-cache"))
        val rows = mutableListOf<Row>()
        val expected = prepare(source, cache, provided != null, rows)

        for (percent in listOf(0, 85, 100)) {
            repeat(5) { run ->
                val opened = measure { cache.openIndexed("benchmark", source) }
                rows += Row("index_open", percent, run + 1, opened.nanos)
                requireNotNull(opened.value).use { session ->
                    assertEquals(expected.totalElements, session.index.totalElements)
                    assertEquals(expected.titles, session.index.chapters.map { it.title })
                    assertEquals(expected.counts, session.index.chapters.map { it.elementCount })
                    assertEquals(expected.starts, session.index.chapters.map { it.startElement })
                    assertEquals(expected.sourceRanges, session.index.epubSourceRanges)
                    val target = ((session.index.totalElements - 1).coerceAtLeast(0).toLong() * percent / 100).toInt()
                    val chapterIndex = session.index.chapters.indexOfLast {
                        it.elementCount > 0 && it.startElement <= target
                    }.coerceAtLeast(0)
                    val chapter = measure { session.readChapter(chapterIndex) }
                    rows += Row("target_chapter", percent, run + 1, chapter.nanos)
                    rows += Row("index_and_target", percent, run + 1, opened.nanos + chapter.nanos)
                    assertArrayEquals(expected.chapterDigests[chapterIndex], digest(requireNotNull(chapter.value)))
                }
            }
        }
        requireNotNull(cache.openIndexed("benchmark", source)).use { session ->
            val globals = measure { session.readGlobals() }
            rows += Row("heavy_globals", null, 1, globals.nanos)
            assertArrayEquals(expected.globalsDigest, digest(requireNotNull(globals.value)))
        }

        val cacheBytes = cache.entryFile("benchmark").length()
        if (provided != null) {
            assertTrue(cache.entryFile("benchmark").delete())
            for (percent in listOf(0, 85)) repeat(5) { trial ->
                val opened = measure { cache.loadEpubSourceIndex("benchmark", source) }
                val index = requireNotNull(opened.value) { "Missing retained source index" }
                val target = (index.totalElements.toLong() * percent / 100).toInt()
                val chapter = index.chapters.indexOfLast { it.startElement <= target && it.elementCount > 0 }
                val extracted = File(temporary.root, "resources")
                extracted.deleteRecursively()
                val mapped = measure {
                    EpubParser.parseTargetWindow(source, extracted, ReaderResourceLimits.DEFAULT, index, chapter, chapter)
                }
                val window = requireNotNull(mapped.value)
                assertEquals(index.chapters[chapter].startElement, window.firstElement)
                assertArrayEquals(expected.chapterDigests[chapter], digest(window.chapters.single()))
                rows += Row("source_index", percent, trial + 1, opened.nanos)
                rows += Row("source_target", percent, trial + 1, mapped.nanos)
                rows += Row("source_index_and_target", percent, trial + 1, opened.nanos + mapped.nanos)
            }
        }

        val report = report(source, provided != null, cacheBytes, expected, rows)
        println(report)
        System.getProperty("frogreader.engineBenchmarkReport")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).apply { parentFile?.mkdirs(); writeText(report) }
        }
    }

    private data class Expected(
        val titles: List<String?>,
        val counts: List<Int>,
        val starts: List<Int>,
        val totalElements: Int,
        val chapterDigests: List<ByteArray>,
        val globalsDigest: ByteArray,
        val publicationDigest: String?,
        val sourceRanges: List<EpubSourceRange>,
    )

    /** No complete BookContent escapes this function into the selective-access measurements. */
    private fun prepare(source: File, cache: ChunkedContentCache, parseEpub: Boolean, rows: MutableList<Row>): Expected {
        val resources = temporary.newFolder("resources")
        val parsed = measure {
            if (parseEpub) EpubParser.parseContent(source, resources)
            else BookContent(List(32) { chapter -> Chapter("Chapter $chapter", List(128) { element ->
                ContentElement.Paragraph(AnnotatedString("Chapter $chapter element $element. " + "Deterministic styled-model benchmark content. ".repeat(16)))
            }) })
        }
        rows += Row(if (parseEpub) "source_parse" else "generate_model", null, 1, parsed.nanos)
        val content = parsed.value
        val saved = measure { cache.save("benchmark", source, content) }
        rows += Row("frb_save", null, 1, saved.nanos)
        assertTrue("A complete model must fit the FRB cache", saved.value)
        var start = 0
        val starts = content.chapters.map { chapter -> start.also { start += chapter.elements.size } }
        val globals = BookContent(emptyList(), content.notes, content.linkTargets, content.fonts, content.language,
            content.linkedDocuments, content.linkedDocumentTargets, content.navigation,
            content.publisherPublication, content.pageProgression)
        val publicationDigest = if (parseEpub) {
            val fingerprint = sha { output ->
                fun record(encoded: String) {
                    val relative = encoded.replace(resources.absolutePath + File.separator, "@resources/")
                    output.write(relative.toByteArray(Charsets.UTF_8))
                    output.write('\n'.code)
                }
                record(json.encodeToString(BookContent.serializer(), globals))
                content.chapters.forEach { record(json.encodeToString(Chapter.serializer(), it)) }
            }
            HexFormat.of().formatHex(fingerprint)
        } else null
        return Expected(
            content.chapters.map { it.title }, content.chapters.map { it.elements.size }, starts,
            content.totalElements, content.chapters.map(::digest),
            digest(globals),
            publicationDigest,
            content.epubSourceRanges,
        )
    }

    private fun digest(chapter: Chapter): ByteArray = sha { json.encodeToStream(Chapter.serializer(), chapter, it) }
    private fun digest(content: BookContent): ByteArray = sha { json.encodeToStream(BookContent.serializer(), content, it) }
    private fun sha(write: (OutputStream) -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use(write)
        return digest.digest()
    }

    private data class Timed<T>(val value: T, val nanos: Long)
    private fun <T> measure(block: () -> T): Timed<T> {
        val started = System.nanoTime()
        val value = block()
        return Timed(value, System.nanoTime() - started)
    }
    private data class Row(val phase: String, val percent: Int?, val run: Int, val nanos: Long)

    private fun report(source: File, parsed: Boolean, bytes: Long, expected: Expected, rows: List<Row>): String = buildString {
        appendLine("# M2 indexed-access host measurements")
        appendLine()
        appendLine("Measured ${java.time.Instant.now()} using ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
        appendLine()
        appendLine("Corpus: ${if (parsed) "optional local EPUB" else "deterministic generated BookContent (not an EPUB parser measurement)"}; source ${source.length()} bytes, ${expected.titles.size} chapters, ${expected.totalElements} elements; FRB $bytes bytes.")
        expected.publicationDigest?.let { appendLine("Normalized publication SHA-256: $it") }
        appendLine("Exact EPUB source ranges verified: ${expected.sourceRanges.size}")
        appendLine()
        appendLine("| Phase | Position | Run | Time (ms) |")
        appendLine("| --- | ---: | ---: | ---: |")
        rows.forEach { appendLine("| ${it.phase} | ${it.percent?.let { value -> "$value%" } ?: "all"} | ${it.run} | ${millis(it.nanos)} |") }
        appendLine()
        appendLine("| Phase | Position | p50 (ms) | p95 (ms) |")
        appendLine("| --- | ---: | ---: | ---: |")
        rows.filter { it.percent != null }.groupBy { it.phase to it.percent }.forEach { (key, samples) ->
            val sorted = samples.map { it.nanos }.sorted()
            appendLine("| ${key.first} | ${key.second}% | ${millis(sorted[sorted.size / 2])} | ${millis(sorted.last())} |")
        }
        appendLine()
        appendLine("Each index_open creates a new native session and validates the complete source SHA-256 and lightweight manifest. target_chapter reads exactly the destination chapter; it does not decode preceding chapters or heavy notes/linked documents. Per-chapter canonical SHA-256, all chapter coordinates and titles are verified outside timers. Heavy globals are timed separately and verified with a canonical model digest. The parser model leaves scope before selective reads.")
        if (parsed) appendLine("source_index_and_target opens the retained source index after deleting the chapter FRB and measures a targeted EPUB parse. Extracted resources are deleted before each timed target parse. The chapter must match the full parse digest.")
        appendLine()
        appendLine("Five repeats share a warmed JVM and OS cache; p95 uses the maximum sample (nearest rank). No CPU, native heap, Android text measurement, page cuts, input latency or displayed frame is measured. These timings establish a storage primitive and do not prove that the real reader's TOC, slider, exact page count or complete readiness meets the M2/M2F budgets.")
    }

    private fun millis(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
}
