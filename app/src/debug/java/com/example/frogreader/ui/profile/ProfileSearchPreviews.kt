package com.example.frogreader.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ResolvedAchievement
import com.example.frogreader.data.search.AchievementSearchHit
import com.example.frogreader.data.search.BookmarkSearchHit
import com.example.frogreader.data.search.ProfileSearchResults
import com.example.frogreader.data.search.QuoteSearchHit
import com.example.frogreader.data.search.ReadingLogSearchHit
import com.example.frogreader.data.search.ReadingQueueSearchHit
import com.example.frogreader.data.search.SearchMatchHighlighter
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Midnight · Profile search results", widthDp = 393, heightDp = 1040, showSystemUi = false)
@Composable
private fun ProfileSearchMidnightResultsPreview() {
    FrogReaderTheme(AppTheme.OLED) {
        ProfileSearchContent(state = sampleEnglishSearchState(categoryOrderExpanded = false))
    }
}

@Preview(name = "Midnight · Category order open", widthDp = 393, heightDp = 1040, showSystemUi = false)
@Composable
private fun ProfileSearchMidnightCategoryOrderPreview() {
    FrogReaderTheme(AppTheme.OLED) {
        ProfileSearchContent(state = sampleEnglishSearchState(categoryOrderExpanded = true))
    }
}

@Preview(name = "Light · Profile search results", widthDp = 393, heightDp = 1040, showSystemUi = false)
@Composable
private fun ProfileSearchLightPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        ProfileSearchContent(state = sampleEnglishSearchState(categoryOrderExpanded = false))
    }
}

@Preview(name = "Beige · Profile search results", widthDp = 393, heightDp = 1040, showSystemUi = false)
@Composable
private fun ProfileSearchBeigePreview() {
    FrogReaderTheme(AppTheme.SEPIA) {
        ProfileSearchContent(state = sampleEnglishSearchState(categoryOrderExpanded = true))
    }
}

@Preview(
    name = "Beige · 360dp · Large text",
    widthDp = 360,
    heightDp = 1120,
    fontScale = 1.35f,
    showSystemUi = false,
)
@Composable
private fun ProfileSearchNarrowLargeTextPreview() {
    FrogReaderTheme(AppTheme.SEPIA) {
        ProfileSearchContent(state = sampleEnglishSearchState(categoryOrderExpanded = false))
    }
}

@Preview(name = "Midnight · RTL Arabic / Hebrew / Ukrainian", widthDp = 393, heightDp = 960, showSystemUi = false)
@Composable
private fun ProfileSearchMultilingualRtlPreview() {
    val query = "زمان"
    val arabicQuote = "القِرَاءَةُ تَمْنَحُنَا زَمَانًا لِلتَّأَمُّلِ وَالسَّكِينَةِ."
    val arabicNote = "خَصِّصْ زَمَانًا كُلَّ صَبَاحٍ."
    val hebrewTitle = "קִיצּוּר תּוֹלְדוֹת הַזְּמַן"
    val ukrainianTitle = "П’ять хвилин для читання"

    val results = ProfileSearchResults(
        query = query,
        quotes = listOf(
            QuoteSearchHit(
                quoteId = "q_ar",
                bookId = "b_ar",
                bookTitle = "فن القراءة",
                bookFormat = BookFormat.PDF,
                hasBookFile = true,
                chapterIndex = 0,
                documentLocation = DocumentLocation.Pdf(pageIndex = 41),
                quoteColor = QuoteColor.AMBER,
                quoteText = arabicQuote,
                quoteSpans = SearchMatchHighlighter.findMatchSpans(arabicQuote, query),
                noteText = arabicNote,
                noteSpans = SearchMatchHighlighter.findMatchSpans(arabicNote, query),
            ),
        ),
        readingQueue = listOf(
            ReadingQueueSearchHit(
                entryId = "rq_he",
                title = hebrewTitle,
                titleSpans = SearchMatchHighlighter.findMatchSpans(hebrewTitle, "זמן"),
                author = "סטיבן הוקינג",
                authorSpans = emptyList(),
                noteSnippet = null,
                noteSpans = emptyList(),
            ),
        ),
        readingLog = listOf(
            ReadingLogSearchHit(
                entryId = "rl_uk",
                title = ukrainianTitle,
                titleSpans = SearchMatchHighlighter.findMatchSpans(ukrainianTitle, "п'ять"),
                author = "Сергій Жадан",
                authorSpans = emptyList(),
                series = "",
                seriesSpans = emptyList(),
                status = ReadingLogStatus.FINISHED,
                reviewSnippet = null,
                reviewSpans = emptyList(),
            ),
        ),
    )

    FrogReaderTheme(AppTheme.OLED) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ProfileSearchContent(
                state = ProfileSearchUiState(
                    query = query,
                    results = results,
                ),
            )
        }
    }
}

