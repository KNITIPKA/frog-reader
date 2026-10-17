package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ReadingProgress
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class BookRepositoryProgressiveContentTest {
    private val directory = File("build/tmp/test_files")
    private val book = Book(
        id = "progressive", title = "Long book", author = "Author", format = BookFormat.EPUB,
        fileName = "progressive.epub", coverFileName = "cover.png",
        addedAtMillis = 1L, progress = ReadingProgress(elementIndex = 70),
    )
    private val preview = content("Opening")
    private val complete = content("Opening", "Remaining")

    @Before fun setup() {
        directory.mkdirs()
        StoreFixture.clear(directory)
        StoreFixture.seed(directory, listOf(book))
        File(directory, "books").apply { mkdirs() }
        File(directory, "books/${book.fileName}").writeText("Parser test source")
        File(directory, "covers").apply { mkdirs() }
        File(directory, "covers/cover.png").writeBytes(byteArrayOf(1))
    }

    @After fun cleanup() {
        StoreFixture.clear(directory)
        File(directory, "books").deleteRecursively()
        File(directory, "covers").deleteRecursively()
    }

    @Test fun `reader receives prefix while ordinary load still waits for the same complete parse`() = runTest {
        val repository = BookRepository(null)
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val previewReceived = CompletableDeferred<BookContent>()
        repository.contentParserForTests = { _, _, _, sourceIndex, publish ->
            calls.incrementAndGet()
            assertEquals("The reader's cover is outside parser coordinates", 69, sourceIndex)
            publish(preview)
            check(release.await(10, TimeUnit.SECONDS))
            complete
        }
        val progressive = async(Dispatchers.Default) {
            repository.loadContentProgressively(book) { previewReceived.complete(it) }
        }
        val ordinary = async(Dispatchers.Default) { repository.loadContent(book) }
        try {
            assertSame(preview, previewReceived.await())
            assertFalse(progressive.isCompleted)
            assertFalse(ordinary.isCompleted)
        } finally {
            release.countDown()
        }
        assertSame(complete, progressive.await())
        assertSame(complete, ordinary.await())
        assertSame(complete, repository.loadContent(book))
        var cachedPreviewCalls = 0
        assertSame(complete, repository.loadContentProgressively(book) { cachedPreviewCalls++ })
        assertEquals(0, cachedPreviewCalls)
        assertEquals(1, calls.get())
    }

    @Test fun `late reader replays preload prefix and cancellation never starts a duplicate parse`() = runTest {
        val repository = BookRepository(null)
        val calls = AtomicInteger()
        val published = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        repository.contentParserForTests = { _, _, _, _, publish ->
            calls.incrementAndGet()
            publish(preview)
            published.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            complete
        }
        val preload = async(Dispatchers.Default) { repository.loadContent(book) }
        published.await()
        val received = CompletableDeferred<BookContent>()
        val reader = async(Dispatchers.Default) {
            repository.loadContentProgressively(book) { received.complete(it) }
        }
        try {
            assertSame(preview, received.await())
            reader.cancelAndJoin()
            preload.cancelAndJoin()
            val nextReader = async(start = CoroutineStart.UNDISPATCHED) { repository.loadContent(book) }
            release.countDown()
            assertSame(complete, nextReader.await())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            reader.cancelAndJoin()
            preload.cancelAndJoin()
        }
    }

    @Test fun `replaced source cannot publish its old prefix or populate the final cache`() = runTest {
        val currentBooks = MutableStateFlow(listOf(book))
        val repository = object : BookRepository(null) {
            override val books = currentBooks
        }
        val parsing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        repository.contentParserForTests = { _, _, _, _, publish ->
            if (calls.incrementAndGet() == 1) {
                parsing.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                publish(preview)
            }
            complete
        }
        val previews = AtomicInteger()
        val old = async(Dispatchers.Default) {
            repository.loadContentProgressively(book) { previews.incrementAndGet() }
        }
        try {
            parsing.await()
            val replacement = book.copy(fileName = "replacement.epub")
            File(directory, "books/replacement.epub").writeText("Replacement")
            currentBooks.value = listOf(replacement)
            release.countDown()
            old.await()
            assertEquals(0, previews.get())
            // Restoring the original identity must reparse it: the obsolete
            // in-flight result was never admitted to the RAM cache.
            currentBooks.value = listOf(book)
            repository.loadContent(book)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            old.cancelAndJoin()
        }
    }

    @Test fun `progressive API honors existing load overrides for EPUB and other formats`() = runTest {
        val calls = AtomicInteger()
        val repository = object : BookRepository(null) {
            override suspend fun loadContent(book: Book): BookContent {
                calls.incrementAndGet()
                return complete
            }
        }
        var previewCalls = 0
        assertSame(complete, repository.loadContentProgressively(book) { previewCalls++ })
        assertSame(complete, repository.loadContentProgressively(book.copy(format = BookFormat.FB2)) { previewCalls++ })
        assertEquals(2, calls.get())
        assertEquals(0, previewCalls)
    }

    private fun content(vararg text: String) = BookContent(listOf(Chapter(
        title = "Chapter", elements = text.map { ContentElement.Paragraph(AnnotatedString(it)) },
    )))
}
