package com.example.frogreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.theme.LocalFrogColors
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.requiredHeight

/** Scale the open-folder panel grows out of its tile from. */
private const val PanelCollapsedScale = 0.78f

/**
 * Books per page: three across, and up to two down, as in the mock. [Rows] is a
 * ceiling, not a promise — the page uses however many whole rows the room it
 * has can hold, which is one while the keyboard is up.
 */
private const val Columns = 3
private const val Rows = 2

private val CellGap = 12.dp
private val RowGap = 14.dp

/**
 * How much room each cell keeps below its cover for the title and author.
 *
 * Reserved per ROW, not per title: a page whose rows resize as you swipe it is
 * unreadable. Inside the cell the author still sits immediately under the
 * title — the slack all ends up at the bottom, where nobody notices it. Two
 * lines of 12sp title (15.4dp each), 2dp, one line of 11sp author (14dp), and
 * the 7dp above the title: 54dp, rounded up so a descender never gets clipped.
 */
private val CellTextHeight = 58.dp

/** The strip under the pager: page dots, or the selection's two buttons. */
private val DotsRowHeight = 58.dp

/** How much of the header's width the search field starts from as it unfurls. */
private const val SearchFurledFraction = 0.28f

/** The name shrinks between these until it fits two lines. */
private const val NameMaxSp = 29f
private const val NameMinSp = 16f

