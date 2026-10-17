package com.example.frogreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DensityLarge
import androidx.compose.material.icons.rounded.DensitySmall
import androidx.compose.material.icons.rounded.FormatAlignJustify
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Superscript
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.components.FrogSegmentedControl
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.groupCustomFonts
import com.example.frogreader.data.storeBookFontFamily
import com.example.frogreader.ui.theme.customFontPreviewFamily
import com.example.frogreader.data.selectCustomFont
import com.example.frogreader.data.selectCustomFontFamily
import com.example.frogreader.data.refreshCustomFontFamily
import com.example.frogreader.data.removeCustomFontFiles
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.theme.customFontFamily
import com.example.frogreader.ui.theme.bundledFontFamilyForCss
import com.example.frogreader.ui.theme.publisherFontFamily
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contents / bookmarks / quotes tabs, shown inside the pull-up panel of the
 * bottom bar (same surface as the reader settings — no modal sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPanelsContent(
    colorScheme: ColorScheme,
    ready: ReaderState.Ready,
    book: Book?,
    currentChapter: Int,
    chapterStartPages: List<Int>?,
    /** Optional styling for the fixed tab header. */
    modifier: Modifier = Modifier,
    onChapterClick: (ReaderNavigationTarget) -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onCopyQuote: (String) -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onRemoveQuote: (String) -> Unit,
    /** Whether the page being read is bookmarked, and its toggle. */
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    /** Exact nav anchor when one spine chapter contains several subchapters. */
    currentNavigationIndex: Int? = null,
    /** Changes when the Contents button opens the panel again. */
    openRequest: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openRequest) { tab = 0 }

    // Match the reader surface even while the app-wide theme is transitioning.
    MaterialTheme(colorScheme = colorScheme) {
        Column(Modifier.fillMaxSize()) {
            val tabs: @Composable () -> Unit = {
                listOf(R.string.reader_contents, R.string.reader_bookmarks, R.string.reader_quotes)
                    .forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            unselectedContentColor = colorScheme.onSurfaceVariant,
                            text = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
            }
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 280.dp || fontScale > 1.25f) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tab,
                        containerColor = colorScheme.surfaceContainerLow,
                        contentColor = colorScheme.primary,
                        edgePadding = 0.dp,
                        divider = {},
                        modifier = modifier.clip(RoundedCornerShape(16.dp)),
                        tabs = tabs,
                    )
                } else {
                    PrimaryTabRow(
                        selectedTabIndex = tab,
                        containerColor = colorScheme.surfaceContainerLow,
                        contentColor = colorScheme.primary,
                        divider = {},
                        modifier = modifier.clip(RoundedCornerShape(16.dp)),
                        tabs = tabs,
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ContentsTab(ready, currentChapter, chapterStartPages, onChapterClick, currentNavigationIndex, openRequest)
                    1 -> BookmarksTab(
                        ready, book, bookmarked, onToggleBookmark,
                        onBookmarkClick, onRemoveBookmark,
                    )
                    else -> QuotesTab(ready, book, onCopyQuote, onQuoteClick, onRemoveQuote)
                }
            }
        }
    }
}

