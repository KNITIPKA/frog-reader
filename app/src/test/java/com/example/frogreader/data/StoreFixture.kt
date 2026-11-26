package com.example.frogreader.data

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.example.frogreader.data.db.BookEntity
import com.example.frogreader.data.db.BookProgressEntity
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.assembleBooksAndShelves
import com.example.frogreader.data.db.toBookEntity
import com.example.frogreader.data.db.toDomain
import com.example.frogreader.data.db.toEntity
import com.example.frogreader.data.db.toProgressEntity
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.sortTs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Seeds and inspects the SQLite database (`databases/frogreader.db`) directly via Room 3 DAOs,
 * plus deterministic fault-injection helpers (`corruptDatabase`, `blockWrites`, `unblockWrites`).
 */
object StoreFixture {

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val writeGuardedTables = listOf(
        "shelves",
        "books",
        "book_progress",
        "quotes",
        "bookmarks",
        "reading_queue",
        "reading_log",
        "daily_reading_stats",
        "reading_stats_meta",
    )

    fun databaseFile(dir: File): File = DatabaseManager.databaseFileFor(dir)

    fun files(dir: File): List<File> {
        val db = databaseFile(dir)
        return listOf(
            db,
            File("${db.absolutePath}-wal"),
            File("${db.absolutePath}-shm"),
            File("${db.absolutePath}-journal"),
        )
    }

    /** Populates a clean SQLite database in [dir] with the given domain state. */
    fun seed(
        dir: File,
        books: List<Book>,
        shelves: List<Shelf> = emptyList(),
        readingQueue: List<ReadingQueueEntry> = emptyList(),
        readingLog: List<ReadingLogEntry> = emptyList(),
    ) = runBlocking {
        dir.mkdirs()
        DatabaseManager.closeAndDeleteForFilesRoot(dir)
        val db = DatabaseManager.getDatabase(dir)
        val now = System.currentTimeMillis()
        val shelfAssign = HashMap<String, Pair<String, Int>>()
        for (shelf in shelves) {
            shelf.bookIds.forEachIndexed { idx, bookId ->
                shelfAssign.putIfAbsent(bookId, shelf.id to idx)
            }
        }
        val libraryBookIds = books.mapTo(HashSet()) { it.id }

        db.withWriteTransaction {
            val shelfDao = db.shelfDao()
            val bookDao = db.bookDao()
            val passagesDao = db.passagesDao()
            val listsDao = db.readingListsDao()

            for (shelf in shelves) {
                shelfDao.saveShelf(shelf.toEntity())
            }
            for (book in books) {
                val assign = shelfAssign[book.id]
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
                    passagesDao.saveQuote(quote.copy(bookId = book.id).toEntity(bookId = book.id))
                }
                for (bookmark in book.bookmarks) {
                    passagesDao.saveBookmark(bookmark.copy(bookId = book.id).toEntity(bookId = book.id))
                }
            }
            readingQueue.forEachIndexed { idx, entry ->
                listsDao.saveQueueEntry(
                    entry.toEntity(priorityOrder = idx, libraryBookIds = libraryBookIds),
                    syncFts = true,
                )
            }
            readingLog.forEachIndexed { idx, entry ->
                listsDao.saveLogEntry(entry.toEntity(manualOrder = idx), syncFts = true)
            }
        }
    }

    /** Reassembles all books directly from SQLite on disk, in library sort order. */
    fun booksOnDisk(dir: File): List<Book> = runBlocking {
        val db = DatabaseManager.getDatabase(dir)
        db.withReadTransaction {
            val (books, _) = assembleBooksAndShelves(
                bookEntities = db.bookDao().getAllBooks(),
                progressEntities = db.bookDao().getAllProgress(),
                quoteEntities = db.passagesDao().getAllQuotes(),
                bookmarkEntities = db.passagesDao().getAllBookmarks(),
                shelfEntities = db.shelfDao().getAllShelves(),
            )
            books.sortedByDescending { it.sortTs }
        }
    }

    fun shelvesOnDisk(dir: File): List<Shelf> = runBlocking {
        val db = DatabaseManager.getDatabase(dir)
        db.withReadTransaction {
            val (_, shelves) = assembleBooksAndShelves(
                bookEntities = db.bookDao().getAllBooks(),
                progressEntities = db.bookDao().getAllProgress(),
                quoteEntities = db.passagesDao().getAllQuotes(),
                bookmarkEntities = db.passagesDao().getAllBookmarks(),
                shelfEntities = db.shelfDao().getAllShelves(),
            )
            shelves
        }
    }

    fun bookEntityOnDisk(dir: File, bookId: String): BookEntity? = runBlocking {
        DatabaseManager.getDatabase(dir).bookDao().getBookById(bookId)
    }

    fun progressEntityOnDisk(dir: File, bookId: String): BookProgressEntity? = runBlocking {
        DatabaseManager.getDatabase(dir).bookDao().getProgressByBookId(bookId)
    }

    fun quotesOnDisk(dir: File, bookId: String): List<Quote> = runBlocking {
        DatabaseManager.getDatabase(dir).passagesDao().getQuotesForBook(bookId).map { it.toDomain() }
    }

    fun bookmarksOnDisk(dir: File, bookId: String): List<Bookmark> = runBlocking {
        DatabaseManager.getDatabase(dir).passagesDao().getBookmarksForBook(bookId).map { it.toDomain() }
    }

    /** Closes any open connection and writes non-SQLite bytes to `databases/frogreader.db`. */
    fun corruptDatabase(dir: File, content: String = ">>> CORRUPTED SQLITE HEADER <<<") {
        DatabaseManager.closeAndDeleteForFilesRoot(dir)
        val dbFile = databaseFile(dir)
        dbFile.parentFile?.mkdirs()
        dbFile.writeText(content)
    }

    /** Installs SQLite `BEFORE INSERT/UPDATE/DELETE` triggers so writes deterministically fail & roll back. */
    fun blockWrites(dir: File) = runBlocking {
        val db = DatabaseManager.getDatabase(dir)
        db.useWriterConnection { conn ->
            for (table in writeGuardedTables) {
                for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                    conn.executeSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS __test_block_${table}_${op.lowercase()}
                        BEFORE $op ON $table
                        BEGIN
                            SELECT RAISE(ABORT, 'Simulated disk/SQLite write failure');
                        END
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    fun unblockWrites(dir: File) = runBlocking {
        val db = DatabaseManager.getDatabase(dir)
        db.useWriterConnection { conn ->
            for (table in writeGuardedTables) {
                for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                    conn.executeSQL("DROP TRIGGER IF EXISTS __test_block_${table}_${op.lowercase()}")
                }
            }
        }
    }

    fun clear(dir: File) {
        DatabaseManager.closeAndDeleteForFilesRoot(dir)
        for (legacy in listOf("library.json", "userdata.json", "progress.json", "reading_stats.json")) {
            File(dir, legacy).delete()
            File(dir, "$legacy.bak").delete()
            File(dir, "$legacy.tmp").deleteRecursively()
        }
    }
}