/**
 * The open folder: a card that unfolds out of the folder's own tile.
 *
 * Not a route and not a bottom sheet. It is a scrim plus a centred [Surface]
 * inside the library's root Box, because the whole point of the animation is
 * that the card grows from where the tile is — which means it has to be drawn
 * in the same coordinate space as the grid.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShelfPanel(
    entry: LibraryEntry.ShelfEntry,
    expanded: Boolean,
    coverOf: (Book) -> java.io.File?,
    tileBounds: LibraryTileBounds,
    selection: LibrarySelection,
    renameOnOpen: Boolean,
    onRename: (String) -> Unit,
    onRenameHandled: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onLongPressBook: (Book, Rect) -> Unit,
    onShelfMenu: (Rect) -> Unit,
    onAddBooks: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shelfScope = remember(entry.shelf.id) { SelectionScope.Shelf(entry.shelf.id) }
    val selecting = selection.scope == shelfScope

    var name by remember(entry.shelf.id) { mutableStateOf(entry.shelf.name) }
    var renaming by remember(entry.shelf.id) { mutableStateOf(false) }
    var searchQuery by remember(entry.shelf.id) { mutableStateOf<String?>(null) }
    val currentName by rememberUpdatedState(name)
    val savedName by rememberUpdatedState(entry.shelf.name)
    val commitRename by rememberUpdatedState(onRename)
    val finish by rememberUpdatedState(onClosed)

    // A folder that has just been made, or one the menu's Rename opened, starts
    // with the cursor in the name field: it was opened to be named.
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(renameOnOpen, expanded) {
        if (renameOnOpen && expanded) {
            renaming = true
            searchQuery = null
            onRenameHandled()
        }
    }
    LaunchedEffect(renaming) {
        if (renaming) runCatching { nameFocus.requestFocus() }
    }

    // The folder unfolding out of its own tile and folding back into it. The
    // open uses the expressive spatial spring; the close is deliberately
    // non-bouncy, because an overshoot on the way out reads as a mistake.
    val openSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val expansion = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            expansion.animateTo(1f, openSpec)
        } else {
            expansion.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            finish()
        }
    }

    // Save on the way out, so a rename survives tapping the scrim.
    DisposableEffect(entry.shelf.id) {
        onDispose { if (currentName.trim() != savedName) commitRename(currentName) }
    }

    // Back peels the panel's own layers before the screen gets a turn.
    BackHandler(enabled = expanded && searchQuery != null) { searchQuery = null }
    BackHandler(enabled = expanded && searchQuery == null && renaming) { renaming = false }

    val shown = remember(entry.books, searchQuery) {
        searchBooks(entry.books, searchQuery.orEmpty())
    }

    // Where the card itself ended up, so the growth origin can be expressed as
    // a fraction of it. Written from onGloballyPositioned, read in the layer.
    var panelRect by remember { mutableStateOf(Rect.Zero) }

    // A folder of one row is lifted clear of the keyboard; one of two rows is
    // not. Lifting a tall card means shortening it, and every shape that has
    // taken — shrinking the covers, re-paginating to one row, easing between
    // the two — looked like a fault rather than a layout. So the big card holds
    // its shape and the keyboard simply covers the bottom of it; the name being
    // typed is in the header, which stays well clear either way.
    val liftAboveKeyboard = entry.books.size <= Columns

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawRect(scheme.scrim.copy(alpha = 0.42f * expansion.value))
                drawContent()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = expanded,
                onClick = onDismiss,
            ),
    ) {
        // The card is centred in the space the keyboard leaves, not in the
        // window: naming a new folder opens the IME straight away, and a card
        // centred in the window has its bottom half behind it. The scrim stays
        // full-size — it is the outer Box that keeps `fillMaxSize`, so the dim
        // still runs edge to edge behind the keyboard.
        //
        // `imePadding` and not a fixed number: the activity is edge-to-edge, so
        // the window does not resize and the real inset is the only thing that
        // knows how tall this particular keyboard is on this particular device.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (liftAboveKeyboard) Modifier.imePadding() else Modifier),
        ) {
            Surface(
                shape = RoundedCornerShape(34.dp),
                color = scheme.surfaceContainerLowest,
                shadowElevation = 24.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    // BEFORE the graphicsLayer, and it has to stay there: modifier
                    // nodes to the left of a layer sit outside it, so positionInRoot
                    // keeps reporting the card's untransformed rect instead of its
                    // mid-animation one.
                    .onGloballyPositioned {
                        panelRect = Rect(it.positionInRoot(), it.size.toSize())
                    }
                    .graphicsLayer {
                        val progress = expansion.value
                        // Grow out of the folder's own tile.
                        val panel = panelRect
                        val tile = tileBounds[entry.id]
                        transformOrigin = if (tile != null && !panel.isEmpty) {
                            TransformOrigin(
                                ((tile.center.x - panel.left) / panel.width).coerceIn(0f, 1f),
                                ((tile.center.y - panel.top) / panel.height).coerceIn(0f, 1f),
                            )
                        } else {
                            TransformOrigin.Center
                        }
                        scaleX = lerp(PanelCollapsedScale, 1f, progress)
                        scaleY = scaleX
                        // Alpha leads the scale, so the panel is solid well before
                        // it stops growing.
                        alpha = (progress * 2.2f).coerceAtMost(1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    ShelfHeader(
                        entry = entry,
                        name = name,
                        onNameChange = { name = it },
                        renaming = renaming,
                        onStartRename = { renaming = true },
                        nameFocus = nameFocus,
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        onOpenSearch = {
                            renaming = false
                            searchQuery = ""
                        },
                        onCloseSearch = { searchQuery = null },
                        onMenu = onShelfMenu,
                    )

                    Spacer(Modifier.height(16.dp))

                    if (entry.books.isEmpty()) {
                        ShelfEmpty(onAddBooks = onAddBooks)
                    } else {
                        ShelfPages(
                            books = shown,
                            // The card is centred, so anything that shortens it
                            // walks its top edge down the screen. Its height is
                            // therefore the FOLDER's, not the query's: narrowing
                            // a search empties cells instead of moving the card.
                            reserveFor = entry.books.size,
                            coverOf = coverOf,
                            selecting = selecting,
                            isSelected = { book -> selection.contains(bookKey(book)) },
                            onClick = { book ->
                                if (selecting) {
                                    selection.toggle(bookKey(book))
                                } else {
                                    onOpenBook(book)
                                }
                            },
                            onLongClick = { book, anchor ->
                                if (selecting) {
                                    selection.toggle(bookKey(book))
                                } else {
                                    onLongPressBook(book, anchor)
                                }
                            },
                            selectionActions = selecting,
                            onRemoveSelected = onRemoveSelected,
                            onDeleteSelected = onDeleteSelected,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A book inside a folder still keys off its own `LibraryEntry.id`, so the ticks
 * survive the folder closing and mean the same thing to the delete path as a
 * tick made out on the grid does.
 */
private fun bookKey(book: Book): String = "b:${book.id}"

