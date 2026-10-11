package com.example.frogreader.ui.library

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BookInfoReadingRemovalTest {
    @get:Rule val temp = TemporaryFolder()
    private val models = mutableListOf<BookInfoViewModel>()

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        Dispatchers.resetMain()
    }

    private fun repository(): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temp.root)
        `when`(context.cacheDir).thenReturn(temp.root)
        return BookRepository(context)
    }
    private suspend fun model(repository: BookRepository, readingId: String? = null, bookId: String = ""): BookInfoViewModel {
        val app = mock(FrogReaderApp::class.java)
        `when`(app.bookRepository).thenReturn(repository)
        `when`(app.cacheDir).thenReturn(temp.root)
        return BookInfoViewModel(app, bookId, readingId).also {
            models += it
            withTimeout(5000) { it.state.first { state -> !state.loading && (readingId == null || state.readingLogEntry?.id == readingId) } }
        }
    }
    private suspend fun removed(model: BookInfoViewModel) = withTimeout(5000) { model.state.first { it.readingRemoved && !it.removingReading } }

    @Test fun `manual detail removal is durable before its single acknowledgement and keeps other readings`() = runBlocking {
        val repo = repository()
        repo.saveReadingLogEntry(ReadingLogEntry("paper", "Paper", source = ReadingLogSource.PAPER, review = "Saved review"))
        repo.saveReadingLogEntry(ReadingLogEntry("other", "Other"))
        val original = repo.readingLog.value.first()
        val model = model(repo, "paper")
        assertTrue(model.state.value.logOnly)
        model.removeReading()
        model.removeReading()
        val completed = removed(model)
        assertFalse(completed.readingRemovalError)
        assertEquals(original, completed.removedReading!!.entry)
        assertEquals(0, completed.removedReading.index)
        assertNull(completed.removedReading.previousReading)
        assertEquals(listOf("other"), repository().readingLog.value.map { it.id })
        model.readingRemovalHandled()
        assertFalse(model.state.value.readingRemoved)
        assertNull(model.state.value.removedReading)
    }

    @Test fun `latest reading removal resets progress and timer preserves annotations and returns an undo receipt`() = runBlocking {
        val repo = repository()
        repo.addBookForRestore(Book("book", "Title", format = BookFormat.FB2, addedAtMillis = 1,
            readingQueueNote = "Keep my note", quotes = listOf(Quote("quote", "A passage", 0, 1, note = "Keep quote note")),
            bookmarks = listOf(Bookmark("bookmark", 4, 0, "A saved position", 1))))
        repo.markStarted("book")
        repo.saveProgress("book", ReadingProgress(fraction = .4f))
        repo.addReadingSeconds("book", 123)
        val original = repo.books.value.single()
        val reading = repo.readingLog.value.single()
        val model = model(repo, reading.id)
        model.removeReading()
        val receipt = removed(model).removedReading!!
        val reset = repository().books.value.single()
        assertEquals(ReadingProgress(), reset.progress)
        assertEquals(0L, reset.readingSeconds)
        assertNull(reset.activeReadingLogId)
        assertEquals(original.quotes, reset.quotes)
        assertEquals(original.bookmarks, reset.bookmarks)
        assertEquals(original.readingQueueNote, reset.readingQueueNote)
        assertNotNull(receipt.previousReading)
        assertTrue(repository().readingLog.value.isEmpty())
        repo.restoreReadingLogEntry(receipt.entry, receipt.index, receipt.previousReading)
        val restored = repository().books.value.single()
        assertEquals(original.progress, restored.progress)
        assertEquals(original.readingSeconds, restored.readingSeconds)
        assertEquals(reading.id, restored.activeReadingLogId)
        assertEquals(original.quotes, restored.quotes)
    }

    @Test fun `historical detail removal preserves the current reading and its progress`() = runBlocking {
        val repo = repository()
        repo.addBookForRestore(Book("book", "Title", format = BookFormat.EPUB, addedAtMillis = 1))
        repo.markStarted("book")
        repo.markFinished("book")
        val old = repo.readingLog.value.single()
        repo.beginReadingAgain("book", true)
        repo.saveProgress("book", ReadingProgress(fraction = .6f))
        repo.addReadingSeconds("book", 45)
        val current = repo.books.value.single()
        val model = model(repo, old.id)
        model.removeReading()
        assertNull(removed(model).removedReading!!.previousReading)
        assertEquals(current, repository().books.value.single())
        assertEquals(listOf(current.activeReadingLogId), repository().readingLog.value.map { it.id })
    }

    @Test fun `failed removal stays on the record without a completion receipt and retry succeeds`() = runBlocking {
        val repo = repository()
        repo.saveReadingLogEntry(ReadingLogEntry("paper", "Paper", review = "Irreplaceable review"))
        val original = repo.readingLog.value.single()
        val model = model(repo, "paper")
        val blockedTemp = File(temp.root, "userdata.json.tmp").apply { mkdirs() }
        File(blockedTemp, "block-write").writeText("block")
        try {
            model.removeReading()
            val failed = withTimeout(5000) { model.state.first { it.readingRemovalError && !it.removingReading } }
            assertFalse(failed.readingRemoved)
            assertNull(failed.removedReading)
            assertEquals(original, failed.readingLogEntry)
            assertEquals(listOf(original), repository().readingLog.value)
        } finally { blockedTemp.deleteRecursively() }
        model.removeReading()
        val completed = removed(model)
        assertFalse(completed.readingRemovalError)
        assertEquals(original, completed.removedReading!!.entry)
        assertTrue(repository().readingLog.value.isEmpty())
    }

    @Test fun `generic book info cannot remove its reading without an explicit Log context`() = runBlocking {
        val repo = repository()
        repo.addBookForRestore(Book("book", "Title", format = BookFormat.EPUB, addedAtMillis = 1))
        repo.markStarted("book")
        val original = repo.readingLog.value
        val model = model(repo, bookId = "book")
        model.removeReading()
        assertFalse(model.state.value.removingReading)
        assertFalse(model.state.value.readingRemoved)
        assertEquals(original, repository().readingLog.value)
    }
}
