package com.example.frogreader.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.theme.LocalFrogColors
import kotlin.math.roundToInt
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import com.example.frogreader.data.model.bookOrderKey
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.CompositionLocalProvider

// Numbers below come straight from the 412x916dp design mock, 1:1.
private val GridGap = 14.dp
private val ListGap = 8.dp
private val SidePadding = 20.dp
private val BottomInset = 20.dp

/**
 * Which shelf, if any, has just been created and still owes the user an
 * arrival animation.
 *
 * A holder rather than a bare `String?` so the tiles can read it themselves.
 * Read at the `items` call site instead, creating one folder would invalidate
 * every book tile on screen twice.
 */
@Stable
private class ShelfPopState {
    /**
     * The book or folder that has just appeared, so it can grow into place
     * while the tiles around it slide aside. Books use it too now: one arriving
     * from outside the app should be visible as an arrival, not just be there
     * the next time you look.
     */
    var entryId by mutableStateOf<String?>(null)
}

/** Scale a brand-new folder grows from. */
private const val ShelfPopFrom = 0.62f

/** Material fade-through, applied to the entries when the view mode changes. */
private const val ModeSwapOutMillis = 90
private const val ModeSwapInMillis = 210
private const val ModeSwapScale = 0.92f

/**
 * The arrival animation for [shelfId], or a flat `1f` for every folder that has
 * been there all along — so the modifier chain is the same shape either way and
 * existing folders pay nothing for it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberEntryArrival(entryId: String, pop: ShelfPopState): State<Float> {
    val justCreated = pop.entryId == entryId
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val arrival = remember { Animatable(if (justCreated) 0f else 1f) }

    LaunchedEffect(justCreated) {
        if (justCreated) {
            // The tile can compose a frame before the new id reaches us — the
            // shelf flow and the callback are separate trips to the main
            // thread — so start from scratch rather than trust the initial.
            arrival.snapTo(0f)
            arrival.animateTo(1f, spec)
            pop.entryId = null
        } else if (arrival.value != 1f) {
            // A second folder created mid-animation cancels this effect. Never
            // leave a tile stranded at two-thirds size.
            arrival.animateTo(1f, spec)
        }
    }
    return arrival.asState()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
    // Clearance for the navigation bar. Taken as CONTENT padding, not as a
    // margin: the grid's background still runs to the bottom of the screen and
    // its items scroll under the bar, which is the point of having one.
    contentPadding: PaddingValues = PaddingValues(),
    onOpenBook: (Book) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * Whether the add-a-book button should be on screen. False while a folder
     * is open or a selection is running: the FAB belongs to the library behind
     * them, and it lands on top of the folder card and the selection bar.
     */
    onFabVisible: (Boolean) -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val resources = LocalResources.current
    val density = LocalDensity.current

    var bookToEdit by remember { mutableStateOf<Book?>(null) }
    var bookForDetails by remember { mutableStateOf<Book?>(null) }
    var openShelfId by rememberSaveable { mutableStateOf<String?>(null) }
    // The panel opens with the cursor already in the name field when the folder
    // was just made, or when the menu's Rename asked for it.
    var renameOnOpen by remember { mutableStateOf(false) }

    // Long-press machinery: which item's menu is showing, and which sheet the
    // menu opened after it.
    val tileBounds = remember { LibraryTileBounds() }
    val pop = remember { ShelfPopState() }
    val selection = remember { LibrarySelection() }
    var menuRequest by remember { mutableStateOf<MenuRequest?>(null) }
    var addToShelfFor by remember { mutableStateOf<AddToShelfRequest?>(null) }
    var shelfToDelete by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }
    var addBooksToShelf by remember { mutableStateOf<String?>(null) }

    val searching = query.isNotBlank()

    // Back peels one layer at a time: the selection first, then the folder.
    BackHandler(enabled = selection.active) { selection.clear() }
    BackHandler(enabled = !selection.active && openShelfId != null) { openShelfId = null }

    // The hero is still "the last book you opened". It keeps that spot even
    // when it lives inside a shelf — only a LOOSE copy is hidden from the grid.
    val heroBook = remember(books) {
        books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
    }
    val visibleEntries = remember(entries, books, query, heroBook) {
        val filtered = filterEntries(entries, books, query)
        if (searching) {
            filtered
        } else {
            filtered.filterNot { it is LibraryEntry.BookEntry && it.book.id == heroBook?.id }
        }
    }

    // What the panel is drawing. It deliberately outlives `openShelfId`: the
    // panel has to stay mounted while it folds back into its tile, and it has
    // to keep its last contents when the shelf itself dissolves underneath it
    // (pull the second-to-last book out and there is no shelf left to read).
    var mountedShelf by remember { mutableStateOf<LibraryEntry.ShelfEntry?>(null) }
    LaunchedEffect(entries, openShelfId) {
        val id = openShelfId ?: return@LaunchedEffect
        val match = entries.filterIsInstance<LibraryEntry.ShelfEntry>()
            .firstOrNull { it.shelf.id == id }
        if (match != null) {
            mountedShelf = match
        } else {
            // The shelf is gone — dissolved down to one book, or restored from
            // a saved state that outlived it. Close, don't sit there holding an
            // id that nothing can open (and that Back would keep swallowing).
            openShelfId = null
        }
    }
    // Opening seeds it from the click instead of waiting for that effect, so
    // the panel is there on the very frame the folder is tapped.
    val onOpenShelf: (LibraryEntry.ShelfEntry) -> Unit = { entry ->
        mountedShelf = entry
        openShelfId = entry.shelf.id
    }

    // A shelf the view model wants opened: made by the FAB, or made just now to
    // hold the books the user picked "add to a new shelf" for. Either way it
    // arrives unnamed, so it opens with the name field ready.
    val openShelfRequest by viewModel.openShelfRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openShelfRequest, entries) {
        val id = openShelfRequest ?: return@LaunchedEffect
        val match = entries.filterIsInstance<LibraryEntry.ShelfEntry>()
            .firstOrNull { it.shelf.id == id }
            // The id lands a beat before the shelves flow catches up. Wait for
            // the entry rather than opening a panel with nothing behind it.
            ?: return@LaunchedEffect
        selection.clear()
        pop.entryId = id
        renameOnOpen = true
        onOpenShelf(match)
        viewModel.consumeOpenShelfRequest()
    }

    // The FAB is the library's, so it steps out of the way of anything laid
    // over the library — and comes back if this screen leaves while one of
    // those is still up.
    val fabWanted = openShelfId == null && !selection.active && addBooksToShelf == null
    val reportFab by rememberUpdatedState(onFabVisible)
    LaunchedEffect(fabWanted) { reportFab(fabWanted) }
    DisposableEffect(Unit) { onDispose { reportFab(true) } }

    // Ticks whose entries have been deleted out from under them. Books INSIDE a
    // shelf count as still there: they are not top-level entries, and dropping
    // them would wipe a selection made in an open folder on its first frame.
    LaunchedEffect(entries) {
        if (!selection.active) return@LaunchedEffect
        val alive = HashSet<String>()
        entries.forEach { entry ->
            alive += entry.id
            if (entry is LibraryEntry.ShelfEntry) {
                entry.books.forEach { alive += bookOrderKey(it.id) }
            }
        }
        selection.retain(alive)
    }

    LaunchedEffect(resources) {
        viewModel.messages.collect { message ->
            when (message) {
                is LibraryMessage.Imported -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.library_imported, message.title),
                    )
                }

                is LibraryMessage.Replaced -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.library_replaced, message.title),
                    )
                }

                is LibraryMessage.ImportedMany -> {
                    haptics.performHapticFeedback(
                        if (message.failed > 0) HapticFeedbackType.Reject else HapticFeedbackType.Confirm,
                    )
                    snackbarHostState.showSnackbar(
                        if (message.failed > 0) {
                            resources.getString(
                                R.string.library_imported_many_partial,
                                message.added,
                                message.failed,
                            )
                        } else {
                            resources.getString(R.string.library_imported_many, message.added)
                        },
                    )
                }

                LibraryMessage.ImportCancelled -> {
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.library_import_cancelled),
                    )
                }

                LibraryMessage.ImportFailed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.library_import_failed),
                    )
                }

                LibraryMessage.ImportFailedDrm -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.library_import_failed_drm),
                    )
                }
            }
        }
    }

    // A book added from outside the app: hand its id to the grid so the tile
    // grows into place, and clear it so a rotation does not replay the arrival.
    val arrived by viewModel.arrived.collectAsStateWithLifecycle()
    LaunchedEffect(arrived) {
        val id = arrived ?: return@LaunchedEffect
        pop.entryId = id
        viewModel.consumeArrival()
    }
    val gridState = rememberLazyGridState()

    // Toggling the mode swaps every tile for a row of a completely different
    // shape. Done in one frame it reads as a stutter, so fade the entries out,
    // relayout while they are invisible, and fade them back — the Material
    // fade-through. `renderMode` is what is actually on screen; `viewMode` is
    // what was asked for.
    var renderMode by remember { mutableStateOf(viewMode) }
    var swapping by remember { mutableStateOf(false) }
    // Only a tap on the toggle earns a transition. The persisted mode arrives
    // from DataStore a beat after the first frame, and animating that would
    // make every cold start look like the app changed its mind.
    var userChoseMode by remember { mutableStateOf(false) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(viewMode) {
        if (viewMode != renderMode) {
            if (userChoseMode) {
                swapping = true
                swap.animateTo(0f, tween(ModeSwapOutMillis, easing = FastOutLinearInEasing))
            }
            renderMode = viewMode
        }
        // Never skip this tail, even when the modes already match. Toggling
        // back mid-fade cancels and restarts this effect with viewMode already
        // equal to renderMode, and an early return there would strand the grid
        // at whatever alpha it had reached — permanently half-invisible.
        swap.animateTo(1f, tween(ModeSwapInMillis, easing = LinearOutSlowInEasing))
        swapping = false
    }
    // Content types for the grid, so LazyGrid can reuse a book tile for another
    // book instead of composing it from scratch. Hoisted rather than built per
    // item: the lambda below runs during measurement, on every item.
    val bookType = remember(renderMode) { "${renderMode.name}:book" }
    val shelfType = remember(renderMode) { "${renderMode.name}:shelf" }

    val swapProgress = swap.asState()
    // Hoisted so the lambda instance is stable, and read only from inside
    // graphicsLayer: the fade runs in the draw phase and recomposes nothing.
    val modeFade = remember(swapProgress) {
        Modifier.graphicsLayer {
            val progress = swapProgress.value
            alpha = progress
            scaleX = lerp(ModeSwapScale, 1f, progress)
            scaleY = scaleX
        }
    }

    // The GRID's own selection run, not any run at all: books ticked inside an
    // open folder must not put checkmarks on the tiles behind it, and a tap out
    // there must still open what it hits.
    val gridSelecting = selection.scope == SelectionScope.Grid

    // A tap either opens the thing or ticks it — never both, or a selection run
    // would keep dropping the user into the reader.
    val onEntryClick: (LibraryEntry) -> Unit = { entry ->
        if (gridSelecting) {
            selection.toggle(entry.id)
        } else {
            when (entry) {
                is LibraryEntry.BookEntry -> onOpenBook(entry.book)
                is LibraryEntry.ShelfEntry -> onOpenShelf(entry)
            }
        }
    }
    val onEntryLongPress: (LibraryEntry) -> Unit = { entry ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (gridSelecting) {
            selection.toggle(entry.id)
        } else {
            val target = when (entry) {
                is LibraryEntry.BookEntry ->
                    MenuTarget.BookTarget(entry.book.id, entry.id, shelfId = null)

                is LibraryEntry.ShelfEntry ->
                    MenuTarget.ShelfTarget(entry.shelf.id, entry.id)
            }
            // The tile already registers its rect for the folder animation, so
            // the menu can hang off the item without the gesture reporting a
            // finger position.
            menuRequest = MenuRequest(target, tileBounds[entry.id] ?: Rect.Zero)
        }
    }

    // `combinedClickable` has no timeout parameter — it reads one from the
    // ambient ViewConfiguration. The platform's 500ms is tuned for text
    // selection; on a grid of covers, where the menu is the point of holding
    // one, it feels like the app is thinking. Delegation keeps every other
    // member (touch slop, fling velocity, double-tap window) at its real value.
    val platformViewConfiguration = LocalViewConfiguration.current
    val quickLongPress = remember(platformViewConfiguration) {
        object : ViewConfiguration by platformViewConfiguration {
            override val longPressTimeoutMillis: Long = MenuHoldMillis
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides quickLongPress) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { tileBounds.rootOrigin = it.positionInRoot() },
        ) {
            LazyVerticalGrid(
                state = gridState,
                // One column in list mode, rather than giving every entry a
                // full-line span: an ITEM whose span changes under an unchanged
                // key makes LazyGrid place the same node twice ("Place was
                // called on a node which was placed already"). Changing the
                // column count keeps every entry at span 1 in both modes, which
                // is what lets the keys below stay stable.
                columns = GridCells.Fixed(if (renderMode == LibraryViewMode.GRID) 3 else 1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SidePadding,
                    end = SidePadding,
                    bottom = BottomInset + contentPadding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(ListGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "library_header") {
                    LibraryHeader(
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        onOpenSettings = onOpenSettings,
                        heroBook = if (searching) null else heroBook,
                        heroCover = heroBook?.let { viewModel.coverFileFor(it) },
                        onOpenBook = onOpenBook,
                        onHeroDetails = { bookForDetails = heroBook },
                        onHeroEdit = { bookToEdit = heroBook },
                        // Through the same sheet as every other delete, so there is
                        // one "remove a book" object in the app rather than two.
                        onHeroDelete = {
                            heroBook?.let { pendingRemoval = PendingRemoval(listOf(it.id)) }
                        },
                        // The header is full-bleed; undo the grid's side padding.
                        modifier = Modifier.bleedHorizontally(SidePadding),
                    )
                }

                // `entries`, not `books`: an empty shelf is the only thing in the
                // library right after the FAB makes one, and the empty state would
                // otherwise hide it.
                if (entries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "library_section") {
                        SectionRow(
                            // `viewMode`, not `renderMode`: the segment lights
                            // up under the finger while the grid is still
                            // fading out behind it. That is most of what makes
                            // the toggle feel instant.
                            viewMode = viewMode,
                            onViewMode = { mode ->
                                userChoseMode = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                viewModel.setViewMode(mode)
                            },
                            // The row doubles as the selection bar rather than a
                            // top app bar sliding in: the library's header is item
                            // one of this grid and scrolls away, so there is no bar
                            // to take over.
                            selection = selection.takeIf { it.scope == SelectionScope.Grid },
                            onSelectAll = { selection.selectAll(visibleEntries.map { it.id }) },
                            onClearSelection = { selection.clear() },
                            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                        )
                    }
                }

                if (entries.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "library_empty") {
                        EmptyLibrary(Modifier.padding(top = 64.dp))
                    }
                } else if (visibleEntries.isEmpty() && searching) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "library_no_results") {
                        NoSearchResults(Modifier.padding(top = 48.dp))
                    }
                } else {
                    items(
                        items = visibleEntries,
                        // The entry id alone, so a mode swap KEEPS every item
                        // slot: the cover's image node survives, its remembers
                        // survive, and the grid keeps its scroll anchor. Safe
                        // because entry items never declare a span — only the
                        // column count changes, exactly as the full-span header
                        // above has always done.
                        key = { it.id },
                        contentType = { entry ->
                            if (entry is LibraryEntry.ShelfEntry) shelfType else bookType
                        },
                    ) { entry ->
                        val itemModifier = Modifier
                            .animateItem(
                                // Snappy and NOT bouncy. The old low-stiffness
                                // bouncy spring overshot and took ~500ms to
                                // settle, so displaced tiles stayed under the
                                // finger long enough to fight the reorder.
                                //
                                // Suppressed outright across a mode swap: every
                                // item now keeps its slot, so every one of them
                                // would otherwise fly from its grid position to
                                // its row position while fading in. The
                                // cross-fade carries that motion instead.
                                placementSpec = if (swapping) {
                                    null
                                } else {
                                    spring(
                                        stiffness = Spring.StiffnessMedium,
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    )
                                },
                            )
                            .tileBounds(entry.id, tileBounds)
                            // AFTER tileBounds, so its onGloballyPositioned sits
                            // outside this layer: a rect measured through a scaled
                            // layer is the wrong place to unfold a folder from.
                            .then(modeFade)

                        val ticked = gridSelecting && entry.id in selection
                        val click = { onEntryClick(entry) }
                        val longPress = { onEntryLongPress(entry) }

                        when (entry) {
                            is LibraryEntry.BookEntry -> if (renderMode == LibraryViewMode.GRID) {
                                BookGridTile(
                                    book = entry.book,
                                    coverFile = viewModel.coverFileFor(entry.book),
                                    pop = pop,
                                    selecting = gridSelecting,
                                    selected = ticked,
                                    onClick = click,
                                    onLongClick = longPress,
                                    modifier = itemModifier.padding(top = 6.dp),
                                )
                            } else {
                                BookListRow(
                                    book = entry.book,
                                    coverFile = viewModel.coverFileFor(entry.book),
                                    pop = pop,
                                    selecting = gridSelecting,
                                    selected = ticked,
                                    onClick = click,
                                    onLongClick = longPress,
                                    modifier = itemModifier,
                                )
                            }

                            is LibraryEntry.ShelfEntry -> if (renderMode == LibraryViewMode.GRID) {
                                ShelfGridTile(
                                    entry = entry,
                                    coverOf = viewModel::coverFileFor,
                                    pop = pop,
                                    selecting = gridSelecting,
                                    selected = ticked,
                                    onClick = click,
                                    onLongClick = longPress,
                                    modifier = itemModifier.padding(top = 6.dp),
                                )
                            } else {
                                ShelfListRow(
                                    entry = entry,
                                    coverOf = viewModel::coverFileFor,
                                    pop = pop,
                                    selecting = gridSelecting,
                                    selected = ticked,
                                    onClick = click,
                                    onLongClick = longPress,
                                    onAddBooks = { addBooksToShelf = entry.shelf.id },
                                    modifier = itemModifier,
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // The bar's height already carries the system inset.
                    .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) { data -> Snackbar(data) }

            mountedShelf?.let { shelfEntry ->
                ShelfPanel(
                    entry = shelfEntry,
                    expanded = openShelfId == shelfEntry.shelf.id,
                    coverOf = viewModel::coverFileFor,
                    tileBounds = tileBounds,
                    selection = selection,
                    renameOnOpen = renameOnOpen,
                    onRename = { newName -> viewModel.renameShelf(shelfEntry.shelf.id, newName) },
                    onRenameHandled = { renameOnOpen = false },
                    onOpenBook = onOpenBook,
                    onLongPressBook = { book, anchor ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuRequest = MenuRequest(
                            target = MenuTarget.BookTarget(
                                bookId = book.id,
                                entryId = bookOrderKey(book.id),
                                shelfId = shelfEntry.shelf.id,
                            ),
                            anchor = anchor,
                        )
                    },
                    onShelfMenu = { anchor ->
                        menuRequest = MenuRequest(
                            MenuTarget.ShelfTarget(shelfEntry.shelf.id, shelfEntry.id),
                            anchor,
                        )
                    },
                    onAddBooks = { addBooksToShelf = shelfEntry.shelf.id },
                    onRemoveSelected = {
                        viewModel.removeFromShelf(shelfEntry.shelf.id, selection.selectedBookIds())
                        selection.clear()
                    },
                    onDeleteSelected = {
                        pendingRemoval = PendingRemoval(selection.selectedBookIds())
                    },
                    onDismiss = {
                        // A selection made inside the folder has nowhere to be
                        // acted on once the folder is gone.
                        if (selection.scope is SelectionScope.Shelf) selection.clear()
                        openShelfId = null
                    },
                    // Unmount only once it has finished folding away — and only if
                    // nothing reopened it in the meantime.
                    onClosed = { if (openShelfId == null) mountedShelf = null },
                )
            }

            // Above the panel, below the sheets: acting on a selection made inside
            // an open folder still has to be reachable.
            if (selection.scope == SelectionScope.Grid) {
                SelectionActionBar(
                    canAddToShelf = selection.selectedBookIds().isNotEmpty(),
                    onAddToShelf = {
                        addToShelfFor = AddToShelfRequest(selection.selectedBookIds())
                    },
                    onDelete = {
                        // A folder in the selection is DISSOLVED, not emptied: the
                        // books it held stay. Wiping a folder's books needs the
                        // folder's own menu, where the two options are spelled out.
                        pendingRemoval = PendingRemoval(
                            bookIds = selection.selectedBookIds(),
                            shelfIds = selection.selectedShelfIds(),
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                )
            }

            // The long-press menu, inside this Box so it can be laid over the item
            // it belongs to. The Box draws nothing and takes the item's exact rect;
            // the DropdownMenu inside anchors to it and flips above near the bottom
            // of the screen, which is what makes it read as belonging to the cover.
            menuRequest?.let { request ->
                val target = request.target
                val anchor = request.anchor
                val bookOf = { id: String -> books.firstOrNull { it.id == id } }

                Box(
                    modifier = Modifier
                        .offset {
                            val local = anchor.topLeft - tileBounds.rootOrigin
                            IntOffset(local.x.roundToInt(), local.y.roundToInt())
                        }
                        .size(
                            width = with(density) { anchor.width.toDp() },
                            height = with(density) { anchor.height.toDp() },
                        ),
                ) {
                    LibraryItemMenu(
                        target = target,
                        onSelect = {
                            val scope = when (target) {
                                is MenuTarget.BookTarget -> target.shelfId
                                    ?.let { SelectionScope.Shelf(it) } ?: SelectionScope.Grid

                                is MenuTarget.ShelfTarget -> SelectionScope.Grid
                            }
                            selection.start(scope, target.entryId)
                            menuRequest = null
                        },
                        onAddToShelf = {
                            val book = target as? MenuTarget.BookTarget
                            if (book != null) {
                                addToShelfFor = AddToShelfRequest(listOf(book.bookId), book.shelfId)
                            }
                            menuRequest = null
                        },
                        onAddBooks = {
                            addBooksToShelf = (target as? MenuTarget.ShelfTarget)?.shelfId
                            menuRequest = null
                        },
                        onRemoveFromShelf = {
                            val book = target as? MenuTarget.BookTarget
                            if (book?.shelfId != null) {
                                viewModel.removeFromShelf(book.shelfId, listOf(book.bookId))
                            }
                            menuRequest = null
                        },
                        onEdit = {
                            bookToEdit = (target as? MenuTarget.BookTarget)?.bookId?.let(bookOf)
                            menuRequest = null
                        },
                        onRename = {
                            val shelf = target as? MenuTarget.ShelfTarget
                            if (shelf != null) {
                                entries.filterIsInstance<LibraryEntry.ShelfEntry>()
                                    .firstOrNull { it.shelf.id == shelf.shelfId }
                                    ?.let {
                                        renameOnOpen = true
                                        onOpenShelf(it)
                                    }
                            }
                            menuRequest = null
                        },
                        onDelete = {
                            when (target) {
                                is MenuTarget.BookTarget ->
                                    pendingRemoval = PendingRemoval(listOf(target.bookId))

                                is MenuTarget.ShelfTarget -> shelfToDelete = target.shelfId
                            }
                            menuRequest = null
                        },
                        onDismiss = { menuRequest = null },
                    )
                }
            }
        }
    }

    bookToEdit?.let { book ->
        EditBookDialog(
            book = book,
            coverFile = viewModel.coverFileFor(book),
            onDismiss = { bookToEdit = null },
            onSave = { title, author, coverUri ->
                viewModel.updateBookDetails(book.id, title, author, coverUri)
                bookToEdit = null
            },
        )
    }

    bookForDetails?.let { book ->
        BookDetailsSheet(
            book = book,
            coverFile = viewModel.coverFileFor(book),
            onDismiss = { bookForDetails = null },
        )
    }


    addToShelfFor?.let { request ->
        AddToShelfSheet(
            shelves = entries.filterIsInstance<LibraryEntry.ShelfEntry>(),
            exceptShelfId = request.fromShelfId,
            onNewShelf = {
                // The shelf is only addressable once the write lands, so the
                // view model reports its id back and the screen opens it there.
                viewModel.createShelf(request.bookIds)
                selection.clear()
                addToShelfFor = null
            },
            onPick = { shelfId ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.addToShelf(shelfId, request.bookIds)
                selection.clear()
                addToShelfFor = null
            },
            onDismiss = { addToShelfFor = null },
        )
    }

    shelfToDelete?.let { shelfId ->
        DeleteShelfSheet(
            onRemoveShelf = {
                viewModel.deleteShelf(shelfId)
                if (openShelfId == shelfId) openShelfId = null
                selection.clear()
                shelfToDelete = null
            },
            onDeleteBooks = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.deleteShelfWithBooks(shelfId)
                if (openShelfId == shelfId) openShelfId = null
                selection.clear()
                shelfToDelete = null
            },
            onDismiss = { shelfToDelete = null },
        )
    }

    addBooksToShelf?.let { shelfId ->
        val target = entries.filterIsInstance<LibraryEntry.ShelfEntry>()
            .firstOrNull { it.shelf.id == shelfId }
        // Which shelf each book is on, already resolved to a display name: the
        // row that renders it is not a composable and cannot look one up.
        val unnamed = stringResource(R.string.shelf_unnamed)
        val shelfOf = remember(entries, unnamed) {
            entries.filterIsInstance<LibraryEntry.ShelfEntry>()
                .flatMap { entry ->
                    val label = entry.shelf.name.ifBlank { unnamed }
                    entry.books.map { it.id to (entry.shelf.id to label) }
                }
                .toMap()
        }
        AddBooksToShelfScreen(
            shelfName = target?.let { shelfName(it) }.orEmpty(),
            candidates = remember(books, shelfOf, shelfId) {
                books.filterNot { shelfOf[it.id]?.first == shelfId }
            },
            shelfNameOf = { book -> shelfOf[book.id]?.second },
            coverOf = viewModel::coverFileFor,
            onConfirm = { bookIds ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.addToShelf(shelfId, bookIds)
                addBooksToShelf = null
            },
            onDismiss = { addBooksToShelf = null },
        )
    }

    pendingRemoval?.let { removal ->
        val onlyBook = removal.bookIds.singleOrNull()
            ?.takeIf { removal.isSingleBook }
            ?.let { id -> books.firstOrNull { it.id == id } }
        ConfirmRemoveSheet(
            title = if (onlyBook != null) {
                stringResource(R.string.library_delete_title)
            } else {
                stringResource(R.string.library_selection_delete_title)
            },
            // One book gets named. A pile of them gets counted.
            message = onlyBook
                ?.let { stringResource(R.string.library_delete_message, it.title) }
                ?: removal.describe(),
            onConfirm = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.deleteBooks(removal.bookIds)
                removal.shelfIds.forEach { viewModel.deleteShelf(it) }
                selection.clear()
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    // The duplicate dialog and the import preview are hosted by MainActivity,
    // not here: a book opened from another app can arrive while the reader is
    // on screen, and this composable would not be there to ask.
}

/**
 * Case-insensitive match over shelf names and, for books, whatever [searchRank]
 * looks at — title and author first, then series and description.
 *
 * Shelves and the books that answered best come first; the books found only by
 * their blurb follow, so a description match can never bury a title match.
 * Within each of those two groups the library's own order is kept.
 */
internal fun filterEntries(
    entries: List<LibraryEntry>,
    allBooks: List<Book>,
    query: String,
): List<LibraryEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries

    val shelves = entries.filterIsInstance<LibraryEntry.ShelfEntry>()
        .filter { it.shelf.name.contains(needle, ignoreCase = true) }
    val alreadyShown = shelves.flatMapTo(HashSet()) { shelf -> shelf.books.map { it.id } }
    // Search reaches INTO shelves: a book you can't find is worse than a book
    // shown outside its shelf for the duration of a query.
    val ranked = allBooks
        .filter { it.id !in alreadyShown }
        .mapNotNull { book -> searchRank(book, needle)?.let { book to it } }

    // A shelf name is as direct a hit as a title, so shelves sit with the top
    // group and are ordered against it by the usual timestamp.
    val byTime = compareByDescending<LibraryEntry> { it.sortTs }.thenBy { it.id }
    val direct = (shelves + ranked.filter { it.second <= 1 }.map { LibraryEntry.BookEntry(it.first) })
        .sortedWith(byTime)
    val indirect = ranked.filter { it.second > 1 }
        .sortedBy { it.second }
        .map { LibraryEntry.BookEntry(it.first) }

    return direct + indirect
}

// ----------------------------------------------------------------- header

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    heroBook: Book?,
    heroCover: java.io.File?,
    onOpenBook: (Book) -> Unit,
    onHeroDetails: () -> Unit,
    onHeroEdit: () -> Unit,
    onHeroDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom)))
            // NOT statusBarsPadding(). The reader hides the system bars, so on
            // the way back from a book this composes while the status bar is
            // still gone: the inset reads 0, the header sits that much too
            // high, and everything slides down as the bar animates back in.
            // The reading surface already sizes itself the same way.
            .padding(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues())
            .padding(bottom = 8.dp),
    ) {
        Row(
            // 16dp, the same inset the hero card below uses, so the search
            // field's left edge and the gear's right edge line up with the
            // card. The mock had the row at 12 and the card at 16.
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(frog.glass)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = frog.ink2,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        if (heroBook != null) {
            HeroCard(
                book = heroBook,
                coverFile = heroCover,
                onOpenBook = { onOpenBook(heroBook) },
                onDetails = onHeroDetails,
                onEdit = onHeroEdit,
                onDelete = onHeroDelete,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FrogSearchField(
        query = query,
        onQueryChange = onQueryChange,
        hint = stringResource(R.string.library_search_hint),
        modifier = modifier,
    )
}

// -------------------------------------------------------------- hero card

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(
    book: Book,
    coverFile: java.io.File?,
    onOpenBook: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroCover(
                book = book,
                coverFile = coverFile,
                onClick = onOpenBook,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = frog.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(8.dp).weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.3).sp,
                                    color = scheme.primary,
                                ),
                            ) { append("$percent%") }
                            append("  ")
                            withStyle(
                                SpanStyle(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp,
                                    color = scheme.onSurfaceVariant,
                                ),
                            ) { append(stringResource(R.string.library_read_label).uppercase()) }
                        },
                        // SpanStyle carries no line height — it belongs here.
                        lineHeight = 15.sp,
                        maxLines = 1,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = scheme.primary.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = formatReadingTime(book.readingSeconds).uppercase(),
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(frog.chip),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(scheme.primary),
                    )
                }

                Spacer(Modifier.height(7.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = chapterLine(book).uppercase(),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                        color = frog.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(11.dp))
                Row(
                    modifier = Modifier.height(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MorphingButton(
                        onClick = onOpenBook,
                        color = scheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        val label = if (book.lastOpenedAtMillis == null) {
                            stringResource(R.string.library_start_reading)
                        } else {
                            stringResource(R.string.library_continue_reading)
                        }
                        Text(
                            text = label.uppercase(),
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            color = scheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                    Box {
                        MorphingButton(
                            onClick = { menuOpen = true },
                            color = frog.chip,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.details_menu),
                                tint = frog.ink2,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        HeroMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            onDetails = { menuOpen = false; onDetails() },
                            onEdit = { menuOpen = false; onEdit() },
                            onDelete = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCover(
    book: Book,
    coverFile: java.io.File?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy,
    )
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spec, label = "heroCoverScale")
    val rotation by animateFloatAsState(if (pressed) -1.5f else 0f, spec, label = "heroCoverTilt")
    val corner by animateFloatAsState(if (pressed) 30f else 16f, spec, label = "heroCoverCorner")

    Box(
        modifier = Modifier
            .size(width = 98.dp, height = 147.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
                shadowElevation = 8.dp.toPx()
                shape = RoundedCornerShape(corner.dp)
                clip = true
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        BookCover(book = book, coverFile = coverFile, titleSize = 11.sp, padding = 10.dp)
    }
}

@Composable
private fun HeroMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(204.dp),
    ) {
        HeroMenuItem(
            icon = Icons.Rounded.Info,
            label = stringResource(R.string.library_menu_info),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDetails,
        )
        HeroMenuItem(
            icon = Icons.Rounded.Edit,
            label = stringResource(R.string.library_menu_edit),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurface,
            onClick = onEdit,
        )
        HeroMenuItem(
            icon = Icons.Rounded.Delete,
            label = stringResource(R.string.library_delete_confirm),
            tint = MaterialTheme.colorScheme.error,
            textColor = MaterialTheme.colorScheme.error,
            onClick = onDelete,
        )
    }
}

@Composable
private fun HeroMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label.uppercase(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = textColor,
            )
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp),
    )
}

