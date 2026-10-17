package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.ui.reader.openingPaginationRange
import java.io.File
import java.nio.file.Paths
import jdk.jfr.Recording
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in local measurement; the private publication never becomes a test fixture. */
class LargeEpubProfileTest {
    @Test fun profileProvidedBook() {
        val sourcePath = System.getProperty("frogreader.profileEpub")
        assumeTrue("Set FROGREADER_PROFILE_EPUB to profile a local EPUB", !sourcePath.isNullOrBlank())
        val source = File(checkNotNull(sourcePath))
        val recordingPath = System.getProperty("frogreader.profileJfr")
        val recording = recordingPath?.let { Recording() }?.apply {
            enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10))
            start()
        }
        try {
            repeat(2) { run ->
                val images = File(System.getProperty("java.io.tmpdir"), "frogreader-large-epub-profile").apply { mkdirs() }
                val start = System.nanoTime()
                val content = EpubParser.parseContent(source, images)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val paragraphs = content.chapters.sumOf { chapter -> chapter.elements.count { it is ContentElement.Paragraph } }
                var flatIndex = 1 // The reader places the cover before chapter zero.
                val starts = content.chapters.map { chapter ->
                    flatIndex.also { flatIndex += chapter.elements.size }
                }
                val preview = openingPaginationRange(starts, flatIndex, 0, true)
                val middlePreview = openingPaginationRange(starts, flatIndex, flatIndex / 2, true)
                println("LARGE_EPUB run=$run parse_ms=$elapsed chapters=${content.chapters.size} " +
                    "elements=${content.totalElements} paragraphs=$paragraphs " +
                    "opening_items=${preview?.let { it.toIndex - it.fromIndex }} " +
                    "middle_items=${middlePreview?.let { it.toIndex - it.fromIndex }}")
            }
        } finally {
            recording?.stop()
            recording?.dump(Paths.get(checkNotNull(recordingPath)))
            recording?.close()
        }
    }
}
