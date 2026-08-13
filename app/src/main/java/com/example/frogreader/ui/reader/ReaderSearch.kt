package com.example.frogreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ContentElement
import kotlinx.coroutines.yield

// ------------------------------------------------------------------- engine

/** One occurrence of the query inside the book text. */
class SearchMatch(
    val itemIndex: Int,
    val chapterIndex: Int,
    /** Character offset of the match inside the item's text. */
    val charStart: Int,
    /** Context around the match, split so the UI can style the hit. */
    val before: String,
    val match: String,
    val after: String,
)

class SearchResults(
    val query: String,
    val matches: List<SearchMatch>,
    /** Every occurrence in the book, even beyond the collected [matches]. */
    val totalCount: Int,
) {
    val truncated: Boolean get() = totalCount > matches.size
}

/** How many result rows are collected; occurrences beyond it are counted. */
const val SEARCH_RESULT_LIMIT = 300

/**
 * Scans the whole book for [query], case-insensitively. A plain text scan is
 * fast enough for multi-megabyte books (tens of milliseconds), so there is no
 * prebuilt index to keep in sync. Runs on a background dispatcher and yields
 * regularly so a newer query can cancel it instantly.
 */
suspend fun searchBook(
    items: List<ReaderItem>,
    query: String,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResults {
    val matches = ArrayList<SearchMatch>(64)
    var total = 0
    for ((index, item) in items.withIndex()) {
        if (index % 64 == 0) yield()
        val text = when (val element = item.element) {
            is ContentElement.Paragraph -> element.text.text
            is ContentElement.Heading -> element.text
            // A hit inside a table lands on the table's first page.
            is ContentElement.Table -> element.flatText()
            else -> continue
        }
        var from = 0
        while (true) {
            val hit = text.indexOf(query, from, ignoreCase = true)
            if (hit < 0) break
            total++
            if (matches.size < limit) {
                matches += snippetFor(item.chapterIndex, index, text, hit, hit + query.length)
            }
            from = hit + query.length
        }
    }
    return SearchResults(query, matches, total)
}

/** Cuts a word-aligned context window around the match. */
private fun snippetFor(
    chapterIndex: Int,
    itemIndex: Int,
    text: String,
    start: Int,
    end: Int,
): SearchMatch {
    var s = (start - 36).coerceAtLeast(0)
    if (s > 0) {
        // Never start mid-word: advance to the next space.
        val space = text.indexOf(' ', s)
        if (space in s until start) s = space + 1
    }
    var e = (end + 110).coerceAtMost(text.length)
    if (e < text.length) {
        val space = text.lastIndexOf(' ', e)
        if (space > end) e = space
    }
    val before = (if (s > 0) "…" else "") + text.substring(s, start).replace('\n', ' ')
    val after = text.substring(end, e).replace('\n', ' ') + (if (e < text.length) "…" else "")
    return SearchMatch(
        itemIndex = itemIndex,
        chapterIndex = chapterIndex,
        charStart = start,
        before = before,
        match = text.substring(start, end),
        after = after,
    )
}

// ----------------------------------------------------------------------- UI

/**
 * Full-screen search over the book: word matches with chapter and page, and
 * — when the query is a number — a direct "go to page" shortcut. Styled with
 * the reading theme so it feels like part of the book, not a separate app
 * screen.
 */
@Composable
fun ReaderSearchOverlay(
    visible: Boolean,
    query: String,
    colors: ReaderColors,
    chapterTitles: List<String?>,
    results: SearchResults?,
    /** Full pagination (never the partial chapter pass), or null. */
    pages: List<BookPage>?,
    onResultClick: (SearchMatch) -> Unit,
    /** 0-based page index the user asked to jump to. */
    onGoToPage: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = visible) {
        keyboard?.hide()
        onClose()
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Swallow taps AND drags so nothing reaches the reader
                // underneath (a horizontal swipe would turn pages unseen).
                .pointerInput(Unit) { detectTapGestures { } }
                .pointerInput(Unit) {
                    detectDragGestures(onDrag = { change, _ -> change.consume() })
                }
                .padding(top = topPadding),
        ) {
            val trimmed = query.trim()
            // Precompute the page of every match in one ordered merge pass:
            // matches and pages are both sorted by (item, char).
            val matchPages = remember(results, pages) {
                val matches = results?.matches
                if (matches == null || pages.isNullOrEmpty()) {
                    null
                } else {
                    var page = 0
                    matches.map { match ->
                        while (
                            page + 1 < pages.size &&
                            (
                                pages[page + 1].firstItemIndex < match.itemIndex ||
                                    (
                                        pages[page + 1].firstItemIndex == match.itemIndex &&
                                            pages[page + 1].firstCharOffset <= match.charStart
                                        )
                                )
                        ) {
                            page++
                        }
                        page
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                // "Go to page N" shortcut for numeric queries (paged mode).
                val pageQuery = trimmed.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
                    ?.toIntOrNull()
                if (pageQuery != null && pages != null) {
                    item(key = "page-jump") {
                        GoToPageCard(
                            pageQuery = pageQuery,
                            totalPages = pages.size,
                            colors = colors,
                            onGoToPage = {
                                keyboard?.hide()
                                onGoToPage(it)
                            },
                        )
                    }
                }

                val current = results
                when {
                    current != null && current.query == trimmed && current.matches.isEmpty() -> {
                        if (pageQuery == null) {
                            item(key = "empty") {
                                SearchHint(
                                    text = stringResource(R.string.reader_search_empty, trimmed),
                                    colors = colors,
                                )
                            }
                        }
                    }

                    current != null -> {
                        item(key = "count") {
                            val count = if (current.totalCount == 1) {
                                stringResource(R.string.reader_search_one_match)
                            } else {
                                stringResource(R.string.reader_search_matches, current.totalCount)
                            }
                            val label = if (current.truncated) {
                                "$count · " + stringResource(
                                    R.string.reader_search_truncated,
                                    current.matches.size,
                                )
                            } else {
                                count
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.secondaryText,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp),
                            )
                        }
                        itemsIndexed(current.matches) { index, match ->
                            SearchResultRow(
                                match = match,
                                chapterTitle = chapterTitles.getOrNull(match.chapterIndex),
                                page = matchPages?.getOrNull(index),
                                colors = colors,
                                onClick = {
                                    keyboard?.hide()
                                    onResultClick(match)
                                },
                            )
                        }
                    }

                    trimmed.length < 2 && pageQuery == null -> {
                        item(key = "idle") {
                            SearchHint(
                                text = stringResource(R.string.reader_search_hint),
                                colors = colors,
                                showIcon = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Accent card offering to jump straight to the typed page number. */
@Composable
private fun GoToPageCard(
    pageQuery: Int,
    totalPages: Int,
    colors: ReaderColors,
    onGoToPage: (Int) -> Unit,
) {
    val valid = pageQuery in 1..totalPages
    Surface(
        color = colors.accent.copy(alpha = if (valid) 0.14f else 0.07f),
        contentColor = colors.text,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .then(
                if (valid) {
                    Modifier.clickable { onGoToPage(pageQuery - 1) }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = colors.accent,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (valid) {
                    Text(
                        text = stringResource(R.string.reader_search_go_to_page, pageQuery),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.reader_search_of_pages, totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.reader_search_pages_range, totalPages),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.secondaryText,
                    )
                }
            }
            if (valid) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    match: SearchMatch,
    chapterTitle: String?,
    page: Int?,
    colors: ReaderColors,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (!chapterTitle.isNullOrBlank()) {
                Text(
                    text = chapterTitle.replace('\n', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = buildAnnotatedString {
                    append(match.before)
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            background = colors.accent.copy(alpha = 0.28f),
                        ),
                    ) {
                        append(match.match)
                    }
                    append(match.after)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (page != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${page + 1}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
            )
        }
    }
}

/** Centered hint / empty state below the field. */
@Composable
private fun SearchHint(
    text: String,
    colors: ReaderColors,
    showIcon: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
    ) {
        if (showIcon) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
