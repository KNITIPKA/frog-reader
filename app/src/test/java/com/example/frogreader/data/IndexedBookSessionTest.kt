package com.example.frogreader.data

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

class IndexedBookSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `late window does not read a corrupt earlier chapter or heavy globals`() = runBlocking {
        val fixture = fixture(List(12) { chapter(it, 2) })
        // Both prefix blocks precede chapter blocks. Break the globals and
        // first chapter payloads without changing the checked native index.
        corruptPayload(fixture.cache.entryFile("book"), 1)
        corruptPayload(fixture.cache.entryFile("book"), 2)
        val resources = AtomicInteger()
        val session = requireNotNull(IndexedBookSession.open(Closeable { resources.incrementAndGet() }) {
            fixture.cache.openIndexed("book", fixture.source)
        })
        try {
            val window = requireNotNull(session.loadWindow(21))
            assertEquals(9, window.firstChapterIndex)
            assertEquals(listOf("Chapter 9", "Chapter 10", "Chapter 11"), window.chapters.map { it.title })
            assertEquals(18, window.firstElement)
            assertEquals(24, window.totalElements)
            assertNull(session.readChapter(0))
            assertNull(session.readGlobals())
            assertEquals("Chapter 10", session.readChapter(10)?.title)
            assertEquals(0, resources.get())
        } finally { session.close() }
        session.close()
        assertEquals(1, resources.get())
        assertNull(session.readChapter(10))
        assertNull(session.readGlobals())
        assertNull(session.loadWindow(21))
    }

    @Test fun `window maps empty chapters and book boundaries without prefix traversal`() = runBlocking {
        val fixture = fixture(listOf(chapter(0, 0), chapter(1, 2), chapter(2, 0), chapter(3, 3), chapter(4, 0)))
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) {
            fixture.cache.openIndexed("book", fixture.source)
        })
        session.use {
            val first = requireNotNull(it.loadWindow(0, neighbors = 0))
            assertEquals(1, first.firstChapterIndex)
            assertEquals(0, first.firstElement)
            assertEquals(listOf("Chapter 1"), first.chapters.map { chapter -> chapter.title })
            val last = requireNotNull(it.loadWindow(4))
            assertEquals(2, last.firstChapterIndex)
            assertEquals(2, last.firstElement)
            assertEquals(listOf("Chapter 2", "Chapter 3", "Chapter 4"), last.chapters.map { chapter -> chapter.title })
            assertNull(it.loadWindow(-1))
            assertNull(it.loadWindow(5))
            assertTrue(runCatching { it.loadWindow(0, neighbors = 2) }.exceptionOrNull() is IllegalArgumentException)
        }
        val empty = fixture(emptyList())
        requireNotNull(IndexedBookSession.open(Closeable {}) {
            empty.cache.openIndexed("book", empty.source)
        }).use {
            assertEquals(0, it.index.totalElements)
            assertNull(it.loadWindow(0))
        }
    }

    @Test fun `cancellation while native open finishes closes undelivered handle and pin`() = runBlocking {
        val fixture = fixture(listOf(chapter(0, 3)))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val resources = AtomicInteger()
        var native: IndexedContentSession? = null
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val opening = async(Dispatchers.Default) {
                IndexedBookSession.open(Closeable { resources.incrementAndGet() }, dispatcher) {
                    fixture.cache.openIndexed("book", fixture.source).also {
                        native = it
                        started.countDown()
                        check(finish.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                assertNotNull(native)
                opening.cancel()
            } finally { finish.countDown() }
            assertTrue(runCatching { opening.await() }.exceptionOrNull() is CancellationException)
            opening.join()
        }
        assertEquals(1, resources.get())
        assertNull(requireNotNull(native).readChapter(0))
    }

    @Test fun `failed open releases resource pin and original failure survives failing cleanup`() = runBlocking {
        val resources = AtomicInteger()
        assertNull(IndexedBookSession.open(Closeable { resources.incrementAndGet() }) { null })
        assertEquals(1, resources.get())
        val original = IllegalStateException("Open failed")
        val cleanup = IllegalStateException("Cleanup failed")
        val failure = runCatching {
            IndexedBookSession.open(Closeable { throw cleanup }) { throw original }
        }.exceptionOrNull()
        // Coroutine stack recovery may copy exceptions across dispatcher
        // boundaries, preserving the thrown instance as the copy's cause.
        val primaryChain = generateSequence(requireNotNull(failure)) { it.cause }.toList()
        assertEquals(original.javaClass, failure.javaClass)
        assertEquals(original.message, failure.message)
        assertTrue(primaryChain.any { it === original })
        val suppressed = primaryChain.flatMap { it.suppressed.toList() }
        assertTrue(suppressed.any { error ->
            generateSequence(error) { it.cause }.any { it === cleanup }
        })
    }

    @Test fun `cancelled window stops before decoding its remaining neighbors`() = runBlocking {
        val fixture = fixture(List(5) { chapter(it, 2) })
        val firstRead = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val reads = AtomicInteger()
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val traced = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readGlobals() = native.readGlobals()
            override fun close() = native.close()
            override fun readChapter(index: Int): Chapter? {
                val chapter = native.readChapter(index)
                if (reads.incrementAndGet() == 1) {
                    firstRead.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                }
                return chapter
            }
        }
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { traced })
        try {
            val loading = async(Dispatchers.Default) { session.loadWindow(5) }
            try {
                assertTrue(firstRead.await(10, TimeUnit.SECONDS))
                loading.cancel()
            } finally { resume.countDown() }
            assertTrue(runCatching { loading.await() }.exceptionOrNull() is CancellationException)
            loading.join()
            assertEquals(1, reads.get())
        } finally { resume.countDown(); session.close() }
    }

    @Test fun `repository opens an indexed cache without restoring the complete model`() = runBlocking {
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val book = Book(id = "book", title = "Indexed book", format = BookFormat.EPUB,
            fileName = "source.epub", addedAtMillis = 1L)
        StoreFixture.seed(temporary.root, listOf(book))
        val source = File(temporary.root, "books/source.epub").apply { parentFile!!.mkdirs(); writeText("Source") }
        val cache = ChunkedContentCache(File(temporary.root, "reader-content"))
        assertTrue(cache.save(book.id, source, BookContent(listOf(chapter(0, 3)))))
        val repository = BookRepository(context)
        val session = requireNotNull(repository.openIndexedContent(requireNotNull(repository.bookById(book.id))))
        assertEquals(3, session.index.totalElements)
        assertTrue(cache.entryFile(book.id).isFile)
        assertEquals("Chapter 0", session.readChapter(0)?.title)
        session.close()
        assertNull(session.readChapter(0))
        assertTrue(source.isFile)
    }

    @Test fun `live session pin prevents eviction until native close`() = runBlocking {
        val fixture = fixture(listOf(chapter(0, 3)))
        val budget = ReaderCacheBudget(fixture.source.parentFile!!)
        val image = File(fixture.source.parentFile, "images/book/extracted-image").apply {
            parentFile!!.mkdirs()
            writeText("Extracted resource")
        }
        val session = requireNotNull(IndexedBookSession.open(budget.pin("book")) {
            fixture.cache.openIndexed("book", fixture.source)
        })
        assertFalse(budget.remove("book"))
        assertTrue(image.exists())
        session.close()
        assertTrue(budget.remove("book"))
        assertFalse(image.exists())
        assertTrue(fixture.source.exists())
    }

    @Test fun `concurrent readChapter and readChapterBlockBytes remain consistent across atomic FRB replacement and multi-book LRU trim`() = runBlocking {
        val root = temporary.newFolder()
        val source = File(root, "books/book.epub").apply {
            parentFile!!.mkdirs()
            writeText("Stable original book")
        }
        val cache = ChunkedContentCache(File(root, "reader-content"))
        val budget = ReaderCacheBudget(root)
        val gen1Chapters = List(8) { chapter(it, 4, prefix = "gen1") }
        assertTrue(cache.save("book", source, BookContent(gen1Chapters)))

        fun chapterSummary(chapter: Chapter): Pair<String?, List<String>> =
            chapter.title to chapter.elements.map { (it as ContentElement.Paragraph).text.text }

        val session1 = requireNotNull(IndexedBookSession.open(budget.pin("book")) {
            cache.openIndexed("book", source)
        })
        val gen1Key = session1.contentKey
        try {
            val startGate = CountDownLatch(1)
            val readers = List(4) { worker ->
                async(Dispatchers.Default) {
                    check(startGate.await(10, TimeUnit.SECONDS))
                    repeat(40) { step ->
                        val chapterIndex = (worker + step) % gen1Chapters.size
                        val expected = chapterSummary(gen1Chapters[chapterIndex])
                        val decodedChapter = requireNotNull(session1.readChapter(chapterIndex))
                        assertEquals(expected, chapterSummary(decodedChapter))
                        val blockBytes = requireNotNull(session1.readChapterBlockBytes(chapterIndex))
                        assertEquals(expected, chapterSummary(FrbModelCodec.decode(blockBytes, Chapter.serializer())))
                        val window = requireNotNull(session1.loadWindow(chapterIndex * 4 + 1))
                        assertTrue(window.chapters.any { chapterSummary(it) == expected })
                    }
                }
            }
            val writer = async(Dispatchers.IO) {
                check(startGate.await(10, TimeUnit.SECONDS))
                repeat(6) { round ->
                    val replacement = List(8 + (round % 3) * 2) { chapter(it, 4, prefix = "gen-${round + 2}") }
                    assertTrue(cache.save("book", source, BookContent(replacement)))
                }
            }
            startGate.countDown()
            writer.await()
            readers.forEach { it.await() }
            assertEquals(gen1Key, session1.contentKey)

            // Open the newly replaced generation on disk while session1 remains open on generation 1.
            val session2 = requireNotNull(IndexedBookSession.open(budget.pin("book")) {
                cache.openIndexed("book", source)
            })
            try {
                assertFalse(session1.contentKey == session2.contentKey)
                val gen1Chapter7 = chapterSummary(gen1Chapters[7])
                assertEquals(gen1Chapter7, chapterSummary(requireNotNull(session1.readChapter(7))))
                val session1Bytes = requireNotNull(session1.readChapterBlockBytes(7))
                assertEquals(gen1Chapter7, chapterSummary(FrbModelCodec.decode(session1Bytes, Chapter.serializer())))

                val latestExpected = chapterSummary(chapter(7, 4, prefix = "gen-7"))
                assertEquals(latestExpected, chapterSummary(requireNotNull(session2.readChapter(7))))
                val session2Bytes = requireNotNull(session2.readChapterBlockBytes(7))
                assertEquals(latestExpected, chapterSummary(FrbModelCodec.decode(session2Bytes, Chapter.serializer())))
            } finally {
                session2.close()
            }

            // Exceed 64 MiB parsed and 128 MiB total budgets across multiple unpinned books while session1 stays pinned.
            val mib = 1024L * 1024L
            fun allocate(path: String, bytes: Long) = File(root, path).apply {
                parentFile!!.mkdirs()
                RandomAccessFile(this, "rw").use { it.setLength(bytes) }
            }
            val oldA = allocate("reader-content/old-a/content.frb", 36 * mib)
            val oldAImg = allocate("images/old-a/source/img.jpg", 36 * mib)
            val oldB = allocate("reader-content/old-b/content.frb", 36 * mib)
            val oldBImg = allocate("images/old-b/source/img.jpg", 36 * mib)
            val trimmed = budget.trim(setOf("book", "old-a", "old-b"))
            assertTrue("At least one unpinned large book must be evicted", trimmed.evictedBookIds.isNotEmpty())
            assertFalse("The pinned active book must not be evicted", "book" in trimmed.evictedBookIds)
            assertTrue(cache.entryFile("book").isFile)
            assertTrue(!oldA.exists() || !oldB.exists())
            assertTrue(!oldAImg.exists() || !oldBImg.exists())
            assertEquals(chapterSummary(gen1Chapters[3]), chapterSummary(requireNotNull(session1.readChapter(3))))
        } finally {
            session1.close()
        }
    }

    @Test fun `readChapter decodes block bytes without calling native readChapter and falls back when raw bytes are unavailable`() = runBlocking {
        val fixture = fixture(List(4) { chapter(it, 3) })
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val blockReads = AtomicInteger()
        val fallbackReads = AtomicInteger()
        var returnRawBytes = true
        val wrapped = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readGlobals() = native.readGlobals()
            override fun close() = native.close()
            override fun readChapterBlockBytes(index: Int): ByteArray? {
                blockReads.incrementAndGet()
                return if (returnRawBytes) native.readChapterBlockBytes(index) else null
            }
            override fun readChapter(index: Int): Chapter? {
                fallbackReads.incrementAndGet()
                return native.readChapter(index)
            }
        }
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { wrapped })
        try {
            val decoded = requireNotNull(session.readChapter(2))
            assertEquals("Chapter 2", decoded.title)
            assertEquals(3, decoded.elements.size)
            assertEquals(1, blockReads.get())
            assertEquals(0, fallbackReads.get())

            returnRawBytes = false
            val fallback = requireNotNull(session.readChapter(1))
            assertEquals("Chapter 1", fallback.title)
            assertEquals(2, blockReads.get())
            assertEquals(1, fallbackReads.get())
        } finally {
            session.close()
        }
    }

    @Test fun `readChapter rejects corrupt or mismatched block bytes outside the session lock`() = runBlocking {
        val fixture = fixture(listOf(chapter(0, 2), chapter(1, 3)))
        val native = requireNotNull(fixture.cache.openIndexed("book", fixture.source))
        val wrapped = object : IndexedContentSession {
            override val index = native.index
            override val contentKey = native.contentKey
            override fun readGlobals() = native.readGlobals()
            override fun close() = native.close()
            override fun readChapterBlockBytes(index: Int): ByteArray = when (index) {
                0 -> byteArrayOf(0x7f, 0x01, 0x02) // corrupt payload
                1 -> requireNotNull(native.readChapterBlockBytes(0)) // mismatched chapter metadata
                else -> error("Unexpected index $index")
            }
            override fun readChapter(index: Int): Chapter? = error("Should not fall back when raw bytes are returned")
        }
        val session = requireNotNull(IndexedBookSession.open(Closeable {}) { wrapped })
        try {
            assertNull(session.readChapter(0))
            assertNull(session.readChapter(1))
        } finally {
            session.close()
        }
    }

    private data class Fixture(val source: File, val cache: ChunkedContentCache)

    private fun fixture(chapters: List<Chapter>): Fixture {
        val root = temporary.newFolder()
        val source = File(root, "source.epub").apply { writeText("Stable original book") }
        val cache = ChunkedContentCache(File(root, "cache"))
        assertTrue(cache.save("book", source, BookContent(chapters)))
        return Fixture(source, cache)
    }

    private fun chapter(number: Int, elements: Int, prefix: String = "") = Chapter("Chapter $number", List(elements) {
        val tag = if (prefix.isEmpty()) "" else "$prefix "
        ContentElement.Paragraph(AnnotatedString("${tag}Chapter $number paragraph $it"))
    })

    private fun corruptPayload(file: File, blockIndex: Int) {
        RandomAccessFile(file, "rw").use { bytes ->
            val header = ByteArray(64).also(bytes::readFully)
            val metadataLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            bytes.seek(64L + metadataLength + blockIndex * 48L)
            val entry = ByteArray(48).also(bytes::readFully)
            val offset = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            bytes.seek(offset)
            val previous = bytes.readUnsignedByte()
            bytes.seek(offset)
            bytes.writeByte(previous xor 0xff)
        }
    }
}
