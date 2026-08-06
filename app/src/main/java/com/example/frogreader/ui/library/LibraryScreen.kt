package com.example.frogreader.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.OpenWith
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.example.frogreader.R
import com.example.frogreader.data.LibraryViewMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.shelfOrderKey
import com.example.frogreader.ui.nav.sharedBookCover
import com.example.frogreader.ui.theme.LocalFrogColors
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

// Numbers below come straight from the 412x916dp design mock, 1:1.
private val GridGap = 14.dp
private val ListGap = 8.dp
private val SidePadding = 20.dp
private val BottomInset = 20.dp
private val GhostWidth = 84.dp
private val GhostHeight = 124.dp
private val AutoScrollZone = 72.dp

/** Scrim behind the shelf panel — rgba(16,44,26,.42) in the mock. */
private val ShelfScrim = Color(0xFF102C1A)

/** Long-press threshold for picking a book up, shortened from the 500ms default. */
private const val DragPickupMillis = 250L

/**
 * How long a released book takes to reach where it landed. Long enough to read
 * as travel, short enough that the repository's `library.json` write (a few
 * milliseconds, on IO) is over well before the ghost arrives.
 */
private const val GhostFlightMillis = 200

/** How small the ghost gets as it disappears into a shelf. */
private const val GhostLandScale = 0.34f

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
    var shelfId by mutableStateOf<String?>(null)
}

/** Scale a brand-new folder grows from. */
private const val ShelfPopFrom = 0.62f

