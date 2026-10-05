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

@Preview(name = "Beige · library grid", widthDp = 393, heightDp = 882, showSystemUi = true)
@Composable
private fun BeigeLibraryChromePreview() = LibraryChrome(AppTheme.SEPIA, expanded = false)

@Preview(name = "Beige · library list", widthDp = 393, heightDp = 882, showSystemUi = true)
@Composable
private fun BeigeListLibraryChromePreview() = LibraryChrome(AppTheme.SEPIA, expanded = false, mode = LibraryViewMode.LIST)

@Preview(name = "Beige · add menu", widthDp = 393, heightDp = 882, showSystemUi = true)
@Composable
private fun BeigeLibraryMenuPreview() = LibraryChrome(AppTheme.SEPIA, expanded = true)

@Preview(name = "Beige · library · large text", widthDp = 360, heightDp = 800, showSystemUi = true, fontScale = 1.5f)
@Composable
private fun BeigeLargeTextLibraryChromePreview() = LibraryChrome(AppTheme.SEPIA, expanded = false)

@Preview(name = "Hero · screenshot title", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun ScreenshotHeroPreview() = LibraryChrome(
    AppTheme.OLED, expanded = false, heroTitle = "Последнее желание", heroAuthor = "Анджей Сапковский",
)

@Preview(name = "Hero · short title", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun ShortHeroPreview() = LibraryChrome(
    AppTheme.WHITE, expanded = false, heroTitle = "Dune", heroAuthor = "Frank Herbert",
)

@Preview(name = "Hero · balanced short title", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun BalancedShortHeroPreview() = LibraryChrome(
    AppTheme.WHITE, expanded = false, heroTitle = "Animal Farm", heroAuthor = "George Orwell",
)

@Preview(name = "Hero · long metadata · 320dp", widthDp = 320, heightDp = 800, showSystemUi = true)
@Composable
private fun LongHeroPreview() = LibraryChrome(
    AppTheme.WHITE, expanded = false,
    heroTitle = "The Lord of the Rings: The Fellowship of the Ring",
    heroAuthor = "John Ronald Reuel Tolkien",
)

@Preview(name = "Hero · no author", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun NoAuthorHeroPreview() = LibraryChrome(
    AppTheme.WHITE, expanded = false, heroTitle = "Dune", heroAuthor = null,
)

@Preview(name = "Hero · landscape", widthDp = 800, heightDp = 400, showSystemUi = true)
@Composable
private fun LandscapeHeroPreview() = LibraryChrome(
    AppTheme.OLED, expanded = false, heroTitle = "Последнее желание", heroAuthor = "Анджей Сапковский",
)

@Preview(name = "Hero · long metadata · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun LargeTextLongHeroPreview() = LibraryChrome(
    AppTheme.WHITE, expanded = false,
    heroTitle = "The Lord of the Rings: The Fellowship of the Ring",
    heroAuthor = "John Ronald Reuel Tolkien",
)

@Composable
private fun LibraryChrome(
    theme: AppTheme,
    expanded: Boolean,
    mode: LibraryViewMode = LibraryViewMode.GRID,
    heroTitle: String = "The Time Machine",
    heroAuthor: String? = "H. G. Wells",
) {
    val viewModel = remember(mode, heroTitle, heroAuthor) {
        LibraryViewModel(PreviewLibraryRepository(heroTitle, heroAuthor)).apply { setViewMode(mode) }
    }
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
private class PreviewLibraryRepository(heroTitle: String, heroAuthor: String?) : BookRepository() {
    override val books = MutableStateFlow(listOf(
        heroTitle to heroAuthor,
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
            progress = ReadingProgress(fraction = listOf(0.84f, 0.41f, 0.66f, 0.49f, 0.57f, 0.11f, 0.01f).getOrElse(index) { 0f }),
            status = if (index < 7) ReadingStatus.READING else ReadingStatus.NONE,
            lastOpenedAtMillis = if (index == 0) 1_700_000_000_000L else null,
        )
    })
    override val shelves = MutableStateFlow<List<Shelf>>(emptyList())
    override suspend fun cleanOrphanCaches() = Unit
    override suspend fun loadContent(book: Book): BookContent = error("Preview has no book files")
    override fun coverFileFor(book: Book) = null
}
