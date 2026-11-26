package com.example.frogreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import androidx.glance.appwidget.updateAll
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.example.frogreader.data.db.BookEntity
import com.example.frogreader.data.db.BookProgressEntity
import com.example.frogreader.data.db.BookmarkEntity
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.DbJson
import com.example.frogreader.data.db.FrogReaderDatabase
import com.example.frogreader.data.db.QuoteEntity
import com.example.frogreader.data.db.ReadingLogEntity
import com.example.frogreader.data.db.ReadingQueueEntity
import com.example.frogreader.data.db.ShelfEntity
import com.example.frogreader.data.db.assembleBooksAndShelves
import com.example.frogreader.data.db.buildReadingStats
import com.example.frogreader.data.db.toBookEntity
import com.example.frogreader.data.db.toDomain
import com.example.frogreader.data.db.toEntity
import com.example.frogreader.data.db.toProgressEntity
import com.example.frogreader.data.metadata.BookMetadataWriter
import com.example.frogreader.data.metadata.CoverEdit
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.model.PrivateQueueAttachment
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.previewText
import com.example.frogreader.data.model.sortTs
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.EpubOpeningDocument
import com.example.frogreader.data.parser.EpubOpeningStructure
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.EpubSourceDestination
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.pdf.annotations.PdfAnnotationClient
import com.example.frogreader.widget.ContinueReadingWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class LibraryIndexCorruptedException(
    message: String,
    cause: Throwable? = null,
) : JsonStoreCorruptedException(message, cause)

/**
 * How long a staged file may sit undecided before a sweep treats it as
 * abandoned. Far longer than any decision takes, so a sweep can never race an
 * import that is still live; short enough that a process killed mid-dialog does
 * not leave a book-sized file behind for good.
 */
private const val STAGING_LIFETIME_MILLIS = 30L * 60 * 1000

/** The title a book gets when neither the file nor its name offers one. */
private const val UNTITLED = "Untitled"

/** Minimum interval between home-screen widget IPC refreshes during rapid page turns. */
private const val WIDGET_REFRESH_THROTTLE_MILLIS = 1_500L

/** Compression must yield CPU time to the page the user is currently reading. */
private val readerContentCacheDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "ReaderContentCache").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/** File hashing and generation commits yield CPU to reading and animation. */
private val pdfAnnotationDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "PdfAnnotationSync").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

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
            else -> pendingSpace = true
        }
    }
    return out.toString()
}

/**
 * Owns the library: imported book files, extracted covers/images and the SQLite
 * database (`databases/frogreader.db` via Room 3 + `BundledSQLiteDriver`) in the
 * app's private storage. All heavy work runs on `Dispatchers.IO`.
 */
