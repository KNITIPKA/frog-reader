package com.example.frogreader.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseBackupManager
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingQueueEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Restore failures are injected around real native SQLite and real asset files. */
class BackupRestoreIntegrityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val roots = mutableListOf<File>()

    private fun newRoot(): File = temporary.newFolder().also { roots += it }

    @After
    fun closeDatabases() {
        roots.forEach(DatabaseManager::closeForFilesRoot)
    }

    private fun book(id: String) = Book(
        id = id, title = "Book $id", format = BookFormat.EPUB, addedAtMillis = 1L,
        fileName = "same.epub", coverFileName = "same.img",
    )

    private fun asset(root: File, folder: String, name: String, text: String): File =
        File(File(root, folder).apply { mkdirs() }, name).apply { writeText(text) }

    private suspend fun <T> inspect(file: File, block: suspend (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(file.absolutePath).use { block(it) }

    private suspend fun snapshot(root: File): File = temporary.newFile().also {
        DatabaseBackupManager.exportSnapshot(root, it)
    }

    private suspend fun collidingArchive(): ByteArray {
        val source = newRoot()
        val sourceFont = asset(source, "fonts", "same.ttf", "restored font")
        val reader = ReaderSettings(
            font = ReaderFont.CUSTOM,
            customFontPath = sourceFont.absolutePath,
            customFontFamilyPaths = listOf(sourceFont.absolutePath),
        )
        StoreFixture.seed(source, listOf(book("same").copy(readerSettings = reader)))
        asset(source, "books", "same.epub", "restored book")
        asset(source, "covers", "same.img", "restored cover")
        val output = ByteArrayOutputStream()
        BackupArchive.write(
            out = output, filesRoot = source, settings = BackupSettings(reader = reader),
            mode = BackupMode.FULL, appVersion = "test", createdAtMillis = 1L,
        )
        return output.toByteArray()
    }

    private fun seedCollidingTarget(): File = newRoot().also { target ->
        StoreFixture.seed(target, listOf(book("old")))
        asset(target, "books", "same.epub", "original book")
        asset(target, "covers", "same.img", "original cover")
        asset(target, "fonts", "same.ttf", "original font")
    }

    private fun assertOldAssetsIntact(target: File) {
        assertEquals("original book", File(target, "books/same.epub").readText())
        assertEquals("original cover", File(target, "covers/same.img").readText())
        assertEquals("original font", File(target, "fonts/same.ttf").readText())
    }

    @Test
    fun `failed restore never overwrites colliding books covers or fonts and retains old database`() = runBlocking {
        val archive = collidingArchive()
        val target = seedCollidingTarget()
        val failure = runCatching {
            BackupArchive.restore(ByteArrayInputStream(archive), target, beforeCommit = {
                throw IOException("injected settings failure")
            })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertOldAssetsIntact(target)
        assertEquals(listOf("old"), StoreFixture.booksOnDisk(target).map { it.id })
    }

    @Test
    fun `successful restore rebases colliding books covers and global and per-book font paths`() = runBlocking {
        val archive = collidingArchive()
        val target = seedCollidingTarget()
        val restored = BackupArchive.restore(ByteArrayInputStream(archive), target)
        assertOldAssetsIntact(target)
        val loaded = StoreFixture.booksOnDisk(target).single()
        assertEquals("same", loaded.id)
        assertNotEquals("same.epub", loaded.fileName)
        assertNotEquals("same.img", loaded.coverFileName)
        assertEquals("restored book", File(target, "books/${loaded.fileName}").readText())
        assertEquals("restored cover", File(target, "covers/${loaded.coverFileName}").readText())
        val globalPath = restored.settings!!.reader.customFontPath!!
        assertNotEquals(File(target, "fonts/same.ttf").absolutePath, globalPath)
        assertEquals("restored font", File(globalPath).readText())
        assertEquals(globalPath, loaded.readerSettings!!.customFontPath)
        assertEquals(listOf(globalPath), loaded.readerSettings!!.customFontFamilyPaths)
        assertEquals(listOf(globalPath), restored.settings!!.reader.customFontFamilyPaths)
    }

    @Test
    fun `startup recovers checkpointed rollback copy after interrupted replacement`() = runBlocking {
        val source = newRoot()
        StoreFixture.seed(source, listOf(book("replacement")))
        val staged = snapshot(source)
        val target = newRoot()
        StoreFixture.seed(target, listOf(book("original")))
        DatabaseManager.closeForFilesRoot(target)
        val live = DatabaseManager.databaseFileFor(target)
        // Closing the last native connection checkpoints WAL. These renames reproduce the
        // process-death window after activation, before removal of the rollback copy.
        val rollback = File(live.parentFile, "${live.name}.pre_restore_bak")
        Files.move(live.toPath(), rollback.toPath())
        staged.copyTo(live)
        assertEquals(listOf("original"), DatabaseManager.getDatabase(target).bookDao().getAllBookIds())
        assertFalse(rollback.exists())
    }

    @Test
    fun `failed restore to fresh root removes uncommitted database`() = runBlocking {
        val source = newRoot()
        StoreFixture.seed(source, listOf(book("replacement")))
        val staged = snapshot(source)
        val target = newRoot()
        assertFalse(DatabaseManager.databaseFileFor(target).exists())
        assertTrue(runCatching {
            DatabaseManager.replaceDatabaseFromStaged(target, staged) {
                throw IOException("injected post-open failure")
            }
        }.isFailure)
        assertFalse(DatabaseManager.databaseFileFor(target).exists())
    }

    @Test
    fun `matching Room identity cannot conceal a dropped unrelated content table in staged backup`() = runBlocking {
        val source = newRoot()
        StoreFixture.seed(source, listOf(book("original")))
        val staged = snapshot(source)
        inspect(staged) { it.execSQL("DROP TABLE achievement_progress") }
        assertTrue(runCatching { DatabaseBackupManager.validateStagedOrThrow(staged) }.isFailure)
        assertEquals(listOf("original"), StoreFixture.booksOnDisk(source).map { it.id })
    }

    @Test
    fun `staged backup rejects inconsistent FTS entity mapping before touching live database`() = runBlocking {
        val source = newRoot()
        StoreFixture.seed(source, listOf(book("replacement")))
        val staged = snapshot(source)
        inspect(staged) { it.execSQL("UPDATE books_fts SET entity_id = 'other-book'") }
        val target = newRoot()
        StoreFixture.seed(target, listOf(book("original")))
        assertTrue(runCatching { DatabaseManager.replaceDatabaseFromStaged(target, staged) }.isFailure)
        assertEquals(listOf("original"), StoreFixture.booksOnDisk(target).map { it.id })
    }

    @Test
    fun `active external WAL snapshot aborts restore without discarding committed user writes`() = runBlocking {
        val source = newRoot()
        StoreFixture.seed(source, listOf(book("replacement")))
        val staged = snapshot(source)
        val target = newRoot()
        StoreFixture.seed(target, listOf(book("original")))
        val db = DatabaseManager.getDatabase(target)
        val live = DatabaseManager.databaseFileFor(target)
        inspect(live) { reader ->
            reader.execSQL("BEGIN")
            reader.prepare("SELECT count(*) FROM books").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
            }
            // This commit lies after the reader's pinned WAL end mark. A successful TRUNCATE
            // checkpoint is impossible until that transaction releases its snapshot.
            db.bookDao().saveBookEntity(com.example.frogreader.data.db.BookEntity(
                id = "late-write", title = "Committed late write", format = "EPUB",
                status = "NONE", addedAtMillis = 2L,
            ))
            assertTrue(File("${live.absolutePath}-wal").length() > 0L)
            assertTrue(runCatching { DatabaseManager.replaceDatabaseFromStaged(target, staged) }.isFailure)
            assertTrue("Busy checkpoint must not delete the active WAL", File("${live.absolutePath}-wal").length() > 0L)
            reader.execSQL("ROLLBACK")
        }
        assertEquals(setOf("original", "late-write"), DatabaseManager.getDatabase(target).bookDao().getAllBookIds().toSet())
    }

    @Test
    fun `malformed persisted payloads and enums reject restoration without erasing source or target data`() = runBlocking {
        val source = newRoot()
        val original = book("source").copy(
            readerSettings = ReaderSettings(),
            progress = ReadingProgress(documentLocation = DocumentLocation.Pdf(pageIndex = 2)),
            quotes = listOf(Quote(id = "quote", text = "Remember this", chapterIndex = 0, createdAtMillis = 1L, bookId = "source")),
            bookmarks = listOf(Bookmark(
                id = "bookmark", flatIndex = 0, chapterIndex = 0, preview = "Place", createdAtMillis = 1L,
                bookId = "source", documentLocation = DocumentLocation.Pdf(pageIndex = 2),
            )),
        )
        StoreFixture.seed(source, listOf(original), readingQueue = listOf(
            ReadingQueueEntry(id = "private", title = "Private attachment", attachment = book("private")),
        ))
        val target = newRoot()
        StoreFixture.seed(target, listOf(book("retained")))
        for (mutation in listOf(
            "UPDATE books SET reader_settings_json = '{'",
            "UPDATE books SET genres_json = ' ' ",
            "UPDATE books SET format = 'UNKNOWN'",
            "UPDATE books SET status = 'UNKNOWN'",
            "UPDATE reading_queue SET private_attachment_json = '{\"id\":\"lost\"}'",
            "UPDATE book_progress SET document_location_json = '{\"type\":\"unknown\"}'",
            "UPDATE quotes SET pdf_selection_json = '[\"invalid\"]'",
            "UPDATE quotes SET color = 'UNKNOWN'",
            "UPDATE bookmarks SET document_location_json = ' '",
        )) {
            val staged = snapshot(source)
            inspect(staged) { it.execSQL(mutation) }
            assertTrue(mutation, runCatching { DatabaseManager.replaceDatabaseFromStaged(target, staged) }.isFailure)
            assertEquals(listOf("retained"), StoreFixture.booksOnDisk(target).map { it.id })
            assertEquals(original, StoreFixture.booksOnDisk(source).single())
        }
    }
}
