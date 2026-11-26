package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Process-death staging files are rebuildable data and must not exhaust future writes. */
class ChunkedContentCacheRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `abandoned chapter and pending container do not permanently consume the entry budget`() {
        val source = temporary.newFile("source.epub").apply { writeText("Immutable complete source") }
        val cache = ChunkedContentCache(temporary.newFolder("cache"))
        assertTrue(cache.save("book", source, content("Before interrupted write")))
        val parent = cache.entryFile("book").parentFile!!
        val abandonedWrite = File(parent, "frb-write-interrupted").apply { assertTrue(mkdir()) }
        val abandonedRead = File(parent, "frb-read-interrupted").apply { assertTrue(mkdir()) }
        // Sparse files reproduce quota accounting without allocating/writing a large buffer.
        RandomAccessFile(File(abandonedWrite, "block.json"), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        File(abandonedWrite, "pending.frb").writeText("Interrupted and incomplete")
        File(abandonedRead, "block.json").writeText("Interrupted decoded chapter")

        assertTrue("Abandoned temporary files must be retired before computing available bytes",
            cache.save("book", source, content("New complete content")))
        assertFalse(abandonedWrite.exists())
        assertFalse(abandonedRead.exists())
        val restored = requireNotNull(cache.load("book", source))
        assertEquals("New complete content", (restored.chapters.single().elements.single() as ContentElement.Paragraph).text.text)
    }

    @Test fun `retiring abandoned staging directories never follows a link to source data`() {
        val originals = temporary.newFolder("originals")
        val source = File(originals, "source.epub").apply { writeText("Preserve this original book") }
        val cache = ChunkedContentCache(temporary.newFolder("cache"))
        val parent = cache.entryFile("book").parentFile!!.apply { assertTrue(mkdirs()) }
        val abandoned = File(parent, "frb-write-interrupted").apply { assertTrue(mkdir()) }
        Files.createSymbolicLink(File(abandoned, "originals-link").toPath(), originals.toPath())

        assertTrue(cache.save("book", source, content("Complete content")))
        assertFalse(abandoned.exists())
        assertTrue(source.isFile)
        assertEquals("Preserve this original book", source.readText())
    }

    private fun content(text: String) = BookContent(listOf(Chapter("Chapter",
        listOf(ContentElement.Paragraph(AnnotatedString(text))))))
}
