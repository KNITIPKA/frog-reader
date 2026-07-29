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
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.sortTs
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class LibraryIndexCorruptedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

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

    private val indexLock = Any()
    @Volatile private var isIndexCorrupted = false

    private val indexFile by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "library.json") }
    private val bakFile by lazy { File(indexFile.parentFile ?: File("build/tmp/test_files"), "library.json.bak") }
    private val tmpFile by lazy { File(indexFile.parentFile ?: File("build/tmp/test_files"), "library.json.tmp") }
    private val booksDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "books") }
    private val coversDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "covers") }
    private val imagesDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "images") }

    /**
     * One disk read shared by [_books] and [_shelves] — reading the index twice
     * would run the .bak recovery twice and could disagree with itself.
     */
    private val initialIndex by lazy {
        try {
            readSnapshot()
        } catch (e: LibraryIndexCorruptedException) {
            LibraryIndex()
        }
    }

    private val _books by lazy { MutableStateFlow(initialIndex.books) }
    open val books: StateFlow<List<Book>> get() = _books.asStateFlow()

    private val _shelves by lazy { MutableStateFlow(initialIndex.shelves) }
    open val shelves: StateFlow<List<Shelf>> get() = _shelves.asStateFlow()

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
        if (isIndexCorrupted || (indexFile.exists() && _books.value.isEmpty())) return@withContext
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

    // --------------------------------------------------------------- shelves

    /**
     * Groups [bookIds] into a new shelf and returns it, or null when fewer than
     * two of the ids resolve to real books (a one-book shelf doesn't exist).
     *
     * **`bookIds[0]` is the anchor**: the shelf inherits its grid position, so
     * the caller passes the DROP TARGET first and the dragged book second.
     */
    open suspend fun createShelf(bookIds: List<String>, name: String = ""): Shelf? {
        val byId = _books.value.associateBy { it.id }
        val ids = bookIds.distinct().filter { it in byId }
        if (ids.size < 2) return null
        val shelf = Shelf(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            bookIds = ids,
            createdAtMillis = System.currentTimeMillis(),
            sortKey = byId.getValue(ids.first()).sortTs,
        )
        updateSnapshot { index -> index.copy(shelves = index.shelves + shelf) }
        return _shelves.value.firstOrNull { it.id == shelf.id }
    }

    open suspend fun renameShelf(id: String, name: String): Unit = updateSnapshot { index ->
        index.copy(
            shelves = index.shelves.map { if (it.id == id) it.copy(name = name.trim()) else it },
        )
    }

    /** Moves [bookId] into [shelfId], taking it out of whatever shelf held it. */
    open suspend fun addToShelf(shelfId: String, bookId: String): Unit = updateSnapshot { index ->
        if (index.shelves.none { it.id == shelfId }) return@updateSnapshot index
        index.copy(
            shelves = index.shelves.map { shelf ->
                if (shelf.id == shelfId) {
                    // Append, so the anchor stays at [0].
                    if (bookId in shelf.bookIds) shelf else shelf.copy(bookIds = shelf.bookIds + bookId)
                } else {
                    shelf.copy(bookIds = shelf.bookIds - bookId)
                }
            },
        )
    }

    /** Takes [bookId] back to the top level; a shelf left with <2 books dissolves. */
    open suspend fun removeFromShelf(shelfId: String, bookId: String): Unit = updateSnapshot { index ->
        index.copy(
            shelves = index.shelves.map { shelf ->
                if (shelf.id == shelfId) shelf.copy(bookIds = shelf.bookIds - bookId) else shelf
            },
        )
    }

    /** Dissolves the shelf; its books return to the top level untouched. */
    open suspend fun deleteShelf(id: String): Unit = updateSnapshot { index ->
        index.copy(shelves = index.shelves.filterNot { it.id == id })
    }

    // ---------------------------------------------------------------- index

    /** Books only — the shape most callers (and the existing tests) want. */
    internal fun readIndex(): List<Book> = readSnapshot().books

    /** Reads books AND shelves, with the same .bak recovery as before. */
    internal fun readSnapshot(): LibraryIndex = synchronized(indexLock) {
        if (!indexFile.exists()) {
            if (bakFile.exists()) {
                val bakIndex = parseIndexFile(bakFile)
                if (bakIndex != null) {
                    runCatching { bakFile.copyTo(indexFile, overwrite = true) }
                    isIndexCorrupted = false
                    return@synchronized bakIndex
                } else {
                    isIndexCorrupted = true
                    throw LibraryIndexCorruptedException("library.json is missing and library.json.bak is corrupted.")
                }
            } else {
                isIndexCorrupted = false
                return@synchronized LibraryIndex()
            }
        }

        val mainIndex = parseIndexFile(indexFile)
        if (mainIndex != null) {
            isIndexCorrupted = false
            return@synchronized mainIndex
        }

        if (bakFile.exists()) {
            val bakIndex = parseIndexFile(bakFile)
            if (bakIndex != null) {
                runCatching { bakFile.copyTo(indexFile, overwrite = true) }
                isIndexCorrupted = false
                return@synchronized bakIndex
            }
        }

        isIndexCorrupted = true
        throw LibraryIndexCorruptedException("Both library.json and library.json.bak are corrupted or unreadable.")
    }

    private fun parseIndexFile(file: File): LibraryIndex? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            // Sniff on "books" only. A file written before shelves existed has
            // no "shelves" key, and requiring one here would reject every
            // legacy library.
            if (!text.contains("\"books\"")) return null
            val index = json.decodeFromString<LibraryIndex>(text)
            val books = index.books.sortedByDescending { it.sortTs }
            // Sanitize in memory only — writing back here would run during the
            // lazy first read, outside the corruption guards.
            LibraryIndex(books, normalizeShelves(index.shelves, books))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The single place every shelf invariant is enforced, applied on read and
     * after every write transform: unknown book ids are dropped, a book can be
     * claimed by at most one shelf, and a shelf left with fewer than two books
     * dissolves. This is why [deleteBook] needs no shelf-specific code.
     */
    private fun normalizeShelves(shelves: List<Shelf>, books: List<Book>): List<Shelf> {
        if (shelves.isEmpty()) return emptyList()
        val known = books.mapTo(HashSet()) { it.id }
        val claimed = HashSet<String>()
        val seenShelfIds = HashSet<String>()
        return shelves
            .filter { seenShelfIds.add(it.id) }
            // `claimed.add` is a side-effecting predicate: correct only because
            // filter/map are ordered — the earlier shelf wins a contested book.
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds.filter { it in known && claimed.add(it) }) }
            .filter { it.bookIds.size >= 2 }
            .sortedWith(compareByDescending<Shelf> { it.sortTs }.thenBy { it.id })
    }

    /** Books-only transform; the shelves come along untouched. */
    private suspend fun updateIndex(transform: (List<Book>) -> List<Book>): Unit =
        updateSnapshot { index -> index.copy(books = transform(index.books)) }

    /**
     * Books and shelves in one transaction: same lock, same atomic
     * tmp → json → bak write, both StateFlows published together.
     *
     * Deliberately a different NAME rather than an overload of [updateIndex]:
     * `(List<Book>) -> List<Book>` and `(LibraryIndex) -> LibraryIndex` both
     * erase to Function1, so two `updateIndex` overloads would be a platform
     * declaration clash.
     */
    private suspend fun updateSnapshot(transform: (LibraryIndex) -> LibraryIndex): Unit =
        withContext(Dispatchers.IO) {
            synchronized(indexLock) {
                // ORDER MATTERS: touching _books forces the lazy disk read, and
                // that read is what sets isIndexCorrupted. Hoisting the guard
                // above this line lets a direct update (with no prior read)
                // overwrite a corrupted index with an empty one — see
                // BookRepositoryStressTest's ..._DirectUpdateWithoutPriorRead.
                val current = LibraryIndex(_books.value, _shelves.value)
                if (isIndexCorrupted) {
                    throw LibraryIndexCorruptedException("Cannot update library index because the index on disk is corrupted.")
                }
                val out = transform(current)
                val updated = out.books.sortedByDescending { it.sortTs }
                val updatedShelves = normalizeShelves(out.shelves, updated)
                _books.value = updated
                _shelves.value = updatedShelves

                val parentDir = indexFile.parentFile ?: File("build/tmp/test_files")
                parentDir.mkdirs()

                val jsonString = json.encodeToString(LibraryIndex(updated, updatedShelves))
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(jsonString.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }

                if (indexFile.exists() && indexFile.length() > 0) {
                    indexFile.copyTo(bakFile, overwrite = true)
                }

                try {
                    Files.move(
                        tmpFile.toPath(),
                        indexFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    if (!tmpFile.renameTo(indexFile)) {
                        tmpFile.copyTo(indexFile, overwrite = true)
                        tmpFile.delete()
                    }
                }
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
