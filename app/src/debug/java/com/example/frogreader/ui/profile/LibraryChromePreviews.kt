package com.example.frogreader.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.FrogMainScaffold
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.library.LibraryViewModel
import com.example.frogreader.ui.nav.NavTab
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow

@Preview(name = "Library · 360dp", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun LibraryChromePreview() = LibraryChrome(AppTheme.WHITE, expanded = false)

@Preview(name = "Library · OLED", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun DarkLibraryChromePreview() = LibraryChrome(AppTheme.OLED, expanded = false)

@Preview(name = "Library · add menu", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun LibraryMenuPreview() = LibraryChrome(AppTheme.WHITE, expanded = true)

@Preview(name = "Library · large text", widthDp = 360, heightDp = 800, showSystemUi = true, fontScale = 1.5f)
@Composable
private fun LargeTextLibraryChromePreview() = LibraryChrome(AppTheme.WHITE, expanded = false)

@Preview(name = "Library · list", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun ListLibraryChromePreview() = LibraryChrome(AppTheme.WHITE, expanded = false, mode = LibraryViewMode.LIST)

@Composable
private fun LibraryChrome(theme: AppTheme, expanded: Boolean, mode: LibraryViewMode = LibraryViewMode.GRID) {
    val viewModel = remember(mode) { LibraryViewModel(PreviewLibraryRepository()).apply { setViewMode(mode) } }
    var menuExpanded by remember { mutableStateOf(expanded) }
    FrogReaderTheme(theme) {
        FrogMainScaffold(
            navigationVisible = true,
            selectedTab = NavTab.LIBRARY,
            onTabSelected = {},
            importVisible = true,
            importing = false,
            importExpanded = menuExpanded,
            onImportExpandedChange = { menuExpanded = it },
            onAddBook = {},
            onScanFolder = {},
            onNewShelf = {},
        ) { padding ->
            LibraryScreen(viewModel = viewModel, contentPadding = padding)
        }
    }
}

/** In-memory fixtures only; previewing must never touch the user's library. */
private class PreviewLibraryRepository : BookRepository() {
    override val books = MutableStateFlow(listOf(
        "The Time Machine" to "H. G. Wells",
        "Pride and Prejudice" to "Jane Austen",
        "The Secret Garden" to "Frances Hodgson Burnett",
        "Treasure Island" to "Robert Louis Stevenson",
        "The Odyssey" to "Homer",
        "A Room of One's Own" to "Virginia Woolf",
        "Frankenstein" to "Mary Shelley",
        "The Adventures of Sherlock Holmes" to "Arthur Conan Doyle",
        "Little Women" to "Louisa May Alcott",
        "Jane Eyre" to "Charlotte Brontë",
    ).mapIndexed { index, (title, author) ->
        Book(
            id = "preview-$index", title = title, author = author,
            format = BookFormat.EPUB, fileName = "preview.epub", addedAtMillis = 0,
            progress = ReadingProgress(fraction = if (index == 0) 0.42f else 0f),
            status = if (index == 0) ReadingStatus.READING else ReadingStatus.NONE,
        )
    })
    override val shelves = MutableStateFlow<List<Shelf>>(emptyList())
    override suspend fun cleanOrphanCaches() = Unit
    override suspend fun loadContent(book: Book): BookContent = error("Preview has no book files")
    override fun coverFileFor(book: Book) = null
}
