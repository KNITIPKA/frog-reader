package com.example.frogreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoStories
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
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Superscript
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.EmbeddedFont
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.ui.theme.customFontFamily
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contents / bookmarks / quotes tabs, shown inside the pull-up panel of the
 * bottom bar (same surface as the reader settings — no modal sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPanelsContent(
    ready: ReaderState.Ready,
    book: Book?,
    currentChapter: Int,
    chapterStartPages: List<Int>?,
    /** Lets the whole tab header drag the panel like the handle does. */
    dragModifier: Modifier = Modifier,
    onChapterClick: (Int) -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onCopyQuote: (String) -> Unit,
    onRemoveQuote: (String) -> Unit,
    /** Whether the page being read is bookmarked, and its toggle. */
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            modifier = dragModifier,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.reader_contents)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.reader_bookmarks)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(stringResource(R.string.reader_quotes)) },
            )
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ContentsTab(ready, currentChapter, chapterStartPages, onChapterClick)
                1 -> BookmarksTab(
                    ready, book, bookmarked, onToggleBookmark,
                    onBookmarkClick, onRemoveBookmark,
                )
                else -> QuotesTab(ready, book, onCopyQuote, onRemoveQuote)
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
@Composable
private fun ContentsTab(
    ready: ReaderState.Ready,
    currentChapter: Int,
    chapterStartPages: List<Int>?,
    onChapterClick: (Int) -> Unit,
) {
    val depths = ready.chapterDepths
    val count = ready.chapterTitles.size

    // A chapter is a group when the next chapter is nested deeper.
    fun isGroup(index: Int): Boolean =
        index + 1 < count && depths[index + 1] > depths[index]

    /** Index of the nearest enclosing group of [index], or -1. */
    fun parentOf(index: Int): Int {
        val depth = depths[index]
        for (i in index - 1 downTo 0) {
            if (depths[i] < depth) return i
        }
        return -1
    }

    // Groups start collapsed, except the path to the current chapter.
    val collapsed = remember(ready, currentChapter) {
        val initiallyExpanded = buildSet {
            var ancestor = parentOf(currentChapter)
            while (ancestor >= 0) {
                add(ancestor)
                ancestor = parentOf(ancestor)
            }
            if (isGroup(currentChapter)) add(currentChapter)
        }
        mutableStateMapOf<Int, Boolean>().apply {
            for (i in 0 until count) {
                if (isGroup(i)) put(i, i !in initiallyExpanded)
            }
        }
    }

    fun visible(index: Int): Boolean {
        var ancestor = parentOf(index)
        while (ancestor >= 0) {
            if (collapsed[ancestor] == true) return false
            ancestor = parentOf(ancestor)
        }
        return true
    }

    val haptics = LocalHapticFeedback.current
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        itemsIndexed(ready.chapterTitles) { index, title ->
            if (!visible(index)) return@itemsIndexed
            val isCurrent = index == currentChapter
            val group = isGroup(index)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChapterClick(index) }
                    .padding(
                        start = 8.dp + 18.dp * depths[index].coerceAtMost(4),
                        end = 8.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
            ) {
                Text(
                    text = (title ?: stringResource(R.string.reader_chapter_n, index + 1))
                        .replace('\n', ' '),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                val page = chapterStartPages?.getOrNull(index)
                if (page != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${page + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group) {
                    Spacer(Modifier.width(6.dp))
                    val isCollapsed = collapsed[index] == true
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                collapsed[index] = !isCollapsed
                            }
                            .rotate(if (isCollapsed) 0f else 180f),
                    )
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
                    .clickable { onBookmarkClick(bookmark.flatIndex) }
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ready.chapterTitles.getOrNull(bookmark.chapterIndex)
                            ?: stringResource(R.string.reader_chapter_n, bookmark.chapterIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = bookmark.preview,
                        style = MaterialTheme.typography.bodyMedium,
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
    onRemoveQuote: (String) -> Unit,
) {
    val quotes = book?.quotes.orEmpty()
    if (quotes.isEmpty()) {
        EmptyTabHint(stringResource(R.string.reader_quotes_empty))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        items(quotes, key = { it.id }) { quote ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
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
    LocalConfiguration.current.screenHeightDp.dp - 140.dp

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
    val bookFontFamily = remember(book?.fontPath, book?.fontCss) {
        book?.fontPath?.let { customFontFamily(it) }
            ?: when (book?.fontCss) {
                "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                "cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
                else -> null
            }
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
    fun bakeBook(dictated: Boolean, keepFont: Boolean = true): (ReaderSettings) -> ReaderSettings {
        val style = book?.takeIf { dictated } ?: return { it }
        // The font file is copied here, on the tap, not inside the settings
        // transform (which may run again on a write conflict).
        val fontPath = style.fontPath
            ?.takeIf { keepFont }
            ?.let { storeBookFont(context, it, style.fontName) }
        // A book that just asks for a generic face keeps that face: these
        // map to the very same families the page was drawn with a moment ago.
        val genericFont = style.fontCss
            ?.takeIf { keepFont && style.fontPath == null }
            ?.let {
                when (it) {
                    "serif" -> ReaderFont.SERIF
                    "sans-serif" -> ReaderFont.SANS
                    else -> null // monospace/cursive have no reader equivalent
                }
            }
        return { current ->
            var out = current.copy(bookStyles = false)
            style.justify?.let { out = out.copy(justify = it) }
            style.lineHeight?.let { out = out.copy(lineHeight = it) }
            style.hyphenation?.let { out = out.copy(hyphenation = it) }
            if (style.dropCaps) out = out.copy(dropCaps = true)
            when {
                fontPath != null ->
                    out = out.copy(font = ReaderFont.CUSTOM, customFontPath = fontPath)

                genericFont != null -> out = out.copy(font = genericFont)
            }
            out
        }
    }

    Column {
        Spacer(Modifier.height(8.dp))

        // Theme
        val themes = AppTheme.entries
        ChipRow(
            // Same names the settings sheet uses; the reader had its own
            // "Light / Beige / Dark" wording for the very same three themes.
            options = listOf(
                stringResource(R.string.theme_light),
                stringResource(R.string.theme_beige),
                stringResource(R.string.theme_midnight),
            ),
            selectedIndex = themes.indexOf(appSettings.theme),
            colors = colors,
            onSelect = { index -> onUpdateApp { it.copy(theme = themes[index]) } },
            leading = { index ->
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(readerColors(themes[index]).background)
                        .border(1.dp, colors.onChrome.copy(alpha = 0.25f), CircleShape),
                )
            },
        )

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
                tint = colors.secondaryText,
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
            // Auto = filled accent pill; manual = hollow outline.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (autoBrightness) colors.accent else Color.Transparent,
                    )
                    .border(
                        width = 1.dp,
                        color = if (autoBrightness) {
                            Color.Transparent
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
                    tint = if (autoBrightness) colors.background else colors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.reader_brightness_auto),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (autoBrightness) FontWeight.Bold else FontWeight.Medium,
                    color = if (autoBrightness) colors.background else colors.secondaryText,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Line spacing and font size: compact steppers, side by side.
        Row {
            Column(Modifier.weight(1f)) {
                PanelSectionLabel(stringResource(R.string.reader_line_height), colors)
                StepperGroup(
                    value = "×%.1f".format(shownLineHeight),
                    colors = colors,
                    onDecrease = {
                        val bake = bakeBook(book?.lineHeight != null)
                        onUpdate {
                            bake(it).copy(
                                lineHeight = (((shownLineHeight - 0.1f) * 10).roundToInt() / 10f)
                                    .coerceAtLeast(1.2f),
                            )
                        }
                    },
                    onIncrease = {
                        val bake = bakeBook(book?.lineHeight != null)
                        onUpdate {
                            bake(it).copy(
                                lineHeight = (((shownLineHeight + 0.1f) * 10).roundToInt() / 10f)
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
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                PanelSectionLabel(stringResource(R.string.reader_font_size), colors)
                StepperGroup(
                    value = stringResource(
                        R.string.reader_font_size_pt,
                        settings.fontSizeSp.roundToInt(),
                    ),
                    colors = colors,
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
            }
        }

        Spacer(Modifier.height(18.dp))

        // Side margins and reading mode, side by side. The bottom of this
        // row is the "peek" height the half-open panel snaps to.
        Row(
            modifier = Modifier.onPlaced { coords ->
                onPeekHeight?.invoke(
                    (coords.positionInParent().y + coords.size.height).roundToInt(),
                )
            },
        ) {
            Column(Modifier.weight(1.15f)) {
                PanelSectionLabel(stringResource(R.string.reader_section_margins), colors)
                val marginOptions = PageMargins.entries
                ChipRow(
                    options = listOf(
                        stringResource(R.string.reader_margins_narrow),
                        stringResource(R.string.reader_margins_normal),
                        stringResource(R.string.reader_margins_wide),
                    ),
                    selectedIndex = marginOptions.indexOf(settings.pageMargins),
                    colors = colors,
                    onSelect = { index -> onUpdate { it.copy(pageMargins = marginOptions[index]) } },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(0.85f)) {
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
            }
        }

        // Page-turn animation only applies to paged mode.
        if (settings.readingMode == ReadingMode.PAGES) {
            Column {
                Spacer(Modifier.height(18.dp))
                PanelSectionLabel(stringResource(R.string.reader_section_page_anim), colors)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimationCard(
                        label = stringResource(R.string.reader_anim_slide),
                        icon = Icons.AutoMirrored.Rounded.Article,
                        selected = settings.pageTurnAnimation == PageTurnAnimation.SLIDE,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    ) { onUpdate { it.copy(pageTurnAnimation = PageTurnAnimation.SLIDE) } }
                    AnimationCard(
                        label = stringResource(R.string.reader_anim_cascade),
                        icon = Icons.Rounded.ViewColumn,
                        selected = settings.pageTurnAnimation == PageTurnAnimation.CASCADE,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    ) { onUpdate { it.copy(pageTurnAnimation = PageTurnAnimation.CASCADE) } }
                    AnimationCard(
                        label = stringResource(R.string.reader_anim_curl),
                        icon = Icons.Rounded.AutoStories,
                        selected = settings.pageTurnAnimation == PageTurnAnimation.PAGE_CURL,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    ) { onUpdate { it.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL) } }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Font family: bundled faces rendered in themselves, plus the
        // user's own font library behind the Custom chip.
        PanelSectionLabel(stringResource(R.string.reader_font), colors)
        var showFontDialog by remember { mutableStateOf(false) }
        var fontsVersion by remember { mutableIntStateOf(0) }
        val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val stored = storeCustomFont(context, uri)
                if (stored != null) {
                    fontsVersion++
                    onUpdate { it.copy(font = ReaderFont.CUSTOM, customFontPath = stored) }
                }
            }
        }
        // With publisher's formatting the book's own face takes the Custom
        // slot: its real name, drawn in itself, so the choice is visible.
        val bookFontName = book?.fontName
        ChipRow(
            options = listOf(
                stringResource(R.string.reader_font_literata),
                stringResource(R.string.reader_font_sans),
                bookFontName ?: stringResource(R.string.reader_font_custom),
            ),
            optionFontFamilies = listOf(
                com.example.frogreader.ui.theme.LiterataFamily,
                androidx.compose.ui.text.font.FontFamily.SansSerif,
                bookFontFamily,
            ),
            selectedIndex = when {
                bookFontName != null -> 2
                settings.font == ReaderFont.SANS -> 1
                settings.font == ReaderFont.CUSTOM -> 2
                else -> 0 // Literata (and the legacy Serif choice)
            },
            colors = colors,
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
                bookFonts = book?.embeddedFonts.orEmpty(),
                bodyFontPath = book?.fontPath,
                onPickBookFont = { font ->
                    // Adopting a face from the book makes it the reader's own.
                    val stored = storeBookFont(context, font.path, font.name)
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    if (stored != null) {
                        onUpdate {
                            bake(it).copy(font = ReaderFont.CUSTOM, customFontPath = stored)
                        }
                    }
                    showFontDialog = false
                },
                selectedPath = settings.customFontPath
                    ?.takeIf { settings.font == ReaderFont.CUSTOM },
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
                onPick = { path ->
                    val bake = bakeBook(bookFontName != null, keepFont = false)
                    onUpdate { bake(it).copy(font = ReaderFont.CUSTOM, customFontPath = path) }
                    showFontDialog = false
                },
                onDelete = { path ->
                    java.io.File(path).delete()
                    fontsVersion++
                    if (settings.customFontPath == path) {
                        onUpdate {
                            it.copy(
                                font = if (it.font == ReaderFont.CUSTOM) {
                                    ReaderFont.LITERATA
                                } else {
                                    it.font
                                },
                                customFontPath = null,
                            )
                        }
                    }
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
                onCheckedChange = { onUpdateApp { s -> s.copy(autoInvertImages = it) } },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Pages & navigation
        PanelSectionLabel(
            stringResource(R.string.reader_section_pages_nav),
            colors,
            icon = Icons.Rounded.MenuBook,
        )
        SettingsGroup(colors) {
            SettingToggleRow(
                icon = Icons.Rounded.MenuBook,
                title = stringResource(R.string.reader_chapter_new_page),
                colors = colors,
                checked = settings.startChaptersOnNewPage,
                onCheckedChange = { onUpdate { s -> s.copy(startChaptersOnNewPage = it) } },
            )
            PanelDivider(colors)
            SettingToggleRow(
                icon = Icons.Rounded.Tag,
                title = stringResource(R.string.reader_show_chapter_pages),
                colors = colors,
                checked = settings.showChapterPagesLeft,
                onCheckedChange = { onUpdate { s -> s.copy(showChapterPagesLeft = it) } },
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

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Moves the book's own face into the reader's font library, so a face that
 * arrived with publisher's formatting survives leaving that mode — and then
 * shows up in the fonts list like any font added by hand.
 */
private fun storeBookFont(
    context: android.content.Context,
    sourcePath: String,
    displayName: String?,
): String? = runCatching {
    val bytes = java.io.File(sourcePath).readBytes()
    if (bytes.size <= 4) return null
    val head = bytes.copyOfRange(0, 4)
    val extension = when {
        head.contentEquals("OTTO".toByteArray()) -> "otf"
        head.contentEquals("ttcf".toByteArray()) -> "ttc"
        head.contentEquals("true".toByteArray()) -> "ttf"
        head[0].toInt() == 0x00 && head[1].toInt() == 0x01 &&
            head[2].toInt() == 0x00 && head[3].toInt() == 0x00 -> "ttf"
        else -> return null // not an sfnt font after all
    }
    val base = (displayName ?: java.io.File(sourcePath).nameWithoutExtension)
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .take(60)
        .ifBlank { "book-font" }

    val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
    var target = java.io.File(dir, "$base.$extension")
    var counter = 2
    while (target.exists() && !target.readBytes().contentEquals(bytes)) {
        target = java.io.File(dir, "${base}_$counter.$extension")
        counter++
    }
    if (!target.exists()) target.writeBytes(bytes)
    target.absolutePath
}.getOrNull()

/**
 * Copies a user-picked font into the app's font library, keeping its file
 * name (that name IS the label in the fonts dialog). Returns the stored
 * path, or null when the file is not a real TTF/OTF font.
 */
private fun storeCustomFont(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        // TTF/OTF/TTC magic bytes — reject anything else outright.
        val ok = bytes.size > 4 && (
            (bytes[0].toInt() == 0x00 && bytes[1].toInt() == 0x01 &&
                bytes[2].toInt() == 0x00 && bytes[3].toInt() == 0x00) ||
                bytes.copyOfRange(0, 4).contentEquals("OTTO".toByteArray()) ||
                bytes.copyOfRange(0, 4).contentEquals("true".toByteArray()) ||
                bytes.copyOfRange(0, 4).contentEquals("ttcf".toByteArray())
            )
        if (!ok) return null

        val display = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "font"
        val base = display.substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(60)
            .ifBlank { "font" }
        val extension = display.substringAfterLast('.', "ttf").lowercase()
            .takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"

        val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
        var target = java.io.File(dir, "$base.$extension")
        var counter = 2
        while (target.exists() && !target.readBytes().contentEquals(bytes)) {
            target = java.io.File(dir, "${base}_$counter.$extension")
            counter++
        }
        if (!target.exists()) target.writeBytes(bytes)
        target.absolutePath
    }.getOrNull()

/**
 * The user's font library: "Add font" on top, then every stored font drawn
 * in its own face. Tap picks a font for reading; long-press offers Delete.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CustomFontsDialog(
    colors: ReaderColors,
    /** Typefaces shipped inside the open book (publisher's mode). */
    bookFonts: List<EmbeddedFont> = emptyList(),
    /** Which of them the book's body text is actually set in, if any. */
    bodyFontPath: String? = null,
    onPickBookFont: (EmbeddedFont) -> Unit = {},
    selectedPath: String?,
    fontsVersion: Int,
    onAdd: () -> Unit,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val fonts = remember(fontsVersion) {
        java.io.File(context.filesDir, "fonts").listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.reader_fonts_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.accent)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onAdd()
                        }
                        .padding(vertical = 13.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = colors.background,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.reader_font_add),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.background,
                    )
                }

                // Every typeface the book ships. The one the body is set in
                // is marked; the others are display faces the book uses on a
                // few paragraphs — tapping any of them adopts it for good.
                if (bookFonts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.reader_fonts_in_book),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryText,
                    )
                    Spacer(Modifier.height(6.dp))
                    for (font in bookFonts) {
                        val isBodyFace = font.path == bodyFontPath
                        val family = remember(font.path) { customFontFamily(font.path) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    colors.accent.copy(alpha = if (isBodyFace) 0.16f else 0.06f),
                                )
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onPickBookFont(font)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = font.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = family,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isBodyFace) {
                                Text(
                                    text = stringResource(R.string.reader_font_from_book),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (fonts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.reader_fonts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                    ) {
                        items(fonts, key = { it.absolutePath }) { file ->
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .combinedClickable(
                                            onClick = { onPick(file.absolutePath) },
                                            onLongClick = {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress,
                                                )
                                                menuOpen = true
                                            },
                                        )
                                        .padding(vertical = 13.dp, horizontal = 10.dp),
                                ) {
                                    Text(
                                        text = file.nameWithoutExtension,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = com.example.frogreader.ui.theme
                                            .customFontFamily(file.absolutePath),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (file.absolutePath == selectedPath) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = colors.accent,
                                        )
                                    }
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.reader_font_delete))
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Delete, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onDelete(file.absolutePath)
                                        },
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

/**
 * A compact stepper: [decrease] and [increase] buttons around a bold value.
 */
@Composable
private fun StepperGroup(
    value: String,
    colors: ReaderColors,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decrease: @Composable () -> Unit,
    increase: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.onChrome.copy(alpha = 0.06f))
            .padding(5.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 46.dp, height = 42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(colors.onChrome.copy(alpha = 0.08f))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    onDecrease()
                },
        ) { decrease() }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 46.dp, height = 42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(colors.onChrome.copy(alpha = 0.08f))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    onIncrease()
                },
        ) { increase() }
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
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.onChrome.copy(alpha = 0.08f))
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(index)
                    }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                if (leading != null) {
                    leading(index)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = optionFontFamilies?.getOrNull(index),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) colors.background else colors.onChrome,
                    maxLines = 1,
                )
            }
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
        val active = colors.accent
        val inactive = colors.secondaryText.copy(alpha = 0.25f)
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
                tint = colors.secondaryText,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText,
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
            .background(colors.onChrome.copy(alpha = 0.06f)),
    ) {
        content()
    }
}

@Composable
private fun PanelDivider(colors: ReaderColors) {
    HorizontalDivider(
        color = colors.onChrome.copy(alpha = 0.08f),
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
                    color = if (fromBook) colors.accent else colors.secondaryText,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.chrome,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.secondaryText,
                uncheckedTrackColor = colors.onChrome.copy(alpha = 0.06f),
                uncheckedBorderColor = colors.secondaryText.copy(alpha = 0.5f),
                // A book-controlled switch keeps its normal look, only muted:
                // greying it out would hide the value it just animated to.
                disabledCheckedThumbColor = colors.chrome,
                disabledCheckedTrackColor = colors.accent.copy(alpha = 0.55f),
                disabledUncheckedThumbColor = colors.secondaryText.copy(alpha = 0.55f),
                disabledUncheckedTrackColor = colors.onChrome.copy(alpha = 0.06f),
                disabledUncheckedBorderColor = colors.secondaryText.copy(alpha = 0.3f),
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

/** Page-turn animation choice card (stub; the animations come later). */
@Composable
private fun AnimationCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.onChrome.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .padding(vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) colors.accent else colors.onChrome.copy(alpha = 0.10f),
                ),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.background else colors.onChrome,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onChrome,
            maxLines = 1,
        )
    }
}