open class BookRepository(
    private val context: Context? = null,
    private val writePdfAnnotations: (suspend (File, File, String, List<Quote>, String?) -> Unit)? = context?.let { owner ->
        { source, target, id, quotes, password -> PdfAnnotationClient.write(owner, source, target, id, quotes, password) }
    },
    private val pdfAnnotationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pdfAnnotationDebounceMillis: Long = 600L,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val stateLock = Any()
    private val writeMutex = Mutex()
    private val pdfAnnotationLock = Mutex()
    private val pdfPasswords = ConcurrentHashMap<String, String>()
    private val pdfAnnotationRequests = mutableMapOf<String, Channel<Unit>>()
    private val _pdfAnnotationErrors = MutableStateFlow<Set<String>>(emptySet())
    internal val pdfAnnotationErrors: StateFlow<Set<String>> = _pdfAnnotationErrors.asStateFlow()

    /** Passwords are kept only in memory for annotation edits; never in SQLite or backups. */
    internal fun rememberPdfPassword(bookId: String, password: String?) {
        if (password != null) pdfPasswords[bookId] = password
    }

    @Volatile
    private var isIndexCorrupted = false

    internal val filesRoot: File get() = context?.filesDir ?: File("build/tmp/test_files")
    internal val fontLibraryDirectory: File get() = File(filesRoot, "fonts")

    private val booksDir by lazy { File(filesRoot, "books") }
    private val coversDir by lazy { File(filesRoot, "covers") }
    private val imagesDir by lazy { File(filesRoot, "images") }
    private val readerCacheBudget by lazy { ReaderCacheBudget(filesRoot) }
    private val parsedContentCache by lazy { ChunkedContentCache(File(filesRoot, "reader-content")) }

    private val bookkeeping = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val widgetRefreshSignals = Channel<Unit>(Channel.CONFLATED).also { channel ->
        if (context != null) {
            bookkeeping.launch {
                for (signal in channel) {
                    runCatching { ContinueReadingWidget().updateAll(context) }
                    delay(WIDGET_REFRESH_THROTTLE_MILLIS)
                }
            }
        }
    }

    private fun requestWidgetRefresh() {
        if (context != null) {
            widgetRefreshSignals.trySend(Unit)
        }
    }

    /** A live reader must keep the images/fonts behind its immutable model. */
    fun retainReaderResources(bookId: String): Closeable = readerCacheBudget.pin(bookId).also {
        bookkeeping.launch { readerCacheBudget.touch(bookId) }
    }

    internal fun trimReaderCaches() {
        bookkeeping.launch { trimReaderCachesNow() }
    }

    private fun trimReaderCachesNow() {
        val result = readerCacheBudget.trim()
        if (result.afterBytes > readerCacheBudget.limits.totalBytes ||
            result.contentBytes > readerCacheBudget.limits.contentBytes
        ) {
            releaseContentCache()
            readerCacheBudget.trim()
        }
    }

    private data class Snapshot(
        val books: List<Book> = emptyList(),
        val shelves: List<Shelf> = emptyList(),
        val readingQueue: List<ReadingQueueEntry> = emptyList(),
        val readingLog: List<ReadingLogEntry> = emptyList(),
    )

    private val initialSnapshots: Pair<Snapshot, Snapshot> by lazy {
        try {
            loadSnapshotBlocking()
        } catch (_: LibraryIndexCorruptedException) {
            val empty = Snapshot()
            empty to empty
        }
    }

    private var currentSnapshot: Snapshot? = null
    private var persistedSnapshot: Snapshot? = null

    private fun ensureLoadedSnapshot(): Snapshot {
        synchronized(stateLock) {
            currentSnapshot?.let { return it }
        }
        val (diskState, synced) = initialSnapshots
        return synchronized(stateLock) {
            currentSnapshot ?: synced.also {
                currentSnapshot = it
                persistedSnapshot = diskState
            }
        }
    }

    private val _books by lazy { MutableStateFlow(ensureLoadedSnapshot().books) }
    open val books: StateFlow<List<Book>> get() = _books.asStateFlow()

    private val _shelves by lazy { MutableStateFlow(ensureLoadedSnapshot().shelves) }
    open val shelves: StateFlow<List<Shelf>> get() = _shelves.asStateFlow()

    private val _readingQueue by lazy { MutableStateFlow(ensureLoadedSnapshot().readingQueue) }
    val readingQueue: StateFlow<List<ReadingQueueEntry>> get() = _readingQueue.asStateFlow()

    private val _readingLog by lazy { MutableStateFlow(ensureLoadedSnapshot().readingLog) }
    val readingLog: StateFlow<List<ReadingLogEntry>> get() = _readingLog.asStateFlow()

    private fun publishSnapshot(next: Snapshot) {
        synchronized(stateLock) {
            currentSnapshot = next
            persistedSnapshot = next
        }
        _books.value = next.books
        _shelves.value = next.shelves
        _readingQueue.value = next.readingQueue
        _readingLog.value = next.readingLog
    }

    private suspend fun <T> executeDbWrite(
        repairPendingSnapshot: Boolean = true,
        block: suspend (FrogReaderDatabase) -> T,
    ): T =
        try {
            DatabaseManager.withSwapLock(filesRoot) { db ->
                db.withWriteTransaction {
                    if (repairPendingSnapshot) {
                        val (persisted, desired) = synchronized(stateLock) { persistedSnapshot to currentSnapshot }
                        if (persisted != null && desired != null && persisted != desired) {
                            persistSnapshotDiff(db, persisted, desired, System.currentTimeMillis())
                        }
                    }
                    block(db)
                }
            }
        } catch (e: LibraryIndexCorruptedException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("SQLite write failed: ${e.message}", e)
        }

    /** Reloads all in-memory StateFlows from the active SQLite database. */
    suspend fun reloadFromDatabase() = withContext(Dispatchers.IO) {
        writeMutex.withLock { reloadSnapshotLocked() }
        requestWidgetRefresh()
    }

    private suspend fun reloadSnapshotLocked() {
        val (diskState, synced) = DatabaseManager.withSwapLock(filesRoot) { db ->
            readAndRepairSnapshotsSuspend(db)
        }
        isIndexCorrupted = false
        synchronized(stateLock) {
            currentSnapshot = synced
            persistedSnapshot = diskState
        }
        _books.value = synced.books
        _shelves.value = synced.shelves
        _readingQueue.value = synced.readingQueue
        _readingLog.value = synced.readingLog
    }

    /** Freeze published asset generations while a backup snapshots SQLite and copies its files. */
    internal suspend fun <T> withBackupLock(block: suspend () -> T): T =
        pdfAnnotationLock.withLock { writeMutex.withLock { block() } }

    /** Quiesce snapshot writers until restore or rollback and the matching reload finish. */
    internal suspend fun <T> withRestoreLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val result = runCatching { block() }
            val refreshed = runCatching {
                withContext(NonCancellable) { reloadSnapshotLocked() }
            }
            result.exceptionOrNull()?.let { failure ->
                refreshed.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            refreshed.getOrThrow()
            requestWidgetRefresh()
            result.getOrThrow()
        }
    }

    /**
     * Reloads in-memory [StateFlow]s after a hot-swapped SQLite restore and removes unreferenced
     * book, cover, extracted image, and pagination files from disk.
     */
    suspend fun reloadAndSweepAfterRestore() = withContext(Dispatchers.IO) {
        reloadFromDatabase()
        val snap = ensureLoadedSnapshot()
        val allAssets = snap.books + snap.readingQueue.mapNotNull { it.attachment }
        val keptBooks = allAssets.mapNotNullTo(HashSet()) { it.fileName }
        val keptCovers = allAssets.mapNotNullTo(HashSet()) { it.coverFileName }
        keptCovers += snap.readingLog.mapNotNull { it.coverFileName }
        val keptIds = allAssets.mapTo(HashSet()) { it.id }
        booksDir.listFiles()?.forEach { if (it.name !in keptBooks) it.delete() }
        coversDir.listFiles()?.forEach { if (it.name !in keptCovers) it.delete() }
        imagesDir.listFiles()?.forEach { if (it.isDirectory && it.name !in keptIds) it.deleteRecursively() }
        File(filesRoot, "pagination").listFiles()?.forEach {
            if (it.nameWithoutExtension !in keptIds) it.delete()
        }
        parsedContentCache.pruneEpubSourceIndexes(keptIds)
        releaseContentCache()
    }

    private fun loadSnapshotBlocking(): Pair<Snapshot, Snapshot> = runBlocking(Dispatchers.IO) {
        try {
            val snapshots = DatabaseManager.withSwapLock(filesRoot) { db ->
                readAndRepairSnapshotsSuspend(db)
            }
            isIndexCorrupted = false
            snapshots
        } catch (e: CancellationException) {
            throw e
        } catch (e: LibraryIndexCorruptedException) {
            isIndexCorrupted = true
            throw e
        } catch (e: Exception) {
            // SQLite error codes for corrupt / non-database files propagate as corruption.
            val msg = e.message.orEmpty()
            if (msg.contains("not a database", ignoreCase = true) ||
                msg.contains("malformed", ignoreCase = true) ||
                msg.contains("corrupt", ignoreCase = true)
            ) {
                isIndexCorrupted = true
                throw LibraryIndexCorruptedException("SQLite database is corrupted: $msg", e)
            }
            throw e
        }
    }

    /** Read and reconcile one database generation while the swap lock stays held. */
    private suspend fun readAndRepairSnapshotsSuspend(db: FrogReaderDatabase): Pair<Snapshot, Snapshot> {
        val (raw, synced) = readSnapshotsSuspend(db)
        if (synced == raw) return raw to synced
        val repaired = try {
            db.withWriteTransaction {
                persistSnapshotDiff(db, raw, synced, System.currentTimeMillis())
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the disk baseline so the next normal write retries this reconciliation.
            false
        }
        return (if (repaired) synced else raw) to synced
    }

    private suspend fun readSnapshotsSuspend(db: FrogReaderDatabase): Pair<Snapshot, Snapshot> =
        db.withReadTransaction {
            val bookEntities = db.bookDao().getAllBooks()
            val progressEntities = db.bookDao().getAllProgress()
            val quoteEntities = db.passagesDao().getAllQuotes()
            val bookmarkEntities = db.passagesDao().getAllBookmarks()
            val shelfEntities = db.shelfDao().getAllShelves()
            val queueEntities = db.readingListsDao().getAllQueueEntries()
            val logEntities = db.readingListsDao().getAllLogEntries()

            val (assembledBooks, assembledShelves) = assembleBooksAndShelves(
                bookEntities = bookEntities,
                progressEntities = progressEntities,
                quoteEntities = quoteEntities,
                bookmarkEntities = bookmarkEntities,
                shelfEntities = shelfEntities,
            )
            val rawLog = logEntities.map { it.toDomain() }
            val rawBooksById = assembledBooks.associateBy { it.id }
            val rawSnapshot = Snapshot(
                books = assembledBooks.sortedByDescending { it.sortTs },
                shelves = normalizeShelves(assembledShelves, assembledBooks),
                readingQueue = queueEntities.map { it.toDomain(rawBooksById) },
                readingLog = rawLog,
            )

            val repairedBooks = assembledBooks.map { book ->
                if (book.suppressReadingLog && book.activeReadingLogId == null &&
                    rawLog.none { it.linkedBookId == book.id }
                ) book.withoutCurrentReading() else book
            }
            val (syncedBooks, syncedLog) = syncCurrentReadingsPure(repairedBooks, rawLog)
            val booksById = syncedBooks.associateBy { it.id }
            val resolvedQueue = queueEntities.map { it.toDomain(booksById) }

            val syncedSnapshot = Snapshot(
                books = syncedBooks.sortedByDescending { it.sortTs },
                shelves = normalizeShelves(assembledShelves, syncedBooks),
                readingQueue = resolvedQueue,
                readingLog = syncedLog,
            )
            rawSnapshot to syncedSnapshot
        }

    fun readingLogEntryFor(bookId: String): ReadingLogEntry? {
        val book = bookById(bookId)?.takeUnless { it.suppressReadingLog } ?: return null
        return readingLog.value.firstOrNull { it.id == book.activeReadingLogId && it.linkedBookId == bookId }
    }

    suspend fun saveReadingLogEntry(entry: ReadingLogEntry): String {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val unchangedIdentity = readingLog.value.firstOrNull { it.id == id }?.let { old ->
            old.source == ReadingLogSource.FROGREADER && entry.source == old.source &&
                entry.title == old.title && entry.author == old.author && entry.series == old.series
        } == true
        require(entry.title.trim().isNotEmpty() && (unchangedIdentity || entry.title.trim().length <= 200))
        require(unchangedIdentity || entry.author.trim().length <= 160 && entry.series.trim().length <= 200)
        require(entry.review.length <= 20_000)
        require(entry.ratingHalfStars == null || entry.ratingHalfStars in 2..10)
        require(entry.startedOn?.isValid() != false && entry.finishedOn?.isValid() != false)
        require(entry.readingSeconds >= 0)
        require(entry.progressFraction == null || entry.progressFraction.isFinite() && entry.progressFraction in 0f..1f)
        require(
            entry.coverFileName == null || entry.coverFileName.isNotBlank() &&
                entry.coverFileName !in listOf(".", "..") && entry.coverFileName == File(entry.coverFileName).name
        )
        if (entry.startedOn != null && entry.finishedOn != null) {
            val earliestStart = entry.startedOn.toLocalDate()
            val latestEnd = entry.finishedOn.let {
                if (it.day != null) it.toLocalDate() else if (it.month != null)
                    java.time.YearMonth.of(it.year, it.month).atEndOfMonth() else java.time.LocalDate.of(it.year, 12, 31)
            }
            require(!earliestStart.isAfter(latestEnd)) { "The finish date precedes the start date." }
        }
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            val old = before.readingLog.firstOrNull { it.id == id }
            val currentBook = before.books.firstOrNull { it.id == entry.linkedBookId && it.activeReadingLogId == id }
            val clean = entry.copy(
                id = id,
                title = entry.title.trim(),
                author = entry.author.trim(),
                series = entry.series.trim(),
                review = entry.review.trim(),
                progressFraction = if (currentBook != null && entry.source == ReadingLogSource.FROGREADER) {
                    if (entry.status == ReadingLogStatus.ABANDONED && old?.status != ReadingLogStatus.ABANDONED)
                        currentBook.readingLogProgressFraction else old?.progressFraction ?: currentBook.readingLogProgressFraction
                } else old?.progressFraction ?: entry.progressFraction,
                bookFormat = if (entry.source == ReadingLogSource.FROGREADER) old?.bookFormat
                    ?: before.books.firstOrNull { it.id == entry.linkedBookId }?.format ?: entry.bookFormat else null,
                startedDateOrder = old?.startedDateOrder?.takeIf { it.date == entry.orderingDate(true) && it.date.day == null && it.rank >= 0 },
                finishedDateOrder = old?.finishedDateOrder?.takeIf { it.date == entry.orderingDate(false) && it.date.day == null && it.rank >= 0 },
                finishedAtMillis = entry.finishedAtMillis?.takeIf {
                    entry.status == ReadingLogStatus.FINISHED && ReadingLogDate.fromMillis(it) == entry.finishedOn
                },
                createdAtMillis = old?.createdAtMillis ?: entry.createdAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            val nextLog = if (old == null) before.readingLog + clean else before.readingLog.map { if (it.id == id) clean else it }
            val nextBooks = if (currentBook == null) {
                before.books
            } else {
                before.books.map {
                    if (it.id != currentBook.id) it else it.copy(
                        ratingHalfStars = clean.ratingHalfStars,
                        rating = clean.ratingHalfStars?.takeIf { stars -> stars % 2 == 0 }?.div(2),
                        review = clean.review.ifBlank { null },
                        reviewUpdatedAtMillis = System.currentTimeMillis(),
                        startedAtMillis = clean.startedOn?.takeIf { date -> date.day != null }?.toLocalDate()
                            ?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: it.startedAtMillis,
                        finishedAtMillis = if (clean.status == ReadingLogStatus.FINISHED)
                            clean.finishedAtMillis ?: clean.finishedOn?.takeIf { date -> date.day != null }?.toLocalDate()
                                ?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() else null,
                        status = when (clean.status) {
                            ReadingLogStatus.IN_PROGRESS -> ReadingStatus.READING
                            ReadingLogStatus.FINISHED -> ReadingStatus.FINISHED
                            ReadingLogStatus.ABANDONED -> ReadingStatus.ABANDONED
                        },
                    )
                }
            }
            before.copy(books = nextBooks, readingLog = nextLog)
        }
        return id
    }

    /** A removed current reading starts fresh on the next open; old history never owns its progress. */
    suspend fun deleteReadingLogEntry(id: String): ReadingLogDeletion? {
        var deletion: ReadingLogDeletion? = null
        var previousReading: Book? = null
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            val removed = before.readingLog.firstOrNull { it.id == id } ?: return@mutateSnapshot before
            val books = before.books.map { book ->
                val latest = book.activeReadingLogId ?: before.readingLog
                    .filter { it.linkedBookId == book.id }.maxByOrNull { it.createdAtMillis }?.id
                val hasCurrentReading = book.activeReadingLogId != null || book.suppressReadingLog ||
                    book.startedAtMillis != null || book.hasFinishedReading || book.status in
                    listOf(ReadingStatus.READING, ReadingStatus.ABANDONED) || book.readingSeconds > 0 || book.progress != ReadingProgress()
                if (removed.linkedBookId == book.id && latest == id && hasCurrentReading) {
                    previousReading = book
                    book.withoutCurrentReading()
                } else book
            }
            val capturedPrevious = previousReading
            val removedSnapshot = if (removed.status == ReadingLogStatus.IN_PROGRESS && capturedPrevious != null)
                removed.copy(progressFraction = capturedPrevious.readingLogProgressFraction ?: removed.progressFraction) else removed
            deletion = ReadingLogDeletion(removedSnapshot, before.readingLog.indexOfFirst { it.id == id }, capturedPrevious)
            before.copy(
                books = books,
                readingLog = before.readingLog.filterNot { it.id == id },
            )
        }
        return deletion
    }

    suspend fun restoreReadingLogEntry(entry: ReadingLogEntry, index: Int, previousReading: Book? = null) =
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            if (before.readingLog.any { it.id == entry.id }) before else {
                val nextLog = before.readingLog.toMutableList().apply { add(index.coerceIn(0, size), entry) }
                val book = previousReading?.let { snapshot ->
                    before.books.firstOrNull {
                        it.id == snapshot.id && it.id == entry.linkedBookId &&
                            it.activeReadingLogId == null && (it.suppressReadingLog || it.status == ReadingStatus.NONE &&
                                it.startedAtMillis == null && it.finishedAtMillis == null && it.readingSeconds == 0L)
                    }
                }
                val nextBooks = if (book != null) {
                    before.books.map {
                        if (it.id == book.id) it.copy(
                            activeReadingLogId = previousReading.activeReadingLogId,
                            suppressReadingLog = previousReading.suppressReadingLog,
                            progress = previousReading.progress,
                            startedAtMillis = previousReading.startedAtMillis,
                            finishedAtMillis = previousReading.finishedAtMillis,
                            readingSeconds = previousReading.readingSeconds,
                            status = previousReading.status,
                        ) else it
                    }
                } else before.books
                before.copy(books = nextBooks, readingLog = nextLog)
            }
        }

    private fun Book.withoutCurrentReading() = copy(
        progress = ReadingProgress(),
        startedAtMillis = null,
        finishedAtMillis = null,
        readingSeconds = 0,
        status = ReadingStatus.NONE,
        activeReadingLogId = null,
        suppressReadingLog = false,
    )

    suspend fun reorderReadingLog(ids: List<String>) = mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
        val remaining = before.readingLog.associateByTo(LinkedHashMap()) { it.id }
        before.copy(readingLog = ids.distinct().mapNotNull { remaining.remove(it) } + remaining.values)
    }

    /** Save chronology for one uncertain date without changing the manual list or the other date axis. */
    suspend fun reorderReadingLogDateGroup(chronologicalIds: List<String>, byStart: Boolean, date: ReadingLogDate) =
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            before.copy(readingLog = withReadingLogDateOrder(before.readingLog, chronologicalIds, byStart, date))
        }

    open fun coverFileFor(entry: ReadingLogEntry): File? = entry.coverFileName
        ?.takeIf { it == File(it).name }?.let { File(coversDir, it) }?.takeIf { it.exists() }

    /** Import a camera/gallery cover as a bounded, upright 2:3 image owned by the log. */
    suspend fun prepareReadingLogCover(uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported cover image." }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2400 || bounds.outHeight / inSampleSize > 2400) inSampleSize *= 2
        }
        val decoded = openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot read cover image.")
        var oriented = decoded
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        val name = "reading-log-${UUID.randomUUID()}.jpg"
        val target = File(coversDir, name)
        val temporary = File(coversDir, "$name.tmp")
        try {
            val orientation = runCatching {
                openStream(uri).use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            val matrix = android.graphics.Matrix().apply {
                when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                }
            }
            if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val width = minOf(oriented.width, (oriented.height * 2f / 3f).roundToInt().coerceAtLeast(1))
            val height = minOf(oriented.height, (width * 3f / 2f).roundToInt().coerceAtLeast(1))
            val crop = Bitmap.createBitmap(oriented, (oriented.width - width) / 2, (oriented.height - height) / 2, width, height)
            cropped = crop
            val outputImage = if (width > 1000) crop.scale(1000, 1500, true) else crop
            scaled = outputImage
            coversDir.mkdirs()
            FileOutputStream(temporary).use { out -> check(outputImage.compress(Bitmap.CompressFormat.JPEG, 92, out)); out.fd.sync() }
            check(temporary.renameTo(target)) { "Cannot save cover image." }
            name
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        } finally {
            listOfNotNull(decoded, oriented, cropped, scaled).distinct().forEach { it.recycle() }
        }
    }

    suspend fun discardReadingLogCover(name: String) = withContext(Dispatchers.IO) {
        ensureLoadedSnapshot()
        if (!isIndexCorrupted && name.startsWith("reading-log-") && name == File(name).name && name !in referencedCovers()) {
            File(coversDir, name).delete()
        }
    }

    suspend fun beginReadingAgain(bookId: String, addToLog: Boolean = true): String? {
        val id = if (addToLog) UUID.randomUUID().toString() else null
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            val now = System.currentTimeMillis()
            val book = before.books.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
            val fresh = book.copy(
                startedAtMillis = now,
                finishedAtMillis = null,
                lastOpenedAtMillis = now,
                readingSeconds = 0,
                progress = ReadingProgress(),
                status = ReadingStatus.READING,
                rating = null,
                ratingHalfStars = null,
                review = null,
                reviewUpdatedAtMillis = null,
                activeReadingLogId = id,
                suppressReadingLog = !addToLog,
            )
            // Capture progressFraction on any ended IN_PROGRESS reading whose activeReadingLogId just changed:
            val previousLogId = book.activeReadingLogId?.takeUnless { book.suppressReadingLog }
            val updatedLog = before.readingLog.map { entry ->
                if (previousLogId != null && entry.id == previousLogId && entry.status == ReadingLogStatus.IN_PROGRESS) {
                    val frac = book.readingLogProgressFraction
                    if (frac != null) entry.copy(progressFraction = frac) else entry
                } else entry
            }
            before.copy(
                books = before.books.map { if (it.id == bookId) fresh else it },
                readingLog = if (id != null) updatedLog + fresh.logEntry(id) else updatedLog,
            )
        }
        return id
    }

    /** Continue the same abandoned reading, retaining its position, dates, time and review. */
    suspend fun resumeReading(bookId: String) = mutateSnapshot(syncCurrentReadingsAfter = true, openedBookId = bookId) { before ->
        val book = before.books.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
        check(book.status == ReadingStatus.ABANDONED || book.status == ReadingStatus.READING) { "This reading cannot be resumed." }
        val resumed = book.copy(
            status = ReadingStatus.READING,
            finishedAtMillis = null,
            lastOpenedAtMillis = System.currentTimeMillis(),
            suppressReadingLog = false,
        )
        val entries = before.readingLog.map {
            if (it.id == book.activeReadingLogId)
                it.copy(status = ReadingLogStatus.IN_PROGRESS, finishedOn = null, lastOpenedAtMillis = resumed.lastOpenedAtMillis)
            else it
        }
        before.copy(
            books = before.books.map { if (it.id == bookId) resumed else it },
            readingLog = entries,
        )
    }

    /** Queue the existing file without moving the book or changing its reading state. */
    suspend fun addLibraryBookToReadingQueue(bookId: String): Boolean {
        var added = false
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            val book = before.books.firstOrNull { it.id == bookId }
                ?: throw IOException("Library book no longer exists.")
            if (before.readingQueue.any { it.attachment?.id == bookId }) before else {
                val entry = ReadingQueueEntry(
                    id = UUID.randomUUID().toString(),
                    title = book.title.trim().take(ReadingQueueEntry.TITLE_LIMIT),
                    author = book.author.orEmpty().trim().take(ReadingQueueEntry.AUTHOR_LIMIT),
                    createdAtMillis = System.currentTimeMillis(),
                    attachment = book,
                )
                added = true
                before.copy(readingQueue = before.readingQueue + entry)
            }
        }
        return added
    }

    /** Remove only this library book's queue entries, preserving the book and its file. */
    suspend fun removeLibraryBookFromReadingQueue(bookId: String) =
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            before.copy(readingQueue = before.readingQueue.filterNot { it.attachment?.id == bookId })
        }

    open suspend fun saveReadingQueueEntry(
        id: String?,
        title: String,
        author: String,
        note: String,
        attachment: Book? = null,
    ): String {
        val cleanTitle = title.trim()
        val cleanAuthor = author.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= ReadingQueueEntry.TITLE_LIMIT)
        require(cleanAuthor.length <= ReadingQueueEntry.AUTHOR_LIMIT)
        val entryId = id ?: UUID.randomUUID().toString()
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            val entries = before.readingQueue
            val existing = id?.let { key -> entries.firstOrNull { it.id == key } }
            require(ReadingQueueEntry.acceptsNote(note, existing?.note))
            val cleanNote = if (note == existing?.note) note else note.trim()
            val next = if (id == null) {
                entries + ReadingQueueEntry(
                    entryId,
                    cleanTitle,
                    cleanAuthor,
                    cleanNote,
                    createdAtMillis = System.currentTimeMillis(),
                    attachment = attachment,
                )
            } else {
                check(entries.any { it.id == id }) { "Reading Queue entry no longer exists." }
                entries.map {
                    if (it.id == id) it.copy(
                        title = cleanTitle,
                        author = cleanAuthor,
                        note = cleanNote,
                        attachment = attachment ?: it.attachment,
                    ) else it
                }
            }
            before.copy(readingQueue = next)
        }
        return entryId
    }

    suspend fun deleteReadingQueueEntry(id: String) = mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
        before.copy(readingQueue = before.readingQueue.filterNot { it.id == id })
    }

    /** Copies a picked file without publishing it to the library. Safe to keep in a saved draft. */
    suspend fun prepareReadingQueueAttachment(uri: Uri): Book {
        val staged = stageImport(uri)
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                val id = "reading-list-${UUID.randomUUID()}"
                booksDir.mkdirs()
                val file = File(booksDir, "$id.${staged.file.extension}")
                var cover: String? = null
                try {
                    moveFile(staged.file, file)
                    FileOutputStream(file, true).use { it.fd.sync() }
                    cover = writeCover(importCoverId(id, staged.format), staged.coverBytes)
                    cachePdfOutline(file, staged.contentHash, staged.metadata)
                    Book(
                        id, staged.title, staged.author, staged.format, file.name, cover,
                        addedAtMillis = System.currentTimeMillis(), contentHash = staged.contentHash,
                        sizeBytes = staged.sizeBytes,
                    ).withParsedMetadata(
                        staged.metadata, staged.format,
                        file.name, cover, staged.contentHash, staged.sizeBytes,
                    )
                } catch (e: Exception) {
                    file.delete()
                    cover?.let { File(coversDir, it).delete() }
                    throw e
                }
            }
        } finally {
            withContext(NonCancellable) { discardImport(staged) }
        }
    }

    suspend fun attachReadingQueueFile(id: String, attachment: Book) =
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            check(before.readingQueue.any { it.id == id }) { "Reading Queue entry no longer exists." }
            check(bookFileFor(attachment) != null) { "The attached file is missing." }
            before.copy(readingQueue = before.readingQueue.map {
                if (it.id == id) it.copy(attachment = attachment) else it
            })
        }

    suspend fun restoreReadingQueueEntry(entry: ReadingQueueEntry, index: Int) =
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            if (before.readingQueue.any { it.id == entry.id }) before else {
                val list = before.readingQueue.toMutableList()
                list.add(index.coerceIn(0, list.size), entry)
                before.copy(readingQueue = list)
            }
        }

    /** A cancelled draft can release its file; never remove a live attachment. */
    suspend fun discardReadingQueueAttachment(book: Book) = withContext(Dispatchers.IO) {
        val current = ensureLoadedSnapshot()
        if (isIndexCorrupted) return@withContext
        val inUse = current.books.mapNotNull { it.fileName } +
            current.readingQueue.mapNotNull { it.attachment?.fileName }
        if (book.id.startsWith("reading-list-") && book.fileName !in inUse) {
            book.fileName?.let { File(booksDir, it).delete() }
            book.coverFileName?.let { File(coversDir, it).delete() }
        }
    }

    /** Publish a real book and remove its intention only after the user chooses Start reading. */
    suspend fun startReadingQueueEntry(id: String): Book {
        var opened: Book? = null
        mutateSnapshot(syncCurrentReadingsAfter = true) { before ->
            val entry = before.readingQueue.firstOrNull { it.id == id }
                ?: throw IOException("Reading Queue entry no longer exists.")
            val attached = entry.attachment ?: throw IOException("Attach a book file first.")
            val library = before.books
            val existing = library.firstOrNull {
                it.id == attached.id || (attached.contentHash != null && it.contentHash == attached.contentHash)
            }
            check(bookFileFor(existing ?: attached) != null || bookFileFor(attached) != null) {
                "The attached file is missing."
            }
            val notes = listOfNotNull(existing?.readingQueueNote, entry.note.takeIf { it.isNotBlank() })
                .distinct().joinToString("\n\n").ifBlank { null }
            val book = (existing ?: attached.copy(
                title = entry.title,
                author = entry.author.ifBlank { attached.author },
                addedAtMillis = System.currentTimeMillis(),
            )).copy(readingQueueNote = notes)
            opened = if (bookFileFor(book) == null) {
                book.copy(fileName = attached.fileName, coverFileName = attached.coverFileName)
            } else {
                book
            }
            before.copy(
                books = library.filterNot { it.id == book.id } + requireNotNull(opened),
                readingQueue = before.readingQueue.filterNot { it.id == id },
            )
        }
        return requireNotNull(opened)
    }

    /** Preserve concurrent additions, and never revive entries removed during a drag. */
    suspend fun reorderReadingQueue(ids: List<String>) = mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
        val remaining = before.readingQueue.associateByTo(LinkedHashMap()) { it.id }
        val ordered = ids.distinct().mapNotNull { remaining.remove(it) } + remaining.values
        before.copy(readingQueue = ordered)
    }

    private fun Book.logEntry(id: String): ReadingLogEntry = ReadingLogEntry(
        id = id,
        title = title,
        author = author.orEmpty(),
        series = series.orEmpty(),
        source = ReadingLogSource.FROGREADER,
        bookFormat = format,
        status = when {
            hasFinishedReading -> ReadingLogStatus.FINISHED
            status == ReadingStatus.ABANDONED -> ReadingLogStatus.ABANDONED
            else -> ReadingLogStatus.IN_PROGRESS
        },
        startedOn = startedAtMillis?.let { ReadingLogDate.fromMillis(it) },
        finishedOn = finishedAtMillis?.let { ReadingLogDate.fromMillis(it) },
        linkedBookId = this.id,
        coverFileName = coverFileName,
        readingSeconds = readingSeconds,
        lastOpenedAtMillis = lastOpenedAtMillis,
        ratingHalfStars = effectiveRatingHalfStars,
        review = review.orEmpty(),
        createdAtMillis = startedAtMillis ?: finishedAtMillis ?: addedAtMillis,
        finishedAtMillis = finishedAtMillis,
        progressFraction = readingLogProgressFraction,
    )

    private val Book.readingLogProgressFraction: Float?
        get() = progress.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

    private fun syncCurrentReadingsPure(
        booksInput: List<Book>,
        logInput: List<ReadingLogEntry>,
        openedBookId: String? = null,
    ): Pair<List<Book>, List<ReadingLogEntry>> {
        val formats = booksInput.associate { it.id to it.format }
        val entries = logInput.map { entry ->
            if (entry.source == ReadingLogSource.FROGREADER && entry.bookFormat == null)
                entry.copy(bookFormat = formats[entry.linkedBookId]) else entry
        }.toMutableList()
        val books = booksInput.map { book ->
            if (book.suppressReadingLog) return@map book
            val at = entries.indexOfFirst { it.id == book.activeReadingLogId }
            if (at < 0) {
                if (book.startedAtMillis == null && book.finishedAtMillis == null &&
                    book.status !in listOf(ReadingStatus.READING, ReadingStatus.FINISHED, ReadingStatus.ABANDONED)
                ) return@map book
                val id = book.activeReadingLogId?.takeUnless { key -> entries.any { it.id == key } }
                    ?: UUID.randomUUID().toString()
                entries += book.logEntry(id)
                book.copy(activeReadingLogId = id)
            } else {
                val old = entries[at]
                val desiredStatus = when {
                    book.hasFinishedReading -> ReadingLogStatus.FINISHED
                    book.status == ReadingStatus.ABANDONED -> ReadingLogStatus.ABANDONED
                    else -> ReadingLogStatus.IN_PROGRESS
                }
                val finishedOn = when {
                    desiredStatus == ReadingLogStatus.IN_PROGRESS -> null
                    old.status != ReadingLogStatus.FINISHED && book.finishedAtMillis != null ->
                        ReadingLogDate.fromMillis(book.finishedAtMillis)
                    else -> old.finishedOn
                }
                val finishedAt = if (desiredStatus == ReadingLogStatus.FINISHED)
                    (old.finishedAtMillis ?: book.finishedAtMillis)?.takeIf { ReadingLogDate.fromMillis(it) == finishedOn }
                else null
                entries[at] = old.copy(
                    status = desiredStatus,
                    readingSeconds = book.readingSeconds,
                    ratingHalfStars = book.effectiveRatingHalfStars,
                    review = book.review.orEmpty(),
                    progressFraction = if (desiredStatus == ReadingLogStatus.ABANDONED &&
                        (old.status != ReadingLogStatus.ABANDONED || old.progressFraction == null)
                    ) book.readingLogProgressFraction else old.progressFraction,
                    lastOpenedAtMillis = if (book.id == openedBookId && old.status == ReadingLogStatus.IN_PROGRESS)
                        book.lastOpenedAtMillis else old.lastOpenedAtMillis,
                    finishedOn = finishedOn,
                    finishedAtMillis = finishedAt,
                )
                book
            }
        }
        return books to entries
    }

    private fun referencedQueueFiles(): Set<String> =
        ensureLoadedSnapshot().readingQueue.mapNotNullTo(HashSet()) { it.attachment?.fileName }

    private fun referencedCovers(): Set<String> {
        val current = ensureLoadedSnapshot()
        return (current.books.mapNotNull { it.coverFileName } +
            current.readingQueue.mapNotNull { it.attachment?.coverFileName } +
            current.readingLog.mapNotNull { it.coverFileName }).toSet()
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

    open suspend fun stageImport(uri: Uri): StagedImport = withContext(Dispatchers.IO) {
        val stagingId = UUID.randomUUID().toString()
        val temp = createTempFile("import-")
        try {
            openStream(uri).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }

            val originalName = displayNameFor(uri)
            val mimeType = runCatching { context?.contentResolver?.getType(uri) }.getOrNull()
            val (format, stored) = BookParsers.detectAndStore(temp, stagingDir, stagingId, originalName, mimeType)
            try {
                val metadata = parseImportMetadata(stored, format)
                val title = metadata.title?.takeIf { it.isNotBlank() }
                    ?: originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
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

    open suspend fun inspectFile(uri: Uri): InspectedFile {
        val staged = stageImport(uri)
        try {
            return InspectedFile(
                format = staged.format,
                title = staged.title,
                author = staged.author,
                coverBytes = staged.coverBytes,
                contentHash = staged.contentHash,
                sizeBytes = staged.sizeBytes,
                duplicateOf = staged.duplicateOf,
            )
        } finally {
            discardImport(staged)
        }
    }

    protected open suspend fun parseImportMetadata(file: File, format: BookFormat): BookMetadata {
        val fallback = BookParsers.parseMetadata(file, format)
        if (format != BookFormat.PDF) return fallback
        val owner = context ?: return fallback
        var extracted = fallback
        return try {
            withTimeoutOrNull(20_000) {
                com.example.frogreader.data.pdf.compat.PdfTextClient.connect(owner, Uri.fromFile(file), null).use { client ->
                    extracted = client.importMetadata()
                    extracted.pdfOutline = try {
                        withTimeoutOrNull(3_000) { client.outline() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    extracted
                }
            } ?: extracted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            extracted
        }
    }

    private suspend fun cachePdfOutline(file: File, hash: String?, metadata: BookMetadata) {
        val outline = metadata.pdfOutline ?: return
        val owner = context ?: return
        try {
            com.example.frogreader.data.pdf.PdfOutlineCache(owner).write(file, hash, outline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    open suspend fun discardImport(staged: StagedImport) = withContext(Dispatchers.IO) {
        staged.file.delete()
        Unit
    }

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
                coverFileName = writeCover(importCoverId(id, staged.format), staged.coverBytes),
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
                additionalMetadata = staged.metadata.additionalMetadata,
            )
            cachePdfOutline(stored, staged.contentHash, staged.metadata)
            updateBooks { listOf(book) + it }
            return book
        } catch (e: Exception) {
            stored.delete()
            throw e
        }
    }

    private suspend fun commitOverBook(staged: StagedImport, bookId: String): Book {
        val existing = bookById(bookId) ?: throw IOException("No such book")
        booksDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val stored = File(booksDir, "$bookId-$stamp.${staged.file.extension}")
        moveFile(staged.file, stored)

        val newCover = try {
            if (staged.format == BookFormat.PDF && (!existing.generatePdfCover ||
                    (staged.contentHash == existing.contentHash && existing.coverFileName != null))
            ) null
            else writeCover(importCoverId(bookId, staged.format) + "-$stamp", staged.coverBytes)
        } catch (e: Exception) {
            stored.delete()
            throw e
        }

        val moved = staged.contentHash != existing.contentHash
        val inheritedCover = existing.coverFileName?.takeUnless {
            moved && it.startsWith("$bookId-pdf-")
        }
        val coverAfter = newCover ?: inheritedCover
        val pdfTransition = moved && (existing.format == BookFormat.PDF || staged.format == BookFormat.PDF)
        val anchors = if (moved && staged.format != BookFormat.PDF) {
            runCatching { readAnchors(bookId, stored, staged.format, coverAfter != null) }.getOrNull()
        } else {
            null
        }
        val previousCovers = HashSet<String>()

        try {
            cachePdfOutline(stored, staged.contentHash, staged.metadata)
            updateBooks { books ->
                books.map { book ->
                    if (book.id != bookId) {
                        book
                    } else {
                        book.coverFileName?.let(previousCovers::add)
                        book.withParsedMetadata(
                            metadata = staged.metadata,
                            format = staged.format,
                            fileName = stored.name,
                            coverFileName = newCover ?: book.coverFileName?.takeUnless {
                                moved && it.startsWith("$bookId-pdf-")
                            },
                            contentHash = staged.contentHash,
                            sizeBytes = staged.sizeBytes,
                        ).let { if (pdfTransition) it.withoutDocumentAnchors() else it }
                            .let { if (anchors == null) it else anchors.reanchor(it) }
                    }
                }
            }
        } catch (e: Exception) {
            stored.delete()
            newCover?.let { File(coversDir, it).delete() }
            throw e
        }

        existing.fileName?.takeIf { it != stored.name && it !in referencedQueueFiles() }
            ?.let { File(booksDir, it).delete() }
        val retainedCovers = referencedCovers()
        previousCovers.filterNot { it in retainedCovers }.forEach { File(coversDir, it).delete() }
        purgeDerived(bookId)
        return bookById(bookId) ?: existing
    }

    private fun Book.withoutDocumentAnchors(): Book = copy(
        pdfAnnotationFingerprint = null,
        bookmarks = bookmarks.map { it.copy(orphaned = true, documentLocation = null) },
        quotes = quotes.map {
            it.copy(
                documentLocation = null, pdfSelection = emptyList(),
                startItem = -1, startChar = -1, endItem = -1, endChar = -1,
            )
        },
        progress = progress.copy(
            chapterIndex = 0, elementIndex = 0, scrollOffset = 0,
            fraction = progress.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            pagesLeftInChapter = -1, totalPages = 0, documentLocation = null,
        ),
    )

    private class Anchors(
        val previewToIndex: Map<String, Int>,
        val chapterOfIndex: (Int) -> Int,
        val itemCount: Int,
    ) {
        fun reanchor(book: Book): Book = book.copy(
            bookmarks = book.bookmarks.map { bookmark ->
                val found = previewToIndex[bookmark.preview]
                if (found == null) {
                    bookmark.copy(orphaned = true, documentLocation = null)
                } else {
                    bookmark.copy(
                        flatIndex = found,
                        chapterIndex = chapterOfIndex(found),
                        orphaned = false,
                        documentLocation = null,
                    )
                }
            },
            progress = book.progress.let { position ->
                val fraction = position.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
                val index = ((itemCount - 1).coerceAtLeast(0) * fraction).roundToInt()
                position.copy(
                    chapterIndex = chapterOfIndex(index),
                    elementIndex = index,
                    scrollOffset = 0,
                    fraction = fraction,
                    pagesLeftInChapter = -1,
                    totalPages = 0,
                    documentLocation = null,
                )
            },
        )
    }

    private fun readAnchors(
        bookId: String,
        file: File,
        format: BookFormat,
        hasCover: Boolean,
    ): Anchors {
        val scratch = File(imagesDir, "$bookId-reanchor")
        try {
            val content = BookParsers.parseContent(file, format, scratch)
            val offset = if (hasCover) 1 else 0
            val previewToIndex = HashMap<String, Int>()
            val chapterStarts = ArrayList<Int>(content.chapters.size)
            var index = offset
            content.chapters.forEach { chapter ->
                chapterStarts += index
                chapter.elements.forEach { element ->
                    element.previewText()?.let { preview ->
                        previewToIndex.putIfAbsent(preview, index)
                    }
                    index++
                }
            }
            return Anchors(
                previewToIndex = previewToIndex,
                chapterOfIndex = { at -> chapterStarts.indexOfLast { it <= at }.coerceAtLeast(0) },
                itemCount = index,
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun writeCover(name: String, bytes: ByteArray?): String? {
        if (bytes == null) return null
        coversDir.mkdirs()
        val fileName = "$name.img"
        File(coversDir, fileName).writeBytes(bytes)
        return fileName
    }

    suspend fun savePdfCoverIfMissing(bookId: String, bytes: ByteArray, expectedContentHash: String?) =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024) { "PDF cover is too large or empty" }
            val current = bookById(bookId) ?: return@withContext
            if (current.format != BookFormat.PDF || !current.generatePdfCover || current.coverFileName != null ||
                current.contentHash != expectedContentHash
            ) return@withContext
            val name = writeCover("$bookId-pdf-${UUID.randomUUID()}", bytes) ?: return@withContext
            var committed = false
            try {
                withContext(NonCancellable) {
                    var selected = false
                    updateBooks { books ->
                        books.map { book ->
                            if (book.id == bookId && book.format == BookFormat.PDF && book.generatePdfCover &&
                                book.coverFileName == null && book.contentHash == expectedContentHash
                            ) {
                                selected = true
                                book.copy(coverFileName = name)
                            } else book
                        }
                    }
                    committed = selected
                }
            } finally {
                if (!committed) File(coversDir, name).delete()
            }
        }

    private fun purgeDerived(bookId: String) {
        if (cachedContentId == bookId) releaseContentCache()
        parsedContentCache.removeEpubSourceIndex(bookId)
        readerCacheBudget.remove(bookId, force = true)
    }

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
            updateBooks { current ->
                current.map { book ->
                    val computed = backfilled[book.id]
                    if (computed == null) book else book.copy(
                        contentHash = computed,
                        sizeBytes = book.sizeBytes.takeIf { it != 0L }
                            ?: bookFileFor(book)?.length() ?: 0L,
                    )
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
                    (wantedAuthor.isEmpty() ||
                        normalizeForMatch(book.author).isEmpty() ||
                        normalizeForMatch(book.author) == wantedAuthor)
            }
            ?.let { DuplicateOf(it, DuplicateMatch.SAME_BOOK) }
    }

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
        additionalMetadata = if (format == BookFormat.PDF) additionalMetadata + metadata.additionalMetadata
        else metadata.additionalMetadata,
    )

    private fun importCoverId(id: String, format: BookFormat): String =
        if (format == BookFormat.PDF) "$id-pdf-${UUID.randomUUID()}" else id

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

    private val stagingDir by lazy { File(filesRoot, "staging") }

    private fun sweepStaging() {
        val deadline = System.currentTimeMillis() - STAGING_LIFETIME_MILLIS
        stagingDir.listFiles()?.forEach { file ->
            if (file.lastModified() < deadline) file.delete()
        }
    }

    internal open fun openStream(uri: Uri): InputStream =
        (if (uri.scheme == "file") uri.path?.let { File(it).inputStream() }
        else context?.contentResolver?.openInputStream(uri))
            ?: throw IOException("Cannot open the selected file")

    open suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        var removed: Book? = null
        mutateSnapshot(syncCurrentReadingsAfter = true) { before ->
            removed = before.books.firstOrNull { it.id == bookId }
            before.withoutBooks(setOf(bookId))
        }
        removed?.let(::deleteUnusedBookAssets)
    }

    /** Preserve the final progress in reading history and private queue files in one mutation. */
    private fun Snapshot.withoutBooks(bookIds: Set<String>): Snapshot {
        val endedFractions = books.asSequence().filter { it.id in bookIds }.mapNotNull { book ->
            book.activeReadingLogId?.takeUnless { book.suppressReadingLog }
                ?.let { it to book.readingLogProgressFraction }
        }.toMap()
        return copy(
            books = books.filterNot { it.id in bookIds },
            readingLog = readingLog.map { entry ->
                val fraction = endedFractions[entry.id]
                if (entry.status == ReadingLogStatus.IN_PROGRESS && fraction != null)
                    entry.copy(progressFraction = fraction) else entry
            },
        )
    }

    /** Assets are released only after every related database row commits successfully. */
    private fun deleteUnusedBookAssets(book: Book) {
        if (cachedContentId == book.id) releaseContentCache()
        book.fileName?.takeIf { it !in referencedQueueFiles() }?.let { File(booksDir, it).delete() }
        book.coverFileName?.takeIf { it !in referencedCovers() }?.let { File(coversDir, it).delete() }
        purgeDerived(book.id)
    }

    private var cachedContentId: String? = null
    private var cachedContent: BookContent? = null
    private var cachedContentFileName: String? = null
    private var cachedContentResources: Closeable? = null
    private val contentCacheWrites = HashMap<String, kotlinx.coroutines.Job>()

    private val inFlight = HashMap<String, Deferred<BookContent>>()
    private val contentPreviews = HashMap<String, MutableStateFlow<BookContent?>>()

    internal var contentParserForTests: ((File, BookFormat, File, Int, (BookContent) -> Unit) -> BookContent)? = null

    /** Opens a complete book index without restoring every chapter into Kotlin. */
    internal suspend fun openIndexedContent(book: Book): IndexedBookSession? {
        if (book.format == BookFormat.PDF) return null
        val name = book.fileName ?: return null
        // Pin before dispatching IO: trimming must not detach this generation
        // between resolving its cache path and opening the native file handle.
        return IndexedBookSession.open(readerCacheBudget.pin(book.id)) {
            if (!isCurrentContentSource(book)) null
            else {
                val source = File(booksDir, name)
                val opened = parsedContentCache.openIndexed(book.id, source)
                    ?: if (contentParserForTests == null && saveContentDirectly(book, source)) {
                        parsedContentCache.openIndexed(book.id, source)
                    } else {
                        null
                    }
                if (opened != null && isCurrentContentSource(book)) opened
                else { opened?.close(); null }
            }
        }
    }

    private fun saveContentDirectly(book: Book, source: File): Boolean = when (book.format) {
        BookFormat.TXT -> parsedContentCache.saveTxtDirectly(book.id, source)
        BookFormat.MD -> parsedContentCache.saveMdDirectly(book.id, source)
        BookFormat.FB2 -> parsedContentCache.saveFb2Directly(
            bookId = book.id,
            sourceFile = source,
            imagesDir = File(File(imagesDir, book.id), source.nameWithoutExtension),
        )
        else -> false
    }

    internal suspend fun openEpubSourceContent(book: Book): IndexedBookSession? {
        if (book.format != BookFormat.EPUB || contentParserForTests != null) return null
        val name = book.fileName ?: return null
        return IndexedBookSession.open(readerCacheBudget.pin(book.id)) {
            if (!isCurrentContentSource(book)) return@open null
            val source = File(booksDir, name)
            val index = parsedContentCache.loadEpubSourceIndex(book.id, source) ?: return@open null
            val extracted = File(File(imagesDir, book.id), source.nameWithoutExtension)
            EpubSourceContentSession(index, source, extracted) { isCurrentContentSource(book) }
        }
    }

    internal suspend fun readEpubOpeningStructure(book: Book): EpubOpeningStructure? =
        readEpubOpeningSource(book) { source, _ -> EpubParser.readOpeningStructure(source) }

    internal suspend fun readEpubOpeningDocument(
        book: Book,
        destination: EpubSourceDestination,
    ): EpubOpeningDocument? = readEpubOpeningSource(book) { source, checkActive ->
        val extracted = File(File(imagesDir, book.id), source.nameWithoutExtension)
        EpubParser.parseOpeningDocument(source, extracted, ReaderResourceLimits.DEFAULT, destination, checkActive)
    }

    /** The reader owns a longer-lived pin for the assets it goes on to display. */
    private suspend fun <T> readEpubOpeningSource(book: Book, read: (File, () -> Unit) -> T?): T? {
        if (book.format != BookFormat.EPUB || contentParserForTests != null) return null
        val name = book.fileName ?: return null
        return readerCacheBudget.pin(book.id).use {
            withContext(Dispatchers.IO) {
                val source = File(booksDir, name)
                if (!isCurrentContentSource(book) || !source.isFile) return@withContext null
                val length = source.length()
                val modified = source.lastModified()
                val context = currentCoroutineContext()
                try {
                    val result = read(source, context::ensureActive)
                    context.ensureActive()
                    result?.takeIf {
                        isCurrentContentSource(book) && source.isFile &&
                            source.length() == length && source.lastModified() == modified
                    }
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    /** Finishes the shared full parse and transfers ownership of the exact index to the reader. */
    internal suspend fun prepareEpubOpeningIndex(book: Book): IndexedBookSession? {
        if (book.format != BookFormat.EPUB || contentParserForTests != null || book.fileName == null) return null
        awaitContentCacheWrites(book.id)
        openIndexedContent(book)?.let { return it }
        prepareEpubContentCache(book)
        return openIndexedContent(book)?.also { releaseCachedContentFor(book) }
    }

    /** Finish the normal parse once. The live reader keeps its source anchor and window. */
    internal suspend fun prepareEpubSourceContent(book: Book, session: IndexedBookSession): Boolean {
        val name = book.fileName ?: return false
        suspend fun adopt(): Boolean = session.adoptPreparedContent {
            if (!isCurrentContentSource(book)) null else parsedContentCache.openIndexed(book.id, File(booksDir, name))
        }
        awaitContentCacheWrites(book.id)
        if (adopt()) return true
        prepareEpubContentCache(book)
        return adopt().also { prepared -> if (prepared) releaseCachedContentFor(book) }
    }

    private suspend fun prepareEpubContentCache(book: Book) {
        val name = book.fileName ?: return
        if (!isCurrentContentSource(book)) return
        val restored = synchronized(contentLock) {
            cachedContent.takeIf { cachedContentId == book.id && cachedContentFileName == book.fileName }
        }
        if (restored == null && contentParserForTests == null) {
            val source = File(booksDir, name)
            val extracted = File(File(imagesDir, book.id), source.nameWithoutExtension)
            val savedDirectly = readerCacheBudget.pin(book.id).use {
                withContext(Dispatchers.IO) {
                    if (!isCurrentContentSource(book) || !source.isFile) false
                    else parsedContentCache.saveEpubDirectly(book.id, source, extracted)
                }
            }
            if (savedDirectly) {
                trimReaderCaches()
                return
            }
        }
        if (restored == null) loadContent(book, producePreview = false)
        else scheduleContentCacheWrite(book, File(booksDir, name), restored, complete = true)
        awaitContentCacheWrites(book.id)
    }

    private fun releaseCachedContentFor(book: Book) {
        synchronized(contentLock) {
            if (cachedContentId == book.id && cachedContentFileName == book.fileName) releaseContentCache()
        }
    }

    /** Reader disposal must not wait on a native read or scratch cleanup on Main. */
    internal fun closeIndexedContent(session: IndexedBookSession) {
        bookkeeping.launch(Dispatchers.IO) {
            try { session.close() } catch (_: IOException) { /* Disposable scratch is swept on next open. */ }
            trimReaderCaches()
        }
    }

    internal suspend fun discardIndexedContent(book: Book): Unit = withContext(Dispatchers.IO) {
        if (isCurrentContentSource(book)) parsedContentCache.entryFile(book.id).delete()
    }

    open suspend fun loadContent(book: Book): BookContent = loadContent(book, producePreview = true)

    private suspend fun loadContent(book: Book, producePreview: Boolean): BookContent {
        if (book.format == BookFormat.PDF) throw IOException("PDF documents use the native PDF reader")
        synchronized(contentLock) {
            if (cachedContentId == book.id && cachedContentFileName == book.fileName) cachedContent else null
        }?.let { return it }

        val parseKey = "${book.id}/${book.fileName}"
        val parse = synchronized(contentLock) {
            // A different full model must not stay strongly retained while a
            // giant book is decoded. Live readers retain their own resources.
            if (cachedContentId != book.id || cachedContentFileName != book.fileName) {
                releaseContentCache()
            }
            inFlight[parseKey] ?: run {
                val preview = contentPreviews.getOrPut(parseKey) { MutableStateFlow(null) }
                val started = bookkeeping.async(start = CoroutineStart.LAZY) {
                    val publish: ((BookContent) -> Unit)? = if (producePreview) { partial ->
                        if (isCurrentContentSource(book)) preview.value = partial
                    } else null
                    parseAndCache(book, publish)
                }
                inFlight[parseKey] = started
                started.invokeOnCompletion {
                    synchronized(contentLock) {
                        if (inFlight[parseKey] === started) {
                            inFlight.remove(parseKey)
                            contentPreviews.remove(parseKey, preview)
                        }
                    }
                }
                started
            }
        }
        parse.start()
        return parse.await()
    }

    open suspend fun loadContentProgressively(
        book: Book,
        onPreview: suspend (BookContent) -> Unit,
    ): BookContent {
        if (book.format != BookFormat.EPUB && book.format != BookFormat.FB2) return loadContent(book)
        val parseKey = "${book.id}/${book.fileName}"
        val preview = synchronized(contentLock) {
            contentPreviews.getOrPut(parseKey) { MutableStateFlow(null) }
        }
        return coroutineScope {
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                preview.filterNotNull().collect { partial ->
                    if (isCurrentContentSource(book)) onPreview(partial)
                }
            }
            try {
                loadContent(book)
            } finally {
                withContext(NonCancellable) {
                    observer.cancelAndJoin()
                    synchronized(contentLock) {
                        if (inFlight[parseKey] == null) contentPreviews.remove(parseKey, preview)
                    }
                }
            }
        }
    }

    private fun isCurrentContentSource(book: Book): Boolean =
        books.value.firstOrNull { it.id == book.id }?.fileName == book.fileName

    private fun parseAndCache(book: Book, onPreview: ((BookContent) -> Unit)?): BookContent {
        readerCacheBudget.pin(book.id).use {
            return parseAndCachePinned(book, onPreview)
        }
    }

    private fun parseAndCachePinned(book: Book, onPreview: ((BookContent) -> Unit)?): BookContent {
        val name = book.fileName ?: throw IOException("This book has no file attached yet")
        val file = File(booksDir, name)
        if (!file.exists()) throw IOException("Book file is missing")
        bookkeeping.launch { readerCacheBudget.touch(book.id) }
        val extracted = File(File(imagesDir, book.id), file.nameWithoutExtension)
        val previewElementIndex = (book.progress.elementIndex - if (coverFileFor(book) == null) 0 else 1)
            .coerceAtLeast(0)
        val diskPrefix = if (onPreview != null && contentParserForTests == null) parsedContentCache.loadPreview(book.id, file) else null
        if (diskPrefix != null && diskPrefix.totalElements > previewElementIndex) {
            onPreview?.invoke(diskPrefix)
        }
        val disk = if (contentParserForTests != null) null
        else if (!parsedContentCache.entryFile(book.id).isFile) null
        else parsedContentCache.load(book.id, file)
        fun publishPreview(partial: BookContent) {
            if (onPreview == null) return
            if (diskPrefix != null && partial.totalElements <= diskPrefix.totalElements) return
            onPreview(partial)
            if (contentParserForTests == null) {
                val snapshot = openingContentSnapshot(partial, previewElementIndex)
                if (diskPrefix == null || snapshot.totalElements > diskPrefix.totalElements) {
                    scheduleContentCacheWrite(book, file, snapshot, complete = false)
                }
            }
        }
        val content = disk
            ?: contentParserForTests?.invoke(file, book.format, extracted, previewElementIndex, ::publishPreview)
            ?: when (book.format) {
                BookFormat.EPUB -> EpubParser.parseContent(
                    file, extracted, ReaderResourceLimits.DEFAULT,
                    onPreview = onPreview?.let { ::publishPreview }, previewElementIndex = previewElementIndex,
                )
                BookFormat.FB2 -> Fb2Parser.parseContent(
                    open = { file.inputStream().buffered() },
                    imagesDir = extracted,
                    limits = ReaderResourceLimits.DEFAULT,
                    onPreview = onPreview?.let { ::publishPreview },
                    previewElementIndex = previewElementIndex,
                )
                else -> BookParsers.parseContent(file, book.format, extracted)
            }
        synchronized(contentLock) {
            if (books.value.firstOrNull { it.id == book.id }?.fileName == book.fileName) {
                cachedContentResources?.close()
                cachedContentResources = readerCacheBudget.pin(book.id)
                cachedContentId = book.id
                cachedContentFileName = book.fileName
                cachedContent = content
            }
        }
        if (disk == null && contentParserForTests == null) {
            scheduleContentCacheWrite(book, file, content, complete = true)
        }
        trimReaderCaches()
        return content
    }

    private fun scheduleContentCacheWrite(book: Book, source: File, content: BookContent, complete: Boolean) {
        val pin = readerCacheBudget.pin(book.id)
        synchronized(contentLock) {
            val previous = contentCacheWrites[book.id]
            val writer = bookkeeping.launch(readerContentCacheDispatcher, start = CoroutineStart.LAZY) {
                try {
                    previous?.join()
                    if (isCurrentContentSource(book)) {
                        if (complete &&
                            (book.format == BookFormat.TXT || book.format == BookFormat.MD || book.format == BookFormat.FB2) &&
                            parsedContentCache.hasValidCompleteEntry(book.id, source)
                        ) {
                            return@launch
                        }
                        val savedDirect = if (complete && book.format == BookFormat.TXT) {
                            parsedContentCache.saveTxtDirectly(book.id, source, content.language)
                        } else {
                            false
                        }
                        if (!savedDirect) {
                            parsedContentCache.save(book.id, source, content, complete)
                        }
                    }
                } finally {
                    pin.close()
                    trimReaderCachesNow()
                }
            }
            contentCacheWrites[book.id] = writer
            writer.invokeOnCompletion {
                synchronized(contentLock) {
                    if (contentCacheWrites[book.id] === writer) contentCacheWrites.remove(book.id)
                }
            }
            writer.start()
        }
    }

    internal suspend fun awaitContentCacheWrites(bookId: String? = null) {
        val writers = synchronized(contentLock) {
            if (bookId == null) contentCacheWrites.values.toList() else listOfNotNull(contentCacheWrites[bookId])
        }
        writers.forEach { it.join() }
    }

    private val contentLock = Any()

    fun releaseContentCache() {
        synchronized(contentLock) {
            cachedContentId = null
            cachedContentFileName = null
            cachedContent = null
            cachedContentResources?.close()
            cachedContentResources = null
        }
    }

    open fun noteOpened(bookId: String) {
        bookkeeping.launch {
            runCatching { markStarted(bookId) }
        }
    }

    /** Stamps the open and promotes the book and its containing shelf together. */
    open suspend fun markStarted(bookId: String) {
        val now = System.currentTimeMillis()
        mutateSnapshot(syncCurrentReadingsAfter = true, openedBookId = bookId) { before ->
            val books = before.books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        startedAtMillis = if (book.hasFinishedReading || book.status == ReadingStatus.ABANDONED) book.startedAtMillis
                        else book.startedAtMillis ?: now,
                        lastOpenedAtMillis = now,
                        readerSettings = book.readerSettings ?: ReaderSettings.defaultFor(book.format),
                        status = when (book.status) {
                            ReadingStatus.NONE, ReadingStatus.WANT_TO_READ -> ReadingStatus.READING
                            else -> book.status
                        },
                    )
                } else book
            }
            val shelves = before.shelves.map { shelf ->
                if (bookId in shelf.bookIds) {
                    shelf.copy(sortKey = now, bookIds = listOf(bookId) + (shelf.bookIds - bookId))
                } else shelf
            }
            before.copy(books = books, shelves = shelves)
        }
    }

    /** Stamps the finished time once (reader reached the end). */
    open suspend fun markFinished(bookId: String) {
        updateBooks { books ->
            books.map { book ->
                if (book.id == bookId && !book.hasFinishedReading) {
                    book.copy(
                        finishedAtMillis = System.currentTimeMillis(),
                        status = ReadingStatus.FINISHED,
                    )
                } else book
            }
        }
    }

    /**
     * Fast targeted SQLite update: increments `books.reading_seconds` and (if actively logged)
     * `reading_log.reading_seconds` directly without touching quotes, bookmarks, progress, or FTS5.
     */
    open suspend fun addReadingSeconds(bookId: String, seconds: Long) {
        if (seconds <= 0) return
        addReadingSecondsInternal(bookId = bookId, seconds = seconds, expectedReadingLogId = null, checkReadingLogMatch = false)
    }

    /** Persist a qualified reader interval in all three counters as one SQLite transaction. */
    open suspend fun recordReadingTimeForReading(
        bookId: String,
        seconds: Long,
        readingLogId: String?,
        date: LocalDate,
        lastReadAtMillis: Long?,
        statsRepository: StatsRepository,
    ) {
        if (seconds <= 0L) return
        addReadingSecondsInternal(
            bookId, seconds, readingLogId, checkReadingLogMatch = true,
            statsRepository = statsRepository, date = date, lastReadAtMillis = lastReadAtMillis,
        )
    }

    private suspend fun addReadingSecondsInternal(
        bookId: String,
        seconds: Long,
        expectedReadingLogId: String?,
        checkReadingLogMatch: Boolean,
        statsRepository: StatsRepository? = null,
        date: LocalDate? = null,
        lastReadAtMillis: Long? = null,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val before = ensureLoadedSnapshot()
            if (isIndexCorrupted) {
                throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
            }
            val target = before.books.firstOrNull { it.id == bookId }?.takeUnless { book ->
                book.hasFinishedReading || checkReadingLogMatch && (
                    book.status != ReadingStatus.READING || book.activeReadingLogId != expectedReadingLogId ||
                        expectedReadingLogId == null && !book.suppressReadingLog
                    )
            }
            if (target == null && statsRepository == null) return@withLock
            val now = System.currentTimeMillis()
            val newSeconds = (target?.readingSeconds ?: 0L) + seconds
            val activeLogId = target?.takeUnless { it.suppressReadingLog }?.activeReadingLogId

            suspend fun commit(): ReadingStats? {
                val committedStats = executeDbWrite { db ->
                    if (target != null) {
                        db.bookDao().addReadingSeconds(bookId = bookId, deltaSeconds = seconds, nowMillis = now)
                        if (activeLogId != null) {
                            db.readingListsDao().updateLogReadingSeconds(
                                logId = activeLogId,
                                readingSeconds = newSeconds,
                                nowMillis = now,
                            )
                        }
                    }
                    if (statsRepository != null) {
                        val dao = db.statsAndAchievementsDao()
                        dao.addReadingSecondsForDate(requireNotNull(date).toString(), seconds, lastReadAtMillis)
                        buildReadingStats(dao.getAllDailyStats(), dao.getStatsMeta())
                    } else null
                }
                if (target != null) {
                    val updatedBook = target.copy(readingSeconds = newSeconds)
                    val nextBooks = before.books.map { if (it.id == bookId) updatedBook else it }
                    val nextLog = if (activeLogId == null) before.readingLog else before.readingLog.map {
                        if (it.id == activeLogId) it.copy(readingSeconds = newSeconds, updatedAtMillis = now) else it
                    }
                    val nextQueue = refreshLinkedQueueEntries(before.readingQueue, updatedBook)
                    publishSnapshot(before.copy(books = nextBooks, readingQueue = nextQueue, readingLog = nextLog))
                }
                return committedStats
            }

            if (statsRepository == null) commit()
            else statsRepository.updateWithBookTransaction(filesRoot) { requireNotNull(commit()) }
        }
        Unit
    }

    open suspend fun saveReaderSettings(bookId: String, settings: ReaderSettings) {
        updateBooks { books ->
            books.map { if (it.id == bookId) it.copy(readerSettings = settings) else it }
        }
    }

    /**
     * Fast targeted SQLite update: writes ONLY the 1-row `book_progress` record and
     * `books.last_opened_at_millis` without rewriting metadata, passages, Reading Log, or FTS5.
     */
    open suspend fun saveProgress(bookId: String, progress: ReadingProgress) {
        saveProgressInternal(bookId = bookId, progress = progress, expectedReadingLogId = null, checkReadingLogMatch = false)
    }

    private suspend fun saveProgressInternal(
        bookId: String,
        progress: ReadingProgress,
        expectedReadingLogId: String?,
        checkReadingLogMatch: Boolean,
    ) = withContext(Dispatchers.IO) {
        var didChange = false
        writeMutex.withLock {
            val before = ensureLoadedSnapshot()
            if (isIndexCorrupted) {
                throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
            }
            val target = before.books.firstOrNull { it.id == bookId } ?: return@withLock
            if (checkReadingLogMatch) {
                if (target.activeReadingLogId != expectedReadingLogId ||
                    (expectedReadingLogId == null && !target.suppressReadingLog)
                ) return@withLock
            }
            val now = System.currentTimeMillis()
            executeDbWrite { db ->
                db.bookDao().upsertProgress(progress.toEntity(bookId = bookId, updatedAtMillis = now))
                db.bookDao().updateLastOpened(bookId = bookId, lastOpenedAtMillis = now, nowMillis = now)
            }
            val updatedBook = target.copy(progress = progress, lastOpenedAtMillis = now)
            val nextBooks = before.books
                .map { if (it.id == bookId) updatedBook else it }
                .sortedByDescending { it.sortTs }
            val nextQueue = refreshLinkedQueueEntries(before.readingQueue, updatedBook)
            publishSnapshot(before.copy(books = nextBooks, readingQueue = nextQueue))
            didChange = true
        }
        if (didChange) {
            requestWidgetRefresh()
        }
    }

    private fun refreshLinkedQueueEntries(
        queue: List<ReadingQueueEntry>,
        updatedBook: Book,
    ): List<ReadingQueueEntry> =
        if (queue.none { it.attachment?.id == updatedBook.id }) queue
        else queue.map { if (it.attachment?.id == updatedBook.id) it.copy(attachment = updatedBook) else it }

    private suspend fun updateCurrentReading(bookId: String, readingLogId: String?, transform: (Book) -> Book) {
        updateBooks { books ->
            books.map { book ->
                if (book.id == bookId && book.activeReadingLogId == readingLogId &&
                    (readingLogId != null || book.suppressReadingLog)
                ) transform(book) else book
            }
        }
    }

    suspend fun saveProgressForReading(bookId: String, progress: ReadingProgress, readingLogId: String?) =
        saveProgressInternal(bookId = bookId, progress = progress, expectedReadingLogId = readingLogId, checkReadingLogMatch = true)

    suspend fun addReadingSecondsForReading(bookId: String, seconds: Long, readingLogId: String?) {
        if (seconds <= 0) return
        addReadingSecondsInternal(bookId = bookId, seconds = seconds, expectedReadingLogId = readingLogId, checkReadingLogMatch = true)
    }

    suspend fun markFinishedForReading(bookId: String, readingLogId: String?) = updateCurrentReading(bookId, readingLogId) {
        if (!it.hasFinishedReading && it.status == ReadingStatus.READING)
            it.copy(finishedAtMillis = System.currentTimeMillis(), status = ReadingStatus.FINISHED) else it
    }

    open suspend fun setStatus(bookId: String, status: ReadingStatus) {
        updateBooks { books ->
            books.map {
                if (it.id == bookId) it.copy(
                    status = status,
                    finishedAtMillis = if (status == ReadingStatus.FINISHED) it.finishedAtMillis else null,
                ) else it
            }
        }
    }

    open suspend fun setRating(bookId: String, stars: Int?) {
        val clamped = stars?.coerceIn(1, 5)
        updateBooks { books ->
            books.map { if (it.id == bookId) it.copy(rating = clamped, ratingHalfStars = clamped?.times(2)) else it }
        }
    }

    open suspend fun setRatingHalfStars(bookId: String, stars: Int?) {
        require(stars == null || stars in 2..10)
        updateBooks { books ->
            books.map {
                if (it.id == bookId) it.copy(ratingHalfStars = stars, rating = stars?.takeIf { half -> half % 2 == 0 }?.div(2))
                else it
            }
        }
    }

    open suspend fun saveBookReview(bookId: String, ratingHalfStars: Int?, review: String) {
        require(ratingHalfStars == null || ratingHalfStars in 2..10)
        require(review.length <= 20_000)
        val text = review.trim().ifBlank { null }
        updateBooks { books ->
            books.map {
                if (it.id != bookId) it else it.copy(
                    ratingHalfStars = ratingHalfStars,
                    rating = ratingHalfStars?.takeIf { stars -> stars % 2 == 0 }?.div(2),
                    review = text,
                    reviewUpdatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    open suspend fun saveReviewForReading(bookId: String, readingLogId: String?, ratingHalfStars: Int?, review: String) {
        require(ratingHalfStars == null || ratingHalfStars in 2..10)
        require(review.length <= 20_000)
        val text = review.trim().ifBlank { null }
        mutateSnapshot(syncCurrentReadingsAfter = true) { before ->
            val current = before.books.firstOrNull { it.id == bookId }
                ?: throw IOException("The book no longer exists.")
            if (current.activeReadingLogId != readingLogId || (readingLogId == null && !current.suppressReadingLog)) {
                throw IOException("This reading is no longer current.")
            }
            before.copy(
                books = before.books.map { book ->
                    if (book.id != bookId) book else book.copy(
                        ratingHalfStars = ratingHalfStars,
                        rating = ratingHalfStars?.takeIf { it % 2 == 0 }?.div(2),
                        review = text,
                        reviewUpdatedAtMillis = System.currentTimeMillis(),
                    )
                }
            )
        }
    }

    open suspend fun setReview(bookId: String, review: String?) {
        val text = review?.takeIf { it.isNotBlank() }
        updateBooks { books ->
            books.map { book ->
                if (book.id != bookId || book.review == text) return@map book
                book.copy(review = text, reviewUpdatedAtMillis = System.currentTimeMillis())
            }
        }
    }

    open suspend fun setQuoteNote(bookId: String, quoteId: String, note: String?) {
        val text = note?.takeIf { it.isNotBlank() }
        updateQuotes(bookId) { quotes -> quotes.map { if (it.id == quoteId) it.copy(note = text) else it } }
    }

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
        updateBooks { listOf(book) + it }
        return book
    }

    internal suspend fun addBookForRestore(book: Book) {
        updateBooks { books -> listOf(book) + books.filterNot { it.id == book.id } }
    }

    internal fun bookFileFor(book: Book): File? =
        book.fileName?.let { File(booksDir, it) }?.takeIf { it.exists() }

    internal suspend fun replaceAll(
        books: List<Book>,
        shelves: List<Shelf>,
        readingQueue: List<ReadingQueueEntry> = emptyList(),
        readingLog: List<ReadingLogEntry> = emptyList(),
    ) {
        mutateSnapshot(syncCurrentReadingsAfter = true, forceFullRewrite = true) {
            Snapshot(
                books = books,
                shelves = shelves,
                readingQueue = readingQueue.distinctBy { it.id },
                readingLog = readingLog.distinctBy { it.id },
            )
        }
        withContext(Dispatchers.IO) {
            val allFiles = books + readingQueue.mapNotNull { it.attachment }
            val keptBooks = allFiles.mapNotNullTo(HashSet()) { it.fileName }
            val keptCovers = allFiles.mapNotNullTo(HashSet()) { it.coverFileName }
            keptCovers += readingLog.mapNotNull { it.coverFileName }
            val keptIds = allFiles.mapTo(HashSet()) { it.id }
            booksDir.listFiles()?.forEach { if (it.name !in keptBooks) it.delete() }
            coversDir.listFiles()?.forEach { if (it.name !in keptCovers) it.delete() }
            imagesDir.listFiles()?.forEach { if (it.isDirectory && it.name !in keptIds) it.deleteRecursively() }
            File(filesRoot, "pagination").listFiles()?.forEach {
                if (it.nameWithoutExtension !in keptIds) it.delete()
            }
            parsedContentCache.pruneEpubSourceIndexes(keptIds)
            releaseContentCache()
        }
    }

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
                val metadata = parseImportMetadata(stored, format)
                val hash = ContentHash.of(stored)
                val coverFileName = existing.coverFileName
                    ?: writeCover(importCoverId(bookId, format), metadata.coverBytes.takeUnless {
                        format == BookFormat.PDF && !existing.generatePdfCover
                    })
                cachePdfOutline(stored, hash, metadata)
                updateBooks { books ->
                    books.map { book ->
                        if (book.id != bookId) return@map book
                        book.withParsedMetadata(
                            metadata = metadata,
                            format = format,
                            fileName = stored.name,
                            coverFileName = coverFileName,
                            contentHash = hash,
                            sizeBytes = stored.length(),
                        ).let { attached ->
                            if ((book.format == BookFormat.PDF || format == BookFormat.PDF) &&
                                (book.format != format || book.contentHash != hash)
                            ) attached.withoutDocumentAnchors()
                            else attached
                        }
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

    open suspend fun cleanOrphanCaches() = withContext(Dispatchers.IO) {
        sweepStaging()
        if (isIndexCorrupted) return@withContext
        readerCacheBudget.trim(_books.value.mapTo(HashSet()) { it.id })
    }

    suspend fun editableMetadata(bookId: String): Pair<Book, EditableBookMetadata> = withContext(Dispatchers.IO) {
        val book = ensurePdfAnnotations(bookId) ?: throw IOException("Book no longer exists.")
        if (book.format == BookFormat.PDF) return@withContext book to EditableBookMetadata.from(book)
        val file = bookFileFor(book) ?: throw IOException("Attach the book file before editing its metadata.")
        book to EditableBookMetadata.from(book, BookParsers.parseMetadata(file, book.format))
    }

    suspend fun metadataFileInfo(book: Book) = withContext(Dispatchers.IO) {
        BookMetadataWriter.fileInfo(bookFileFor(book) ?: throw IOException("Book file is missing."), book.format)
    }

    suspend fun saveBookMetadata(
        bookId: String,
        expectedFileName: String?,
        metadata: EditableBookMetadata,
        cover: CoverEdit = CoverEdit.Keep,
    ): Book = withContext(Dispatchers.IO) {
        val original = books.value.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
        require(original.fileName == expectedFileName) { "The book file changed. Reopen the editor before saving." }
        if (original.format == BookFormat.PDF) {
            return@withContext savePdfLibraryMetadata(bookId, expectedFileName, metadata, cover)
        }
        val source = bookFileFor(original) ?: throw IOException("Attach the book file before editing its metadata.")
        val generation = "$bookId-edit-${UUID.randomUUID()}"
        val target = File(booksDir, "$generation.${source.extension}")
        var newCover: File? = null
        var committed = false
        try {
            val actual = BookMetadataWriter.write(source, target, original.format, metadata, cover)
            val hash = ContentHash.of(target)
            if (actual.coverBytes != null) {
                coversDir.mkdirs()
                newCover = File(coversDir, "$generation.img")
                FileOutputStream(newCover).use { it.write(actual.coverBytes); it.fd.sync() }
            }
            withContext(NonCancellable) {
                updateBooks { current ->
                    val live = current.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
                    require(live.fileName == expectedFileName) { "The book file changed. Reopen the editor before saving." }
                    current.map { book ->
                        if (book.id != bookId) book else book.copy(
                            title = actual.title ?: metadata.title,
                            author = actual.authors.ifEmpty { listOfNotNull(actual.author) }.joinToString(", ").ifBlank { null },
                            description = actual.description, genres = actual.genres,
                            series = actual.series, seriesNumber = actual.seriesNumber,
                            publisher = actual.publisher, year = actual.year, isbn = actual.isbn,
                            translators = actual.translators, language = actual.language,
                            fileName = target.name, coverFileName = newCover?.name,
                            contentHash = hash, sizeBytes = target.length(),
                        )
                    }
                }
                committed = true
                releaseContentCache()
                File(File(filesRoot, "pagination"), "$bookId.json").delete()
                File(File(filesRoot, "pagination"), "$bookId.rpi").delete()
                File(File(filesRoot, "pagination"), "$bookId/layout.frb").delete()
                cleanupMetadataEditFiles(bookId)
            }
            books.value.first { it.id == bookId }
        } finally {
            if (!committed) {
                target.delete()
                newCover?.delete()
            }
        }
    }

    private suspend fun savePdfLibraryMetadata(
        bookId: String,
        expectedFileName: String?,
        metadata: EditableBookMetadata,
        cover: CoverEdit,
    ): Book {
        val desired = metadata.normalized().also { it.validate() }
        var newCover: File? = null
        var committed = false
        try {
            if (cover is CoverEdit.Replace) {
                require(cover.bytes.isNotEmpty() && cover.bytes.size <= 32 * 1024 * 1024) { "Choose an image smaller than 32 MB." }
                coversDir.mkdirs()
                val target = File(coversDir, "$bookId-edit-${UUID.randomUUID()}.img")
                newCover = target
                FileOutputStream(target).use { it.write(cover.bytes); it.fd.sync() }
            }
            withContext(NonCancellable) {
                updateBooks { books ->
                    val live = books.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
                    require(live.fileName == expectedFileName && live.format == BookFormat.PDF) {
                        "The book file changed. Reopen the editor before saving."
                    }
                    books.map { book ->
                        if (book.id != bookId) book else book.copy(
                            title = desired.title,
                            author = desired.authors.joinToString(", ").ifBlank { null },
                            description = desired.description.ifBlank { null }, genres = desired.genres,
                            series = desired.series.ifBlank { null }, seriesNumber = desired.seriesNumber.toFloatOrNull(),
                            publisher = desired.publisher.ifBlank { null }, year = desired.year.ifBlank { null },
                            isbn = desired.isbn.ifBlank { null }, translators = desired.translators,
                            language = desired.language.ifBlank { null },
                            coverFileName = when (cover) {
                                CoverEdit.Keep -> book.coverFileName
                                CoverEdit.Remove -> null
                                is CoverEdit.Replace -> newCover!!.name
                            },
                            generatePdfCover = if (cover == CoverEdit.Keep) book.generatePdfCover else false,
                        )
                    }
                }
                committed = true
                cleanupMetadataEditFiles(bookId)
            }
            return books.value.first { it.id == bookId }
        } finally {
            if (!committed) newCover?.delete()
        }
    }

    private fun cleanupMetadataEditFiles(bookId: String) {
        runCatching {
            val current = ensureLoadedSnapshot()
            val keptBooks = current.books.mapNotNull { it.fileName }.toSet() + referencedQueueFiles()
            val keptCovers = referencedCovers()
            booksDir.listFiles()?.filter { it.name.startsWith("$bookId-edit-") && it.name !in keptBooks }?.forEach { it.delete() }
            coversDir.listFiles()?.filter { it.name.startsWith("$bookId-edit-") && it.name !in keptCovers }?.forEach { it.delete() }
        }
    }

    suspend fun exportBook(bookId: String, destination: Uri) = withContext(Dispatchers.IO) {
        val c = context ?: throw IOException("Cannot export without a document provider.")
        val book = books.value.firstOrNull { it.id == bookId } ?: throw IOException("Book no longer exists.")
        val file = bookFileFor(book) ?: throw IOException("Book file is missing.")
        c.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw IOException("Cannot write to the selected location.")
    }

    suspend fun toggleBookmark(bookId: String, bookmark: Bookmark) {
        updateBookmarksFast(bookId) { current ->
            val existing = current.firstOrNull { it.hasSameLocationAs(bookmark) }
            if (existing != null) {
                current - existing
            } else {
                (current + bookmark).sortedBy { (it.documentLocation as? DocumentLocation.Pdf)?.pageIndex ?: it.flatIndex }
            }
        }
    }

    private fun Bookmark.hasSameLocationAs(other: Bookmark): Boolean {
        val pdf = documentLocation as? DocumentLocation.Pdf
        val otherPdf = other.documentLocation as? DocumentLocation.Pdf
        return if (pdf != null || otherPdf != null) {
            !orphaned && !other.orphaned && pdf != null && otherPdf != null &&
                pdf.pageIndex >= 0 && pdf.pageIndex == otherPdf.pageIndex
        } else flatIndex >= 0 && flatIndex == other.flatIndex
    }

    suspend fun removeBookmark(bookId: String, bookmarkId: String) {
        updateBookmarksFast(bookId) { current -> current.filterNot { it.id == bookmarkId } }
    }

    suspend fun restoreBookmark(bookId: String, bookmark: Bookmark) {
        updateBookmarksFast(bookId) { current ->
            if (current.any { it.id == bookmark.id || it.hasSameLocationAs(bookmark) }) current
            else current + bookmark
        }
    }

    suspend fun addQuote(bookId: String, quote: Quote) {
        updateQuotes(bookId) { quotes -> if (quotes.any { it.id == quote.id }) quotes else quotes + quote }
    }

    internal suspend fun addPdfNote(bookId: String, quote: Quote) {
        updateQuotes(bookId) { quotes ->
            if (quotes.none { it.id == quote.id }) quotes + quote
            else quotes.map { if (it.id == quote.id) it.copy(note = quote.note, color = quote.color) else it }
        }
    }

    suspend fun setQuoteColor(bookId: String, quoteId: String, color: QuoteColor) {
        updateQuotes(bookId) { quotes -> quotes.map { if (it.id == quoteId) it.copy(color = color) else it } }
    }

    suspend fun removeQuote(bookId: String, quoteId: String) {
        removeQuotes(bookId, setOf(quoteId))
    }

    suspend fun removeQuotes(bookId: String, quoteIds: Set<String>) {
        if (quoteIds.isEmpty()) return
        updateQuotes(bookId) { quotes -> quotes.filterNot { it.id in quoteIds } }
    }

    suspend fun restoreQuote(bookId: String, quote: Quote) {
        updateQuotes(bookId) { quotes -> if (quotes.any { it.id == quote.id }) quotes else quotes + quote }
    }

    private suspend fun updateQuotes(bookId: String, transform: (List<Quote>) -> List<Quote>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val before = ensureLoadedSnapshot()
                if (isIndexCorrupted) {
                    throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
                }
                val book = before.books.firstOrNull { it.id == bookId } ?: return@withLock
                val now = System.currentTimeMillis()
                val oldById = book.quotes.associateBy { it.id }
                val rawDesired = transform(book.quotes)
                val stampedDesired = rawDesired.map { q ->
                    val prev = oldById[q.id]
                    val stamped = q.copy(bookId = bookId)
                    if (prev != null && stamped.copy(updatedAtMillis = prev.updatedAtMillis) == prev) {
                        prev
                    } else {
                        stamped.copy(updatedAtMillis = now)
                    }
                }.sortedBy { it.createdAtMillis }

                if (stampedDesired == book.quotes) return@withLock

                val desiredIds = stampedDesired.mapTo(HashSet()) { it.id }
                val removedIds = oldById.keys.filterNot { it in desiredIds }

                executeDbWrite { db ->
                    val dao = db.passagesDao()
                    for (removedId in removedIds) {
                        dao.deleteQuoteById(removedId)
                    }
                    for (q in stampedDesired) {
                        if (oldById[q.id] != q) {
                            dao.saveQuote(q.toEntity(bookId = bookId))
                        }
                    }
                }

                val updatedBook = book.copy(quotes = stampedDesired)
                val nextBooks = before.books.map { if (it.id == bookId) updatedBook else it }
                val nextQueue = refreshLinkedQueueEntries(before.readingQueue, updatedBook)
                publishSnapshot(before.copy(books = nextBooks, readingQueue = nextQueue))
            }
        }
        schedulePdfAnnotations(bookId)
    }

    private suspend fun updateBookmarksFast(bookId: String, transform: (List<Bookmark>) -> List<Bookmark>) =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val before = ensureLoadedSnapshot()
                if (isIndexCorrupted) {
                    throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
                }
                val book = before.books.firstOrNull { it.id == bookId } ?: return@withLock
                val now = System.currentTimeMillis()
                val oldById = book.bookmarks.associateBy { it.id }
                val rawDesired = transform(book.bookmarks)
                val stampedDesired = rawDesired.map { b ->
                    val prev = oldById[b.id]
                    val stamped = b.copy(bookId = bookId)
                    if (prev != null && stamped.copy(updatedAtMillis = prev.updatedAtMillis) == prev) {
                        prev
                    } else {
                        stamped.copy(updatedAtMillis = now)
                    }
                }.sortedBy { it.flatIndex }

                if (stampedDesired == book.bookmarks) return@withLock

                val desiredIds = stampedDesired.mapTo(HashSet()) { it.id }
                val removedIds = oldById.keys.filterNot { it in desiredIds }

                executeDbWrite { db ->
                    val dao = db.passagesDao()
                    for (removedId in removedIds) {
                        dao.deleteBookmarkById(removedId)
                    }
                    for (b in stampedDesired) {
                        if (oldById[b.id] != b) {
                            dao.saveBookmark(b.toEntity(bookId = bookId))
                        }
                    }
                }

                val updatedBook = book.copy(bookmarks = stampedDesired)
                val nextBooks = before.books.map { if (it.id == bookId) updatedBook else it }
                val nextQueue = refreshLinkedQueueEntries(before.readingQueue, updatedBook)
                publishSnapshot(before.copy(books = nextBooks, readingQueue = nextQueue))
            }
        }

    internal fun schedulePdfAnnotations(bookId: String) {
        if (writePdfAnnotations == null || bookById(bookId)?.format != BookFormat.PDF) return
        _pdfAnnotationErrors.update { it - bookId }
        val requests = synchronized(pdfAnnotationRequests) {
            pdfAnnotationRequests.getOrPut(bookId) {
                Channel<Unit>(Channel.CONFLATED).also { channel ->
                    pdfAnnotationScope.launch {
                        for (request in channel) {
                            while (withTimeoutOrNull(pdfAnnotationDebounceMillis.coerceAtLeast(1)) {
                                    channel.receive(); true
                                } == true
                            ) {
                            }
                            try {
                                ensurePdfAnnotations(bookId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    internal suspend fun ensurePdfAnnotations(bookId: String): Book? {
        if (writePdfAnnotations == null) return bookById(bookId)
        try {
            return syncPdfAnnotations(bookId).also { _pdfAnnotationErrors.update { it - bookId } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _pdfAnnotationErrors.update { it + bookId }
            throw failure
        }
    }

    internal suspend fun flushPdfAnnotations() {
        books.value.filter { it.format == BookFormat.PDF }.forEach { ensurePdfAnnotations(it.id) }
    }

    private fun annotationFingerprint(quotes: List<Quote>): String {
        val stable = quotes.map { it.copy(bookId = "", updatedAtMillis = 0L) }
        val bytes = json.encodeToString(ListSerializer(Quote.serializer()), stable).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private suspend fun syncPdfAnnotations(bookId: String): Book? = pdfAnnotationLock.withLock {
        withContext(pdfAnnotationDispatcher) {
            while (true) {
                val original = bookById(bookId) ?: return@withContext null
                if (original.format != BookFormat.PDF) return@withContext original
                val desired = original.quotes
                val fingerprint = annotationFingerprint(desired)
                if (original.pdfAnnotationFingerprint == fingerprint) return@withContext original
                val source = bookFileFor(original)
                if (source == null || (original.pdfAnnotationFingerprint == null &&
                        desired.none { it.pdfSelection.isNotEmpty() })
                ) return@withContext original
                val target = File(booksDir, "$bookId-edit-${UUID.randomUUID()}.pdf")
                var committed = false
                var accepted = false
                try {
                    writePdfAnnotations!!.invoke(source, target, bookId, desired, pdfPasswords[bookId])
                    val hash = ContentHash.of(target)
                    withContext(NonCancellable) {
                        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
                            val live = before.books.firstOrNull { it.id == bookId } ?: return@mutateSnapshot before
                            if (live.fileName != original.fileName || live.quotes != desired) return@mutateSnapshot before
                            accepted = true
                            before.copy(
                                books = before.books.map { book ->
                                    if (book.id != bookId) book else book.copy(
                                        fileName = target.name,
                                        contentHash = hash,
                                        sizeBytes = target.length(),
                                        pdfAnnotationFingerprint = fingerprint,
                                    )
                                }
                            )
                        }
                        committed = accepted
                        if (committed) cleanupMetadataEditFiles(bookId)
                    }
                    if (committed) {
                        val current = bookById(bookId)
                        if (current == null || current.pdfAnnotationFingerprint == annotationFingerprint(current.quotes)) {
                            return@withContext current
                        }
                    }
                } finally {
                    if (!committed) {
                        val unreferenced = runCatching {
                            ensureLoadedSnapshot().books.none { it.fileName == target.name }
                        }.getOrDefault(false)
                        if (unreferenced) target.delete()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    // --------------------------------------------------------------- shelves

    open suspend fun createShelf(bookIds: List<String>, name: String = ""): Shelf {
        val byId = _books.value.associateBy { it.id }
        val ids = bookIds.distinct().filter { it in byId }
        val shelf = Shelf(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            bookIds = ids,
            createdAtMillis = System.currentTimeMillis(),
            sortKey = ids.firstOrNull()?.let { byId.getValue(it).sortTs } ?: 0L,
        )
        updateShelves { shelves -> shelves + shelf }
        return _shelves.value.firstOrNull { it.id == shelf.id } ?: shelf
    }

    open suspend fun renameShelf(id: String, name: String): Unit = updateShelves { shelves ->
        shelves.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    open suspend fun addToShelf(shelfId: String, bookIds: List<String>): Unit = updateShelves { shelves ->
        if (shelves.none { it.id == shelfId }) return@updateShelves shelves
        shelves.map { shelf ->
            if (shelf.id == shelfId) {
                shelf.copy(bookIds = shelf.bookIds + bookIds.filterNot { it in shelf.bookIds })
            } else {
                shelf.copy(bookIds = shelf.bookIds - bookIds.toSet())
            }
        }
    }

    open suspend fun removeFromShelf(shelfId: String, bookIds: List<String>): Unit =
        updateShelves { shelves ->
            shelves.map { shelf ->
                if (shelf.id == shelfId) {
                    shelf.copy(bookIds = shelf.bookIds - bookIds.toSet())
                } else shelf
            }
        }

    open suspend fun deleteShelf(id: String): Unit = updateShelves { shelves ->
        shelves.filterNot { it.id == id }
    }

    open suspend fun deleteShelfWithBooks(id: String) = withContext(Dispatchers.IO) {
        var removed = emptyList<Book>()
        mutateSnapshot(syncCurrentReadingsAfter = true) { before ->
            val bookIds = before.shelves.firstOrNull { it.id == id }?.bookIds.orEmpty().toSet()
            removed = before.books.filter { it.id in bookIds }
            before.withoutBooks(bookIds).copy(shelves = before.shelves.filterNot { it.id == id })
        }
        removed.forEach(::deleteUnusedBookAssets)
    }

    internal fun readIndex(): List<Book> = loadSnapshotBlocking().second.books

    private fun normalizeShelves(shelves: List<Shelf>, books: List<Book>): List<Shelf> {
        if (shelves.isEmpty()) return emptyList()
        val known = books.mapTo(HashSet()) { it.id }
        val claimed = HashSet<String>()
        val seenShelfIds = HashSet<String>()
        return shelves
            .filter { seenShelfIds.add(it.id) }
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds.filter { it in known && claimed.add(it) }) }
            .sortedWith(compareByDescending<Shelf> { it.sortTs }.thenBy { it.id })
    }

    private suspend fun updateBooks(transform: (List<Book>) -> List<Book>) {
        mutateSnapshot(syncCurrentReadingsAfter = true) { before ->
            val nextBooks = transform(before.books)
            // Capture progressFraction on any active reading that just lost its active link:
            val activeAfter = nextBooks.mapNotNullTo(HashSet()) { it.activeReadingLogId.takeUnless { _ -> it.suppressReadingLog } }
            val endedFractions = before.books.mapNotNull { b ->
                val logId = b.activeReadingLogId?.takeUnless { b.suppressReadingLog }
                if (logId != null && logId !in activeAfter) logId to b.readingLogProgressFraction else null
            }.toMap()
            val adjustedLog = if (endedFractions.isEmpty()) before.readingLog else before.readingLog.map { entry ->
                val frac = endedFractions[entry.id]
                if (entry.status == ReadingLogStatus.IN_PROGRESS && frac != null) entry.copy(progressFraction = frac) else entry
            }
            before.copy(books = nextBooks, readingLog = adjustedLog)
        }
    }

    private suspend fun updateShelves(transform: (List<Shelf>) -> List<Shelf>) {
        mutateSnapshot(syncCurrentReadingsAfter = false) { before ->
            before.copy(shelves = transform(before.shelves))
        }
    }

    /**
     * Runs [transform] under [writeMutex], persists ONLY the SQLite rows that actually changed
     * inside a single ACID `withWriteTransaction`, and publishes the updated [StateFlow]s only
     * after the SQLite transaction commits.
     */
    private suspend fun mutateSnapshot(
        syncCurrentReadingsAfter: Boolean,
        openedBookId: String? = null,
        forceFullRewrite: Boolean = false,
        transform: (Snapshot) -> Snapshot,
    ) = withContext(Dispatchers.IO) {
        var libraryChanged = false
        writeMutex.withLock {
            val before = ensureLoadedSnapshot()
            if (isIndexCorrupted) {
                throw LibraryIndexCorruptedException("Cannot update the library because the data on disk is corrupted.")
            }

            val transformed = transform(before)
            val (syncedBooks, syncedLog) = if (syncCurrentReadingsAfter) {
                syncCurrentReadingsPure(transformed.books, transformed.readingLog, openedBookId = openedBookId)
            } else {
                transformed.books to transformed.readingLog
            }

            val now = System.currentTimeMillis()
            val normalizedBooks = syncedBooks
                .map { book ->
                    book.copy(
                        quotes = book.quotes.map { it.copy(bookId = book.id) }.sortedBy { it.createdAtMillis },
                        bookmarks = book.bookmarks.map { it.copy(bookId = book.id) }.sortedBy { it.flatIndex },
                    )
                }
                .sortedByDescending { it.sortTs }

            val normalizedShelves = normalizeShelves(transformed.shelves, normalizedBooks)
                .map { shelf ->
                    val prev = before.shelves.firstOrNull { it.id == shelf.id }
                    if (prev != null && shelf.copy(updatedAtMillis = prev.updatedAtMillis) == prev) prev
                    else shelf.copy(updatedAtMillis = now)
                }

            val stampedLog = syncedLog.map { entry ->
                val prev = before.readingLog.firstOrNull { it.id == entry.id }
                if (prev != null && entry.copy(updatedAtMillis = prev.updatedAtMillis) == prev) prev
                else entry.copy(updatedAtMillis = now)
            }

            val booksById = normalizedBooks.associateBy { it.id }
            val stampedQueue = transformed.readingQueue.map { entry ->
                val resolvedAttachment = entry.attachment?.let { att -> booksById[att.id] ?: att }
                val candidate = entry.copy(attachment = resolvedAttachment)
                val prev = before.readingQueue.firstOrNull { it.id == entry.id }
                if (prev != null && candidate.copy(updatedAtMillis = prev.updatedAtMillis) == prev) prev
                else candidate.copy(updatedAtMillis = now)
            }

            val after = Snapshot(
                books = normalizedBooks,
                shelves = normalizedShelves,
                readingQueue = stampedQueue,
                readingLog = stampedLog,
            )

            val diskBefore = synchronized(stateLock) { persistedSnapshot } ?: before
            if (!forceFullRewrite && after == before && diskBefore == before) return@withLock

            executeDbWrite(repairPendingSnapshot = false) { db ->
                if (forceFullRewrite) {
                    rewriteAllTables(db, after, now)
                } else {
                    persistSnapshotDiff(db, diskBefore, after, now)
                }
            }

            libraryChanged = after.books != before.books || after.shelves != before.shelves
            publishSnapshot(after)
        }
        if (libraryChanged) {
            requestWidgetRefresh()
        }
    }

    private suspend fun rewriteAllTables(db: FrogReaderDatabase, after: Snapshot, now: Long) {
        val bookDao = db.bookDao()
        val shelfDao = db.shelfDao()
        val passagesDao = db.passagesDao()
        val listsDao = db.readingListsDao()

        passagesDao.clearAllQuotesFts()
        passagesDao.clearAllQuotes()
        passagesDao.clearAllBookmarksFts()
        passagesDao.clearAllBookmarks()
        listsDao.clearAllQueueFts()
        listsDao.clearAllQueue()
        listsDao.clearAllLogFts()
        listsDao.clearAllLog()
        bookDao.clearAllProgress()
        bookDao.clearAllBooksFts()
        bookDao.clearAllBooks()
        shelfDao.clearAllShelves()

        val shelfAssignments = buildShelfAssignments(after.shelves)
        for (shelf in after.shelves) {
            shelfDao.saveShelf(shelf.toEntity())
        }
        for (book in after.books) {
            val assign = shelfAssignments[book.id]
            bookDao.saveBookEntity(
                book.toBookEntity(
                    shelfId = assign?.first,
                    shelfPosition = assign?.second ?: 0,
                    updatedAtMillis = now,
                ),
                syncFts = true,
            )
            if (book.progress != ReadingProgress()) {
                bookDao.upsertProgress(book.toProgressEntity(updatedAtMillis = now))
            }
            for (quote in book.quotes) {
                passagesDao.saveQuote(quote.toEntity(bookId = book.id))
            }
            for (bookmark in book.bookmarks) {
                passagesDao.saveBookmark(bookmark.toEntity(bookId = book.id))
            }
        }
        val libraryBookIds = after.books.mapTo(HashSet()) { it.id }
        after.readingQueue.forEachIndexed { idx, entry ->
            listsDao.saveQueueEntry(entry.toEntity(priorityOrder = idx, libraryBookIds = libraryBookIds), syncFts = true)
        }
        after.readingLog.forEachIndexed { idx, entry ->
            listsDao.saveLogEntry(entry.toEntity(manualOrder = idx), syncFts = true)
        }
    }

    private suspend fun persistSnapshotDiff(
        db: FrogReaderDatabase,
        before: Snapshot,
        after: Snapshot,
        now: Long,
    ) {
        val bookDao = db.bookDao()
        val shelfDao = db.shelfDao()
        val passagesDao = db.passagesDao()
        val listsDao = db.readingListsDao()

        val beforeShelvesById = before.shelves.associateBy { it.id }
        val afterShelfIds = after.shelves.mapTo(HashSet()) { it.id }

        // 1. Insert/update shelves first so books can reference new shelf_id foreign keys:
        for (shelf in after.shelves) {
            val prev = beforeShelvesById[shelf.id]
            if (prev == null || prev.toEntity() != shelf.toEntity()) {
                shelfDao.saveShelf(shelf.toEntity())
            }
        }

        val beforeShelfAssign = buildShelfAssignments(before.shelves)
        val afterShelfAssign = buildShelfAssignments(after.shelves)

        val beforeBooksById = before.books.associateBy { it.id }
        val afterBookIds = after.books.mapTo(HashSet()) { it.id }

        // 2. Before deleting any removed books, snapshot linked queue attachments so ON DELETE SET NULL
        //    never drops a queued library book's file metadata!
        val removedBookIds = beforeBooksById.keys.filterNot { it in afterBookIds }
        if (removedBookIds.isNotEmpty()) {
            val afterQueueById = after.readingQueue.associateBy { it.id }
            after.readingQueue.forEachIndexed { idx, queueEntry ->
                val attachedId = queueEntry.attachment?.id
                if (attachedId != null && attachedId in removedBookIds && queueEntry.id in afterQueueById) {
                    listsDao.saveQueueEntry(
                        queueEntry.toEntity(priorityOrder = idx, libraryBookIds = afterBookIds),
                        syncFts = false,
                    )
                }
            }
            for (removedBookId in removedBookIds) {
                bookDao.deleteBookCascadeWithFts(removedBookId)
            }
        }

        // 3. Delete removed shelves (books reassigned away or set null):
        for (oldShelfId in beforeShelvesById.keys) {
            if (oldShelfId !in afterShelfIds) {
                shelfDao.deleteShelfById(oldShelfId)
            }
        }

        // 4. Insert/update books, progress, quotes, and bookmarks:
        for (book in after.books) {
            val prev = beforeBooksById[book.id]
            val prevAssign = beforeShelfAssign[book.id]
            val nextAssign = afterShelfAssign[book.id]

            val nextBookEntity = book.toBookEntity(
                shelfId = nextAssign?.first,
                shelfPosition = nextAssign?.second ?: 0,
                updatedAtMillis = now,
            )
            val prevBookEntity = prev?.toBookEntity(
                shelfId = prevAssign?.first,
                shelfPosition = prevAssign?.second ?: 0,
                updatedAtMillis = now,
            )

            if (prevBookEntity == null || nextBookEntity != prevBookEntity) {
                val ftsChanged = prev == null ||
                    prev.title != book.title ||
                    prev.author != book.author ||
                    prev.series != book.series ||
                    prev.description != book.description
                bookDao.saveBookEntity(nextBookEntity, syncFts = ftsChanged)
            }

            if (prev == null || prev.progress != book.progress) {
                if (book.progress == ReadingProgress()) {
                    if (prev != null) bookDao.deleteProgressByBookId(book.id)
                } else {
                    bookDao.upsertProgress(book.toProgressEntity(updatedAtMillis = now))
                }
            }

            if (prev == null || prev.quotes != book.quotes) {
                val oldQuotes = prev?.quotes.orEmpty().associateBy { it.id }
                val newQuoteIds = book.quotes.mapTo(HashSet()) { it.id }
                for (oldId in oldQuotes.keys) {
                    if (oldId !in newQuoteIds) passagesDao.deleteQuoteById(oldId)
                }
                for (q in book.quotes) {
                    if (oldQuotes[q.id] != q) {
                        passagesDao.saveQuote(q.toEntity(bookId = book.id))
                    }
                }
            }

            if (prev == null || prev.bookmarks != book.bookmarks) {
                val oldBookmarks = prev?.bookmarks.orEmpty().associateBy { it.id }
                val newBookmarkIds = book.bookmarks.mapTo(HashSet()) { it.id }
                for (oldId in oldBookmarks.keys) {
                    if (oldId !in newBookmarkIds) passagesDao.deleteBookmarkById(oldId)
                }
                for (b in book.bookmarks) {
                    if (oldBookmarks[b.id] != b) {
                        passagesDao.saveBookmark(b.toEntity(bookId = book.id))
                    }
                }
            }
        }

        // 5. Diff Reading Queue:
        if (before.readingQueue != after.readingQueue || removedBookIds.isNotEmpty()) {
            val beforeQueueById = before.readingQueue.mapIndexed { idx, e -> e.id to (idx to e) }.toMap()
            val afterQueueIds = after.readingQueue.mapTo(HashSet()) { it.id }
            val beforeBookIds = beforeBooksById.keys
            for (oldId in beforeQueueById.keys) {
                if (oldId !in afterQueueIds) {
                    listsDao.deleteQueueEntryById(oldId)
                }
            }
            after.readingQueue.forEachIndexed { idx, entry ->
                val prevPair = beforeQueueById[entry.id]
                val nextEntity = entry.toEntity(priorityOrder = idx, libraryBookIds = afterBookIds)
                val prevEntity = prevPair?.let { (prevIdx, prevEntry) ->
                    prevEntry.toEntity(priorityOrder = prevIdx, libraryBookIds = beforeBookIds)
                }
                if (prevEntity == null || nextEntity != prevEntity) {
                    val ftsChanged = prevPair == null ||
                        prevPair.second.title != entry.title ||
                        prevPair.second.author != entry.author ||
                        prevPair.second.note != entry.note
                    listsDao.saveQueueEntry(nextEntity, syncFts = ftsChanged)
                }
            }
        }

        // 6. Diff Reading Log:
        if (before.readingLog != after.readingLog) {
            val beforeLogById = before.readingLog.mapIndexed { idx, e -> e.id to (idx to e) }.toMap()
            val afterLogIds = after.readingLog.mapTo(HashSet()) { it.id }
            for (oldId in beforeLogById.keys) {
                if (oldId !in afterLogIds) {
                    listsDao.deleteLogEntryById(oldId)
                }
            }
            after.readingLog.forEachIndexed { idx, entry ->
                val prevPair = beforeLogById[entry.id]
                val nextEntity = entry.toEntity(manualOrder = idx)
                val prevEntity = prevPair?.let { (prevIdx, prevEntry) -> prevEntry.toEntity(manualOrder = prevIdx) }
                if (prevEntity == null || nextEntity != prevEntity) {
                    val ftsChanged = prevPair == null ||
                        prevPair.second.title != entry.title ||
                        prevPair.second.author != entry.author ||
                        prevPair.second.series != entry.series ||
                        prevPair.second.review != entry.review
                    listsDao.saveLogEntry(nextEntity, syncFts = ftsChanged)
                }
            }
        }
    }

    private fun buildShelfAssignments(shelves: List<Shelf>): Map<String, Pair<String, Int>> {
        if (shelves.isEmpty()) return emptyMap()
        val map = HashMap<String, Pair<String, Int>>()
        for (shelf in shelves) {
            shelf.bookIds.forEachIndexed { pos, bookId ->
                map.putIfAbsent(bookId, shelf.id to pos)
            }
        }
        return map
    }

    internal open fun displayNameFor(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else runCatching {
        context?.contentResolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
