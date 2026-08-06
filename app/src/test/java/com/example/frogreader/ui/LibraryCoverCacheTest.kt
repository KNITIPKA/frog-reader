package com.example.frogreader.ui

import com.example.frogreader.data.BookRepository
import com.example.frogreader.e2e.M3TestFixtures
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.File

/**
 * The grid resolves a cover file during composition for every visible tile, and
 * the repository call ends in `File.exists()`. These tests pin the memoisation
 * that keeps that off the main thread — and, just as importantly, pin the cases
 * where it must NOT be reused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCoverCacheTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(): BookRepository = mock(BookRepository::class.java).also { repo ->
        given(repo.books).willReturn(MutableStateFlow(emptyList()))
        given(repo.shelves).willReturn(MutableStateFlow(emptyList()))
    }

    @Test
    fun `repeated lookups hit the repository once`() = runTest {
        val repo = repository()
        val book = M3TestFixtures.createTestBook(id = "b1", coverFileName = "b1-100.img")
        val cover = File("/covers/b1-100.img")
        given(repo.coverFileFor(book)).willReturn(cover)

        val viewModel = LibraryViewModel(repo)

        assertEquals(cover, viewModel.coverFileFor(book))
        assertEquals(cover, viewModel.coverFileFor(book))
        assertEquals(cover, viewModel.coverFileFor(book))

        verify(repo, times(1)).coverFileFor(book)
    }

    @Test
    fun `a new cover file name invalidates the cached file`() = runTest {
        val repo = repository()
        // updateBookDetails always writes "<id>-<timestamp>.img", so a changed
        // cover always arrives under a new name.
        val before = M3TestFixtures.createTestBook(id = "b1", coverFileName = "b1-100.img")
        val after = before.copy(coverFileName = "b1-200.img")
        val oldFile = File("/covers/b1-100.img")
        val newFile = File("/covers/b1-200.img")
        given(repo.coverFileFor(before)).willReturn(oldFile)
        given(repo.coverFileFor(after)).willReturn(newFile)

        val viewModel = LibraryViewModel(repo)

        assertEquals(oldFile, viewModel.coverFileFor(before))
        assertEquals(newFile, viewModel.coverFileFor(after))
    }

    @Test
    fun `a book without a cover never reaches the repository`() = runTest {
        val repo = repository()
        val book = M3TestFixtures.createTestBook(id = "b1", coverFileName = null)

        val viewModel = LibraryViewModel(repo)

        assertNull(viewModel.coverFileFor(book))
        verify(repo, never()).coverFileFor(book)
    }

    @Test
    fun `an unresolved cover stays retryable`() = runTest {
        val repo = repository()
        // An import whose index write landed before its cover write: the name is
        // set but the file is not there yet. Caching that miss would leave the
        // tile blank until the app restarts.
        val book = M3TestFixtures.createTestBook(id = "b1", coverFileName = "b1-100.img")
        val cover = File("/covers/b1-100.img")
        given(repo.coverFileFor(book)).willReturn(null, cover)

        val viewModel = LibraryViewModel(repo)

        assertNull(viewModel.coverFileFor(book))
        assertEquals(cover, viewModel.coverFileFor(book))
        verify(repo, times(2)).coverFileFor(book)
    }
}