private fun sampleEnglishSearchState(categoryOrderExpanded: Boolean): ProfileSearchUiState {
    val q = "time"
    val q1 = "Reading gives us time to pause and think."
    val q2 = "Begin with one small step."
    val q2Note = "Make time to read every morning."
    val q3 = "Quiet time turns pages into memories."
    val bm = "…how we choose to spend our time…"
    val queueTitle = "A Brief History of Time"
    val logTitle = "The Time Machine"
    val achTitle = "Time Well Spent"
    val achDesc = "Read for 10 hours in total"

    return ProfileSearchUiState(
        query = q,
        isCategoryOrderExpanded = categoryOrderExpanded,
        results = ProfileSearchResults(
            query = q,
            quotes = listOf(
                QuoteSearchHit(
                    quoteId = "1",
                    bookId = "b1",
                    bookTitle = "The Art of Reading",
                    bookFormat = BookFormat.PDF,
                    hasBookFile = true,
                    chapterIndex = 0,
                    documentLocation = DocumentLocation.Pdf(pageIndex = 41),
                    quoteColor = QuoteColor.AMBER,
                    quoteText = q1,
                    quoteSpans = SearchMatchHighlighter.findMatchSpans(q1, q),
                    noteText = null,
                    noteSpans = emptyList(),
                ),
                QuoteSearchHit(
                    quoteId = "2",
                    bookId = "b2",
                    bookTitle = "Small Daily Habits",
                    bookFormat = BookFormat.PDF,
                    hasBookFile = true,
                    chapterIndex = 0,
                    documentLocation = DocumentLocation.Pdf(pageIndex = 17),
                    quoteColor = QuoteColor.DEFAULT,
                    quoteText = q2,
                    quoteSpans = SearchMatchHighlighter.findMatchSpans(q2, q),
                    noteText = q2Note,
                    noteSpans = SearchMatchHighlighter.findMatchSpans(q2Note, q),
                ),
                QuoteSearchHit(
                    quoteId = "3",
                    bookId = "b1",
                    bookTitle = "The Art of Reading",
                    bookFormat = BookFormat.PDF,
                    hasBookFile = true,
                    chapterIndex = 0,
                    documentLocation = DocumentLocation.Pdf(pageIndex = 88),
                    quoteColor = QuoteColor.BLUE,
                    quoteText = q3,
                    quoteSpans = SearchMatchHighlighter.findMatchSpans(q3, q),
                    noteText = null,
                    noteSpans = emptyList(),
                ),
            ),
            bookmarks = listOf(
                BookmarkSearchHit(
                    bookmarkId = "bm1",
                    bookId = "b1",
                    bookTitle = "The Art of Reading",
                    bookFormat = BookFormat.PDF,
                    hasBookFile = true,
                    chapterIndex = 0,
                    documentLocation = DocumentLocation.Pdf(pageIndex = 64),
                    orphaned = false,
                    snippetText = bm,
                    snippetSpans = SearchMatchHighlighter.findMatchSpans(bm, q),
                ),
            ),
            readingQueue = listOf(
                ReadingQueueSearchHit(
                    entryId = "rq1",
                    title = queueTitle,
                    titleSpans = SearchMatchHighlighter.findMatchSpans(queueTitle, q),
                    author = "Stephen Hawking",
                    authorSpans = emptyList(),
                    noteSnippet = null,
                    noteSpans = emptyList(),
                ),
            ),
            readingLog = listOf(
                ReadingLogSearchHit(
                    entryId = "rl1",
                    title = logTitle,
                    titleSpans = SearchMatchHighlighter.findMatchSpans(logTitle, q),
                    author = "H. G. Wells",
                    authorSpans = emptyList(),
                    series = "",
                    seriesSpans = emptyList(),
                    status = ReadingLogStatus.FINISHED,
                    reviewSnippet = null,
                    reviewSpans = emptyList(),
                ),
            ),
            achievements = listOf(
                AchievementSearchHit(
                    achievement = ResolvedAchievement(
                        id = "time_well_spent",
                        title = achTitle,
                        description = achDesc,
                        progressLabel = "10 hours read",
                        targetValue = 10L,
                        currentValue = 10L,
                        unlocked = true,
                        unlockedAtMillis = 1_700_000_000_000L,
                        displayOrder = 3,
                    ),
                    titleSpans = SearchMatchHighlighter.findMatchSpans(achTitle, q),
                    descriptionSpans = SearchMatchHighlighter.findMatchSpans(achDesc, q),
                ),
            ),
        ),
        categoryOrder = ProfileSearchCategory.DEFAULT_ORDER,
    )
}
