package com.example.frogreader.ui.profile

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogDateOrder
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingLogReorderingTest {
    @get:Rule val temp = TemporaryFolder()
    private val models = mutableListOf<ReadingLogViewModel>()
    private val year = ReadingLogDate(2020)
    private val month = ReadingLogDate(2020, 5)
    private fun entry(id: String, start: ReadingLogDate? = year, finish: ReadingLogDate? = month, time: Long = 1) =
        ReadingLogEntry(id, id, startedOn = start, finishedOn = finish, createdAtMillis = time)

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        Dispatchers.resetMain()
    }
    private fun repository(): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temp.root)
        `when`(context.cacheDir).thenReturn(temp.root)
        return BookRepository(context)
    }
    private suspend fun model(repository: BookRepository, sort: ReadingLogSort) =
        ReadingLogViewModel(repository, SavedStateHandle(mapOf("readingLogSort" to sort.name))).also { model ->
            models += model
            withTimeout(5000) { model.ready.first { it } }
        }
    private fun ids(model: ReadingLogViewModel, sort: ReadingLogSort) = sortedReadingLog(model.entries.value, sort).map { it.id }

    @Test fun `handles only appear for identical incomplete date peers`() {
        val entries = listOf(entry("year", finish = year), entry("other-year", finish = year),
            entry("month"), entry("other-month"), entry("june", finish = ReadingLogDate(2020, 6)),
            entry("day", finish = ReadingLogDate(2020, 5, 1)), entry("unknown", finish = null))
        val sort = ReadingLogSort.FINISHED_OLDEST
        assertTrue(readingLogCanReorder(entries, sort, "year"))
        assertTrue(readingLogCanReorder(entries, sort, "month"))
        assertFalse(readingLogCanReorder(entries, sort, "june"))
        assertFalse(readingLogCanReorder(entries, sort, "day"))
        assertFalse(readingLogCanReorder(entries, sort, "unknown"))
        assertTrue(readingLogCanMove(entries, sort, "year", 1))
        assertFalse(readingLogCanMove(entries, sort, "year", 2))
        assertTrue(readingLogCanReorder(entries, ReadingLogSort.MANUAL, "day"))
    }

    @Test fun `year January and exact January first stay separate contiguous groups`() {
        val entries = listOf(entry("year-a", finish = year, time = 1),
            entry("day", finish = ReadingLogDate(2020, 1, 1), time = 2),
            entry("year-b", finish = year, time = 3),
            entry("january", finish = ReadingLogDate(2020, 1), time = 4))
        assertEquals(listOf("year-a", "year-b", "january", "day"),
            sortedReadingLog(entries, ReadingLogSort.FINISHED_OLDEST).map { it.id })
        assertEquals(listOf("day", "january", "year-b", "year-a"),
            sortedReadingLog(entries, ReadingLogSort.FINISHED_NEWEST).map { it.id })
    }

    @Test fun `stored partial order is ignored after the remembered date changes`() {
        val a = entry("a", finish = year, time = 1).copy(finishedDateOrder = ReadingLogDateOrder(month, 1))
        val b = entry("b", finish = year, time = 2).copy(finishedDateOrder = ReadingLogDateOrder(month, 0))
        assertEquals(listOf("a", "b"), sortedReadingLog(listOf(b, a), ReadingLogSort.FINISHED_OLDEST).map { it.id })
    }

    @Test fun `date drag saves canonical chronology reverses direction and keeps manual and start orders`() = runBlocking {
        val repo = repository()
        for ((index, id) in listOf("a", "b", "c").withIndex()) repo.saveReadingLogEntry(entry(id, time = index + 1L))
        val model = model(repo, ReadingLogSort.FINISHED_NEWEST)
        assertEquals(listOf("c", "b", "a"), ids(model, ReadingLogSort.FINISHED_NEWEST))
        assertTrue(model.beginReorder("a"))
        model.setSort(ReadingLogSort.STARTED_OLDEST)
        assertEquals(ReadingLogSort.FINISHED_NEWEST.name, model.sort.value)
        model.move("a", 0)
        assertEquals(listOf("a", "c", "b"), ids(model, ReadingLogSort.FINISHED_NEWEST))
        model.finishReorder()
        withTimeout(5000) { model.busy.first { !it } }
        assertEquals(listOf("b", "c", "a"), ids(model, ReadingLogSort.FINISHED_OLDEST))
        assertEquals(listOf("a", "b", "c"), ids(model, ReadingLogSort.STARTED_OLDEST))
        assertEquals(listOf("a", "b", "c"), ids(model, ReadingLogSort.MANUAL))
        assertEquals(listOf("a", "c", "b"), sortedReadingLog(repository().readingLog.value, ReadingLogSort.FINISHED_NEWEST).map { it.id })
        model.setSort(ReadingLogSort.STARTED_OLDEST)
        assertTrue(model.moveAccessibly("c", 0))
        withTimeout(5000) { model.busy.first { !it } }
        assertEquals(listOf("c", "a", "b"), ids(model, ReadingLogSort.STARTED_OLDEST))
        assertEquals(listOf("a", "c", "b"), ids(model, ReadingLogSort.FINISHED_NEWEST))
    }

    @Test fun `drag cannot leave its date group and cancellation restores current repository state`() = runBlocking {
        val repo = repository()
        repo.saveReadingLogEntry(entry("a", time = 1))
        repo.saveReadingLogEntry(entry("b", time = 2))
        repo.saveReadingLogEntry(entry("later", finish = ReadingLogDate(2021), time = 3))
        val model = model(repo, ReadingLogSort.FINISHED_OLDEST)
        assertFalse(model.moveAccessibly("a", 2))
        assertFalse(model.beginReorder("later"))
        assertTrue(model.beginReorder("a"))
        model.move("a", 2)
        assertEquals(listOf("a", "b", "later"), ids(model, ReadingLogSort.FINISHED_OLDEST))
        model.move("a", 1)
        repo.saveReadingLogEntry(entry("new", finish = ReadingLogDate(2022), time = 4))
        model.finishReorder(cancel = true)
        assertEquals(listOf("a", "b", "later", "new"), ids(model, ReadingLogSort.FINISHED_OLDEST))
        assertTrue(repo.readingLog.value.all { it.finishedDateOrder == null })
    }

    @Test fun `details deletion handoff offers Undo and preserves later additions and saved order`() = runBlocking {
        val repo = repository()
        repo.saveReadingLogEntry(entry("a", time = 1).copy(review = "Keep my review", ratingHalfStars = 7))
        repo.saveReadingLogEntry(entry("b", time = 2))
        repo.reorderReadingLogDateGroup(listOf("b", "a"), false, month)
        val original = repo.readingLog.value.first { it.id == "a" }
        val deletion = repo.deleteReadingLogEntry("a")!!
        repo.saveReadingLogEntry(entry("later", finish = ReadingLogDate(2021), time = 3))
        val model = model(repo, ReadingLogSort.FINISHED_OLDEST)
        model.announceDeletedReading(deletion)
        val event = withTimeout(5000) { model.events.first() } as ReadingLogEvent.Deleted
        assertEquals(original, event.entry)
        assertEquals(listOf("b", "later"), repo.readingLog.value.map { it.id })
        model.undo(event)
        withTimeout(5000) { model.busy.first { !it } }
        assertEquals(listOf("a", "b", "later"), repo.readingLog.value.map { it.id })
        val restored = repo.readingLog.value.first { it.id == "a" }
        assertEquals(original.review, restored.review)
        assertEquals(original.ratingHalfStars, restored.ratingHalfStars)
        assertEquals(original.finishedDateOrder, restored.finishedDateOrder)
        assertEquals(listOf("b", "a", "later"), ids(model, ReadingLogSort.FINISHED_OLDEST))
    }
}