// ------------------------------------------------------------------- header

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfHeader(
    entry: LibraryEntry.ShelfEntry,
    name: String,
    onNameChange: (String) -> Unit,
    renaming: Boolean,
    onStartRename: () -> Unit,
    nameFocus: FocusRequester,
    searchQuery: String?,
    onSearchQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onMenu: (Rect) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(scheme.surfaceContainer)
            .padding(16.dp),
    ) {
        val searching = searchQuery != null
        // One number drives the whole swap: the name fades out as the field
        // unfurls from the button that opened it. On the expressive spatial
        // spring, so it lands with the same weight as the folder itself.
        val reveal by animateFloatAsState(
            targetValue = if (searching) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "shelfSearchReveal",
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The name and subtitle stay in the layout while the search field
            // is up, invisible, holding the header at one height. Swapping them
            // out for a 44dp field made the whole card jump a centimetre the
            // moment the magnifier was tapped.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(modifier = Modifier.graphicsLayer { alpha = 1f - reveal }) {
                    ShelfNameField(
                        name = name,
                        onNameChange = onNameChange,
                        renaming = renaming && !searching,
                        onStartRename = onStartRename,
                        enabled = !searching,
                        focusRequester = nameFocus,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = shelfSubtitle(entry),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Kept alive until it has finished folding away, or closing
                // the search would be a disappearance rather than a movement.
                if (reveal > 0.001f) {
                    FrogSearchField(
                        query = searchQuery.orEmpty(),
                        onQueryChange = onSearchQuery,
                        hint = stringResource(R.string.shelf_search_hint),
                        modifier = Modifier
                            // Grows out of the right-hand end, where the button
                            // that opened it is.
                            .fillMaxWidth(lerp(SearchFurledFraction, 1f, reveal))
                            .align(Alignment.CenterEnd)
                            .graphicsLayer { alpha = reveal },
                    )
                }
            }

            if (searching) {
                PanelIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.shelf_search_close),
                    onClick = onCloseSearch,
                )
            } else {
                PanelIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.shelf_search_open),
                    onClick = onOpenSearch,
                    modifier = Modifier.graphicsLayer { alpha = 1f - reveal },
                )
                // Reports its own rect so the folder's menu hangs off the "..."
                // button rather than off the folder tile hidden behind the card.
                var menuButton by remember { mutableStateOf(Rect.Zero) }
                PanelIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.library_menu_more),
                    onClick = { onMenu(menuButton) },
                    modifier = Modifier.onGloballyPositioned {
                        menuButton = Rect(it.positionInRoot(), it.size.toSize())
                    },
                )
            }
        }
    }
}

/**
 * The round chrome button of the folder header.
 *
 * Not [GlassIconButton]: that one is white-at-8%-to-55%, tuned to sit on the
 * library's coloured header gradient, and on the folder card's surfaceContainer
 * it all but disappears in the light themes.
 */
@Composable
private fun PanelIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = scheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** "16 books · 13 in progress" — the second half only when there is one. */
@Composable
private fun shelfSubtitle(entry: LibraryEntry.ShelfEntry): String {
    val total = entry.books.size
    val reading = entry.books.count { book ->
        val fraction = book.progress.fraction
        !fraction.isNaN() && fraction > 0f && fraction < 1f
    }
    val count = pluralStringResource(R.plurals.shelf_books_count, total, total)
    return if (reading > 0) {
        "$count  ·  ${stringResource(R.string.shelf_in_progress, reading)}"
    } else {
        count
    }
}

/**
 * The folder's name, at whatever size lets it fill the space it has.
 *
 * A one-word name gets the full 29sp; a long one steps down until two lines fit.
 * Measured rather than guessed from the character count, because "Українська
 * література XX століття" and "MMMMMMMMMMMMMMMMMMMMMM" are the same length and
 * nothing like the same width.
 */
@Composable
private fun ShelfNameField(
    name: String,
    onNameChange: (String) -> Unit,
    renaming: Boolean,
    onStartRename: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
) {
    val scheme = MaterialTheme.colorScheme
    val placeholder = stringResource(R.string.shelf_unnamed)
    val shown = name.ifBlank { placeholder }

    BoxWithConstraints {
        val size = rememberNameSize(shown, maxWidth)
        val style = TextStyle(
            fontSize = size,
            lineHeight = size * 1.18f,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )

        if (renaming) {
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = style,
                maxLines = 2,
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (name.isEmpty()) {
                        Text(
                            text = stringResource(R.string.shelf_name_hint),
                            style = style.copy(color = scheme.onSurfaceVariant),
                        )
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        } else {
            Text(
                text = shown,
                style = style,
                color = if (name.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        // Invisible behind the search field, and not to be
                        // tapped into a rename from there.
                        enabled = enabled,
                        onClick = onStartRename,
                    ),
            )
        }
    }
}

@Composable
private fun rememberNameSize(text: String, maxWidth: Dp): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, maxWidth, density) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        var candidate = NameMaxSp
        while (candidate > NameMinSp) {
            val fits = measurer.measure(
                text = AnnotatedString(text),
                style = TextStyle(
                    fontSize = candidate.sp,
                    lineHeight = (candidate * 1.18f).sp,
                    fontWeight = FontWeight.Medium,
                ),
                overflow = TextOverflow.Clip,
                maxLines = 2,
                constraints = Constraints(maxWidth = widthPx),
            )
            if (!fits.hasVisualOverflow) break
            candidate -= 1f
        }
        candidate.sp
    }
}

