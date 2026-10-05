package com.example.frogreader.ui.profile

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.theme.floatingSurface
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun QuotesAndBookmarksScreen(
    onBack: () -> Unit,
    onOpenQuoteOrBookmark: (bookId: String, quoteId: String?, bookmarkId: String?) -> Unit,
    onBookInfo: (String) -> Unit,
) {
    val model: QuotesAndBookmarksViewModel = viewModel(factory = QuotesAndBookmarksViewModel.Factory)
    val books by model.books.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(model) {
        model.events.collectLatest { event ->
            when (event) {
                QuotesAndBookmarksEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.quotes_bookmarks_write_error))
                is QuotesAndBookmarksEvent.Deleted -> {
                    val result = snackbar.showSnackbar(
                        context.getString(if (event.quote != null) R.string.quotes_bookmarks_quote_deleted else R.string.quotes_bookmarks_bookmark_deleted),
                        actionLabel = context.getString(R.string.quotes_bookmarks_undo),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) model.undo(event)
                }
            }
        }
    }
    QuotesAndBookmarksContent(
        books = books, coverOf = model::coverFor, editor = editor, busy = busy, snackbar = snackbar,
        onBack = onBack, onOpenQuoteOrBookmark = onOpenQuoteOrBookmark, onBookInfo = onBookInfo,
        onEdit = model::edit, onThoughtChange = model::changeThought,
        onSaveThought = model::saveThought, onDiscardThought = model::discardThought,
        onDeleteQuote = model::deleteQuote, onDeleteBookmark = model::deleteBookmark,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuotesAndBookmarksContent(
    books: List<Book>,
    coverOf: (Book) -> File? = { null },
    editor: QuoteThoughtEditor? = null,
    busy: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    initialBookId: String? = null,
    initialTab: Int? = null,
    onBack: () -> Unit = {},
    onOpenQuoteOrBookmark: (String, String?, String?) -> Unit = { _, _, _ -> },
    onBookInfo: (String) -> Unit = {},
    onEdit: (Book, Quote) -> Unit = { _, _ -> },
    onThoughtChange: (String) -> Unit = {},
    onSaveThought: () -> Unit = {},
    onDiscardThought: () -> Unit = {},
    onDeleteQuote: (Book, Quote) -> Unit = { _, _ -> },
    onDeleteBookmark: (Book, Bookmark) -> Unit = { _, _ -> },
) {
    var selectedBookId by rememberSaveable { mutableStateOf(initialBookId) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var librarySearchVisible by rememberSaveable { mutableStateOf(false) }
    var bookQuery by rememberSaveable(selectedBookId) { mutableStateOf("") }
    var bookSearchVisible by rememberSaveable(selectedBookId) { mutableStateOf(false) }
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    var leaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val selectedBook = books.firstOrNull { it.id == selectedBookId }
    val query = if (selectedBook == null) libraryQuery else bookQuery
    val searchVisible = if (selectedBook == null) librarySearchVisible else bookSearchVisible
    val colors = MaterialTheme.colorScheme
    val collection = remember(books, libraryQuery) { booksWithQuotesAndBookmarks(books, libraryQuery) }
    val bookListState = rememberLazyListState()

    fun changeQuery(value: String) {
        if (selectedBook == null) libraryQuery = value else bookQuery = value
    }
    fun toggleSearch() {
        if (selectedBook == null) { librarySearchVisible = !librarySearchVisible; libraryQuery = "" }
        else { bookSearchVisible = !bookSearchVisible; bookQuery = "" }
    }

    fun leaveEditor(action: () -> Unit) {
        if (editor?.saving == true) return
        if (editor?.dirty == true) leaveAction = action
        else { onDiscardThought(); action() }
    }
    val goBack: () -> Unit = {
        leaveEditor {
            if (selectedBookId != null) selectedBookId = null
            else if (searchVisible) toggleSearch()
            else onBack()
        }
    }
    BackHandler { goBack() }

    Scaffold(
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_quotes_bookmarks), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = goBack, enabled = editor?.saving != true) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_back))
                    }
                },
                actions = {
                    IconButton(onClick = { leaveEditor { toggleSearch() } }) {
                        Icon(if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                            stringResource(if (searchVisible) R.string.quotes_bookmarks_close_search else R.string.quotes_bookmarks_search))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (searchVisible) {
                OutlinedTextField(
                    value = query, onValueChange = { changeQuery(it) }, singleLine = true,
                    enabled = editor == null,
                    placeholder = { Text(stringResource(if (selectedBook == null) R.string.quotes_bookmarks_search_hint else R.string.quotes_bookmarks_search_book_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {{
                        IconButton(onClick = { changeQuery("") }, enabled = editor == null) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.quotes_bookmarks_clear_search))
                        }
                    }} else null,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                )
            }
            AnimatedContent(
                targetState = selectedBook, contentKey = { it?.id }, label = "saved book",
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { book ->
                if (book == null) {
                    LazyColumn(
                        state = bookListState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (collection.isNotEmpty()) item("heading") {
                            CollectionLabel(stringResource(R.string.quotes_bookmarks_books_label))
                        }
                        if (collection.isEmpty()) item("empty") {
                            EmptyQuotesAndBookmarks(libraryQuery, R.string.quotes_bookmarks_empty_collection, R.string.quotes_bookmarks_empty_collection_hint)
                        }
                        items(collection, key = { it.id }) { savedBook ->
                            SavedBookCard(savedBook, coverOf) { selectedBookId = savedBook.id }
                        }
                    }
                } else key(book.id) {
                    val annotations = remember(book, bookQuery, newestFirst) {
                        requireNotNull(bookQuotesAndBookmarks(listOf(book), book.id, bookQuery, newestFirst))
                    }
                    val pager = rememberPagerState(initialPage = initialTab ?: if (book.quotes.isEmpty()) 1 else 0) { 2 }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize()) {
                        BookQuotesAndBookmarksHeader(book, coverOf)
                        QuotesAndBookmarksTabs(pager.currentPage, annotations.quoteCount, annotations.bookmarkCount) { index ->
                            leaveEditor { scope.launch { pager.animateScrollToPage(index) } }
                        }
                        HorizontalPager(
                            state = pager, userScrollEnabled = editor == null,
                            modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Top,
                        ) { page ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (book.fileName == null) item("missing") {
                                    MissingBookHint { leaveEditor { onBookInfo(book.id) } }
                                }
                                if (page == 0) {
                                    if (annotations.quotes.isNotEmpty()) item("sort") {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(
                                                onClick = { leaveEditor { newestFirst = !newestFirst } },
                                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                                            ) {
                                                Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(if (newestFirst) R.string.quotes_bookmarks_newest_first else R.string.quotes_bookmarks_book_order))
                                            }
                                        }
                                    }
                                    if (annotations.quotes.isEmpty()) item("empty") {
                                        EmptyQuotesAndBookmarks(bookQuery, R.string.quotes_bookmarks_empty_book_quotes, R.string.quotes_bookmarks_empty_quotes_hint)
                                    }
                                    items(annotations.quotes, key = { "quote:${it.id}" }) { quote ->
                                        QuoteCard(
                                            book, quote, editor?.takeIf { it.bookId == book.id && it.quoteId == quote.id },
                                            busy = busy || editor?.saving == true,
                                            onEdit = { leaveEditor { onEdit(book, quote) } },
                                            onThoughtChange, onSaveThought,
                                            onCancel = { leaveEditor {} },
                                            onOpen = { leaveEditor { onOpenQuoteOrBookmark(book.id, quote.id, null) } },
                                            onDelete = { leaveEditor { onDeleteQuote(book, quote) } },
                                        )
                                    }
                                } else {
                                    if (annotations.bookmarks.isNotEmpty()) item("heading") {
                                        CollectionLabel(stringResource(R.string.quotes_bookmarks_newest_first))
                                    }
                                    if (annotations.bookmarks.isEmpty()) item("empty") {
                                        EmptyQuotesAndBookmarks(bookQuery, R.string.quotes_bookmarks_empty_book_bookmarks, R.string.quotes_bookmarks_empty_bookmarks_hint)
                                    }
                                    items(annotations.bookmarks, key = { "bookmark:${it.bookmark.id}" }) { entry ->
                                        BookmarkCard(
                                            entry, coverOf, busy,
                                            onOpen = { onOpenQuoteOrBookmark(book.id, null, entry.bookmark.id) },
                                            onBookInfo = { onBookInfo(book.id) },
                                            onDelete = { onDeleteBookmark(book, entry.bookmark) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }
    if (leaveAction != null) {
        AlertDialog(
            onDismissRequest = { leaveAction = null },
            title = { Text(stringResource(R.string.quotes_bookmarks_discard_title)) },
            text = { Text(stringResource(R.string.quotes_bookmarks_discard_message)) },
            confirmButton = { TextButton(onClick = {
                val action = leaveAction
                leaveAction = null
                onDiscardThought()
                action?.invoke()
            }) { Text(stringResource(R.string.quotes_bookmarks_discard)) } },
            dismissButton = { TextButton(onClick = { leaveAction = null }) { Text(stringResource(R.string.quotes_bookmarks_keep_editing)) } },
            containerColor = colors.floatingSurface,
        )
    }
}

@Composable
private fun QuotesAndBookmarksTabs(selected: Int, quoteCount: Int, bookmarkCount: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainer, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp),
    ) {
        Row(Modifier.selectableGroup().padding(4.dp)) {
            listOf(R.string.reader_quotes to quoteCount, R.string.reader_bookmarks to bookmarkCount)
                .forEachIndexed { index, (label, count) ->
                    Tab(
                        selected = selected == index, onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (selected == index) colors.primary else colors.surfaceContainer),
                        selectedContentColor = colors.onPrimary,
                        unselectedContentColor = colors.onSurfaceVariant,
                        text = {
                            Text(stringResource(R.string.quotes_bookmarks_tab_count, stringResource(label), count),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2)
                        },
                    )
                }
        }
    }
}

@Composable
private fun CollectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
}

@Composable
private fun AnnotationBookCover(book: Book, coverOf: (Book) -> File?, width: Dp = 42.dp, height: Dp = 60.dp) {
    val file = remember(book.coverFileName) { coverOf(book) }
    Box(Modifier.size(width, height).clip(RoundedCornerShape(6.dp))) {
        BookCover(book, file, titleSize = 7.sp, padding = 5.dp)
    }
}

@Composable
private fun SavedBookCard(book: Book, coverOf: (Book) -> File?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AnnotationBookCover(book, coverOf)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(author, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val quotes = pluralStringResource(R.plurals.quotes_bookmarks_quotes_count, book.quotes.size, book.quotes.size)
                val bookmarks = pluralStringResource(R.plurals.quotes_bookmarks_bookmarks_count, book.bookmarks.size, book.bookmarks.size)
                Text(stringResource(R.string.quotes_bookmarks_book_counts, quotes, bookmarks),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BookQuotesAndBookmarksHeader(book: Book, coverOf: (Book) -> File?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AnnotationBookCover(book, coverOf)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuoteCard(
    book: Book, quote: Quote, editor: QuoteThoughtEditor?, busy: Boolean,
    onEdit: () -> Unit, onThoughtChange: (String) -> Unit, onSave: () -> Unit,
    onCancel: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(quote.id) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FormatQuote, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Text(stringResource(R.string.reader_chapter_n, quote.chapterIndex + 1),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.quotes_bookmarks_actions)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = colors.floatingSurface) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_copy)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(book.title, quote.text))) }
                                showMenu = false
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_info_share)) },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                showMenu = false
                                val author = book.author?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: context.getString(R.string.import_author_unknown)
                                val text = buildString {
                                    append(quote.text.trimEnd())
                                    append("\n\n— ")
                                    append(book.title.trim())
                                    append('\n')
                                    append(author)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                                    }, null))
                                } catch (_: android.content.ActivityNotFoundException) { shareFailed = true }
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.quotes_bookmarks_open_in_book)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) },
                            enabled = book.fileName != null && quote.startItem >= 0 && quote.startChar >= 0 && !busy,
                            onClick = { showMenu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_font_delete), color = colors.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) }, enabled = !busy,
                            onClick = { showMenu = false; onDelete() })
                    }
                }
            }
            var hasOverflow by remember(quote.text) { mutableStateOf(false) }
            Text(quote.text, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 27.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow })
            Text(
                listOfNotNull(book.title, book.author?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (hasOverflow || expanded) TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                ) {
                    Text(stringResource(if (expanded) R.string.quotes_bookmarks_show_less else R.string.quotes_bookmarks_read_more))
                    Spacer(Modifier.width(4.dp))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                }
                if (editor == null && quote.note.isNullOrBlank()) TextButton(
                    onClick = onEdit, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(Icons.Rounded.EditNote, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.quotes_bookmarks_add_thoughts))
                }
            }
            if (quote.startItem < 0 || quote.startChar < 0) {
                Text(stringResource(R.string.quotes_bookmarks_unlinked_quote), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (shareFailed) Text(stringResource(R.string.quotes_bookmarks_share_error), color = colors.error, style = MaterialTheme.typography.bodySmall)
            when {
                editor != null -> ThoughtEditor(editor, onThoughtChange, onSave, onCancel)
                !quote.note.isNullOrBlank() -> Surface(
                    shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLow,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.quotes_bookmarks_my_thoughts), style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton(onClick = onEdit, enabled = !busy) {
                                Icon(Icons.Rounded.EditNote, stringResource(R.string.quotes_bookmarks_edit_thoughts), tint = colors.primary)
                            }
                        }
                        Text(quote.note, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThoughtEditor(editor: QuoteThoughtEditor, onChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = editor.text, onValueChange = onChange, label = { Text(stringResource(R.string.quotes_bookmarks_my_thoughts)) },
            placeholder = { Text(stringResource(R.string.quotes_bookmarks_thought_hint)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
            enabled = !editor.saving, isError = editor.failed, shape = RoundedCornerShape(16.dp),
            supportingText = if (editor.failed) {{ Text(stringResource(R.string.quotes_bookmarks_thought_error)) }} else null,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !editor.saving) { Text(stringResource(R.string.backup_cancel)) }
            Button(onClick = onSave, enabled = !editor.saving, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )) {
                if (editor.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.edit_save))
            }
        }
    }
}