/**
 * The table of contents as a collapsible tree: parts contain books contain
 * chapters (chapter depths come from the file's own hierarchy). Group rows
 * expand/collapse their subtree with the chevron; every row still jumps to
 * its chapter, and the right edge shows the page it starts on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentsTab(
    ready: ReaderState.Ready,
    currentChapter: Int,
    chapterStartPages: List<Int>?,
    onChapterClick: (ReaderNavigationTarget) -> Unit,
    currentNavigationIndex: Int?,
    openRequest: Int,
) {
    val entries = ready.navigation
    val tree = remember(entries) { ReaderContentsTree(entries.map { it.depth }) }
    val currentEntryIndex = remember(entries, currentChapter, currentNavigationIndex) {
        currentNavigationIndex?.takeIf { it in entries.indices } ?: entries.indexOfFirst { entry ->
            (entry.target as? ReaderNavigationTarget.ReadingOrder)?.chapterIndex == currentChapter
        }.takeIf { it >= 0 }
    }
    val collapsed = remember(tree) {
        val expanded = currentEntryIndex?.let { tree.ancestorsOf(it) + it }.orEmpty().toSet()
        mutableStateMapOf<Int, Boolean>().apply {
            tree.isGroup.indices.forEach { index ->
                if (tree.isGroup[index]) put(index, index !in expanded)
            }
        }
    }
    val visibleIndices by remember(tree, collapsed) {
        derivedStateOf { tree.visibleIndices { collapsed[it] == true } }
    }
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = tree.visiblePositionOf(currentEntryIndex, visibleIndices),
    )
    var previousOpenRequest by remember(tree) { mutableIntStateOf(Int.MIN_VALUE) }
    LaunchedEffect(tree, currentEntryIndex, openRequest) {
        var revealedRows = false
        currentEntryIndex?.let { current ->
            (tree.ancestorsOf(current) + current).forEach { index ->
                if (tree.isGroup[index] && collapsed[index] != false) {
                    collapsed[index] = false
                    revealedRows = true
                }
            }
        }
        if (revealedRows) withFrameNanos { }
        val visible = tree.visibleIndices { collapsed[it] == true }
        if (visible.isEmpty()) return@LaunchedEffect
        val target = tree.visiblePositionOf(currentEntryIndex, visible)
        val opening = previousOpenRequest != openRequest
        previousOpenRequest = openRequest
        if (opening) {
            listState.scrollToItem(target)
        } else if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            // A tapped row stays under the finger. Only bring the current row
            // into view if navigation elsewhere moved it outside this viewport.
            listState.animateScrollToItem(target)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow),
    ) {
        if (!ready.contentComplete) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(24.dp), color = scheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.reader_loading_chapters),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            itemsIndexed(
                items = visibleIndices,
                key = { _, index -> index },
                contentType = { _, index -> if (tree.isGroup[index]) "group" else "chapter" },
            ) { visibleIndex, index ->
                val entry = entries[index]
                val group = tree.isGroup[index]
                val isCurrent = index == currentEntryIndex
                val previous = visibleIndices.getOrNull(visibleIndex - 1)
                val startsSection = group || previous != null && tree.parent[previous] != tree.parent[index]
                val title = entry.title ?: stringResource(R.string.reader_chapter_n, index + 1)
                val titleParts = remember(title, group) {
                    if (group) contentsGroupTitle(title) else null to title.replace('\n', ' ')
                }
                val rowBackground by animateColorAsState(
                    contentsRowBackground(scheme, current = isCurrent, group = group),
                    animationSpec = readerChapterSwitchSpec(),
                    label = "contentsCurrentRow",
                )
                // Spacing expresses hierarchy. A single rounded fill marks the
                // current location; no duplicate stripe, check badge or row grid.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 14.dp * tree.depth[index].coerceAtMost(4),
                            top = if (startsSection && visibleIndex > 0) 10.dp else 2.dp,
                            bottom = 2.dp,
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(rowBackground)
                        .semantics { selected = isCurrent }
                        .clickable { onChapterClick(entry.target) }
                        .heightIn(min = 48.dp)
                        .padding(start = 14.dp, end = if (group) 2.dp else 14.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = if (group) 14.dp else 12.dp)) {
                        titleParts.first?.let { prefix ->
                            Text(
                                text = prefix,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = titleParts.second,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent || group) FontWeight.SemiBold else FontWeight.Normal,
                            color = scheme.onSurface,
                        )
                    }
                    val page = (entry.target as? ReaderNavigationTarget.ReadingOrder)
                        ?.chapterIndex?.let { chapterStartPages?.getOrNull(it) }
                    if (page != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (group) {
                        val isCollapsed = collapsed[index] == true
                        val rotation by animateFloatAsState(if (isCollapsed) 0f else 180f, label = "contentsChevron")
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            collapsed[index] = !isCollapsed
                        }) {
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(
                                    if (isCollapsed) R.string.reader_expand_section else R.string.reader_collapse_section,
                                ),
                                tint = scheme.primary,
                                modifier = Modifier.rotate(rotation),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksTab(
    ready: ReaderState.Ready,
    book: Book?,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    val bookmarks = book?.bookmarks.orEmpty()
    Column(Modifier.fillMaxSize()) {
        // Bookmarking the page being read lives here now that the top bar
        // hosts search instead of the bookmark toggle.
        Surface(
            onClick = onToggleBookmark,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (bookmarked) 0.14f else 0.08f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = if (bookmarked) {
                        Icons.Rounded.Bookmark
                    } else {
                        Icons.Rounded.BookmarkBorder
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        if (bookmarked) {
                            R.string.reader_bookmark_remove_here
                        } else {
                            R.string.reader_bookmark_here
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (bookmarks.isEmpty()) {
            EmptyTabHint(stringResource(R.string.reader_bookmarks_empty))
            return@Column
        }
        BookmarkList(ready, bookmarks, onBookmarkClick, onRemoveBookmark)
    }
}

@Composable
private fun BookmarkList(
    ready: ReaderState.Ready,
    bookmarks: List<com.example.frogreader.data.model.Bookmark>,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        items(bookmarks, key = { it.id }) { bookmark ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // A bookmark whose passage is not in this file cannot be
                    // jumped to. Better to say so than to seek to whatever
                    // happens to sit at the old index now.
                    .clickable(enabled = !bookmark.orphaned) {
                        onBookmarkClick(bookmark.flatIndex)
                    }
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (bookmark.orphaned) {
                            stringResource(R.string.reader_bookmark_orphaned)
                        } else {
                            ready.chapterTitles.getOrNull(bookmark.chapterIndex)
                                ?: stringResource(R.string.reader_chapter_n, bookmark.chapterIndex + 1)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (bookmark.orphaned) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = bookmark.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (bookmark.orphaned) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.reader_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotesTab(
    ready: ReaderState.Ready,
    book: Book?,
    onCopyQuote: (String) -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onRemoveQuote: (String) -> Unit,
) {
    val quotes = book?.quotes.orEmpty()
    if (quotes.isEmpty()) {
        EmptyTabHint(stringResource(R.string.reader_quotes_empty))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        items(quotes, key = { it.id }) { quote ->
            // A quote saved before anchors existed, or one whose book file was
            // replaced under it, has nowhere to jump to — it still copies.
            val anchored = quote.startItem >= 0
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (anchored) {
                            Modifier.clickable { onQuoteClick(quote) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ready.chapterTitles.getOrNull(quote.chapterIndex)
                            ?: stringResource(R.string.reader_chapter_n, quote.chapterIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "“${quote.text}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
                IconButton(onClick = { onCopyQuote(quote.text) }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.selection_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemoveQuote(quote.id) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.reader_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTabHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Reader sheets stay this much shorter than the window on purpose: when a
 * sheet grows to full height in this material3 alpha, expanding it misaligns
 * the sheet's hit targets and its controls stop responding to taps. Keeping
 * the sheet below the status bar avoids that regime entirely; anything that
 * doesn't fit scrolls inside the sheet.
 */