// ------------------------------------------------------------ section row

@Composable
private fun SectionRow(
    viewMode: LibraryViewMode,
    onViewMode: (LibraryViewMode) -> Unit,
    selection: LibrarySelection?,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val selecting = selection != null

    // No top padding here: the caller adds exactly what the grid's own item
    // spacing does not already cover, so header → section stays at 16dp.
    //
    // Fixed height, because this row swaps the view-mode toggle for two chips
    // when a selection starts and they do not measure the same. Left to itself
    // the row changed height, and the whole grid below it stepped a few pixels
    // up the moment anything was ticked.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SectionRowHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selection != null) {
                stringResource(R.string.library_selected_count, selection.count).uppercase()
            } else {
                stringResource(R.string.library_all_books).uppercase()
            },
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = if (selecting) scheme.primary else scheme.onSurface,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selecting) {
                // The view-mode toggle steps aside: relaying out the whole grid
                // mid-selection is not something anyone reaches for, and the
                // two controls that matter now need the room.
                SelectionChip(
                    label = stringResource(R.string.library_select_all),
                    onClick = onSelectAll,
                )
                SelectionChip(
                    label = stringResource(R.string.library_clear_selection),
                    onClick = onClearSelection,
                )
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ViewModeButton(
                        selected = viewMode == LibraryViewMode.GRID,
                        selectedIcon = Icons.Rounded.GridView,
                        icon = Icons.Outlined.GridView,
                        label = stringResource(R.string.view_mode_grid),
                        onClick = { onViewMode(LibraryViewMode.GRID) },
                    )
                    ViewModeButton(
                        selected = viewMode == LibraryViewMode.LIST,
                        selectedIcon = Icons.Rounded.ViewAgenda,
                        icon = Icons.Outlined.ViewAgenda,
                        label = stringResource(R.string.view_mode_list),
                        onClick = { onViewMode(LibraryViewMode.LIST) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label.uppercase(),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * The two things a grid selection can do, floating above the navigation bar.
 *
 * A bar rather than more entries in the section row: these are commitments, and
 * they belong under the thumb rather than at the top of a list the user is
 * still scrolling through.
 */
@Composable
private fun SelectionActionBar(
    canAddToShelf: Boolean,
    onAddToShelf: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canAddToShelf) {
            MorphingButton(
                onClick = onAddToShelf,
                color = scheme.primary,
                modifier = Modifier.height(44.dp),
            ) {
                SelectionActionLabel(
                    icon = Icons.Rounded.LibraryAdd,
                    label = stringResource(R.string.library_menu_add_to_shelf),
                    tint = scheme.onPrimary,
                )
            }
        }
        MorphingButton(
            onClick = onDelete,
            color = scheme.errorContainer,
            modifier = Modifier.height(44.dp),
        ) {
            SelectionActionLabel(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.library_delete_confirm),
                tint = scheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SelectionActionLabel(icon: ImageVector, label: String, tint: Color) {
    Row(
        modifier = Modifier.padding(horizontal = 18.dp),
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
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun ViewModeButton(
    selected: Boolean,
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            .clickable(enabled = !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(17.dp),
        )
    }
}

/** How long a press has to be held before the item's menu appears. */
private const val MenuHoldMillis = 260L

/** Height of the "ALL BOOKS" row, held constant across selection mode. */
private val SectionRowHeight = 32.dp

/** One book's spine in a folder's list row, and how narrow it may go to fit. */
private val SpineWidth = 34.dp
private val MinSpineWidth = 26.dp
private val SpineGap = 6.dp

// -------------------------------------------------------------- grid tiles

@Composable
private fun BookGridTile(
    book: Book,
    coverFile: java.io.File?,
    pop: ShelfPopState,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A book that has just been added grows into its slot while the tiles
    // around it slide aside — the same arrival a new folder gets.
    val arrival by rememberEntryArrival(book.id, pop)
    val frog = LocalFrogColors.current
    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()

    Column(
        modifier = modifier.graphicsLayer {
            // Grows from ShelfPopFrom into place. A layer, so the arrival
            // costs a draw and not a re-measure of the row it lands in.
            scaleX = ShelfPopFrom + (1f - ShelfPopFrom) * arrival
            scaleY = ShelfPopFrom + (1f - ShelfPopFrom) * arrival
            alpha = arrival
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(onLongClick = onLongClick, onClick = onClick)
                .selectionOverlay(selected, RoundedCornerShape(18.dp)),
        ) {
            BookCover(book = book, coverFile = coverFile, titleSize = 10.sp, padding = 9.dp)
            if (percent > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(frog.pill)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "$percent%",
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (selecting) {
                SelectionCheck(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        TileTitle(text = book.title)
        book.author?.let { author ->
            Spacer(Modifier.height(2.dp))
            TileSubtitle(text = author)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfGridTile(
    entry: LibraryEntry.ShelfEntry,
    coverOf: (Book) -> java.io.File?,
    pop: ShelfPopState,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val extra = entry.books.size - 4
    val arrival = rememberEntryArrival(entry.shelf.id, pop)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                // The artwork pops, the label below it does not: an overshoot
                // spring on 12sp type is visibly jittery.
                .graphicsLayer {
                    scaleX = lerp(ShelfPopFrom, 1f, arrival.value)
                    scaleY = scaleX
                }
                .clip(RoundedCornerShape(22.dp))
                .background(frog.folder)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick)
                .selectionOverlay(selected, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { column ->
                            val slot = row * 2 + column
                            val book = entry.books.getOrNull(slot)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(2f / 3f)
                                    // Mini covers land one after another, so a
                                    // new folder reads as books dropping in
                                    // rather than a card appearing.
                                    .graphicsLayer {
                                        val t = ((arrival.value - slot * 0.07f) / 0.6f)
                                            .coerceIn(0f, 1f)
                                        alpha = t
                                        scaleX = lerp(0.7f, 1f, t)
                                        scaleY = scaleX
                                    }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        // Empty slots need to read as plates in
                                        // every theme; `pill60` is near-black in
                                        // Midnight and vanished into the shelf.
                                        if (book == null) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (book != null) {
                                    BookCover(
                                        book = book,
                                        coverFile = coverOf(book),
                                        titleSize = 0.sp,
                                        padding = 0.dp,
                                    )
                                }
                                // The count of what is NOT shown, over the last
                                // cover that is. Floating in the corner of the
                                // tile it looked like a badge on the folder;
                                // over the fourth cover it reads as "and this
                                // many more behind this one".
                                if (extra > 0 && slot == 3) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "+$extra",
                                            fontSize = 15.sp,
                                            lineHeight = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                SelectionCheck(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        TileTitle(text = shelfName(entry))
        Spacer(Modifier.height(2.dp))
        TileSubtitle(
            text = pluralStringResource(
                R.plurals.shelf_books_count,
                entry.books.size,
                entry.books.size,
            ),
        )
    }
}

/** The ring a ticked tile wears. Drawn, not laid out, so it costs no measure pass. */
@Composable
private fun Modifier.selectionOverlay(
    selected: Boolean,
    shape: RoundedCornerShape,
): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    return this.drawWithContent {
        drawContent()
        if (!selected) return@drawWithContent
        val cornerPx = shape.topStart.toPx(size, this)
        val radius = CornerRadius(cornerPx, cornerPx)
        drawRoundRect(color = primary.copy(alpha = 0.22f), cornerRadius = radius)
        val inset = 1.5.dp.toPx()
        drawRoundRect(
            color = primary,
            cornerRadius = radius,
            style = Stroke(3.dp.toPx()),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
        )
    }
}

// -------------------------------------------------------------- list rows

@Composable
private fun BookListRow(
    book: Book,
    coverFile: java.io.File?,
    pop: ShelfPopState,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrival by rememberEntryArrival(book.id, pop)
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()
    val fillWidth by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(400),
        label = "listProgressFill",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = ShelfPopFrom + (1f - ShelfPopFrom) * arrival
                scaleY = ShelfPopFrom + (1f - ShelfPopFrom) * arrival
                alpha = arrival
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .selectionOverlay(selected, RoundedCornerShape(20.dp)),
    ) {
        // Progress IS the row fill, not a separate bar.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fillWidth)
                .background(frog.folder),
        )

        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                BookCover(book = book, coverFile = coverFile, titleSize = 7.sp, padding = 6.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                TileTitle(text = book.title, fontSize = 13.5.sp)
                book.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    TileSubtitle(text = author, fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = listMetaLine(book).uppercase(),
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.7.sp,
                    color = frog.ink2.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (selecting) {
                SelectionCheck(selected = selected, modifier = Modifier.padding(end = 8.dp))
            } else if (percent > 0) {
                Text(
                    text = "$percent%",
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfListRow(
    entry: LibraryEntry.ShelfEntry,
    coverOf: (Book) -> java.io.File?,
    pop: ShelfPopState,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddBooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val arrival = rememberEntryArrival(entry.shelf.id, pop)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The whole card is the folder here, so all of it pops.
            .graphicsLayer {
                scaleX = lerp(ShelfPopFrom, 1f, arrival.value)
                scaleY = scaleX
            }
            .clip(RoundedCornerShape(20.dp))
            .background(frog.folder)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .selectionOverlay(selected, RoundedCornerShape(20.dp))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(
            // Fixed, so swapping the book count for a selection tick — which is
            // ten dp taller — cannot make the whole card grow.
            modifier = Modifier.height(SelectionCheckDiameter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = shelfName(entry),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = frog.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selecting) {
                SelectionCheck(selected = selected)
            } else {
                Text(
                    text = pluralStringResource(
                        R.plurals.shelf_books_count,
                        entry.books.size,
                        entry.books.size,
                    ).uppercase(),
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.7.sp,
                    color = frog.ink2.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            // The strip keeps its height whether or not there is anything in
            // it, so an empty folder is the same size as a full one.
            modifier = Modifier.height(SpineWidth * 3 / 2),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .size(SelectionCheckDiameter + 12.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .clickable(onClick = onAddBooks),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.shelf_add_books),
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                return@BoxWithConstraints
            }

            // Every book that can be shown at a readable size, rather than a
            // fixed six that stopped short of the right-hand edge. Spines
            // narrow a little to let one more in before any of them is dropped,
            // and the row is filled exactly when some have to be.
            val fit = ((maxWidth + SpineGap) / (MinSpineWidth + SpineGap))
                .toInt()
                .coerceAtLeast(1)
            val spines = entry.books.take(fit)
            val hidden = entry.books.size - spines.size
            val spineWidth = minOf(
                SpineWidth,
                (maxWidth - SpineGap * (spines.size - 1)) / spines.size,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpineGap),
            ) {
                spines.forEachIndexed { index, book ->
                    Box(
                        modifier = Modifier
                            .size(width = spineWidth, height = spineWidth * 3 / 2)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BookCover(
                            book = book,
                            coverFile = coverOf(book),
                            titleSize = 5.5.sp,
                            padding = 4.dp,
                            alignBottom = true,
                        )
                        // The count rides the last spine there is room for, the
                        // same way it rides the fourth cover of a grid tile.
                        if (hidden > 0 && index == spines.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+$hidden",
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ empty states

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.library_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

@Composable
private fun NoSearchResults(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.library_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ----------------------------------------------------------------- dialogs

@Composable
private fun EditBookDialog(
    book: Book,
    coverFile: java.io.File?,
    onDismiss: () -> Unit,
    onSave: (title: String, author: String?, coverUri: Uri?) -> Unit,
) {
    var title by rememberSaveable(book.id) { mutableStateOf(book.title) }
    var author by rememberSaveable(book.id) { mutableStateOf(book.author.orEmpty()) }
    var pickedCover by remember(book.id) { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pickedCover = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_book_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        val model: Any? = pickedCover ?: coverFile
                        if (model != null) {
                            AsyncImage(
                                model = model,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.AutoStories,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.edit_change_cover)) }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.edit_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.edit_field_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title.trim(), author.trim().ifBlank { null }, pickedCover) },
            ) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_delete_cancel))
            }
        },
    )
}

// ----------------------------------------------------------------- helpers

/**
 * Lets a full-bleed item (the header) escape the grid's horizontal
 * contentPadding, which the columns still need for their own math.
 */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = amount.roundToPx() * 2
    val widened = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = constraints.maxWidth + extra,
    )
    val placeable = measurable.measure(widened)
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-amount.roundToPx(), 0)
    }
}

@Composable
internal fun shelfName(entry: LibraryEntry.ShelfEntry): String =
    entry.shelf.name.ifBlank { stringResource(R.string.shelf_unnamed) }

@Composable
private fun chapterLine(book: Book): String {
    val left = book.progress.pagesLeftInChapter
    return if (left >= 0) {
        stringResource(R.string.library_pages_left_in_chapter, left)
    } else {
        val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
        stringResource(R.string.library_percent_left, ((1f - fraction) * 100).roundToInt())
    }
}

@Composable
private fun listMetaLine(book: Book): String {
    val status = if (book.progress.fraction > 0f) {
        stringResource(R.string.library_status_reading)
    } else {
        stringResource(R.string.library_status_not_started)
    }
    return "${book.format.name} · $status"
}

/** "45h 34m" — the shape the mock uses; minutes only under an hour. */
private fun formatReadingTime(seconds: Long): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