@Composable
private fun BookmarkCard(
    entry: SavedBookmark, coverOf: (Book) -> File?, busy: Boolean,
    onOpen: () -> Unit, onBookInfo: () -> Unit, onDelete: () -> Unit,
) {
    val (book, bookmark) = entry
    val colors = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }
    val canOpen = book.fileName != null && !bookmark.orphaned && bookmark.flatIndex >= 0
    Card(onClick = { if (canOpen) onOpen() else onBookInfo() }, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnnotationBookCover(book, coverOf, width = 34.dp, height = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.reader_chapter_n, bookmark.chapterIndex + 1),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(book.title, book.author?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.quotes_bookmarks_actions)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = colors.floatingSurface) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_font_delete), color = colors.error) }, enabled = !busy,
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) },
                            onClick = { showMenu = false; onDelete() })
                    }
                }
            }
            if (bookmark.preview.isNotBlank()) Text(bookmarkExcerpt(bookmark.preview), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
            if (!canOpen) Text(stringResource(if (book.fileName == null) R.string.quotes_bookmarks_missing_book else R.string.quotes_bookmarks_unlinked_bookmark),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun MissingBookHint(onBookInfo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.quotes_bookmarks_missing_book), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onBookInfo) { Text(stringResource(R.string.quotes_bookmarks_book_details)) }
        }
    }
}

@Composable
private fun EmptyQuotesAndBookmarks(query: String, title: Int, hint: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (query.isBlank()) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Search,
            null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (query.isBlank()) title else R.string.quotes_bookmarks_no_results), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (query.isBlank()) hint else R.string.quotes_bookmarks_no_results_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
