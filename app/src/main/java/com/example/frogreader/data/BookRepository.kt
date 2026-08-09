package com.example.frogreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookProgress
import com.example.frogreader.data.model.BookRecord
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.ProgressStore
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.Timestamped
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
import java.io.InputStream
import java.util.UUID

class LibraryIndexCorruptedException(
    message: String,
    cause: Throwable? = null,
) : JsonStoreCorruptedException(message, cause)

/** How long a deletion is remembered; see [UserDataStore.deletedIds]. */
private const val TOMBSTONE_LIFETIME_MILLIS = 365L * 24 * 60 * 60 * 1000

/**
 * How long a staged file may sit undecided before a sweep treats it as
 * abandoned. Far longer than any decision takes, so a sweep can never race an
 * import that is still live; short enough that a process killed mid-dialog does
 * not leave a book-sized file behind for good.
 */
private const val STAGING_LIFETIME_MILLIS = 30L * 60 * 1000

/** The title a book gets when neither the file nor its name offers one. */
private const val UNTITLED = "Untitled"

/**
 * Flattens a title or an author down to what two people would agree is "the
 * same" — case, punctuation and spacing all removed, since one source writes
 * "Dostoyevsky, Fyodor" and another "Fyodor  Dostoyevsky.".
 */
internal fun normalizeForMatch(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val out = StringBuilder(text.length)
    var pendingSpace = false
    for (ch in text.lowercase()) {
        when {
            ch.isLetterOrDigit() -> {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(ch)
            }
            // Punctuation and whitespace alike collapse to a single gap, so
            // "Anna Karenina" and "anna-karenina" land on the same string.
            else -> pendingSpace = true
        }
    }
    return out.toString()
}

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
    private fun mergeBooks(from: Stored): List<Book> {
        // Grouped once rather than filtered per book: with a few thousand
        // quotes, per-book filtering would make every write quadratic.
        val quotesByBook = from.user.quotes.values.groupBy { it.bookId }
        val bookmarksByBook = from.user.bookmarks.values.groupBy { it.bookId }
        return from.index.books
            .map { record ->
                record.withUserData(
                    user = from.user.userData[record.id],
                    prog = from.progress.progress[record.id],
                    quotes = quotesByBook[record.id]?.sortedBy { it.createdAtMillis } ?: emptyList(),
                    bookmarks = bookmarksByBook[record.id]?.sortedBy { it.flatIndex } ?: emptyList(),
                )
            }
            .sortedByDescending { it.sortTs }
    }

    open fun coverFileFor(book: Book): File? =
        book.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    /** Adds a book outright. Stage and commit in one call, for callers with no question to ask. */
    open suspend fun importBook(uri: Uri): Book {
        val staged = stageImport(uri)
        return try {
            commitImport(staged, ImportMode.New)
        } catch (e: Exception) {
            discardImport(staged)
            throw e
        }
    }

    /**
     * Works out what a file is, and whether the library already has it, without
     * writing anything.
     *
     * The file is copied in and normalized here rather than at commit time, so
     * the answer to "is this a duplicate" is about the bytes that would actually
     * be stored — a `.fb2.zip` and the `.fb2` inside it are the same book, and
     * only the unpacked form can say so.
     *
     * The caller owns what comes back: every path has to end in [commitImport]
     * or [discardImport].
     */
    open suspend fun stageImport(uri: Uri): StagedImport = withContext(Dispatchers.IO) {
        val stagingId = UUID.randomUUID().toString()
        val temp = createTempFile("import-")
        try {
            openStream(uri).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }

            val (format, stored) = BookParsers.detectAndStore(temp, stagingDir, stagingId)
            try {
                val metadata = BookParsers.parseMetadata(stored, format)
                val title = metadata.title?.takeIf { it.isNotBlank() }
                    ?: displayNameFor(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                    ?: UNTITLED
                val author = metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: metadata.author
                val hash = ContentHash.of(stored)
                val size = stored.length()

                StagedImport(
                    file = stored,
                    metadata = metadata,
                    format = format,
                    title = title,
                    author = author,
                    contentHash = hash,
                    sizeBytes = size,
                    coverBytes = metadata.coverBytes,
                    duplicateOf = findDuplicate(hash, size, title, author),
                )
            } catch (e: Exception) {
                stored.delete()
                throw e
            }
        } finally {
            temp.delete()
        }
    }

    /** Throws away a staged file the user decided against. */
    open suspend fun discardImport(staged: StagedImport) = withContext(Dispatchers.IO) {
        staged.file.delete()
        Unit
    }

    /**
     * Puts a staged file into the library, as a new book or over an existing one.
     */
    open suspend fun commitImport(staged: StagedImport, mode: ImportMode): Book =
        withContext(Dispatchers.IO) {
            when (mode) {
                ImportMode.New, ImportMode.Clone -> commitAsNewBook(staged)
                is ImportMode.Replace -> commitOverBook(staged, mode.bookId)
            }
        }

    private suspend fun commitAsNewBook(staged: StagedImport): Book {
        val id = UUID.randomUUID().toString()
        booksDir.mkdirs()
        val stored = File(booksDir, "$id.${staged.file.extension}")
        moveFile(staged.file, stored)
        try {
            val book = Book(
                id = id,
                title = staged.title,
                author = staged.author,
                format = staged.format,
                fileName = stored.name,
                coverFileName = writeCover(id, staged.coverBytes),
                addedAtMillis = System.currentTimeMillis(),
                contentHash = staged.contentHash,
                sizeBytes = staged.sizeBytes,
                genres = staged.metadata.genres,
                series = staged.metadata.series,
                seriesNumber = staged.metadata.seriesNumber,
                publisher = staged.metadata.publisher,
                year = staged.metadata.year,
                isbn = staged.metadata.isbn,
                translators = staged.metadata.translators,
                description = staged.metadata.description,
                language = staged.metadata.language,
            )
            updateIndex { listOf(book) + it }
            return book
        } catch (e: Exception) {
            stored.delete()
            throw e
        }
    }

    /**
     * Swaps the file under an existing record.
     *
     * Order matters: the new file lands under a name of its own and the index
     * write happens FIRST; only once that has succeeded is the old file removed.
     * Overwriting in place would leave a record pointing at a half-written file
     * if the write failed — and the new file may not even share the old one's
     * extension, since the replacement can be an FB2 where the original was an
     * EPUB.
     *
     * Everything the user made is untouched. It lives in the other two
     * documents keyed by this id, and mapping only the metadata fields here
     * means `split` produces byte-identical user and progress documents, so
     * `updateStored` does not even write them.
     */
    private suspend fun commitOverBook(staged: StagedImport, bookId: String): Book {
        val existing = bookById(bookId) ?: throw IOException("No such book")
        booksDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val stored = File(booksDir, "$bookId-$stamp.${staged.file.extension}")
        moveFile(staged.file, stored)

        val newCover = try {
            // A fresh name each time, so image caches keyed on the path cannot
            // keep showing the cover of the file that was just replaced.
            writeCover("$bookId-$stamp", staged.coverBytes)
        } catch (e: Exception) {
            stored.delete()
            throw e
        }

        try {
            updateIndex { books ->
                books.map { book ->
                    if (book.id != bookId) {
                        book
                    } else {
                        book.withParsedMetadata(
                            metadata = staged.metadata,
                            format = staged.format,
                            fileName = stored.name,
                            coverFileName = newCover ?: book.coverFileName,
                            contentHash = staged.contentHash,
                            sizeBytes = staged.sizeBytes,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            stored.delete()
            newCover?.let { File(coversDir, it).delete() }
            throw e
        }

        // Only now: until the index write landed, these were the live files.
        existing.fileName?.takeIf { it != stored.name }?.let { File(booksDir, it).delete() }
        if (newCover != null) {
            existing.coverFileName?.takeIf { it != newCover }?.let { File(coversDir, it).delete() }
        }
        purgeDerived(bookId)
        return bookById(bookId) ?: existing
    }

    /** Writes cover art under `<name>.img`, or returns null when there is none. */
    private fun writeCover(name: String, bytes: ByteArray?): String? {
        if (bytes == null) return null
        coversDir.mkdirs()
        val fileName = "$name.img"
        File(coversDir, fileName).writeBytes(bytes)
        return fileName
    }

    /**
     * Drops everything derived from a book's file: extracted images and the
     * pagination cache, plus the parsed-content cache if this is the book in it.
     * All of it is rebuilt on the next open, and all of it describes a file that
     * is no longer there.
     */
    private fun purgeDerived(bookId: String) {
        if (cachedContentId == bookId) releaseContentCache()
        File(imagesDir, bookId).deleteRecursively()
        context?.let { File(File(it.filesDir, "pagination"), "$bookId.json").delete() }
    }

    /**
     * The book in the library that [hash]/[title]/[author] describes, if any.
     *
     * Deliberately cheap in the common case. An exact hash match is a map
     * lookup. Only when that fails does it consider hashing anything, and only
     * files whose SIZE already matches — one stat per book, against reading
     * every book in the library from disk. A library of a thousand books
     * normally costs a thousand `length()` calls and zero reads.
     */
    private suspend fun findDuplicate(
        hash: String,
        size: Long,
        title: String,
        author: String?,
    ): DuplicateOf? {
        val books = _books.value
        books.firstOrNull { it.contentHash == hash }?.let {
            return DuplicateOf(it, DuplicateMatch.SAME_FILE)
        }

        // Books stored before hashes existed. Backfilling here rather than in a
        // sweep at startup means the cost is paid only by libraries that
        // actually meet a possible duplicate, and only for the handful of books
        // that could be one.
        val backfilled = HashMap<String, String>()
        for (book in books) {
            if (book.contentHash != null) continue
            val file = bookFileFor(book) ?: continue
            val knownSize = book.sizeBytes.takeIf { it != 0L } ?: file.length()
            if (knownSize != size) continue
            val computed = runCatching { ContentHash.of(file) }.getOrNull() ?: continue
            backfilled[book.id] = computed
        }
        if (backfilled.isNotEmpty()) {
            // One write for all of them; it touches library.json only, since
            // nothing about the user's data or their reading position changed.
            updateIndex { current ->
                current.map { book ->
                    val computed = backfilled[book.id]
                    if (computed == null) {
                        book
                    } else {
                        book.copy(
                            contentHash = computed,
                            sizeBytes = book.sizeBytes.takeIf { it != 0L }
                                ?: bookFileFor(book)?.length() ?: 0L,
                        )
                    }
                }
            }
            _books.value.firstOrNull { backfilled[it.id] == hash }?.let {
                return DuplicateOf(it, DuplicateMatch.SAME_FILE)
            }
        }

        val wantedTitle = normalizeForMatch(title)
        if (wantedTitle.isEmpty()) return null
        val wantedAuthor = normalizeForMatch(author)
        return _books.value
            .firstOrNull { book ->
                normalizeForMatch(book.title) == wantedTitle &&
                    // An unknown author on either side is not a disagreement:
                    // the same book parsed from two formats often names the
                    // author in only one of them.
                    (wantedAuthor.isEmpty() ||
                        normalizeForMatch(book.author).isEmpty() ||
                        normalizeForMatch(book.author) == wantedAuthor)
            }
            ?.let { DuplicateOf(it, DuplicateMatch.SAME_BOOK) }
    }

    /**
     * Merges what a file says into a record the user may already have edited.
     *
     * The rule, shared with [attachFile]: what the user has wins for title and
     * author — they may have corrected them — and the file fills in anything
     * that was empty.
     */
    private fun Book.withParsedMetadata(
        metadata: BookMetadata,
        format: BookFormat,
        fileName: String,
        coverFileName: String?,
        contentHash: String?,
        sizeBytes: Long,
    ): Book = copy(
        author = author ?: metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: metadata.author,
        format = format,
        fileName = fileName,
        coverFileName = coverFileName,
        contentHash = contentHash,
        sizeBytes = sizeBytes,
        genres = genres.ifEmpty { metadata.genres },
        series = series ?: metadata.series,
        seriesNumber = seriesNumber ?: metadata.seriesNumber,
        publisher = publisher ?: metadata.publisher,
        year = year ?: metadata.year,
        isbn = isbn ?: metadata.isbn,
        translators = translators.ifEmpty { metadata.translators },
        description = description ?: metadata.description,
        language = language ?: metadata.language,
    )

    private fun moveFile(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun createTempFile(prefix: String): File {
        val dir = context?.cacheDir ?: File(filesRoot, "cache").apply { mkdirs() }
        return File.createTempFile(prefix, null, dir)
    }

    /**
     * Where a staged file waits for the user's decision.
     *
     * Under filesDir, not cacheDir: the system is free to empty the cache under
     * memory pressure, and doing so while a "you already have this book" dialog
     * is on screen would delete the file the dialog is about.
     */
    private val stagingDir by lazy { File(filesRoot, "staging") }

    /**
     * Deletes staged files nobody is waiting on any more — what a process killed
     * between "which book is this?" and the user's answer leaves behind.
     */
    private fun sweepStaging() {
        val deadline = System.currentTimeMillis() - STAGING_LIFETIME_MILLIS
        stagingDir.listFiles()?.forEach { file ->
            if (file.lastModified() < deadline) file.delete()
        }
    }

    /** The only place a content URI is read. Overridden in tests. */
    internal open fun openStream(uri: Uri): InputStream =
        context?.contentResolver?.openInputStream(uri)
            ?: throw IOException("Cannot open the selected file")

    open suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = _books.value.firstOrNull { it.id == bookId } ?: return@withContext
        if (cachedContentId == bookId) releaseContentCache()
        updateIndex { books -> books.filterNot { it.id == bookId } }
        book.fileName?.let { File(booksDir, it).delete() }
        book.coverFileName?.let { File(coversDir, it).delete() }
        purgeDerived(bookId)
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
        val name = book.fileName ?: throw IOException("This book has no file attached yet")
        val file = File(booksDir, name)
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
                        // Opening a book is what moves it off the want-to-read
                        // list. A book already finished or abandoned keeps that
                        // status until the user says otherwise.
                        status = when (book.status) {
                            ReadingStatus.NONE, ReadingStatus.WANT_TO_READ -> ReadingStatus.READING
                            else -> book.status
                        },
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
                    book.copy(
                        finishedAtMillis = System.currentTimeMillis(),
                        status = ReadingStatus.FINISHED,
                    )
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

    /** Moves a book between the reading lists. */
    open suspend fun setStatus(bookId: String, status: ReadingStatus) {
        updateIndex { books ->
            books.map { if (it.id == bookId) it.copy(status = status) else it }
        }
    }

    /** [stars] is 1..5, or null to clear the rating. */
    open suspend fun setRating(bookId: String, stars: Int?) {
        val clamped = stars?.coerceIn(1, 5)
        updateIndex { books ->
            books.map { if (it.id == bookId) it.copy(rating = clamped) else it }
        }
    }

    open suspend fun setReview(bookId: String, review: String?) {
        val text = review?.takeIf { it.isNotBlank() }
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                if (book.review == text) return@map book
                book.copy(review = text, reviewUpdatedAtMillis = System.currentTimeMillis())
            }
        }
    }

    /** The user's own note about a saved quote. */
    open suspend fun setQuoteNote(bookId: String, quoteId: String, note: String?) {
        val text = note?.takeIf { it.isNotBlank() }
        updateIndex { books ->
            books.map { book ->
                if (book.id != bookId) return@map book
                book.copy(
                    quotes = book.quotes.map { if (it.id == quoteId) it.copy(note = text) else it },
                )
            }
        }
    }

    /**
     * Adds a book the user wants to read but has no file for.
     *
     * The same shape as a book restored from a data-only backup: a real record
     * with no [Book.fileName], which [attachFile] can later fill in without
     * disturbing anything written about it in the meantime.
     */
    open suspend fun addWishlistBook(title: String, author: String? = null): Book {
        val book = Book(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Untitled" },
            author = author?.takeIf { it.isNotBlank() },
            format = BookFormat.EPUB,
            fileName = null,
            addedAtMillis = System.currentTimeMillis(),
            status = ReadingStatus.WANT_TO_READ,
        )
        updateIndex { listOf(book) + it }
        return book
    }

    /**
     * Puts a whole book into the library exactly as given, replacing any record
     * with the same id. What restoring a backup is made of.
     */
    internal suspend fun addBookForRestore(book: Book) {
        updateIndex { books -> listOf(book) + books.filterNot { it.id == book.id } }
    }

    /** The book's own file, or null when it has none or the file is gone. */
    internal fun bookFileFor(book: Book): File? =
        book.fileName?.let { File(booksDir, it) }?.takeIf { it.exists() }

    /**
     * Swaps the entire library for [books] and [shelves].
     *
     * Restoring a backup is a replacement, not a merge, so book files that no
     * longer belong to any record are swept: the user asked for the library to
     * become this one, and leaving gigabytes of unreferenced files behind would
     * make "restore" quietly mean "restore and also keep everything else".
     */
    internal suspend fun replaceAll(books: List<Book>, shelves: List<Shelf>) {
        updateStored { before ->
            split(books, before).copy(
                index = LibraryIndex(books.map { it.toRecord() }, shelves),
            )
        }
        withContext(Dispatchers.IO) {
            val keptBooks = books.mapNotNullTo(HashSet()) { it.fileName }
            val keptCovers = books.mapNotNullTo(HashSet()) { it.coverFileName }
            val keptIds = books.mapTo(HashSet()) { it.id }
            booksDir.listFiles()?.forEach { if (it.name !in keptBooks) it.delete() }
            coversDir.listFiles()?.forEach { if (it.name !in keptCovers) it.delete() }
            imagesDir.listFiles()?.forEach { if (it.isDirectory && it.name !in keptIds) it.deleteRecursively() }
            context?.let { c ->
                File(c.filesDir, "pagination").listFiles()?.forEach {
                    if (it.nameWithoutExtension !in keptIds) it.delete()
                }
            }
            releaseContentCache()
        }
    }

    /**
     * Binds a file to a book that did not have one, keeping everything the user
     * has already written about it.
     *
     * Deliberately not importBook: that mints a new id, which would strand the
     * quotes, the rating and the reading position on the old record.
     */
    open suspend fun attachFile(bookId: String, uri: Uri): Book = withContext(Dispatchers.IO) {
        val c = context ?: throw IOException("Cannot open the selected file")
        val existing = bookById(bookId) ?: throw IOException("No such book")
        if (existing.fileName != null) throw IOException("This book already has a file")

        val temp = File.createTempFile("attach-", null, c.cacheDir)
        try {
            c.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot open the selected file")

            val (format, stored) = BookParsers.detectAndStore(temp, booksDir, bookId)
            try {
                val metadata = BookParsers.parseMetadata(stored, format)
                val coverFileName = existing.coverFileName
                    ?: writeCover(bookId, metadata.coverBytes)
                updateIndex { books ->
                    books.map { book ->
                        if (book.id != bookId) return@map book
                        // Same merge as a replace: what the user typed wins,
                        // because they named this book before it had a file.
                        book.withParsedMetadata(
                            metadata = metadata,
                            format = format,
                            fileName = stored.name,
                            coverFileName = coverFileName,
                            contentHash = ContentHash.of(stored),
                            sizeBytes = stored.length(),
                        )
                    }
                }
                bookById(bookId) ?: existing
            } catch (e: Exception) {
                stored.delete()
                throw e
            }
        } finally {
            temp.delete()
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
        // Before the guard below, not after: an abandoned staging file belongs
        // to no record at all, so whether the index is readable has no bearing
        // on whether it is safe to delete.
        sweepStaging()
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
        val quotes = LinkedHashMap<String, Quote>()
        val bookmarks = LinkedHashMap<String, Bookmark>()
        books.forEach { book ->
            // Books with nothing to say are left out entirely, which keeps both
            // documents small and makes deletion cascade for free: a book that
            // is no longer in the list cannot leave a key behind.
            book.toUserData().takeIf { !it.isEmpty }?.let { user[book.id] = it }
            book.toProgress().takeIf { !it.isEmpty }?.let { progress[book.id] = it }
            // Callers build quotes and bookmarks without knowing which book they
            // will end up on, so the owning id is stamped here rather than being
            // one more thing every call site has to remember.
            book.quotes.forEach { quotes[it.id] = it.copy(bookId = book.id) }
            book.bookmarks.forEach { bookmarks[it.id] = it.copy(bookId = book.id) }
        }
        return before.copy(
            index = before.index.copy(books = books.map { it.toRecord() }),
            user = before.user.copy(
                userData = user,
                quotes = quotes,
                bookmarks = bookmarks,
            ),
            progress = ProgressStore(progress),
        )
    }

    /**
     * Stamps every record that actually changed, and records a tombstone for
     * every one that disappeared.
     *
     * Both are derived by comparing before and after rather than being left to
     * each caller, for the same reason the write routing is: a caller that
     * forgets to stamp produces a record that silently loses the next merge,
     * and a caller that forgets a tombstone produces a deletion that silently
     * comes back. Neither failure is visible until a sync exists to expose it.
     *
     * Nothing reads any of this yet — see [UserDataStore.deletedIds].
     */
    private fun restamp(after: Stored, before: Stored, now: Long): Stored {
        fun <T : Timestamped<T>> stamp(fresh: T, old: T?): T =
            if (old != null && fresh.withUpdatedAt(0L) == old.withUpdatedAt(0L)) {
                fresh.withUpdatedAt(old.updatedAtMillis)
            } else {
                fresh.withUpdatedAt(now)
            }

        val oldRecords = before.index.books.associateBy { it.id }
        val oldShelves = before.index.shelves.associateBy { it.id }

        val tombstones = LinkedHashMap(before.user.deletedIds)
        fun buryMissing(oldKeys: Set<String>, newKeys: Set<String>) {
            (oldKeys - newKeys).forEach { tombstones[it] = now }
        }
        buryMissing(oldRecords.keys, after.index.books.mapTo(HashSet()) { it.id })
        buryMissing(before.user.quotes.keys, after.user.quotes.keys)
        buryMissing(before.user.bookmarks.keys, after.user.bookmarks.keys)
        buryMissing(oldShelves.keys, after.index.shelves.mapTo(HashSet()) { it.id })
        // An id that came back is no longer deleted. Restoring a backup over a
        // library that once held the same book is exactly this case.
        val alive = after.index.books.map { it.id } + after.user.quotes.keys +
            after.user.bookmarks.keys + after.index.shelves.map { it.id }
        alive.forEach { tombstones.remove(it) }
        // Bound the growth. A device offline for longer than this could
        // resurrect a book it never heard was deleted; a year is far outside
        // the gap between two phones in daily use.
        val horizon = now - TOMBSTONE_LIFETIME_MILLIS
        tombstones.entries.removeAll { it.value < horizon }

        return after.copy(
            index = LibraryIndex(
                books = after.index.books.map { stamp(it, oldRecords[it.id]) },
                shelves = after.index.shelves.map { stamp(it, oldShelves[it.id]) },
            ),
            user = after.user.copy(
                userData = after.user.userData.mapValues { (id, v) -> stamp(v, before.user.userData[id]) },
                quotes = after.user.quotes.mapValues { (id, v) -> stamp(v, before.user.quotes[id]) },
                bookmarks = after.user.bookmarks.mapValues { (id, v) -> stamp(v, before.user.bookmarks[id]) },
                deletedIds = tombstones,
            ),
            progress = ProgressStore(
                after.progress.progress.mapValues { (id, v) -> stamp(v, before.progress.progress[id]) },
            ),
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
                val after = restamp(
                    after = out.copy(index = LibraryIndex(merged.map { it.toRecord() }, shelves)),
                    before = before,
                    now = System.currentTimeMillis(),
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
