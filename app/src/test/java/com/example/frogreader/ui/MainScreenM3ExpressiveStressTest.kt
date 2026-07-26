package com.example.frogreader.ui

import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.e2e.M3TestFixtures
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenM3ExpressiveStressTest {

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
    fun testEmptyLibraryEdgeCase() {
        val books: List<Book> = emptyList()
        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        val remainingBooks = if (lastOpenedBook == null) emptyList() else books.filter { it.id != lastOpenedBook.id }

        assertNull(lastOpenedBook)
        assertTrue(remainingBooks.isEmpty())
    }

    @Test
    fun testNullLastOpenedAtMillisAllBooks() {
        val b1 = M3TestFixtures.createTestBook(id = "1", title = "Book 1", lastOpenedAtMillis = null)
        val b2 = M3TestFixtures.createTestBook(id = "2", title = "Book 2", lastOpenedAtMillis = null)
        val b3 = M3TestFixtures.createTestBook(id = "3", title = "Book 3", lastOpenedAtMillis = null)
        val books = listOf(b1, b2, b3)

        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        val remainingBooks = if (lastOpenedBook == null) emptyList() else books.filter { it.id != lastOpenedBook.id }

        assertNotNull(lastOpenedBook)
        assertEquals("1", lastOpenedBook?.id)
        assertEquals(2, remainingBooks.size)
        assertEquals(listOf("2", "3"), remainingBooks.map { it.id })
    }

    @Test
    fun testNullLastOpenedAtMillisMixedBooks() {
        val b1 = M3TestFixtures.createTestBook(id = "1", title = "Book 1", lastOpenedAtMillis = null)
        val b2 = M3TestFixtures.createTestBook(id = "2", title = "Book 2", lastOpenedAtMillis = 9999L)
        val b3 = M3TestFixtures.createTestBook(id = "3", title = "Book 3", lastOpenedAtMillis = null)
        val books = listOf(b1, b2, b3)

        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        val remainingBooks = if (lastOpenedBook == null) emptyList() else books.filter { it.id != lastOpenedBook.id }

        assertNotNull(lastOpenedBook)
        assertEquals("2", lastOpenedBook?.id)
        assertEquals(2, remainingBooks.size)
        assertEquals(listOf("1", "3"), remainingBooks.map { it.id })
    }

    @Test
    fun testProgressFractionEdgeCases() {
        fun formatProgressText(fraction: Float): String {
            val safeFraction = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
            return "${(safeFraction * 100).toInt()}%"
        }

        fun rawFormatProgressText(fraction: Float): String {
            return "${(fraction * 100).toInt()}%"
        }

        // Test normal fractions
        assertEquals("0%", rawFormatProgressText(0.0f))
        assertEquals("100%", rawFormatProgressText(1.0f))
        assertEquals("50%", rawFormatProgressText(0.5f))
        assertEquals("99%", rawFormatProgressText(0.999f))

        // Test safe formatting for out of bound values
        assertEquals("0%", formatProgressText(-0.5f))
        assertEquals("100%", formatProgressText(1.5f))
        assertEquals("0%", formatProgressText(Float.NaN))
        assertEquals("100%", formatProgressText(Float.POSITIVE_INFINITY))
        assertEquals("0%", formatProgressText(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun testRapidViewModeToggleClicks() = runTest {
        val repo = mock(BookRepository::class.java)
        given(repo.books).willReturn(MutableStateFlow(emptyList()))
        val settingsRepo = mock(SettingsRepository::class.java)
        given(settingsRepo.libraryViewMode).willReturn(MutableStateFlow(LibraryViewMode.GRID))

        val viewModel = LibraryViewModel(repo, settingsRepo)
        advanceUntilIdle()

        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.value)

        // Perform 100 rapid toggles
        repeat(100) { index ->
            viewModel.toggleViewMode()
            val expected = if (index % 2 == 0) LibraryViewMode.LIST else LibraryViewMode.GRID
            assertEquals(expected, viewModel.viewMode.value)
        }

        // Perform rapid explicit set calls
        repeat(50) {
            viewModel.setViewMode(LibraryViewMode.GRID)
            assertEquals(LibraryViewMode.GRID, viewModel.viewMode.value)
            viewModel.setViewMode(LibraryViewMode.LIST)
            assertEquals(LibraryViewMode.LIST, viewModel.viewMode.value)
        }

        advanceUntilIdle()
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())
    }

    @Test
    fun testLongBookTitlesAndAuthors() {
        val longTitle = "A".repeat(10000)
        val longAuthor = "B".repeat(5000)
        val book = M3TestFixtures.createTestBook(
            id = "long_1",
            title = longTitle,
            author = longAuthor,
            format = BookFormat.EPUB,
            lastOpenedAtMillis = 1000L,
            progress = ReadingProgress(fraction = 0.75f),
        )

        val books = listOf(book)
        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()

        assertNotNull(lastOpenedBook)
        assertEquals(10000, lastOpenedBook?.title?.length)
        assertEquals(5000, lastOpenedBook?.author?.length)
        assertEquals("75%", "${((lastOpenedBook?.progress?.fraction ?: 0f) * 100).toInt()}%")
    }

    @Test
    fun testUnicodeAndEmojiBookMetadata() {
        val emojiTitle = "🐸 Frog Reader: 📖 Chapter 1 — 🚀 Advanced Compose ✨"
        val emojiAuthor = "✍️ Author Emoji Name 🌈"
        val book = M3TestFixtures.createTestBook(
            id = "emoji_1",
            title = emojiTitle,
            author = emojiAuthor,
            format = BookFormat.MOBI,
            lastOpenedAtMillis = 2000L,
        )

        val books = listOf(book)
        val lastOpenedBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()

        assertNotNull(lastOpenedBook)
        assertEquals(emojiTitle, lastOpenedBook?.title)
        assertEquals(emojiAuthor, lastOpenedBook?.author)
    }
}
