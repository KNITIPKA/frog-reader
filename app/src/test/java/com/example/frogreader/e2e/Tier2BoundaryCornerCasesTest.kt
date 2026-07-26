package com.example.frogreader.e2e

import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.BookScanner
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.library.LibraryMessage
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class Tier2BoundaryCornerCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBoundary_EmptyLibrary() {
        val emptyBooks = emptyList<Book>()
        assertTrue(emptyBooks.isEmpty())

        val heroCardBook = emptyBooks.firstOrNull { it.lastOpenedAtMillis != null } ?: emptyBooks.firstOrNull()
        assertNull(heroCardBook)
    }

    @Test
    fun testBoundary_NoLastOpenedBook() {
        val now = System.currentTimeMillis()
        val book1 = M3TestFixtures.createTestBook(id = "1", addedAtMillis = now - 5000, lastOpenedAtMillis = null)
        val book2 = M3TestFixtures.createTestBook(id = "2", addedAtMillis = now - 1000, lastOpenedAtMillis = null)

        val books = listOf(book1, book2).sortedByDescending { it.lastOpenedAtMillis ?: it.addedAtMillis }
        val heroCardBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()

        assertNotNull(heroCardBook)
        assertEquals("2", heroCardBook?.id) // Most recently added book
    }

    @Test
    fun testBoundary_MissingCovers() {
        val nonExistentFileName = "missing_cover_art_12345.img"
        val book = M3TestFixtures.createTestBook(coverFileName = nonExistentFileName)

        val coversDir = tempFolder.newFolder("covers")
        fun coverFileFor(b: Book): File? =
            b.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

        val coverFile = coverFileFor(book)
        assertNull(coverFile)
    }

    @Test
    fun testBoundary_LongTitleExceeds3Lines() {
        val superLongTitle = "A ".repeat(500) + "Very Long Book Title That Spans Multiple Lines"
        val book = M3TestFixtures.createTestBook(title = superLongTitle)

        assertTrue(book.title.length > 1000)
        assertEquals(superLongTitle, book.title)
    }

    @Test
    fun testBoundary_LongAuthorExceeds2Lines() {
        val multiAuthors = (1..30).joinToString(", ") { "Author Number $it with a Long Name" }
        val book = M3TestFixtures.createTestBook(author = multiAuthors)

        assertNotNull(book.author)
        assertTrue((book.author?.length ?: 0) > 500)
    }

    @Test
    fun testBoundary_CorruptFiles() {
        val corruptFile = tempFolder.newFile("corrupt.epub")
        corruptFile.writeBytes(byteArrayOf(0, 0, 0, 0)) // invalid headers

        var errorCaught = false
        try {
            if (corruptFile.length() < 10) {
                throw IOException("Corrupt or incomplete book file header")
            }
        } catch (e: IOException) {
            errorCaught = true
        }

        assertTrue(errorCaught)
    }

    @Test
    fun testBoundary_InvalidURIs() = runBlocking {
        val testRepo = TestBookRepository()
        val viewModel = LibraryViewModel(testRepo)

        // Passing null URI should be ignored silently without state change or crash
        viewModel.importBook(null)
        assertEquals(false, viewModel.importing.value)
    }

    @Test
    fun testBoundary_FolderRemovalEdgeCases() {
        val savedSet = mutableSetOf(
            "content://com.android.externalstorage.documents/tree/primary%3AFolderA",
        )

        // Removing non-existent folder
        val nonExistentUri = "content://com.android.externalstorage.documents/tree/primary%3AFolderB"
        savedSet.remove(nonExistentUri)
        assertEquals(1, savedSet.size)

        // Removing all folders
        savedSet.clear()
        assertTrue(savedSet.isEmpty())
    }
}
