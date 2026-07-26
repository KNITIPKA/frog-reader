package com.example.frogreader.ui

import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.e2e.M3TestFixtures
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModeTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDefaultViewModeIsGrid() = runTest {
        val repo = mock(BookRepository::class.java)
        org.mockito.BDDMockito.given(repo.books).willReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val viewModel = LibraryViewModel(repo)
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.value)
    }

    @Test
    fun testSetViewModeUpdatesState() = runTest {
        val repo = mock(BookRepository::class.java)
        org.mockito.BDDMockito.given(repo.books).willReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val viewModel = LibraryViewModel(repo)

        viewModel.setViewMode(LibraryViewMode.LIST)
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())

        viewModel.setViewMode(LibraryViewMode.GRID)
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.first())
    }

    @Test
    fun testToggleViewModeSwitchesBetweenGridAndList() = runTest {
        val repo = mock(BookRepository::class.java)
        org.mockito.BDDMockito.given(repo.books).willReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val viewModel = LibraryViewModel(repo)

        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.value)
        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.value)
        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.value)
    }

    @Test
    fun testHeroBookSelectionLogic() {
        val b1 = M3TestFixtures.createTestBook(
            id = "1",
            title = "Book 1",
            author = "Author 1",
            format = BookFormat.EPUB,
            addedAtMillis = 1000L,
            lastOpenedAtMillis = null,
            progress = ReadingProgress(fraction = 0.1f),
        )
        val b2 = M3TestFixtures.createTestBook(
            id = "2",
            title = "Book 2",
            author = "Author 2",
            format = BookFormat.FB2,
            addedAtMillis = 2000L,
            lastOpenedAtMillis = 5000L,
            progress = ReadingProgress(fraction = 0.5f),
        )
        val books = listOf(b1, b2)

        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertNotNull(lastOpenedBook)
        assertEquals("2", lastOpenedBook?.id)

        val remainingBooks = books.filter { it.id != lastOpenedBook?.id }
        assertEquals(1, remainingBooks.size)
        assertEquals("1", remainingBooks[0].id)
    }

    @Test
    fun testSettingsRepositoryViewModeCollection() = runTest {
        val repo = mock(BookRepository::class.java)
        org.mockito.BDDMockito.given(repo.books).willReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val settingsRepo = mock(com.example.frogreader.data.SettingsRepository::class.java)
        val modeFlow = kotlinx.coroutines.flow.MutableStateFlow(LibraryViewMode.LIST)
        org.mockito.BDDMockito.given(settingsRepo.libraryViewMode).willReturn(modeFlow)

        val viewModel = LibraryViewModel(repo, settingsRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.value)
    }

    @Test
    fun testSetViewModePersistsToSettingsRepository() = runTest {
        val repo = mock(BookRepository::class.java)
        org.mockito.BDDMockito.given(repo.books).willReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        val settingsRepo = mock(com.example.frogreader.data.SettingsRepository::class.java)
        val modeFlow = kotlinx.coroutines.flow.MutableStateFlow(LibraryViewMode.GRID)
        org.mockito.BDDMockito.given(settingsRepo.libraryViewMode).willReturn(modeFlow)

        val viewModel = LibraryViewModel(repo, settingsRepo)
        viewModel.setViewMode(LibraryViewMode.LIST)
        testDispatcher.scheduler.advanceUntilIdle()

        org.mockito.Mockito.verify(settingsRepo).setLibraryViewMode(LibraryViewMode.LIST)
    }
}
