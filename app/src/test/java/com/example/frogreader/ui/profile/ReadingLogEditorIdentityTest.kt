package com.example.frogreader.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingLogEditorIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private val models = mutableListOf<ReadingLogViewModel>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        Dispatchers.resetMain()
    }

    private val linked = ReadingLogEntry("app-reading", "Original title", "Original author", "Original series",
        source = ReadingLogSource.FROGREADER, linkedBookId = "book", coverFileName = "original.jpg")

    private fun repository(): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temp.root)
        `when`(context.cacheDir).thenReturn(temp.root)
        return BookRepository(context)
    }

    private fun model(repository: BookRepository = repository(), handle: SavedStateHandle = SavedStateHandle()) =
        ReadingLogViewModel(repository, handle).also { model ->
            models += model
            runBlocking { withTimeout(5000) { model.ready.first { it } } }
        }

    @Test fun `FrogReader identity remains locked while rating review and dates change`() {
        val model = model()
        model.edit(linked)
        model.change(linked.copy(title = "Changed", author = "Changed", series = "Changed", coverFileName = "new.jpg",
            source = ReadingLogSource.EXTERNAL, linkedBookId = null, ratingHalfStars = 7, review = "My review", finishedOn = ReadingLogDate(2025)))
        model.removeCover()
        val draft = model.editor.value!!.draft
        assertFalse(model.editor.value!!.canEditIdentity)
        assertEquals(linked.title, draft.title)
        assertEquals(linked.author, draft.author)
        assertEquals(linked.series, draft.series)
        assertEquals(linked.coverFileName, draft.coverFileName)
        assertEquals(linked.source, draft.source)
        assertEquals(linked.linkedBookId, draft.linkedBookId)
        assertEquals(7, draft.ratingHalfStars)
        assertEquals("My review", draft.review)
        assertEquals(ReadingLogDate(2025), draft.finishedOn)
    }

    @Test fun `restored FrogReader draft cannot carry identity edits from the old form`() {
        val changed = linked.copy(title = "Old edited title", coverFileName = "old-edited.jpg", ratingHalfStars = 9, review = "Keep this draft")
        val handle = SavedStateHandle(mapOf("readingLogDraft" to Json.encodeToString(changed),
            "readingLogOriginal" to Json.encodeToString(linked)))
        val model = model(handle = handle)
        assertEquals(linked.title, model.editor.value!!.draft.title)
        assertEquals(linked.coverFileName, model.editor.value!!.draft.coverFileName)
        assertEquals(9, model.editor.value!!.draft.ratingHalfStars)
        assertEquals("Keep this draft", model.editor.value!!.draft.review)
    }

    @Test fun `camera and gallery results cannot replace a FrogReader cover`() = runBlocking<Unit> {
        val repository = spy(repository())
        val model = model(repository)
        model.edit(linked)
        val uri = mock(Uri::class.java)
        model.attachCover(uri)
        assertFalse(model.busy.value)
        assertEquals(linked.coverFileName, model.editor.value!!.draft.coverFileName)
        verify(repository, never()).prepareReadingLogCover(uri)
    }

    @Test fun `manual paper and external entries retain editable identity`() {
        for (source in listOf(ReadingLogSource.PAPER, ReadingLogSource.EXTERNAL)) {
            val model = model()
            val entry = linked.copy(source = source, linkedBookId = null)
            model.edit(entry)
            model.change(entry.copy(title = "New title", author = "New author", coverFileName = null))
            assertTrue(model.editor.value!!.canEditIdentity)
            assertEquals("New title", model.editor.value!!.draft.title)
            assertEquals("New author", model.editor.value!!.draft.author)
            assertNull(model.editor.value!!.draft.coverFileName)
        }
    }

    @Test fun `long imported identity still allows a personal review to be saved intact`() = runBlocking {
        val book = Book("book", "A".repeat(240), "B".repeat(180), BookFormat.EPUB, addedAtMillis = 1,
            series = "C".repeat(220), status = ReadingStatus.READING, startedAtMillis = 100)
        StoreFixture.seed(temp.root, listOf(book))
        val repository = repository()
        val entry = repository.readingLog.value.single()
        val model = model(repository)
        model.edit(entry)
        model.change(entry.copy(ratingHalfStars = 9, review = "Saved without truncating the title"))
        assertTrue(model.editor.value!!.valid)
        model.save()
        withTimeout(5000) { model.busy.first { !it } }
        assertNull(model.editor.value)
        val saved = repository.readingLog.value.single()
        assertEquals(book.title, saved.title)
        assertEquals(book.author, saved.author)
        assertEquals(book.series, saved.series)
        assertEquals(9, saved.ratingHalfStars)
    }

    @Test fun `FrogReader ending can be set and corrected while identity stays protected`() = runBlocking {
        val started = ReadingLogDate(2026, 10, 1).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        StoreFixture.seed(temp.root, listOf(Book("book", "Original title", "Original author", BookFormat.EPUB,
            addedAtMillis = 1, status = ReadingStatus.READING, startedAtMillis = started)))
        val repository = repository()
        val model = model(repository)
        for (date in listOf(ReadingLogDate(2026, 10, 8), ReadingLogDate(2026, 10, 9))) {
            val entry = repository.readingLog.value.single()
            model.edit(entry)
            model.change(entry.withEditedEndDate(date))
            assertTrue(model.editor.value!!.valid)
            assertFalse(model.editor.value!!.canEditIdentity)
            model.save()
            withTimeout(5000) { model.busy.first { !it } }
            assertNull(model.editor.value)
            val saved = repository.readingLog.value.single()
            assertEquals(ReadingLogStatus.FINISHED, saved.status)
            assertEquals(date, saved.finishedOn)
            assertEquals(entry.title, saved.title)
            val book = repository.books.value.single()
            assertEquals(ReadingStatus.FINISHED, book.status)
            assertEquals(date, ReadingLogDate.fromMillis(book.finishedAtMillis!!))
            assertEquals(date, repository().readingLog.value.single().finishedOn)
        }
    }
}
