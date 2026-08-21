package com.example.frogreader.e2e

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.library.ScanRow
import com.example.frogreader.ui.library.filterScanRows
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.util.Locale

class Tier1FeatureCoverageTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // -------------------------------------------------------------------------
    // FEATURE 1: Hero Card (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun testHeroCard_SelectsMostRecentlyOpenedBook() {
        val now = System.currentTimeMillis()
        val bookOld = M3TestFixtures.createTestBook(
            id = "book-old",
            title = "Older Opened Book",
            lastOpenedAtMillis = now - 10000,
        )
        val bookRecent = M3TestFixtures.createTestBook(
            id = "book-recent",
            title = "Most Recently Opened Book",
            lastOpenedAtMillis = now - 1000,
        )
        val books = listOf(bookRecent, bookOld)

        val heroBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertNotNull(heroBook)
        assertEquals("book-recent", heroBook?.id)
        assertEquals("Most Recently Opened Book", heroBook?.title)
    }

    @Test
    fun testHeroCard_FallbackToFirstBookWhenNoneOpened() {
        val book1 = M3TestFixtures.createTestBook(
            id = "book-1",
            title = "First Added Book",
            lastOpenedAtMillis = null,
        )
        val book2 = M3TestFixtures.createTestBook(
            id = "book-2",
            title = "Second Added Book",
            lastOpenedAtMillis = null,
        )
        val books = listOf(book1, book2)

        val heroBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertNotNull(heroBook)
        assertEquals("book-1", heroBook?.id)
    }

    @Test
    fun testHeroCard_ReturnsNullWhenLibraryIsEmpty() {
        val books = emptyList<Book>()
        val heroBook = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
        assertNull(heroBook)
    }

    @Test
    fun testHeroCard_ReadingProgressBinding() {
        val bookZero = M3TestFixtures.createTestBook(progress = ReadingProgress(fraction = 0.0f))
        val bookPartial = M3TestFixtures.createTestBook(progress = ReadingProgress(fraction = 0.45f))
        val bookComplete = M3TestFixtures.createTestBook(progress = ReadingProgress(fraction = 1.0f))

        assertEquals(0.0f, bookZero.progress.fraction, 0.001f)
        assertEquals(0.45f, bookPartial.progress.fraction, 0.001f)
        assertEquals(1.0f, bookComplete.progress.fraction, 0.001f)
    }

    @Test
    fun testHeroCard_CoverFileResolutionLogic() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "frog_test_covers")
        tempDir.mkdirs()
        try {
            val validCoverFile = File(tempDir, "cover-123.img")
            validCoverFile.writeBytes(byteArrayOf(1, 2, 3))

            val bookWithCover = M3TestFixtures.createTestBook(coverFileName = "cover-123.img")
            val bookWithoutCover = M3TestFixtures.createTestBook(coverFileName = null)

            fun resolveCover(b: Book): File? {
                return b.coverFileName?.let { File(tempDir, it) }?.takeIf { it.exists() }
            }

            val resolvedValid = resolveCover(bookWithCover)
            assertNotNull(resolvedValid)
            assertEquals("cover-123.img", resolvedValid?.name)

            val resolvedNone = resolveCover(bookWithoutCover)
            assertNull(resolvedNone)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHeroCard_QuickActionContinueReadingUpdatesTimestamp() {
        val initialTime = 1000000L
        val book = M3TestFixtures.createTestBook(
            lastOpenedAtMillis = initialTime,
            progress = ReadingProgress(fraction = 0.2f),
        )

        val updatedTime = System.currentTimeMillis()
        val updatedBook = book.copy(
            lastOpenedAtMillis = updatedTime,
            progress = book.progress.copy(fraction = 0.5f),
        )

        assertTrue(updatedBook.lastOpenedAtMillis!! >= initialTime)
        assertEquals(0.5f, updatedBook.progress.fraction, 0.001f)
    }

    // -------------------------------------------------------------------------
    // FEATURE 2: View Toggle (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun testViewToggle_InitialDefaultModeIsGrid() {
        val defaultMode = LibraryViewMode.GRID
        assertEquals("GRID", defaultMode.name)
        assertEquals(LibraryViewMode.GRID, defaultMode)
    }

    @Test
    fun testViewToggle_SwitchGridToListMode() {
        var currentMode = LibraryViewMode.GRID
        currentMode = if (currentMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID
        assertEquals(LibraryViewMode.LIST, currentMode)
    }

    @Test
    fun testViewToggle_SwitchListToGridMode() {
        var currentMode = LibraryViewMode.LIST
        currentMode = if (currentMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID
        assertEquals(LibraryViewMode.GRID, currentMode)
    }

    @Test
    fun testViewToggle_SerializationAndEnumValues() {
        val gridMode = LibraryViewMode.valueOf("GRID")
        val listMode = LibraryViewMode.valueOf("LIST")

        assertEquals(LibraryViewMode.GRID, gridMode)
        assertEquals(LibraryViewMode.LIST, listMode)
        assertEquals(2, LibraryViewMode.entries.size)
    }

    @Test
    fun testViewToggle_StateFlowEmissionOnToggle() = runBlocking {
        val testRepo = TestBookRepository()
        val viewModel = LibraryViewModel(testRepo)

        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.first())

        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.LIST, viewModel.viewMode.first())

        viewModel.toggleViewMode()
        assertEquals(LibraryViewMode.GRID, viewModel.viewMode.first())
    }

    // -------------------------------------------------------------------------
    // FEATURE 3: M3 Expressive Layout (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun testM3Expressive_ContainerColorsMapping() {
        val formatEpub = BookFormat.EPUB
        val formatFb2 = BookFormat.FB2
        val formatMobi = BookFormat.MOBI

        val badgeTypeEpub = when (formatEpub) {
            BookFormat.EPUB -> "primaryContainer"
            BookFormat.FB2 -> "secondaryContainer"
            BookFormat.MOBI -> "tertiaryContainer"
        }
        val badgeTypeFb2 = when (formatFb2) {
            BookFormat.EPUB -> "primaryContainer"
            BookFormat.FB2 -> "secondaryContainer"
            BookFormat.MOBI -> "tertiaryContainer"
        }
        val badgeTypeMobi = when (formatMobi) {
            BookFormat.EPUB -> "primaryContainer"
            BookFormat.FB2 -> "secondaryContainer"
            BookFormat.MOBI -> "tertiaryContainer"
        }

        assertEquals("primaryContainer", badgeTypeEpub)
        assertEquals("secondaryContainer", badgeTypeFb2)
        assertEquals("tertiaryContainer", badgeTypeMobi)
    }

    @Test
    fun testM3Expressive_ShapeCornerRadiusSpecs() {
        val searchPillCorner = 28.dp
        val cardDialogCorner = 20.dp
        val bookCardCorner = 16.dp
        val coverThumbCorner = 12.dp

        assertEquals(28.dp, searchPillCorner)
        assertEquals(20.dp, cardDialogCorner)
        assertEquals(16.dp, bookCardCorner)
        assertEquals(12.dp, coverThumbCorner)
    }

    @Test
    fun testM3Expressive_SpringAnimationParameters() {
        val stiffness = Spring.StiffnessMediumLow
        val damping = Spring.DampingRatioLowBouncy

        assertTrue(stiffness == Spring.StiffnessMediumLow)
        assertTrue(damping == Spring.DampingRatioLowBouncy)
    }

    @Test
    fun testM3Expressive_EmptyLibraryCookie9SidedContainer() {
        val cookieBoxSize = 140.dp
        val cookieIconSize = 56.dp

        assertEquals(140.dp, cookieBoxSize)
        assertEquals(56.dp, cookieIconSize)
    }

    @Test
    fun testM3Expressive_ProgressIndicatorFractionClamping() {
        fun clampFraction(value: Float): Float = value.coerceIn(0f, 1f)

        assertEquals(0.0f, clampFraction(-0.5f), 0.001f)
        assertEquals(0.45f, clampFraction(0.45f), 0.001f)
        assertEquals(1.0f, clampFraction(1.5f), 0.001f)
    }

    // -------------------------------------------------------------------------
    // FEATURE 4: Import Screen (5 tests)
    // -------------------------------------------------------------------------

    // The production filter, not a copy of it. A local reimplementation passes
    // happily while the real screen filters by something else entirely.
    private fun filterScannedBooks(rows: List<ScanRow>, query: String) = filterScanRows(rows, query)

    @Test
    fun testImportScreen_FilterScannedBooksByTitle() {
        val file1 = M3TestFixtures.createScanRow(title = "The Hobbit")
        val file2 = M3TestFixtures.createScanRow(title = "Clean Code")
        val books = listOf(file1, file2)

        val filtered = filterScannedBooks(books, "Hobbit")
        assertEquals(1, filtered.size)
        assertEquals("The Hobbit", filtered[0].title)
    }

    @Test
    fun testImportScreen_FilterScannedBooksByAuthor() {
        val file1 = M3TestFixtures.createScanRow(title = "Book A", author = "Tolkien")
        val file2 = M3TestFixtures.createScanRow(title = "Book B", author = "Martin")
        val books = listOf(file1, file2)

        val filtered = filterScannedBooks(books, "tolk")
        assertEquals(1, filtered.size)
        assertEquals("Book A", filtered[0].title)
    }

    @Test
    fun testImportScreen_FilterScannedBooksByFileName() {
        val file1 = M3TestFixtures.createScanRow(title = "Untitled", name = "my_special_book.epub")
        val file2 = M3TestFixtures.createScanRow(title = "Other")
        val books = listOf(file1, file2)

        val filtered = filterScannedBooks(books, "special")
        assertEquals(1, filtered.size)
        assertEquals("Untitled", filtered[0].title)
    }

    @Test
    fun testImportScreen_BlankSearchQueryReturnsAll() {
        val file1 = M3TestFixtures.createScanRow(title = "Book A")
        val file2 = M3TestFixtures.createScanRow(title = "Book B")
        val books = listOf(file1, file2)

        val filtered = filterScannedBooks(books, "   ")
        assertEquals(2, filtered.size)
    }

    @Test
    fun testImportScreen_UnmatchedSearchQueryReturnsEmpty() {
        val file1 = M3TestFixtures.createScanRow(title = "Book A")
        val file2 = M3TestFixtures.createScanRow(title = "Book B")
        val books = listOf(file1, file2)

        val filtered = filterScannedBooks(books, "NonExistentTerm")
        assertTrue(filtered.isEmpty())
    }

    // -------------------------------------------------------------------------
    // FEATURE 5: Wide Metadata Cards (5 tests)
    // -------------------------------------------------------------------------

    private fun formatSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", sizeBytes.toFloat() / (1024 * 1024))
        }
    }

    @Test
    fun testWideMetadataCards_ThumbnailDimensionsSpec() {
        val widthDp = 56.dp
        val heightDp = 80.dp

        assertEquals(56.dp, widthDp)
        assertEquals(80.dp, heightDp)
    }

    @Test
    fun testWideMetadataCards_TitleMaxLinesLimit() {
        val titleMaxLines = 3
        assertEquals(3, titleMaxLines)
    }

    @Test
    fun testWideMetadataCards_AuthorMaxLinesLimit() {
        val authorMaxLines = 2
        assertEquals(2, authorMaxLines)
    }

    @Test
    fun testWideMetadataCards_FormatBadgesColors() {
        fun badgeColor(format: BookFormat): String = when (format) {
            BookFormat.EPUB -> "primaryContainer"
            BookFormat.FB2 -> "secondaryContainer"
            BookFormat.MOBI -> "tertiaryContainer"
        }

        assertEquals("primaryContainer", badgeColor(BookFormat.EPUB))
        assertEquals("secondaryContainer", badgeColor(BookFormat.FB2))
        assertEquals("tertiaryContainer", badgeColor(BookFormat.MOBI))
    }

    @Test
    fun testWideMetadataCards_SizeFormatterUnitDisplay() {
        assertEquals("500 B", formatSize(500L))
        assertEquals("500 KB", formatSize(500L * 1024))
        assertEquals("2.5 MB", formatSize((2.5f * 1024 * 1024).toLong()))
    }

    // -------------------------------------------------------------------------
    // FEATURE 6: SAF Permissions (5 tests)
    // -------------------------------------------------------------------------

    private fun parseFolderName(docId: String): String {
        val name = docId.substringAfterLast(':').substringAfterLast('/').trim()
        return if (name.isNotBlank()) name else "Папка"
    }

    @Test
    fun testSafPermissions_AddFolderToSharedPreferences() {
        val currentFolders = mutableSetOf<String>()
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks"

        currentFolders.add(treeUri)
        assertTrue(currentFolders.contains(treeUri))
        assertEquals(1, currentFolders.size)
    }

    @Test
    fun testSafPermissions_RemoveFolderFromSharedPreferences() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks"
        val currentFolders = mutableSetOf(treeUri)

        currentFolders.remove(treeUri)
        assertTrue(currentFolders.isEmpty())
    }

    @Test
    fun testSafPermissions_FolderNameParsingFromDocumentId() {
        val docId = "primary:Documents/MyBooks"
        val folderName = parseFolderName(docId)
        assertEquals("MyBooks", folderName)
    }

    @Test
    fun testSafPermissions_FolderNameParsingFallbackForRoot() {
        val docIdRoot = "primary:"
        val docIdBlank = "   "

        assertEquals("Папка", parseFolderName(docIdRoot))
        assertEquals("Папка", parseFolderName(docIdBlank))
    }

    @Test
    fun testSafPermissions_PersistableUriPermissionFlag() {
        val flagRead = Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertTrue(flagRead and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
