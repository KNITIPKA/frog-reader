package com.example.frogreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import androidx.glance.appwidget.updateAll
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.widget.ContinueReadingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns the library: imported book files, extracted covers/images and the JSON
 * index in the app's private storage. All heavy work runs on Dispatchers.IO.
 */
open class BookRepository(private val context: Context? = null) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val indexFile by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "library.json") }
    private val booksDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "books") }
    private val coversDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "covers") }
    private val imagesDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "images") }

    private val indexMutex = Mutex()
    private val _books by lazy { MutableStateFlow(readIndex()) }
    open val books: StateFlow<List<Book>> get() = _books.asStateFlow()

    open fun coverFileFor(book: Book): File? =
        book.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    open suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val c = context ?: throw IOException("Cannot open the selected file")
        val id = UUID.randomUUID().toString()
        val temp = File.createTempFile("import-", null, c.cacheDir)
        try {
            c.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot open the selected file")

            val (format, stored) = BookParsers.detectAndStore(temp, booksDir, id)
            try {
                val metadata = BookParsers.parseMetadata(stored, format)

                val coverFileName = metadata.coverBytes?.let { bytes ->
                    coversDir.mkdirs()
                    val name = "$id.img"
                    File(coversDir, name).writeBytes(bytes)
                    name
                }

                val fallbackTitle = displayNameFor(uri)?.substringBeforeLast('.')
                val book = Book(
                    id = id,
                    title = metadata.title?.takeIf { it.isNotBlank() }
                        ?: fallbackTitle?.takeIf { it.isNotBlank() }
                        ?: "Untitled",
                    author = metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: metadata.author,
                    format = format,
                    fileName = stored.name,
                    coverFileName = coverFileName,
                    addedAtMillis = System.currentTimeMillis(),
                    genres = metadata.genres,
                    series = metadata.series,
                    seriesNumber = metadata.seriesNumber,
                    publisher = metadata.publisher,
                    year = metadata.year,
                    isbn = metadata.isbn,
                    translators = metadata.translators,
                    description = metadata.description,
                    language = metadata.language,
                )
                updateIndex { listOf(book) + it }
                book
            } catch (e: Exception) {
                stored.delete()
                throw e
            }
        } finally {
            temp.delete()
        }
    }

    open suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = _books.value.firstOrNull { it.id == bookId } ?: return@withContext
        updateIndex { books -> books.filterNot { it.id == bookId } }
        File(booksDir, book.fileName).delete()
        book.coverFileName?.let { File(coversDir, it).delete() }
        File(imagesDir, book.id).deleteRecursively()
        val c = context
        if (c != null) {
            File(File(c.filesDir, "pagination"), "$bookId.json").delete()
        }
    }

    open suspend fun loadContent(book: Book): BookContent = withContext(Dispatchers.IO) {
        val file = File(booksDir, book.fileName)
        if (!file.exists()) throw IOException("Book file is missing")
        BookParsers.parseContent(file, book.format, File(imagesDir, book.id))
    }

    /** Stamps the first-opened time once and updates lastOpenedAtMillis. */
    open suspend fun markStarted(bookId: String) {
        val now = System.currentTimeMillis()
        updateIndex { books ->
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        startedAtMillis = book.startedAtMillis ?: now,
                        lastOpenedAtMillis = now,
                    )
                } else {
                    book
                }
            }
        }
    }

    /** Stamps the finished time once (reader reached the end). */
    open suspend fun markFinished(bookId: String) {
        updateIndex { books ->
            books.map { book ->
                if (book.id == bookId && book.finishedAtMillis == null) {
                    book.copy(finishedAtMillis = System.currentTimeMillis())
                } else {
                    book
                }
            }
        }
    }

    open suspend fun addReadingSeconds(bookId: String, seconds: Long) {
        if (seconds <= 0) return
        updateIndex { books ->
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(readingSeconds = book.readingSeconds + seconds)
                } else {
                    book
                }
            }
        }
    }

    /** Stores the book's own reading settings (see Book.readerSettings). */
    open suspend fun saveReaderSettings(bookId: String, settings: ReaderSettings) {
        updateIndex { books ->
            books.map { if (it.id == bookId) it.copy(readerSettings = settings) else it }
        }
    }

    open suspend fun saveProgress(bookId: String, progress: ReadingProgress) {
        updateIndex { books ->
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(progress = progress, lastOpenedAtMillis = System.currentTimeMillis())
                } else {
                    book
                }
            }
        }
    }

    open fun bookById(bookId: String): Book? = _books.value.firstOrNull { it.id == bookId }

    /**
     * Deletes per-book caches whose book no longer exists (leftovers of
     * failed imports or interrupted deletes). Safe: images/ and pagination/
     * are derived data, re-created on the next open. books/ and covers/ are
     * originals and are never touched.
     */
    open suspend fun cleanOrphanCaches() = withContext(Dispatchers.IO) {
        // A corrupt library.json reads as an empty list — don't wipe the
        // (regenerable, but expensive) caches on that failure mode.
        if (indexFile.exists() && _books.value.isEmpty()) return@withContext
        val ids = _books.value.mapTo(HashSet()) { it.id }
        imagesDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in ids) dir.deleteRecursively()
        }
        val c = context
        if (c != null) {
            File(c.filesDir, "pagination").listFiles()?.forEach { file ->
                if (file.extension == "json" && file.nameWithoutExtension !in ids) file.delete()
            }
        }
    }

    /** Updates title/author and optionally replaces the cover with [newCoverUri]. */
    open suspend fun updateBookDetails(
        bookId: String,
        title: String,
        author: String?,
        newCoverUri: Uri?,
    ) = withContext(Dispatchers.IO) {
        val c = context
        val newCoverFileName = newCoverUri?.let { uri ->
            if (c == null) throw IOException("Cannot read the selected image")
            coversDir.mkdirs()
            // A fresh file name each time so image caches don't show stale art.
            val name = "$bookId-${System.currentTimeMillis()}.img"
            c.contentResolver.openInputStream(uri)?.use { input ->
                File(coversDir, name).outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot read the selected image")
            name
        }
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                if (newCoverFileName != null) {
                    book.coverFileName?.let { File(coversDir, it).delete() }
                }
                book.copy(
                    title = title.ifBlank { book.title },
                    author = author?.takeIf { it.isNotBlank() },
                    coverFileName = newCoverFileName ?: book.coverFileName,
                )
            }
        }
    }

    suspend fun toggleBookmark(bookId: String, bookmark: Bookmark) {
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                val existing = book.bookmarks.firstOrNull { it.flatIndex == bookmark.flatIndex }
                book.copy(
                    bookmarks = if (existing != null) {
                        book.bookmarks - existing
                    } else {
                        (book.bookmarks + bookmark).sortedBy { it.flatIndex }
                    },
                )
            }
        }
    }

    suspend fun removeBookmark(bookId: String, bookmarkId: String) {
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId })
            }
        }
    }

    suspend fun addQuote(bookId: String, quote: Quote) {
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                book.copy(quotes = book.quotes + quote)
            }
        }
    }

    suspend fun removeQuote(bookId: String, quoteId: String) {
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                book.copy(quotes = book.quotes.filterNot { it.id == quoteId })
            }
        }
    }

    // ---------------------------------------------------------------- index

    private fun readIndex(): List<Book> = runCatching {
        if (indexFile.exists()) {
            json.decodeFromString<LibraryIndex>(indexFile.readText()).books
                .sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    private suspend fun updateIndex(transform: (List<Book>) -> List<Book>) =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val updated = transform(_books.value)
                    .sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }
                _books.value = updated
                indexFile.writeText(json.encodeToString(LibraryIndex(updated)))
            }
            // Keep the home-screen widget in sync with the library.
            runCatching { context?.let { ContinueReadingWidget().updateAll(it) } }
        }

    private fun displayNameFor(uri: Uri): String? = runCatching {
        context?.contentResolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