/** Scale the open-folder panel grows out of its tile from. */
private const val PanelCollapsedScale = 0.78f

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
private fun rememberShelfArrival(shelfId: String, pop: ShelfPopState): State<Float> {
    val justCreated = pop.shelfId == shelfId
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val arrival = remember { Animatable(if (justCreated) 0f else 1f) }

    LaunchedEffect(justCreated) {
        if (justCreated) {
            // The tile can compose a frame before the new id reaches us — the
            // shelf flow and the callback are separate trips to the main
            // thread — so start from scratch rather than trust the initial.
            arrival.snapTo(0f)
            arrival.animateTo(1f, spec)
            pop.shelfId = null
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
    onOpenBook: (Book) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var bookToEdit by remember { mutableStateOf<Book?>(null) }
    var bookForDetails by remember { mutableStateOf<Book?>(null) }
    var openShelfId by rememberSaveable { mutableStateOf<String?>(null) }

    val searching = query.isNotBlank()

    // Without this, Back with a folder open leaves the library entirely.
    BackHandler(enabled = openShelfId != null) { openShelfId = null }

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
    val hasAnyShelf = remember(entries) { entries.any { it is LibraryEntry.ShelfEntry } }

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

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            when (message) {
                is LibraryMessage.Imported -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.library_imported, message.title),
                    )
                }

                LibraryMessage.ImportFailed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.library_import_failed),
                    )
                }

                LibraryMessage.ImportFailedDrm -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.library_import_failed_drm),
                    )
                }
            }
        }
    }

    val drag = remember { LibraryDragState() }
    val pop = remember { ShelfPopState() }
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

    // `detectDragGesturesAfterLongPress` has no timeout parameter — it reads
    // this from the ambient ViewConfiguration. Delegation keeps every other
    // member (touch slop, fling velocity…) at the platform value.
    val platformViewConfiguration = LocalViewConfiguration.current
    val quickLongPress = remember(platformViewConfiguration) {
        object : ViewConfiguration by platformViewConfiguration {
            override val longPressTimeoutMillis: Long = DragPickupMillis
        }
    }

    val onDrop: (DragDrop) -> Unit = { drop ->
        val draggedBookId = drop.draggedId.substringAfter(':')
        val target = drop.mergeTargetId
        // Every release flies the cover somewhere — into the thing that
        // swallowed it, or back into the slot it came from. The one exception
        // is a book carried out of an open folder: the panel fading back in
        // already accounts for where it went.
        val landsHome = target == null && !drop.outsideContainer
        if (target != null || landsHome) {
            drag.beginLanding(
                entryId = drop.draggedId,
                from = drop.releaseRoot,
                to = drop.targetCenter ?: drop.releaseRoot,
                liveTargetId = target ?: drop.draggedId.takeIf { landsHome },
                merged = target != null,
            )
        }
        when {
            target == null -> Unit

            target.startsWith("s:") -> {
                viewModel.addToShelf(target.substringAfter(':'), draggedBookId)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }

            else -> {
                // Deliberately no auto-open: dropping one icon on another in a
                // launcher makes a folder, it does not walk you into it. The
                // shelf lands unnamed in the target's slot; tapping it opens the
                // panel with the name field.
                viewModel.createShelf(
                    draggedBookId = draggedBookId,
                    targetBookId = target.substringAfter(':'),
                    // The shelf only becomes addressable once the write lands.
                    // Handing its id to the flight lets the ghost finish on the
                    // real folder rather than on the target's remembered slot,
                    // and tells that one tile to play its arrival.
                    onCreated = { shelfId ->
                        drag.retargetLanding(shelfOrderKey(shelfId))
                        pop.shelfId = shelfId
                    },
                )
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { drag.rootOrigin = it.positionInRoot() },
    ) {
        CompositionLocalProvider(
            LocalViewConfiguration provides quickLongPress,
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
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        drag.viewport = Rect(it.positionInRoot(), it.size.toSize())
                    },
                contentPadding = PaddingValues(
                    start = SidePadding,
                    end = SidePadding,
                    bottom = BottomInset,
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
                        onHeroDelete = { bookToDelete = heroBook },
                        // The header is full-bleed; undo the grid's side padding.
                        modifier = Modifier.bleedHorizontally(SidePadding),
                    )
                }

                if (books.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "library_section") {
                        SectionRow(
                            // `viewMode`, not `renderMode`: the segment lights
                            // up under the finger while the grid is still
                            // fading out behind it. That is most of what makes
                            // the toggle feel instant.
                            viewMode = viewMode,
                            showDragHint = renderMode == LibraryViewMode.GRID && !hasAnyShelf,
                            onViewMode = { mode ->
                                userChoseMode = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                viewModel.setViewMode(mode)
                            },
                            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                        )
                    }
                }

                if (books.isEmpty()) {
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
                            .dragSource(
                                id = entry.id,
                                drag = drag,
                                // Shelves are drop targets, not draggable items.
                                enabled = entry is LibraryEntry.BookEntry && !searching,
                                onDrop = onDrop,
                            )
                            // INSIDE dragSource, so its onGloballyPositioned sits
                            // outside this layer: bounds registered through a
                            // scaled layer would report shrunken hit regions.
                            .then(modeFade)

                        when (entry) {
                            is LibraryEntry.BookEntry -> if (renderMode == LibraryViewMode.GRID) {
                                BookGridTile(
                                    book = entry.book,
                                    coverFile = viewModel.coverFileFor(entry.book),
                                    drag = drag,
                                    entryId = entry.id,
                                    onClick = { onOpenBook(entry.book) },
                                    modifier = itemModifier.padding(top = 6.dp),
                                )
                            } else {
                                BookListRow(
                                    book = entry.book,
                                    coverFile = viewModel.coverFileFor(entry.book),
                                    drag = drag,
                                    entryId = entry.id,
                                    onClick = { onOpenBook(entry.book) },
                                    modifier = itemModifier,
                                )
                            }

                            is LibraryEntry.ShelfEntry -> if (renderMode == LibraryViewMode.GRID) {
                                ShelfGridTile(
                                    entry = entry,
                                    coverOf = viewModel::coverFileFor,
                                    drag = drag,
                                    pop = pop,
                                    onClick = { onOpenShelf(entry) },
                                    modifier = itemModifier.padding(top = 6.dp),
                                )
                            } else {
                                ShelfListRow(
                                    entry = entry,
                                    coverOf = viewModel::coverFileFor,
                                    drag = drag,
                                    pop = pop,
                                    onClick = { onOpenShelf(entry) },
                                    modifier = itemModifier,
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) { data -> Snackbar(data) }

        mountedShelf?.let { shelfEntry ->
            ShelfPanel(
                entry = shelfEntry,
                expanded = openShelfId == shelfEntry.shelf.id,
                coverOf = viewModel::coverFileFor,
                drag = drag,
                onRename = { newName -> viewModel.renameShelf(shelfEntry.shelf.id, newName) },
                onTakeOut = { bookId ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    viewModel.removeFromShelf(shelfEntry.shelf.id, bookId)
                    if (shelfEntry.books.size <= 2) openShelfId = null
                },
                onDismiss = { openShelfId = null },
                // Unmount only once it has finished folding away — and only if
                // nothing reopened it in the meantime.
                onClosed = { if (openShelfId == null) mountedShelf = null },
            )
        }

        // LAST in the Box: the carried cover has to float above the folder
        // panel, not disappear behind it the moment a book is lifted out.
        DragOverlay(
            drag = drag,
            gridState = gridState,
            books = books,
            coverOf = viewModel::coverFileFor,
        )

    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_message, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.deleteBook(book)
                        bookToDelete = null
                    },
                ) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(stringResource(R.string.library_delete_cancel))
                }
            },
        )
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

}

