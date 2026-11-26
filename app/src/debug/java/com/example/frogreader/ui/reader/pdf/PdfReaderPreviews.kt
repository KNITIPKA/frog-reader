package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.theme.FrogReaderTheme

private val pdfPreviewBook = Book(
    id = "pdf-preview", title = "A field guide to the natural world", author = "Frog Reader",
    format = BookFormat.PDF, fileName = "preview.pdf", addedAtMillis = 0,
    bookmarks = listOf(
        Bookmark("opening", -1, -1, "A quiet opening to the next part of the journey.", 0,
            documentLocation = DocumentLocation.Pdf(12, y = 180f)),
        Bookmark("current", -1, -1, "The roots of a forest reach beyond what we can see.", 1,
            documentLocation = DocumentLocation.Pdf(42, y = 240f)),
        Bookmark("missing", -1, -1, "The original page was in a previous document.", 2, orphaned = true),
    ),
    quotes = listOf(
        Quote("one", "The clearest way into the universe is through a forest wilderness.", -1, 0,
            color = QuoteColor.DEFAULT, note = "Keep this for the introduction.",
            documentLocation = DocumentLocation.Pdf(12, y = 180f)),
        Quote("two", "Between every two pines is a doorway to a new world.", -1, 1,
            color = QuoteColor.BLUE, documentLocation = DocumentLocation.Pdf(42, y = 240f)),
    ),
)

private val pdfPreviewOutline = PdfOutline(
    entries = listOf(
        PdfOutlineEntry("A field guide to the natural world"),
        PdfOutlineEntry("Introduction: a place among the trees", 0, 1),
        PdfOutlineEntry("Part I · The living forest", 12),
        PdfOutlineEntry("Inside the forest", depth = 1),
        PdfOutlineEntry("The roots of a forest", 42, 2),
        PdfOutlineEntry("Connections beneath the forest floor and the lives they sustain", 42, 3),
        PdfOutlineEntry("Looking more closely", 44, 4),
        PdfOutlineEntry("Patterns in the undergrowth", 48, 7),
        PdfOutlineEntry("Part II · Following the river", 84),
        PdfOutlineEntry("Between the banks", 86, 1),
    ),
    truncated = true,
)

@Preview(name = "PDF contents · Light", widthDp = 393, heightDp = 750)
@Composable private fun PdfContentsLight() = PdfPanelPreview(AppTheme.WHITE, 0)

@Preview(name = "PDF contents · Beige · narrow large text", widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable private fun PdfContentsBeigeLarge() = PdfPanelPreview(AppTheme.SEPIA, 0)

@Preview(name = "PDF contents · empty · Beige", widthDp = 320, heightDp = 450, fontScale = 1.3f)
@Composable private fun PdfContentsEmpty() = PdfPanelPreview(AppTheme.SEPIA, 0, PdfOutlineState.Ready(PdfOutline()))

@Preview(name = "PDF contents · error · Light", widthDp = 320, heightDp = 450, fontScale = 1.5f)
@Composable private fun PdfContentsError() = PdfPanelPreview(AppTheme.WHITE, 0, PdfOutlineState.Error)

@Preview(name = "PDF contents · loading · Midnight", widthDp = 393, heightDp = 300)
@Composable private fun PdfContentsLoading() = PdfPanelPreview(AppTheme.OLED, 0, PdfOutlineState.Loading)

@Preview(name = "PDF pages · Light", widthDp = 393, heightDp = 520)
@Composable private fun PdfPagesLight() = PdfPanelPreview(AppTheme.WHITE, 1)

@Preview(name = "PDF pages · Beige · large text", widthDp = 320, heightDp = 650, fontScale = 1.5f)
@Composable private fun PdfPagesBeigeLarge() = PdfPanelPreview(AppTheme.SEPIA, 1)

@Preview(name = "PDF bookmarks · Light", widthDp = 393, heightDp = 650)
@Composable private fun PdfBookmarksLight() = PdfPanelPreview(AppTheme.WHITE, 2)

@Preview(name = "PDF bookmarks · Beige · narrow", widthDp = 320, heightDp = 750, fontScale = 1.3f)
@Composable private fun PdfBookmarksBeige() = PdfPanelPreview(AppTheme.SEPIA, 2)

@Preview(name = "PDF highlights · Beige · large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable private fun PdfQuotesBeige() = PdfPanelPreview(AppTheme.SEPIA, 3)

@Preview(name = "PDF highlights · Midnight", widthDp = 393, heightDp = 650)
@Composable private fun PdfQuotesMidnight() = PdfPanelPreview(AppTheme.OLED, 3)

@Preview(name = "PDF notes · Light", widthDp = 393, heightDp = 650)
@Composable private fun PdfNotesLight() = PdfPanelPreview(AppTheme.WHITE, 4)

@Preview(name = "PDF notes · Beige · narrow large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable private fun PdfNotesBeigeLarge() = PdfPanelPreview(AppTheme.SEPIA, 4)

@Preview(name = "PDF notes · Midnight", widthDp = 393, heightDp = 650)
@Composable private fun PdfNotesMidnight() = PdfPanelPreview(AppTheme.OLED, 4)

@Composable private fun PdfPanelPreview(theme: AppTheme, tab: Int,
    outline: PdfOutlineState = PdfOutlineState.Ready(pdfPreviewOutline)) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Surface(Modifier.fillMaxSize(), color = colors.chrome, contentColor = colors.onChrome) {
            PdfReaderPanels(pdfPreviewBook, 240, 42, colors, {}, {}, {}, {}, {},
                onQuoteNote = { _, _ -> }, onQuoteColor = { _, _ -> }, initialTab = tab, outlineState = outline)
        }
    }
}

@Preview(name = "PDF settings · Light", widthDp = 393, heightDp = 720)
@Composable private fun PdfSettingsLight() = PdfSettingsPreview(AppTheme.WHITE)

@Preview(name = "PDF settings · Beige · large text", widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable private fun PdfSettingsBeige() = PdfSettingsPreview(AppTheme.SEPIA)

@Preview(name = "PDF settings · Pages · Midnight", widthDp = 393, heightDp = 720)
@Composable private fun PdfSettingsPages() = PdfSettingsPreview(AppTheme.OLED, singlePage = true)

@Composable private fun PdfSettingsPreview(theme: AppTheme, singlePage: Boolean = false) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        var appSettings by remember { mutableStateOf(AppSettings(theme = theme)) }
        var settings by remember { mutableStateOf(ReaderSettings(pdfSinglePage = singlePage, pdfTheme = AppTheme.SEPIA)) }
        var brightness by remember { mutableStateOf<Float?>(null) }
        Surface(Modifier.fillMaxSize(), color = colors.chrome, contentColor = colors.onChrome) {
            PdfReaderSettings(colors, appSettings,
                { appSettings = it(appSettings) }, brightness, { brightness = it },
                onBookInfo = {},
                settings = settings, onUpdateSettings = { settings = it(settings) })
        }
    }
}