// -------------------------------------------------------------------- pages

@Composable
private fun ShelfPages(
    books: List<Book>,
    reserveFor: Int,
    coverOf: (Book) -> java.io.File?,
    selecting: Boolean,
    isSelected: (Book) -> Boolean,
    onClick: (Book) -> Unit,
    onLongClick: (Book, Rect) -> Unit,
    selectionActions: Boolean,
    onRemoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth - CellGap * (Columns - 1)) / Columns
        // A row at its proper size: a 2:3 cover the full width of a column,
        // plus the strip the cell keeps for its title and author.
        val naturalRow = cellWidth * 3 / 2 + CellTextHeight
        val roomForPages = maxHeight - DotsRowHeight

        val laidOutRows = ((roomForPages + RowGap) / (naturalRow + RowGap))
            .toInt()
            .coerceIn(1, Rows)

        val perPage = Columns * laidOutRows
        val pageCount = ceil(books.size / perPage.toFloat()).toInt().coerceAtLeast(1)

        // A single page is as tall as the books it holds — three books should
        // not float in the middle of a two-row hole. More than one page and
        // every page keeps the full height, or the card would resize under the
        // swipe. Counted over the whole folder, so a search never resizes it.
        val reservePages = ceil(reserveFor / perPage.toFloat()).toInt().coerceAtLeast(1)
        val contentRows = if (reservePages == 1) {
            ceil(reserveFor / Columns.toFloat()).toInt().coerceIn(1, laidOutRows)
        } else {
            laidOutRows
        }

        // A book is always its natural size. The page asks for the rows it has;
        // if the keyboard has left less room than that, `height` yields to the
        // constraint and the card ends up exactly as tall as it can be, with
        // the bottom row cut. The rows themselves use requiredHeight, so being
        // cut is all that happens to them.
        val pageHeight = naturalRow * contentRows + RowGap * (contentRows - 1)

        val pagerState = rememberPagerState(pageCount = { pageCount })
        // Fewer pages than before — a filter narrowed the list, or the keyboard
        // closed and two rows fit again — must not leave the pager parked past
        // the end, showing nothing.
        LaunchedEffect(pageCount) {
            if (pagerState.currentPage >= pageCount) pagerState.scrollToPage(pageCount - 1)
        }

        Column {
            if (books.isEmpty()) {
                // Inside the reserved area, so "nothing matched" is exactly as
                // tall as the books it stands in for — and it goes through the
                // same Column, so the strip below it is reserved too.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    ShelfNoMatches()
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = CellGap,
                    // Pager centres its pages vertically by default, which left a
                    // last page holding one book floating in the middle of a
                    // two-row card. Books start at the top row, always.
                    verticalAlignment = Alignment.Top,
                    // Clipped purely as insurance: nothing should ever be taller
                    // than its page, and if something is, better a cut edge than a
                    // cover drawn over the dots.
                    modifier = Modifier
                        .height(pageHeight)
                        .clipToBounds(),
                ) { page ->
                    val slice = books.drop(page * perPage).take(perPage)
                    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                        slice.chunked(Columns).forEach { row ->
                            Row(
                                // requiredHeight, not height: a plain height yields
                                // to whatever the parent offers, so a page shorter
                                // than its rows SQUEEZED them — and the covers,
                                // which are a fixed height of their own, spilled out
                                // of the squeezed row onto the one below with the
                                // titles pushed out of sight. That is the mess that
                                // appeared for a frame at the end of the collapse,
                                // whenever the height and the page contents came
                                // from different frames. A row that cannot be
                                // squeezed simply gets cut off by the card's edge,
                                // which is the whole effect anyway.
                                modifier = Modifier.requiredHeight(naturalRow),
                                horizontalArrangement = Arrangement.spacedBy(CellGap),
                            ) {
                                row.forEach { book ->
                                    key(book.id) {
                                        ShelfPanelBook(
                                            book = book,
                                            coverFile = coverOf(book),
                                            coverHeight = naturalRow - CellTextHeight,
                                            selecting = selecting,
                                            selected = isSelected(book),
                                            onClick = { onClick(book) },
                                            onLongClick = { anchor -> onLongClick(book, anchor) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                repeat(Columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (selectionActions) {
                ShelfSelectionActions(
                    onRemove = onRemoveSelected,
                    onDelete = onDeleteSelected,
                )
            } else {
                // The strip keeps its height whether or not there are dots in
                // it. Letting it collapse was the rest of the sinking card: a
                // search that narrowed to one page dropped the dots out of the
                // layout, and the centred card slid down by half of that.
                val dots by animateFloatAsState(
                    targetValue = if (pageCount > 1) 1f else 0f,
                    label = "shelfPageDots",
                )
                Box(
                    modifier = Modifier
                        .height(DotsHeight)
                        .align(Alignment.CenterHorizontally),
                ) {
                    if (dots > 0.001f) {
                        PageDots(
                            count = pageCount,
                            current = pagerState.currentPage,
                            modifier = Modifier.graphicsLayer { alpha = dots },
                        )
                    }
                }
            }
        }
    }
}

/** Reserved whether or not there are dots to put in it. */
private val DotsHeight = 10.dp

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.height(DotsHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 18.dp else 6.dp,
                label = "shelfPageDot",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

@Composable
private fun ShelfSelectionActions(onRemove: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MorphingButton(
            onClick = onRemove,
            color = scheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
        ) {
            ActionLabel(
                icon = Icons.Rounded.RemoveCircleOutline,
                label = stringResource(R.string.shelf_menu_remove_from_shelf),
                tint = scheme.onSurface,
            )
        }
        MorphingButton(
            onClick = onDelete,
            color = scheme.errorContainer,
            modifier = Modifier.height(44.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.library_delete_confirm),
                tint = scheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 18.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun ActionLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 12.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An empty folder is a folder waiting to be filled, so the whole of it is one
 * big "add books" target rather than a sentence explaining where to go instead.
 */
@Composable
private fun ShelfEmpty(onAddBooks: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(scheme.primary)
                .clickable(onClick = onAddBooks),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.shelf_add_books),
                tint = scheme.onPrimary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.shelf_empty),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.shelf_empty_hint),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShelfNoMatches() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.LibraryBooks,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.shelf_search_empty),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------------- cell

@Composable
private fun ShelfPanelBook(
    book: Book,
    coverFile: java.io.File?,
    /**
     * How tall the cover may be. The cell takes its size from HEIGHT and lets
     * the 2:3 ratio work out the width, not the other way round: sized from the
     * column width, a cover in a row the layout had to shorten simply overflowed
     * it, which drew the covers on top of each other and pushed the titles out
     * of the card entirely.
     */
    coverHeight: Dp,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()

    // The cover's rect, so the long-press menu can hang off this book rather
    // than off the middle of the card.
    var coverRect by remember { mutableStateOf(Rect.Zero) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .height(coverHeight)
                .aspectRatio(2f / 3f)
                .onGloballyPositioned {
                    coverRect = Rect(it.positionInRoot(), it.size.toSize())
                }
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onLongClick = { onLongClick(coverRect) }, onClick = onClick),
        ) {
            BookCover(book = book, coverFile = coverFile, titleSize = 9.sp, padding = 8.dp)

            if (percent > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(frog.pill)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "$percent%",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                }
                // How far in, along the bottom edge of the cover.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(scheme.primary),
                )
            }

            if (selecting) {
                SelectionCheck(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.primary.copy(alpha = 0.22f)),
                )
            }
        }

        Spacer(Modifier.height(7.dp))
        // Full column width even when a shortened cover is narrower than it, so
        // the labels stay left-aligned with each other rather than centring.
        TileTitle(text = book.title, maxLines = 2, modifier = Modifier.fillMaxWidth())
        book.author?.let { author ->
            Spacer(Modifier.height(2.dp))
            TileSubtitle(text = author, modifier = Modifier.fillMaxWidth())
        }
    }
}
