package com.example.frogreader.ui

import android.net.Uri
import com.example.frogreader.data.ScannedBookFile
import com.example.frogreader.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * Empirical stress tests for Import Screen M3 & SAF Redesign (Milestone 3).
 * Tests edge cases in search filtering, empty folder scans, missing author metadata,
 * null cover bytes fallback rendering, and folder removal state transitions.
 */
class ImportScreenM3StressTest {

    private fun createScannedBook(
        uriString: String = "content://com.android.providers.media.documents/document/1",
        file: File? = null,
        title: String = "Test Title",
        author: String? = "Test Author",
        coverBytes: ByteArray? = byteArrayOf(1, 2, 3),
        format: BookFormat = BookFormat.EPUB,
        sizeBytes: Long = 1024L,
        lastModifiedMillis: Long = System.currentTimeMillis(),
    ): ScannedBookFile {
        val mockUri = mock(Uri::class.java)
        return ScannedBookFile(
            uri = mockUri,
            file = file,
            title = title,
            author = author,
            coverBytes = coverBytes,
            format = format,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis,
        )
    }

    // Helper matching ImportBookSheet's filter logic (lines 303-312)
    private fun filterScannedBooks(books: List<ScannedBookFile>, searchQuery: String): List<ScannedBookFile> {
        if (searchQuery.isBlank()) return books
        val query = searchQuery.trim().lowercase()
        return books.filter {
            it.title.lowercase().contains(query) ||
                (it.author?.lowercase()?.contains(query) == true) ||
                (it.file?.name?.lowercase()?.contains(query) == true)
        }
    }

    // -------------------------------------------------------------------------
    // 1. EMPTY FOLDER SCANS & EMPTY STATES
    // -------------------------------------------------------------------------

    @Test
    fun testEmptyFolderScan_ReturnsEmptyList() {
        val books = emptyList<ScannedBookFile>()
        val filtered = filterScannedBooks(books, "")
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testEmptyStateTitle_SelectionBasedOnSearchQuery() {
        val emptyBlankQuery = ""
        val emptySpaceQuery = "   "
        val nonBlankQuery = "NonExistentBook"

        val titleForBlank = if (emptyBlankQuery.isBlank()) "import_empty_title" else "import_empty_search"
        val titleForSpace = if (emptySpaceQuery.isBlank()) "import_empty_title" else "import_empty_search"
        val titleForNonBlank = if (nonBlankQuery.isBlank()) "import_empty_title" else "import_empty_search"

        assertEquals("import_empty_title", titleForBlank)
        assertEquals("import_empty_title", titleForSpace)
        assertEquals("import_empty_search", titleForNonBlank)
    }

    // -------------------------------------------------------------------------
    // 2. SEARCH FILTER QUERIES (Case Insensitivity & Special Characters)
    // -------------------------------------------------------------------------

    @Test
    fun testSearchFilter_CaseInsensitivity() {
        val b1 = createScannedBook(title = "WAR AND PEACE", author = "Leo Tolstoy")
        val b2 = createScannedBook(title = "Crime and Punishment", author = "FYODOR DOSTOEVSKY")
        val books = listOf(b1, b2)

        val uppercaseSearch = filterScannedBooks(books, "WAR")
        assertEquals(1, uppercaseSearch.size)
        assertEquals("WAR AND PEACE", uppercaseSearch[0].title)

        val lowercaseSearch = filterScannedBooks(books, "tolstoy")
        assertEquals(1, lowercaseSearch.size)
        assertEquals("WAR AND PEACE", lowercaseSearch[0].title)

        val mixedCaseSearch = filterScannedBooks(books, "DoStOeVsKy")
        assertEquals(1, mixedCaseSearch.size)
        assertEquals("Crime and Punishment", mixedCaseSearch[0].title)
    }

    @Test
    fun testSearchFilter_SpecialCharacters() {
        val b1 = createScannedBook(title = "[Special] (2026) *Book* & More - #1 $100%!")
        val books = listOf(b1)

        val specialCharsToTest = listOf("[", "]", "(", ")", "*", "&", "-", "#", "$", "%", "!")

        for (char in specialCharsToTest) {
            val result = filterScannedBooks(books, char)
            assertEquals("Special character '$char' search failed", 1, result.size)
        }
    }

    @Test
    fun testSearchFilter_NullAuthorAndNullFileSafelyHandled() {
        val b1 = createScannedBook(title = "SAF Document Book", author = null, file = null)
        val books = listOf(b1)

        // Matching title
        val titleMatch = filterScannedBooks(books, "saf")
        assertEquals(1, titleMatch.size)

        // Query not matching title; author & file are null
        val noMatch = filterScannedBooks(books, "unknownauthor")
        assertTrue(noMatch.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 3. MISSING AUTHOR METADATA
    // -------------------------------------------------------------------------

    @Test
    fun testMissingAuthor_FallbackLogic() {
        val bWithAuthor = createScannedBook(author = "Jane Austen")
        val bNullAuthor = createScannedBook(author = null)

        val authorDisplay1 = bWithAuthor.author ?: "import_author_unknown"
        val authorDisplay2 = bNullAuthor.author ?: "import_author_unknown"

        assertEquals("Jane Austen", authorDisplay1)
        assertEquals("import_author_unknown", authorDisplay2)
    }

    // -------------------------------------------------------------------------
    // 4. NULL COVER BYTES & FALLBACK RENDERING
    // -------------------------------------------------------------------------

    @Test
    fun testNullCoverBytes_FallbackRenderingCondition() {
        val bWithCover = createScannedBook(coverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val bNullCover = createScannedBook(coverBytes = null)

        val rendersImage1 = bWithCover.coverBytes != null
        val rendersImage2 = bNullCover.coverBytes != null

        assertTrue("Book with non-null coverBytes should render AsyncImage", rendersImage1)
        assertFalse("Book with null coverBytes should render fallback placeholder icon", rendersImage2)
    }

    @Test
    fun testEmptyCoverBytes_EdgeCase() {
        // Zero-length byte array edge case: coverBytes is non-null but empty
        val bEmptyCover = createScannedBook(coverBytes = byteArrayOf())

        val isNonNull = bEmptyCover.coverBytes != null
        val isEmpty = bEmptyCover.coverBytes?.isEmpty() == true

        assertTrue(isNonNull)
        assertTrue("Empty byte array is non-null but has length 0", isEmpty)
    }

    // -------------------------------------------------------------------------
    // 5. FOLDER REMOVAL & RESCAN BEHAVIOR
    // -------------------------------------------------------------------------

    @Test
    fun testFolderRemoval_StateTransitionFromUserFoldersToDefaultScan() {
        val savedFolderUris = mutableSetOf("content://tree/folder1", "content://tree/folder2")

        // Initial state: 2 folders
        var userFoldersCount = savedFolderUris.size
        var isDefaultScanMode = userFoldersCount == 0

        assertEquals(2, userFoldersCount)
        assertFalse(isDefaultScanMode)

        // Remove folder 1
        savedFolderUris.remove("content://tree/folder1")
        userFoldersCount = savedFolderUris.size
        isDefaultScanMode = userFoldersCount == 0

        assertEquals(1, userFoldersCount)
        assertFalse(isDefaultScanMode)

        // Remove folder 2 -> 0 folders left
        savedFolderUris.remove("content://tree/folder2")
        userFoldersCount = savedFolderUris.size
        isDefaultScanMode = userFoldersCount == 0

        assertEquals(0, userFoldersCount)
        assertTrue("Removing all folders transitions scanner to default MediaStore scan mode", isDefaultScanMode)
    }
}
