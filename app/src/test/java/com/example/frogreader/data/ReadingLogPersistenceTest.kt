package com.example.frogreader.data

import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.backup.BackupArchive.resolveDocument
import com.example.frogreader.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ReadingLogPersistenceTest {
    private val directory = File("build/tmp/test_files")
    private val cover = File(directory, "covers/reading-log-persistence-test.jpg")
    private fun book() = Book("log-book", "A lifetime book", author = "An Author", series = "Series",
        format = BookFormat.EPUB, addedAtMillis = 1L)
    @Before fun setup() { directory.mkdirs(); StoreFixture.clear(directory); cover.delete() }
    @After fun cleanup() { StoreFixture.clear(directory); cover.delete() }

    @Test fun `manual records preserve partial and unknown dates half stars review and ordering`() = runTest {
        val repo = BookRepository()
        val a = repo.saveReadingLogEntry(ReadingLogEntry("", " Paper ", author = " Author ", series = " Cycle ",
            source = ReadingLogSource.PAPER, startedOn = ReadingLogDate(2007), finishedOn = ReadingLogDate(2007, 5),
            ratingHalfStars = 9, review = "My own review"))
        val b = repo.saveReadingLogEntry(ReadingLogEntry("", "Remembered", status = ReadingLogStatus.ABANDONED))
        repo.reorderReadingLog(listOf(b, a))
        val reopened = BookRepository()
        assertEquals(listOf(b, a), reopened.readingLog.value.map { it.id })
        val paper = reopened.readingLog.value.last()
        assertEquals("Paper", paper.title)
        assertEquals(ReadingLogDate(2007), paper.startedOn)
        assertEquals(ReadingLogDate(2007, 5), paper.finishedOn)
        assertEquals(9, paper.ratingHalfStars)
        assertEquals("My own review", paper.review)
        assertEquals(ReadingLogSource.PAPER, paper.source)
        assertNull(reopened.readingLog.value.first().finishedOn)
        assertTrue(reopened.books.value.isEmpty())
        assertFalse(File(directory, "library.json").exists())
    }

    @Test fun `invalid dates and impossible date ranges do not persist`() = runTest {
        val repo = BookRepository()
        for (date in listOf(ReadingLogDate(2025, 2, 29), ReadingLogDate(2024, null, 1), ReadingLogDate(2024, 13))) {
            try { repo.saveReadingLogEntry(ReadingLogEntry("", "Invalid", startedOn = date)); fail("Invalid date") }
            catch (_: IllegalArgumentException) { }
        }
        try { repo.saveReadingLogEntry(ReadingLogEntry("", "Invalid range", startedOn = ReadingLogDate(2025, 3),
            finishedOn = ReadingLogDate(2025, 2))); fail("Impossible date range") }
        catch (_: IllegalArgumentException) { }
        assertTrue(repo.readingLog.value.isEmpty())
        repo.saveReadingLogEntry(ReadingLogEntry("", "Overlapping partial dates", startedOn = ReadingLogDate(2025, 3, 12),
            finishedOn = ReadingLogDate(2025)))
        assertEquals(1, repo.readingLog.value.size)
    }

    @Test fun `opening and completing create one durable record with actual reading time`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.markStarted("log-book")
        assertEquals(1, repo.readingLog.value.size)
        assertEquals(ReadingLogStatus.IN_PROGRESS, repo.readingLog.value.single().status)
        repo.addReadingSeconds("log-book", 123)
        repo.markFinished("log-book")
        repo.markFinished("log-book")
        val entry = BookRepository().readingLog.value.single()
        assertEquals(ReadingLogStatus.FINISHED, entry.status)
        assertEquals(123L, entry.readingSeconds)
        assertNotNull(entry.startedOn)
        assertNotNull(entry.finishedOn)
        assertNull(entry.ratingHalfStars)
        assertEquals(ReadingLogSource.FROGREADER, entry.source)
        assertEquals("Series", entry.series)
    }

    @Test fun `opening refreshes active log last opened while page turns do not rewrite user data`() = runTest {
        StoreFixture.seed(directory, listOf(book().copy(startedAtMillis = 1, lastOpenedAtMillis = 2)))
        val repo = BookRepository()
        repo.markStarted("log-book")
        assertTrue(repo.readingLog.value.single().lastOpenedAtMillis!! > 2)
        val original = File(directory, "userdata.json").readBytes()
        repo.saveProgress("log-book", ReadingProgress(fraction = .4f))
        assertArrayEquals(original, File(directory, "userdata.json").readBytes())
    }

    @Test fun `logged rereading starts a fresh instance and preserves the old review and time`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.addReadingSeconds("log-book", 55)
        repo.markFinished("log-book")
        repo.setRatingHalfStars("log-book", 9)
        repo.setReview("log-book", "First reading")
        val old = repo.readingLog.value.single()
        val id = repo.beginReadingAgain("log-book", true)
        assertNotEquals(old.id, id)
        assertNull(repo.bookById("log-book")!!.finishedAtMillis)
        assertEquals(0L, repo.bookById("log-book")!!.readingSeconds)
        assertEquals(ReadingProgress(), repo.bookById("log-book")!!.progress)
        repo.addReadingSeconds("log-book", 100)
        repo.markFinished("log-book")
        repo.saveBookReview("log-book", 7, "Second reading")
        assertEquals(old, repo.readingLog.value.first())
        assertEquals(100L, repo.readingLog.value.last().readingSeconds)
        assertEquals(7, repo.readingLog.value.last().ratingHalfStars)
        assertEquals(2, BookRepository().readingLog.value.size)
    }

    @Test fun `unlogged rereading never changes previous history on page time rating or completion`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.addReadingSeconds("log-book", 99)
        repo.markFinished("log-book")
        repo.setRatingHalfStars("log-book", 9)
        val old = repo.readingLog.value.single()
        assertNull(repo.beginReadingAgain("log-book", false))
        repo.markStarted("log-book")
        repo.saveProgress("log-book", ReadingProgress(fraction = .9f))
        repo.addReadingSeconds("log-book", 77)
        repo.saveBookReview("log-book", 3, "Unlogged review")
        repo.markFinished("log-book")
        assertEquals(listOf(old), BookRepository().readingLog.value)
    }

    @Test fun `editing earlier readings does not replace current library review`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.markFinished("log-book")
        val old = repo.readingLog.value.single()
        repo.beginReadingAgain("log-book", true)
        repo.setReview("log-book", "Current")
        repo.saveReadingLogEntry(old.copy(review = "Historical", ratingHalfStars = 9))
        assertEquals("Current", repo.bookById("log-book")!!.review)
        assertEquals("Historical", BookRepository().readingLog.value.first().review)
    }

    @Test fun `changing completed status to abandoned survives subsequent reader mutations`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.markFinished("log-book")
        repo.saveReadingLogEntry(repo.readingLog.value.single().copy(status = ReadingLogStatus.ABANDONED,
            startedOn = ReadingLogDate(2020), finishedOn = null))
        assertNull(repo.bookById("log-book")!!.finishedAtMillis)
        repo.saveProgress("log-book", ReadingProgress(fraction = .3f))
        repo.markStarted("log-book")
        val entry = BookRepository().readingLog.value.single()
        assertEquals(ReadingLogStatus.ABANDONED, entry.status)
        assertEquals(ReadingLogDate(2020), entry.startedOn)
        assertNull(entry.finishedOn)
    }

    @Test fun `library deletion retains log metadata cover and user review`() = runTest {
        cover.parentFile!!.mkdirs(); cover.writeBytes(byteArrayOf(1, 2, 3))
        StoreFixture.seed(directory, listOf(book().copy(coverFileName = cover.name)))
        val repo = BookRepository()
        repo.markStarted("log-book")
        repo.markFinished("log-book")
        repo.setReview("log-book", "Keep forever")
        val old = repo.readingLog.value.single()
        repo.deleteBook("log-book")
        val reopened = BookRepository()
        assertTrue(reopened.books.value.isEmpty())
        assertEquals(old, reopened.readingLog.value.single())
        assertEquals(cover, reopened.coverFileFor(old))
    }

    @Test fun `deleting a current log does not resurrect it and undo reattaches it`() = runTest {
        StoreFixture.seed(directory, listOf(book()))
        val repo = BookRepository()
        repo.markStarted("log-book")
        val old = repo.readingLog.value.single()
        repo.deleteReadingLogEntry(old.id)
        repo.saveProgress("log-book", ReadingProgress(fraction = .2f))
        assertTrue(BookRepository().readingLog.value.isEmpty())
        assertTrue(StoreFixture.tombstonesOnDisk(directory).containsKey(old.id))
        repo.restoreReadingLogEntry(old, 0)
        assertEquals(old.id, repo.bookById("log-book")!!.activeReadingLogId)
        assertFalse(repo.bookById("log-book")!!.suppressReadingLog)
    }

    @Test fun `stale manual reorder preserves additions and cannot resurrect removed records`() = runTest {
        val repo = BookRepository()
        for (title in listOf("A", "B", "C")) repo.saveReadingLogEntry(ReadingLogEntry(title, title))
        repo.deleteReadingLogEntry("B")
        repo.saveReadingLogEntry(ReadingLogEntry("D", "D"))
        repo.reorderReadingLog(listOf("C", "B", "C", "A", "missing"))
        assertEquals(listOf("C", "A", "D"), BookRepository().readingLog.value.map { it.id })
        assertTrue(StoreFixture.tombstonesOnDisk(directory).containsKey("B"))
    }

    @Test fun `legacy started and finished books migrate once and retain whole-star fallback`() = runTest {
        StoreFixture.seed(directory, listOf(book().copy(startedAtMillis = 1000, finishedAtMillis = 2000,
            status = ReadingStatus.FINISHED, rating = 4, review = "Legacy")))
        val repo = BookRepository()
        assertEquals(8, repo.bookById("log-book")!!.effectiveRatingHalfStars)
        assertEquals(8, repo.readingLog.value.single().ratingHalfStars)
        val id = repo.readingLog.value.single().id
        repo.setRatingHalfStars("log-book", 9)
        assertEquals(9, BookRepository().bookById("log-book")!!.effectiveRatingHalfStars)
        assertEquals(listOf(id), BookRepository().readingLog.value.map { it.id })
    }

    @Test fun `failed log write publishes nothing and corrupt userdata remains protected`() = runTest {
        val repo = BookRepository()
        repo.saveReadingLogEntry(ReadingLogEntry("", "A"))
        val old = repo.readingLog.value
        val temp = File(directory, "userdata.json.tmp").apply { mkdirs() }
        File(temp, "blocked").writeText("blocked")
        try {
            try { repo.saveReadingLogEntry(ReadingLogEntry("", "B")); fail("Must fail") }
            catch (_: IOException) { }
            assertEquals(old, repo.readingLog.value)
        } finally { temp.deleteRecursively() }
        StoreFixture.clear(directory)
        File(directory, "userdata.json").writeText("broken")
        try { BookRepository().saveReadingLogEntry(ReadingLogEntry("", "C")); fail("Must protect") }
        catch (_: LibraryIndexCorruptedException) { }
        assertEquals("broken", File(directory, "userdata.json").readText())
    }

    @Test fun `data and full backups retain log photos dates manual order and reviews`() = runTest {
        cover.parentFile!!.mkdirs(); cover.writeBytes(byteArrayOf(2, 4, 6))
        val repo = BookRepository()
        repo.saveReadingLogEntry(ReadingLogEntry("photo", "Paper", source = ReadingLogSource.PAPER,
            coverFileName = cover.name, ratingHalfStars = 9, review = "Forever", finishedOn = ReadingLogDate(2010)))
        repo.saveReadingLogEntry(ReadingLogEntry("unknown", "Unknown dates"))
        repo.reorderReadingLog(listOf("unknown", "photo"))
        val expected = repo.readingLog.value
        for (mode in BackupMode.entries) {
            val out = ByteArrayOutputStream()
            BackupArchive.write(out, repo.backupDocument(), ReadingStats(), BackupSettings(), mode, "test", 1,
                logCoverFile = { repo.coverFileFor(it) })
            cover.delete()
            val contents = BackupArchive.read(out.toByteArray().inputStream(), File(directory, "books"), cover.parentFile)
            assertTrue(cover.exists())
            assertEquals(2, contents.manifest.readingLogCount)
            val resolved = contents.resolveDocument(File(directory, "books"), cover.parentFile!!)
            repo.replaceAll(resolved.books, resolved.shelves, resolved.readingQueue, resolved.readingLog)
            assertEquals(expected.map { it.copy(updatedAtMillis = 0) },
                BookRepository().readingLog.value.map { it.copy(updatedAtMillis = 0) })
            assertEquals(cover.name, resolved.readingLog.last().coverFileName)
        }
    }
}
