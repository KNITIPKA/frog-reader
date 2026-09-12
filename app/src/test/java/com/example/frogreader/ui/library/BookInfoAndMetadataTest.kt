package com.example.frogreader.ui.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.EditableBookMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BookInfoAndMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    companion object {
        private val testDispatcher = UnconfinedTestDispatcher()

        @BeforeClass
        @JvmStatic
        fun setupClass() {
            Dispatchers.setMain(testDispatcher)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            Dispatchers.resetMain()
        }
    }

    private fun mockApp(repository: BookRepository): FrogReaderApp {
        val app = mock(FrogReaderApp::class.java)
        `when`(app.bookRepository).thenReturn(repository)
        `when`(app.cacheDir).thenReturn(File(temp.root, "cache").apply { mkdirs() })
        return app
    }

    private fun repository(): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temp.root)
        `when`(context.cacheDir).thenReturn(File(temp.root, "cache").apply { mkdirs() })
        return BookRepository(context)
    }

    private suspend fun addBook(repository: BookRepository, id: String = "test-book"): Book {
        val file = File(temp.root, "books/$id.fb2").apply {
            parentFile!!.mkdirs()
            writeText("""<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Test Book</book-title><author><first-name>Arthur</first-name><last-name>Conan Doyle</last-name></author></title-info></description><body><section><p>Text</p></section></body></FictionBook>""")
        }
        val book = Book(
            id = id,
            title = "Test Book",
            author = "Arthur Conan Doyle",
            format = BookFormat.FB2,
            fileName = file.name,
            sizeBytes = file.length(),
            addedAtMillis = 1000L,
            publisher = "Penguin",
            year = "1892",
            series = "Sherlock Holmes",
            seriesNumber = 1f,
        )
        repository.addBookForRestore(book)
        return book
    }

    @Test
    fun `EditableBookMetadata from book populates fields correctly`() {
        val book = Book(
            id = "b1",
            title = "A Study in Scarlet",
            author = "Arthur Conan Doyle",
            format = BookFormat.EPUB,
            addedAtMillis = 1000L,
            description = "A detective story",
            genres = listOf("Mystery", "Classic"),
            series = "Sherlock Holmes",
            seriesNumber = 1f,
            publisher = "Ward, Lock & Co.",
            year = "1887",
            isbn = "1234567890",
            language = "en",
        )
        val meta = EditableBookMetadata.from(book)
        assertEquals("A Study in Scarlet", meta.title)
        assertEquals(listOf("Arthur Conan Doyle"), meta.authors)
        assertEquals("A detective story", meta.description)
        assertEquals(listOf("Mystery", "Classic"), meta.genres)
        assertEquals("Sherlock Holmes", meta.series)
        assertEquals("1", meta.seriesNumber)
        assertEquals("Ward, Lock & Co.", meta.publisher)
        assertEquals("1887", meta.year)
        assertEquals("1234567890", meta.isbn)
        assertEquals("en", meta.language)
    }

    @Test
    fun `EditableBookMetadata from book handles blank or null author`() {
        val book = Book(id = "b2", title = "Anonymous", author = null, format = BookFormat.EPUB, addedAtMillis = 1000L)
        val meta = EditableBookMetadata.from(book)
        assertTrue(meta.authors.isEmpty())

        val bookBlank = Book(id = "b3", title = "Blank", author = "   ", format = BookFormat.EPUB, addedAtMillis = 1000L)
        val metaBlank = EditableBookMetadata.from(bookBlank)
        assertTrue(metaBlank.authors.isEmpty())
    }

    @Test
    fun `BookInfoViewModel initializes with book without full-screen loading`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)

        val viewModel = BookInfoViewModel(app, book.id)
        val state = viewModel.state.value

        assertFalse(state.loading)
        assertEquals(book.id, state.book?.id)
        assertEquals("Test Book", state.book?.title)

        var elapsed = 0
        while (viewModel.state.value.fileLoading && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }
        assertFalse(viewModel.state.value.fileLoading)
    }

    @Test
    fun `BookInfoViewModel initializes fileAvailable immediately without disabled button flicker`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)

        val viewModel = BookInfoViewModel(app, book.id)
        val initialState = viewModel.state.value

        assertFalse(initialState.loading)
        assertTrue("fileAvailable must be true immediately on initialization", initialState.fileAvailable)
        assertTrue(initialState.fileLoading)

        var elapsed = 0
        while (viewModel.state.value.fileLoading && elapsed < 3000) {
            assertTrue("fileAvailable must not temporarily flip to false while fileLoading is in progress", viewModel.state.value.fileAvailable)
            Thread.sleep(20)
            elapsed += 20
        }
        assertFalse(viewModel.state.value.fileLoading)
        assertTrue(viewModel.state.value.fileAvailable)
    }

    @Test
    fun `BookInfoViewModel maintains fileAvailable without flicker when book metadata and fileName change`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)

        val viewModel = BookInfoViewModel(app, book.id)
        var elapsed = 0
        while (viewModel.state.value.fileLoading && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }
        assertFalse(viewModel.state.value.fileLoading)
        assertTrue(viewModel.state.value.fileAvailable)
        val initialSize = viewModel.state.value.sizeBytes
        assertTrue(initialSize > 0)

        // Simulate user editing and saving metadata in BookMetadataScreen, which changes the file name on disk
        val updated = repository.saveBookMetadata(
            book.id,
            book.fileName,
            repository.editableMetadata(book.id).second.copy(title = "Updated Title"),
        )
        assertNotEquals(book.fileName, updated.fileName)

        var checkElapsed = 0
        while (viewModel.state.value.fileLoading && checkElapsed < 3000) {
            assertTrue("fileAvailable must not flip to false when fileName changes after metadata save", viewModel.state.value.fileAvailable)
            assertTrue("sizeBytes must not drop to 0 during reload", viewModel.state.value.sizeBytes > 0)
            Thread.sleep(20)
            checkElapsed += 20
        }
        assertFalse(viewModel.state.value.fileLoading)
        assertTrue(viewModel.state.value.fileAvailable)
        assertEquals("Updated Title", viewModel.state.value.book?.title)
        assertTrue(viewModel.state.value.sizeBytes > 0)
    }

    @Test
    fun `BookInfoViewModel populates fileAvailable immediately when book becomes available after initial null`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val app = mockApp(repository)

        val viewModel = BookInfoViewModel(app, "delayed-book")
        assertNull(viewModel.state.value.book)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.fileAvailable)

        val book = addBook(repository, "delayed-book")

        var waitElapsed = 0
        while (viewModel.state.value.book == null && waitElapsed < 3000) {
            Thread.sleep(20)
            waitElapsed += 20
        }
        assertNotNull(viewModel.state.value.book)
        assertTrue("fileAvailable must be true immediately when book is loaded", viewModel.state.value.fileAvailable)
        assertTrue("sizeBytes must be positive immediately", viewModel.state.value.sizeBytes > 0)

        while (viewModel.state.value.fileLoading && waitElapsed < 3000) {
            assertTrue("fileAvailable must remain true while fileLoading completes", viewModel.state.value.fileAvailable)
            Thread.sleep(20)
            waitElapsed += 20
        }
        assertFalse(viewModel.state.value.fileLoading)
        assertTrue(viewModel.state.value.fileAvailable)
    }

    @Test
    fun `BookInfoViewModel with nonexistent book sets book to null`() = runTest(testDispatcher) {
        val repository = repository()
        val app = mockApp(repository)

        val viewModel = BookInfoViewModel(app, "nonexistent")
        assertNull(viewModel.state.value.book)
    }

    @Test
    fun `BookMetadataViewModel initializes draft and book immediately without null jump`() = runTest(testDispatcher) {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)
        val savedState = mock(SavedStateHandle::class.java)

        val viewModel = BookMetadataViewModel(app, book.id, savedState)
        val state = viewModel.state.value

        assertNotNull(state.book)
        assertEquals("Test Book", state.book?.title)
        assertNotNull(state.draft)
        assertEquals("Test Book", state.draft?.title)
        assertEquals(listOf("Arthur Conan Doyle"), state.draft?.authors)

        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `BookInfoViewModel share requires fileAvailable`() = runTest(testDispatcher) {
        val repository = repository()
        val app = mockApp(repository)
        val viewModel = BookInfoViewModel(app, "missing")

        viewModel.share()
        assertFalse(viewModel.state.value.sharing)
        assertNull(viewModel.state.value.preparedShare)
    }

    @Test
    fun `BookInfoViewModel share prepares file when available and shareFinished resets state`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)
        val viewModel = BookInfoViewModel(app, book.id)

        // Wait for file loading on Dispatchers.IO to finish
        var elapsed = 0
        while (!viewModel.state.value.fileAvailable && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }
        assertTrue(viewModel.state.value.fileAvailable)

        viewModel.share()
        elapsed = 0
        while (viewModel.state.value.preparedShare == null && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }

        val state = viewModel.state.value
        assertNotNull(state.preparedShare)
        assertTrue(state.sharing)

        viewModel.shareFinished()
        val finishedState = viewModel.state.value
        assertFalse(finishedState.sharing)
        assertNull(finishedState.preparedShare)
    }

    @Test
    fun `BookInfoViewModel share sets shareError when backing file deleted on disk`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val book = addBook(repository)
        val app = mockApp(repository)
        val viewModel = BookInfoViewModel(app, book.id)

        // Wait for file loading to complete
        var elapsed = 0
        while (!viewModel.state.value.fileAvailable && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }
        assertTrue(viewModel.state.value.fileAvailable)

        // Delete backing file from disk
        val file = repository.bookFileFor(book)
        assertNotNull(file)
        file!!.delete()

        viewModel.share()
        elapsed = 0
        while (viewModel.state.value.shareError == null && elapsed < 3000) {
            Thread.sleep(20)
            elapsed += 20
        }

        val state = viewModel.state.value
        assertFalse(state.sharing)
        assertNull(state.preparedShare)
        assertNotNull(state.shareError)

        viewModel.dismissError()
        assertNull(viewModel.state.value.shareError)
    }

    @Test
    fun `BookSharing prepare succeeds even if file has corrupt mobi header`() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        val file = File(temp.root, "books/corrupt.mobi").apply {
            parentFile!!.mkdirs()
            writeText("corrupt non-mobi bytes")
        }
        val book = Book(
            id = "corrupt-mobi",
            title = "Corrupt Mobi",
            format = BookFormat.MOBI,
            fileName = file.name,
            sizeBytes = file.length(),
            addedAtMillis = 1000L,
        )
        repository.addBookForRestore(book)

        val shared = com.example.frogreader.data.BookSharing.prepare(repository, File(temp.root, "cache"), book.id)
        assertNotNull(shared)
        assertTrue(shared.file.exists())
        assertEquals("application/x-mobipocket-ebook", shared.mimeType)
        assertEquals("Corrupt Mobi.mobi", shared.file.name)
    }
}
