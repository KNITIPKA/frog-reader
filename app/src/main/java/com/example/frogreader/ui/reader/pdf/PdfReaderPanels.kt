package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.components.FrogSegmentedControl
import com.example.frogreader.ui.profile.sortedQuotes
import com.example.frogreader.ui.reader.QuoteColorChoices
import com.example.frogreader.ui.reader.ReaderColors
import com.example.frogreader.ui.reader.bookmarkDocumentLocation
import com.example.frogreader.ui.reader.quoteDocumentLocation
import com.example.frogreader.ui.theme.floatingSurface

/** One-based input is converted only to a genuine PDF page, never a chapter. */
internal fun pdfPageIndexFromInput(input: String, pageCount: Int): Int? =
    input.trim().toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)

/** Pick the closest preceding target; source order breaks ties between headings on one page. */
internal fun currentPdfOutlineIndex(entries: List<PdfOutlineEntry>, currentPage: Int, pageCount: Int): Int {
    var currentIndex = -1
    var closestPage = -1
    entries.forEachIndexed { index, entry ->
        val page = entry.pageIndex
        if (page != null && page in 0 until pageCount && page <= currentPage && page >= closestPage) {
            currentIndex = index
            closestPage = page
        }
    }
    return currentIndex
}

/** Tabs shared by the drawer panels; scrolls instead of truncating at large font scales. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PdfPanelTabs(labels: List<Int>, selectedTab: Int, colors: ReaderColors, onSelect: (Int) -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (largeText && (labels.size > 2 || maxWidth < 360.dp)) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, containerColor = colors.control,
                scrollState = rememberScrollState(initial = if (selectedTab > 0) Int.MAX_VALUE else 0),
                contentColor = colors.onChrome, edgePadding = 4.dp, divider = {}, indicator = {},
                modifier = Modifier.clip(RoundedCornerShape(24.dp))) {
                labels.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { onSelect(index) },
                        selectedContentColor = colors.onSelectedControl, unselectedContentColor = colors.onChromeVariant,
                        text = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (selectedTab == index) colors.selectedControl else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        } else {
            FrogSegmentedControl(
                count = labels.size, selectedIndex = selectedTab, onSelect = onSelect,
                containerColor = colors.control, indicatorColor = colors.selectedControl,
                contentColor = colors.onChromeVariant, selectedContentColor = colors.onSelectedControl,
                modifier = Modifier.fillMaxWidth(), minItemHeight = 44.dp,
            ) { index, _, color ->
                Text(stringResource(labels[index]), color = color, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/** Compatibility helper for previews and tests. */
@Composable
internal fun PdfReaderPanels(
    book: Book,
    pageCount: Int,
    currentPage: Int,
    colors: ReaderColors,
    onNavigate: (DocumentLocation.Pdf) -> Unit,
    onBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveQuote: (String) -> Unit,
    onCopy: (String) -> Unit,
    onQuoteNote: ((String, String) -> Unit)? = null,
    onQuoteColor: ((String, QuoteColor) -> Unit)? = null,
    initialTab: Int = 0,
    onPageNavigate: (Int) -> Unit = { onNavigate(DocumentLocation.Pdf(it)) },
    outlineState: PdfOutlineState = PdfOutlineState.Idle,
    onLoadOutline: () -> Unit = {},
    onRetryOutline: () -> Unit = {},
) {
    if (initialTab <= 1) {
        PdfContentsPanel(
            bookId = book.id,
            pageCount = pageCount,
            currentPage = currentPage,
            colors = colors,
            onPageNavigate = onPageNavigate,
            outlineState = outlineState,
            onLoadOutline = onLoadOutline,
            onRetryOutline = onRetryOutline,
            initialTab = initialTab,
        )
    } else {
        PdfSavedPanel(
            book = book,
            pageCount = pageCount,
            currentPage = currentPage,
            colors = colors,
            onNavigate = onNavigate,
            onBookmark = onBookmark,
            onRemoveBookmark = onRemoveBookmark,
            onRemoveQuote = onRemoveQuote,
            onCopy = onCopy,
            onQuoteNote = onQuoteNote,
            onQuoteColor = onQuoteColor,
            initialTab = when (initialTab) { 2 -> 2; 4 -> 1; else -> 0 },
        )
    }
}

/** Contents drawer: the embedded outline, and page thumbnails (not implemented yet). */
@Composable
internal fun PdfContentsPanel(
    bookId: String,
    pageCount: Int,
    currentPage: Int,
    colors: ReaderColors,
    onPageNavigate: (Int) -> Unit,
    outlineState: PdfOutlineState = PdfOutlineState.Idle,
    onLoadOutline: () -> Unit = {},
    onRetryOutline: () -> Unit = {},
    initialTab: Int = 0,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable(bookId) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    LaunchedEffect(bookId, selectedTab) {
        if (selectedTab == 0) onLoadOutline()
    }
    Column(modifier.fillMaxWidth()) {
        PdfPanelTabs(listOf(R.string.pdf_panel_chapters, R.string.pdf_panel_pages), selectedTab, colors) { selectedTab = it }
        when (selectedTab) {
            0 -> PdfContents(outlineState, pageCount, currentPage, colors, onPageNavigate, onRetryOutline)
            else -> PdfPagePreviewsPlaceholder(colors)
        }
    }
}

