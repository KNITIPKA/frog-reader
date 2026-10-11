package com.example.frogreader.reader

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.ReaderViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRereadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReaderViewModel>()
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        settings = mock(SettingsRepository::class.java)
        given(settings.settings).willReturn(MutableStateFlow(ReaderSettings()))
        given(settings.appSettings).willReturn(MutableStateFlow(AppSettings()))
    }

    @After fun cleanup() = runTest(dispatcher) {
        try {
            // Reader writes deliberately outlive cancellation. Keep Main and the
            // temporary store available until these IO continuations finish.
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun book(id: String = "book", status: ReadingStatus = ReadingStatus.FINISHED) = Book(
        id, "A completed book", author = "An Author", format = BookFormat.EPUB, addedAtMillis = 1,
        status = status, readingSeconds = 120, ratingHalfStars = 9, review = "The first reading",
        progress = ReadingProgress(elementIndex = 2, fraction = 1f),
    )

    private fun repository(beforeStart: suspend () -> Unit = {}): BookRepository = object : BookRepository(context) {
        override suspend fun loadContent(book: Book) = BookContent(listOf(Chapter("Chapter", List(3) {
            ContentElement.Paragraph(AnnotatedString("Page $it"))
        })))
        override suspend fun markStarted(bookId: String) {
            beforeStart()
            super.markStarted(bookId)
        }
        // Opening bookkeeping is exercised separately by the persistence tests.
        override fun noteOpened(bookId: String) = Unit
    }

    private suspend fun open(repo: BookRepository, id: String = "book"): ReaderViewModel {
        val model = ReaderViewModel(repo, settings, mock(StatsRepository::class.java), id, temporary.root)
        models += model
        model.state.first { it is ReaderState.Ready || it is ReaderState.Error }
        assertTrue(model.state.value is ReaderState.Ready)
        return model
    }

    @Test fun `completion review saves half stars and text together without altering reading dates or progress`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book().copy(ratingHalfStars = null, review = null)))
        val repo = repository()
        val model = open(repo)
        model.keepPreviousReading()
        val before = repo.bookById("book")!!
        model.setCompletionRating(9)
        model.setCompletionReview("  My review\nwith a second line  ")
        var exits = 0
        model.saveCompletionReview {
            assertEquals("My review\nwith a second line", BookRepository(context).bookById("book")!!.review)
            exits++
        }
        model.completionReview.first { it.savedGeneration == 1 }
        assertEquals(1, exits)
        assertFalse(model.completionReview.value.dirty)
        val reopened = BookRepository(context)
        val saved = reopened.bookById("book")!!
        assertEquals(9, saved.ratingHalfStars)
        assertNull(saved.rating)
        assertEquals("My review\nwith a second line", saved.review)
        assertEquals(before.progress, saved.progress)
        assertEquals(before.startedAtMillis, saved.startedAtMillis)
        assertEquals(before.finishedAtMillis, saved.finishedAtMillis)
        assertEquals(before.readingSeconds, saved.readingSeconds)
        assertEquals(9, reopened.readingLog.value.single().ratingHalfStars)
        assertEquals(saved.review, reopened.readingLog.value.single().review)
    }

    @Test fun `failed review save keeps the draft and retries without discarding the existing review`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val model = open(repo)
        model.keepPreviousReading()
        val original = repo.readingLog.value.single()
        model.setCompletionRating(7)
        model.setCompletionReview("Keep this draft")
        val blockedTemporaryFile = File(temporary.root, "userdata.json.tmp").apply { mkdir() }
        var exits = 0
        model.saveCompletionReview { exits++ }
        model.completionReview.first { it.error }
        assertEquals(0, exits)
        assertEquals(original, repo.readingLog.value.single())
        assertEquals(7, model.completionReview.value.draft.ratingHalfStars)
        assertEquals("Keep this draft", model.completionReview.value.draft.review)
        assertTrue(model.completionReview.value.dirty)
        assertTrue(blockedTemporaryFile.delete())
        model.saveCompletionReview { exits++ }
        model.completionReview.first { it.savedGeneration == 1 }
        assertEquals(1, exits)
        assertFalse(model.completionReview.value.error)
        assertEquals("Keep this draft", BookRepository(context).bookById("book")!!.review)
    }

    @Test fun `save and exit with an unchanged review exits without rewriting user data`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val model = open(repo)
        model.keepPreviousReading()
        val before = File(temporary.root, "userdata.json").readBytes()
        var exits = 0
        model.saveCompletionReview { exits++ }
        assertEquals(1, exits)
        assertArrayEquals(before, File(temporary.root, "userdata.json").readBytes())
    }

    @Test fun `exit without a rating or review waits for completion and saves the finished book and log`() = runTest(dispatcher) {
        assertCompletionExit(rating = null, review = "")
    }

    @Test fun `exit automatically saves a rating alone after pending completion`() = runTest(dispatcher) {
        assertCompletionExit(rating = 7, review = "")
    }

    @Test fun `exit automatically saves a review alone after pending completion`() = runTest(dispatcher) {
        assertCompletionExit(rating = null, review = "  An optional review  ")
    }

    private suspend fun assertCompletionExit(rating: Int?, review: String) {
        StoreFixture.seed(temporary.root, listOf(book(status = ReadingStatus.READING).copy(
            ratingHalfStars = null, review = null, startedAtMillis = 1,
        )))
        val start = CompletableDeferred<Unit>()
        val repo = repository { start.await() }
        val model = open(repo)
        model.observeCompletionPosition(lastPage = false, completion = true)
        model.setCompletionRating(rating)
        model.setCompletionReview(review)
        val exited = CompletableDeferred<Unit>()
        var exits = 0
        val onExit = {
            exits++
            exited.complete(Unit)
            Unit
        }
        model.saveCompletionReview(onExit)
        model.saveCompletionReview(onExit)
        assertEquals(0, exits)
        assertTrue(model.completionReview.value.saving)
        start.complete(Unit)
        exited.await()
        assertEquals(1, exits)
        val reopened = BookRepository(context)
        val saved = reopened.bookById("book")!!
        // Statistics count finishedAtMillis independently of the optional form.
        assertEquals(ReadingStatus.FINISHED, saved.status)
        assertNotNull(saved.finishedAtMillis)
        assertEquals(1f, saved.progress.fraction)
        assertEquals(rating, saved.effectiveRatingHalfStars)
        assertEquals(review.trim().ifBlank { null }, saved.review)
        val entry = reopened.readingLog.value.single()
        assertEquals(ReadingLogStatus.FINISHED, entry.status)
        assertNotNull(entry.finishedOn)
        assertEquals(rating, entry.ratingHalfStars)
        assertEquals(review.trim(), entry.review)
    }

    @Test fun `exit saves clearing an existing rating and review`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val model = open(repo)
        model.keepPreviousReading()
        model.setCompletionRating(null)
        model.setCompletionReview("")
        val exited = CompletableDeferred<Unit>()
        model.saveCompletionReview { exited.complete(Unit) }
        exited.await()
        val reopened = BookRepository(context)
        assertNull(reopened.bookById("book")!!.effectiveRatingHalfStars)
        assertNull(reopened.bookById("book")!!.review)
        assertNull(reopened.readingLog.value.single().ratingHalfStars)
        assertEquals("", reopened.readingLog.value.single().review)
        assertEquals(ReadingLogStatus.FINISHED, reopened.readingLog.value.single().status)
    }

    @Test fun `a completion form from an older reading cannot overwrite a new reading review`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val model = open(repo)
        model.keepPreviousReading()
        val previous = repo.readingLog.value.single()
        repo.beginReadingAgain("book", addToLog = true)
        repo.saveBookReview("book", 6, "New reading review")
        val current = repo.readingLog.value.last()
        model.setCompletionRating(10)
        model.setCompletionReview("Stale form")
        model.saveCompletionReview()
        model.completionReview.first { it.error }
        assertEquals(listOf(previous, current), BookRepository(context).readingLog.value)
        assertEquals("New reading review", repo.bookById("book")!!.review)
        assertEquals("Stale form", model.completionReview.value.draft.review)
    }

    @Test fun `finished and abandoned readings prompt but a last page position alone does not`() = runTest(dispatcher) {
        val books = listOf(ReadingStatus.NONE, ReadingStatus.READING, ReadingStatus.WANT_TO_READ,
            ReadingStatus.ABANDONED, ReadingStatus.FINISHED).map { book(it.name, it) } +
            book("timestamp", ReadingStatus.READING).copy(finishedAtMillis = 5)
        StoreFixture.seed(temporary.root, books)
        val repo = repository()
        books.forEach { book ->
            val model = open(repo, book.id)
            assertEquals(book.id, book.hasFinishedReading || book.status == ReadingStatus.ABANDONED, model.offerReread.value)
        }
    }

    @Test fun `browsing preserves the completed reading and does not restart the book`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val completed = repo.readingLog.value.single()
        val model = open(repo)
        assertTrue(model.offerReread.value)
        // The reader's first frame must not persist a different position while the choice is open.
        model.saveProgress(0, 0)
        assertEquals(2, repo.bookById("book")!!.progress.elementIndex)
        model.keepPreviousReading()
        model.setReadingActive(true)
        model.addReadingTime(600)
        model.markFinished()
        model.saveProgress(1, 0)
        repo.books.first { it.single().progress.elementIndex == 1 }
        assertFalse(model.offerReread.value)
        assertEquals(0, model.readingGeneration.value)
        assertEquals(120L, repo.bookById("book")!!.readingSeconds)
        assertNull(repo.bookById("book")!!.finishedAtMillis)
        assertEquals(listOf(completed), BookRepository(context).readingLog.value)
    }

    @Test fun `manual completion with unknown dates prompts and completed history stays untouched`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book(status = ReadingStatus.READING).copy(startedAtMillis = 1)))
        val repo = repository()
        val entry = repo.readingLog.value.single()
        repo.saveReadingLogEntry(entry.copy(status = ReadingLogStatus.FINISHED, startedOn = null, finishedOn = null))
        val completed = repo.readingLog.value.single()
        val model = open(repo)
        assertTrue(model.offerReread.value)
        model.keepPreviousReading()
        model.observeCompletionPosition(lastPage = false, completion = true)
        assertEquals(listOf(completed), BookRepository(context).readingLog.value)
    }

    @Test fun `rereading resets progress once and the next opening resumes the new log entry`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val previous = repo.readingLog.value.single()
        val model = open(repo)
        model.beginReadingAgain()
        model.beginReadingAgain()
        model.keepPreviousReading()
        assertTrue(model.offerReread.value)
        model.saveProgress(2, 50)
        model.observeVisiblePosition(2, 50, 30)
        model.readingGeneration.first { it == 1 }
        val fresh = repo.bookById("book")!!
        assertEquals(ReadingStatus.READING, fresh.status)
        assertEquals(ReadingProgress(), fresh.progress)
        assertEquals(0, model.currentFlatIndex)
        assertEquals(0, model.currentCharOffset)
        assertEquals(0L, fresh.readingSeconds)
        assertNull(fresh.finishedAtMillis)
        assertNull(fresh.effectiveRatingHalfStars)
        assertNull(fresh.review)
        assertNotNull(fresh.startedAtMillis)
        assertEquals(2, repo.readingLog.value.size)
        assertEquals(previous, repo.readingLog.value.first())
        assertEquals(ReadingLogStatus.IN_PROGRESS, repo.readingLog.value.last().status)
        assertEquals(fresh.activeReadingLogId, repo.readingLog.value.last().id)
        // Reader openings share the application's repository and its write lock.
        val reopened = open(repo)
        assertFalse(reopened.offerReread.value)
        // Completion writes to the new reading, retaining every field of the earlier one.
        repo.addReadingSeconds("book", 45)
        repo.markFinished("book")
        assertEquals(previous, BookRepository(context).readingLog.value.first())
        assertEquals(45L, repo.readingLog.value.last().readingSeconds)
        assertEquals(ReadingLogStatus.FINISHED, repo.readingLog.value.last().status)
    }

    @Test fun `failed reread save keeps the original reading and a retry creates just one new entry`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book()))
        val repo = repository()
        val completed = repo.readingLog.value.single()
        val model = open(repo)
        val blockedTemporaryFile = File(temporary.root, "userdata.json.tmp").apply { mkdir() }
        model.beginReadingAgain()
        model.rereadError.first { it }
        assertTrue(model.offerReread.value)
        assertEquals(ReadingStatus.FINISHED, repo.bookById("book")!!.status)
        assertEquals(listOf(completed), repo.readingLog.value)
        assertEquals(0, model.readingGeneration.value)
        assertTrue(blockedTemporaryFile.delete())
        model.beginReadingAgain()
        model.readingGeneration.first { it == 1 }
        assertFalse(model.rereadError.value)
        assertEquals(2, BookRepository(context).readingLog.value.size)
        assertEquals(completed, repo.readingLog.value.first())
    }

    @Test fun `an abandoned reading resumes in place without creating a new entry`() = runTest(dispatcher) {
        seedAbandoned()
        val repo = repository()
        val abandoned = repo.readingLog.value.single()
        val position = repo.bookById("book")!!.progress
        val model = open(repo)
        assertTrue(model.offerReread.value)
        model.resumeReading()
        model.resumeReading()
        model.readingGeneration.first { it == 1 }
        assertFalse(model.offerReread.value)
        assertEquals(2, model.currentFlatIndex)
        assertEquals(position, repo.bookById("book")!!.progress)
        assertEquals(ReadingStatus.READING, repo.bookById("book")!!.status)
        assertEquals(abandoned.id, repo.readingLog.value.single().id)
        assertEquals(120L, repo.readingLog.value.single().readingSeconds)
        model.addReadingTime(30)
        repo.readingLog.first { it.single().readingSeconds == 150L }
        assertFalse(open(repo).offerReread.value)
    }

    @Test fun `an abandoned reading can restart from zero while the abandoned history stays saved`() = runTest(dispatcher) {
        seedAbandoned()
        val repo = repository()
        val abandoned = repo.readingLog.value.single()
        val model = open(repo)
        model.beginReadingAgain()
        model.readingGeneration.first { it == 1 }
        assertEquals(0, model.currentFlatIndex)
        assertEquals(ReadingProgress(), repo.bookById("book")!!.progress)
        assertEquals(abandoned, repo.readingLog.value.first())
        assertEquals(ReadingLogStatus.IN_PROGRESS, repo.readingLog.value.last().status)
        assertEquals(0L, repo.readingLog.value.last().readingSeconds)
    }

    @Test fun `a failed resume leaves the abandoned reading untouched and can be retried`() = runTest(dispatcher) {
        seedAbandoned()
        val repo = repository()
        val abandoned = repo.readingLog.value.single()
        val model = open(repo)
        val blockedTemporaryFile = File(temporary.root, "userdata.json.tmp").apply { mkdir() }
        model.resumeReading()
        model.rereadError.first { it }
        assertEquals(listOf(abandoned), repo.readingLog.value)
        assertEquals(ReadingStatus.ABANDONED, repo.bookById("book")!!.status)
        assertTrue(model.offerReread.value)
        assertTrue(blockedTemporaryFile.delete())
        model.resumeReading()
        model.readingGeneration.first { it == 1 }
        assertEquals(abandoned.id, repo.readingLog.value.single().id)
        assertEquals(ReadingLogStatus.IN_PROGRESS, repo.readingLog.value.single().status)
    }

    @Test fun `an old reader cannot bring back progress or a log after its reading is deleted`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book(status = ReadingStatus.READING).copy(startedAtMillis = 1)))
        val repo = repository()
        val model = open(repo)
        repo.books.first { it.single().lastOpenedAtMillis != null }
        repo.deleteReadingLogEntry(repo.readingLog.value.single().id)
        model.saveProgress(2, 50)
        model.addReadingTime(500)
        model.markFinished()
        model.finishSaved.first { it }
        assertEquals(ReadingProgress(), repo.bookById("book")!!.progress)
        assertEquals(ReadingStatus.NONE, repo.bookById("book")!!.status)
        assertTrue(repo.readingLog.value.isEmpty())
        model.readingLogEntry.first { it == null }
    }

    @Test fun `the first position waits for a fresh reading id to be created`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(Book("book", "A new book", format = BookFormat.EPUB, addedAtMillis = 1)))
        val start = CompletableDeferred<Unit>()
        val repo = repository { start.await() }
        val model = open(repo)
        assertNull(repo.bookById("book")!!.activeReadingLogId)
        model.saveProgress(1, 23, 4)
        start.complete(Unit)
        repo.books.first { it.single().progress.elementIndex == 1 }
        val saved = BookRepository(context).bookById("book")!!
        assertEquals(repo.readingLog.value.single().id, saved.activeReadingLogId)
        assertEquals(23, saved.progress.scrollOffset)
        assertEquals(4, model.currentCharOffset)
    }

    private fun seedAbandoned() = StoreFixture.seed(temporary.root,
        listOf(book(status = ReadingStatus.ABANDONED).copy(startedAtMillis = 1)))
}
