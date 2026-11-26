package com.example.frogreader.ui.profile

import android.content.Context
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.search.ProfileSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSearchViewModelTest {

    private val workDir = File("build/tmp/profile_search_vm_test")
    private val filesRoot = File(workDir, "live")
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        DatabaseManager.closeAndDeleteForFilesRoot(filesRoot)
        workDir.deleteRecursively()
        filesRoot.mkdirs()
    }

    @After
    fun tearDown() {
        DatabaseManager.closeAndDeleteForFilesRoot(filesRoot)
        workDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun contextFor(root: File): Context = mock(Context::class.java).also {
        `when`(it.filesDir).thenReturn(root)
        `when`(it.cacheDir).thenReturn(root)
    }

    private class FakePreferences(
        initialOrder: List<ProfileSearchCategory> = ProfileSearchCategory.DEFAULT_ORDER,
        initialHidden: Set<ProfileSearchCategory> = emptySet(),
    ) : ProfileSearchPreferences {
        val state = MutableStateFlow(initialOrder to initialHidden)
        override val categoryPreferences: Flow<Pair<List<ProfileSearchCategory>, Set<ProfileSearchCategory>>> = state
        override val dailyGoalMinutes: Flow<Int> = MutableStateFlow(30)
        var saveCount = 0

        override suspend fun saveCategories(
            order: List<ProfileSearchCategory>,
            hidden: Set<ProfileSearchCategory>,
        ) {
            saveCount++
            state.value = order to hidden
        }
    }

    @Test
    fun `search populates 5 categories, recalculates visible count on eye toggle, and persists drag reorder`() = runTest(testDispatcher) {
        val artOfReading = Book(
            id = "b1",
            title = "The Art of Reading",
            format = BookFormat.PDF,
            fileName = "b1.pdf",
            addedAtMillis = 1_000L,
            quotes = listOf(
                Quote(
                    id = "q1",
                    chapterIndex = 0,
                    text = "Reading gives us time to pause and think.",
                    documentLocation = DocumentLocation.Pdf(pageIndex = 41),
                    createdAtMillis = 1_100L,
                ),
                Quote(
                    id = "q2",
                    chapterIndex = 0,
                    text = "Begin with one small step.",
                    note = "Make time to read every morning.",
                    documentLocation = DocumentLocation.Pdf(pageIndex = 17),
                    createdAtMillis = 1_200L,
                ),
                Quote(
                    id = "q3",
                    chapterIndex = 0,
                    text = "Quiet time turns pages into memories.",
                    documentLocation = DocumentLocation.Pdf(pageIndex = 88),
                    createdAtMillis = 1_300L,
                ),
            ),
            bookmarks = listOf(
                Bookmark(
                    id = "bm1",
                    chapterIndex = 0,
                    flatIndex = 0,
                    preview = "Every chapter reminds us how we choose to spend our time in silence.",
                    documentLocation = DocumentLocation.Pdf(pageIndex = 64),
                    createdAtMillis = 1_400L,
                ),
            ),
        )
        val queueItem = ReadingQueueEntry(
            id = "rq1",
            title = "A Brief History of Time",
            author = "Stephen Hawking",
            createdAtMillis = 2_000L,
        )
        val logItem = ReadingLogEntry(
            id = "rl1",
            title = "The Time Machine",
            author = "H. G. Wells",
            status = ReadingLogStatus.FINISHED,
            source = ReadingLogSource.PAPER,
            createdAtMillis = 3_000L,
        )

        StoreFixture.seed(
            dir = filesRoot,
            books = listOf(artOfReading),
            readingQueue = listOf(queueItem),
            readingLog = listOf(logItem),
        )
        val ctx = contextFor(filesRoot)
        val bookRepo = BookRepository(ctx)
        val statsRepo = StatsRepository(ctx)
        statsRepo.replaceAll(ReadingStats(dailySeconds = mapOf("2026-10-20" to 36_000L)))

        val prefs = FakePreferences()
        val vm = ProfileSearchViewModel(
            bookRepository = bookRepo,
            statsRepository = statsRepo,
            searchRepository = ProfileSearchRepository(filesRoot, ioDispatcher = testDispatcher),
            preferences = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setQuery("time")
        testDispatcher.scheduler.advanceUntilIdle()
        // Room's internal query pool runs on Dispatchers.IO; wait on real wall-clock briefly if needed:
        val deadline = System.nanoTime() + 5_000_000_000L
        while (vm.uiState.value.results.totalCountAllCategories != 7 && System.nanoTime() < deadline) {
            Thread.sleep(10)
            testDispatcher.scheduler.advanceUntilIdle()
        }
        val settled = vm.uiState.value
        assertEquals(3, settled.results.quotes.size)
        assertEquals(1, settled.results.bookmarks.size)
        assertEquals(1, settled.results.readingQueue.size)
        assertEquals(1, settled.results.readingLog.size)
        assertEquals(1, settled.results.achievements.size)
        assertEquals(7, settled.visibleResultCount)

        // Hide QUOTES via eye toggle -> visibleResultCount drops from 7 to 4 immediately & persists
        vm.toggleCategoryVisibility(ProfileSearchCategory.QUOTES)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(4, vm.uiState.value.visibleResultCount)
        assertTrue(ProfileSearchCategory.QUOTES in prefs.state.value.second)

        // Reorder ACHIEVEMENTS (index 4) to top (index 0)
        assertTrue(vm.moveCategory(fromIndex = 4, toIndex = 0))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ProfileSearchCategory.ACHIEVEMENTS, vm.uiState.value.categoryOrder.first())
        assertEquals(ProfileSearchCategory.ACHIEVEMENTS, prefs.state.value.first.first())

        // Expand "Show 1 more quote" and verify clearQuery resets expansion
        vm.toggleCategoryExpanded(ProfileSearchCategory.QUOTES)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(ProfileSearchCategory.QUOTES in vm.uiState.value.expandedCategories)
        vm.clearQuery()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.query)
        assertFalse(ProfileSearchCategory.QUOTES in vm.uiState.value.expandedCategories)
    }

    @Test
    fun `hydrates saved category order and hidden categories from preferences`() = runTest(testDispatcher) {
        val customOrder = listOf(
            ProfileSearchCategory.READING_LOG,
            ProfileSearchCategory.QUOTES,
            ProfileSearchCategory.BOOKMARKS,
            ProfileSearchCategory.ACHIEVEMENTS,
            ProfileSearchCategory.READING_QUEUE,
        )
        val customHidden = setOf(ProfileSearchCategory.BOOKMARKS, ProfileSearchCategory.ACHIEVEMENTS)
        val ctx = contextFor(filesRoot)
        val prefs = FakePreferences(initialOrder = customOrder, initialHidden = customHidden)

        val vm = ProfileSearchViewModel(
            bookRepository = BookRepository(ctx),
            statsRepository = StatsRepository(ctx),
            searchRepository = ProfileSearchRepository(filesRoot),
            preferences = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(customOrder, vm.uiState.value.categoryOrder)
        assertEquals(customHidden, vm.uiState.value.hiddenCategories)
    }
}
