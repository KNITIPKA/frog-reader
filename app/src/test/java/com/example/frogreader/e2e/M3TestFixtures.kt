package com.example.frogreader.e2e

import android.net.Uri
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ScannedBookFile
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val testDispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class TestBookRepository(
    initialBooks: List<Book> = emptyList(),
) : BookRepository(null) {

    private val _testBooks = MutableStateFlow(initialBooks)
    override val books: StateFlow<List<Book>> get() = _testBooks.asStateFlow()

    // Own flow on purpose: falling through to the real lazy would read the
    // shared build/tmp/test_files/library.json that the repository tests write.
    private val _testShelves = MutableStateFlow(emptyList<Shelf>())
    override val shelves: StateFlow<List<Shelf>> get() = _testShelves.asStateFlow()

    fun setBooks(list: List<Book>) {
        _testBooks.value = list
    }

    fun setShelves(list: List<Shelf>) {
        _testShelves.value = list
    }

    override suspend fun cleanOrphanCaches() {
        // No-op in unit test fixture
    }

    override suspend fun importBook(uri: Uri): Book {
        val newBook = M3TestFixtures.createTestBook(title = "Imported Book")
        _testBooks.value = listOf(newBook) + _testBooks.value
        return newBook
    }

    override suspend fun deleteBook(bookId: String) {
        _testBooks.value = _testBooks.value.filterNot { it.id == bookId }
        _testShelves.value = _testShelves.value
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds - bookId) }
            .filter { it.bookIds.size >= 2 }
    }
}

object M3TestFixtures {

    fun mockUri(uriString: String = "content://com.android.providers.downloads.documents/document/123"): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(uriString)
        return uri
    }

    fun createTestBook(
        id: String = UUID.randomUUID().toString(),
        title: String = "Sample Book Title",
        author: String? = "Sample Author Name",
        format: BookFormat = BookFormat.EPUB,
        fileName: String = "$id.epub",
        coverFileName: String? = null,
        addedAtMillis: Long = System.currentTimeMillis(),
        lastOpenedAtMillis: Long? = null,
        progress: ReadingProgress = ReadingProgress(),
        description: String? = null,
        genres: List<String> = emptyList(),
        series: String? = null,
        seriesNumber: Float? = null,
        publisher: String? = null,
        year: String? = null,
        isbn: String? = null,
    ): Book {
        return Book(
            id = id,
            title = title,
            author = author,
            format = format,
            fileName = fileName,
            coverFileName = coverFileName,
            addedAtMillis = addedAtMillis,
            lastOpenedAtMillis = lastOpenedAtMillis,
            progress = progress,
            description = description,
            genres = genres,
            series = series,
            seriesNumber = seriesNumber,
            publisher = publisher,
            year = year,
            isbn = isbn,
        )
    }

    fun createScannedBookFile(
        uriString: String = "content://com.android.providers.downloads.documents/document/123",
        file: File? = null,
        title: String = "Scanned Test Book",
        author: String? = "Scanned Author",
        coverBytes: ByteArray? = byteArrayOf(1, 2, 3, 4),
        format: BookFormat = BookFormat.EPUB,
        sizeBytes: Long = 1024L * 500, // 500 KB
        lastModifiedMillis: Long = System.currentTimeMillis(),
    ): ScannedBookFile {
        val uri = mockUri(uriString)
        return ScannedBookFile(
            uri = uri,
            file = file,
            title = title,
            author = author,
            coverBytes = coverBytes,
            format = format,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis,
        )
    }
}
