package com.example.frogreader.data

import android.content.Context
import com.example.frogreader.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

class ReadingLogSynchronizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setup() {
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
    }

    private fun book(id: String = "book") = Book(id, "A book", author = "An Author", format = BookFormat.EPUB,
        fileName = "$id.epub", addedAtMillis = 1, startedAtMillis = 100, status = ReadingStatus.READING,
        readingSeconds = 321, progress = ReadingProgress(elementIndex = 12, scrollOffset = 35, fraction = .6f),
        quotes = listOf(Quote("quote-$id", "Saved passage", 0, 1, note = "An irreplaceable note")),
        bookmarks = listOf(Bookmark("bookmark-$id", 12, 0, "Bookmark", 1)),
        readingQueueNote = "Why I wanted to read it", readerSettings = ReaderSettings(fontSizeSp = 22f),
        ratingHalfStars = 9, review = "Keep this review")

    private fun repository() = BookRepository(context)
    private fun seed(vararg books: Book) = StoreFixture.seed(temporary.root, books.toList())

    private fun assertReset(book: Book) {
        assertEquals(ReadingProgress(), book.progress)
        assertEquals(0L, book.readingSeconds)
        assertEquals(ReadingStatus.NONE, book.status)
        assertNull(book.startedAtMillis)
        assertNull(book.finishedAtMillis)
        assertNull(book.activeReadingLogId)
        assertFalse(book.suppressReadingLog)
    }

    @Test fun `deleting the current reading resets only reading data and keeps all annotations`() = runTest {
        seed(book())
        val repo = repository()
        val before = repo.bookById("book")!!
        val entry = repo.readingLog.value.single()
        assertEquals(before, repo.deleteReadingLogEntry(entry.id)?.previousReading)
        val after = repository().bookById("book")!!
        assertReset(after)
        assertEquals(before.quotes, after.quotes)
        assertEquals(before.bookmarks, after.bookmarks)
        assertEquals(before.readingQueueNote, after.readingQueueNote)
        assertEquals(before.readerSettings, after.readerSettings)
        assertEquals(before.review, after.review)
        assertEquals(before.effectiveRatingHalfStars, after.effectiveRatingHalfStars)
        assertEquals(before.fileName, after.fileName)
        assertTrue(repository().readingLog.value.isEmpty())
    }

    @Test fun `deleting an older reading leaves the current reading and library progress untouched`() = runTest {
        seed(book())
        val repo = repository()
        repo.markFinished("book")
        val old = repo.readingLog.value.single()
        repo.beginReadingAgain("book")
        repo.saveProgress("book", ReadingProgress(elementIndex = 8, fraction = .4f))
        repo.addReadingSeconds("book", 70)
        val before = repo.bookById("book")!!
        val current = repo.readingLogEntryFor("book")!!
        assertNull(repo.deleteReadingLogEntry(old.id)?.previousReading)
        assertEquals(before, repository().bookById("book"))
        assertEquals(listOf(current), repository().readingLog.value)
    }

    @Test fun `deleting the newest reading retains older history and reopening creates a distinct id`() = runTest {
        seed(book())
        val repo = repository()
        repo.markFinished("book")
        val old = repo.readingLog.value.single()
        val removedId = repo.beginReadingAgain("book")!!
        repo.reorderReadingLog(listOf(removedId, old.id))
        repo.deleteReadingLogEntry(removedId)
        assertReset(repo.bookById("book")!!)
        assertNull(repo.readingLogEntryFor("book"))
        assertEquals(listOf(old), repository().readingLog.value)
        repo.markStarted("book")
        val current = repo.readingLogEntryFor("book")!!
        assertNotEquals(old.id, current.id)
        assertNotEquals(removedId, current.id)
        assertEquals(2, repo.readingLog.value.map { it.id }.distinct().size)
        assertEquals(old, repo.readingLog.value.first())
        assertEquals(ReadingLogStatus.IN_PROGRESS, current.status)
        assertEquals(0L, current.readingSeconds)
        assertEquals(ReadingProgress(), repo.bookById("book")!!.progress)
    }

    @Test fun `removing all logs resets their books and each book logs again only when opened`() = runTest {
        seed(book("one"), book("two"))
        val repo = repository()
        repo.readingLog.value.toList().forEach { repo.deleteReadingLogEntry(it.id) }
        assertTrue(repository().readingLog.value.isEmpty())
        repository().books.value.forEach(::assertReset)
        repo.markStarted("one")
        assertEquals("one", repository().readingLog.value.single().linkedBookId)
        assertReset(repo.bookById("two")!!)
        repo.markStarted("two")
        assertEquals(setOf("one", "two"), repository().readingLog.value.map { it.linkedBookId }.toSet())
    }

    @Test fun `undo restores the same reading position while preserving newer annotation edits`() = runTest {
        seed(book())
        val repo = repository()
        val entry = repo.readingLog.value.single()
        val snapshot = repo.deleteReadingLogEntry(entry.id)!!.previousReading!!
        repo.setQuoteNote("book", "quote-book", "Edited after deletion")
        repo.restoreReadingLogEntry(entry, 0, snapshot)
        val restored = repository().bookById("book")!!
        assertEquals(snapshot.progress, restored.progress)
        assertEquals(snapshot.startedAtMillis, restored.startedAtMillis)
        assertEquals(snapshot.readingSeconds, restored.readingSeconds)
        assertEquals(snapshot.status, restored.status)
        assertEquals(entry.id, restored.activeReadingLogId)
        assertEquals("Edited after deletion", restored.quotes.single().note)
        val restoredEntry = repository().readingLog.value.single()
        assertEquals(entry, restoredEntry.copy(updatedAtMillis = entry.updatedAtMillis))
        assertTrue(restoredEntry.updatedAtMillis > entry.updatedAtMillis)
    }

    @Test fun `undo after reopening restores history without replacing the new reading`() = runTest {
        seed(book())
        val repo = repository()
        val entry = repo.readingLog.value.single()
        val snapshot = repo.deleteReadingLogEntry(entry.id)?.previousReading
        repo.markStarted("book")
        repo.saveProgress("book", ReadingProgress(elementIndex = 2, fraction = .1f))
        repo.addReadingSeconds("book", 25)
        val current = repo.bookById("book")!!
        repo.restoreReadingLogEntry(entry, 0, snapshot)
        assertEquals(current, repository().bookById("book"))
        assertEquals(2, repository().readingLog.value.size)
        assertEquals(current.activeReadingLogId, repo.readingLogEntryFor("book")!!.id)
    }

    @Test fun `an empty legacy suppressed history repairs stale progress and persists the repair`() = runTest {
        seed(book().copy(activeReadingLogId = null, suppressReadingLog = true))
        val repo = repository()
        assertReset(repo.bookById("book")!!)
        assertTrue(repo.readingLog.value.isEmpty())
        repo.setQuoteNote("book", "quote-book", "Keep this note")
        assertReset(StoreFixture.booksOnDisk(temporary.root).single())
        repo.markStarted("book")
        assertEquals(ReadingLogStatus.IN_PROGRESS, repository().readingLog.value.single().status)
        assertEquals(0L, repo.readingLog.value.single().readingSeconds)
        assertEquals("Keep this note", repo.bookById("book")!!.quotes.single().note)
    }

    @Test fun `continuing an abandoned reading retains its identity position date precision and statistics`() = runTest {
        seed(book())
        val repo = repository()
        val entry = repo.readingLog.value.single()
        repo.saveReadingLogEntry(entry.copy(status = ReadingLogStatus.ABANDONED,
            startedOn = ReadingLogDate(2020, 1), finishedOn = ReadingLogDate(2020, 6)))
        val abandoned = repo.readingLog.value.single()
        val position = repo.bookById("book")!!.progress
        repo.markStarted("book")
        assertEquals(abandoned, repo.readingLog.value.single())
        repo.resumeReading("book")
        val resumed = repository().readingLog.value.single()
        assertEquals(abandoned.id, resumed.id)
        assertEquals(ReadingLogStatus.IN_PROGRESS, resumed.status)
        assertEquals(abandoned.startedOn, resumed.startedOn)
        assertNull(resumed.finishedOn)
        assertEquals(abandoned.readingSeconds, resumed.readingSeconds)
        assertEquals(abandoned.review, resumed.review)
        assertEquals(abandoned.ratingHalfStars, resumed.ratingHalfStars)
        assertEquals(position, repository().bookById("book")!!.progress)
        assertEquals(ReadingStatus.READING, repo.bookById("book")!!.status)
        repo.addReadingSeconds("book", 17)
        repo.markFinished("book")
        assertEquals(338L, repository().readingLog.value.single().readingSeconds)
        assertEquals(ReadingLogStatus.FINISHED, repo.readingLog.value.single().status)
    }

    @Test fun `restarting an abandoned book creates a new reading and preserves the abandoned one`() = runTest {
        seed(book())
        val repo = repository()
        repo.setStatus("book", ReadingStatus.ABANDONED)
        val abandoned = repo.readingLog.value.single()
        repo.beginReadingAgain("book")
        assertEquals(abandoned, repository().readingLog.value.first())
        assertEquals(ReadingLogStatus.IN_PROGRESS, repo.readingLog.value.last().status)
        assertEquals(ReadingProgress(), repo.bookById("book")!!.progress)
        assertEquals(0L, repo.bookById("book")!!.readingSeconds)
        assertNotEquals(abandoned.id, repo.bookById("book")!!.activeReadingLogId)
    }

    @Test fun `library and log status changes agree and resumed readings have no finish date`() = runTest {
        seed(book())
        val repo = repository()
        repo.markFinished("book")
        val entry = repo.readingLog.value.single()
        assertNotNull(entry.finishedOn)
        repo.setStatus("book", ReadingStatus.READING)
        assertEquals(ReadingLogStatus.IN_PROGRESS, repository().readingLog.value.single().status)
        assertNull(repo.readingLog.value.single().finishedOn)
        repo.saveReadingLogEntry(repo.readingLog.value.single().copy(status = ReadingLogStatus.FINISHED))
        assertEquals(ReadingStatus.FINISHED, repository().bookById("book")!!.status)
        repo.saveReadingLogEntry(repo.readingLog.value.single().copy(status = ReadingLogStatus.ABANDONED))
        assertEquals(ReadingStatus.ABANDONED, repository().bookById("book")!!.status)
    }

    @Test fun `late reader writes cannot revive deleted progress or overwrite a later reading`() = runTest {
        seed(book())
        val repo = repository()
        val oldId = repo.readingLog.value.single().id
        suspend fun staleWrites() {
            repo.saveProgressForReading("book", ReadingProgress(fraction = .9f), oldId)
            repo.addReadingSecondsForReading("book", 999, oldId)
            repo.markFinishedForReading("book", oldId)
        }
        repo.deleteReadingLogEntry(oldId)
        staleWrites()
        assertReset(repository().bookById("book")!!)
        assertTrue(repo.readingLog.value.isEmpty())
        repo.markStarted("book")
        val newReading = repo.bookById("book")!!
        staleWrites()
        assertEquals(newReading, repository().bookById("book"))
        repo.addReadingSecondsForReading("book", 20, newReading.activeReadingLogId)
        assertEquals(20L, repository().readingLog.value.single().readingSeconds)
    }
}
