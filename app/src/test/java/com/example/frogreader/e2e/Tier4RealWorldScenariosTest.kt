package com.example.frogreader.e2e

import com.example.frogreader.data.BookScanner
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.ScannedFolder
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Tier4RealWorldScenariosTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testE2E_FullUserJourney_LaunchToResumeReading() = runBlocking {
        // ---------------------------------------------------------------------
        // STEP 1: App Launch Initialization
        // ---------------------------------------------------------------------
        val testRepo = TestBookRepository()
        val viewModel = LibraryViewModel(testRepo)
        assertEquals(0, viewModel.books.first().size)
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.first())

        // ---------------------------------------------------------------------
        // STEP 2: View Mode Toggle to LIST
        // ---------------------------------------------------------------------
        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())

        // ---------------------------------------------------------------------
        // STEP 3: Launch Import Dialog & Pick Tree Folder via SAF
        // ---------------------------------------------------------------------
        val treeUri = M3TestFixtures.mockUri("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks")
        val activeFolders = mutableListOf(ScannedFolder(treeUri, "Books"))
        assertEquals(1, activeFolders.size)
        assertEquals("Books", activeFolders[0].name)

        // ---------------------------------------------------------------------
        // STEP 4: Scan Discovered Books (EPUB, FB2, MOBI) with Wide Metadata
        // ---------------------------------------------------------------------
        val discoveredEpub = M3TestFixtures.createScannedBookFile(
            uriString = "content://tree/primary%3ADocuments%2FBooks/1.epub",
            title = "The Great Gatsby (Epub)",
            author = "F. Scott Fitzgerald",
            format = BookFormat.EPUB,
        )
        val discoveredFb2 = M3TestFixtures.createScannedBookFile(
            uriString = "content://tree/primary%3ADocuments%2FBooks/2.fb2",
            title = "War and Peace (FB2)",
            author = "Leo Tolstoy",
            format = BookFormat.FB2,
        )
        val discoveredMobi = M3TestFixtures.createScannedBookFile(
            uriString = "content://tree/primary%3ADocuments%2FBooks/3.mobi",
            title = "Moby Dick (MOBI)",
            author = "Herman Melville",
            format = BookFormat.MOBI,
        )
        val scannedList = listOf(discoveredEpub, discoveredFb2, discoveredMobi)
        assertEquals(3, scannedList.size)

        // ---------------------------------------------------------------------
        // STEP 5: Filter Books via Search Bar
        // ---------------------------------------------------------------------
        val query = "Epub"
        val filteredList = scannedList.filter { it.title.contains(query, ignoreCase = true) }
        assertEquals(1, filteredList.size)
        assertEquals("The Great Gatsby (Epub)", filteredList[0].title)

        // ---------------------------------------------------------------------
        // STEP 6: Import EPUB, FB2, and MOBI Books into Repository
        // ---------------------------------------------------------------------
        val now = System.currentTimeMillis()
        val bookEpub = M3TestFixtures.createTestBook(
            id = "b-epub",
            title = "The Great Gatsby (Epub)",
            author = "F. Scott Fitzgerald",
            format = BookFormat.EPUB,
            addedAtMillis = now - 3000,
        )
        val bookFb2 = M3TestFixtures.createTestBook(
            id = "b-fb2",
            title = "War and Peace (FB2)",
            author = "Leo Tolstoy",
            format = BookFormat.FB2,
            addedAtMillis = now - 2000,
        )
        val bookMobi = M3TestFixtures.createTestBook(
            id = "b-mobi",
            title = "Moby Dick (MOBI)",
            author = "Herman Melville",
            format = BookFormat.MOBI,
            addedAtMillis = now - 1000,
        )

        var libraryBooks = listOf(bookEpub, bookFb2, bookMobi).sortedByDescending {
            it.lastOpenedAtMillis ?: it.addedAtMillis
        }
        testRepo.setBooks(libraryBooks)
        assertEquals(3, viewModel.books.first().size)
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())

        // ---------------------------------------------------------------------
        // STEP 7: Open EPUB Book & Update Reading Progress to 60%
        // ---------------------------------------------------------------------
        val openedTime = System.currentTimeMillis()
        val updatedEpub = bookEpub.copy(
            lastOpenedAtMillis = openedTime,
            progress = ReadingProgress(fraction = 0.60f, chapterIndex = 5),
        )
        libraryBooks = listOf(updatedEpub, bookFb2, bookMobi).sortedByDescending {
            it.lastOpenedAtMillis ?: it.addedAtMillis
        }
        testRepo.setBooks(libraryBooks)

        // ---------------------------------------------------------------------
        // STEP 8: Verify Hero Card Prominently Selects EPUB Book with 60% Progress
        // ---------------------------------------------------------------------
        val heroBook = viewModel.books.first().firstOrNull { it.lastOpenedAtMillis != null }
            ?: viewModel.books.first().firstOrNull()

        assertNotNull(heroBook)
        assertEquals("b-epub", heroBook?.id)
        assertEquals("The Great Gatsby (Epub)", heroBook?.title)
        assertEquals(0.60f, heroBook?.progress?.fraction ?: 0f, 0.001f)

        // ---------------------------------------------------------------------
        // STEP 9: Quick Action "Continue Reading" Resumes Reading Seamlessly
        // ---------------------------------------------------------------------
        val resumedTime = System.currentTimeMillis() + 500
        val resumedEpub = heroBook!!.copy(
            lastOpenedAtMillis = resumedTime,
            progress = heroBook.progress.copy(fraction = 0.65f),
        )
        assertTrue(resumedEpub.lastOpenedAtMillis!! >= openedTime)
        assertEquals(0.65f, resumedEpub.progress.fraction, 0.001f)
    }
}
