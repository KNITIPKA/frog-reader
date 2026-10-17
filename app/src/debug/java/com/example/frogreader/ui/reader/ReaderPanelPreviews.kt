package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Reader settings · compact · Light", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsCompactPreview() = ReaderSettingsDrawerPreview(AppTheme.WHITE)

@Preview(name = "Reader settings · compact · Midnight", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsCompactMidnightPreview() = ReaderSettingsDrawerPreview(AppTheme.OLED)

@Preview(name = "Reader menu · collapsed · Midnight", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderMenuCollapsedPreview() = ReaderSettingsDrawerPreview(AppTheme.OLED, closed = true)

@Preview(name = "Reader menu · collapsed · handle enabled", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderMenuHandleEnabledPreview() = ReaderSettingsDrawerPreview(
    AppTheme.OLED, closed = true, showCollapsedHandle = true,
)

@Preview(name = "Reader menu · classic · collapsed", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderMenuClassicCollapsedPreview() = ReaderSettingsDrawerPreview(
    AppTheme.WHITE, closed = true, chromeStyle = ReaderChromeStyle.REGULAR,
)

@Preview(name = "Reader settings · compact · scrolled", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsCompactScrolledPreview() = ReaderSettingsDrawerPreview(
    AppTheme.OLED, scrollOffset = 620,
)

@Preview(name = "Reader settings · compact · 360", widthDp = 360, heightDp = 760)
@Composable
private fun ReaderSettings360Preview() = ReaderSettingsDrawerPreview(AppTheme.OLED)

@Preview(name = "Reader settings · full · Light", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsFullLightPreview() = ReaderSettingsDrawerPreview(AppTheme.WHITE, full = true)

@Preview(name = "Reader settings · full · Beige", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsFullBeigePreview() = ReaderSettingsDrawerPreview(AppTheme.SEPIA, full = true)

@Preview(name = "Reader settings · full · Midnight", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsFullMidnightPreview() = ReaderSettingsDrawerPreview(AppTheme.OLED, full = true)

@Preview(name = "Reader settings · narrow · large text", widthDp = 320, heightDp = 760, fontScale = 1.5f)
@Composable
private fun ReaderSettingsLargeTextPreview() = ReaderSettingsDrawerPreview(AppTheme.WHITE, full = true)

@Preview(name = "Reader settings · margins minimum", widthDp = 360, heightDp = 760)
@Composable
private fun ReaderSettingsMinimumMarginPreview() = ReaderSettingsDrawerPreview(
    AppTheme.SEPIA, margin = ReaderSettings.MIN_SIDE_MARGIN_DP,
)

@Preview(name = "Reader settings · margins maximum", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderSettingsMaximumMarginPreview() = ReaderSettingsDrawerPreview(
    AppTheme.WHITE, margin = ReaderSettings.MAX_SIDE_MARGIN_DP,
)

@Preview(name = "Reader chapters · compact · Light", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderContentsCompactPreview() = ReaderSettingsDrawerPreview(AppTheme.WHITE, contents = true)

@Preview(name = "Reader chapters · full · Beige", widthDp = 393, heightDp = 760)
@Composable
private fun ReaderContentsFullPreview() = ReaderSettingsDrawerPreview(AppTheme.SEPIA, full = true, contents = true)

@Preview(name = "Reader chapters · narrow · large text", widthDp = 320, heightDp = 760, fontScale = 1.5f)
@Composable
private fun ReaderContentsLargeTextPreview() = ReaderSettingsDrawerPreview(AppTheme.WHITE, full = true, contents = true)

/** Memory-only controls: the panel and disabled numeric bounds can be tried interactively. */
@Composable
private fun ReaderSettingsDrawerPreview(
    theme: AppTheme,
    full: Boolean = false,
    margin: Int = 20,
    contents: Boolean = false,
    closed: Boolean = false,
    showCollapsedHandle: Boolean = false,
    chromeStyle: ReaderChromeStyle = ReaderChromeStyle.FLOATING,
    scrollOffset: Int = 0,
) {
    var settings by remember {
        mutableStateOf(
            ReaderSettings(
                readingMode = ReadingMode.SCROLL,
                pageTurnAnimation = PageTurnAnimation.SLIDE,
                pageMarginDp = margin,
            ),
        )
    }
    var appSettings by remember { mutableStateOf(AppSettings(theme = theme, followSystemTheme = false, showCollapsedReaderHandle = showCollapsedHandle)) }
    var expanded by remember { mutableStateOf(full) }
    var open by remember { mutableStateOf(!closed) }
    var peekHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val ready = remember { readerContentsPreviewBook() }
    FrogReaderTheme(appSettings.theme) {
        val colors = readerColors(appSettings.theme)
        Box(
            Modifier.fillMaxSize().background(colors.background)
                .padding(horizontal = if (chromeStyle == ReaderChromeStyle.FLOATING) 16.dp else 0.dp, vertical = 8.dp),
        ) {
            Text(
                "Alice looked all round the table, but there was nothing on it but tea.\n\n" +
                    "‘I don’t see any wine,’ she remarked.",
                color = colors.text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 60.dp),
            )
            ReaderBarSurface(
                colors = colors,
                chromeStyle = chromeStyle,
                topBar = false,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    if (open || appSettings.showCollapsedReaderHandle) {
                        ReaderPanelHandle(
                            colors = colors,
                            expanded = expanded,
                            open = open,
                            onToggleExpand = { if (!open) open = true else expanded = !expanded },
                        )
                    }
                    val compactHeight = if (peekHeight > 0) with(density) { peekHeight.toDp() + 10.dp } else 320.dp
                    val panelModifier = Modifier.height(if (expanded) 520.dp else compactHeight.coerceAtMost(520.dp))
                    if (open && contents) {
                        Box(panelModifier) {
                            ReaderPanelsContent(
                                colorScheme = MaterialTheme.colorScheme,
                                ready = ready,
                                book = ready.book,
                                currentChapter = 3,
                                chapterStartPages = List(12) { it * 18 },
                                onChapterClick = {},
                                onBookmarkClick = {},
                                onRemoveBookmark = {},
                                onCopyQuote = {},
                                onQuoteClick = {},
                                onRemoveQuote = {},
                            )
                        }
                    } else if (open) {
                        ReaderSettingsPanel(
                            settings = settings,
                            appSettings = appSettings,
                            colors = colors,
                            brightness = null,
                            onUpdate = { transform -> settings = transform(settings) },
                            onUpdateApp = { transform -> appSettings = transform(appSettings) },
                            onBrightnessChange = {},
                            modifier = panelModifier,
                            onPeekHeight = { peekHeight = it },
                            scrollState = rememberScrollState(initial = scrollOffset),
                        )
                    }
                    ReaderChapterFooter(
                        chapterLabel = "Chapter 190",
                        chapterParents = emptyList(),
                        bookFraction = 0.38f,
                        bookPageCount = 8314,
                        chapterStops = listOf(0f, 0.12f, 0.26f, 0.42f, 0.61f, 0.84f),
                        chapterStartPages = listOf(0, 997, 2162, 3491, 5072, 6984),
                        progressValuesReady = true,
                        colors = colors,
                        contentsSelected = open && contents,
                        settingsSelected = open && !contents,
                        chromeStyle = chromeStyle,
                        onContents = { open = !open },
                        onSettings = { open = !open },
                        onSeekBook = { false },
                    )
                }
            }
        }
    }
}

private fun readerContentsPreviewBook(): ReaderState.Ready {
    val chapterTitles = listOf(
        "Down the Rabbit-Hole", "The Pool of Tears", "A Caucus-Race and a Long Tale",
        "The Rabbit Sends in a Little Bill", "Advice from a Caterpillar", "Pig and Pepper",
        "A Mad Tea-Party", "The Queen’s Croquet-Ground", "The Mock Turtle’s Story",
        "The Lobster Quadrille", "Who Stole the Tarts?", "Alice’s Evidence",
    )
    return ReaderState.Ready(
        book = Book(
            id = "reader-contents-preview",
            title = "Alice’s Adventures in Wonderland",
            author = "Lewis Carroll",
            format = BookFormat.EPUB,
            addedAtMillis = 0,
        ),
        items = emptyList(),
        chapterStarts = List(chapterTitles.size) { it },
        chapterTitles = chapterTitles,
        chapterDepths = List(chapterTitles.size) { 1 },
        notes = emptyMap(),
        navigation = listOf(
            ReaderNavigationEntry(
                "Alice’s Adventures in Wonderland", 0, ReaderNavigationTarget.ReadingOrder(0, 0),
            ),
        ) + chapterTitles.mapIndexed { index, title ->
            ReaderNavigationEntry(
                "${index + 1}. $title", 1, ReaderNavigationTarget.ReadingOrder(index, index),
            )
        },
    )
}
