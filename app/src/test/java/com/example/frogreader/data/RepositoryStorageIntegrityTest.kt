package com.example.frogreader.data

import android.content.Context
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.reader.ReaderReadingWriteQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Exercises repositories against native bundled SQLite, including fault injection and hot swap. */
class RepositoryStorageIntegrityTest {
    @get:Rule val directory = TemporaryFolder()

    private val root: File get() = directory.root
    private val day = LocalDate.of(2026, 10, 29)

    private fun context(): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(root)
    }

    private fun book(id: String, addedAt: Long = 1L) = Book(
        id = id,
        title = "Book $id",
        author = "Author",
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        addedAtMillis = addedAt,
    )

    @After
    fun closeDatabase() {
        DatabaseManager.closeForFilesRoot(root)
    }

    @Test
    fun `first statistics write publishes its seconds exactly once`() = runTest {
        val stats = StatsRepository(filesRootOverride = root)
        stats.addSeconds(day, 9, lastReadAtMillis = 1_000L)
        assertEquals(9L, stats.stats.value.secondsOn(day))
        assertEquals(stats.stats.value, StatsRepository(filesRootOverride = root).stats.value)
    }

    @Test
    fun `statistics increment refreshes an older repository snapshot`() = runTest {
        val first = StatsRepository(filesRootOverride = root)
        val second = StatsRepository(filesRootOverride = root)
        assertEquals(0L, first.stats.value.secondsOn(day))
        assertEquals(0L, second.stats.value.secondsOn(day))
        first.addSeconds(day, 10, lastReadAtMillis = 2_000L)
        second.addSeconds(day, 20, lastReadAtMillis = 1_000L)
        assertEquals(30L, second.stats.value.secondsOn(day))
        assertEquals(2_000L, second.stats.value.lastReadAtMillis)
        first.addSeconds(day, 5)
        assertEquals(35L, first.stats.value.secondsOn(day))
        assertEquals(first.stats.value, StatsRepository(filesRootOverride = root).stats.value)
    }

    @Test
    fun `failed statistics transaction leaves the visible state unchanged`() = runTest {
        val stats = StatsRepository(filesRootOverride = root)
        stats.addSeconds(day, 9, lastReadAtMillis = 1_000L)
        val before = stats.stats.value
        StoreFixture.blockWrites(root)
        try {
            try {
                stats.addSeconds(day, 5, lastReadAtMillis = 2_000L)
                fail("SQLite trigger should reject this write")
            } catch (_: Exception) {
                assertEquals(before, stats.stats.value)
                assertEquals(before, StatsRepository(filesRootOverride = root).stats.value)
            }
        } finally {
            StoreFixture.unblockWrites(root)
        }
    }

    @Test
    fun `corrupt statistics database is reported rather than replaced by empty history`() {
        StoreFixture.corruptDatabase(root)
        try {
            StatsRepository(filesRootOverride = root).stats.value
            fail("A corrupt database must be reported")
        } catch (_: LibraryIndexCorruptedException) {
            assertTrue(StoreFixture.databaseFile(root).readText().contains("CORRUPTED"))
        }
    }

    @Test
    fun `shelf deletion rolls back all books passages queue links and assets if a later delete fails`() = runTest {
        val first = book("a", 3L).copy(
            quotes = listOf(Quote("quote-a", "Atomic quote", 0, 1L)),
            bookmarks = listOf(Bookmark("bookmark-a", 1, 0, "Atomic bookmark", 1L)),
        )
        val second = book("b", 2L)
        val outside = book("c")
        val shelf = Shelf("shelf", "Shelf", listOf(first.id, second.id), createdAtMillis = 1L)
        val queue = ReadingQueueEntry("queue", "Queued", attachment = first)
        StoreFixture.seed(root, listOf(first, second, outside), listOf(shelf), listOf(queue))
        val assets = listOf(first, second).map { value ->
            File(root, "books/${value.fileName}").apply { parentFile!!.mkdirs(); writeText(value.id) }
        }
        val repository = BookRepository(context())
        val beforeBooks = repository.books.value
        val beforeShelves = repository.shelves.value
        val beforeQueue = repository.readingQueue.value
        val db = DatabaseManager.getDatabase(root)
        db.useWriterConnection {
            it.executeSQL("""
                CREATE TRIGGER fail_second_book_delete BEFORE DELETE ON books
                WHEN OLD.id = 'b'
                BEGIN SELECT RAISE(ABORT, 'Injected failure on the second book'); END
            """.trimIndent())
        }
        try {
            repository.deleteShelfWithBooks(shelf.id)
            fail("The second book deletion should fail")
        } catch (_: IOException) {
            assertEquals(beforeBooks, repository.books.value)
            assertEquals(beforeShelves, repository.shelves.value)
            assertEquals(beforeQueue, repository.readingQueue.value)
            assertEquals(beforeBooks, StoreFixture.booksOnDisk(root))
            assertEquals(beforeShelves, StoreFixture.shelvesOnDisk(root))
            assertEquals(first.id, db.readingListsDao().getAllQueueEntries().single().linkedBookId)
            assertTrue(assets.all { it.exists() })
        } finally {
            db.useWriterConnection { it.executeSQL("DROP TRIGGER fail_second_book_delete") }
        }
        repository.deleteShelfWithBooks(shelf.id)
        assertEquals(listOf(outside.id), repository.books.value.map { it.id })
        assertTrue(repository.shelves.value.isEmpty())
        assertTrue(StoreFixture.quotesOnDisk(root, first.id).isEmpty())
        assertTrue(StoreFixture.bookmarksOnDisk(root, first.id).isEmpty())
        assertEquals(first.fileName, repository.readingQueue.value.single().attachment?.fileName)
        assertTrue(assets[0].exists()) // The queue still owns this file.
        assertFalse(assets[1].exists())
    }

    @Test
    fun `fast write persists pending reading reconciliation before publishing its baseline`() = runTest {
        StoreFixture.seed(root, listOf(book("reading").copy(status = ReadingStatus.READING, startedAtMillis = 1L)))
        StoreFixture.blockWrites(root)
        val books = BookRepository(context())
        val readingId = try {
            books.books.value.single().activeReadingLogId.also { assertTrue(it != null) }
        } finally {
            StoreFixture.unblockWrites(root)
        }
        assertTrue(DatabaseManager.getDatabase(root).readingListsDao().getAllLogEntries().isEmpty())
        books.saveProgress("reading", ReadingProgress(fraction = 0.25f))
        val restarted = BookRepository(context())
        assertEquals(readingId, restarted.books.value.single().activeReadingLogId)
        assertEquals(readingId, restarted.readingLog.value.single().id)
        assertEquals(readingId, StoreFixture.bookEntityOnDisk(root, "reading")!!.activeReadingLogId)
    }

    @Test
    fun `reading interval rolls back book log and daily counters together and retries once`() = runTest {
        StoreFixture.seed(root, listOf(book("reading")))
        val books = BookRepository(context())
        val stats = StatsRepository(filesRootOverride = root)
        books.markStarted("reading")
        stats.addSeconds(day, 7L, lastReadAtMillis = 100L)
        val beforeBook = books.bookById("reading")!!
        val beforeLog = books.readingLog.value
        val beforeStats = stats.stats.value
        val db = DatabaseManager.getDatabase(root)
        db.useWriterConnection {
            it.executeSQL("""
                CREATE TRIGGER fail_daily_update BEFORE UPDATE ON daily_reading_stats
                BEGIN SELECT RAISE(ABORT, 'Injected failure after book and log increments'); END
            """.trimIndent())
        }
        var finished = false
        val queue = ReaderReadingWriteQueue()
        queue.add {
            books.recordReadingTimeForReading("reading", 30L, beforeBook.activeReadingLogId, day, 1_000L, stats)
        }
        queue.add { finished = true }
        try {
            queue.drain()
            fail("The daily counter trigger should abort the shared transaction")
        } catch (_: IOException) {
            assertEquals(beforeBook, books.bookById("reading"))
            assertEquals(beforeLog, books.readingLog.value)
            assertEquals(beforeStats, stats.stats.value)
            assertEquals(beforeBook, StoreFixture.booksOnDisk(root).single())
            assertEquals(beforeStats, StatsRepository(filesRootOverride = root).stats.value)
            assertFalse(finished)
        } finally {
            db.useWriterConnection { it.executeSQL("DROP TRIGGER fail_daily_update") }
        }
        queue.drain()
        queue.drain()
        assertEquals(30L, books.bookById("reading")!!.readingSeconds)
        assertEquals(30L, books.readingLog.value.single().readingSeconds)
        assertEquals(37L, stats.stats.value.secondsOn(day))
        assertEquals(1_000L, stats.stats.value.lastReadAtMillis)
        assertEquals(books.bookById("reading"), StoreFixture.booksOnDisk(root).single())
        assertEquals(stats.stats.value, StatsRepository(filesRootOverride = root).stats.value)
        assertTrue(finished)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `full backup keeps a snapshotted asset while book deletion waits`() = runTest {
        val value = book("exported")
        StoreFixture.seed(root, listOf(value))
        val asset = File(root, "books/${value.fileName}").apply {
            parentFile!!.mkdirs()
            writeText("irreplaceable book bytes")
        }
        val books = BookRepository(context())
        assertEquals(1, books.books.value.size)
        val backupEntered = CompletableDeferred<Unit>()
        val beginExport = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val export = async(Dispatchers.IO) {
            books.withBackupLock {
                backupEntered.complete(Unit)
                beginExport.await()
                ByteArrayOutputStream().also { out ->
                    BackupArchive.write(out, root, BackupSettings(), BackupMode.FULL, "test", 1L)
                }.toByteArray()
            }
        }
        backupEntered.await()
        val deletion = async(Dispatchers.IO) {
            deleteStarted.complete(Unit)
            books.deleteBook(value.id)
        }
        deleteStarted.await()
        assertFalse(deletion.isCompleted)
        beginExport.complete(Unit)
        val bytes = export.await()
        deletion.await()
        assertFalse(asset.exists())
        assertTrue(books.books.value.isEmpty())
        val stagedRoot = File(root, "archive-check")
        val staged = BackupArchive.read(bytes.inputStream(), stagedRoot)
        assertEquals(1, staged.manifest.bookCount)
        assertEquals("irreplaceable book bytes", File(stagedRoot, "books/${value.fileName}").readText())
    }

    @Test
    fun `writers waiting for restore use restored snapshots before applying their mutations`() = runTest {
        StoreFixture.seed(root, listOf(book("restored")))
        StatsRepository(filesRootOverride = root).replaceAll(ReadingStats(mapOf(day.toString() to 100L)))
        val staged = File(root, "restore.db")
        DatabaseManager.vacuumInto(root, staged)
        StoreFixture.seed(root, listOf(book("old")))
        val books = BookRepository(context())
        val stats = StatsRepository(filesRootOverride = root)
        stats.replaceAll(ReadingStats(mapOf(day.toString() to 20L)))
        assertEquals(listOf("old"), books.books.value.map { it.id })
        val restoreEntered = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val restore = async(Dispatchers.IO) {
            books.withRestoreLock {
                stats.withRestoreLock {
                    restoreEntered.complete(Unit)
                    releaseRestore.await()
                    DatabaseManager.replaceDatabaseFromStaged(root, staged)
                }
            }
        }
        restoreEntered.await()
        val added = async(Dispatchers.IO) { books.addWishlistBook("Queued write") }
        val increment = async(Dispatchers.IO) { stats.addSeconds(day, 5L) }
        releaseRestore.complete(Unit)
        restore.await()
        val newBook = added.await()
        increment.await()
        assertEquals(setOf("restored", newBook.id), books.books.value.map { it.id }.toSet())
        assertEquals(105L, stats.stats.value.secondsOn(day))
        assertEquals(books.books.value, StoreFixture.booksOnDisk(root))
        assertEquals(stats.stats.value, StatsRepository(filesRootOverride = root).stats.value)
    }
}
