package com.example.frogreader.reader

import android.content.Context
import android.net.Uri
import android.util.SparseArray
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.PdfPasswordException
import androidx.pdf.content.PageMatchBounds
import com.example.frogreader.data.*
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.PdfReaderState
import com.example.frogreader.ui.reader.pdf.PdfReaderViewModel
import com.example.frogreader.ui.reader.pdf.PdfOutlineState
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PdfReaderViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val models = mutableListOf<PdfReaderViewModel>()
    private val repositoryScopes = mutableListOf<CoroutineScope>()
    private lateinit var context: Context
    private lateinit var preferences: SettingsRepository
    private lateinit var uriStatics: MockedStatic<Uri>
    private var now = 0L

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = mock(Context::class.java)
        whenever(context.filesDir).thenReturn(temporary.root)
        whenever(context.cacheDir).thenReturn(temporary.root)
        preferences = mock(SettingsRepository::class.java)
        whenever(preferences.appSettings).thenReturn(MutableStateFlow(AppSettings()))
        val uri = mock(Uri::class.java)
        uriStatics = mockStatic(Uri::class.java)
        uriStatics.`when`<Uri> { Uri.fromFile(any<File>()) }.thenReturn(uri)
        File(temporary.root, "books/book.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-test") }
    }

    @After fun cleanup() = runTest(dispatcher) {
        try {
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            stores.forEach(ViewModelStore::clear)
            jobs.joinAll()
            repositoryScopes.forEach {
                it.cancel()
                (it.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? TestDispatcher)?.scheduler?.runCurrent()
            }
            repositoryScopes.mapNotNull { it.coroutineContext[Job] }.joinAll()
        } finally {
            uriStatics.close()
            Dispatchers.resetMain()
        }
    }

    private fun book() = Book(
        "book", "PDF book", format = BookFormat.PDF, addedAtMillis = 1,
        fileName = "book.pdf", coverFileName = "cover.jpg",
    )

    private fun repository(
        initial: Book = book(),
        noteOpened: () -> Unit = {},
        beforeStart: suspend () -> Unit = {},
    ): BookRepository {
        StoreFixture.seed(temporary.root, listOf(initial))
        return object : BookRepository(context,
            writePdfAnnotations = { source, target, _, _, _ -> source.copyTo(target); Unit },
            pdfAnnotationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler())).also { repositoryScopes += it },
        ) {
            override fun noteOpened(bookId: String) = noteOpened()
            override suspend fun markStarted(bookId: String) {
                beforeStart()
                super.markStarted(bookId)
            }
        }
    }

    private fun document(pages: Int = 10): PdfDocument = mock(PdfDocument::class.java).also {
        whenever(it.pageCount).thenReturn(pages)
    }

    private fun model(
        repository: BookRepository,
        document: PdfDocument = document(),
        opener: (suspend (Uri, String?) -> PdfDocument)? = null,
        stats: StatsRepository = StatsRepository(context),
        textAvailable: Boolean? = true,
        outlineLoader: suspend (File, String?, String?, Int) -> PdfOutline = { _, _, _, _ -> PdfOutline() },
    ): PdfReaderViewModel {
        val session = PdfDocumentSession(opener ?: { _, _ -> document }, dispatcher)
        val model = PdfReaderViewModel(
            context, repository, preferences, stats, "book", session,
            nowMillis = { now }, textAvailable = textAvailable?.let { enabled -> { enabled } },
            outlineLoader = outlineLoader,
        )
        stores += ViewModelStore().apply { put("pdf", model) }
        models += model
        return model
    }

    private suspend fun ready(model: PdfReaderViewModel, repository: BookRepository) {
        // A retry is launched asynchronously; first let it replace the previous error state.
        dispatcher.scheduler.runCurrent()
        assertTrue(model.state.first { it !is PdfReaderState.Loading } is PdfReaderState.Ready)
        repository.books.first { it.single().status == ReadingStatus.READING }
    }

    @Test fun `PDF progress bookmarks and quote rectangles persist through the existing stores`() = runTest(dispatcher) {
        val repository = repository()
        val model = model(repository)
        ready(model, repository)
        val location = DocumentLocation.Pdf(4, 120f, 450f, 2.2f)
        val bounds = listOf(PdfTextBounds(4, 10f, 20f, 200f, 36f), PdfTextBounds(5, 10f, 20f, 90f, 36f))
        model.observePosition(location, pageOffsetFraction = 0.5f)
        model.savePosition()
        model.toggleBookmark()
        model.addQuote("A passage across pages", bounds, QuoteColor.BLUE)
        repository.books.first { it.single().quotes.size == 1 && it.single().bookmarks.size == 1 }
        val reopened = BookRepository(context).bookById("book")!!
        assertEquals(location, reopened.progress.documentLocation)
        assertEquals(10, reopened.progress.totalPages)
        assertEquals(0.45f, reopened.progress.fraction, 0.00001f)
        assertEquals(location, reopened.bookmarks.single().documentLocation)
        assertEquals(-1, reopened.bookmarks.single().flatIndex)
        assertEquals(bounds, reopened.quotes.single().pdfSelection)
        assertEquals(4, (reopened.quotes.single().documentLocation as DocumentLocation.Pdf).pageIndex)
        assertEquals(-1, reopened.quotes.single().chapterIndex)
        model.toggleBookmark()
        repository.books.first { it.single().bookmarks.isEmpty() }
        assertEquals(1, repository.bookById("book")!!.quotes.size)
    }

    @Test fun `foreground seconds update reading history and daily statistics exactly once`() = runTest(dispatcher) {
        val repository = repository()
        val stats = StatsRepository(context)
        val model = model(repository, stats = stats)
        ready(model, repository)
        model.setReadingActive(true)
        now = 12_500
        model.setReadingActive(false)
        repository.books.first { it.single().readingSeconds == 12L }
        stats.stats.first { it.secondsOn(LocalDate.now()) == 12L }
        model.flushTime()
        runCurrent()
        assertEquals(12, repository.bookById("book")!!.readingSeconds)
        assertEquals(12, repository.readingLog.value.single().readingSeconds)
        assertEquals(12, stats.stats.value.secondsOn(LocalDate.now()))
        assertNotNull(stats.stats.value.lastReadAtMillis)
    }

    @Test fun `foreground time before first open bookkeeping keeps the newly assigned reading id`() = runTest(dispatcher) {
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val repository = repository(beforeStart = {
            startEntered.complete(Unit)
            releaseStart.await()
        })
        val stats = StatsRepository(context)
        val model = model(repository, stats = stats)
        assertTrue(model.state.first { it !is PdfReaderState.Loading } is PdfReaderState.Ready)
        startEntered.await()
        assertNull(repository.bookById("book")!!.activeReadingLogId)
        model.setReadingActive(true)
        now = 12_500
        model.setReadingActive(false)
        releaseStart.complete(Unit)
        repository.books.first { it.single().readingSeconds == 12L }
        stats.stats.first { it.secondsOn(LocalDate.now()) == 12L }
        assertEquals(12L, repository.readingLog.value.single().readingSeconds)
        assertEquals(repository.bookById("book")!!.activeReadingLogId, repository.readingLog.value.single().id)
        assertFalse(model.saveError.value)
    }

    @Test fun `browsing a finished PDF preserves its previous reading and progress`() = runTest(dispatcher) {
        val previous = book().copy(
            status = ReadingStatus.FINISHED, startedAtMillis = 1, finishedAtMillis = 2,
            readingSeconds = 60, progress = ReadingProgress(fraction = 1f, documentLocation = DocumentLocation.Pdf(9)),
        )
        val repository = repository(previous)
        val stats = StatsRepository(context)
        val model = model(repository, stats = stats)
        assertTrue(model.state.first { it !is PdfReaderState.Loading } is PdfReaderState.Ready)
        assertTrue(model.offerReread.value)
        model.keepPreviousReading()
        model.observePosition(DocumentLocation.Pdf(0, 50f, 50f))
        model.savePosition()
        model.setReadingActive(true)
        now = 30_000
        model.setReadingActive(false)
        model.finishReading()
        runCurrent()
        assertEquals(previous.progress, repository.bookById("book")!!.progress)
        assertEquals(60, repository.bookById("book")!!.readingSeconds)
        assertTrue(stats.stats.value.dailySeconds.isEmpty())
    }

    @Test fun `password retry opens only after successful unlock and session closes with the view model`() = runTest(dispatcher) {
        val repository = repository()
        val document = document()
        val passwords = mutableListOf<String?>()
        val model = model(repository, document, opener = { _, password ->
            passwords += password
            if (password != "secret") throw PdfPasswordException("Locked")
            document
        })
        assertTrue(model.state.first { it !is PdfReaderState.Loading } is PdfReaderState.Password)
        assertNull(repository.bookById("book")!!.startedAtMillis)
        model.load("secret")
        ready(model, repository)
        assertEquals(listOf(null, "secret"), passwords)
        assertNotNull(repository.bookById("book")!!.startedAtMillis)
        stores.single().clear()
        verify(document, times(1)).close()
    }

    @Test fun `failed progress writes retain their captured location and can be retried`() = runTest(dispatcher) {
        val repository = repository()
        val model = model(repository)
        ready(model, repository)
        val original = DocumentLocation.Pdf(2, 50f, 80f, 1.5f)
        StoreFixture.blockWrites(temporary.root)
        try {
            model.observePosition(original)
            model.savePosition()
            model.saveError.first { it }
            model.observePosition(DocumentLocation.Pdf(8))
        } finally {
            StoreFixture.unblockWrites(temporary.root)
        }
        model.retryWrites()
        repository.books.first { it.single().progress.documentLocation == original }
        assertEquals(original, BookRepository(context).bookById("book")!!.progress.documentLocation)
        assertFalse(model.saveError.value)
    }

    @Test fun `text search stops at its result cap and keeps genuine page coordinates`() = runTest(dispatcher) {
        val repository = repository()
        val document = document()
        val rect = android.graphics.RectF().apply { left = 10f; top = 20f; right = 30f; bottom = 40f }
        val match = PageMatchBounds(listOf(rect), 7)
        @Suppress("UNCHECKED_CAST")
        val results = mock(SparseArray::class.java) as SparseArray<List<PageMatchBounds>>
        whenever(results[0]).thenReturn(List(2_001) { match })
        whenever(document.searchDocument(any(), any())).thenReturn(results)
        val model = model(repository, document)
        ready(model, repository)
        model.search("  passage  ")
        runCurrent()
        val result = model.search.first { it.limited }
        assertEquals("passage", result.query)
        assertFalse(result.searching)
        assertEquals(2_000, result.matches.size)
        assertEquals(listOf(PdfTextBounds(0, 10f, 20f, 30f, 40f)), result.matches.first().bounds)
        verify(document, times(1)).searchDocument("passage", 0..0)
    }

    @Test fun `empty PDF result is released and the same session can retry successfully`() = runTest(dispatcher) {
        val repository = repository()
        val empty = document(0)
        val valid = document()
        var attempts = 0
        val model = model(repository, opener = { _, _ -> if (++attempts == 1) empty else valid })
        assertTrue(model.state.first { it !is PdfReaderState.Loading } is PdfReaderState.Error)
        verify(empty, times(1)).close()
        assertNull(repository.bookById("book")!!.startedAtMillis)
        model.load()
        ready(model, repository)
        assertSame(valid, (model.state.value as PdfReaderState.Ready).document)
        verify(valid, never()).close()
    }

    @Test fun `search follows the opened document capability and clears the editable query on close`() = runTest(dispatcher) {
        val repository = repository()
        val document = document(1)
        @Suppress("UNCHECKED_CAST")
        val results = mock(SparseArray::class.java) as SparseArray<List<PageMatchBounds>>
        whenever(document.isFeatureSupported(PdfFeature.SEARCH)).thenReturn(true)
        whenever(document.searchDocument(any(), any())).thenReturn(results)
        val model = model(repository, document, textAvailable = null)
        ready(model, repository)
        model.setSearchInput("  Café  ")
        assertEquals("  Café  ", model.searchInput.value)
        runCurrent()
        model.search.first { it.query == "Café" && !it.searching }
        verify(document).searchDocument("Café", 0..0)
        model.setSearchInput("")
        assertEquals("", model.searchInput.value)
        assertTrue(model.search.value.matches.isEmpty())
        assertFalse(model.search.value.searching)
    }

    @Test fun `clearing a query cancels an in flight page and cannot republish stale matches`() = runTest(dispatcher) {
        val repository = repository()
        val document = document(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        @Suppress("UNCHECKED_CAST")
        val results = mock(SparseArray::class.java) as SparseArray<List<PageMatchBounds>>
        whenever(results[0]).thenReturn(listOf(PageMatchBounds(emptyList(), 0)))
        whenever(document.searchDocument(any(), any())).thenAnswer {
            runBlocking {
                entered.complete(Unit)
                release.await()
                returned.complete(Unit)
                results
            }
        }
        val model = model(repository, document)
        ready(model, repository)
        model.setSearchInput("old")
        advanceTimeBy(250)
        runCurrent()
        entered.await()
        model.setSearchInput("")
        release.complete(Unit)
        returned.await()
        runCurrent()
        assertEquals("", model.searchInput.value)
        assertEquals("", model.search.value.query)
        assertTrue(model.search.value.matches.isEmpty())
        assertFalse(model.search.value.searching)
    }

    @Test fun `text worker failure becomes a search error while the document stays readable`() = runTest(dispatcher) {
        val repository = repository()
        val document = document(1)
        whenever(document.searchDocument(any(), any())).thenAnswer { throw java.io.IOException("Worker died") }
        val model = model(repository, document)
        ready(model, repository)
        model.setSearchInput("passage")
        val failed = model.search.first { it.error }
        assertFalse(failed.searching)
        assertEquals("passage", model.searchInput.value)
        assertTrue(model.state.value is PdfReaderState.Ready)
    }

    @Test fun `bookkeeping failure keeps an opened PDF usable and offers write retry`() = runTest(dispatcher) {
        var attempts = 0
        val repository = repository(noteOpened = {
            if (++attempts == 1) throw java.io.IOException("Cannot update open stamp")
        })
        val model = model(repository)
        ready(model, repository)
        model.saveError.first { it }
        assertTrue(model.state.value is PdfReaderState.Ready)
        model.retryWrites()
        runCurrent()
        assertFalse(model.saveError.value)
        assertEquals(2, attempts)
        assertTrue(model.state.value is PdfReaderState.Ready)
    }

    @Test fun `outline is requested on demand once and an empty result stays known`() = runTest(dispatcher) {
        val repository = repository()
        var calls = 0
        val model = model(repository, outlineLoader = { file, _, _, pageCount ->
            calls++
            assertEquals("book.pdf", file.name)
            assertEquals(10, pageCount)
            PdfOutline()
        })
        ready(model, repository)
        assertEquals(0, calls)
        assertEquals(PdfOutlineState.Idle, model.outline.value)
        model.loadOutline()
        model.loadOutline()
        assertEquals(PdfOutlineState.Loading, model.outline.value)
        assertEquals(PdfOutlineState.Ready(PdfOutline()), model.outline.first { it is PdfOutlineState.Ready })
        model.loadOutline()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(model.state.value is PdfReaderState.Ready)
    }

    @Test fun `outline failure can retry without reloading or closing the readable document`() = runTest(dispatcher) {
        val repository = repository()
        val document = document()
        val expected = PdfOutline(listOf(PdfOutlineEntry("Part one", 2)))
        var calls = 0
        val model = model(repository, document, outlineLoader = { _, _, _, _ ->
            if (++calls == 1) throw IOException("Outline worker disconnected")
            expected
        })
        ready(model, repository)
        model.loadOutline()
        model.outline.first { it == PdfOutlineState.Error }
        model.loadOutline()
        runCurrent()
        assertEquals(1, calls)
        assertSame(document, (model.state.value as PdfReaderState.Ready).document)
        model.retryOutline()
        assertEquals(PdfOutlineState.Ready(expected), model.outline.first { it is PdfOutlineState.Ready })
        assertEquals(2, calls)
        verify(document, never()).close()
    }

    @Test fun `unlocked outline receives transient password only after document succeeds`() = runTest(dispatcher) {
        val repository = repository()
        val document = document()
        val receivedPasswords = mutableListOf<String?>()
        val model = model(repository, document, opener = { _, password ->
            if (password != "secret") throw PdfPasswordException("Locked")
            document
        }, outlineLoader = { _, _, password, _ ->
            receivedPasswords += password
            PdfOutline(listOf(PdfOutlineEntry("Opening", 0)))
        })
        model.state.first { it is PdfReaderState.Password }
        model.loadOutline()
        assertTrue(receivedPasswords.isEmpty())
        model.load("secret")
        ready(model, repository)
        assertTrue(receivedPasswords.isEmpty())
        model.loadOutline()
        model.outline.first { it is PdfOutlineState.Ready }
        assertEquals(listOf("secret"), receivedPasswords)
    }

    @Test fun `a superseded outline result cannot overwrite a reopened document`() = runTest(dispatcher) {
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = PdfOutline(listOf(PdfOutlineEntry("New session", 4)))
        var calls = 0
        val model = model(repository, opener = { _, _ -> document() }, outlineLoader = { _, _, _, _ ->
            if (++calls == 1) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                PdfOutline(listOf(PdfOutlineEntry("Abandoned session", 1)))
            } else expected
        })
        ready(model, repository)
        model.loadOutline()
        entered.await()
        model.load()
        ready(model, repository)
        assertEquals(PdfOutlineState.Idle, model.outline.value)
        model.loadOutline()
        assertEquals(PdfOutlineState.Ready(expected), model.outline.first { it is PdfOutlineState.Ready })
        release.complete(Unit)
        runCurrent()
        assertEquals(PdfOutlineState.Ready(expected), model.outline.value)
    }

    @Test fun `closing reader cancels an outstanding outline request`() = runTest(dispatcher) {
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val model = model(repository, outlineLoader = { _, _, _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        ready(model, repository)
        model.loadOutline()
        entered.await()
        stores.single().clear()
        cancelled.await()
    }
}