@Composable
private fun PdfPagePreviewsPlaceholder(colors: ReaderColors) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = colors.control, contentColor = colors.onChromeVariant, shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Rounded.GridView, null, Modifier.padding(16.dp).size(28.dp))
        }
        Text(stringResource(R.string.pdf_page_previews_title), color = colors.onChrome,
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.pdf_page_previews_soon), color = colors.onChromeVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** PDF passages stay in one saved collection, with separate views for highlights and notes. */
@Composable
internal fun PdfSavedPanel(
    book: Book,
    pageCount: Int,
    currentPage: Int,
    colors: ReaderColors,
    onNavigate: (DocumentLocation.Pdf) -> Unit,
    onBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveQuote: (String) -> Unit,
    onCopy: (String) -> Unit,
    onQuoteNote: ((String, String) -> Unit)? = null,
    onQuoteColor: ((String, QuoteColor) -> Unit)? = null,
    initialTab: Int = 0,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable(book.id) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val highlightsList = rememberLazyListState()
    val notesList = rememberLazyListState()
    val passages = remember(book.quotes, selectedTab) {
        sortedQuotes(book.quotes.filter { !it.note.isNullOrBlank() == (selectedTab == 1) }, false)
    }
    Column(modifier.fillMaxWidth().imePadding()) {
        PdfPanelTabs(listOf(R.string.pdf_saved_highlights, R.string.pdf_saved_notes, R.string.pdf_panel_bookmarks), selectedTab, colors) { selectedTab = it }
        when (selectedTab) {
            0, 1 -> LazyColumn(
                state = if (selectedTab == 1) notesList else highlightsList,
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (passages.isEmpty()) item("empty") {
                    PdfEmptyHint(if (selectedTab == 1) R.string.pdf_saved_notes_empty else R.string.pdf_saved_highlights_empty, colors)
                }
                items(passages, key = { "quote:${it.id}" }) { quote ->
                    val location = (quoteDocumentLocation(book, quote) as? DocumentLocation.Pdf)
                        ?.takeIf { it.pageIndex in 0 until pageCount }
                    PdfSavedQuote(quote, location, colors, onNavigate, onCopy, onRemoveQuote, onQuoteNote, onQuoteColor)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item("bookmark-current-action") {
                    val bookmarked = book.bookmarks.any {
                        (bookmarkDocumentLocation(book, it) as? DocumentLocation.Pdf)?.pageIndex == currentPage
                    }
                    Surface(onClick = onBookmark, color = colors.control, contentColor = colors.onChrome,
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                null, tint = colors.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(if (bookmarked) R.string.reader_bookmark_remove_here else R.string.reader_bookmark_here),
                                style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                if (book.bookmarks.isEmpty()) item("empty") { PdfEmptyHint(R.string.reader_bookmarks_empty, colors) }
                items(book.bookmarks, key = { "bookmark:${it.id}" }) { bookmark ->
                    val location = (bookmarkDocumentLocation(book, bookmark) as? DocumentLocation.Pdf)
                        ?.takeIf { it.pageIndex in 0 until pageCount }
                    Surface(color = colors.controlButton, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = location != null) { location?.let(onNavigate) }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.BookmarkBorder, null, tint = colors.onChromeVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                PdfLocationLabel(location, colors, colors.onChrome, prominent = true)
                                if (bookmark.preview.isNotBlank()) Text(bookmark.preview,
                                    color = colors.onChromeVariant, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (location != null) Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null,
                                tint = colors.onChromeVariant, modifier = Modifier.size(16.dp))
                            IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.reader_remove), tint = colors.onChromeVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfContents(
    state: PdfOutlineState,
    pageCount: Int,
    currentPage: Int,
    colors: ReaderColors,
    onNavigate: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        PdfOutlineState.Idle, PdfOutlineState.Loading -> Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
            Text(stringResource(R.string.pdf_outline_loading), color = colors.onChrome,
                style = MaterialTheme.typography.bodyMedium)
        }
        PdfOutlineState.Error -> Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pdf_outline_error), color = colors.onChrome,
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                Text(stringResource(R.string.pdf_outline_retry))
            }
        }
        is PdfOutlineState.Ready -> {
            val outline = state.outline
            val currentIndex = remember(outline.entries, currentPage, pageCount) {
                currentPdfOutlineIndex(outline.entries, currentPage, pageCount)
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.25f
                val indent = if (compact) 8.dp else 12.dp
                val maximumDepth = if (compact) 3 else 4
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (outline.entries.isEmpty()) item("empty") {
                        PdfEmptyHint(R.string.pdf_outline_empty, colors)
                    }
                    items(outline.entries.size, key = { "outline:$it" }) { index ->
                        val entry = outline.entries[index]
                        val page = entry.pageIndex?.takeIf { it in 0 until pageCount }
                        val current = index == currentIndex
                        val ink = if (current) colors.onSelectedControl else colors.onChrome
                        val captionInk = if (current) colors.onSelectedControl else colors.onChromeVariant
                        Surface(color = if (current) colors.selectedControl else colors.controlButton,
                            contentColor = ink, shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = indent * entry.depth.coerceIn(0, maximumDepth))) {
                            Column(Modifier.fillMaxWidth()
                                .semantics { selected = current }
                                .then(if (page != null) Modifier.clickable { onNavigate(page) } else Modifier)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title.ifBlank { stringResource(R.string.pdf_outline_untitled) },
                                    color = ink, style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (current || entry.depth == 0) FontWeight.SemiBold else FontWeight.Normal)
                                if (page != null) Text(
                                    stringResource(if (current) R.string.pdf_outline_current_page else R.string.saved_passage_pdf_page,
                                        page + 1), color = captionInk, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (outline.truncated) item("limited") {
                        PdfEmptyHint(R.string.pdf_outline_truncated, colors)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfSavedQuote(
    quote: Quote, location: DocumentLocation.Pdf?, colors: ReaderColors,
    onNavigate: (DocumentLocation.Pdf) -> Unit, onCopy: (String) -> Unit, onRemove: (String) -> Unit,
    onQuoteNote: ((String, String) -> Unit)?, onQuoteColor: ((String, QuoteColor) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var editingNote by rememberSaveable(quote.id) { mutableStateOf(false) }
    var note by rememberSaveable(quote.id) { mutableStateOf(quote.note.orEmpty()) }
    var pendingNote by rememberSaveable(quote.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(quote.note, pendingNote) {
        if (pendingNote != null && quote.note.orEmpty() == pendingNote) {
            editingNote = false
            pendingNote = null
        }
    }
    Surface(color = colors.controlButton, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PdfLocationLabel(location, colors,
                        if (quote.note.isNullOrBlank()) colors.onChrome else colors.onChromeVariant)
                }
                Box(Modifier.size(12.dp).background(colors.quoteInk(quote.color), CircleShape))
                Box {
                    IconButton(onClick = { colorMenu = false; menu = true }) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.quotes_bookmarks_actions), tint = colors.onChromeVariant)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.floatingSurface) {
                        if (colorMenu && onQuoteColor != null) {
                            QuoteColorChoices(colors, QuoteColor.entries) { color -> onQuoteColor(quote.id, color); menu = false }
                        } else {
                            DropdownMenuItem(text = { Text(stringResource(R.string.selection_copy)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = { menu = false; onCopy(quote.text) })
                            if (onQuoteNote != null) DropdownMenuItem(
                                text = { Text(stringResource(if (quote.note.isNullOrBlank()) R.string.pdf_saved_add_note else R.string.pdf_saved_edit_note)) },
                                leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                                onClick = { note = quote.note.orEmpty(); pendingNote = null; editingNote = true; menu = false })
                            if (onQuoteColor != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_panel_quote_color)) },
                                leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = { colorMenu = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_remove)) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                onClick = { menu = false; onRemove(quote.id) })
                        }
                    }
                }
            }
            Text(quote.text, color = colors.onChrome, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable(enabled = location != null) { location?.let(onNavigate) }
                    .padding(bottom = 4.dp))
            if (editingNote && onQuoteNote != null) {
                OutlinedTextField(note, { note = it.take(20_000); pendingNote = null },
                    label = { Text(stringResource(R.string.pdf_saved_note_label)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, shape = RoundedCornerShape(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingNote = false; pendingNote = null }) { Text(stringResource(R.string.backup_cancel)) }
                    TextButton(onClick = {
                        val clean = note.trim()
                        if (clean == quote.note.orEmpty()) editingNote = false else {
                            pendingNote = clean
                            onQuoteNote(quote.id, clean)
                        }
                    }) { Text(stringResource(R.string.edit_save)) }
                }
            } else if (!quote.note.isNullOrBlank()) {
                HorizontalDivider(color = colors.track, modifier = Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.EditNote, stringResource(R.string.pdf_saved_note_label),
                        tint = colors.onChromeVariant, modifier = Modifier.size(22.dp))
                    Text(quote.note, color = colors.onChrome, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PdfLocationLabel(location: DocumentLocation.Pdf?, colors: ReaderColors,
    ink: androidx.compose.ui.graphics.Color = colors.accent, prominent: Boolean = false) {
    Text(if (location != null) stringResource(R.string.saved_passage_pdf_page, location.pageIndex + 1)
        else stringResource(R.string.saved_passage_location_unavailable),
        color = if (location != null) ink else colors.onChromeVariant,
        style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
}

@Composable
private fun PdfEmptyHint(resource: Int, colors: ReaderColors) {
    Text(stringResource(resource), color = colors.onChromeVariant, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(16.dp))
}

