package com.example.frogreader.data

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

/** Real parser, codec and repository paths; no parser hook can bypass the disk cache. */
class BookRepositoryDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val book = Book(
        id = "cached-book", title = "Cache fixture", format = BookFormat.EPUB,
        fileName = "cached-book.epub", addedAtMillis = 1L,
        status = ReadingStatus.READING, startedAtMillis = 1L, activeReadingLogId = "reading-cached-book",
        progress = ReadingProgress(elementIndex = 17, scrollOffset = 23, fraction = 0.2f),
    )
    private val source: File get() = File(temporary.root, "books/${book.fileName}")
    private val cache: ParsedContentCache get() = ParsedContentCache(File(temporary.root, "reader-content"))

    @Before fun setup() {
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        StoreFixture.seed(temporary.root, listOf(book))
    }

    @Test fun `fresh repository restores decoded disk content without changing saved reading position`() = runTest {
        writeEpub("Before")
        val firstRepository = BookRepository(context)
        val parsed = firstRepository.loadContent(book)
        firstRepository.awaitContentCacheWrites()
        firstRepository.releaseContentCache()
        assertEquals("Before 0.", paragraphs(parsed).first())
        assertTrue(cache.entryFile(book.id).isFile)

        // A recognizable decoded snapshot proves this open uses the disk
        // model rather than obtaining equal text by parsing the EPUB again.
        val decoded = BookContent(listOf(Chapter("Cached", List(80) { index ->
            ContentElement.Paragraph(AnnotatedString("Decoded $index."))
        })))
        assertTrue(cache.save(book.id, source, decoded))
        val freshRepository = BookRepository(context)
        val currentBook = freshRepository.bookById(book.id)!!
        assertEquals(book.progress, currentBook.progress)
        val restored = freshRepository.loadContent(currentBook)
        assertEquals("Decoded 17.", paragraphs(restored)[currentBook.progress.elementIndex])
        assertEquals(book.progress, freshRepository.bookById(book.id)!!.progress)
        assertEquals(book.progress, StoreFixture.booksOnDisk(temporary.root).single().progress)
        freshRepository.releaseContentCache()
    }

    @Test fun `corrupt snapshots fall back to the source and rebuild a usable cache`() = runTest {
        writeEpub("Before")
        val first = BookRepository(context)
        first.loadContent(book)
        first.awaitContentCacheWrites()
        first.releaseContentCache()
        for (complete in listOf(false, true)) {
            cache.entryFile(book.id, complete).takeIf { it.exists() }?.writeBytes(byteArrayOf(1, 2, 3))
        }

        val fresh = BookRepository(context)
        assertEquals("Before 0.", paragraphs(fresh.loadContent(book)).first())
        fresh.awaitContentCacheWrites()
        assertTrue(cache.load(book.id, source)!!.complete)
        fresh.releaseContentCache()
    }

    @Test fun `source replacement with unchanged length and timestamp cannot restore stale text`() = runTest {
        writeEpub("Before")
        val first = BookRepository(context)
        first.loadContent(book)
        first.awaitContentCacheWrites()
        first.releaseContentCache()
        val originalLength = source.length()
        val originalModified = source.lastModified()
        writeEpub("After_")
        assertEquals(originalLength, source.length())
        assertTrue(source.setLastModified(originalModified))

        val fresh = BookRepository(context)
        assertEquals("After_ 0.", paragraphs(fresh.loadContent(book)).first())
        fresh.awaitContentCacheWrites()
        assertEquals("After_ 0.", paragraphs(cache.load(book.id, source)!!.content).first())
        fresh.releaseContentCache()
    }

    @Test fun `evicting derived text and images rebuilds both while original and user data survive`() = runTest {
        writeEpub("Before", image = true)
        val first = BookRepository(context)
        val parsed = first.loadContent(book)
        first.awaitContentCacheWrites()
        val imagePath = parsed.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Image>().single().path
        assertTrue(File(imagePath).isFile)
        val originalBytes = source.readBytes()
        val storeBytes = StoreFixture.files(temporary.root).filter { it.isFile }.associate { it.name to it.readBytes() }
        first.releaseContentCache()

        val result = ReaderCacheBudget(
            temporary.root, ReaderCacheBudget.Limits(totalBytes = 0, contentBytes = 0, contentEntryBytes = 0),
        ).trim(setOf(book.id))
        assertTrue(book.id in result.evictedBookIds)
        assertFalse(File(imagePath).exists())
        assertFalse(cache.entryFile(book.id).exists())
        assertArrayEquals(originalBytes, source.readBytes())
        storeBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, File(temporary.root, name).readBytes()) }

        val fresh = BookRepository(context)
        val reparsed = fresh.loadContent(book)
        assertEquals("Before 0.", paragraphs(reparsed).first())
        assertTrue(File(imagePath).isFile)
        fresh.awaitContentCacheWrites()
        assertTrue(cache.load(book.id, source)!!.complete)
        fresh.releaseContentCache()
    }

    @Test fun `a cached model with a missing image reparses and repairs its extracted resource`() = runTest {
        writeEpub("Before", image = true)
        val first = BookRepository(context)
        val content = first.loadContent(book)
        first.awaitContentCacheWrites()
        first.releaseContentCache()
        val imagePath = content.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Image>().single().path
        assertTrue(File(imagePath).delete())

        val fresh = BookRepository(context)
        val restored = fresh.loadContent(book)
        assertEquals("Before 0.", paragraphs(restored).first())
        assertTrue(File(imagePath).isFile)
        fresh.awaitContentCacheWrites()
        assertTrue(cache.load(book.id, source)!!.complete)
        fresh.releaseContentCache()
    }

    private fun paragraphs(content: BookContent): List<String> = content.chapters.flatMap { it.elements }
        .filterIsInstance<ContentElement.Paragraph>().map { it.text.text }

    private fun writeEpub(prefix: String, image: Boolean = false) {
        source.parentFile!!.mkdirs()
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply {
                    // Stored entries make same-length source mutations truly
                    // same-length ZIPs; their CRC/digest still changes.
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                    time = 0L
                })
                zip.write(bytes)
                zip.closeEntry()
            }
            fun text(path: String, value: String) = entry(path, value.toByteArray())
            text("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            text("OPS/content.opf", """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                <metadata><dc:title>Cache fixture</dc:title><dc:language>en</dc:language></metadata>
                <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                  <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                  <item id="last" href="last.xhtml" media-type="application/xhtml+xml"/>
                  ${if (image) """<item id="image" href="image.png" media-type="image/png"/>""" else ""}
                </manifest><spine><itemref idref="first"/><itemref idref="last"/></spine></package>""")
            text("OPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="first.xhtml">First</a></li><li><a href="last.xhtml">Last</a></li></ol></nav></body></html>""")
            for ((file, start) in listOf("first.xhtml" to 0, "last.xhtml" to 40)) {
                val paragraphs = (start until start + 40).joinToString("") { "<p>$prefix $it.</p>" }
                val picture = if (image && start == 0) """<img src="image.png" width="1" height="1" alt="Fixture image"/>""" else ""
                text("OPS/$file", """<html xmlns="http://www.w3.org/1999/xhtml"><body>$paragraphs$picture</body></html>""")
            }
            if (image) entry("OPS/image.png", Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRZkAAAAASUVORK5CYII=",
            ))
        }
    }
}
