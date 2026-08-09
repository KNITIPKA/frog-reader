package com.example.frogreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookProgress
import com.example.frogreader.data.model.BookRecord
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.ProgressStore
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.UserBookData
import com.example.frogreader.data.model.UserDataStore
import com.example.frogreader.data.model.sortTs
import com.example.frogreader.data.model.toProgress
import com.example.frogreader.data.model.toRecord
import com.example.frogreader.data.model.toUserData
import com.example.frogreader.data.model.withUserData
import androidx.glance.appwidget.updateAll
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.widget.ContinueReadingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

class LibraryIndexCorruptedException(
    message: String,
    cause: Throwable? = null,
) : JsonStoreCorruptedException(message, cause)

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

    /**
     * Set when a store the user cannot afford to lose came back unreadable.
     * While it is true every write is refused, because the alternative is to
     * treat "cannot read" as "there was nothing there" and then persist that.
     * Covers library.json and userdata.json; progress.json is allowed to
     * degrade to empty, since a lost reading position is an annoyance and a
     * lost quote is not.
     */
    @Volatile private var isIndexCorrupted = false

    private val filesRoot: File get() = context?.filesDir ?: File("build/tmp/test_files")

    private val indexStore by lazy {
        AtomicJsonFile(
            file = File(filesRoot, "library.json"),
            json = json,
            serializer = LibraryIndex.serializer(),
            // Sniff on "books" only. A file written before shelves existed has
            // no "shelves" key, and requiring one here would reject every
            // legacy library. Some sniff is essential: every field of
            // LibraryIndex has a default, so an unrelated JSON object would
            // otherwise decode into an empty library and then be written back.
            looksValid = { it.contains("\"books\"") },
        )
    }

    private val userStore by lazy {
        AtomicJsonFile(
            file = File(filesRoot, "userdata.json"),
            json = json,
            serializer = UserDataStore.serializer(),
            looksValid = { it.contains("\"userData\"") },
        )
    }

    private val progressStore by lazy {
        AtomicJsonFile(
            file = File(filesRoot, "progress.json"),
            json = json,
            serializer = ProgressStore.serializer(),
            looksValid = { it.contains("\"progress\"") },
        )
    }

    private val booksDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "books") }
    private val coversDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "covers") }
    private val imagesDir by lazy { File(context?.filesDir ?: File("build/tmp/test_files"), "images") }

    /** The three documents as last read or written, guarded by [indexLock]. */
    private data class Stored(
        val index: LibraryIndex = LibraryIndex(),
        val user: UserDataStore = UserDataStore(),
        val progress: ProgressStore = ProgressStore(),
    )

    /**
     * One disk read shared by everything below — reading a store twice would
     * run its .bak recovery twice and could disagree with itself.
     */
    private val initialStored by lazy {
        try {
            readStored()
        } catch (e: LibraryIndexCorruptedException) {
            Stored()
        }
    }

    private var mutableStored: Stored? = null

    /**
     * Touching this forces the lazy first read, which is what sets
     * [isIndexCorrupted]. Callers must therefore read it BEFORE checking that
     * flag — see the note in [updateStored].
     */
    private fun stored(): Stored = mutableStored ?: initialStored.also { mutableStored = it }

    private val _books by lazy { MutableStateFlow(mergeBooks(initialStored)) }
    open val books: StateFlow<List<Book>> get() = _books.asStateFlow()

    private val _shelves by lazy { MutableStateFlow(initialStored.index.shelves) }
    open val shelves: StateFlow<List<Shelf>> get() = _shelves.asStateFlow()

    /** Puts the three documents back together into the [Book]s callers expect. */
    private fun mergeBooks(from: Stored): List<Book> =
        from.index.books
            .map { it.withUserData(from.user.userData[it.id], from.progress.progress[it.id]) }
            .sortedByDescending { it.sortTs }

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
        if (cachedContentId == bookId) releaseContentCache()
        updateIndex { books -> books.filterNot { it.id == bookId } }
        File(booksDir, book.fileName).delete()
        book.coverFileName?.let { File(coversDir, it).delete() }
        File(imagesDir, book.id).deleteRecursively()
        val c = context
        if (c != null) {
            File(File(c.filesDir, "pagination"), "$bookId.json").delete()
        }
    }

    /**
     * The last book that was parsed, kept whole.
     *
     * Parsing is 90% of the time it takes to open a book — 2.7s for a 4MB EPUB
     * on a Pixel 9a — and the reader's ViewModel dies with its back-stack
     * entry, so backing out and stepping straight back in used to pay all of it
     * again. One entry, not two: a parsed book is tens of megabytes of
     * AnnotatedStrings, and "leave and come back" is far more common than
     * alternating between two books.
     *
     * Not a SoftReference — ART clears those eagerly and unpredictably, which
     * would drop the cache exactly when the device is busy and the user would
     * feel it most. [releaseContentCache] handles memory pressure explicitly.
     */
    private var cachedContentId: String? = null
    private var cachedContent: BookContent? = null

    /** Parses already running, so a second asker joins instead of repeating. */
    private val inFlight = HashMap<String, Deferred<BookContent>>()

    open suspend fun loadContent(book: Book): BookContent {
        synchronized(contentLock) {
            if (cachedContentId == book.id) cachedContent else null
        }?.let { return it }

        // The parse runs on the repository's own scope, not the caller's. Two
        // things fall out of that, and both matter now that the library
        // pre-loads the "continue reading" book in the background: a tap on
        // that same book JOINS the parse already running instead of starting a
        // second one, and backing out mid-open no longer throws the work away —
        // `parseContent` has no suspension points, so it cannot be interrupted
        // anyway. It finishes, caches, and the next tap is instant.
        val parse = synchronized(contentLock) {
            inFlight.getOrPut(book.id) {
                bookkeeping.async { parseAndCache(book) }
            }
        }
        try {
            return parse.await()
        } finally {
            synchronized(contentLock) { inFlight.remove(book.id, parse) }
        }
    }

    private fun parseAndCache(book: Book): BookContent {
        val file = File(booksDir, book.fileName)
        if (!file.exists()) throw IOException("Book file is missing")
        val content = BookParsers.parseContent(file, book.format, File(imagesDir, book.id))
        synchronized(contentLock) {
            cachedContentId = book.id
            cachedContent = content
        }
        return content
    }

    private val contentLock = Any()

    /** Drops the parsed-book cache — called when the system asks for memory. */
    fun releaseContentCache() {
        synchronized(contentLock) {
            cachedContentId = null
            cachedContent = null
        }
    }

    /**
     * For bookkeeping that must finish even when the screen that asked for it
     * is already gone. A ViewModel scope dies with its back-stack entry, so a
     * quick look into a book and straight back out would lose the "last opened"
     * stamp — and the home-screen widget with it.
     */
    private val bookkeeping = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Records that a book was opened, without the reader waiting for it.
     *
     * Both writes land in [updateSnapshot]: a full re-serialize of the library
     * index, an fsync, a `.bak` copy, an atomic move and a Glance widget
     * rebuild. Awaiting that before the book was even read off disk cost
     * 60-150ms of every single open, measured.
     *
     * Nothing on screen depends on the result. Until the settings write lands,
     * the reader falls back to the app-wide settings — which is the very value
     * being written.
     */
    open fun noteOpened(bookId: String, defaultSettings: suspend () -> ReaderSettings) {
        bookkeeping.launch {
            runCatching { markStarted(bookId) }
            // First open pins the current settings as THIS book's own: from
            // now on, changes made in other books cannot touch it.
            if (bookById(bookId)?.readerSettings == null) {
                runCatching { saveReaderSettings(bookId, defaultSettings()) }
            }
        }
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
        if (isIndexCorrupted || (indexStore.file.exists() && _books.value.isEmpty())) return@withContext
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
        updateShelves { shelves -> shelves + shelf }
        return _shelves.value.firstOrNull { it.id == shelf.id }
    }

    open suspend fun renameShelf(id: String, name: String): Unit = updateShelves { shelves ->
        shelves.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    /** Moves [bookId] into [shelfId], taking it out of whatever shelf held it. */
    open suspend fun addToShelf(shelfId: String, bookId: String): Unit = updateShelves { shelves ->
        if (shelves.none { it.id == shelfId }) return@updateShelves shelves
        shelves.map { shelf ->
            if (shelf.id == shelfId) {
                // Append, so the anchor stays at [0].
                if (bookId in shelf.bookIds) shelf else shelf.copy(bookIds = shelf.bookIds + bookId)
            } else {
                shelf.copy(bookIds = shelf.bookIds - bookId)
            }
        }
    }

    /** Takes [bookId] back to the top level; a shelf left with <2 books dissolves. */
    open suspend fun removeFromShelf(shelfId: String, bookId: String): Unit = updateShelves { shelves ->
        shelves.map { shelf ->
            if (shelf.id == shelfId) shelf.copy(bookIds = shelf.bookIds - bookId) else shelf
        }
    }

    /** Dissolves the shelf; its books return to the top level untouched. */
    open suspend fun deleteShelf(id: String): Unit = updateShelves { shelves ->
        shelves.filterNot { it.id == id }
    }

    // ---------------------------------------------------------------- index

    /** Books only — the shape most callers (and the existing tests) want. */
    internal fun readIndex(): List<Book> = mergeBooks(readStored())

    /**
     * Reads all three documents, recovering each from its own .bak as needed.
     *
     * The two that hold something irreplaceable propagate corruption; a damaged
     * progress.json only costs reading positions, so it degrades to empty and
     * gets rewritten on the next page turn.
     */
    private fun readStored(): Stored = synchronized(indexLock) {
        val index = try {
            indexStore.read() ?: LibraryIndex()
        } catch (e: JsonStoreCorruptedException) {
            isIndexCorrupted = true
            throw LibraryIndexCorruptedException(
                e.message ?: "The library index is corrupted or unreadable.",
                e,
            )
        }
        val user = try {
            userStore.read() ?: UserDataStore()
        } catch (e: JsonStoreCorruptedException) {
            isIndexCorrupted = true
            throw LibraryIndexCorruptedException(
                e.message ?: "The user data store is corrupted or unreadable.",
                e,
            )
        }
        isIndexCorrupted = false

        // Sanitize in memory only — writing back here would run during the
        // lazy first read, outside the corruption guards.
        Stored(
            index = LibraryIndex(index.books, normalizeShelves(index.shelves, index.books)),
            user = user,
            progress = progressStore.readOrDefault(ProgressStore()),
        )
    }

    /**
     * The single place every shelf invariant is enforced, applied on read and
     * after every write transform: unknown book ids are dropped, a book can be
     * claimed by at most one shelf, and a shelf left with fewer than two books
     * dissolves. This is why [deleteBook] needs no shelf-specific code.
     */
    private fun normalizeShelves(shelves: List<Shelf>, books: List<BookRecord>): List<Shelf> {
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

    /**
     * Transforms the library as whole [Book]s — the shape every caller wants —
     * and lets [updateStored] work out which of the three documents that
     * actually touched.
     */
    private suspend fun updateIndex(transform: (List<Book>) -> List<Book>): Unit =
        updateStored { before -> split(transform(mergeBooks(before)), before) }

    /** Shelf-only transform; the books come along untouched. */
    private suspend fun updateShelves(transform: (List<Shelf>) -> List<Shelf>): Unit =
        updateStored { before ->
            before.copy(index = before.index.copy(shelves = transform(before.index.shelves)))
        }

    /** Takes whole [Book]s apart into the three documents they are stored as. */
    private fun split(books: List<Book>, before: Stored): Stored {
        val user = LinkedHashMap<String, UserBookData>(books.size)
        val progress = LinkedHashMap<String, BookProgress>(books.size)
        books.forEach { book ->
            // Books with nothing to say are left out entirely, which keeps both
            // documents small and makes deletion cascade for free: a book that
            // is no longer in the list cannot leave a key behind.
            book.toUserData().takeIf { !it.isEmpty }?.let { user[book.id] = it }
            book.toProgress().takeIf { !it.isEmpty }?.let { progress[book.id] = it }
        }
        return before.copy(
            index = before.index.copy(books = books.map { it.toRecord() }),
            user = UserDataStore(user),
            progress = ProgressStore(progress),
        )
    }

    /**
     * One transaction across all three documents: same lock, same atomic
     * tmp → json → bak write for each, both StateFlows published together.
     *
     * Only the documents that actually changed are written, decided by
     * comparing before and after rather than by asking each caller to declare
     * what it touched. That is the whole point of the split — a settled page
     * turn rewrites the small position map and leaves the user's quotes, and
     * every byte of book metadata, untouched on disk.
     */
    private suspend fun updateStored(transform: (Stored) -> Stored): Unit =
        withContext(Dispatchers.IO) {
            val libraryOrProgressChanged = synchronized(indexLock) {
                // ORDER MATTERS: stored() forces the lazy disk read, and that
                // read is what sets isIndexCorrupted. Hoisting the guard above
                // this line lets a direct update (with no prior read) overwrite
                // a corrupted store with an empty one — see
                // BookRepositoryStressTest's ..._DirectUpdateWithoutPriorRead.
                val before = stored()
                if (isIndexCorrupted) {
                    throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
                }

                val out = transform(before)
                val shelves = normalizeShelves(out.index.shelves, out.index.books)
                // Re-merge before writing so the records land on disk in the
                // same order the library shows them. Order is not load-bearing —
                // reads sort anyway — but a file meant to be readable by hand
                // should not shuffle itself on every write.
                val merged = mergeBooks(out.copy(index = out.index.copy(shelves = shelves)))
                val after = out.copy(
                    index = LibraryIndex(merged.map { it.toRecord() }, shelves),
                )

                mutableStored = after
                _books.value = merged
                _shelves.value = shelves

                if (after.index != before.index) indexStore.write(after.index)
                if (after.user != before.user) userStore.write(after.user)
                if (after.progress != before.progress) progressStore.write(after.progress)

                after.index != before.index || after.progress != before.progress
            }
            // Keep the home-screen widget in sync. Quotes and bookmarks never
            // appear on it, so a userdata-only change does not rebuild it.
            if (libraryOrProgressChanged) {
                runCatching { context?.let { ContinueReadingWidget().updateAll(it) } }
            }
        }

    private fun displayNameFor(uri: Uri): String? = runCatching {
        context?.contentResolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
