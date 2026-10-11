package com.example.frogreader.data

import android.content.Context
import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.backup.BackupArchive.resolveDocument
import com.example.frogreader.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ReadingLogProgressTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setup() {
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        StoreFixture.seed(temporary.root, listOf(Book("book", "A book", format = BookFormat.EPUB,
            addedAtMillis = 1, startedAtMillis = 100, status = ReadingStatus.READING,
            progress = ReadingProgress(fraction = .31f))))
    }

    private fun repository() = BookRepository(context)

    @Test fun `page progress stays in progress store and does not rewrite history`() = runTest {
        val repo = repository()
        repo.markStarted("book")
        val before = File(temporary.root, "userdata.json").readBytes()
        val entry = repo.readingLog.value.single()
        repo.saveProgressForReading("book", ReadingProgress(fraction = .72f), entry.id)
        assertArrayEquals(before, File(temporary.root, "userdata.json").readBytes())
        assertEquals(entry, repo.readingLog.value.single())
        assertEquals(.72f, repository().bookById("book")!!.progress.fraction)
    }

    @Test fun `abandoning from a stale editor captures current progress and keeps it after late saves`() = runTest {
        val repo = repository()
        val draft = repo.readingLog.value.single()
        repo.saveProgressForReading("book", ReadingProgress(fraction = .72f), draft.id)
        repo.saveReadingLogEntry(draft.copy(status = ReadingLogStatus.ABANDONED))
        assertEquals(.72f, repository().readingLog.value.single().progressFraction)
        repo.saveProgressForReading("book", ReadingProgress(fraction = .95f), draft.id)
        val abandoned = repo.readingLog.value.single()
        repo.saveReadingLogEntry(abandoned.copy(review = "A later edit", progressFraction = .1f))
        assertEquals(.72f, repository().readingLog.value.single().progressFraction)
    }

    @Test fun `resuming and abandoning again records the new stopping point`() = runTest {
        val repo = repository()
        repo.setStatus("book", ReadingStatus.ABANDONED)
        val id = repo.readingLog.value.single().id
        assertEquals(.31f, repo.readingLog.value.single().progressFraction)
        repo.resumeReading("book")
        repo.saveProgressForReading("book", ReadingProgress(fraction = .83f), id)
        repo.setStatus("book", ReadingStatus.ABANDONED)
        assertEquals(.83f, repository().readingLog.value.single().progressFraction)
    }

    @Test fun `rereading preserves abandoned percentage and resets only new reading progress`() = runTest {
        val repo = repository()
        repo.saveProgress("book", ReadingProgress(fraction = .64f))
        repo.setStatus("book", ReadingStatus.ABANDONED)
        val abandoned = repo.readingLog.value.single()
        repo.beginReadingAgain("book")
        repo.saveProgress("book", ReadingProgress(fraction = .12f))
        val reopened = repository()
        assertEquals(abandoned, reopened.readingLog.value.first())
        assertEquals(.64f, reopened.readingLog.value.first().progressFraction)
        assertEquals(.12f, reopened.bookById("book")!!.progress.fraction)
    }

    @Test fun `deleting the library file captures current percentage for remaining history`() = runTest {
        val repo = repository()
        repo.saveProgress("book", ReadingProgress(fraction = .83f))
        repo.deleteBook("book")
        val reopened = repository()
        assertTrue(reopened.books.value.isEmpty())
        assertEquals(ReadingLogStatus.IN_PROGRESS, reopened.readingLog.value.single().status)
        assertEquals(.83f, reopened.readingLog.value.single().progressFraction)
    }

    @Test fun `undo after opening a new reading retains removed reading latest percentage`() = runTest {
        val repo = repository()
        repo.markStarted("book")
        val oldId = repo.readingLog.value.single().id
        assertEquals(.31f, repo.readingLog.value.single().progressFraction)
        repo.saveProgressForReading("book", ReadingProgress(fraction = .83f), oldId)
        val deletion = repo.deleteReadingLogEntry(oldId)!!
        assertEquals(.83f, deletion.entry.progressFraction)
        repo.markStarted("book")
        val newId = repo.bookById("book")!!.activeReadingLogId
        repo.saveProgressForReading("book", ReadingProgress(fraction = .12f), newId)
        repo.restoreReadingLogEntry(deletion.entry, deletion.index, deletion.previousReading)
        val reopened = repository()
        assertNotEquals(oldId, newId)
        assertEquals(.83f, reopened.readingLog.value.first { it.id == oldId }.progressFraction)
        assertEquals(newId, reopened.bookById("book")!!.activeReadingLogId)
        assertEquals(.12f, reopened.bookById("book")!!.progress.fraction)
    }

    @Test fun `removed abandoned reading retains frozen stopping percentage despite later position saves`() = runTest {
        val repo = repository()
        repo.setStatus("book", ReadingStatus.ABANDONED)
        val oldId = repo.readingLog.value.single().id
        repo.saveProgressForReading("book", ReadingProgress(fraction = .83f), oldId)
        val deletion = repo.deleteReadingLogEntry(oldId)!!
        assertEquals(.31f, deletion.entry.progressFraction)
        assertEquals(.83f, deletion.previousReading!!.progress.fraction)
        repo.markStarted("book")
        repo.restoreReadingLogEntry(deletion.entry, deletion.index, deletion.previousReading)
        assertEquals(.31f, repository().readingLog.value.first { it.id == oldId }.progressFraction)
    }

    @Test fun `legacy active abandoned records backfill from their own library position`() = runTest {
        val legacy = ReadingLogEntry("legacy", "A book", source = ReadingLogSource.FROGREADER,
            status = ReadingLogStatus.ABANDONED, linkedBookId = "book")
        val oldBook = repository().bookById("book")!!.copy(status = ReadingStatus.ABANDONED, activeReadingLogId = legacy.id)
        StoreFixture.seed(temporary.root, listOf(oldBook))
        AtomicJsonFile(File(temporary.root, "userdata.json"), StoreFixture.json, UserDataStore.serializer()).write(
            StoreFixture.userDataOf(listOf(oldBook)).copy(readingLog = listOf(legacy)))
        assertEquals(.31f, repository().readingLog.value.single().progressFraction)
        assertNull(StoreFixture.json.decodeFromString<ReadingLogEntry>("""{"id":"old","title":"Old"}""").progressFraction)
    }

    @Test fun `failed abandonment does not publish a stopping point`() = runTest {
        val repo = repository()
        repo.markStarted("book")
        val original = repo.readingLog.value.single()
        repo.saveProgress("book", ReadingProgress(fraction = .81f))
        val temp = File(temporary.root, "userdata.json.tmp").apply { mkdirs() }
        File(temp, "blocked").writeText("blocked")
        try {
            try { repo.setStatus("book", ReadingStatus.ABANDONED); fail("Must fail") }
            catch (_: IOException) { }
            assertEquals(original, repo.readingLog.value.single())
            assertEquals(ReadingStatus.READING, repo.bookById("book")!!.status)
        } finally { temp.deleteRecursively() }
    }

    @Test fun `data and full backups retain stopped percentage after deleting the ebook`() = runTest {
        val repo = repository()
        repo.saveProgress("book", ReadingProgress(fraction = .64f))
        repo.setStatus("book", ReadingStatus.ABANDONED)
        repo.deleteBook("book")
        for (mode in BackupMode.entries) {
            val out = ByteArrayOutputStream()
            BackupArchive.write(out, repo.backupDocument(), ReadingStats(), BackupSettings(), mode, "test", 1)
            val booksDir = File(temporary.root, "books")
            val coversDir = File(temporary.root, "covers")
            val contents = BackupArchive.read(out.toByteArray().inputStream(), booksDir, coversDir)
            val restored = contents.resolveDocument(booksDir, coversDir)
            assertEquals(BackupManifest.FORMAT_VERSION, contents.manifest.formatVersion)
            repo.replaceAll(restored.books, restored.shelves, restored.readingQueue, restored.readingLog)
            assertEquals(.64f, repository().readingLog.value.single().progressFraction)
        }
    }
}