@Composable
internal fun sheetMaxContentHeight(): Dp =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() - 140.dp }.coerceAtLeast(0.dp)

/**
 * The reader settings controls, shown inside the pull-up panel of the bottom
 * bar. Everything applies instantly so the page behind previews the change.
 */
@Composable
fun ReaderSettingsControls(
    settings: ReaderSettings,
    appSettings: AppSettings,
    colors: ReaderColors,
    brightness: Float?,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onUpdateApp: ((AppSettings) -> AppSettings) -> Unit,
    onBrightnessChange: (Float?) -> Unit,
    /** Reports the content height (px) up to the margins/mode row. */
    onPeekHeight: ((Int) -> Unit)? = null,
    /** What the open book's own formatting asks for, when it asks at all. */
    publisherStyle: PublisherStyle? = null,
    /** Live book page numbers for page counter preview cards. */
    pageCounterInfo: PageCounterInfo? = null,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    // In publisher's formatting the book overrules the reader — the controls
    // below show the book's answer so the mode's effect is visible.
    val book = publisherStyle?.takeIf { settings.bookStyles }
    val shownJustify = book?.justify ?: settings.justify
    val shownHyphenation = book?.hyphenation ?: settings.hyphenation
    val shownLineHeight = book?.lineHeight ?: settings.lineHeight
    val shownDropCaps = settings.dropCaps || book?.dropCaps == true
    val bookFontFamily = remember(book?.fontPath, book?.fontCss, book?.fontFaces) {
        book?.fontPath?.let { publisherFontFamily(book.fontFaces) ?: customFontFamily(it) }
            ?: book?.fontCss?.let { css -> bundledFontFamilyForCss(css) ?: when (css) {
                "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                "cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
                else -> null
            } }
    }

    /**
     * Changing something the book itself chose steps out of publisher's
     * formatting — the mode is a starting point, not a cage. What it had
     * already given is kept: the book's choices are written into the
     * reader's own settings first, and the edit lands on top of them, so
     * only the one thing the reader touched actually changes.
     *
     * [keepFont] is false when the edit IS the font — no point copying the
     * book's face into the library just to replace it in the same breath.
     */
    fun bakeBook(
        dictated: Boolean,
        keepFont: Boolean = true,
    ): (ReaderSettings) -> ReaderSettings {
        val style = book?.takeIf { dictated } ?: return { it }
        // The font file is copied here, on the tap, not inside the settings
        // transform (which may run again on a write conflict).
        val storedBookFamily = style.fontPath?.takeIf { keepFont }?.let { path ->
            groupCustomFonts((style.fontFaces.map { java.io.File(it.path) } + java.io.File(path)).distinct())
                .firstOrNull { it.contains(path) }
                ?.let { storeBookFontFamily(java.io.File(context.filesDir, "fonts"), it) }
        }
        // Keep a book's generic category when leaving publisher formatting.
        // Sans adopts the reader's bundled IBM Plex Sans default.
        val genericFont = style.fontCss
            ?.takeIf { keepFont && style.fontPath == null }
            ?.let {
                val bundled = bundledFontFamilyForCss(it)
                when {
                    bundled === com.example.frogreader.ui.theme.LiterataFamily -> ReaderFont.LITERATA
                    bundled === com.example.frogreader.ui.theme.IBMPlexSansFamily -> ReaderFont.SANS
                    it == "serif" -> ReaderFont.SERIF
                    it == "sans-serif" -> ReaderFont.SANS
                    else -> null // monospace/cursive have no reader equivalent
                }
            }
        return { current ->
            // Rapid held steps build on the latest edit even before the
            // composition has observed that publisher formatting was left.
            if (!current.bookStyles) current else {
                var out = current.copy(bookStyles = false)
                style.justify?.let { out = out.copy(justify = it) }
                style.lineHeight?.let { out = out.copy(lineHeight = it) }
                style.hyphenation?.let { out = out.copy(hyphenation = it) }
                if (style.dropCaps) out = out.copy(dropCaps = true)
                when {
                    storedBookFamily != null -> out = out.selectCustomFontFamily(storedBookFamily)
                        .copy(customFontAxes = style.fontTypography?.variationAxes.orEmpty())

                    genericFont != null -> out = out.copy(font = genericFont)
                }
                out
            }
        }
    }

    Column {
        Spacer(Modifier.height(8.dp))

        // Theme
        val themes = AppTheme.entries
        val themeLabels = listOf(
            stringResource(R.string.theme_light),
            stringResource(R.string.theme_beige),
            stringResource(R.string.theme_midnight),
        )
        val themeSwatch: @Composable (Int) -> Unit = { index ->
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(readerColors(themes[index]).background)
                    .border(1.dp, colors.onChrome.copy(alpha = 0.25f), CircleShape),
            )
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp || fontScale > 1.25f) {
                Column(
                    Modifier.clip(RoundedCornerShape(26.dp)).background(colors.control),
                ) {
                    themes.forEachIndexed { index, theme ->
                        ChipRow(
                            options = listOf(themeLabels[index]),
                            selectedIndex = if (theme == appSettings.theme) 0 else -1,
                            colors = colors,
                            onSelect = {
                                onUpdateApp { it.copy(theme = theme, followSystemTheme = false) }
                            },
                            leading = { themeSwatch(index) },
                        )
                    }
                }
            } else {
                ChipRow(
                    options = themeLabels,
                    selectedIndex = themes.indexOf(appSettings.theme),
                    colors = colors,
                    onSelect = { index ->
                        onUpdateApp { it.copy(theme = themes[index], followSystemTheme = false) }
                    },
                    leading = themeSwatch,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Brightness: manual override, or "Auto" = the system level (which
        // keeps the phone's adaptive brightness in charge). While on Auto the
        // slider tracks the live system value and looks dimmed.
        val autoBrightness = brightness == null
        fun readSystemBrightness(): Float = runCatching {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            ) / 255f
        }.getOrDefault(0.5f)
        var systemBrightness by remember { mutableFloatStateOf(readSystemBrightness()) }
        DisposableEffect(Unit) {
            val observer = object : android.database.ContentObserver(
                android.os.Handler(android.os.Looper.getMainLooper()),
            ) {
                override fun onChange(selfChange: Boolean) {
                    systemBrightness = readSystemBrightness()
                }
            }
            context.contentResolver.registerContentObserver(
                android.provider.Settings.System.getUriFor(
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                ),
                false,
                observer,
            )
            onDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
        PanelSectionLabel(stringResource(R.string.reader_brightness), colors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.WbSunny,
                contentDescription = null,
                tint = colors.onChromeVariant,
                modifier = Modifier.size(16.dp),
            )
            SettingSlider(
                value = brightnessPosition(brightness ?: systemBrightness),
                onChange = { position -> onBrightnessChange(brightnessFromPosition(position)) },
                colors = colors,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                Icons.Rounded.WbSunny,
                contentDescription = null,
                tint = colors.onChrome,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            // Auto shares the selected-setting face; manual remains an outline.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (autoBrightness) colors.selectedControl else Color.Transparent,
                    )
                    .border(
                        width = 1.dp,
                        color = if (autoBrightness) {
                            colors.selectedControlBorder
                        } else {
                            colors.onChrome.copy(alpha = 0.3f)
                        },
                        shape = RoundedCornerShape(20.dp),
                    )
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onBrightnessChange(null)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Rounded.BrightnessAuto,
                    contentDescription = null,
                    tint = if (autoBrightness) colors.onSelectedControl else colors.onChromeVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.reader_brightness_auto),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (autoBrightness) FontWeight.Bold else FontWeight.Medium,
                    color = if (autoBrightness) colors.onSelectedControl else colors.onChromeVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Line spacing and font size: compact steppers, side by side.
        ReaderControlPair(
            first = {
                PanelSectionLabel(stringResource(R.string.reader_line_height), colors)
                StepperGroup(
                    value = "×%.1f".format(shownLineHeight),
                    colors = colors,
                    decreaseEnabled = shownLineHeight > 1.2f,
                    increaseEnabled = shownLineHeight < 2.0f,
                    decreaseDescription = stringResource(R.string.reader_decrease_line_spacing),
                    increaseDescription = stringResource(R.string.reader_increase_line_spacing),
                    onDecrease = {
                        val bake = bakeBook(book?.lineHeight != null)
                        onUpdate {
                            val current = bake(it)
                            current.copy(
                                lineHeight = (((current.lineHeight - 0.1f) * 10).roundToInt() / 10f)
                                    .coerceAtLeast(1.2f),
                            )
                        }
                    },
                    onIncrease = {
                        val bake = bakeBook(book?.lineHeight != null)
                        onUpdate {
                            val current = bake(it)
                            current.copy(
                                lineHeight = (((current.lineHeight + 0.1f) * 10).roundToInt() / 10f)
                                    .coerceAtMost(2.0f),
                            )
                        }
                    },
                    decrease = {
                        Icon(
                            Icons.Rounded.DensitySmall,
                            contentDescription = null,
                            tint = colors.onChrome,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    increase = {
                        Icon(
                            Icons.Rounded.DensityLarge,
                            contentDescription = null,
                            tint = colors.onChrome,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            },
            second = {
                PanelSectionLabel(stringResource(R.string.reader_font_size), colors)
                StepperGroup(
                    value = stringResource(
                        R.string.reader_font_size_pt,
                        settings.fontSizeSp.roundToInt(),
                    ),
                    colors = colors,
                    decreaseEnabled = settings.fontSizeSp > 12f,
                    increaseEnabled = settings.fontSizeSp < 32f,
                    decreaseDescription = stringResource(R.string.reader_decrease_font_size),
                    increaseDescription = stringResource(R.string.reader_increase_font_size),
                    onDecrease = {
                        onUpdate { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(12f)) }
                    },
                    onIncrease = {
                        onUpdate { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(32f)) }
                    },
                    decrease = {
                        Text(
                            "A−",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.accent,
                        )
                    },
                    increase = {
                        Text(
                            "A+",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.accent,
                        )
                    },
                )
            },
        )

        Spacer(Modifier.height(18.dp))

        // Side margins and reading mode, side by side. The bottom of this
        // row is the "peek" height the half-open panel snaps to.
        ReaderControlPair(
            modifier = Modifier.onPlaced { coords ->
                onPeekHeight?.invoke(
                    (coords.positionInParent().y + coords.size.height).roundToInt(),
                )
            },
            first = {
                PanelSectionLabel(stringResource(R.string.reader_section_margins), colors)
                StepperGroup(
                    value = stringResource(R.string.reader_margins_dp, settings.sideMarginDp),
                    colors = colors,
                    repeatOnHold = true,
                    decreaseEnabled = settings.sideMarginDp > ReaderSettings.MIN_SIDE_MARGIN_DP,
                    increaseEnabled = settings.sideMarginDp < ReaderSettings.MAX_SIDE_MARGIN_DP,
                    decreaseDescription = stringResource(R.string.reader_decrease_side_margins),
                    increaseDescription = stringResource(R.string.reader_increase_side_margins),
                    onDecrease = {
                        onUpdate {
                            it.copy(
                                pageMarginDp = (it.sideMarginDp - ReaderSettings.SIDE_MARGIN_STEP_DP)
                                    .coerceAtLeast(ReaderSettings.MIN_SIDE_MARGIN_DP),
                            )
                        }
                    },
                    onIncrease = {
                        onUpdate {
                            it.copy(
                                pageMarginDp = (it.sideMarginDp + ReaderSettings.SIDE_MARGIN_STEP_DP)
                                    .coerceAtMost(ReaderSettings.MAX_SIDE_MARGIN_DP),
                            )
                        }
                    },
                    decrease = {
                        Icon(
                            Icons.Rounded.UnfoldMore,
                            contentDescription = null,
                            tint = colors.onChrome,
                            modifier = Modifier.size(20.dp).rotate(90f),
                        )
                    },
                    increase = {
                        Icon(
                            Icons.Rounded.UnfoldLess,
                            contentDescription = null,
                            tint = colors.onChrome,
                            modifier = Modifier.size(20.dp).rotate(90f),
                        )
                    },
                )
            },
            second = {
                PanelSectionLabel(stringResource(R.string.reader_mode), colors)
                val modes = ReadingMode.entries
                ChipRow(
                    options = listOf(
                        stringResource(R.string.reader_mode_scroll),
                        stringResource(R.string.reader_mode_pages),
                    ),
                    selectedIndex = modes.indexOf(settings.readingMode),
                    colors = colors,
                    onSelect = { index -> onUpdate { it.copy(readingMode = modes[index]) } },
                )
            },
        )

        // Page-turn animation only applies to paged mode.
        if (settings.readingMode == ReadingMode.PAGES) {
            Column {
                Spacer(Modifier.height(18.dp))
                PanelSectionLabel(stringResource(R.string.reader_section_page_anim), colors)
                ReaderPageTurnPicker(
                    selected = settings.pageTurnAnimation,
                    colors = colors,
                    onSelected = { animation -> onUpdate { it.copy(pageTurnAnimation = animation) } },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Font family: bundled faces rendered in themselves, plus the
        // user's own font library behind the Custom chip.
        PanelSectionLabel(stringResource(R.string.reader_font), colors)
        var showFontDialog by remember { mutableStateOf(false) }
        var fontsVersion by remember { mutableIntStateOf(0) }
        val fontScope = rememberCoroutineScope()
        val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                fontScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        uris.mapNotNull { storeCustomFont(context, it) }
                    }
                    if (imported.isNotEmpty()) {
                        com.example.frogreader.ui.theme.forgetCustomFontFiles(imported)
                        val groups = withContext(Dispatchers.IO) {
                            groupCustomFonts(java.io.File(context.filesDir, "fonts").listFiles().orEmpty().toList())
                        }
                        onUpdate { it.refreshCustomFontFamily(groups) }
                        fontsVersion++
                        showFontDialog = true
                    }
                    if (imported.size < uris.size) {
                        android.widget.Toast.makeText(
                            context,
                            R.string.reader_fonts_import_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        val bookFontName = book?.fontName
        ReaderFontPicker(
            settings = settings,
            colors = colors,
            bookFontName = bookFontName,
            bookFontPath = book?.fontPath,
            bookFontFamily = bookFontFamily,
            onSelect = { index ->
                val bake = bakeBook(bookFontName != null, keepFont = false)
                when (index) {
                    0 -> onUpdate { bake(it).copy(font = ReaderFont.LITERATA) }
                    1 -> onUpdate { bake(it).copy(font = ReaderFont.SANS) }
                    else -> showFontDialog = true
                }
            },
        )
        if (bookFontName != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.reader_font_from_book),
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (showFontDialog) {
            CustomFontsDialog(
                colors = colors,
                bookFonts = publisherStyle?.embeddedFonts.orEmpty(),
                bodyFontPath = book?.fontPath,
                bodyFontAuto = book?.fontPath != null,
                onPickBookFont = { font ->
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    fontScope.launch {
                        val adopted = withContext(Dispatchers.IO) {
                            val group = groupCustomFonts(publisherStyle?.embeddedFonts.orEmpty().map { java.io.File(it.path) })
                                .firstOrNull { it.contains(font.path) }
                            group?.let {
                                val index = it.faces.indexOfFirst { face -> face.key == font.key }
                                storeBookFontFamily(java.io.File(context.filesDir, "fonts"), it)?.let { stored ->
                                    stored.faces.getOrNull(index)?.let { selected -> selected to stored }
                                }
                            }
                        }
                        if (adopted != null) {
                            onUpdate {
                                bake(it).selectCustomFont(adopted.first).refreshCustomFontFamily(listOf(adopted.second))
                            }
                            fontsVersion++
                            showFontDialog = false
                        }
                    }
                },
                onPickBookFamily = { group ->
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    fontScope.launch {
                        val adopted = withContext(Dispatchers.IO) {
                            storeBookFontFamily(java.io.File(context.filesDir, "fonts"), group)
                        }
                        if (adopted != null) {
                            onUpdate { bake(it).selectCustomFontFamily(adopted) }
                            fontsVersion++
                            showFontDialog = false
                        }
                    }
                },
                selectedPath = settings.customFontPath
                    ?.takeIf { settings.font == ReaderFont.CUSTOM && bookFontName == null },
                selectedAxes = settings.customFontAxes,
                selectedAuto = settings.customFontAuto,
                fontsVersion = fontsVersion,
                onAdd = {
                    fontPicker.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "font/*",
                            "application/x-font-ttf",
                            "application/octet-stream",
                        ),
                    )
                },
                onPick = { face ->
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    fontScope.launch {
                        val groups = withContext(Dispatchers.IO) {
                            groupCustomFonts(java.io.File(context.filesDir, "fonts").listFiles().orEmpty().toList())
                        }
                        onUpdate { bake(it).selectCustomFont(face).refreshCustomFontFamily(groups) }
                        showFontDialog = false
                    }
                },
                onPickAuto = { group ->
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    onUpdate { bake(it).selectCustomFontFamily(group) }
                    showFontDialog = false
                },
                onDelete = { paths ->
                    val deleted = paths.filter { java.io.File(it).delete() }
                    com.example.frogreader.ui.theme.forgetCustomFontFiles(deleted)
                    fontsVersion++
                    onUpdate { it.removeCustomFontFiles(deleted) }
                },
                onDismiss = { showFontDialog = false },
            )
        }

        Spacer(Modifier.height(22.dp))

        // The book's own design — one switch that moves the controls below.
        PanelSectionLabel(
            stringResource(R.string.reader_section_book_design),
            colors,
            icon = Icons.Rounded.AutoFixHigh,
        )
        SettingsGroup(colors) {
            SettingToggleRow(
                icon = Icons.Rounded.AutoFixHigh,
                title = stringResource(R.string.reader_book_styles),
                subtitle = stringResource(R.string.reader_book_styles_subtitle),
                colors = colors,
                checked = settings.bookStyles,
                onCheckedChange = { onUpdate { s -> s.copy(bookStyles = it) } },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Text & layout
        PanelSectionLabel(
            stringResource(R.string.reader_section_text_layout),
            colors,
            icon = Icons.Rounded.TextFields,
        )
        SettingsGroup(colors) {
            SettingToggleRow(
                icon = Icons.Rounded.FormatAlignJustify,
                title = stringResource(R.string.reader_justify),
                colors = colors,
                checked = shownJustify,
                fromBook = book?.justify != null,
                onCheckedChange = { value ->
                    val bake = bakeBook(book?.justify != null)
                    onUpdate { s -> bake(s).copy(justify = value) }
                },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.Remove,
                title = stringResource(R.string.reader_hyphenation),
                colors = colors,
                checked = shownHyphenation,
                fromBook = book?.hyphenation != null,
                onCheckedChange = { value ->
                    val bake = bakeBook(book?.hyphenation != null)
                    onUpdate { s -> bake(s).copy(hyphenation = value) }
                },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.FormatSize,
                title = stringResource(R.string.reader_drop_caps),
                subtitle = stringResource(R.string.reader_drop_caps_subtitle),
                colors = colors,
                checked = shownDropCaps,
                fromBook = book?.dropCaps == true,
                onCheckedChange = { value ->
                    val bake = bakeBook(book?.dropCaps == true)
                    onUpdate { s -> bake(s).copy(dropCaps = value) }
                },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.InvertColors,
                title = stringResource(R.string.reader_invert_images),
                subtitle = stringResource(R.string.reader_invert_images_subtitle),
                colors = colors,
                checked = appSettings.autoInvertImages,
                onCheckedChange = { value ->
                    onUpdateApp { s -> s.copy(autoInvertImages = value) }
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Pages & navigation
        val isPagedMode = settings.readingMode == ReadingMode.PAGES
        PanelSectionLabel(
            stringResource(R.string.reader_section_pages_nav),
            colors,
            icon = Icons.AutoMirrored.Rounded.MenuBook,
        )
        SettingsGroup(colors) {
            SettingToggleRow(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = stringResource(R.string.reader_chapter_new_page),
                colors = colors,
                checked = settings.startChaptersOnNewPage,
                enabled = isPagedMode,
                onCheckedChange = { onUpdate { s -> s.copy(startChaptersOnNewPage = it) } },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.Superscript,
                title = stringResource(R.string.reader_hide_footnotes),
                subtitle = stringResource(R.string.reader_hide_footnotes_subtitle),
                colors = colors,
                checked = settings.hideFootnotes,
                onCheckedChange = { onUpdate { s -> s.copy(hideFootnotes = it) } },
            )
        }

        Spacer(Modifier.height(12.dp))
        ReaderPageCounterSettings(settings, colors, pageCounterInfo, onUpdate)

        Spacer(Modifier.height(20.dp))

        // Device
        PanelSectionLabel(
            stringResource(R.string.reader_section_device),
            colors,
            icon = Icons.Rounded.Smartphone,
        )
        SettingsGroup(colors) {
            SettingToggleRow(
                icon = Icons.Rounded.Lightbulb,
                title = stringResource(R.string.settings_keep_screen_on),
                colors = colors,
                checked = appSettings.keepScreenOn,
                onCheckedChange = { onUpdateApp { s -> s.copy(keepScreenOn = it) } },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.Vibration,
                title = stringResource(R.string.reader_haptics),
                colors = colors,
                checked = appSettings.haptics,
                onCheckedChange = { onUpdateApp { s -> s.copy(haptics = it) } },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.reader_volume_keys),
                colors = colors,
                checked = appSettings.volumeKeyPaging,
                onCheckedChange = { onUpdateApp { s -> s.copy(volumeKeyPaging = it) } },
            )
        }

        Spacer(Modifier.height(20.dp))

        PanelSectionLabel(stringResource(R.string.settings_reader_chrome_title), colors)
        ChipRow(
            options = listOf(
                stringResource(R.string.reader_chrome_floating),
                stringResource(R.string.reader_chrome_classic),
            ),
            selectedIndex = ReaderChromeStyle.entries.indexOf(appSettings.readerChromeStyle),
            colors = colors,
            onSelect = { index ->
                onUpdateApp { it.copy(readerChromeStyle = ReaderChromeStyle.entries[index]) }
            },
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** Preview the real regular face without adding selection weight to the typeface. */
@Composable
internal fun ReaderFontPicker(
    settings: ReaderSettings,
    colors: ReaderColors,
    bookFontName: String? = null,
    bookFontPath: String? = null,
    bookFontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    onSelect: (Int) -> Unit,
) {
    val labelFamily = remember(bookFontPath, bookFontName, bookFontFamily) {
        if (bookFontPath != null && bookFontName != null) {
            customFontPreviewFamily(bookFontPath, 400, false, previewText = bookFontName) ?: bookFontFamily
        } else bookFontFamily
    }
    ChipRow(
        options = listOf(
            stringResource(R.string.reader_font_literata),
            stringResource(R.string.reader_font_sans),
            bookFontName ?: stringResource(R.string.reader_font_custom),
        ),
        optionFontFamilies = listOf(
            com.example.frogreader.ui.theme.LiterataFamily,
            com.example.frogreader.ui.theme.IBMPlexSansFamily,
            labelFamily,
        ),
        labelMaxLines = 2,
        selectedIndex = when {
            bookFontName != null -> 2
            settings.font == ReaderFont.SANS -> 1
            settings.font == ReaderFont.CUSTOM -> 2
            else -> 0
        },
        colors = colors,
        onSelect = onSelect,
    )
}

/** Copy selected files into the library; family names come from their OpenType metadata. */
private fun storeCustomFont(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"
        com.example.frogreader.data.storeFontFile(
            java.io.File(context.filesDir, "fonts"), bytes, displayName,
        )
    }.getOrNull()

/** Keep the paired controls compact until text or window width needs a full row. */
@Composable
private fun ReaderControlPair(
    modifier: Modifier = Modifier,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp * fontScale.coerceAtLeast(1f) || fontScale > 1.25f) {
            Column {
                Column(Modifier.fillMaxWidth(), content = first)
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth(), content = second)
            }
        } else {
            Row {
                Column(Modifier.weight(1f), content = first)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), content = second)
            }
        }
    }
}

/** A compact stepper: named buttons around a bold value, with real disabled bounds. */
@Composable
private fun StepperGroup(
    value: String,
    colors: ReaderColors,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decrease: @Composable () -> Unit,
    increase: @Composable () -> Unit,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
    decreaseDescription: String? = null,
    increaseDescription: String? = null,
    repeatOnHold: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.control)
            .padding(5.dp),
    ) {
        ReaderStepperButton(
            colors = colors,
            enabled = decreaseEnabled,
            description = decreaseDescription,
            repeatOnHold = repeatOnHold,
            onStep = onDecrease,
            content = decrease,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colors.onChrome,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ReaderStepperButton(
            colors = colors,
            enabled = increaseEnabled,
            description = increaseDescription,
            repeatOnHold = repeatOnHold,
            onStep = onIncrease,
            content = increase,
        )
    }
}

/** Pill of equal chips; the selected one fills with the accent color. */
@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    colors: ReaderColors,
    onSelect: (Int) -> Unit,
    leading: (@Composable (Int) -> Unit)? = null,
    /** Per-option font families (a font chip previews its own face). */
    optionFontFamilies: List<androidx.compose.ui.text.font.FontFamily?>? = null,
    /** Longer font names wrap without changing the other settings rows. */
    labelMaxLines: Int = 1,
    enabled: Boolean = true,
    equalWeights: Boolean = true,
    customWeights: List<Float>? = null,
) {
    val haptics = LocalHapticFeedback.current
    FrogSegmentedControl(
        count = options.size,
        selectedIndex = selectedIndex,
        enabled = enabled,
        weights = options.mapIndexed { index, label ->
            customWeights?.getOrNull(index) ?: if (equalWeights) 1f else label.length.coerceAtLeast(4).toFloat()
        },
        containerColor = colors.control,
        indicatorColor = colors.selectedControl,
        contentColor = colors.onChrome,
        selectedContentColor = colors.onSelectedControl,
        indicatorBorder = colors.selectedControlBorder,
        containerRadius = 26.dp,
        indicatorRadius = 22.dp,
        minItemHeight = 0.dp,
        onSelect = { index ->
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(index)
        },
    ) { index, selected, foreground ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            if (leading != null) {
                leading(index)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = options[index],
                style = if (optionFontFamilies != null) MaterialTheme.typography.labelLarge.copy(fontSynthesis = FontSynthesis.None)
                    else MaterialTheme.typography.labelLarge,
                fontFamily = optionFontFamilies?.getOrNull(index),
                fontWeight = if (optionFontFamilies != null) FontWeight.Normal
                    else if (selected) FontWeight.Bold else FontWeight.Medium,
                fontStyle = FontStyle.Normal,
                color = foreground,
                maxLines = labelMaxLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * The window brightness override is LINEAR backlight power, but the system
 * slider is perceptual — a γ-curve maps between the two so our slider feels
 * like the system one and can dim to the real minimum (a linear floor of 3%
 * looked like ~20% brightness).
 */
internal fun brightnessPosition(value: Float): Float = value.coerceIn(0f, 1f).pow(1f / 2.2f)

internal fun brightnessFromPosition(position: Float): Float =
    position.coerceIn(0f, 1f).pow(2.2f)

/** Full-width slider with the reader's pill thumb; reports values in 0..1. */
@Composable
private fun SettingSlider(
    value: Float,
    onChange: (Float) -> Unit,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val shown = value.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val active = colors.progress
        val inactive = colors.track
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val trackHeight = 6.dp.toPx()
            val top = (size.height - trackHeight) / 2
            val radius = CornerRadius(trackHeight / 2)
            val position = shown * w

            drawRoundRect(inactive, Offset(0f, top), Size(w, trackHeight), radius)
            if (position > 0f) {
                drawRoundRect(active, Offset(0f, top), Size(position, trackHeight), radius)
            }

            val thumbWidth = 4.dp.toPx()
            val thumbHeight = 18.dp.toPx()
            val thumbX = position.coerceIn(thumbWidth / 2, w - thumbWidth / 2)
            drawRoundRect(
                color = active,
                topLeft = Offset(thumbX - thumbWidth / 2, (size.height - thumbHeight) / 2),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2),
            )
        }
    }
}

@Composable
private fun PanelSectionLabel(
    text: String,
    colors: ReaderColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.onChromeVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onChromeVariant,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun SettingsGroup(colors: ReaderColors, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.control),
    ) {
        content()
    }
}

@Composable
private fun PanelDivider(colors: ReaderColors) {
    HorizontalDivider(
        color = colors.controlStrong,
        modifier = Modifier.padding(start = 54.dp),
    )
}

@Composable
private fun SettingToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    colors: ReaderColors,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    /**
     * The book decides this one (publisher's formatting): the switch animates
     * to the book's answer and says so. It stays fully usable — changing it
     * simply steps out of publisher's formatting.
     */
    fromBook: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val lockedNote = stringResource(R.string.reader_set_by_book)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.onChrome,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val caption = if (fromBook) lockedNote else subtitle
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fromBook) colors.accent else colors.onChromeVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.onChromeVariant,
                uncheckedTrackColor = colors.control,
                uncheckedBorderColor = colors.onChromeVariant.copy(alpha = 0.5f),
                // A book-controlled switch keeps its normal look, only muted:
                // greying it out would hide the value it just animated to.
                disabledCheckedThumbColor = colors.onAccent,
                disabledCheckedTrackColor = colors.accent.copy(alpha = 0.55f),
                disabledUncheckedThumbColor = colors.onChromeVariant.copy(alpha = 0.55f),
                disabledUncheckedTrackColor = colors.control,
                disabledUncheckedBorderColor = colors.onChromeVariant.copy(alpha = 0.3f),
            ),
            onCheckedChange = { value ->
                haptics.performHapticFeedback(
                    if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                )
                onCheckedChange(value)
            },
        )
    }
}
