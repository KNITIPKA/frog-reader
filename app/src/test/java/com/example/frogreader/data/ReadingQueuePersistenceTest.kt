package com.example.frogreader.data

import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadingQueuePersistenceTest {
    private val directory = File("build/tmp/test_files")
    @Before fun setup() { directory.mkdirs(); StoreFixture.clear(directory) }
    @After fun cleanup() { StoreFixture.clear(directory) }

    @Test fun `create edit and reorder survive restart without creating library books`() = runTest {
        val repo = BookRepository()
        repo.saveReadingQueueEntry(null, "  Книга 🐸  ", "  Автор  ", "  Порада друга\nНа осінь  ")
        repo.saveReadingQueueEntry(null, "Second", "", "")
        val (a, b) = repo.readingQueue.value
        repo.saveReadingQueueEntry(a.id, "New title", "Author", "New note")
        repo.reorderReadingQueue(listOf(b.id, a.id))
        val reopened = BookRepository()
        assertEquals(listOf(b.id, a.id), reopened.readingQueue.value.map { it.id })
        assertEquals("New note", reopened.readingQueue.value.last().note)
        assertEquals("New title", reopened.readingQueue.value.last().title)
        assertEquals("Порада друга\nНа осінь", a.note)
        assertTrue(reopened.books.value.isEmpty())
        assertTrue(reopened.readingQueue.value.all { it.updatedAtMillis > 0 })
        assertTrue(StoreFixture.booksOnDisk(directory).isEmpty())
    }

    @Test fun `other library mutations preserve reading intentions and their order`() = runTest {
        val book = Book("book", "Imported", format = BookFormat.EPUB, addedAtMillis = 1)
        StoreFixture.seed(directory, listOf(book))
        val repo = BookRepository()
        repo.saveReadingQueueEntry(null, "Later", "", "Keep this")
        val before = repo.readingQueue.value
        repo.setRating(book.id, 4)
        repo.addQuote(book.id, Quote("quote", "text", 0, 1))
        repo.deleteBook(book.id)
        assertEquals(before, BookRepository().readingQueue.value)
    }

    @Test fun `stale reorder neither deletes new entries nor resurrects removed ones`() = runTest {
        val repo = BookRepository()
        for (title in listOf("A", "B", "C")) repo.saveReadingQueueEntry(null, title, "", "")
        val (a, b, c) = repo.readingQueue.value
        repo.deleteReadingQueueEntry(b.id)
        repo.saveReadingQueueEntry(null, "D", "", "")
        repo.reorderReadingQueue(listOf(c.id, b.id, c.id, a.id, "missing"))
        assertEquals(listOf("C", "A", "D"), BookRepository().readingQueue.value.map { it.title })
        assertNull(DatabaseManager.getDatabase(directory).readingListsDao().getQueueEntryById(b.id))
        try {
            repo.saveReadingQueueEntry(b.id, "Revive", "", "")
            fail("Editing a missing entry must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `blank titles and excessive fields are rejected without altering data`() = runTest {
        val repo = BookRepository()
        repo.saveReadingQueueEntry(null, "Valid", "", "")
        val before = repo.readingQueue.value
        for ((title, author, note) in listOf(
            Triple("  ", "", ""), Triple("x".repeat(201), "", ""),
            Triple("Title", "a".repeat(161), ""), Triple("Title", "", "n".repeat(101)),
        )) {
            try { repo.saveReadingQueueEntry(null, title, author, note); fail("Must reject invalid input") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, BookRepository().readingQueue.value)
        repo.saveReadingQueueEntry(null, "x".repeat(200), "a".repeat(160), "n".repeat(100))
        assertEquals(2, repo.readingQueue.value.size)
    }

    @Test fun `legacy long notes survive metadata edits and require shortening only when changed`() = runTest {
        val repo = BookRepository()
        val legacy = ReadingQueueEntry("legacy", "Old title", note = "A".repeat(500))
        repo.restoreReadingQueueEntry(legacy, 0)
        repo.saveReadingQueueEntry(legacy.id, "New title", "New author", legacy.note)
        assertEquals(legacy.note, BookRepository().readingQueue.value.single().note)
        try {
            repo.saveReadingQueueEntry(legacy.id, "New title", "New author", "B".repeat(101))
            fail("Changed notes must respect the new limit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(legacy.note, BookRepository().readingQueue.value.single().note)
        repo.saveReadingQueueEntry(legacy.id, "New title", "New author", "Shorter note")
        assertEquals("Shorter note", BookRepository().readingQueue.value.single().note)
    }

    @Test fun `failed write does not publish a new entry or new order`() = runTest {
        val repo = BookRepository()
        repo.saveReadingQueueEntry(null, "A", "", "")
        repo.saveReadingQueueEntry(null, "B", "", "")
        val before = repo.readingQueue.value
        StoreFixture.blockWrites(directory)
        try {
            try { repo.reorderReadingQueue(before.reversed().map { it.id }); fail("Must fail") }
            catch (_: IOException) { }
            assertEquals(before, repo.readingQueue.value)
            try { repo.saveReadingQueueEntry(null, "C", "", ""); fail("Must fail") }
            catch (_: IOException) { }
            assertEquals(before, repo.readingQueue.value)
            assertEquals(before, BookRepository().readingQueue.value)
        } finally { StoreFixture.unblockWrites(directory) }
    }

    @Test fun `corrupt database cannot be overwritten by reading queue or exported as empty`() = runTest {
        StoreFixture.corruptDatabase(directory, ">>> CORRUPTED SQLITE <<<")
        val repo = BookRepository()
        try { repo.saveReadingQueueEntry(null, "A", "", ""); fail("Must protect corrupt data") }
        catch (_: LibraryIndexCorruptedException) { }
        try {
            BackupArchive.write(ByteArrayOutputStream(), directory, BackupSettings(), BackupMode.DATA, "test", 1)
            fail("Must not export empty data")
        } catch (_: LibraryIndexCorruptedException) { }
        assertEquals(">>> CORRUPTED SQLITE <<<", StoreFixture.databaseFile(directory).readText())
    }

    @Test fun `data and full archives restore every field and priority through repository`() = runTest {
        val repo = BookRepository()
        repo.saveReadingQueueEntry(null, "Книга", "Автор", "Порада 🐸\nЗ бібліотеки")
        // A note from the previous 500-character editor must also survive new backups.
        repo.restoreReadingQueueEntry(ReadingQueueEntry("legacy", "Read first", note = "x".repeat(500)), 1)
        repo.reorderReadingQueue(repo.readingQueue.value.reversed().map { it.id })
        val expected = repo.readingQueue.value
        for (mode in BackupMode.entries) {
            val out = ByteArrayOutputStream()
            BackupArchive.write(out, directory, BackupSettings(), mode, "test", 1)
            repo.deleteReadingQueueEntry(expected.first().id)
            val restored = BackupArchive.restore(out.toByteArray().inputStream(), directory, null, null, null)
            assertEquals(expected.size, restored.manifest.readingQueueCount)
            repo.reloadAndSweepAfterRestore()
            assertEquals(expected.map { it.copy(updatedAtMillis = 0) },
                BookRepository().readingQueue.value.map { it.copy(updatedAtMillis = 0) })
        }
    }

    @Test fun `empty database starts with empty reading queue and replaceAll clears queue`() = runTest {
        val repo = BookRepository()
        assertTrue(repo.readingQueue.value.isEmpty())
        repo.saveReadingQueueEntry(null, "Current intention", "", "")
        assertEquals(1, repo.readingQueue.value.size)
        repo.replaceAll(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(BookRepository().readingQueue.value.isEmpty())
    }
}