@Preview(name = "PDF toolbar · Light · Solid", widthDp = 393, heightDp = 160)
@Composable private fun PdfBottomBarLight() = PdfBottomBarPreview(AppTheme.WHITE, PdfToolbarAppearance.SOLID)

@Preview(name = "PDF toolbar · Beige · Glass", widthDp = 393, heightDp = 160)
@Composable private fun PdfBottomBarBeige() = PdfBottomBarPreview(AppTheme.SEPIA, PdfToolbarAppearance.GLASS)

@Preview(name = "PDF toolbar · Midnight · Clear", widthDp = 393, heightDp = 160)
@Composable private fun PdfBottomBarMidnight() = PdfBottomBarPreview(AppTheme.OLED, PdfToolbarAppearance.CLEAR)

@Composable private fun PdfBottomBarPreview(theme: AppTheme, appearance: PdfToolbarAppearance) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        val fraction = remember { mutableFloatStateOf(0f) }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            PdfBottomBar(
                modifier = Modifier.wrapContentHeight(Alignment.Bottom),
                colors = colors,
                appearance = appearance,
                page = 27,
                pageCount = 432,
                showCollapsedHandle = false,
                expandOnSwipe = false,
                showFinish = false,
                onFinish = {},
                onPageIndicator = {},
                onSearch = {},
                onPanelOpenChange = {},
                panelFraction = fraction,
            ) { _, _ -> }
        }
    }
}

@Preview(name = "PDF search bar · Text mode · Light", widthDp = 393, heightDp = 180)
@Composable private fun PdfSearchBarTextLight() = PdfSearchBarPreview(AppTheme.WHITE, PdfSearchMode.TEXT, "chapter")

@Preview(name = "PDF search bar · Page mode · Beige", widthDp = 393, heightDp = 180)
@Composable private fun PdfSearchBarPageBeige() = PdfSearchBarPreview(AppTheme.SEPIA, PdfSearchMode.PAGE, "")

@Preview(name = "PDF search bar · Text mode · Midnight", widthDp = 393, heightDp = 180)
@Composable private fun PdfSearchBarTextMidnight() = PdfSearchBarPreview(AppTheme.OLED, PdfSearchMode.TEXT, "")

@Composable private fun PdfSearchBarPreview(theme: AppTheme, initialMode: PdfSearchMode, initialQuery: String) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        var mode by remember { mutableStateOf(initialMode) }
        var query by remember { mutableStateOf(initialQuery) }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            PdfBottomBar(
                modifier = Modifier.wrapContentHeight(Alignment.Bottom),
                colors = colors,
                appearance = PdfToolbarAppearance.GLASS,
                page = 27,
                pageCount = 432,
                showCollapsedHandle = false,
                expandOnSwipe = false,
                showFinish = false,
                onFinish = {}, onPageIndicator = {}, onSearch = {}, onPanelOpenChange = {},
                panelFraction = remember { mutableFloatStateOf(0f) },
                searchVisible = true,
                searchContent = { panelColors ->
                    PdfSearchContent(
                        colors = panelColors,
                        mode = mode,
                        onModeChange = { mode = it },
                        query = query,
                        onQueryChange = { query = it },
                        textStatus = if (query.isNotEmpty()) "3 of 12" else null,
                        hasMatches = query.isNotEmpty(),
                        onPrevious = {},
                        onNext = {},
                        currentPage = 27,
                        pageCount = 432,
                        onGoToPage = {},
                        onClose = {},
                    )
                },
            ) { _, _ -> }
        }
    }
}

@Preview(name = "PDF search · narrow large text", widthDp = 320, heightDp = 240, fontScale = 1.5f)
@Composable private fun PdfSearchNarrowLarge() = PdfSearchBarPreview(AppTheme.SEPIA, PdfSearchMode.TEXT, "A longer search phrase")
