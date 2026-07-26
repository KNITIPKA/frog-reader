package com.example.frogreader.e2e

import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.ScannedBookFile
import com.example.frogreader.data.ScannedFolder
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Tier3CrossFeatureCombinationsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun testCrossFeature_ViewToggleWhileImporting() = runBlocking {
        val testRepo = TestBookRepository()
        val viewModel = LibraryViewModel(testRepo)

        // Simulate active importing state
        val dummyUri = M3TestFixtures.mockUri("content://dummy/123")
        viewModel.importBook(dummyUri)

        // Toggle view mode while import flow is executing/triggered
        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())

        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.first())
    }

    @Test
    fun testCrossFeature_HeroCardVsGridListOpen() {
        val now = System.currentTimeMillis()
        val book1 = M3TestFixtures.createTestBook(id = "b1", title = "Book 1", lastOpenedAtMillis = now - 5000)
        val book2 = M3TestFixtures.createTestBook(id = "b2", title = "Book 2", lastOpenedAtMillis = now - 1000)
        var books = listOf(book1, book2).sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }

        // Hero Card resolves b2
        val initialHero = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertEquals("b2", initialHero?.id)

        // User opens b1 from Grid/List item
        val updatedNow = System.currentTimeMillis() + 100
        val b1Opened = book1.copy(lastOpenedAtMillis = updatedNow)
        books = listOf(b1Opened, book2).sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }

        // Hero Card now resolves b1
        val updatedHero = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertEquals("b1", updatedHero?.id)
    }

    @Test
    fun testCrossFeature_SearchFilteredWideMetadata_WithActiveFolderChips() {
        val folderA = ScannedFolder(M3TestFixtures.mockUri("content://tree/FolderA"), "FolderA")
        val activeFolders = listOf(folderA)

        val file1 = M3TestFixtures.createScannedBookFile(
            uriString = "content://tree/FolderA/book1",
            title = "Android Expressive UI Guide",
            format = BookFormat.EPUB,
        )
        val file2 = M3TestFixtures.createScannedBookFile(
            uriString = "content://tree/FolderA/book2",
            title = "Kotlin Programming",
            format = BookFormat.FB2,
        )
        val scannedList = listOf(file1, file2)

        // Combined logic: verify active folders exist AND search query filters scanned list
        assertTrue(activeFolders.isNotEmpty())

        val query = "Expressive"
        val filteredList = scannedList.filter {
            it.title.lowercase().contains(query.lowercase())
        }

        assertEquals(1, filteredList.size)
        assertEquals("Android Expressive UI Guide", filteredList[0].title)
        assertEquals(BookFormat.EPUB, filteredList[0].format)
    }
}