/** Case-insensitive substring match over titles, authors and shelf names. */
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
    val matches = allBooks
        .filter { book ->
            book.id !in alreadyShown && (
                book.title.contains(needle, ignoreCase = true) ||
                    book.author?.contains(needle, ignoreCase = true) == true
                )
        }
        .map { LibraryEntry.BookEntry(it) }

    return (shelves + matches).sortedWith(
        compareByDescending<LibraryEntry> { it.sortTs }.thenBy { it.id },
    )
}

// ----------------------------------------------------------------- header

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
            .statusBarsPadding()
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
    val frog = LocalFrogColors.current

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(frog.glass)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = frog.ink2,
            modifier = Modifier.size(19.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_search_hint),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = frog.ink2,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = frog.ink,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.library_search_clear),
                tint = frog.ink2,
                modifier = Modifier
                    .size(19.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
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
                        Text(
                            text = stringResource(R.string.library_continue_reading).uppercase(),
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
            .sharedBookCover(book.id)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        BookCover(book = book, coverFile = coverFile, titleSize = 11.sp, padding = 10.dp)
    }
}

/**
 * A button whose corner radius snaps tighter while pressed — the M3 Expressive
 * shape morph, done by hand so it also works on the plain Box we need here.
 */
@Composable
private fun MorphingButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        targetValue = if (pressed) 10f else 20f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "buttonCorner",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(color)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
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
    showDragHint: Boolean,
    onViewMode: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hintAlpha by animateFloatAsState(
        targetValue = if (showDragHint) 1f else 0f,
        animationSpec = tween(400),
        label = "dragHintAlpha",
    )

    // No top padding here: the caller adds exactly what the grid's own item
    // spacing does not already cover, so header → section stays at 16dp.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.library_all_books).uppercase(),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = scheme.onSurface,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hintAlpha > 0f) {
                Row(
                    modifier = Modifier.graphicsLayer { alpha = hintAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenWith,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.library_drag_hint).uppercase(),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.9.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

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

// -------------------------------------------------------------- grid tiles

@Composable
private fun BookGridTile(
    book: Book,
    coverFile: java.io.File?,
    drag: LibraryDragState,
    entryId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .sharedBookCover(book.id)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .dropTargetOverlay(drag, entryId, RoundedCornerShape(18.dp), isShelf = false),
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
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = book.title,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let { author ->
            Spacer(Modifier.height(1.dp))
            Text(
                text = author,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfGridTile(
    entry: LibraryEntry.ShelfEntry,
    coverOf: (Book) -> java.io.File?,
    drag: LibraryDragState,
    pop: ShelfPopState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val extra = entry.books.size - 4
    val arrival = rememberShelfArrival(entry.shelf.id, pop)

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
                .clickable(onClick = onClick)
                .dropTargetOverlay(drag, entry.id, RoundedCornerShape(22.dp), isShelf = true),
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
                            ) {
                                if (book != null) {
                                    BookCover(
                                        book = book,
                                        coverFile = coverOf(book),
                                        titleSize = 0.sp,
                                        padding = 0.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (extra > 0) {
                Text(
                    text = "+$extra",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = frog.ink,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = shelfName(entry),
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = stringResource(R.string.shelf_books_count, entry.books.size),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// -------------------------------------------------------------- list rows

@Composable
private fun BookListRow(
    book: Book,
    coverFile: java.io.File?,
    drag: LibraryDragState,
    entryId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .dropTargetOverlay(drag, entryId, RoundedCornerShape(20.dp), isShelf = false),
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
                    .sharedBookCover(book.id)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                BookCover(book = book, coverFile = coverFile, titleSize = 7.sp, padding = 6.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

            if (percent > 0) {
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
    drag: LibraryDragState,
    pop: ShelfPopState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val spines = entry.books.take(6)
    val extra = entry.books.size - spines.size
    val arrival = rememberShelfArrival(entry.shelf.id, pop)

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
            .clickable(onClick = onClick)
            .dropTargetOverlay(drag, entry.id, RoundedCornerShape(20.dp), isShelf = true)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(
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
            Text(
                text = stringResource(R.string.shelf_books_count, entry.books.size).uppercase(),
                fontSize = 8.5.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.7.sp,
                color = frog.ink2.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spines.forEach { book ->
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 51.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    BookCover(
                        book = book,
                        coverFile = coverOf(book),
                        titleSize = 5.5.sp,
                        padding = 4.dp,
                        alignBottom = true,
                    )
                }
            }
            if (extra > 0) {
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 51.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(frog.pill60),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$extra",
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = frog.ink2,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------- shelf panel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfPanel(
    entry: LibraryEntry.ShelfEntry,
    expanded: Boolean,
    coverOf: (Book) -> java.io.File?,
    drag: LibraryDragState,
    onRename: (String) -> Unit,
    onTakeOut: (String) -> Unit,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember(entry.shelf.id) { mutableStateOf(entry.shelf.name) }
    val currentName by rememberUpdatedState(name)
    val savedName by rememberUpdatedState(entry.shelf.name)
    val commitRename by rememberUpdatedState(onRename)
    val finish by rememberUpdatedState(onClosed)

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

    // Save on the way out, so a rename survives tapping the scrim or dragging
    // the last book out from under the panel.
    DisposableEffect(entry.shelf.id) {
        onDispose { if (currentName.trim() != savedName) commitRename(currentName) }
    }

    // Carrying a book beyond the panel edge fades the folder away so the grid
    // it is going back to is visible — the launcher "pull it out" gesture. The
    // panel stays COMPOSED: unmounting it would dispose the pointerInput node
    // that owns the gesture still in flight.
    val pullingOut = drag.dragShelfId == entry.shelf.id && drag.outsidePanel
    val panelAlpha by animateFloatAsState(
        targetValue = if (pullingOut) 0f else 1f,
        animationSpec = tween(180),
        label = "shelfPanelAlpha",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (pullingOut) 0.12f else 0.42f,
        animationSpec = tween(180),
        label = "shelfScrimAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawRect(ShelfScrim.copy(alpha = scrimAlpha * expansion.value))
                drawContent()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = expanded && !drag.isDragging,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(34.dp),
            color = scheme.surfaceContainerLowest,
            shadowElevation = 24.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
                // BEFORE the graphicsLayer, and it has to stay there: modifier
                // nodes to the left of a layer sit outside it, so positionInRoot
                // keeps reporting untransformed bounds and "did the book leave
                // the folder?" stays truthful while the panel is mid-scale.
                .onGloballyPositioned {
                    drag.panelBounds = Rect(it.positionInRoot(), it.size.toSize())
                }
                .graphicsLayer {
                    val progress = expansion.value
                    // Grow out of the folder's own tile. Both rects are plain
                    // fields read in the layout phase, and the lambda re-runs
                    // every frame because `progress` changed — so this resolves
                    // itself on the first frame the panel has any size at all.
                    val panel = drag.panelBounds
                    val tile = drag.bounds[entry.id]
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
                    // it stops growing. `panelAlpha` still wins outright — a
                    // book being pulled out has to see the grid underneath.
                    alpha = (progress * 2.2f).coerceAtMost(1f) * panelAlpha
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 24.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface,
                            ),
                            cursorBrush = SolidColor(scheme.primary),
                            decorationBox = { inner ->
                                if (name.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.shelf_name_hint),
                                        fontSize = 24.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        DashedRule(color = scheme.outlineVariant)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerHigh)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.library_delete_cancel),
                            tint = scheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.shelf_panel_hint, entry.books.size),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(18.dp))
                entry.books.chunked(3).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { book ->
                            key(book.id) {
                                ShelfPanelBook(
                                    book = book,
                                    coverFile = coverOf(book),
                                    drag = drag,
                                    modifier = Modifier
                                        .weight(1f)
                                        .dragSource(
                                            id = panelKey(book.id),
                                            drag = drag,
                                            enabled = true,
                                            fromShelfId = entry.shelf.id,
                                            onDrop = { drop ->
                                                if (drop.outsideContainer) {
                                                    onTakeOut(book.id)
                                                } else {
                                                    // Let go inside the folder:
                                                    // settle back into the slot
                                                    // instead of blinking out.
                                                    drag.beginLanding(
                                                        entryId = drop.draggedId,
                                                        from = drop.releaseRoot,
                                                        to = drop.targetCenter ?: drop.releaseRoot,
                                                        liveTargetId = drop.draggedId,
                                                        merged = false,
                                                    )
                                                }
                                            },
                                        ),
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Books inside an open shelf get their own key space. */
private fun panelKey(bookId: String): String = "p:$bookId"

@Composable
private fun ShelfPanelBook(
    book: Book,
    coverFile: java.io.File?,
    drag: LibraryDragState,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val fraction = book.progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    val percent = (fraction * 100).roundToInt()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                // Veil only — a book inside a folder is never a drop target,
                // it leaves by being carried past the folder's edge.
                .drawWithContent {
                    drawContent()
                    if (drag.draggingId == panelKey(book.id)) {
                        drawRoundRect(
                            color = frog.lift,
                            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        )
                    }
                },
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = book.title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashedRule(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawWithContent {
                var x = 0f
                val dash = 4.dp.toPx()
                val gap = 3.dp.toPx()
                while (x < size.width) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(dash.coerceAtMost(size.width - x), size.height),
                    )
                    x += dash + gap
                }
            },
    )
}

// -------------------------------------------------------------------- drag

/**
 * Makes a tile draggable and registers its bounds for hit-testing.
 *
 * The gesture detector must be the INNERMOST pointer handler on the tile:
 * Compose delivers the Main pass from the inside out, so the tile is offered
 * every move before the LazyGrid's `scrollable` node. Before the long press
 * fires it consumes nothing (a swipe still scrolls); after it fires it consumes
 * everything (the grid stays put).
 */
@Composable
private fun Modifier.dragSource(
    id: String,
    drag: LibraryDragState,
    enabled: Boolean,
    fromShelfId: String? = null,
    onDrop: (DragDrop) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentOnDrop by rememberUpdatedState(onDrop)

    // onGloballyPositioned has no removal callback, so prune here instead.
    DisposableEffect(id) {
        onDispose { if (drag.draggingId != id) drag.bounds.remove(id) }
    }

    // positionInRoot() is UNCLIPPED, unlike every boundsIn* accessor — a tile
    // half-scrolled past the top of the grid still reports its true rect.
    return this
        .onGloballyPositioned { coordinates ->
            drag.bounds[id] = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        }
        .then(
            if (!enabled) {
                Modifier
            } else {
                // Keyed on the stable id only: keying on the entry would restart
                // the detector whenever progress changes and kill a live drag.
                Modifier.pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            val origin = drag.bounds[id]?.topLeft ?: return@detectDragGesturesAfterLongPress
                            drag.start(id, origin + local, fromShelfId)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, _ ->
                            // Recompute from the tile's CURRENT origin rather than
                            // accumulating deltas: while auto-scrolling, the tile
                            // slides under a stationary finger and accumulation drifts.
                            val origin = drag.bounds[id]?.topLeft
                            drag.fingerRoot = if (origin != null) {
                                origin + change.position
                            } else {
                                drag.fingerRoot + change.positionChange()
                            }
                            // No updateHover() here on purpose: the frame
                            // loop drives it at a steady rate, so the dwell
                            // advances even when the finger stops sending
                            // events, and speed never skews the geometry.
                        },
                        onDragEnd = {
                            val drop = drag.currentDrop()
                            drag.reset()
                            drop?.let(currentOnDrop)
                        },
                        onDragCancel = { drag.reset() },
                    )
                }
            },
        )
}

/** Veil on the lifted tile, ring + icon on the tile under the finger. */
@Composable
private fun Modifier.dropTargetOverlay(
    drag: LibraryDragState,
    id: String,
    shape: RoundedCornerShape,
    isShelf: Boolean,
): Modifier {
    val frog = LocalFrogColors.current
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val painter = rememberVectorPainter(
        if (isShelf) Icons.Rounded.LibraryAdd else Icons.Rounded.CreateNewFolder,
    )
    val iconTint = remember(surface) { ColorFilter.tint(surface) }

    // Every read of the drag state below happens inside a DRAW lambda, so
    // picking a tile up or moving over it repaints without recomposing.
    return this.drawWithContent {
        drawContent()
        val cornerPx = shape.topStart.toPx(size, this)
        val radius = CornerRadius(cornerPx, cornerPx)

        if (drag.draggingId == id) {
            drawRoundRect(color = frog.lift, cornerRadius = radius)
            val inset = 1.dp.toPx()
            drawRoundRect(
                color = outline,
                cornerRadius = radius,
                style = Stroke(2.dp.toPx()),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
            )
        }

        if (drag.mergeTargetId != id) return@drawWithContent

        drawRoundRect(
            color = primary.copy(alpha = if (isShelf) 0.30f else 0.24f),
            cornerRadius = radius,
        )
        val inset = 1.5.dp.toPx()
        drawRoundRect(
            color = primary,
            cornerRadius = radius,
            style = Stroke(3.dp.toPx()),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
        )
        val iconSize = if (isShelf) 28.dp.toPx() else 26.dp.toPx()
        translate(
            left = (size.width - iconSize) / 2f,
            top = (size.height - iconSize) / 2f,
        ) {
            with(painter) {
                draw(size = Size(iconSize, iconSize), colorFilter = iconTint)
            }
        }
    }
}

/**
 * Everything that reacts to a live drag: the per-frame hover/auto-scroll loop
 * and the floating ghost.
 *
 * Its own composable purely so that `drag.isDragging` is read HERE. Read from
 * `LibraryScreen`'s body, picking a book up invalidated the whole screen and
 * rebuilt the header with its hero card and cover — at the exact moment the
 * frame budget is being spent on the gesture.
 */
@Composable
private fun DragOverlay(
    drag: LibraryDragState,
    gridState: LazyGridState,
    books: List<Book>,
    coverOf: (Book) -> java.io.File?,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val isDragging = drag.isDragging

    // Auto-scroll the grid while a book is held near an edge. Bound to the
    // composition, so it cannot outlive the screen.
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val zonePx = with(density) { AutoScrollZone.toPx() }
        var lastFrame = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val deltaSeconds = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            // Per FRAME, not per pointer event: the dwell that turns a hover
            // into a shelf has to advance while the finger is perfectly still,
            // and a still finger sends nothing.
            if (drag.updateHover()) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            val velocity = drag.autoScrollVelocity(zonePx, maxVelocity = 1200f)
            if (velocity != 0f && deltaSeconds > 0f) {
                gridState.scrollBy(velocity * deltaSeconds)
            }
        }
    }

    DragGhost(drag = drag, books = books, coverOf = coverOf)
}

/**
 * The carried cover — under the finger while the gesture is live, then flying
 * to wherever the book ended up.
 *
 * One composable for both, deliberately: splitting them would dispose the
 * cover's image node at exactly the handover and reintroduce the blank frame
 * the flight exists to remove.
 */
@Composable
private fun DragGhost(
    drag: LibraryDragState,
    books: List<Book>,
    coverOf: (Book) -> java.io.File?,
) {
    val landing = drag.landing
    val carriedId = drag.draggingId ?: landing?.entryId ?: return
    val carriedBook = remember(carriedId, books) {
        val id = carriedId.substringAfter(':')
        books.firstOrNull { it.id == id }
    } ?: return

    // A fresh animator per flight; the cover subtree below is untouched.
    val flight = remember(landing) { Animatable(0f) }
    LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        flight.animateTo(1f, tween(GhostFlightMillis, easing = FastOutSlowInEasing))
        drag.endLanding()
    }

    Box(
        modifier = Modifier
            // Placement phase: reads the finger and the flight without
            // recomposing anything.
            .offset {
                val centre = if (landing == null) {
                    drag.fingerRoot
                } else {
                    // Re-read every frame so the ghost tracks a destination
                    // that is still settling into place under it.
                    val target = landing.liveTargetId?.let { drag.bounds[it]?.center } ?: landing.to
                    lerp(landing.from, target, flight.value)
                }
                val local = centre - drag.rootOrigin
                IntOffset(
                    (local.x - GhostWidth.toPx() / 2f).roundToInt(),
                    (local.y - GhostHeight.toPx() / 2f).roundToInt(),
                )
            }
            .size(GhostWidth, GhostHeight)
            .graphicsLayer {
                val t = flight.value
                val endScale = if (landing?.merged == true) GhostLandScale else 1f
                rotationZ = -4f * (1f - t)
                scaleX = lerp(1.04f, endScale, t)
                scaleY = scaleX
                // Squared, so it stays solid for most of the trip and only
                // gives way at the end — otherwise it reads as a fade, not
                // as the book being put somewhere.
                alpha = 1f - t * t
                shadowElevation = 18.dp.toPx() * (1f - t)
                shape = RoundedCornerShape(20.dp)
                clip = true
            },
    ) {
        BookCover(book = carriedBook, coverFile = coverOf(carriedBook), titleSize = 10.sp, padding = 9.dp)
    }
}


// ------------------------------------------------------------------ covers

/**
 * A cover image, or a deterministic gradient plate with the title on it. The
 * hue comes from the title, so the same book always gets the same plate.
 */
@Composable
private fun BookCover(
    book: Book,
    coverFile: java.io.File?,
    titleSize: TextUnit,
    padding: Dp,
    alignBottom: Boolean = false,
) {
    if (coverFile != null) {
        val platform = LocalPlatformContext.current
        // Cover nodes are thrown away and rebuilt constantly — on every scroll
        // and on every grid/list swap. Pinning the memory-cache key to the file
        // path (a new cover always gets a new name) lets the rebuilt node paint
        // the cached bitmap on its FIRST frame; without it Coil treats each new
        // node as a fresh load and the tile flashes empty.
        val request = remember(platform, coverFile) {
            ImageRequest.Builder(platform)
                .data(coverFile)
                .memoryCacheKey(coverFile.path)
                .placeholderMemoryCacheKey(coverFile.path)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.library_book_cover, book.title),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        FallbackCover(
            book = book,
            titleSize = titleSize,
            padding = padding,
            alignBottom = alignBottom,
        )
    }
}

@Composable
private fun FallbackCover(
    book: Book,
    titleSize: TextUnit,
    padding: Dp,
    alignBottom: Boolean = false,
) {
    val (top, bottom) = remember(book.title) { plateColors(book.title) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = if (alignBottom) Alignment.BottomStart else Alignment.Center,
    ) {
        if (titleSize.value > 0f) {
            Text(
                text = book.title.uppercase(),
                fontSize = titleSize,
                lineHeight = 1.3.em,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = if (alignBottom) TextAlign.Start else TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Deterministic dark→light plate, hue derived from the title. */
private fun plateColors(title: String): Pair<Color, Color> {
    var hash = 0
    for (character in title) hash = hash * 31 + character.code
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), 0.42f, 0.27f) to
        Color.hsl(((hue + 22) % 360).toFloat(), 0.34f, 0.47f)
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
private fun shelfName(entry: LibraryEntry.ShelfEntry): String =
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
