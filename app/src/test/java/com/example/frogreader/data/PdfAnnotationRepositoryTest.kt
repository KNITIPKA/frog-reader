package com.example.frogreader.data

import android.content.Context
import com.example.frogreader.data.model.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PdfAnnotationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pausedScopes = mutableListOf<Pair<CoroutineScope, TestCoroutineScheduler>>()

    private fun pausedWorkerScope(): CoroutineScope {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        pausedScopes += scope to scheduler
        return scope
    }

    @After fun cancelWorkerScopes() {
        pausedScopes.forEach { (scope, scheduler) -> scope.cancel(); scheduler.runCurrent() }
    }

    private fun context(): Context = mock(Context::class.java).also {
        whenever(it.filesDir).thenReturn(temporary.root)
        whenever(it.cacheDir).thenReturn(temporary.root)
    }
    private fun seed(): Book {
        val book = Book("pdf", "PDF", format = BookFormat.PDF, fileName = "original.pdf", addedAtMillis = 1)
        File(temporary.root, "books/original.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-test-source") }
        StoreFixture.seed(temporary.root, listOf(book))
        return book
    }
    private fun quote() = Quote("note", "Selected passage", -1, 1, note = "User's irreplaceable note",
        documentLocation = DocumentLocation.Pdf(0), pdfSelection = listOf(PdfTextBounds(0, 10f, 20f, 100f, 35f)))

    @Test fun `failed PDF writer keeps the already saved note and original bytes and removes staging generation`() = runTest {
        val book = seed()
        val context = context()
        val repository = BookRepository(context, writePdfAnnotations = { _, target, _, _, _ ->
            target.writeText("Incomplete output")
            throw IOException("Disk full")
        }, pdfAnnotationScope = pausedWorkerScope())
        repository.addPdfNote(book.id, quote())
        assertEquals(quote().note, repository.bookById(book.id)!!.quotes.single().note)
        assertEquals(book.fileName, repository.bookById(book.id)!!.fileName)
        try { repository.ensurePdfAnnotations(book.id); fail("Explicit flush must report writer failure") } catch (_: IOException) { }
        assertEquals("%PDF-test-source", repository.bookFileFor(book)!!.readText())
        assertEquals(listOf("original.pdf"), File(temporary.root, "books").list()!!.toList())
        assertEquals(quote().note, BookRepository(context, writePdfAnnotations = null).bookById(book.id)!!.quotes.single().note)
    }

    @Test fun `failed library commit and later edits preserve durable note and explicit retry repairs the PDF`() = runTest {
        val book = seed()
        val context = context()
        val writer: suspend (File, File, String, List<Quote>, String?) -> Unit = { source, target, _, _, _ -> source.copyTo(target); Unit }
        val repository = BookRepository(context, writer, pdfAnnotationScope = pausedWorkerScope())
        repository.addPdfNote(book.id, quote())
        StoreFixture.blockWrites(temporary.root)
        try {
            try { repository.ensurePdfAnnotations(book.id); fail("Commit failure must be reported") } catch (_: IOException) { }
            assertEquals(quote().note, repository.bookById(book.id)!!.quotes.single().note)
            assertEquals("%PDF-test-source", repository.bookFileFor(book)!!.readText())
            assertEquals(listOf("original.pdf"), File(temporary.root, "books").list()!!.toList())
        } finally {
            StoreFixture.unblockWrites(temporary.root)
        }
        // An unrelated userdata update after the failed PDF commit must not erase the note.
        repository.setReview(book.id, "Unrelated review")
        repository.setQuoteNote(book.id, quote().id, "Updated after failed flush")
        val reopened = BookRepository(context, writer, pdfAnnotationScope = pausedWorkerScope())
        assertEquals("Updated after failed flush", reopened.bookById(book.id)!!.quotes.single().note)
        assertEquals("Unrelated review", reopened.bookById(book.id)!!.review)
        assertEquals(book.fileName, reopened.bookById(book.id)!!.fileName)
        val repaired = reopened.ensurePdfAnnotations(book.id)!!
        assertNotEquals(book.fileName, repaired.fileName)
        assertNotNull(repaired.pdfAnnotationFingerprint)
        assertTrue(reopened.bookFileFor(repaired)!!.isFile)
        reopened.addPdfNote(book.id, quote().copy(note = "Updated restored draft"))
        assertEquals("Updated restored draft", reopened.bookById(book.id)!!.quotes.single().note)
        assertEquals(1, reopened.bookById(book.id)!!.quotes.size)
    }

    @Test fun `note saves return durably while a blocked older PDF snapshot cannot overwrite newer note or progress`() = runTest {
        val book = seed()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<List<Quote>>()
        val context = context()
        val repository = BookRepository(context, writePdfAnnotations = { source, target, _, quotes, _ ->
            snapshots += quotes
            if (snapshots.size == 1) { entered.complete(Unit); resume.await() }
            source.copyTo(target)
            Unit
        }, pdfAnnotationScope = pausedWorkerScope())
        repository.addPdfNote(book.id, quote())
        val flushing = async { repository.ensurePdfAnnotations(book.id) }
        entered.await()
        val progress = ReadingProgress(fraction = 0.5f, documentLocation = DocumentLocation.Pdf(4, 100f, 200f, 2f))
        try {
            // Use a real timeout because repository fsyncs execute on Dispatchers.IO.
            withContext(Dispatchers.Default) { withTimeout(5_000) {
                repository.setQuoteNote(book.id, quote().id, "Latest note saved during PDF write")
                repository.setQuoteColor(book.id, quote().id, QuoteColor.BLUE)
            } }
            repository.saveProgress(book.id, progress)
            val durable = BookRepository(context, writePdfAnnotations = null).bookById(book.id)!!
            assertEquals("Latest note saved during PDF write", durable.quotes.single().note)
            assertEquals(QuoteColor.BLUE, durable.quotes.single().color)
            assertEquals(progress, durable.progress)
            assertEquals(book.fileName, durable.fileName)
        } finally { resume.complete(Unit) }
        flushing.await()
        assertEquals(2, snapshots.size)
        assertEquals("Latest note saved during PDF write", snapshots.last().single().note)
        assertEquals(QuoteColor.BLUE, snapshots.last().single().color)
        assertEquals(progress, repository.bookById(book.id)!!.progress)
        assertEquals("Latest note saved during PDF write", repository.bookById(book.id)!!.quotes.single().note)
        assertEquals(progress, BookRepository(context, writePdfAnnotations = null).bookById(book.id)!!.progress)
    }

    @Test fun `debounced PDF worker batches rapid note edits into a single latest snapshot`() = runTest {
        val book = seed()
        val snapshots = mutableListOf<List<Quote>>()
        val written = CompletableDeferred<Unit>()
        val workerScheduler = TestCoroutineScheduler()
        val workerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(workerScheduler))
        try {
            val repository = BookRepository(context(), writePdfAnnotations = { source, target, _, quotes, _ ->
                snapshots += quotes
                source.copyTo(target)
                written.complete(Unit)
                Unit
            }, pdfAnnotationScope = workerScope, pdfAnnotationDebounceMillis = 600)
            repository.addPdfNote(book.id, quote())
            workerScheduler.runCurrent()
            repository.setQuoteNote(book.id, quote().id, "Second draft")
            repository.setQuoteNote(book.id, quote().id, "Final draft")
            repository.setQuoteColor(book.id, quote().id, QuoteColor.BLUE)
            workerScheduler.runCurrent()
            assertTrue(snapshots.isEmpty())
            workerScheduler.advanceTimeBy(600)
            workerScheduler.runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { written.await() } }
            repository.ensurePdfAnnotations(book.id)
            assertEquals(1, snapshots.size)
            assertEquals("Final draft", snapshots.single().single().note)
            assertEquals(QuoteColor.BLUE, snapshots.single().single().color)
        } finally { workerScope.cancel() }
    }

    @Test fun `sharing waits for the latest pending PDF annotation snapshot`() = runTest {
        val book = seed()
        val snapshots = mutableListOf<List<Quote>>()
        val repository = BookRepository(context(), writePdfAnnotations = { source, target, _, quotes, _ ->
            snapshots += quotes
            source.copyTo(target)
            target.appendText("\n% ${quotes.single().note}")
        }, pdfAnnotationScope = pausedWorkerScope())
        repository.addPdfNote(book.id, quote())
        repository.setQuoteNote(book.id, quote().id, "Newest exported note")
        val shared = BookSharing.prepare(repository, temporary.root, book.id)
        assertEquals("Newest exported note", snapshots.last().single().note)
        assertTrue(shared.file.readText().contains("Newest exported note"))
        assertEquals(1, snapshots.size)
    }
}
