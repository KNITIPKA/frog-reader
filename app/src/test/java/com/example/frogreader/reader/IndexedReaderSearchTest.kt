package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.IndexedContentSession
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.SearchResults
import com.example.frogreader.ui.reader.adjustReaderElement
import com.example.frogreader.ui.reader.searchBook
import com.example.frogreader.ui.reader.searchIndexedBook
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexedReaderSearchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `indexed search preserves complete rich-text results and global cover anchors`() = runBlocking {
        val chapters = listOf(
            Chapter("Empty", emptyList()),
            Chapter("Start", listOf(paragraph("One needle here and NEEDLE there"),
                ContentElement.Heading("Needle heading", 2))),
            Chapter("Empty middle", emptyList()),
            Chapter("Last", listOf(ContentElement.Table(listOf(TableRow(listOf(
                TableCell(AnnotatedString("A needle in a table")),
            ), isHeader = false))), paragraph("The final needle destination")), depth = 2),
        )
        val content = BookContent(chapters,
            notes = mapOf("note" to NoteDocument(listOf(paragraph("needle in excluded notes")))),
            linkedDocuments = mapOf("appendix" to LinkedDocument("appendix", "Appendix",
                listOf(paragraph("needle in excluded non-linear document")))))
        val fixture = fixture(content)
        // Whole-book search must not decode notes/linked documents merely
        // because they contain text; a corrupt heavy block is unrequested.
        corruptPayload(fixture.cache.entryFile("book"), 1)
        session(fixture).use { session ->
            val actual = searchIndexedBook(session, "needle", coverCount = 1, hideFootnotes = false)
            val expected = searchBook(ReaderChapterItems(chapters,
                ReaderItem(0, ContentElement.Image("/cover.png"))) { adjustReaderElement(it, false) }, "needle")
            assertResults(expected, actual)
            assertEquals(5, actual.totalCount)
            assertEquals(listOf(1, 1, 2, 3, 4), actual.matches.map { it.itemIndex })
            assertEquals(listOf(1, 1, 1, 3, 3), actual.matches.map { it.chapterIndex })
            assertTrue(actual.matches.last().before.contains("final"))
        }
    }

    @Test fun `only bounded result rows are retained while every later occurrence is counted`() = runBlocking {
        val content = BookContent(List(7) { chapter -> Chapter("Chapter $chapter",
            List(60) { paragraph("needle needle at $chapter:$it") }) })
        val fixture = fixture(content)
        session(fixture).use { session ->
            val first = searchIndexedBook(session, "needle", 0, false, limit = 3)
            assertEquals(840, first.totalCount)
            assertEquals(3, first.matches.size)
            assertTrue(first.truncated)
            assertResults(searchBook(ReaderChapterItems(content.chapters), "needle", 3), first)
            val capped = searchIndexedBook(session, "needle", 0, false, limit = 10_000)
            assertEquals(840, capped.totalCount)
            assertEquals(300, capped.matches.size)
            val countOnly = searchIndexedBook(session, "needle", 0, false, limit = 0)
            assertEquals(840, countOnly.totalCount)
            assertTrue(countOnly.matches.isEmpty())
            assertTrue(countOnly.truncated)
        }
    }

    @Test fun `hidden footnote markers use the same transformed offsets and snippets as rendered text`() = runBlocking {
        val annotated = buildAnnotatedString {
            append("Prefix ")
            pushStringAnnotation(FOOTNOTE_TAG, "note")
            append("needle")
            pop()
            append(" then body needle")
        }
        val content = BookContent(listOf(Chapter("Body", listOf(ContentElement.Paragraph(annotated)))))
        val fixture = fixture(content)
        session(fixture).use { session ->
            for (hide in listOf(false, true)) {
                val actual = searchIndexedBook(session, "needle", 0, hide)
                val expected = searchBook(ReaderChapterItems(content.chapters) { adjustReaderElement(it, hide) }, "needle")
                assertResults(expected, actual)
                assertEquals(if (hide) 1 else 2, actual.totalCount)
            }
        }
    }

    @Test fun `a corrupt chapter fails instead of publishing an incomplete complete-book result`() = runBlocking {
        val fixture = fixture(BookContent(List(3) { Chapter("Chapter $it", listOf(paragraph("needle $it"))) }))
        corruptPayload(fixture.cache.entryFile("book"), 3) // Chapter1 follows the two prefix blocks.
        session(fixture).use { session ->
            val failure = runCatching { searchIndexedBook(session, "needle", 0, false) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(requireNotNull(failure).message!!.contains("chapter 1"))
        }
    }

    @Test fun `cancelled search does not read later chapters or return partial rows`() = runBlocking {
        val fixture = fixture(BookContent(List(5) { Chapter("Chapter $it", listOf(paragraph("needle $it"))) }))
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val delegate = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readChapter(index: Int): Chapter? {
                reads.incrementAndGet()
                val chapter = native.readChapter(index)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return chapter
            }
            override fun readGlobals(): BookContent? = error("Search must never read globals")
            override fun close() = native.close()
        }
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { delegate })
        try {
            val search = async(Dispatchers.Default) { searchIndexedBook(session, "needle", 0, false) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                search.cancel()
            } finally { release.countDown() }
            assertTrue(runCatching { search.await() }.exceptionOrNull() is CancellationException)
            search.join()
            assertEquals(1, reads.get())
        } finally {
            release.countDown()
            session.close()
        }
    }

    @Test fun `empty query returns immediately without needing a live source session`() = runBlocking {
        val fixture = fixture(BookContent(listOf(Chapter("Body", listOf(paragraph("needle"))))))
        val session = session(fixture)
        session.close()
        val empty = searchIndexedBook(session, "", 0, false)
        assertEquals("", empty.query)
        assertEquals(0, empty.totalCount)
        assertTrue(empty.matches.isEmpty())
        assertTrue(runCatching { searchIndexedBook(session, "needle", -1, false) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { searchIndexedBook(session, "needle", 0, false, -1) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `batched native handle search spans multiple batches without reading chapter buffers`() = runBlocking {
        val chapters = List(70) { ch ->
            if (ch % 10 == 0) {
                Chapter("Empty $ch", emptyList())
            } else {
                Chapter(
                    "Chapter $ch",
                    listOf(
                        paragraph("Chapter $ch needle and NEEDLE"),
                        ContentElement.Heading("Heading $ch needle", 2),
                    ),
                )
            }
        }
        val content = BookContent(chapters)
        val fixture = fixture(content)
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val handleCalls = AtomicInteger()
        val blockReads = AtomicInteger()
        val chapterReads = AtomicInteger()
        val spy = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readChapter(index: Int): Chapter? {
                chapterReads.incrementAndGet()
                return native.readChapter(index)
            }
            override fun readChapterBlockBytes(index: Int): ByteArray? {
                blockReads.incrementAndGet()
                return native.readChapterBlockBytes(index)
            }
            override fun <T> withNativeHandle(action: (Long) -> T): T? {
                handleCalls.incrementAndGet()
                return native.withNativeHandle(action)
            }
            override fun readGlobals(): BookContent? = error("Search must never read globals")
            override fun close() = native.close()
        }
        requireNotNull(IndexedBookSession.open(Closeable {}) { spy }).use { s ->
            val actual = searchIndexedBook(s, "needle", coverCount = 1, hideFootnotes = false, limit = 5)
            val expected = searchBook(
                ReaderChapterItems(chapters, ReaderItem(0, ContentElement.Image("/cover.png"))),
                "needle",
                limit = 5,
            )
            assertResults(expected, actual)
            if (RustBridge.shared.isLibraryReady) {
                // 70 chapters -> 3 batches of 32 (32 + 32 + 6), zero JVM chapter buffer copies.
                assertEquals(3, handleCalls.get())
                assertEquals(0, blockReads.get())
                assertEquals(0, chapterReads.get())
            }
        }
    }

    @Test fun `cancelled batched handle search stops before scanning remaining batches`() = runBlocking {
        val chapters = List(70) { ch -> Chapter("Chapter $ch", listOf(paragraph("needle $ch"))) }
        val fixture = fixture(BookContent(chapters))
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handleCalls = AtomicInteger()
        val chapterReads = AtomicInteger()
        val delegate = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readChapter(index: Int): Chapter? {
                chapterReads.incrementAndGet()
                val chapter = native.readChapter(index)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return chapter
            }
            override fun <T> withNativeHandle(action: (Long) -> T): T? {
                handleCalls.incrementAndGet()
                val result = native.withNativeHandle(action)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return result
            }
            override fun readGlobals(): BookContent? = error("Search must never read globals")
            override fun close() = native.close()
        }
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { delegate })
        try {
            val search = async(Dispatchers.Default) { searchIndexedBook(session, "needle", 0, false) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                search.cancel()
            } finally {
                release.countDown()
            }
            assertTrue(runCatching { search.await() }.exceptionOrNull() is CancellationException)
            search.join()
            if (RustBridge.shared.isLibraryReady) {
                assertEquals(1, handleCalls.get())
                assertEquals(0, chapterReads.get())
            } else {
                assertEquals(1, chapterReads.get())
            }
        } finally {
            release.countDown()
            session.close()
        }
    }

    private data class Fixture(val source: File, val cache: ChunkedContentCache)
    private fun fixture(content: BookContent): Fixture {
        val root = temporary.newFolder()
        val source = File(root, "source.epub").apply { writeText("Immutable search source") }
        val cache = ChunkedContentCache(File(root, "reader-content"))
        assertTrue(cache.save("book", source, content))
        return Fixture(source, cache)
    }
    private suspend fun session(fixture: Fixture) = requireNotNull(IndexedBookSession.open(Closeable {}) {
        fixture.cache.openIndexed("book", fixture.source)
    })
    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun assertResults(expected: SearchResults, actual: SearchResults) {
        assertEquals(expected.query, actual.query)
        assertEquals(expected.totalCount, actual.totalCount)
        assertEquals(expected.truncated, actual.truncated)
        fun rows(results: SearchResults) = results.matches.map {
            listOf(it.itemIndex, it.chapterIndex, it.charStart, it.before, it.match, it.after)
        }
        assertEquals(rows(expected), rows(actual))
    }
    private fun corruptPayload(file: File, block: Int) {
        RandomAccessFile(file, "rw").use { bytes ->
            val header = ByteArray(64).also(bytes::readFully)
            val metadataLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            bytes.seek(64L + metadataLength + block * 48L)
            val entry = ByteArray(48).also(bytes::readFully)
            val offset = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            bytes.seek(offset)
            val original = bytes.readUnsignedByte()
            bytes.seek(offset)
            bytes.writeByte(original xor 0xff)
        }
    }
}
